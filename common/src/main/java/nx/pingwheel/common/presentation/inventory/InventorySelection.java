package nx.pingwheel.common.presentation.inventory;

import java.util.Objects;

/** A single server-resolved selection. Aggregate selections never invent an exact variant. */
public record InventorySelection(InventoryScanner.Key exact, String itemId, String label, String displayJson,
	boolean aggregate, String itemPingType) {
	public InventorySelection {
		Objects.requireNonNull(itemId);
		Objects.requireNonNull(label);
		Objects.requireNonNull(itemPingType);
		if (aggregate == (exact != null) || itemId.isBlank()) throw new IllegalArgumentException("selection mode");
	}
}
