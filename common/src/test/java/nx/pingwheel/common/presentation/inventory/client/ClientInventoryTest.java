package nx.pingwheel.common.presentation.inventory.client;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
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
	private final V2Fixture session = new V2Fixture();
	private final MarkerId markerId = new MarkerId(3L);

	/** Existing delivery regressions run under an explicitly authorized v2 presentation fixture. */
	private final class V2Fixture {
		final ClientInventory client = new ClientInventory(sent::add);
		long open(Target target) {
			client.presentationReset(100, 1);
			return client.open((Target.BlockTarget) target, nx.pingwheel.common.domain.BlockFace.NORTH, "entity_block");
		}
		void accept(InventoryS2CPacket packet) {
			client.accept(packet.stamp(100, 1));
			if (packet.kind() == InventoryS2CPacket.Kind.OFFER && packet.epoch() == EPOCH) {
				client.presentationReset(100, 1);
				client.accept(InventoryS2CPacket.policy(EPOCH, 100, 1, java.util.Set.of("entity_block")));
				for (long id : new long[] {3, 8}) client.markerCreated(new nx.pingwheel.common.marker.MarkerSnapshot(new MarkerId(id), new java.util.UUID(1, 1),
					target(), "entity_block", "attention", new nx.pingwheel.common.marker.MarkerAnchor(1, 2, 3), 0, 10000));
			}
		}
		void tick(boolean connected) { client.tick(connected); }
		void reset() { client.reset(); }
		boolean ready() { return client.ready(); }
		boolean helloPending() { return client.helloPending(); }
		long epoch() { return client.epoch(); }
		ClientInventory.Stats stats() { return client.stats(); }
		ClientInventory.Tracking tracking(MarkerId id) { return client.tracking(id); }
		ClientInventory.Preview preview(long request) { return client.preview(request); }
		boolean select(long request, String key, String item, String type) { return client.select(request, key, item, type); }
	}

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

	private void receive(InventoryS2CPacket packet) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buffer);
			InventoryS2CPacket decoded = new InventoryS2CPacket(buffer);
			assertFalse(decoded.isCorrupt(), "the regression stimulus is an actual valid inventory frame");
			assertFalse(buffer.isReadable(), "the decoder consumes the whole frame");
			session.accept(decoded);
		} finally {
			buffer.release();
		}
	}

	private static long checksum(InventoryS2CPacket.Entry... entries) {
		InventoryChecksums.Builder builder = InventoryChecksums.builder();
		for (InventoryS2CPacket.Entry entry : entries)
			builder.add(entry.key(), entry.itemId(), entry.count(),
				entry.quality() == null ? null : entry.quality().name(), entry.fallback());
		return builder.checksum();
	}

	private ClientInventory.EntryView trackedEntry(String key) {
		return session.tracking(markerId).entries().stream().filter(view -> view.key().equals(key))
			.findFirst().orElseThrow();
	}

	private void elapseRepairCooldown() {
		for (long i = 0; i < WINDOW; i++)
			session.tick(true);
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
		session.accept(previewData(requestId, 1, 1, 1, 0, 1, false, InventoryS2CPacket.Status.UPDATING, List.of(entry("a", 1, 1))));
		assertEquals(ClientInventory.DispatchOutcome.SENT, session.client.select(session.client.selectable(requestId).getFirst(), "attention", packet -> {
			sent.add(packet); return ClientInventory.DispatchOutcome.SENT;
		}));
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
	void equalFenceInvalidationIsFencedAndKeepsValidTracking() {
		commitTwoEntries();
		long fencedBefore = session.stats().fencedPackets();

		session.accept(InventoryS2CPacket.status(EPOCH, 1L, markerId, BASELINE, REVISION, WATERMARK,
			InventoryS2CPacket.Status.INVALID, 0L));

		ClientInventory.Tracking tracking = session.tracking(markerId);
		assertFalse(tracking.grey(), "an equal-fence invalid is refused by the store fence");
		assertEquals(InventoryS2CPacket.Status.READY, tracking.status());
		assertEquals(2, tracking.entries().size(), "committed values stay valid");
		assertTrue(session.stats().fencedPackets() > fencedBefore);
	}

	@Test
	void newerInvalidationDropsBufferedOldStateAndBlocksOldData() {
		commitTwoEntries();
		session.accept(data(InventoryS2CPacket.Kind.STREAM, 9L, REVISION, 0, 1, WATERMARK,
			List.of(entry("x", 1L, 1L))));
		assertEquals(1, session.stats().unknownSessions());
		long fencedBefore = session.stats().fencedPackets();

		session.accept(InventoryS2CPacket.status(EPOCH, 1L, markerId, BASELINE, REVISION + 1L, WATERMARK,
			InventoryS2CPacket.Status.INVALID, 0L));

		ClientInventory.Tracking invalid = session.tracking(markerId);
		assertTrue(invalid.grey());
		assertEquals(2, invalid.entries().size(), "the last committed values stay visible grey");
		assertEquals(0, session.stats().unknownSessions(), "an accepted invalidation drops old unknown streams");

		session.accept(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, WATERMARK,
			List.of(entry("a", 99L, 9L))));

		ClientInventory.Tracking after = session.tracking(markerId);
		assertEquals(2, after.entries().size(), "old valid data cannot revive an invalidated channel");
		assertTrue(session.stats().fencedPackets() > fencedBefore);
	}

	@Test
	void invalidationRetiresOldBuffersButPreservesReorderedRecoveryAndOtherMarker() {
		commitTwoEntries();
		MarkerId other = new MarkerId(8L);
		receive(data(InventoryS2CPacket.Kind.STREAM, 8L, REVISION, 0, 1, WATERMARK,
			List.of(entry("old", 99L, 1L))));
		receive(data(InventoryS2CPacket.Kind.STREAM, 9L, 3L, 1, 2, 1L,
			List.of(entry("recovered-b", 6L, 0L))));
		receive(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, EPOCH, 2L, other, 12L, 1L, 2L,
			1, 2, true, InventoryS2CPacket.Status.READY, 0L, List.of(entry("other-b", 8L, 0L))));
		assertEquals(3, session.stats().unknownSessions());
		sent.clear();

		receive(InventoryS2CPacket.status(EPOCH, 1L, markerId, BASELINE, 2L, 99L,
			InventoryS2CPacket.Status.INVALID, 0L));
		assertEquals(2, session.stats().unknownSessions(), "only <= invalidation-fence buffers retire");
		assertEquals(WATERMARK, session.tracking(markerId).watermark(), "control is not a delivered cut");
		assertTrue(session.tracking(markerId).grey());

		receive(InventoryS2CPacket.status(EPOCH, 1L, markerId, 9L, 3L, 100L,
			InventoryS2CPacket.Status.READY, 0L));
		assertEquals(0L, session.tracking(markerId).watermark(), "a clean baseline resets the old cut");
		receive(data(InventoryS2CPacket.Kind.SNAPSHOT, 9L, 3L, 0, 2, 1L,
			List.of(entry("recovered-a", 4L, 0L))));

		ClientInventory.Tracking recovered = session.tracking(markerId);
		assertTrue(recovered.complete(), "the future fragment buffered before invalidation completes recovery");
		assertFalse(recovered.grey());
		assertEquals(1L, recovered.watermark());
		assertEquals(List.of("recovered-a", "recovered-b"),
			recovered.entries().stream().map(ClientInventory.EntryView::key).toList());
		assertEquals(1, session.stats().unknownSessions(), "A's recovery never drains B's buffer");

		receive(InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, EPOCH, 2L, other, 12L, 1L, 2L,
			0, 2, true, InventoryS2CPacket.Status.READY, 0L, List.of(entry("other-a", 7L, 0L))));
		assertTrue(session.tracking(other).complete());
		assertEquals(2, session.tracking(other).entries().size());
		assertEquals(0, session.stats().unknownSessions());
		assertTrue(sent.isEmpty(), "valid reorderings require neither timeout nor repair");
	}

	@Test
	void rejectedBaselinePartCannotPoisonMetadataStatusOrCut() {
		offer();
		receive(snapshot(0, 2, List.of(entry("a", 3L, 1L))));
		InventoryS2CPacket.Entry poison = new InventoryS2CPacket.Entry("a", "minecraft:dirt", "poison",
			"{}", 99L, 9L, true, InventoryS2CPacket.Status.COMPONENT_TOO_LONG);
		long rejects = session.stats().boundRejects();
		receive(InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, EPOCH, 1L, markerId, BASELINE,
			REVISION, WATERMARK, 0, 2, true, InventoryS2CPacket.Status.UNCERTAIN, 0L, List.of(poison)));
		receive(data(InventoryS2CPacket.Kind.SNAPSHOT, BASELINE, REVISION, 1, 2, WATERMARK,
			List.of(poison)));
		receive(data(InventoryS2CPacket.Kind.SNAPSHOT, BASELINE, REVISION, 1, 2, WATERMARK + 20L,
			List.of(entry("b", 99L, 9L))));
		assertFalse(session.tracking(markerId).complete());
		assertEquals(0L, session.tracking(markerId).watermark());
		assertTrue(session.tracking(markerId).entries().isEmpty());
		assertTrue(session.stats().boundRejects() >= rejects + 2L);

		receive(snapshot(1, 2, List.of(entry("b", 5L, 1L))));
		assertEquals("minecraft:stone", trackedEntry("a").itemId());
		assertEquals("a", trackedEntry("a").label());
		assertNull(trackedEntry("a").displayJson());
		assertFalse(trackedEntry("a").fallback());
		assertNull(trackedEntry("a").quality());
		assertEquals(3L, trackedEntry("a").count());
		assertEquals(InventoryS2CPacket.Status.READY, session.tracking(markerId).status());
		assertEquals(WATERMARK, session.tracking(markerId).watermark());
		elapseRepairCooldown();
		sent.clear();
		long expected = checksum(entry("a", 3L, 1L), entry("b", 5L, 1L));
		receive(heartbeat(expected, WATERMARK));
		assertTrue(sent.isEmpty(), "the digest uses only the metadata and quantities of accepted fragments");
		receive(heartbeat(expected + 1L, WATERMARK));
		assertEquals(1, sent.size(), "a mismatch at this cut can repair: the positive assertion is not cooldown-masked");
		assertEquals(InventoryC2SPacket.Kind.RESYNC,
			assertInstanceOf(InventoryC2SPacket.class, sent.get(0)).kind());
	}

	@Test
	void acceptedSameKeyRevisionOwnsMetadataAndQuantityTogether() {
		commitTwoEntries();
		InventoryS2CPacket.Entry fresh = new InventoryS2CPacket.Entry("a", "minecraft:dirt", "fresh", "{}",
			7L, 3L, true, InventoryS2CPacket.Status.COMPONENT_TOO_LONG);
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, 7L, List.of(fresh)));
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, 6L,
			List.of(entry("a", 90L, 2L), entry("b", 8L, 2L))));
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, 8L,
			List.of(entry("a", 91L, 3L))));

		assertEquals(7L, trackedEntry("a").count());
		assertEquals("minecraft:dirt", trackedEntry("a").itemId());
		assertEquals("fresh", trackedEntry("a").label());
		assertEquals("{}", trackedEntry("a").displayJson());
		assertTrue(trackedEntry("a").fallback());
		assertEquals(InventoryS2CPacket.Status.COMPONENT_TOO_LONG, trackedEntry("a").quality());
		assertEquals(8L, trackedEntry("b").count(), "a late different key is not packet-global stale");
		sent.clear();
		receive(heartbeat(checksum(fresh, entry("b", 8L, 2L)), 8L));
		assertTrue(sent.isEmpty(), "stale/equal item revisions cannot contaminate the closed digest");
	}

	@Test
	void multipartStreamClosesOnlyEveryAcceptedDistinctPartAndRechecksDeferredHeartbeat() {
		commitTwoEntries();
		sent.clear();
		InventoryS2CPacket part = data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 1, 2, 6L,
			List.of(entry("b", 8L, 2L)));
		receive(part);
		receive(part);
		assertEquals(8L, trackedEntry("b").count(), "tracking applies accepted parts immediately per key");
		assertEquals(WATERMARK, session.tracking(markerId).watermark(), "duplicate indexes never close the cut");
		long expected = checksum(entry("a", 9L, 2L), entry("b", 8L, 2L));
		receive(heartbeat(expected + 1L, 6L));
		assertTrue(sent.isEmpty(), "partial state is not compared to the heartbeat's future cut");
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 2, 6L,
			List.of(entry("a", 9L, 2L))));
		assertEquals(6L, session.tracking(markerId).watermark());
		assertEquals(1, sent.size(), "the deferred mismatch is tested as soon as the cut closes");
		assertEquals(InventoryC2SPacket.Kind.RESYNC,
			assertInstanceOf(InventoryC2SPacket.class, sent.get(0)).kind());
	}

	@Test
	void conflictingStreamIndexCannotMutateMetadataStatusOrCloseBarrier() {
		commitTwoEntries();
		sent.clear();
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 2, 6L,
			List.of(entry("a", 6L, 2L))));
		InventoryS2CPacket.Entry poison = new InventoryS2CPacket.Entry("a", "minecraft:dirt", "bad", null,
			99L, 9L, true, InventoryS2CPacket.Status.COMPONENT_TOO_LONG);
		receive(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, EPOCH, 1L, markerId, BASELINE,
			REVISION, 6L, 0, 2, true, InventoryS2CPacket.Status.UNCERTAIN, 0L, List.of(poison)));
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 1, 3, 6L,
			List.of(entry("b", 99L, 9L))));
		assertEquals(6L, trackedEntry("a").count());
		assertEquals("minecraft:stone", trackedEntry("a").itemId());
		assertEquals(5L, trackedEntry("b").count());
		assertEquals(InventoryS2CPacket.Status.READY, session.tracking(markerId).status());
		assertEquals(WATERMARK, session.tracking(markerId).watermark());
		elapseRepairCooldown();
		sent.clear();
		long expected = checksum(entry("a", 6L, 2L), entry("b", 7L, 2L));
		receive(heartbeat(expected, 6L));
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 1, 2, 6L,
			List.of(entry("b", 7L, 2L))));
		assertEquals(6L, session.tracking(markerId).watermark());
		assertTrue(sent.isEmpty(), "conflicting content was neither applied nor used by the digest");
		receive(heartbeat(expected + 1L, 6L));
		assertEquals(1, sent.size(), "a mismatch still repairs after the accepted parts close, without cooldown masking");
		assertEquals(InventoryC2SPacket.Kind.RESYNC,
			assertInstanceOf(InventoryC2SPacket.class, sent.get(0)).kind());
	}

	@Test
	void interleavedDeliveriesKeepLivePerKeyRevisionsSeparateFromClosedDigest() {
		commitTwoEntries();
		sent.clear();
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 2, 6L,
			List.of(entry("a", 6L, 2L))));
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, 7L,
			List.of(entry("a", 7L, 3L))));
		assertEquals(7L, trackedEntry("a").count(), "newer live per-key state is not stalled by another cut");
		assertEquals(WATERMARK, session.tracking(markerId).watermark());
		receive(heartbeat(checksum(entry("a", 6L, 2L), entry("b", 6L, 2L)), 6L));
		receive(heartbeat(checksum(entry("a", 7L, 3L), entry("b", 6L, 2L)), 7L));
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 1, 2, 6L,
			List.of(entry("b", 6L, 2L))));
		assertEquals(7L, session.tracking(markerId).watermark());
		assertEquals(7L, trackedEntry("a").count());
		assertEquals(6L, trackedEntry("b").count(), "a late distinct key is still accepted");
		assertTrue(sent.isEmpty(), "neither closed-cut checksum sees the other cut's newer value");
	}

	@Test
	void baselineHeartbeatWaitsForLastPartAndAssemblySurvivesDuplicateStatusBeyondUnknownWindow() {
		offer();
		receive(snapshot(0, 2, List.of(entry("a", 3L, 1L))));
		receive(InventoryS2CPacket.status(EPOCH, 1L, markerId, BASELINE, REVISION, 99L,
			InventoryS2CPacket.Status.UPDATING, 0L));
		receive(InventoryS2CPacket.status(EPOCH, 1L, markerId, BASELINE, REVISION, 99L,
			InventoryS2CPacket.Status.UPDATING, 0L));
		receive(heartbeat(checksum(entry("a", 3L, 1L), entry("b", 5L, 1L)) + 1L, WATERMARK));
		for (int i = 0; i < WINDOW * 2L; i++)
			session.tick(true);
		assertTrue(sent.isEmpty(), "admitted assembly is not an unknown-buffer timeout");
		assertFalse(session.tracking(markerId).complete());
		assertEquals(0L, session.tracking(markerId).watermark());
		receive(snapshot(1, 2, List.of(entry("b", 5L, 1L))));
		assertTrue(session.tracking(markerId).complete());
		assertEquals(WATERMARK, session.tracking(markerId).watermark());
		assertEquals(1, sent.size(), "the deferred baseline heartbeat is compared after completion");
	}

	private static List<InventoryS2CPacket.Entry> largeMetadataPart(int part) {
		return largeMetadataPart(part, 3990);
	}

	private static List<InventoryS2CPacket.Entry> largeMetadataPart(int part, int displayLength) {
		List<InventoryS2CPacket.Entry> entries = new ArrayList<>();
		for (int i = part * 8; i < (part + 1) * 8; i++)
			entries.add(new InventoryS2CPacket.Entry("k" + i, "minecraft:stone", "", "d".repeat(displayLength),
				1L, 1L, false, null));
		if (part == 7)
			entries.add(new InventoryS2CPacket.Entry("small", "minecraft:stone", "", null, 1L, 1L, false, null));
		return entries;
	}

	@Test
	void trackingReplacementGrowthIsRejectedBeforeQuantityMetadataAndCutMutation() {
		offer();
		for (int part = 0; part < 8; part++)
			receive(snapshot(part, 8, largeMetadataPart(part)));
		assertTrue(session.tracking(markerId).complete());
		assertEquals(65, session.tracking(markerId).entries().size());
		long rejects = session.stats().boundRejects();
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, 6L,
			List.of(new InventoryS2CPacket.Entry("small", "minecraft:stone", "", "d".repeat(3990),
				99L, 2L, false, null))));
		assertTrue(session.stats().boundRejects() > rejects);
		assertEquals(1L, trackedEntry("small").count(), "byte admission precedes numeric acceptance");
		assertNull(trackedEntry("small").displayJson());
		assertEquals(WATERMARK, session.tracking(markerId).watermark());
		sent.clear();
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, 6L,
			List.of(new InventoryS2CPacket.Entry("small", "minecraft:stone", "", null, 9L, 2L, false, null))));
		assertEquals(9L, trackedEntry("small").count(), "rejected growth did not consume the item revision");
		assertEquals(6L, session.tracking(markerId).watermark(), "the fitting retry closes normally");
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, 7L,
			List.of(entry("extra", 2L, 1L))));
		assertEquals(66, session.tracking(markerId).entries().size(), "retained-byte accounting still admits room");
	}

	@Test
	void baselineMetadataOverageRejectsWholePartAndFittingRetryCompletes() {
		offer();
		for (int part = 0; part < 7; part++)
			receive(snapshot(part, 8, largeMetadataPart(part, 4030)));
		receive(snapshot(7, 8, largeMetadataPart(7, 4010)));
		assertFalse(session.tracking(markerId).complete());
		assertTrue(session.tracking(markerId).entries().isEmpty());
		assertEquals(0L, session.tracking(markerId).watermark());
		receive(snapshot(7, 8, largeMetadataPart(7, 3820)));
		assertTrue(session.tracking(markerId).complete(), "byte rejection did not consume the last part index");
		assertEquals(65, session.tracking(markerId).entries().size());
		assertEquals(1L, trackedEntry("small").count());
		assertNull(trackedEntry("small").displayJson());
	}

	@Test
	void unknownLaterDeliveryAfterBaselineCommitIsAppliedRatherThanDropped() {
		offer();
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, 6L,
			List.of(entry("a", 8L, 2L))));
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 1, 2, WATERMARK,
			List.of(entry("b", 5L, 1L))));
		receive(snapshot(0, 2, List.of(entry("a", 3L, 1L))));
		assertTrue(session.tracking(markerId).complete());
		assertEquals(8L, trackedEntry("a").count());
		assertEquals(5L, trackedEntry("b").count());
		assertEquals(6L, session.tracking(markerId).watermark());
		assertEquals(0, session.stats().unknownSessions());
	}

	@Test
	void trackedReplayGuardsDoNotStarveLongSequenceOfServiceableDeliveries() {
		commitTwoEntries();
		sent.clear();
		for (long watermark = 6L; watermark < 50L; watermark++) {
			receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, watermark,
				List.of(entry("a", watermark, watermark))));
			receive(heartbeat(checksum(entry("a", watermark, watermark), entry("b", 5L, 1L)), watermark));
		}
		assertEquals(49L, session.tracking(markerId).watermark());
		assertEquals(49L, trackedEntry("a").count());
		assertEquals(0L, session.stats().boundRejects());
		assertTrue(sent.isEmpty(), "bounded replay history is retired without blocking new deliveries");
	}

	@Test
	void evictedSnapshotReplayCannotReopenItsClosedBarrierOrSuppressRepair() {
		assertEvictedReplayCannotReopenBarrier(InventoryS2CPacket.Kind.SNAPSHOT);
	}

	@Test
	void evictedMultipartStreamReplayCannotReopenItsClosedBarrierOrSuppressRepair() {
		assertEvictedReplayCannotReopenBarrier(InventoryS2CPacket.Kind.STREAM);
	}

	private void assertEvictedReplayCannotReopenBarrier(InventoryS2CPacket.Kind kind) {
		commitTwoEntries();
		InventoryS2CPacket replay;
		InventoryS2CPacket.Entry lastB;
		long first;
		if (kind == InventoryS2CPacket.Kind.SNAPSHOT) {
			replay = snapshot(0, 2, List.of(entry("a", 3L, 1L)));
			lastB = entry("b", 5L, 1L);
			first = 6L;
		} else {
			replay = data(kind, BASELINE, REVISION, 0, 2, 6L, List.of(entry("a", 6L, 2L)));
			lastB = entry("b", 6L, 2L);
			receive(replay);
			receive(data(kind, BASELINE, REVISION, 1, 2, 6L, List.of(lastB)));
			first = 7L;
		}
		// Eight newer completed deliveries evict every retained part of the replay's
		// delivery. The completion fence must outlive the bounded replay history.
		long closed = first + 7L;
		for (long watermark = first; watermark <= closed; watermark++)
			receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, watermark,
				List.of(entry("a", watermark, watermark))));
		sent.clear();
		receive(replay);
		assertEquals(closed, session.tracking(markerId).watermark());
		assertEquals(closed, trackedEntry("a").count());
		receive(heartbeat(checksum(entry("a", closed, closed), lastB), closed));
		assertTrue(sent.isEmpty(), "a stale replay cannot contaminate the current digest");
		receive(heartbeat(checksum(entry("a", closed, closed), lastB) + 1L, closed));
		assertEquals(1, sent.size(), "evicted old fragments cannot defer a mismatch at the already closed cut");
		assertEquals(InventoryC2SPacket.Kind.RESYNC,
			assertInstanceOf(InventoryC2SPacket.class, sent.get(0)).kind());

		elapseRepairCooldown();
		sent.clear();
		long next = closed + 1L;
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, next,
			List.of(entry("a", next, next))));
		assertEquals(next, session.tracking(markerId).watermark(), "the higher completed delivery cannot starve");
		receive(heartbeat(checksum(entry("a", next, next), lastB), next));
		assertTrue(sent.isEmpty(), "the new closed-cut digest includes no replay rollback");
		receive(heartbeat(checksum(entry("a", next, next), lastB) + 1L, next));
		assertEquals(1, sent.size(), "repair remains enabled at the higher cut after the cooldown");
		assertEquals(0L, session.stats().boundRejects(), "benign old replays allocate no blocking delivery");
	}

	@Test
	void lateHistoricalMultipartKeyCoalescesWithoutReopeningClosedCut() {
		commitTwoEntries();
		for (long watermark = 7L; watermark <= 14L; watermark++)
			receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, watermark,
				List.of(entry("a", watermark, watermark))));
		sent.clear();
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 2, 15L,
			List.of(entry("a", 15L, 15L))));
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 2, 6L,
			List.of(entry("a", 90L, 2L), entry("b", 8L, 2L))));
		assertEquals(15L, trackedEntry("a").count(), "a stale historical key does not roll back a newer live revision");
		assertEquals(8L, trackedEntry("b").count(), "a lower watermark does not discard an independent newer key");
		assertEquals(14L, session.tracking(markerId).watermark(), "historical partial delivery never reopens the cut");
		receive(heartbeat(checksum(entry("a", 14L, 14L), entry("b", 8L, 2L)), 14L));
		assertTrue(sent.isEmpty(), "the late absolute value coalesces without importing the newer open cut's value");

		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 1, 2, 15L, List.of()));
		assertEquals(15L, session.tracking(markerId).watermark(), "the absent historical part cannot starve new cuts");
		receive(heartbeat(checksum(entry("a", 15L, 15L), entry("b", 8L, 2L)), 15L));
		assertTrue(sent.isEmpty());
		receive(heartbeat(checksum(entry("a", 15L, 15L), entry("b", 8L, 2L)) + 1L, 15L));
		assertEquals(1, sent.size(), "any missing historical contribution is repairable, not a permanent deferral");
	}

	@Test
	void resetCanReinstallTheSameMarkerAndFenceWithoutRetainedStoreState() {
		commitTwoEntries();
		session.reset();
		offer();
		receive(snapshot(0, 1, List.of(entry("a", 8L, 1L))));
		assertTrue(session.tracking(markerId).complete());
		assertEquals(8L, trackedEntry("a").count());
	}

	@Test
	void pendingFutureBaselineSurvivesOlderInvalidationAndRecoveryCannotReadmitOldBaseline() {
		commitTwoEntries();
		receive(data(InventoryS2CPacket.Kind.SNAPSHOT, 9L, 3L, 0, 2, 1L,
			List.of(entry("new-a", 3L, 0L))));
		receive(InventoryS2CPacket.status(EPOCH, 1L, markerId, BASELINE, 2L, 99L,
			InventoryS2CPacket.Status.INVALID, 0L));
		assertFalse(session.tracking(markerId).grey(), "an admitted future fence is not canceled by old control");
		receive(InventoryS2CPacket.status(EPOCH, 1L, markerId, BASELINE, REVISION, 99L,
			InventoryS2CPacket.Status.UNCERTAIN, 0L));
		assertEquals(InventoryS2CPacket.Status.READY, session.tracking(markerId).status(),
			"an old matching-committed control cannot evade the stronger pending fence");
		receive(data(InventoryS2CPacket.Kind.SNAPSHOT, 9L, 3L, 1, 2, 1L,
			List.of(entry("new-b", 4L, 0L))));
		assertTrue(session.tracking(markerId).complete());
		assertEquals(9L, session.tracking(markerId).baselineId());
		assertEquals(1L, session.tracking(markerId).watermark());
		receive(data(InventoryS2CPacket.Kind.STREAM, 8L, 3L, 0, 1, 2L,
			List.of(entry("old", 99L, 0L))));
		assertEquals(0, session.stats().unknownSessions(), "retired baselines cannot consume the unknown buffer");
		assertEquals(2, session.tracking(markerId).entries().size());
	}

	@Test
	void openDeliveryBoundsRejectWithoutConsumingPartsAndAdmittedWorkStillCompletes() {
		commitTwoEntries();
		for (long watermark = 6L; watermark < 14L; watermark++)
			receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 2, watermark,
				List.of(entry("a", watermark, watermark))));
		long rejects = session.stats().boundRejects();
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, 14L,
			List.of(entry("a", 14L, 14L))));
		assertTrue(session.stats().boundRejects() > rejects);
		assertEquals(13L, trackedEntry("a").count(), "a rejected ninth delivery changes no live state");
		assertEquals(WATERMARK, session.tracking(markerId).watermark());
		for (long watermark = 6L; watermark < 14L; watermark++)
			receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 1, 2, watermark,
				List.of(entry("b", watermark, watermark))));
		assertEquals(13L, session.tracking(markerId).watermark(), "every admitted cut progresses despite overage");
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 1, 14L,
			List.of(entry("a", 14L, 14L))));
		assertEquals(14L, trackedEntry("a").count());
		assertEquals(14L, session.tracking(markerId).watermark());
	}

	@Test
	void oneMarkersOpenCutAndRepairNeverBlockOrRebaseAnotherMarker() {
		commitTwoEntries();
		MarkerId other = new MarkerId(8L);
		receive(InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, EPOCH, 2L, other, BASELINE, REVISION,
			WATERMARK, 0, 1, true, InventoryS2CPacket.Status.READY, 0L, List.of(entry("x", 4L, 1L))));
		sent.clear();
		receive(data(InventoryS2CPacket.Kind.STREAM, BASELINE, REVISION, 0, 2, 6L,
			List.of(entry("a", 6L, 2L))));
		receive(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, EPOCH, 2L, other, BASELINE, REVISION,
			6L, 0, 1, true, InventoryS2CPacket.Status.READY, 0L, List.of(entry("x", 7L, 2L))));
		receive(InventoryS2CPacket.heartbeat(EPOCH, 2L, other, BASELINE, REVISION, 6L,
			checksum(entry("x", 7L, 2L)) + 1L));
		assertEquals(1, sent.size());
		InventoryC2SPacket repair = assertInstanceOf(InventoryC2SPacket.class, sent.get(0));
		assertEquals(other, repair.markerId());
		assertEquals(0L, repair.requestId(), "tracking scope is independent of any preview request");
		assertEquals(6L, session.tracking(other).watermark());
		assertEquals(WATERMARK, session.tracking(markerId).watermark());
		receive(InventoryS2CPacket.status(EPOCH, 1L, markerId, BASELINE, 2L, 6L,
			InventoryS2CPacket.Status.INVALID, 0L));
		assertTrue(session.tracking(markerId).grey());
		assertFalse(session.tracking(other).grey());
		assertEquals(7L, session.tracking(other).entries().get(0).count());
		assertEquals(6L, session.tracking(other).watermark());
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
