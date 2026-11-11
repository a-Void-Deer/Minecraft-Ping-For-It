package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.TransportHandoff;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inventory/shared-source contracts: cancellation isolates demand, period usage
 * is not refundable, memory refusal is retryable, and historical clients cannot
 * exhaust bounded live admission. All pressure and observations use public seams.
 */
@NotNullByDefault
class InventoryBackendLifecycleTest {

	private static final UUID OWNER = new UUID(71, 1), OTHER = new UUID(71, 2);
	private static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	private static final int PERIOD = 4;

	@Test void reentrantAdmissionTickCannotPublishTheClaimedPlaceholderAcrossAViewRebuild() {
		try (Fixture f = new Fixture(1)) {
			long epoch = f.hello(OWNER, 0);
			f.host.duringOpen = () -> { f.host.view = 2; f.backend.tick(0, f.settings); };
			f.open(OWNER, epoch, 1, 0);
			var outcomes = f.host.sent.stream().map(Delivery::packet).filter(packet -> packet.requestId() == 1
				&& (packet.kind() == InventoryS2CPacket.Kind.OPENED || packet.kind() == InventoryS2CPacket.Kind.OPEN_REJECTED)).toList();
			assertEquals(1, outcomes.size()); assertEquals(InventoryS2CPacket.Kind.OPENED, outcomes.getFirst().kind());
			assertEquals(2, outcomes.getFirst().view()); assertEquals(1, f.host.opens);
			f.tick(1); assertTrue(f.complete(OWNER, epoch, 1));
		}
	}

	@Test void admissionCallbackCancellationNeverResurrectsItsOutcomeOrLane() {
		for (boolean disconnect : List.of(false, true)) try (Fixture f = new Fixture(1)) {
			long epoch = f.hello(OWNER, 0);
			f.host.duringOpen = () -> {
				if (disconnect) f.backend.disconnect(OWNER);
				else f.closeRequest(OWNER, epoch, 1, 0);
			};
			int start = f.host.sent.size(); f.open(OWNER, epoch, 1, 0); f.tick(1);
			assertTrue(f.host.sent.subList(start, f.host.sent.size()).stream().noneMatch(delivery -> delivery.packet().requestId() == 1));
			assertEquals(0, f.state.resolutions); assertEquals(0, f.memory().reserved());
		}
	}

	@Test void openedHandoffCallbackDisconnectCannotAttachAnOrphanLane() throws Exception {
		try (Fixture f = new Fixture(1)) {
			long epoch = f.hello(OWNER, 0);
			f.host.duringOpenedHandoff = () -> f.backend.disconnect(OWNER);
			f.open(OWNER, epoch, 1, 0); f.tick(1);
			assertEquals(0, f.state.resolutions); assertEquals(0, f.memory().reserved());
			assertTrue(((java.util.Map<?, ?>) InventoryBackendTest.field(f.backend, "sessions")).isEmpty());
			Object publisher = java.util.Objects.requireNonNull(InventoryBackendTest.field(f.backend, "publisher"));
			assertTrue(((java.util.Map<?, ?>) InventoryBackendTest.field(publisher, "states")).isEmpty());
		}
	}

