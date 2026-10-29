package nx.pingwheel.common.presentation.inventory;

import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Inventory-local historical publication authority. Each retained owner releases
 * exactly one reference. It contains metadata only, never a round or journal.
 * Validation is side-effect-free with respect to runtime/publisher collections.
 */
@NotNullByDefault
public interface InventoryPublicationEvidence extends AutoCloseable {
	InventoryValidationBudget.Cost validationCost();
	int probeCalls();
	InventorySharedRead.Check validate(InventoryValidationBudget.Allowance allowance);
	InventoryPublicationEvidence retain();
	/** Equal certified physical proof, not equal owner authority or a current policy. */
	default InventorySharedRead.@Nullable Proof proof() { return null; }
	default boolean sameProof(InventoryPublicationEvidence other) {
		InventorySharedRead.@Nullable Proof proof = proof();
		return this == other || proof != null && proof.equals(other.proof());
	}
	@Override void close();
}
