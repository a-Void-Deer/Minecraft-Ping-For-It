package nx.pingwheel.common.client.marker;

import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure id-level synchronization plan of
 * {@link MarkerOverlayState#syncPlan}.
 */
class MarkerOverlayStateSyncPlanTest {

	private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

	@Test
	void addsUnknownIds() {
		var plan = MarkerOverlayState.syncPlan(Set.of(), Set.of(marker(1), marker(2)));

		assertEquals(Set.of(new MarkerId(1), new MarkerId(2)), plan.toAdd());
		assertTrue(plan.toReplace().isEmpty());
		assertTrue(plan.toRemove().isEmpty());
	}

	@Test
	void replacesOverlappingIdsAndDropsMissingOnes() {
		var known = Set.of(new MarkerId(1), new MarkerId(2));

		var plan = MarkerOverlayState.syncPlan(known, Set.of(marker(2), marker(3)));

		assertEquals(Set.of(new MarkerId(3)), plan.toAdd());
		assertEquals(Set.of(new MarkerId(2)), plan.toReplace());
		assertEquals(Set.of(new MarkerId(1)), plan.toRemove());
	}

	@Test
	void removesAllKnownIdsWhenStoreIsEmpty() {
		var known = Set.of(new MarkerId(1), new MarkerId(7));

		var plan = MarkerOverlayState.syncPlan(known, Set.of());

		assertTrue(plan.toAdd().isEmpty());
		assertTrue(plan.toReplace().isEmpty());
		assertEquals(known, plan.toRemove());
	}

	@Test
	void emptyInputsProduceEmptyPlan() {
		var plan = MarkerOverlayState.syncPlan(Set.of(), Set.of());

		assertTrue(plan.toAdd().isEmpty());
		assertTrue(plan.toReplace().isEmpty());
		assertTrue(plan.toRemove().isEmpty());
	}

	@Test
	void rejectsNullArguments() {
		assertThrows(NullPointerException.class, () -> MarkerOverlayState.syncPlan(null, Set.of()));
		assertThrows(NullPointerException.class, () -> MarkerOverlayState.syncPlan(Set.of(), null));
	}

	private static ClientMarker marker(long id) {
		return new ClientMarker(
			new MarkerId(id),
			OWNER,
			new Target.LocationTarget("minecraft:overworld", 0, 0, 0),
			"block",
			"attention",
			new MarkerAnchor(0, 0, 0),
			1L,
			100L,
			0L,
			100L);
	}
}
