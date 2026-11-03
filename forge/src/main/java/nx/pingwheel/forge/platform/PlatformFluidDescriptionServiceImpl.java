package nx.pingwheel.forge.platform;

import net.minecraft.world.level.material.Fluid;
import nx.pingwheel.common.platform.FluidDescriptions;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

@NotNullByDefault
public final class PlatformFluidDescriptionServiceImpl implements IPlatformFluidDescriptionService {
	@Override public @Nullable Description describeBase(Fluid fluid) {
		return FluidDescriptions.project(fluid.getFluidType().getDescription());
	}
}
