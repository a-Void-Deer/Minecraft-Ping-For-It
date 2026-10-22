package nx.pingwheel.neoforge.integration.create;

import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class CreateEntityOutlineBackendProbeTest {

	@Test
	void disabledWarningDoesNotReadTheActualProbeFailuresMessageCauseOrStack() {
		AtomicInteger lookups = new AtomicInteger();
		UnreadableLinkageFailure failure = new UnreadableLinkageFailure();
		CreateEntityOutlineBackendProbe probe = new CreateEntityOutlineBackendProbe("test.disabledProbe", "supports", name -> {
			lookups.incrementAndGet();
			throw failure;
		});
		try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture(Level.OFF)) {
			assertFalse(nx.pingwheel.common.diagnostics.Diagnostics.logger().isEnabled(Level.WARN),
				"the fixture must disable WARN despite the loader's global filter");
			assertFalse(probe.availability().available());
			assertEquals("probe-failed", probe.diagnosticDescription());
			assertFalse(probe.availability().available());
			assertEquals(1, lookups.get());
			assertEquals(0, failure.reads.get());
			assertTrue(capture.events.isEmpty());
		}
	}

	@Test
	void theProbePublishesOnlyAvailabilityAndFormatsItsOriginalFailureAfterReleasingTheMonitor() {
		AtomicReference<@Nullable CreateEntityOutlineBackendProbe> owner = new AtomicReference<>();
		LinkageError failure = new OutsideMonitorFailure(owner);
		failure.initCause(new IllegalArgumentException("probe original cause"));
		CreateEntityOutlineBackendProbe probe = new CreateEntityOutlineBackendProbe("test.outsideLockProbe", "supports", name -> {
			throw failure;
		});
		owner.set(probe);
		try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture()) {
			assertFalse(probe.availability().available());
			assertFalse(probe.availability().available());
			assertEquals(1, capture.events.size());
			String message = capture.events.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("probe original"));
			assertTrue(message.contains("probe original cause"));
			assertTrue(message.length() <= SafeExceptionReport.MAX_CHARACTERS);
			assertNull(capture.events.getFirst().getThrown());
		}
	}

	@Test
	void aSuppressedActualProbeFailureDoesNotReadMessageOrCauseForACachedReport() {
		ScalarReadFailure failure = new ScalarReadFailure();
		try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture()) {
			for (int index = 0; index < 2; index++) {
				CreateEntityOutlineBackendProbe probe = new CreateEntityOutlineBackendProbe("test.suppressedProbe", "supports", name -> {
					throw failure;
				});
				assertFalse(probe.availability().available());
				if (index == 0) {
					assertTrue(failure.scalarReads.get() > 0);
					failure.scalarReads.set(0);
				}
			}
			assertEquals(0, failure.scalarReads.get());
			assertEquals(1, capture.events.size());
		}
	}

	@Test
	void concurrentAvailabilityRequestsStillPerformExactlyOneProbe() throws Exception {
		AtomicInteger lookups = new AtomicInteger();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CreateEntityOutlineBackendProbe probe = new CreateEntityOutlineBackendProbe("test.concurrentProbe", "supports", name -> {
			lookups.incrementAndGet();
			entered.countDown();
			try {
				assertTrue(release.await(5, TimeUnit.SECONDS));
			} catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
				throw new AssertionError(failure);
			}
			throw new ClassNotFoundException(name);
		});
		try (var pool = Executors.newFixedThreadPool(4); CreateDiagnosticCapture capture = new CreateDiagnosticCapture()) {
			List<Future<CreateEntityOutlineBackendProbe.Availability>> requests = new ArrayList<>();
			requests.add(pool.submit(probe::availability));
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			for (int index = 0; index < 6; index++) {
				requests.add(pool.submit(probe::availability));
			}
			release.countDown();
			CreateEntityOutlineBackendProbe.Availability first = requests.getFirst().get(5, TimeUnit.SECONDS);
			for (Future<CreateEntityOutlineBackendProbe.Availability> request : requests) {
				assertSame(first, request.get(5, TimeUnit.SECONDS));
			}
			assertEquals(1, lookups.get());
			assertEquals("class-missing", first.description());
			assertTrue(capture.events.isEmpty(), "normal optional class absence stays silent");
		} finally {
			release.countDown();
		}
	}

	@NotNullByDefault
	private static final class UnreadableLinkageFailure extends LinkageError {
		private final AtomicInteger reads = new AtomicInteger();

		@Override public String getMessage() { return unexpectedRead(); }
		@Override public synchronized @Nullable Throwable getCause() { unexpectedRead(); return null; }
		@Override public StackTraceElement @Unmodifiable [] getStackTrace() { unexpectedRead(); return new StackTraceElement @Unmodifiable [0]; }

		private String unexpectedRead() {
			reads.incrementAndGet();
			throw new AssertionError("disabled diagnostics read throwable details");
		}
	}

	@NotNullByDefault
	private static final class OutsideMonitorFailure extends LinkageError {
		private final AtomicReference<@Nullable CreateEntityOutlineBackendProbe> owner;

		private OutsideMonitorFailure(AtomicReference<@Nullable CreateEntityOutlineBackendProbe> owner) {
			this.owner = owner;
		}

		@Override public String getMessage() {
			@Nullable CreateEntityOutlineBackendProbe probe = owner.get();
			assertNotNull(probe);
			assertFalse(Thread.holdsLock(probe), "formatting must not run under the single-probe monitor");
			assertEquals("probe-failed", probe.diagnosticDescription(), "publish cached status before reporting");
			return "probe original";
		}
	}

	@NotNullByDefault
	private static final class ScalarReadFailure extends LinkageError {
		private final AtomicInteger scalarReads = new AtomicInteger();

		@Override public String getMessage() { scalarReads.incrementAndGet(); return "probe scalar failure"; }
		@Override public synchronized @Nullable Throwable getCause() { scalarReads.incrementAndGet(); return null; }
	}
}
