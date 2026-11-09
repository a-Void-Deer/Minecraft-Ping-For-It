package nx.pingwheel.common.platform;

import org.jetbrains.annotations.NotNullByDefault;

/**
 * Observation at the irreversible transport handoff boundary, not recipient
 * receipt or compressed physical traffic. UNOBSERVED must never be inferred as
 * success merely because an older void send method returned normally.
 */
@NotNullByDefault
public enum TransportHandoff {
	HANDED_OFF,
	NOT_HANDED_OFF,
	UNOBSERVED
}
