package nx.pingwheel.common.presentation.inventory;

import org.jetbrains.annotations.NotNullByDefault;

/** Closed inventory lanes. Quantity units are fixed by the lane, never negotiated. */
@NotNullByDefault
public enum InventoryResourceKind {
	ITEM(0, InventoryPresentation.ITEMS),
	FLUID(1, InventoryPresentation.FLUIDS);

	private final int wireId;
	private final String fieldId;

	InventoryResourceKind(int wireId, String fieldId) { this.wireId = wireId; this.fieldId = fieldId; }
	public int wireId() { return wireId; }
	public String fieldId() { return fieldId; }
	public static InventoryResourceKind fromWireId(int id) {
		return switch (id) {
			case 0 -> ITEM;
			case 1 -> FLUID;
			default -> throw new IllegalArgumentException("unknown inventory resource kind");
		};
	}
}
