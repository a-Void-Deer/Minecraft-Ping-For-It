package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.*;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

/** Two-phase root transaction. Plans are ephemeral; only owned canonical/display data escape. */
@NotNullByDefault
final class InventoryItemFreezer {
	private final InventoryItemBudget budget;
	private final InventoryResidentAccess raw;
	private final InventoryItemNativeAccess nativeAccess;
	private final InventoryComponentBounds bounds;
	private final InventoryComponentCertificate certificates;
	private final HolderLookup.Provider registries;
	private final int displayBytes;

	InventoryItemFreezer(HolderLookup.Provider registries, int displayBytes, InventoryItemBudget budget,
		InventoryResidentAccess raw, InventoryItemNativeAccess nativeAccess) {
		this.registries = registries; this.displayBytes = displayBytes; this.budget = budget; this.raw = raw;
		this.nativeAccess = nativeAccess; bounds = new InventoryComponentBounds(budget, raw);
		certificates = new InventoryComponentCertificate(budget, raw, nativeAccess, bounds);
	}

	InventoryDetachedItem freeze(InventoryResidentItem input) {
		// Terminal reserve before optional identity can exhaust its phase:
		// char[327], String+compact/UTF16 attempts, Display(1B/3R),
		// DetachedItem(8B/4R). String scalar fields fit16B; no trace formatting.
		budget.allocate(25 + 3L * 327, 327, 8, 6); budget.work(8192);
		// One caught identity/display refusal may precede a capture-retention
		// refusal. Each Throwable has depth int/five refs, no backtrace/message
		// formatting; both allocation attempts are reserved, not just the last.
		budget.allocate(8, 0, 10, 2);
		// Catalog summary/strict OLD parse/hash/map processing is separately
		// admitted by InventoryCatalogAdmission, never charged to identity here.
		if (input instanceof InventoryResidentItem.Stack stack) InventoryComponentCertificate.exact(stack.resident(), ItemStack.class);
		Item item = input instanceof InventoryResidentItem.Stack stack ? stack.resident().getItem() : ((InventoryResidentItem.Patch) input).item();
		String itemId = id(item); String label = label(item, itemId);
		Plan plan; byte[] canonical; RegistryOps<Tag> nbtContext;
		try {
			InventoryRegistryContext context = InventoryRegistryContext.prepare(registries, raw, bounds, budget);
			certificates.context(context);
			plan = plan(input, 0, false);
			// Pure native roots retain the closed provider certificate. A mixed
			// root still demands it lazily for every native holder/registry path;
			// only external codecs use the real provider's native adapter.
			if (!certificates.hasExternalComponents()) context.requireAudited();
			// Native families are certified before their static codecs/copies.
			// External codecs are invoked only now, once for persistent identity.
			budget.allocate(0, 0, 2, 1);
			nbtContext = RegistryOps.create(NbtOps.INSTANCE, context);
			Tag patch = plan.patch(nbtContext, false);
			canonical = InventoryItemCodec.canonicalTag(patch, budget);
		} catch (InventoryItemBudget.RootLimitException | InventoryItemCodec.IdentityUnavailableException rejected) {
			InventoryItemDiagnostics.refusal("inventory/item-identity", itemId, rejected);
			return aggregate(itemId, label);
		}
		// Identity is final. Neither display admission nor retained-result failure
		// is inside the identity-refusal catch; capture exhaustion always escapes.
		@Nullable String json;
		budget.beginDisplay();
		try {
			budget.allocate(0, 0, 2, 1); // RegistryOps.withParent JSON
			json = InventoryDisplayWriter.project(plan.saved(nbtContext.withParent(JsonOps.INSTANCE), true, false), itemId, displayBytes, budget);
		} catch (InventoryItemBudget.RootLimitException displayRefusal) {
			InventoryItemDiagnostics.refusal("inventory/item-display", itemId, displayRefusal);
			json = null;
		}
		var display = new InventoryItemCodec.Display(itemId, label, json, false);
		long retained = retained(canonical.length, itemId, label, json);
		budget.retain(retained);
		return new InventoryDetachedItem(itemId, InventoryIdentityMode.EXACT, display, canonical, retained);
	}

