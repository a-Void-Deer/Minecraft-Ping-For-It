package nx.pingwheel.common.config;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The confirmed quantized grids for the server-authoritative send-byte
 * multipliers. Values are normalized with exact {@link BigDecimal} arithmetic
 * so floating-point rounding can never store an off-grid value or drift a byte
 * allowance:
 *
 * <ul>
 *   <li>client multiplier {@code 0.25..16}: step {@code 0.25} below 4,
 *       {@code 0.5} below 8, {@code 1} from 8;</li>
 *   <li>global multiplier {@code 0.125..32}: step {@code 0.125} below 4,
 *       {@code 0.25} below 8, {@code 0.5} below 16, {@code 1} from 16.</li>
 * </ul>
 *
 * <p>The step boundaries are aligned, so the value set is a real integer grid
 * and every base byte amount used by {@link InventorySettings} stays an exact
 * integer after scaling.
 */
public enum ByteMultiplierGrid {

	CLIENT("0.25", "0.25", "16"),
	GLOBAL("0.125", "0.125", "32");

	private static final BigDecimal FOUR = new BigDecimal("4");
	private static final BigDecimal EIGHT = new BigDecimal("8");
	private static final BigDecimal SIXTEEN = new BigDecimal("16");

	private final BigDecimal quantum;
	private final BigDecimal minimum;
	private final BigDecimal maximum;
	private final int unitsPerWhole;

	ByteMultiplierGrid(String quantum, String minimum, String maximum) {
		this.quantum = new BigDecimal(quantum);
		this.minimum = new BigDecimal(minimum);
		this.maximum = new BigDecimal(maximum);
		this.unitsPerWhole = BigDecimal.ONE.divide(this.quantum).intValueExact();
	}

	public BigDecimal quantum() { return quantum; }
	public BigDecimal minimum() { return minimum; }
	public BigDecimal maximum() { return maximum; }
	public int unitsPerWhole() { return unitsPerWhole; }

	/** The confirmed piecewise step at the given value. */
	public BigDecimal stepFor(BigDecimal value) {
		if (value.compareTo(FOUR) < 0) return quantum;
		if (value.compareTo(EIGHT) < 0) return this == GLOBAL ? new BigDecimal("0.25") : new BigDecimal("0.5");
		if (this == CLIENT) return BigDecimal.ONE;
		if (value.compareTo(SIXTEEN) < 0) return new BigDecimal("0.5");
		return BigDecimal.ONE;
	}

	/**
	 * Snaps any supplied value onto the confirmed grid and clamps it into the
	 * confirmed range. Null and non-positive entries fall back to the minimum.
	 */
	public BigDecimal normalize(BigDecimal supplied) {
		if (supplied == null) return minimum;
		BigDecimal value = supplied;
		if (value.compareTo(minimum) < 0) value = minimum;
		if (value.compareTo(maximum) > 0) value = maximum;

		BigDecimal step = stepFor(value);
		value = value.divide(step, 0, RoundingMode.HALF_UP).multiply(step);

		if (value.compareTo(minimum) < 0) value = minimum;
		if (value.compareTo(maximum) > 0) value = maximum;
		return canonical(value);
	}

	/** The next legal value above {@code current}, saturating at the maximum. */
	public BigDecimal next(BigDecimal current) {
		BigDecimal normalized = normalize(current);
		BigDecimal next = normalized.add(stepFor(normalized));
		return next.compareTo(maximum) > 0 ? maximum : canonical(next);
	}

	/** The previous legal value below {@code current}, saturating at the minimum. */
	public BigDecimal previous(BigDecimal current) {
		BigDecimal normalized = normalize(current);
		BigDecimal below = normalized.subtract(quantum);
		if (below.compareTo(minimum) < 0) return minimum;

		BigDecimal previous = normalized.subtract(stepFor(below));
		return previous.compareTo(minimum) < 0 ? minimum : canonical(previous);
	}

	/** Exact number of grid quanta in a validated value. */
	public int quantaOf(BigDecimal value) {
		return value.divide(quantum, 0, RoundingMode.UNNECESSARY).intValueExact();
	}

	/** A scale-stable form so equal numeric values have equal hash codes and stable JSON. */
	public static BigDecimal canonical(BigDecimal value) {
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}
}
