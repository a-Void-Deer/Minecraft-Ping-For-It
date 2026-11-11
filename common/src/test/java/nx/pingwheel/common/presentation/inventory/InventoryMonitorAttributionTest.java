package nx.pingwheel.common.presentation.inventory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.monitor.server.ServerInventoryMonitor;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.TransportHandoff;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Independent real-callback oracles for nested attribution and request teardown. */
@NotNullByDefault
class InventoryMonitorAttributionTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void tickSessionRevocationIncludesRealPreviewSourceClose(boolean trackingEnabled) {
		var clock = new InventoryMonitorBackendTest.Clock(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 2), clock);
		var settings = InventorySettings.serverDefaults(); var host = new InventoryBackendTest.Host(); var closes = new AtomicInteger();
		var runtime = new InventoryRuntime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1) {
			@Override public void close() { closes.incrementAndGet(); clock.time += 29; }
		}), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, false, monitor);
		try (var backend = new InventoryBackend(host, runtime, 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1,
				InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings); backend.tick(0, settings);
			monitor.configure(true, trackingEnabled); host.policies.remove(InventoryBackendTest.A);
			int before = closes.get(); long started = clock.time; backend.tick(1, settings);
			var sample = java.util.Objects.requireNonNull(backend.completeMonitorTick(1));
			assertEquals(1, closes.get() - before); assertEquals(29, clock.time - started);
			assertEquals(29, sample.preview().timing(Phase.TICK_WORK).totalNs());
			assertEquals(1, sample.preview().timing(Phase.TICK_WORK).count()); assertNull(sample.preview().timing(Phase.INGRESS));
			if (trackingEnabled) assertEquals(0, sample.tracking().timing(Phase.TICK_WORK).totalNs());
		}
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void unknownSessionRefreshCostIsNotInventedAsEitherLane(boolean previewEnabled) {
		var clock = new InventoryMonitorBackendTest.Clock(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 2), clock);
		var settings = InventorySettings.serverDefaults(); boolean[] ticking = {false};
		var host = new InventoryBackendTest.Host() {
			@Override public Optional<InventoryBackend.Policy> policy(UUID player) { if (ticking[0]) clock.time += 29; return super.policy(player); }
		};
		var runtime = new InventoryRuntime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1)),
			settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, false, monitor);
		try (var backend = new InventoryBackend(host, runtime, 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			monitor.configure(previewEnabled, true); ticking[0] = true; long started = clock.time; backend.tick(1, settings);
			var sample = java.util.Objects.requireNonNull(backend.completeMonitorTick(1)); assertEquals(29, clock.time - started);
			if (previewEnabled) assertEquals(0, sample.preview().timing(Phase.TICK_WORK).totalNs());
			assertEquals(0, sample.tracking().timing(Phase.TICK_WORK).totalNs());
		}
	}

	@ParameterizedTest @CsvSource({"true,true", "false,true", "true,false"})
	void trackingCallbackNestedTickPreservesActualPreviewAttribution(boolean previewEnabled, boolean trackingEnabled) {
		try (var f = new InventoryMonitorBackendTest.Fixture()) {
			f.hello(0); f.open(1, 0); f.backend.tick(0, f.settings);
			f.backend.handle(InventoryBackendTest.A, f.select(1, 1), 1, f.settings); f.backend.tick(2, f.settings); f.open(2, 3);
			f.monitor.configure(previewEnabled, trackingEnabled); boolean[] nested = {false};
			f.markerLookup = () -> { if (!nested[0]) { nested[0] = true; f.clock.time += 101; f.backend.tick(3, f.settings); } };
			int frameStart = f.host.packets.size();
			long started = f.clock.time; f.backend.tick(3, f.settings); var sample = f.sample(3);
			long progressControls = f.host.packets.subList(frameStart, f.host.packets.size()).stream()
				.filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE).count();
			assertEquals(1, progressControls, "one real scan/delivery progress notice");
			long progressSendNs = progressControls * (11 + 13);
			assertTrue(nested[0]); assertEquals(101 + 86 + progressSendNs, f.clock.time - started,
				"Tracking callback, nested Preview work and independently counted progress-control measurement/handoff");
			if (previewEnabled) {
				assertEquals(41, sample.preview().timing(Phase.READ).totalNs());
				assertEquals(31 + progressSendNs, sample.preview().timing(Phase.PUBLISH).totalNs(),
					"PUBLISH includes the measured PREVIEW_STATE encode and handoff");
				assertEquals(86 + progressSendNs, sample.preview().timing(Phase.TICK_WORK).totalNs(),
					"inclusive Preview tick work includes its independently observed progress-control callbacks");
			} else assertNull(sample.preview());
			if (trackingEnabled) assertEquals(101, sample.tracking().timing(Phase.TICK_WORK).totalNs(), "disabled or enabled Preview business time is never Tracking-owned");
			else assertNull(sample.tracking());
		}
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void nestedHelloSameOrDifferentPlayerIsOneNonoverlappingIngressInterval(boolean samePlayer) {
		var clock = new InventoryMonitorBackendTest.Clock(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 2), clock);
		var settings = InventorySettings.serverDefaults(); @Nullable InventoryBackend[] backend = new @Nullable InventoryBackend[] {null};
		var host = new InventoryBackendTest.Host() {
			boolean nested;
			@Override public void policyDemand(UUID player) {
				if (!nested) { nested = true; java.util.Objects.requireNonNull(backend[0]).handle(samePlayer ? player : InventoryBackendTest.B, InventoryC2SPacket.hello(), 0, settings); }
			}
			@Override public int encodedBytes(InventoryS2CPacket packet, int display, int frame) { clock.time += 11; return packet.encodedSize(display, frame); }
			@Override public TransportHandoff sendObserved(UUID player, InventoryS2CPacket packet) { clock.time += 13; send(player, packet); return TransportHandoff.HANDED_OFF; }
		};
		var runtime = new InventoryRuntime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1)),
			settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, false, monitor);
		try (var instance = new InventoryBackend(host, runtime, 1)) {
			backend[0] = instance; monitor.configure(true, false); long started = clock.time;
			instance.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			var sample = java.util.Objects.requireNonNull(instance.completeMonitorTick(0)); var ingress = sample.preview().timing(Phase.INGRESS);
			assertTrue(host.nested); assertEquals(144, clock.time - started); assertEquals(6, host.packets.size(), "both real HELLO calls publish three controls");
			assertEquals(clock.time - started, ingress.totalNs()); assertEquals(1, ingress.count()); assertEquals(ingress.totalNs(), ingress.maxNs());
		}
	}

	@ParameterizedTest @CsvSource({"true,true", "false,true", "true,false"})
	void selectCreateCallbackNestedHelloSeparatesIngressLanesEvenWhenOneIsDisabled(boolean previewEnabled, boolean trackingEnabled) {
		var clock = new InventoryMonitorBackendTest.Clock(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 2), clock);
		var settings = InventorySettings.serverDefaults(); @Nullable InventoryBackend[] backend = new @Nullable InventoryBackend[] {null};
		var host = new InventoryBackendTest.Host() {
			@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
				java.util.Objects.requireNonNull(backend[0]).handle(InventoryBackendTest.B, InventoryC2SPacket.hello(), 1, settings);
				return super.create(player, frozen, admission);
			}
			@Override public int encodedBytes(InventoryS2CPacket packet, int display, int frame) { clock.time += 11; return packet.encodedSize(display, frame); }
			@Override public TransportHandoff sendObserved(UUID player, InventoryS2CPacket packet) { clock.time += 13; send(player, packet); return TransportHandoff.HANDED_OFF; }
		};
		var runtime = new InventoryRuntime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1) {
			@Override public boolean valid() { clock.time += 7; return true; }
		}), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, false, monitor);
		try (var instance = new InventoryBackend(host, runtime, 1)) {
			backend[0] = instance; instance.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			instance.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1,
				InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings); instance.tick(0, settings);
			var preview = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && !packet.entries().isEmpty()).findFirst().orElseThrow();
			monitor.configure(previewEnabled, trackingEnabled); long started = clock.time; int firstFrame = host.packets.size();
			instance.handle(InventoryBackendTest.A, InventoryC2SPacket.select(host.epoch(InventoryBackendTest.A), 100, 1, 1, 1,
				preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger"), 1, settings);
			var sample = java.util.Objects.requireNonNull(instance.completeMonitorTick(1)); assertEquals(1, host.store.size());
			assertEquals(3, host.packets.stream().filter(packet -> packet.epoch() == host.epoch(InventoryBackendTest.B)).count(), "nested HELLO sends OFFER and both POLICY controls");
			if (previewEnabled) { assertEquals(72, sample.preview().timing(Phase.INGRESS).totalNs()); assertEquals(1, sample.preview().timing(Phase.INGRESS).count()); }
			else assertNull(sample.preview());
			if (trackingEnabled) { assertEquals(clock.time - started - 72, sample.tracking().timing(Phase.INGRESS).totalNs()); assertEquals(1, sample.tracking().timing(Phase.INGRESS).count()); }
			else assertNull(sample.tracking());
			if (previewEnabled) {
				long charged = host.packets.subList(firstFrame, host.packets.size()).stream()
					.mapToLong(packet -> packet.encodedSize((int) settings.effectiveDisplayBytes(), (int) settings.effectiveFrameBytes())).sum();
				assertEquals(charged, sample.preview().counter(Counter.BUDGET_CHARGED_BYTES).value(), "counter ownership follows the unchanged Preview control-wire ledger");
			}
			if (trackingEnabled) assertNull(sample.tracking().counter(Counter.BUDGET_CHARGED_BYTES), "timing repair does not move the existing control-byte ledger");
		}
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void helloCallbackNestedTickAndHelloRemainSeparateTopLevelUnions(boolean trackingEnabled) {
		var clock = new InventoryMonitorBackendTest.Clock(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 2), clock);
		var settings = InventorySettings.serverDefaults(); @Nullable InventoryBackend[] backend = new @Nullable InventoryBackend[] {null};
		var nestedTickFrames = new java.util.ArrayList<InventoryS2CPacket>();
		var host = new InventoryBackendTest.Host() {
			boolean enteredTick, enteredHello;
			@Override public void policyDemand(UUID player) {
				if (!enteredTick) {
					enteredTick = true; clock.time += 5; int firstFrame = packets.size();
					java.util.Objects.requireNonNull(backend[0]).tick(0, settings);
					nestedTickFrames.addAll(packets.subList(firstFrame, packets.size())); clock.time += 7;
				}
			}
			@Override public Optional<InventoryBackend.Policy> policy(UUID player) {
				if (enteredTick && !enteredHello) { enteredHello = true; java.util.Objects.requireNonNull(backend[0]).handle(InventoryBackendTest.B, InventoryC2SPacket.hello(), 0, settings); }
				return super.policy(player);
			}
			@Override public int encodedBytes(InventoryS2CPacket packet, int display, int frame) { clock.time += 11; return packet.encodedSize(display, frame); }
			@Override public TransportHandoff sendObserved(UUID recipient, InventoryS2CPacket packet) { clock.time += 13; send(recipient, packet); return TransportHandoff.HANDED_OFF; }
		};
		var runtime = new InventoryRuntime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1)),
			settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, false, monitor);
		try (var instance = new InventoryBackend(host, runtime, 1)) {
			backend[0] = instance; monitor.configure(true, trackingEnabled); long started = clock.time;
			instance.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			var sample = java.util.Objects.requireNonNull(instance.completeMonitorTick(0)); var preview = sample.preview();
			assertTrue(host.enteredHello); assertEquals(228, clock.time - started);
			assertEquals(6, nestedTickFrames.size());
			assertEquals(2, nestedTickFrames.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OFFER).count());
			assertEquals(4, nestedTickFrames.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.POLICY).count());
			assertEquals(156, preview.timing(Phase.INGRESS).totalNs()); assertEquals(1, preview.timing(Phase.INGRESS).count());
			assertEquals(72, preview.timing(Phase.TICK_WORK).totalNs()); assertEquals(1, preview.timing(Phase.TICK_WORK).count());
			assertEquals(clock.time - started, preview.timing(Phase.INGRESS).totalNs() + preview.timing(Phase.TICK_WORK).totalNs());
		}
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void boundedTimingOverflowDoesNotStopRealNestedHelloAndNextIngressRecovers(boolean enabled) {
		var clock = new InventoryMonitorBackendTest.Clock(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 2), clock);
		var settings = InventorySettings.serverDefaults(); @Nullable InventoryBackend[] backend = new @Nullable InventoryBackend[] {null};
		var host = new InventoryBackendTest.Host() {
			int calls;
			@Override public void policyDemand(UUID player) {
				if (++calls < 140) java.util.Objects.requireNonNull(backend[0]).handle(player, InventoryC2SPacket.hello(), 0, settings);
			}
			@Override public int encodedBytes(InventoryS2CPacket packet, int display, int frame) { clock.time += 11; return packet.encodedSize(display, frame); }
			@Override public TransportHandoff sendObserved(UUID recipient, InventoryS2CPacket packet) { clock.time += 13; send(recipient, packet); return TransportHandoff.HANDED_OFF; }
		};
		var runtime = new InventoryRuntime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1)),
			settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, false, monitor);
		try (var instance = new InventoryBackend(host, runtime, 1)) {
			backend[0] = instance; monitor.configure(enabled, false); instance.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			assertEquals(140, host.calls); assertFalse(host.packets.isEmpty());
			if (!enabled) { assertEquals(0, clock.reads); assertNull(instance.completeMonitorTick(0)); return; }
			var overflow = java.util.Objects.requireNonNull(instance.completeMonitorTick(0)); assertNull(overflow.preview().timing(Phase.INGRESS), "over-depth timing is unavailable rather than a partial exact total");
			long started = clock.time; instance.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 1, settings);
			var recovered = java.util.Objects.requireNonNull(instance.completeMonitorTick(1));
			assertEquals(1, recovered.preview().timing(Phase.INGRESS).count()); assertEquals(clock.time - started, recovered.preview().timing(Phase.INGRESS).totalNs());
		}
	}
}
