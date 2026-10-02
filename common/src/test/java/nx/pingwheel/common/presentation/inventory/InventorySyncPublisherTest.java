package nx.pingwheel.common.presentation.inventory;

import java.util.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.*;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.source.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventorySyncPublisherTest {
	static class Transport implements InventorySyncPublisher.Transport {
		final List<InventoryS2CPacket> sent = new ArrayList<>(); boolean authorized = true; int encoded;
		@Override public boolean authorized(SyncPublisher.Context context) { return authorized; }
		@Override public int encodedBytes(InventoryS2CPacket packet) { encoded++; return bytes(packet); }
		@Override public void send(UUID player, InventoryS2CPacket packet) { sent.add(packet); }
	}
	static int bytes(InventoryS2CPacket packet) { var buf = new FriendlyByteBuf(Unpooled.buffer()); try { packet.write(buf); return buf.readableBytes(); } finally { buf.release(); } }
	static SyncPublisher.Context context(String id, long baseline) { return new SyncPublisher.Context(id, new UUID(1, 1), "100/1", 1, baseline); }
	static InventorySyncPublisher.Route route(MarkerId marker, String target) { return new InventorySyncPublisher.Route(7, 100, 1, 0, marker, target, "danger"); }
	static CaptureResult capture(long revision, int entries, int display) {
		Map<String, CaptureResult.OpaqueValue> values = new LinkedHashMap<>();
		for (int i = 0; i < entries; i++) values.put("k" + i, InventoryDomainCodec.encode(new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "v" + i), i,
			"stone", "d".repeat(display), false)));
		return new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, values)),
			new CaptureResult.Coverage("inventory", revision, entries, OptionalLong.of(entries)), CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
	}
	static void publish(InventorySyncPublisher publisher, SyncPublisher.Context context, CaptureResult result) {
		var payload = (CaptureResult.OpaqueKeyedFragment) result.payload().orElseThrow();
		assertInstanceOf(SyncPublisher.Outcome.Accepted.class, publisher.publish(result,
			new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, payload.entries().keySet()), context,
			new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L))));
	}
	@Test void immutableBaselineSurvivesMoreThanFivePeriodsAndRecipientRepairIsIsolated() {
		InventorySettings settings = InventorySettings.serverDefaults(); settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(.25)));
		Transport transport = new Transport(); RetainedMemoryLedger memory = new RetainedMemoryLedger(16000000);
		try (InventorySyncPublisher publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var a = context("tracking/1", 1); var b = context("tracking/2", 2);
			assertTrue(publisher.register(a, route(new MarkerId(1), "target-a"))); assertTrue(publisher.register(b, route(new MarkerId(2), "target-b")));
			publish(publisher, a, capture(1, 12, 2500)); publish(publisher, b, capture(1, 1, 2500));
			for (int period = 0; period < 20; period++) {
				publisher.advance(period * 3L, settings); int start = transport.sent.size(); publisher.drain();
				long snapshots = transport.sent.subList(start, transport.sent.size()).stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).mapToLong(InventorySyncPublisherTest::bytes).sum();
				assertTrue(snapshots <= settings.trackingSnapshotClientPeriodBytes(), "period sum covers all targets, not each part");
				if (period < 5) assertTrue(publisher.pending(a));
			}
			assertFalse(publisher.pending(a)); assertFalse(publisher.pending(b));
			assertEquals(12, transport.sent.stream().filter(p -> p.markerId() != null && p.markerId().value() == 1 && p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).count());
			publisher.cancel(a); var update = capture(2, 1, 550); publish(publisher, b, update); publisher.advance(63, settings); publisher.drain();
			assertTrue(transport.sent.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.STREAM && p.markerId().value() == 2 && p.baselineId() == 2));
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void revokeAndMemoryDeferDoNotEncodeOrSendAndAccountingReleasesPartialBatches() {
		InventorySettings settings = InventorySettings.serverDefaults(); Transport transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("tracking/1", 1); assertTrue(publisher.register(context, route(new MarkerId(1), "target")));
			publish(publisher, context, capture(1, 3, 100)); memory.setCap(memory.retained()); publisher.drain(); assertEquals(0, transport.encoded);
			transport.authorized = false; memory.setCap(16000000); publisher.drain(); assertTrue(transport.sent.isEmpty()); assertEquals(0, memory.retained());
		}
	}
	@Test void changingPeriodAndWindowCannotRefundCurrentSpend() {
		InventoryPeriodClock clock = new InventoryPeriodClock(); assertTrue(clock.advance(0, 3)); assertFalse(clock.advance(1, 1));
		assertFalse(clock.advance(2, 1)); assertTrue(clock.advance(3, 1));
		InventoryWireWindow window = new InventoryWireWindow(100, 4); assertTrue(window.trySpend(450));
		window.reconfigure(100, 4); assertEquals(50, window.remaining());
		window.reconfigure(50, 2); assertEquals(0, window.remaining()); assertEquals(450, window.spent());
	}
	@Test void newerOfferedCutCannotRewriteAnAdmittedBaselineAndHeartbeatUsesClosedDelivery() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(.25)));
		settings.getTracking().setHeartbeatPeriods(1); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("tracking/1", 1); publisher.register(context, route(new MarkerId(1), "target"));
			var first = capture(1, 3, 2500); publish(publisher, context, first); publisher.drain();
			assertEquals(1, transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).count());
			var latest = capture(2, 3, 550); publish(publisher, context, latest);
			for (int tick = 3; tick <= 18; tick += 3) { publisher.advance(tick, settings); publisher.drain(); }
			var baseline = transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).toList();
			assertEquals(3, baseline.size()); assertTrue(baseline.stream().allMatch(p -> p.watermark() == 1 && p.entries().getFirst().displayJson().length() == 2500));
			var heartbeat = transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.HEARTBEAT).reduce((a, b) -> b).orElseThrow();
			var updates = transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STREAM).flatMap(p -> p.entries().stream()).collect(java.util.stream.Collectors.toMap(InventoryS2CPacket.Entry::key, e -> e, (a, b) -> b));
			assertEquals(2, heartbeat.watermark()); assertEquals(InventoryChecksums.checksumEntries(updates.values()), heartbeat.checksum());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void invalidationCancelsPartialBatchAndHasPriorityOverAnotherConsumersItems() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(.25)));
		var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var a = context("tracking/1", 1); var b = context("tracking/2", 2);
			publisher.register(a, route(new MarkerId(1), "target-a")); publisher.register(b, route(new MarkerId(2), "target-b"));
			publish(publisher, a, capture(1, 3, 2500)); publish(publisher, b, capture(1, 3, 2500)); publisher.drain();
			int before = transport.sent.size(); publisher.status(b, InventoryS2CPacket.Status.INVALID); publisher.advance(3, settings); publisher.drain();
			assertEquals(InventoryS2CPacket.Kind.STATUS, transport.sent.get(before).kind()); assertEquals(new MarkerId(2), transport.sent.get(before).markerId());
			assertTrue(transport.sent.subList(before, transport.sent.size()).stream().noneMatch(p -> p.markerId().equals(new MarkerId(2)) && !p.entries().isEmpty()));
			publisher.cancel(a); publisher.cancel(b);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void controlNegotiationAndReplayAreByteChargedAndCannotResetClientPeriodAllowance() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); UUID recipient = new UUID(1, 1);
			var offer = InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, 0)).stamp(100, 1);
			for (int i = 0; i < 1000; i++) { publisher.control(recipient, offer); publisher.drainControls(); }
			long bytes = transport.sent.stream().mapToLong(InventorySyncPublisherTest::bytes).sum();
			assertTrue(bytes > 0 && bytes <= settings.previewClientPeriodBytes());
			int count = transport.sent.size(); publisher.advance(1, settings); publisher.drainControls(); assertEquals(count, transport.sent.size());
			publisher.advance(5, settings); publisher.drainControls(); assertEquals(count + 1, transport.sent.size());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void indivisibleOversizedPreviewBatchTerminatesIncompleteInsteadOfWaitingForever() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("preview/1", 1);
			publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null));
			publish(publisher, context, capture(1, 1, 4096)); publisher.drain();
			assertFalse(publisher.pending(context));
			assertEquals(InventoryS2CPacket.Status.INCOMPLETE, transport.sent.getLast().status());
			assertFalse(transport.sent.getLast().completeScan()); assertTrue(transport.sent.getLast().entries().isEmpty());
		}
	}
}
