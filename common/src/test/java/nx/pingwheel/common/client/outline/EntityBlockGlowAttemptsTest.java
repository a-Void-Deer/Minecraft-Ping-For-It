package nx.pingwheel.common.client.outline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class EntityBlockGlowAttemptsTest {
	@Test
	void attemptsBothRoutesWhenBlockEntityRouteSucceeds() {
		AtomicInteger blockEntityAttempts = new AtomicInteger();
		AtomicInteger bakedModelAttempts = new AtomicInteger();
		AtomicInteger attemptOrder = new AtomicInteger();
		AtomicInteger blockEntityOrder = new AtomicInteger();
		AtomicInteger bakedModelOrder = new AtomicInteger();

		boolean success = EntityBlockGlowAttempts.attemptBoth(
			() -> {
				blockEntityAttempts.incrementAndGet();
				blockEntityOrder.set(attemptOrder.incrementAndGet());
				return true;
			},
			() -> {
				bakedModelAttempts.incrementAndGet();
				bakedModelOrder.set(attemptOrder.incrementAndGet());
				return false;
			});

		assertTrue(success);
		assertBothAttempted(blockEntityAttempts, bakedModelAttempts);
		assertEquals(1, blockEntityOrder.get());
		assertEquals(2, bakedModelOrder.get());
	}

	@Test
	void attemptsBothRoutesWhenBlockEntityRouteFailsAndUsesModelSuccess() {
		AtomicInteger blockEntityAttempts = new AtomicInteger();
		AtomicInteger bakedModelAttempts = new AtomicInteger();

		boolean success = EntityBlockGlowAttempts.attemptBoth(
			() -> {
				blockEntityAttempts.incrementAndGet();
				return false;
			},
			() -> {
				bakedModelAttempts.incrementAndGet();
				return true;
			});

		assertTrue(success);
		assertBothAttempted(blockEntityAttempts, bakedModelAttempts);
	}

	@Test
	void onlyFallsBackWhenBothRoutesFail() {
		AtomicInteger blockEntityAttempts = new AtomicInteger();
		AtomicInteger bakedModelAttempts = new AtomicInteger();

		boolean success = EntityBlockGlowAttempts.attemptBoth(
			() -> {
				blockEntityAttempts.incrementAndGet();
				return false;
			},
			() -> {
				bakedModelAttempts.incrementAndGet();
				return false;
			});

		assertFalse(success);
		assertBothAttempted(blockEntityAttempts, bakedModelAttempts);
	}

	private static void assertBothAttempted(
		AtomicInteger blockEntityAttempts, AtomicInteger bakedModelAttempts
	) {
		assertEqualsOne(blockEntityAttempts);
		assertEqualsOne(bakedModelAttempts);
	}

	private static void assertEqualsOne(AtomicInteger attempts) {
		assertEquals(1, attempts.get());
	}
}
