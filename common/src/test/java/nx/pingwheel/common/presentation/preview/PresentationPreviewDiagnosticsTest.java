package nx.pingwheel.common.presentation.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import nx.pingwheel.common.client.ClientPingActionDispatcher;
import nx.pingwheel.common.client.ClientPingRuntime;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.interaction.state.TargetValidation;
import nx.pingwheel.common.interaction.state.TargetValidator;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationDiagnosticCapture;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class PresentationPreviewDiagnosticsTest {
	private static final String ADAPTER = "test:preview", FIELD = "test:remote";
	private static final Target TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:stone");
	private static final UUID PLAYER = new UUID(0, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static PresentationPreviewAccess access() {
		return new PresentationPreviewAccess(71, 1, "block", Map.of(ADAPTER, new PresentationPreviewAccess.Adapter(1,
			Map.of(FIELD, new PresentationField(FIELD, PresentationField.Kind.NUMBER, true, 0, "Remote")))));
	}
	private static PresentationPreviewC2SPacket request(long id) {
		return PresentationPreviewC2SPacket.read(71, 1, id, TARGET, "block", ADAPTER, Set.of(FIELD));
	}
	@Test void throwingLocalAndCacheReadersFallbackOnceThenTimeoutWithoutInventingValues() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var context = new Context(); var sent = new ArrayList<PresentationPreviewC2SPacket>();
			var preview = new ClientPresentationPreview(type -> Optional.of(access()), () -> context,
				List.of(new ThrowingReader()), (target, type) -> { throw new IllegalStateException("cache failure", new RuntimeException("cache cause")); }, sent::add);
			preview.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", context.level));
			for (int tick = 0; tick < 5; tick++) preview.tick();
			assertEquals(1, sent.size());
			context.tick = PresentationPreviewLimits.REQUEST_TICKS; preview.tick();
			var ref = PresentationPropertyRef.root(ADAPTER, FIELD);
			assertTrue(preview.projection().orElseThrow().property(ref).isEmpty());
			assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, preview.projection().orElseThrow().fields().get(ref));
			assertEquals(2, sent.size()); assertEquals(PresentationPreviewC2SPacket.Kind.CANCEL, sent.getLast().kind());
			assertEquals(2, capture.events().size());
			assertTrue(capture.messages().stream().anyMatch(line -> line.contains("reader cause")));
			assertTrue(capture.messages().stream().anyMatch(line -> line.contains("cache cause")));
		}
	}
	@Test void throwingRequestTransportCleansUnavailableThenRethrowsWithoutLocalDuplicateReport() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var context = new Context();
			var failure = new IllegalStateException("request transport", new RuntimeException("request cause"));
			int[] sends = {0};
			var preview = new ClientPresentationPreview(type -> Optional.of(access()), () -> context, List.of(),
				(target, type) -> Optional.empty(), packet -> { sends[0]++; throw failure; });
			assertSame(failure, assertThrows(IllegalStateException.class,
				() -> preview.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", context.level))));
			for (int tick = 0; tick < 5; tick++) preview.tick();
			assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, preview.projection().orElseThrow().fields().get(PresentationPropertyRef.root(ADAPTER, FIELD)));
			assertEquals(1, sends[0]);
			assertTrue(capture.events().isEmpty(), "the existing interaction boundary owns propagated transport failures");
		}
	}
	@Test void propagatedRequestTransportFailureIsReportedByTheExistingRuntimeInteractionBoundary() throws Exception {
		var config = ClientConfig.HANDLER.getConfig();
		boolean compatibility = config.isLongPressCompatibilityMode(), gesture = config.isGestureSkipHoldEnabled();
		config.setLongPressCompatibilityMode(false); config.setGestureSkipHoldEnabled(false);
		try (var capture = new PresentationDiagnosticCapture()) {
			var context = new Context(); int[] sends = {0};
			var failure = new IllegalStateException("request transport", new RuntimeException("runtime preview transport cause"));
			var preview = new ClientPresentationPreview(type -> Optional.of(access()), () -> context, List.of(),
				(target, type) -> Optional.empty(), packet -> { sends[0]++; throw failure; });
			ClientPingRuntime runtime = runtimeWithPreview(preview, context.level);
			assertDoesNotThrow(() -> runtime.onPress(0));
			assertEquals(1, sends[0]);
			assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE,
				preview.projection().orElseThrow().fields().get(PresentationPropertyRef.root(ADAPTER, FIELD)));
			assertEquals(1, capture.events().size(), "no duplicate report from the preview transport cleanup");
			assertEquals(Level.WARN, capture.events().getFirst().getLevel());
			assertTrue(capture.messages().getFirst().contains("operation=selector-content-init"));
			assertTrue(capture.messages().getFirst().contains("runtime preview transport cause"));
			preview.tick(); assertEquals(1, sends[0]);
			runtime.close();
		} finally {
			config.setLongPressCompatibilityMode(compatibility); config.setGestureSkipHoldEnabled(gesture);
		}
	}
	/** Uses the existing package-private headless world port without changing its owner or adding a production seam. */
	private static ClientPingRuntime runtimeWithPreview(ClientPresentationPreview preview, Object level) throws Exception {
		Class<?> accessType = Class.forName("nx.pingwheel.common.client.ClientPingRuntime$InteractionAccess");
		Class<?> lifecycleType = Class.forName("nx.pingwheel.common.client.ClientPingRuntime$InteractionAccess$Lifecycle");
		var constructor = lifecycleType.getDeclaredConstructor(Object.class, String.class, Object.class, boolean.class, boolean.class, boolean.class);
		constructor.setAccessible(true);
		Object lifecycle = constructor.newInstance(level, TARGET.dimensionId(), null, true, true, false);
		Object access = Proxy.newProxyInstance(accessType.getClassLoader(), new Class<?>[] {accessType},
			(Object proxy, Method method, @Nullable Object @Nullable [] arguments) -> switch (method.getName()) {
			case "lifecycle" -> lifecycle;
			case "capturePressRay" -> Optional.of(CapturedRay.defaultRay());
			case "capture" -> {
				Object completion = java.util.Objects.requireNonNull(java.util.Objects.requireNonNull(arguments)[2]);
				Method complete = Class.forName("nx.pingwheel.common.client.ClientPingRuntime$CaptureCompletion")
					.getDeclaredMethod("complete", nx.pingwheel.common.interaction.TargetSnapshot.class, Optional.class);
				complete.setAccessible(true);
				complete.invoke(completion, TargetSnapshotFactory.block(TARGET.dimensionId(), 1, 2, 3, "minecraft:stone"), Optional.empty());
				yield null;
			}
			case "inputFrame", "mousePosition" -> Optional.empty();
			case "syncMouse", "disposeMouse", "resetInput" -> null;
			default -> throw new AssertionError("unexpected interaction port method " + method);
		});
		Method create = ClientPingRuntime.class.getDeclaredMethod("createForInteraction", ClientPingActionDispatcher.LocalErrorSink.class,
			ClientPingActionDispatcher.PacketSender.class, ClientRateLimitPolicy.class, InteractionTimeSource.class, TargetValidator.class,
			accessType, nx.pingwheel.common.presentation.client.ClientPresentation.class,
			nx.pingwheel.common.presentation.inventory.client.ClientInventory.class, ClientPresentationPreview.class);
		create.setAccessible(true);
		var presentation = new ClientPresentation(packet -> fail("no presentation request"));
		return (ClientPingRuntime) create.invoke(null, (ClientPingActionDispatcher.LocalErrorSink) (key, color) -> fail("no local error"),
			(ClientPingActionDispatcher.PacketSender) packet -> fail("no marker request"), ClientRateLimitPolicy.DEFAULT,
			(InteractionTimeSource) () -> 0, (TargetValidator) target -> TargetValidation.valid(), access, presentation, null, preview);
	}
	@Test void clientAccessAndContextFailuresStillPropagateWithoutInventedAbortOrLocalReport() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var context = new Context();
			var accessFailure = new IllegalStateException("client access cause");
			var denied = new ClientPresentationPreview(type -> { throw accessFailure; },
				() -> context, List.of(), (target, type) -> Optional.empty(), packet -> fail("denied client cannot send"));
			assertSame(accessFailure, assertThrows(IllegalStateException.class,
				() -> denied.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", context.level))));
			for (int attempt = 0; attempt < 5; attempt++) assertSame(accessFailure, assertThrows(IllegalStateException.class, denied::projection));
			var contextFailure = new NoClassDefFoundError("client context cause");
			var absentContext = new ClientPresentationPreview(type -> Optional.of(access()),
				() -> { throw contextFailure; }, List.of(),
				(target, type) -> Optional.empty(), packet -> fail("missing context cannot send"));
			assertSame(contextFailure, assertThrows(NoClassDefFoundError.class,
				() -> absentContext.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", context.level))));
			assertSame(contextFailure, assertThrows(NoClassDefFoundError.class, absentContext::projection));
			assertTrue(capture.events().isEmpty());
		}
	}
	@Test void failedCancelStillPropagatesAfterClearingPendingAndAbortFinallyClearsBinding() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var context = new Context(); var sent = new ArrayList<PresentationPreviewC2SPacket>();
			var failure = new IllegalStateException("cancel cause");
			var preview = new ClientPresentationPreview(type -> Optional.of(access()), () -> context, List.of(),
				(target, type) -> Optional.empty(), packet -> {
					sent.add(packet); if (packet.kind() == PresentationPreviewC2SPacket.Kind.CANCEL) throw failure;
				});
			preview.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", context.level));
			assertSame(failure, assertThrows(IllegalStateException.class, preview::abort));
			assertTrue(preview.projection().isEmpty()); assertDoesNotThrow(preview::abort);
			assertEquals(2, sent.size()); assertTrue(capture.events().isEmpty());
		}
	}
	@Test void serverAccessFailureStillDeniesAndCaptureAndSendFailuresDoNotRepeatReads() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var denied = new Host(); denied.deny = true;
			var server = new PresentationPreviewServer(denied);
			for (int attempt = 1; attempt < 5; attempt++) assertFalse(server.handle(PLAYER, request(attempt), attempt));
			assertEquals(0, denied.reads);
			var unavailable = new Host(); unavailable.failCapture = true;
			server = new PresentationPreviewServer(unavailable);
			assertTrue(server.handle(PLAYER, request(1), 0));
			assertEquals(1, server.drain(0, new PresentationAdapter.CaptureBudget(10), 1));
			assertEquals(PresentationPreviewS2CPacket.Status.UNAVAILABLE, unavailable.sent.getFirst().status());
			assertEquals(0, server.drain(1, new PresentationAdapter.CaptureBudget(10), 1)); assertEquals(1, unavailable.reads);
			var transport = new Host(); transport.failSend = true;
			server = new PresentationPreviewServer(transport); assertTrue(server.handle(PLAYER, request(1), 0));
			assertEquals(1, server.drain(0, new PresentationAdapter.CaptureBudget(10), 1));
			assertEquals(0, server.drain(1, new PresentationAdapter.CaptureBudget(10), 1)); assertEquals(1, transport.reads);
			assertEquals(3, capture.events().size());
			assertTrue(capture.events().stream().allMatch(event -> event.getLevel() == Level.WARN));
		}
	}
	@NotNullByDefault
	private static final class Context implements PreviewFieldAccess.ReadContext {
		private final Object level = new Object(); private long tick;
		@Override public Object levelIdentity() { return level; }
		@Override public String dimensionId() { return TARGET.dimensionId(); }
		@Override public long tick() { return tick; }
	}
	@NotNullByDefault
	private static final class ThrowingReader implements PreviewFieldAccess {
		@Override public String adapterId() { return ADAPTER; }
		@Override public @Unmodifiable Map<String, Outcome> observe(Target target, Set<String> demand, ReadContext context) {
			throw new IllegalStateException("reader failure", new RuntimeException("reader cause"));
		}
	}
	@NotNullByDefault
	private static final class Host implements PresentationPreviewServer.Host {
		private boolean deny, failCapture, failSend;
		private int reads;
		private final List<PresentationPreviewS2CPacket> sent = new ArrayList<>();
		@Override public Optional<PresentationPreviewAccess> access(UUID player, String targetTypeId) {
			if (deny) throw new IllegalStateException("access cause"); return Optional.of(PresentationPreviewDiagnosticsTest.access());
		}
		@Override public int intervalTicks(UUID player, String adapterId) { return 1; }
		@Override public int scanBudget() { return 4; }
		@Override public @Nullable PresentationPreviewServer.Capture capture(UUID player, PresentationPreviewC2SPacket request,
			PresentationPreviewAccess authorized, PresentationAdapter.CaptureBudget budget) {
			reads++; if (failCapture) throw new IllegalStateException("capture cause");
			return new PresentationPreviewServer.Capture(new PresentationSection(ADAPTER, 1, Map.of(), false), PresentationPreviewS2CPacket.Status.RESULT);
		}
		@Override public boolean send(UUID player, PresentationPreviewS2CPacket packet) {
			if (failSend) throw new IllegalStateException("send cause"); sent.add(packet); return true;
		}
	}
}
