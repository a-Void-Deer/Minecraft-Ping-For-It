package nx.pingwheel.common.client.spatial;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic tests for {@link InventoryListModel}. Cases drive explicit
 * discovery batches, selection moves, and wheel deltas, so the assertions
 * describe ordering/selection behavior rather than any renderer or game
 * state. No timestamps, Minecraft classes, or configuration are involved.
 */
class InventoryListModelTest {

	private static InventoryListModel.Entry entry(String key, long count) {
		return new InventoryListModel.Entry(key, "label-" + key, count, "common");
	}

	private static InventoryListModel.Entry entry(String key, long count, String quality) {
		return new InventoryListModel.Entry(key, "label-" + key, count, quality);
	}

	private static List<String> keys(InventoryListModel model) {
		return model.snapshot().entries().stream().map(InventoryListModel.Entry::key).toList();
	}

	private static InventoryListModel opened(int visibleRows, InventoryListModel.Entry... entries) {
		InventoryListModel model = new InventoryListModel(visibleRows);
		model.open(List.of(entries), 0.0, 0.0, 90.0);
		return model;
	}

	private static void assertDirection(double bearing, InventoryListModel.Side back, InventoryListModel.Side forward) {
		InventoryListModel.Direction direction = InventoryListModel.directionFor(bearing);
		assertEquals(back, direction.back(), "back side at " + bearing + " degrees");
		assertEquals(forward, direction.forward(), "forward side at " + bearing + " degrees");
	}

	@Test
	void openSortsByQuantityWithStableFirstSeenTies() {
		InventoryListModel model = opened(3, entry("a", 10), entry("b", 20), entry("c", 20), entry("d", 5));

		assertEquals(List.of("b", "c", "a", "d"), keys(model));
		assertEquals("b", model.selectedKey());
		assertEquals(0, model.selectedIndex());
	}

	@Test
	void pureQuantityUpdatesNeverReorder() {
		InventoryListModel model = opened(3, entry("a", 10), entry("b", 20), entry("c", 20), entry("d", 5));

		assertFalse(model.applyBatch(List.of(entry("d", 999), entry("a", 1))));

		assertEquals(List.of("b", "c", "a", "d"), keys(model));
		assertEquals(999, model.snapshot().entries().get(3).count());
		assertEquals(1, model.snapshot().entries().get(2).count());
	}

	@Test
	void newKeyBeforeVerticalScrollResortsAndKeepsSelectionKey() {
		InventoryListModel model = opened(3, entry("a", 10), entry("b", 20));
		assertEquals("b", model.selectedKey());

		assertTrue(model.applyBatch(List.of(entry("z", 30))));

		assertEquals(List.of("z", "b", "a"), keys(model));
		assertEquals("b", model.selectedKey(), "selection follows its key through the resort");
		assertEquals(1, model.selectedIndex());
	}

	@Test
	void afterVerticalScrollExistingRowsStayFixedAndNewKeysAppendInternallySorted() {
		InventoryListModel model = opened(3, entry("a", 10), entry("b", 20), entry("c", 30));
		assertEquals(List.of("c", "b", "a"), keys(model));

		model.glide(1);
		assertEquals("b", model.selectedKey());

		assertTrue(model.applyBatch(List.of(entry("d", 5), entry("e", 25), entry("a", 100))));

		assertEquals(List.of("c", "b", "a", "e", "d"), keys(model), "existing positions fixed, new keys sorted then appended");
		assertEquals(100, model.snapshot().entries().get(2).count(), "in-place count update keeps its row");
		assertEquals("b", model.selectedKey());
		assertEquals(1, model.selectedIndex());
	}

	@Test
	void zeroCountRowsAreRetainedAndNeverDeletedWhileOpen() {
		InventoryListModel model = opened(3, entry("a", 5), entry("b", 3));

		assertFalse(model.applyBatch(List.of(entry("a", 0))));

		assertEquals(List.of("a", "b"), keys(model));
		assertEquals(0, model.snapshot().entries().get(0).count());
		assertEquals(2, model.size());
	}

	@Test
	void closeAndReopenSortsFreshlyDiscoveredEntries() {
		InventoryListModel model = opened(3, entry("a", 5), entry("b", 3));
		model.close();
		assertFalse(model.isOpen());
		assertEquals(0, model.size());

		model.open(List.of(entry("a", 1), entry("b", 9)), 0.0, 0.0, 0.0);

		assertTrue(model.isOpen());
		assertEquals(List.of("b", "a"), keys(model));
		assertEquals("b", model.selectedKey());
	}

