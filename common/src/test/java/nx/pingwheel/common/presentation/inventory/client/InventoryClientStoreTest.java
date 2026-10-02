package nx.pingwheel.common.presentation.inventory.client;

import java.util.LinkedHashMap;
import java.util.Map;

import nx.pingwheel.common.presentation.inventory.client.InventoryClientStore.Channel;
import nx.pingwheel.common.presentation.inventory.client.InventoryClientStore.Outcome;
import nx.pingwheel.common.presentation.inventory.client.InventoryClientStore.PartOutcome;
import nx.pingwheel.common.presentation.inventory.client.InventoryClientStore.Value;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryClientStoreTest {

	private static Value value(long count, long revision) {
		return new Value(count, revision, "valid");
	}

	private static Map<String, Value> map(String key, Value value) {
		return Map.of(key, value);
	}

	@Test
	void applyUpdatesOnlyKeysWithNewerItemRevision() {
		var store = new InventoryClientStore(4, 16);
		var channel = Channel.tracked(1L);
		store.snapshot(channel, 7L, 1L, Map.of("a", value(5L, 1L), "b", value(7L, 1L)));

		var outcome = store.apply(channel, 7L, 1L, Map.of("a", value(9L, 0L), "b", value(3L, 2L)));

		assertEquals(Outcome.APPLIED, outcome);
		assertEquals(5L, store.values(channel).get("a").count(), "an older revision for one key does not apply");
		assertEquals(3L, store.values(channel).get("b").count(), "a newer revision for another key still applies");
		assertEquals(Outcome.IGNORED_UNKNOWN_BASELINE, store.apply(channel, 8L, 1L, map("a", value(1L, 4L))));
		assertEquals(Outcome.IGNORED_STALE, store.apply(channel, 7L, 0L, map("a", value(1L, 4L))));
	}

	@Test
	void sparseUpdateKeepsMissingKeysAndExplicitZero() {
		var store = new InventoryClientStore(4, 16);
		var channel = Channel.tracked(1L);
		store.snapshot(channel, 7L, 1L, Map.of("a", value(5L, 1L), "b", value(7L, 1L)));

		store.apply(channel, 7L, 1L, map("a", value(5L, 2L)));
		store.apply(channel, 7L, 1L, map("b", value(7L, 2L)));
		store.apply(channel, 7L, 1L, map("a", value(0L, 3L)));

		assertEquals(0L, store.values(channel).get("a").count(), "an explicit zero stays present");
		assertEquals(7L, store.values(channel).get("b").count(), "an unlisted key stays unchanged");
		assertEquals(2, store.values(channel).size());
	}

	@Test
	void invalidStatusRetainsValuesAndBlocksResurrection() {
		var store = new InventoryClientStore(4, 16);
		var channel = Channel.tracked(1L);
		store.snapshot(channel, 7L, 1L, map("a", value(3L, 1L)));

		store.invalidate(channel, 2L);

		assertTrue(store.isInvalid(channel));
		assertEquals(3L, store.values(channel).get("a").count(), "grey values are retained");
		assertEquals(Outcome.IGNORED_INVALID, store.apply(channel, 7L, 1L, map("a", value(99L, 9L))));
		assertEquals(Outcome.IGNORED_INVALID, store.apply(channel, 7L, 2L, map("a", value(99L, 9L))));
		assertEquals(Outcome.IGNORED_INVALID, store.snapshot(channel, 8L, 2L, map("a", value(99L, 9L))));
		assertEquals(3L, store.values(channel).get("a").count(), "no valid update resurrects invalid state");
	}

	@Test
	void rebaseClearsChannelBeforeNewBaseline() {
		var store = new InventoryClientStore(4, 16);
		var channel = Channel.tracked(1L);
		store.snapshot(channel, 7L, 1L, map("a", value(3L, 1L)));
		store.invalidate(channel, 2L);

		store.rebase(channel, 8L, 3L);

		assertFalse(store.isInvalid(channel));
		assertTrue(store.values(channel).isEmpty(), "rebase clears old values before the new baseline");
		assertEquals(Outcome.IGNORED_STALE, store.apply(channel, 7L, 1L, map("a", value(4L, 4L))));
		assertEquals(Outcome.APPLIED, store.snapshot(channel, 8L, 3L, map("a", value(4L, 0L))));
		assertEquals(4L, store.values(channel).get("a").count());
	}

	@Test
	void channelsAreIndependent() {
		var store = new InventoryClientStore(4, 16);
		var preview = Channel.preview(1L);
		var tracked = Channel.tracked(2L);

		store.snapshot(preview, 5L, 1L, map("x", value(1L, 1L)));
		store.snapshot(tracked, 9L, 4L, map("x", value(2L, 1L)));
		assertEquals(Outcome.APPLIED, store.apply(preview, 5L, 1L, map("x", value(3L, 2L))));

		assertEquals(3L, store.values(preview).get("x").count());
		assertEquals(2L, store.values(tracked).get("x").count(), "another channel's update is isolated");

		store.invalidate(preview, 2L);
		assertTrue(store.isInvalid(preview));
		assertFalse(store.isInvalid(tracked));
		assertEquals(2L, store.values(tracked).get("x").count());

		store.expire(preview);
		assertFalse(store.hasChannel(preview));
		assertTrue(store.hasChannel(tracked));
		assertEquals(9L, store.baselineId(tracked));
	}

	@Test
	void partialSnapshotStaysHiddenUntilComplete() {
		var store = new InventoryClientStore(4, 16);
		var channel = Channel.tracked(1L);

		assertEquals(PartOutcome.STARTED, store.beginSnapshot(channel, 7L, 1L, 2, 0L));
		assertEquals(PartOutcome.ACCEPTED, store.part(channel, 7L, 1L, 0, map("a", value(1L, 0L)), 1L));
		assertTrue(store.assembling(channel));
		assertTrue(store.values(channel).isEmpty(), "a partial baseline is not visible");
		assertEquals(0L, store.assemblyStartTick(channel).orElseThrow());

		assertEquals(PartOutcome.DUPLICATE_PART, store.part(channel, 7L, 1L, 0, map("a", value(1L, 0L)), 2L));
		assertEquals(PartOutcome.CONFLICT_PART, store.part(channel, 7L, 1L, 0, map("a", value(2L, 0L)), 3L));
		assertEquals(Outcome.IGNORED_UNKNOWN_BASELINE, store.apply(channel, 7L, 1L, map("a", value(5L, 5L))));

		assertEquals(PartOutcome.COMMITTED, store.part(channel, 7L, 1L, 1, map("b", value(2L, 0L)), 4L));
		assertFalse(store.assembling(channel));
		assertEquals(1L, store.values(channel).get("a").count());
		assertEquals(2L, store.values(channel).get("b").count());
		assertEquals(Outcome.APPLIED, store.apply(channel, 7L, 1L, map("a", value(6L, 1L))));
		assertEquals(PartOutcome.NO_ASSEMBLY, store.part(channel, 7L, 1L, 0, map("a", value(1L, 0L)), 5L));
	}

	@Test
	void assemblyKeepsProgressingAcrossTicks() {
		var store = new InventoryClientStore(4, 16);
		var channel = Channel.tracked(1L);

		store.beginSnapshot(channel, 7L, 1L, 2, 0L);
		assertEquals(PartOutcome.ACCEPTED, store.part(channel, 7L, 1L, 0, map("a", value(1L, 0L)), 100L));
		assertTrue(store.assembling(channel), "elapsed ticks never expire a progressing assembly");
		assertEquals(PartOutcome.COMMITTED, store.part(channel, 7L, 1L, 1, map("b", value(2L, 0L)), 1_000L));
		assertEquals(1L, store.values(channel).get("a").count());
	}

	@Test
	void fragmentBoundsRejectExcessPartsAndEntries() {
		var store = new InventoryClientStore(2, 3);
		var channel = Channel.tracked(1L);

		assertEquals(PartOutcome.REJECTED_BOUND, store.beginSnapshot(channel, 7L, 1L, 3, 0L));
		assertEquals(PartOutcome.REJECTED_BOUND, store.beginSnapshot(channel, 7L, 1L, 0, 0L));
		assertEquals(PartOutcome.STARTED, store.beginSnapshot(channel, 7L, 1L, 2, 0L));
		assertEquals(PartOutcome.REJECTED_BOUND, store.part(channel, 7L, 1L, 2, map("a", value(1L, 0L)), 1L));
		assertEquals(PartOutcome.ACCEPTED,
			store.part(channel, 7L, 1L, 0, Map.of("a", value(1L, 0L), "b", value(2L, 0L)), 2L));
		assertEquals(PartOutcome.REJECTED_BOUND,
			store.part(channel, 7L, 1L, 1, Map.of("c", value(3L, 0L), "d", value(4L, 0L)), 3L));
		assertEquals(PartOutcome.COMMITTED, store.part(channel, 7L, 1L, 1, map("c", value(3L, 0L)), 4L));
	}

	@Test
	void snapshotFencesRevisionAndBaselineOrdering() {
		var store = new InventoryClientStore(4, 16);
		var channel = Channel.preview(1L);

		assertEquals(Outcome.APPLIED, store.snapshot(channel, 5L, 1L, map("a", value(1L, 0L))));
		assertEquals(Outcome.IGNORED_STALE, store.snapshot(channel, 4L, 1L, map("a", value(2L, 0L))));
		assertEquals(Outcome.IGNORED_STALE, store.snapshot(channel, 5L, 0L, map("a", value(2L, 0L))));
		assertEquals(Outcome.APPLIED, store.snapshot(channel, 6L, 1L, map("a", value(2L, 0L))));
		assertEquals(Outcome.APPLIED, store.snapshot(channel, 4L, 2L, map("a", value(3L, 0L))));
		assertEquals(3L, store.values(channel).get("a").count(), "a newer revision replaces an older baseline");
		assertEquals(4L, store.baselineId(channel));

		store.expire(channel);
		assertFalse(store.hasChannel(channel));
		assertTrue(store.values(channel).isEmpty());
		assertEquals(-1L, store.baselineId(channel));
	}

	@Test
	void inputsAreValidatedAndCopied() {
		assertThrows(IllegalArgumentException.class, () -> new InventoryClientStore(0, 1));
		assertThrows(IllegalArgumentException.class, () -> new InventoryClientStore(1, 0));
		assertThrows(IllegalArgumentException.class, () -> new Value(-1L, 0L, "valid"));
		assertThrows(IllegalArgumentException.class, () -> new Value(0L, -1L, "valid"));
		assertThrows(IllegalArgumentException.class, () -> new Value(0L, 0L, " "));

		var store = new InventoryClientStore(4, 16);
		var channel = Channel.tracked(1L);
		var incoming = new LinkedHashMap<String, Value>();
		incoming.put("a", value(1L, 0L));
		store.snapshot(channel, 7L, 1L, incoming);
		incoming.put("a", value(99L, 9L));

		assertEquals(1L, store.values(channel).get("a").count(), "the store copies the incoming map");
		assertThrows(UnsupportedOperationException.class, () -> store.values(channel).put("b", value(1L, 0L)));
		assertEquals(Outcome.IGNORED_UNKNOWN_BASELINE, store.apply(Channel.tracked(99L), 7L, 1L, Map.of()));
	}

	@Test
	void applyAndSnapshotEnforceEntryBoundBeforeMutation() {
		var store = new InventoryClientStore(4, 3L, 8);
		var channel = Channel.tracked(1L);
		store.snapshot(channel, 7L, 1L, Map.of("a", value(1L, 0L), "b", value(2L, 0L)));

		assertEquals(Outcome.REJECTED_BOUND, store.apply(channel, 7L, 1L,
			Map.of("c", value(3L, 0L), "d", value(4L, 0L))));
		assertEquals(2, store.values(channel).size(), "a rejected apply must not add keys");
		assertEquals(Outcome.APPLIED, store.apply(channel, 7L, 1L, map("a", value(5L, 1L))));
		assertEquals(Outcome.APPLIED, store.apply(channel, 7L, 1L, map("c", value(3L, 0L))));
		assertEquals(3, store.values(channel).size());
		assertEquals(Outcome.REJECTED_BOUND, store.apply(channel, 7L, 1L, map("d", value(4L, 0L))));
		assertEquals(3, store.values(channel).size(), "the bound holds across accumulated batches");

		assertEquals(Outcome.REJECTED_BOUND, store.snapshot(channel, 8L, 2L,
			Map.of("a", value(1L, 0L), "b", value(2L, 0L), "c", value(3L, 0L), "d", value(4L, 0L))));
		assertEquals(3, store.values(channel).size(), "a rejected snapshot preserves old values");
		assertEquals(7L, store.baselineId(channel));
	}

	@Test
	void channelAndStringBoundsAreFinite() {
		assertThrows(IllegalArgumentException.class, () -> Channel.preview(-1L));
		assertThrows(IllegalArgumentException.class, () -> Channel.tracked(-5L));
		assertThrows(IllegalArgumentException.class, () -> new InventoryClientStore(0, 16L, 4));
		assertThrows(IllegalArgumentException.class, () -> new InventoryClientStore(4, 0L, 4));
		assertThrows(IllegalArgumentException.class, () -> new InventoryClientStore(4, 16L, 0));

		new Value(0L, 0L, "q".repeat(64));
		assertThrows(IllegalArgumentException.class, () -> new Value(0L, 0L, "q".repeat(65)));

		var store = new InventoryClientStore(4, 16L, 1);
		var first = Channel.tracked(1L);
		var second = Channel.tracked(2L);
		assertEquals(Outcome.APPLIED, store.snapshot(first, 7L, 1L, map("k", value(1L, 0L))));
		assertEquals(Outcome.REJECTED_BOUND, store.snapshot(second, 7L, 1L, map("k", value(1L, 0L))));
		assertFalse(store.invalidate(second, 1L));
		assertFalse(store.rebase(second, 7L, 1L));
		assertFalse(store.hasChannel(second), "rejected channels are not created");

		var maxKey = "k".repeat(256);
		assertEquals(Outcome.APPLIED, store.snapshot(first, 7L, 2L, map(maxKey, value(1L, 0L))));
		assertThrows(IllegalArgumentException.class,
			() -> store.snapshot(first, 7L, 3L, map("k".repeat(257), value(1L, 0L))));
	}

	@Test
	void duplicateBeginKeepsRunningAssembly() {
		var store = new InventoryClientStore(4, 16L, 4);
		var channel = Channel.tracked(1L);

		assertEquals(PartOutcome.STARTED, store.beginSnapshot(channel, 7L, 1L, 2, 0L));
		assertEquals(PartOutcome.ACCEPTED, store.part(channel, 7L, 1L, 0, map("a", value(1L, 0L)), 1L));

		assertEquals(PartOutcome.STARTED, store.beginSnapshot(channel, 7L, 1L, 2, 99L));
		assertEquals(0L, store.assemblyStartTick(channel).orElseThrow(), "duplicate begin keeps the start tick");
		assertEquals(PartOutcome.CONFLICT, store.beginSnapshot(channel, 7L, 1L, 3, 99L));

		assertEquals(PartOutcome.COMMITTED, store.part(channel, 7L, 1L, 1, map("b", value(2L, 0L)), 2L));
		assertEquals(1L, store.values(channel).get("a").count(), "the first part survived the duplicate begin");
		assertEquals(2, store.values(channel).size());
	}

	@Test
	void oldBeginCannotReplaceFutureAssembly() {
		var store = new InventoryClientStore(4, 16L, 4);
		var channel = Channel.tracked(1L);

		assertEquals(PartOutcome.STARTED, store.beginSnapshot(channel, 999L, 1L, 2, 0L));
		assertEquals(PartOutcome.IGNORED_STALE, store.beginSnapshot(channel, 500L, 1L, 2, 1L));
		assertEquals(PartOutcome.NO_ASSEMBLY, store.part(channel, 500L, 1L, 0, map("a", value(1L, 0L)), 2L));
		assertEquals(PartOutcome.ACCEPTED, store.part(channel, 999L, 1L, 0, map("a", value(1L, 0L)), 3L));

		assertEquals(PartOutcome.STARTED, store.beginSnapshot(channel, 1000L, 1L, 2, 4L));
		assertEquals(PartOutcome.IGNORED_STALE, store.beginSnapshot(channel, 1000L, 0L, 2, 5L));

		var other = Channel.tracked(2L);
		store.snapshot(other, 900L, 1L, map("a", value(1L, 0L)));
		assertEquals(PartOutcome.IGNORED_STALE, store.beginSnapshot(other, 500L, 1L, 2, 6L));
	}

	@Test
	void invalidSameFenceRebaseCannotRevive() {
		var store = new InventoryClientStore(4, 16L, 4);
		var channel = Channel.tracked(1L);
		store.snapshot(channel, 5L, 1L, map("a", value(3L, 0L)));

		assertFalse(store.invalidate(channel, 1L), "a valid channel cannot go invalid at the same fence");
		assertTrue(store.invalidate(channel, 2L));
		assertEquals(Outcome.IGNORED_INVALID, store.apply(channel, 5L, 2L, map("a", value(4L, 1L))));

		assertFalse(store.rebase(channel, 6L, 2L), "same-fence rebase cannot revive invalid state");
		assertFalse(store.rebase(channel, 5L, 1L), "older-fence rebase is stale");
		assertTrue(store.isInvalid(channel));

		assertTrue(store.rebase(channel, 6L, 3L));
		assertFalse(store.isInvalid(channel));
		assertTrue(store.values(channel).isEmpty());
	}

	@Test
	void snapshotReplayCannotRollBackStreamedValues() {
		var store = new InventoryClientStore(4, 16L, 4);
		var channel = Channel.tracked(1L);
		store.snapshot(channel, 5L, 1L, Map.of("a", value(1L, 0L), "b", value(2L, 0L)));
		store.apply(channel, 5L, 1L, map("a", value(9L, 1L)));

		assertEquals(Outcome.APPLIED,
			store.snapshot(channel, 5L, 1L, Map.of("a", value(1L, 0L), "b", value(2L, 0L))));
		assertEquals(9L, store.values(channel).get("a").count(), "a replay cannot roll back a newer streamed value");
		assertEquals(2L, store.values(channel).get("b").count());

		assertEquals(Outcome.APPLIED, store.snapshot(channel, 5L, 1L, map("b", value(2L, 0L))));
		assertEquals(9L, store.values(channel).get("a").count(),
			"a duplicate initial snapshot does not clear omitted keys");
	}

	@Test
	void duplicateKeysAcrossPartsAreRejected() {
		var store = new InventoryClientStore(4, 16L, 4);
		var channel = Channel.tracked(1L);
		store.beginSnapshot(channel, 7L, 1L, 3, 0L);

		assertEquals(PartOutcome.ACCEPTED, store.part(channel, 7L, 1L, 0, map("a", value(1L, 0L)), 1L));
		assertEquals(PartOutcome.CONFLICT_PART, store.part(channel, 7L, 1L, 1, map("a", value(1L, 0L)), 2L));
		assertEquals(PartOutcome.CONFLICT_PART, store.part(channel, 7L, 1L, 1, map("a", value(2L, 0L)), 3L));
		assertEquals(PartOutcome.ACCEPTED, store.part(channel, 7L, 1L, 1, map("b", value(2L, 0L)), 4L));
		assertEquals(PartOutcome.COMMITTED, store.part(channel, 7L, 1L, 2, map("c", value(3L, 0L)), 5L));

		assertEquals(3, store.values(channel).size());
		assertEquals(1L, store.values(channel).get("a").count());
		assertEquals(2L, store.values(channel).get("b").count());
		assertEquals(3L, store.values(channel).get("c").count());
	}

	@Test
	void applyRequiresExactFenceAndInstalledBaseline() {
		var store = new InventoryClientStore(4, 16L, 4);
		var channel = Channel.tracked(1L);
		store.snapshot(channel, 5L, 1L, map("a", value(1L, 0L)));

		assertEquals(Outcome.IGNORED_UNKNOWN_BASELINE, store.apply(channel, 5L, 2L, map("a", value(2L, 1L))));
		assertEquals(Outcome.IGNORED_STALE, store.apply(channel, 5L, 0L, map("a", value(2L, 1L))));

		assertTrue(store.rebase(channel, 6L, 2L));
		assertEquals(Outcome.IGNORED_UNKNOWN_BASELINE, store.apply(channel, 6L, 2L, map("a", value(2L, 1L))));
		assertEquals(Outcome.APPLIED, store.snapshot(channel, 6L, 2L, map("a", value(1L, 0L))));
		assertEquals(Outcome.APPLIED, store.apply(channel, 6L, 2L, map("a", value(2L, 1L))));
		assertEquals(2L, store.values(channel).get("a").count());
	}

	@Test
	void rebaseEqualRevisionRequiresMonotonicBaseline() {
		var store = new InventoryClientStore(4, 16L, 4);
		var channel = Channel.tracked(1L);
		store.snapshot(channel, 5L, 1L, map("a", value(3L, 0L)));

		assertTrue(store.rebase(channel, 5L, 1L), "an identical rebase is a no-op success");
		assertEquals(3L, store.values(channel).get("a").count(), "identical rebase preserves verified values");
		assertFalse(store.rebase(channel, 4L, 1L), "a backward baseline at the same fence is rejected");

		assertTrue(store.rebase(channel, 6L, 1L), "a monotonic forward baseline at the same fence is a resync");
		assertTrue(store.values(channel).isEmpty());
		assertEquals(6L, store.baselineId(channel));
	}

	@Test
	void matchingRebaseKeepsAdmittedPartsAndStartTickWhileWeakerControlCannotReplaceThem() {
		var store = new InventoryClientStore(4, 16L, 4);
		var channel = Channel.tracked(1L);
		store.snapshot(channel, 5L, 1L, map("old", value(7L, 1L)));
		store.beginSnapshot(channel, 9L, 3L, 2, 10L);
		store.part(channel, 9L, 3L, 0, map("a", value(3L, 0L)), 11L);

		assertFalse(store.rebase(channel, 8L, 3L));
		assertFalse(store.rebase(channel, 9L, 2L));
		assertTrue(store.rebase(channel, 9L, 3L));
		assertTrue(store.rebase(channel, 9L, 3L));
		assertEquals(10L, store.assemblyStartTick(channel).orElseThrow());
		assertTrue(store.values(channel).isEmpty(), "new fence clears old values but not admitted parts");
		assertEquals(PartOutcome.COMMITTED,
			store.part(channel, 9L, 3L, 1, map("b", value(5L, 0L)), 1_000L));
		assertEquals(Map.of("a", value(3L, 0L), "b", value(5L, 0L)), store.values(channel));
	}

	@Test
	void invalidationCannotRetireAnAlreadyAdmittedNewerOrEqualFence() {
		var store = new InventoryClientStore(4, 16L, 4);
		var channel = Channel.tracked(1L);
		store.snapshot(channel, 5L, 1L, map("old", value(7L, 1L)));
		store.beginSnapshot(channel, 9L, 3L, 2, 10L);
		store.part(channel, 9L, 3L, 0, map("a", value(3L, 0L)), 11L);
		assertFalse(store.invalidate(channel, 2L));
		assertFalse(store.invalidate(channel, 3L));
		assertFalse(store.isInvalid(channel));
		assertEquals(PartOutcome.COMMITTED,
			store.part(channel, 9L, 3L, 1, map("b", value(5L, 0L)), 12L));
		assertTrue(store.invalidate(channel, 4L));
		assertTrue(store.isInvalid(channel));
	}
}
