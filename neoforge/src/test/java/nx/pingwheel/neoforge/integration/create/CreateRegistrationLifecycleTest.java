package nx.pingwheel.neoforge.integration.create;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class CreateRegistrationLifecycleTest {
	@Test void sameThreadReentrantRegisterFailsFastDuringInstallAndCleanupWithoutConsumingEitherOperation() throws Exception {
		assertReentryRejected(true);
	}

	@Test void sameThreadReentrantCloseFailsFastDuringInstallAndCleanupWithoutPretendingToClose() throws Exception {
		assertReentryRejected(false);
	}

	private static void assertReentryRejected(boolean register) throws Exception {
		var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
		var task = executor.submit(() -> {
			var lifecycle = new CreateRegistrationLifecycle<TestHandle>();
			var installs = new AtomicInteger(); var closes = new AtomicInteger();
			Runnable reenter = register ? () -> lifecycle.register(() -> {
				fail("reentrant register must not begin a nested installation"); return new TestHandle(() -> {});
			}) : () -> lifecycle.close();
			var installFailure = assertThrows(IllegalStateException.class, () -> lifecycle.register(() -> {
				installs.incrementAndGet(); reenter.run(); return new TestHandle(closes::incrementAndGet);
			}));
			assertEquals("create-registration-lifecycle-reentry", installFailure.getMessage());
			assertEquals(1, installs.get()); assertEquals(0, closes.get()); assertEquals("not-registered", lifecycle.state());
			assertNotNull(lifecycle.register(() -> new TestHandle(() -> { closes.incrementAndGet(); reenter.run(); })));
			var closeFailure = assertThrows(IllegalStateException.class, lifecycle::close);
			assertEquals("create-registration-lifecycle-reentry", closeFailure.getMessage());
			assertEquals(1, closes.get()); assertEquals("not-registered", lifecycle.state());
			assertNotNull(lifecycle.register(() -> new TestHandle(closes::incrementAndGet)));
			assertEquals("registered", lifecycle.state()); assertTrue(lifecycle.close()); assertFalse(lifecycle.close());
			assertEquals(2, closes.get());
		});
		try { task.get(5, TimeUnit.SECONDS); }
		finally {
			// A regressed uninterruptible self-wait cannot keep the test worker alive or block executor.close().
			task.cancel(true); executor.shutdownNow();
		}
	}

	@Test void concurrentRegistrationsInstallOnlyOneHandle() throws Exception {
		var lifecycle = new CreateRegistrationLifecycle<TestHandle>();
		var installing = new CountDownLatch(1); var release = new CountDownLatch(1); var secondCalled = new CountDownLatch(1);
		var installs = new AtomicInteger(); var closes = new AtomicInteger();
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> lifecycle.register(() -> {
				installs.incrementAndGet(); installing.countDown(); await(release); return new TestHandle(closes::incrementAndGet);
			}));
			try {
				assertTrue(installing.await(5, TimeUnit.SECONDS));
				var second = executor.submit(() -> { secondCalled.countDown(); return lifecycle.register(() -> {
					installs.incrementAndGet(); return new TestHandle(closes::incrementAndGet);
				}); });
				assertTrue(secondCalled.await(5, TimeUnit.SECONDS)); assertFalse(second.isDone());
				release.countDown(); assertNotNull(first.get(5, TimeUnit.SECONDS)); assertNull(second.get(5, TimeUnit.SECONDS));
				assertEquals(1, installs.get()); assertTrue(lifecycle.close()); assertEquals(1, closes.get());
			} finally { release.countDown(); }
		}
	}

	@Test void concurrentRegisterAndCloseStaySerializedWhileStateReadsNeverWaitForInstallOrCleanup() throws Exception {
		var lifecycle = new CreateRegistrationLifecycle<TestHandle>();
		var installing = new CountDownLatch(1); var releaseInstall = new CountDownLatch(1);
		var closing = new CountDownLatch(1); var releaseClose = new CountDownLatch(1);
		var closeCalled = new CountDownLatch(1); var registerCalled = new CountDownLatch(1);
		var installs = new AtomicInteger(); var closes = new AtomicInteger();
		try (var executor = Executors.newFixedThreadPool(3)) {
			var first = executor.submit(() -> lifecycle.register(() -> {
				assertFalse(Thread.holdsLock(lifecycle)); installs.incrementAndGet(); installing.countDown(); await(releaseInstall);
				return new TestHandle(() -> {
					assertFalse(Thread.holdsLock(lifecycle)); closing.countDown(); await(releaseClose); closes.incrementAndGet();
				});
			}));
			try {
				assertTrue(installing.await(5, TimeUnit.SECONDS));
				assertEquals("not-registered", executor.submit(lifecycle::state).get(5, TimeUnit.SECONDS));
				var close = executor.submit(() -> { closeCalled.countDown(); return lifecycle.close(); });
				assertTrue(closeCalled.await(5, TimeUnit.SECONDS)); assertFalse(close.isDone());
				releaseInstall.countDown(); assertNotNull(first.get(5, TimeUnit.SECONDS));
				assertTrue(closing.await(5, TimeUnit.SECONDS));
				assertEquals("registered", executor.submit(lifecycle::state).get(5, TimeUnit.SECONDS));
				var next = executor.submit(() -> { registerCalled.countDown(); return lifecycle.register(() -> {
					installs.incrementAndGet(); return new TestHandle(closes::incrementAndGet);
				}); });
				assertTrue(registerCalled.await(5, TimeUnit.SECONDS)); assertFalse(next.isDone());
				assertEquals(1, installs.get(), "new installation cannot overtake cleanup");
				releaseClose.countDown(); assertTrue(close.get(5, TimeUnit.SECONDS)); assertNotNull(next.get(5, TimeUnit.SECONDS));
				assertEquals(2, installs.get()); assertEquals(1, closes.get());
				assertNull(lifecycle.register(() -> { fail("idempotent registration must not install again"); return new TestHandle(() -> {}); }));
				assertTrue(lifecycle.close()); assertFalse(lifecycle.close()); assertEquals(2, closes.get());
			} finally { releaseInstall.countDown(); releaseClose.countDown(); }
		}
	}

	@Test void failedInstallOrCleanupReleasesTheReservationAndAllowsRetry() {
		var lifecycle = new CreateRegistrationLifecycle<TestHandle>();
		var original = new LinkageError("install original");
		assertSame(original, assertThrows(LinkageError.class, () -> lifecycle.register(() -> { throw original; })));
		assertEquals("not-registered", lifecycle.state());
		var cleanupFailure = new IllegalStateException("cleanup original");
		assertNotNull(lifecycle.register(() -> new TestHandle(() -> { throw cleanupFailure; })));
		assertSame(cleanupFailure, assertThrows(IllegalStateException.class, lifecycle::close));
		assertEquals("not-registered", lifecycle.state()); assertFalse(lifecycle.close());
		assertNotNull(lifecycle.register(() -> new TestHandle(() -> {}))); assertTrue(lifecycle.close());
	}

	@Test void interruptedTeardownStillWaitsForInstallationAndRestoresTheInterrupt() throws Exception {
		var lifecycle = new CreateRegistrationLifecycle<TestHandle>();
		var installing = new CountDownLatch(1); var release = new CountDownLatch(1); var closeCalled = new CountDownLatch(1);
		var closes = new AtomicInteger();
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> lifecycle.register(() -> {
				installing.countDown(); await(release); return new TestHandle(closes::incrementAndGet);
			}));
			try {
				assertTrue(installing.await(5, TimeUnit.SECONDS));
				var close = executor.submit(() -> {
					Thread.currentThread().interrupt(); closeCalled.countDown();
					assertTrue(lifecycle.close()); return Thread.interrupted();
				});
				assertTrue(closeCalled.await(5, TimeUnit.SECONDS)); assertFalse(close.isDone()); release.countDown();
				assertNotNull(first.get(5, TimeUnit.SECONDS)); assertTrue(close.get(5, TimeUnit.SECONDS));
				assertEquals(1, closes.get()); assertEquals("not-registered", lifecycle.state());
			} finally { release.countDown(); }
		}
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
		catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
	}

	@NotNullByDefault
	private record TestHandle(Runnable cleanup) implements CreateRegistrationLifecycle.Handle {
		@Override public String state() { return "registered"; }
		@Override public void close() { cleanup.run(); }
	}
}
