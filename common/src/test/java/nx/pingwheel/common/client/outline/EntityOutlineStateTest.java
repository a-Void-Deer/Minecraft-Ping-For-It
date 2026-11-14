package nx.pingwheel.common.client.outline;

import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the main-thread-confined static entity outline state.
 */
class EntityOutlineStateTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID OWNER = new UUID(0L, 100L);
	private static final UUID ENTITY_A = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
	private static final UUID ENTITY_B = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");

	@BeforeEach
	void clearBeforeTest() {
		EntityOutlineState.clear();
	}

	@AfterEach
	void clearAfterTest() {
		EntityOutlineState.clear();
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

	@Test
	void prepareExposesEveryVisibleEntityWinner() {
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target targetA = entityTarget(ENTITY_A);
		Target targetB = entityTarget(ENTITY_B);

		store.onCreated(snapshot(2L, targetA, "attention"), 0L);
		store.onCreated(snapshot(1L, targetB, "attention"), 0L);
		store.onWinnerChanged(TargetKey.from(targetA), Optional.of(new MarkerId(2L)));
		store.onWinnerChanged(TargetKey.from(targetB), Optional.of(new MarkerId(1L)));

		EntityOutlineState.prepare(store, OVERWORLD);

		assertTrue(EntityOutlineState.shouldOutline(EntityLocator.uuid(ENTITY_A)));
		assertTrue(EntityOutlineState.shouldOutline(EntityLocator.uuid(ENTITY_B)));
		assertEquals(Integer.valueOf(0xFFFFC247),
			EntityOutlineState.colorFor(EntityLocator.uuid(ENTITY_A)));
		assertEquals(Integer.valueOf(0xFFFFC247),
			EntityOutlineState.colorFor(EntityLocator.uuid(ENTITY_B)));
		assertEquals(OVERWORLD, EntityOutlineState.dimensionId());
	}

	@Test
	void preparePopulatesQueriesForTheWinner() {
		EntityOutlineState.prepare(
			storeWithWinner(5L, entityTarget(ENTITY_A), "go_to"), OVERWORLD);

		assertTrue(EntityOutlineState.shouldOutline(EntityLocator.uuid(ENTITY_A)));
		assertEquals(Integer.valueOf(0xFF4DB8FF),
			EntityOutlineState.colorFor(EntityLocator.uuid(ENTITY_A)));
		assertFalse(EntityOutlineState.shouldOutline(EntityLocator.uuid(ENTITY_B)));
		assertNull(EntityOutlineState.colorFor(EntityLocator.uuid(ENTITY_B)));
		assertTrue(EntityOutlineState.hasOutlines());
	}

	@Test
	void repeatedIdenticalPrepareKeepsState() {
		ClientMarkerStore store = storeWithWinner(5L, entityTarget(ENTITY_A), "attention");

		EntityOutlineState.prepare(store, OVERWORLD);
		EntityOutlineState.prepare(store, OVERWORLD);
		EntityOutlineState.prepare(store, OVERWORLD);

		assertTrue(EntityOutlineState.shouldOutline(EntityLocator.uuid(ENTITY_A)));
		assertEquals(Integer.valueOf(0xFFFFC247),
			EntityOutlineState.colorFor(EntityLocator.uuid(ENTITY_A)));
		assertEquals(OVERWORLD, EntityOutlineState.dimensionId());
		assertTrue(EntityOutlineState.hasOutlines());
	}

	@Test
	void replacingTheWinnerDropsThePreviousEntity() {
		EntityOutlineState.prepare(
			storeWithWinner(1L, entityTarget(ENTITY_A), "attention"), OVERWORLD);
		assertTrue(EntityOutlineState.shouldOutline(EntityLocator.uuid(ENTITY_A)));

		EntityOutlineState.prepare(
			storeWithWinner(2L, entityTarget(ENTITY_B), "attention"), OVERWORLD);

		assertFalse(EntityOutlineState.shouldOutline(EntityLocator.uuid(ENTITY_A)));
		assertNull(EntityOutlineState.colorFor(EntityLocator.uuid(ENTITY_A)));
		assertTrue(EntityOutlineState.shouldOutline(EntityLocator.uuid(ENTITY_B)));
		assertEquals(Integer.valueOf(0xFFFFC247),
			EntityOutlineState.colorFor(EntityLocator.uuid(ENTITY_B)));
		assertEquals(OVERWORLD, EntityOutlineState.dimensionId());
	}

	@Test
	void winnerChangeUpdatesColor() {
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target target = entityTarget(ENTITY_A);
		TargetKey key = TargetKey.from(target);

		store.onCreated(snapshot(1L, target, "attention"), 0L);
		store.onCreated(snapshot(2L, target, "loot"), 0L);
		store.onWinnerChanged(key, Optional.of(new MarkerId(1L)));

		EntityOutlineState.prepare(store, OVERWORLD);
		assertEquals(Integer.valueOf(0xFFFFC247),
			EntityOutlineState.colorFor(EntityLocator.uuid(ENTITY_A)));

		store.onWinnerChanged(key, Optional.of(new MarkerId(2L)));

		EntityOutlineState.prepare(store, OVERWORLD);
		assertEquals(Integer.valueOf(0xFF52D273),
			EntityOutlineState.colorFor(EntityLocator.uuid(ENTITY_A)));
		assertTrue(EntityOutlineState.shouldOutline(EntityLocator.uuid(ENTITY_A)));
		assertEquals(OVERWORLD, EntityOutlineState.dimensionId());
	}

	@Test
	void clearDropsEverythingAndResetsDimensionId() {
		EntityOutlineState.prepare(
			storeWithWinner(5L, entityTarget(ENTITY_A), "attention"), OVERWORLD);
		assertTrue(EntityOutlineState.hasOutlines());
		assertEquals(OVERWORLD, EntityOutlineState.dimensionId());

		EntityOutlineState.clear();

		assertFalse(EntityOutlineState.hasOutlines());
		assertFalse(EntityOutlineState.shouldOutline(EntityLocator.uuid(ENTITY_A)));
		assertNull(EntityOutlineState.colorFor(EntityLocator.uuid(ENTITY_A)));
		assertEquals("", EntityOutlineState.dimensionId());

		// A second clear of the already-empty state stays safe and idempotent.
		EntityOutlineState.clear();
		assertFalse(EntityOutlineState.hasOutlines());
		assertEquals("", EntityOutlineState.dimensionId());
	}

	@Test
	void prepareWithNullStoreOrDimensionClears() {
		EntityOutlineState.prepare(
			storeWithWinner(5L, entityTarget(ENTITY_A), "attention"), OVERWORLD);
		assertTrue(EntityOutlineState.hasOutlines());

		EntityOutlineState.prepare(null, OVERWORLD);

		assertFalse(EntityOutlineState.hasOutlines());
		assertFalse(EntityOutlineState.shouldOutline(EntityLocator.uuid(ENTITY_A)));
		assertNull(EntityOutlineState.colorFor(EntityLocator.uuid(ENTITY_A)));
		assertEquals("", EntityOutlineState.dimensionId());

		EntityOutlineState.prepare(
			storeWithWinner(5L, entityTarget(ENTITY_A), "attention"), OVERWORLD);
		EntityOutlineState.prepare(
			storeWithWinner(5L, entityTarget(ENTITY_A), "attention"), null);

		assertFalse(EntityOutlineState.hasOutlines());
		assertFalse(EntityOutlineState.shouldOutline(EntityLocator.uuid(ENTITY_A)));
		assertNull(EntityOutlineState.colorFor(EntityLocator.uuid(ENTITY_A)));
		assertEquals("", EntityOutlineState.dimensionId());
	}
}
