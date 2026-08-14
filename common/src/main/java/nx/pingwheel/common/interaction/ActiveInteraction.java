package nx.pingwheel.common.interaction;

import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe holder for the single active ping-key interaction.
 *
 * <p>A new {@link #begin()} supersedes and invalidates any prior token: the
 * previous token is no longer {@linkplain #isCurrent(InteractionToken) current},
 * so stale asynchronous results arriving for it are rejected. Completion is
 * one-time per token: the first accepted {@link CapturedPingContext} for the
 * current token is frozen and can never be replaced by a later duplicate or by
 * a stale token.
 *
 * <p>Sequence assignment is monotonic ({@code 0, 1, 2, ...}) for the lifetime
 * of this holder. If the sequence space is ever exhausted — i.e. the counter
 * reaches {@link Long#MAX_VALUE} — {@link #begin()} throws an
 * {@link IllegalStateException} instead of wrapping the counter negative, so a
 * negative (and therefore invalid) sequence can never be minted.
 *
 * <p>No timing, threshold, wheel, cancellation, or timeout state lives here;
 * those concerns are reserved for the phase-5 interaction state machine.
 */
public final class ActiveInteraction {

	private long nextSequence;
	private InteractionToken currentToken;
	private CapturedPingContext currentContext;

	/**
	 * Creates an interaction whose sequence counter starts at zero.
	 */
	public ActiveInteraction() {
		this(0L);
	}

	/**
	 * Creates an interaction whose sequence counter starts at
	 * {@code initialSequence}.
	 *
	 * <p>Package-private test seam used only to exercise sequence exhaustion;
	 * production callers always start at zero via {@link #ActiveInteraction()}.
	 */
	ActiveInteraction(long initialSequence) {
		if (initialSequence < 0L) {
			throw new IllegalArgumentException("initialSequence must be non-negative: " + initialSequence);
		}

		this.nextSequence = initialSequence;
	}

	/**
	 * Starts a new interaction, invalidating any prior token and clearing any
	 * prior capture. Returns the freshly minted token.
	 *
	 * <p>Throws {@link IllegalStateException} if the monotonic sequence counter
	 * is exhausted at {@link Long#MAX_VALUE}, before it could wrap negative.
	 */
	public synchronized InteractionToken begin() {
		if (nextSequence == Long.MAX_VALUE) {
			throw new IllegalStateException("interaction sequence exhausted");
		}

		InteractionToken token = new InteractionToken(nextSequence++);
		currentToken = token;
		currentContext = null;
		return token;
	}

	/**
	 * Whether {@code token} is the current (not superseded) token. A null token
	 * is never current.
	 */
	public synchronized boolean isCurrent(InteractionToken token) {
		return token != null && token == currentToken;
	}

	/**
	 * Atomically records {@code context} as the capture for {@code token}.
	 *
	 * <p>Returns {@code true} only if {@code token} is still current and no
	 * capture has been recorded for it yet. A stale token or a second
	 * completion never replaces the frozen capture. A context whose token does
	 * not match the completing token is rejected as a programming error.
	 */
	public synchronized boolean tryComplete(InteractionToken token, CapturedPingContext context) {
		Objects.requireNonNull(context, "context");

		if (token != currentToken) {
			return false;
		}

		if (currentContext != null) {
			return false;
		}

		if (context.token() != token) {
			throw new IllegalArgumentException(
				"captured context token does not match the completing token");
		}

		currentContext = context;
		return true;
	}

	/**
	 * The currently frozen capture for the current token, if any.
	 */
	public synchronized Optional<CapturedPingContext> currentContext() {
		return Optional.ofNullable(currentContext);
	}
}
