package nx.pingwheel.common.client.outline;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Resolves a vanilla two-block door into its lower and upper subjects. */
public final class VanillaDoorBlockPresentationResolver implements BlockPresentationResolver {
	public static final String ID = "pingforit:vanilla_door";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public BlockPresentationResolution resolve(BlockPresentationContext context) {
		BlockState source = context.sourceState();
		if (!(source.getBlock() instanceof DoorBlock)) {
			return BlockPresentationResolution.UNHANDLED;
		}

		if (!context.sourceSpec().blockKey().blockRegistryId()
			.equals(BlockPresentationContext.blockRegistryId(source))) {
			return BlockPresentationResolution.handled(List.of());
		}

		BlockPos sourcePos = context.sourcePos();
		DoubleBlockHalf sourceHalf = source.getValue(DoorBlock.HALF);
		BlockPos lowerPos = sourceHalf == DoubleBlockHalf.LOWER ? sourcePos : sourcePos.below();
		BlockPos upperPos = lowerPos.above();
		BlockState lower = context.world().getBlockState(lowerPos);
		BlockState upper = context.world().getBlockState(upperPos);

		if (!validPair(source, lower, upper)) {
			return context.directResolution();
		}

		String expectedId = context.sourceSpec().blockKey().blockRegistryId();
		return BlockPresentationResolution.handled(List.of(
			new BlockRenderSubject(
				"lower", lowerPos, lower, expectedId, "block",
				BlockPresentationRelation.COMPOSITE),
			new BlockRenderSubject(
				"upper", upperPos, upper, expectedId, "block",
				BlockPresentationRelation.COMPOSITE)));
	}

	private static boolean validPair(BlockState source, BlockState lower, BlockState upper) {
		return lower.getBlock() == source.getBlock()
			&& upper.getBlock() == source.getBlock()
			&& lower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
			&& upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
			&& lower.getValue(DoorBlock.FACING) == upper.getValue(DoorBlock.FACING)
			&& lower.getValue(DoorBlock.HINGE) == upper.getValue(DoorBlock.HINGE)
			&& lower.getValue(DoorBlock.OPEN).equals(upper.getValue(DoorBlock.OPEN))
			&& lower.getValue(DoorBlock.POWERED).equals(upper.getValue(DoorBlock.POWERED));
	}
}
