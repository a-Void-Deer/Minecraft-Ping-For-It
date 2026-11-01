package nx.pingwheel.common.presentation.inventory;

import java.util.Objects;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** A single server-resolved selection. Aggregate selections never invent an exact variant. */
@NotNullByDefault
public record InventorySelection(InventoryResourceKind kind, InventoryScanner.@Nullable Key exact, String itemId, String label, @Nullable String displayJson,
	boolean aggregate, String itemPingType) {
	public InventorySelection(InventoryScanner.@Nullable Key exact, String itemId, String label, @Nullable String displayJson,
		boolean aggregate, String itemPingType) {
		this(InventoryResourceKind.ITEM, exact, itemId, label, displayJson, aggregate, itemPingType);
	}
	public InventorySelection {
		Objects.requireNonNull(kind);
		Objects.requireNonNull(itemId);
		Objects.requireNonNull(label);
		Objects.requireNonNull(itemPingType);
		if (aggregate == (exact != null) || itemId.isBlank()) throw new IllegalArgumentException("selection mode");
		if (exact != null && (exact.kind() != kind || !exact.itemId().equals(itemId))) throw new IllegalArgumentException("selection resource mismatch");
	}
	public String resourceId() { return itemId; }
}
