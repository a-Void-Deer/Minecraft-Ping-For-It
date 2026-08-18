package nx.pingwheel.common.interaction;

import java.util.Objects;

import nx.pingwheel.common.domain.ResolvedTarget;

/**
 * The frozen outcome of one interaction: the {@link InteractionToken} that owns
 * it, the {@link ResolvedTarget} resolved once at capture time, and the exact
 * press-time ray used for capture.
 *
 * <p>This value is deliberately minimal: it carries no hold timing, wheel
 * state, cancellation, or error data. Those concerns belong to the phase-5
 * interaction state machine. All fields are validated non-null and are
 * effectively immutable (the token is identity-compared and the resolved target
 * and ray are immutable records).
 */
public record CapturedPingContext(
	InteractionToken token,
	ResolvedTarget resolvedTarget,
	CapturedRay ray
) {

	public CapturedPingContext {
		Objects.requireNonNull(token, "token");
		Objects.requireNonNull(resolvedTarget, "resolvedTarget");
		Objects.requireNonNull(ray, "ray");
	}

	/**
	 * Compatibility constructor for pure interaction seams that predate the
	 * press-ray field. Client capture uses the three-argument constructor.
	 */
	public CapturedPingContext(InteractionToken token, ResolvedTarget resolvedTarget) {
		this(token, resolvedTarget, CapturedRay.defaultRay());
	}

	/**
	 * Descriptive alias for callers that refer to the value as a press ray.
	 */
	public CapturedRay pressRay() {
		return ray;
	}
}
