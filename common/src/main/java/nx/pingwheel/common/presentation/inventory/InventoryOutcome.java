package nx.pingwheel.common.presentation.inventory;

import org.jetbrains.annotations.NotNullByDefault;

/** Final inventory outcome carried by one diagnostic report. */
@NotNullByDefault
public enum InventoryOutcome {
	ACCEPTED("accepted"),
	DEFERRED("deferred"),
	UNSUPPORTED("unsupported"),
	REJECTED("rejected"),
	TRUNCATED("truncated"),
	DEGRADED("degraded"),
	FAILED("failed");

	private final String code;

	InventoryOutcome(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}
}
