package nx.pingwheel.common.presentation.inventory.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.advancements.critereon.BlockPredicate;
import net.minecraft.advancements.critereon.NbtPredicate;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.AdventureModePredicate;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.*;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.UnsafeDisplay;
import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.Validation;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryNativeTraversal;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess;

/** Per-consumer native mutable values, distinct from the stricter lazy-hover copier. */
final class ClientDisplayWorkingCopy {

	private static final InventoryResidentAccess RAW = InventoryResidentAccess.load();
	private static final Class<?> SIMPLE = DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 1).build().getClass();
	private static final Constructor<ItemStack> EMPTY_STACK = constructor(ItemStack.class, Void.class);
	private static final Field STACK_ITEM = ClientItemDisplay.nativeField(ItemStack.class, Item.class);
	private static final Field STACK_COMPONENTS = ClientItemDisplay.nativeField(ItemStack.class, PatchedDataComponentMap.class);
	private static final Constructor<ItemContainerContents> CONTAINER = constructor(ItemContainerContents.class, int.class);
	private static final Field CONTAINER_HASH = ClientItemDisplay.nativeField(ItemContainerContents.class, int.class);
	private static final Constructor<BundleContents> BUNDLE = constructor(BundleContents.class, List.class, org.apache.commons.lang3.math.Fraction.class);
	private static final Constructor<ChargedProjectiles> PROJECTILES = constructor(ChargedProjectiles.class, List.class);
	private static final Constructor<ItemEnchantments> ENCHANTMENTS = constructor(ItemEnchantments.class, it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap.class, boolean.class);
	private static final Constructor<CustomData> CUSTOM_DATA = constructor(CustomData.class, CompoundTag.class);
	private static final Constructor<AdventureModePredicate> ADVENTURE = constructor(AdventureModePredicate.class, List.class, boolean.class, List.class);
	private static final Field ADVENTURE_TOOLTIP = tooltipField();
	private static final Class<?> PROFILE_MAP = com.google.common.collect.LinkedHashMultimap.create().getClass();
	private static final Field HIDDEN_EFFECT = ClientItemDisplay.nativeField(MobEffectInstance.class, MobEffectInstance.class);

	private ClientDisplayWorkingCopy() {}

	static DataComponentPatch overlay(DataComponentMap prototype, DataComponentPatch patch, int depth, Validation budget) {
		Map<DataComponentType<?>, Object> defaults = new IdentityHashMap<>(), effective = new IdentityHashMap<>();
		collect(prototype, defaults, depth, budget);
		budget.cells(defaults.size()); effective.putAll(defaults);
		// When restoring onto an existing native map, keep removals introduced by
		// its hook as well as removals in the transmitted patch. Otherwise clearing
		// that map would silently resurrect a default from its root prototype.
		DataComponentMap root = prototype;
		while (root.getClass() == PatchedDataComponentMap.class) { budget.visit(++depth); root = RAW.prototype((PatchedDataComponentMap) root); }
		if (prototype != root) collect(root, defaults, depth, budget);
		ClientItemDisplay.admitComponentMap(RAW.patch(patch), budget);
		for (var entry : RAW.patch(patch).entrySet()) {
			budget.visit(depth);
			if (entry.getValue().isPresent()) effective.put(entry.getKey(), entry.getValue().get()); else effective.remove(entry.getKey());
		}
		budget.cells(effective.size() + defaults.size());
		DataComponentPatch.Builder owned = DataComponentPatch.builder();
		for (var entry : effective.entrySet()) { budget.visit(depth); put(owned, entry.getKey(), value(entry.getKey(), entry.getValue(), depth, budget)); }
		for (var type : defaults.keySet()) if (!effective.containsKey(type)) { budget.visit(depth); owned.remove(type); }
		return owned.build(); // type-identity keys only; no value hash/equals.
	}

	static void detach(ItemStack stack, Validation budget) {
		if (stack.getComponents().getClass() != PatchedDataComponentMap.class) throw new UnsafeDisplay();
		DataComponentPatch owned = overlay(stack.getComponents(), DataComponentPatch.EMPTY, 0, budget);
		budget.cells(RAW.patch(owned).size());
		((PatchedDataComponentMap) stack.getComponents()).restorePatch(owned);
	}

	private static void collect(DataComponentMap source, Map<DataComponentType<?>, Object> result, int depth, Validation budget) {
		budget.visit(depth);
		if (source == DataComponentMap.EMPTY) return;
		if (source.getClass() == PatchedDataComponentMap.class) {
			var map = (PatchedDataComponentMap) source;
			collect(RAW.prototype(map), result, depth + 1, budget);
			ClientItemDisplay.admitComponentMap(RAW.patch(map), budget);
			for (var entry : RAW.patch(map).entrySet()) {
				budget.visit(depth);
				if (entry.getValue().isPresent()) result.put(entry.getKey(), entry.getValue().get()); else result.remove(entry.getKey());
			}
		} else {
			if (source.getClass() != SIMPLE) throw new UnsafeDisplay();
			ClientItemDisplay.admitComponentMap(RAW.simple(source), budget);
			for (var entry : RAW.simple(source).entrySet()) { budget.visit(depth); result.put(entry.getKey(), entry.getValue()); }
		}
	}

	private static ItemStack stack(ItemStack source, int depth, Validation budget) {
		budget.visit(depth);
		if (source.isEmpty()) return ItemStack.EMPTY;
		if (!ClientItemDisplay.supportedComponents(source.getComponents(), depth, budget)) throw new UnsafeDisplay();
		DataComponentPatch patch = overlay(source.getComponents(), DataComponentPatch.EMPTY, depth, budget);
		budget.visit(depth);
		// The exact native empty constructor initializes only fields, with no
		// item verification. Populate ONLY the new object, not EMPTY or a source
		// stack. Nested copies must not repeat PROFILE/load callbacks.
		ItemStack result = construct(EMPTY_STACK, (Object) null);
		set(STACK_ITEM, result, source.getItem());
		PatchedDataComponentMap components = new PatchedDataComponentMap(DataComponentMap.EMPTY);
		budget.cells(RAW.patch(patch).size()); components.restorePatch(patch);
		set(STACK_COMPONENTS, result, components);
		result.setCount(source.getCount());
		return result;
	}

	private static Object value(DataComponentType<?> type, Object value, int depth, Validation budget) {
		budget.visit(depth);
		if (type == DataComponents.CUSTOM_NAME || type == DataComponents.ITEM_NAME || type == DataComponents.LORE
			|| type == DataComponents.WRITABLE_BOOK_CONTENT || type == DataComponents.WRITTEN_BOOK_CONTENT)
			return ClientDisplayItemCopy.value(type, value, depth, budget);
		if (type == DataComponents.CONTAINER) {
			exact(value, ItemContainerContents.class);
			List<ItemStack> items = items(RAW.container((ItemContainerContents) value), depth, budget);
			// The size constructor hashes EMPTY slots only. Preserve the native
			// cached hash scalar, then fill owned slots without recursively hashing
			// components, invoking item hooks or comparing resident values.
			budget.cells(items.size());
			ItemContainerContents result = construct(CONTAINER, items.size());
			List<ItemStack> slots = RAW.container(result);
			for (int i = 0; i < items.size(); i++) { budget.visit(depth); slots.set(i, items.get(i)); }
			set(CONTAINER_HASH, result, get(CONTAINER_HASH, value));
			return result;
		}
		if (type == DataComponents.BUNDLE_CONTENTS) {
			exact(value, BundleContents.class); BundleContents bundle = (BundleContents) value;
			return construct(BUNDLE, items(RAW.bundle(bundle), depth, budget), bundle.weight());
		}
		if (type == DataComponents.CHARGED_PROJECTILES) {
			exact(value, ChargedProjectiles.class); return construct(PROJECTILES, items(RAW.projectiles((ChargedProjectiles) value), depth, budget));
		}
		if (type == DataComponents.FOOD) {
			exact(value, FoodProperties.class); FoodProperties food = (FoodProperties) value;
			List<FoodProperties.PossibleEffect> effects = new ArrayList<>();
			for (Object object : ClientItemDisplay.admittedList(food.effects(), budget)) {
				budget.visit(depth); exact(object, FoodProperties.PossibleEffect.class);
				var effect = (FoodProperties.PossibleEffect) object;
				MobEffectInstance resident = ClientDisplayFoodAccess.resident(effect, depth, budget);
				effects.add(ClientDisplayFoodAccess.wrap(effect(resident, depth + 1, budget), effect.probability(), depth, budget));
			}
			return new FoodProperties(food.nutrition(), food.saturation(), food.canAlwaysEat(), food.eatSeconds(),
				food.usingConvertsTo().map(child -> stack(child, depth + 1, budget)), effects);
		}
		if (type == DataComponents.PROFILE) return profile(value, depth, budget);
		if (type == DataComponents.CUSTOM_DATA || type == DataComponents.ENTITY_DATA || type == DataComponents.BLOCK_ENTITY_DATA || type == DataComponents.BUCKET_ENTITY_DATA) {
			exact(value, CustomData.class); return construct(CUSTOM_DATA, tag(((CustomData) value).getUnsafe(), depth + 1, budget));
		}
		if (type == DataComponents.POTION_CONTENTS) {
			exact(value, PotionContents.class); PotionContents potion = (PotionContents) value;
			potion.potion().ifPresent(holder -> exact(holder, Holder.Reference.class));
			List<MobEffectInstance> effects = new ArrayList<>();
			for (Object child : ClientItemDisplay.admittedList(RAW.potion(potion), budget)) effects.add(effect((MobEffectInstance) child, depth + 1, budget));
			return new PotionContents(potion.potion(), potion.customColor(), effects);
		}
		if (type == DataComponents.ENCHANTMENTS || type == DataComponents.STORED_ENCHANTMENTS) {
			exact(value, ItemEnchantments.class); ItemEnchantments source = (ItemEnchantments) value;
			var map = RAW.enchantments(source); exact(map, it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap.class);
			budget.cells(RAW.objectIntCapacity(map)); budget.cells(map.size());
			for (var entry : map.object2IntEntrySet()) { budget.visit(depth); exact(entry.getKey(), Holder.Reference.class); }
			// Native clone duplicates its primitive/reference arrays without key
			// hash/equals callbacks. Registry holders remain stable registry leaves.
			return construct(ENCHANTMENTS, map.clone(), RAW.enchantmentTooltip(source));
		}
		if (type == DataComponents.ATTRIBUTE_MODIFIERS) {
			exact(value, ItemAttributeModifiers.class); var modifiers = (ItemAttributeModifiers) value;
			var entries = leaves(modifiers.modifiers(), ItemAttributeModifiers.Entry.class, budget);
			for (var entry : entries) {
				budget.visit(depth); exact(entry.attribute(), Holder.Reference.class);
				exact(entry.modifier(), net.minecraft.world.entity.ai.attributes.AttributeModifier.class);
			}
			return new ItemAttributeModifiers(entries, modifiers.showInTooltip());
		}
		if (type == DataComponents.TOOL) {
			exact(value, Tool.class); Tool tool = (Tool) value; List<Tool.Rule> rules = new ArrayList<>();
			for (Object object : ClientItemDisplay.admittedList(tool.rules(), budget)) {
				budget.visit(depth); exact(object, Tool.Rule.class); Tool.Rule rule = (Tool.Rule) object;
				rules.add(new Tool.Rule(holders(rule.blocks(), budget), rule.speed(), rule.correctForDrops()));
			}
			return new Tool(rules, tool.defaultMiningSpeed(), tool.damagePerBlock());
		}
		if (type == DataComponents.BLOCK_STATE) {
			exact(value, BlockItemStateProperties.class); return new BlockItemStateProperties(strings(((BlockItemStateProperties) value).properties(), budget));
		}
		if (type == DataComponents.DEBUG_STICK_STATE) {
			exact(value, DebugStickState.class); var source = ((DebugStickState) value).properties(); map(source, budget);
			Map<Holder<net.minecraft.world.level.block.Block>, net.minecraft.world.level.block.state.properties.Property<?>> result = new IdentityHashMap<>();
			if (!source.isEmpty()) for (var entry : source.entrySet()) {
				budget.visit(depth); exact(entry.getKey(), Holder.Reference.class);
				// Block properties are registry-owned descriptors, not item-owned state.
				result.put(entry.getKey(), entry.getValue());
			}
			return new DebugStickState(result);
		}
		if (type == DataComponents.CAN_BREAK || type == DataComponents.CAN_PLACE_ON) return adventure(value, depth, budget);
		if (type == DataComponents.MAP_DECORATIONS) {
			exact(value, MapDecorations.class); Map<String, MapDecorations.Entry> source = ((MapDecorations) value).decorations();
			map(source, budget); Map<String, MapDecorations.Entry> result = new java.util.LinkedHashMap<>();
			if (!source.isEmpty()) for (var entry : source.entrySet()) {
				budget.characters(entry.getKey()); exact(entry.getValue(), MapDecorations.Entry.class); exact(entry.getValue().type(), Holder.Reference.class);
				result.put(entry.getKey(), entry.getValue());
			}
			return new MapDecorations(result);
		}
		if (type == DataComponents.RECIPES) {
			List<Object> result = new ArrayList<>();
			for (Object child : ClientItemDisplay.admittedList((List<?>) value, budget)) { budget.visit(depth); exact(child, net.minecraft.resources.ResourceLocation.class); result.add(child); }
			return result;
		}
		if (type == DataComponents.BANNER_PATTERNS) {
			exact(value, net.minecraft.world.level.block.entity.BannerPatternLayers.class);
			var layers = leaves(((net.minecraft.world.level.block.entity.BannerPatternLayers) value).layers(), net.minecraft.world.level.block.entity.BannerPatternLayers.Layer.class, budget);
			for (var layer : layers) { budget.visit(depth); exact(layer.pattern(), Holder.Reference.class); }
			return new net.minecraft.world.level.block.entity.BannerPatternLayers(layers);
		}
		if (type == DataComponents.SUSPICIOUS_STEW_EFFECTS) {
			exact(value, SuspiciousStewEffects.class); var effects = leaves(((SuspiciousStewEffects) value).effects(), SuspiciousStewEffects.Entry.class, budget);
			for (var effect : effects) { budget.visit(depth); exact(effect.effect(), Holder.Reference.class); }
			return new SuspiciousStewEffects(effects);
		}
		if (type == DataComponents.BEES) {
			List<net.minecraft.world.level.block.entity.BeehiveBlockEntity.Occupant> bees = new ArrayList<>();
			for (Object object : ClientItemDisplay.admittedList((List<?>) value, budget)) {
				budget.visit(depth); exact(object, net.minecraft.world.level.block.entity.BeehiveBlockEntity.Occupant.class);
				var bee = (net.minecraft.world.level.block.entity.BeehiveBlockEntity.Occupant) object;
				bees.add(new net.minecraft.world.level.block.entity.BeehiveBlockEntity.Occupant((CustomData) value(DataComponents.CUSTOM_DATA, bee.entityData(), depth + 1, budget), bee.ticksInHive(), bee.minTicksInHive()));
			}
			return bees;
		}
		if (type == DataComponents.LODESTONE_TRACKER) {
			exact(value, LodestoneTracker.class); var tracker = (LodestoneTracker) value;
			return new LodestoneTracker(tracker.target().map(pos -> {
				Class<?> block = pos.pos().getClass();
				if (block != net.minecraft.core.BlockPos.class && block != net.minecraft.core.BlockPos.MutableBlockPos.class) throw new UnsafeDisplay();
				return net.minecraft.core.GlobalPos.of(pos.dimension(), pos.pos().immutable());
			}), tracker.tracked());
		}
		if (type == DataComponents.FIREWORK_EXPLOSION) return explosion(value, budget);
		if (type == DataComponents.FIREWORKS) {
			exact(value, Fireworks.class); Fireworks source = (Fireworks) value; List<FireworkExplosion> result = new ArrayList<>();
			for (Object child : ClientItemDisplay.admittedList(source.explosions(), budget)) result.add(explosion(child, budget));
			return new Fireworks(source.flightDuration(), result);
		}
		if (type == DataComponents.TRIM) {
			exact(value, net.minecraft.world.item.armortrim.ArmorTrim.class); var trim = (net.minecraft.world.item.armortrim.ArmorTrim) value;
			exact(trim.material(), Holder.Reference.class); exact(trim.pattern(), Holder.Reference.class);
			// withTooltip preserves the flag but shares native memoized texture
			// maps. A fresh native owner must not retain those resident caches.
			return new net.minecraft.world.item.armortrim.ArmorTrim(trim.material(), trim.pattern(), trimTooltip(trim));
		}
		if (type == DataComponents.JUKEBOX_PLAYABLE) {
			exact(value, net.minecraft.world.item.JukeboxPlayable.class); var song = (net.minecraft.world.item.JukeboxPlayable) value;
			exact(song.song(), net.minecraft.world.item.EitherHolder.class);
			song.song().holder().ifPresent(holder -> exact(holder, Holder.Reference.class));
			return value; // Immutable record; only a registry reference or key below it.
		}
		if (type == DataComponents.POT_DECORATIONS) {
			exact(value, net.minecraft.world.level.block.entity.PotDecorations.class); var pot = (net.minecraft.world.level.block.entity.PotDecorations) value;
			for (var holder : List.of(pot.back(), pot.left(), pot.right(), pot.front())) holder.ifPresent(child -> exact(child, Holder.Reference.class));
			return value;
		}
		// Exact scalar/registry descriptors have no producer-mutable children.
		Class<?> shape = value.getClass();
		if (shape == Integer.class || shape == Boolean.class || shape == net.minecraft.util.Unit.class || shape == net.minecraft.world.item.Rarity.class
			|| shape == net.minecraft.world.item.DyeColor.class || shape == CustomModelData.class || shape == Unbreakable.class || shape == DyedItemColor.class
			|| shape == MapItemColor.class || shape == net.minecraft.world.level.saveddata.maps.MapId.class || shape == net.minecraft.resources.ResourceLocation.class
			|| shape == net.minecraft.world.LockCode.class || shape == SeededContainerLoot.class
			|| (type == DataComponents.INSTRUMENT && shape == Holder.Reference.class)) return value;
		throw new UnsafeDisplay();
	}

	private static List<ItemStack> items(List<ItemStack> source, int depth, Validation budget) {
		List<ItemStack> result = new ArrayList<>();
		for (Object child : ClientItemDisplay.admittedList(source, budget)) result.add(stack((ItemStack) child, depth + 1, budget));
		return result;
	}
	private static <T> List<T> leaves(List<T> source, Class<T> type, Validation budget) {
		List<T> result = new ArrayList<>();
		for (Object child : ClientItemDisplay.admittedList(source, budget)) { budget.visit(0); exact(child, type); result.add(type.cast(child)); }
		return result;
	}
	private static <T> HolderSet<T> holders(HolderSet<T> source, Validation budget) {
		budget.visit(0);
		if (source.getClass() == HolderSet.Named.class) return source; // Registry-owned live tag membership, not copied item storage.
		if (source.getClass() != HolderSet.Direct.class) throw new UnsafeDisplay();
		List<Holder<T>> values = new ArrayList<>();
		for (Object holder : ClientItemDisplay.admittedList(RAW.directContents((HolderSet.Direct<?>) source), budget)) {
			budget.visit(0);
			if (holder.getClass() != Holder.Reference.class) throw new UnsafeDisplay();
			@SuppressWarnings("unchecked") Holder<T> cast = (Holder<T>) holder; values.add(cast);
		}
		budget.cells(values.size()); return HolderSet.direct(values);
	}
	private static MobEffectInstance effect(MobEffectInstance source, int depth, Validation budget) {
		budget.visit(depth); exact(source, MobEffectInstance.class);
		exact(source.getEffect(), Holder.Reference.class);
		if (HIDDEN_EFFECT == null) throw new UnsafeDisplay();
		EffectCuresCopy.admit(source, budget);
		MobEffectInstance hidden = RAW.hidden(source), ownedHidden = hidden == null ? null : effect(hidden, depth + 1, budget);
		// Native copy preserves NeoForge's explicit cures without holder.value()
		// or fillEffectCures. Its BlendState is fresh and hiddenEffect is null on
		// every loader. Only the NEW owner receives the recursively owned chain.
		MobEffectInstance result = new MobEffectInstance(source);
		set(HIDDEN_EFFECT, result, ownedHidden);
		return result;
	}

	/** Exact NeoForge/JDK representation only; not a general collection accessor. */
	private static final class EffectCuresCopy {
		private static final boolean PRESENT = java.util.Arrays.stream(MobEffectInstance.class.getDeclaredFields())
			.anyMatch(field -> !Modifier.isStatic(field.getModifiers()) && field.getType() == Set.class);
		private static final Field CURES = ClientItemDisplay.nativeField(MobEffectInstance.class, Set.class);
		private record Shape(sun.misc.Unsafe read, Class<?> set, Class<?> keys, Class<?> cure, Field name,
			long map, long view, long owner, long table) {}
		private static final Shape SHAPE = PRESENT ? shape() : null;

		private static Shape shape() {
			if (!InventoryNativeTraversal.runtimeSupported() || CURES == null) return null;
			try {
				var map = new IdentityHashMap<Object, Boolean>(); Class<?> set = Collections.newSetFromMap(map).getClass(), keys = map.keySet().getClass();
				Class<?> cure = Class.forName("net.neoforged.neoforge.common.EffectCure", false, MobEffectInstance.class.getClassLoader());
				if (!Modifier.isFinal(cure.getModifiers())) return null;
				Field name = ClientItemDisplay.nativeField(cure, String.class);
				if (name == null || !Modifier.isFinal(name.getModifiers())) return null;
				for (Field field : cure.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()) && !field.equals(name)) return null;
				// java.base does not expose these backing fields. Cache read-only
				// offsets once; no opens, arbitrary offsets, allocation or writes.
				Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
				if (!singleton.trySetAccessible()) return null;
				sun.misc.Unsafe read = (sun.misc.Unsafe) singleton.get(null);
				return new Shape(read, set, keys, cure, name, offset(read, set, Map.class), offset(read, set, Set.class),
					offset(read, keys, IdentityHashMap.class), offset(read, IdentityHashMap.class, Object[].class));
			} catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
		}
		private static long offset(sun.misc.Unsafe read, Class<?> owner, Class<?> type) throws NoSuchFieldException {
			Field result = null;
			for (Field field : owner.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
				if (result != null) throw new NoSuchFieldException(); result = field;
			}
			if (result == null) throw new NoSuchFieldException();
			return read.objectFieldOffset(result);
		}
		static void admit(MobEffectInstance source, Validation budget) {
			// Vanilla: effect + fresh BlendState, fields/headers and the owned-only
			// hidden field install. No resident method except exact native getters.
			budget.cells(16);
			if (!PRESENT) return;
			Shape shape = SHAPE; if (shape == null) throw new UnsafeDisplay();
			budget.cells(32); // Fixed reads, six native owners/iterator and scalar/reference fields.
			Object set = get(CURES, source); exact(set, shape.set);
			Object backing = shape.read.getObject(set, shape.map); exact(backing, IdentityHashMap.class);
			Object view = shape.read.getObject(set, shape.view); exact(view, shape.keys);
			if (shape.read.getObject(view, shape.owner) != backing) throw new UnsafeDisplay();
			Object raw = shape.read.getObject(backing, shape.table); exact(raw, Object[].class);
			Object[] table = (Object[]) raw; int length = table.length, size = ((IdentityHashMap<?, ?>) backing).size();
			if (length < 8 || (length & (length - 1)) != 0 || size < 0 || 3L * size > length) throw new UnsafeDisplay();
			long capacity = 64, growth = 64;
			while (3L * size > capacity) { capacity *= 2; growth += capacity; }
			// BEFORE scanning or native addAll/iterator/allocations:
			// two source-table passes (guard + copy, even retained-empty tables),
			// all destination tables/clear/resize scans, array headers, and a
			// quadratic identity-probe allowance for inserts plus all rehashes.
			long cost = 2L * length + 2 * growth + 2L * size * (size + 1L);
			if (cost > budget.remainingVisits()) throw new UnsafeDisplay();
			budget.cells((int) cost);
			int entries = 0;
			for (int i = 0; i < length; i += 2) {
				Object cure = table[i];
				if (cure == null) { if (table[i + 1] != null) throw new UnsafeDisplay(); continue; }
				exact(cure, shape.cure); if (table[i + 1] != Boolean.TRUE) throw new UnsafeDisplay();
				budget.characters((String) get(shape.name, cure)); entries++;
			}
			if (entries != size) throw new UnsafeDisplay();
		}
	}
	private static ResolvableProfile profile(Object value, int depth, Validation budget) {
		budget.visit(depth); exact(value, ResolvableProfile.class); ResolvableProfile source = (ResolvableProfile) value;
		source.name().ifPresent(budget::characters);
		GameProfile nativeProfile = source.gameProfile(); exact(nativeProfile, GameProfile.class);
		if (nativeProfile.getName() != null) budget.characters(nativeProfile.getName());
		GameProfile game = new GameProfile(nativeProfile.getId(), nativeProfile.getName());
		PropertyMap props = properties(source.properties(), budget), gameProps = properties(nativeProfile.getProperties(), budget);
		budget.cells(gameProps.size()); game.getProperties().putAll(gameProps);
		return new ResolvableProfile(source.name(), source.id(), props, game);
	}
	private static PropertyMap properties(PropertyMap source, Validation budget) {
		exact(source, PropertyMap.class); var delegate = RAW.profile(source); exact(delegate, PROFILE_MAP);
		budget.cells(delegate.size());
		// Native linked multimap insertion hashes only exact strings/properties;
		// reserve worst-case collisions before constructing any target entry.
		budget.cells(Math.multiplyExact(delegate.size(), delegate.size()));
		PropertyMap result = new PropertyMap();
		for (var entry : delegate.entries()) {
			budget.visit(0); budget.characters(entry.getKey()); exact(entry.getValue(), Property.class); Property property = entry.getValue();
			budget.characters(property.name()); budget.characters(property.value()); if (property.signature() != null) budget.characters(property.signature());
			result.put(entry.getKey(), new Property(property.name(), property.value(), property.signature()));
		}
		return result;
	}
	private static AdventureModePredicate adventure(Object value, int depth, Validation budget) {
		exact(value, AdventureModePredicate.class); AdventureModePredicate source = (AdventureModePredicate) value;
		List<BlockPredicate> predicates = new ArrayList<>();
		for (Object object : ClientItemDisplay.admittedList(RAW.predicates(source), budget)) {
			budget.visit(depth); exact(object, BlockPredicate.class); BlockPredicate predicate = (BlockPredicate) object;
			Optional<StatePropertiesPredicate> properties = predicate.properties().map(state -> {
				exact(state, StatePropertiesPredicate.class);
				List<StatePropertiesPredicate.PropertyMatcher> matches = new ArrayList<>();
				for (Object matchObject : ClientItemDisplay.admittedList(state.properties(), budget)) {
					budget.visit(depth); exact(matchObject, StatePropertiesPredicate.PropertyMatcher.class);
					var match = (StatePropertiesPredicate.PropertyMatcher) matchObject; budget.characters(match.name());
					if (match.valueMatcher().getClass() == StatePropertiesPredicate.ExactMatcher.class) budget.characters(((StatePropertiesPredicate.ExactMatcher) match.valueMatcher()).value());
					else if (match.valueMatcher().getClass() == StatePropertiesPredicate.RangedMatcher.class) {
						var range = (StatePropertiesPredicate.RangedMatcher) match.valueMatcher(); range.minValue().ifPresent(budget::characters); range.maxValue().ifPresent(budget::characters);
					} else throw new UnsafeDisplay();
					matches.add(match); // Exact scalar-only native match records.
				}
				return new StatePropertiesPredicate(matches);
			});
			Optional<NbtPredicate> nbt = predicate.nbt().map(child -> {
				exact(child, NbtPredicate.class); return new NbtPredicate((CompoundTag) tag(child.tag(), depth + 1, budget));
			});
			predicates.add(new BlockPredicate(predicate.blocks().map(set -> holders(set, budget)), properties, nbt));
		}
		Object stored = get(ADVENTURE_TOOLTIP, source);
		if (!(stored instanceof List<?> list)) throw new UnsafeDisplay();
		List<Component> tooltip = new ArrayList<>();
		for (Object child : ClientItemDisplay.admittedList(list, budget)) tooltip.add(ClientDisplayText.snapshot((Component) child, depth + 1, budget));
		// No computeTooltip/getName/stream.distinct callbacks or lastCheckedBlock
		// world reference. Preserve the checked native tooltip, independently owned.
		return construct(ADVENTURE, predicates, source.showInTooltip(), tooltip);
	}
	private static final Field TRIM_TOOLTIP = ClientItemDisplay.nativeField(net.minecraft.world.item.armortrim.ArmorTrim.class, boolean.class);
	private static boolean trimTooltip(net.minecraft.world.item.armortrim.ArmorTrim source) { return (Boolean) get(TRIM_TOOLTIP, source); }
	private static Field tooltipField() {
		try {
			Field result = null;
			for (Field field : AdventureModePredicate.class.getDeclaredFields()) {
				if (field.getType() == List.class && field.getGenericType() instanceof java.lang.reflect.ParameterizedType signature
					&& java.util.Arrays.equals(signature.getActualTypeArguments(), new java.lang.reflect.Type[] {Component.class})) {
					if (result != null || !field.trySetAccessible()) return null; result = field;
				}
			}
			return result;
		} catch (RuntimeException unavailable) { return null; }
	}
	private static FireworkExplosion explosion(Object value, Validation budget) {
		budget.visit(0); exact(value, FireworkExplosion.class); FireworkExplosion source = (FireworkExplosion) value;
		return new FireworkExplosion(source.shape(), ints(source.colors(), budget), ints(source.fadeColors(), budget), source.hasTrail(), source.hasTwinkle());
	}
	private static it.unimi.dsi.fastutil.ints.IntList ints(it.unimi.dsi.fastutil.ints.IntList source, Validation budget) {
		Class<?> type = source.getClass();
		if (type != it.unimi.dsi.fastutil.ints.IntArrayList.class && type != it.unimi.dsi.fastutil.ints.IntList.of().getClass()
			&& type != it.unimi.dsi.fastutil.ints.IntList.of(1).getClass()) throw new UnsafeDisplay();
		budget.cells(source.size()); var result = new it.unimi.dsi.fastutil.ints.IntArrayList(source.size());
		for (int i = 0; i < source.size(); i++) result.add(source.getInt(i));
		return result;
	}
	private static Map<String, String> strings(Map<String, String> source, Validation budget) {
		map(source, budget); Map<String, String> result = new java.util.LinkedHashMap<>();
		if (!source.isEmpty()) for (var entry : source.entrySet()) { budget.characters(entry.getKey()); budget.characters(entry.getValue()); result.put(entry.getKey(), entry.getValue()); }
		return result;
	}
	private static void map(Map<?, ?> source, Validation budget) {
		if (source.getClass() == Map.of().getClass() || source.getClass() == Map.of(1, 1).getClass() || source.getClass() == com.google.common.collect.ImmutableMap.of().getClass()
			|| source.getClass() == com.google.common.collect.ImmutableMap.of(1, 1).getClass()) budget.cells(source.size());
		else {
			budget.cells(InventoryNativeTraversal.MAX_INSPECTION_PORT_CALLS);
			var inspection = InventoryNativeTraversal.inspect(source, budget.remainingVisits());
			if (!inspection.accepted()) throw new UnsafeDisplay(); budget.cells((int) inspection.traversalWork());
		}
		// Hash-map creation/comparisons are bounded before insertion; no arbitrary
		// keys are accepted by callers. Conservative collision allowance.
		budget.cells(Math.multiplyExact(source.size(), source.size()));
	}
	private static Tag tag(Tag source, int depth, Validation budget) {
		budget.visit(depth); Class<?> type = source.getClass();
		if (type == StringTag.class) { budget.characters(source.getAsString()); return source; }
		if (type == ByteTag.class || type == ShortTag.class || type == IntTag.class || type == LongTag.class || type == FloatTag.class || type == DoubleTag.class) return source;
		if (type == ByteArrayTag.class) { var array = ((ByteArrayTag) source).getAsByteArray(); budget.cells(array.length); return new ByteArrayTag(array.clone()); }
		if (type == IntArrayTag.class) { var array = ((IntArrayTag) source).getAsIntArray(); budget.cells(array.length); return new IntArrayTag(array.clone()); }
		if (type == LongArrayTag.class) { var array = ((LongArrayTag) source).getAsLongArray(); budget.cells(array.length); return new LongArrayTag(array.clone()); }
		if (type == CompoundTag.class) {
			Map<String, Tag> entries = RAW.compound((CompoundTag) source); map(entries, budget); CompoundTag result = new CompoundTag();
			if (!entries.isEmpty()) for (var entry : entries.entrySet()) { budget.characters(entry.getKey()); result.put(entry.getKey(), tag(entry.getValue(), depth + 1, budget)); }
			return result;
		}
		if (type == ListTag.class) {
			List<?> entries = ClientItemDisplay.admittedList(RAW.list((ListTag) source), budget);
			if (entries.isEmpty()) return source.copy(); // Exact native empty list; preserves its type scalar, no children.
			ListTag result = new ListTag(); for (Object child : entries) result.add(tag((Tag) child, depth + 1, budget)); return result;
		}
		throw new UnsafeDisplay();
	}
	private static void exact(Object value, Class<?> type) { if (value == null || value.getClass() != type) throw new UnsafeDisplay(); }
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void put(DataComponentPatch.Builder patch, DataComponentType<?> type, Object value) { patch.set((DataComponentType) type, value); }
	private static <T> Constructor<T> constructor(Class<T> owner, Class<?>... parameters) {
		try { var result = owner.getDeclaredConstructor(parameters); return result.trySetAccessible() ? result : null; }
		catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
	}
	private static <T> T construct(Constructor<T> constructor, Object... args) {
		if (constructor == null) throw new UnsafeDisplay();
		try { return constructor.newInstance(args); }
		catch (ReflectiveOperationException unavailable) { throw new UnsafeDisplay(unavailable); }
	}
	private static Object get(Field field, Object source) {
		if (field == null) throw new UnsafeDisplay();
		try { return field.get(source); } catch (IllegalAccessException unavailable) { throw new UnsafeDisplay(unavailable); }
	}
	private static void set(Field field, Object target, Object value) {
		if (field == null) throw new UnsafeDisplay();
		try { field.set(target, value); } catch (IllegalAccessException unavailable) { throw new UnsafeDisplay(unavailable); }
	}
}
