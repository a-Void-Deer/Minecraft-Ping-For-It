package nx.pingwheel.common.client.outline;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static nx.pingwheel.common.client.outline.BlockModelOutlineRoute.BLOCK_DISPLAY;
import static nx.pingwheel.common.client.outline.BlockModelOutlineRoute.ENTITY_BLOCK;
import static nx.pingwheel.common.client.outline.BlockModelOutlineRoute.VOXEL;

/**
 * Headless tests for the pure per-frame block outline route policy.
 */
class BlockModelOutlineRouteTest {

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	// --- routing ---

	@Test
	void entityBlockUsesTheEffectiveNativeGlowDecision() {
		assertEquals(VOXEL, BlockModelOutlineRoute.route("entity_block", false));
		assertEquals(ENTITY_BLOCK, BlockModelOutlineRoute.route("entity_block", true));
	}

	@Test
	void plainBlockRoutesToBlockDisplayOnlyWhenWhitelisted() {
		assertEquals(BLOCK_DISPLAY, BlockModelOutlineRoute.route("block", true));
		assertEquals(VOXEL, BlockModelOutlineRoute.route("block", false));
	}

	@Test
	void externalOrdinaryBlockKeepsTheSharedPolicyAndModelGate() {
		assertEquals(
			BLOCK_DISPLAY,
			BlockModelOutlineRoute.routeExternal("block", true, true));
		assertEquals(
			VOXEL,
			BlockModelOutlineRoute.routeExternal("block", false, true));
		assertEquals(
			VOXEL,
			BlockModelOutlineRoute.routeExternal("block", true, false));
		assertEquals(
			VOXEL,
			BlockModelOutlineRoute.routeExternal("block", false, false));
	}

	@Test
	void externalEntityBlockUsesTheRunnerEvenWithoutAStaticModel() {
		assertEquals(
			ENTITY_BLOCK,
			BlockModelOutlineRoute.routeExternal("entity_block", true, true));
		assertEquals(
			ENTITY_BLOCK,
			BlockModelOutlineRoute.routeExternal("entity_block", true, false));
		assertEquals(
			VOXEL,
			BlockModelOutlineRoute.routeExternal("entity_block", false, true));
		assertEquals(
			VOXEL,
			BlockModelOutlineRoute.routeExternal("entity_block", false, false));
	}

	@Test
	void externalUnknownTargetTypeNeverEntersTheModelRoute() {
		assertEquals(
			VOXEL,
			BlockModelOutlineRoute.routeExternal("unknown_type", true, true));
	}

	@Test
	void nonBlockTargetTypesAlwaysRouteToVoxel() {
		for (String targetTypeId : new String[] {"entity", "location", "dropped_item", "", "unknown_type"}) {
			assertEquals(VOXEL, BlockModelOutlineRoute.route(targetTypeId, true), "should be voxel: '" + targetTypeId + "'");
			assertEquals(VOXEL, BlockModelOutlineRoute.route(targetTypeId, false), "should be voxel: '" + targetTypeId + "'");
		}
	}

	// --- block rendering participation ---

	@Test
	void onlyBlockAndEntityBlockParticipateInBlockRendering() {
		assertTrue(BlockModelOutlineRoute.acceptsForBlockRendering("block"));
		assertTrue(BlockModelOutlineRoute.acceptsForBlockRendering("entity_block"));

		for (String targetTypeId : new String[] {"entity", "location", "dropped_item", "", "unknown_type"}) {
			assertFalse(BlockModelOutlineRoute.acceptsForBlockRendering(targetTypeId), "should reject: '" + targetTypeId + "'");
		}
	}

	@Test
	void nullTargetTypeIdIsRejected() {
		assertThrows(NullPointerException.class, () -> BlockModelOutlineRoute.route(null, true));
		assertThrows(NullPointerException.class, () -> BlockModelOutlineRoute.acceptsForBlockRendering(null));
	}

	@Test
	void sourceBlockPresentationCanRouteItsSubjectAsEntityBlockAtSubjectPosition() {
		BlockPos sourcePos = new BlockPos(1, 2, 3);
		BlockPos subjectPos = new BlockPos(7, 8, 9);
		BlockOutlineSpec source = new BlockOutlineSpec(
			new MarkerId(1L),
			new TargetKey.BlockKey(
				"minecraft:overworld", sourcePos.getX(), sourcePos.getY(), sourcePos.getZ(),
				"minecraft:stone"),
			"block",
			"attention",
			0xFF123456);
		BlockRenderSubject subject = new BlockRenderSubject(
			"entity-subject",
			subjectPos,
			Blocks.STONE.defaultBlockState(),
			"minecraft:stone",
			"entity_block",
			BlockPresentationRelation.PROXY_TO_OWNER);
		BlockPresentation presentation = new BlockPresentation(source, List.of(subject));

		assertEquals(ENTITY_BLOCK, BlockModelOutlineRoute.route(subject.renderTargetTypeId(), true));
		assertEquals(subjectPos, presentation.renderSubjects().get(0).renderPos());
		assertEquals("block", presentation.sourceSpec().targetTypeId());
	}
}
