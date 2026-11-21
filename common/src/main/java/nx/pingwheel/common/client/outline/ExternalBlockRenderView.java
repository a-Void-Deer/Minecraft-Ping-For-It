package nx.pingwheel.common.client.outline;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Supplies native rendering metadata without widening an external plot's block reads. */
@NotNullByDefault
public class ExternalBlockRenderView implements BlockAndTintGetter {

	private final ExternalBlockReadView plot;
	private final BlockAndTintGetter lighting;
	private final LevelLightEngine emptyLightEngine;

	public ExternalBlockRenderView(ExternalBlockReadView plot, BlockAndTintGetter lighting) {
		this.plot = plot;
		this.lighting = lighting;
		// Native tessellation uses the position-gated methods below. Do not expose the
		// parent's mutable, unbounded light engine to a model through the host interface.
		this.emptyLightEngine = new LevelLightEngine(new EmptyLightChunks(plot), false, false);
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		return plot.getBlockState(pos);
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return plot.getBlockEntity(pos);
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return plot.getFluidState(pos);
	}

	@Override
	public int getHeight() {
		return plot.getHeight();
	}

	@Override
	public int getMinBuildHeight() {
		return plot.getMinBuildHeight();
	}

	@Override
	public float getShade(Direction direction, boolean shade) {
		return lighting.getShade(direction, shade);
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return emptyLightEngine;
	}

	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		return plot.loadedState(pos) == null ? 0 : lighting.getBrightness(layer, pos);
	}

	@Override
	public int getRawBrightness(BlockPos pos, int amount) {
		return plot.loadedState(pos) == null ? 0 : lighting.getRawBrightness(pos, amount);
	}

	@Override
	public int getBlockTint(BlockPos pos, ColorResolver resolver) {
		return plot.loadedState(pos) == null ? -1 : lighting.getBlockTint(pos, resolver);
	}

	@NotNullByDefault
	private record EmptyLightChunks(BlockGetter level) implements LightChunkGetter {
		@Override
		public @Nullable LightChunk getChunkForLighting(int x, int z) {
			return null;
		}

		@Override
		public BlockGetter getLevel() {
			return level;
		}
	}
}
