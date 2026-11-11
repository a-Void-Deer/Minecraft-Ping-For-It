package nx.pingwheel.common.presentation.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.monitor.server.ServerInventoryMonitor;
import nx.pingwheel.common.monitor.server.ServerMonitorCoordinator;
import nx.pingwheel.common.monitor.server.ServerMonitorCoordinator.CollectionState;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket.SampleCapability;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.platform.TransportHandoff;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryMonitorIntegrationTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	@ParameterizedTest @EnumSource(TransportHandoff.class)
	void actualBackendPublisherAndPlatformObservationPreserveBusinessProgressForEveryNormalOutcome(TransportHandoff outcome) {
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 4), () -> 0);
		monitor.resetGeneration(1); monitor.configure(true, false);
		var reads = new AtomicInteger(); var sends = new AtomicInteger();
		var attemptedPackets = new java.util.ArrayList<InventoryS2CPacket>();
		var settings = InventorySettings.serverDefaults();
		try (var f = new InventoryPublicationBackendFixture((input, display) -> Optional.of(
			new InventoryRuntimeTest.Source(input, reads, 1)), settings, TargetKey.from(InventoryRuntimeTest.TARGET), monitor)) {
			var player = allocate(ServerPlayer.class);
			IPlatformNetworkService network = new IPlatformNetworkService() {
				@Override public void sendToServer(IPacket packet) { fail("unexpected C2S"); }
				@Override public void sendToClient(IPacket packet, ServerPlayer recipient) { fail("no legacy fallback"); }
				@Override public TransportHandoff sendToClientObserved(IPacket packet, ServerPlayer recipient) {
					assertSame(player, recipient); sends.incrementAndGet();
					attemptedPackets.add(assertInstanceOf(InventoryS2CPacket.class, packet)); return outcome;
				}
			};
			f.observed = (recipient, packet) -> {
				TransportHandoff handoff = network.sendToClientObserved(packet, player);
				if (handoff != TransportHandoff.NOT_HANDED_OFF) f.send(recipient, packet);
				return handoff;
			};
			f.open(InventoryPublicationBackendFixture.A, InventoryRuntimeTest.TARGET, BlockFace.NORTH, 0);
			f.backend.tick(0, settings);
			var first = Objects.requireNonNull(f.backend.completeMonitorTick(0));
			assertTrue(sends.get() > 0);
			assertEquals(outcome != TransportHandoff.NOT_HANDED_OFF, f.frames.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW));
			int attempts = sends.get();
			int attemptStart = attemptedPackets.size();
			var opened = attemptedPackets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OPENED).findFirst().orElseThrow();
			f.backend.tick(1, settings); f.backend.completeMonitorTick(1);
			if (outcome == TransportHandoff.NOT_HANDED_OFF) {
				@Unmodifiable List<InventoryS2CPacket> retriedOpened = attemptedPackets.subList(attemptStart, attemptedPackets.size()).stream()
					.filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OPENED && packet.requestId() == opened.requestId()).toList();
				assertFalse(retriedOpened.isEmpty());
				assertTrue(retriedOpened.stream().allMatch(packet -> packet == opened), "every retry keeps the exact pre-encoding OPENED owner");
				assertEquals(attemptedPackets.size() - attemptStart, sends.get() - attempts); assertEquals(0, reads.get());
				assertTrue(f.frames.stream().noneMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.OPENED),
					"a refused attempt reaches neither the wire endpoint nor the client's grant");
				f.observed = (recipient, packet) -> {
					attemptedPackets.add(packet); f.send(recipient, packet); sends.incrementAndGet(); return TransportHandoff.HANDED_OFF;
				};
				for (int tick = 2; tick <= 12 && f.frames.stream().noneMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW); tick++) f.backend.tick(tick, settings);
				assertTrue(f.frames.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW)); assertEquals(1, reads.get());
				assertTrue(attemptedPackets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OPENED)
					.allMatch(packet -> packet == opened), "the eventual handoff still belongs to the retained owner");
				f.backend.tick(13, settings);
				assertEquals(1, f.frames.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OPENED).count(),
					"successful handoff retires this owner exactly once");
			} else assertEquals(attempts, sends.get(), "accepted or legacy-unobserved progress is not retried");
			var counter = outcome == TransportHandoff.HANDED_OFF ? Counter.HANDOFF_FRAMES
				: outcome == TransportHandoff.NOT_HANDED_OFF ? Counter.NOT_HANDED_OFF_FRAMES : Counter.UNOBSERVED_HANDOFF_FRAMES;
			assertTrue(first.preview().counter(counter).value() > 0);
			if (outcome != TransportHandoff.HANDED_OFF) assertNull(first.preview().counter(Counter.HANDOFF_BYTES),
				"no observed byte count may be invented for an unhanded or unobserved attempt");
		}
	}

	@Test void zeroOneAndThreeTelemetrySubscribersDoNotChangeActualCollectionOrInventoryAudience() {
		long expectedReads = -1, expectedSequence = -1;
		for (int subscribers : new int[] {0, 1, 3}) {
			var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 4), () -> 0);
			monitor.resetGeneration(1); monitor.configure(true, false);
			var reads = new AtomicInteger(); var latestReads = new AtomicInteger();
			var settings = InventorySettings.serverDefaults();
			try (var f = new InventoryPublicationBackendFixture((input, display) -> Optional.of(
				new InventoryRuntimeTest.Source(input, reads, 1)), settings, TargetKey.from(InventoryRuntimeTest.TARGET), monitor);
				var coordinator = new ServerMonitorCoordinator(key -> true, new ServerMonitorCoordinator.Capabilities() {
					@Override public boolean statusAvailable(UUID key) { return true; }
					@Override public SampleCapability sampleCapability(UUID key) { return SampleCapability.AVAILABLE; }
				}, new ServerMonitorCoordinator.Sender() {
					@Override public boolean sendStatus(UUID key, InventoryMonitorStatusS2CPacket packet) { return true; }
					@Override public boolean sendSample(UUID key, InventoryMonitorSampleS2CPacket packet) { return true; }
				}, () -> { latestReads.incrementAndGet(); return monitor.latest(); },
				() -> new CollectionState(monitor.serverGeneration(), monitor.collectionGeneration(), true, false))) {
				for (int index = 0; index < subscribers; index++) coordinator.subscribe(new UUID(500, index),
					InventoryMonitorSubscribeC2SPacket.watch(1,
						List.of(Objects.requireNonNull(Metric.byId("server.preview.requests.received")))));
				f.open(InventoryPublicationBackendFixture.A, InventoryRuntimeTest.TARGET, BlockFace.NORTH, 0);
				for (long tick = 0; tick < 3; tick++) {
					f.backend.tick(tick, settings); f.backend.completeMonitorTick(tick); coordinator.tick(tick);
					assertNull(f.backend.completeMonitorTick(tick), "duplicate completion cannot create a subscriber-driven sample");
				}
				assertNotNull(monitor.latest());
				if (expectedReads < 0) { expectedReads = reads.get(); expectedSequence = monitor.latest().sampleSequence(); }
				assertEquals(expectedReads, reads.get()); assertEquals(expectedSequence, monitor.latest().sampleSequence());
				assertEquals(subscribers == 0 ? 0 : 1, latestReads.get(), "fanout reads latest once, never samples per viewer");
				assertTrue(f.frames.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW),
					"zero telemetry subscribers is not zero inventory recipients");
			}
		}
	}

	private static <T> T allocate(Class<T> type) {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
