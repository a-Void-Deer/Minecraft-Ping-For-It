package nx.pingwheel.common.util;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitedDiagnosticsTest {

	@Test
	void productionFormattingRetainsLevelContextOriginalMessageCauseSuppressedAndStack() {
		Fixture fixture = new Fixture();
		RuntimeException cause = failure("cause registry=othermod:storage", "resolve");
		RuntimeException failure = new RuntimeException("top item={name:Alice}", cause);
		failure.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("provider.Inventory", "read", "Inventory.java", 17)
		});
		failure.addSuppressed(failure("suppressed component=othermod:data", "close"));

		fixture.diagnostics.report(Level.WARN, "inventory-read", "othermod:storage",
			() -> "position=(1,64,-2) requestId=91", failure);
		fixture.diagnostics.report(Level.ERROR, "inventory-read", "othermod:storage",
			() -> "position=(1,64,-2) requestId=92", failure);

		assertEquals(List.of(Level.WARN, Level.ERROR), fixture.sink.levels());
		for (Event event : fixture.sink.events) {
			assertTrue(event.message.contains("inventory-read"));
			assertTrue(event.message.contains("othermod:storage"));
			assertTrue(event.message.contains("position=(1,64,-2)"));
			assertTrue(event.message.contains("top item={name:Alice}"));
			assertTrue(event.message.contains("cause registry=othermod:storage"));
			assertTrue(event.message.contains("suppressed component=othermod:data"));
			assertTrue(event.message.contains("java.lang.RuntimeException"));
			assertTrue(event.message.contains("provider.Inventory.read (Inventory.java:17)"));
			assertTrue(event.message.contains("provider.Inventory.resolve"));
			assertTrue(event.message.contains("provider.Inventory.close"));
			assertTrue(event.message.length() <= SafeExceptionReport.MAX_CHARACTERS);
		}
	}

	@Test
	void disabledLevelDoesNotReadContextThrowableOrCreateBucket() {
		Fixture fixture = new Fixture();
		fixture.sink.enabled = false;
		AtomicInteger contextReads = new AtomicInteger();
		Throwable unreadable = new Throwable() {
			@Override
			public String getMessage() {
				throw new AssertionError("disabled diagnostics read the message");
			}

			@Override
			public StackTraceElement[] getStackTrace() {
				throw new AssertionError("disabled diagnostics read the stack");
			}
		};

		fixture.diagnostics.report(Level.WARN, "read", "provider", () -> {
			contextReads.incrementAndGet();
			return "context";
		}, unreadable);

		assertEquals(0, contextReads.get());
		assertEquals(0, fixture.diagnostics.bucketCount());
		assertTrue(fixture.sink.events.isEmpty());
	}

	@Test
	void duplicateWindowIgnoresMessageAndRequestChurnAndRecoversAtExactBoundary() {
		Fixture fixture = new Fixture();
		AtomicInteger contextReads = new AtomicInteger();
		AtomicInteger messageReads = new AtomicInteger();
		Throwable failure = new RuntimeException() {
			@Override
			public String getMessage() {
				return "request=" + messageReads.incrementAndGet();
			}
		};
		failure.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("provider.Inventory", "read", "Inventory.java", 17)
		});

		for (int request = 0; request < 20; request++) {
			fixture.diagnostics.report(Level.WARN, "read", "provider", () ->
				"request=" + contextReads.incrementAndGet(), failure);
		}
		fixture.clock.set(RateLimitedDiagnostics.INTERVAL_NANOS - 1);
		fixture.diagnostics.report(Level.WARN, "read", "provider", () -> {
			throw new AssertionError("suppressed context must remain lazy");
		}, failure);

		assertEquals(1, fixture.sink.events.size());
		assertEquals(1, contextReads.get());
		assertEquals(1, messageReads.get());

		fixture.clock.incrementAndGet();
		fixture.diagnostics.report(Level.WARN, "read", "provider", () ->
			"request=" + contextReads.incrementAndGet(), failure);

		assertEquals(2, fixture.sink.events.size());
		assertEquals(2, contextReads.get());
		assertEquals(2, messageReads.get());
		assertTrue(fixture.sink.events.get(1).message.contains("suppressed=20"));
	}

	@Test
	void differentScopeOperationExceptionTypeAndSourceLocationAreIndependent() {
		Fixture fixture = new Fixture();
		RuntimeException failure = failure("detail", "read");
		fixture.diagnostics.report(Level.WARN, "read", "provider-a", () -> "a", failure);
		fixture.diagnostics.report(Level.WARN, "read", "provider-b", () -> "b", failure);
		fixture.diagnostics.report(Level.WARN, "resolve", "provider-a", () -> "a", failure);
		IllegalArgumentException differentType = new IllegalArgumentException("detail");
		differentType.setStackTrace(failure.getStackTrace());
		fixture.diagnostics.report(Level.WARN, "read", "provider-a", () -> "a", differentType);
		fixture.diagnostics.report(Level.WARN, "read", "provider-a", () -> "a", failure("detail", "extract"));

		assertEquals(5, fixture.sink.events.size());
	}

	@Test
	void activeCapacityCannotBeEvictedIntoLogSpamAndExpiredEntriesRecover() {
		Fixture fixture = new Fixture();
		RuntimeException failure = failure("detail", "read");
		AtomicInteger contextReads = new AtomicInteger();
		for (int scope = 0; scope < RateLimitedDiagnostics.CAPACITY; scope++) {
			fixture.diagnostics.report(Level.WARN, "read", "provider-" + scope,
				() -> "context=" + contextReads.incrementAndGet(), failure);
		}
		for (int scope = 0; scope < 1000; scope++) {
			fixture.diagnostics.report(Level.WARN, "read", "overflow-" + scope,
				() -> { throw new AssertionError("overflow evaluated context"); }, failure);
		}

		assertEquals(RateLimitedDiagnostics.CAPACITY, fixture.diagnostics.bucketCount());
		assertEquals(RateLimitedDiagnostics.CAPACITY, contextReads.get());
		assertEquals(RateLimitedDiagnostics.CAPACITY + 1, fixture.sink.events.size());
		assertTrue(fixture.sink.events.getLast().message.contains("capacity"));
		fixture.diagnostics.report(Level.WARN, "read", "provider-0", () -> "must stay suppressed", failure);
		assertEquals(RateLimitedDiagnostics.CAPACITY + 1, fixture.sink.events.size());

		fixture.clock.set(RateLimitedDiagnostics.INTERVAL_NANOS);
		fixture.diagnostics.report(Level.WARN, "read", "recovered-provider", () -> "recovered", failure);

		assertEquals(1, fixture.diagnostics.bucketCount());
		assertEquals(RateLimitedDiagnostics.CAPACITY + 2, fixture.sink.events.size());
		assertTrue(fixture.sink.events.getLast().message.contains("recovered"));
	}

	@Test
	void overflowSummaryIsLowFrequencyAndCountsSuppressedNewFingerprints() {
		Fixture fixture = new Fixture();
		RuntimeException failure = failure("detail", "read");
		for (int scope = 0; scope < RateLimitedDiagnostics.CAPACITY; scope++) {
			fixture.diagnostics.report(Level.WARN, "read", "provider-" + scope, () -> "context", failure);
		}
		for (int scope = 0; scope < 20; scope++) {
			fixture.diagnostics.report(Level.WARN, "read", "overflow-" + scope, () -> "context", failure);
		}
		assertEquals(RateLimitedDiagnostics.CAPACITY + 1, fixture.sink.events.size());

		// Refresh the occupied slots first: overflow cannot evict these active
		// fingerprints, even when the overflow summary itself becomes eligible.
		fixture.clock.set(RateLimitedDiagnostics.INTERVAL_NANOS);
		for (int scope = 0; scope < RateLimitedDiagnostics.CAPACITY; scope++) {
			fixture.diagnostics.report(Level.WARN, "read", "provider-" + scope, () -> "context", failure);
		}
		fixture.diagnostics.report(Level.WARN, "read", "new-overflow", () -> "context", failure);

		assertEquals(2 * RateLimitedDiagnostics.CAPACITY + 2, fixture.sink.events.size());
		assertTrue(fixture.sink.events.getLast().message.contains("suppressed=20"));
		assertEquals(RateLimitedDiagnostics.CAPACITY, fixture.diagnostics.bucketCount());
	}

	@Test
	void elapsedWindowSurvivesMonotonicNanoTimeWraparound() {
		Fixture fixture = new Fixture();
		RuntimeException failure = failure("detail", "read");
		fixture.clock.set(Long.MAX_VALUE - RateLimitedDiagnostics.INTERVAL_NANOS / 2);
		fixture.diagnostics.report(Level.ERROR, "read", "provider", () -> "first", failure);
		fixture.clock.addAndGet(RateLimitedDiagnostics.INTERVAL_NANOS - 1);
		fixture.diagnostics.report(Level.ERROR, "read", "provider", () -> "suppressed", failure);
		assertEquals(1, fixture.sink.events.size());
		fixture.clock.incrementAndGet();
		fixture.diagnostics.report(Level.ERROR, "read", "provider", () -> "recovered", failure);
		assertEquals(2, fixture.sink.events.size());
	}

	@Test
	void hugeFieldsAreBoundedAndClearlyMarked() {
		Fixture fixture = new Fixture();
		String huge = "registry:" + "x".repeat(50_000);
		fixture.diagnostics.report(Level.ERROR, huge, huge, () -> huge, failure("original failure", "read"));

		String report = fixture.sink.events.getFirst().message;
		assertTrue(report.contains("[truncated]"));
		assertTrue(report.contains("original failure"));
		assertTrue(report.contains("provider.Inventory.read"));
		assertTrue(report.length() <= SafeExceptionReport.MAX_CHARACTERS);
	}

	@Test
	void longScopesSharingReadablePrefixStillHaveIndependentAdmissions() {
		Fixture fixture = new Fixture();
		String prefix = "provider." + "x".repeat(1000);
		RuntimeException failure = failure("detail", "read");
		fixture.diagnostics.report(Level.WARN, "read", prefix + "alpha", () -> "alpha", failure);
		fixture.diagnostics.report(Level.WARN, "read", prefix + "beta", () -> "beta", failure);

		assertEquals(2, fixture.sink.events.size());
	}

	@Test
	void runtimeAndLinkageContextFailuresStillReportOriginalThrowable() {
		for (boolean linkage : List.of(false, true)) {
			Fixture fixture = new Fixture();
			fixture.diagnostics.report(Level.WARN, "inventory-read", "provider", () -> {
				if (linkage) {
					throw new NoClassDefFoundError("context failed");
				}
				throw new IllegalStateException("context failed");
			}, failure("original failure", "read"));

			String report = fixture.sink.events.getFirst().message;
			assertTrue(report.contains("context unavailable"));
			assertTrue(report.contains("inventory-read"));
			assertTrue(report.contains("provider"));
			assertTrue(report.contains("original failure"));
			assertTrue(report.contains("provider.Inventory.read"));
		}
	}

	@Test
	void runtimeAndLinkageSinkFailuresCannotBreakBusinessOperation() {
		for (boolean enabledFailure : List.of(false, true)) {
			for (boolean linkage : List.of(false, true)) {
				RateLimitedDiagnostics diagnostics = new RateLimitedDiagnostics(() -> 0,
					new RateLimitedDiagnostics.Sink() {
						@Override
						public boolean isEnabled(Level level) {
							if (enabledFailure) {
								fail();
							}
							return true;
						}

						@Override
						public void log(Level level, String message) {
							fail();
						}

						private void fail() {
							if (linkage) {
								throw new NoClassDefFoundError("sink failed");
							}
							throw new IllegalStateException("sink failed");
						}
					});

				assertDoesNotThrow(() -> diagnostics.report(Level.ERROR, "read", "provider",
					() -> "context", failure("original failure", "read")));
			}
		}
	}

	@Test
	void fatalContextErrorIsNotSwallowed() {
		Fixture fixture = new Fixture();
		assertThrows(OutOfMemoryError.class, () -> fixture.diagnostics.report(Level.WARN,
			"read", "provider", () -> { throw new OutOfMemoryError("fatal"); }, failure("detail", "read")));
	}

	@Test
	void concurrentDuplicateAdmissionsEmitOnlyOnce() throws Exception {
		Fixture fixture = new Fixture();
		RuntimeException failure = failure("detail", "read");
		AtomicInteger contextReads = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(4)) {
			List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
			for (int index = 0; index < 32; index++) {
				tasks.add(executor.submit(() -> {
					start.await();
					fixture.diagnostics.report(Level.WARN, "read", "provider", () ->
						"context=" + contextReads.incrementAndGet(), failure);
					return null;
				}));
			}
			start.countDown();
			for (var task : tasks) {
				task.get(5, TimeUnit.SECONDS);
			}
		}

		assertEquals(1, fixture.sink.events.size());
		assertEquals(1, contextReads.get());
	}

	private static RuntimeException failure(String message, String method) {
		RuntimeException failure = new RuntimeException(message);
		failure.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("provider.Inventory", method, "Inventory.java", 17)
		});
		return failure;
	}

	private record Event(Level level, String message) {}

	private static final class RecordingSink implements RateLimitedDiagnostics.Sink {
		private boolean enabled = true;
		private final List<Event> events = new ArrayList<>();

		@Override
		public boolean isEnabled(Level level) {
			return enabled;
		}

		@Override
		public synchronized void log(Level level, String message) {
			events.add(new Event(level, message));
		}

		private List<Level> levels() {
			return events.stream().map(Event::level).toList();
		}
	}

	private static final class Fixture {
		private final AtomicLong clock = new AtomicLong();
		private final RecordingSink sink = new RecordingSink();
		private final RateLimitedDiagnostics diagnostics = new RateLimitedDiagnostics(clock::get, sink);
	}
}
