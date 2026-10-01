package nx.pingwheel.common.presentation.inventory.client;

import java.util.ArrayList;
import java.util.List;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.InventoryChecksums;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientInventoryTest {

	private static final long EPOCH = 7L;
	private static final long BASELINE = 4L;
	private static final long REVISION = 1L;
	private static final long WATERMARK = 5L;
	private static final long WINDOW = 60L;

	private final List<IPacket> sent = new ArrayList<>();
	private final ClientInventory session = new ClientInventory(sent::add);
	private final MarkerId markerId = new MarkerId(3L);

	private static Target target() {
		return new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	}

	private static InventoryS2CPacket.Entry entry(String key, long count, long revision) {
		return new InventoryS2CPacket.Entry(key, "minecraft:stone", key, null, count, revision, false, null);
	}

	private static InventoryS2CPacket.Offer settings() {
		return new InventoryS2CPacket.Offer(10, 20, 3, 5);
	}

	private void offer() {
		session.accept(InventoryS2CPacket.offer(EPOCH, settings()));
	}

	private InventoryS2CPacket data(InventoryS2CPacket.Kind kind, long baseline, long revision, int partIndex,
			int partCount, long watermark, List<InventoryS2CPacket.Entry> entries) {
		return InventoryS2CPacket.data(kind, EPOCH, 1L, markerId, baseline, revision, watermark, partIndex,
			partCount, true, InventoryS2CPacket.Status.READY, 0L, entries);
	}

	private InventoryS2CPacket snapshot(int partIndex, int partCount, List<InventoryS2CPacket.Entry> entries) {
		return data(InventoryS2CPacket.Kind.SNAPSHOT, BASELINE, REVISION, partIndex, partCount, WATERMARK, entries);
	}

	private InventoryS2CPacket previewData(long requestId, long baseline, long revision, long watermark,
			int partIndex, int partCount, boolean completeScan, InventoryS2CPacket.Status status,
			List<InventoryS2CPacket.Entry> entries) {
		return InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, EPOCH, requestId, null, baseline, revision,
			watermark, partIndex, partCount, completeScan, status, 0L, entries);
	}

	private void commitTwoEntries() {
		offer();
		session.accept(snapshot(0, 2, List.of(entry("a", 3L, 1L))));
		session.accept(snapshot(1, 2, List.of(entry("b", 5L, 1L))));
	}

	private InventoryS2CPacket heartbeat(long checksum, long watermark) {
		return InventoryS2CPacket.heartbeat(EPOCH, 1L, markerId, BASELINE, REVISION, watermark, checksum);
	}

	@Test
	void helloIsAttemptedOnFirstTickAndStoppedByOffer() {
		session.tick(true);

		assertEquals(1, sent.size());
		InventoryC2SPacket hello = assertInstanceOf(InventoryC2SPacket.class, sent.get(0));
		assertEquals(InventoryC2SPacket.Kind.HELLO, hello.kind());
		assertTrue(session.helloPending());

		offer();

		assertTrue(session.ready());
		assertEquals(EPOCH, session.epoch());
		assertFalse(session.helloPending());
		int afterOffer = sent.size();
		for (int i = 0; i < 10; i++)
			session.tick(true);
		assertEquals(afterOffer, sent.size(), "no further HELLO after an offer");
	}

	@Test
	void disconnectedTickSendsNothing() {
		session.tick(false);
		assertTrue(sent.isEmpty());
	}

	@Test
	void openBeforeOfferIsDeferredAndSentAfterOffer() {
		long requestId = session.open(target());
		assertTrue(requestId >= 0L);
		assertTrue(sent.isEmpty(), "OPEN waits for the epoch");
		assertFalse(session.select(requestId, "a", "minecraft:stone", "basic"),
			"a selection before the epoch is not sent");

		offer();

		assertEquals(1, sent.size());
		InventoryC2SPacket open = assertInstanceOf(InventoryC2SPacket.class, sent.get(0));
		assertEquals(InventoryC2SPacket.Kind.OPEN, open.kind());
		assertEquals(EPOCH, open.epoch());
		assertEquals(requestId, open.requestId());
		assertTrue(session.select(requestId, "a", "minecraft:stone", "basic"));
		assertEquals(InventoryC2SPacket.Kind.SELECT,
			assertInstanceOf(InventoryC2SPacket.class, sent.get(1)).kind());
	}

	@Test
	void previewPublishesPartialEntriesImmediately() {
		long requestId = session.open(target());
		offer();
		sent.clear();

		session.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, EPOCH, requestId, null, 2L, 1L,
			WATERMARK, 0, 2, false, InventoryS2CPacket.Status.UPDATING, 0L,
			List.of(entry("a", 4L, 1L))));

		ClientInventory.Preview preview = session.preview(requestId);
		assertEquals(1, preview.entries().size());
		assertEquals(4L, preview.entries().get(0).count());
		assertEquals(1, preview.receivedParts());
		assertEquals(2, preview.totalParts());
		assertFalse(preview.completeScan());
		assertEquals(InventoryS2CPacket.Status.UPDATING, preview.status());
	}

	@Test
	void previewReplacementGrowthRespectsByteBound() {
		long requestId = session.open(target());
		offer();
		sent.clear();

		String display = "d".repeat(4096);
		List<InventoryS2CPacket.Entry> fill = new ArrayList<>();
		for (int i = 0; i < 62; i++) {
			fill.add(new InventoryS2CPacket.Entry("k" + i, "minecraft:stone", "", display, 1L, 1L, false, null));
		}
		fill.add(new InventoryS2CPacket.Entry("small", "minecraft:stone", "", null, 1L, 1L, false, null));
		session.accept(previewData(requestId, 1L, 1L, 1L, 0, 1, false, InventoryS2CPacket.Status.UPDATING, fill));
		assertEquals(63, session.preview(requestId).entries().size());
		long rejectsBefore = session.stats().boundRejects();

		session.accept(previewData(requestId, 1L, 1L, 2L, 0, 1, false, InventoryS2CPacket.Status.UPDATING,
			List.of(new InventoryS2CPacket.Entry("small", "minecraft:stone", "", display, 9L, 2L, false, null))));

		var kept = session.preview(requestId).entries().stream()
			.filter(view -> view.key().equals("small")).findFirst().orElseThrow();
		assertEquals(1L, kept.count(), "a rejected replacement keeps the prior row");
		assertNull(kept.displayJson(), "the oversized replacement must not overwrite the prior row");
		assertTrue(session.stats().boundRejects() > rejectsBefore, "the replacement is refused at the byte bound");

		session.accept(previewData(requestId, 1L, 1L, 3L, 0, 1, false, InventoryS2CPacket.Status.UPDATING,
			List.of(new InventoryS2CPacket.Entry("extra", "minecraft:stone", "", null, 1L, 3L, false, null))));
		assertEquals(64, session.preview(requestId).entries().size(),
			"the retained total still admits an entry that fits the bound");
	}

	@Test
	void previewMultipartCompletesOnlyAfterEveryIndexArrives() {
		long requestId = session.open(target());
		offer();
		sent.clear();

		session.accept(previewData(requestId, 1L, 1L, 5L, 1, 2, true, InventoryS2CPacket.Status.UPDATING,
			List.of(entry("b", 5L, 5L))));

		ClientInventory.Preview late = session.preview(requestId);
		assertEquals(1, late.receivedParts(), "only the arrived index counts, not the highest index");
		assertEquals(2, late.totalParts());
		assertFalse(late.completeScan(), "a late part alone is not completion");

		session.accept(previewData(requestId, 1L, 1L, 5L, 0, 2, false, InventoryS2CPacket.Status.UPDATING,
			List.of(entry("a", 3L, 5L))));

		ClientInventory.Preview complete = session.preview(requestId);
		assertEquals(2, complete.receivedParts());
		assertEquals(2, complete.totalParts());
		assertTrue(complete.completeScan(), "the accumulated complete flag plus every part completes the group");
		assertEquals(2, complete.entries().size());
	}

	@Test
	void previewNewerWatermarkResetsCompletion() {
		long requestId = session.open(target());
		offer();
		sent.clear();

		session.accept(previewData(requestId, 1L, 1L, 5L, 0, 1, true, InventoryS2CPacket.Status.READY,
			List.of(entry("a", 3L, 5L))));
		assertTrue(session.preview(requestId).completeScan());

		session.accept(previewData(requestId, 1L, 1L, 6L, 0, 1, false, InventoryS2CPacket.Status.UPDATING,
			List.of(entry("b", 5L, 6L))));

		ClientInventory.Preview newer = session.preview(requestId);
		assertFalse(newer.completeScan(), "a newer watermark begins an incomplete scan step");
		assertEquals(6L, newer.watermark());
		assertEquals(2, newer.entries().size(), "sparse deltas accumulate across watermarks");
	}

	@Test
	void previewLowerBaselineAtSameFenceIsFenced() {
		long requestId = session.open(target());
		offer();
		sent.clear();

		session.accept(previewData(requestId, 5L, 2L, 10L, 0, 1, true, InventoryS2CPacket.Status.READY,
			List.of(entry("a", 3L, 10L))));
		long fencedBefore = session.stats().fencedPackets();

		session.accept(previewData(requestId, 4L, 2L, 11L, 0, 1, false, InventoryS2CPacket.Status.UPDATING,
			List.of(entry("b", 9L, 11L))));

		ClientInventory.Preview fenced = session.preview(requestId);
		assertEquals(5L, fenced.baselineId());
		assertTrue(fenced.completeScan(), "a stale lower baseline cannot roll back completion");
		assertEquals(1, fenced.entries().size());
		assertTrue(session.stats().fencedPackets() > fencedBefore);

		session.accept(previewData(requestId, 6L, 2L, 12L, 0, 1, false, InventoryS2CPacket.Status.UPDATING,
			List.of(entry("c", 4L, 12L))));

		ClientInventory.Preview resync = session.preview(requestId);
		assertEquals(6L, resync.baselineId());
		assertEquals(1, resync.entries().size(), "a new baseline clears prior values");
		assertFalse(resync.completeScan());
	}

	@Test
	void previewSinglePartSparseBatchesThenTerminalComplete() {
		long requestId = session.open(target());
		offer();
		sent.clear();

		session.accept(previewData(requestId, 1L, 1L, 9L, 0, 1, false, InventoryS2CPacket.Status.UPDATING,
			List.of(entry("a", 1L, 9L))));
		session.accept(previewData(requestId, 1L, 1L, 9L, 0, 1, false, InventoryS2CPacket.Status.UPDATING,
			List.of(entry("b", 2L, 9L))));
		assertFalse(session.preview(requestId).completeScan());

		session.accept(previewData(requestId, 1L, 1L, 9L, 0, 1, true, InventoryS2CPacket.Status.READY,
			List.of()));

		ClientInventory.Preview done = session.preview(requestId);
		assertTrue(done.completeScan(), "an empty terminal control completes the latest watermark group");
		assertEquals(2, done.entries().size(), "repeated single-part packets are independent deltas");
		assertEquals(1, done.receivedParts());
		assertEquals(1, done.totalParts());
	}

	@Test
	void previewInvalidControlCannotComplete() {
		long requestId = session.open(target());
		offer();
		sent.clear();

		session.accept(previewData(requestId, 1L, 1L, 5L, 0, 1, false, InventoryS2CPacket.Status.INVALID,
			List.of()));

		ClientInventory.Preview invalid = session.preview(requestId);
		assertTrue(invalid.invalid());
		assertFalse(invalid.completeScan(), "an invalid control never completes the scan");

		session.accept(previewData(requestId, 1L, 2L, 6L, 0, 1, false, InventoryS2CPacket.Status.INCOMPLETE,
			List.of()));

		ClientInventory.Preview incomplete = session.preview(requestId);
		assertEquals(InventoryS2CPacket.Status.INCOMPLETE, incomplete.status());
		assertFalse(incomplete.completeScan(), "an incomplete control never completes the scan");
	}

	@Test
	void previewRejectedEntryPreventsFalseCompletion() {
		long requestId = session.open(target());
		offer();
		sent.clear();

		List<InventoryS2CPacket.Entry> first = new ArrayList<>();
		List<InventoryS2CPacket.Entry> second = new ArrayList<>();
		for (int i = 0; i < 128; i++) {
			first.add(entry("a" + i, 1L, 1L));
			second.add(entry("b" + i, 1L, 1L));
		}

		session.accept(previewData(requestId, 1L, 1L, 3L, 0, 1, true, InventoryS2CPacket.Status.UPDATING, first));
		session.accept(previewData(requestId, 1L, 1L, 3L, 0, 1, false, InventoryS2CPacket.Status.UPDATING, second));
		long rejectsBefore = session.stats().boundRejects();

		session.accept(previewData(requestId, 1L, 1L, 3L, 0, 1, false, InventoryS2CPacket.Status.UPDATING,
			List.of(entry("overflow", 1L, 1L))));

		assertTrue(session.stats().boundRejects() > rejectsBefore);
		assertEquals(256, session.preview(requestId).entries().size());

		session.accept(previewData(requestId, 1L, 1L, 3L, 0, 1, true, InventoryS2CPacket.Status.READY,
			List.of()));

		ClientInventory.Preview preview = session.preview(requestId);
		assertFalse(preview.completeScan(), "a bound rejection keeps the scan incomplete");
		assertEquals(InventoryS2CPacket.Status.INCOMPLETE, preview.status());
	}

	@Test
	void trackingMetadataPublishesOnlyOnCommit() {
		offer();
		session.accept(snapshot(0, 2, List.of(entry("a", 3L, 1L))));

		assertTrue(session.tracking(markerId).entries().isEmpty(), "a partial assembly stays hidden");
		assertFalse(session.tracking(markerId).complete());

		session.accept(snapshot(1, 2, List.of(entry("b", 5L, 1L))));

		ClientInventory.Tracking tracking = session.tracking(markerId);
		assertTrue(tracking.complete());
		assertEquals(2, tracking.entries().size());
		assertEquals(3L, tracking.entries().get(0).count());
		assertEquals("minecraft:stone", tracking.entries().get(0).itemId());
		assertFalse(tracking.grey());
	}

	@Test
	void heartbeatComparesOnlyTheClosedCommittedWatermark() {
		commitTwoEntries();
		long checksum = InventoryChecksums.builder()
			.add("a", "minecraft:stone", 3L, null, false)
			.add("b", "minecraft:stone", 5L, null, false)
			.checksum();
		sent.clear();

		session.accept(heartbeat(checksum, WATERMARK));
		assertTrue(sent.isEmpty(), "a matching closed digest needs no repair");

		session.accept(heartbeat(checksum + 1L, WATERMARK + 1L));
		assertTrue(sent.isEmpty(), "a heartbeat ahead of the committed watermark is not a mismatch");

		session.accept(heartbeat(checksum + 1L, WATERMARK));
		assertEquals(1, sent.size());
		assertEquals(InventoryC2SPacket.Kind.RESYNC,
			assertInstanceOf(InventoryC2SPacket.class, sent.get(0)).kind());

		session.accept(heartbeat(checksum + 1L, WATERMARK));
		assertEquals(1, sent.size(), "the repair cooldown throttles repeated resyncs");
	}

	@Test
	void unknownBaselineStreamIsBufferedAndExpiresIntoRepair() {
		offer();
		sent.clear();

		session.accept(data(InventoryS2CPacket.Kind.STREAM, 9L, 1L, 0, 1, WATERMARK, List.of(entry("a", 1L, 1L))));

		assertEquals(1, session.stats().unknownSessions());
		assertTrue(session.tracking(markerId).entries().isEmpty(), "an unknown baseline never publishes");

		for (int i = 0; i < WINDOW; i++)
			session.tick(true);

		assertEquals(0, session.stats().unknownSessions());
		assertEquals(1L, session.stats().expiredUnknownSessions());
		assertEquals(1, sent.size());
		InventoryC2SPacket resync = assertInstanceOf(InventoryC2SPacket.class, sent.get(0));
		assertEquals(InventoryC2SPacket.Kind.RESYNC, resync.kind());
		assertEquals(markerId, resync.markerId());
	}

	@Test
	void unknownBaselineFlushesWhenTheSnapshotStarts() {
		offer();
		session.accept(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 1, 2, WATERMARK,
			List.of(entry("b", 5L, 1L))));
		assertEquals(1, session.stats().unknownSessions());

		session.accept(snapshot(0, 2, List.of(entry("a", 3L, 1L))));

		ClientInventory.Tracking tracking = session.tracking(markerId);
		assertTrue(tracking.complete(), "the buffered fragment completes the baseline");
		assertEquals(2, tracking.entries().size());
		assertEquals(0, session.stats().unknownSessions());
	}

	@Test
	void invalidStatusRetainsGreyValuesAndNewerBaselineClears() {
		commitTwoEntries();

		session.accept(InventoryS2CPacket.status(EPOCH, 1L, markerId, BASELINE, REVISION + 1L, WATERMARK,
			InventoryS2CPacket.Status.INVALID, 0L));

		ClientInventory.Tracking invalid = session.tracking(markerId);
		assertTrue(invalid.grey());
		assertEquals(2, invalid.entries().size(), "grey keeps the last values");

		session.accept(InventoryS2CPacket.status(EPOCH, 1L, markerId, 9L, REVISION + 2L, WATERMARK,
			InventoryS2CPacket.Status.READY, 0L));

		ClientInventory.Tracking recovered = session.tracking(markerId);
		assertFalse(recovered.grey());
		assertTrue(recovered.entries().isEmpty(), "a recovered baseline clears old values");
	}

	@Test
	void expiredTombstoneFencesLateData() {
		commitTwoEntries();

		session.accept(InventoryS2CPacket.status(EPOCH, 1L, markerId, BASELINE, REVISION + 1L, WATERMARK,
			InventoryS2CPacket.Status.EXPIRED, 0L));
		assertTrue(session.tracking(markerId).entries().isEmpty());

		session.accept(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, WATERMARK,
			List.of(entry("a", 99L, 9L))));

		assertEquals(0, session.stats().trackedChannels(), "an expired marker is not recreated by late data");
		assertTrue(session.stats().fencedPackets() > 0L);
	}

	@Test
	void resetClearsAllConnectionState() {
		commitTwoEntries();
		session.open(target());
		assertTrue(session.stats().previewChannels() > 0);

		session.reset();

		assertFalse(session.ready());
		assertEquals(0, session.stats().previewChannels());
		assertEquals(0, session.stats().trackedChannels());
		assertEquals(0, session.stats().unknownSessions());
		assertTrue(session.tracking(markerId).entries().isEmpty());
	}

	@Test
	void corruptPacketsAndWrongEpochsChangeNothing() {
		offer();
		session.accept(new InventoryS2CPacket());
		session.accept(InventoryS2CPacket.offer(EPOCH + 1L, settings()));
		session.accept(InventoryS2CPacket.status(EPOCH + 1L, 1L, markerId, BASELINE, REVISION, WATERMARK,
			InventoryS2CPacket.Status.READY, 0L));

		assertEquals(1, session.stats().invalidPackets());
		assertEquals(1L, session.stats().staleEpochPackets(), "a second offer is ignored without an epoch error");
		assertEquals(0, session.stats().trackedChannels());
	}
}
