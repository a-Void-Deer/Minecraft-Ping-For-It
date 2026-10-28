package nx.pingwheel.common.marker;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformContextService;
import nx.pingwheel.common.topology.VanillaDoubleChestTopology;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Creation-only grouping. Unavailable topology never rejects a valid ordinary ping. */
@NotNullByDefault
public final class MinecraftTargetGroupingResolver implements TargetGroupingResolver {

	private final MinecraftServer server;

	public MinecraftTargetGroupingResolver(MinecraftServer server) {
		this.server = Objects.requireNonNull(server, "server");
	}

	@Override
	public TargetKey resolve(UUID owner, Target committedTarget) {
		TargetKey exact = TargetKey.from(committedTarget);
		// External/provider positions must never be interpreted as ordinary-world coordinates.
		if (!(committedTarget instanceof Target.BlockTarget block)) return exact;
		if (!VanillaDoubleChestTopology.supports(block.blockRegistryId())
			&& !block.blockRegistryId().equals("create:item_vault")) return exact;
		try {
			@Nullable ServerPlayer player = server.getPlayerList().getPlayer(owner);
			if (player == null) return exact;
			ServerLevel level = player.serverLevel();
			if (!level.dimension().location().toString().equals(block.dimensionId())) return exact;
			BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
			if (VanillaDoubleChestTopology.supports(block.blockRegistryId())) {
				return VanillaDoubleChestTopology.resolve(pos, block.blockRegistryId(), member -> readLoaded(level, member))
					.<TargetKey>map(pair -> key(block, pair)).orElse(exact);
			}
			@Nullable BlockState state = readLoaded(level, pos);
			if (state == null || !BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(block.blockRegistryId())) return exact;
			return TargetKey.requireCompatible(block, IPlatformContextService.INSTANCE.groupVaultTarget(level, block));
		} catch (RuntimeException | LinkageError | AssertionError unavailable) {
			Diagnostics.warn("target-grouping", "minecraft", () -> "target=" + block + "; using exact hit", unavailable);
			return exact;
		}
	}

	private static @Nullable BlockState readLoaded(ServerLevel level, BlockPos pos) {
		@Nullable LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		return chunk == null ? null : chunk.getBlockState(pos);
	}

	private static TargetKey.DoubleChestKey key(Target.BlockTarget block, VanillaDoubleChestTopology.Pair pair) {
		BlockPos first = pair.leftPos(), second = pair.rightPos();
		if (first.getX() > second.getX() || first.getX() == second.getX()
			&& (first.getY() > second.getY() || first.getY() == second.getY() && first.getZ() > second.getZ())) {
			BlockPos swap = first; first = second; second = swap;
		}
		return new TargetKey.DoubleChestKey(block.dimensionId(), first.getX(), first.getY(), first.getZ(),
			second.getX(), second.getY(), second.getZ(), block.blockRegistryId());
	}
}
