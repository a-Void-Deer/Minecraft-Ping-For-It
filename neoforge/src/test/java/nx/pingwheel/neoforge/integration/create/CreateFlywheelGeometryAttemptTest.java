package nx.pingwheel.neoforge.integration.create;

import nx.pingwheel.common.client.outline.EntityBlockGeometryOutcome;
import nx.pingwheel.common.client.outline.FlywheelDiagnosticReason;
import nx.pingwheel.common.client.outline.FlywheelDiagnostics;
import nx.pingwheel.common.client.outline.FlywheelInstanceResolution;
import nx.pingwheel.common.client.outline.FlywheelModelBudget;
import nx.pingwheel.common.util.SafeExceptionReport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class CreateFlywheelGeometryAttemptTest {

	@Test
	void mixedResolutionAndZeroTriangleSiblingUsesTheProductionEarlyExitWithoutLosingTheOriginalFailure() {
		assertEarlyModelExit(0L, FlywheelDiagnosticReason.NO_INSTANCES);
	}

	@Test
	void mixedResolutionAndOverBudgetSiblingUsesTheProductionBudgetExitWithoutLosingTheOriginalFailure() {
		assertEarlyModelExit(FlywheelModelBudget.MAX_TRIANGLES_PER_TARGET + 1L, FlywheelDiagnosticReason.BUDGET_EXCEEDED);
	}

	private static void assertEarlyModelExit(long triangles, FlywheelDiagnosticReason expectedReason) {
		AtomicInteger resolutions = new AtomicInteger();
		AtomicInteger models = new AtomicInteger();
		AtomicInteger reports = new AtomicInteger();
		RuntimeException original = new IllegalStateException("resolution original", new IllegalArgumentException("resolution cause"));
		FlywheelDiagnostics diagnostics = FlywheelDiagnostics.global();
		try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture()) {
			for (int frame = 0; frame < 2; frame++) {
				assertEquals(EntityBlockGeometryOutcome.EMPTY, CreateFlywheelGeometryAttempt.run(attempt -> {
					FlywheelInstanceResolution.Batch<String> batch = attempt.resolve(List.of("broken", "live"), instance -> {
						resolutions.incrementAndGet();
						if (instance.equals("broken")) {
							throw original;
						}
						return instance;
					});
					return attempt.models(batch.live(), instance -> {
						models.incrementAndGet();
						return triangles;
					}, Long::longValue,
						(reason, details) -> result(EntityBlockGeometryOutcome.EMPTY, reason, details, null),
						geometries -> { throw new AssertionError("early model exit must not plan or reserve a frame budget"); });
				}, result -> {
					reports.incrementAndGet();
					assertEquals(expectedReason, result.reason());
					assertSame(original, result.failure());
					diagnostics.report("target", result.reason(), result.target(), result.details(), result.failure());
				}));
			}
			assertEquals(4, resolutions.get(), "both instances are retried every attempt");
			assertEquals(2, models.get(), "valid sibling model work is not abandoned by resolution failure");
			assertEquals(2, reports.get(), "each attempt selects exactly one diagnostic");
			assertEquals(1, capture.events.size(), "the unchanged reason is domain-suppressed on retry");
			String message = capture.events.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("resolution original"));
			assertTrue(message.contains("resolution cause"));
			assertTrue(message.contains("liveSiblingCount=1"));
			assertTrue(message.contains(expectedReason == FlywheelDiagnosticReason.NO_INSTANCES
				? "no indexed triangles" : "maxTargetTriangles="));
			assertNull(capture.events.getFirst().getThrown());
		}
	}

	@Test
	void aLaterFailurePreservesBothOriginalChainsWithoutMutatingEitherAndPartialSuccessKeepsItsOutcome() {
		for (EntityBlockGeometryOutcome outcome : List.of(EntityBlockGeometryOutcome.FAILED, EntityBlockGeometryOutcome.RENDERED)) {
			RuntimeException resolution = new IllegalStateException("resolution original", new IllegalArgumentException("resolution cause"));
			RuntimeException terminal = new IllegalStateException("terminal original", new IllegalArgumentException("terminal cause"));
			FlywheelDiagnosticReason reason = outcome == EntityBlockGeometryOutcome.RENDERED
				? FlywheelDiagnosticReason.MASK_PARTIAL_EMISSION : FlywheelDiagnosticReason.MASK_EMISSION_FAILED;
			try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture()) {
				assertEquals(outcome, CreateFlywheelGeometryAttempt.run(attempt -> {
					attempt.resolve(List.of("broken", "live"), instance -> {
						if (instance.equals("broken")) { throw resolution; }
						return instance;
					});
					return result(outcome, reason, () -> "terminal stage", terminal);
				}, selected -> {
					assertEquals(reason, selected.reason());
					FlywheelDiagnostics.global().report("target", selected.reason(), selected.target(), selected.details(), selected.failure());
				}));
				String message = capture.events.getFirst().getMessage().getFormattedMessage();
				for (String part : List.of("resolution original", "resolution cause", "terminal original", "terminal cause")) {
					assertTrue(message.contains(part));
				}
				assertTrue(message.length() <= SafeExceptionReport.MAX_CHARACTERS);
				assertNull(capture.events.getFirst().getThrown());
				assertEquals(0, resolution.getSuppressed().length);
				assertEquals(0, terminal.getSuppressed().length);
			}
		}
	}

	@Test
	void modelExtractionFailureAndSuppressedReportsKeepTheResolutionErrorLazyAndAttemptLocal() {
		AtomicInteger messageReads = new AtomicInteger();
		RuntimeException resolution = new ReadCountingFailure("resolution payload", messageReads);
		RuntimeException extraction = new ReadCountingFailure("model extraction payload", messageReads);
		FlywheelDiagnostics diagnostics = FlywheelDiagnostics.global();
		try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture()) {
			for (int frame = 0; frame < 2; frame++) {
				assertEquals(EntityBlockGeometryOutcome.FAILED, CreateFlywheelGeometryAttempt.run(attempt -> {
					FlywheelInstanceResolution.Batch<String> batch = attempt.resolve(List.of("bad", "good"), instance -> {
						if (instance.equals("bad")) { throw resolution; }
						return instance;
					});
					try {
						return attempt.models(batch.live(), instance -> { throw extraction; }, (Long value) -> value,
							(reason, detail) -> result(EntityBlockGeometryOutcome.EMPTY, reason, detail, null),
							geometries -> { throw new AssertionError("failed extraction must not plan geometry"); });
					} catch (RuntimeException failure) {
						return result(EntityBlockGeometryOutcome.FAILED, FlywheelDiagnosticReason.MESH_EXTRACTION_FAILED,
							() -> "model extraction failed", failure);
					}
				}, result -> diagnostics.report("target", result.reason(), result.target(), result.details(), result.failure())));
				if (frame == 0) {
					assertTrue(messageReads.get() > 0);
					messageReads.set(0);
				}
			}
			assertEquals(0, messageReads.get(), "suppressed attempt must not format either failure");
			assertEquals(1, capture.events.size());
			String message = capture.events.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("resolution payload"));
			assertTrue(message.contains("model extraction payload"));
			assertEquals(EntityBlockGeometryOutcome.EMPTY, CreateFlywheelGeometryAttempt.run(attempt ->
				result(EntityBlockGeometryOutcome.EMPTY, FlywheelDiagnosticReason.NO_INSTANCES, () -> "next clean attempt", null),
				result -> assertNull(result.failure(), "a later attempt cannot retain the previous failures")));
		}
	}

	@NotNullByDefault
	private static final class ReadCountingFailure extends RuntimeException {
		private final AtomicInteger reads;

		private ReadCountingFailure(String message, AtomicInteger reads) {
			super(message);
			this.reads = reads;
		}

		@Override public @Nullable String getMessage() {
			reads.incrementAndGet();
			return super.getMessage();
		}
	}

	private static CreateFlywheelGeometryAttempt.Result result(EntityBlockGeometryOutcome outcome,
		FlywheelDiagnosticReason reason, Supplier<String> details, @Nullable Throwable failure) {
		return new CreateFlywheelGeometryAttempt.Result(outcome, reason, () -> "target", details, failure);
	}
}
