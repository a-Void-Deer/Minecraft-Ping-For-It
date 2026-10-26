package nx.pingwheel.common.presentation.inventory.client;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.component.*;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.ItemStack;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Headless fixed native fields only; loader test runtime does not apply Mixins. */
@NotNullByDefault
public final class ClientDisplayFoodTestPorts implements InventoryResidentAccess {
	/** Monotonic, primitive observation only; never reset or change the service's lifetime. */
	public static long constructions;
	public ClientDisplayFoodTestPorts() { constructions++; }
	@Override public int styleFlags(net.minecraft.network.chat.Style style) {
		return (nullableField(style, "bold") == null ? 0 : 1) | (nullableField(style, "italic") == null ? 0 : 2)
			| (nullableField(style, "underlined") == null ? 0 : 4) | (nullableField(style, "strikethrough") == null ? 0 : 8)
			| (nullableField(style, "obfuscated") == null ? 0 : 16);
	}
	@Override public net.minecraft.resources.@Nullable ResourceLocation styleFont(net.minecraft.network.chat.Style style) { return nullableField(style, "font"); }
	@Override public @Nullable String colorName(net.minecraft.network.chat.TextColor color) { return nullableField(color, "name"); }
	@Override public Map<?, ?> builderEntries(net.minecraft.core.HolderLookup.Provider value) { return fieldAny(value, "val$map", "val$lookups"); }
	@Override public net.minecraft.resources.RegistryOps.RegistryInfo<?> builderInfo(Object value) { return field(value, "opsInfo"); }
	@Override public net.minecraft.resources.ResourceKey<?> builderKey(Object value) { return fieldAny(value, "val$registryKey", "val$key", "val$p_311196_"); }
	@Override public com.mojang.serialization.Lifecycle builderLifecycle(Object value) { return fieldAny(value, "val$registryLifecycle", "val$lifecycle", "val$p_311352_"); }
	@Override public Map<?, ?> builderElements(Object value) { return fieldAny(value, "val$elements", "val$entries", "val$p_311458_"); }
	@Override public net.minecraft.core.HolderOwner<?> builderOwner(Object value) { return fieldAt(value, value.getClass().getSuperclass().getSuperclass(), "owner"); }
	@Override public net.minecraft.core.MappedRegistry<?> lookupRegistry(Object value) { return fieldAny(value, "this$0", "field_36468"); }
	@Override public net.minecraft.core.Registry<?> registryDelegate(net.minecraft.core.HolderLookup.Provider value) { return fieldAny(value, "val$registryOfRegistries", "val$registries", "val$p_206166_"); }
	@Override public java.util.List<?> nonNullList(net.minecraft.core.NonNullList<?> value) { return field(value, "list"); }
	@Override public Map<String, net.minecraft.nbt.Tag> compound(net.minecraft.nbt.CompoundTag value) { return field(value, "tags"); }
	@Override public java.util.List<net.minecraft.nbt.Tag> list(net.minecraft.nbt.ListTag value) { return field(value, "list"); }
	@Override public DataComponentMap itemComponents(net.minecraft.world.item.Item value) { return fieldAt(value, net.minecraft.world.item.Item.class, "components"); }
	@Override public net.minecraft.core.HolderOwner<?> holderOwner(net.minecraft.core.Holder.Reference<?> value) { return field(value, "owner"); }
	@Override public net.minecraft.resources.ResourceKey<?> holderKey(net.minecraft.core.Holder.Reference<?> value) { return field(value, "key"); }
	@Override public Object holderValue(net.minecraft.core.Holder.Reference<?> value) { return field(value, "value"); }
	@Override public Map<? extends net.minecraft.resources.ResourceKey<?>, ? extends net.minecraft.core.Registry<?>> registries(net.minecraft.core.RegistryAccess.ImmutableRegistryAccess value) { return fieldAt(value, net.minecraft.core.RegistryAccess.ImmutableRegistryAccess.class, "registries"); }
	@Override public net.minecraft.resources.ResourceKey<?> registryKey(net.minecraft.core.MappedRegistry<?> value) { return fieldAt(value, net.minecraft.core.MappedRegistry.class, "key"); }
	@Override public net.minecraft.core.HolderLookup.RegistryLookup<?> registryLookup(net.minecraft.core.MappedRegistry<?> value) { return fieldAt(value, net.minecraft.core.MappedRegistry.class, "lookup"); }
	@Override public com.mojang.serialization.Lifecycle registryLifecycle(net.minecraft.core.MappedRegistry<?> value) { return fieldAt(value, net.minecraft.core.MappedRegistry.class, "registryLifecycle"); }
	@Override public Map<net.minecraft.resources.ResourceKey<?>, net.minecraft.core.Holder.Reference<?>> registryEntries(net.minecraft.core.MappedRegistry<?> value) { return fieldAt(value, net.minecraft.core.MappedRegistry.class, "byKey"); }
	@Override public Map<?, ?> registryValues(net.minecraft.core.MappedRegistry<?> value) { return fieldAt(value, net.minecraft.core.MappedRegistry.class, "byValue"); }
	@Override public Map<?, ?> registrationInfos(net.minecraft.core.MappedRegistry<?> value) { return fieldAt(value, net.minecraft.core.MappedRegistry.class, "registrationInfos"); }
	@Override public net.minecraft.core.HolderOwner<?> namedOwner(net.minecraft.core.HolderSet.Named<?> value) { return field(value, "owner"); }
	@Override public net.minecraft.tags.TagKey<?> namedKey(net.minecraft.core.HolderSet.Named<?> value) { return field(value, "key"); }
	@Override public java.util.List<net.minecraft.core.Holder<?>> directContents(net.minecraft.core.HolderSet.Direct<?> value) { return field(value, "contents"); }
	@Override public java.util.List<ItemStack> container(net.minecraft.world.item.component.ItemContainerContents value) { return field(value, "items"); }
	@Override public java.util.List<ItemStack> projectiles(net.minecraft.world.item.component.ChargedProjectiles value) { return field(value, "items"); }
	@Override public java.util.List<ItemStack> bundle(net.minecraft.world.item.component.BundleContents value) { return field(value, "items"); }
	@Override public java.util.List<net.minecraft.advancements.critereon.BlockPredicate> predicates(net.minecraft.world.item.AdventureModePredicate value) { return field(value, "predicates"); }
	@Override public java.util.List<MobEffectInstance> potion(net.minecraft.world.item.alchemy.PotionContents value) { return field(value, "customEffects"); }
	@Override public DataComponentMap prototype(PatchedDataComponentMap map) { return field(map, "prototype"); }
	@Override public Map<DataComponentType<?>, Optional<?>> patch(PatchedDataComponentMap map) { return field(map, "patch"); }
	@Override public Map<DataComponentType<?>, Optional<?>> patch(DataComponentPatch patch) { return field(patch, "map"); }
	@Override public Map<DataComponentType<?>, Object> simple(DataComponentMap map) { return field(map, "map"); }
	@Override public @Nullable MobEffectInstance hidden(MobEffectInstance effect) { return nullableField(effect, "hiddenEffect"); }
	@Override public it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<?> enchantments(ItemEnchantments value) { return field(value, "enchantments"); }
	@Override public boolean enchantmentTooltip(ItemEnchantments value) { return field(value, "showInTooltip"); }
	private static <T> T field(Object source, String name) { return fieldAt(source, source.getClass(), name); }
	private static <T> T fieldAny(Object source, String... names) {
		for (String name : names) {
			try { source.getClass().getDeclaredField(name); return field(source, name); }
			catch (NoSuchFieldException absent) { /* exact mapped field alternatives, not arbitrary discovery */ }
		}
		throw new AssertionError("native captured field drift: " + source.getClass().getName());
	}
	private static <T> T fieldAt(Object source, Class<?> owner, String name) { return java.util.Objects.requireNonNull(ClientDisplayFoodTestPorts.<T>nullableFieldAt(source, owner, name)); }
	private static <T> @Nullable T nullableField(Object source, String name) { return nullableFieldAt(source, source.getClass(), name); }
	@SuppressWarnings("unchecked") private static <T> @Nullable T nullableFieldAt(Object source, Class<?> owner, String name) {
		try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return (T) field.get(source); }
		catch (ReflectiveOperationException unavailable) { throw new AssertionError(unavailable); }
	}
}
