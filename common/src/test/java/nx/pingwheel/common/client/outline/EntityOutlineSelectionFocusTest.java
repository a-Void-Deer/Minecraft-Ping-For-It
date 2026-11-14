package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOutlineSelectionFocusTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:nether";
	private static final UUID OWNER = new UUID(0L, 100L);
	private static final UUID ENTITY_A = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
	private static final UUID ENTITY_B = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");

	@Test
	void knownPingTypesResolveToExactOpaqueOutlineColors() {
		UUID[] entities = {
			ENTITY_A,
			UUID.fromString("cccccccc-1111-2222-3333-444444444444"),
			UUID.fromString("dddddddd-1111-2222-3333-444444444444"),
			UUID.fromString("eeeeeeee-1111-2222-3333-444444444444")
		};
		String[] pingTypes = {"attention", "danger", "go_to", "loot"};
		int[] expected = {0xFFFFC247, 0xFFFF4D4D, 0xFF4DB8FF, 0xFF52D273};
		ClientMarker[] markers = new ClientMarker[entities.length];

		for (int index = 0; index < entities.length; index++) {
			markers[index] = marker(index + 1L, entityTarget(OVERWORLD, entities[index]), pingTypes[index]);
		}

		Map<EntityLocator, Integer> selected = EntityOutlineSelection.select(List.of(markers), OVERWORLD);

		assertEquals(4, selected.size());

		for (int index = 0; index < entities.length; index++) {
			assertEquals(Integer.valueOf(expected[index]), selected.get(EntityLocator.uuid(entities[index])));
		}
	}

	@Test
	void unknownPingTypeFallsBackToOpaqueWhite() {
		Map<EntityLocator, Integer> selected = EntityOutlineSelection.select(
			List.of(marker(1L, entityTarget(OVERWORLD, ENTITY_A), "unknown_type")), OVERWORLD);

		assertEquals(Integer.valueOf(0xFFFFFFFF), selected.get(EntityLocator.uuid(ENTITY_A)));
	}

	@Test
	void runtimeLocatorIsRetained() {
		EntityLocator locator = EntityLocator.runtimeId(42);
		Target target = new Target.EntityTarget(OVERWORLD, locator);

		Map<EntityLocator, Integer> selected = EntityOutlineSelection.select(
			List.of(marker(1L, target, "attention")), OVERWORLD);

		assertEquals(1, selected.size());
		assertEquals(Integer.valueOf(0xFFFFC247), selected.get(locator));
	}

	@Test
	void onlyEntityWinnersAreSelected() {
		Target entity = entityTarget(OVERWORLD, ENTITY_A);
		Target block = new Target.BlockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		Target location = new Target.LocationTarget(OVERWORLD, 4.0, 5.0, 6.0);

		Map<EntityLocator, Integer> selected = EntityOutlineSelection.select(
			List.of(
				marker(1L, block, "attention"),
				marker(2L, entity, "attention"),
				marker(3L, location, "attention")),
			OVERWORLD);

		assertEquals(1, selected.size());
		assertEquals(Set.of(EntityLocator.uuid(ENTITY_A)), selected.keySet());
		assertFalse(selected.containsKey(EntityLocator.uuid(ENTITY_B)));
	}

	@Test
	void oneSpecPerEntityWithLargerMarkerIdWinning() {
		Target target = entityTarget(OVERWORLD, ENTITY_A);

		Map<EntityLocator, Integer> selected = EntityOutlineSelection.select(
			List.of(
				marker(2L, target, "danger"),
				marker(1L, target, "attention")),
			OVERWORLD);

		assertEquals(1, selected.size());
		assertEquals(Integer.valueOf(0xFFFF4D4D), selected.get(EntityLocator.uuid(ENTITY_A)));
	}

	@Test
	void movementAndAnchorChangesRetainEntityIdentity() {
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target target = entityTarget(OVERWORLD, ENTITY_A);
		TargetKey key = TargetKey.from(target);
		MarkerId movedId = new MarkerId(2L);
		MarkerId originalId = new MarkerId(1L);

		store.onCreated(new MarkerSnapshot(
			originalId, OWNER, target, "entity", "attention",
			new MarkerAnchor(0, 0, 0), 1L, 100L), 0L);
		store.onCreated(new MarkerSnapshot(
			movedId, OWNER, target, "entity", "danger",
			new MarkerAnchor(1234.5, 64, -987.25), 1L, 100L), 0L);
		store.onWinnerChanged(key, Optional.of(movedId));

		Map<EntityLocator, Integer> selected = EntityOutlineSelection.select(
			store.visibleWinnersInDimension(OVERWORLD).values(), OVERWORLD);

		assertEquals(1, selected.size());
		assertEquals(Integer.valueOf(0xFFFF4D4D), selected.get(EntityLocator.uuid(ENTITY_A)));
	}

	@Test
	void differentDimensionIsExcludedViaStoreQuery() {
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target netherTarget = entityTarget(NETHER, ENTITY_A);
		MarkerId netherId = new MarkerId(1L);

		store.onCreated(snapshot(netherId, netherTarget, "attention"), 0L);
		store.onWinnerChanged(TargetKey.from(netherTarget), Optional.of(netherId));

		assertTrue(EntityOutlineSelection.select(
			store.visibleWinnersInDimension(OVERWORLD).values(), OVERWORLD).isEmpty());
		assertEquals(1, EntityOutlineSelection.select(
			store.visibleWinnersInDimension(NETHER).values(), NETHER).size());
	}

	@Test
	void selectHandlesNullArguments() {
		List<ClientMarker> winners = List.of(marker(1L, entityTarget(OVERWORLD, ENTITY_A), "attention"));

		assertTrue(EntityOutlineSelection.select(null, OVERWORLD).isEmpty());
		assertTrue(EntityOutlineSelection.select(winners, null).isEmpty());
	}

	@Test
	void selectReturnsImmutableSnapshot() {
		List<ClientMarker> winners = List.of(marker(1L, entityTarget(OVERWORLD, ENTITY_A), "attention"));
		Map<EntityLocator, Integer> first = EntityOutlineSelection.select(winners, OVERWORLD);
		Map<EntityLocator, Integer> second = EntityOutlineSelection.select(winners, OVERWORLD);

		assertEquals(first, second);
		assertThrows(UnsupportedOperationException.class,
			() -> first.put(EntityLocator.uuid(ENTITY_B), 0));
	}

	private static ClientMarker marker(long id, Target target, String pingTypeId) {
		return new ClientMarker(
			new MarkerId(id),
			OWNER,
			target,
			"entity",
			pingTypeId,
			new MarkerAnchor(0, 0, 0),
			1L,
			100L,
			0L,
			100L);
	}

	private static MarkerSnapshot snapshot(MarkerId id, Target target, String pingTypeId) {
		return new MarkerSnapshot(
			id, OWNER, target, "entity", pingTypeId,
			new MarkerAnchor(0, 0, 0), 1L, 100L);
	}

	private static Target entityTarget(String dimension, UUID entityId) {
		return new Target.EntityTarget(dimension, entityId);
	}
}