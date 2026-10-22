package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Objects;

/** Owned item observation; neither a resident stack nor a component value survives here. */
public final class InventoryDetachedItem {
	private final String itemId;
	private final InventoryIdentityMode identityMode;
	private final InventoryItemCodec.Display display;
	private final byte[] canonical;
	private final long retainedBytes;

	/** Takes exclusive ownership of an already charged buffer; callers must not retain it. */
	InventoryDetachedItem(String itemId, InventoryIdentityMode identityMode,
		InventoryItemCodec.Display display, byte[] ownedCanonical, long retainedBytes) {
		this.itemId = Objects.requireNonNull(itemId, "itemId");
		this.identityMode = Objects.requireNonNull(identityMode, "identityMode");
		this.display = Objects.requireNonNull(display, "display");
		if (!itemId.equals(display.itemId()) || retainedBytes < 0
			|| (identityMode == InventoryIdentityMode.EXACT) != (ownedCanonical != null)
			|| (ownedCanonical != null && ownedCanonical.length > InventoryItemCodec.MAX_CANONICAL_BYTES)
			|| display.aggregateRequired() != (identityMode == InventoryIdentityMode.ALL_VARIANTS)) {
			throw new IllegalArgumentException("detached inventory item disposition");
		}
		this.canonical = ownedCanonical;
		this.retainedBytes = retainedBytes;
	}

	public String itemId() { return itemId; }
	public InventoryIdentityMode identityMode() { return identityMode; }
	public InventoryItemCodec.Display display() { return display; }
	public long retainedBytes() { return retainedBytes; }

	/** Catalog-only read access, not a public mutable-array export. */
	byte[] canonicalForCatalog() { return canonical; }

	/**
	 * Same identity and canonical payload with a display reprojected inside an
	 * admitted tightening call. Only the UTF-16 display ownership shrinks; the
	 * immutable canonical buffer is shared with its replacement, never cloned.
	 */
	InventoryDetachedItem withDisplay(InventoryItemCodec.Display display) {
		Objects.requireNonNull(display, "display");
		if (!itemId.equals(display.itemId()) || !this.display.label().equals(display.label())
			|| display.aggregateRequired() != (identityMode == InventoryIdentityMode.ALL_VARIANTS))
			throw new IllegalArgumentException("detached inventory item display replacement");
		long refund = 2L * ((this.display.displayJson() == null ? 0 : this.display.displayJson().length())
			- (display.displayJson() == null ? 0 : display.displayJson().length()));
		if (refund < 0 || refund > retainedBytes) throw new IllegalArgumentException("detached inventory retained bound");
		long retained = retainedBytes - refund;
		return new InventoryDetachedItem(itemId, identityMode, display, canonical, retained);
	}

	/** Bounded defensive export for differential evidence, charged by the caller's workspace. */
	byte[] canonicalCopy(InventoryItemBudget budget) {
		if (canonical == null) return null;
		budget.allocate(canonical.length, 0, 0, 1);
		budget.work(canonical.length);
		return canonical.clone();
	}
}