	private InventoryDetachedItem aggregate(String itemId, String label) {
		// Root-local rejection is not empty and must not create a truncated digest.
		// Fixed default suppression does not inspect any refused component value.
		var display = InventoryItemCodec.aggregateDisplay(itemId, label, displayBytes);
		@Nullable String json = display.displayJson();
		long retained = retained(0, itemId, label, json);
		budget.retain(retained);
		return new InventoryDetachedItem(itemId, InventoryIdentityMode.ALL_VARIANTS, display, null, retained);
	}

	private Plan plan(InventoryResidentItem input, int depth, boolean nestedCount) {
		budget.node(depth); budget.work(32); bounds.enter(input instanceof InventoryResidentItem.Stack stack ? stack.resident() : input);
		try {
			Item item; int count = 1;
			if (input instanceof InventoryResidentItem.Stack stack) {
				if (stack.resident().isEmpty()) throw new InventoryItemBudget.InvalidInputException("an empty stack carries no item identity");
				item = stack.resident().getItem(); count = nestedCount ? stack.resident().getCount() : 1;
			} else {
				item = ((InventoryResidentItem.Patch) input).item();
			}
			if (nestedCount && (count < 1 || count > 99)) InventoryComponentCertificate.refuse();
			String itemId = id(item);
			budget.allocate(8, 0, 4, 1);
			var collector = new PatchPlan(depth);
			boolean patchPresent = false;
			try {
				if (input instanceof InventoryResidentItem.Stack stack)
					patchPresent = InventoryPatches.forEach(stack.resident().getComponents(), raw, collector, collector) != 0;
				else patchPresent = InventoryPatches.forEach(((InventoryResidentItem.Patch) input).residentPatch(), raw, collector, collector) != 0;
			} catch (InventoryPatches.UnsupportedPatchException unsupported) { InventoryComponentCertificate.refuse(); }
			return new Plan(itemId, count, patchPresent, collector.additions(), collector.removals(), certificates);
		} finally { bounds.leave(); }
	}

	@NotNullByDefault
	private final class PatchPlan implements InventoryPatches.Admission, BiConsumer<DataComponentType<?>, Optional<?>> {
		private final int depth;
		private @Nullable ArrayList<Entry> additions;
		private @Nullable ArrayList<String> removals;
		private PatchPlan(int depth) { this.depth = depth; }
		@Override public void inspect() {
			budget.work(256 + InventoryExternalFields.READ_WORK);
			budget.allocate(4, 0, 5, 1); // Fixed unsupported-shape exception, no trace.
		}
		@Override public void traverse(int size, int capacity) {
			bounds.patchPass(size, capacity);
			// Two exact-capacity lists, Plan (including its one-byte raw-patch
			// presence flag) and at most one Entry/Value per cell.
			budget.allocate(21 + 8L * size, 0, 7L + 12L * size, 5L + 2L * size);
			additions = new ArrayList<>(size); removals = new ArrayList<>(size);
		}
		@Override public void accept(DataComponentType<?> type, Optional<?> optional) {
			budget.work(288);
			String typeId = typeId(type); // Registration precedes any external descriptor getter.
			if (InventoryComponentCertificate.transientType(type)) return;
			@Nullable Codec<?> externalCodec = externalCodec(type);
			if (!InventoryComponentCertificate.nativeType(type) && externalCodec == null) return;
			if (optional.isEmpty()) removals().add(typeId);
			else additions().add(new Entry(typeId, value(type, optional.orElseThrow(), depth + 1, externalCodec)));
		}
		private ArrayList<Entry> additions() { return java.util.Objects.requireNonNull(additions); }
		private ArrayList<String> removals() { return java.util.Objects.requireNonNull(removals); }
	}
	static void initialize() {
		// Fixed class identities are process initialization, never input work.
		InventoryComponentBounds.initialize(); InventoryRegistryContext.initialize();
	}

