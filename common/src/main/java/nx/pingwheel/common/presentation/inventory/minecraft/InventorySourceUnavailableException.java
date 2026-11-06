package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.function.Supplier;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Expected read denial or lifecycle/topology loss, not a failing provider callback. */
@NotNullByDefault
public final class InventorySourceUnavailableException extends IllegalStateException {

	private final @Nullable Supplier<String> diagnosticContext;

	public InventorySourceUnavailableException(String message) {
		super(message);
		diagnosticContext = null;
	}

	/**
	 * Keeps an already-collected, bounded diagnostic context for the consuming
	 * logging boundary. The supplier is not evaluated during construction,
	 * message access or business validation.
	 */
	public InventorySourceUnavailableException(String message, Supplier<String> diagnosticContext) {
		super(message);
		this.diagnosticContext = diagnosticContext;
	}

	/** Call only inside a lazy diagnostic detail supplier. */
	public @Nullable String diagnosticContext() {
		@Nullable Supplier<String> context = diagnosticContext;
		return context == null ? null : context.get();
	}
}
