package nx.pingwheel.common.marker;

/**
 * Why the server rejected a marker create/remove request.
 *
 * <p>Rejections are explicit: a rejected request never produces a marker or
 * removal, and the reason is sent back to the requesting client so it can
 * surface the appropriate feedback.
 */
public enum MarkerRejectReason {
	TARGET_GONE,
	INVALID_PING_TYPE,
	OUT_OF_RANGE,
	CHANNEL_DISABLED,
	RATE_LIMITED,
	INVALID_REQUEST,
	NOT_OWNER,
	NOT_FOUND
}
