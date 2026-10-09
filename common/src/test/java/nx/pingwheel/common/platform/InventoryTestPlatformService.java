package nx.pingwheel.common.platform;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Test-only service registration permits default-interface dispatch without loading a real loader. */
public final class InventoryTestPlatformService implements IPlatformInventoryService {
	@Override public Optional<Access> find(ServerLevel level, BlockPos pos, Direction side) {
		throw new UnsupportedOperationException("headless inventory tests must inject their physical source lookup");
	}
}
