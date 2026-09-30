package nx.pingwheel.common.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One server-authoritative inventory cap that carries an explicit unlimited
 * mode. Unlimited is a persisted boolean, never a numeric sentinel: the finite
 * value is preserved for the settings UI toggle, and the consuming runtime
 * substitutes its own finite guard through {@link #effective(int)}.
 *
 * <p>The JSON shape is stable and explicit:
 * {@code {"unlimited": false, "value": 32}}.
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
public final class IntLimit {

	private boolean unlimited;
	private int value;

	public IntLimit() {}

	private IntLimit(boolean unlimited, int value) {
		this.unlimited = unlimited;
		this.value = value;
	}

	/** A finite cap with a positive value. */
	public static IntLimit finite(int value) {
		return new IntLimit(false, value);
	}

	/** An explicit unlimited cap; the finite value keeps its default for the UI toggle. */
	public static IntLimit unlimited(int finiteValue) {
		return new IntLimit(true, finiteValue);
	}

	/**
	 * Clamps the finite value to a positive integer. Non-positive entries are
	 * clamped rather than treated as "disabled" or "unlimited"; the existing
	 * configuration convention clamps numeric values.
	 */
	public void validate() {
		if (value < InventoryLimits.MIN_FINITE_LIMIT) {
			value = InventoryLimits.MIN_FINITE_LIMIT;
		}
	}

	/** The finite configured value, or the caller-owned finite guard in unlimited mode. */
	public int effective(int internalGuard) {
		return unlimited ? internalGuard : value;
	}
}
