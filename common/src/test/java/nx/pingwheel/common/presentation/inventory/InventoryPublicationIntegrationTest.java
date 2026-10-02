package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.ByteMultiplier;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Replays the production publisher's ordered wire frames, not hand-ordered status/data fixtures. */
class InventoryPublicationIntegrationTest {
	static InventoryS2CPacket wire(InventoryS2CPacket packet) {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buf); var decoded = InventoryS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt()); assertFalse(buf.isReadable()); return decoded;
		} finally { buf.release(); }
	}
	static final class Endpoint {
		final List<IPacket> requests = new ArrayList<>();
		final ClientInventory client = new ClientInventory(requests::add);
		final UUID recipient;
		int cursor;
		Endpoint(UUID recipient, MarkerSnapshot marker) {
			this.recipient = recipient; client.presentationReset(100, 1); client.markerCreated(marker);
		}
		void drain(InventoryBackendTest.Host host) { drain(host, p -> true); }
		void drain(InventoryBackendTest.Host host, java.util.function.Predicate<InventoryS2CPacket> applied) {
			for (; cursor < host.packets.size(); cursor++) if (host.recipients.get(cursor).equals(recipient)) {
				var decoded = wire(host.packets.get(cursor)); if (applied.test(decoded)) client.accept(decoded);
			}
		}
		void tickTo(int from, int to) { for (int i = from; i < to; i++) client.tick(true); }
		List<InventoryC2SPacket> repairs() { return requests.stream().filter(p -> p instanceof InventoryC2SPacket i && i.kind() == InventoryC2SPacket.Kind.RESYNC).map(p -> (InventoryC2SPacket) p).toList(); }
	}
	static void count(ClientInventory client, MarkerId marker, long expected) {
		assertTrue(client.tracking(marker).complete()); assertFalse(client.tracking(marker).grey());
		assertEquals(1, client.tracking(marker).entries().size()); assertEquals(expected, client.tracking(marker).entries().getFirst().count());
	}
	@Test void smallRecoveryFencePrecedesSnapshotAndEachRecipientReplacesGreyBaselineWithoutOldStreamResurrection() {
		try (var f = new InventoryBackendTest.Fixture()) {
			f.open(); f.backend.handle(InventoryBackendTest.A, f.select(1), 1, f.settings);
			var marker = f.host.store.allMarkers().getFirst(); var a = new Endpoint(InventoryBackendTest.A, MarkerSnapshot.from(marker)); var b = new Endpoint(InventoryBackendTest.B, MarkerSnapshot.from(marker));
			f.backend.tick(2, f.settings); a.drain(f.host); b.drain(f.host); count(a.client, marker.id(), 7); count(b.client, marker.id(), 7);
			f.amount = 8; f.backend.tick(5, f.settings); a.drain(f.host); b.drain(f.host);
			var oldStreams = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STREAM).toList(); assertEquals(2, oldStreams.size());
			long baselineA = a.client.tracking(marker.id()).baselineId(), baselineB = b.client.tracking(marker.id()).baselineId();
			f.valid = false; f.backend.tick(6, f.settings); a.drain(f.host); b.drain(f.host);
			assertTrue(a.client.tracking(marker.id()).grey()); assertEquals(8, a.client.tracking(marker.id()).entries().getFirst().count()); assertTrue(b.client.tracking(marker.id()).grey());
			int start = f.host.packets.size(); f.valid = true; f.amount = 11; f.backend.tick(9, f.settings);
			for (UUID recipient : List.of(InventoryBackendTest.A, InventoryBackendTest.B)) {
				List<InventoryS2CPacket> recovery = new ArrayList<>();
				for (int i = start; i < f.host.packets.size(); i++) if (recipient.equals(f.host.recipients.get(i)) && marker.id().equals(f.host.packets.get(i).markerId())) recovery.add(wire(f.host.packets.get(i)));
				assertEquals(InventoryS2CPacket.Kind.STATUS, recovery.getFirst().kind()); assertEquals(InventoryS2CPacket.Status.UPDATING, recovery.getFirst().status());
				assertEquals(0, recovery.getFirst().watermark()); assertEquals(3, recovery.getFirst().statusRevision());
				assertEquals(InventoryS2CPacket.Kind.SNAPSHOT, recovery.get(1).kind()); assertEquals(recovery.getFirst().baselineId(), recovery.get(1).baselineId());
			}
			a.drain(f.host); count(a.client, marker.id(), 11); assertTrue(b.client.tracking(marker.id()).grey(), "A's receipt never rebases B's client");
			b.drain(f.host); count(b.client, marker.id(), 11);
			assertNotEquals(baselineA, a.client.tracking(marker.id()).baselineId()); assertNotEquals(baselineB, b.client.tracking(marker.id()).baselineId());
			for (var old : oldStreams) { a.client.accept(wire(old)); b.client.accept(wire(old)); }
			for (int now = 12; now <= 39; now += 3) { f.backend.tick(now, f.settings); a.drain(f.host); b.drain(f.host); }
			count(a.client, marker.id(), 11); count(b.client, marker.id(), 11); assertTrue(a.repairs().isEmpty()); assertTrue(b.repairs().isEmpty());
			long bRecovered = b.client.tracking(marker.id()).baselineId();
			f.backend.handle(InventoryBackendTest.A, InventoryC2SPacket.resync(f.host.epoch(InventoryBackendTest.A), 0, marker.id()).stamp(100, 1), 40, f.settings);
			f.backend.tick(40, f.settings); a.drain(f.host); b.drain(f.host); count(a.client, marker.id(), 11); assertEquals(bRecovered, b.client.tracking(marker.id()).baselineId());
		}
	}
	@Test void missingApplicationStreamIsRepairedByRealCompletionControlsAtNormalHeartbeatCadenceAndCooldownIsBounded() {
		try (var f = new InventoryBackendTest.Fixture()) {
			f.host.expiry = 200; f.settings.getTracking().setHeartbeatPeriods(16); f.open(); f.backend.handle(InventoryBackendTest.A, f.select(1), 1, f.settings);
			var marker = f.host.store.allMarkers().getFirst(); var a = new Endpoint(InventoryBackendTest.A, MarkerSnapshot.from(marker)); var b = new Endpoint(InventoryBackendTest.B, MarkerSnapshot.from(marker));
			f.backend.tick(2, f.settings); a.drain(f.host); b.drain(f.host); count(a.client, marker.id(), 7);
			long oldA = a.client.tracking(marker.id()).baselineId(), oldB = b.client.tracking(marker.id()).baselineId();
			f.amount = 9; f.backend.tick(5, f.settings); int[] withheld = {0};
			a.drain(f.host, p -> { if (p.kind() == InventoryS2CPacket.Kind.STREAM) { withheld[0]++; return false; } return true; }); b.drain(f.host);
			assertEquals(1, withheld[0], "application seam omission, not a transport-loss claim"); assertEquals(7, a.client.tracking(marker.id()).entries().getFirst().count());
			assertEquals(1, a.repairs().size(), "the completion digest exposes the omitted stream without a same-cut periodic heartbeat"); assertTrue(b.repairs().isEmpty());
			int previous = 0;
			for (int now = 8; now <= 32; now += 3) {
				a.tickTo(previous, now); b.tickTo(previous, now); previous = now;
				f.backend.tick(now, f.settings); a.drain(f.host); b.drain(f.host);
				assertTrue(a.repairs().size() <= 1 + now / 15, "one repair per negotiated five-period cooldown");
			}
			assertEquals(3, a.repairs().size()); count(b.client, marker.id(), 9); assertEquals(oldB, b.client.tracking(marker.id()).baselineId());
			f.backend.handle(InventoryBackendTest.A, a.repairs().getLast(), 33, f.settings); f.backend.tick(33, f.settings); a.drain(f.host); b.drain(f.host);
			count(a.client, marker.id(), 9); assertNotEquals(oldA, a.client.tracking(marker.id()).baselineId()); assertEquals(oldB, b.client.tracking(marker.id()).baselineId());
			int repairs = a.repairs().size();
			for (int now = 36; now <= 120; now += 3) { a.tickTo(previous, now); b.tickTo(previous, now); previous = now; f.backend.tick(now, f.settings); a.drain(f.host); b.drain(f.host); }
			count(a.client, marker.id(), 9); assertEquals(repairs, a.repairs().size()); assertTrue(b.repairs().isEmpty());
		}
	}
	static CaptureResult value(long watermark, long count) {
		var item = InventoryRuntimeTest.item(count);
		return new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, Map.of("selected", InventoryDomainCodec.encode(item)))),
			new CaptureResult.Coverage("inventory", watermark, 1, OptionalLong.of(1)), CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
	}
	static Endpoint negotiated(MarkerId marker, InventorySettings settings) {
		var snapshot = new MarkerSnapshot(marker, InventoryBackendTest.A, InventoryRuntimeTest.TARGET, "entity_block", "attention", new nx.pingwheel.common.marker.MarkerAnchor(1, 2, 3), 0, 200);
		var endpoint = new Endpoint(InventoryBackendTest.A, snapshot);
		endpoint.client.accept(wire(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, settings.getTracking().getHeartbeatPeriods())).stamp(100, 1)));
		endpoint.client.accept(wire(InventoryS2CPacket.policy(7, 100, 1, Set.of("entity_block")))); return endpoint;
	}
	@Test void publisherAndClientCloseUnchangedCutsAndRepairWithheldStreamEvenWhenPeriodicHeartbeatIsDisabled() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setHeartbeatPeriods(0);
		var transport = new InventorySyncPublisherTest.Transport(); var memory = new RetainedMemoryLedger(16000000); var marker = new MarkerId(1); var endpoint = negotiated(marker, settings);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			var context = InventorySyncPublisherTest.context("tracking/1", 1); publisher.advance(0, settings); publisher.register(context, InventorySyncPublisherTest.route(marker, "target"));
			InventorySyncPublisherTest.publish(publisher, context, value(1, 7)); publisher.drain(); transport.sent.forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear();
			publisher.advance(3, settings); InventorySyncPublisherTest.publish(publisher, context, value(2, 7)); publisher.drain(); transport.sent.forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear();
			assertEquals(2, endpoint.client.tracking(marker).watermark()); assertTrue(endpoint.repairs().isEmpty());
			publisher.advance(6, settings); InventorySyncPublisherTest.publish(publisher, context, value(3, 9)); publisher.drain();
			transport.sent.stream().filter(p -> p.kind() != InventoryS2CPacket.Kind.STREAM).forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear(); assertEquals(1, endpoint.repairs().size());
			publisher.advance(9, settings); InventorySyncPublisherTest.publish(publisher, context, value(4, 9)); publisher.drain(); transport.sent.forEach(p -> endpoint.client.accept(wire(p))); assertEquals(1, endpoint.repairs().size());
			assertEquals(7, endpoint.client.tracking(marker).entries().getFirst().count()); assertEquals(2, endpoint.client.tracking(marker).watermark(), "mismatch cannot fabricate a received cut");
		}
	}
	@Test void admittedMultipartBeyondFivePeriodsDefersFutureDigestAndRepeatedRequestsResumeSameBaseline() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(.25))); settings.getTracking().setHeartbeatPeriods(1);
		var transport = new InventorySyncPublisherTest.Transport(); var memory = new RetainedMemoryLedger(16000000); var marker = new MarkerId(1); var endpoint = negotiated(marker, settings);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			var context = InventorySyncPublisherTest.context("tracking/1", 1); publisher.advance(0, settings); publisher.register(context, InventorySyncPublisherTest.route(marker, "target"));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 12, 2500));
			int received = 0;
			for (int now = 0; now <= 60; now += 3) {
				if (now > 0) endpoint.tickTo(now - 3, now); publisher.advance(now, settings);
				InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 12, 2500)); publisher.drain();
				for (; received < transport.sent.size(); received++) endpoint.client.accept(wire(transport.sent.get(received)));
				assertTrue(endpoint.repairs().isEmpty(), "admitted stable baseline is not a future-cut timeout");
				if (now <= 15) assertTrue(publisher.pending(context));
			}
			assertTrue(endpoint.client.tracking(marker).complete()); assertEquals(12, endpoint.client.tracking(marker).entries().size()); assertEquals(1, endpoint.client.tracking(marker).watermark());
		}
		class BackpressuredHost extends InventoryBackendTest.Host {
			boolean blocked = true; final List<Long> attempts = new ArrayList<>();
			@Override public void send(UUID player, InventoryS2CPacket packet) {
				if (player.equals(InventoryBackendTest.A) && packet.kind() == InventoryS2CPacket.Kind.SNAPSHOT) {
					attempts.add(packet.baselineId()); if (blocked) throw new IllegalStateException("application send seam backpressure");
				}
				super.send(player, packet);
			}
		}
		var host = new BackpressuredHost(); var reads = new java.util.concurrent.atomic.AtomicInteger();
		try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 1)), 16000000), 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1, InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH), 0, settings); backend.tick(0, settings);
			var preview = host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(host.epoch(InventoryBackendTest.A), 100, 1, 1, 1, preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger"), 1, settings);
			var stored = host.store.allMarkers().getFirst(); var client = new Endpoint(InventoryBackendTest.A, MarkerSnapshot.from(stored)); backend.tick(2, settings); client.drain(host);
			long baseline = host.attempts.getFirst(); assertFalse(client.client.tracking(stored.id()).complete());
			for (int now = 5; now <= 23; now += 3) {
				client.tickTo(now - 3, now); backend.handle(InventoryBackendTest.A, InventoryC2SPacket.resync(host.epoch(InventoryBackendTest.A), 0, stored.id()).stamp(100, 1), now, settings);
				backend.tick(now, settings); client.drain(host);
			}
			assertTrue(host.attempts.size() > 5); assertTrue(host.attempts.stream().allMatch(id -> id == baseline), "repeated RESYNC resumes the admitted baseline, not a new one");
			host.blocked = false; backend.tick(26, settings); client.drain(host); count(client.client, stored.id(), 7); assertEquals(baseline, client.client.tracking(stored.id()).baselineId()); assertTrue(client.repairs().isEmpty());
		}
	}
	@Test void futureHeartbeatWithoutAnyApplicationDeliveryRequestsBoundedRepairInsteadOfSilentEviction() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setHeartbeatPeriods(1); var transport = new InventorySyncPublisherTest.Transport(); var marker = new MarkerId(1); var endpoint = negotiated(marker, settings);
		try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16000000), transport)) {
			var context = InventorySyncPublisherTest.context("tracking/1", 1); publisher.advance(0, settings); publisher.register(context, InventorySyncPublisherTest.route(marker, "target"));
			InventorySyncPublisherTest.publish(publisher, context, value(1, 7)); publisher.drain(); transport.sent.forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear();
			for (int now = 3; now <= 33; now += 3) {
				endpoint.tickTo(now - 3, now); publisher.advance(now, settings); InventorySyncPublisherTest.publish(publisher, context, value(now / 3 + 1, 9)); publisher.drain();
				// Reject STREAM and completion STATUS at the application seam; pass only heartbeat progress.
				transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.HEARTBEAT).forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear();
			}
			assertEquals(2, endpoint.repairs().size()); assertEquals(7, endpoint.client.tracking(marker).entries().getFirst().count()); assertEquals(1, endpoint.client.tracking(marker).watermark());
		}
	}
	@Test void missingMultipartApplicationPartDefersFutureChecksumThenRepairsCompletedGapWithoutPartialComparison() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setHeartbeatPeriods(16); var transport = new InventorySyncPublisherTest.Transport(); var marker = new MarkerId(1); var endpoint = negotiated(marker, settings);
		try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16000000), transport)) {
			var context = InventorySyncPublisherTest.context("tracking/1", 1); publisher.advance(0, settings); publisher.register(context, InventorySyncPublisherTest.route(marker, "target"));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 2, 0)); publisher.drain(); transport.sent.forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear();
			publisher.advance(3, settings); InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(2, 2, 550)); publisher.drain();
			var parts = transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STREAM).toList(); assertEquals(2, parts.size());
			var retainedPart = parts.getFirst(); int[] applied = {0};
			for (var packet : transport.sent) if (packet.kind() != InventoryS2CPacket.Kind.STREAM || applied[0]++ == 0) endpoint.client.accept(wire(packet));
			transport.sent.clear(); assertEquals(1, endpoint.client.tracking(marker).watermark()); assertTrue(endpoint.repairs().isEmpty(), "the future completed digest cannot compare the partial live projection");
			endpoint.tickTo(0, 15); assertEquals(1, endpoint.repairs().size(), "completed but unapplied gap repairs after the negotiated interval");
			endpoint.client.accept(wire(parts.getLast())); assertEquals(2, endpoint.client.tracking(marker).watermark()); int repairs = endpoint.repairs().size();
			endpoint.tickTo(15, 35); assertEquals(repairs, endpoint.repairs().size(), "receiving the missing admitted part clears the gap");
			endpoint.client.accept(wire(retainedPart)); assertEquals(2, endpoint.client.tracking(marker).watermark());
		}
	}
	@Test void failedRecoveryFenceTransmissionBlocksAllBaselinePartsUntilSameFenceCanBeSent() {
		var settings = InventorySettings.serverDefaults(); var marker = new MarkerId(1);
		class FenceTransport extends InventorySyncPublisherTest.Transport {
			boolean blocked = true;
			@Override public void send(UUID recipient, InventoryS2CPacket packet) {
				if (blocked && packet.kind() == InventoryS2CPacket.Kind.STATUS && packet.status() == InventoryS2CPacket.Status.UPDATING) throw new IllegalStateException("fence send failure");
				super.send(recipient, packet);
			}
		}
		var transport = new FenceTransport();
		try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16000000), transport)) {
			publisher.advance(0, settings); var context = new SyncPublisher.Context("tracking/1", InventoryBackendTest.A, "100/1", 3, 6); publisher.register(context, InventorySyncPublisherTest.route(marker, "target"));
			InventorySyncPublisherTest.publish(publisher, context, value(2, 11)); publisher.drain(); assertTrue(transport.sent.isEmpty()); assertTrue(publisher.pending(context));
			transport.blocked = false; publisher.advance(3, settings); publisher.drain(); assertEquals(InventoryS2CPacket.Kind.STATUS, transport.sent.getFirst().kind()); assertEquals(InventoryS2CPacket.Kind.SNAPSHOT, transport.sent.get(1).kind());
		}
	}
	@Test void completedBaselineWithMissingApplicationPartRepairsButDuplicateControlsNeverResetItsAssemblyOrGapTimer() {
		var settings = InventorySettings.serverDefaults(); var transport = new InventorySyncPublisherTest.Transport(); var marker = new MarkerId(1); var endpoint = negotiated(marker, settings);
		try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16000000), transport)) {
			var context = InventorySyncPublisherTest.context("tracking/1", 1); publisher.advance(0, settings); publisher.register(context, InventorySyncPublisherTest.route(marker, "target"));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 2, 0)); publisher.drain();
			var missing = transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && p.partIndex() == 1).findFirst().orElseThrow();
			transport.sent.stream().filter(p -> p != missing).forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear(); assertFalse(endpoint.client.tracking(marker).complete()); assertTrue(endpoint.repairs().isEmpty());
			for (int now = 3; now <= 33; now += 3) {
				endpoint.tickTo(now - 3, now); publisher.advance(now, settings); InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(now / 3 + 1, 2, 0)); publisher.drain();
				transport.sent.forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear();
			}
			assertEquals(2, endpoint.repairs().size(), "completion controls prove the missing part was transmitted, but repeated controls cannot postpone repair indefinitely");
			endpoint.client.accept(wire(missing)); assertTrue(endpoint.client.tracking(marker).complete()); assertEquals(2, endpoint.client.tracking(marker).entries().size()); assertEquals(12, endpoint.client.tracking(marker).watermark());
			int repairs = endpoint.repairs().size(); endpoint.tickTo(33, 70); assertEquals(repairs, endpoint.repairs().size());
		}
	}
}
