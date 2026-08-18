package nx.pingwheel.common.interaction.state;

import java.util.Objects;
import java.util.Optional;

/**
 * An immutable validation verdict for a frozen
 * {@link nx.pingwheel.common.domain.ResolvedTarget}.
 *
 * <p>The verdict is either {@link #valid()} or {@link #gone(TargetGoneReason)};
 * there is no third state. The invariant "valid iff no gone reason" is enforced
 * by the strict factories, so callers never have to defend against an
 * inconsistent value.
 */
public final class TargetValidation {

	private static final TargetValidation VALID = new TargetValidation(true, null);

	private final boolean valid;
	private final TargetGoneReason goneReason;

	private TargetValidation(boolean valid, TargetGoneReason goneReason) {
		this.valid = valid;
		this.goneReason = goneReason;
	}

	/**
	 * The shared "target is still valid" verdict.
	 */
	public static TargetValidation valid() {
		return VALID;
	}

	/**
	 * A "target is gone" verdict carrying a non-null reason.
	 */
	public static TargetValidation gone(TargetGoneReason reason) {
		Objects.requireNonNull(reason, "reason");
		return new TargetValidation(false, reason);
	}

	/**
	 * Whether the captured target is still valid.
	 */
	public boolean isValid() {
		return valid;
	}

	/**
	 * The reason the target is gone; empty when the target is valid.
	 */
	public Optional<TargetGoneReason> goneReason() {
		return valid ? Optional.empty() : Optional.of(goneReason);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}

		if (!(obj instanceof TargetValidation other)) {
			return false;
		}

		return valid == other.valid && goneReason == other.goneReason;
	}

	@Override
	public int hashCode() {
		int result = Boolean.hashCode(valid);
		return 31 * result + (goneReason == null ? 0 : goneReason.hashCode());
	}

	@Override
	public String toString() {
		return valid ? "TargetValidation{valid}" : "TargetValidation{gone: " + goneReason + "}";
	}
}
