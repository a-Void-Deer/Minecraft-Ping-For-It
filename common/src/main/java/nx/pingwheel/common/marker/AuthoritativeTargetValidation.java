package nx.pingwheel.common.marker;

import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, authoritative validation verdict for a requested ping target.
 *
 * <p>The verdict is either {@link #accepted(ValidatedMarkerTarget)} or
 * {@link #rejected(MarkerRejectReason)}; there is no third state. The invariant
 * "accepted iff a validated target is present iff no reject reason" is enforced
 * by the strict factories, so callers never have to defend against an
 * inconsistent value.
 *
 * <p>Construction is restricted to the static factories so an invalid
 * combination (for example a reject reason on an accepted verdict) cannot be
 * built.
 */
public final class AuthoritativeTargetValidation {

	private final boolean accepted;
	private final ValidatedMarkerTarget validatedTarget;
	private final MarkerRejectReason rejectReason;

	private AuthoritativeTargetValidation(
		boolean accepted, ValidatedMarkerTarget validatedTarget, MarkerRejectReason rejectReason
	) {
		this.accepted = accepted;
		this.validatedTarget = validatedTarget;
		this.rejectReason = rejectReason;
	}

	/**
	 * An accepted verdict carrying the server's normalized target form.
	 */
	public static AuthoritativeTargetValidation accepted(ValidatedMarkerTarget validatedTarget) {
		Objects.requireNonNull(validatedTarget, "validatedTarget");
		return new AuthoritativeTargetValidation(true, validatedTarget, null);
	}

	/**
	 * A rejected verdict carrying a non-null reason.
	 */
	public static AuthoritativeTargetValidation rejected(MarkerRejectReason reason) {
		Objects.requireNonNull(reason, "reason");
		return new AuthoritativeTargetValidation(false, null, reason);
	}

	/**
	 * Whether the requested target was accepted.
	 */
	public boolean isAccepted() {
		return accepted;
	}

	/**
	 * The server's normalized target form; present only when accepted.
	 */
	public Optional<ValidatedMarkerTarget> validatedTarget() {
		return accepted ? Optional.of(validatedTarget) : Optional.empty();
	}

	/**
	 * The reason the target was rejected; empty when accepted.
	 */
	public Optional<MarkerRejectReason> rejectReason() {
		return accepted ? Optional.empty() : Optional.of(rejectReason);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}

		if (!(obj instanceof AuthoritativeTargetValidation other)) {
			return false;
		}

		return accepted == other.accepted
			&& java.util.Objects.equals(validatedTarget, other.validatedTarget)
			&& rejectReason == other.rejectReason;
	}

	@Override
	public int hashCode() {
		int result = Boolean.hashCode(accepted);
		result = 31 * result + (validatedTarget == null ? 0 : validatedTarget.hashCode());
		return 31 * result + (rejectReason == null ? 0 : rejectReason.hashCode());
	}

	@Override
	public String toString() {
		return accepted
			? "AuthoritativeTargetValidation{accepted: " + validatedTarget + "}"
			: "AuthoritativeTargetValidation{rejected: " + rejectReason + "}";
	}
}
