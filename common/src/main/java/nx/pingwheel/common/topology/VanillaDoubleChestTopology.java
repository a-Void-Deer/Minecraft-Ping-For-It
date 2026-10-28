package nx.pingwheel.common.topology;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Bounded metadata-only topology; neither inventories nor block entities participate. */
@NotNullByDefault
public final class VanillaDoubleChestTopology {

	private VanillaDoubleChestTopology() {}

	@FunctionalInterface
	public interface LoadedStates {
		/** Returns null when unavailable, without loading a chunk or reading its state. */
		@Nullable BlockState read(BlockPos pos);
	}

	/** Presentation order is LEFT then RIGHT, independently of the original hit. */
	@NotNullByDefault
	public record Pair(BlockPos leftPos, BlockState leftState, BlockPos rightPos, BlockState rightState) {}

	public static boolean supports(String registryId) {
		return registryId.equals("minecraft:chest") || registryId.equals("minecraft:trapped_chest");
	}

	public static Optional<Pair> resolve(BlockPos sourcePos, String expectedRegistryId, LoadedStates states) {
		if (!supports(expectedRegistryId)) return Optional.empty();
		@Nullable BlockState source = states.read(sourcePos);
		if (source == null || (source.getBlock() != Blocks.CHEST && source.getBlock() != Blocks.TRAPPED_CHEST)
			|| !BuiltInRegistries.BLOCK.getKey(source.getBlock()).toString().equals(expectedRegistryId)
			|| source.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return Optional.empty();
		BlockPos siblingPos = sourcePos.relative(ChestBlock.getConnectedDirection(source));
		if (Math.abs((long) siblingPos.getX() - sourcePos.getX())
			+ Math.abs((long) siblingPos.getZ() - sourcePos.getZ()) != 1) return Optional.empty();
		@Nullable BlockState sibling = states.read(siblingPos);
		if (sibling == null || sibling.getBlock() != source.getBlock()
			|| sibling.getValue(ChestBlock.TYPE) != source.getValue(ChestBlock.TYPE).getOpposite()
			|| sibling.getValue(ChestBlock.FACING) != source.getValue(ChestBlock.FACING)
			|| !siblingPos.relative(ChestBlock.getConnectedDirection(sibling)).equals(sourcePos)) return Optional.empty();
		return Optional.of(source.getValue(ChestBlock.TYPE) == ChestType.LEFT
			? new Pair(sourcePos.immutable(), source, siblingPos, sibling)
			: new Pair(siblingPos, sibling, sourcePos.immutable(), source));
	}
}
