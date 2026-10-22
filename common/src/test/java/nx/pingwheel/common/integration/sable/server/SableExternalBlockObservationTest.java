package nx.pingwheel.common.integration.sable.server;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableExternalBlockObservationTest {
	private static final UUID STABLE_ID = UUID.fromString("c4b31d39-2e3b-4c28-bb64-3a181df68415");
private static final UUID CURRENT_SUBLEVEL = UUID.fromString("66e0ecf2-00af-47f8-8b1e-faa82bc1d27b");

	@Test
	void committedStableIdSelectsCurrentTrackingPointPositionRatherThanAnyOldLocator() {
		var current = SableExternalBlockServerProvider.currentTrackingPosition(
			STABLE_ID.toString(), 2, STABLE_ID, true, CURRENT_SUBLEVEL, new Vector3d(18.25, 64.75, -3.5));

		assertEquals(CURRENT_SUBLEVEL, current.subLevelId());
		assertEquals(18, current.position().getX());
		assertEquals(64, current.position().getY());
		assertEquals(-4, current.position().getZ());
	}

	@Test
	void releasedOrMismatchedStableReferenceCannotBeObserved() {
		assertNull(SableExternalBlockServerProvider.currentTrackingPosition(
			STABLE_ID.toString(), 0, STABLE_ID, true, CURRENT_SUBLEVEL, new Vector3d(1, 2, 3)));
		assertNull(SableExternalBlockServerProvider.currentTrackingPosition(
			STABLE_ID.toString(), 1, UUID.randomUUID(), true, CURRENT_SUBLEVEL, new Vector3d(1, 2, 3)));
		assertNull(SableExternalBlockServerProvider.currentTrackingPosition(
			STABLE_ID.toString(), 1, STABLE_ID, false, CURRENT_SUBLEVEL, new Vector3d(1, 2, 3)));
	}

	@Test
	void observeStableEntryResolvesCurrentLivePositionWithoutChangingReferencesOrEntries() throws Exception {
		RecordingEntry entry = new RecordingEntry(STABLE_ID);
		Map<String, RecordingEntry> entries = new HashMap<>();
		entries.put(STABLE_ID.toString(), entry);
		Map<String, Integer> referenceCounts = new HashMap<>();
		referenceCounts.put(STABLE_ID.toString(), 2);
		AtomicInteger resolverCalls = new AtomicInteger();
		Vector3d currentPoint = new Vector3d(18.25, 64.75, -3.5);

		String observed = SableExternalBlockServerProvider.observeStableEntry(
			committedTarget(), entries, stableId -> referenceCounts.getOrDefault(stableId, 0), entry, Boolean.TRUE,
			CURRENT_SUBLEVEL, currentPoint, (subLevelId, position) -> {
				resolverCalls.incrementAndGet();
				assertEquals(CURRENT_SUBLEVEL, subLevelId);
				assertEquals(new net.minecraft.core.BlockPos(18, 64, -4), position);
				return "live-sample";
			});

		assertEquals("live-sample", observed);
		assertEquals(1, resolverCalls.get());
		assertEquals(Map.of(STABLE_ID.toString(), entry), entries);
		assertEquals(Map.of(STABLE_ID.toString(), 2), referenceCounts);
		assertSame(entry, entries.get(STABLE_ID.toString()));
	}

	@Test
	void observeStableEntryFailsClosedForWrongEntryReleasedReferenceOrInvalidPoint() throws Exception {
		RecordingEntry entry = new RecordingEntry(STABLE_ID);
		RecordingEntry other = new RecordingEntry(UUID.randomUUID());
		Map<String, RecordingEntry> entries = Map.of(STABLE_ID.toString(), entry);
		AtomicInteger resolverCalls = new AtomicInteger();
		SableExternalBlockServerProvider.CurrentLiveResolver<String> resolver = (subLevelId, position) -> {
			resolverCalls.incrementAndGet();
			return "unexpected";
		};

		assertNull(SableExternalBlockServerProvider.observeStableEntry(committedTarget(), entries,
			stableId -> 1, other, Boolean.TRUE, CURRENT_SUBLEVEL, new Vector3d(1, 2, 3), resolver));
		assertNull(SableExternalBlockServerProvider.observeStableEntry(committedTarget(), entries,
			stableId -> 0, entry, Boolean.TRUE, CURRENT_SUBLEVEL, new Vector3d(1, 2, 3), resolver));
		assertNull(SableExternalBlockServerProvider.observeStableEntry(committedTarget(), entries,
			stableId -> 1, entry, Boolean.FALSE, CURRENT_SUBLEVEL, new Vector3d(1, 2, 3), resolver));
		assertNull(SableExternalBlockServerProvider.observeStableEntry(committedTarget(), entries,
			stableId -> 1, entry, Boolean.TRUE, "not-a-sublevel-id", new Vector3d(1, 2, 3), resolver));
		assertNull(SableExternalBlockServerProvider.observeStableEntry(committedTarget(), entries,
			stableId -> 1, entry, Boolean.TRUE, CURRENT_SUBLEVEL, new Vector3d(Double.NaN, 2, 3), resolver));
		assertEquals(0, resolverCalls.get(), "invalid live identity must not sample the provider");
		assertTrue(entries.containsKey(STABLE_ID.toString()));
	}

	private static Target.ExternalBlockTarget committedTarget() {
		return Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", STABLE_ID.toString(), "minecraft:chest", "old-locator", true);
	}

	private record RecordingEntry(UUID trackingId)
		implements SableExternalBlockServerProvider.StableEntryView {
	}
}
