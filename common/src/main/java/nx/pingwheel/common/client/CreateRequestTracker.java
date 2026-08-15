package nx.pingwheel.common.client;

/**
 * Tracks the most recently dispatched marker-create request id.
 *
 * <p>Authoritative rejections can arrive after newer create requests have
 * already been dispatched (for example when the player pings twice in quick
 * succession). Only the latest create request's {@code TARGET_GONE} rejection
 * may surface the local target-gone error; rejections for older requests are
 * stale and only debug logged by the caller.
 *
 * <p>This class keeps a single slot instead of an ever-growing set of ids:
 * anything that is not the latest request is simply ignored, so no unbounded
 * bookkeeping is needed. It is pure (JDK types only) and testable without a
 * game client.
 */
public final class CreateRequestTracker {

	private long latestRequestId;
	private boolean hasLatest;

	public CreateRequestTracker() {
	}

	/**
	 * Records {@code requestId} as the latest dispatched create request,
	 * superseding any previously recorded id.
	 */
	public void onCreateDispatched(long requestId) {
		latestRequestId = requestId;
		hasLatest = true;
	}

	/**
	 * True when no create request has been dispatched yet.
	 */
	public boolean isEmpty() {
		return !hasLatest;
	}

	/**
	 * The latest dispatched create request id; meaningless while
	 * {@link #isEmpty()} is true.
	 */
	public long latestRequestId() {
		return latestRequestId;
	}

	/**
	 * True when {@code requestId} is the latest dispatched create request id.
	 */
	public boolean isLatest(long requestId) {
		return hasLatest && requestId == latestRequestId;
	}
}
