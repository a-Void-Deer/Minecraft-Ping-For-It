package nx.pingwheel.common.presentation.inventory.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import nx.pingwheel.common.platform.FluidDescriptions;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService;
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
		return resolve(fluidId, label, json, FluidDescriptions::describeBase);
	}
	static Display resolve(@Nullable String fluidId, String label, @Nullable String json, IPlatformFluidDescriptionService descriptions) {
		Component fallback = fallbackName(fluidId, label);
		if (fluidId != null && fluidId.length() > 256) return new Display(fallback, null);
		@Nullable ResourceLocation id = fluidId == null ? null : ResourceLocation.tryParse(fluidId);
		if (id == null || !BuiltInRegistries.FLUID.containsKey(id)) return new Display(fallback, null);
		var projection = InventoryFluidDisplayCodec.decode(fluidId, json);
		if (projection.isEmpty()) return new Display(fallback, null);
		Fluid fluid = BuiltInRegistries.FLUID.get(id);
		@Nullable var base = FluidDescriptions.describeBase(fluid, descriptions);
		if (base instanceof IPlatformFluidDescriptionService.Literal literal) fallback = Component.literal(literal.text());
		else if (base instanceof IPlatformFluidDescriptionService.Translation translation) {
			if (Language.getInstance().has(translation.key()))
				fallback = Component.translatableWithFallback(translation.key(), translation.fallback() == null ? fluidId : translation.fallback());
			else if (translation.fallback() != null) fallback = Component.literal(translation.fallback());
		}
		@Nullable String custom = projection.get().customName();
		Component name = custom == null ? fallback
			: Component.translatableWithFallback("pingforit.chat.content.custom_item_name", "\"%s\" (%s)", Component.literal(custom), fallback);
		return new Display(name, fluid);
	}

	public static Component fallbackName(String label) {
		return fallbackName(null, label);
	}
	private static Component fallbackName(@Nullable String fluidId, String label) {
		// An unverified/missing translation is never exposed as a raw key. Registry IDs are literal.
		if (FluidDescriptions.validKey(label) && Language.getInstance().has(label)) return Component.translatable(label);
		if (validRegistryId(fluidId)) return Component.literal(fluidId);
		if (validRegistryId(label)) return Component.literal(label);
		return Component.translatable("pingforit.inventory.unknown_fluid");
	}
	private static boolean validRegistryId(@Nullable String value) {
		return value != null && value.length() <= 256 && value.indexOf(':') > 0 && ResourceLocation.tryParse(value) != null;
	}
}
