package nx.pingwheel.common.client.outline;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Resolves a vanilla bed into its foot and head subjects. */
public final class VanillaBedBlockPresentationResolver implements BlockPresentationResolver {
	public static final String ID = "pingforit:vanilla_bed";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public BlockPresentationResolution resolve(BlockPresentationContext context) {
		BlockState source = context.sourceState();
		if (!(source.getBlock() instanceof BedBlock)) {
			return BlockPresentationResolution.UNHANDLED;
		}

		if (!context.sourceSpec().blockKey().blockRegistryId()
			.equals(BlockPresentationContext.blockRegistryId(source))) {
			return BlockPresentationResolution.handled(List.of());
		}

		BlockPos sourcePos = context.sourcePos();
		BedPart sourcePart = source.getValue(BedBlock.PART);
		Direction facing = source.getValue(BedBlock.FACING);
		BlockPos footPos = sourcePart == BedPart.FOOT
			? sourcePos
			: sourcePos.relative(facing.getOpposite());
		BlockPos headPos = footPos.relative(facing);
		BlockState foot = context.world().getBlockState(footPos);
		BlockState head = context.world().getBlockState(headPos);

		if (!validPair(source, foot, head)) {
			return context.directResolution();
		}

		String expectedId = context.sourceSpec().blockKey().blockRegistryId();
		return BlockPresentationResolution.handled(List.of(
			new BlockRenderSubject(
				"foot", footPos, foot, expectedId, "block",
				BlockPresentationRelation.COMPOSITE),
			new BlockRenderSubject(
				"head", headPos, head, expectedId, "block",
				BlockPresentationRelation.COMPOSITE)));
	}

	private static boolean validPair(BlockState source, BlockState foot, BlockState head) {
		return foot.getBlock() == source.getBlock()
			&& head.getBlock() == source.getBlock()
			&& foot.getValue(BedBlock.PART) == BedPart.FOOT
			&& head.getValue(BedBlock.PART) == BedPart.HEAD
			&& foot.getValue(BedBlock.FACING) == head.getValue(BedBlock.FACING);
	}
}
