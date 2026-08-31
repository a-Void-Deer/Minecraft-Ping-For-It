package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the main-thread-confined block outline state.
 */
class BlockOutlineStateTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID OWNER = new UUID(0L, 100L);

	/**
	 * Records the exact transition tuples; the logger interface only carries
	 * the aggregate counts, so block positions, registry ids, dimensions,
	 * colors, and names can never reach it.
	 */
	private record Transition(int added, int removed, int changed, int total) {}

	private final List<Transition> transitions = new ArrayList<>();

	@AfterEach
	void tearDown() {
		BlockOutlineState.INSTANCE.clear();
		BlockOutlineState.resetLogger();
	}

	private BlockOutlineState installRecordingLogger() {
		BlockOutlineState.setLogger(
			(added, removed, changed, total) -> transitions.add(
				new Transition(added, removed, changed, total)));
		return BlockOutlineState.INSTANCE;
	}

	private static Target blockTarget(int x, int y, int z) {
		return new Target.BlockTarget(OVERWORLD, x, y, z, "minecraft:stone");
	}

	private static TargetKey.BlockKey keyAt(int x, int y, int z) {
		return new TargetKey.BlockKey(OVERWORLD, x, y, z, "minecraft:stone");
	}

	private static Target.ExternalBlockTarget externalTarget() {
		return Target.ExternalBlockTarget.committed(
			OVERWORLD, "provider:test", "tracking-id", "minecraft:chest", "locator", true);
	}

	private static MarkerSnapshot snapshot(long id, Target target, String pingTypeId) {
		return new MarkerSnapshot(
			new MarkerId(id),
			OWNER,
			target,
			"block",
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
		BlockOutlineState state = installRecordingLogger();
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target targetA = blockTarget(1, 2, 3);
		Target targetB = blockTarget(4, 5, 6);

		// Block B carries the smaller marker id, block A the larger one, and
		// winners are announced in the reverse order: ascending marker-id
		// iteration (B, A) differs from both position order (A, B) and
		// announcement order (A, B).
		store.onCreated(snapshot(2L, targetA, "attention"), 0L);
		store.onCreated(snapshot(1L, targetB, "attention"), 0L);
		store.onWinnerChanged(TargetKey.from(targetA), Optional.of(new MarkerId(2L)));
		store.onWinnerChanged(TargetKey.from(targetB), Optional.of(new MarkerId(1L)));

		state.prepare(store, OVERWORLD);

		assertEquals(List.of(keyAt(4, 5, 6), keyAt(1, 2, 3)),
			new ArrayList<>(state.snapshot().keySet()));
		assertThrows(UnsupportedOperationException.class,
			() -> state.snapshot().put(keyAt(1, 2, 3), null));
	}

	@Test
	void preparePopulatesSnapshotAndQueries() {
		BlockOutlineState state = installRecordingLogger();
		Target target = blockTarget(1, 2, 3);

		state.prepare(storeWithWinner(5L, target, "go_to"), OVERWORLD);

		assertEquals(new MarkerId(5L), state.specFor(keyAt(1, 2, 3)).markerId());
		assertEquals(0xFF4DB8FF, state.colorFor(keyAt(1, 2, 3)));
		assertNull(state.specFor(keyAt(4, 5, 6)));
		assertEquals(0, state.colorFor(keyAt(4, 5, 6)));
	}

	@Test
	void repeatedIdenticalPrepareEmitsNoLog() {
		BlockOutlineState state = installRecordingLogger();
		ClientMarkerStore store = storeWithWinner(5L, blockTarget(1, 2, 3), "attention");

		state.prepare(store, OVERWORLD);
		state.prepare(store, OVERWORLD);
		state.prepare(store, OVERWORLD);

		assertEquals(List.of(new Transition(1, 0, 0, 1)), transitions);
	}

	@Test
	void transitionsReportOnlyAggregateCounts() {
		BlockOutlineState state = installRecordingLogger();

		// add A
		state.prepare(storeWithWinner(1L, blockTarget(1, 2, 3), "attention"), OVERWORLD);
		// replace A with B: one added, one removed
		state.prepare(storeWithWinner(2L, blockTarget(4, 5, 6), "attention"), OVERWORLD);
		// same block, different winner payload: one changed
		state.prepare(storeWithWinner(3L, blockTarget(4, 5, 6), "danger"), OVERWORLD);

		assertEquals(List.of(
			new Transition(1, 0, 0, 1),
			new Transition(1, 1, 0, 1),
			new Transition(0, 0, 1, 1)
		), transitions);
	}

	@Test
	void winnerChangeUpdatesColorAndSpec() {
		BlockOutlineState state = installRecordingLogger();
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target target = blockTarget(1, 2, 3);
		TargetKey key = TargetKey.from(target);

		store.onCreated(snapshot(1L, target, "attention"), 0L);
		store.onCreated(snapshot(2L, target, "loot"), 0L);
		store.onWinnerChanged(key, Optional.of(new MarkerId(1L)));

		state.prepare(store, OVERWORLD);
		assertEquals(0xFFFFC247, state.colorFor(keyAt(1, 2, 3)));

		store.onWinnerChanged(key, Optional.of(new MarkerId(2L)));

		state.prepare(store, OVERWORLD);
		assertEquals(0xFF52D273, state.colorFor(keyAt(1, 2, 3)));
		assertEquals(new Transition(0, 0, 1, 1), transitions.get(1));
	}

	// --- clear / absent runtime ---

	@Test
	void clearEmitsTransitionAndEmptiesState() {
		BlockOutlineState state = installRecordingLogger();

		state.prepare(storeWithWinner(5L, blockTarget(1, 2, 3), "attention"), OVERWORLD);
		state.clear();

		assertNull(state.specFor(keyAt(1, 2, 3)));
		assertEquals(0, state.colorFor(keyAt(1, 2, 3)));
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
		BlockOutlineState state = installRecordingLogger();

		state.prepare(storeWithWinner(5L, blockTarget(1, 2, 3), "attention"), OVERWORLD);

		state.prepare(null, OVERWORLD);
		state.prepare(storeWithWinner(5L, blockTarget(1, 2, 3), "attention"), null);

		assertNull(state.specFor(keyAt(1, 2, 3)));
		assertTrue(state.snapshot().isEmpty());
		assertEquals(List.of(
			new Transition(1, 0, 0, 1),
			new Transition(0, 1, 0, 0)
		), transitions);
	}

	@Test
	void prepareAndClearTracksExternalSnapshotSeparately() {
		BlockOutlineState state = installRecordingLogger();
		Target.ExternalBlockTarget target = externalTarget();
		TargetKey key = TargetKey.from(target);
		ClientMarkerStore store = new ClientMarkerStore(10L);
		MarkerId markerId = new MarkerId(6L);

		store.onCreated(
			new MarkerSnapshot(
				markerId,
				OWNER,
				target,
				"entity_block",
				"attention",
				new MarkerAnchor(1, 2, 3),
				1L,
				100L),
			0L);
		store.onWinnerChanged(key, Optional.of(markerId));

		state.prepare(store, OVERWORLD);

		assertTrue(state.snapshot().isEmpty());
		assertEquals(target, state.externalSnapshot().get(key).target());
		assertEquals(markerId, state.externalSnapshot().get(key).markerId());

		state.clear();

		assertTrue(state.externalSnapshot().isEmpty());
		assertEquals(List.of(
			new Transition(1, 0, 0, 1),
			new Transition(0, 1, 0, 0)
		), transitions);
	}

	@Test
	void externalCoverageRequiresNonzeroEmittedVerticesBeforeFallbackSuppression() {
		BlockOutlineState state = installRecordingLogger();
		BlockModelOutlineState modelState = BlockModelOutlineState.INSTANCE;
		modelState.clear();
		Target.ExternalBlockTarget target = externalTarget();
		TargetKey.ExternalBlockKey key = (TargetKey.ExternalBlockKey) TargetKey.from(target);
		ClientMarkerStore store = new ClientMarkerStore(10L);
		MarkerId markerId = new MarkerId(6L);

		store.onCreated(
			new MarkerSnapshot(
				markerId,
				OWNER,
				target,
				"entity_block",
				"attention",
				new MarkerAnchor(1, 2, 3),
				1L,
				100L),
			0L);
		store.onWinnerChanged(key, Optional.of(markerId));
		state.prepare(store, OVERWORLD);

		assertEquals(
			EntityBlockGeometryOutcome.EMPTY,
			EntityBlockGeometryOutcome.fromEmittedVertices(0));
		assertFalse(state.allCoveredBy(
			modelState.presentations(), modelState.successKeys(), modelState.externalSuccessKeys()));

		assertEquals(
			EntityBlockGeometryOutcome.RENDERED,
			EntityBlockGeometryOutcome.fromEmittedVertices(1));
		modelState.addExternalSuccess(key);
		assertTrue(state.allCoveredBy(
			modelState.presentations(), modelState.successKeys(), modelState.externalSuccessKeys()));
		modelState.clear();
	}
}
