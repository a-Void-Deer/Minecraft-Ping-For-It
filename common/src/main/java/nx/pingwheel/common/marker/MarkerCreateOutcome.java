package nx.pingwheel.common.marker;

import java.util.Objects;
import java.util.Optional;

/**
 * The immutable outcome of a marker creation attempt against
 * {@link MarkerCreationService}.
 *
 * <p>The outcome is either {@link #accepted(MarkerCreation)} or
 * {@link #rejected(MarkerRejectReason)}; there is no third state. A rejected
 * outcome guarantees that no marker was stored. The invariant "accepted iff a
 * creation is present iff no reject reason" is enforced by the strict
 * factories, so callers never have to defend against an inconsistent value.
 *
 * <p>Construction is restricted to the static factories so an invalid
 * combination (for example a reject reason on an accepted outcome) cannot be
 * built.
 */
public final class MarkerCreateOutcome {

	private final boolean accepted;
	private final MarkerCreation creation;
	private final MarkerRejectReason rejectReason;

	private MarkerCreateOutcome(boolean accepted, MarkerCreation creation, MarkerRejectReason rejectReason) {
		this.accepted = accepted;
		this.creation = creation;
		this.rejectReason = rejectReason;
	}

	/**
	 * An accepted outcome carrying the stored marker and its winner transitions.
	 */
	public static MarkerCreateOutcome accepted(MarkerCreation creation) {
		Objects.requireNonNull(creation, "creation");
		return new MarkerCreateOutcome(true, creation, null);
	}

	/**
	 * A rejected outcome carrying a non-null reason; nothing was stored.
	 */
	public static MarkerCreateOutcome rejected(MarkerRejectReason reason) {
		Objects.requireNonNull(reason, "reason");
		return new MarkerCreateOutcome(false, null, reason);
	}

	/**
	 * Whether the marker was created.
	 */
	public boolean isAccepted() {
		return accepted;
	}

	/**
	 * The stored marker and its winner transitions; present only when accepted.
	 */
	public Optional<MarkerCreation> creation() {
		return accepted ? Optional.of(creation) : Optional.empty();
	}

	/**
	 * The reason the creation was rejected; empty when accepted.
	 */
	public Optional<MarkerRejectReason> rejectReason() {
		return accepted ? Optional.empty() : Optional.of(rejectReason);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}

		if (!(obj instanceof MarkerCreateOutcome other)) {
			return false;
		}

		return accepted == other.accepted
			&& Objects.equals(creation, other.creation)
			&& rejectReason == other.rejectReason;
	}

	@Override
	public int hashCode() {
		int result = Boolean.hashCode(accepted);
		result = 31 * result + (creation == null ? 0 : creation.hashCode());
		return 31 * result + (rejectReason == null ? 0 : rejectReason.hashCode());
	}

	@Override
	public String toString() {
		return accepted
			? "MarkerCreateOutcome{accepted: " + creation + "}"
			: "MarkerCreateOutcome{rejected: " + rejectReason + "}";
	}
}
