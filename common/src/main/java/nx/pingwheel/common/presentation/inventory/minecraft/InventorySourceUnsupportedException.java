package nx.pingwheel.common.presentation.inventory.minecraft;

import org.jetbrains.annotations.NotNullByDefault;

/** Explicit optional-route absence observed during paid initial discovery. */
@NotNullByDefault
public final class InventorySourceUnsupportedException extends IllegalStateException {
	private static final long serialVersionUID = 1L;
	public InventorySourceUnsupportedException() { super("optional inventory resource route absent"); }
}
