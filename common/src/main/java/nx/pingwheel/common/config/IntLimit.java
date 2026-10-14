package nx.pingwheel.common.config;

import lombok.AccessLevel;
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
 *
 * <p>A persisted object whose {@code value} member is absent or null restores
 * the owning field's model default during validation, while an explicitly
 * stored zero or negative value still clamps to the finite minimum. The
 * presence marker is transient, never serialized, and excluded from equality.
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
public final class IntLimit {

	private boolean unlimited;
	private int value;

	/**
	 * True while a persisted object supplied no finite value member and the
	 * owning field's model default has not been restored yet.
	 */
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private transient boolean valueMissing;

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

	/** Explicit assignment clears any pending persisted-member default. */
	public void setValue(int value) {
		this.value = value;
		this.valueMissing = false;
	}

	/**
	 * Restores the owning field's model default when the persisted object
	 * omitted (or nulled) the finite value member, then clears the marker so a
	 * later validation only clamps. An explicitly stored value never reaches
	 * this path.
	 */
	void restoreMissingValue(int modelDefault) {
		if (!valueMissing) return;
		value = modelDefault;
		valueMissing = false;
	}

	/** Records that the persisted object supplied no finite value member. */
	void markValueMissing() {
		valueMissing = true;
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
