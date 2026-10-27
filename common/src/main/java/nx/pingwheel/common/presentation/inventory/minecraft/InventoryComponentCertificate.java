package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import net.minecraft.advancements.critereon.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.LockCode;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.*;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;

/** Closed native resident encode certificates, separate from external-trusted codecs. */
final class InventoryComponentCertificate {
	private final InventoryItemBudget budget;
	private final InventoryResidentAccess raw;
	private final InventoryItemNativeAccess nativeAccess;
	private final InventoryComponentBounds bounds;
	private @Nullable EffectCost @Nullable [] effectCopies;
	private int effectCopyCount;
	private InventoryRegistryContext context;
	private long laterListVisits;
	private long laterCopyCells;
	private boolean external;
	private final InventoryEncodingCost encoding;
	private final InventoryNativeCost nativeCost;
	private static final Class<?> CURE_SET = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()).getClass();
	private static final Class<?> PROPERTY_DELEGATE = com.google.common.collect.LinkedHashMultimap.create().getClass();
	private static final Class<?> EMPTY_COLORS = it.unimi.dsi.fastutil.ints.IntList.of().getClass();
	private static final Class<?> SINGLE_COLORS = it.unimi.dsi.fastutil.ints.IntList.of(1).getClass();
	private static final Codec<List<ResourceLocation>> RECIPES = ResourceLocation.CODEC.listOf();
	static {
		// Materialize the recursive STATIC codec graph on a fixed literal before
		// admitted resident roots. No provider/resident callbacks participate.
		ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, Component.literal("")).result().orElseThrow();
		MobEffectInstance.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,
			new MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON, 1)).result().orElseThrow();
	}
	// emptyNamed overrides ONLY contents(); encoding reads the inherited raw
	// owner/key. Keep the actual native class identity, never an instanceof gate.
	private static final Class<?> EMPTY_NAMED = HolderSet.emptyNamed(BuiltInRegistries.BLOCK.asLookup(),
		net.minecraft.tags.TagKey.create(Registries.BLOCK, ResourceLocation.withDefaultNamespace("inventory_shape"))).getClass();
	static boolean nativeNamed(HolderSet<?> set) {
		return set != null && (set.getClass() == HolderSet.Named.class || set.getClass() == EMPTY_NAMED);
	}
	static void initialize() { /* process-lived static codec/native class identities */ }

	InventoryComponentCertificate(InventoryItemBudget budget, InventoryResidentAccess raw,
		InventoryItemNativeAccess nativeAccess, InventoryComponentBounds bounds) {
		this.budget = budget; this.raw = raw; this.nativeAccess = nativeAccess; this.bounds = bounds;
		budget.allocate(148, 0, 16, 3);
		encoding = new InventoryEncodingCost(budget);
		nativeCost = new InventoryNativeCost(budget, raw, nativeAccess, encoding);
	}

	void admitValue(DataComponentType<?> type, Object resident, boolean display) {
		budget.work(32); // cost expansion is an owned pass; codec passes remain separate
		// The arithmetic walker uses admitted list/owned ArrayList iterators.
		// Its own source-side iterators are distinct from E4's codec iterators.
		encoding.reset(display);
		if (resident instanceof Owned owned) ownedCost(owned);
		else nativeCost.value(type, resident);
		encoding.admit();
	}
	void admitMap(int count, long keyUnits, int longest, boolean display) {
		encoding.reset(display); encoding.ownedMap(count, keyUnits, longest); encoding.keys(count); encoding.admit();
	}
	void beginMap(boolean display) { encoding.reset(display); encoding.beginKeys(); }
	void mapKey(String key) { encoding.mapKey(key); }
	void endMap() { encoding.ownedKeyMap(); encoding.keys(encoding.keyCount()); encoding.admit(); }
	void admitList(int count, boolean display) {
		encoding.reset(display); encoding.ownedList(count, 0, false); encoding.admit();
	}
	void admitFoodEffect(MobEffectInstance effect, boolean display) {
		encoding.reset(display); nativeCost.effect(effect); encoding.result(); encoding.admit();
	}
	void admitScalars(int integers, int floats, int bools, int strings, boolean display) {
		encoding.reset(display);
		for (int index = 0; index < integers + floats; index++) encoding.number(4);
		for (int index = 0; index < bools; index++) encoding.bool();
		for (int index = 0; index < strings; index++) encoding.outputString();
		encoding.admit();
	}
	void admitEmptyMaps(int count, boolean display) {
		encoding.reset(display);
		for (int index = 0; index < count; index++) encoding.ownedMap(0, 0, 0);
		encoding.admit();
	}
	void admitRemovalNames(List<String> names, boolean display) {
		encoding.reset(display);
		// One schedule key and one actual output key; neither shares new backing.
		for (int index = 0; index < names.size(); index++) removalNameCost(names.get(index));
		encoding.admit();
	}
	void admitRemovalName(String name, boolean display) {
		encoding.reset(display); removalNameCost(name); encoding.admit();
	}
	private void removalNameCost(String name) {
		encoding.v(32 + 6L * (name.length() + 1), 0, 2, 6);
		encoding.w(24 + 6L * name.length());
	}
	void planVisit() { budget.work(32); }
	private void ownedCost(Owned owned) {
		// Owned descriptors use indexed ArrayLists. Their cost walk is distinct
		// from the producer streams and cannot borrow native collector work.
		budget.work(128);
		if (owned instanceof OwnedData data) nbtCost(data.value);
		else if (owned instanceof OwnedBees bees) {
			encoding.ownedList(bees.bees.size(), 0, false);
			for (int index = 0; index < bees.bees.size(); index++) { OwnedBee bee = bees.bees.get(index);
				ownedMap(bee.data.emptyCompound() ? 2 : 3, 45, 17);
				encoding.number(4); encoding.number(4); if (!bee.data.emptyCompound()) nbtCost(bee.data);
			}
		} else if (owned instanceof OwnedDecorations decorations) {
			encoding.beginKeys(); for (int index = 0; index < decorations.entries.size(); index++) encoding.mapKey(decorations.entries.get(index).key);
			ownedKeyMap();
			for (int index = 0; index < decorations.entries.size(); index++) nativeCost.decoration(decorations.entries.get(index).value);
		} else if (owned instanceof OwnedState state) {
			encoding.beginKeys(); for (int index = 0; index < state.fields.size(); index++) encoding.mapKey(state.fields.get(index).key);
			ownedKeyMap();
			for (int index = 0; index < state.fields.size(); index++) encoding.string(state.fields.get(index).value);
		} else if (owned instanceof OwnedEnchantments enchantments) {
			ownedMap(enchantments.tooltip ? 1 : 2, 22, 15);
			encoding.beginKeys(); for (int index = 0; index < enchantments.levels.size(); index++) encoding.mapKey(enchantments.levels.get(index).key);
			ownedKeyMap();
			for (int index = 0; index < enchantments.levels.size(); index++) encoding.number(4);
			if (!enchantments.tooltip) encoding.bool();
		} else if (owned instanceof OwnedAdventure adventure) {
			ownedMap(adventure.tooltip ? 1 : 2, 25, 15); encoding.ownedList(adventure.predicates.size(), 0, false);
			if (!adventure.tooltip) encoding.bool();
			for (int index = 0; index < adventure.predicates.size(); index++) { Predicate predicate = adventure.predicates.get(index);
				int n = (predicate.blocks == null ? 0 : 1) + (predicate.state == null ? 0 : 1) + (predicate.nbt == null ? 0 : 1);
				ownedMap(n, 14, 6);
				if (predicate.nbt != null) encoding.string(predicate.nbt);
				if (predicate.blocks != null) {
					BlockSet set = predicate.blocks;
					if (set.tag != null) encoding.string(set.tag);
					else if (set.values.size() == 1) encoding.string(set.values.getFirst());
					else { encoding.ownedList(set.values.size(), 0, false); for (int element = 0; element < set.values.size(); element++) encoding.string(set.values.get(element)); }
				}
				if (predicate.state != null) {
					encoding.beginKeys(); for (int element = 0; element < predicate.state.size(); element++) encoding.mapKey(predicate.state.get(element).name);
					ownedKeyMap();
					for (int element = 0; element < predicate.state.size(); element++) { Match match = predicate.state.get(element);
						if (match.exact != null) encoding.string(match.exact);
						else {
							int count = (match.minimum == null ? 0 : 1) + (match.maximum == null ? 0 : 1);
							ownedMap(count, 6, 3); if (match.minimum != null) encoding.string(match.minimum); if (match.maximum != null) encoding.string(match.maximum);
						}
					}
				}
			}
		}
	}
	private void ownedMap(int n, long units, int longest) { encoding.ownedMap(n, units, longest); encoding.keys(n); }
	private void ownedKeyMap() { int count = encoding.keyCount(); encoding.ownedKeyMap(); encoding.keys(count); }
	private void nbtCost(InventoryComponentBounds.NbtValue value) {
		budget.work(64); // owned shape dispatch/getters, independent of native output
		if (value.fields() != null) {
			encoding.beginKeys(); for (int index = 0; index < value.fields().size(); index++) encoding.mapKey(value.fields().get(index).key());
			ownedKeyMap();
			for (int index = 0; index < value.fields().size(); index++) nbtCost(value.fields().get(index).value());
		} else if (value.children() != null) {
			// Exact mixed collector descriptors are represented conservatively by
			// one possible transition/replay and all possible wrapper calls.
			int kind = 0; boolean mixed = false; int first = -1;
			budget.work(32L * value.children().size());
			for (int index = 0; index < value.children().size(); index++) { var child = value.children().get(index);
				int id = convertedId(child);
				if (first == -1) first = id; else if (first != id) mixed = true;
				if (child.fields() != null && child.fields().size() == 1 && child.fields().getFirst().key().isEmpty()) mixed = true;
			}
			if (first == Tag.TAG_INT) kind = 1; else if (first == Tag.TAG_BYTE) kind = 2; else if (first == Tag.TAG_LONG) kind = 3;
			encoding.ownedList(value.children().size(), kind, mixed);
			for (int index = 0; index < value.children().size(); index++) nbtCost(value.children().get(index));
		} else if (value.scalar() instanceof net.minecraft.nbt.StringTag string) encoding.string(string.getAsString());
		else if (value.scalar() instanceof net.minecraft.nbt.ByteArrayTag array) arrayCost(array.getAsByteArray().length, 1);
		else if (value.scalar() instanceof net.minecraft.nbt.IntArrayTag array) arrayCost(array.getAsIntArray().length, 4);
		else if (value.scalar() instanceof net.minecraft.nbt.LongArrayTag array) arrayCost(array.getAsLongArray().length, 8);
		else encoding.number(value.scalar().getId() == Tag.TAG_LONG || value.scalar().getId() == Tag.TAG_DOUBLE ? 8 : 4);
	}
	private int convertedId(InventoryComponentBounds.NbtValue value) {
		budget.work(32);
		if (value.scalar() != null) return value.scalar().getId();
		if (value.fields() != null) return Tag.TAG_COMPOUND;
		if (value.children().isEmpty()) return Tag.TAG_LIST;
		int first = convertedId(value.children().getFirst());
		if (first != Tag.TAG_BYTE && first != Tag.TAG_INT && first != Tag.TAG_LONG) return Tag.TAG_LIST;
		for (int index = 1; index < value.children().size(); index++) if (convertedId(value.children().get(index)) != first) return Tag.TAG_LIST;
		return first == Tag.TAG_BYTE ? Tag.TAG_BYTE_ARRAY : first == Tag.TAG_INT ? Tag.TAG_INT_ARRAY : Tag.TAG_LONG_ARRAY;
	}
	private void arrayCost(int n, int width) {
		// Same explicit primitive terminal as UUID/BlockPos, without another
		// source array or PrimitiveCodec Success. ByteBuffer is a distinct route.
		encoding.primitiveArray(n, width);
	}

	void context(InventoryRegistryContext context) { this.context = context; }
	void externalComponent() { external = true; }
	boolean hasExternalComponents() { return external; }
	InventoryExternalComponent external(Object value, Codec<?> codec, int depth) {
		externalComponent();
		budget.allocate(4, 0, 6, 1);
		return new InventoryExternalComponent(value, codec, context, bounds, budget, depth);
	}

	static boolean nativeType(DataComponentType<?> type) {
		return codec(type) != null || type == DataComponents.FOOD || type == DataComponents.CONTAINER
			|| type == DataComponents.BUNDLE_CONTENTS || type == DataComponents.CHARGED_PROJECTILES;
	}

	static boolean transientType(DataComponentType<?> type) {
		return type == DataComponents.CREATIVE_SLOT_LOCK || type == DataComponents.MAP_POST_PROCESSING;
	}

	/** Returns only original static codecs. Nested items and FOOD are owned writers. */
	static Codec<?> codec(DataComponentType<?> type) {
		if (type == DataComponents.MAX_STACK_SIZE || type == DataComponents.MAX_DAMAGE || type == DataComponents.DAMAGE
			|| type == DataComponents.REPAIR_COST || type == DataComponents.OMINOUS_BOTTLE_AMPLIFIER) return Codec.INT;
		if (type == DataComponents.UNBREAKABLE) return Unbreakable.CODEC;
		if (type == DataComponents.RARITY) return Rarity.CODEC;
		if (type == DataComponents.CUSTOM_MODEL_DATA) return CustomModelData.CODEC;
		if (type == DataComponents.ENCHANTMENT_GLINT_OVERRIDE) return Codec.BOOL;
		if (type == DataComponents.DYED_COLOR) return DyedItemColor.CODEC;
		if (type == DataComponents.MAP_COLOR) return MapItemColor.CODEC;
		if (type == DataComponents.MAP_ID) return net.minecraft.world.level.saveddata.maps.MapId.CODEC;
		if (type == DataComponents.BASE_COLOR) return DyeColor.CODEC;
		if (type == DataComponents.HIDE_ADDITIONAL_TOOLTIP || type == DataComponents.HIDE_TOOLTIP
			|| type == DataComponents.INTANGIBLE_PROJECTILE || type == DataComponents.FIRE_RESISTANT) return net.minecraft.util.Unit.CODEC;
		if (type == DataComponents.CUSTOM_NAME || type == DataComponents.ITEM_NAME) return ComponentSerialization.FLAT_CODEC;
		if (type == DataComponents.LORE) return ItemLore.CODEC;
		if (type == DataComponents.CUSTOM_DATA || type == DataComponents.BUCKET_ENTITY_DATA) return CustomData.CODEC;
		if (type == DataComponents.ENTITY_DATA || type == DataComponents.BLOCK_ENTITY_DATA) return CustomData.CODEC_WITH_ID;
		if (type == DataComponents.ENCHANTMENTS || type == DataComponents.STORED_ENCHANTMENTS) return ItemEnchantments.CODEC;
		if (type == DataComponents.ATTRIBUTE_MODIFIERS) return ItemAttributeModifiers.CODEC;
		if (type == DataComponents.TOOL) return Tool.CODEC;
		if (type == DataComponents.POTION_CONTENTS) return PotionContents.CODEC;
		if (type == DataComponents.SUSPICIOUS_STEW_EFFECTS) return SuspiciousStewEffects.CODEC;
		if (type == DataComponents.MAP_DECORATIONS) return MapDecorations.CODEC;
		if (type == DataComponents.WRITABLE_BOOK_CONTENT) return WritableBookContent.CODEC;
		if (type == DataComponents.WRITTEN_BOOK_CONTENT) return WrittenBookContent.CODEC;
		if (type == DataComponents.TRIM) return ArmorTrim.CODEC;
		if (type == DataComponents.INSTRUMENT) return Instrument.CODEC;
		if (type == DataComponents.JUKEBOX_PLAYABLE) return JukeboxPlayable.CODEC;
		if (type == DataComponents.RECIPES) return RECIPES;
		if (type == DataComponents.NOTE_BLOCK_SOUND) return ResourceLocation.CODEC;
		if (type == DataComponents.LODESTONE_TRACKER) return LodestoneTracker.CODEC;
		if (type == DataComponents.FIREWORK_EXPLOSION) return FireworkExplosion.CODEC;
		if (type == DataComponents.FIREWORKS) return Fireworks.CODEC;
		if (type == DataComponents.PROFILE) return ResolvableProfile.CODEC;
		if (type == DataComponents.BANNER_PATTERNS) return BannerPatternLayers.CODEC;
		if (type == DataComponents.POT_DECORATIONS) return PotDecorations.CODEC;
		if (type == DataComponents.BLOCK_STATE) return BlockItemStateProperties.CODEC;
		if (type == DataComponents.BEES) return BeehiveBlockEntity.Occupant.LIST_CODEC;
		if (type == DataComponents.LOCK) return LockCode.CODEC;
		if (type == DataComponents.CONTAINER_LOOT) return SeededContainerLoot.CODEC;
		if (type == DataComponents.CAN_PLACE_ON || type == DataComponents.CAN_BREAK) return AdventureModePredicate.CODEC;
		if (type == DataComponents.DEBUG_STICK_STATE) return DebugStickState.CODEC;
		return null;
	}

	/** Finite replacements for resident arbitrary-key map and CustomData envelopes. */
	Owned owned(DataComponentType<?> type, Object value, int depth) {
		if (type == DataComponents.CAN_PLACE_ON || type == DataComponents.CAN_BREAK) return ownedAdventure(value, depth);
		if (type == DataComponents.ENCHANTMENTS || type == DataComponents.STORED_ENCHANTMENTS) {
			exact(value, ItemEnchantments.class);
			var enchantments = (ItemEnchantments) value;
			var map = raw.enchantments(enchantments);
			exact(map, it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap.class);
			int size = map.size(); cardinality(size);
			budget.allocate(9 + 4L * size, 0, 3L * size + 3, size + 3L);
			var levels = new ArrayList<EnchantmentLevel>(size);
			if (size != 0) {
				budget.work(InventoryExternalFields.READ_WORK);
				int capacity = raw.objectIntCapacity(map);
				if (capacity < 1 || (capacity & (capacity - 1)) != 0) refuse();
				bounds.nativeTraversal((long) capacity + size);
				budget.allocate(8, 0, 5, 3L + size); // entry set, iterator and returned entry wrappers
				for (var entry : map.object2IntEntrySet()) {
					Holder<?> holder = (Holder<?>) entry.getKey();
					holder(holder, Registries.ENCHANTMENT);
					int level = entry.getIntValue(); if (level < 0 || level > 255) refuse();
					String key = resourceString(raw.holderKey((Holder.Reference<?>) holder).location());
					for (int index = 0; index < levels.size(); index++) { budget.work(key.length() + 1L); if (key.equals(levels.get(index).key)) refuse(); }
					levels.add(new EnchantmentLevel(key, level));
				}
			}
			return new OwnedEnchantments(levels, raw.enchantmentTooltip(enchantments));
		}
		if (type == DataComponents.CUSTOM_DATA || type == DataComponents.BUCKET_ENTITY_DATA
			|| type == DataComponents.ENTITY_DATA || type == DataComponents.BLOCK_ENTITY_DATA) {
			exact(value, CustomData.class); var custom = (CustomData) value;
			InventoryComponentBounds.NbtValue data = bounds.customData(custom.getUnsafe());
			if (type == DataComponents.ENTITY_DATA || type == DataComponents.BLOCK_ENTITY_DATA) {
				boolean hasId = false;
				for (int index = 0; index < data.fields().size(); index++) { var field = data.fields().get(index);
					budget.work(8); if (field.key().equals("id") && field.value().scalar() instanceof net.minecraft.nbt.StringTag) hasId = true; }
				if (!hasId) refuse();
			}
			budget.allocate(0, 0, 1, 1);
			return new OwnedData(data);
		}
		if (type == DataComponents.BEES) {
			List<?> bees = list((List<?>) value); budget.allocate(8 + 8L * bees.size(), 0, 2L * bees.size() + 2, bees.size() + 3L);
			var owned = new ArrayList<OwnedBee>(bees.size());
			for (int index = 0; index < bees.size(); index++) { Object object = bees.get(index);
				exact(object, BeehiveBlockEntity.Occupant.class); var bee = (BeehiveBlockEntity.Occupant) object;
				exact(bee.entityData(), CustomData.class);
				owned.add(new OwnedBee(bounds.customData(bee.entityData().getUnsafe()), bee.ticksInHive(), bee.minTicksInHive()));
			}
			return new OwnedBees(owned);
		}
		if (type == DataComponents.MAP_DECORATIONS) {
			exact(value, MapDecorations.class); Map<String, MapDecorations.Entry> map = ((MapDecorations) value).decorations();
			int size = bounds.mapPass(map); budget.allocate(8, 0, 3L * size + 3, size + 3L);
			var entries = new ArrayList<Decoration>(size);
			if (size != 0) for (var entry : map.entrySet()) {
				budget.string(entry.getKey()); exact(entry.getValue(), MapDecorations.Entry.class);
				var decoration = entry.getValue(); holder(decoration.type(), Registries.MAP_DECORATION_TYPE); finite(decoration.x()); finite(decoration.z()); finite(decoration.rotation());
				entries.add(new Decoration(entry.getKey(), decoration));
			}
			return new OwnedDecorations(entries);
		}
		if (type == DataComponents.BLOCK_STATE) {
			exact(value, BlockItemStateProperties.class); Map<String, String> map = ((BlockItemStateProperties) value).properties();
			int size = bounds.mapPass(map); budget.allocate(8, 0, 3L * size + 3, size + 3L);
			var entries = new ArrayList<StringField>(size);
			if (size != 0) for (var entry : map.entrySet()) {
				budget.string(entry.getKey()); budget.string(entry.getValue()); entries.add(new StringField(entry.getKey(), entry.getValue()));
			}
			return new OwnedState(entries);
		}
		if (type == DataComponents.DEBUG_STICK_STATE) {
			exact(value, DebugStickState.class); if (bounds.mapPass(((DebugStickState) value).properties()) != 0) refuse();
			budget.allocate(0, 0, 1, 1);
			return new OwnedState(List.of());
		}
		return null;
	}

	/**
	 * Native resident passes after the single shape guard. The owned replacements
	 * above perform no resident output pass. Other families remain explicit here
	 * so a later direction can be admitted without repeating recursive validation.
	 */
	void beginNativePasses() { laterListVisits = 0; laterCopyCells = 0; }
	void admitPasses(NativePasses passes) { passes.admit(budget, bounds); }
	void admitCostPass(NativePasses passes) {
		bounds.nativeTraversal(passes.visits);
	}
	NativePasses nativePasses() {
		budget.allocate(16, 0, 0, 1);
		return new NativePasses(laterListVisits, laterCopyCells);
	}
	private void listPass(int size) { laterListVisits = plus(laterListVisits, size); }
	private void copyPass(int size) { laterCopyCells = plus(laterCopyCells, size); }
	record NativePasses(long visits, long copies) {
		void admit(InventoryItemBudget budget, InventoryComponentBounds bounds) {
			bounds.nativeTraversal(plus(visits, copies));
		}
	}

	sealed interface Owned permits OwnedData, OwnedBees, OwnedDecorations, OwnedState, OwnedEnchantments, OwnedAdventure {
		<T> T encode(DynamicOps<T> ops);
	}
	private record OwnedData(InventoryComponentBounds.NbtValue value) implements Owned {
		public <T> T encode(DynamicOps<T> ops) { return value.encode(ops); }
	}
	private record OwnedBee(InventoryComponentBounds.NbtValue data, int ticks, int minimum) {
		<T> T encode(DynamicOps<T> ops) {
			var fields = new ArrayList<Pair<T, T>>(3);
			if (!data.emptyCompound()) fields.add(Pair.of(ops.createString("entity_data"), data.encode(ops)));
			fields.add(Pair.of(ops.createString("ticks_in_hive"), ops.createInt(ticks)));
			fields.add(Pair.of(ops.createString("min_ticks_in_hive"), ops.createInt(minimum)));
			return ops.createMap(fields.stream());
		}
	}
	private record OwnedBees(List<OwnedBee> bees) implements Owned {
		public <T> T encode(DynamicOps<T> ops) { return ops.createList(bees.stream().map(bee -> bee.encode(ops))); }
	}
	private record Decoration(String key, MapDecorations.Entry value) {}
	private record OwnedDecorations(List<Decoration> entries) implements Owned {
		public <T> T encode(DynamicOps<T> ops) {
			return entries.isEmpty() ? ops.emptyMap() : ops.createMap(entries.stream().map(entry -> Pair.of(ops.createString(entry.key),
				InventoryItemDiagnostics.complete(MapDecorations.Entry.CODEC.encodeStart(ops, entry.value), "certified decoration"))));
		}
	}
	private record StringField(String key, String value) {}
	private record Match(String name, String exact, String minimum, String maximum) {
		<T> T encode(DynamicOps<T> ops) {
			if (exact != null) return ops.createString(exact);
			var fields = new ArrayList<Pair<T, T>>(2);
			if (minimum != null) fields.add(Pair.of(ops.createString("min"), ops.createString(minimum)));
			if (maximum != null) fields.add(Pair.of(ops.createString("max"), ops.createString(maximum)));
			return fields.isEmpty() ? ops.emptyMap() : ops.createMap(fields.stream());
		}
	}
	private record BlockSet(String tag, List<String> values) {
		<T> T encode(DynamicOps<T> ops) {
			if (tag != null) return ops.createString(tag);
			// RegistryCodecs.homogeneousList defaults disallowInline=false:
			// exactly one direct element uses the scalar alternative.
			return values.size() == 1 ? ops.createString(values.getFirst()) : ops.createList(values.stream().map(ops::createString));
		}
	}
	private record Predicate(BlockSet blocks, List<Match> state, String nbt) {
		<T> T encode(DynamicOps<T> ops) {
			var fields = new ArrayList<Pair<T, T>>(3);
			if (blocks != null) fields.add(Pair.of(ops.createString("blocks"), blocks.encode(ops)));
			if (state != null) fields.add(Pair.of(ops.createString("state"), state.isEmpty() ? ops.emptyMap() : ops.createMap(state.stream()
				.map(match -> Pair.of(ops.createString(match.name), match.encode(ops))))));
			if (nbt != null) fields.add(Pair.of(ops.createString("nbt"), ops.createString(nbt)));
			return fields.isEmpty() ? ops.emptyMap() : ops.createMap(fields.stream());
		}
	}
	private record OwnedAdventure(List<Predicate> predicates, boolean tooltip) implements Owned {
		public <T> T encode(DynamicOps<T> ops) {
			var predicatesField = Pair.of(ops.createString("predicates"), ops.createList(predicates.stream().map(predicate -> predicate.encode(ops))));
			return ops.createMap(tooltip ? java.util.stream.Stream.of(predicatesField) : java.util.stream.Stream.of(predicatesField,
				Pair.of(ops.createString("show_in_tooltip"), ops.createBoolean(false))));
		}
	}
	private record EnchantmentLevel(String key, int level) {}
	private record OwnedEnchantments(List<EnchantmentLevel> levels, boolean tooltip) implements Owned {
		public <T> T encode(DynamicOps<T> ops) {
			T values = levels.isEmpty() ? ops.emptyMap() : ops.createMap(levels.stream()
				.map(level -> Pair.of(ops.createString(level.key), ops.createInt(level.level))));
			Pair<T, T> field = Pair.of(ops.createString("levels"), values);
			return ops.createMap(tooltip ? java.util.stream.Stream.of(field) : java.util.stream.Stream.of(field,
				Pair.of(ops.createString("show_in_tooltip"), ops.createBoolean(false))));
		}
	}
	private record OwnedState(List<StringField> fields) implements Owned {
		public <T> T encode(DynamicOps<T> ops) { return fields.isEmpty() ? ops.emptyMap() : ops.createMap(fields.stream()
			.map(field -> Pair.of(ops.createString(field.key), ops.createString(field.value)))); }
	}

	private static long plus(long left, long right) { return InventoryItemBudget.add(left, right); }

	void value(DataComponentType<?> type, Object value, int depth) {
		budget.node(depth); budget.work(256); // closed family dispatch/getters/validation control
		if (type == DataComponents.MAX_STACK_SIZE || type == DataComponents.MAX_DAMAGE || type == DataComponents.DAMAGE
			|| type == DataComponents.REPAIR_COST || type == DataComponents.OMINOUS_BOTTLE_AMPLIFIER) {
			exact(value, Integer.class); int i = (Integer) value;
			if (i < 0 || (type == DataComponents.MAX_STACK_SIZE && (i < 1 || i > 99))
				|| (type == DataComponents.MAX_DAMAGE && i < 1) || (type == DataComponents.OMINOUS_BOTTLE_AMPLIFIER && i > 4)) refuse();
			return;
		}
		if (type == DataComponents.CUSTOM_NAME || type == DataComponents.ITEM_NAME) { text((Component) value, depth + 1); return; }
		if (type == DataComponents.LORE) {
			exact(value, ItemLore.class); List<?> lines = list(((ItemLore) value).lines()); listPass(lines.size());
			for (Object line : lines) text((Component) line, depth + 1); return;
		}
		if (type == DataComponents.ATTRIBUTE_MODIFIERS) {
			exact(value, ItemAttributeModifiers.class);
			List<?> modifiers = list(((ItemAttributeModifiers) value).modifiers()); listPass(modifiers.size());
			for (Object object : modifiers) {
				exact(object, ItemAttributeModifiers.Entry.class); var entry = (ItemAttributeModifiers.Entry) object;
				holder(entry.attribute(), Registries.ATTRIBUTE); exact(entry.modifier(), net.minecraft.world.entity.ai.attributes.AttributeModifier.class);
				resource(entry.modifier().id()); finite(entry.modifier().amount());
				exact(entry.slot(), net.minecraft.world.entity.EquipmentSlotGroup.class);
				exact(entry.modifier().operation(), net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.class);
			} return;
		}
		if (type == DataComponents.TOOL) {
			exact(value, Tool.class); var tool = (Tool) value; finite(tool.defaultMiningSpeed()); if (tool.damagePerBlock() < 0) refuse();
			List<?> rules = list(tool.rules()); listPass(rules.size());
			for (Object object : rules) {
				exact(object, Tool.Rule.class); var rule = (Tool.Rule) object; holders(rule.blocks());
				if (rule.speed().isPresent()) { Object speed = ((Optional<?>) rule.speed()).orElseThrow(); exact(speed, Float.class); finite((Float) speed); if ((Float) speed <= 0) refuse(); }
				if (rule.correctForDrops().isPresent()) exact(((Optional<?>) rule.correctForDrops()).orElseThrow(), Boolean.class);
			}
			return;
		}
		if (type == DataComponents.POTION_CONTENTS) {
			exact(value, PotionContents.class); var potion = (PotionContents) value;
			if (potion.potion().isPresent()) holder(potion.potion().orElseThrow(), Registries.POTION);
			if (potion.customColor().isPresent()) exact(((Optional<?>) potion.customColor()).orElseThrow(), Integer.class);
			List<?> effects = list(raw.potion(potion)); listPass(effects.size());
			// Transform view/list iterators and empty-default equality do not call
			// the copying getter for the first element of a nonempty list.
			for (Object effect : effects) effectCopy((MobEffectInstance) effect, depth + 1);
			return; // Intentional native-copy semantics: no hiddenEffect descent.
		}
		if (type == DataComponents.SUSPICIOUS_STEW_EFFECTS) {
			exact(value, SuspiciousStewEffects.class);
			List<?> effects = list(((SuspiciousStewEffects) value).effects()); listPass(effects.size());
			for (Object object : effects) { exact(object, SuspiciousStewEffects.Entry.class); holder(((SuspiciousStewEffects.Entry) object).effect(), Registries.MOB_EFFECT); } return;
		}
		if (type == DataComponents.WRITABLE_BOOK_CONTENT) {
			exact(value, WritableBookContent.class); List<?> pages = list(((WritableBookContent) value).pages()); listPass(pages.size());
			for (Object page : pages) filter(page, false, depth, 1024); return;
		}
		if (type == DataComponents.WRITTEN_BOOK_CONTENT) {
			exact(value, WrittenBookContent.class); var book = (WrittenBookContent) value; filter(book.title(), false, depth, 32); budget.string(book.author());
			if (book.generation() < 0 || book.generation() > 3) refuse();
			List<?> pages = list(book.pages()); listPass(pages.size()); for (Object page : pages) filter(page, true, depth, 32767); return;
		}
		if (type == DataComponents.TRIM) { exact(value, ArmorTrim.class); holder(((ArmorTrim) value).material(), Registries.TRIM_MATERIAL); holder(((ArmorTrim) value).pattern(), Registries.TRIM_PATTERN); return; }
		if (type == DataComponents.INSTRUMENT) { holder((Holder<?>) value, Registries.INSTRUMENT); return; }
		if (type == DataComponents.JUKEBOX_PLAYABLE) { exact(value, JukeboxPlayable.class); var song = ((JukeboxPlayable) value).song(); exact(song, EitherHolder.class);
			if (song.holder().isPresent()) holder(song.holder().orElseThrow(), Registries.JUKEBOX_SONG);
			if (song.holder().isEmpty()) { exact(song.key(), ResourceKey.class); if (!song.key().isFor(Registries.JUKEBOX_SONG)) refuse(); resource(song.key().location()); } return; }
		if (type == DataComponents.RECIPES) { List<?> recipes = list((List<?>) value); listPass(recipes.size()); for (Object location : recipes) resource((ResourceLocation) location); return; }
		if (type == DataComponents.NOTE_BLOCK_SOUND) { resource((ResourceLocation) value); return; }
		if (type == DataComponents.LODESTONE_TRACKER) {
			exact(value, LodestoneTracker.class); var target = ((LodestoneTracker) value).target(); if (target.isPresent()) { var pos = target.orElseThrow();
				exact(pos, GlobalPos.class); exact(pos.dimension(), ResourceKey.class);
				BlockPos block = pos.pos();
				if (block == null || (block.getClass() != BlockPos.class && block.getClass() != BlockPos.MutableBlockPos.class)) refuse();
				resource(pos.dimension().location());
			} return;
		}
		if (type == DataComponents.FIREWORK_EXPLOSION) { explosion(value); return; }
		if (type == DataComponents.FIREWORKS) { exact(value, Fireworks.class); if (((Fireworks) value).flightDuration() > 255) refuse();
			List<?> explosions = list(((Fireworks) value).explosions()); listPass(explosions.size());
			for (Object explosion : explosions) explosion(explosion); return; }
		if (type == DataComponents.PROFILE) { profile(value); return; }
		if (type == DataComponents.BANNER_PATTERNS) { exact(value, BannerPatternLayers.class); List<?> layers = list(((BannerPatternLayers) value).layers()); listPass(layers.size()); for (Object object : layers) {
			exact(object, BannerPatternLayers.Layer.class); holder(((BannerPatternLayers.Layer) object).pattern(), Registries.BANNER_PATTERN);
			exact(((BannerPatternLayers.Layer) object).color(), DyeColor.class); } return; }
		if (type == DataComponents.POT_DECORATIONS) { exact(value, PotDecorations.class); var pot = (PotDecorations) value;
			potItem(pot.back()); potItem(pot.left()); potItem(pot.right()); potItem(pot.front()); return; }
		if (type == DataComponents.LOCK) { exact(value, LockCode.class); budget.string(((LockCode) value).key()); return; }
		if (type == DataComponents.CONTAINER_LOOT) { exact(value, SeededContainerLoot.class); resource(((SeededContainerLoot) value).lootTable().location()); return; }
		Class<?> scalar = scalarClass(type); if (scalar != null) {
			exact(value, scalar);
			if (type == DataComponents.RARITY) budget.string(((Rarity) value).getSerializedName());
			return;
		}
		refuse();
	}

	private static Class<?> scalarClass(DataComponentType<?> type) {
		if (type == DataComponents.UNBREAKABLE) return Unbreakable.class;
		if (type == DataComponents.RARITY) return Rarity.class;
		if (type == DataComponents.CUSTOM_MODEL_DATA) return CustomModelData.class;
		if (type == DataComponents.ENCHANTMENT_GLINT_OVERRIDE) return Boolean.class;
		if (type == DataComponents.DYED_COLOR) return DyedItemColor.class;
		if (type == DataComponents.MAP_COLOR) return MapItemColor.class;
		if (type == DataComponents.MAP_ID) return net.minecraft.world.level.saveddata.maps.MapId.class;
		if (type == DataComponents.BASE_COLOR) return DyeColor.class;
		if (type == DataComponents.HIDE_ADDITIONAL_TOOLTIP || type == DataComponents.HIDE_TOOLTIP
			|| type == DataComponents.INTANGIBLE_PROJECTILE || type == DataComponents.FIRE_RESISTANT) return net.minecraft.util.Unit.class;
		return null;
	}

	EffectCost effectCopy(MobEffectInstance value, int depth) {
		exact(value, MobEffectInstance.class); budget.node(depth); holder(value.getEffect(), Registries.MOB_EFFECT);
		if (value.getAmplifier() < 0 || value.getAmplifier() > 255) refuse();
		if (effectCopyCount == InventoryItemBudget.MAX_NODES) refuse();
		budget.allocate(40, 0, 0, 1); // EffectCost's five long fields
		// First use only, but still before cure inspection or any native copy.
		// The fixed cardinality and append order survive identity/display passes.
		if (effectCopies == null) {
			budget.allocate(0, 0, InventoryItemBudget.MAX_NODES, 1);
			effectCopies = new EffectCost[InventoryItemBudget.MAX_NODES];
		}
		@Nullable Set<?> cures = nativeAccess.effectCures(value);
		EffectCost cost;
		if (cures != null) {
			exact(cures, CURE_SET); if (!InventoryNativeTraversal.runtimeSupported()) refuse();
			budget.allocate(524, 0, 65, 33); budget.work(290);
			int size = cures.size(); cardinality(size);
			// IdentityHashMap's empty iterator still scans retained capacity during
			// native copy.addAll. Probe it even when no cure is currently present.
			var probe = cures.spliterator(); long cells = 2;
			for (int split = 0; ; split++) {
				if (cells > budget.remainingTraversalWork() || split > 29) refuse();
				var lower = probe.trySplit(); if (lower == null) break; probe = lower; cells <<= 1;
			}
			if (size != 0) {
				bounds.nativeTraversal(cells + size);
				budget.allocate(8, 0, 1, 1); // resident KeyIterator on the guard pass
				for (Object cure : cures) { @Nullable String name = nativeAccess.effectCureName(cure); if (name == null) refuse(); budget.string(name); }
			}
			long table = 64, growth = 64, arrays = 1;
			while (3L * size > table) { table *= 2; growth += table; arrays++; }
			// Native copy + BlendState + Details: 30 scalar bytes, six refs,
			// three headers. asDetails' cures Optional and show_icon getter's
			// Optional add two refs/two headers. SetFromMap, IdentityHashMap,
			// KeySet and the source KeyIterator add 21 bytes/nine refs/four
			// headers (including AbstractMap's inherited view refs). All table
			// attempts contribute growth refs/arrays headers, including size=0.
			// Nonempty cures additionally use KeySet.toArray's empty array and
			// exact size array, then one ImmutableList wrapper (one ref/header).
			// This is copied-table order: never emit resident order or sort it.
			cost = new EffectCost(51, growth + 17 + (size == 0 ? 0 : size + 1L),
				9 + arrays + (size == 0 ? 0 : 3),
				cells + size + (size == 0 ? 0 : table / 2 + 2L * size),
				272 + 6 * cells + 28L * size + 192 + 16L * size * table + 12 * growth
					+ (size == 0 ? 0 : 4 * table + 12L * size));
		} else {
			// Vanilla copy (11 scalar bytes/three refs), BlendState (8 bytes),
			// Details (11 bytes/one ref) and show_icon Optional (one ref).
			// The copied hiddenEffect is null, so its Optional/map allocate none.
			cost = new EffectCost(30, 5, 4, 0, 112);
		}
		effectCopies[effectCopyCount++] = cost;
		return cost;
	}

	int effectCopyCount() { return effectCopyCount; }
	void admitEffectCopies(int first, int after) {
		for (int index = first; index < after; index++) effectCopies[index].admit(budget, bounds);
	}

	/** Native copy/Details/cure materialization only; RCB/builders/leaf codecs are additive. */
	record EffectCost(long bytes, long references, long objects, long traversal, long operations) {
		void admit(InventoryItemBudget budget, InventoryComponentBounds bounds) {
			budget.allocate(bytes, 0, references, objects); bounds.nativeTraversal(traversal); budget.work(operations);
		}
	}

	void text(Component value, int depth) {
		exact(value, MutableComponent.class); budget.node(depth); budget.work(256); bounds.enter(value);
		try {
			ComponentContents contents = value.getContents(); Class<?> type = contents.getClass();
			if (contents == PlainTextContents.EMPTY) { }
			else if (type == PlainTextContents.LiteralContents.class) budget.string(((PlainTextContents.LiteralContents) contents).text());
			else if (type == TranslatableContents.class) { var trans = (TranslatableContents) contents;
				budget.string(trans.getKey()); if (trans.getFallback() != null) budget.string(trans.getFallback());
				Object[] args = trans.getArgs(); cardinality(args.length);
				for (Object arg : args) { if (arg instanceof Component c) text(c, depth + 1); else if (arg instanceof String s) budget.string(s);
					else if (arg == null || !(arg.getClass() == Boolean.class || arg.getClass() == Integer.class || arg.getClass() == Long.class
						|| arg.getClass() == Double.class || arg.getClass() == Float.class || arg.getClass() == Byte.class || arg.getClass() == Short.class)) refuse();
					if (arg instanceof Double d && !Double.isFinite(d) || arg instanceof Float f && !Float.isFinite(f)) refuse();
				}
			} else if (type == KeybindContents.class) budget.string(((KeybindContents) contents).getName());
			else if (type == ScoreContents.class) { budget.string(((ScoreContents) contents).getName()); budget.string(((ScoreContents) contents).getObjective()); }
			else if (type == SelectorContents.class) { var selector = (SelectorContents) contents; budget.string(selector.getPattern()); if (selector.getSeparator().isPresent()) text(selector.getSeparator().orElseThrow(), depth + 1); }
			else if (type == NbtContents.class) { var nbt = (NbtContents) contents; budget.string(nbt.getNbtPath()); if (nbt.getSeparator().isPresent()) text(nbt.getSeparator().orElseThrow(), depth + 1);
				var source = nbt.getDataSource(); if (source.getClass() == BlockDataSource.class) budget.string(((BlockDataSource) source).posPattern());
				else if (source.getClass() == EntityDataSource.class) budget.string(((EntityDataSource) source).selectorPattern());
				else if (source.getClass() == StorageDataSource.class) resource(((StorageDataSource) source).id()); else refuse();
			} else refuse();
			List<?> siblings = list(value.getSiblings()); listPass(siblings.size());
			for (Object sibling : siblings) text((Component) sibling, depth + 1);
			Style style = value.getStyle(); exact(style, Style.class); resource(style.getFont());
			if (style.getInsertion() != null) budget.string(style.getInsertion());
			if (style.getColor() != null) {
				exact(style.getColor(), TextColor.class);
				String name = raw.colorName(style.getColor()); if (name != null) budget.string(name);
			}
			ResourceLocation font = raw.styleFont(style); if (font != null) resource(font);
			ClickEvent click = style.getClickEvent(); if (click != null) {
				exact(click, ClickEvent.class); exact(click.getAction(), ClickEvent.Action.class); budget.string(click.getValue());
			}
			HoverEvent hover = style.getHoverEvent(); if (hover != null) { exact(hover, HoverEvent.class);
				if (hover.getAction() == HoverEvent.Action.SHOW_TEXT) text(hover.getValue(HoverEvent.Action.SHOW_TEXT), depth + 1);
				else if (hover.getAction() == HoverEvent.Action.SHOW_ENTITY) { var entity = hover.getValue(HoverEvent.Action.SHOW_ENTITY); exact(entity, HoverEvent.EntityTooltipInfo.class);
					budget.work(InventoryRegistryContext.valueWork(BuiltInRegistries.ENTITY_TYPE, raw));
					resource(BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)); exact(entity.id, java.util.UUID.class); if (entity.name.isPresent()) text(entity.name.orElseThrow(), depth + 1); }
				else refuse();
			}
		} finally { bounds.leave(); }
	}

	private OwnedAdventure ownedAdventure(Object value, int depth) {
		exact(value, AdventureModePredicate.class); var adventure = (AdventureModePredicate) value;
		List<?> source = list(raw.predicates(adventure)); if (source.isEmpty()) refuse();
		budget.allocate(9, 0, 4L * source.size() + 3, source.size() + 3L);
		var predicates = new ArrayList<Predicate>(source.size());
		for (Object object : source) {
			exact(object, BlockPredicate.class); var predicate = (BlockPredicate) object;
			BlockSet blocks = null;
			if (predicate.blocks().isPresent()) {
				HolderSet<?> set = predicate.blocks().orElseThrow();
				if (context == null || context.owner(Registries.BLOCK) == null) refuse();
				if (nativeNamed(set)) {
					var named = (HolderSet.Named<?>) set; var key = raw.namedKey(named); exact(key, net.minecraft.tags.TagKey.class);
					if (key.registry() != Registries.BLOCK || raw.namedOwner(named) != context.owner(Registries.BLOCK)) refuse();
					resource(key.location()); String tag = resourceString(key.location());
					budget.allocate(16 + 3L * (tag.length() + 1), 0, 3, 4);
					blocks = new BlockSet("#" + tag, null);
				} else {
					exact(set, HolderSet.Direct.class); List<?> holders = list(raw.directContents((HolderSet.Direct<?>) set));
					budget.allocate(8, 0, holders.size() + 3L, 3); var ids = new ArrayList<String>(holders.size());
					for (Object element : holders) {
						holder((Holder<?>) element, Registries.BLOCK);
						ids.add(resourceString(raw.holderKey((Holder.Reference<?>) element).location()));
					}
					blocks = new BlockSet(null, ids);
				}
			}
			List<Match> state = null;
			if (predicate.properties().isPresent()) {
				var properties = predicate.properties().orElseThrow(); exact(properties, StatePropertiesPredicate.class);
				List<?> matches = list(properties.properties()); budget.allocate(8, 0, 5L * matches.size() + 2, matches.size() + 2L);
				state = new ArrayList<>(matches.size());
				for (Object matchObject : matches) {
					exact(matchObject, StatePropertiesPredicate.PropertyMatcher.class); var match = (StatePropertiesPredicate.PropertyMatcher) matchObject;
					budget.string(match.name());
					for (int index = 0; index < state.size(); index++) { budget.work(match.name().length() + 1L); if (match.name().equals(state.get(index).name)) refuse(); }
					Object matcher = match.valueMatcher();
					if (matcher != null && matcher.getClass() == StatePropertiesPredicate.ExactMatcher.class) {
						String exact = ((StatePropertiesPredicate.ExactMatcher) matcher).value(); budget.string(exact);
						state.add(new Match(match.name(), exact, null, null));
					} else if (matcher != null && matcher.getClass() == StatePropertiesPredicate.RangedMatcher.class) {
						var ranged = (StatePropertiesPredicate.RangedMatcher) matcher;
						if (ranged.minValue().isPresent()) budget.string(ranged.minValue().orElseThrow()); if (ranged.maxValue().isPresent()) budget.string(ranged.maxValue().orElseThrow());
						state.add(new Match(match.name(), null, ranged.minValue().orElse(null), ranged.maxValue().orElse(null)));
					} else refuse();
				}
			}
			String nbt = null;
			if (predicate.nbt().isPresent()) {
				var nbtPredicate = predicate.nbt().orElseThrow(); exact(nbtPredicate, NbtPredicate.class);
				nbt = InventorySnbtWriter.write(bounds.snapshotNbt(nbtPredicate.tag()), budget);
			}
			predicates.add(new Predicate(blocks, state, nbt));
		}
		return new OwnedAdventure(predicates, adventure.showInTooltip());
	}

	private void profile(Object value) {
		exact(value, ResolvableProfile.class); var profile = (ResolvableProfile) value;
		Optional<?> nameValue = profile.name();
		if (nameValue.isPresent()) {
			Object object = nameValue.orElseThrow(); exact(object, String.class); String name = (String) object;
			budget.string(name); if (name.length() > 16) refuse();
			for (int index = 0; index < name.length(); index++) {
				budget.work(1); char c = name.charAt(index); if (c <= 32 || c >= 127) refuse();
			}
		}
		Optional<?> idValue = profile.id(); if (idValue.isPresent()) exact(idValue.orElseThrow(), java.util.UUID.class);
		exact(profile.properties(), com.mojang.authlib.properties.PropertyMap.class);
		budget.work(InventoryExternalFields.READ_WORK);
		var delegate = raw.profile(profile.properties()); exact(delegate, PROPERTY_DELEGATE);
		int size = delegate.size(); cardinality(size); bounds.nativeTraversal(size);
		budget.allocate(0, 0, 5, 3); // values view and linked iterators on this guard pass
		listPass(size); copyPass(size); // resident values().stream().toList then owned ListCodec
		for (var property : delegate.values()) { exact(property, com.mojang.authlib.properties.Property.class);
			budget.string(property.name()); budget.string(property.value()); if (property.signature() != null) budget.string(property.signature()); }
	}

	private void explosion(Object object) {
		exact(object, FireworkExplosion.class); var explosion = (FireworkExplosion) object;
		exact(explosion.shape(), FireworkExplosion.Shape.class); budget.string(explosion.shape().getSerializedName());
		colors(explosion.colors()); colors(explosion.fadeColors());
	}
	private void colors(it.unimi.dsi.fastutil.ints.IntList colors) {
		Class<?> type = colors.getClass(); if (type != it.unimi.dsi.fastutil.ints.IntArrayList.class
			&& type != EMPTY_COLORS && type != SINGLE_COLORS) refuse();
		cardinality(colors.size()); budget.array(4L * colors.size());
		listPass(colors.size()); copyPass(colors.size()); // ArrayList(Collection) boxing/toArray, then ListCodec
	}

	private void filter(Object value, boolean text, int depth, int maximum) {
		exact(value, net.minecraft.server.network.Filterable.class); var filter = (net.minecraft.server.network.Filterable<?>) value;
		if (text) { text((Component) filter.raw(), depth + 1); if (filter.filtered().isPresent()) text((Component) filter.filtered().orElseThrow(), depth + 1); }
		else {
			boundedString(filter.raw(), maximum);
			if (filter.filtered().isPresent()) boundedString(filter.filtered().orElseThrow(), maximum);
		}
	}
	private void boundedString(Object value, int maximum) {
		exact(value, String.class); String string = (String) value;
		budget.string(string); if (string.length() > maximum) refuse();
	}
	void holders(HolderSet<?> set) {
		if (context == null || context.owner(Registries.BLOCK) == null) refuse();
		if (nativeNamed(set)) {
			var named = (HolderSet.Named<?>) set;
			if (raw.namedOwner(named) != context.owner(Registries.BLOCK)) refuse();
			var key = raw.namedKey(named);
			exact(key, net.minecraft.tags.TagKey.class);
			if (!key.registry().equals(Registries.BLOCK)) refuse(); resource(key.location()); return;
		}
		exact(set, HolderSet.Direct.class);
		List<?> holders = raw.directContents((HolderSet.Direct<?>) set);
		List<?> admitted = list(holders);
		copyPass(admitted.size()); listPass(admitted.size()); listPass(admitted.size()); // List.copyOf, ensureHomogenous, ListCodec
		for (Object holder : admitted) holder((Holder<?>) holder, Registries.BLOCK);
	}
	void holder(Holder<?> holder, ResourceKey<? extends Registry<?>> expectedRegistry) {
		exact(holder, Holder.Reference.class);
		var reference = (Holder.Reference<?>) holder;
		var key = raw.holderKey(reference);
		if (key == null || raw.holderValue(reference) == null) refuse();
		exact(key, ResourceKey.class);
		if (!key.registry().equals(expectedRegistry.location())) refuse();
		// Built-in holderByNameCodec captures its own registry. File/fixed codecs
		// must have the admitted context owner before native inline/error paths.
		if (expectedRegistry != Registries.POTION && expectedRegistry != Registries.MOB_EFFECT
			&& expectedRegistry != Registries.ATTRIBUTE && expectedRegistry != Registries.MAP_DECORATION_TYPE) {
			HolderOwner<?> owner = context == null ? null : context.owner(expectedRegistry);
			if (owner == null || raw.holderOwner(reference) != owner) refuse();
		}
		resource(key.location());
	}
	void resource(ResourceLocation value) { exact(value, ResourceLocation.class); budget.string(value.getNamespace()); budget.string(value.getPath()); }
	private String resourceString(ResourceLocation value) {
		int length = value.getNamespace().length() + value.getPath().length() + 1;
		budget.allocate(16 + 3L * length, 0, 1, 3); budget.work(length); return value.toString();
	}
	List<?> list(List<?> value) {
		List<?> admitted = bounds.list(value); budget.work(admitted.size());
		// One guard iterator on the ORIGINAL admitted list (NonNullList may
		// wrap the raw backing). Subsequent CODEC/cost passes pay separately.
		budget.allocate(32, 0, 3, 2);
		for (int index = 0; index < admitted.size(); index++) budget.node(0);
		return admitted;
	}
	private void finite(double value) { if (!Double.isFinite(value)) refuse(); }
	private void potItem(Optional<Item> item) {
		if (item.isPresent()) {
			// DefaultedMappedRegistry.getKey may return its default for an
			// unregistered value. Prove wrapAsHolder's Reference branch instead
			// of admitting an allocating Direct holder followed by safeCast error.
			Map<?, ?> values = raw.registryValues((MappedRegistry<?>) BuiltInRegistries.ITEM);
			budget.work(InventoryRegistryContext.lookupWork(values));
			Object registered = values.get(item.orElseThrow()); exact(registered, Holder.Reference.class);
			var reference = (Holder.Reference<?>) registered;
			if (raw.holderValue(reference) != item.orElseThrow()) refuse();
			var key = raw.holderKey(reference); exact(key, ResourceKey.class);
			if (!key.isFor(Registries.ITEM)) refuse(); resource(key.location());
			budget.work(InventoryRegistryContext.valueWork(BuiltInRegistries.ITEM, raw));
			resource(BuiltInRegistries.ITEM.getKey(item.orElseThrow()));
		}
	}
	static void exact(Object value, Class<?> expected) { if (value == null || value.getClass() != expected) refuse(); }
	private static void cardinality(int size) { if (size < 0 || size > InventoryItemBudget.MAX_COLLECTION) refuse(); }
	static void refuse() { throw new InventoryItemBudget.RootLimitException("uncertified inventory component shape"); }
}
