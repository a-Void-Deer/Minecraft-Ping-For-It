package nx.pingwheel.common.presentation.inventory.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.marker.MarkerRejectReason;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryPreviewLifecycleTest {
	@NotNullByDefault
	private static final class Clock implements InteractionTimeSource {
		long millis;
		@Override public long nowMillis() { return millis; }
	}
	@NotNullByDefault
	private static final class Fixture {
		final Clock clock = new Clock();
		final List<IPacket> sent = new ArrayList<>();
		final ClientInventory client = new ClientInventory(sent::add, clock);
		Fixture() { ClientInventoryV2Test.ready(client); }
		InventoryPreviewLifecycle open() {
			return Objects.requireNonNull(client.openPreview(ClientInventoryV2Test.TARGET, BlockFace.NORTH, "entity_block"));
		}
		void reject(InventoryPreviewLifecycle preview, InventoryFailureReason reason) {
			client.accept(InventoryS2CPacket.openRejected(7, 100, 1, preview.requestId(), reason));
		}
		void advance(long millis) { clock.millis = millis; client.tick(true); }
		@Unmodifiable List<InventoryC2SPacket> packets(InventoryC2SPacket.Kind kind) {
			return sent.stream().filter(InventoryC2SPacket.class::isInstance).map(InventoryC2SPacket.class::cast)
				.filter(packet -> packet.kind() == kind).toList();
		}
		void control(long wire, long revision, long progress, InventoryS2CPacket.PreviewCondition condition,
			@Nullable InventoryFailureReason reason) {
			client.accept(InventoryS2CPacket.previewState(InventoryResourceKind.ITEM, 7, 100, 1, wire,
				revision, progress, InventoryS2CPacket.PreviewPhase.SCAN, condition, reason));
		}
		void data(long wire, InventoryResourceKind kind, long watermark, int part, int parts, boolean complete,
			InventoryS2CPacket.Status status, List<InventoryS2CPacket.Entry> entries, @Nullable InventoryFailureReason reason) {
			client.accept(InventoryS2CPacket.data(kind, InventoryS2CPacket.Kind.PREVIEW, 7, wire, null, 1, 1,
				watermark, part, parts, complete, status, 0, entries, reason).stamp(100, 1));
		}
	}
	@Test void retryUsesRealFiveSecondsStableHandleNewWireAndNoCatchupBurst() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle preview = f.open();
		long local = preview.localId(), first = preview.requestId();
		f.reject(preview, InventoryFailureReason.REQUEST_CAPACITY);
		assertEquals(0, f.client.stats().previewChannels());
		assertEquals(first, f.packets(InventoryC2SPacket.Kind.CLOSE).getFirst().requestId());
		assertTrue(preview.retrying()); assertEquals(1, preview.retriesUsed());
		f.advance(4_999); assertEquals(1, f.packets(InventoryC2SPacket.Kind.OPEN).size());
		f.advance(5_000); assertEquals(2, f.packets(InventoryC2SPacket.Kind.OPEN).size());
		assertEquals(local, preview.localId()); assertTrue(preview.requestId() > first);
		assertEquals(1, preview.retriesUsed(), "second attempt still displays 1/3");
		assertFalse(f.client.hasPreview(first));
		f.reject(preview, InventoryFailureReason.MEMORY_LIMIT);
		f.advance(100_000);
		assertEquals(3, preview.attempts(), "late tick starts one attempt only");
		assertEquals(2, preview.retriesUsed());
		f.reject(preview, InventoryFailureReason.PROVIDER_WORK_LIMIT);
		f.advance(105_000); assertEquals(4, preview.attempts()); assertEquals(3, preview.retriesUsed());
		f.reject(preview, InventoryFailureReason.SOURCE_UNAVAILABLE);
		assertTrue(preview.stopped()); assertFalse(preview.retrying());
		assertEquals(InventoryFailureReason.SOURCE_UNAVAILABLE, preview.failureReason());
		f.advance(1_000_000); assertEquals(4, f.packets(InventoryC2SPacket.Kind.OPEN).size());
	}
	@Test void throwingSenderRetiresAttemptAndRowsEvenWhenCloseAlsoThrows() {
		Clock clock = new Clock(); List<InventoryC2SPacket.Kind> kinds = new ArrayList<>();
		ClientInventory client = new ClientInventory(packet -> {
			InventoryC2SPacket wire = (InventoryC2SPacket) packet;
			kinds.add(wire.kind()); throw new IllegalStateException("transport");
		}, clock);
		ClientInventoryV2Test.ready(client);
		InventoryPreviewLifecycle preview = Objects.requireNonNull(client.openPreview(ClientInventoryV2Test.TARGET, BlockFace.UP, "entity_block"));
		assertTrue(preview.waiting()); assertEquals(ClientInventory.NO_REQUEST, preview.requestId());
		assertEquals(0, client.stats().previewChannels()); assertFalse(client.hasPreview(1));
		assertEquals(List.of(InventoryC2SPacket.Kind.OPEN, InventoryC2SPacket.Kind.CLOSE), kinds);
		assertEquals(InventoryFailureReason.TRANSPORT_FAILURE, preview.failureReason());
	}
	@Test void closingDisconnectAndTrueEpochChangeCancelWaitingRetry() {
		for (int mode = 0; mode < 3; mode++) {
			Fixture f = new Fixture(); InventoryPreviewLifecycle preview = f.open();
			f.reject(preview, InventoryFailureReason.REQUEST_CAPACITY);
			if (mode == 0) preview.close();
			else if (mode == 1) f.client.tick(false);
			else f.client.presentationReset(101, 1);
			f.advance(50_000); assertEquals(1, f.packets(InventoryC2SPacket.Kind.OPEN).size());
			assertTrue(preview.cancelled());
		}
	}
	@Test void releaseCancelsRetryBeforeDispatchAndKeepsSelectBeforeClose() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle preview = f.open(); long wire = preview.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, true, InventoryS2CPacket.Status.READY, List.of(entry("one")), null);
		var ref = f.client.selectable(wire).getFirst();
		assertEquals(ClientInventory.DispatchOutcome.SENT, f.client.select(ref, "attention", packet -> {
			f.clock.millis = 100_000; f.client.tick(true);
			assertEquals(1, preview.attempts()); f.sent.add(packet); return ClientInventory.DispatchOutcome.SENT;
		}));
		preview.close();
		@Unmodifiable List<InventoryC2SPacket.Kind> kinds = f.sent.stream().map(InventoryC2SPacket.class::cast).map(InventoryC2SPacket::kind).toList();
		assertTrue(kinds.indexOf(InventoryC2SPacket.Kind.SELECT) < kinds.indexOf(InventoryC2SPacket.Kind.CLOSE));
	}
	@Test void watchdogSeparatesSessionOpenAndStallAndDeferDoesNotRenewProgress() {
		Clock clock = new Clock(); ClientInventory noOffer = new ClientInventory(packet -> {}, clock);
		InventoryPreviewLifecycle session = Objects.requireNonNull(noOffer.openPreview(ClientInventoryV2Test.TARGET, BlockFace.UP, "entity_block"));
		clock.millis = 15_000; noOffer.tick(true); assertEquals(InventoryFailureReason.SESSION_TIMEOUT, session.failureReason());
		Fixture f = new Fixture(); InventoryPreviewLifecycle opening = f.open();
		f.advance(15_000); assertEquals(InventoryFailureReason.OPEN_TIMEOUT, opening.failureReason());
		Fixture defer = new Fixture(); InventoryPreviewLifecycle scan = defer.open();
		ClientInventoryV2Test.opened(defer.client, scan.requestId(), 1);
		for (int i = 1; i <= 15; i++) {
			defer.clock.millis = i * 1_000L;
			defer.control(scan.requestId(), i, 0, InventoryS2CPacket.PreviewCondition.DEFERRED, InventoryFailureReason.PROVIDER_WORK_LIMIT);
			defer.client.tick(true);
		}
		assertTrue(scan.waiting()); assertEquals(InventoryFailureReason.PROVIDER_WORK_LIMIT, scan.failureReason());
	}
	@Test void genuineProgressContinuesBeyondWholeRoundDeadlineButDuplicateRevisionDoesNot() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle preview = f.open(); long wire = preview.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		for (int i = 1; i <= 8; i++) {
			f.clock.millis = i * 10_000L;
			f.control(wire, i, i, InventoryS2CPacket.PreviewCondition.PROGRESS, null); f.client.tick(true);
			assertFalse(preview.waiting());
		}
		f.clock.millis += 14_999;
		f.control(wire, 100, 8, InventoryS2CPacket.PreviewCondition.PROGRESS, null); f.client.tick(true);
		assertFalse(preview.waiting());
		f.advance(95_000); assertTrue(preview.waiting()); assertEquals(InventoryFailureReason.STALL_TIMEOUT, preview.failureReason());
	}
	@Test void itemSuccessSurvivesMissingFluidAndIncompleteSelectableRowsAndEmptyCompletionAreSuccess() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle preview = f.open(); long wire = preview.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 3);
		f.client.accept(InventoryS2CPacket.previewState(InventoryResourceKind.FLUID, 7, 100, 1, wire, 1, 0,
			InventoryS2CPacket.PreviewPhase.ADMISSION, InventoryS2CPacket.PreviewCondition.UNSUPPORTED, InventoryFailureReason.UNSUPPORTED_LANE));
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, false, InventoryS2CPacket.Status.INCOMPLETE,
			List.of(entry("partial")), InventoryFailureReason.VARIANT_LIMIT);
		f.advance(100_000); assertEquals(1, preview.attempts()); assertFalse(preview.waiting());
		assertEquals(1, f.client.selectable(wire).size());
		assertEquals(InventoryFailureReason.VARIANT_LIMIT, f.client.preview(wire).failureReason());
		for (InventoryS2CPacket.Status status : List.of(InventoryS2CPacket.Status.READY, InventoryS2CPacket.Status.UNCERTAIN)) {
			Fixture empty = new Fixture(); InventoryPreviewLifecycle handle = empty.open();
			ClientInventoryV2Test.opened(empty.client, handle.requestId(), 1);
			empty.data(handle.requestId(), InventoryResourceKind.ITEM, 1, 0, 1, true, status, List.of(), null);
			empty.advance(100_000); assertEquals(1, handle.attempts()); assertFalse(handle.waiting());
		}
	}
	@Test void retiredAttemptAndOldViewCannotRefreshCurrentWatchdog() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle preview = f.open(); long retired = preview.requestId();
		f.reject(preview, InventoryFailureReason.REQUEST_CAPACITY); f.advance(5_000);
		long current = preview.requestId(); ClientInventoryV2Test.opened(f.client, current, 1);
		f.clock.millis = 19_999;
		f.control(retired, 99, 99, InventoryS2CPacket.PreviewCondition.PROGRESS, null);
		f.client.accept(InventoryS2CPacket.previewState(InventoryResourceKind.ITEM, 7, 100, 0, current, 99, 99,
			InventoryS2CPacket.PreviewPhase.SCAN, InventoryS2CPacket.PreviewCondition.PROGRESS, null));
		f.advance(20_000); assertTrue(preview.waiting()); assertEquals(2, preview.retriesUsed());
	}
	@Test void full1024PartCutIsAcceptedWithoutPrematureCompletion() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle preview = f.open(); long wire = preview.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		for (int i = 0; i < 1024; i++) {
			f.data(wire, InventoryResourceKind.ITEM, 1, i, 1024, i == 1023, InventoryS2CPacket.Status.UNCERTAIN, List.of(entry("row" + i)), null);
			assertEquals(i == 1023, f.client.preview(wire).completeScan());
		}
		assertEquals(1024, f.client.preview(wire).entries().size()); assertEquals(1024, f.client.selectable(wire).size());
	}
	@Test void duplicateDataDoesNotKeepStalledPreviewUpdating() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle preview = f.open(); long wire = preview.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 2, false, InventoryS2CPacket.Status.UPDATING, List.of(), null);
		f.clock.millis = 14_999;
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 2, false, InventoryS2CPacket.Status.UPDATING, List.of(), null);
		f.advance(15_000); assertTrue(preview.waiting()); assertEquals(InventoryFailureReason.STALL_TIMEOUT, preview.failureReason());
	}
	@Test void deferredForeverStopsAfterExactlyFourAttempts() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle preview = f.open();
		for (int attempt = 0; attempt < 4; attempt++) {
			long wire = preview.requestId(); ClientInventoryV2Test.opened(f.client, wire, 1);
			for (int second = 1; second <= 15; second++) {
				f.clock.millis += 1_000;
				f.control(wire, second, 0, InventoryS2CPacket.PreviewCondition.DEFERRED, InventoryFailureReason.MEMORY_LIMIT);
				f.client.tick(true);
			}
			if (attempt < 3) { f.clock.millis += 5_000; f.client.tick(true); }
		}
		assertEquals(4, preview.attempts()); assertTrue(preview.stopped()); assertFalse(preview.retrying());
		assertEquals(InventoryFailureReason.MEMORY_LIMIT, preview.failureReason());
	}
	@Test void everyMatchingCommitRejectReportsItsSpecificCauseOnceAndNeverResends() {
		Fixture f = new Fixture(); List<InventoryFailureReason> failures = new ArrayList<>();
		f.client.trackingFailureSink(failures::add);
		for (InventoryFailureReason reason : InventoryFailureReason.values()) {
			InventoryPreviewLifecycle preview = f.open(); long wire = preview.requestId();
			ClientInventoryV2Test.opened(f.client, wire, 1);
			f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, true, InventoryS2CPacket.Status.READY, List.of(entry("one")), null);
			f.client.select(f.client.selectable(wire).getFirst(), "attention", packet -> { f.sent.add(packet); return ClientInventory.DispatchOutcome.SENT; });
			InventoryC2SPacket selected = f.packets(InventoryC2SPacket.Kind.SELECT).getLast(); preview.close();
			InventoryS2CPacket reject = InventoryS2CPacket.selected(7, wire, selected.commitId(), null, MarkerRejectReason.INVALID_REQUEST, reason).stamp(100, 1);
			f.client.accept(reject); f.client.accept(reject);
			assertEquals(reason, failures.getLast());
		}
		assertEquals(InventoryFailureReason.values().length, failures.size());
		assertEquals(InventoryFailureReason.values().length, f.packets(InventoryC2SPacket.Kind.SELECT).size());
	}
	@Test void olderPendingCommitRejectionIsNotHiddenByANewerSelection() {
		Fixture f = new Fixture(); List<InventoryFailureReason> failures = new ArrayList<>(); f.client.trackingFailureSink(failures::add);
		for (int i = 0; i < 2; i++) {
			InventoryPreviewLifecycle preview = f.open(); long wire = preview.requestId(); ClientInventoryV2Test.opened(f.client, wire, 1);
			f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, true, InventoryS2CPacket.Status.READY, List.of(entry("selected")), null);
			f.client.select(f.client.selectable(wire).getFirst(), "attention", packet -> { f.sent.add(packet); return ClientInventory.DispatchOutcome.SENT; });
			preview.close();
		}
		var selects = f.packets(InventoryC2SPacket.Kind.SELECT);
		for (InventoryC2SPacket selected : selects) {
			var reject = InventoryS2CPacket.selected(7, selected.requestId(), selected.commitId(), null,
				MarkerRejectReason.OUT_OF_RANGE, InventoryFailureReason.OUT_OF_RANGE).stamp(100, 1);
			f.client.accept(reject); f.client.accept(reject);
		}
		assertEquals(List.of(InventoryFailureReason.OUT_OF_RANGE, InventoryFailureReason.OUT_OF_RANGE), failures);
		assertEquals(2, selects.size());
	}
	@Test void stalledOptionalFluidPreservesCompletedItemWithoutReopening() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle preview = f.open(); long wire = preview.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 3);
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, true, InventoryS2CPacket.Status.READY, List.of(entry("selected")), null);
		f.advance(15_000);
		assertEquals(1, preview.attempts()); assertFalse(preview.waiting());
		assertEquals(1, f.client.selectable(wire).size()); assertTrue(f.client.preview(wire).completeScan());
		assertEquals(InventoryFailureReason.STALL_TIMEOUT, f.client.preview(wire, InventoryResourceKind.FLUID).failureReason());
	}
	@Test void initialPresentationNegotiationKeepsHeldHandleAndStartsItsQueuedAttempt() {
		Clock clock = new Clock(); List<IPacket> sent = new ArrayList<>(); ClientInventory client = new ClientInventory(sent::add, clock);
		InventoryPreviewLifecycle handle = Objects.requireNonNull(client.openPreview(ClientInventoryV2Test.TARGET, BlockFace.UP, "entity_block"));
		long wire = handle.requestId(); assertTrue(sent.isEmpty());
		client.accept(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, 0)).stamp(100, 1));
		client.presentationReset(100, 1);
		assertTrue(client.hasPreview(handle)); assertFalse(handle.cancelled()); assertEquals(wire, handle.requestId());
		assertEquals(1, sent.stream().filter(packet -> packet instanceof InventoryC2SPacket p && p.kind() == InventoryC2SPacket.Kind.OPEN).count());
	}
	@Test void waitingRetryDoesNotOccupyOneOfThe64WireSlots() {
		Fixture f = new Fixture(); List<InventoryPreviewLifecycle> handles = new ArrayList<>();
		for (int i = 0; i < ClientInventory.MAX_PREVIEW_CHANNELS; i++) handles.add(f.open());
		assertEquals(64, f.client.stats().previewChannels());
		f.reject(handles.getFirst(), InventoryFailureReason.REQUEST_CAPACITY);
		assertEquals(63, f.client.stats().previewChannels());
		long wireOnly = f.client.open(ClientInventoryV2Test.TARGET, BlockFace.UP, "entity_block");
		assertNotEquals(ClientInventory.NO_REQUEST, wireOnly);
		assertEquals(64, f.client.stats().previewChannels());
		f.advance(5_000);
		assertEquals(64, f.client.stats().previewChannels());
		assertTrue(handles.getFirst().waiting()); assertEquals(2, handles.getFirst().attempts());
	}
	@Test void sameEpochRegrantCannotTreatHistoricalIncompleteRowsAsNewViewSuccess() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, false, InventoryS2CPacket.Status.INCOMPLETE,
			List.of(entry("old")), InventoryFailureReason.VARIANT_LIMIT);
		var retiredReference = f.client.selectable(wire).getFirst();
		f.client.presentationReset(100, 2);
		assertEquals(InventoryS2CPacket.Status.INCOMPLETE, f.client.preview(wire).status());
		assertEquals(InventoryFailureReason.VARIANT_LIMIT, f.client.preview(wire).failureReason());
		assertTrue(f.client.selectable(wire).isEmpty());
		f.client.accept(InventoryS2CPacket.opened(7, 100, 2, wire, 1));
		f.clock.millis = 14_999;
		f.control(wire, 999, 999, InventoryS2CPacket.PreviewCondition.PROGRESS, null); // Old view cannot renew the grant wait.
		f.advance(15_000);
		assertTrue(handle.waiting()); assertEquals(InventoryFailureReason.STALL_TIMEOUT, handle.failureReason());
		assertFalse(f.client.hasPreview(retiredReference.requestId()));
	}
	@Test void sameEpochRegrantAcceptsFreshControlAndReplacesHistoricalRowsUnderFreshBaseline() {
		for (InventoryS2CPacket.Status oldStatus : List.of(InventoryS2CPacket.Status.READY, InventoryS2CPacket.Status.INCOMPLETE)) {
			Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
			ClientInventoryV2Test.opened(f.client, wire, 1);
			f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, oldStatus == InventoryS2CPacket.Status.READY,
				oldStatus, List.of(entry("old")), oldStatus == InventoryS2CPacket.Status.INCOMPLETE ? InventoryFailureReason.VARIANT_LIMIT : null);
			f.client.presentationReset(100, 2); f.client.accept(InventoryS2CPacket.opened(7, 100, 2, wire, 1));
			assertTrue(f.client.selectable(wire).isEmpty());
			f.clock.millis = 10_000;
			f.client.accept(InventoryS2CPacket.previewState(InventoryResourceKind.ITEM, 7, 100, 2, wire, 1, 1,
				InventoryS2CPacket.PreviewPhase.DELIVERY, InventoryS2CPacket.PreviewCondition.PROGRESS, null));
			f.advance(20_000); assertFalse(handle.waiting(), "fresh progress after regrant renews inactivity even for an old terminal lane");
			f.client.accept(InventoryS2CPacket.data(InventoryResourceKind.ITEM, InventoryS2CPacket.Kind.PREVIEW, 7, wire, null,
				2, 1, 1, 0, 1, true, InventoryS2CPacket.Status.READY, 0, List.of(entry("new")), null).stamp(100, 2));
			assertEquals(List.of("new"), f.client.preview(wire).entries().stream().map(ClientInventory.EntryView::key).toList());
			assertEquals(2, f.client.selectable(wire).getFirst().baselineId());
			assertTrue(f.client.preview(wire).completeScan()); f.advance(100_000); assertEquals(1, handle.attempts());
		}
	}
	@Test void incompleteMultipartWaitsForItsExplicitPartBarrierWhenFluidIsUnsupported() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 3);
		f.client.accept(InventoryS2CPacket.previewState(InventoryResourceKind.FLUID, 7, 100, 1, wire, 1, 0,
			InventoryS2CPacket.PreviewPhase.ADMISSION, InventoryS2CPacket.PreviewCondition.UNSUPPORTED, InventoryFailureReason.UNSUPPORTED_LANE));
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 2, false, InventoryS2CPacket.Status.INCOMPLETE, List.of(), InventoryFailureReason.VARIANT_LIMIT);
		f.client.accept(InventoryS2CPacket.previewState(InventoryResourceKind.ITEM, 7, 100, 1, wire, 2, 1,
			InventoryS2CPacket.PreviewPhase.DELIVERY, InventoryS2CPacket.PreviewCondition.FAILED, InventoryFailureReason.VARIANT_LIMIT));
		assertFalse(handle.waiting()); assertTrue(f.client.hasPreview(wire)); assertTrue(f.packets(InventoryC2SPacket.Kind.CLOSE).isEmpty());
		f.data(wire, InventoryResourceKind.ITEM, 1, 1, 2, false, InventoryS2CPacket.Status.INCOMPLETE, List.of(entry("later")), InventoryFailureReason.VARIANT_LIMIT);
		assertEquals(1, f.client.selectable(wire).size()); assertFalse(handle.waiting()); assertFalse(f.client.preview(wire).completeScan());
		f.advance(100_000); assertEquals(1, handle.attempts());
	}
	@Test void sameEpochRegrantWithoutReplacementOpenedUsesOpenTimeoutEvenWithOldPartialRows() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, false, InventoryS2CPacket.Status.INCOMPLETE,
			List.of(entry("old")), InventoryFailureReason.VARIANT_LIMIT);
		f.client.presentationReset(100, 2); f.advance(15_000);
		assertTrue(handle.waiting()); assertEquals(InventoryFailureReason.OPEN_TIMEOUT, handle.failureReason());
	}
	@Test void regrantedTerminalLaneAcceptsCurrentDeferReasonButDoesNotRenewInactivity() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, false, InventoryS2CPacket.Status.INCOMPLETE,
			List.of(entry("old")), InventoryFailureReason.VARIANT_LIMIT);
		f.client.presentationReset(100, 2); f.client.accept(InventoryS2CPacket.opened(7, 100, 2, wire, 1));
		f.clock.millis = 14_999;
		f.client.accept(InventoryS2CPacket.previewState(InventoryResourceKind.ITEM, 7, 100, 2, wire, 1, 0,
			InventoryS2CPacket.PreviewPhase.DELIVERY, InventoryS2CPacket.PreviewCondition.DEFERRED, InventoryFailureReason.MEMORY_LIMIT));
		f.advance(15_000);
		assertTrue(handle.waiting()); assertEquals(InventoryFailureReason.MEMORY_LIMIT, handle.failureReason());
	}
	@Test void staleReferenceCannotDispatchDuringReplacementOpenedWait() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, true, InventoryS2CPacket.Status.READY, List.of(entry("old")), null);
		var old = f.client.selectable(wire).getFirst(); f.client.presentationReset(100, 2);
		assertEquals(ClientInventory.DispatchOutcome.STALE_REFERENCE, f.client.select(old, "attention", packet -> { throw new AssertionError("old view reference dispatched"); }));
		f.client.accept(InventoryS2CPacket.opened(7, 100, 2, wire, 1));
		assertEquals(ClientInventory.DispatchOutcome.STALE_REFERENCE, f.client.select(old, "attention", packet -> { throw new AssertionError("old baseline survived replacement OPENED"); }));
		assertTrue(f.packets(InventoryC2SPacket.Kind.SELECT).isEmpty());
	}
	@Test void zeroProgressRevisionOnlyDeclaresPhaseAndCannotRenewWatchdog() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1); f.clock.millis = 14_999;
		f.control(wire, 1, 0, InventoryS2CPacket.PreviewCondition.PROGRESS, null);
		f.advance(15_000); assertTrue(handle.waiting()); assertEquals(InventoryFailureReason.STALL_TIMEOUT, handle.failureReason());
	}
	@Test void regrantEmptyIncompleteFenceWaitsForReplacementPublicationInsteadOfClosingRequest() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, false, InventoryS2CPacket.Status.INCOMPLETE, List.of(entry("old")), InventoryFailureReason.VARIANT_LIMIT);
		f.client.presentationReset(100, 2); f.client.accept(InventoryS2CPacket.opened(7, 100, 2, wire, 1));
		f.client.accept(InventoryS2CPacket.data(InventoryResourceKind.ITEM, InventoryS2CPacket.Kind.PREVIEW, 7, wire, null,
			2, 1, 0, 0, 1, false, InventoryS2CPacket.Status.INCOMPLETE, 0, List.of(), InventoryFailureReason.VARIANT_LIMIT).stamp(100, 2));
		assertFalse(handle.waiting()); assertTrue(f.client.hasPreview(wire)); assertTrue(f.client.selectable(wire).isEmpty());
		f.client.accept(InventoryS2CPacket.data(InventoryResourceKind.ITEM, InventoryS2CPacket.Kind.PREVIEW, 7, wire, null,
			2, 1, 1, 0, 1, false, InventoryS2CPacket.Status.INCOMPLETE, 0, List.of(entry("fresh")), InventoryFailureReason.VARIANT_LIMIT).stamp(100, 2));
		assertFalse(handle.waiting()); assertEquals("fresh", f.client.selectable(wire).getFirst().entryKey());
		f.advance(100_000); assertEquals(1, handle.attempts());
	}
	@Test void emptyIncompleteCutWaitsForAllPartsThenRetriesWithoutClaimingEmptySuccess() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		f.data(wire, InventoryResourceKind.ITEM, 1, 1, 2, false, InventoryS2CPacket.Status.INCOMPLETE, List.of(), InventoryFailureReason.MEMORY_LIMIT);
		assertFalse(handle.waiting());
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 2, false, InventoryS2CPacket.Status.INCOMPLETE, List.of(), InventoryFailureReason.MEMORY_LIMIT);
		assertTrue(handle.waiting()); assertEquals(InventoryFailureReason.MEMORY_LIMIT, handle.failureReason());
		assertEquals(1, f.packets(InventoryC2SPacket.Kind.CLOSE).size());
	}
	@Test void regrantEmptyIncompleteFenceWithoutReplacementDataStillExpiresUnderWatchdog() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, true, InventoryS2CPacket.Status.READY, List.of(entry("old")), null);
		f.client.presentationReset(100, 2); f.client.accept(InventoryS2CPacket.opened(7, 100, 2, wire, 1));
		f.clock.millis = 1_000;
		var fence = InventoryS2CPacket.data(InventoryResourceKind.ITEM, InventoryS2CPacket.Kind.PREVIEW, 7, wire, null,
			2, 1, 0, 0, 1, false, InventoryS2CPacket.Status.INCOMPLETE, 0, List.of(), InventoryFailureReason.MEMORY_LIMIT).stamp(100, 2);
		f.client.accept(fence);
		f.advance(14_999); assertFalse(handle.waiting()); assertTrue(f.client.selectable(wire).isEmpty());
		f.client.accept(fence); // A replay of the fence is not replacement progress.
		f.advance(15_000);
		assertTrue(handle.waiting()); assertEquals(InventoryFailureReason.MEMORY_LIMIT, handle.failureReason());
		assertFalse(f.client.hasPreview(wire)); assertEquals(1, f.packets(InventoryC2SPacket.Kind.CLOSE).size());
	}
	@Test void watchdogStoppedPartialRejectsLateCutAndProgressButStillAcceptsInvalidation() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 2, false, InventoryS2CPacket.Status.UPDATING, List.of(entry("kept")), null);
		f.advance(15_000);
		assertEquals(InventoryS2CPacket.Status.INCOMPLETE, f.client.preview(wire).status());
		@Unmodifiable List<ClientInventory.EntryView> retained = f.client.preview(wire).entries();
		f.data(wire, InventoryResourceKind.ITEM, 1, 1, 2, false, InventoryS2CPacket.Status.UPDATING,
			List.of(new InventoryS2CPacket.Entry("kept", "minecraft:stone", "stone", null, 99, 2, false, null), entry("late")), null);
		f.control(wire, 1, 1, InventoryS2CPacket.PreviewCondition.PROGRESS, null);
		assertEquals(retained, f.client.preview(wire).entries());
		assertEquals(InventoryS2CPacket.Status.INCOMPLETE, f.client.preview(wire).status());
		assertEquals(InventoryFailureReason.STALL_TIMEOUT, f.client.preview(wire).failureReason());
		f.advance(100_000); assertEquals(1, handle.attempts()); assertFalse(handle.waiting());
		f.client.accept(InventoryS2CPacket.data(InventoryResourceKind.ITEM, InventoryS2CPacket.Kind.PREVIEW, 7, wire, null,
			1, 2, 2, 0, 1, false, InventoryS2CPacket.Status.INVALID, 0, List.of(), InventoryFailureReason.SOURCE_CHANGED).stamp(100, 1));
		assertTrue(f.client.selectable(wire).isEmpty()); assertTrue(handle.waiting());
		assertEquals(InventoryFailureReason.SOURCE_CHANGED, handle.failureReason());
	}
	@Test void watchdogStopSurvivesRegrantAndCannotInstallReplacementValidRows() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 2, false, InventoryS2CPacket.Status.UPDATING, List.of(entry("old")), null);
		f.advance(15_000); f.client.presentationReset(100, 2);
		f.client.accept(InventoryS2CPacket.opened(7, 100, 2, wire, 1));
		assertTrue(handle.waiting(), "a stopped old-view prefix cannot grant new-view eligibility or reopen the same attempt");
		f.client.accept(InventoryS2CPacket.data(InventoryResourceKind.ITEM, InventoryS2CPacket.Kind.PREVIEW, 7, wire, null,
			2, 1, 2, 0, 1, true, InventoryS2CPacket.Status.READY, 0, List.of(entry("late")), null).stamp(100, 2));
		assertTrue(f.client.selectable(wire).isEmpty()); assertEquals(1, handle.attempts());
	}
	@Test void repeatedLegitimateRegrantsAndDefersCannotExtendAnyAttemptOrItsRetryBudget() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long view = 1;
		for (int attempt = 0; attempt < 4; attempt++) {
			long wire = handle.requestId(); long start = f.clock.millis;
			f.client.accept(InventoryS2CPacket.opened(7, 100, view, wire, 1));
			for (int elapsed : List.of(7_000, 14_000)) {
				f.clock.millis = start + elapsed; f.client.presentationReset(100, ++view);
				f.client.accept(InventoryS2CPacket.opened(7, 100, view, wire, 1));
				f.client.accept(InventoryS2CPacket.previewState(InventoryResourceKind.ITEM, 7, 100, view, wire, 1, 0,
					InventoryS2CPacket.PreviewPhase.DELIVERY, InventoryS2CPacket.PreviewCondition.DEFERRED, InventoryFailureReason.MEMORY_LIMIT));
				f.client.tick(true); assertFalse(handle.waiting());
			}
			f.advance(start + 15_000);
			assertEquals(InventoryFailureReason.MEMORY_LIMIT, handle.failureReason());
			if (attempt < 3) { assertTrue(handle.waiting()); f.advance(start + 20_000); }
		}
		assertTrue(handle.stopped()); assertEquals(4, handle.attempts()); assertEquals(3, handle.retriesUsed());
		f.advance(1_000_000); assertEquals(4, handle.attempts());
	}
	@Test void regrantOfTheSameProgressRevisionDoesNotCountAsNewWork() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		ClientInventoryV2Test.opened(f.client, wire, 1);
		f.clock.millis = 1_000; f.control(wire, 1, 1, InventoryS2CPacket.PreviewCondition.PROGRESS, null);
		f.clock.millis = 15_000; f.client.presentationReset(100, 2);
		f.client.accept(InventoryS2CPacket.opened(7, 100, 2, wire, 1));
		f.client.accept(InventoryS2CPacket.previewState(InventoryResourceKind.ITEM, 7, 100, 2, wire, 1, 1,
			InventoryS2CPacket.PreviewPhase.SCAN, InventoryS2CPacket.PreviewCondition.PROGRESS, null));
		f.advance(16_000); assertTrue(handle.waiting()); assertEquals(InventoryFailureReason.STALL_TIMEOUT, handle.failureReason());
	}
	@Test void onlyTheFirstOpenedStartsTheScanPhaseClock() {
		Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
		f.clock.millis = 14_000; ClientInventoryV2Test.opened(f.client, wire, 1);
		f.advance(15_000); assertFalse(handle.waiting());
		f.clock.millis = 28_000; f.client.presentationReset(100, 2);
		f.client.accept(InventoryS2CPacket.opened(7, 100, 2, wire, 1));
		f.advance(29_000); assertTrue(handle.waiting()); assertEquals(InventoryFailureReason.STALL_TIMEOUT, handle.failureReason());
	}
	@Test void stoppedPartialStillAcceptsExplicitLaneRevocation() {
		for (var condition : List.of(InventoryS2CPacket.PreviewCondition.UNSUPPORTED, InventoryS2CPacket.PreviewCondition.FAILED)) {
			Fixture f = new Fixture(); InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId();
			ClientInventoryV2Test.opened(f.client, wire, 1);
			f.data(wire, InventoryResourceKind.ITEM, 1, 0, 2, false, InventoryS2CPacket.Status.UPDATING, List.of(entry("old")), null);
			f.advance(15_000);
			InventoryFailureReason reason = condition == InventoryS2CPacket.PreviewCondition.UNSUPPORTED
				? InventoryFailureReason.UNSUPPORTED_LANE : InventoryFailureReason.SOURCE_AUTHORITY_DENIED;
			f.client.accept(InventoryS2CPacket.previewState(InventoryResourceKind.ITEM, 7, 100, 1, wire, 1, 0,
				InventoryS2CPacket.PreviewPhase.ADMISSION, condition, reason));
			assertTrue(f.client.selectable(wire).isEmpty()); assertTrue(handle.waiting());
			assertEquals(reason, handle.failureReason());
		}
	}
	@Test void pendingCommitCapacityRejectDoesNotConsumeAReferenceThatWasNeverDispatched() {
		Fixture f = new Fixture(); List<InventoryFailureReason> failures = new ArrayList<>(); f.client.trackingFailureSink(failures::add);
		for (int i = 0; i < 64; i++) {
			InventoryPreviewLifecycle handle = f.open(); long wire = handle.requestId(); ClientInventoryV2Test.opened(f.client, wire, 1);
			f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, true, InventoryS2CPacket.Status.READY, List.of(entry("row")), null);
			assertEquals(ClientInventory.DispatchOutcome.SENT, f.client.select(f.client.selectable(wire).getFirst(), "attention",
				packet -> { f.sent.add(packet); return ClientInventory.DispatchOutcome.SENT; })); handle.close();
		}
		InventoryPreviewLifecycle last = f.open(); long wire = last.requestId(); ClientInventoryV2Test.opened(f.client, wire, 1);
		f.data(wire, InventoryResourceKind.ITEM, 1, 0, 1, true, InventoryS2CPacket.Status.READY, List.of(entry("last")), null);
		var reference = f.client.selectable(wire).getFirst();
		assertEquals(ClientInventory.DispatchOutcome.NOT_READY, f.client.select(reference, "attention", packet -> { throw new AssertionError("capacity must precede transport"); }));
		assertEquals(1, f.client.selectable(wire).size()); assertEquals(List.of(InventoryFailureReason.CONTROL_CAPACITY), failures);
		var earlier = f.packets(InventoryC2SPacket.Kind.SELECT).getFirst();
		f.client.accept(InventoryS2CPacket.selected(7, earlier.requestId(), earlier.commitId(), new nx.pingwheel.common.domain.MarkerId(91), null).stamp(100, 1));
		assertEquals(ClientInventory.DispatchOutcome.SENT, f.client.select(reference, "attention", packet -> { f.sent.add(packet); return ClientInventory.DispatchOutcome.SENT; }));
		assertEquals(65, f.packets(InventoryC2SPacket.Kind.SELECT).size()); last.close();
	}
	private static InventoryS2CPacket.Entry entry(String key) {
		return new InventoryS2CPacket.Entry(key, "minecraft:stone", "stone", null, 1, 1, false, null);
	}
}
