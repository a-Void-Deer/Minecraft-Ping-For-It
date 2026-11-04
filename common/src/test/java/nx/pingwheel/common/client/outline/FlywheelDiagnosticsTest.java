package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.apache.logging.log4j.Level;
import java.util.concurrent.atomic.AtomicBoolean;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import nx.pingwheel.common.diagnostics.DiagnosticFaultReporter.Stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

@NotNullByDefault
class FlywheelDiagnosticsTest {
	@Test
	void debugDisabledRecoveryStillObservesSuccessAndTheNextFailureIsATransition() {
		List<FlywheelDiagnostics.Event> events = new ArrayList<>();
		FlywheelDiagnostics diagnostics = new FlywheelDiagnostics(() -> 1L, events::add, level -> level == Level.WARN);
		assertEquals(true, diagnostics.report("target", FlywheelDiagnosticReason.MODEL_UNAVAILABLE, () -> "failure", null));
		assertEquals(false, diagnostics.report("target", FlywheelDiagnosticReason.RENDERED,
			() -> { throw new AssertionError("disabled DEBUG detail must remain lazy"); }, null));
		assertEquals(true, diagnostics.report("target", FlywheelDiagnosticReason.MODEL_UNAVAILABLE, () -> "failed again", null));
		assertEquals(FlywheelDiagnostics.EventKind.REASON_TRANSITION, events.get(1).kind());
	}

	@Test
	void disabledWarningDoesNotSpendAdmissionAndAnAdmittedSinkFailureDoes() {
		AtomicBoolean enabled = new AtomicBoolean(false);
		AtomicInteger calls = new AtomicInteger();
		FlywheelDiagnostics diagnostics = new FlywheelDiagnostics(() -> 1L, event -> {
			calls.incrementAndGet();
			throw new IllegalStateException("sink failed");
		}, level -> enabled.get());
		assertEquals(false, diagnostics.report("target", FlywheelDiagnosticReason.VISUAL_NULL,
			() -> { throw new AssertionError("disabled details"); }, null));
		enabled.set(true);
		assertEquals(true, diagnostics.report("target", FlywheelDiagnosticReason.VISUAL_NULL, () -> "enabled", null));
		assertEquals(false, diagnostics.report("target", FlywheelDiagnosticReason.VISUAL_NULL, () -> "suppressed", null));
		assertEquals(1, calls.get());
	}
	@Test
	void firstTransitionHeartbeatAndRecoveryAreRateControlled() {
		long[] now = { 0L };
		List<FlywheelDiagnostics.Event> events = new ArrayList<>();
		FlywheelDiagnostics diagnostics = new FlywheelDiagnostics(
			() -> now[0], events::add);

		assertEquals(true, diagnostics.report(
			"target", FlywheelDiagnosticReason.VISUAL_NULL, () -> "visual details", null));
		assertEquals(false, diagnostics.report(
			"target", FlywheelDiagnosticReason.VISUAL_NULL, () -> "same frame", null));

		now[0] = FlywheelDiagnostics.HEARTBEAT_MILLIS - 1;
		assertEquals(false, diagnostics.report(
			"target", FlywheelDiagnosticReason.VISUAL_NULL, () -> "before heartbeat", null));
		now[0] = FlywheelDiagnostics.HEARTBEAT_MILLIS;
		assertEquals(true, diagnostics.report(
			"target", FlywheelDiagnosticReason.VISUAL_NULL, () -> "heartbeat", null));

		assertEquals(true, diagnostics.report(
			"target", FlywheelDiagnosticReason.BUDGET_EXCEEDED, () -> "changed", null));
		assertEquals(true, diagnostics.report(
			"target", FlywheelDiagnosticReason.RENDERED, () -> "recovered", null));
		assertEquals(List.of(
			FlywheelDiagnostics.EventKind.FIRST,
			FlywheelDiagnostics.EventKind.HEARTBEAT,
			FlywheelDiagnostics.EventKind.REASON_TRANSITION,
			FlywheelDiagnostics.EventKind.RECOVERY),
			events.stream().map(FlywheelDiagnostics.Event::kind).toList());
		assertEquals(FlywheelDiagnosticReason.RENDERED, events.get(3).reason());
	}

	@Test
	void transitionEventsRetainCompleteContextAndTheOriginalException() {
		List<FlywheelDiagnostics.Event> events = new ArrayList<>();
		FlywheelDiagnostics diagnostics = new FlywheelDiagnostics(() -> 1L, events::add);
		RuntimeException failure = new RuntimeException("mesh payload details");

		diagnostics.report(
			"dimension=minecraft:overworld; position=1,2,3; registry=create:machine",
			FlywheelDiagnosticReason.MESH_EXTRACTION_FAILED,
			() -> "class=example.Mesh; material=example.Material; texture=create:block/machine",
			failure);

		FlywheelDiagnostics.Event event = events.get(0);
		assertEquals("dimension=minecraft:overworld; position=1,2,3; registry=create:machine",
			event.target());
		assertEquals("class=example.Mesh; material=example.Material; texture=create:block/machine",
			event.details());
		assertSame(failure, event.failure());
		assertEquals("mesh", event.reason().diagnosticId());
	}

