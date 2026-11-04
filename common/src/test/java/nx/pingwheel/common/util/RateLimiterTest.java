package nx.pingwheel.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertEquals;
import static nx.pingwheel.common.testing.Assertions.assertFalse;
import static nx.pingwheel.common.testing.Assertions.assertTrue;

/**
 * Contract tests for {@link RateLimiter}, written for this port.
 *
 * <p>Upstream ships no {@code RateLimiter} test (review 65 G2), so the behaviour is
 * pinned here directly against the implementation: a token bucket with
 * {@code limit} tokens per {@code timeToRegenerate * limit} window, refilling one
 * token every {@code timeToRegenerate}. {@link RateLimiter#checkExceeded()} returns
 * {@code false} while a token is available and {@code true} once the budget is spent.
 *
 * <p>Timing uses generous margins ({@code regen} in the hundreds of ms) so the cases
 * stay deterministic on a loaded machine instead of asserting exact millisecond maths.
 */
class RateLimiterTest {

	/** regen=1000ms, limit=3 -> 3 tokens per 3s window. */
	private static void configureThreePerSecond() {
		RateLimiter.setRates(1000L, 3L);
	}

	@Test
	void firstCheckIsAlwaysAllowed() {
		configureThreePerSecond();

		RateLimiter limiter = new RateLimiter();

		assertFalse(limiter.checkExceeded(), "a fresh limiter must let the first call through");
	}

	@Test
	void allowsExactlyTheConfiguredLimit() {
		configureThreePerSecond();
		RateLimiter limiter = new RateLimiter();

		assertFalse(limiter.checkExceeded(), "1st of 3");
		assertFalse(limiter.checkExceeded(), "2nd of 3");
		assertFalse(limiter.checkExceeded(), "3rd of 3");
		assertTrue(limiter.checkExceeded(), "4th call in the same window must be rejected");
	}

	@Test
	void rejectionStaysTrueAfterTheBudgetIsSpent() {
		configureThreePerSecond();
		RateLimiter limiter = new RateLimiter();
		limiter.checkExceeded();
		limiter.checkExceeded();
		limiter.checkExceeded();
		assertTrue(limiter.checkExceeded(), "first rejection");

		assertTrue(limiter.checkExceeded(), "and it stays rejected while no token is refilled");
		assertTrue(limiter.checkExceeded(), "repeated polling must not refill the bucket");
	}

	@Test
	void oneTokenRefillsAfterOneRegenerateInterval() throws Exception {
		// regen=500ms, limit=2 -> window=1000ms; a 600ms pause refills exactly one token.
		RateLimiter.setRates(500L, 2L);
		RateLimiter limiter = new RateLimiter();
		assertFalse(limiter.checkExceeded(), "1st of 2");
		assertFalse(limiter.checkExceeded(), "2nd of 2");
		assertTrue(limiter.checkExceeded(), "budget is spent");

		Thread.sleep(600L);

		assertFalse(limiter.checkExceeded(), "one token must have been refilled by now");
		assertTrue(limiter.checkExceeded(), "the refilled token is consumed by the previous call");
	}

	@Test
	void idlingForAFullWindowRestoresTheWholeBudget() throws Exception {
		// regen=200ms, limit=2 -> window=400ms; idling longer than the window saturates the bucket.
		RateLimiter.setRates(200L, 2L);
		RateLimiter limiter = new RateLimiter();
		assertFalse(limiter.checkExceeded(), "1st of 2");
		assertFalse(limiter.checkExceeded(), "2nd of 2");
		assertTrue(limiter.checkExceeded(), "budget is spent");

		Thread.sleep(500L);

		assertFalse(limiter.checkExceeded(), "idle time beyond the window restores token 1");
		assertFalse(limiter.checkExceeded(), "idle time beyond the window restores token 2");
		assertTrue(limiter.checkExceeded(), "and only limit tokens come back");
	}

	@Test
	void freshInstanceStartsWithItsOwnFullBudget() {
		configureThreePerSecond();
		RateLimiter spent = new RateLimiter();
		spent.checkExceeded();
		spent.checkExceeded();
		spent.checkExceeded();
		assertTrue(spent.checkExceeded(), "the first limiter is exhausted");

		RateLimiter fresh = new RateLimiter();

		assertFalse(fresh.checkExceeded(), "a new limiter must not inherit the spent budget");
	}

