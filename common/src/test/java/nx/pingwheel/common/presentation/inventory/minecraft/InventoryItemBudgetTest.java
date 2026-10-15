package nx.pingwheel.common.presentation.inventory.minecraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Admission/lifecycle seam only; does not establish a native codec certificate. */
class InventoryItemBudgetTest {
	private static final int DISPLAY = InventoryItemCodec.MAX_DISPLAY_BYTES;

	@Test void captureWorkAndRetentionAreCumulativeAcrossRootScratchRelease() {
		var budget = InventoryItemBudget.capture(DISPLAY, 3, 100, 88);
		budget.begin(DISPLAY);
		budget.allocate(64, 8, 2, 2);
		budget.work(7);
		budget.retain(60);
		budget.end();
		budget.begin(DISPLAY);
		assertEquals(0, budget.rootAllocatedBytes(), "scratch alone resets");
		assertEquals(83, budget.attemptedWork(), "allocation cells and explicit work are not refunded");
		assertEquals(60, budget.retainedBytes());
		budget.work(5);
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> budget.work(1));
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> budget.retain(41));
		assertEquals(60, budget.retainedBytes(), "rejected retention commits nothing");
		budget.end();
	}

	@Test void rootStructureRefusalDoesNotRefundCaptureWorkOrRootCount() {
		var budget = InventoryItemBudget.capture(DISPLAY, 2, 4096, 1000);
		budget.begin(DISPLAY);
		budget.work(21);
		assertThrows(InventoryItemBudget.RootLimitException.class,
			() -> budget.node(Integer.MAX_VALUE));
		budget.end();
		budget.begin(DISPLAY);
		assertEquals(22, budget.attemptedWork());
		budget.end();
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> budget.begin(DISPLAY));
	}

	@Test void vectorsUseExplicitSupportedLayoutWeightsAndRejectOverflowBeforeAllocation() {
		assertEquals(64 + 16 + 16 + 80, InventoryItemBudget.allocationCost(64, 8, 2, 2));
		assertThrows(IllegalArgumentException.class,
			() -> InventoryItemBudget.allocationCost(-1, 0, 0, 0));
		assertThrows(InventoryItemBudget.RootLimitException.class,
			() -> InventoryItemBudget.allocationCost(0, Long.MAX_VALUE, 0, 0));
		var budget = InventoryItemBudget.root(DISPLAY);
		budget.begin(DISPLAY);
		assertThrows(InventoryItemBudget.RootLimitException.class,
			() -> budget.allocate(Long.MAX_VALUE, 0, 0, 0));
		assertEquals(0, budget.rootAllocatedBytes());
		budget.end();
	}

	@Test void oldInputRatherThanLoweredOutputDeterminesReprojectionAdmission() {
		assertTrue(InventoryItemBudget.reprojectionWorkspaceUpperBound(20000)
			> InventoryItemBudget.reprojectionWorkspaceUpperBound(1024));
		assertEquals(2367488, InventoryItemBudget.reprojectionWorkspaceUpperBound(131072));
		assertTrue(InventoryItemBudget.reprojectionWorkUpperBound(20000) > InventoryItemBudget.reprojectionWorkUpperBound(1024));
		assertThrows(IllegalArgumentException.class,
			() -> InventoryItemBudget.reprojectionWorkspaceUpperBound(-1));
		assertThrows(IllegalArgumentException.class,
			() -> InventoryItemBudget.workspaceUpperBound(InventoryItemCodec.minimumDisplayBytes() - 1));
		assertThrows(IllegalArgumentException.class,
			() -> InventoryItemBudget.capture(DISPLAY, 0, 1, 1));
	}

	@Test void lifecycleCannotOverlapOrChangeTheAdmittedDisplayCeiling() {
		var budget = InventoryItemBudget.root(DISPLAY);
		assertThrows(IllegalStateException.class, () -> budget.work(1));
		assertThrows(IllegalStateException.class, () -> budget.begin(DISPLAY * 2));
		budget.begin(DISPLAY);
		assertThrows(IllegalStateException.class, () -> budget.begin(DISPLAY));
		budget.end();
		assertThrows(IllegalStateException.class, budget::end);
	}
}
