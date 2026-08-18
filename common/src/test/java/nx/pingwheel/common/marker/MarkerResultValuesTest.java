package nx.pingwheel.common.marker;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerResultValuesTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID RECIPIENT_A = UUID.randomUUID();
	private static final UUID RECIPIENT_B = UUID.randomUUID();

	private static final PingTypeCatalog PING_TYPES = PingTypeCatalog.builtIn();
	private static final TargetTypeCatalog TARGET_TYPES = TargetTypeCatalog.builtIn();

	private static ServerMarker marker(long id, long arrivalTick, UUID... recipients) {
		return new ServerMarker(
			new MarkerId(id),
			OWNER,
			new Target.EntityTarget(OVERWORLD, UUID.randomUUID()),
			TARGET_TYPES.findById("entity").orElseThrow(),
			PING_TYPES.findById("attention").orElseThrow(),
			new MarkerAnchor(0, 0, 0),
			arrivalTick,
			arrivalTick + 100L,
			List.of(recipients));
	}

	private static MarkerWinnerChange change(UUID recipient, long previous, long current) {
		return new MarkerWinnerChange(
			TargetKey.from(new Target.EntityTarget(OVERWORLD, UUID.randomUUID())),
			recipient,
			Optional.of(new MarkerId(previous)),
			Optional.of(new MarkerId(current)));
	}

	@Test
	void winnerChangeRejectsNullArguments() {
		TargetKey key = TargetKey.from(new Target.EntityTarget(OVERWORLD, UUID.randomUUID()));

		assertThrows(NullPointerException.class,
			() -> new MarkerWinnerChange(null, RECIPIENT_A, Optional.empty(), Optional.of(new MarkerId(1L))));
		assertThrows(NullPointerException.class,
			() -> new MarkerWinnerChange(key, null, Optional.empty(), Optional.of(new MarkerId(1L))));
		assertThrows(NullPointerException.class,
			() -> new MarkerWinnerChange(key, RECIPIENT_A, null, Optional.of(new MarkerId(1L))));
		assertThrows(NullPointerException.class,
			() -> new MarkerWinnerChange(key, RECIPIENT_A, Optional.empty(), null));
	}

	@Test
	void winnerChangeRejectsEqualWinners() {
		TargetKey key = TargetKey.from(new Target.EntityTarget(OVERWORLD, UUID.randomUUID()));

		// Both empty is not a transition and must be rejected.
		assertThrows(IllegalArgumentException.class,
			() -> new MarkerWinnerChange(key, RECIPIENT_A, Optional.empty(), Optional.empty()));
		// Same id on both sides is not a transition either.
		assertThrows(IllegalArgumentException.class,
			() -> new MarkerWinnerChange(
				key, RECIPIENT_A, Optional.of(new MarkerId(5L)), Optional.of(new MarkerId(5L))));
	}

	@Test
	void winnerChangeAcceptsDistinctWinners() {
		MarkerWinnerChange change = change(RECIPIENT_A, 3L, 4L);

		assertEquals(Optional.of(new MarkerId(3L)), change.previousWinner());
		assertEquals(Optional.of(new MarkerId(4L)), change.currentWinner());
	}

	@Test
	void markerCreationIsImmutableAndStrict() {
		ServerMarker marker = marker(0L, 10L, RECIPIENT_A);
		MarkerWinnerChange change = change(RECIPIENT_A, 0L, 1L);
		List<MarkerWinnerChange> input = new ArrayList<>(List.of(change));

		MarkerCreation creation = new MarkerCreation(marker, input);

		input.clear();
		assertEquals(List.of(change), creation.winnerChanges());
		assertThrows(UnsupportedOperationException.class, () -> creation.winnerChanges().add(change));

		assertThrows(NullPointerException.class, () -> new MarkerCreation(null, List.of()));
		assertThrows(NullPointerException.class, () -> new MarkerCreation(marker, null));
		assertThrows(NullPointerException.class,
			() -> new MarkerCreation(marker, java.util.Arrays.asList(change, null)));
	}

	@Test
	void markerRemovalIsImmutableAndStrict() {
		ServerMarker marker = marker(0L, 10L, RECIPIENT_A);
		MarkerRemoval removal = new MarkerRemoval(marker, MarkerRemovalReason.CANCELLED);

		assertEquals(marker, removal.marker());
		assertEquals(MarkerRemovalReason.CANCELLED, removal.reason());

		assertThrows(NullPointerException.class, () -> new MarkerRemoval(null, MarkerRemovalReason.EXPIRED));
		assertThrows(NullPointerException.class, () -> new MarkerRemoval(marker, null));
	}

	@Test
	void markerBatchRemovalIsImmutableAndStrict() {
		ServerMarker marker = marker(0L, 10L, RECIPIENT_A);
		MarkerRemoval removal = new MarkerRemoval(marker, MarkerRemovalReason.EXPIRED);
		MarkerWinnerChange change = change(RECIPIENT_A, 0L, 1L);
		List<MarkerRemoval> removalsInput = new ArrayList<>(List.of(removal));
		List<MarkerWinnerChange> changesInput = new ArrayList<>(List.of(change));

		MarkerBatchRemoval batch = new MarkerBatchRemoval(removalsInput, changesInput);

		removalsInput.clear();
		changesInput.clear();
		assertEquals(List.of(removal), batch.removals());
		assertEquals(List.of(change), batch.winnerChanges());
		assertThrows(UnsupportedOperationException.class, () -> batch.removals().add(removal));
		assertThrows(UnsupportedOperationException.class, () -> batch.winnerChanges().add(change));

		assertThrows(NullPointerException.class, () -> new MarkerBatchRemoval(null, List.of()));
		assertThrows(NullPointerException.class, () -> new MarkerBatchRemoval(List.of(), null));
		assertThrows(NullPointerException.class,
			() -> new MarkerBatchRemoval(java.util.Arrays.asList(removal, null), List.of()));
	}

	@Test
	void removalResultRemovedFactoryIsStrict() {
		ServerMarker marker = marker(0L, 10L, RECIPIENT_A);
		MarkerRemoval removal = new MarkerRemoval(marker, MarkerRemovalReason.CANCELLED);
		MarkerWinnerChange change = change(RECIPIENT_A, 0L, 1L);
		List<MarkerWinnerChange> input = new ArrayList<>(List.of(change));

		MarkerRemovalResult result = MarkerRemovalResult.removed(removal, input);

		assertEquals(MarkerRemovalResult.Status.REMOVED, result.status());
		assertEquals(removal, result.removal().orElseThrow());
		input.clear();
		assertEquals(List.of(change), result.winnerChanges());
		assertThrows(UnsupportedOperationException.class, () -> result.winnerChanges().add(change));

		assertThrows(NullPointerException.class, () -> MarkerRemovalResult.removed(null, List.of()));
		assertThrows(NullPointerException.class, () -> MarkerRemovalResult.removed(removal, null));
		assertThrows(NullPointerException.class,
			() -> MarkerRemovalResult.removed(removal, java.util.Arrays.asList(change, null)));
	}

	@Test
	void removalResultNotFoundAndNotOwnerCarryNothing() {
		MarkerRemovalResult notFound = MarkerRemovalResult.notFound();
		MarkerRemovalResult notOwner = MarkerRemovalResult.notOwner();

		assertEquals(MarkerRemovalResult.Status.NOT_FOUND, notFound.status());
		assertEquals(MarkerRemovalResult.Status.NOT_OWNER, notOwner.status());
		assertTrue(notFound.removal().isEmpty());
		assertTrue(notOwner.removal().isEmpty());
		assertTrue(notFound.winnerChanges().isEmpty());
		assertTrue(notOwner.winnerChanges().isEmpty());
		assertThrows(UnsupportedOperationException.class,
			() -> notFound.winnerChanges().add(change(RECIPIENT_A, 0L, 1L)));
	}

	@Test
	void removalResultStatusEnumIsComplete() {
		assertEquals(3, MarkerRemovalResult.Status.values().length);
		assertEquals("REMOVED", MarkerRemovalResult.Status.REMOVED.name());
		assertEquals("NOT_FOUND", MarkerRemovalResult.Status.NOT_FOUND.name());
		assertEquals("NOT_OWNER", MarkerRemovalResult.Status.NOT_OWNER.name());
	}

	@Test
	void markerRemovalAndCreationComposeWithEachOther() {
		ServerMarker marker = marker(0L, 10L, RECIPIENT_A, RECIPIENT_B);
		MarkerRemoval removal = new MarkerRemoval(marker, MarkerRemovalReason.OWNER_DISCONNECTED);
		MarkerRemovalResult result = MarkerRemovalResult.removed(
			removal, List.of(
				change(RECIPIENT_A, 0L, 1L),
				change(RECIPIENT_B, 0L, 2L)));

		assertEquals(marker, result.removal().orElseThrow().marker());
		assertEquals(2, result.winnerChanges().size());
	}
}
