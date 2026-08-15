package nx.pingwheel.common.marker;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetType;
import nx.pingwheel.common.domain.TargetTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerMarkerStoreTest {

	private static final String OVERWORLD = "minecraft:overworld";

	// Fixed UUIDs so recipient ordering assertions are deterministic:
	// RECIPIENT_A < RECIPIENT_B in natural UUID order.
	private static final UUID OWNER = new UUID(0L, 100L);
	private static final UUID STRANGER = new UUID(0L, 200L);
	private static final UUID RECIPIENT_A = new UUID(0L, 10L);
	private static final UUID RECIPIENT_B = new UUID(0L, 20L);

	private static final PingTypeCatalog PING_TYPES = PingTypeCatalog.builtIn();
	private static final TargetTypeCatalog TARGET_TYPES = TargetTypeCatalog.builtIn();

	private static ServerMarkerStore newStore() {
		return new ServerMarkerStore(new MarkerIdSource());
	}

	private static Target entityTarget(UUID uuid) {
		return new Target.EntityTarget(OVERWORLD, uuid);
	}

	private static TargetType entityType() {
		return TARGET_TYPES.findById("entity").orElseThrow();
	}

	private static PingType attentionPingType() {
		return PING_TYPES.findById("attention").orElseThrow();
	}

	private static MarkerCreation create(
		ServerMarkerStore store, Target target, long arrival, long expires, UUID... recipients
	) {
		return store.create(
			OWNER, target, entityType(), attentionPingType(), new MarkerAnchor(0, 0, 0), arrival, expires,
			List.of(recipients));
	}

	private static MarkerCreation createOwnedBy(
		ServerMarkerStore store, UUID owner, Target target, long arrival, long expires, UUID... recipients
	) {
		return store.create(
			owner, target, entityType(), attentionPingType(), new MarkerAnchor(0, 0, 0), arrival, expires,
			List.of(recipients));
	}

	@Test
	void createAssignsMonotonicIdsAndPreservesFields() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());

		MarkerCreation first = create(store, target, 10L, 110L, RECIPIENT_A);
		MarkerCreation second = create(store, entityTarget(UUID.randomUUID()), 20L, 120L, RECIPIENT_B);

		assertEquals(new MarkerId(0L), first.marker().id());
		assertEquals(new MarkerId(1L), second.marker().id());

		ServerMarker marker = first.marker();
		assertEquals(OWNER, marker.owner());
		assertEquals(target, marker.target());
		assertEquals(10L, marker.arrivalTick());
		assertEquals(110L, marker.expiresAtTick());
		assertEquals(List.of(RECIPIENT_A), marker.recipients());
		assertEquals(2, store.size());
	}

	@Test
	void createReportsWinnerChangeWhenFirstMarkerForPair() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());

		MarkerCreation creation = create(store, target, 10L, 110L, RECIPIENT_A, RECIPIENT_B);

		assertEquals(2, creation.winnerChanges().size());
		assertEquals(
			new MarkerWinnerChange(
				TargetKey.from(target), RECIPIENT_A, Optional.empty(), Optional.of(new MarkerId(0L))),
			creation.winnerChanges().get(0));
		assertEquals(
			new MarkerWinnerChange(
				TargetKey.from(target), RECIPIENT_B, Optional.empty(), Optional.of(new MarkerId(0L))),
			creation.winnerChanges().get(1));
	}

	@Test
	void createReportsNoChangeWhenWinnerUnchanged() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());

		create(store, target, 20L, 120L, RECIPIENT_A); // id 0: winner for A
		MarkerCreation older = create(store, target, 10L, 110L, RECIPIENT_A); // id 1: earlier arrival

		assertTrue(older.winnerChanges().isEmpty());
		assertEquals(new MarkerId(0L), store.winnerFor(TargetKey.from(target), RECIPIENT_A).orElseThrow().id());
	}

	@Test
	void equalArrivalTickResolvesToLargerId() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());

		create(store, target, 10L, 110L, RECIPIENT_A); // id 0
		MarkerCreation second = create(store, target, 10L, 110L, RECIPIENT_A); // id 1

		assertEquals(1, second.winnerChanges().size());
		assertEquals(Optional.of(new MarkerId(0L)), second.winnerChanges().get(0).previousWinner());
		assertEquals(Optional.of(new MarkerId(1L)), second.winnerChanges().get(0).currentWinner());
		assertEquals(new MarkerId(1L), store.winnerFor(TargetKey.from(target), RECIPIENT_A).orElseThrow().id());
	}

	@Test
	void winnerChangesArePerRecipientAudienceIsolation() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());
		TargetKey key = TargetKey.from(target);

		MarkerCreation forA = create(store, target, 20L, 120L, RECIPIENT_A);
		MarkerCreation forB = create(store, target, 10L, 110L, RECIPIENT_B);

		assertEquals(1, forA.winnerChanges().size());
		assertEquals(RECIPIENT_A, forA.winnerChanges().get(0).recipientId());
		assertEquals(1, forB.winnerChanges().size());
		assertEquals(RECIPIENT_B, forB.winnerChanges().get(0).recipientId());

		assertEquals(new MarkerId(0L), store.winnerFor(key, RECIPIENT_A).orElseThrow().id());
		assertEquals(new MarkerId(1L), store.winnerFor(key, RECIPIENT_B).orElseThrow().id());
		assertTrue(store.winnerFor(key, STRANGER).isEmpty());
	}

	@Test
	void removeOwnedMissingReturnsNotFoundWithoutMutation() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());
		create(store, target, 10L, 110L, RECIPIENT_A);

		MarkerRemovalResult result = store.removeOwned(OWNER, new MarkerId(99L));

		assertEquals(MarkerRemovalResult.Status.NOT_FOUND, result.status());
		assertTrue(result.removal().isEmpty());
		assertTrue(result.winnerChanges().isEmpty());
		assertEquals(1, store.size());
		assertEquals(new MarkerId(0L), store.winnerFor(TargetKey.from(target), RECIPIENT_A).orElseThrow().id());
	}

	@Test
	void removeOwnedForeignOwnerReturnsNotOwnerWithoutMutation() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());
		create(store, target, 10L, 110L, RECIPIENT_A);

		MarkerRemovalResult result = store.removeOwned(STRANGER, new MarkerId(0L));

		assertEquals(MarkerRemovalResult.Status.NOT_OWNER, result.status());
		assertTrue(result.removal().isEmpty());
		assertTrue(result.winnerChanges().isEmpty());
		assertEquals(1, store.size());
		assertEquals(new MarkerId(0L), store.winnerFor(TargetKey.from(target), RECIPIENT_A).orElseThrow().id());
	}

	@Test
	void removeOwnedRemovesWithCancelledReason() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());
		create(store, target, 10L, 110L, RECIPIENT_A);

		MarkerRemovalResult result = store.removeOwned(OWNER, new MarkerId(0L));

		assertEquals(MarkerRemovalResult.Status.REMOVED, result.status());
		MarkerRemoval removal = result.removal().orElseThrow();
		assertEquals(MarkerRemovalReason.CANCELLED, removal.reason());
		assertEquals(new MarkerId(0L), removal.marker().id());
		assertEquals(0, store.size());
	}

	@Test
	void removeByServerUsesGivenReasonAndSkipsOwnershipCheck() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());
		create(store, target, 10L, 110L, RECIPIENT_A);

		MarkerRemovalResult result = store.removeByServer(new MarkerId(0L), MarkerRemovalReason.TARGET_INVALID);

		assertEquals(MarkerRemovalResult.Status.REMOVED, result.status());
		assertEquals(MarkerRemovalReason.TARGET_INVALID, result.removal().orElseThrow().reason());
		assertEquals(0, store.size());

		// A stranger may force-remove as well; it is not an ownership check.
		create(store, target, 10L, 110L, RECIPIENT_A);
		assertEquals(MarkerRemovalResult.Status.REMOVED,
			store.removeByServer(new MarkerId(1L), MarkerRemovalReason.EXPIRED).status());
	}

	@Test
	void removeByServerMissingReturnsNotFoundAndRejectsNullReason() {
		ServerMarkerStore store = newStore();

		MarkerRemovalResult result = store.removeByServer(new MarkerId(1L), MarkerRemovalReason.EXPIRED);

		assertEquals(MarkerRemovalResult.Status.NOT_FOUND, result.status());
		assertTrue(result.removal().isEmpty());
		assertTrue(result.winnerChanges().isEmpty());
		assertEquals(0, store.size());

		assertThrows(NullPointerException.class,
			() -> store.removeByServer(new MarkerId(0L), null));
		assertThrows(NullPointerException.class,
			() -> store.removeByServer(null, MarkerRemovalReason.EXPIRED));
	}

	@Test
	void removalRecomputesOlderWinner() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());
		create(store, target, 10L, 110L, RECIPIENT_A); // id 0
		create(store, target, 20L, 120L, RECIPIENT_A); // id 1: winner

		MarkerRemovalResult result = store.removeOwned(OWNER, new MarkerId(1L));

		assertEquals(1, result.winnerChanges().size());
		assertEquals(Optional.of(new MarkerId(1L)), result.winnerChanges().get(0).previousWinner());
		assertEquals(Optional.of(new MarkerId(0L)), result.winnerChanges().get(0).currentWinner());
		assertEquals(new MarkerId(0L), store.winnerFor(TargetKey.from(target), RECIPIENT_A).orElseThrow().id());
	}

	@Test
	void removingLastMarkerReportsTransitionToEmptyWinner() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());
		TargetKey key = TargetKey.from(target);
		create(store, target, 10L, 110L, RECIPIENT_A);

		MarkerRemovalResult result = store.removeOwned(OWNER, new MarkerId(0L));

		assertEquals(1, result.winnerChanges().size());
		assertEquals(Optional.of(new MarkerId(0L)), result.winnerChanges().get(0).previousWinner());
		assertEquals(Optional.empty(), result.winnerChanges().get(0).currentWinner());
		assertTrue(store.winnerFor(key, RECIPIENT_A).isEmpty());
	}

	@Test
	void expiryBoundaryIsInclusiveAndUsesExpiredReason() {
		ServerMarkerStore store = newStore();
		create(store, entityTarget(UUID.randomUUID()), 0L, 50L, RECIPIENT_A); // id 0
		create(store, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_B); // id 1

		assertTrue(store.expire(49L).removals().isEmpty());

		MarkerBatchRemoval at50 = store.expire(50L);
		assertEquals(List.of(new MarkerId(0L)), at50.removals().stream().map(r -> r.marker().id()).toList());
		assertEquals(MarkerRemovalReason.EXPIRED, at50.removals().get(0).reason());
		assertEquals(1, store.size());

		assertTrue(store.expire(99L).removals().isEmpty());

		MarkerBatchRemoval at100 = store.expire(100L);
		assertEquals(List.of(new MarkerId(1L)), at100.removals().stream().map(r -> r.marker().id()).toList());
		assertEquals(0, store.size());
	}

	@Test
	void expiryComputesSingleCombinedChangePerPairWithoutTransientStates() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());
		create(store, target, 10L, 100L, RECIPIENT_A); // id 0
		create(store, target, 20L, 100L, RECIPIENT_A); // id 1: winner
		create(store, target, 15L, 200L, RECIPIENT_A); // id 2: survives, older than id 1

		MarkerBatchRemoval batch = store.expire(100L);

		assertEquals(List.of(new MarkerId(0L), new MarkerId(1L)),
			batch.removals().stream().map(r -> r.marker().id()).toList());
		// One combined transition per pair: id1 -> id2, never id1 -> empty -> id2.
		assertEquals(1, batch.winnerChanges().size());
		assertEquals(Optional.of(new MarkerId(1L)), batch.winnerChanges().get(0).previousWinner());
		assertEquals(Optional.of(new MarkerId(2L)), batch.winnerChanges().get(0).currentWinner());
		assertEquals(new MarkerId(2L), store.winnerFor(TargetKey.from(target), RECIPIENT_A).orElseThrow().id());
	}

	@Test
	void expiryChangeOrderingIsByRemovedIdThenSortedRecipient() {
		ServerMarkerStore store = newStore();
		Target t1 = entityTarget(UUID.randomUUID());
		Target t2 = entityTarget(UUID.randomUUID());

		create(store, t1, 10L, 100L, RECIPIENT_A, RECIPIENT_B); // id 0: recipients [A, B]
		create(store, t2, 10L, 100L, RECIPIENT_A); // id 1
		create(store, t1, 5L, 100L, RECIPIENT_A); // id 2

		MarkerBatchRemoval batch = store.expire(100L);

		assertEquals(List.of(new MarkerId(0L), new MarkerId(1L), new MarkerId(2L)),
			batch.removals().stream().map(r -> r.marker().id()).toList());

		List<MarkerWinnerChange> changes = batch.winnerChanges();
		// First encounter order: (t1, A), (t1, B) from id 0, (t2, A) from id 1;
		// id 2 touches (t1, A) again, which is already covered.
		assertEquals(3, changes.size());
		assertEquals(new MarkerWinnerChange(
			TargetKey.from(t1), RECIPIENT_A, Optional.of(new MarkerId(0L)), Optional.empty()), changes.get(0));
		assertEquals(new MarkerWinnerChange(
			TargetKey.from(t1), RECIPIENT_B, Optional.of(new MarkerId(0L)), Optional.empty()), changes.get(1));
		assertEquals(new MarkerWinnerChange(
			TargetKey.from(t2), RECIPIENT_A, Optional.of(new MarkerId(1L)), Optional.empty()), changes.get(2));
	}

	@Test
	void removeOwnedByUsesOwnerDisconnectedByDefault() {
		ServerMarkerStore store = newStore();
		create(store, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_A); // id 0
		create(store, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_B); // id 1
		createOwnedBy(store, STRANGER, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_A); // id 2
		create(store, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_B); // id 3

		MarkerBatchRemoval batch = store.removeOwnedBy(OWNER);

		assertEquals(List.of(new MarkerId(0L), new MarkerId(1L), new MarkerId(3L)),
			batch.removals().stream().map(r -> r.marker().id()).toList());
		assertTrue(batch.removals().stream()
			.allMatch(removal -> removal.reason() == MarkerRemovalReason.OWNER_DISCONNECTED));
		assertEquals(1, store.size());
		assertEquals(new MarkerId(2L), store.allMarkers().get(0).id());
	}

	@Test
	void removeOwnedByUsesSuppliedReason() {
		ServerMarkerStore store = newStore();
		create(store, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_A);
		create(store, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_A);

		MarkerBatchRemoval batch = store.removeOwnedBy(OWNER, MarkerRemovalReason.TARGET_INVALID);

		assertEquals(2, batch.removals().size());
		assertTrue(batch.removals().stream()
			.allMatch(removal -> removal.reason() == MarkerRemovalReason.TARGET_INVALID));
		assertEquals(0, store.size());
	}

	@Test
	void removeOwnedByCombinesChangesPerPair() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());
		TargetKey key = TargetKey.from(target);
		create(store, target, 10L, 300L, RECIPIENT_A); // id 0
		create(store, target, 20L, 300L, RECIPIENT_A); // id 1
		create(store, target, 30L, 300L, RECIPIENT_A); // id 2: winner

		MarkerBatchRemoval batch = store.removeOwnedBy(OWNER);

		assertEquals(3, batch.removals().size());
		assertEquals(1, batch.winnerChanges().size());
		assertEquals(Optional.of(new MarkerId(2L)), batch.winnerChanges().get(0).previousWinner());
		assertEquals(Optional.empty(), batch.winnerChanges().get(0).currentWinner());
		assertTrue(store.winnerFor(key, RECIPIENT_A).isEmpty());
	}

	@Test
	void forgetRecipientShrinksAudienceAndDropsEmptyMarkers() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());
		TargetKey key = TargetKey.from(target);
		create(store, target, 10L, 110L, RECIPIENT_A, RECIPIENT_B); // id 0
		create(store, target, 20L, 120L, RECIPIENT_A); // id 1: winner for A, only audience

		List<ServerMarker> dropped = store.forgetRecipient(RECIPIENT_A);

		assertEquals(1, store.size());
		ServerMarker remaining = store.allMarkers().get(0);
		assertEquals(new MarkerId(0L), remaining.id());
		assertEquals(List.of(RECIPIENT_B), remaining.recipients());
		// Only the marker left with no audience is dropped and reported; the
		// marker merely rebuilt with the remaining recipients is not returned.
		assertEquals(List.of(new MarkerId(1L)), dropped.stream().map(ServerMarker::id).toList());
		// Remaining-recipient winners stay correct.
		assertEquals(new MarkerId(0L), store.winnerFor(key, RECIPIENT_B).orElseThrow().id());
		assertTrue(store.winnerFor(key, RECIPIENT_A).isEmpty());
	}

	@Test
	void forgetRecipientDropsAreSortedByMarkerIdAndImmutable() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());
		create(store, target, 10L, 110L, RECIPIENT_A); // id 0: dropped
		create(store, target, 20L, 120L, RECIPIENT_A, RECIPIENT_B); // id 1: rebuilt to [B]
		create(store, target, 30L, 130L, RECIPIENT_A); // id 2: dropped

		List<ServerMarker> dropped = store.forgetRecipient(RECIPIENT_A);

		assertEquals(List.of(new MarkerId(0L), new MarkerId(2L)),
			dropped.stream().map(ServerMarker::id).toList());
		assertThrows(UnsupportedOperationException.class, () -> dropped.remove(0));

		assertEquals(1, store.size());
		ServerMarker remaining = store.allMarkers().get(0);
		assertEquals(new MarkerId(1L), remaining.id());
		assertEquals(List.of(RECIPIENT_B), remaining.recipients());
	}

	@Test
	void forgetRecipientUnknownRecipientIsNoOp() {
		ServerMarkerStore store = newStore();
		create(store, entityTarget(UUID.randomUUID()), 10L, 110L, RECIPIENT_A);

		List<ServerMarker> dropped = store.forgetRecipient(STRANGER);

		assertTrue(dropped.isEmpty());
		assertEquals(1, store.size());
		assertEquals(List.of(RECIPIENT_A), store.allMarkers().get(0).recipients());
	}

	@Test
	void queriesReturnImmutableSortedResults() {
		ServerMarkerStore store = newStore();
		create(store, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_A); // id 0
		createOwnedBy(store, STRANGER, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_B); // id 1
		create(store, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_A); // id 2

		List<ServerMarker> all = store.allMarkers();
		assertEquals(List.of(new MarkerId(0L), new MarkerId(1L), new MarkerId(2L)),
			all.stream().map(ServerMarker::id).toList());
		assertThrows(UnsupportedOperationException.class, () -> all.remove(0));

		List<ServerMarker> owned = store.markersByOwner(OWNER);
		assertEquals(List.of(new MarkerId(0L), new MarkerId(2L)), owned.stream().map(ServerMarker::id).toList());
		assertThrows(UnsupportedOperationException.class, () -> owned.remove(0));

		assertEquals(new MarkerId(1L), store.find(new MarkerId(1L)).orElseThrow().id());
		assertTrue(store.find(new MarkerId(99L)).isEmpty());
	}

	@Test
	void sizeAndClearSupportServerLifecycle() {
		ServerMarkerStore store = newStore();
		create(store, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_A);
		create(store, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_A);

		assertEquals(2, store.size());

		store.clear();

		assertEquals(0, store.size());
		assertTrue(store.allMarkers().isEmpty());
		// Clearing is idempotent and the id source keeps running.
		store.clear();
		assertEquals(new MarkerId(2L), create(store, entityTarget(UUID.randomUUID()), 0L, 100L, RECIPIENT_A)
			.marker().id());
	}

	@Test
	void repeatedRunsProduceIdenticalResults() {
		Target shared = entityTarget(UUID.randomUUID());
		Target other = entityTarget(UUID.randomUUID());

		List<Object> first = runScenario(shared, other);
		for (int i = 0; i < 10; i++) {
			assertEquals(first, runScenario(shared, other));
		}
	}

	private List<Object> runScenario(Target shared, Target other) {
		ServerMarkerStore store = newStore();
		List<Object> results = new ArrayList<>();

		results.add(create(store, shared, 10L, 100L, RECIPIENT_A).winnerChanges()); // id 0
		results.add(create(store, shared, 20L, 100L, RECIPIENT_A).winnerChanges()); // id 1: winner
		results.add(create(store, other, 30L, 300L, RECIPIENT_B).winnerChanges()); // id 2
		results.add(project(store.expire(100L))); // removes id 0 and id 1
		results.add(project(store.expire(100L))); // idempotent second run
		results.add(project(store.removeOwned(OWNER, new MarkerId(2L)))); // id 2 remains owned
		results.add(store.allMarkers().stream().map(ServerMarker::id).toList());

		return List.copyOf(results);
	}

	private static List<Object> project(MarkerBatchRemoval batch) {
		return List.of(
			batch.removals().stream()
				.map(removal -> List.of(removal.marker().id(), removal.reason()))
				.toList(),
			batch.winnerChanges());
	}

	private static List<Object> project(MarkerRemovalResult result) {
		return List.of(
			result.status(),
			result.removal()
				.map(removal -> List.of(removal.marker().id(), removal.reason()))
				.orElse(null),
			result.winnerChanges());
	}

	@Test
	void createValidatesInputWithoutMutatingStore() {
		ServerMarkerStore store = newStore();
		Target target = entityTarget(UUID.randomUUID());

		assertThrows(IllegalArgumentException.class, () -> store.create(
			OWNER, target, entityType(), attentionPingType(), new MarkerAnchor(0, 0, 0), 0L, 10L, List.of()));
		assertThrows(IllegalArgumentException.class, () -> store.create(
			OWNER, target, entityType(), attentionPingType(), new MarkerAnchor(0, 0, 0), 10L, 10L,
			List.of(RECIPIENT_A)));
		assertThrows(NullPointerException.class, () -> store.create(
			null, target, entityType(), attentionPingType(), new MarkerAnchor(0, 0, 0), 0L, 10L,
			List.of(RECIPIENT_A)));

		assertEquals(0, store.size());
	}

	@Test
	void exhaustedIdSourceCreateFailsWithoutMutatingStore() {
		// Package-private test seam: start the source already exhausted so the
		// first nextId() call after Long.MAX_VALUE fails.
		ServerMarkerStore store = new ServerMarkerStore(new MarkerIdSource(Long.MAX_VALUE));
		Target target = entityTarget(UUID.randomUUID());
		TargetKey key = TargetKey.from(target);

		MarkerCreation first = create(store, target, 10L, 110L, RECIPIENT_A);
		assertEquals(new MarkerId(Long.MAX_VALUE), first.marker().id());

		assertThrows(IllegalStateException.class,
			() -> create(store, entityTarget(UUID.randomUUID()), 20L, 120L, RECIPIENT_B));

		assertEquals(1, store.size());
		assertEquals(List.of(new MarkerId(Long.MAX_VALUE)),
			store.allMarkers().stream().map(ServerMarker::id).toList());
		assertEquals(new MarkerId(Long.MAX_VALUE),
			store.winnerFor(key, RECIPIENT_A).orElseThrow().id());
	}

	@Test
	void storeRejectsNullArguments() {
		ServerMarkerStore store = newStore();
		TargetKey key = TargetKey.from(entityTarget(UUID.randomUUID()));

		assertThrows(NullPointerException.class, () -> new ServerMarkerStore(null));
		assertThrows(NullPointerException.class, () -> store.removeOwned(null, new MarkerId(0L)));
		assertThrows(NullPointerException.class, () -> store.removeOwned(OWNER, null));
		assertThrows(NullPointerException.class, () -> store.removeOwnedBy(null));
		assertThrows(NullPointerException.class, () -> store.removeOwnedBy(OWNER, null));
		assertThrows(NullPointerException.class, () -> store.forgetRecipient(null));
		assertThrows(NullPointerException.class, () -> store.find(null));
		assertThrows(NullPointerException.class, () -> store.markersByOwner(null));
		assertThrows(NullPointerException.class, () -> store.winnerFor(null, RECIPIENT_A));
		assertThrows(NullPointerException.class, () -> store.winnerFor(key, null));
	}

	@Test
	void emptyStoreBehavesDeterministically() {
		ServerMarkerStore store = newStore();

		assertEquals(0, store.size());
		assertTrue(store.allMarkers().isEmpty());
		assertTrue(store.expire(1000L).removals().isEmpty());
		assertTrue(store.expire(1000L).winnerChanges().isEmpty());
		assertTrue(store.removeOwnedBy(OWNER).removals().isEmpty());
		assertTrue(store.winnerFor(TargetKey.from(entityTarget(UUID.randomUUID())), RECIPIENT_A).isEmpty());
	}
}
