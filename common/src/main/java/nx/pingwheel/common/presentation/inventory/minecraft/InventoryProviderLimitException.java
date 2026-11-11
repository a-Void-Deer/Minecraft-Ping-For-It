package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Objects;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import org.jetbrains.annotations.NotNullByDefault;

/** Typed native guard evidence, already reported at its owning check. */
@NotNullByDefault
public final class InventoryProviderLimitException extends IllegalStateException {
	private static final long serialVersionUID = 1L;
	private final ProviderLimitSite site;
	private final long required, limit;
	InventoryProviderLimitException(ProviderLimitSite site, long required, long limit) {
		super("inventory provider limit: " + Objects.requireNonNull(site).code());
		this.site = site; this.required = required; this.limit = limit;
	}
	public InventoryFailureReason reason() { return site.reason(); }
	public ProviderLimitSite site() { return site; }
	public long required() { return required; }
	public long limit() { return limit; }
}