	@Test
	void zeroRegenerateIntervalNeverRejects() {
		// regen=0ms, limit=1 -> window=0ms: elapsed is clamped to 0 and leftOver never goes negative.
		RateLimiter.setRates(0L, 1L);
		RateLimiter limiter = new RateLimiter();

		for (int i = 0; i < 1000; i++) {
			assertFalse(limiter.checkExceeded(), "call " + i + " with a 0ms regenerate interval");
		}
	}

	@Test
	void largeWindowStillEnforcesTheLimit() {
		// regen=1h, limit=5 -> window=5h; the burst is still capped at 5.
		RateLimiter.setRates(3_600_000L, 5L);
		RateLimiter limiter = new RateLimiter();

		for (int i = 1; i <= 5; i++) {
			assertFalse(limiter.checkExceeded(), "call " + i + " of 5 inside a 5h window");
		}
		assertTrue(limiter.checkExceeded(), "the 6th call inside a 5h window must be rejected");
	}

	@Test
	void independentInstancesDoNotShareTheirBudget() {
		RateLimiter.setRates(1000L, 2L);
		RateLimiter first = new RateLimiter();
		RateLimiter second = new RateLimiter();

		assertFalse(first.checkExceeded(), "first/1");
		assertFalse(first.checkExceeded(), "first/2");
		assertTrue(first.checkExceeded(), "first is exhausted");

		assertFalse(second.checkExceeded(), "second/1 must still be allowed");
		assertFalse(second.checkExceeded(), "second/2 must still be allowed");
		assertTrue(second.checkExceeded(), "second is exhausted too");
	}

	@Test
	void concurrentChecksOnSeparateInstancesStayWithinTheirOwnBudgets() throws Exception {
		RateLimiter.setRates(1000L, 3L);
		int threads = 4;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();

		try {
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					// One limiter per thread: the shared static configuration is read-only here,
					// so the per-instance budget must still be exactly 3 allowed calls.
					RateLimiter limiter = new RateLimiter();
					start.await(5, TimeUnit.SECONDS);
					int allowed = 0;
					for (int call = 0; call < 10; call++) {
						if (!limiter.checkExceeded()) {
							allowed++;
						}
					}
					return allowed;
				}));
			}
			start.countDown();
			for (Future<Integer> result : results) {
				assertEquals(3, result.get(10, TimeUnit.SECONDS).intValue(),
					"each limiter must allow exactly 3 of 10 rapid calls");
			}
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * Same-instance concurrency, which is <strong>not</strong> a guarantee of this class.
	 *
	 * <p>{@link RateLimiter} mirrors upstream exactly: one non-volatile {@code startTime}
	 * field, read-modify-written by {@link RateLimiter#checkExceeded()} with no
	 * synchronization. Two threads can therefore read the same {@code startTime} and both be
	 * admitted, so the admitted total is <em>not</em> capped at {@code limit} under real
	 * concurrency (a local 200-round probe of 8 threads x 25 calls admitted between 3 and 39
	 * tokens with {@code limit = 3}). Production call sites
	 * own one limiter per player and only touch it from the server thread, so this is
	 * acceptable — but a test must not pretend otherwise.
	 *
	 * <p>What <em>is</em> guaranteed and asserted here: concurrent polling never loses the
	 * configured budget. If the implementation ever advanced {@code startTime} without
	 * handing out a token, the admitted total would drop below {@code limit} and this case
	 * fails.
	 */
	@Test
	void concurrentChecksOnOneInstanceNeverLoseTheBudget() throws Exception {
		// regen = 1h, limit = 3 -> the burst itself cannot refill anything.
		RateLimiter.setRates(3_600_000L, 3L);
		RateLimiter shared = new RateLimiter();
		int threads = 8;
		int callsPerThread = 25;
		AtomicInteger allowed = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();

		try {
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					start.await(5, TimeUnit.SECONDS);
					for (int call = 0; call < callsPerThread; call++) {
						if (!shared.checkExceeded()) {
							allowed.incrementAndGet();
						}
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get(10, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		assertTrue(allowed.get() >= 3,
			"concurrent polling must not lose the configured budget, admitted " + allowed.get());
	}
}
