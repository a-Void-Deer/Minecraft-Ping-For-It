package nx.pingwheel.common.presentation.inventory.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.monitor.MonitorSchema.*;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.monitor.client.ClientMonitorCollector;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
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
class ClientInventoryMonitorOutcomeTest {

	private static final MarkerId MARKER = new MarkerId(1);
	private static final Target TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");

	@NotNullByDefault
	private static final class Fixture {
		final AtomicLong clock = new AtomicLong();
		final ClientMonitorCollector collector = new ClientMonitorCollector(new MonitorTuning(1, 8), clock::incrementAndGet);
		final ClientInventory inventory;
		Fixture() { this(packet -> {}); }
		Fixture(Consumer<IPacket> sender) {
			inventory = new ClientInventory(sender); inventory.monitor(collector); inventory.presentationReset(10, 1);
			inventory.accept(InventoryS2CPacket.offer(3, new InventoryS2CPacket.Offer(10, 10, 0, 0)).stamp(10, 1));
			inventory.markerCreated(new MarkerSnapshot(MARKER, new UUID(1, 2), TARGET, "entity_block", "attention",
				new MarkerAnchor(1, 2, 3), 0, 1000), new MarkerContentGrant(TestContentGrants.adapters(
				InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA, Set.of(InventoryResourceKind.ITEM.fieldId())), InventoryResourceKind.ITEM));
		}
		void baseline() { inventory.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, MARKER, 1, 1, 2, 1, 0, 1, 4, 1)); }
		LaneSample sample(Lane lane, long tick) {
			MonitorSample sample = Objects.requireNonNull(collector.completeTick(tick));
			return Objects.requireNonNull(lane == Lane.PREVIEW ? sample.preview() : sample.tracking());
		}
	}

	private static InventoryS2CPacket data(InventoryS2CPacket.Kind kind, @Nullable MarkerId marker, long request,
		long baseline, long statusRevision, long watermark, int index, int parts, long count, long itemRevision) {
		return InventoryS2CPacket.data(kind, 3, request, marker, baseline, statusRevision, watermark, index, parts, false,
			InventoryS2CPacket.Status.UPDATING, 0, List.of(new InventoryS2CPacket.Entry("stone" + index, "minecraft:stone", "Stone",
				null, count, itemRevision, false, null))).stamp(10, 1);
	}
	private static long value(LaneSample sample, Counter counter) {
		@Nullable CounterReading reading = sample.counter(counter);
		return reading == null ? 0 : reading.value();
	}

	@Test void oldSnapshotStreamAndStatusAreRejectedWithoutAcceptedApplyButStillMeasureReceivedDataFrames() {
		var f = new Fixture(); f.baseline(); f.collector.configure(true);
		long diagnosticAccepted = f.inventory.stats().acceptedPackets();
		f.inventory.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, MARKER, 1, 1, 1, 1, 0, 1, 99, 2));
		f.inventory.accept(data(InventoryS2CPacket.Kind.STREAM, MARKER, 1, 1, 1, 1, 0, 1, 99, 2));
		f.inventory.accept(InventoryS2CPacket.status(3, 1, MARKER, 1, 1, 1, InventoryS2CPacket.Status.UPDATING, 0).stamp(10, 1));
		var sample = f.sample(Lane.TRACKING, 1);
		assertEquals(4, f.inventory.tracking(MARKER).entries().getFirst().count());
		assertEquals(3, f.inventory.stats().acceptedPackets() - diagnosticAccepted, "legacy diagnostic remains preliminary ingress");
		assertEquals(3, f.inventory.stats().fencedPackets());
		assertEquals(0, value(sample, Counter.ACCEPTED)); assertEquals(3, value(sample, Counter.REJECTED));
		assertEquals(1, value(sample, Counter.FRAMES_SNAPSHOT)); assertEquals(1, value(sample, Counter.FRAMES_STREAM));
		assertEquals(3, sample.timing(Phase.APPLY).count());
	}

	@Test void unknownStreamsAndIncompleteBaselinePartsAreHeldNotAcceptedUntilTheyActuallyInstall() {
		var f = new Fixture(); f.collector.configure(true);
		f.inventory.accept(data(InventoryS2CPacket.Kind.STREAM, MARKER, 1, 2, 1, 1, 1, 2, 7, 1));
		var held = f.sample(Lane.TRACKING, 1);
		assertEquals(1, f.inventory.stats().unknownSessions()); assertTrue(f.inventory.tracking(MARKER).entries().isEmpty());
		assertEquals(0, value(held, Counter.ACCEPTED)); assertEquals(0, value(held, Counter.REJECTED));
		f.inventory.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, MARKER, 1, 2, 1, 1, 0, 2, 4, 1));
		var installed = f.sample(Lane.TRACKING, 2);
		assertEquals(0, f.inventory.stats().unknownSessions()); assertEquals(2, f.inventory.tracking(MARKER).entries().size());
		assertEquals(1, value(installed, Counter.ACCEPTED), "one committed baseline, not buffered fragments");
		assertEquals(1, value(installed, Counter.FRAMES_STREAM), "internal buffer replay is not another reception");
		assertEquals(1, value(installed, Counter.FRAMES_SNAPSHOT));
	}

	@Test void duplicateAndConflictingPartsAndWrongPreviewRequestCannotBecomeSuccessfulApplies() {
		var f = new Fixture(); f.collector.configure(true);
		var partial = data(InventoryS2CPacket.Kind.SNAPSHOT, MARKER, 1, 2, 1, 1, 0, 2, 4, 1);
		f.inventory.accept(partial); f.inventory.accept(partial);
		f.inventory.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, MARKER, 99, 2, 1, 1, 1, 2, 8, 1));
		f.inventory.accept(data(InventoryS2CPacket.Kind.PREVIEW, null, 99, 1, 1, 1, 0, 1, 99, 1));
		var tracking = f.sample(Lane.TRACKING, 1);
		assertTrue(f.inventory.tracking(MARKER).entries().isEmpty());
		assertEquals(0, value(tracking, Counter.ACCEPTED)); assertEquals(1, value(tracking, Counter.REJECTED));
		assertEquals(3, value(tracking, Counter.FRAMES_SNAPSHOT));
		var local = Objects.requireNonNull(f.collector.latest()).preview();
		assertEquals(0, value(Objects.requireNonNull(local), Counter.ACCEPTED)); assertEquals(1, value(local, Counter.REJECTED));
		assertEquals(1, value(local, Counter.FRAMES_PREVIEW));
	}

	@Test void closedSnapshotReplayAndPendingFutureHeartbeatDoNotCountAsAppliedValues() {
		var f = new Fixture(); f.baseline(); f.collector.configure(true);
		f.baseline();
		f.inventory.accept(InventoryS2CPacket.heartbeat(3, 1, MARKER, 1, 2, 2, 0).stamp(10, 1));
		var sample = f.sample(Lane.TRACKING, 1);
		assertEquals(0, value(sample, Counter.ACCEPTED)); assertEquals(0, value(sample, Counter.REJECTED));
		assertEquals(1, value(sample, Counter.FRAMES_SNAPSHOT));
		assertEquals(4, f.inventory.tracking(MARKER).entries().getFirst().count());
	}

	@Test void nestedSenderReplyRecordsItsOwnOutcomeOnceWithoutChangingInclusiveTimingOrDiagnostics() {
		List<ClientInventory> clients = new ArrayList<>(); boolean[] replied = {false};
		var f = new Fixture(packet -> {
			if (packet instanceof InventoryC2SPacket request && request.kind() == InventoryC2SPacket.Kind.RESYNC && !replied[0]) {
				replied[0] = true;
				clients.getFirst().accept(InventoryS2CPacket.heartbeat(3, 1, MARKER, 1, 2, 1, 0).stamp(10, 1));
			}
		});
		clients.add(f.inventory); f.baseline(); f.collector.configure(true);
		long diagnosticAccepted = f.inventory.stats().acceptedPackets();
		f.inventory.accept(InventoryS2CPacket.heartbeat(3, 1, MARKER, 1, 2, 1, Long.MAX_VALUE).stamp(10, 1));
		var sample = f.sample(Lane.TRACKING, 1);
		assertTrue(replied[0]); assertEquals(2, f.inventory.stats().acceptedPackets() - diagnosticAccepted);
		assertEquals(2, value(sample, Counter.ACCEPTED), "two control comparisons, no cumulative outer delta");
		assertEquals(0, value(sample, Counter.REJECTED)); assertEquals(1, sample.timing(Phase.APPLY).count());
	}

	@Test void duplicatePreviewAndStatusControlsStayUnchangedAndWrongSelectionCorrelationIsRejected() {
		var f = new Fixture();
		long request = f.inventory.open(TARGET, BlockFace.NORTH, "entity_block");
		f.inventory.accept(InventoryS2CPacket.opened(3, 10, 1, request, 1));
		var preview = data(InventoryS2CPacket.Kind.PREVIEW, null, request, 1, 2, 1, 0, 1, 4, 1);
		var status = InventoryS2CPacket.status(3, 1, MARKER, 1, 2, 1, InventoryS2CPacket.Status.UPDATING, 0).stamp(10, 1);
		f.inventory.accept(preview); f.baseline(); f.inventory.accept(status); f.collector.configure(true);
		f.inventory.accept(preview); f.inventory.accept(status);
		f.inventory.accept(InventoryS2CPacket.opened(3, 10, 1, request, 1));
		var reference = f.inventory.selectable(request).getFirst();
		var commits = new ArrayList<InventoryC2SPacket>();
		f.inventory.select(reference, "attention", packet -> { commits.add(packet); return ClientInventory.DispatchOutcome.SENT; });
		var commit = commits.getFirst();
		f.inventory.accept(InventoryS2CPacket.selected(InventoryResourceKind.ITEM, 3, request + 1, commit.commitId(), MARKER, null).stamp(10, 1));
		assertNull(f.inventory.selectionResult(commit.commitId()));
		var tracking = f.sample(Lane.TRACKING, 1); var local = Objects.requireNonNull(f.collector.latest().preview());
		assertEquals(0, value(tracking, Counter.ACCEPTED)); assertEquals(0, value(local, Counter.ACCEPTED));
		assertEquals(1, value(local, Counter.REJECTED)); assertEquals(1, value(local, Counter.FRAMES_PREVIEW));
	}

	@Test void throwingRepairSinkDoesNotReportTheUnfinishedControlAsAccepted() {
		var f = new Fixture(packet -> { if (packet instanceof InventoryC2SPacket request
			&& request.kind() == InventoryC2SPacket.Kind.RESYNC) throw new IllegalStateException("test transport"); });
		f.baseline(); f.collector.configure(true);
		assertThrows(IllegalStateException.class, () -> f.inventory.accept(InventoryS2CPacket.heartbeat(3, 1, MARKER,
			1, 2, 1, Long.MAX_VALUE).stamp(10, 1)));
		var sample = f.sample(Lane.TRACKING, 1);
		assertEquals(0, value(sample, Counter.ACCEPTED)); assertEquals(0, value(sample, Counter.REJECTED));
		assertEquals(1, sample.timing(Phase.APPLY).count()); assertEquals(4, f.inventory.tracking(MARKER).entries().getFirst().count());
	}

	@Test void repeatedReadyAndUncertainCompletionControlsDoNotCountClearedOrPendingBarriersAgain() {
		for (var status : List.of(InventoryS2CPacket.Status.READY, InventoryS2CPacket.Status.UNCERTAIN)) {
			for (long watermark : new long[] {1, 2}) {
				var f = new Fixture(); f.baseline();
				long checksum = nx.pingwheel.common.presentation.inventory.InventoryChecksums.builder()
					.add("stone0", "minecraft:stone", 4, null, false).checksum();
				var completion = InventoryS2CPacket.status(3, 1, MARKER, 1, 2, watermark, status, checksum).stamp(10, 1);
				f.inventory.accept(completion); f.collector.configure(true);
				long diagnostic = f.inventory.stats().acceptedPackets();
				f.inventory.accept(completion); f.inventory.accept(completion);
				var sample = f.sample(Lane.TRACKING, 1);
				assertEquals(0, value(sample, Counter.ACCEPTED), status + " repeated completion watermark " + watermark);
				assertEquals(0, value(sample, Counter.REJECTED)); assertEquals(2, sample.timing(Phase.APPLY).count());
				assertEquals(2, f.inventory.stats().acceptedPackets() - diagnostic);
				assertEquals(4, f.inventory.tracking(MARKER).entries().getFirst().count());
			}
		}
	}

	@Test void firstPrebaselineStatusInstallsOnceAndIdenticalControlsAreNeitherAcceptedNorRejected() {
		var f = new Fixture(); f.collector.configure(true);
		var status = InventoryS2CPacket.status(3, 1, MARKER, 1, 2, 1, InventoryS2CPacket.Status.UPDATING, 0).stamp(10, 1);
		long diagnostic = f.inventory.stats().acceptedPackets();
		f.inventory.accept(status);
		assertEquals(1, value(f.sample(Lane.TRACKING, 1), Counter.ACCEPTED), "first control installs its fence");
		var before = f.inventory.tracking(MARKER);
		f.inventory.accept(status); f.inventory.accept(status);
		var sample = f.sample(Lane.TRACKING, 2);
		assertEquals(before, f.inventory.tracking(MARKER)); assertTrue(before.entries().isEmpty());
		assertEquals(1, value(sample, Counter.ACCEPTED)); assertEquals(0, value(sample, Counter.REJECTED));
		assertEquals(3, f.inventory.stats().acceptedPackets() - diagnostic); assertEquals(3, sample.timing(Phase.APPLY).count());
	}

	@Test void prebaselineRevisionBaselineAndStatusChangesStillInstallWhileTheirReplaysDoNot() {
		var f = new Fixture(); f.collector.configure(true);
		long expected = 0;
		for (var update : List.of(
			InventoryS2CPacket.status(3, 1, MARKER, 1, 2, 1, InventoryS2CPacket.Status.UPDATING, 0),
			InventoryS2CPacket.status(3, 1, MARKER, 1, 3, 1, InventoryS2CPacket.Status.UPDATING, 0),
			InventoryS2CPacket.status(3, 1, MARKER, 2, 3, 1, InventoryS2CPacket.Status.UPDATING, 0),
			InventoryS2CPacket.status(3, 1, MARKER, 2, 3, 1, InventoryS2CPacket.Status.READY, 11),
			InventoryS2CPacket.status(3, 1, MARKER, 2, 3, 1, InventoryS2CPacket.Status.UNCERTAIN, 11))) {
			var packet = update.stamp(10, 1);
			f.inventory.accept(packet); f.inventory.accept(packet); f.inventory.accept(packet);
			var sample = f.sample(Lane.TRACKING, ++expected);
			assertEquals(expected, value(sample, Counter.ACCEPTED), "only actual control changes install");
			assertEquals(0, value(sample, Counter.REJECTED)); assertTrue(f.inventory.tracking(MARKER).entries().isEmpty());
		}
		assertEquals(15, f.inventory.stats().acceptedPackets());
	}

	@Test void repeatingPrebaselineStatusKeepsPendingAssemblyHiddenWithoutAnAppliedOutcome() {
		for (var status : List.of(InventoryS2CPacket.Status.UPDATING, InventoryS2CPacket.Status.READY,
			InventoryS2CPacket.Status.UNCERTAIN, InventoryS2CPacket.Status.INCOMPLETE)) {
			var f = new Fixture();
			var initial = InventoryS2CPacket.status(3, 1, MARKER, 1, 2, 1, status, 11).stamp(10, 1);
			f.inventory.accept(initial);
			f.inventory.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, MARKER, 1, 1, 2, 1, 0, 2, 4, 1));
			var before = f.inventory.tracking(MARKER); f.collector.configure(true);
			f.inventory.accept(initial); f.inventory.accept(initial);
			var sample = f.sample(Lane.TRACKING, 1);
			assertEquals(before, f.inventory.tracking(MARKER));
			assertEquals(0, value(sample, Counter.ACCEPTED), "pending assembly duplicate " + status);
			assertEquals(0, value(sample, Counter.REJECTED));
		}
	}

	@Test void everyPrebaselineStatusVariantCountsItsFirstInstallationNotItsIdenticalReplays() {
		for (var status : InventoryS2CPacket.Status.values()) {
			var f = new Fixture(); f.collector.configure(true);
			var packet = InventoryS2CPacket.status(3, 1, MARKER, 4, 7, 2, status, 19).stamp(10, 1);
			f.inventory.accept(packet);
			var first = f.inventory.tracking(MARKER);
			assertEquals(1, value(f.sample(Lane.TRACKING, 1), Counter.ACCEPTED), "first status " + status);
			f.inventory.accept(packet); f.inventory.accept(packet);
			var repeated = f.sample(Lane.TRACKING, 2);
			assertEquals(first, f.inventory.tracking(MARKER)); assertTrue(first.entries().isEmpty());
			assertEquals(1, value(repeated, Counter.ACCEPTED), "repeated status " + status);
			assertEquals(0, value(repeated, Counter.REJECTED)); assertEquals(3, f.inventory.stats().acceptedPackets());
		}
	}

	@Test void changingOnlyPrebaselineCompletionEvidenceIsHeldRatherThanAProjectionApply() {
		var f = new Fixture(); f.collector.configure(true);
		f.inventory.accept(InventoryS2CPacket.status(3, 1, MARKER, 1, 2, 1, InventoryS2CPacket.Status.READY, 11).stamp(10, 1));
		assertEquals(1, value(f.sample(Lane.TRACKING, 1), Counter.ACCEPTED));
		var before = f.inventory.tracking(MARKER);
		f.inventory.accept(InventoryS2CPacket.status(3, 1, MARKER, 1, 2, 2, InventoryS2CPacket.Status.READY, 22).stamp(10, 1));
		f.inventory.accept(InventoryS2CPacket.status(3, 1, MARKER, 1, 2, 2, InventoryS2CPacket.Status.READY, 22).stamp(10, 1));
		var sample = f.sample(Lane.TRACKING, 2);
		assertEquals(before, f.inventory.tracking(MARKER)); assertEquals(1, value(sample, Counter.ACCEPTED));
		assertEquals(0, value(sample, Counter.REJECTED));
	}
}
