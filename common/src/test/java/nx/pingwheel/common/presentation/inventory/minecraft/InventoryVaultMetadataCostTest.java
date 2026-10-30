package nx.pingwheel.common.presentation.inventory.minecraft;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Warm invocation accounting is restricted to the already-acquired fixed Vault metadata API. */
@NotNullByDefault
class InventoryVaultMetadataCostTest {
	@Test void warmValidationStillPaysEveryInvocationWrapperAndWorkWhileDiscoveryAndUnknownLocalsStayCold() {
		long object = InventoryItemBudget.OBJECT_BYTES, reference = InventoryItemBudget.REFERENCE_BYTES;
		for (int members : new int[] {1, 2, 45, 81}) {
			var warm = InventoryValidationBudget.vaultValidation(members);
			long calls = 6 + 3L * members;
			long wrappers = (2 + 2L * members) * (2 * object + 5 * reference) + members * (object + 3 * reference);
			assertEquals(calls * 2 * object + wrappers, warm.allocationBytes(), "each zero-arg invoke/boxing and both live member wrappers remain paid");
			assertEquals(2 * object + wrappers, warm.workspaceBytes(), "invocation scratch overlaps the live wrapper envelope");
			assertEquals(calls * 8 + 8 + 9L * members, warm.workUnits());
			var cold = InventoryValidationBudget.vaultLayout(members);
			assertTrue(cold.allocationBytes() > warm.allocationBytes()); assertTrue(cold.workspaceBytes() > warm.workspaceBytes());
			assertTrue(cold.workUnits() > warm.workUnits());
		}
		var one = InventoryValidationBudget.localClassification(1); var two = InventoryValidationBudget.localClassification(2);
		assertEquals(2 * one.allocationBytes(), two.allocationBytes()); assertEquals(2 * one.workUnits(), two.workUnits());
		assertEquals(one.workspaceBytes(), two.workspaceBytes(), "replacement local audits retain full cold discovery, sequential by peak");
		assertTrue(one.allocationBytes() > InventoryValidationBudget.vaultValidation(81).allocationBytes(), "unknown subclass parent walks were not globally warmed");
	}
}
