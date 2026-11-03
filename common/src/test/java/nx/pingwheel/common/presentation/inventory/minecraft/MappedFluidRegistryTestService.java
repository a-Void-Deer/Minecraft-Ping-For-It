package nx.pingwheel.common.presentation.inventory.minecraft;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import nx.pingwheel.common.platform.IPlatformFluidRegistryService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Only Common's provider-free test classpath uses this exact mapped-registry implementation. */
@NotNullByDefault
public final class MappedFluidRegistryTestService implements IPlatformFluidRegistryService {
	@Override public @Nullable ResourceLocation registeredKey(Fluid fluid, Admission admission) {
		// Shared Common tests also run on loader classpaths. Always choose the installed
		// native adapter there; this fixture must never make Forge appear mapped-safe.
		admission.admit(32768, 16384);
		for (var provider : java.util.ServiceLoader.load(IPlatformFluidRegistryService.class).stream().toList()) {
			if (provider.type() != MappedFluidRegistryTestService.class) return provider.get().registeredKey(fluid, admission);
		}
		return InventoryFluidRegistryLookup.mappedKey(fluid, admission);
	}
}
