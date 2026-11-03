package nx.pingwheel.neoforge.platform;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import nx.pingwheel.common.platform.IPlatformFluidRegistryService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidRegistryLookup;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

@NotNullByDefault
public final class PlatformFluidRegistryServiceImpl implements IPlatformFluidRegistryService {
	@Override public @Nullable ResourceLocation registeredKey(Fluid fluid, Admission admission) {
		return InventoryFluidRegistryLookup.mappedKey(fluid, admission);
	}
}
