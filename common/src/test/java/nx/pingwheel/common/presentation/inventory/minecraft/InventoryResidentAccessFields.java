package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.advancements.critereon.BlockPredicate;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.AdventureModePredicate;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemContainerContents;

/** Headless injection of the SAME fields as production accessors, never combined-map views. */
public class InventoryResidentAccessFields implements InventoryResidentAccess {
	public InventoryResidentAccessFields() {}
	@Override public int styleFlags(net.minecraft.network.chat.Style style) {
		return (field(style, "bold") == null ? 0 : 1) | (field(style, "italic") == null ? 0 : 2)
			| (field(style, "underlined") == null ? 0 : 4) | (field(style, "strikethrough") == null ? 0 : 8)
			| (field(style, "obfuscated") == null ? 0 : 16);
	}
	@Override public net.minecraft.resources.ResourceLocation styleFont(net.minecraft.network.chat.Style style) { return field(style, "font"); }
	@Override public String colorName(net.minecraft.network.chat.TextColor color) { return field(color, "name"); }
	@Override public Map<?, ?> builderEntries(net.minecraft.core.HolderLookup.Provider provider) { return fieldAny(provider, "val$map", "val$lookups"); }
	@Override public net.minecraft.resources.RegistryOps.RegistryInfo<?> builderInfo(Object entry) { return field(entry, "opsInfo"); }
	@Override public net.minecraft.resources.ResourceKey<?> builderKey(Object lookup) { return fieldAny(lookup, "val$registryKey", "val$key", "val$p_311196_"); }
	@Override public com.mojang.serialization.Lifecycle builderLifecycle(Object lookup) { return fieldAny(lookup, "val$registryLifecycle", "val$lifecycle", "val$p_311352_"); }
	@Override public Map<?, ?> builderElements(Object lookup) { return fieldAny(lookup, "val$elements", "val$entries", "val$p_311458_"); }
	@Override public net.minecraft.core.HolderOwner<?> builderOwner(Object lookup) { return fieldAt(lookup, lookup.getClass().getSuperclass().getSuperclass(), "owner"); }
	@Override public net.minecraft.core.MappedRegistry<?> lookupRegistry(Object lookup) { return fieldAny(lookup, "this$0", "field_36468"); }
	@Override public net.minecraft.core.Registry<?> registryDelegate(net.minecraft.core.HolderLookup.Provider provider) {
		for (String name : new String[] {"val$registryOfRegistries", "val$registries", "val$p_206166_"}) {
			try { provider.getClass().getDeclaredField(name); return field(provider, name); }
			catch (NoSuchFieldException absent) { /* next resolved loader spelling */ }
		}
		throw new AssertionError("native registry delegate field drift");
	}
	@Override public Map<? extends net.minecraft.resources.ResourceKey<?>, ? extends net.minecraft.core.Registry<?>> registries(
		net.minecraft.core.RegistryAccess.ImmutableRegistryAccess access) { return fieldAt(access, net.minecraft.core.RegistryAccess.ImmutableRegistryAccess.class, "registries"); }
	@Override public net.minecraft.resources.ResourceKey<?> registryKey(net.minecraft.core.MappedRegistry<?> registry) { return fieldAt(registry, net.minecraft.core.MappedRegistry.class, "key"); }
	@Override public net.minecraft.core.HolderLookup.RegistryLookup<?> registryLookup(net.minecraft.core.MappedRegistry<?> registry) { return fieldAt(registry, net.minecraft.core.MappedRegistry.class, "lookup"); }
	@Override public com.mojang.serialization.Lifecycle registryLifecycle(net.minecraft.core.MappedRegistry<?> registry) { return fieldAt(registry, net.minecraft.core.MappedRegistry.class, "registryLifecycle"); }
	@Override public Map<net.minecraft.resources.ResourceKey<?>, net.minecraft.core.Holder.Reference<?>> registryEntries(net.minecraft.core.MappedRegistry<?> registry) { return fieldAt(registry, net.minecraft.core.MappedRegistry.class, "byKey"); }
	@Override public Map<?, ?> registryValues(net.minecraft.core.MappedRegistry<?> registry) { return fieldAt(registry, net.minecraft.core.MappedRegistry.class, "byValue"); }
	@Override public Map<?, ?> registrationInfos(net.minecraft.core.MappedRegistry<?> registry) { return fieldAt(registry, net.minecraft.core.MappedRegistry.class, "registrationInfos"); }
	@Override public net.minecraft.core.HolderOwner<?> holderOwner(net.minecraft.core.Holder.Reference<?> holder) { return field(holder, "owner"); }
	@Override public net.minecraft.resources.ResourceKey<?> holderKey(net.minecraft.core.Holder.Reference<?> holder) { return field(holder, "key"); }
	@Override public Object holderValue(net.minecraft.core.Holder.Reference<?> holder) { return field(holder, "value"); }
	@Override public net.minecraft.core.HolderOwner<?> namedOwner(net.minecraft.core.HolderSet.Named<?> set) { return fieldAt(set, net.minecraft.core.HolderSet.Named.class, "owner"); }
	@Override public net.minecraft.tags.TagKey<?> namedKey(net.minecraft.core.HolderSet.Named<?> set) { return fieldAt(set, net.minecraft.core.HolderSet.Named.class, "key"); }
	@Override public List<net.minecraft.core.Holder<?>> directContents(net.minecraft.core.HolderSet.Direct<?> set) { return field(set, "contents"); }
	@Override public boolean enchantmentTooltip(net.minecraft.world.item.enchantment.ItemEnchantments value) { return field(value, "showInTooltip"); }
	@Override public DataComponentMap prototype(PatchedDataComponentMap map) { return field(map, "prototype"); }
	@Override public Map<DataComponentType<?>, Optional<?>> patch(PatchedDataComponentMap map) { return field(map, "patch"); }
	@Override public Map<DataComponentType<?>, Optional<?>> patch(DataComponentPatch patch) { return field(patch, "map"); }
	@Override public Map<DataComponentType<?>, Object> simple(DataComponentMap map) { return field(map, "map"); }
	@Override public DataComponentMap itemComponents(Item item) { return fieldAt(item, Item.class, "components"); }
	@Override public int referenceMapCapacity(Map<?, ?> map) { return field(map, "n"); }
	@Override public List<?> nonNullList(NonNullList<?> list) { return field(list, "list"); }
	@Override public Map<String, Tag> compound(CompoundTag tag) { return field(tag, "tags"); }
	@Override public List<Tag> list(ListTag tag) { return field(tag, "list"); }
	@Override public List<ItemStack> container(ItemContainerContents contents) { return field(contents, "items"); }
	@Override public List<ItemStack> projectiles(ChargedProjectiles projectiles) { return field(projectiles, "items"); }
	@Override public List<ItemStack> bundle(BundleContents bundle) { return field(bundle, "items"); }
	@Override public MobEffectInstance hidden(MobEffectInstance effect) { return field(effect, "hiddenEffect"); }
	@Override public List<BlockPredicate> predicates(AdventureModePredicate predicate) { return field(predicate, "predicates"); }
	@Override public List<MobEffectInstance> potion(net.minecraft.world.item.alchemy.PotionContents potion) { return field(potion, "customEffects"); }
	@Override public it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<?> enchantments(
		net.minecraft.world.item.enchantment.ItemEnchantments value) { return field(value, "enchantments"); }
	@Override public int objectIntCapacity(it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<?> map) { return field(map, "n"); }
	@Override public com.google.common.collect.Multimap<String, com.mojang.authlib.properties.Property> profile(
		com.mojang.authlib.properties.PropertyMap map) { return field(map, "properties"); }

	static <T> T field(Object owner, String name) { return fieldAt(owner, owner.getClass(), name); }
	static <T> T fieldAny(Object owner, String... names) {
		for (String name : names) {
			try { owner.getClass().getDeclaredField(name); return field(owner, name); }
			catch (NoSuchFieldException absent) { /* next mapped spelling */ }
		}
		throw new AssertionError("native captured field drift");
	}

	@SuppressWarnings("unchecked")
	static <T> T fieldAt(Object owner, Class<?> declaredOwner, String name) {
		try {
			Field field = declaredOwner.getDeclaredField(name);
			field.setAccessible(true);
			return (T) field.get(owner);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("native resident field drift: " + declaredOwner.getName() + "." + name, failure);
		}
	}
}