	@Test
	void managerStorageUnavailableHasItsOwnStableDiagnosticId() {
		assertNotEquals(
			FlywheelDiagnosticReason.MANAGER_NULL.diagnosticId(),
			FlywheelDiagnosticReason.MANAGER_STORAGE_UNAVAILABLE.diagnosticId());
		assertEquals(
			"manager-storage-unavailable",
			FlywheelDiagnosticReason.MANAGER_STORAGE_UNAVAILABLE.diagnosticId());
	}

	@Test
	void suppressedReportsDoNotBuildLazyDetailsButTransitionsHeartbeatAndRecoveryDo() {
		long[] now = { 0L };
		AtomicInteger detailCalls = new AtomicInteger();
		List<FlywheelDiagnostics.Event> events = new ArrayList<>();
		FlywheelDiagnostics diagnostics = new FlywheelDiagnostics(() -> now[0], event -> {
			event.details();
			events.add(event);
		});

		assertEquals(true, diagnostics.report(
			"target", FlywheelDiagnosticReason.VISUAL_NULL,
			() -> {
				detailCalls.incrementAndGet();
				return "first";
			}, null));
		assertEquals(1, detailCalls.get());

		assertEquals(false, diagnostics.report(
			"target", FlywheelDiagnosticReason.VISUAL_NULL,
			() -> {
				detailCalls.incrementAndGet();
				return "suppressed";
			}, null));
		assertEquals(1, detailCalls.get());

		now[0] = FlywheelDiagnostics.HEARTBEAT_MILLIS;
		assertEquals(true, diagnostics.report(
			"target", FlywheelDiagnosticReason.VISUAL_NULL,
			() -> {
				detailCalls.incrementAndGet();
				return "heartbeat";
			}, null));
		assertEquals(2, detailCalls.get());

		assertEquals(true, diagnostics.report(
			"target", FlywheelDiagnosticReason.BUDGET_EXCEEDED,
			() -> {
				detailCalls.incrementAndGet();
				return "transition";
			}, null));
		assertEquals(3, detailCalls.get());

		assertEquals(true, diagnostics.report(
			"target", FlywheelDiagnosticReason.RENDERED,
			() -> {
				detailCalls.incrementAndGet();
				return "recovery";
			}, null));
		assertEquals(4, detailCalls.get());
	}

	@Test
	void targetAndDetailSuppliersAreBothDeferredUntilAnEmission() {
		AtomicInteger targetCalls = new AtomicInteger();
		AtomicInteger detailCalls = new AtomicInteger();
		FlywheelDiagnostics diagnostics = new FlywheelDiagnostics(() -> 1L, event -> {
			event.target();
			event.details();
		});

		assertEquals(true, diagnostics.report(
			new Object(), FlywheelDiagnosticReason.VISUAL_NULL,
			() -> {
				targetCalls.incrementAndGet();
				return "target";
			}, () -> {
				detailCalls.incrementAndGet();
				return "details";
			}, null));
		assertEquals(1, targetCalls.get());
		assertEquals(1, detailCalls.get());
	}

	@Test
	void targetStateIsBoundedAndClearable() {
		FlywheelDiagnostics diagnostics = new FlywheelDiagnostics(() -> 1L, event -> {});
		for (int index = 0; index < FlywheelDiagnostics.MAX_TRACKED_TARGETS + 1; index++) {
			assertEquals(true, diagnostics.report(
				"target-" + index, FlywheelDiagnosticReason.VISUAL_NULL,
				() -> "details", null));
		}

		assertEquals(FlywheelDiagnostics.MAX_TRACKED_TARGETS, diagnostics.trackedTargetCount());
		diagnostics.clear();
		assertEquals(0, diagnostics.trackedTargetCount());
	}

