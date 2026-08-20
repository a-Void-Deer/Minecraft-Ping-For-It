package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class FlywheelDiagnosticsTest {
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
		FlywheelDiagnostics diagnostics = new FlywheelDiagnostics(() -> now[0], events::add);

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
		FlywheelDiagnostics diagnostics = new FlywheelDiagnostics(() -> 1L, event -> {});

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
}
