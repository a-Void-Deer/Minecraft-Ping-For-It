package nx.pingwheel.common.diagnostics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class DiagnosticFaultReporterTest {
	@Test
	void publicCrossOwnerEntryKeepsControlledSitesWithoutReadingARejectedFailure() throws Exception {
		var method = DiagnosticFaultReporter.class.getMethod("report", DiagnosticSide.class, String.class,
			DiagnosticFaultReporter.Stage.class, LoggingProfile.class, Throwable.class);
		assertTrue(Modifier.isPublic(method.getModifiers()));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		DiagnosticFaultReporter reporter = new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(() -> output));
		AtomicInteger reads = new AtomicInteger();
		Throwable unreadable = new RuntimeException() {
			@Override public String getMessage() { reads.incrementAndGet(); return "must stay unread"; }
			@Override public StackTraceElement @Unmodifiable [] getStackTrace() { reads.incrementAndGet(); return new StackTraceElement[0]; }
		};
		assertThrows(IllegalArgumentException.class, () -> reporter.report(DiagnosticSide.CLIENT,
			"dynamic provider name", DiagnosticFaultReporter.Stage.CONTEXT, LoggingProfile.DEBUG, unreadable));
		assertEquals(0, reads.get());
		assertTrue(reporter.observations().isEmpty());
		assertTrue(output.toString(StandardCharsets.UTF_8).isEmpty());
		reporter.report(DiagnosticSide.CLIENT, "create-entity-outline-gate",
			DiagnosticFaultReporter.Stage.CHANNEL_CLOCK, LoggingProfile.NORMAL, unreadable);
		assertEquals(0, reads.get());
		assertEquals(1, records(output).size());
		assertTrue(records(output).getFirst().contains("site=create-entity-outline-gate stage=CHANNEL_CLOCK kind=RUNTIME"));
	}

	@Test
	void publicReportDerivesKindFromReentryStageThenLinkageThenRuntime() {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		DiagnosticFaultReporter reporter = new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(() -> output));
		reporter.report(DiagnosticSide.CLIENT, "reentry-site", DiagnosticFaultReporter.Stage.REENTRY,
			LoggingProfile.NORMAL, new NoClassDefFoundError("reentry"));
		reporter.report(DiagnosticSide.CLIENT, "linkage-site", DiagnosticFaultReporter.Stage.CONTEXT,
			LoggingProfile.NORMAL, new NoClassDefFoundError("linkage"));
		reporter.report(DiagnosticSide.CLIENT, "runtime-site", DiagnosticFaultReporter.Stage.CONTEXT,
			LoggingProfile.NORMAL, new AssertionError("runtime"));
		reporter.report(DiagnosticSide.CLIENT, "null-site", DiagnosticFaultReporter.Stage.CONTEXT,
			LoggingProfile.NORMAL, null);
		List<DiagnosticFaultReporter.Kind> kinds = reporter.observations().stream()
			.map(value -> value.key().kind()).toList();
		assertTrue(kinds.containsAll(List.of(DiagnosticFaultReporter.Kind.REENTRY,
			DiagnosticFaultReporter.Kind.LINKAGE, DiagnosticFaultReporter.Kind.RUNTIME)));
		assertEquals(4, kinds.size());
		assertEquals(4, records(output).size());
	}

	@Test
	void normalUsesOnlyControlledIdentityAndLaterRealFaultAddsDetailsOnceWithoutReplay() {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		DiagnosticFaultReporter reporter = new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(() -> output));
		AtomicInteger reads = new AtomicInteger();
		RuntimeException original = new RuntimeException() {
			@Override public String getMessage() { reads.incrementAndGet(); return "original-secret"; }
			@Override public StackTraceElement @Unmodifiable [] getStackTrace() { throw new AssertionError("No self graph"); }
			@Override public @Nullable Throwable getCause() { throw new AssertionError("No self cause"); }
			@Override public String toString() { throw new AssertionError("No self toString"); }
		};
		reporter.report(DiagnosticSide.CLIENT, "first-site", DiagnosticFaultReporter.Stage.SINK, LoggingProfile.NORMAL, original);
		reporter.report(DiagnosticSide.CLIENT, "first-site", DiagnosticFaultReporter.Stage.SINK, LoggingProfile.NORMAL, original);
		assertEquals(0, reads.get());
		assertEquals(1, records(output).size());
		assertFalse(output.toString(StandardCharsets.UTF_8).contains("original-secret"));
		// Raising the global profile emits nothing, and retains no exception to replay.
		LoggingProfile previous = Diagnostics.profile(DiagnosticSide.CLIENT);
		try {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			assertEquals(1, records(output).size());
			reporter.report(DiagnosticSide.CLIENT, "first-site", DiagnosticFaultReporter.Stage.SINK, LoggingProfile.DEEP,
				new IllegalStateException("fresh-secret"));
			reporter.report(DiagnosticSide.CLIENT, "first-site", DiagnosticFaultReporter.Stage.SINK, LoggingProfile.DEBUG, original);
			reporter.report(DiagnosticSide.CLIENT, "first-site", DiagnosticFaultReporter.Stage.SINK, LoggingProfile.NORMAL, original);
			assertEquals(2, records(output).size());
			assertTrue(records(output).getLast().contains("fresh-secret"));
			assertFalse(output.toString(StandardCharsets.UTF_8).contains("original-secret"));
			assertEquals(0, reads.get());
			DiagnosticFaultReporter.Observation observation = reporter.observations().getFirst();
			assertEquals(5, observation.occurrences());
			assertEquals(3, observation.repeats());
			assertEquals(3, observation.attemptedDisclosure());
		} finally {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, previous);
		}
	}

	@Test
	void sideSiteStageAndKindAreIndependentAndCounterSaturates() {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		DiagnosticFaultReporter reporter = new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(() -> output));
		for (DiagnosticSide side : DiagnosticSide.values()) {
			for (String site : List.of("read", "write")) {
				for (DiagnosticFaultReporter.Stage stage : List.of(DiagnosticFaultReporter.Stage.SINK, DiagnosticFaultReporter.Stage.ENABLED)) {
					for (Throwable failure : List.of(new IllegalStateException(), new NoClassDefFoundError())) {
						reporter.report(side, site, stage, LoggingProfile.NORMAL, failure);
					}
				}
			}
		}
		assertEquals(24, records(output).size());
		assertEquals(24, reporter.observations().size());
		assertThrows(UnsupportedOperationException.class, () -> reporter.observations().clear());
		assertEquals(Long.MAX_VALUE, DiagnosticFaultReporter.increment(Long.MAX_VALUE));
		assertEquals(Long.MAX_VALUE, DiagnosticFaultReporter.increment(Long.MAX_VALUE - 1));
		assertEquals(Long.MAX_VALUE, DiagnosticFaultReporter.add(Long.MAX_VALUE - 2, 3));
	}

	@Test
	void firstDetailedAttemptMarksBothLayersAndDoesNotEmitAgainAfterProfileChanges() {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		DiagnosticFaultReporter reporter = new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(() -> output));
		for (LoggingProfile profile : List.of(LoggingProfile.DEEP, LoggingProfile.NORMAL, LoggingProfile.DEBUG, LoggingProfile.DEEP)) {
			reporter.report(DiagnosticSide.BOOTSTRAP, "bootstrap-fault", DiagnosticFaultReporter.Stage.SETUP, profile,
				new IllegalStateException("class and message only"));
		}
		assertEquals(1, records(output).size());
		assertTrue(records(output).getFirst().contains("class=java.lang.IllegalStateException message=class and message only"));
		assertFalse(records(output).getFirst().contains("at nx."));
		assertEquals(3, reporter.observations().getFirst().attemptedDisclosure());
		assertEquals(3, reporter.observations().getFirst().repeats());
	}

	@Test
	void ownFormatterFailureUsesFixedFallbackOnceAndRecursiveAccessorsTerminate() {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		DiagnosticFaultReporter reporter = new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(() -> output));
		Throwable hostile = new RuntimeException() {
			@Override public String getMessage() {
				reporter.report(DiagnosticSide.CLIENT, "recursive", DiagnosticFaultReporter.Stage.CONTEXT, LoggingProfile.DEBUG, this);
				throw new IllegalStateException("self-formatter-secret");
			}
		};
		reporter.report(DiagnosticSide.CLIENT, "first", DiagnosticFaultReporter.Stage.CONTEXT, LoggingProfile.DEBUG, hostile);
		reporter.report(DiagnosticSide.CLIENT, "second", DiagnosticFaultReporter.Stage.CONTEXT, LoggingProfile.DEBUG, hostile);
		assertEquals(List.of("[PingForIt diagnostic-fault] self-format-unavailable"), records(output));
		assertEquals(2, reporter.formattingFailures());
		assertEquals(2, reporter.recursiveFaults());
	}

	@Test
	void concurrentDisclosuresAtomicallyClaimOneAttemptPerLayerAndInitializeWriterOnce() throws Exception {
		CountingOutput output = new CountingOutput();
		DiagnosticEmergencyWriter writer = new DiagnosticEmergencyWriter(() -> output);
		DiagnosticFaultReporter reporter = new DiagnosticFaultReporter(writer);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(8)) {
			List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
			for (int index = 0; index < 64; index++) tasks.add(executor.submit(() -> {
				try { start.await(); } catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
				}
				reporter.report(DiagnosticSide.SERVER, "same-site", DiagnosticFaultReporter.Stage.SINK, LoggingProfile.NORMAL,
					new IllegalStateException("hidden"));
			}));
			start.countDown();
			for (var task : tasks) task.get(5, TimeUnit.SECONDS);
		}
		assertEquals(1, writer.initializations());
		assertEquals(1, output.writes.get());
		assertEquals(1, output.flushes.get());
		assertEquals(0, output.closes.get());
		assertEquals(64, reporter.observations().getFirst().occurrences());
		assertEquals(63, reporter.observations().getFirst().repeats());
	}

	@Test
	void writerPublishesOneInstanceWithCompleteShortRecordsAndFlushesEachConcurrentWrite() throws Exception {
		CountingOutput output = new CountingOutput();
		DiagnosticEmergencyWriter writer = new DiagnosticEmergencyWriter(() -> output);
		try (var executor = Executors.newFixedThreadPool(8)) {
			List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
			for (int index = 0; index < 64; index++) {
				int record = index;
				tasks.add(executor.submit(() -> writer.write("record=" + record)));
			}
			for (var task : tasks) task.get(5, TimeUnit.SECONDS);
		}
		assertEquals(1, writer.initializations());
		assertEquals(64, output.writes.get());
		assertEquals(64, output.flushes.get());
		assertEquals(64, records(output.bytes).stream().distinct().count());
		assertTrue(records(output.bytes).stream().allMatch(line -> line.matches("record=[0-9]+")));
		assertEquals(0, output.closes.get());
		assertSame(DiagnosticEmergencyWriter.process(), DiagnosticEmergencyWriter.process());
	}

	@Test
	void utf8RecordsHaveValidBoundariesAndNewlineWithinByteLimit() throws Exception {
		CountingOutput output = new CountingOutput();
		DiagnosticEmergencyWriter writer = new DiagnosticEmergencyWriter(() -> output);
		writer.write("短😀".repeat(20_000));
		byte @Unmodifiable [] bytes = output.bytes.toByteArray();
		assertTrue(bytes.length <= DiagnosticEmergencyWriter.MAX_RECORD_BYTES);
		String record = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		assertTrue(record.endsWith("[truncated]\n"));
		assertEquals(1, output.writes.get());
		assertEquals(1, output.flushes.get());
		assertEquals(0, output.closes.get());
	}

	@Test
	void openWriteAndFlushFailuresStopWithoutRetryCloseOrMainLoggerRecursion() {
		for (String stage : List.of("open", "write", "flush")) {
			AtomicInteger opens = new AtomicInteger();
			CountingOutput output = new CountingOutput();
			output.failure = stage;
			DiagnosticEmergencyWriter writer = new DiagnosticEmergencyWriter(() -> {
				opens.incrementAndGet();
				if (stage.equals("open")) throw new IOException("unavailable");
				return output;
			});
			for (int index = 0; index < 10; index++) assertDoesNotThrow(() -> writer.write("record"));
			assertEquals(1, opens.get());
			assertEquals(1, writer.failures());
			assertEquals(0, output.closes.get());
			assertEquals(stage.equals("open") ? 0 : 1, output.writes.get());
			assertEquals(stage.equals("flush") ? 1 : 0, output.flushes.get());
		}
	}

	private static @Unmodifiable List<String> records(ByteArrayOutputStream output) {
		return output.toString(StandardCharsets.UTF_8).lines().toList();
	}

	@NotNullByDefault
	private static final class CountingOutput extends OutputStream {
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final AtomicInteger writes = new AtomicInteger();
		private final AtomicInteger flushes = new AtomicInteger();
		private final AtomicInteger closes = new AtomicInteger();
		private @Nullable String failure;
		@Override public void write(int value) { throw new AssertionError("Expected one complete byte-array write"); }
		@Override public void write(byte @Unmodifiable [] record) throws IOException {
			writes.incrementAndGet();
			if ("write".equals(failure)) throw new IOException("write failed");
			bytes.write(record);
		}
		@Override public void flush() throws IOException {
			flushes.incrementAndGet();
			if ("flush".equals(failure)) throw new IOException("flush failed");
		}
		@Override public void close() { closes.incrementAndGet(); }
	}
}
