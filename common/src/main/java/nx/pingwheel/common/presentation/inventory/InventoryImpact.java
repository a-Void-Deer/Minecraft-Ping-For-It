package nx.pingwheel.common.presentation.inventory;

import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Consequence of an inventory limit or failure, independent of its cause. The
 * impact selects the minimum logging profile; it is supplied by the decision
 * owner and is never derived from an {@link InventoryFailureReason}.
 */
@NotNullByDefault
public enum InventoryImpact {
	PING_UNAVAILABLE("ping-unavailable", LoggingProfile.NORMAL),
	DEGRADED("degraded", LoggingProfile.DEEP),
	DETAIL_ONLY("detail-only", LoggingProfile.DEBUG);

	private final String code;
	private final LoggingProfile minimumProfile;

	InventoryImpact(String code, LoggingProfile minimumProfile) {
		this.code = code;
		this.minimumProfile = minimumProfile;
	}

	public String code() {
		return code;
	}

	public LoggingProfile minimumProfile() {
		return minimumProfile;
	}
}
