package nx.pingwheel.common.marker;

import java.util.Objects;
import java.util.Optional;

import nx.pingwheel.common.name.TargetNameJson;

/**
 * The immutable outcome of a marker creation attempt against
 * {@link MarkerCreationService}.
 *
 * <p>The outcome is either {@link #accepted(MarkerCreation, TargetNameJson)}
 * or {@link #rejected(MarkerRejectReason)}; there is no third state. An
 * accepted outcome carries the validator's authoritative target name, and a
 * rejected outcome guarantees that no marker was stored and carries no name.
 * The invariant "accepted iff a creation is present iff a name is present iff
 * no reject reason" is enforced by the strict factories, so callers never have
 * to defend against an inconsistent value.
 *
 * <p>Construction is restricted to the static factories so an invalid
 * combination (for example a reject reason on an accepted outcome) cannot be
 * built.
 */
public final class MarkerCreateOutcome {

	private final boolean accepted;
	private final MarkerCreation creation;
	private final TargetNameJson targetName;
	private final MarkerRejectReason rejectReason;

	private MarkerCreateOutcome(
		boolean accepted, MarkerCreation creation, TargetNameJson targetName, MarkerRejectReason rejectReason
	) {
		this.accepted = accepted;
		this.creation = creation;
		this.targetName = targetName;
		this.rejectReason = rejectReason;
	}

	/**
	 * An accepted outcome carrying the stored marker, its winner transitions,
	 * and the validator's authoritative target name.
	 */
	public static MarkerCreateOutcome accepted(MarkerCreation creation, TargetNameJson targetName) {
		Objects.requireNonNull(creation, "creation");
		Objects.requireNonNull(targetName, "targetName");
		return new MarkerCreateOutcome(true, creation, targetName, null);
	}

	/**
	 * A rejected outcome carrying a non-null reason; nothing was stored and no
	 * name is carried.
	 */
	public static MarkerCreateOutcome rejected(MarkerRejectReason reason) {
		Objects.requireNonNull(reason, "reason");
		return new MarkerCreateOutcome(false, null, null, reason);
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
	 * The validator's authoritative target display name JSON; present only
	 * when accepted.
	 */
	public Optional<TargetNameJson> targetName() {
		return accepted ? Optional.of(targetName) : Optional.empty();
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
			&& Objects.equals(targetName, other.targetName)
			&& rejectReason == other.rejectReason;
	}

	@Override
	public int hashCode() {
		int result = Boolean.hashCode(accepted);
		result = 31 * result + (creation == null ? 0 : creation.hashCode());
		result = 31 * result + (targetName == null ? 0 : targetName.hashCode());
		return 31 * result + (rejectReason == null ? 0 : rejectReason.hashCode());
	}

	/**
	 * Never exposes the target name content — the name JSON is decoded display
	 * text that must not leak into logs; only its presence is reported.
	 */
	@Override
	public String toString() {
		return accepted
			? "MarkerCreateOutcome{accepted: " + creation + ", namePresent: " + (targetName != null) + "}"
			: "MarkerCreateOutcome{rejected: " + rejectReason + "}";
	}
}
