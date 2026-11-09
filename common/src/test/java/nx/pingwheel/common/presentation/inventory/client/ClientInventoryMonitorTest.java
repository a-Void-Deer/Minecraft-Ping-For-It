package nx.pingwheel.common.presentation.inventory.client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.monitor.MonitorSchema.*;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.monitor.client.ClientMonitorCollector;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.MarkerContentGrant;
import nx.pingwheel.common.presentation.TestContentGrants;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ClientInventoryMonitorTest {

	private static final Target TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	private static final MarkerId MARKER = new MarkerId(1);
	private static InventoryS2CPacket.Entry entry(long count, long revision) {
		return new InventoryS2CPacket.Entry("stone", "minecraft:stone", "Stone", null, count, revision, false, null);
	}
	private static ClientInventory fixture(ClientMonitorCollector collector) {
		var inventory = new ClientInventory(packet -> {});
		inventory.monitor(collector); inventory.presentationReset(10, 1);
		inventory.accept(InventoryS2CPacket.offer(3, new InventoryS2CPacket.Offer(10, 10, 0, 0)).stamp(10, 1));
		inventory.markerCreated(new MarkerSnapshot(MARKER, new UUID(1, 2), TARGET, "entity_block", "attention",
			new MarkerAnchor(1, 2, 3), 0, 1000), new MarkerContentGrant(TestContentGrants.adapters(
			InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA, Set.of(InventoryResourceKind.ITEM.fieldId())), InventoryResourceKind.ITEM));
		return inventory;
	}
	private static InventoryS2CPacket data(InventoryS2CPacket.Kind kind, @Nullable MarkerId marker, long request, long count, long revision, long watermark) {
		return InventoryS2CPacket.data(kind, 3, request, marker, 1, 1, watermark, 0, 1, false,
			InventoryS2CPacket.Status.UPDATING, 0, List.of(entry(count, revision))).stamp(10, 1);
	}

	@Test void actualPreviewSnapshotCommitAndStreamPathsMeasureOneInclusiveApplyPerPacket() {
		AtomicLong clock = new AtomicLong();
		var collector = new ClientMonitorCollector(new MonitorTuning(1, 2), () -> clock.getAndAdd(10));
		var inventory = fixture(collector);
		long request = inventory.open(TARGET, BlockFace.NORTH, "entity_block");
		inventory.accept(InventoryS2CPacket.opened(3, 10, 1, request, 1));
		collector.configure(true);
		inventory.accept(data(InventoryS2CPacket.Kind.PREVIEW, null, request, 3, 1, 1));
		inventory.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, MARKER, 1, 4, 1, 1));
		inventory.accept(data(InventoryS2CPacket.Kind.STREAM, MARKER, 1, 7, 2, 2));
		inventory.observeMonitorState(); collector.completeTick(1);
		var sample = java.util.Objects.requireNonNull(collector.latest());
		assertEquals(1, sample.preview().timing(Phase.APPLY).count());
		assertEquals(2, sample.tracking().timing(Phase.APPLY).count(), "beginSnapshot/part/commit/applyReplacing are nested, not additional inclusive totals");
		assertEquals(3, inventory.preview(request).entries().getFirst().count());
		assertEquals(7, inventory.tracking(MARKER).entries().getFirst().count());
		assertEquals(1, sample.preview().counter(Counter.FRAMES_PREVIEW).value());
		assertEquals(1, sample.tracking().counter(Counter.FRAMES_SNAPSHOT).value());
		assertEquals(1, sample.tracking().counter(Counter.FRAMES_STREAM).value());
		assertEquals(1, sample.tracking().gauge(Gauge.CHANNELS).value());
		assertNull(sample.preview().gauge(Gauge.BUFFER_ESTIMATED_BYTES), "no guessed buffer/wire or heap bytes");
	}

	@Test void actualProductionStoreFragmentAndReplacementMethodsAreInstrumentedWhenUsedDirectly() {
		AtomicLong clock = new AtomicLong();
		var collector = new ClientMonitorCollector(new MonitorTuning(1, 1), () -> clock.getAndAdd(1));
		collector.configure(true);
		var store = new InventoryClientStore(4, 8, 2); store.monitor(collector);
		var channel = InventoryClientStore.Channel.tracked(1);
		assertEquals(InventoryClientStore.PartOutcome.STARTED, store.beginSnapshot(channel, 1, 1, 1, 0));
		assertEquals(InventoryClientStore.PartOutcome.COMMITTED, store.part(channel, 1, 1, 0,
			Map.of("a", new InventoryClientStore.Value(3, 1, "UPDATING")), 0));
		assertEquals(InventoryClientStore.Outcome.APPLIED, store.applyReplacing(channel, 1, 1,
			Map.of("b", new InventoryClientStore.Value(5, 2, "UPDATING")), Set.of("a")));
		collector.completeTick(1);
		assertEquals(3, collector.latest().tracking().timing(Phase.APPLY).count());
		assertEquals(Set.of("b"), store.values(channel).keySet());
	}

	@Test void disabledInstrumentationDoesNotTouchItsClockOrChangeInventoryOutput() {
		var collector = new ClientMonitorCollector(new MonitorTuning(1, 1), () -> { throw new AssertionError("disabled clock"); });
		var inventory = fixture(collector);
		inventory.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, MARKER, 1, 4, 1, 1));
		inventory.accept(data(InventoryS2CPacket.Kind.STREAM, MARKER, 1, 0, 2, 2));
		inventory.observeMonitorState(); assertNull(collector.completeTick(1));
		assertEquals(0, inventory.tracking(MARKER).entries().getFirst().count());
	}
}
