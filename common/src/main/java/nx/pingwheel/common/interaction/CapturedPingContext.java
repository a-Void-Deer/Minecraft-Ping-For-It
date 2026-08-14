package nx.pingwheel.common.interaction;

import java.util.Objects;

import nx.pingwheel.common.domain.ResolvedTarget;

/**
 * The frozen outcome of one interaction: the {@link InteractionToken} that owns
 * it plus the {@link ResolvedTarget} resolved once at capture time.
 *
 * <p>This value is deliberately minimal: it carries no hold timing, wheel
 * state, cancellation, or error data. Those concerns belong to the phase-5
 * interaction state machine. Both fields are validated non-null and are
 * effectively immutable (the token is identity-compared and the resolved target
 * is an immutable record).
 */
public record CapturedPingContext(InteractionToken token, ResolvedTarget resolvedTarget) {

	public CapturedPingContext {
		Objects.requireNonNull(token, "token");
		Objects.requireNonNull(resolvedTarget, "resolvedTarget");
	}
}
