package nx.pingwheel.common.marker;

/**
 * Why an active {@link ServerMarker} was removed.
 */
public enum MarkerRemovalReason {
	CANCELLED,
	EXPIRED,
	TARGET_INVALID,
	OWNER_DISCONNECTED
}