	private @Nullable Codec<?> externalCodec(DataComponentType<?> type) {
		budget.work(256); // closed native dispatch, not external getter internals
		if (InventoryComponentCertificate.nativeType(type)) return null;
		// Registration was established before this external-trusted descriptor
		// getter. Preserve its real native cache wrapper; do not unwrap it.
		budget.work(64);
		@Nullable Codec<?> codec = type.codec();
		if (codec != null) certificates.externalComponent();
		return codec;
	}

	private Value value(DataComponentType<?> type, Object value, int depth, @Nullable Codec<?> externalCodec) {
		budget.container(); budget.work(64);
		if (externalCodec != null) {
			budget.node(depth);
			return new Value(type, certificates.external(value, externalCodec, depth), null, null, null, null, certificates, 0, 0, null);
		}
		if (type == DataComponents.CONTAINER || type == DataComponents.BUNDLE_CONTENTS || type == DataComponents.CHARGED_PROJECTILES) {
			List<ItemStack> resident;
			if (type == DataComponents.CONTAINER) { InventoryComponentCertificate.exact(value, ItemContainerContents.class); resident = raw.container((ItemContainerContents) value); }
			else if (type == DataComponents.BUNDLE_CONTENTS) { InventoryComponentCertificate.exact(value, BundleContents.class); resident = raw.bundle((BundleContents) value); }
			else { InventoryComponentCertificate.exact(value, ChargedProjectiles.class); resident = raw.projectiles((ChargedProjectiles) value); }
			List<?> list = certificates.list(resident); budget.allocate(8 + 4L * list.size(), 0, 3L * list.size() + 1, 2L * list.size() + 2);
			var plans = new ArrayList<Slot>(list.size());
			for (int index = 0; index < list.size(); index++) {
				budget.work(32);
				Object object = list.get(index); InventoryComponentCertificate.exact(object, ItemStack.class); var stack = (ItemStack) object;
				if (stack.isEmpty()) { if (type != DataComponents.CONTAINER) InventoryComponentCertificate.refuse(); continue; }
				plans.add(new Slot(index, plan(new InventoryResidentItem.Stack(stack), depth + 1, true)));
			}
			return new Value(type, null, null, plans, null, null, certificates, 0, 0, null);
		}
		if (type == DataComponents.FOOD) {
			InventoryComponentCertificate.exact(value, FoodProperties.class); var food = (FoodProperties) value;
			if (food.nutrition() < 0 || !Float.isFinite(food.saturation()) || !Float.isFinite(food.eatSeconds()) || food.eatSeconds() <= 0) InventoryComponentCertificate.refuse();
			@Nullable Plan conversion = null;
			if (food.usingConvertsTo().isPresent()) {
				budget.allocate(0, 0, 1, 1);
				conversion = plan(new InventoryResidentItem.Stack(food.usingConvertsTo().orElseThrow()), depth + 1, false);
			}
			int firstEffect = certificates.effectCopyCount();
			var effects = certificates.list(food.effects()); budget.allocate(8 + 4L * effects.size(), 0, 2L * effects.size() + 1, effects.size() + 2L);
			var admitted = new ArrayList<FoodEffect>(effects.size());
			for (int index = 0; index < effects.size(); index++) { Object possible = effects.get(index);
				budget.work(48);
				InventoryComponentCertificate.exact(possible, FoodProperties.PossibleEffect.class); var effect = (FoodProperties.PossibleEffect) possible;
				if (!Float.isFinite(effect.probability()) || effect.probability() < 0 || effect.probability() > 1) InventoryComponentCertificate.refuse();
				@Nullable MobEffectInstance resident = nativeAccess.foodEffect(effect); if (resident == null) InventoryComponentCertificate.refuse();
				certificates.effectCopy(resident, depth + 1); admitted.add(new FoodEffect(resident, effect.probability()));
			}
			return new Value(type, food, null, null, conversion, admitted, certificates, firstEffect, certificates.effectCopyCount(), null);
		}
		@Nullable Codec<?> codec = InventoryComponentCertificate.codec(type); if (codec == null) InventoryComponentCertificate.refuse();
		@Nullable Object owned = certificates.owned(type, value, depth);
		if (owned != null) return new Value(type, owned, null, null, null, null, certificates, 0, 0, null);
		int firstEffect = certificates.effectCopyCount();
		certificates.beginNativePasses();
		certificates.value(type, value, depth);
		return new Value(type, value, codec, null, null, null, certificates, firstEffect, certificates.effectCopyCount(), certificates.nativePasses());
	}

