package nx.pingwheel.common.interaction.candidate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import java.util.function.Predicate;

/** Guards shape/neighbor/BE reads; live read-only BEs are used only during the press scan. */
public final class LoadedCandidateBlockView implements BlockGetter {
	private final BlockGetter level;
	private final Predicate<BlockPos> loaded;
	private final CandidateWorkBudget budget;
	private boolean missingNeighbor;
	public LoadedCandidateBlockView(Level level) { this(level, level::isLoaded); }
	public LoadedCandidateBlockView(Level level, CandidateWorkBudget budget) { this(level, level::isLoaded, budget); }
	public LoadedCandidateBlockView(BlockGetter level, Predicate<BlockPos> loaded) {
		this(level, loaded, null);
	}
	public LoadedCandidateBlockView(BlockGetter level, Predicate<BlockPos> loaded, CandidateWorkBudget budget) {
		this.level = java.util.Objects.requireNonNull(level, "level");
		this.loaded = java.util.Objects.requireNonNull(loaded, "loaded");
		this.budget = budget;
	}
	@Override public BlockEntity getBlockEntity(BlockPos pos) {
		chargeRead();
		if (!loaded.test(pos)) { missingNeighbor = true; return null; }
		BlockEntity entity;
		if (level instanceof Level liveLevel) {
			// Level.getBlockEntity uses IMMEDIATE and may create/register a missing BE.
			// Even LevelChunk CHECK may promote pending NBT. Read the existing map only.
			var chunk = liveLevel.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
			entity = chunk instanceof LevelChunk loadedChunk ? loadedChunk.getBlockEntities().get(pos) : null;
		} else {
			entity = level.getBlockEntity(pos);
		}
		if (entity == null || entity.isRemoved()) { missingNeighbor = true; return null; }
		return entity;
	}
	@Override public BlockState getBlockState(BlockPos pos) {
		chargeRead();
		if (loaded.test(pos)) return level.getBlockState(pos);
		missingNeighbor = true;
		return Blocks.AIR.defaultBlockState();
	}
	@Override public FluidState getFluidState(BlockPos pos) {
		chargeRead();
		if (loaded.test(pos)) return level.getFluidState(pos);
		missingNeighbor = true;
		return Fluids.EMPTY.defaultFluidState();
	}
	/** A shape asking for unknown neighbor state cannot certify its observed surface. */
	public boolean complete() { return !missingNeighbor; }
	private void chargeRead() {
		if (budget != null && !budget.visitBlock()) {
			missingNeighbor = true;
			throw new ExactNativeShapeClip.Incomplete();
		}
	}
	@Override public int getHeight() { return level.getHeight(); }
	@Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
}
