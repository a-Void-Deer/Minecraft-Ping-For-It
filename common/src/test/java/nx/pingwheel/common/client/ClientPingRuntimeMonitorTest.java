package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.server.Bootstrap;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.client.spatial.NativeSelectorInput;
import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.CancellationContext;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.interaction.state.TargetValidation;
import nx.pingwheel.common.monitor.MonitorSchema.*;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.monitor.client.ClientMonitorCollector;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.render.SpatialOverlayRenderer;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ClientPingRuntimeMonitorTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@NotNullByDefault
	private static final class Access implements ClientPingRuntime.InteractionAccess {
		final Object world = new Object();
		int geometryCalls;
		public Lifecycle lifecycle() { return new Lifecycle(world, "minecraft:overworld", null, true, true, false); }
		public Optional<CapturedRay> capturePressRay() { return Optional.of(CapturedRay.defaultRay()); }
		public void capture(InteractionToken token, CapturedRay ray, ClientPingRuntime.CaptureCompletion completion) {
			completion.complete(TargetSnapshotFactory.block("minecraft:overworld", 1, 2, 3, "minecraft:chest", true, BlockFace.NORTH), Optional.empty());
		}
		public CancellationContext cancellation(CapturedRay ray) { throw new AssertionError("no cancellation expected"); }
		public void syncMouse(PingInteractionPhase phase) {}
		public void disposeMouse(boolean screenTransition) {}
		public Optional<NativeSelectorInput.Frame> inputFrame() { return Optional.of(new NativeSelectorInput.Frame(1, 800, 600, 400, 300, true, true)); }
		public SpatialSelectorSession.ListGeometry listGeometry(SpatialOverlayRenderer.Style style) {
			geometryCalls++; return new SpatialSelectorSession.ListGeometry(5, 220, 18, 24, 20, 40);
		}
		public void resetInput() {}
		public void applyToggle(SelectorIntent.CaptureToggle toggle, long timeMillis) { throw new AssertionError("no toggle expected"); }
	}

	@Test void runtimeIngressAndRealSelectorGeometryUseTheInjectedLocalCollectorWithoutServerTelemetry() {
		var config = ClientConfig.HANDLER.getConfig();
		int hold = config.getWheelHoldMillis(); boolean compatibility = config.isLongPressCompatibilityMode();
		config.setWheelHoldMillis(250); config.setLongPressCompatibilityMode(false);
		try {
			AtomicLong interactionClock = new AtomicLong(), measurementClock = new AtomicLong();
			var access = new Access(); var sent = new ArrayList<IPacket>();
			var presentation = new ClientPresentation(sent::add); var inventory = new ClientInventory(sent::add);
			var runtime = ClientPingRuntime.createForInteraction((key, color) -> fail(key), sent::add, ClientRateLimitPolicy.DEFAULT,
				interactionClock::get, target -> TargetValidation.valid(), access, presentation, inventory, null);
			var collector = new ClientMonitorCollector(new MonitorTuning(1, 2), () -> measurementClock.getAndAdd(1));
			var monitor = new ClientMonitorState(collector, measurementClock::get); runtime.monitor(monitor);
			presentation.tick(true);
			runtime.onPresentationPacket(PresentationS2CPacket.offer(10, Map.of(PresentationBasic.ID, PresentationBasic.fields(),
				InventoryPresentation.ADAPTER_ID, InventoryPresentation.INSTANCE.fields()), Map.of(PresentationBasic.ID, 1, InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA)));
			runtime.onPresentationPacket(PresentationS2CPacket.reset(10, 1, Map.of("entity_block", Map.of(PresentationBasic.ID, Set.of(PresentationBasic.NAME)))));
			runtime.onInventoryPacket(InventoryS2CPacket.offer(3, new InventoryS2CPacket.Offer(10, 10, 0, 0)).stamp(10, 1));
			monitor.configure(true, Metric.defaults());
			runtime.onPress(0); interactionClock.set(300); runtime.onRenderFrame(true);
			assertTrue(runtime.selectorSnapshot().isPresent()); assertTrue(access.geometryCalls > 0);
			runtime.onInventoryPacket(InventoryS2CPacket.policy(3, 10, 1, Set.of("entity_block")));
			runtime.drawSelector(null); // Prepared selector, native renderer's explicit null-graphics seam.
			runtime.onTick();
			var local = java.util.Objects.requireNonNull(monitor.view().local());
			assertNotNull(local.preview().timing(Phase.PREPARE)); assertEquals(1, local.preview().timing(Phase.APPLY).count());
			assertEquals(1, local.preview().timing(Phase.DRAW).count());
			assertEquals(Source.LOCAL, local.preview().source()); assertNull(monitor.view().remote());
			runtime.close(); assertFalse(monitor.enabled()); assertFalse(collector.enabled());
		} finally { config.setWheelHoldMillis(hold); config.setLongPressCompatibilityMode(compatibility); }
	}

	@Test void runtimeDisabledIngressPrepareAndDrawEarlyReturnsDoNotReadTheMonitorClock() {
		var runtime = ClientPingRuntime.create((key, color) -> {}, packet -> {}, ClientRateLimitPolicy.DEFAULT, () -> 0);
		var collector = new ClientMonitorCollector(new MonitorTuning(1, 1), () -> { throw new AssertionError("disabled monitor clock"); });
		runtime.monitor(new ClientMonitorState(collector, () -> { throw new AssertionError("disabled mirror clock"); }));
		runtime.inventory(new ClientInventory(packet -> {}));
		runtime.onInventoryPacket(InventoryS2CPacket.policy(1, 1, 1, Set.of()));
		runtime.prepareInventoryTracking(null); // No live level: existing production early-return path.
		runtime.drawSelector(null); // No native frame: existing production early-return path.
		assertNull(collector.latest()); runtime.close();
	}

	/** Empty prepared marker projection exercises the real draw entry, not a synthetic timer call. */
	@Test void trackingPrepareAndDrawUseTheirOwnLocalPhaseWithoutSamplingTheHudPainter() throws Exception {
		var runtime = ClientPingRuntime.create((key, color) -> {}, packet -> {}, ClientRateLimitPolicy.DEFAULT, () -> 0);
		AtomicLong clock = new AtomicLong();
		var collector = new ClientMonitorCollector(new MonitorTuning(1, 1), () -> clock.getAndAdd(10));
		var state = new ClientMonitorState(collector, clock::get); runtime.monitor(state); state.configure(true, Metric.defaults());
		runtime.prepareInventoryTracking(null); // No live level: clear the real projection.
		runtime.drawInventoryTracking(allocate(RecordingGraphics.class));
		state.tick(1);
		assertEquals(1, state.view().local().tracking().timing(Phase.PREPARE).count());
		assertEquals(1, state.view().local().tracking().timing(Phase.DRAW).count());
		assertNull(state.view().local().preview().timing(Phase.DRAW));
		runtime.close();
	}

	@NotNullByDefault
	private static final class RecordingGraphics extends GuiGraphics {
		private RecordingGraphics() { super(null, null); }
		@Override public PoseStack pose() { return new PoseStack(); }
	}

	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) throws Exception {
		var unsafeType = Class.forName("sun.misc.Unsafe"); var field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
		return (T) unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
	}
}
