package nx.pingwheel.common.presentation.inventory.client;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.component.*;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess;

/** Headless fixed native fields only; loader test runtime does not apply Mixins. */
public final class ClientDisplayFoodTestPorts implements InventoryResidentAccess {
	@Override public DataComponentMap prototype(PatchedDataComponentMap map) { return field(map, "prototype"); }
	@Override public Map<DataComponentType<?>, Optional<?>> patch(PatchedDataComponentMap map) { return field(map, "patch"); }
	@Override public Map<DataComponentType<?>, Optional<?>> patch(DataComponentPatch patch) { return field(patch, "map"); }
	@Override public Map<DataComponentType<?>, Object> simple(DataComponentMap map) { return field(map, "map"); }
	@Override public MobEffectInstance hidden(MobEffectInstance effect) { return field(effect, "hiddenEffect"); }
	@Override public it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<?> enchantments(ItemEnchantments value) { return field(value, "enchantments"); }
	@Override public boolean enchantmentTooltip(ItemEnchantments value) { return field(value, "showInTooltip"); }
	@SuppressWarnings("unchecked") private static <T> T field(Object source, String name) {
		try { Field field = source.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(source); }
		catch (ReflectiveOperationException unavailable) { throw new AssertionError(unavailable); }
	}
}
