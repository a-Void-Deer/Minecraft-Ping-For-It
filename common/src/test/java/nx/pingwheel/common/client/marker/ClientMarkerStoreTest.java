package nx.pingwheel.common.client.marker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMarkerStoreTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:nether";
	private static final UUID OWNER = new UUID(0L, 100L);
	private static final UUID STRANGER = new UUID(0L, 200L);

	private static ClientMarkerStore newStore() {
		return new ClientMarkerStore(10L);
	}

	private static MarkerSnapshot snapshot(MarkerId id, Target target, long arrival, long expires) {
		return new MarkerSnapshot(id, OWNER, target, "entity", "attention", new MarkerAnchor(0, 0, 0), arrival, expires);
	}

	private static Target entityTarget(String dimension, UUID entity) {
		return new Target.EntityTarget(dimension, entity);
	}

	private static Target locationTarget(String dimension, double x) {
		return new Target.LocationTarget(dimension, x, 0, 0);
	}

	// --- created / upsert / idempotency ---

	@Test
	void createdStoresMarkerWithFallbackExpiry() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		MarkerId id = new MarkerId(5L);

		store.onCreated(snapshot(id, target, 10L, 110L), 50L);

		ClientMarker marker = store.marker(id).orElseThrow();
		assertEquals(id, marker.id());
		assertEquals(target, marker.target());
		assertEquals(50L, marker.receivedAtLocalTick());
		// 50 + (110 - 10) + 10 = 160
		assertEquals(160L, marker.fallbackExpiresAtLocalTick());
		assertEquals(List.of(marker), store.allMarkers());
	}

	@Test
	void createdIsIdempotentForSameIdAndPayload() {
		ClientMarkerStore store = newStore();
		MarkerId id = new MarkerId(3L);
		MarkerSnapshot first = snapshot(id, locationTarget(OVERWORLD, 1), 10L, 110L);

		store.onCreated(first, 50L);
		store.onCreated(first, 50L);

		assertEquals(1, store.allMarkers().size());
		assertEquals(ClientMarker.from(first, 50L, 10L), store.marker(id).orElseThrow());
	}

	@Test
	void createdSameIdReplacesPayloadWithLatest() {
		ClientMarkerStore store = newStore();
		MarkerId id = new MarkerId(3L);
		Target first = locationTarget(OVERWORLD, 1);
		Target second = locationTarget(OVERWORLD, 2);

		store.onCreated(snapshot(id, first, 10L, 110L), 50L);
		store.onCreated(snapshot(id, second, 10L, 110L), 60L);

		assertEquals(1, store.allMarkers().size());
		assertEquals(second, store.marker(id).orElseThrow().target());
	}

	@Test
	void externalSameIdUpsertReplacesLocatorWithoutChangingStableTargetKey() {
		ClientMarkerStore store = newStore();
		MarkerId id = new MarkerId(3L);
		Target.ExternalBlockTarget first = Target.ExternalBlockTarget.committed(
			OVERWORLD, "sable", "tracking-id", "minecraft:chest", "locator-a", true);
		Target.ExternalBlockTarget second = Target.ExternalBlockTarget.committed(
			OVERWORLD, "sable", "tracking-id", "minecraft:chest", "locator-b", true);

		store.onCreated(snapshot(id, first, 10L, 110L), 50L);
		TargetKey key = TargetKey.from(first);
		store.onCreated(snapshot(id, second, 10L, 110L), 60L);

		assertEquals(second, store.marker(id).orElseThrow().target());
		assertEquals(key, store.marker(id).orElseThrow().targetKey());
		assertEquals(1, store.allMarkers().size());
	}

	@Test
	void createdRejectsNullSnapshotAndNegativeTick() {
		ClientMarkerStore store = newStore();

		assertThrows(NullPointerException.class, () -> store.onCreated(null, 0L));
		assertThrows(IllegalArgumentException.class,
			() -> store.onCreated(snapshot(new MarkerId(1L), locationTarget(OVERWORLD, 1), 1L, 10L), -1L));
	}

	// --- removed / unknown ---

	@Test
	void removedRemovesMarker() {
		ClientMarkerStore store = newStore();
		MarkerId id = new MarkerId(7L);

		store.onCreated(snapshot(id, locationTarget(OVERWORLD, 1), 1L, 10L), 0L);
		store.onRemoved(id);

		assertTrue(store.marker(id).isEmpty());
		assertTrue(store.allMarkers().isEmpty());
	}

	@Test
	void removedUnknownIdIsSafe() {
		ClientMarkerStore store = newStore();

		assertDoesNotThrow(() -> store.onRemoved(new MarkerId(99L)));
	}

	@Test
	void removedClearsWinnerSlotsPointingToRemovedMarker() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		TargetKey key = TargetKey.from(target);
		MarkerId id = new MarkerId(7L);

		store.onCreated(snapshot(id, target, 1L, 10L), 0L);
		store.onWinnerChanged(key, Optional.of(id));
		store.onRemoved(id);

		assertTrue(store.winnerId(key).isEmpty());
		assertTrue(store.winnerMarker(key).isEmpty());
	}

	@Test
	void removedDoesNotClearWinnerSlotsForOtherIds() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		TargetKey key = TargetKey.from(target);
		MarkerId removed = new MarkerId(7L);
		MarkerId kept = new MarkerId(8L);

		store.onCreated(snapshot(removed, target, 1L, 10L), 0L);
		store.onCreated(snapshot(kept, target, 2L, 20L), 0L);
		store.onWinnerChanged(key, Optional.of(kept));
		store.onRemoved(removed);

		assertEquals(kept, store.winnerId(key).orElseThrow());
	}

	@Test
	void removedRejectsNull() {
		ClientMarkerStore store = newStore();

		assertThrows(NullPointerException.class, () -> store.onRemoved(null));
	}

	// --- winner: order of arrival / out-of-order / empty / wrong key ---

	@Test
	void winnerIsHiddenUntilMarkerArrives() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		TargetKey key = TargetKey.from(target);
		MarkerId id = new MarkerId(2L);

		store.onWinnerChanged(key, Optional.of(id));

		assertTrue(store.winnerId(key).isEmpty());
		assertTrue(store.winnerMarker(key).isEmpty());

		store.onCreated(snapshot(id, target, 1L, 10L), 0L);

		assertEquals(id, store.winnerId(key).orElseThrow());
		assertEquals(id, store.winnerMarker(key).orElseThrow().id());
	}

	@Test
	void winnerAnnouncedAfterCreationIsVisible() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		TargetKey key = TargetKey.from(target);
		MarkerId id = new MarkerId(2L);

		store.onCreated(snapshot(id, target, 1L, 10L), 0L);

		assertTrue(store.winnerId(key).isEmpty());

		store.onWinnerChanged(key, Optional.of(id));

		assertEquals(id, store.winnerId(key).orElseThrow());
	}

	@Test
	void winnerEmptyClearsSlot() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		TargetKey key = TargetKey.from(target);
		MarkerId id = new MarkerId(2L);

		store.onCreated(snapshot(id, target, 1L, 10L), 0L);
		store.onWinnerChanged(key, Optional.of(id));
		store.onWinnerChanged(key, Optional.empty());

		assertTrue(store.winnerId(key).isEmpty());
	}

	@Test
	void winnerEmptyForUnknownKeyIsSafe() {
		ClientMarkerStore store = newStore();

		assertDoesNotThrow(
			() -> store.onWinnerChanged(TargetKey.from(locationTarget(OVERWORLD, 1)), Optional.empty()));
	}

	@Test
	void winnerIdBelongingToMarkerWithDifferentKeyIsNotExposed() {
		ClientMarkerStore store = newStore();
		Target announced = locationTarget(OVERWORLD, 1);
		Target actual = locationTarget(OVERWORLD, 2);
		TargetKey announcedKey = TargetKey.from(announced);
		MarkerId id = new MarkerId(2L);

		store.onWinnerChanged(announcedKey, Optional.of(id));
		store.onCreated(snapshot(id, actual, 1L, 10L), 0L);

		assertTrue(store.winnerId(announcedKey).isEmpty());
		assertTrue(store.winnerMarker(announcedKey).isEmpty());
		assertTrue(store.winnerId(TargetKey.from(actual)).isEmpty());
	}

	@Test
	void winnerRejectsNullArguments() {
		ClientMarkerStore store = newStore();
		TargetKey key = TargetKey.from(locationTarget(OVERWORLD, 1));

		assertThrows(NullPointerException.class, () -> store.onWinnerChanged(null, Optional.empty()));
		assertThrows(NullPointerException.class, () -> store.onWinnerChanged(key, null));
	}

	// --- dimension and ownership queries ---

	@Test
	void markersInDimensionFiltersByDimension() {
		ClientMarkerStore store = newStore();
		MarkerId overworldId = new MarkerId(1L);
		MarkerId netherId = new MarkerId(2L);

		store.onCreated(snapshot(overworldId, locationTarget(OVERWORLD, 1), 1L, 10L), 0L);
		store.onCreated(snapshot(netherId, locationTarget(NETHER, 1), 1L, 10L), 0L);

		assertEquals(List.of(overworldId), store.markersInDimension(OVERWORLD).stream().map(ClientMarker::id).toList());
		assertEquals(List.of(netherId), store.markersInDimension(NETHER).stream().map(ClientMarker::id).toList());
		assertTrue(store.markersInDimension("minecraft:the_end").isEmpty());
	}

	@Test
	void markersOwnedInDimensionFiltersByDimensionAndOwner() {
		ClientMarkerStore store = newStore();
		MarkerId ownedOverworld = new MarkerId(1L);
		MarkerId strangerOverworld = new MarkerId(2L);
		MarkerId ownedNether = new MarkerId(3L);

		store.onCreated(snapshot(ownedOverworld, locationTarget(OVERWORLD, 1), 1L, 10L), 0L);
		store.onCreated(
			new MarkerSnapshot(strangerOverworld, STRANGER, locationTarget(OVERWORLD, 2),
				"entity", "attention", new MarkerAnchor(0, 0, 0), 1L, 10L), 0L);
		store.onCreated(snapshot(ownedNether, locationTarget(NETHER, 1), 1L, 10L), 0L);

		assertEquals(
			List.of(ownedOverworld),
			store.markersOwnedInDimension(OVERWORLD, OWNER).stream().map(ClientMarker::id).toList());
		assertEquals(
			List.of(strangerOverworld),
			store.markersOwnedInDimension(OVERWORLD, STRANGER).stream().map(ClientMarker::id).toList());
		assertEquals(
			List.of(ownedNether),
			store.markersOwnedInDimension(NETHER, OWNER).stream().map(ClientMarker::id).toList());
		assertTrue(store.markersOwnedInDimension(OVERWORLD, UUID.randomUUID()).isEmpty());
	}

	@Test
	void dimensionQueriesRejectNull() {
		ClientMarkerStore store = newStore();

		assertThrows(NullPointerException.class, () -> store.markersInDimension(null));
		assertThrows(NullPointerException.class, () -> store.markersOwnedInDimension(null, OWNER));
		assertThrows(NullPointerException.class, () -> store.markersOwnedInDimension(OVERWORLD, null));
	}

	@Test
	void visibleWinnersInDimensionFiltersByDimensionAndKeyMatch() {
		ClientMarkerStore store = newStore();
		Target overworldTarget = locationTarget(OVERWORLD, 1);
		Target netherTarget = locationTarget(NETHER, 1);
		Target mismatchedTarget = locationTarget(OVERWORLD, 2);
		MarkerId overworldId = new MarkerId(1L);
		MarkerId netherId = new MarkerId(2L);
		MarkerId mismatchedId = new MarkerId(3L);

		store.onCreated(snapshot(overworldId, overworldTarget, 1L, 10L), 0L);
		store.onCreated(snapshot(netherId, netherTarget, 1L, 10L), 0L);
		store.onCreated(snapshot(mismatchedId, mismatchedTarget, 1L, 10L), 0L);
		store.onWinnerChanged(TargetKey.from(overworldTarget), Optional.of(overworldId));
		store.onWinnerChanged(TargetKey.from(netherTarget), Optional.of(netherId));
		// Slot announced for a key that mismatches the marker's own key.
		store.onWinnerChanged(TargetKey.from(locationTarget(OVERWORLD, 3)), Optional.of(mismatchedId));

		Map<TargetKey, ClientMarker> overworld = store.visibleWinnersInDimension(OVERWORLD);
		assertEquals(1, overworld.size());
		assertEquals(overworldId, overworld.get(TargetKey.from(overworldTarget)).id());

		Map<TargetKey, ClientMarker> nether = store.visibleWinnersInDimension(NETHER);
		assertEquals(1, nether.size());
		assertEquals(netherId, nether.get(TargetKey.from(netherTarget)).id());
	}

	@Test
	void visibleWinnersInDimensionRejectsNull() {
		ClientMarkerStore store = newStore();

		assertThrows(NullPointerException.class, () -> store.visibleWinnersInDimension(null));
	}

	// --- fallback expiry ---

	@Test
	void expireFallbackRejectsNegativeTick() {
		ClientMarkerStore store = newStore();
		MarkerId id = new MarkerId(1L);

		store.onCreated(snapshot(id, locationTarget(OVERWORLD, 1), 1L, 11L), 0L);

		assertThrows(IllegalArgumentException.class, () -> store.expireFallback(-1L));
		assertTrue(store.marker(id).isPresent());
	}

	@Test
	void expireFallbackRemovesAtExactBoundary() {
		ClientMarkerStore store = newStore();
		MarkerId id = new MarkerId(1L);

		// fallback expiry = 50 + (110 - 10) + 10 = 160
		store.onCreated(snapshot(id, locationTarget(OVERWORLD, 1), 10L, 110L), 50L);

		assertTrue(store.expireFallback(159L).isEmpty());
		assertTrue(store.marker(id).isPresent());

		assertEquals(List.of(id), store.expireFallback(160L).stream().map(ClientMarker::id).toList());
		assertTrue(store.marker(id).isEmpty());
	}

	@Test
	void expireFallbackDoesNotRemoveEarly() {
		ClientMarkerStore store = newStore();
		MarkerId id = new MarkerId(1L);

		store.onCreated(snapshot(id, locationTarget(OVERWORLD, 1), 10L, 110L), 50L);

		assertTrue(store.expireFallback(100L).isEmpty());
		assertTrue(store.marker(id).isPresent());
	}

	@Test
	void expireFallbackReturnsSortedRemovedList() {
		ClientMarkerStore store = newStore();
		MarkerId high = new MarkerId(7L);
		MarkerId low = new MarkerId(3L);

		// Both fallback-expire at 0 + (11 - 1) + 10 = 20.
		store.onCreated(snapshot(high, locationTarget(OVERWORLD, 1), 1L, 11L), 0L);
		store.onCreated(snapshot(low, locationTarget(OVERWORLD, 2), 1L, 11L), 0L);

		assertEquals(
			List.of(low, high),
			store.expireFallback(20L).stream().map(ClientMarker::id).toList());
	}

	@Test
	void expireFallbackRecomputesWinnerToLatestArrivalAmongRemaining() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		TargetKey key = TargetKey.from(target);
		MarkerId older = new MarkerId(1L);
		MarkerId newer = new MarkerId(2L);

		// older: arrival 10, expires 110, received 50 -> fallback 160
		store.onCreated(snapshot(older, target, 10L, 110L), 50L);
		// newer: arrival 20, expires 25, received 50 -> fallback 50 + 5 + 10 = 65
		store.onCreated(snapshot(newer, target, 20L, 25L), 50L);
		store.onWinnerChanged(key, Optional.of(newer));

		store.expireFallback(65L);

		assertTrue(store.marker(newer).isEmpty());
		assertEquals(older, store.winnerId(key).orElseThrow());
	}

	@Test
	void expireFallbackRecomputeTieBreakPrefersLargerId() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		TargetKey key = TargetKey.from(target);
		MarkerId smaller = new MarkerId(1L);
		MarkerId larger = new MarkerId(2L);
		MarkerId removedWinner = new MarkerId(3L);

		// All three share arrival 10; the removed winner expires first.
		store.onCreated(snapshot(smaller, target, 10L, 200L), 50L);
		store.onCreated(snapshot(larger, target, 10L, 200L), 50L);
		store.onCreated(snapshot(removedWinner, target, 10L, 20L), 50L);
		store.onWinnerChanged(key, Optional.of(removedWinner));

		// removedWinner fallback-expires at 50 + (20 - 10) + 10 = 70.
		store.expireFallback(70L);

		assertEquals(larger, store.winnerId(key).orElseThrow());
	}

	@Test
	void expireFallbackClearsWinnerWhenNoMatchingMarkerRemains() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		TargetKey key = TargetKey.from(target);
		MarkerId id = new MarkerId(1L);

		store.onCreated(snapshot(id, target, 10L, 20L), 50L);
		store.onWinnerChanged(key, Optional.of(id));

		// id fallback-expires at 50 + (20 - 10) + 10 = 70.
		store.expireFallback(70L);

		assertTrue(store.winnerId(key).isEmpty());
	}

	@Test
	void expireFallbackIgnoresRemainingMarkersWithDifferentKey() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		Target other = locationTarget(OVERWORLD, 2);
		MarkerId winner = new MarkerId(1L);
		MarkerId otherMarker = new MarkerId(2L);

		store.onCreated(snapshot(winner, target, 10L, 20L), 50L);
		store.onCreated(snapshot(otherMarker, other, 10L, 200L), 50L);
		store.onWinnerChanged(TargetKey.from(target), Optional.of(winner));

		// winner fallback-expires at 50 + (20 - 10) + 10 = 70.
		store.expireFallback(70L);

		assertTrue(store.winnerId(TargetKey.from(target)).isEmpty());
		assertTrue(store.marker(otherMarker).isPresent());
	}

	@Test
	void expireFallbackLeavesSlotWhenRemovedMarkerWasNotTheWinner() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		TargetKey key = TargetKey.from(target);
		MarkerId winner = new MarkerId(1L);
		MarkerId loser = new MarkerId(2L);

		store.onCreated(snapshot(winner, target, 10L, 200L), 50L);
		store.onCreated(snapshot(loser, target, 10L, 20L), 50L);
		store.onWinnerChanged(key, Optional.of(winner));

		// loser fallback-expires at 50 + (20 - 10) + 10 = 70.
		store.expireFallback(70L);

		assertTrue(store.marker(loser).isEmpty());
		assertEquals(winner, store.winnerId(key).orElseThrow());
	}

	@Test
	void expireFallbackClearsMismatchedSlotAndRecomputesOwnKeySlot() {
		ClientMarkerStore store = newStore();
		Target ownTarget = locationTarget(OVERWORLD, 1);
		TargetKey ownKey = TargetKey.from(ownTarget);
		Target otherTarget = locationTarget(OVERWORLD, 2);
		TargetKey otherKey = TargetKey.from(otherTarget);
		MarkerId expiredWinner = new MarkerId(1L);
		MarkerId fallback = new MarkerId(2L);
		MarkerId otherMarker = new MarkerId(3L);

		store.onCreated(snapshot(expiredWinner, ownTarget, 10L, 20L), 50L);
		store.onCreated(snapshot(fallback, ownTarget, 10L, 200L), 50L);
		store.onCreated(snapshot(otherMarker, otherTarget, 10L, 200L), 50L);

		// Own-key slot correctly references the expiring winner.
		store.onWinnerChanged(ownKey, Optional.of(expiredWinner));
		// Mismatched slot for another key also references the expiring id.
		store.onWinnerChanged(otherKey, Optional.of(expiredWinner));

		// expiredWinner fallback-expires at 50 + (20 - 10) + 10 = 70.
		store.expireFallback(70L);

		// The own-key slot is recomputed to the remaining same-target marker.
		assertEquals(fallback, store.winnerId(ownKey).orElseThrow());
		// The mismatched slot is cleared, not recomputed: otherMarker stays hidden.
		assertTrue(store.winnerId(otherKey).isEmpty());
		assertTrue(store.marker(otherMarker).isPresent());
	}

	@Test
	void expireFallbackRecomputeOverwrittenByLaterAuthoritativeUpdate() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		TargetKey key = TargetKey.from(target);
		MarkerId older = new MarkerId(1L);
		MarkerId newer = new MarkerId(2L);

		store.onCreated(snapshot(older, target, 10L, 110L), 50L);
		store.onCreated(snapshot(newer, target, 20L, 25L), 50L);
		store.onWinnerChanged(key, Optional.of(newer));

		store.expireFallback(65L);

		assertEquals(older, store.winnerId(key).orElseThrow());

		// The authoritative update after the fallback recompute simply wins.
		store.onWinnerChanged(key, Optional.empty());
		assertTrue(store.winnerId(key).isEmpty());
	}

	@Test
	void expireFallbackSaturatesWithoutOverflowRemovingEarly() {
		ClientMarkerStore store = newStore();
		MarkerId id = new MarkerId(1L);

		// Server lifetime Long.MAX_VALUE -> fallback expiry saturates at Long.MAX_VALUE.
		store.onCreated(snapshot(id, locationTarget(OVERWORLD, 1), 0L, Long.MAX_VALUE), 5L);

		assertTrue(store.expireFallback(Long.MAX_VALUE - 1L).isEmpty());
		assertEquals(List.of(id), store.expireFallback(Long.MAX_VALUE).stream().map(ClientMarker::id).toList());
	}

	// --- clear / immutability / deterministic ordering ---

	@Test
	void clearDropsMarkersAndWinners() {
		ClientMarkerStore store = newStore();
		Target target = locationTarget(OVERWORLD, 1);
		TargetKey key = TargetKey.from(target);
		MarkerId id = new MarkerId(1L);

		store.onCreated(snapshot(id, target, 1L, 10L), 0L);
		store.onWinnerChanged(key, Optional.of(id));

		store.clear();

		assertTrue(store.allMarkers().isEmpty());
		assertTrue(store.winnerId(key).isEmpty());
		assertTrue(store.visibleWinnersInDimension(OVERWORLD).isEmpty());
	}

	@Test
	void allMarkersAreSortedByMarkerId() {
		ClientMarkerStore store = newStore();

		store.onCreated(snapshot(new MarkerId(7L), locationTarget(OVERWORLD, 1), 1L, 10L), 0L);
		store.onCreated(snapshot(new MarkerId(3L), locationTarget(OVERWORLD, 2), 1L, 10L), 0L);
		store.onCreated(snapshot(new MarkerId(5L), locationTarget(OVERWORLD, 3), 1L, 10L), 0L);

		assertEquals(
			List.of(new MarkerId(3L), new MarkerId(5L), new MarkerId(7L)),
			store.allMarkers().stream().map(ClientMarker::id).toList());
	}

	@Test
	void markerQueryRejectsNull() {
		ClientMarkerStore store = newStore();

		assertThrows(NullPointerException.class, () -> store.marker(null));
		assertThrows(NullPointerException.class, () -> store.winnerId(null));
		assertThrows(NullPointerException.class, () -> store.winnerMarker(null));
	}

	@Test
	void constructorRejectsNegativeGraceTicks() {
		assertThrows(IllegalArgumentException.class, () -> new ClientMarkerStore(-1L));
	}

	@Test
	void listsAreImmutable() {
		ClientMarkerStore store = newStore();
		store.onCreated(snapshot(new MarkerId(1L), locationTarget(OVERWORLD, 1), 1L, 11L), 0L);
		store.onCreated(snapshot(new MarkerId(2L), locationTarget(OVERWORLD, 2), 1L, 11L), 0L);

		assertThrows(UnsupportedOperationException.class, () -> store.allMarkers().add(null));
		assertThrows(UnsupportedOperationException.class, () -> store.markersInDimension(OVERWORLD).clear());
		assertThrows(
			UnsupportedOperationException.class,
			() -> store.expireFallback(20L).remove(0));
	}

	@Test
	void winnerMapsAreUnmodifiableAndOrderedByMarkerId() {
		ClientMarkerStore store = newStore();
		Target firstTarget = locationTarget(OVERWORLD, 1);
		Target secondTarget = locationTarget(OVERWORLD, 2);
		MarkerId first = new MarkerId(4L);
		MarkerId second = new MarkerId(2L);

		store.onCreated(snapshot(first, firstTarget, 1L, 10L), 0L);
		store.onCreated(snapshot(second, secondTarget, 1L, 10L), 0L);
		store.onWinnerChanged(TargetKey.from(firstTarget), Optional.of(first));
		store.onWinnerChanged(TargetKey.from(secondTarget), Optional.of(second));

		Map<TargetKey, ClientMarker> winners = store.visibleWinnersInDimension(OVERWORLD);

		assertEquals(List.of(second, first), winners.values().stream().map(ClientMarker::id).toList());
		assertThrows(UnsupportedOperationException.class,
			() -> winners.put(TargetKey.from(locationTarget(OVERWORLD, 3)), null));
	}

	@Test
	void winnerQueriesIgnoreMismatchedSlotWhenMarkerArrivesLater() {
		ClientMarkerStore store = newStore();
		Target announced = locationTarget(OVERWORLD, 1);
		Target actual = locationTarget(OVERWORLD, 2);
		TargetKey announcedKey = TargetKey.from(announced);
		TargetKey actualKey = TargetKey.from(actual);
		MarkerId id = new MarkerId(2L);

		store.onWinnerChanged(announcedKey, Optional.of(id));

		assertTrue(store.winnerId(announcedKey).isEmpty());

		store.onCreated(snapshot(id, actual, 1L, 10L), 0L);

		assertTrue(store.winnerId(announcedKey).isEmpty());
		assertTrue(store.winnerMarker(announcedKey).isEmpty());
		assertTrue(store.visibleWinnersInDimension(OVERWORLD).isEmpty());

		// The actual key was never announced, so it stays hidden as well.
		assertTrue(store.winnerId(actualKey).isEmpty());
	}
}
