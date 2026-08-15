package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the main-thread-confined entity outline state.
 */
class EntityOutlineStateTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID OWNER = new UUID(0L, 100L);
	private static final UUID ENTITY_A = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
	private static final UUID ENTITY_B = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");

	/**
	 * Records the exact transition tuples; the logger interface only carries
	 * the aggregate counts, so UUIDs, dimensions, colors, positions, and
	 * names can never reach it.
	 */
	private record Transition(int added, int removed, int changed, int total) {}

	private final List<Transition> transitions = new ArrayList<>();

	@AfterEach
	void tearDown() {
		EntityOutlineState.INSTANCE.clear();
		EntityOutlineState.resetLogger();
	}

	private EntityOutlineState installRecordingLogger() {
		EntityOutlineState.setLogger(
			(added, removed, changed, total) -> transitions.add(
				new Transition(added, removed, changed, total)));
		return EntityOutlineState.INSTANCE;
	}

	private static Target entityTarget(UUID entityId) {
		return new Target.EntityTarget(OVERWORLD, entityId);
	}

	private static MarkerSnapshot snapshot(long id, Target target, String pingTypeId) {
		return new MarkerSnapshot(
			new MarkerId(id),
			OWNER,
			target,
			"entity",
			pingTypeId,
			new MarkerAnchor(0, 0, 0),
			1L,
			100L);
	}

	private static ClientMarkerStore storeWithWinner(long id, Target target, String pingTypeId) {
		ClientMarkerStore store = new ClientMarkerStore(10L);
		store.onCreated(snapshot(id, target, pingTypeId), 0L);
		store.onWinnerChanged(TargetKey.from(target), Optional.of(new MarkerId(id)));
		return store;
	}

	// --- snapshot queries ---

	@Test
	void snapshotPreservesAscendingMarkerIdIterationOrder() {
		EntityOutlineState state = installRecordingLogger();
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target targetA = entityTarget(ENTITY_A);
		Target targetB = entityTarget(ENTITY_B);

		// ENTITY_B carries the smaller marker id, ENTITY_A the larger one, and
		// winners are announced in the reverse order: ascending marker-id
		// iteration (B, A) differs from both UUID order (A, B) and
		// announcement order (A, B).
		store.onCreated(snapshot(2L, targetA, "attention"), 0L);
		store.onCreated(snapshot(1L, targetB, "attention"), 0L);
		store.onWinnerChanged(TargetKey.from(targetA), Optional.of(new MarkerId(2L)));
		store.onWinnerChanged(TargetKey.from(targetB), Optional.of(new MarkerId(1L)));

		state.prepare(store, OVERWORLD);

		assertEquals(List.of(ENTITY_B, ENTITY_A), new ArrayList<>(state.snapshot().keySet()));
		assertThrows(UnsupportedOperationException.class,
			() -> state.snapshot().put(ENTITY_A, null));
	}

	@Test
	void preparePopulatesSnapshotAndQueries() {
		EntityOutlineState state = installRecordingLogger();
		Target target = entityTarget(ENTITY_A);

		state.prepare(storeWithWinner(5L, target, "go_to"), OVERWORLD);

		assertTrue(state.shouldOutline(ENTITY_A));
		assertEquals(0xFF4DB8FF, state.colorFor(ENTITY_A));
		assertFalse(state.shouldOutline(ENTITY_B));
		assertEquals(0, state.colorFor(ENTITY_B));
	}

	@Test
	void repeatedIdenticalPrepareEmitsNoLog() {
		EntityOutlineState state = installRecordingLogger();
		ClientMarkerStore store = storeWithWinner(5L, entityTarget(ENTITY_A), "attention");

		state.prepare(store, OVERWORLD);
		state.prepare(store, OVERWORLD);
		state.prepare(store, OVERWORLD);

		assertEquals(List.of(new Transition(1, 0, 0, 1)), transitions);
	}

	@Test
	void transitionsReportOnlyAggregateCounts() {
		EntityOutlineState state = installRecordingLogger();

		// add A
		state.prepare(storeWithWinner(1L, entityTarget(ENTITY_A), "attention"), OVERWORLD);
		// replace A with B: one added, one removed
		state.prepare(storeWithWinner(2L, entityTarget(ENTITY_B), "attention"), OVERWORLD);
		// same entity, different winner payload: one changed
		state.prepare(storeWithWinner(3L, entityTarget(ENTITY_B), "danger"), OVERWORLD);

		assertEquals(List.of(
			new Transition(1, 0, 0, 1),
			new Transition(1, 1, 0, 1),
			new Transition(0, 0, 1, 1)
		), transitions);
	}

	@Test
	void winnerChangeUpdatesColorAndSpec() {
		EntityOutlineState state = installRecordingLogger();
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target target = entityTarget(ENTITY_A);
		TargetKey key = TargetKey.from(target);

		store.onCreated(snapshot(1L, target, "attention"), 0L);
		store.onCreated(snapshot(2L, target, "loot"), 0L);
		store.onWinnerChanged(key, Optional.of(new MarkerId(1L)));

		state.prepare(store, OVERWORLD);
		assertEquals(0xFFFFC247, state.colorFor(ENTITY_A));

		store.onWinnerChanged(key, Optional.of(new MarkerId(2L)));

		state.prepare(store, OVERWORLD);
		assertEquals(0xFF52D273, state.colorFor(ENTITY_A));
		assertEquals(new Transition(0, 0, 1, 1), transitions.get(1));
	}

	// --- clear / absent runtime ---

	@Test
	void clearEmitsTransitionAndEmptiesState() {
		EntityOutlineState state = installRecordingLogger();

		state.prepare(storeWithWinner(5L, entityTarget(ENTITY_A), "attention"), OVERWORLD);
		state.clear();

		assertFalse(state.shouldOutline(ENTITY_A));
		assertEquals(0, state.colorFor(ENTITY_A));
		assertEquals(List.of(
			new Transition(1, 0, 0, 1),
			new Transition(0, 1, 0, 0)
		), transitions);

		// A second clear of the already-empty state logs nothing.
		state.clear();
		assertEquals(2, transitions.size());
	}

	@Test
	void prepareWithNullStoreOrDimensionClears() {
		EntityOutlineState state = installRecordingLogger();

		state.prepare(storeWithWinner(5L, entityTarget(ENTITY_A), "attention"), OVERWORLD);

		state.prepare(null, OVERWORLD);
		state.prepare(storeWithWinner(5L, entityTarget(ENTITY_A), "attention"), null);

		assertFalse(state.shouldOutline(ENTITY_A));
		assertEquals(List.of(
			new Transition(1, 0, 0, 1),
			new Transition(0, 1, 0, 0)
		), transitions);
	}
}
