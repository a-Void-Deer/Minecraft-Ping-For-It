package nx.pingwheel.common.marker;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerWinnerTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID RECIPIENT_A = UUID.randomUUID();
	private static final UUID RECIPIENT_B = UUID.randomUUID();

	private static final PingTypeCatalog PING_TYPES = PingTypeCatalog.builtIn();
	private static final TargetTypeCatalog TARGET_TYPES = TargetTypeCatalog.builtIn();

	private static ServerMarker marker(long id, Target target, long arrivalTick, UUID... recipients) {
		return new ServerMarker(
			new MarkerId(id),
			OWNER,
			target,
			TARGET_TYPES.findById("entity").orElseThrow(),
			PING_TYPES.findById("attention").orElseThrow(),
			new MarkerAnchor(0, 0, 0),
			arrivalTick,
			arrivalTick + 100L,
			List.of(recipients));
	}

	private static Target entityTarget(UUID uuid) {
		return new Target.EntityTarget(OVERWORLD, uuid);
	}

	@Test
	void latestArrivalWins() {
		UUID uuid = UUID.randomUUID();
		Target target = entityTarget(uuid);
		ServerMarker older = marker(1L, target, 10L, RECIPIENT_A);
		ServerMarker newer = marker(2L, target, 20L, RECIPIENT_A);

		assertEquals(new MarkerId(2L),
			MarkerWinner.winnerFor(List.of(older, newer), TargetKey.from(target), RECIPIENT_A).orElseThrow().id());
	}

	@Test
	void equalArrivalResolvesToLargerId() {
		UUID uuid = UUID.randomUUID();
		Target target = entityTarget(uuid);
		ServerMarker small = marker(1L, target, 10L, RECIPIENT_A);
		ServerMarker large = marker(5L, target, 10L, RECIPIENT_A);

		assertEquals(new MarkerId(5L),
			MarkerWinner.winnerFor(List.of(small, large), TargetKey.from(target), RECIPIENT_A).orElseThrow().id());
	}

	@Test
	void differentTargetKeysAreIgnored() {
		UUID uuidA = UUID.randomUUID();
		UUID uuidB = UUID.randomUUID();
		ServerMarker onA = marker(1L, entityTarget(uuidA), 10L, RECIPIENT_A);
		ServerMarker onB = marker(2L, entityTarget(uuidB), 99L, RECIPIENT_A);

		// onB arrived later but refers to a different target key; it must not win for A.
		assertEquals(new MarkerId(1L),
			MarkerWinner.winnerFor(List.of(onA, onB), TargetKey.from(entityTarget(uuidA)), RECIPIENT_A).orElseThrow().id());
	}

	@Test
	void uuidAndRuntimeLocatorsUseIndependentWinnerKeys() {
		Target uuidTarget = new Target.EntityTarget(OVERWORLD, EntityLocator.uuid(new UUID(0L, 7L)));
		Target runtimeTarget = new Target.EntityTarget(OVERWORLD, EntityLocator.runtimeId(7));
		ServerMarker uuidMarker = marker(1L, uuidTarget, 20L, RECIPIENT_A);
		ServerMarker runtimeMarker = marker(2L, runtimeTarget, 10L, RECIPIENT_A);

		assertEquals(new MarkerId(1L),
			MarkerWinner.winnerFor(List.of(uuidMarker, runtimeMarker), TargetKey.from(uuidTarget), RECIPIENT_A)
				.orElseThrow().id());
		assertEquals(new MarkerId(2L),
			MarkerWinner.winnerFor(List.of(uuidMarker, runtimeMarker), TargetKey.from(runtimeTarget), RECIPIENT_A)
				.orElseThrow().id());
	}

	@Test
	void winnerIsPerRecipient() {
		UUID uuid = UUID.randomUUID();
		Target target = entityTarget(uuid);
		TargetKey key = TargetKey.from(target);

		ServerMarker forA = marker(1L, target, 20L, RECIPIENT_A);
		ServerMarker forB = marker(2L, target, 10L, RECIPIENT_B);

		assertEquals(new MarkerId(1L), MarkerWinner.winnerFor(List.of(forA, forB), key, RECIPIENT_A).orElseThrow().id());
		assertEquals(new MarkerId(2L), MarkerWinner.winnerFor(List.of(forA, forB), key, RECIPIENT_B).orElseThrow().id());
	}

	@Test
	void noCandidateYieldsEmpty() {
		UUID uuid = UUID.randomUUID();
		Target target = entityTarget(uuid);
		ServerMarker forA = marker(1L, target, 10L, RECIPIENT_A);

		// recipient not visible to any marker
		assertTrue(MarkerWinner.winnerFor(List.of(forA), TargetKey.from(target), RECIPIENT_B).isEmpty());
		// wrong key
		assertTrue(MarkerWinner.winnerFor(List.of(forA), TargetKey.from(entityTarget(UUID.randomUUID())), RECIPIENT_A).isEmpty());
		// empty collection
		assertTrue(MarkerWinner.winnerFor(List.of(), TargetKey.from(target), RECIPIENT_A).isEmpty());
	}

	@Test
	void resultIsDeterministicAcrossIterationOrders() {
		UUID uuid = UUID.randomUUID();
		Target target = entityTarget(uuid);
		TargetKey key = TargetKey.from(target);

		List<ServerMarker> markers = new ArrayList<>(List.of(
			marker(3L, target, 15L, RECIPIENT_A),
			marker(1L, target, 10L, RECIPIENT_A),
			marker(4L, target, 15L, RECIPIENT_A),
			marker(2L, target, 20L, RECIPIENT_A)));

		for (int i = 0; i < 25; i++) {
			Collections.shuffle(markers);
			assertEquals(new MarkerId(2L), MarkerWinner.winnerFor(markers, key, RECIPIENT_A).orElseThrow().id());
		}
	}

	@Test
	void mixedTargetKeyCollectionIsHandledWithoutAmbiguity() {
		UUID uuidA = UUID.randomUUID();
		UUID uuidB = UUID.randomUUID();
		UUID uuidC = UUID.randomUUID();
		Target targetA = entityTarget(uuidA);
		TargetKey keyA = TargetKey.from(targetA);

		List<ServerMarker> markers = List.of(
			marker(1L, targetA, 10L, RECIPIENT_A),
			marker(9L, entityTarget(uuidB), 50L, RECIPIENT_A),
			marker(8L, entityTarget(uuidC), 50L, RECIPIENT_A),
			marker(2L, targetA, 20L, RECIPIENT_A));

		assertEquals(new MarkerId(2L), MarkerWinner.winnerFor(markers, keyA, RECIPIENT_A).orElseThrow().id());
	}

	@Test
	void arrivalThenIdComparatorOrdersLatestThenLargerLast() {
		UUID uuid = UUID.randomUUID();
		Target target = entityTarget(uuid);

		ServerMarker a = marker(1L, target, 10L, RECIPIENT_A);
		ServerMarker b = marker(9L, target, 10L, RECIPIENT_A);
		ServerMarker c = marker(5L, target, 20L, RECIPIENT_A);

		assertTrue(MarkerWinner.ARRIVAL_THEN_ID.compare(a, b) < 0);
		assertTrue(MarkerWinner.ARRIVAL_THEN_ID.compare(b, c) < 0);
		assertTrue(MarkerWinner.ARRIVAL_THEN_ID.compare(c, a) > 0);
	}

	@Test
	void rejectsNullArguments() {
		UUID uuid = UUID.randomUUID();
		Target target = entityTarget(uuid);
		TargetKey key = TargetKey.from(target);
		List<ServerMarker> markers = List.of(marker(1L, target, 10L, RECIPIENT_A));

		assertThrows(NullPointerException.class, () -> MarkerWinner.winnerFor(null, key, RECIPIENT_A));
		assertThrows(NullPointerException.class, () -> MarkerWinner.winnerFor(markers, null, RECIPIENT_A));
		assertThrows(NullPointerException.class, () -> MarkerWinner.winnerFor(markers, key, null));
	}

	@Test
	void rejectsNullElementInCollection() {
		UUID uuid = UUID.randomUUID();
		Target target = entityTarget(uuid);
		TargetKey key = TargetKey.from(target);
		List<ServerMarker> markers = new ArrayList<>(List.of(marker(1L, target, 10L, RECIPIENT_A)));
		markers.add(null);

		assertThrows(NullPointerException.class, () -> MarkerWinner.winnerFor(markers, key, RECIPIENT_A));
	}
}
