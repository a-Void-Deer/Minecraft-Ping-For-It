package nx.pingwheel.common.client.rate;

import java.util.Objects;

import nx.pingwheel.common.interaction.state.InteractionTimeSource;

/**
 * A per-runtime marker-create token bucket.
 *
 * <p>The timestamp arithmetic intentionally mirrors the existing server
 * {@code RateLimiter}: a fresh bucket has exactly {@code rateLimit} available
 * creates, an accepted create consumes one regeneration interval, and the
 * unused part of an interval is retained.  Unlike the old wall-clock server
 * helper this implementation receives the interaction clock and clamps a
 * clock rollback to zero elapsed time.</p>
 */
public final class ClientCreateRateLimiter {

	private final InteractionTimeSource timeSource;
	private ClientRateLimitPolicy policy;

	/** The server limiter's equivalent of its first-create timestamp. */
	private long startTime;
	private boolean initialized;

	public ClientCreateRateLimiter(InteractionTimeSource timeSource) {
		this(timeSource, ClientRateLimitPolicy.DEFAULT);
	}

	public ClientCreateRateLimiter(
		InteractionTimeSource timeSource,
		ClientRateLimitPolicy policy
	) {
		this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
		this.policy = Objects.requireNonNull(policy, "policy");
	}

	/**
	 * Attempts to consume one create token.
	 *
	 * @return {@code true} when the create may be sent; {@code false} when it
	 *         is throttled
	 */
	public boolean tryAcquire() {
		ClientRateLimitPolicy currentPolicy = policy;

		if (!currentPolicy.enabled()) {
			return true;
		}

		long now = timeSource.nowMillis();
		long regeneration = currentPolicy.msToRegenerate();
		long timeWindow = safeMultiply(regeneration, currentPolicy.rateLimit());

		if (!initialized) {
			// Equivalent to: now - timeWindow + regeneration.
			startTime = safeAdd(safeSubtract(now, timeWindow), regeneration);
			initialized = true;
			return true;
		}

		// A rollback must not manufacture tokens.  Keeping startTime unchanged
		// also preserves the existing bucket history until the clock catches up.
		long elapsed = now < startTime ? 0L : safeSubtract(now, startTime);

		if (elapsed > timeWindow) {
			elapsed = timeWindow;
		}

		long leftOver = elapsed - regeneration;

		if (leftOver < 0L) {
			return false;
		}

		startTime = safeSubtract(now, leftOver);
		return true;
	}

	/**
	 * Replaces the policy without resetting the timestamp/history of this
	 * runtime.  Applying the same value is deliberately a no-op.
	 */
	public void applyPolicy(ClientRateLimitPolicy policy) {
		Objects.requireNonNull(policy, "policy");

		if (this.policy.equals(policy)) {
			return;
		}

		this.policy = policy;
	}

	/**
	 * The policy currently governing this runtime.
	 */
	public ClientRateLimitPolicy policy() {
		return policy;
	}

	private static long safeMultiply(long left, long right) {
		if (left == 0L || right == 0L) {
			return 0L;
		}

		if (left > 0L && right > 0L && left > Long.MAX_VALUE / right) {
			return Long.MAX_VALUE;
		}

		if (left < 0L && right < 0L && left < Long.MAX_VALUE / right) {
			return Long.MAX_VALUE;
		}

		if (left > 0L && right < 0L && right < Long.MIN_VALUE / left) {
			return Long.MIN_VALUE;
		}

		if (left < 0L && right > 0L && left < Long.MIN_VALUE / right) {
			return Long.MIN_VALUE;
		}

		return left * right;
	}

	private static long safeAdd(long left, long right) {
		if (right > 0L && left > Long.MAX_VALUE - right) {
			return Long.MAX_VALUE;
		}

		if (right < 0L && left < Long.MIN_VALUE - right) {
			return Long.MIN_VALUE;
		}

		return left + right;
	}

	private static long safeSubtract(long left, long right) {
		if (right > 0L && left < Long.MIN_VALUE + right) {
			return Long.MIN_VALUE;
		}

		if (right < 0L && left > Long.MAX_VALUE + right) {
			return Long.MAX_VALUE;
		}

		return left - right;
	}
}