	@Test
	void selectionMovementAndWindowClamp() {
		InventoryListModel model = new InventoryListModel(5);
		model.open(
			List.of(entry("e0", 10), entry("e1", 9), entry("e2", 8), entry("e3", 7), entry("e4", 6),
				entry("e5", 5), entry("e6", 4), entry("e7", 3), entry("e8", 2), entry("e9", 1)),
			10.0,
			20.0,
			90.0);

		model.select(0);
		assertEquals(0, model.windowFirst());
		model.select(4);
		assertEquals(2, model.windowFirst());
		model.select(7);
		assertEquals(5, model.windowFirst());
		model.select(9);
		assertEquals(5, model.windowFirst(), "window clamps to the last page");
		model.select(999);
		assertEquals(9, model.selectedIndex());
		model.select(-3);
		assertEquals(0, model.selectedIndex());
		assertEquals(0, model.windowFirst());

		InventoryListModel small = opened(5, entry("a", 2), entry("b", 1));
		small.select(1);
		assertEquals(0, small.windowFirst(), "a list shorter than the viewport starts at zero");
	}

	@Test
	void selectByKeyIsStableAndGlideClamps() {
		InventoryListModel model = opened(3, entry("a", 10), entry("b", 20), entry("c", 30));

		assertTrue(model.select("a"));
		assertEquals(2, model.selectedIndex());
		assertFalse(model.select("missing"));

		assertEquals(0, model.glide(10), "glide cannot move past the list end");
		assertEquals(2, model.selectedIndex());
		assertEquals(-1, model.glide(-1));
		assertEquals(1, model.selectedIndex());
	}

	@Test
	void wheelAccumulatesFractionalRowsAndSuppressesWhileBackHoverFocused() {
		InventoryListModel model = opened(3, entry("e0", 50), entry("e1", 40), entry("e2", 30), entry("e3", 20), entry("e4", 10));

		assertEquals(0, model.wheel(0.4f));
		assertEquals(0, model.selectedIndex());
		assertEquals(1, model.wheel(0.7f));
		assertEquals(1, model.selectedIndex());
		assertEquals(0, model.wheel(-0.5f));
		assertEquals(1, model.selectedIndex());
		assertEquals(-1, model.wheel(-0.6f));
		assertEquals(0, model.selectedIndex());

		model.setBackHoverFocused(true);
		assertEquals(0, model.wheel(10.0f), "wheel is suppressed while Back is hovered");
		assertEquals(0, model.selectedIndex());

		model.setBackHoverFocused(false);
		assertEquals(0, model.wheel(0.9f), "suppressed wheel must not leave a stale remainder");
		assertEquals(1, model.wheel(0.2f));
		assertEquals(1, model.selectedIndex());
	}

	@Test
	void directionForVerticalParentsKeepsBackLeft() {
		assertDirection(0.0, InventoryListModel.Side.LEFT, InventoryListModel.Side.RIGHT);
		assertDirection(2.0, InventoryListModel.Side.LEFT, InventoryListModel.Side.RIGHT);
		assertDirection(358.0, InventoryListModel.Side.LEFT, InventoryListModel.Side.RIGHT);
		assertDirection(180.0, InventoryListModel.Side.LEFT, InventoryListModel.Side.RIGHT);
		assertDirection(178.0, InventoryListModel.Side.LEFT, InventoryListModel.Side.RIGHT);
		assertDirection(182.0, InventoryListModel.Side.LEFT, InventoryListModel.Side.RIGHT);

		assertDirection(90.0, InventoryListModel.Side.RIGHT, InventoryListModel.Side.LEFT);
		assertDirection(10.0, InventoryListModel.Side.RIGHT, InventoryListModel.Side.LEFT);
		assertDirection(177.0, InventoryListModel.Side.RIGHT, InventoryListModel.Side.LEFT);
		assertDirection(270.0, InventoryListModel.Side.LEFT, InventoryListModel.Side.RIGHT);
		assertDirection(183.0, InventoryListModel.Side.LEFT, InventoryListModel.Side.RIGHT);
	}

	@Test
	void statusIsASeparateSetterFromRowData() {
		InventoryListModel model = opened(3, entry("a", 1));
		assertEquals(InventoryListModel.Status.UPDATING, model.status());
		assertEquals(InventoryListModel.Status.UPDATING, model.snapshot().status());

		model.setStatus(InventoryListModel.Status.COMPLETE);

		assertEquals(InventoryListModel.Status.COMPLETE, model.status());
		assertEquals(InventoryListModel.Status.COMPLETE, model.snapshot().status());
		assertEquals(List.of("a"), keys(model), "status changes never touch rows");
	}

