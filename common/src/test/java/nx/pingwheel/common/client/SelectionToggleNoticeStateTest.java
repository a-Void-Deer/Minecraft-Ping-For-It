package nx.pingwheel.common.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionToggleNoticeStateTest {
	@Test
	void alphaHasNoFadeInAndUsesExactLifetimeBoundaries() {
		assertEquals(255, SelectionToggleNoticeState.alphaAt(0));
		assertEquals(255, SelectionToggleNoticeState.alphaAt(1_999));
		assertEquals(255, SelectionToggleNoticeState.alphaAt(2_000));
		assertTrue(SelectionToggleNoticeState.alphaAt(2_500) > 0);
		assertTrue(SelectionToggleNoticeState.alphaAt(2_500) < 255);
		assertEquals(1, SelectionToggleNoticeState.alphaAt(2_999));
		assertEquals(0, SelectionToggleNoticeState.alphaAt(3_000));
	}

	@Test
	void aLaterToggleReplacesTheOnlyNoticeSlotAndRestartsLifetime() {
		SelectionToggleNoticeState state = new SelectionToggleNoticeState(() -> 0L);
		state.show(SelectionToggleNoticeState.Kind.PASS_THROUGH_TRANSPARENT_BLOCKS, true, 100L);
		state.show(SelectionToggleNoticeState.Kind.MARK_FLUIDS, false, 150L);

		SelectionToggleNoticeState.Snapshot snapshot = state.snapshot(150L).orElseThrow();
		assertEquals(SelectionToggleNoticeState.Kind.MARK_FLUIDS, snapshot.kind());
		assertFalse(snapshot.enabled());
		assertEquals(255, snapshot.alpha());
		assertTrue(state.snapshot(3_149L).isPresent());
		assertTrue(state.snapshot(3_150L).isEmpty());
	}
}
