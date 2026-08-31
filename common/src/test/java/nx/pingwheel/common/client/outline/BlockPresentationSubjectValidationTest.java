package nx.pingwheel.common.client.outline;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused tests for the live subject identity guard used by both render passes. */
class BlockPresentationSubjectValidationTest {

	@Test
	void replacedLiveRegistryStateIsRejectedWhileSavedSubjectStateRemainsStable() {
		BlockRenderSubject subject = new BlockRenderSubject(
			"subject",
			new BlockPos(7, 8, 9),
			Blocks.STONE.defaultBlockState(),
			"minecraft:stone",
			"entity_block",
			BlockPresentationRelation.PROXY_TO_OWNER);

		assertTrue(BlockPresentationSubjectValidation.hasExpectedRegistryId(
			subject, Blocks.STONE.defaultBlockState()));
		assertFalse(BlockPresentationSubjectValidation.hasExpectedRegistryId(
			subject, Blocks.DIRT.defaultBlockState()));
		assertEquals(Blocks.STONE, subject.blockState().getBlock());
	}
}
