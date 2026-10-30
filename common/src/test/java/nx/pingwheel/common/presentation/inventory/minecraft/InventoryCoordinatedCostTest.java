package nx.pingwheel.common.presentation.inventory.minecraft;

import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryCoordinatedCostTest {
	@Test void wholeSlotAdmissionPaysFourSuccessChecksOneFailureRecheckAndOneLiveOperationWithoutPageChecks() {
		var validation = new InventoryValidationBudget.Cost(100, 80, 200);
		for (int members : new int[] {1, 2, 45, 81}) {
			var resident = InventoryValidationBudget.coordinatedVaultResident(members);
			var operation = InventoryValidationBudget.coordinatedOperation();
			assertTrue(resident.allocationBytes() >= InventoryValidationBudget.localClassification(2).allocationBytes());
			assertTrue(operation.allocationBytes() >= 40 + 9 * 8 + 8 + 40 + 24 * 8);
			for (int slots = 1; slots <= InventorySourceAccess.MAX_STEP; slots++) {
				var cost = InventoryValidationBudget.coordinatedRead(validation, resident, slots);
				assertEquals((5 * validation.allocationBytes() + resident.allocationBytes() + operation.allocationBytes()) * slots, cost.allocationBytes());
				assertEquals(Math.max(validation.workspaceBytes(), resident.workspaceBytes()) + operation.workspaceBytes(), cost.workspaceBytes());
				assertEquals((5 * validation.workUnits() + resident.workUnits() + operation.workUnits()) * slots, cost.workUnits());
				var exact = new InventoryValidationBudget.Allowance(cost);
				for (int slot = 0; slot < slots; slot++) exact.debit(InventoryValidationBudget.coordinatedRead(validation, resident, 1));
				assertEquals(cost.allocationBytes(), exact.attemptedAllocationBytes()); assertEquals(cost.workUnits(), exact.attemptedWorkUnits());
			}
		}
		assertThrows(IllegalArgumentException.class, () -> InventoryValidationBudget.coordinatedRead(validation, validation, -1));
		assertThrows(IllegalArgumentException.class, () -> InventoryValidationBudget.coordinatedRead(validation, validation, 65));
	}
}