	@Test
	void productionNormalAndRejectedProfilesDoNotCollectTargetOrDetails() {
		AtomicInteger reads = new AtomicInteger();
		try (IntegrationDiagnosticCapture capture = new IntegrationDiagnosticCapture(Level.DEBUG, LoggingProfile.NORMAL)) {
			FlywheelDiagnostics diagnostics = FlywheelDiagnostics.global();
			assertFalse(diagnostics.report("target", FlywheelDiagnosticReason.VISUAL_NULL,
				() -> { reads.incrementAndGet(); return "private-target"; },
				() -> { reads.incrementAndGet(); return "private-details"; }, null));
			assertTrue(diagnostics.report("target", FlywheelDiagnosticReason.MODEL_UNAVAILABLE,
				() -> { reads.incrementAndGet(); return "private-target"; },
				() -> { reads.incrementAndGet(); return "private-details"; }, null));
			assertEquals(0, reads.get());
			assertFalse(capture.events().getFirst().getMessage().getFormattedMessage().contains("private"));
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			assertTrue(diagnostics.report("target", FlywheelDiagnosticReason.VISUAL_NULL,
				() -> { reads.incrementAndGet(); return "private-target"; },
				() -> { reads.incrementAndGet(); return "private-details"; }, null));
			assertEquals(2, reads.get());
		}
	}

	@Test
	void suppliersAndSinkRunWithoutHoldingDomainMonitor() {
		@Nullable FlywheelDiagnostics[] holder = new FlywheelDiagnostics[1];
		holder[0] = new FlywheelDiagnostics(() -> 1L, event -> {
			assertFalse(Thread.holdsLock(holder[0]));
			event.target();
			event.details();
		});
		assertTrue(holder[0].report("key", FlywheelDiagnosticReason.MODEL_UNAVAILABLE, () -> {
			assertFalse(Thread.holdsLock(holder[0]));
			return "target";
		}, () -> {
			assertFalse(Thread.holdsLock(holder[0]));
			return "details";
		}, null));
	}

	@Test
	void clockEnabledAndSinkFaultsAreObservedWithoutHoldingTheDomainMonitor() {
		long clockFaults = faults(Stage.CHANNEL_CLOCK);
		long enabledFaults = faults(Stage.ENABLED);
		long sinkFaults = faults(Stage.SINK);
		@Nullable FlywheelDiagnostics[] holder = new FlywheelDiagnostics[1];
		holder[0] = new FlywheelDiagnostics(() -> {
			assertFalse(Thread.holdsLock(holder[0]));
			throw new IllegalStateException("clock fault");
		}, event -> failUnexpectedSink());
		assertFalse(holder[0].report("target", FlywheelDiagnosticReason.MODEL_UNAVAILABLE, () -> "detail", null));
		assertEquals(clockFaults + 1, faults(Stage.CHANNEL_CLOCK));
		AtomicBoolean enabledFails = new AtomicBoolean(true);
		holder[0] = new FlywheelDiagnostics(() -> 1L, event -> {
			assertFalse(Thread.holdsLock(holder[0]));
			throw new IllegalStateException("sink fault");
		}, level -> {
			assertFalse(Thread.holdsLock(holder[0]));
			if (enabledFails.get()) throw new LinkageError("enabled fault");
			return true;
		});
		assertFalse(holder[0].report("target", FlywheelDiagnosticReason.MODEL_UNAVAILABLE, () -> "detail", null));
		assertEquals(enabledFaults + 1, faults(Stage.ENABLED));
		enabledFails.set(false);
		assertTrue(holder[0].report("target", FlywheelDiagnosticReason.MODEL_UNAVAILABLE, () -> "detail", null));
		assertFalse(holder[0].report("target", FlywheelDiagnosticReason.MODEL_UNAVAILABLE, () -> "detail", null));
		assertEquals(sinkFaults + 1, faults(Stage.SINK));
	}

	@Test
	void unavailableTargetContextDoesNotReplaceTheOriginalFailureOrRepeatCollection() {
		long contextFaults = faults(Stage.CONTEXT);
		AtomicInteger reads = new AtomicInteger();
		RuntimeException original = new IllegalStateException("business cause");
		FlywheelDiagnostics diagnostics = new FlywheelDiagnostics(() -> 1L, event -> {
			assertEquals("[context unavailable]", event.target());
			assertEquals("[context unavailable]", event.target());
			assertEquals("detail", event.details());
			assertSame(original, event.failure());
		});
		assertTrue(diagnostics.report("target", FlywheelDiagnosticReason.MODEL_UNAVAILABLE, () -> {
			reads.incrementAndGet();
			throw new IllegalStateException("context fault");
		}, () -> "detail", original));
		assertEquals(1, reads.get());
		assertEquals(contextFaults + 1, faults(Stage.CONTEXT));
	}

	private static void failUnexpectedSink() { throw new AssertionError("sink must not run after clock failure"); }

	private static long faults(Stage stage) {
		return Diagnostics.faults().observations().stream()
			.filter(observation -> observation.key().side() == DiagnosticSide.CLIENT
				&& observation.key().site().equals("create-flywheel") && observation.key().stage() == stage)
			.mapToLong(observation -> observation.occurrences()).sum();
	}
}
