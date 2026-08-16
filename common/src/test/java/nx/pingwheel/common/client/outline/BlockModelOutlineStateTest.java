package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.marker.TargetKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the per-frame model-outline success record.
 */
class BlockModelOutlineStateTest {

	@AfterEach
	void tearDown() {
		BlockModelOutlineState.INSTANCE.clear();
	}

	@Test
	void frameStartsEmptyAndRecordsSuccesses() {
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		TargetKey.BlockKey stone = new TargetKey.BlockKey("minecraft:overworld", 1, 2, 3, "minecraft:stone");
		TargetKey.BlockKey chest = new TargetKey.BlockKey("minecraft:overworld", 4, 5, 6, "minecraft:chest");

		state.beginFrame();
		assertFalse(state.emitted());
		assertTrue(state.successKeys().isEmpty());

		state.addSuccess(stone);
		assertTrue(state.emitted());
		assertEquals(java.util.Set.of(stone), state.successKeys());

		state.addSuccess(chest);
		assertEquals(java.util.Set.of(stone, chest), state.successKeys());

		// Duplicate recording keeps the set stable.
		state.addSuccess(stone);
		assertEquals(java.util.Set.of(stone, chest), state.successKeys());
	}

	@Test
	void beginFrameClearsThePreviousFrame() {
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		state.addSuccess(new TargetKey.BlockKey("minecraft:overworld", 1, 2, 3, "minecraft:stone"));
		assertTrue(state.emitted());

		state.beginFrame();
		assertFalse(state.emitted());
		assertTrue(state.successKeys().isEmpty());
	}

	@Test
	void clearIsAliasForBeginFrame() {
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		state.addSuccess(new TargetKey.BlockKey("minecraft:overworld", 1, 2, 3, "minecraft:stone"));
		state.clear();

		assertFalse(state.emitted());
		assertTrue(state.successKeys().isEmpty());
	}
}
