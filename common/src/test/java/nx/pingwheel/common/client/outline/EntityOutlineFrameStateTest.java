package nx.pingwheel.common.client.outline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOutlineFrameStateTest {

	@AfterEach
	void tearDown() {
		EntityOutlineFrameState.INSTANCE.clear();
	}

	@Test
	void beginFrameAdvancesFrameIdAndClearsBothFlags() {
		long before = EntityOutlineFrameState.INSTANCE.frameId();
		EntityOutlineFrameState.INSTANCE.markEmitted();
		EntityOutlineFrameState.INSTANCE.markRequestSucceeded(true);

		EntityOutlineFrameState.INSTANCE.beginFrame();

		assertEquals(before + 1, EntityOutlineFrameState.INSTANCE.frameId());
		assertFalse(EntityOutlineFrameState.INSTANCE.emitted());
		assertFalse(EntityOutlineFrameState.INSTANCE.requestSucceeded());
	}

	@Test
	void marksAreTrackedIndependentlyWithinAFrame() {
		assertFalse(EntityOutlineFrameState.INSTANCE.emitted());
		assertFalse(EntityOutlineFrameState.INSTANCE.requestSucceeded());

		EntityOutlineFrameState.INSTANCE.markRequestSucceeded(true);
		assertFalse(EntityOutlineFrameState.INSTANCE.emitted());
		assertTrue(EntityOutlineFrameState.INSTANCE.requestSucceeded());

		EntityOutlineFrameState.INSTANCE.markEmitted();
		assertTrue(EntityOutlineFrameState.INSTANCE.emitted());
		assertTrue(EntityOutlineFrameState.INSTANCE.requestSucceeded());
	}

	@Test
	void requestFailureMarkAndEmittedAreDistinct() {
		EntityOutlineFrameState.INSTANCE.markRequestSucceeded(false);
		EntityOutlineFrameState.INSTANCE.markEmitted();

		assertTrue(EntityOutlineFrameState.INSTANCE.emitted());
		assertFalse(EntityOutlineFrameState.INSTANCE.requestSucceeded());
	}

	@Test
	void clearResetsToAFreshFrame() {
		long before = EntityOutlineFrameState.INSTANCE.frameId();
		EntityOutlineFrameState.INSTANCE.markEmitted();
		EntityOutlineFrameState.INSTANCE.markRequestSucceeded(true);

		EntityOutlineFrameState.INSTANCE.clear();

		assertEquals(before + 1, EntityOutlineFrameState.INSTANCE.frameId());
		assertFalse(EntityOutlineFrameState.INSTANCE.emitted());
		assertFalse(EntityOutlineFrameState.INSTANCE.requestSucceeded());
	}

	@Test
	void frameIdIsMonotonicAcrossFrames() {
		long first = EntityOutlineFrameState.INSTANCE.frameId();
		EntityOutlineFrameState.INSTANCE.beginFrame();
		long second = EntityOutlineFrameState.INSTANCE.frameId();
		EntityOutlineFrameState.INSTANCE.beginFrame();
		long third = EntityOutlineFrameState.INSTANCE.frameId();

		assertTrue(first < second);
		assertTrue(second < third);
	}
}
