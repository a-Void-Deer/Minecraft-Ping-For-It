package nx.pingwheel.common.client.marker;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@NotNullByDefault
class ObservedServerClockTest {
	@Test
	void measuresOnlyReceivedProgressThroughIrregularSlowFastSlowSamples() {
		ObservedServerClock clock = new ObservedServerClock();
		Object world = new Object();
		var receivedBeforeFirstSample = clock.receiptOrigin();
		assertEquals(0L, receivedBeforeFirstSample.elapsedTicks());

		clock.observeSample(world, 100L);
		assertEquals(0L, receivedBeforeFirstSample.elapsedTicks());
		clock.observeSample(world, 101L);
		assertEquals(1L, receivedBeforeFirstSample.elapsedTicks());
		clock.observeSample(world, 131L);
		assertEquals(31L, receivedBeforeFirstSample.elapsedTicks());
		clock.observeSample(world, 131L);
		assertEquals(31L, receivedBeforeFirstSample.elapsedTicks(), "equal samples do not advance game time");
		clock.observeSample(world, 133L);
		assertEquals(33L, receivedBeforeFirstSample.elapsedTicks());
	}

	@Test
	void receiptsBetweenSamplesSkipTheContainingIntervalWithoutDeferringOlderReceipts() {
		ObservedServerClock clock = new ObservedServerClock();
		Object world = new Object();
		var earlierReceipt = clock.receiptOrigin();
		clock.observeSample(world, 5L);

		var firstInBatch = clock.receiptOrigin();
		var lastInBatch = clock.receiptOrigin();
		clock.observeSample(world, 105L);
		assertEquals(100L, earlierReceipt.elapsedTicks());
		assertEquals(0L, firstInBatch.elapsedTicks());
		assertEquals(0L, lastInBatch.elapsedTicks());

		var justAfterSample = clock.receiptOrigin();
		clock.observeSample(world, 109L);
		assertEquals(4L, firstInBatch.elapsedTicks());
		assertEquals(4L, lastInBatch.elapsedTicks());
		assertEquals(0L, justAfterSample.elapsedTicks());
	}

	@Test
	void rebaselinesOnRewindWorldIdentityChangeAndDetachWithoutLosingPriorProgress() {
		ObservedServerClock clock = new ObservedServerClock();
		World firstWorld = new World("overworld");
		World replacement = new World("overworld");
		var existing = clock.receiptOrigin();
		clock.observeSample(firstWorld, 200L);
		clock.observeSample(firstWorld, 205L);
		clock.observeSample(firstWorld, 50L);
		assertEquals(5L, existing.elapsedTicks());
		clock.observeSample(firstWorld, 53L);
		assertEquals(8L, existing.elapsedTicks());

		var duringTransition = clock.receiptOrigin();
		clock.observeSample(replacement, 1000L);
		assertEquals(8L, existing.elapsedTicks());
		assertEquals(0L, duringTransition.elapsedTicks());
		clock.observeSample(replacement, 1002L);
		assertEquals(10L, existing.elapsedTicks());
		assertEquals(2L, duringTransition.elapsedTicks());

		clock.breakWorldBaseline();
		clock.observeSample(replacement, 2000L);
		assertEquals(10L, existing.elapsedTicks());
		clock.observeSample(replacement, 2001L);
		assertEquals(3L, duringTransition.elapsedTicks());
	}

	@Test
	void disconnectInvalidatesSettledAndPendingReceiptsAndStartsFresh() {
		ObservedServerClock clock = new ObservedServerClock();
		Object world = new Object();
		var oldSettled = clock.receiptOrigin();
		clock.observeSample(world, 100L);
		clock.observeSample(world, 200L);
		assertEquals(100L, oldSettled.elapsedTicks());
		var oldPending = clock.receiptOrigin();

		clock.reset();
		var newReceipt = clock.receiptOrigin();
		clock.observeSample(world, 600L);
		clock.observeSample(world, 603L);
		assertEquals(0L, oldSettled.elapsedTicks());
		assertEquals(0L, oldPending.elapsedTicks());
		assertEquals(3L, newReceipt.elapsedTicks());
	}

	@Test
	void saturatesElapsedWithoutOverflowOrFreezingLaterReceipts() {
		ObservedServerClock clock = new ObservedServerClock();
		Object world = new Object();
		var oldest = clock.receiptOrigin();
		clock.observeSample(world, Long.MIN_VALUE);

		var later = clock.receiptOrigin();
		clock.observeSample(world, Long.MAX_VALUE);
		assertEquals(Long.MAX_VALUE, oldest.elapsedTicks());
		assertEquals(0L, later.elapsedTicks(), "a huge delta does not spill past the later receipt");

		clock.observeSample(world, 5L);
		clock.observeSample(world, 9L);
		assertEquals(Long.MAX_VALUE, oldest.elapsedTicks());
		assertEquals(4L, later.elapsedTicks());

		clock.observeSample(world, Long.MIN_VALUE);
		clock.observeSample(world, Long.MAX_VALUE);
		assertEquals(Long.MAX_VALUE, later.elapsedTicks());
		assertEquals(Long.MAX_VALUE, oldest.elapsedTicks(), "multiple rollovers do not rejuvenate old receipts");
	}

	@NotNullByDefault
	private record World(String dimension) {}
}
