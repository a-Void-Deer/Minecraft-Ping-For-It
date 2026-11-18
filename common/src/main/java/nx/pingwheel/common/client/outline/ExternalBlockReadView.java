package nx.pingwheel.common.client.outline;

import java.util.Objects;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Read view of one provider-owned plot. The state and entity readers must prove membership before each read. */
@NotNullByDefault
public final class ExternalBlockReadView implements BlockGetter {

	private final BlockGetter backingLevel;
	private final Function<BlockPos, @Nullable BlockState> stateReader;
	private final Function<BlockPos, @Nullable BlockEntity> entityReader;

	public ExternalBlockReadView(
		BlockGetter backingLevel,
		Function<BlockPos, @Nullable BlockState> stateReader,
		Function<BlockPos, @Nullable BlockEntity> entityReader
	) {
		this.backingLevel = Objects.requireNonNull(backingLevel);
		this.stateReader = Objects.requireNonNull(stateReader);
		this.entityReader = Objects.requireNonNull(entityReader);
	}

	boolean isFor(BlockGetter level) {
		return backingLevel == level;
	}

	public @Nullable BlockState loadedState(BlockPos pos) {
		return isOutsideBuildHeight(pos) ? null : stateReader.apply(pos);
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		@Nullable BlockState state = loadedState(pos);
		return state == null ? Blocks.AIR.defaultBlockState() : state;
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return isOutsideBuildHeight(pos) ? null : entityReader.apply(pos);
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public int getHeight() {
		return backingLevel.getHeight();
	}

	@Override
	public int getMinBuildHeight() {
		return backingLevel.getMinBuildHeight();
	}
}
