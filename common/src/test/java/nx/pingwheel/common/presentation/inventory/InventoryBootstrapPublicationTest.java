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
import nx.pingwheel.common.network.InventoryS2CPacket.Status;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.render.InventoryTrackingRenderer;
import nx.pingwheel.common.render.SpatialInventoryView;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real writer/reader deliveries; bootstrap is presentation, never capture evidence. */
@NotNullByDefault
class InventoryBootstrapPublicationTest {
	@org.junit.jupiter.api.BeforeAll static void bootstrapMinecraft() {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
	}
	private static final long LONG_COUNT = 9_007_199_254_740_993L;
	private static final MarkerId MARKER = new MarkerId(1);
	private static final SyncPublisher.AuthorizedProjection SELECTED = new SyncPublisher.AuthorizedProjection(
		SyncPublisher.PublicationForm.KEYED_ABSOLUTE, Set.of("selected"));

	private static CaptureResult.OpaqueKeyedFragment payload(long count, boolean aggregate, @Nullable String display) {
		var item = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", aggregate ? "" : "exact"),
			count, "stone", display, aggregate);
		return new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, Map.of("selected", InventoryDomainCodec.encode(item)));
	}
	private static CostLedger work() { return new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L)); }
	private static CaptureResult complete(long revision, long count, boolean aggregate) {
		return new CaptureResult(Optional.of(payload(count, aggregate, null)),
			new CaptureResult.Coverage("inventory", revision, 1, OptionalLong.of(1)), CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
	}
	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		final InventorySettings settings = InventorySettings.serverDefaults();
		final InventorySyncPublisherTest.Transport transport = new InventorySyncPublisherTest.Transport();
		final RetainedMemoryLedger memory = new RetainedMemoryLedger(16_000_000);
		final InventorySyncPublisher publisher = new InventorySyncPublisher(memory, transport);
		final InventoryPublicationIntegrationTest.Endpoint endpoint = InventoryPublicationIntegrationTest.negotiated(MARKER, settings);
		SyncPublisher.Context context = InventorySyncPublisherTest.context("tracking/1", 1);
		Fixture() { publisher.advance(0, settings); assertTrue(publisher.register(context, InventorySyncPublisherTest.route(MARKER, "target"))); }
		void bootstrap(long revision, Status status, boolean aggregate) {
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, publisher.publishBootstrap(payload(LONG_COUNT, aggregate, null), revision,
				status, SELECTED, context, work()));
		}
		@Unmodifiable List<InventoryS2CPacket> drain() {
			publisher.drain(); var frames = List.copyOf(transport.sent); transport.sent.clear();
			for (var packet : frames) endpoint.client.accept(InventoryPublicationIntegrationTest.wire(packet));
			return frames;
		}
		ClientInventory.Tracking view() { return endpoint.client.tracking(MARKER); }
		@Override public void close() { publisher.close(); assertEquals(0, memory.retained()); assertEquals(0, memory.reserved()); }
	}

	@Test void longBootstrapIsQuantityBearingButOnlyTheFirstFreshScanCompletesAndAlwaysRevisionsEvenEqualAggregates() {
		for (boolean aggregate : new boolean[] {false, true}) for (boolean nativeSweep : new boolean[] {false, true})
			for (long count : new long[] {LONG_COUNT, 19, 0}) try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, aggregate); var seed = f.drain();
			assertTrue(seed.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && !p.entries().isEmpty()));
			assertTrue(seed.stream().noneMatch(InventoryS2CPacket::completeScan));
			assertEquals(LONG_COUNT, f.view().entries().getFirst().count()); assertFalse(f.view().complete());
			assertEquals(Status.UPDATING, f.view().status()); assertFalse(f.view().grey());
			assertEquals(aggregate ? Status.COMPONENT_TOO_LONG : Status.UPDATING, f.view().entries().getFirst().quality());
			f.publisher.advance(3, f.settings);
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(complete(2, count, aggregate),
				nativeSweep ? InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP : InventoryStep.SweepEvidence.NONE, SELECTED, f.context, work()));
			var fresh = f.drain();
			assertTrue(fresh.stream().anyMatch(p -> p.completeScan() && p.entries().stream().anyMatch(e -> e.itemRevision() == 2)));
			assertTrue(f.view().complete()); assertEquals(count, f.view().entries().getFirst().count());
			assertEquals(nativeSweep ? Status.READY : Status.UNCERTAIN, f.view().status());
			for (var old : seed) f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(old));
			assertEquals(count, f.view().entries().getFirst().count()); assertTrue(f.view().complete());
			assertEquals(nativeSweep ? Status.READY : Status.UNCERTAIN, f.view().status());
			assertInstanceOf(SyncPublisher.Outcome.Rejected.class, f.publisher.publishBootstrap(payload(LONG_COUNT, aggregate, null),
				3, Status.UPDATING, SELECTED, f.context, work()));
		}
	}

	@Test void narrowBootstrapDoesNotAuthorizeGenericPartialCapturesOrPreviewOrMultipleSelections() {
		try (var f = new Fixture()) {
			var source = complete(1, LONG_COUNT, false);
			for (var progress : List.of(CaptureResult.Completeness.CONTINUE, CaptureResult.Completeness.INCOMPLETE)) {
				var partial = new CaptureResult(source.payload(), source.coverage(), source.availability(), progress,
					source.consistency(), source.sourceVersion(), source.nextCursor());
				assertInstanceOf(SyncPublisher.Outcome.Rejected.class, f.publisher.publish(partial, SELECTED, f.context, work()));
			}
			assertInstanceOf(SyncPublisher.Outcome.Rejected.class, f.publisher.publishBootstrap(payload(1, false, null), 1,
				Status.READY, SELECTED, f.context, work()));
			assertInstanceOf(SyncPublisher.Outcome.Rejected.class, f.publisher.publishBootstrap(payload(1, false, null), 1,
				Status.UPDATING, new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, Set.of("selected", "other")), f.context, work()));
			var preview = InventorySyncPublisherTest.context("preview/1", 2);
			assertTrue(f.publisher.register(preview, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null)));
			assertInstanceOf(SyncPublisher.Outcome.Rejected.class, f.publisher.publishBootstrap(payload(1, false, null), 1,
				Status.UPDATING, SELECTED, preview, work()));
			assertTrue(f.drain().stream().noneMatch(p -> !p.entries().isEmpty()));
		}
	}

	@Test void provisionalHeartbeatClosesTransportCutWithoutCompletingSourceOrRequestingEndlessRepair() {
		try (var f = new Fixture()) {
			f.settings.getTracking().setHeartbeatPeriods(1); f.bootstrap(1, Status.UPDATING, false); f.drain();
			for (int tick = 3; tick < 90; tick += 3) {
				f.publisher.advance(tick, f.settings); f.drain(); f.endpoint.tickTo(tick - 3, tick);
			}
			assertFalse(f.view().complete()); assertEquals(LONG_COUNT, f.view().entries().getFirst().count()); assertTrue(f.endpoint.repairs().isEmpty());
		}
	}

	@Test void failedSendAndMemoryRefusalRetryTheSameSeedAndReleaseEveryCharge() {
		try (var f = new Fixture()) {
			f.memory.setCap(f.memory.retained());
			assertInstanceOf(SyncPublisher.Outcome.Deferred.class, f.publisher.publishBootstrap(payload(LONG_COUNT, false, null), 1,
				Status.UPDATING, SELECTED, f.context, work()));
			assertEquals(0, f.memory.reserved()); assertTrue(f.transport.sent.isEmpty());
			f.memory.setCap(16_000_000); f.bootstrap(1, Status.UPDATING, false);
			f.transport.failNextSend = true; f.drain(); assertTrue(f.view().entries().isEmpty());
			f.publisher.advance(3, f.settings); f.drain(); assertEquals(LONG_COUNT, f.view().entries().getFirst().count());
			assertFalse(f.publisher.pending(f.context)); assertFalse(f.view().complete()); assertEquals(0, f.memory.reserved());
		}
	}

	@Test void incompleteWithdrawsImmutableUpdatingFramesAndLateArrivalsCannotRegressReceivedHistory() {
		for (boolean received : new boolean[] {false, true}) try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, false); f.publisher.drain();
			var old = List.copyOf(f.transport.sent); f.transport.sent.clear();
			if (received) old.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			f.publisher.status(f.context, Status.INCOMPLETE); f.drain();
			old.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertEquals(Status.INCOMPLETE, f.view().status()); assertTrue(f.view().grey()); assertFalse(f.view().complete());
			assertEquals(received ? 1 : 0, f.view().entries().size());
			f.bootstrap(2, Status.INCOMPLETE, false); f.publisher.advance(3, f.settings);
			var replay = f.drain(); assertTrue(replay.stream().filter(p -> !p.entries().isEmpty()).allMatch(p -> p.status() == Status.INCOMPLETE));
			assertEquals(LONG_COUNT, f.view().entries().getFirst().count()); assertFalse(f.view().complete());
		}
	}

	@Test void invalidWithdrawsUnsentSeedAndRecoveryNeverRevivesItWhileReceivedHistoryStaysGreyUntilFreshScan() {
		for (boolean received : new boolean[] {false, true}) try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, false);
			if (received) f.drain(); else f.publisher.gateData(f.context.recipient());
			f.publisher.rebase(f.context, 2); f.context = new SyncPublisher.Context(f.context.consumerId(), f.context.recipient(), "100/1", 2, 1);
			f.publisher.status(f.context, Status.INVALID); f.drain(); assertFalse(f.publisher.pending(f.context));
			assertEquals(received ? 1 : 0, f.view().entries().size()); assertTrue(f.view().grey()); assertFalse(f.view().complete());
			f.publisher.rebase(f.context, 3); f.context = new SyncPublisher.Context(f.context.consumerId(), f.context.recipient(), "100/1", 3, 1);
			assertInstanceOf(SyncPublisher.Outcome.Rejected.class, f.publisher.publishBootstrap(payload(LONG_COUNT, false, null), 2,
				Status.UPDATING, SELECTED, f.context, work()));
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.status(7, 0, MARKER, 1, 3, 0, Status.UPDATING, 0).stamp(100, 1)));
			assertEquals(received ? 1 : 0, f.view().entries().size());
			if (received) { assertTrue(f.view().grey()); assertEquals(Status.INVALID, f.view().status()); }
			f.publisher.releaseData(f.context.recipient()); f.publisher.advance(3, f.settings);
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(complete(2, 0, false), InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP,
				SELECTED, f.context, work())); f.drain();
			assertTrue(f.view().complete()); assertFalse(f.view().grey()); assertEquals(0, f.view().entries().getFirst().count());
		}
	}

	@Test void precompletionRebaseKeepsBoundedGreyDisplayOutsideTheNewDigestAndSeedRepairStillDoesNotComplete() {
		try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, false); f.drain(); f.publisher.status(f.context, Status.INCOMPLETE); f.drain();
			f.publisher.rebase(f.context, 2); f.context = new SyncPublisher.Context(f.context.consumerId(), f.context.recipient(), "100/1", 2, 1);
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.status(7, 0, MARKER, 1, 2, 0, Status.UPDATING, 0).stamp(100, 1)));
			assertEquals(LONG_COUNT, f.view().entries().getFirst().count()); assertEquals(Status.INCOMPLETE, f.view().status()); assertTrue(f.view().grey());
			f.bootstrap(2, Status.INCOMPLETE, false); f.publisher.advance(3, f.settings); f.drain();
			assertFalse(f.view().complete()); assertEquals(Status.INCOMPLETE, f.view().status()); assertTrue(f.endpoint.repairs().isEmpty());
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(complete(3, 19, false), InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP,
				SELECTED, f.context, work())); f.drain();
			assertTrue(f.view().complete()); assertEquals(19, f.view().entries().getFirst().count()); assertFalse(f.view().grey());
		}
	}

	@Test void completeStatusCannotBypassMissingFreshPartsOrTurnAClosedSeedIntoTheFirstScan() {
		try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, false); f.drain();
			long digest = InventoryChecksums.checksumEntries(List.of(new InventoryS2CPacket.Entry("selected", "minecraft:stone", "stone", null,
				LONG_COUNT, 1, false, Status.UPDATING, 0, false, "danger")));
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.status(7, 0, MARKER, 1, 1, 2, Status.READY, digest).stamp(100, 1)));
			assertFalse(f.view().complete());
			var item = new InventoryS2CPacket.Entry("selected", "minecraft:stone", "stone", null, 7, 3, false, Status.READY, 0, false, "danger");
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 7, 0, MARKER,
				1, 1, 3, 0, 2, true, Status.READY, 0, List.of(item)).stamp(100, 1)));
			assertFalse(f.view().complete());
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 7, 0, MARKER,
				1, 1, 3, 1, 2, true, Status.READY, 0, List.of()).stamp(100, 1)));
			assertTrue(f.view().complete()); assertEquals(7, f.view().entries().getFirst().count());
		}
	}

	@Test void recipientsNeedNoPreviewAndTheirAdmissionCancellationAndBaselinesStayIndependent() {
		var settings = InventorySettings.serverDefaults(); var memory = new RetainedMemoryLedger(16_000_000);
		UUID a = new UUID(1, 1), b = new UUID(2, 2);
		@NotNullByDefault
		class Transport implements InventorySyncPublisher.Transport {
			boolean admitB;
			final @Unmodifiable Map<UUID, List<InventoryS2CPacket>> sent = Map.of(a, new ArrayList<>(), b, new ArrayList<>());
			@Override public boolean authorized(SyncPublisher.Context context) { return true; }
			@Override public boolean admittedToSend(SyncPublisher.Context context) { return context.recipient().equals(a) || admitB; }
			@Override public int encodedBytes(InventoryS2CPacket packet) { return InventorySyncPublisherTest.bytes(packet); }
			@Override public void send(UUID recipient, InventoryS2CPacket packet) { sent.get(recipient).add(InventoryPublicationIntegrationTest.wire(packet)); }
		}
		var transport = new Transport();
		var first = InventoryPublicationIntegrationTest.negotiated(MARKER, settings);
		var second = InventoryPublicationIntegrationTest.negotiated(MARKER, settings);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings);
			var ca = new SyncPublisher.Context("tracking/1", a, "100/1", 1, 1);
			var cb = new SyncPublisher.Context("tracking/1", b, "100/1", 1, 2);
			for (var context : List.of(ca, cb)) {
				assertTrue(publisher.register(context, InventorySyncPublisherTest.route(MARKER, "target")));
				assertInstanceOf(SyncPublisher.Outcome.Accepted.class, publisher.publishBootstrap(payload(LONG_COUNT, false, null),
					1, Status.UPDATING, SELECTED, context, work()));
			}
			publisher.drain(); transport.sent.get(a).forEach(first.client::accept); transport.sent.get(a).clear();
			assertEquals(LONG_COUNT, first.client.tracking(MARKER).entries().getFirst().count());
			assertTrue(transport.sent.get(b).isEmpty(), "the host Basic admission seam gates both fence and seed");
			assertTrue(second.client.tracking(MARKER).entries().isEmpty()); assertEquals(0, second.client.stats().previewChannels());
			publisher.cancel(ca); transport.admitB = true; publisher.advance(3, settings); publisher.drain();
			transport.sent.get(b).forEach(second.client::accept);
			assertEquals(LONG_COUNT, second.client.tracking(MARKER).entries().getFirst().count());
			assertEquals(2, second.client.tracking(MARKER).baselineId()); assertFalse(second.client.tracking(MARKER).complete());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}

	@Test void capLoweringStripsHistoricalDisplayButKeepsLongQuantityAndTerminalRepairCannotResurrectDetail() {
		try (var f = new Fixture()) {
			String display = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_name\":\"" + "x".repeat(2000) + "\"}}";
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publishBootstrap(payload(LONG_COUNT, false, display), 1,
				Status.UPDATING, SELECTED, f.context, work())); f.drain();
			assertEquals(display, f.view().entries().getFirst().displayJson());
			f.publisher.status(f.context, Status.INCOMPLETE); f.drain();
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.offer(7,
				new InventoryS2CPacket.Offer(5, 3, 5, 16, 1024, 8192)).stamp(100, 1)));
			assertNull(f.view().entries().getFirst().displayJson()); assertEquals(LONG_COUNT, f.view().entries().getFirst().count());
			f.publisher.rebase(f.context, 2); f.context = new SyncPublisher.Context(f.context.consumerId(), f.context.recipient(), "100/1", 2, 1);
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.status(7, 0, MARKER, 1, 2, 0, Status.UPDATING, 0).stamp(100, 1)));
			assertNull(f.view().entries().getFirst().displayJson()); assertTrue(f.view().grey());
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publishBootstrap(payload(LONG_COUNT, false, "{\"id\":\"minecraft:stone\"}"),
				2, Status.INCOMPLETE, SELECTED, f.context, work())); f.publisher.advance(3, f.settings); f.drain();
			assertFalse(f.view().complete()); assertEquals(LONG_COUNT, f.view().entries().getFirst().count());
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.offer(7,
				new InventoryS2CPacket.Offer(5, 3, 5, 16)).stamp(100, 1)));
			assertNotEquals(display, f.view().entries().getFirst().displayJson());
		}
	}

	@Test void missingSeedCannotBeReplacedByStatusAndLatePartsCannotDowngradeTheFreshFullCut() {
		try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, false); f.publisher.drain();
			var seed = List.copyOf(f.transport.sent); f.transport.sent.clear();
			seed.stream().filter(p -> p.entries().isEmpty()).forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertTrue(f.view().entries().isEmpty()); assertFalse(f.view().complete());
			f.publisher.advance(3, f.settings);
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(complete(2, 0, false), InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP,
				SELECTED, f.context, work())); f.drain();
			assertTrue(f.view().complete()); assertEquals(0, f.view().entries().getFirst().count());
			seed.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertTrue(f.view().complete()); assertEquals(Status.READY, f.view().status()); assertEquals(0, f.view().entries().getFirst().count());
		}
	}

	@Test void equalAggregateCompletionControlBeforeFreshDataCannotRetireItsRangeOrClaimTheSeedIsFresh() {
		try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, true); f.drain(); f.publisher.advance(3, f.settings);
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(complete(2, LONG_COUNT, true),
				InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, SELECTED, f.context, work())); f.publisher.drain();
			var frames = List.copyOf(f.transport.sent); f.transport.sent.clear();
			frames.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STATUS)
				.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertFalse(f.view().complete()); assertEquals(1, f.view().watermark());
			frames.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STREAM)
				.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertTrue(f.view().complete()); assertEquals(2, f.view().watermark()); assertEquals(Status.READY, f.view().status());
			assertTrue(f.endpoint.repairs().isEmpty());
		}
	}

	@Test void actualCompletionControlsKeepExactAndAggregateSeedVisiblyUpdatingUntilFreshDataArrives() {
		for (boolean aggregate : new boolean[] {false, true}) for (boolean nativeSweep : new boolean[] {false, true})
			try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, aggregate); var seed = f.drain();
			f.publisher.advance(3, f.settings);
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(complete(2, 19, aggregate),
				nativeSweep ? InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP : InventoryStep.SweepEvidence.NONE, SELECTED, f.context, work()));
			f.publisher.drain(); var fresh = List.copyOf(f.transport.sent); f.transport.sent.clear();
			assertTrue(fresh.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.STATUS
				&& p.status() == (nativeSweep ? Status.READY : Status.UNCERTAIN)));
			fresh.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STATUS)
				.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertProvisionalLine(f.view(), Status.UPDATING, LONG_COUNT, aggregate);
			fresh.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STREAM)
				.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertTrue(f.view().complete()); assertEquals(19, f.view().entries().getFirst().count());
			assertEquals(nativeSweep ? Status.READY : Status.UNCERTAIN, f.view().status());
			seed.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertEquals(nativeSweep ? Status.READY : Status.UNCERTAIN, f.view().status());
			f.endpoint.tickTo(0, 90);
			assertTrue(f.endpoint.repairs().isEmpty(), "accepted controls still close normally after full data receipt");
		}
	}

	private static void assertProvisionalLine(ClientInventory.Tracking view, Status status, long count, boolean aggregate) {
		assertFalse(view.complete()); assertEquals(status, view.status());
		var line = InventoryTrackingRenderer.lines(view).getFirst();
		assertEquals(count, line.count());
		assertEquals(SpatialInventoryView.Status.fromName(status.name()).translationKey(), line.statusKey());
		assertEquals(aggregate ? SpatialInventoryView.Status.COMPONENT_TOO_LONG.translationKey() : null, line.qualifierKey());
		assertEquals(aggregate || status != Status.UPDATING, line.grey());
	}

	@Test void completionControlsAndReorderedMultipartDataPreserveSeedAndFailureDisplayUntilTheFullFreshCut() {
		for (boolean aggregate : new boolean[] {false, true}) for (Status failure : List.of(Status.UPDATING, Status.INCOMPLETE, Status.INVALID))
			try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, aggregate); var seed = f.drain();
			long revision = 1;
			if (failure == Status.INCOMPLETE) { f.publisher.status(f.context, failure); f.drain(); }
			if (failure == Status.INVALID) {
				f.publisher.rebase(f.context, 2);
				f.context = new SyncPublisher.Context(f.context.consumerId(), f.context.recipient(), "100/1", 2, 1);
				f.publisher.status(f.context, failure); f.drain(); revision = 3;
			}
			for (Status completed : List.of(Status.READY, Status.UNCERTAIN))
				f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.status(7, 0, MARKER, 1, revision,
					3, completed, 0).stamp(100, 1)));
			assertProvisionalLine(f.view(), failure, LONG_COUNT, aggregate);
			var item = new InventoryS2CPacket.Entry("selected", "minecraft:stone", "stone", null, 19, 2,
				aggregate, aggregate ? Status.COMPONENT_TOO_LONG : Status.READY, aggregate ? 2 : 0, aggregate, "danger");
			for (int index : new int[] {1, 0}) {
				f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM,
					7, 0, MARKER, 1, revision, 3, index, 3, true, Status.READY, 0,
					index == 1 ? List.of(item) : List.of()).stamp(100, 1)));
				assertProvisionalLine(f.view(), failure, LONG_COUNT, aggregate);
			}
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM,
				7, 0, MARKER, 1, revision, 3, 2, 3, true, Status.READY, 0, List.of()).stamp(100, 1)));
			assertTrue(f.view().complete()); assertEquals(Status.READY, f.view().status());
			assertEquals(19, InventoryTrackingRenderer.lines(f.view()).getFirst().count());
			seed.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertEquals(Status.READY, f.view().status()); assertEquals(19, f.view().entries().getFirst().count());
		}
	}

	@Test void failureAfterEarlyCompletionControlStaysVisibleAndCompletedControlsStillRefreshWithoutEntryResends() {
		try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, false); f.drain(); f.publisher.advance(3, f.settings);
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(complete(2, 19, false),
				InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, SELECTED, f.context, work()));
			f.publisher.drain(); var fresh = List.copyOf(f.transport.sent); f.transport.sent.clear();
			var completed = fresh.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STATUS && p.status() == Status.READY).findFirst().orElseThrow();
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(completed));
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.status(7, 0, MARKER, 1, 1,
				completed.watermark(), Status.INCOMPLETE, 0).stamp(100, 1)));
			assertProvisionalLine(f.view(), Status.INCOMPLETE, LONG_COUNT, false);
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(completed));
			assertProvisionalLine(f.view(), Status.INCOMPLETE, LONG_COUNT, false);
			fresh.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STREAM)
				.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertTrue(f.view().complete()); assertEquals(Status.READY, f.view().status());
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.status(7, 0, MARKER, 1, 1,
				completed.watermark(), Status.UNCERTAIN, completed.checksum()).stamp(100, 1)));
			assertTrue(f.view().complete()); assertEquals(Status.UNCERTAIN, f.view().status());
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(completed));
			assertEquals(Status.READY, f.view().status()); assertEquals(19, f.view().entries().getFirst().count());
			assertTrue(f.endpoint.repairs().isEmpty());
		}
	}

	@Test void loweringDisplayCapStripsTheClosedProvisionalProjectionWithoutResurrectionOnIncrease() {
		try (var f = new Fixture()) {
			String display = "x".repeat(2048);
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publishBootstrap(payload(LONG_COUNT, false, display),
				1, Status.UPDATING, SELECTED, f.context, work())); f.drain();
			assertEquals(display, f.view().entries().getFirst().displayJson());
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.offer(7,
				new InventoryS2CPacket.Offer(5, 3, 5, 16, 1024, 8192)).stamp(100, 1)));
			assertNull(f.view().entries().getFirst().displayJson());
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.offer(7,
				new InventoryS2CPacket.Offer(5, 3, 5, 16)).stamp(100, 1)));
			assertNull(f.view().entries().getFirst().displayJson()); assertEquals(LONG_COUNT, f.view().entries().getFirst().count());
			assertFalse(f.view().complete()); assertEquals(Status.UPDATING, f.view().status());
		}
	}

	@Test void failureDuringFirstMultipartDeliveryRetainsTheSeedInsteadOfItsPartiallyAppliedReplacement() {
		for (Status failure : List.of(Status.INCOMPLETE, Status.INVALID)) try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, false); f.drain();
			var item = new InventoryS2CPacket.Entry("selected", "minecraft:stone", "stone", null, 19, 2, false, Status.READY);
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM,
				7, 0, MARKER, 1, 1, 2, 0, 2, true, Status.READY, 0, List.of(item)).stamp(100, 1)));
			assertProvisionalLine(f.view(), Status.UPDATING, LONG_COUNT, false);
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.status(7, 0, MARKER, 1,
				failure == Status.INVALID ? 2 : 1, 2, failure, 0).stamp(100, 1)));
			assertProvisionalLine(f.view(), failure, LONG_COUNT, false);
			if (failure == Status.INVALID) {
				var invalid = InventoryS2CPacket.status(7, 0, MARKER, 1, 2, 2, Status.INVALID, 0).stamp(100, 1);
				f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(invalid));
				assertProvisionalLine(f.view(), Status.INVALID, LONG_COUNT, false);
				f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.status(7, 0, MARKER, 2,
					3, 0, Status.UPDATING, 0).stamp(100, 1)));
				assertProvisionalLine(f.view(), Status.INVALID, LONG_COUNT, false);
				var recovery = InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, 7, 0, MARKER, 2, 3,
					3, 0, 2, true, Status.UNCERTAIN, 0, List.of(item)).stamp(100, 1);
				f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(recovery));
				assertProvisionalLine(f.view(), Status.INVALID, LONG_COUNT, false);
				f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM,
					7, 0, MARKER, 2, 3, 3, 1, 2, true, Status.UNCERTAIN, 0, List.of()).stamp(100, 1)));
				assertTrue(f.view().complete()); assertEquals(Status.UNCERTAIN, f.view().status());
				assertEquals(19, f.view().entries().getFirst().count());
				f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(invalid));
				assertTrue(f.view().complete()); assertEquals(19, f.view().entries().getFirst().count());
			}
		}
	}

	@Test void actualPublisherMultipartHistorySurvivesInvalidationAndRecoveryWithoutExposingAnUnclosedKey() {
		try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, false); f.drain(); f.publisher.advance(3, f.settings);
			var selected = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "exact"), 19, "stone", null, false);
			var second = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:dirt", "exact"), 1, "dirt", null, false);
			var projection = new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, Set.of("selected", "second"));
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(InventorySyncPublisherTest.entries(2,
				Map.of("selected", selected, "second", second)), InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, projection, f.context, work()));
			f.publisher.drain(); var fresh = List.copyOf(f.transport.sent); f.transport.sent.clear();
			var parts = fresh.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STREAM).toList();
			assertEquals(2, parts.size()); assertTrue(parts.stream().allMatch(p -> p.partCount() == 2));
			var first = parts.stream().filter(p -> p.entries().stream().anyMatch(e -> e.key().equals("second"))).findFirst().orElseThrow();
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(first));
			assertProvisionalLine(f.view(), Status.UPDATING, LONG_COUNT, false); assertEquals(1, f.view().entries().size());
			f.publisher.rebase(f.context, 2); f.context = new SyncPublisher.Context(f.context.consumerId(), f.context.recipient(), "100/1", 2, 1);
			f.publisher.status(f.context, Status.INVALID); var invalid = f.drain();
			invalid.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertProvisionalLine(f.view(), Status.INVALID, LONG_COUNT, false); assertEquals(1, f.view().entries().size());
			f.publisher.rebase(f.context, 3); f.context = new SyncPublisher.Context(f.context.consumerId(), f.context.recipient(), "100/1", 3, 1);
			f.publisher.status(f.context, Status.UPDATING); f.drain();
			assertProvisionalLine(f.view(), Status.INVALID, LONG_COUNT, false); assertEquals(1, f.view().entries().size());
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(InventorySyncPublisherTest.entries(3,
				Map.of("selected", selected, "second", second)), InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, projection, f.context, work()));
			f.publisher.advance(6, f.settings); f.publisher.drain(); var recovery = List.copyOf(f.transport.sent); f.transport.sent.clear();
			var data = recovery.stream().filter(p -> !p.entries().isEmpty()).toList();
			assertEquals(2, data.size()); assertTrue(data.stream().allMatch(p -> p.partCount() == 2));
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(data.getFirst()));
			assertProvisionalLine(f.view(), Status.INVALID, LONG_COUNT, false); assertEquals(1, f.view().entries().size());
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(data.getLast()));
			assertTrue(f.view().complete()); assertEquals(Status.READY, f.view().status());
			assertEquals(Map.of("selected", 19L, "second", 1L), f.view().entries().stream().collect(java.util.stream.Collectors.toMap(ClientInventory.EntryView::key, ClientInventory.EntryView::count)));
			fresh.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertTrue(f.view().complete()); assertEquals(19, f.view().entries().stream().filter(e -> e.key().equals("selected")).findFirst().orElseThrow().count());
			f.endpoint.client.markerRemoved(MARKER); assertTrue(f.view().entries().isEmpty());
		}
	}

	@Test void closedEmptyAndCompletedHistoryCannotAcquireDisjointUnclosedRecoveryEntries() {
		for (int initial : new int[] {-1, 0, 1, 2}) try (var f = new Fixture()) {
			var closed = new InventoryS2CPacket.Entry("selected", "minecraft:stone", "stone", null, LONG_COUNT, 1, false, Status.READY);
			if (initial == 2) {
				assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publishBootstrap(payload(0, false, null), 1,
					Status.UPDATING, SELECTED, f.context, work())); f.drain();
			} else if (initial >= 0) f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT,
				7, 0, MARKER, 1, 1, 1, 0, 1, true, Status.READY, 0, initial == 0 ? List.of() : List.of(closed)).stamp(100, 1)));
			var partial = new InventoryS2CPacket.Entry("unclosed", "minecraft:dirt", "dirt", null, 19, 2, false, Status.READY);
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.data(initial < 0 ? InventoryS2CPacket.Kind.SNAPSHOT : InventoryS2CPacket.Kind.STREAM,
				7, 0, MARKER, 1, 1, 2, 0, 2, true, Status.READY, 0, List.of(partial)).stamp(100, 1)));
			var invalid = InventoryS2CPacket.status(7, 0, MARKER, 1, 2, 2, Status.INVALID, 0).stamp(100, 1);
			for (int replay = 0; replay < 2; replay++) {
				f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(invalid));
				assertEquals(initial > 0 ? List.of("selected") : List.of(), f.view().entries().stream().map(ClientInventory.EntryView::key).toList());
				if (initial > 0) assertEquals(initial == 2 ? 0 : LONG_COUNT, f.view().entries().getFirst().count());
			}
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.status(7, 0, MARKER, 2, 3, 0, Status.UPDATING, 0).stamp(100, 1)));
			assertEquals(initial > 0 ? List.of("selected") : List.of(), f.view().entries().stream().map(ClientInventory.EntryView::key).toList());
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT,
				7, 0, MARKER, 2, 3, 3, 0, 1, true, Status.READY, 0, List.of(partial)).stamp(100, 1)));
			assertTrue(f.view().complete()); assertEquals(List.of("unclosed"), f.view().entries().stream().map(ClientInventory.EntryView::key).toList());
			assertEquals(19, f.view().entries().getFirst().count());
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(invalid));
			assertTrue(f.view().complete()); assertEquals(List.of("unclosed"), f.view().entries().stream().map(ClientInventory.EntryView::key).toList());
			f.endpoint.client.reset(); assertTrue(f.view().entries().isEmpty());
		}
	}

	@Test void incompleteRestagingWithdrawsAnAdmittedFailedSeedEvenWhenReplacementMemoryDefers() {
		try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, true);
			f.publisher.gateData(f.context.recipient()); f.drain(); // hand off just the initial fence
			f.publisher.releaseData(f.context.recipient()); f.transport.failNextSend = true; f.drain();
			assertTrue(f.publisher.pending(f.context)); assertTrue(f.view().entries().isEmpty());
			f.memory.setCap(f.memory.retained());
			assertInstanceOf(SyncPublisher.Outcome.Deferred.class, f.publisher.publishBootstrap(payload(LONG_COUNT, true, null), 2,
				Status.INCOMPLETE, SELECTED, f.context, work()));
			assertFalse(f.publisher.pending(f.context), "old immutable UPDATING work is withdrawn before replacement admission");
			f.memory.setCap(16_000_000); f.publisher.advance(3, f.settings);
			var failure = f.drain(); assertTrue(failure.stream().allMatch(p -> p.entries().isEmpty()));
			assertEquals(Status.INCOMPLETE, f.view().status());
			f.bootstrap(2, Status.INCOMPLETE, true); f.drain();
			assertEquals(Status.INCOMPLETE, f.view().status()); assertEquals(LONG_COUNT, f.view().entries().getFirst().count()); assertFalse(f.view().complete());
		}
	}

	@Test void lateIncompleteSeedControlsCannotDowngradeNewerCompletedStatusWhileGenericZeroWatermarkFailureStillWorks() {
		try (var f = new Fixture()) {
			f.bootstrap(1, Status.INCOMPLETE, true); var seed = f.drain();
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(complete(2, LONG_COUNT, true),
				InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, SELECTED, f.context, work())); f.drain();
			seed.forEach(p -> f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(p)));
			assertTrue(f.view().complete()); assertEquals(Status.READY, f.view().status());
			f.endpoint.client.accept(InventoryPublicationIntegrationTest.wire(InventoryS2CPacket.status(7, 0, MARKER, 1, 1, 0,
				Status.INCOMPLETE, 0).stamp(100, 1)));
			assertEquals(Status.INCOMPLETE, f.view().status()); assertTrue(f.view().grey());
		}
	}

	@Test void failureWithdrawsAnOldAdmittedSeedEvenAfterAFreshReplacementWasOffered() {
		try (var f = new Fixture()) {
			f.bootstrap(1, Status.UPDATING, false);
			f.publisher.gateData(f.context.recipient()); f.drain(); f.publisher.releaseData(f.context.recipient());
			f.transport.failNextSend = true; f.drain(); assertTrue(f.publisher.pending(f.context));
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(complete(2, LONG_COUNT, false),
				InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, SELECTED, f.context, work()));
			f.publisher.status(f.context, Status.INCOMPLETE); f.publisher.advance(3, f.settings);
			var failure = f.drain(); assertTrue(failure.stream().allMatch(p -> p.entries().isEmpty()));
			assertFalse(f.publisher.pending(f.context)); assertEquals(Status.INCOMPLETE, f.view().status()); assertFalse(f.view().complete());
		}
	}
}
