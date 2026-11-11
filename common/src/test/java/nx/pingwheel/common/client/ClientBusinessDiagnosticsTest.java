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
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
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
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ClientBusinessDiagnosticsTest {

	@Test void createFailureReportsOriginalCauseAndRollsBackWithoutRefundOrRetry() {
		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEEP)) {
			var active = new ActiveInteraction();
			var tracker = new CreateRequestTracker();
			tracker.onCreateDispatched(CreateRequestTracker.Route.INVENTORY, 91);
			int[] attempts = {0};
			var dispatcher = new ClientPingActionDispatcher(packet -> {
				attempts[0]++;
				throw new IllegalStateException("create-transport-sentinel", new IllegalArgumentException("send-root-sentinel"));
			}, new ClientPingActionDispatcher.LocalErrorSink() {
				@Override public void showLocalError(String messageKey, int color) { fail("no local error"); }
				@Override public void showCreateCooldown(String messageKey, int color) { }
			}, PingInteractionLogger.noop(), tracker,
				new ClientCreateRateLimiter(() -> 0, new ClientRateLimitPolicy(1, 1000)), readyPresentation());
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
		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEEP)) {
			java.util.List<String> errors = new java.util.ArrayList<>();
			var presentation = readyPresentation();
			var tracker = new CreateRequestTracker();
			tracker.onCreateDispatched(CreateRequestTracker.Route.PRESENTATION, 42);
			var dispatcher = new ClientPingActionDispatcher(packet -> {
				throw new IllegalStateException("select-transport-sentinel", new IllegalArgumentException("select-root-sentinel"));
			}, (key, color) -> errors.add(key), PingInteractionLogger.noop(), tracker,
				new ClientCreateRateLimiter(() -> 0, new ClientRateLimitPolicy(1, 1000)), presentation);
			var packet = InventoryC2SPacket.select(7, 31, 1, 55, 3, 1, 1, "entry", "attention");
			assertEquals(ClientInventory.DispatchOutcome.TRANSPORT_FAILED, dispatcher.dispatchInventory(packet));
			assertTrue(tracker.isLatest(CreateRequestTracker.Route.PRESENTATION, 42));
			assertEquals(ClientInventory.DispatchOutcome.THROTTLED, dispatcher.dispatchInventory(packet));
			assertEquals(java.util.List.of(nx.pingwheel.common.presentation.inventory.InventoryFailureReason.TRANSPORT_FAILURE.translationKey()), errors,
				"one failed commit gets one local line even if dispatch is called again");
			assertReport(logs, "client-send/select", "select-transport-sentinel", "select-root-sentinel");
		}
	}

	@Test void normalTransportFailureKeepsSafeSiteAndNeverReadsOriginalAccessors() {
		try (var logs = new DiagnosticLogCapture()) {
			var active = new ActiveInteraction();
			var tracker = new CreateRequestTracker();
			tracker.onCreateDispatched(CreateRequestTracker.Route.INVENTORY, 73);
			var dispatcher = new ClientPingActionDispatcher(packet -> {
				throw new UnreadableTransportFailure();
			}, (key, color) -> fail("no local error"), PingInteractionLogger.noop(), tracker,
				new ClientCreateRateLimiter(() -> 0, new ClientRateLimitPolicy(1, 1000)), readyPresentation());
			var snapshot = TargetSnapshotFactory.location("minecraft:overworld", 1, 2, 3);
			var resolved = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()).resolve(snapshot.target(), snapshot.matchContext());
			var action = new PingInteractionAction.CreatePing(new CapturedPingContext(active.begin(), resolved), resolved.targetType().defaultPingType());
			assertEquals(ClientPingActionDispatcher.DispatchOutcome.TRANSPORT_FAILED, dispatcher.dispatchOutcome(action));
			var event = logs.operation("client-send/create").getFirst();
			assertEquals(Level.WARN, event.getLevel());
			assertNull(event.getThrown());
			String report = event.getMessage().getFormattedMessage();
			assertTrue(report.contains("site=client-send/create"));
			assertTrue(report.contains("handling=transport"));
			assertTrue(report.contains("failure=type-"));
			assertFalse(report.contains("transport-message-sentinel"));
			assertFalse(report.contains("transport-root-sentinel"));
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
			assertTrue(report.contains("site=capture/resolve"));
			assertTrue(report.contains("handling=resolver-failure"));
			assertTrue(report.contains("failure=invalid-state"));
			assertFalse(report.contains("private-message-sentinel"));
			assertFalse(report.contains("private-root-sentinel"));
			assertFalse(report.contains("private:entity"));
			assertFalse(report.contains(identity.toString()));
		}
	}

	@Test void nativeAndDistantFailuresKeepIncompleteAndFallbackSemantics() {
		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEEP)) {
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
			String nativeReport = logs.operation("precise/native-capture").getFirst().getMessage().getFormattedMessage();
			assertTrue(nativeReport.contains("generation=1"), nativeReport);
			assertTrue(nativeReport.contains("nativeRange=10.0"), nativeReport);
			assertTrue(nativeReport.contains("pingRange=100.0"), nativeReport);
			assertTrue(nativeReport.contains("policy="), nativeReport);
			String distantReport = logs.operation("precise/distant-start").getFirst().getMessage().getFormattedMessage();
			assertTrue(distantReport.contains("fallback=MISSING"), distantReport);
		}
	}

	@Test void preciseRefreshFailuresAreNormalWarningsWithIncompleteOrFallbackSemantics() {
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

			var nativeEvent = logs.operation("precise/native-capture").getFirst();
			assertEquals(Level.WARN, nativeEvent.getLevel());
			assertNull(nativeEvent.getThrown());
			String nativeReport = nativeEvent.getMessage().getFormattedMessage();
			assertTrue(nativeReport.contains("handling=all-native-slots-incomplete"), nativeReport);
			assertTrue(nativeReport.contains("failure=invalid-state"), nativeReport);
			assertFalse(nativeReport.contains("native-sentinel"));
			assertFalse(nativeReport.contains("native-root"));

			var distant = logs.operation("precise/distant-start").getFirst();
			assertEquals(Level.WARN, distant.getLevel());
			assertNull(distant.getThrown());
			String distantReport = distant.getMessage().getFormattedMessage();
			assertTrue(distantReport.contains("handling=request-fallback"), distantReport);
			assertTrue(distantReport.contains("failure=invalid-state"), distantReport);
			assertFalse(distantReport.contains("distant-sentinel"));
			assertFalse(distantReport.contains("distant-root"));
		}
	}

	@Test void selectorContentAndChatConsumptionFailuresUseSafeNormalHandling() {
		try (var logs = new DiagnosticLogCapture()) {
			var token = new ActiveInteraction().begin();
			var fence = new SpatialSelectorSession.ContentFence(token.sequence(), 41, token.sequence(), "candidate");
			ClientPingRuntime.reportSelectorPreviewStartFailure(token, PingInteractionPhase.WHEEL_OPEN, 7,
				new IllegalStateException("preview-start-sentinel"));
			ClientPingRuntime.reportSelectorProjectionUpdateSkipped(token, fence, PingInteractionPhase.WHEEL_OPEN, 8,
				new IllegalStateException("projection-sentinel"));
			ClientPingRuntime.reportSelectorPreviewCloseFailure(token.sequence(), PingInteractionPhase.IDLE, 9, true,
				new IllegalStateException("preview-close-sentinel"));
			ClientPingRuntime.reportChatTemplateLookupFailure("attention",
				new IllegalStateException("template-sentinel"));
			ClientPingRuntime.reportMarkerDisplayFailure(new MarkerId(11),
				new IllegalStateException("display-sentinel"));

			assertSafeHandling(logs, "selector-content-init", "preview-start-failed", "preview-start-sentinel");
			assertSafeHandling(logs, "selector-content-read", "projection-update-skipped", "projection-sentinel");
			assertSafeHandling(logs, "selector-content-close", "preview-close-failed", "preview-close-sentinel");
			assertSafeHandling(logs, "chat/template-lookup", "legacy-template", "template-sentinel");
			assertSafeHandling(logs, "chat/marker-display", "receipt-not-displayed", "display-sentinel");
		}
	}

	@Test void selectorContentFailureDetailExpandsOnlyAtDeepWithExplicitFields() {
		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEEP)) {
			var token = new ActiveInteraction().begin();
			var fence = new SpatialSelectorSession.ContentFence(token.sequence(), 41, token.sequence(), "candidate");
			ClientPingRuntime.reportSelectorPreviewStartFailure(token, PingInteractionPhase.WHEEL_OPEN, 7,
				new IllegalStateException("deep-preview-start-sentinel"));
			ClientPingRuntime.reportSelectorProjectionUpdateSkipped(token, fence, PingInteractionPhase.WHEEL_OPEN, 8,
				new IllegalStateException("deep-projection-sentinel"));
			ClientPingRuntime.reportSelectorPreviewCloseFailure(token.sequence(), PingInteractionPhase.IDLE, 9, true,
				new IllegalStateException("deep-preview-close-sentinel"));

			String start = logs.operation("selector-content-init").getFirst().getMessage().getFormattedMessage();
			assertTrue(start.contains("token=" + token.sequence()), start);
			assertTrue(start.contains("phase=WHEEL_OPEN"), start);
			assertTrue(start.contains("tick=7"), start);
			assertTrue(start.contains("deep-preview-start-sentinel"), start);

			String read = logs.operation("selector-content-read").getFirst().getMessage().getFormattedMessage();
			assertTrue(read.contains("selectorSession=" + token.sequence()), read);
			assertTrue(read.contains("requestId=41"), read);
			assertTrue(read.contains("previewSession=" + token.sequence()), read);
			assertTrue(read.contains("candidateId=candidate"), read);
			assertTrue(read.contains("deep-projection-sentinel"), read);

			String close = logs.operation("selector-content-close").getFirst().getMessage().getFormattedMessage();
			assertTrue(close.contains("token=" + token.sequence()), close);
			assertTrue(close.contains("phase=IDLE"), close);
			assertTrue(close.contains("hard=true"), close);
			assertTrue(close.contains("deep-preview-close-sentinel"), close);

			ClientPingRuntime.reportChatTemplateLookupFailure("attention",
				new IllegalStateException("deep-template-sentinel"));
			String template = logs.operation("chat/template-lookup").getFirst().getMessage().getFormattedMessage();
			assertTrue(template.contains("pingType=attention"), template);
			assertTrue(template.contains("deep-template-sentinel"), template);

			ClientPingRuntime.reportMarkerDisplayFailure(new MarkerId(11),
				new IllegalStateException("deep-display-sentinel"));
			String display = logs.operation("chat/marker-display").getFirst().getMessage().getFormattedMessage();
			assertTrue(display.contains("markerId=11"), display);
			assertTrue(display.contains("deep-display-sentinel"), display);
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
			assertTrue(report.contains("site=capture/finalize-candidates"));
			assertTrue(report.contains("handling=finalization-failure"));
			assertTrue(report.contains("failure=invalid-state"));
			assertFalse(report.contains("finalization-private-message"));
			assertFalse(report.contains("finalization-private-root"));
			assertFalse(report.contains("private:block"));
			assertFalse(report.contains("987654"));
		}
	}

	@Test void localeFailureIsOncePerCachedReloadAndRestoresKeysOnNextReload() {
		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEEP)) {
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

	private static ClientPresentation readyPresentation() {
		var presentation = new ClientPresentation(packet -> {});
		presentation.tick(true);
		assertTrue(presentation.offer(PresentationS2CPacket.offer(31,
			Map.of(PresentationBasic.ID, PresentationBasic.fields()), Map.of(PresentationBasic.ID, 1))));
		assertTrue(presentation.reset(PresentationS2CPacket.reset(31, 1, Map.of())));
		return presentation;
	}

	private static void assertSafeHandling(DiagnosticLogCapture logs, String site, String handling, String rawSentinel) {
		assertEquals(1, logs.operation(site).size(), site);
		var event = logs.operation(site).getFirst();
		assertEquals(Level.WARN, event.getLevel());
		assertNull(event.getThrown());
		String report = event.getMessage().getFormattedMessage();
		assertTrue(report.contains("handling=" + handling), report);
		assertFalse(report.contains(rawSentinel), report);
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
		var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger("pingforit");
		var context = logger.getContext();
		context.getConfiguration().getLoggerConfig(logger.getName()).setLevel(level);
		context.updateLoggers();
	}

	@NotNullByDefault
	private static final class UnreadableTransportFailure extends IllegalStateException {
		@Override public String getMessage() { throw new AssertionError("message accessor must stay disabled"); }
		@Override public StackTraceElement[] getStackTrace() {
			throw new AssertionError("stack accessor must stay disabled");
		}
		@Override public @Nullable Throwable getCause() { throw new AssertionError("cause accessor must stay disabled"); }
	}
}
