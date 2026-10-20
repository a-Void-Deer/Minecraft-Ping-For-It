package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

/** Two-phase root transaction. Plans are ephemeral; only owned canonical/display data escape. */
final class InventoryItemFreezer {
	private static final Class<?> SIMPLE_MAP = DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 1).build().getClass();
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
			Tag effective = plan.effective(nbtContext);
			canonical = InventoryItemCodec.canonicalTag(effective, budget);
		} catch (InventoryItemBudget.RootLimitException | InventoryItemCodec.IdentityUnavailableException rejected) {
			return aggregate(itemId, label);
		}
		// Identity is final. Neither display admission nor retained-result failure
		// is inside the identity-refusal catch; capture exhaustion always escapes.
		String json;
		budget.beginDisplay();
		try {
			budget.allocate(0, 0, 2, 1); // RegistryOps.withParent JSON
			json = InventoryDisplayWriter.project(plan.saved(nbtContext.withParent(JsonOps.INSTANCE), true, false), itemId, displayBytes, budget);
		} catch (InventoryItemBudget.RootLimitException displayRefusal) {
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
		String json = display.displayJson();
		long retained = retained(0, itemId, label, json);
		budget.retain(retained);
		return new InventoryDetachedItem(itemId, InventoryIdentityMode.ALL_VARIANTS, display, null, retained);
	}

	private Plan plan(InventoryResidentItem input, int depth, boolean nestedCount) {
		budget.node(depth); budget.work(32); bounds.enter(input instanceof InventoryResidentItem.Stack stack ? stack.resident() : input);
		try {
			Item item; DataComponentMap prototype; Map<DataComponentType<?>, Optional<?>> patch; int count = 1;
			if (input instanceof InventoryResidentItem.Stack stack) {
				if (stack.resident().isEmpty()) throw new InventoryItemBudget.InvalidInputException("an empty stack carries no item identity");
				item = stack.resident().getItem(); count = nestedCount ? stack.resident().getCount() : 1;
				DataComponentMap components = stack.resident().getComponents();
				InventoryComponentCertificate.exact(components, PatchedDataComponentMap.class);
				prototype = raw.prototype((PatchedDataComponentMap) components); patch = raw.patch((PatchedDataComponentMap) components);
			} else {
				var variant = (InventoryResidentItem.Patch) input; item = variant.item(); prototype = raw.itemComponents(item); patch = raw.patch(variant.residentPatch());
			}
			if (nestedCount && (count < 1 || count > 99)) InventoryComponentCertificate.refuse();
			String itemId = id(item);
			Map<DataComponentType<?>, Object> defaults;
			if (prototype == DataComponentMap.EMPTY) defaults = Map.of();
			else {
				if (prototype.getClass() != SIMPLE_MAP) InventoryComponentCertificate.refuse();
				defaults = raw.simple(prototype);
			}
			int defaultSize = bounds.mapPass(defaults); int patchSize = bounds.mapPass(patch);
			// Four ArrayList shells/backings, Plan, <=n Entry and <=n Value
			// descriptors (Value has8 reference fields and two int fields).
			long n = defaultSize + patchSize;
			budget.allocate(36 + 8 * n, 0, 9L + 11L * defaultSize + 14L * patchSize, 9 + 2 * n);
			// Exact capacities effective[d+p], additions[p], removals[p],
			// detached patch entries[p]. There are no growth/trim arrays.
			budget.work(64 + 24L * (defaultSize + patchSize));
			var effective = new ArrayList<Entry>(defaultSize + patchSize);
			var additions = new ArrayList<Entry>(patchSize);
			var removals = new ArrayList<String>(patchSize);
			var patchEntries = new ArrayList<Map.Entry<DataComponentType<?>, Optional<?>>>(patchSize);
			if (patchSize != 0) for (var entry : patch.entrySet()) {
				budget.work(24);
				if (entry.getValue() == null) InventoryComponentCertificate.refuse();
				// Detach map entry wrapper itself; resident keys/values remain local.
				budget.allocate(0, 0, 2, 1);
				patchEntries.add(new java.util.AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue()));
			}
			if (defaultSize != 0) for (var entry : defaults.entrySet()) {
				budget.work(32);
				DataComponentType<?> type = entry.getKey();
				Optional<?> override = null;
				for (int changedIndex = 0; changedIndex < patchEntries.size(); changedIndex++) {
					var changed = patchEntries.get(changedIndex); budget.work(8); if (changed.getKey() == type) { override = changed.getValue(); break; }
				}
				if (override != null) continue; // Never inspect a replaced/removed default value or codec.
				String typeId = typeId(type);
				if (InventoryComponentCertificate.transientType(type)) continue;
				Codec<?> externalCodec = externalCodec(type);
				budget.work(256);
				if (!InventoryComponentCertificate.nativeType(type) && externalCodec == null) continue;
				effective.add(new Entry(typeId, value(type, entry.getValue(), depth + 1, externalCodec)));
			}
			for (int index = 0; index < patchEntries.size(); index++) { var entry = patchEntries.get(index);
				budget.work(32);
				DataComponentType<?> type = entry.getKey(); String typeId = typeId(type);
				if (InventoryComponentCertificate.transientType(type)) continue;
				Codec<?> externalCodec = externalCodec(type);
				budget.work(256);
				if (!InventoryComponentCertificate.nativeType(type) && externalCodec == null) continue;
				Optional<?> optional = entry.getValue(); if (optional == null) InventoryComponentCertificate.refuse();
				if (optional.isEmpty()) removals.add(typeId);
				else { var admitted = new Entry(typeId, value(type, optional.orElseThrow(), depth + 1, externalCodec)); effective.add(admitted); additions.add(admitted); }
			}
			return new Plan(itemId, count, effective, additions, removals, certificates);
		} finally { bounds.leave(); }
	}
	static boolean simpleMap(DataComponentMap map) { return map != null && map.getClass() == SIMPLE_MAP; }
	static void initialize() {
		// Fixed class identities are process initialization, never input work.
		InventoryComponentBounds.initialize(); InventoryRegistryContext.initialize();
	}

	private Codec<?> externalCodec(DataComponentType<?> type) {
		budget.work(256); // closed native dispatch, not external getter internals
		if (InventoryComponentCertificate.nativeType(type)) return null;
		// Registration was established before this external-trusted descriptor
		// getter. Preserve its real native cache wrapper; do not unwrap it.
		budget.work(64);
		Codec<?> codec = type.codec();
		if (codec != null) certificates.externalComponent();
		return codec;
	}

	private Value value(DataComponentType<?> type, Object value, int depth, Codec<?> externalCodec) {
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
			Plan conversion = null;
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
				MobEffectInstance resident = nativeAccess.foodEffect(effect); if (resident == null) InventoryComponentCertificate.refuse();
				certificates.effectCopy(resident, depth + 1); admitted.add(new FoodEffect(resident, effect.probability()));
			}
			return new Value(type, food, null, null, conversion, admitted, certificates, firstEffect, certificates.effectCopyCount(), null);
		}
		Codec<?> codec = InventoryComponentCertificate.codec(type); if (codec == null) InventoryComponentCertificate.refuse();
		Object owned = certificates.owned(type, value, depth);
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
	private static long retained(int canonical, String id, String label, String json) {
		return InventoryItemBudget.add(canonical, 512L + 2L * (id.length() + label.length() + (json == null ? 0 : json.length())));
	}

	private record Entry(String id, Value value) {}
	private record Slot(int slot, Plan item) {}
	private record FoodEffect(MobEffectInstance resident, float probability) {}

	private record Plan(String id, int count, List<Entry> effective, List<Entry> additions,
		List<String> removals, InventoryComponentCertificate certificate) {
		<T> T effective(DynamicOps<T> ops) {
			certificate.beginMap(false);
			for (int index = 0; index < effective.size(); index++) certificate.mapKey(effective.get(index).id);
			certificate.endMap();
			return ops.createMap(effective.stream().map(entry -> Pair.of(ops.createString(entry.id), entry.value.encode(ops, false))));
		}
		<T> T saved(DynamicOps<T> ops, boolean display, boolean countIncluded) {
			// Producer ArrayLists/Pairs/streams and both native owned map outputs;
			// max metadata key width is the admitted component resource name+!.
			// Removal concatenations are separately admitted before construction.
			certificate.admitRemovalNames(removals, display);
			List<Entry> selected = display ? effective : additions;
			if (display) for (int index = 0; index < selected.size(); index++) { Entry entry = selected.get(index);
				certificate.planVisit();
				if (entry.value.external()) certificate.admitRemovalName(entry.id, true);
			}
			// reset by admitRemovalNames: restart the allocation-free key schedule
			certificate.beginMap(display);
			for (int index = 0; index < selected.size(); index++) { Entry entry = selected.get(index);
				if (display && (entry.id.equals("minecraft:lore") || (entry.id.equals("minecraft:container") && InventoryDisplayWriter.shulker(id)))) continue;
				certificate.mapKey(display && entry.value.external() ? "!" + entry.id : entry.id);
			}
			for (int index = 0; index < removals.size(); index++) { String removed = removals.get(index);
				if (!display || (!removed.equals("minecraft:lore")
					&& !(removed.equals("minecraft:container") && InventoryDisplayWriter.shulker(id)))) certificate.mapKey("!" + removed);
			}
			if (display) { certificate.mapKey("!minecraft:lore"); if (InventoryDisplayWriter.shulker(id)) certificate.mapKey("!minecraft:container"); }
			certificate.endMap();
			certificate.admitMap(countIncluded ? 3 : 2, 17, 10, display);
			certificate.admitScalars(countIncluded ? 1 : 0, 0, 0, 1, display);
			int suppressed = 0;
			if (display) for (int index = 0; index < selected.size(); index++) {
				certificate.planVisit(); if (selected.get(index).value.external()) suppressed++;
			}
			certificate.admitEmptyMaps(removals.size() + suppressed + (display ? 2 : 0), display);
			var fields = new ArrayList<Pair<T, T>>(); fields.add(Pair.of(ops.createString("id"), ops.createString(id)));
			if (countIncluded) fields.add(Pair.of(ops.createString("count"), ops.createInt(count)));
			var patch = new ArrayList<Pair<T, T>>();
			// Display sends admitted effective values so omitted prototype values
			// cannot reconstruct unredacted nested Lore/shulker contents. Identity
			// saved items below still use the exact resident patch grammar.
			for (int index = 0; index < selected.size(); index++) { Entry entry = selected.get(index);
				if (display && (entry.id.equals("minecraft:lore") || (entry.id.equals("minecraft:container") && InventoryDisplayWriter.shulker(id)))) continue;
				if (display && entry.value.external()) patch.add(Pair.of(ops.createString("!" + entry.id), ops.emptyMap()));
				else patch.add(Pair.of(ops.createString(entry.id), entry.value.encode(ops, display)));
			}
			for (int index = 0; index < removals.size(); index++) { String removed = removals.get(index);
				if (!display || (!removed.equals("minecraft:lore")
					&& !(removed.equals("minecraft:container") && InventoryDisplayWriter.shulker(id))))
					patch.add(Pair.of(ops.createString("!" + removed), ops.emptyMap()));
			}
			if (display) {
				patch.add(Pair.of(ops.createString("!minecraft:lore"), ops.emptyMap()));
				if (InventoryDisplayWriter.shulker(id)) patch.add(Pair.of(ops.createString("!minecraft:container"), ops.emptyMap()));
			}
			if (!patch.isEmpty()) fields.add(Pair.of(ops.createString("components"), ops.createMap(patch.stream())));
			return ops.createMap(fields.stream());
		}
	}

	private record Value(DataComponentType<?> type, Object resident, Codec<?> codec,
		List<Slot> nested, Plan conversion, List<FoodEffect> effects, InventoryComponentCertificate certificate,
		int firstEffect, int afterEffect, InventoryComponentCertificate.NativePasses passes) {
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
			if (codec != null) return ((Codec<Object>) codec).encodeStart(ops, resident).result()
				.orElseThrow(() -> new InventoryItemBudget.RootLimitException("certified component does not encode"));
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
					T encoded = MobEffectInstance.CODEC.encodeStart(ops, new MobEffectInstance(effect.resident)).result()
						.orElseThrow(() -> new InventoryItemBudget.RootLimitException("certified food effect does not encode"));
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
