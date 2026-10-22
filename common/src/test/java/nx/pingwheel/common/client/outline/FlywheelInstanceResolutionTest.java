package nx.pingwheel.common.client.outline;

import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class FlywheelInstanceResolutionTest {

	@Test
	void aFailedInstanceKeepsItsOriginalPayloadAndDoesNotDiscardItsSiblingsOrRetry() {
		AtomicInteger calls = new AtomicInteger();
		RuntimeException original = new IllegalStateException("instance original", new IllegalArgumentException("native handle cause"));
		for (int frame = 0; frame < 2; frame++) {
			FlywheelInstanceResolution.Batch<String> batch = FlywheelInstanceResolution.resolve(List.of("bad", "good", "stale"), instance -> {
				calls.incrementAndGet();
				return switch (instance) {
					case "bad" -> throw original;
					case "stale" -> null;
					default -> instance;
				};
			});
			assertEquals(List.of("good"), batch.live());
			assertEquals(2, batch.nonLiveCount());
			assertEquals(1, batch.failureCount());
			assertSame(original, batch.firstFailure());
			assertThrows(UnsupportedOperationException.class, () -> batch.live().add("other"));
			try (OutlineDiagnosticCapture capture = new OutlineDiagnosticCapture(Level.WARN)) {
				FlywheelDiagnostics.global().report("target", FlywheelDiagnosticReason.INSTANCE_RESOLUTION_FAILED,
					batch::failureDetails, batch.firstFailure());
				assertEquals(1, capture.events.size());
				String report = capture.events.getFirst().getMessage().getFormattedMessage();
				assertTrue(report.contains("instance original"));
				assertTrue(report.contains("native handle cause"));
				assertTrue(report.contains("liveSiblingCount=1"));
				assertNull(capture.events.getFirst().getThrown());
			}
		}
		assertEquals(6, calls.get());
	}

	@Test
	void allFailuresRetainTheFirstOriginalFailureWhileNormalMissingInstancesStayExceptionFree() {
		LinkageError failure = new LinkageError("original full failure");
		FlywheelInstanceResolution.Batch<String> batch = FlywheelInstanceResolution.resolve(List.of("a", "b"), instance -> { throw failure; });
		assertTrue(batch.live().isEmpty());
		assertEquals(2, batch.failureCount());
		assertEquals(0, batch.firstFailureIndex());
		assertSame(failure, batch.firstFailure());
		try (OutlineDiagnosticCapture capture = new OutlineDiagnosticCapture(Level.WARN)) {
			FlywheelDiagnostics.global().report("target", FlywheelDiagnosticReason.NO_INSTANCES,
				batch::failureDetails, batch.firstFailure());
			assertEquals(1, capture.events.size());
			assertTrue(capture.events.getFirst().getMessage().getFormattedMessage().contains("original full failure"));
			assertNull(capture.events.getFirst().getThrown());
		}
		FlywheelInstanceResolution.Batch<String> missing = FlywheelInstanceResolution.resolve(List.of("missing"), instance -> null);
		assertNull(missing.firstFailure());
		assertEquals(0, missing.failureCount());
	}
}
