package nx.pingwheel.common.client.rate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.interaction.state.InteractionTimeSource;

class ClientCreateRateLimiterTest {

	@Test
	void defaultAllowsExactlyFiveImmediateCreates() {
		ManualTime time = new ManualTime();
		ClientCreateRateLimiter limiter = new ClientCreateRateLimiter(time);

		for (int i = 0; i < 5; i++) {
			assertTrue(limiter.tryAcquire());
		}

		assertFalse(limiter.tryAcquire());
	}

	@Test
	void refillUsesExactBoundaryAndPreservesRemainder() {
		ManualTime time = new ManualTime();
		ClientCreateRateLimiter limiter = new ClientCreateRateLimiter(time);

		for (int i = 0; i < 5; i++) {
			assertTrue(limiter.tryAcquire());
		}

		time.now = 999;
		assertFalse(limiter.tryAcquire());
		time.now = 1000;
		assertTrue(limiter.tryAcquire());
		time.now = 1999;
		assertFalse(limiter.tryAcquire());
		time.now = 2000;
		assertTrue(limiter.tryAcquire());
	}

	@Test
	void refillCapsAtTheConfiguredLimit() {
		ManualTime time = new ManualTime();
		ClientCreateRateLimiter limiter = new ClientCreateRateLimiter(time);

		for (int i = 0; i < 5; i++) {
			assertTrue(limiter.tryAcquire());
		}

		time.now = 100_000;
		for (int i = 0; i < 5; i++) {
			assertTrue(limiter.tryAcquire());
		}
		assertFalse(limiter.tryAcquire());
	}

	@Test
	void zeroLimitOrZeroIntervalIsUnlimited() {
		ManualTime time = new ManualTime();
		ClientCreateRateLimiter zeroLimit = new ClientCreateRateLimiter(
			time, new ClientRateLimitPolicy(0, 1000));
		ClientCreateRateLimiter zeroInterval = new ClientCreateRateLimiter(
			time, new ClientRateLimitPolicy(5, 0));

		for (int i = 0; i < 20; i++) {
			assertTrue(zeroLimit.tryAcquire());
			assertTrue(zeroInterval.tryAcquire());
		}
	}

	@Test
	void limitOneNeedsOneRegenerationInterval() {
		ManualTime time = new ManualTime();
		ClientCreateRateLimiter limiter = new ClientCreateRateLimiter(
			time, new ClientRateLimitPolicy(1, 1000));

		assertTrue(limiter.tryAcquire());
		assertFalse(limiter.tryAcquire());
		time.now = 1000;
		assertTrue(limiter.tryAcquire());
	}

	@Test
	void rollbackClampsElapsedToZero() {
		ManualTime time = new ManualTime();
		ClientCreateRateLimiter limiter = new ClientCreateRateLimiter(time);

		for (int i = 0; i < 5; i++) {
			assertTrue(limiter.tryAcquire());
		}

		time.now = -1;
		assertFalse(limiter.tryAcquire());
		time.now = 999;
		assertFalse(limiter.tryAcquire());
		time.now = 1000;
		assertTrue(limiter.tryAcquire());
	}

	@Test
	void equalPolicyUpdatePreservesTheExistingBucket() {
		ManualTime time = new ManualTime();
		ClientCreateRateLimiter limiter = new ClientCreateRateLimiter(time);

		for (int i = 0; i < 5; i++) {
			assertTrue(limiter.tryAcquire());
		}
		limiter.applyPolicy(new ClientRateLimitPolicy(5, 1000));
		assertFalse(limiter.tryAcquire());
	}

	@Test
	void changedPolicyRetainsTimestampHistory() {
		ManualTime time = new ManualTime();
		ClientCreateRateLimiter limiter = new ClientCreateRateLimiter(time);

		assertTrue(limiter.tryAcquire());
		assertTrue(limiter.tryAcquire());
		limiter.applyPolicy(new ClientRateLimitPolicy(2, 1000));

		assertTrue(limiter.tryAcquire());
		assertTrue(limiter.tryAcquire());
		assertFalse(limiter.tryAcquire());
	}

	@Test
	void aFreshLimiterStartsWithAFullBucket() {
		ManualTime time = new ManualTime();
		ClientCreateRateLimiter exhausted = new ClientCreateRateLimiter(time);

		for (int i = 0; i < 5; i++) {
			assertTrue(exhausted.tryAcquire());
		}
		assertFalse(exhausted.tryAcquire());

		ClientCreateRateLimiter fresh = new ClientCreateRateLimiter(time);
		assertTrue(fresh.tryAcquire());
	}

	private static final class ManualTime implements InteractionTimeSource {
		private long now;

		@Override
		public long nowMillis() {
			return now;
		}
	}
}