	@Test void activeAndPendingOnlyOutcomesShareTheSame64OwnerWindow() {
		try (Fixture f = new Fixture(1)) {
			long epoch = f.hello(OWNER, 0); f.open(OWNER, epoch, 1, 0);
			f.host.handoff = TransportHandoff.NOT_HANDED_OFF;
			for (int request = 2; request <= 64; request++) f.open(OWNER, epoch, request, 0);
			f.open(OWNER, epoch, 65, 0);
			f.closeRequest(OWNER, epoch, 5, 1);
			f.host.handoff = TransportHandoff.HANDED_OFF;
			f.open(OWNER, epoch, 65, 1); f.tick(1);
			var rejected = f.host.sent.stream().map(Delivery::packet).filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OPEN_REJECTED).toList();
			assertEquals(60, rejected.size());
			assertTrue(rejected.stream().noneMatch(packet -> packet.requestId() == 5));
			assertEquals(1, rejected.stream().filter(packet -> packet.requestId() == 65 && packet.failureReason() == InventoryFailureReason.TARGET_CAPACITY).count());
			assertEquals(4, f.host.opens, "mailbox refusal cannot advance the fence for the later retry of request 65");
		}
	}

	@Test void terminalLaneNoticeWaitsForReplacementOpenedAndKeepsItsProgressRevision() {
		try (Fixture f = new Fixture(1)) {
			f.host.bothLanes = true;
			long epoch = f.hello(OWNER, 0); f.open(OWNER, epoch, 1, 0);
			int start = f.host.sent.size(); f.host.view = 2;
			f.state.fluidFailure = InventoryFailureReason.UNSUPPORTED_LANE;
			f.host.handoff = TransportHandoff.NOT_HANDED_OFF; f.tick(1);
			assertEquals(start, f.host.sent.size());
			f.host.handoff = TransportHandoff.HANDED_OFF; f.tick(2); f.tick(3);
			var replacement = f.host.sent.subList(start, f.host.sent.size()).stream().map(Delivery::packet).toList();
			int opened = -1, unsupported = -1;
			for (int i = 0; i < replacement.size(); i++) {
				var packet = replacement.get(i);
				if (packet.kind() == InventoryS2CPacket.Kind.OPENED && packet.requestId() == 1) opened = i;
				if (packet.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE && packet.resourceKind() == InventoryResourceKind.FLUID
					&& packet.condition() == InventoryS2CPacket.PreviewCondition.UNSUPPORTED) {
					unsupported = i; assertEquals(0, packet.progressRevision()); assertEquals(2, packet.view());
					assertEquals(InventoryFailureReason.UNSUPPORTED_LANE, packet.failureReason());
				}
			}
			assertTrue(opened >= 0 && unsupported > opened, "the retained terminal notice follows its replacement grant");
		}
	}

	@Test void readableIncompletePrefixKeepsDeliveryAliveBeforeTheFirstRowHandoff() {
		try (Fixture f = new Fixture(2)) {
			f.state.unique = true;
			f.settings.getPreview().setMaxVariantsPerClientPeriod(IntLimit.finite(1));
			long epoch = f.hello(OWNER, 0); f.open(OWNER, epoch, 1, 0);
			int start = f.host.sent.size(); f.tick(0);
			var sequence = f.host.sent.subList(start, f.host.sent.size()).stream().map(Delivery::packet).toList();
			assertTrue(sequence.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW
				&& !packet.entries().isEmpty() && packet.status() == InventoryS2CPacket.Status.INCOMPLETE && !packet.completeScan()));
			assertTrue(sequence.stream().noneMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE
				&& packet.condition() == InventoryS2CPacket.PreviewCondition.FAILED), "a usable prefix cannot tell a row-less client to abandon its data barrier");
			assertTrue(sequence.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE
				&& packet.phase() == InventoryS2CPacket.PreviewPhase.DELIVERY && packet.failureReason() == InventoryFailureReason.VARIANT_LIMIT));
			assertEquals(2, f.state.reads);
		}
	}

	@Test void pendingPageRetriesKeepTheSamePaidOwnerAndNeverReadOrCountItTwice() throws Exception {
		try (Fixture f = new Fixture(2)) {
			long epoch = f.hello(OWNER, 0); f.open(OWNER, epoch, 1, 0);
			var sessions = (java.util.Map<?, ?>) InventoryBackendTest.field(f.backend, "sessions");
			Object session = java.util.Objects.requireNonNull(sessions.get(OWNER));
			var previews = (java.util.Map<?, ?>) InventoryBackendTest.field(session, "previews");
			Object preview = previews.values().iterator().next();
			var consumer = (InventoryRuntime.Consumer) InventoryBackendTest.field(preview, "consumer");
			var page = f.backend.runtime().step(consumer, 1).orElseThrow();
			assertEquals(1, f.state.reads);
			var pageOwner = f.memory().tryReserve(65536).orElseThrow(); pageOwner.commit(65536);
			var pageField = preview.getClass().getDeclaredField("pendingObservation"); pageField.setAccessible(true); pageField.set(preview, page);
			var ownerField = preview.getClass().getDeclaredField("pendingMemory"); ownerField.setAccessible(true); ownerField.set(preview, pageOwner);
			long remaining = f.memory().remaining();
			try (var pressure = f.memory().tryReserve(remaining).orElseThrow()) {
				pressure.commit(remaining); f.tick(1); f.tick(2);
				assertTrue(pageOwner == ownerField.get(preview)); assertTrue(page == pageField.get(preview));
				assertEquals(1, f.state.reads);
			}
			f.tick(PERIOD); f.tick(2L * PERIOD);
			assertTrue(f.complete(OWNER, epoch, 1)); assertEquals(2, f.state.reads);
			assertEquals(3, f.latestEntry(OWNER, epoch, 1).count());
			assertTrue(ownerField.get(preview) == null && pageField.get(preview) == null);
		}
	}

	@Test void optionalFluidUnsupportedRequiresTheExactTypedReason() {
		for (InventoryFailureReason cause : List.of(InventoryFailureReason.UNSUPPORTED_LANE, InventoryFailureReason.SOURCE_UNAVAILABLE,
			InventoryFailureReason.PROVIDER_FAILURE)) try (Fixture f = new Fixture(1)) {
			f.host.bothLanes = true; f.state.fluidFailure = cause;
			long epoch = f.hello(OWNER, 0); f.open(OWNER, epoch, 1, 0); f.tick(0);
			var states = f.host.sent.stream().map(Delivery::packet).filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE
				&& packet.resourceKind() == InventoryResourceKind.FLUID && packet.failureReason() == cause).toList();
			assertFalse(states.isEmpty());
			assertTrue(states.stream().allMatch(packet -> packet.condition() == (cause == InventoryFailureReason.UNSUPPORTED_LANE
				? InventoryS2CPacket.PreviewCondition.UNSUPPORTED : InventoryS2CPacket.PreviewCondition.FAILED)));
			assertTrue(f.complete(OWNER, epoch, 1), "a failed optional lane cannot erase ITEM completion");
		}
	}

	@Test void pendingPageDisplayTighteningReplacesItsPaidVersionWithoutAnotherGetter() throws Exception {
		try (Fixture f = new Fixture(1)) {
			long epoch = f.hello(OWNER, 0); f.open(OWNER, epoch, 1, 0);
			var sessions = (java.util.Map<?, ?>) InventoryBackendTest.field(f.backend, "sessions");
			Object session = java.util.Objects.requireNonNull(sessions.get(OWNER));
			var previews = (java.util.Map<?, ?>) InventoryBackendTest.field(session, "previews");
			Object preview = previews.values().iterator().next();
			var consumer = (InventoryRuntime.Consumer) InventoryBackendTest.field(preview, "consumer");
			var observed = f.backend.runtime().step(consumer, 1).orElseThrow();
			InventoryResourceValue value = java.util.Objects.requireNonNull(observed.slots().getFirst());
			var large = InventoryResourceCodec.value(value.key(), value.amount(), value.label(), "{\"label\":\"" + "x".repeat(1200) + "\"}", false);
			var page = new InventoryRuntime.Observation(observed.result(), List.of(large), observed.inventorySweepEvidence());
			var owner = f.memory().tryReserve(65536).orElseThrow(); owner.commit(65536);
			InventoryBackendTest.field(preview, "pendingObservation", page); InventoryBackendTest.field(preview, "pendingMemory", owner);
			f.settings.setDisplayByteMultiplier(new java.math.BigDecimal("0.25"));
			for (int tick = 1; tick <= 12 && !f.complete(OWNER, epoch, 1); tick++) f.tick(tick);
			assertTrue(f.complete(OWNER, epoch, 1)); assertEquals(1, f.state.reads);
			assertTrue(f.latestEntry(OWNER, epoch, 1).displayJson() == null, "an unavailable projection port uses the bounded default display");
			assertTrue(InventoryBackendTest.field(preview, "pendingObservation") == null);
			assertTrue(InventoryBackendTest.field(preview, "pendingMemory") == null);
		}
	}

	@Test void openRejectionUsesPrepaidMailboxUnderMemoryPressureAndDoesNotOccupyATarget() {
		try (Fixture f = new Fixture(1)) {
			long epoch = f.hello(OWNER, 0);
			long remaining = f.memory().remaining();
			try (var pressure = f.memory().tryReserve(remaining).orElseThrow()) {
				pressure.commit(remaining);
				f.open(OWNER, epoch, 1, 0);
				InventoryS2CPacket rejected = f.host.sent.getLast().packet();
				assertEquals(InventoryS2CPacket.Kind.OPEN_REJECTED, rejected.kind());
				assertEquals(InventoryFailureReason.MEMORY_LIMIT, rejected.failureReason());
				assertEquals(0, f.host.opens); assertEquals(0, f.state.resolutions);
			}
			f.open(OWNER, epoch, 2, 1); f.tick(1);
			assertTrue(f.complete(OWNER, epoch, 2));
			assertEquals(1, f.host.opens, "a rejection never occupied active target capacity");
		}
	}

	@Test void notHandedOffOutcomeIsRestampedWithoutRepeatingAdmissionAndExactOldViewCloseCancelsMailboxOnly() {
		try (Fixture f = new Fixture(1)) {
			long epoch = f.hello(OWNER, 0);
			f.host.openFailure = InventoryFailureReason.OUT_OF_RANGE; f.host.handoff = TransportHandoff.NOT_HANDED_OFF;
			f.open(OWNER, epoch, 1, 0); assertEquals(1, f.host.opens);
			assertTrue(f.host.sent.stream().noneMatch(d -> d.packet().kind() == InventoryS2CPacket.Kind.OPEN_REJECTED));
			f.host.view = 2; f.tick(1); assertEquals(1, f.host.opens);
			f.backend.handle(OWNER, InventoryC2SPacket.close(epoch, 1).stamp(100, 0), 1, f.settings);
			f.host.handoff = TransportHandoff.HANDED_OFF; f.tick(2);
			var rejected = f.host.sent.stream().map(Delivery::packet).filter(p -> p.kind() == InventoryS2CPacket.Kind.OPEN_REJECTED).toList();
			assertEquals(1, rejected.size()); assertEquals(2, rejected.getFirst().view());
			assertEquals(InventoryFailureReason.OUT_OF_RANGE, rejected.getFirst().failureReason());
			f.host.handoff = TransportHandoff.NOT_HANDED_OFF;
			f.backend.handle(OWNER, InventoryC2SPacket.open(epoch, 100, 2, 2, TARGET, BlockFace.NORTH), 2, f.settings);
			f.host.view = 3; f.tick(3);
			f.backend.handle(OWNER, InventoryC2SPacket.close(epoch, 2).stamp(100, 2), 3, f.settings);
			f.host.handoff = TransportHandoff.HANDED_OFF; f.tick(4);
			assertTrue(f.host.sent.stream().noneMatch(d -> d.packet().kind() == InventoryS2CPacket.Kind.OPEN_REJECTED && d.packet().requestId() == 2));
			assertEquals(2, f.host.opens);
		}
	}

	@Test void repeatedDeferredControlsNeverAdvanceTheRealProgressRevision() {
		try (Fixture f = new Fixture(2)) {
			long epoch = f.hello(OWNER, 0); f.open(OWNER, epoch, 1, 0);
			long occupied = f.memory().remaining();
			try (var pressure = f.memory().tryReserve(occupied).orElseThrow()) {
				pressure.commit(occupied); f.tick(0); f.tick(80); f.tick(160);
				var notices = f.host.sent.stream().map(Delivery::packet).filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE
					&& p.condition() == InventoryS2CPacket.PreviewCondition.DEFERRED).toList();
				assertTrue(notices.size() >= 2);
				assertTrue(notices.stream().allMatch(p -> p.progressRevision() == 0 && p.failureReason() == InventoryFailureReason.MEMORY_LIMIT));
				assertEquals(0, f.state.reads);
			}
			f.tick(161);
			assertTrue(f.host.sent.stream().map(Delivery::packet).anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE && p.progressRevision() > 0));
		}
	}

	@Test void controlFullRetainsOpenOutcomeUntilQueueSpaceReturnsWithoutRepeatingAuthority() throws Exception {
		try (Fixture f = new Fixture(1)) {
			long epoch = f.hello(OWNER, 0); f.host.openFailure = InventoryFailureReason.OUT_OF_RANGE;
			f.host.handoff = TransportHandoff.NOT_HANDED_OFF;
			var field = InventoryBackend.class.getDeclaredField("publisher"); field.setAccessible(true);
			var publisher = (InventorySyncPublisher) field.get(f.backend);
			for (int i = 1; i <= 64; i++) assertTrue(publisher.controlPrepaid(OWNER,
				InventoryS2CPacket.selected(epoch, 1, i, new MarkerId(i), null).stamp(100, 1)));
			f.open(OWNER, epoch, 1, 0);
			assertEquals(1, f.host.opens);
			publisher.cancelControls(OWNER); f.host.handoff = TransportHandoff.HANDED_OFF; f.tick(1);
			var outcomes = f.host.sent.stream().map(Delivery::packet).filter(p -> p.kind() == InventoryS2CPacket.Kind.OPEN_REJECTED).toList();
			assertEquals(1, outcomes.size()); assertEquals(InventoryFailureReason.OUT_OF_RANGE, outcomes.getFirst().failureReason());
			assertEquals(1, f.host.opens, "mailbox retry never reruns OPEN admission");
		}
	}
	@Test void a1024CategoryPreviewCompletesItsRealCumulativeCutAndBackingSurvivesInvalidClear() throws Exception {
		try (Fixture f = new Fixture(1024)) {
			f.state.unique = true;
			f.settings.getPreview().setMaxVariantsPerClientPeriod(IntLimit.finite(1024));
			f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1024));
			f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(1024));
			long epoch = f.hello(OWNER, 0);
			var requests = new ArrayList<InventoryC2SPacket>();
			ClientInventory client = new ClientInventory(packet -> requests.add((InventoryC2SPacket) packet));
			client.presentationReset(100, 1);
			f.host.sent.stream().map(Delivery::packet).map(InventoryPublicationIntegrationTest::wire).forEach(client::accept);
			assertEquals(Optional.of(true), client.authorization(100, 1, "entity_block"));
			long request = client.open(TARGET, BlockFace.NORTH, "entity_block"); assertEquals(1, request);
			assertEquals(1, requests.size()); assertEquals(InventoryC2SPacket.Kind.OPEN, requests.getFirst().kind());
			assertEquals(epoch, requests.getFirst().epoch());
			int openedStart = f.host.sent.size();
			f.backend.handle(OWNER, requests.getFirst(), 0, f.settings);
			f.host.sent.subList(openedStart, f.host.sent.size()).stream().map(Delivery::packet)
				.map(InventoryPublicationIntegrationTest::wire).forEach(client::accept);
			for (int tick = 0; tick < 600 && !client.preview(request).completeScan(); tick++) {
				int before = f.host.sent.size(); f.tick(tick);
				f.host.sent.subList(before, f.host.sent.size()).stream().map(Delivery::packet)
					.map(InventoryPublicationIntegrationTest::wire).forEach(client::accept);
			}
			assertTrue(client.preview(request).completeScan(), "the ClientInventory multipart completion barrier must close");
			assertEquals(1024, f.state.reads);
			assertEquals(1024, client.preview(request).entries().stream().map(ClientInventory.EntryView::key).distinct().count());
			assertEquals(1024L * 1025 / 2, client.preview(request).entries().stream().mapToLong(ClientInventory.EntryView::count).sum(),
				"all 1024 distinct fixture amounts reach the client unchanged");
			var sessionsField = InventoryBackend.class.getDeclaredField("sessions"); sessionsField.setAccessible(true);
			Object session = java.util.Objects.requireNonNull(((java.util.Map<?, ?>) sessionsField.get(f.backend)).get(OWNER));
			var previewsField = session.getClass().getDeclaredField("previews"); previewsField.setAccessible(true);
			Object preview = ((java.util.Map<?, ?>) previewsField.get(session)).values().iterator().next();
			var capacityField = preview.getClass().getDeclaredField("categoryCapacity"); capacityField.setAccessible(true);
			assertEquals(1024, capacityField.getInt(preview));
			var ticketField = preview.getClass().getDeclaredField("categoryMemory"); ticketField.setAccessible(true);
			Object ticket = java.util.Objects.requireNonNull(ticketField.get(preview));
			f.state.valid = false; f.tick(604);
			assertEquals(1024, capacityField.getInt(preview));
			assertTrue(ticket == ticketField.get(preview), "clear keeps the admitted backing owner, including below-cap retirement headroom");
		}
	}

	@Test
	void duplicateOpenDoesNotRestartPaidProgressAndLastCloseStopsAllSourceWork() {
		try (Fixture f = new Fixture(3)) {
			f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
			f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(1));
			long epoch = f.hello(OWNER, 0);
			f.open(OWNER, epoch, 1, 0);
			f.open(OWNER, epoch, 2, 0);
			f.tick(0);
			assertEquals(1, f.state.reads, "duplicate requests share this client's one paid slot");
			assertEquals(1, f.latestEntry(OWNER, epoch, 1).count());
			assertEquals(1, f.latestEntry(OWNER, epoch, 2).count());
			assertFalse(f.complete(OWNER, epoch, 2));

			long retained = f.memory().retained();
			f.open(OWNER, epoch, 1, 1);
			f.tick(1);
			assertEquals(2, f.host.opens, "replayed OPEN cannot repeat target admission");
			assertEquals(1, f.state.reads);
			assertEquals(retained, f.memory().retained(), "replayed OPEN owns no second request or progress allocation");

			f.closeRequest(OWNER, epoch, 1, 1);
			long afterClose = f.memory().retained();
			int releases = f.state.releases;
			f.closeRequest(OWNER, epoch, 1, 1);
			assertEquals(afterClose, f.memory().retained(), "duplicate CLOSE cannot release a peer's ownership");
			assertEquals(releases, f.state.releases);
			int afterCancellation = f.host.sent.size();
			f.tick(PERIOD);
			assertEquals(2, f.state.reads, "the remaining request continues, not restarts, the sweep");
			f.tick(2L * PERIOD);
			assertTrue(f.complete(OWNER, epoch, 2));
			assertEquals(6, f.latestEntry(OWNER, epoch, 2).count(), "the three fixture slots contain 1 + 2 + 3");
			assertEquals(3, f.state.reads);
			assertTrue(f.host.sent.subList(afterCancellation, f.host.sent.size()).stream()
				.noneMatch(delivery -> delivery.packet().kind() == InventoryS2CPacket.Kind.PREVIEW
					&& delivery.packet().requestId() == 1), "cancelled preview data cannot escape later drains");

			f.closeRequest(OWNER, epoch, 2, 2L * PERIOD);
			long afterLastClose = f.memory().retained();
			int afterLastRelease = f.state.releases;
			f.closeRequest(OWNER, epoch, 2, 2L * PERIOD);
			assertEquals(afterLastClose, f.memory().retained());
			assertEquals(afterLastRelease, f.state.releases);
			assertEquals(0, f.state.liveSources);
			int resolutions = f.state.resolutions, validations = f.state.validations;
			f.tick(2L * PERIOD + 1);
			f.tick(3L * PERIOD);
			assertEquals(resolutions, f.state.resolutions, "last CLOSE stops recovery and source resolution too");
			assertEquals(validations, f.state.validations);
			assertEquals(3, f.state.reads);
			f.backend.disconnect(OWNER);
			f.tick(4L * PERIOD);
			assertEquals(0, f.memory().retained(), "period-owned quota tickets may outlive CLOSE, but not their period");
		}
	}

	@Test
	void firstSamplingConstructsOneSourceAndLaterTicksReuseTheRound() {
		try (Fixture f = new Fixture(2)) {
			long epoch = f.hello(OWNER, 0);
			f.open(OWNER, epoch, 1, 0);
			f.tick(0);
			assertEquals(1, f.state.resolutions, "one probe source is transferred into the first round");
			assertEquals(1, f.state.liveSources);
			assertEquals(2, f.state.reads);
			f.tick(PERIOD);
			assertEquals(1, f.state.resolutions, "existing round evidence never resolves another source");
			assertEquals(2, f.state.reads, "a completed preview has no periodic recapture");
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void closeAndReopenCannotRefundClientOrGlobalPeriodSlots(boolean globalLimit) {
		try (Fixture f = new Fixture(2)) {
			f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(globalLimit ? 8 : 1));
			f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(globalLimit ? 1 : 8));
			long epoch = f.hello(OWNER, 0);
			f.open(OWNER, epoch, 1, 0);
			f.tick(0);
			assertEquals(1, f.state.reads);
			f.closeRequest(OWNER, epoch, 1, 1);
			f.open(OWNER, epoch, 2, 1);
			long otherEpoch = 0;
			if (globalLimit) {
				otherEpoch = f.hello(OTHER, 1);
				f.open(OTHER, otherEpoch, 1, 1);
			}
			f.tick(1);
			assertEquals(1, f.state.reads, "closing a request cannot buy another slot in the same period");
			assertTrue(f.previews(OWNER, epoch, 2).stream().allMatch(packet -> packet.entries().isEmpty() && !packet.completeScan()));
			if (globalLimit) assertTrue(f.previews(OTHER, otherEpoch, 1).stream()
				.allMatch(packet -> packet.entries().isEmpty() && !packet.completeScan()), "another UUID cannot spend the departed request's global usage again");

			f.tick(PERIOD);
			assertEquals(2, f.state.reads, "the next period admits exactly one new slot, not a refund plus a new allowance");
			long admitted = f.previews(OWNER, epoch, 2).stream().filter(packet -> !packet.entries().isEmpty()).count();
			if (globalLimit) admitted += f.previews(OTHER, otherEpoch, 1).stream().filter(packet -> !packet.entries().isEmpty()).count();
			assertEquals(1, admitted);
		}
	}

	@Test
	void reconnectGetsANewEpochWithoutRefundingGlobalUsageOrAcceptingOldSessionPackets() {
		try (Fixture f = new Fixture(2)) {
			f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(8));
			f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(1));
			long oldEpoch = f.hello(OWNER, 0);
			f.open(OWNER, oldEpoch, 1, 0);
			f.tick(0);
			assertEquals(1, f.state.reads);
			f.backend.disconnect(OWNER);
			long disconnected = f.memory().retained();
			int releases = f.state.releases;
			f.backend.disconnect(OWNER);
			assertEquals(disconnected, f.memory().retained());
			assertEquals(releases, f.state.releases);
			assertEquals(0, f.state.liveSources);
			int afterDisconnect = f.host.sent.size();

			long epoch = f.hello(OWNER, 1);
			assertNotEquals(oldEpoch, epoch);
			f.open(OWNER, oldEpoch, 99, 1);
			assertEquals(1, f.host.opens, "an old epoch cannot bind a source or advance the new session's request fence");
			f.open(OWNER, epoch, 1, 1);
			f.closeRequest(OWNER, oldEpoch, 1, 1);
			f.tick(1);
			assertEquals(1, f.state.reads, "a reconnect is not new global period credit");
			assertFalse(f.complete(OWNER, epoch, 1));
			f.tick(PERIOD);
			assertEquals(2, f.state.reads);
			assertEquals(1, f.latestEntry(OWNER, epoch, 1).count(), "old CLOSE must not cancel the new request with the same ID");
			assertTrue(f.host.sent.subList(afterDisconnect, f.host.sent.size()).stream()
				.allMatch(delivery -> delivery.packet().epoch() == epoch), "no old preview or queued control crosses reconnect");
		}
	}

	@Test
	void memoryDeferKeepsTheOpenRequestAndRetriesWithoutEmptyCompletionOrSourceWork() {
		try (Fixture f = new Fixture(2)) {
			f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
			long epoch = f.hello(OWNER, 0);
			f.open(OWNER, epoch, 1, 0);
			long occupied = f.memory().remaining();
			try (RetainedMemoryLedger.Ticket pressure = f.memory().tryReserve(occupied).orElseThrow()) {
				pressure.commit(occupied);
				f.tick(0);
				f.tick(1);
				assertEquals(0, f.state.resolutions, "retained pressure defers before resolver or validation callbacks");
				assertEquals(0, f.state.validations);
				assertEquals(0, f.state.reads);
				assertTrue(f.previews(OWNER, epoch, 1).isEmpty(), "admission refusal is not an empty or terminal source result");
				assertEquals(0, f.memory().reserved());
			}
			f.tick(2);
			assertEquals(1, f.state.reads);
			assertFalse(f.complete(OWNER, epoch, 1));
			assertEquals(1, f.latestEntry(OWNER, epoch, 1).count());
			f.tick(PERIOD);
			assertTrue(f.complete(OWNER, epoch, 1));
			assertEquals(3, f.latestEntry(OWNER, epoch, 1).count());
			assertEquals(2, f.state.reads);
			assertEquals(1, f.host.opens, "the admitted request resumes without another OPEN or target binding");
		}
	}

	@Test
	void historicalUuidChurnReturnsToAnchoredCostAfterEachPaidPeriod() {
		try (Fixture f = new Fixture(1)) {
			f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
			long anchorEpoch = f.hello(OWNER, 0);
			f.open(OWNER, anchorEpoch, 1, 0);
			f.tick(0);
			assertTrue(f.complete(OWNER, anchorEpoch, 1));
			f.tick(PERIOD);
			long anchored = f.memory().retained();
			// Hundreds of historical identities, but only the anchor and one guest live at once.
			for (int index = 0; index < 300; index++) {
				UUID guest = new UUID(72, index);
				long now = (index + 1L) * PERIOD;
				long epoch = f.hello(guest, now);
				f.open(guest, epoch, 1, now);
				f.tick(now);
				assertTrue(f.complete(guest, epoch, 1), "historical UUIDs cannot consume live session or logical-subject capacity");
				assertEquals(1, f.latestEntry(guest, epoch, 1).count());
				f.backend.disconnect(guest);
				long afterDisconnect = f.memory().retained();
				int releases = f.state.releases;
				f.backend.disconnect(guest);
				assertEquals(afterDisconnect, f.memory().retained(), "duplicate disconnect cannot double-release retained tickets");
				assertEquals(releases, f.state.releases);
				assertEquals(1, f.state.liveSources, "the guest cannot release the anchor's source");
				f.tick(now + PERIOD);
				assertEquals(anchored, f.memory().retained(), "departed UUID accounting retires with its paid period, not with all peers");
				assertEquals(0, f.memory().reserved());
				f.host.sent.clear();
			}
			assertEquals(301, f.state.reads, "the anchor has no periodic preview capture; each guest pays its own owner-scoped sweep");
			f.backend.disconnect(OWNER);
			int resolutions = f.state.resolutions, validations = f.state.validations;
			f.tick(302L * PERIOD);
			assertEquals(0, f.memory().retained());
			assertEquals(0, f.state.liveSources);
			assertEquals(resolutions, f.state.resolutions);
			assertEquals(validations, f.state.validations);
		}
	}

	@NotNullByDefault
	private record Delivery(UUID recipient, InventoryS2CPacket packet) {}

	@NotNullByDefault
	private static final class Host implements InventoryBackend.Host {
		private final List<Delivery> sent = new ArrayList<>();
		private final InventoryBackend.Policy policy = new InventoryBackend.Policy(100, 1, Set.of("entity_block"));
		private int opens;
		private long view = 1;
		private boolean bothLanes;
		private @Nullable InventoryFailureReason openFailure;
		private TransportHandoff handoff = TransportHandoff.HANDED_OFF;
		private Runnable duringOpen = () -> {};
		private Runnable duringOpenedHandoff = () -> {};

		@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.of(policy); }
		@Override public Optional<InventoryBackend.SessionAccess> session(UUID player) {
			return Optional.of(new InventoryBackend.SessionAccess(policy.epoch(), view, InventoryTestGrants.both()));
		}
		@Override public Optional<nx.pingwheel.common.marker.ServerMarker> activeMarker(MarkerId marker) { return Optional.empty(); }
		@Override public java.util.Set<InventoryResourceKind> freezeInventoryPreviewLanes(UUID owner, String targetTypeId) {
			return bothLanes ? InventoryTestGrants.both() : Set.of(InventoryResourceKind.ITEM);
		}
		@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) {
			opens++;
			return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention"));
		}
		@Override public InventoryDecision<InventoryBackend.Opened> openDecision(UUID player, Target requested) {
			duringOpen.run();
			if (openFailure == null) return InventoryDecision.accept(open(player, requested).orElseThrow());
			opens++;
			return InventoryDecision.reject(openFailure, InventoryImpact.PING_UNAVAILABLE);
		}
		@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) { return false; }
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			throw new AssertionError("preview lifecycle must not create a marker");
		}
		@Override public boolean knows(UUID player, MarkerId marker) { return false; }
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public int encodedBytes(InventoryS2CPacket packet) {
			return packet.encodedSize(InventoryS2CPacket.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES);
		}
		@Override public void send(UUID recipient, InventoryS2CPacket packet) {
			assertFalse(packet.isCorrupt());
			sent.add(new Delivery(recipient, packet));
		}
		@Override public TransportHandoff sendObserved(UUID recipient, InventoryS2CPacket packet) {
			if (packet.kind() == InventoryS2CPacket.Kind.OPENED) duringOpenedHandoff.run();
			if (handoff == TransportHandoff.HANDED_OFF) send(recipient, packet);
			return handoff;
		}
	}

	@NotNullByDefault
	private static final class State {
		private int resolutions, validations, reads, releases, liveSources;
		private boolean unique, valid = true;
		private @Nullable InventoryFailureReason fluidFailure;
	}

	@NotNullByDefault
	private static final class Source implements InventorySourceAccess.Source {
		private final InventorySourceInput input;
		private final State state;
		private final int slots, displayBytes;
		private boolean closed;

		private Source(InventorySourceInput input, State state, int slots, int displayBytes) {
			this.input = input; this.state = state; this.slots = slots; this.displayBytes = displayBytes;
			state.resolutions++; state.liveSources++;
		}
		@Override public SourceKey key() { return new SourceKey("test", "inventory", "lifecycle", input.viewKey()); }
		@Override public boolean valid() {
			assertFalse(closed, "released source evidence cannot be probed");
			state.validations++;
			return state.valid;
		}
		@Override public boolean stableCursor() { return true; }
		@Override public InventoryDecision<Boolean> validationDecision(
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Allowance allowance) {
			if (input.resourceKind() != InventoryResourceKind.FLUID || state.fluidFailure == null)
				return valid() ? InventoryDecision.accept(true) : InventoryDecision.reject(InventoryFailureReason.SOURCE_UNAVAILABLE, InventoryImpact.DEGRADED);
			return state.fluidFailure == InventoryFailureReason.UNSUPPORTED_LANE
				? InventoryDecision.unsupported(state.fluidFailure, InventoryImpact.DETAIL_ONLY)
				: InventoryDecision.reject(state.fluidFailure, InventoryImpact.DEGRADED);
		}
		@Override public int slots() { return slots; }
		@Override public int displayBytes() { return displayBytes; }
		@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
		@Override public InventoryDomainCodec.Item read(int slot) {
			assertFalse(closed, "released source evidence cannot be read");
			assertTrue(slot >= 0 && slot < slots);
			state.reads++;
			return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", state.unique ? "variant_" + slot : "plain"), slot + 1L, "stone", null, false);
		}
		@Override public void close() {
			assertFalse(closed, "every resolved source has exactly one owner and one release");
			closed = true; state.releases++; state.liveSources--;
		}
	}

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		private final Host host = new Host();
		private final State state = new State();
		private final InventorySettings settings = InventorySettings.serverDefaults();
		private final InventoryBackend backend;

		private Fixture(int slots) {
			settings.setPendingMemoryMiB(64);
			settings.setPhysicalSlotsPerTick(IntLimit.finite(8));
			settings.getPreview().setPeriodTicks(PERIOD);
			settings.getTracking().setPeriodTicks(PERIOD);
			settings.getPreview().setMaxTargetsPerClient(IntLimit.finite(4));
			settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(8));
			settings.getPreview().setMaxSlotsServer(IntLimit.finite(16));
			settings.getPreview().setMaxVariantsPerClientPeriod(IntLimit.finite(8));
			settings.getPreview().getClientByteMultiplier().setUnlimited(true);
			settings.getPreview().getGlobalByteMultiplier().setUnlimited(true);
			backend = new InventoryBackend(host, new InventoryRuntime((input, displayBytes) ->
				Optional.of(new Source(input, state, slots, displayBytes)), settings.pendingMemoryBytes()), 1);
		}
		private RetainedMemoryLedger memory() { return backend.runtime().memory(); }
		private void tick(long now) {
			backend.tick(now, settings);
			assertEquals(0, memory().reserved(), "no admission workspace survives a backend tick");
		}
		private long hello(UUID player, long now) {
			int start = host.sent.size();
			backend.handle(player, InventoryC2SPacket.hello(), now, settings);
			return host.sent.subList(start, host.sent.size()).stream()
				.filter(delivery -> delivery.recipient().equals(player) && delivery.packet().kind() == InventoryS2CPacket.Kind.OFFER)
				.mapToLong(delivery -> delivery.packet().epoch()).findFirst().orElseThrow();
		}
		private void open(UUID player, long epoch, long request, long now) {
			backend.handle(player, InventoryC2SPacket.open(epoch, 100, 1, request, TARGET, BlockFace.NORTH), now, settings);
		}
		private void closeRequest(UUID player, long epoch, long request, long now) {
			backend.handle(player, InventoryC2SPacket.close(epoch, request).stamp(100, 1), now, settings);
		}
		private @Unmodifiable List<InventoryS2CPacket> previews(UUID player, long epoch, long request) {
			return host.sent.stream().filter(delivery -> delivery.recipient().equals(player))
				.map(Delivery::packet).filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW
					&& packet.epoch() == epoch && packet.requestId() == request).toList();
		}
		private boolean complete(UUID player, long epoch, long request) {
			return previews(player, epoch, request).stream().anyMatch(InventoryS2CPacket::completeScan);
		}
		private InventoryS2CPacket.Entry latestEntry(UUID player, long epoch, long request) {
			return previews(player, epoch, request).stream().filter(packet -> !packet.entries().isEmpty())
				.reduce((previous, next) -> next).orElseThrow().entries().getFirst();
		}
		@Override public void close() {
			backend.close();
			assertEquals(0, state.liveSources);
			assertEquals(state.resolutions, state.releases);
			assertEquals(0, memory().reserved());
			assertEquals(0, memory().retained());
		}
	}
}
