package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.Objects;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import nx.pingwheel.common.topology.VanillaDoubleChestTopology;
import org.jetbrains.annotations.Nullable;

/**
 * Narrow, world-aware context supplied to a block presentation resolver.
 *
 * <p>The world is a {@link BlockGetter}, rather than a client level, so
 * resolver behavior can be tested with a small state/block-entity lookup.</p>
 */
public record BlockPresentationContext(
	BlockGetter world,
	BlockOutlineSpec sourceSpec
) {

	public BlockPresentationContext {
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(sourceSpec, "sourceSpec");
	}

	public BlockPos sourcePos() {
		return new BlockPos(
			sourceSpec.blockKey().x(), sourceSpec.blockKey().y(), sourceSpec.blockKey().z());
	}

	/** Reads the current source state; callers should not retain it across frames. */
	public BlockState sourceState() {
		if (VanillaDoubleChestTopology.supports(sourceSpec.blockKey().blockRegistryId())
			|| "create:item_vault".equals(sourceSpec.blockKey().blockRegistryId())) {
			@Nullable BlockState loaded = loadedState(sourcePos());
			return loaded == null ? Blocks.AIR.defaultBlockState() : loaded;
		}
		return world.getBlockState(sourcePos());
	}

	/** Positive nonloading client lookup; synthetic BlockGetters supply their own finite states. */
	public @Nullable BlockState loadedState(BlockPos pos) {
		if (world instanceof ClientLevel level) {
			@Nullable LevelChunk chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
			return chunk == null ? null : chunk.getBlockState(pos);
		}
		return world.getBlockState(pos);
	}

	/** Reads the source block entity when one is currently present. */
	public BlockEntity sourceBlockEntity() {
		return world.getBlockEntity(sourcePos());
	}

	public String sourceBlockRegistryId() {
		return blockRegistryId(sourceState());
	}

	public boolean sourceBlockMatches() {
		return sourceSpec.blockKey().blockRegistryId().equals(sourceBlockRegistryId());
	}

	/**
	 * Builds the only valid direct subject for the source, or no subject when the
	 * frozen block type has been replaced.
	 */
	public BlockPresentationResolution directResolution() {
		BlockState state = sourceState();
		if (!sourceSpec.blockKey().blockRegistryId().equals(blockRegistryId(state))) {
			return BlockPresentationResolution.handled(List.of());
		}

		return BlockPresentationResolution.handled(new BlockRenderSubject(
			"direct",
			sourcePos(),
			state,
			sourceSpec.blockKey().blockRegistryId(),
			sourceSpec.targetTypeId(),
			BlockPresentationRelation.DIRECT));
	}

	static String blockRegistryId(BlockState state) {
		Objects.requireNonNull(state, "state");
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}
}
