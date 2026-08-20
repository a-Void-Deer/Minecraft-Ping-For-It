package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.marker.TargetKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredEntityBlockGeometryStateTest {
	private final DeferredEntityBlockGeometryState state = DeferredEntityBlockGeometryState.INSTANCE;

	@AfterEach
	void reset() {
		state.leave();
	}

	@Test
	void onlyACompleteCurrentTargetBatchIsPublished() {
		state.beginFrame();
		TargetKey.BlockKey key = key(1);
		EntityBlockGeometryLineSink sink = state.open(key);

		assertTrue(sink.addLine(0, 0, 0, 1, 0, 0));
		assertFalse(state.hasLinesFor(key));
		assertTrue(sink.commit());
		assertTrue(state.hasLinesFor(key));
		assertEquals(1, state.linesFor(key).size());
		assertEquals(1, state.committedLineCount());
	}

	@Test
	void staleInvalidAndPartialBatchesAreRejectedAtomically() {
		state.beginFrame();
		TargetKey.BlockKey key = key(2);
		EntityBlockGeometryLineSink stale = state.open(key);
		state.beginFrame();
		assertTrue(stale.addLine(0, 0, 0, 1, 0, 0));
		assertFalse(stale.commit());
		assertFalse(state.hasLinesFor(key));

		EntityBlockGeometryLineSink invalid = state.open(key);
		assertFalse(invalid.addLine(Double.NaN, 0, 0, 1, 0, 0));
		assertFalse(invalid.commit());
		assertFalse(state.hasLinesFor(key));

		EntityBlockGeometryLineSink first = state.open(key);
		assertTrue(first.addLine(0, 0, 0, 1, 0, 0));
		assertTrue(first.commit());
		EntityBlockGeometryLineSink second = state.open(key);
		assertTrue(second.addLine(0, 0, 0, 0, 1, 0));
		assertTrue(second.commit());
		assertEquals(2, state.linesFor(key).size());
		assertEquals(new EntityBlockGeometryLine(0, 0, 0, 1, 0, 0), state.linesFor(key).get(0));
		assertEquals(new EntityBlockGeometryLine(0, 0, 0, 0, 1, 0), state.linesFor(key).get(1));
		assertEquals(2, state.committedLineCount());
	}

	@Test
	void laterOverBudgetCommitLeavesEarlierTargetBatchUntouched() {
		state.beginFrame();
		TargetKey.BlockKey key = key(5);
		EntityBlockGeometryLineSink first = state.open(key);
		for (int i = 0; i < DeferredEntityBlockGeometryState.MAX_LINES_PER_TARGET; i++) {
			assertTrue(first.addLine(i, 0, 0, i, 1, 0));
		}
		assertTrue(first.commit());

		EntityBlockGeometryLineSink overBudget = state.open(key);
		assertTrue(overBudget.addLine(0, 0, 0, 0, 1, 0));
		assertFalse(overBudget.commit());
		assertEquals(DeferredEntityBlockGeometryState.MAX_LINES_PER_TARGET,
			state.linesFor(key).size());
		assertEquals(DeferredEntityBlockGeometryState.MAX_LINES_PER_TARGET,
			state.committedLineCount());
	}

	@Test
	void frameBudgetIsAggregatedAcrossTargetCommits() {
		state.beginFrame();

		int targetCount = DeferredEntityBlockGeometryState.MAX_LINES_PER_FRAME
			/ DeferredEntityBlockGeometryState.MAX_LINES_PER_TARGET;
		for (int target = 0; target < targetCount; target++) {
			EntityBlockGeometryLineSink sink = state.open(key(10 + target));
			for (int line = 0; line < DeferredEntityBlockGeometryState.MAX_LINES_PER_TARGET; line++) {
				assertTrue(sink.addLine(line, 0, 0, line, 1, 0));
			}
			assertTrue(sink.commit());
		}

		TargetKey.BlockKey rejectedKey = key(10 + targetCount);
		EntityBlockGeometryLineSink overBudget = state.open(rejectedKey);
		assertTrue(overBudget.addLine(0, 0, 0, 0, 1, 0));
		assertFalse(overBudget.commit());
		assertFalse(state.hasLinesFor(rejectedKey));
		assertEquals(DeferredEntityBlockGeometryState.MAX_LINES_PER_FRAME,
			state.committedLineCount());
	}

	@Test
	void leaveAndBeginFrameClearPublishedLines() {
		state.beginFrame();
		TargetKey.BlockKey key = key(3);
		EntityBlockGeometryLineSink sink = state.open(key);
		sink.addLine(0, 0, 0, 1, 0, 0);
		assertTrue(sink.commit());
		state.leave();
		assertFalse(state.hasLinesFor(key));
		state.beginFrame();
		assertEquals(0, state.committedLineCount());
	}

	@Test
	void perTargetBudgetRejectsTheWholeBatch() {
		state.beginFrame();
		TargetKey.BlockKey key = key(4);
		EntityBlockGeometryLineSink sink = state.open(key);
		for (int i = 0; i < DeferredEntityBlockGeometryState.MAX_LINES_PER_TARGET; i++) {
			assertTrue(sink.addLine(i, 0, 0, i, 1, 0));
		}
		assertFalse(sink.addLine(0, 0, 0, 0, 1, 0));
		assertFalse(sink.commit());
		assertEquals(0, state.committedLineCount());
	}

	private static TargetKey.BlockKey key(int x) {
		return new TargetKey.BlockKey("minecraft:overworld", x, 64, 0, "minecraft:stone");
	}
}
