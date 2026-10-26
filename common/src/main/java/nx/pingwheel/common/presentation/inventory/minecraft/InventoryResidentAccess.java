package nx.pingwheel.common.presentation.inventory.minecraft;

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

import nx.pingwheel.common.mixin.InventoryAdventurePredicatesAccessor;
import nx.pingwheel.common.mixin.InventoryBundleItemsAccessor;
import nx.pingwheel.common.mixin.InventoryComponentPatchAccessor;
import nx.pingwheel.common.mixin.InventoryCompoundTagAccessor;
import nx.pingwheel.common.mixin.InventoryContainerItemsAccessor;
import nx.pingwheel.common.mixin.InventoryHiddenEffectAccessor;
import nx.pingwheel.common.mixin.InventoryItemComponentsAccessor;
import nx.pingwheel.common.mixin.InventoryListTagAccessor;
import nx.pingwheel.common.mixin.InventoryNonNullListAccessor;
import nx.pingwheel.common.mixin.InventoryPatchedComponentsAccessor;
import nx.pingwheel.common.mixin.InventoryProjectileItemsAccessor;
import nx.pingwheel.common.mixin.InventorySimpleComponentsAccessor;
import nx.pingwheel.common.mixin.InventoryPotionEffectsAccessor;
import nx.pingwheel.common.mixin.InventoryEnchantmentsAccessor;

/**
 * Narrow raw field ports. Consumers must certify exact outer/backing shapes
 * before traversal. Minecraft fields use accessors; three fixed external-library
 * fields use cached reads independent of loader transformation. No copying
 * getters, combined component views, field discovery or synthetic flattening.
 */
