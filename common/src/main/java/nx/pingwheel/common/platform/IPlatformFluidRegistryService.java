package nx.pingwheel.common.platform;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Current fluid registration only. No world, variant, display or arbitrary-registry lookup. */
@NotNullByDefault
public interface IPlatformFluidRegistryService {

	@Nullable ResourceLocation registeredKey(Fluid fluid, Admission admission);

	/** Debits the current freezer root before each owned observation or allocation. Never retained. */
	@NotNullByDefault
	@FunctionalInterface
	interface Admission {
		void admit(long workUnits, long allocationBytes);
	}
}
