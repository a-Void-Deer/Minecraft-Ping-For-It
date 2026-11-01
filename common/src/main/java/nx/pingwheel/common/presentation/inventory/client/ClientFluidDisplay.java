package nx.pingwheel.common.presentation.inventory.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import nx.pingwheel.common.presentation.inventory.InventoryFluidDisplayCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Safe base-fluid name/icon identity. Never constructs an ItemStack or decodes native components. */
@NotNullByDefault
public final class ClientFluidDisplay {

	private ClientFluidDisplay() {}
	@NotNullByDefault
	public record Display(Component name, @Nullable Fluid fluid) {}

	public static Display resolve(@Nullable String fluidId, String label, @Nullable String json) {
		Component fallback = fallbackName(label);
		@Nullable ResourceLocation id = fluidId == null ? null : ResourceLocation.tryParse(fluidId);
		if (id == null || !BuiltInRegistries.FLUID.containsKey(id)) return new Display(fallback, null);
		var projection = InventoryFluidDisplayCodec.decode(fluidId, json);
		if (projection.isEmpty()) return new Display(fallback, null);
		@Nullable String custom = projection.get().customName();
		Component name = custom == null ? fallback
			: Component.translatableWithFallback("pingforit.chat.content.custom_item_name", "\"%s\" (%s)", Component.literal(custom), fallback);
		return new Display(name, BuiltInRegistries.FLUID.get(id));
	}

	public static Component fallbackName(String label) {
		// The label is a bounded translation fallback, not another display-document channel.
		return label.matches("[a-zA-Z0-9_.:/-]{1,256}") ? Component.translatable(label)
			: Component.translatable("pingforit.inventory.unknown_fluid");
	}
}