public interface InventoryResidentAccess {
	default int styleFlags(net.minecraft.network.chat.Style style) {
		var fields = (nx.pingwheel.common.mixin.InventoryStyleAccessor) (Object) style;
		return (fields.pingforit$bold() == null ? 0 : 1) | (fields.pingforit$italic() == null ? 0 : 2)
			| (fields.pingforit$underlined() == null ? 0 : 4) | (fields.pingforit$strikethrough() == null ? 0 : 8)
			| (fields.pingforit$obfuscated() == null ? 0 : 16);
	}
	default net.minecraft.resources.ResourceLocation styleFont(net.minecraft.network.chat.Style style) {
		return ((nx.pingwheel.common.mixin.InventoryStyleAccessor) (Object) style).pingforit$font();
	}
	default String colorName(net.minecraft.network.chat.TextColor color) {
		return ((nx.pingwheel.common.mixin.InventoryTextColorAccessor) (Object) color).pingforit$name();
	}
	interface RegistryRoot { net.minecraft.core.Registry<?> pingforit$registryDelegate(); }
	interface BuilderProvider { Map<?, ?> pingforit$entries(); }
	interface BuilderLookup {
		net.minecraft.resources.ResourceKey<?> pingforit$key();
		com.mojang.serialization.Lifecycle pingforit$lifecycle();
		Map<?, ?> pingforit$elements();
	}
	interface MappedLookup { net.minecraft.core.MappedRegistry<?> pingforit$registry(); }
	default Map<?, ?> builderEntries(net.minecraft.core.HolderLookup.Provider provider) { return ((BuilderProvider) provider).pingforit$entries(); }
	default net.minecraft.resources.RegistryOps.RegistryInfo<?> builderInfo(Object entry) {
		return ((nx.pingwheel.common.mixin.InventoryBuilderEntryAccessor) entry).pingforit$opsInfo();
	}
	default net.minecraft.resources.ResourceKey<?> builderKey(Object lookup) { return ((BuilderLookup) lookup).pingforit$key(); }
	default com.mojang.serialization.Lifecycle builderLifecycle(Object lookup) { return ((BuilderLookup) lookup).pingforit$lifecycle(); }
	default Map<?, ?> builderElements(Object lookup) { return ((BuilderLookup) lookup).pingforit$elements(); }
	default net.minecraft.core.HolderOwner<?> builderOwner(Object lookup) { return ((nx.pingwheel.common.mixin.InventoryBuilderOwnerAccessor) lookup).pingforit$owner(); }
	default net.minecraft.core.MappedRegistry<?> lookupRegistry(Object lookup) { return ((MappedLookup) lookup).pingforit$registry(); }
	default net.minecraft.core.Registry<?> registryDelegate(net.minecraft.core.HolderLookup.Provider provider) {
		return ((RegistryRoot) provider).pingforit$registryDelegate();
	}
	default Map<? extends net.minecraft.resources.ResourceKey<?>, ? extends net.minecraft.core.Registry<?>> registries(
		net.minecraft.core.RegistryAccess.ImmutableRegistryAccess access) {
		return ((nx.pingwheel.common.mixin.InventoryRegistryAccessAccessor) access).pingforit$registries();
	}
	default net.minecraft.resources.ResourceKey<?> registryKey(net.minecraft.core.MappedRegistry<?> registry) {
		return ((nx.pingwheel.common.mixin.InventoryMappedRegistryAccessor) registry).pingforit$key();
	}
	default net.minecraft.core.HolderLookup.RegistryLookup<?> registryLookup(net.minecraft.core.MappedRegistry<?> registry) {
		return ((nx.pingwheel.common.mixin.InventoryMappedRegistryAccessor) registry).pingforit$lookup();
	}
	default com.mojang.serialization.Lifecycle registryLifecycle(net.minecraft.core.MappedRegistry<?> registry) {
		return ((nx.pingwheel.common.mixin.InventoryMappedRegistryAccessor) registry).pingforit$lifecycle();
	}
	default Map<net.minecraft.resources.ResourceKey<?>, net.minecraft.core.Holder.Reference<?>> registryEntries(net.minecraft.core.MappedRegistry<?> registry) {
		return ((nx.pingwheel.common.mixin.InventoryMappedRegistryAccessor) registry).pingforit$byKey();
	}
	default Map<?, ?> registryValues(net.minecraft.core.MappedRegistry<?> registry) {
		return ((nx.pingwheel.common.mixin.InventoryMappedRegistryAccessor) registry).pingforit$byValue();
	}
	default Map<?, ?> registrationInfos(net.minecraft.core.MappedRegistry<?> registry) {
		return ((nx.pingwheel.common.mixin.InventoryMappedRegistryAccessor) registry).pingforit$registrationInfos();
	}
	default net.minecraft.core.HolderOwner<?> holderOwner(net.minecraft.core.Holder.Reference<?> holder) {
		return ((nx.pingwheel.common.mixin.InventoryHolderReferenceAccessor) holder).pingforit$owner();
	}
	default net.minecraft.resources.ResourceKey<?> holderKey(net.minecraft.core.Holder.Reference<?> holder) {
		return ((nx.pingwheel.common.mixin.InventoryHolderReferenceAccessor) holder).pingforit$key();
	}
	default Object holderValue(net.minecraft.core.Holder.Reference<?> holder) {
		return ((nx.pingwheel.common.mixin.InventoryHolderReferenceAccessor) holder).pingforit$value();
	}
	default net.minecraft.core.HolderOwner<?> namedOwner(net.minecraft.core.HolderSet.Named<?> set) {
		return ((nx.pingwheel.common.mixin.InventoryNamedHolderSetAccessor) set).pingforit$owner();
	}
	default net.minecraft.tags.TagKey<?> namedKey(net.minecraft.core.HolderSet.Named<?> set) {
		return ((nx.pingwheel.common.mixin.InventoryNamedHolderSetAccessor) set).pingforit$key();
	}
	default List<net.minecraft.core.Holder<?>> directContents(net.minecraft.core.HolderSet.Direct<?> set) {
		return ((nx.pingwheel.common.mixin.InventoryDirectHolderSetAccessor) (Object) set).pingforit$contents();
	}
	default boolean enchantmentTooltip(net.minecraft.world.item.enchantment.ItemEnchantments value) {
		return ((InventoryEnchantmentsAccessor) (Object) value).pingforit$showInTooltip();
	}
	InventoryResidentAccess MIXIN = new InventoryResidentAccess() {};
	/**
	 * Resolve once per defining class loader, lazily on the first request using
	 * ServiceLoader's default thread context class loader and first-provider order.
	 * Later context-loader changes do not replace the port. No production service
	 * overrides the Mixin fallback; headless tests install the exact-field port.
	 * Discovery failures propagate rather than selecting an empty fallback.
	 */
	static InventoryResidentAccess load() {
		return DefaultAccess.INSTANCE;
	}
	@org.jetbrains.annotations.NotNullByDefault
	final class DefaultAccess {
		private static final InventoryResidentAccess INSTANCE =
			java.util.ServiceLoader.load(InventoryResidentAccess.class).findFirst().orElse(MIXIN);
		private DefaultAccess() {}
	}

