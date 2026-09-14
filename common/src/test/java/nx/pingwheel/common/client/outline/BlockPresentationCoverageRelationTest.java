package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockPresentationCoverageRelationTest {

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void presentationCopiesRelationListAndRejectsInvalidTopology() {
		List<BlockPresentationCoverageRelation> relations = new ArrayList<>();
		relations.add(relation("lower", "upper"));
		BlockPresentation presentation = presentation(relations);
		relations.clear();
		assertEquals(1, presentation.coverageRelations().size());
		assertThrows(UnsupportedOperationException.class,
			() -> presentation.coverageRelations().add(relation("lower", "third")));

		assertThrows(IllegalArgumentException.class,
			() -> presentation(List.of(relation("upper", "lower"))));
		assertThrows(IllegalArgumentException.class,
			() -> presentation(List.of(relation("lower", "lower"))));
		assertThrows(IllegalArgumentException.class,
			() -> presentation(List.of(new BlockPresentationCoverageRelation(
				"missing", EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID, "upper"))));
		assertThrows(IllegalArgumentException.class,
			() -> presentation(List.of(relation("lower", "upper"), relation("lower", "upper"))));
		assertThrows(IllegalArgumentException.class,
			() -> presentation(List.of(
				relation("lower", "upper"),
				new BlockPresentationCoverageRelation(
					"lower", EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID, "upper"))));
		assertThrows(IllegalArgumentException.class,
			() -> new BlockPresentationCoverageRelation("lower", "not-a-source", "upper"));
	}

	private static BlockPresentationCoverageRelation relation(String owner, String covered) {
		return new BlockPresentationCoverageRelation(
			owner, EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID, covered);
	}

	private static BlockPresentation presentation(List<BlockPresentationCoverageRelation> relations) {
		BlockPos pos = new BlockPos(8, 64, 8);
		return new BlockPresentation(
			new BlockOutlineSpec(
				new MarkerId(8L),
				new TargetKey.BlockKey(
					"minecraft:overworld", pos.getX(), pos.getY(), pos.getZ(), "minecraft:stone"),
				"entity_block", "attention", 0xFF123456),
			List.of(
				subject("lower", pos),
				subject("upper", pos.above()),
				subject("third", pos.above(2))),
			relations);
	}

	private static BlockRenderSubject subject(String id, BlockPos pos) {
		return new BlockRenderSubject(
			id, pos, Blocks.STONE.defaultBlockState(), "minecraft:stone", "entity_block",
			BlockPresentationRelation.COMPOSITE);
	}
}
