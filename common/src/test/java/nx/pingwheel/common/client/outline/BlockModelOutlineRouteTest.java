package nx.pingwheel.common.client.outline;

import org.junit.jupiter.api.Test;

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
}
