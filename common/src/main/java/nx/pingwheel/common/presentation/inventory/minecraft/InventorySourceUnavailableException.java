package nx.pingwheel.common.presentation.inventory.minecraft;

/** Expected read denial or lifecycle/topology loss, not a failing provider callback. */
public final class InventorySourceUnavailableException extends IllegalStateException {

	public InventorySourceUnavailableException(String message) {
		super(message);
	}
}
