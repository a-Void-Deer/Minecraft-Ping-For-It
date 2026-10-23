package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.client.rate.ClientCreateRateLimiter;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.client.spatial.PreciseCaptureRefresh;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.PingCaptureCoordinator;
import nx.pingwheel.common.interaction.PingCaptureLogger;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.candidate.PreciseTargetType;
import nx.pingwheel.common.interaction.candidate.FrozenCandidateAcquisition;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.PreciseSlot.Availability;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.math.RaycastPolicy;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;
import nx.pingwheel.common.testing.DiagnosticLogCapture;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ClientBusinessDiagnosticsTest {

	@Test void createFailureReportsOriginalCauseAndRollsBackWithoutRefundOrRetry() {
		try (var logs = new DiagnosticLogCapture()) {
			var active = new ActiveInteraction();
			var tracker = new CreateRequestTracker();
			tracker.onCreateDispatched(CreateRequestTracker.Route.INVENTORY, 91);
			int[] attempts = {0};
			var dispatcher = new ClientPingActionDispatcher(packet -> {
				attempts[0]++;
				throw new IllegalStateException("create-transport-sentinel", new IllegalArgumentException("send-root-sentinel"));
			}, (key, color) -> fail("no local error"), PingInteractionLogger.noop(), tracker,
				new ClientCreateRateLimiter(() -> 0, new ClientRateLimitPolicy(1, 1000)));
			var snapshot = TargetSnapshotFactory.location("minecraft:overworld", 1, 2, 3);
			var resolved = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()).resolve(snapshot.target(), snapshot.matchContext());
			var action = new PingInteractionAction.CreatePing(new CapturedPingContext(active.begin(), resolved), resolved.targetType().defaultPingType());
			assertEquals(ClientPingActionDispatcher.DispatchOutcome.TRANSPORT_FAILED, dispatcher.dispatchOutcome(action));
			assertTrue(tracker.isLatest(CreateRequestTracker.Route.INVENTORY, 91));
			assertEquals(ClientPingActionDispatcher.DispatchOutcome.THROTTLED, dispatcher.dispatchOutcome(action));
			assertEquals(1, attempts[0]);
			assertReport(logs, "client-send/create", "create-transport-sentinel", "send-root-sentinel");
		}
	}

	@Test void selectFailureReportsOriginalCauseWithoutRecordingOrRefunding() {
		try (var logs = new DiagnosticLogCapture()) {
			var presentation = new ClientPresentation(packet -> {});
			presentation.tick(true);
			assertTrue(presentation.offer(PresentationS2CPacket.offer(31,
				Map.of(PresentationBasic.ID, PresentationBasic.fields()), Map.of(PresentationBasic.ID, 1))));
			assertTrue(presentation.reset(PresentationS2CPacket.reset(31, 1, Map.of())));
			var tracker = new CreateRequestTracker();
			tracker.onCreateDispatched(CreateRequestTracker.Route.PRESENTATION, 42);
			var dispatcher = new ClientPingActionDispatcher(packet -> {
				throw new IllegalStateException("select-transport-sentinel", new IllegalArgumentException("select-root-sentinel"));
			}, (key, color) -> fail("no local error"), PingInteractionLogger.noop(), tracker,
				new ClientCreateRateLimiter(() -> 0, new ClientRateLimitPolicy(1, 1000)), presentation);
			var packet = InventoryC2SPacket.select(7, 31, 1, 55, 3, 1, 1, "entry", "attention");
			assertEquals(ClientInventory.DispatchOutcome.TRANSPORT_FAILED, dispatcher.dispatchInventory(packet));
			assertTrue(tracker.isLatest(CreateRequestTracker.Route.PRESENTATION, 42));
			assertEquals(ClientInventory.DispatchOutcome.THROTTLED, dispatcher.dispatchInventory(packet));
			assertReport(logs, "client-send/select", "select-transport-sentinel", "select-root-sentinel");
		}
	}

	@Test void ordinaryResolverWarningPreservesWhitelistAndUncompletedToken() {
		try (var logs = new DiagnosticLogCapture()) {
			var active = new ActiveInteraction();
			var coordinator = new PingCaptureCoordinator((target, context) -> {
				throw new IllegalStateException("private-message-sentinel", new RuntimeException("private-root-sentinel"));
			}, active, PingCaptureLogger.global());
			var token = coordinator.begin();
			UUID identity = UUID.randomUUID();
			assertTrue(coordinator.complete(token, TargetSnapshotFactory.entity("minecraft:overworld", identity, "private:entity")).isEmpty());
			assertTrue(active.isCurrent(token));
			assertTrue(active.currentContext().isEmpty());
			var event = logs.operation("capture/resolve").getFirst();
			assertEquals(Level.WARN, event.getLevel());
			assertNull(event.getThrown());
			String report = event.getMessage().getFormattedMessage();
			assertTrue(report.contains("IllegalStateException"));
			assertTrue(report.contains("dimension=minecraft:overworld"));
			assertFalse(report.contains("private-message-sentinel"));
			assertFalse(report.contains("private-root-sentinel"));
			assertFalse(report.contains("private:entity"));
			assertFalse(report.contains(identity.toString()));
		}
	}

	@Test void nativeAndDistantFailuresKeepIncompleteAndFallbackSemantics() {
		try (var logs = new DiagnosticLogCapture()) {
			var token = new ActiveInteraction().begin();
			Object level = new Object();
			var inputs = new PreciseCaptureRefresh.Inputs(token, level, CapturedRay.defaultRay(), RaycastPolicy.from(false, false, false), 10, 100);
			List<PreciseCaptureRefresh.Published> seen = new ArrayList<>();
			var nativeFailure = new PreciseCaptureRefresh(token, level, 1, () -> Optional.of(inputs), () -> true,
				request -> { throw new IllegalStateException("native-sentinel", new RuntimeException("native-root")); },
				(request, completion) -> fail("incomplete native scan cannot start distant"), seen::add);
			nativeFailure.enter(0);
			assertTrue(seen.getLast().slots().values().stream().allMatch(slot -> slot.outcome().availability() == Availability.INCOMPLETE));
			var distantFailure = new PreciseCaptureRefresh(token, level, 1, () -> Optional.of(inputs), () -> true,
				request -> new PreciseCaptureRefresh.Scan(PreciseCaptureRefresh.Scan.incomplete().nativeSlots(), PreciseCaptureRefresh.Outcome.missing(), true),
				(request, completion) -> { throw new IllegalStateException("distant-sentinel", new RuntimeException("distant-root")); }, seen::add);
			distantFailure.enter(0);
			assertEquals(Availability.MISSING, seen.getLast().slots().get(PreciseTargetType.LOCATION).outcome().availability());
			assertReport(logs, "precise/native-capture", "native-sentinel", "native-root");
			assertReport(logs, "precise/distant-start", "distant-sentinel", "distant-root");
		}
	}

	@Test void candidateFinalizationWarningKeepsOrdinaryCompletionAndRestrictedDetail() {
		try (var logs = new DiagnosticLogCapture()) {
			var active = new ActiveInteraction();
			int[] calls = {0};
			var resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());
			var coordinator = new PingCaptureCoordinator((target, context) -> {
				if (++calls[0] > 1) throw new IllegalStateException("finalization-private-message", new RuntimeException("finalization-private-root"));
				return resolver.resolve(target, context);
			}, active, PingCaptureLogger.global());
			var token = coordinator.begin();
			var ray = CapturedRay.defaultRay();
			var acquisition = new FrozenCandidateAcquisition(token, ray, 100,
				new WorldVector(1, 2, 3), List.of(), Set.of(PreciseTargetType.BLOCK));
			var context = coordinator.complete(token, TargetSnapshotFactory.block("minecraft:overworld", 987654, -42, 123456, "private:block"),
				ray, Optional.of(acquisition)).orElseThrow();
			assertTrue(active.currentContext().isPresent());
			assertTrue(context.selectorCandidates().isEmpty());
			var event = logs.operation("capture/finalize-candidates").getFirst();
			assertEquals(Level.WARN, event.getLevel());
			assertNull(event.getThrown());
			String report = event.getMessage().getFormattedMessage();
			assertTrue(report.contains("IllegalStateException"));
			assertFalse(report.contains("finalization-private-message"));
			assertFalse(report.contains("finalization-private-root"));
			assertFalse(report.contains("private:block"));
			assertFalse(report.contains("987654"));
		}
	}

	@Test void localeFailureIsOncePerCachedReloadAndRestoresKeysOnNextReload() {
		try (var logs = new DiagnosticLogCapture()) {
			setDiagnosticThreshold(Level.INFO);
			int[] attempts = {0};
			var cache = new SelectedLocaleTranslationKeyCache((code, resources) -> {
				if (++attempts[0] == 1) throw new IllegalStateException("locale-sentinel", new RuntimeException("locale-root"));
				return Set.of("restored");
			});
			Object language = new Object();
			assertFalse(cache.contains("zh_cn", language, null, "restored"));
			assertFalse(cache.contains("zh_cn", language, null, "restored"));
			assertEquals(1, attempts[0]);
			assertEquals(1, logs.operation("locale/resource").size());
			var event = logs.operation("locale/resource").getFirst();
			assertEquals(Level.WARN, event.getLevel());
			assertNull(event.getThrown());
			assertTrue(event.getMessage().getFormattedMessage().contains("locale-sentinel"));
			assertTrue(event.getMessage().getFormattedMessage().contains("locale-root"));
			assertTrue(cache.contains("zh_cn", new Object(), null, "restored"));
			assertEquals(2, attempts[0]);
			assertEquals(1, logs.operation("locale/resource").size());
		}
	}

	@Test void absentLocaleResourceOrKeyDoesNotWarn() {
		try (var logs = new DiagnosticLogCapture()) {
			setDiagnosticThreshold(Level.INFO);
			var absentResource = new SelectedLocaleTranslationKeyCache((code, resources) -> Set.of());
			var missingKey = new SelectedLocaleTranslationKeyCache((code, resources) -> Set.of("present"));
			assertFalse(absentResource.contains("zh_cn", new Object(), null, "missing"));
			assertFalse(missingKey.contains("zh_cn", new Object(), null, "missing"));
			assertTrue(logs.operation("locale/resource").isEmpty());
		}
	}

	private static void assertReport(DiagnosticLogCapture logs, String operation, String message, String cause) {
		assertEquals(1, logs.operation(operation).size());
		var event = logs.operation(operation).getFirst();
		assertEquals(Level.WARN, event.getLevel());
		assertNull(event.getThrown());
		String report = event.getMessage().getFormattedMessage();
		assertTrue(report.contains(message), report);
		assertTrue(report.contains(cause), report);
	}

	private static void setDiagnosticThreshold(Level level) {
		var logger = (org.apache.logging.log4j.core.Logger) Diagnostics.logger();
		var context = logger.getContext();
		context.getConfiguration().getLoggerConfig(logger.getName()).setLevel(level);
		context.updateLoggers();
	}
}
