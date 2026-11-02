package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Publication remains lane-homogeneous while queue, wire and memory admission stay global. */
@NotNullByDefault
class InventoryResourcePublisherTest {
	private static final UUID OWNER = new UUID(92, 3);
	@NotNullByDefault
	private static final class Transport implements InventorySyncPublisher.Transport {
		final List<InventoryS2CPacket> sent = new ArrayList<>();
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public int encodedBytes(InventoryS2CPacket packet) { return packet.encodedSize(4096, 8192); }
		@Override public void send(UUID recipient, InventoryS2CPacket packet) { sent.add(packet); }
	}
	private static SyncPublisher.Context context(String id, long baseline) { return new SyncPublisher.Context(id, OWNER, "100/1", 1, baseline); }
	private static InventorySyncPublisher.Route route(InventoryResourceKind kind, @Nullable MarkerId marker) {
		return new InventorySyncPublisher.Route(7, 100, 1, marker == null ? 1 : 0, marker, "same-target", marker == null ? null : "attention", kind);
	}
	private static CaptureResult.OpaqueKeyedFragment payload(InventoryResourceKind kind, long amount, boolean aggregate) {
		var value = InventoryResourceCodec.value(new InventoryScanner.Key(kind, "minecraft:water", aggregate ? "" : "native"),
			amount, "water", null, aggregate);
		return new CaptureResult.OpaqueKeyedFragment(InventoryResourceCodec.id(kind), Map.of("opaque", InventoryResourceCodec.encode(kind, value)));
	}
	private static CaptureResult capture(InventoryResourceKind kind, long revision, long amount, boolean aggregate) {
		return new CaptureResult(Optional.of(payload(kind, amount, aggregate)), new CaptureResult.Coverage("scope", revision, 1, OptionalLong.of(1)),
			CaptureResult.Availability.READABLE, CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL,
			Optional.empty(), Optional.empty());
	}
	private static SyncPublisher.AuthorizedProjection projection() {
		return new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, Set.of("opaque"));
	}
	private static CostLedger work(long entries) { return new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, entries)); }

	@Test void wrongLanePayloadRejectsBeforeSpendingPublicationGrantAndCannotReplaceSibling() {
		var memory = new RetainedMemoryLedger(16_000_000); var transport = new Transport(); var settings = InventorySettings.serverDefaults();
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var item = context("preview/item", 1); var fluid = context("preview/fluid", 2);
			assertTrue(publisher.register(item, route(InventoryResourceKind.ITEM, null)));
			assertTrue(publisher.register(fluid, route(InventoryResourceKind.FLUID, null)));
			var ledger = work(2);
			assertInstanceOf(SyncPublisher.Outcome.Rejected.class, publisher.publish(capture(InventoryResourceKind.ITEM, 1, 81, false), projection(), fluid, ledger));
			assertEquals(0, ledger.used(InventorySyncPublisher.PUBLICATIONS));
			for (var entry : Map.of(item, InventoryResourceKind.ITEM, fluid, InventoryResourceKind.FLUID).entrySet())
				assertInstanceOf(SyncPublisher.Outcome.Accepted.class, publisher.publish(capture(entry.getValue(), 1, 81, false),
					InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, projection(), entry.getKey(), ledger));
			assertEquals(2, ledger.used(InventorySyncPublisher.PUBLICATIONS)); publisher.drain();
			assertInstanceOf(SyncPublisher.Outcome.Rejected.class, publisher.publish(capture(InventoryResourceKind.ITEM, 1, 81, false), projection(), fluid, work(0)),
				"an old duplicate watermark cannot bypass the route's codec fence");
			for (var kind : InventoryResourceKind.values()) {
				var packet = transport.sent.stream().filter(value -> value.resourceKind() == kind && !value.entries().isEmpty()).findFirst().orElseThrow();
				assertTrue(packet.completeScan()); assertEquals(81, packet.entries().getFirst().amount());
				assertEquals(InventoryChecksums.checksumEntries(kind, packet.entries()), packet.checksum());
				var other = kind == InventoryResourceKind.ITEM ? InventoryResourceKind.FLUID : InventoryResourceKind.ITEM;
				assertNotEquals(InventoryChecksums.checksumEntries(other, packet.entries()), packet.checksum());
			}
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void lanePoliciesShareOfferButDoNotReplaceEachOthersPendingControl() {
		var memory = new RetainedMemoryLedger(16_000_000); var transport = new Transport();
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, InventorySettings.serverDefaults());
			assertTrue(publisher.control(OWNER, InventoryS2CPacket.policy(InventoryResourceKind.ITEM, 7, 100, 1, Set.of("entity_block"))));
			assertTrue(publisher.control(OWNER, InventoryS2CPacket.policy(InventoryResourceKind.FLUID, 7, 100, 1, Set.of())));
			publisher.drainControls(); assertEquals(2, transport.sent.size());
			assertEquals(Set.of(InventoryResourceKind.ITEM, InventoryResourceKind.FLUID), transport.sent.stream().map(InventoryS2CPacket::resourceKind).collect(java.util.stream.Collectors.toSet()));
			assertTrue(transport.sent.stream().filter(packet -> packet.resourceKind() == InventoryResourceKind.FLUID).allMatch(packet -> packet.allowedTypes().isEmpty()));
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void invalidFluidSeedNeverReappearsAfterRebaseWhileItemStreamKeepsItsOwnCut() {
		var memory = new RetainedMemoryLedger(16_000_000); var transport = new Transport(); var settings = InventorySettings.serverDefaults();
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var item = context("tracking/item", 1); var fluid = context("tracking/fluid", 2);
			assertTrue(publisher.register(item, route(InventoryResourceKind.ITEM, new MarkerId(1))));
			assertTrue(publisher.register(fluid, route(InventoryResourceKind.FLUID, new MarkerId(2))));
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, publisher.publish(capture(InventoryResourceKind.ITEM, 1, 7, false), projection(), item, work(1)));
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, publisher.publishBootstrap(payload(InventoryResourceKind.FLUID, 81000, false), 1,
				InventoryS2CPacket.Status.UPDATING, projection(), fluid, work(1)));
			publisher.status(fluid, InventoryS2CPacket.Status.INVALID); publisher.drain();
			assertTrue(transport.sent.stream().anyMatch(packet -> packet.resourceKind() == InventoryResourceKind.ITEM && !packet.entries().isEmpty()));
			assertTrue(transport.sent.stream().filter(packet -> packet.resourceKind() == InventoryResourceKind.FLUID).allMatch(packet -> packet.entries().isEmpty()));
			publisher.rebase(fluid, 2); var recovered = new SyncPublisher.Context(fluid.consumerId(), OWNER, fluid.sessionView(), 2, fluid.baseline());
			assertInstanceOf(SyncPublisher.Outcome.Rejected.class, publisher.publishBootstrap(payload(InventoryResourceKind.FLUID, 81000, false), 2,
				InventoryS2CPacket.Status.UPDATING, projection(), recovered, work(1)));
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, publisher.publish(capture(InventoryResourceKind.FLUID, 3, 0, false),
				InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, projection(), recovered, work(1)));
			publisher.drain();
			assertTrue(transport.sent.stream().anyMatch(packet -> packet.resourceKind() == InventoryResourceKind.FLUID && packet.completeScan()
				&& packet.statusRevision() == 2 && packet.entries().getFirst().amount() == 0));
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
}