	default DataComponentMap prototype(PatchedDataComponentMap map) {
		return ((InventoryPatchedComponentsAccessor) (Object) map).pingforit$prototype();
	}
	default Map<DataComponentType<?>, Optional<?>> patch(PatchedDataComponentMap map) {
		return ((InventoryPatchedComponentsAccessor) (Object) map).pingforit$patch();
	}
	default Map<DataComponentType<?>, Optional<?>> patch(DataComponentPatch patch) {
		return ((InventoryComponentPatchAccessor) (Object) patch).pingforit$map();
	}
	default Map<DataComponentType<?>, Object> simple(DataComponentMap map) {
		return ((InventorySimpleComponentsAccessor) (Object) map).pingforit$map();
	}
	default DataComponentMap itemComponents(Item item) {
		return ((InventoryItemComponentsAccessor) (Object) item).pingforit$components();
	}
	default int referenceMapCapacity(Map<?, ?> map) {
		return InventoryExternalFields.referenceMapCapacity(map);
	}
	default List<?> nonNullList(NonNullList<?> list) {
		return ((InventoryNonNullListAccessor) list).pingforit$list();
	}
	default Map<String, Tag> compound(CompoundTag tag) {
		return ((InventoryCompoundTagAccessor) (Object) tag).pingforit$tags();
	}
	default List<Tag> list(ListTag tag) {
		return ((InventoryListTagAccessor) (Object) tag).pingforit$list();
	}
	default List<ItemStack> container(ItemContainerContents contents) {
		return ((InventoryContainerItemsAccessor) (Object) contents).pingforit$items();
	}
	default List<ItemStack> projectiles(ChargedProjectiles projectiles) {
		return ((InventoryProjectileItemsAccessor) (Object) projectiles).pingforit$items();
	}
	default List<ItemStack> bundle(BundleContents bundle) {
		return ((InventoryBundleItemsAccessor) (Object) bundle).pingforit$items();
	}
	default MobEffectInstance hidden(MobEffectInstance effect) {
		return ((InventoryHiddenEffectAccessor) effect).pingforit$hiddenEffect();
	}
	default List<BlockPredicate> predicates(AdventureModePredicate predicate) {
		return ((InventoryAdventurePredicatesAccessor) (Object) predicate).pingforit$predicates();
	}
	default List<MobEffectInstance> potion(net.minecraft.world.item.alchemy.PotionContents potion) {
		return ((InventoryPotionEffectsAccessor) (Object) potion).pingforit$effects();
	}
	default it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<?> enchantments(
		net.minecraft.world.item.enchantment.ItemEnchantments value) {
		return ((InventoryEnchantmentsAccessor) (Object) value).pingforit$enchantments();
	}
	default int objectIntCapacity(it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<?> map) {
		return InventoryExternalFields.objectIntCapacity(map);
	}
	default com.google.common.collect.Multimap<String, com.mojang.authlib.properties.Property> profile(
		com.mojang.authlib.properties.PropertyMap map) {
		return InventoryExternalFields.profile(map);
	}
}