	@Test
	void emptyDiscoveryIsALegitimateStateWithoutClientFallback() {
		InventoryListModel model = new InventoryListModel(4);
		model.open(List.of(), 0.0, 0.0, 90.0);

		assertTrue(model.isOpen());
		assertEquals(0, model.size());
		assertEquals(-1, model.selectedIndex());
		assertNull(model.selectedKey());
		assertEquals(0, model.windowFirst());

		assertTrue(model.applyBatch(List.of(entry("a", 1))));
		assertEquals(1, model.size());
		assertEquals("a", model.selectedKey(), "first discovered row becomes selectable after an empty open");
	}

	@Test
	void labelAndQualityUpdateInPlaceWithoutReordering() {
		InventoryListModel model = new InventoryListModel(3);
		model.open(List.of(new InventoryListModel.Entry("a", "first", 5, "rare")), 0.0, 0.0, 90.0);

		model.applyBatch(List.of(new InventoryListModel.Entry("a", "second", 7, "common")));
		assertEquals("second", model.snapshot().entries().get(0).label());
		assertEquals(7, model.snapshot().entries().get(0).count());
		assertEquals("common", model.snapshot().entries().get(0).quality());

		assertTrue(model.updateCount("a", 3));
		assertEquals(3, model.snapshot().entries().get(0).count());
		assertEquals("second", model.snapshot().entries().get(0).label());
		assertFalse(model.updateCount("missing", 1));
	}

	@Test
	void snapshotEntriesAreImmutable() {
		InventoryListModel model = opened(3, entry("a", 1));
		List<InventoryListModel.Entry> snapshot = model.snapshot().entries();

		assertThrows(UnsupportedOperationException.class, () -> snapshot.add(entry("b", 2)));
	}

	@Test
	void newKeyFromABatchIsIndexedOnceAndLaterUpdatesApplyInPlace() {
		InventoryListModel model = opened(3, entry("a", 1));

		assertTrue(model.applyBatch(List.of(entry("b", 2))));
		assertEquals(List.of("b", "a"), keys(model));

		assertFalse(model.applyBatch(List.of(entry("b", 5))), "a later batch updates the discovered key");
		assertEquals(2, model.size(), "no duplicate row is created");
		assertEquals(List.of("b", "a"), keys(model));
		assertEquals(5, model.snapshot().entries().get(0).count());

		assertTrue(model.updateCount("b", 7));
		assertEquals(7, model.snapshot().entries().get(0).count());
		assertTrue(model.select("b"));
		assertEquals("b", model.selectedKey());
	}

	@Test
	void duplicateNewKeysWithinOneBatchCoalesceIntoOneRow() {
		InventoryListModel model = opened(3, entry("a", 1));

		assertTrue(model.applyBatch(List.of(entry("b", 2), entry("b", 9), entry("c", 5))));

		assertEquals(List.of("b", "c", "a"), keys(model), "a duplicate key contributes one row with its last value");
		assertEquals(3, model.size());
		assertTrue(model.select("b"));
		assertTrue(model.updateCount("b", 1));
		assertEquals(1, model.snapshot().entries().get(0).count(), "in-place update works for the coalesced row");
	}

	@Test
	void newlyIndexedKeySelectAndUpdateDoNotDisturbOrder() {
		InventoryListModel model = opened(3, entry("a", 1));

		assertTrue(model.applyBatch(List.of(entry("z", 10), entry("y", 5))));
		assertEquals(List.of("z", "y", "a"), keys(model));

		assertTrue(model.select("y"));
		assertEquals(1, model.selectedIndex());

		assertTrue(model.updateCount("z", 1));
		assertEquals(List.of("z", "y", "a"), keys(model), "count-only updates never reorder");

		assertFalse(model.applyBatch(List.of(entry("z", 2))));
		assertEquals(List.of("z", "y", "a"), keys(model), "a later existing-key batch keeps fixed positions");
		assertEquals("y", model.selectedKey());
	}

	@Test
	void attemptedScrollAtBoundaryFreezesLaterDiscoveryPositions() {
		InventoryListModel model = opened(3, entry("a", 10), entry("b", 5));
		assertEquals(0, model.glide(-1));
		model.applyBatch(List.of(entry("new", 100)));
		assertEquals(List.of("a", "b", "new"), keys(model), "a deliberate scroll need not change selection to freeze rows");
	}

	@Test
	void fractionalWheelScrollFreezesDiscoveryWithoutNeedingAWholeRow() {
		InventoryListModel model = opened(3, entry("a", 10), entry("b", 5));
		assertEquals(0, model.wheel(0.25f));
		model.applyBatch(List.of(entry("new", 100)));
		assertEquals(List.of("a", "b", "new"), keys(model));
	}
}
