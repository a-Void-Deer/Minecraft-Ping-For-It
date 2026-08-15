package nx.pingwheel.common.client.outline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.domain.MarkerId;
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
 * Tests for the pure block outline selection logic.
 */
class BlockOutlineSelectionTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:nether";
	private static final UUID OWNER = new UUID(0L, 100L);

	private static ClientMarker marker(long id, Target target, String pingTypeId) {
		return new ClientMarker(
			new MarkerId(id),
			OWNER,
			target,
			"block",
			pingTypeId,
			new MarkerAnchor(0, 0, 0),
			1L,
			100L,
			0L,
			100L);
	}

	private static Target blockTarget(String dimension, int x, int y, int z, String registryId) {
		return new Target.BlockTarget(dimension, x, y, z, registryId);
	}

	private static TargetKey.BlockKey keyOf(String dimension, int x, int y, int z, String registryId) {
		return new TargetKey.BlockKey(dimension, x, y, z, registryId);
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
		String[] pingTypes = {"attention", "danger", "go_to", "loot"};
		int[] expected = {0xFFC247, 0xFF4D4D, 0x4DB8FF, 0x52D273};

		for (int i = 0; i < pingTypes.length; i++) {
			Target target = blockTarget(OVERWORLD, i + 1, 2, 3, "minecraft:stone");
			winners.put(TargetKey.from(target), marker(i + 1L, target, pingTypes[i]));
		}

		Map<TargetKey.BlockKey, BlockOutlineSpec> selected =
			BlockOutlineSelection.select(winners, PingTypeCatalog.builtIn());

		assertEquals(4, selected.size());

		for (int i = 0; i < pingTypes.length; i++) {
			BlockOutlineSpec spec = selected.get(keyOf(OVERWORLD, i + 1, 2, 3, "minecraft:stone"));

			assertEquals(0xFF000000 | expected[i], spec.argbColor());
			assertEquals(pingTypes[i], spec.pingTypeId());
		}
	}

	@Test
	void unknownPingTypeFallsBackToOpaqueWhite() {
		Target target = blockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");

		Map<TargetKey.BlockKey, BlockOutlineSpec> selected = BlockOutlineSelection.select(
			winnersMap(TargetKey.from(target), marker(1L, target, "unknown_type")),
			PingTypeCatalog.builtIn());

		assertEquals(0xFFFFFFFF, selected.get(keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone")).argbColor());
	}

	// --- inclusion / exclusion ---

	@Test
	void onlyBlockWinnersAreSelected() {
		Target block = blockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		Target entity = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());
		Target location = new Target.LocationTarget(OVERWORLD, 4, 5, 6);

		Map<TargetKey.BlockKey, BlockOutlineSpec> selected = BlockOutlineSelection.select(
			winnersMap(
				TargetKey.from(entity), marker(1L, entity, "attention"),
				TargetKey.from(block), marker(2L, block, "attention"),
				TargetKey.from(location), marker(3L, location, "attention")),
			PingTypeCatalog.builtIn());

		assertEquals(1, selected.size());
		assertEquals(new MarkerId(2L), selected.get(keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone")).markerId());
	}

	@Test
	void mismatchedKeyAndTargetAreExcluded() {
		Target stone = blockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		Target dirt = blockTarget(OVERWORLD, 1, 2, 3, "minecraft:dirt");
		Target shifted = blockTarget(OVERWORLD, 9, 2, 3, "minecraft:stone");
		Target nether = blockTarget(NETHER, 1, 2, 3, "minecraft:stone");
		Target entity = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());

		Map<TargetKey.BlockKey, BlockOutlineSpec> selected = BlockOutlineSelection.select(
			winnersMap(
				// Key says stone but the marker target is dirt (registry id mismatch).
				keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), marker(1L, dirt, "attention"),
				// Key says x=1 but the marker target is at x=9.
				keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), marker(2L, shifted, "attention"),
				// Key is overworld but the marker target is nether.
				keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), marker(3L, nether, "attention"),
				// Key is a block key but the marker target is an entity.
				keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), marker(4L, entity, "attention")),
			PingTypeCatalog.builtIn());

		assertTrue(selected.isEmpty());
	}

	// --- determinism / one per block key ---

	@Test
	void oneSpecPerBlockKeyWithItsOwnWinner() {
		// A Map cannot hold two entries with the same BlockKey (unlike entity
		// keys across dimensions), so the selection can never deduplicate
		// identical keys. Instead this pins the one-spec-per-block invariant:
		// two winners at the same position with different registry ids are two
		// distinct block identities, each yielding exactly one spec with its
		// own marker and ping type.
		Target stone = blockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		Target dirt = blockTarget(OVERWORLD, 1, 2, 3, "minecraft:dirt");

		Map<TargetKey.BlockKey, BlockOutlineSpec> selected = BlockOutlineSelection.select(
			winnersMap(
				TargetKey.from(dirt), marker(1L, dirt, "attention"),
				TargetKey.from(stone), marker(2L, stone, "danger")),
			PingTypeCatalog.builtIn());

		assertEquals(2, selected.size());
		assertEquals(new MarkerId(2L), selected.get(keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone")).markerId());
		assertEquals("danger", selected.get(keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone")).pingTypeId());
		assertEquals(new MarkerId(1L), selected.get(keyOf(OVERWORLD, 1, 2, 3, "minecraft:dirt")).markerId());
		assertEquals("attention", selected.get(keyOf(OVERWORLD, 1, 2, 3, "minecraft:dirt")).pingTypeId());
	}

	@Test
	void selectionOrderIsDeterministicByAscendingMarkerId() {
		Target targetA = blockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		Target targetB = blockTarget(OVERWORLD, 4, 5, 6, "minecraft:stone");
		Map<TargetKey, ClientMarker> winners = new LinkedHashMap<>();

		// Insert out of marker-id order on purpose.
		winners.put(TargetKey.from(targetB), marker(7L, targetB, "attention"));
		winners.put(TargetKey.from(targetA), marker(3L, targetA, "attention"));

		Map<TargetKey.BlockKey, BlockOutlineSpec> selected =
			BlockOutlineSelection.select(winners, PingTypeCatalog.builtIn());

		assertEquals(
			List.of(
				keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"),
				keyOf(OVERWORLD, 4, 5, 6, "minecraft:stone")),
			selected.keySet().stream().toList());
		assertEquals(
			List.of(new MarkerId(3L), new MarkerId(7L)),
			selected.values().stream().map(BlockOutlineSpec::markerId).toList());
	}

	// --- store-backed behavior ---

	@Test
	void winnerChangeUpdatesSpecAndNonWinnersAreNotSelected() {
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target target = blockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		Target otherTarget = blockTarget(OVERWORLD, 4, 5, 6, "minecraft:stone");
		TargetKey key = TargetKey.from(target);
		MarkerId attentionId = new MarkerId(1L);
		MarkerId dangerId = new MarkerId(2L);
		MarkerId nonWinnerId = new MarkerId(3L);

		store.onCreated(
			new MarkerSnapshot(attentionId, OWNER, target, "block", "attention",
				new MarkerAnchor(0, 0, 0), 1L, 100L), 0L);
		store.onCreated(
			new MarkerSnapshot(dangerId, OWNER, target, "block", "danger",
				new MarkerAnchor(0, 0, 0), 1L, 100L), 0L);
		// A marker with no winner slot must never produce a spec.
		store.onCreated(
			new MarkerSnapshot(nonWinnerId, OWNER, otherTarget, "block", "loot",
				new MarkerAnchor(0, 0, 0), 1L, 100L), 0L);

		store.onWinnerChanged(key, Optional.of(attentionId));

		Map<TargetKey.BlockKey, BlockOutlineSpec> first = BlockOutlineSelection.select(
			store.visibleWinnersInDimension(OVERWORLD), PingTypeCatalog.builtIn());

		assertEquals(1, first.size());
		assertEquals(attentionId, first.get(keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone")).markerId());
		assertEquals(0xFFFFC247, first.get(keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone")).argbColor());

		store.onWinnerChanged(key, Optional.of(dangerId));

		Map<TargetKey.BlockKey, BlockOutlineSpec> second = BlockOutlineSelection.select(
			store.visibleWinnersInDimension(OVERWORLD), PingTypeCatalog.builtIn());

		assertEquals(1, second.size());
		assertEquals(dangerId, second.get(keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone")).markerId());
		assertEquals(0xFFFF4D4D, second.get(keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone")).argbColor());
	}

	@Test
	void blockStateChangeKeepsBlockIdentityWhileRegistryChangeDoesNot() {
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target stone = blockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		// Same block type, different anchor (e.g. a waterlogged or oriented
		// BlockState change): the frozen identity is unchanged.
		Target stoneStateChanged = blockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		// A replaced block type at the same position is a different key.
		Target dirtReplaced = blockTarget(OVERWORLD, 1, 2, 3, "minecraft:dirt");
		MarkerId originalId = new MarkerId(1L);
		MarkerId stateChangedId = new MarkerId(2L);
		MarkerId replacedId = new MarkerId(3L);

		store.onCreated(
			new MarkerSnapshot(originalId, OWNER, stone, "block", "attention",
				new MarkerAnchor(0, 0, 0), 1L, 100L), 0L);
		store.onCreated(
			new MarkerSnapshot(stateChangedId, OWNER, stoneStateChanged, "block", "attention",
				new MarkerAnchor(1234.5, 64, -987.25), 1L, 100L), 0L);
		store.onCreated(
			new MarkerSnapshot(replacedId, OWNER, dirtReplaced, "block", "danger",
				new MarkerAnchor(0, 0, 0), 1L, 100L), 0L);

		// The two stone markers share one target key: a single winner slot.
		store.onWinnerChanged(TargetKey.from(stone), Optional.of(stateChangedId));
		store.onWinnerChanged(TargetKey.from(dirtReplaced), Optional.of(replacedId));

		Map<TargetKey.BlockKey, BlockOutlineSpec> selected = BlockOutlineSelection.select(
			store.visibleWinnersInDimension(OVERWORLD), PingTypeCatalog.builtIn());

		// One spec for the stone identity and one for the replaced dirt
		// identity: the keys differ only by block registry id.
		assertEquals(2, selected.size());
		assertEquals(stateChangedId, selected.get(keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone")).markerId());
		assertEquals(replacedId, selected.get(keyOf(OVERWORLD, 1, 2, 3, "minecraft:dirt")).markerId());
	}

	@Test
	void differentDimensionIsExcludedViaStoreQuery() {
		ClientMarkerStore store = new ClientMarkerStore(10L);
		Target netherTarget = blockTarget(NETHER, 1, 2, 3, "minecraft:stone");
		MarkerId netherId = new MarkerId(1L);

		store.onCreated(
			new MarkerSnapshot(netherId, OWNER, netherTarget, "block", "attention",
				new MarkerAnchor(0, 0, 0), 1L, 100L), 0L);
		store.onWinnerChanged(TargetKey.from(netherTarget), Optional.of(netherId));

		assertTrue(BlockOutlineSelection.select(
			store.visibleWinnersInDimension(OVERWORLD), PingTypeCatalog.builtIn()).isEmpty());
		assertEquals(1, BlockOutlineSelection.select(
			store.visibleWinnersInDimension(NETHER), PingTypeCatalog.builtIn()).size());
	}

	// --- validation ---

	@Test
	void selectRejectsNullArguments() {
		assertThrows(NullPointerException.class,
			() -> BlockOutlineSelection.select(null, PingTypeCatalog.builtIn()));
		assertThrows(NullPointerException.class,
			() -> BlockOutlineSelection.select(Map.of(), null));
	}

	@Test
	void specConstructorIsStrictAndForcesOpaque() {
		assertThrows(NullPointerException.class,
			() -> new BlockOutlineSpec(new MarkerId(1L), null, "attention", 0xFFC247));
		assertThrows(IllegalArgumentException.class,
			() -> new BlockOutlineSpec(new MarkerId(1L), keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), "  ", 0xFFC247));

		BlockOutlineSpec spec = new BlockOutlineSpec(
			new MarkerId(1L), keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), "attention", 0xC247);

		assertEquals(0xFF00C247, spec.argbColor());

		// A nonzero partial caller alpha is discarded: the spec stays fully
		// opaque and keeps only the 24-bit RGB payload.
		BlockOutlineSpec alphaSpec = new BlockOutlineSpec(
			new MarkerId(2L), keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), "attention", 0x8000C247);

		assertEquals(0xFF00C247, alphaSpec.argbColor());
	}
}
