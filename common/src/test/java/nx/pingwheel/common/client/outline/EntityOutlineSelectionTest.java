package nx.pingwheel.common.client.outline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure entity outline selection logic.
 */
class EntityOutlineSelectionTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:nether";
	private static final UUID OWNER = new UUID(0L, 100L);
	private static final UUID ENTITY_A = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
	private static final UUID ENTITY_B = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");

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

	private static Target entityTarget(String dimension, UUID entityId) {
		return new Target.EntityTarget(dimension, entityId);
	}

	private static Map<TargetKey, ClientMarker> winnersMap(Object... entries) {
		Map<TargetKey, ClientMarker> map = new LinkedHashMap<>();

		for (int i = 0; i < entries.length; i += 2) {
			map.put((TargetKey) entries[i], (ClientMarker) entries[i + 1]);
		}

		return map;
	}

	// --- known / unknown ping type colors ---

	@Test
	void knownPingTypesResolveToExactOpaqueOutlineColors() {
		Map<TargetKey, ClientMarker> winners = new LinkedHashMap<>();
		UUID[] entities = {
			ENTITY_A,
			UUID.fromString("cccccccc-1111-2222-3333-444444444444"),
			UUID.fromString("dddddddd-1111-2222-3333-444444444444"),
			UUID.fromString("eeeeeeee-1111-2222-3333-444444444444")
		};
		String[] pingTypes = {"attention", "danger", "go_to", "loot"};
		int[] expected = {0xFFC247, 0xFF4D4D, 0x4DB8FF, 0x52D273};

		for (int i = 0; i < pingTypes.length; i++) {
			Target target = entityTarget(OVERWORLD, entities[i]);
			winners.put(TargetKey.from(target), marker(i + 1L, target, pingTypes[i]));
		}

		Map<EntityLocator, EntityOutlineSpec> selected =
			EntityOutlineSelection.select(winners, PingTypeCatalog.builtIn());

		assertEquals(4, selected.size());

		for (int i = 0; i < pingTypes.length; i++) {
			EntityOutlineSpec spec = selected.get(EntityLocator.uuid(entities[i]));

			assertEquals(0xFF000000 | expected[i], spec.argbColor());
			assertEquals(pingTypes[i], spec.pingTypeId());
		}
	}

	@Test
	void unknownPingTypeFallsBackToOpaqueWhite() {
		Target target = entityTarget(OVERWORLD, ENTITY_A);

		Map<EntityLocator, EntityOutlineSpec> selected = EntityOutlineSelection.select(
			winnersMap(TargetKey.from(target), marker(1L, target, "unknown_type")),
			PingTypeCatalog.builtIn());

		assertEquals(0xFFFFFFFF, selected.get(EntityLocator.uuid(ENTITY_A)).argbColor());
	}

	@Test
	void runtimeLocatorIsRetainedByOutlineSelection() {
		EntityLocator locator = EntityLocator.runtimeId(42);
		Target target = new Target.EntityTarget(OVERWORLD, locator);

		Map<EntityLocator, EntityOutlineSpec> selected = EntityOutlineSelection.select(
			winnersMap(TargetKey.from(target), marker(1L, target, "attention")),
			PingTypeCatalog.builtIn());

		assertEquals(1, selected.size());
		assertEquals(locator, selected.get(locator).locator());
	}

	// --- inclusion / exclusion ---

	@Test
	void onlyEntityWinnersAreSelected() {
		Target entity = entityTarget(OVERWORLD, ENTITY_A);
		Target block = new Target.BlockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		Target location = new Target.LocationTarget(OVERWORLD, 4, 5, 6);

		Map<EntityLocator, EntityOutlineSpec> selected = EntityOutlineSelection.select(
			winnersMap(
				TargetKey.from(block), marker(1L, block, "attention"),
				TargetKey.from(entity), marker(2L, entity, "attention"),
				TargetKey.from(location), marker(3L, location, "attention")),
			PingTypeCatalog.builtIn());

		assertEquals(1, selected.size());
		assertEquals(new MarkerId(2L), selected.get(EntityLocator.uuid(ENTITY_A)).markerId());
	}

	@Test
	void mismatchedKeyAndTargetAreExcluded() {
		Target targetForB = entityTarget(OVERWORLD, ENTITY_B);
		Target block = new Target.BlockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		Target netherEntity = entityTarget(NETHER, ENTITY_A);

		Map<EntityLocator, EntityOutlineSpec> selected = EntityOutlineSelection.select(
			winnersMap(
				// Key names ENTITY_A but the marker target is ENTITY_B.
				new TargetKey.EntityKey(OVERWORLD, ENTITY_A),
				marker(1L, targetForB, "attention"),
				// Key is an entity key but the marker target is a block.
				new TargetKey.EntityKey(OVERWORLD, ENTITY_A),
				marker(2L, block, "attention"),
				// Key dimension differs from the marker target dimension.
				new TargetKey.EntityKey(OVERWORLD, ENTITY_A),
				marker(3L, netherEntity, "attention")),
			PingTypeCatalog.builtIn());

		assertTrue(selected.isEmpty());
	}

	// --- determinism / one per entity ---

	@Test
	void oneSpecPerEntityWithLargerMarkerIdWinning() {
		Target overworld = entityTarget(OVERWORLD, ENTITY_A);
		Target nether = entityTarget(NETHER, ENTITY_A);

		Map<EntityLocator, EntityOutlineSpec> selected = EntityOutlineSelection.select(
			winnersMap(
				TargetKey.from(overworld), marker(2L, overworld, "danger"),
				TargetKey.from(nether), marker(1L, nether, "attention")),
			PingTypeCatalog.builtIn());

		assertEquals(1, selected.size());
		assertEquals(new MarkerId(2L), selected.get(EntityLocator.uuid(ENTITY_A)).markerId());
		assertEquals("danger", selected.get(EntityLocator.uuid(ENTITY_A)).pingTypeId());
	}

	@Test
	void selectionOrderIsDeterministicByAscendingMarkerId() {
		Target targetA = entityTarget(OVERWORLD, ENTITY_A);
		Target targetB = entityTarget(OVERWORLD, ENTITY_B);
		Map<TargetKey, ClientMarker> winners = new LinkedHashMap<>();

		// Insert out of marker-id order on purpose.
		winners.put(TargetKey.from(targetB), marker(7L, targetB, "attention"));
		winners.put(TargetKey.from(targetA), marker(3L, targetA, "attention"));

		Map<EntityLocator, EntityOutlineSpec> selected =
			EntityOutlineSelection.select(winners, PingTypeCatalog.builtIn());

		assertEquals(
			List.of(EntityLocator.uuid(ENTITY_A), EntityLocator.uuid(ENTITY_B)),
			selected.keySet().stream().toList());
		assertEquals(
			List.of(new MarkerId(3L), new MarkerId(7L)),
			selected.values().stream().map(EntityOutlineSpec::markerId).toList());
	}

	// --- store-backed behavior ---

	@Test
	void winnerChangeUpdatesSpecAndNonWinnersAreNotSelected() {
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target target = entityTarget(OVERWORLD, ENTITY_A);
		Target otherTarget = entityTarget(OVERWORLD, ENTITY_B);
		TargetKey key = TargetKey.from(target);
		MarkerId attentionId = new MarkerId(1L);
		MarkerId dangerId = new MarkerId(2L);
		MarkerId nonWinnerId = new MarkerId(3L);

		store.onCreated(
			new MarkerSnapshot(attentionId, OWNER, target, "entity", "attention",
				new MarkerAnchor(0, 0, 0), 1L, 100L), 0L);
		store.onCreated(
			new MarkerSnapshot(dangerId, OWNER, target, "entity", "danger",
				new MarkerAnchor(0, 0, 0), 1L, 100L), 0L);
		// A marker with no winner slot must never produce a spec.
		store.onCreated(
			new MarkerSnapshot(nonWinnerId, OWNER, otherTarget, "entity", "loot",
				new MarkerAnchor(0, 0, 0), 1L, 100L), 0L);

		store.onWinnerChanged(key, Optional.of(attentionId));

		Map<EntityLocator, EntityOutlineSpec> first = EntityOutlineSelection.select(
			store.visibleWinnersInDimension(OVERWORLD), PingTypeCatalog.builtIn());

		assertEquals(1, first.size());
		assertEquals(attentionId, first.get(EntityLocator.uuid(ENTITY_A)).markerId());
		assertEquals(0xFFFFC247, first.get(EntityLocator.uuid(ENTITY_A)).argbColor());

		store.onWinnerChanged(key, Optional.of(dangerId));

		Map<EntityLocator, EntityOutlineSpec> second = EntityOutlineSelection.select(
			store.visibleWinnersInDimension(OVERWORLD), PingTypeCatalog.builtIn());

		assertEquals(1, second.size());
		assertEquals(dangerId, second.get(EntityLocator.uuid(ENTITY_A)).markerId());
		assertEquals(0xFFFF4D4D, second.get(EntityLocator.uuid(ENTITY_A)).argbColor());
	}

	@Test
	void movementAndAnchorChangesRetainEntityIdentity() {
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target target = entityTarget(OVERWORLD, ENTITY_A);
		TargetKey key = TargetKey.from(target);
		MarkerId movedId = new MarkerId(2L);
		MarkerId originalId = new MarkerId(1L);

		// Same entity UUID with a different anchor: identity is unchanged.
		store.onCreated(
			new MarkerSnapshot(originalId, OWNER, target, "entity", "attention",
				new MarkerAnchor(0, 0, 0), 1L, 100L), 0L);
		store.onCreated(
			new MarkerSnapshot(movedId, OWNER, target, "entity", "attention",
				new MarkerAnchor(1234.5, 64, -987.25), 1L, 100L), 0L);

		store.onWinnerChanged(key, Optional.of(movedId));

		Map<EntityLocator, EntityOutlineSpec> selected = EntityOutlineSelection.select(
			store.visibleWinnersInDimension(OVERWORLD), PingTypeCatalog.builtIn());

		assertEquals(1, selected.size());
		assertEquals(movedId, selected.get(EntityLocator.uuid(ENTITY_A)).markerId());
	}

	@Test
	void differentDimensionIsExcludedViaStoreQuery() {
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target netherTarget = entityTarget(NETHER, ENTITY_A);
		MarkerId netherId = new MarkerId(1L);

		store.onCreated(
			new MarkerSnapshot(netherId, OWNER, netherTarget, "entity", "attention",
				new MarkerAnchor(0, 0, 0), 1L, 100L), 0L);
		store.onWinnerChanged(TargetKey.from(netherTarget), Optional.of(netherId));

		assertTrue(EntityOutlineSelection.select(
			store.visibleWinnersInDimension(OVERWORLD), PingTypeCatalog.builtIn()).isEmpty());
		assertEquals(1, EntityOutlineSelection.select(
			store.visibleWinnersInDimension(NETHER), PingTypeCatalog.builtIn()).size());
	}

	// --- validation ---

	@Test
	void selectRejectsNullArguments() {
		assertThrows(NullPointerException.class,
			() -> EntityOutlineSelection.select(null, PingTypeCatalog.builtIn()));
		assertThrows(NullPointerException.class,
			() -> EntityOutlineSelection.select(Map.of(), null));
	}

	@Test
	void specConstructorIsStrictAndForcesOpaque() {
		assertThrows(NullPointerException.class,
			() -> new EntityOutlineSpec(new MarkerId(1L), (EntityLocator)null, "attention", 0xFFC247));
		assertThrows(IllegalArgumentException.class,
			() -> new EntityOutlineSpec(new MarkerId(1L), ENTITY_A, "  ", 0xFFC247));

		EntityOutlineSpec spec = new EntityOutlineSpec(
			new MarkerId(1L), ENTITY_A, "attention", 0xC247);

		assertEquals(0xFF00C247, spec.argbColor());

		// A nonzero partial caller alpha is discarded: the spec stays fully
		// opaque and keeps only the 24-bit RGB payload.
		EntityOutlineSpec alphaSpec = new EntityOutlineSpec(
			new MarkerId(2L), ENTITY_A, "attention", 0x8000C247);

		assertEquals(0xFF00C247, alphaSpec.argbColor());
	}
}
