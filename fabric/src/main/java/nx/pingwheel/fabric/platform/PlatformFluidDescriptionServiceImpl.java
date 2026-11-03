package nx.pingwheel.fabric.platform;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.minecraft.world.level.material.Fluid;
import nx.pingwheel.common.platform.FluidDescriptions;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

@NotNullByDefault
public final class PlatformFluidDescriptionServiceImpl implements IPlatformFluidDescriptionService {
	@Override public @Nullable Description describeBase(Fluid fluid) {
		return FluidDescriptions.project(FluidVariantAttributes.getName(FluidVariant.of(fluid)));
	}
}