	private String typeId(DataComponentType<?> type) {
		budget.work(128);
		// Identity lookup uses the audited registry's IdentityHashMap: no codec,
		// value, hashCode or formatting hook of an unregistered type is called.
		budget.work(InventoryRegistryContext.valueWork(BuiltInRegistries.DATA_COMPONENT_TYPE, raw));
		var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type); certificates.resource(key);
		int length = key.getNamespace().length() + key.getPath().length() + 1;
		budget.allocate(16 + 3L * length, 0, 1, 3); budget.work(32 + 16L * length); return key.toString();
	}
	private String id(Item item) {
		if (item == Items.AIR) throw new InventoryItemBudget.InvalidInputException("air carries no inventory identity");
		budget.work(InventoryRegistryContext.valueWork(BuiltInRegistries.ITEM, raw));
		var key = BuiltInRegistries.ITEM.getKey(item); if (key == null) throw new InventoryItemBudget.InvalidInputException("unregistered inventory item");
		int length = key.getNamespace().length() + key.getPath().length() + 1;
		if (length > 256) throw new InventoryItemBudget.InvalidInputException("inventory item id");
		budget.allocate(16 + 3L * length, 0, 1, 3); budget.work(32 + 16L * length); return key.toString();
	}
	private String label(Item item, String id) {
		// Two bounded replacement Strings and the concatenated label, including
		// compact attempts; reserve before any String transformation.
		budget.allocate(48 + 9L * (id.length() + 6), 0, 3, 9); budget.work(64 + 32L * (id.length() + 6));
		// Native vanilla description metadata only. An arbitrary mod item hook
		// is unnecessary for identity: use bounded registry translation fallback.
		boolean alias = item == Items.REDSTONE || item == Items.STRING || item == Items.WHEAT_SEEDS || item == Items.COCOA_BEANS
			|| item == Items.PUMPKIN_SEEDS || item == Items.MELON_SEEDS || item == Items.NETHER_WART
			|| item == Items.CARROT || item == Items.POTATO || item == Items.TORCHFLOWER_SEEDS || item == Items.PITCHER_POD
			|| item == Items.BEETROOT_SEEDS || item == Items.SWEET_BERRIES || item == Items.GLOW_BERRIES || item == Items.POWDER_SNOW_BUCKET;
		return (item instanceof net.minecraft.world.item.BlockItem && !alias ? "block." : "item.")
			+ id.replace(':', '.').replace('/', '.');
	}
	private static long retained(int canonical, String id, String label, @Nullable String json) {
		return InventoryItemBudget.add(canonical, 512L + 2L * (id.length() + label.length() + (json == null ? 0 : json.length())));
	}

	@NotNullByDefault private record Entry(String id, Value value) {}
	@NotNullByDefault private record Slot(int slot, Plan item) {}
	@NotNullByDefault private record FoodEffect(MobEffectInstance resident, float probability) {}

	@NotNullByDefault
	private record Plan(String id, int count, boolean patchPresent, @UnmodifiableView List<Entry> additions,
		@UnmodifiableView List<String> removals, InventoryComponentCertificate certificate) {
		<T> T patch(DynamicOps<T> ops, boolean display) {
			// Producer ArrayLists/Pairs/streams and both native owned map outputs;
			// max metadata key width is the admitted component resource name+!.
			// Removal concatenations are separately admitted before construction.
			certificate.admitRemovalNames(removals, display);
			@UnmodifiableView List<Entry> selected = additions;
			if (display) for (int index = 0; index < selected.size(); index++) { Entry entry = selected.get(index);
				certificate.planVisit();
				if (entry.value.external()) certificate.admitRemovalName(entry.id, true);
			}
			// reset by admitRemovalNames: restart the allocation-free key schedule
			certificate.beginMap(display);
			for (int index = 0; index < selected.size(); index++) { Entry entry = selected.get(index);
				if (display) certificate.planVisit();
				if (display && InventoryDisplayWriter.forcedSuppression(entry.id, id)) continue;
				certificate.mapKey(display && entry.value.external() ? "!" + entry.id : entry.id);
			}
			for (int index = 0; index < removals.size(); index++) { String removed = removals.get(index);
				if (display) certificate.planVisit();
				if (!display || !InventoryDisplayWriter.forcedSuppression(removed, id)) certificate.mapKey("!" + removed);
			}
			if (display) { certificate.mapKey("!minecraft:lore"); if (InventoryDisplayWriter.shulker(id)) certificate.mapKey("!minecraft:container"); }
			certificate.endMap();
			int suppressed = 0;
			if (display) for (int index = 0; index < selected.size(); index++) {
				Entry entry = selected.get(index); certificate.planVisit();
				if (InventoryDisplayWriter.forcedSuppression(entry.id, id)) continue;
				if (entry.value.external()) suppressed++;
			}
			certificate.admitEmptyMaps(removals.size() + suppressed + (display ? 2 : 0), display);
			var patch = new ArrayList<Pair<T, T>>();
			// Both identity and display read only the actual explicit patch.
			for (int index = 0; index < selected.size(); index++) { Entry entry = selected.get(index);
				if (display) certificate.planVisit();
				if (display && InventoryDisplayWriter.forcedSuppression(entry.id, id)) continue;
				if (display && entry.value.external()) patch.add(Pair.of(ops.createString("!" + entry.id), ops.emptyMap()));
				else patch.add(Pair.of(ops.createString(entry.id), entry.value.encode(ops, display)));
			}
			for (int index = 0; index < removals.size(); index++) { String removed = removals.get(index);
				if (display) certificate.planVisit();
				if (!display || !InventoryDisplayWriter.forcedSuppression(removed, id))
					patch.add(Pair.of(ops.createString("!" + removed), ops.emptyMap()));
			}
			if (display) {
				patch.add(Pair.of(ops.createString("!minecraft:lore"), ops.emptyMap()));
				if (InventoryDisplayWriter.shulker(id)) patch.add(Pair.of(ops.createString("!minecraft:container"), ops.emptyMap()));
			}
			return ops.createMap(patch.stream());
		}

		<T> T saved(DynamicOps<T> ops, boolean display, boolean countIncluded) {
			certificate.admitMap(countIncluded ? 3 : 2, 17, 10, display);
			certificate.admitScalars(countIncluded ? 1 : 0, 0, 0, 1, display);
			var fields = new ArrayList<Pair<T, T>>(); fields.add(Pair.of(ops.createString("id"), ops.createString(id)));
			if (countIncluded) fields.add(Pair.of(ops.createString("count"), ops.createInt(count)));
			// Native saved-item codecs test the raw patch before its codec filters
			// transient entries; a transient-only patch still emits components:{}.
			if (display || patchPresent) fields.add(Pair.of(ops.createString("components"), patch(ops, display)));
			return ops.createMap(fields.stream());
		}
	}

	@NotNullByDefault
	private record Value(DataComponentType<?> type, @Nullable Object resident, @Nullable Codec<?> codec,
		@Nullable @UnmodifiableView List<Slot> nested, @Nullable Plan conversion, @Nullable @UnmodifiableView List<FoodEffect> effects, InventoryComponentCertificate certificate,
		int firstEffect, int afterEffect, InventoryComponentCertificate.@Nullable NativePasses passes) {
		boolean external() { return resident instanceof InventoryExternalComponent; }
		@SuppressWarnings("unchecked")
		<T> T encode(DynamicOps<T> ops, boolean display) {
			if (resident instanceof InventoryExternalComponent external) {
				if (display) throw new IllegalStateException("external component is not display data");
				return (T) external.encode();
			}
			certificate.admitEffectCopies(firstEffect, afterEffect);
			if (passes != null) { certificate.admitPasses(passes); certificate.admitCostPass(passes); }
			if (resident instanceof InventoryComponentCertificate.Owned || codec != null) certificate.admitValue(type, resident, display);
			if (resident instanceof InventoryComponentCertificate.Owned owned) return owned.encode(ops);
			if (codec != null) return InventoryItemDiagnostics.complete(((Codec<Object>) codec).encodeStart(ops, resident), "certified component");
			if (type == DataComponents.FOOD) {
				var food = (FoodProperties) resident;
				certificate.admitMap(2 + (food.canAlwaysEat() ? 1 : 0) + (food.eatSeconds() == 1.6F ? 0 : 1)
					+ (conversion == null ? 0 : 1) + (effects.isEmpty() ? 0 : 1), 76, 17, display);
				certificate.admitScalars(1, 1 + (food.eatSeconds() == 1.6F ? 0 : 1), food.canAlwaysEat() ? 1 : 0, 0, display);
				var fields = new ArrayList<Pair<T, T>>();
				fields.add(Pair.of(ops.createString("nutrition"), ops.createInt(food.nutrition())));
				fields.add(Pair.of(ops.createString("saturation"), ops.createFloat(food.saturation())));
				if (food.canAlwaysEat()) fields.add(Pair.of(ops.createString("can_always_eat"), ops.createBoolean(true)));
				if (food.eatSeconds() != 1.6F) fields.add(Pair.of(ops.createString("eat_seconds"), ops.createFloat(food.eatSeconds())));
				if (conversion != null) fields.add(Pair.of(ops.createString("using_converts_to"), conversion.saved(ops, display, false)));
				if (!effects.isEmpty()) {
					certificate.admitList(effects.size(), display);
					fields.add(Pair.of(ops.createString("effects"), ops.createList(effects.stream().map(effect -> {
					certificate.admitMap(effect.probability == 1.0F ? 1 : 2, 17, 11, display);
					certificate.admitScalars(0, effect.probability == 1.0F ? 0 : 1, 0, 0, display);
					certificate.admitFoodEffect(effect.resident, display);
					var entry = new ArrayList<Pair<T, T>>();
					T encoded = InventoryItemDiagnostics.complete(MobEffectInstance.CODEC.encodeStart(ops, new MobEffectInstance(effect.resident)), "certified food effect");
					entry.add(Pair.of(ops.createString("effect"), encoded));
					if (effect.probability != 1.0F) entry.add(Pair.of(ops.createString("probability"), ops.createFloat(effect.probability)));
					return ops.createMap(entry.stream());
				})))); }
				return ops.createMap(fields.stream());
			}
			certificate.admitList(nested.size(), display);
			return ops.createList(nested.stream().map(slot -> {
				T item = slot.item.saved(ops, display, true);
				if (type != DataComponents.CONTAINER) return item;
				certificate.admitMap(2, 8, 4, display); certificate.admitScalars(1, 0, 0, 0, display);
				return ops.createMap(java.util.stream.Stream.of(Pair.of(ops.createString("slot"), ops.createInt(slot.slot)), Pair.of(ops.createString("item"), item)));
			}));
		}
	}

}
