package nx.pingwheel.common.monitor.client;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Pure numeric presentation for the performance-monitor HUD. This class owns no
 * clock, sample, transport, translation or Minecraft type; callers supply exact
 * rational values and decide which field a result belongs to.
 *
 * <p>Byte quantities use base-1024 units and only promote at a strict raw
 * magnitude greater than 8192 of the current unit. Byte displayed numbers share
 * a four-digit capacity between integer and fractional digits; non-byte values
 * keep every integer digit and use at most two decimal places. A known lower
 * bound is rounded down (floor) and prefixed with {@code >=} so the display never
 * overstates it. Missing required inputs produce an unavailable result with no
 * text rather than a partial series or an {@code N/A} token.</p>
 */
@NotNullByDefault
public final class MonitorNumberFormat {

	private static final BigInteger BYTE_STEP = BigInteger.valueOf(1024);
	private static final BigInteger BYTE_PROMOTION_LIMIT = BigInteger.valueOf(8192);
	private static final BigInteger NANOS_PER_MILLISECOND = BigInteger.valueOf(1_000_000);
	private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000);
	private static final BigInteger PERCENT = BigInteger.valueOf(100);
	private static final int MAX_DECIMALS = 2;
	private static final int MAX_BYTE_DIGITS = 4;
	private static final int UNBOUNDED_DIGITS = Integer.MAX_VALUE;
	private static final String @Unmodifiable [] BYTE_UNITS = { "B", "KB", "MB", "GB", "TB", "PB", "EB" };
	private static final Result UNAVAILABLE = new Result(false, "");

	private MonitorNumberFormat() {}

	/** How much a recorded value is known about its true value. */
	@NotNullByDefault
	public enum Quality {
		/** The value is the measured value. */
		EXACT,
		/** The true value is at least the recorded value. */
		LOWER_BOUND,
		/** The recorded value hit an accounting limit and is a lower bound. */
		SATURATED
	}

	/** Rational value with a positive denominator. */
	@NotNullByDefault
	public record Value(BigInteger numerator, BigInteger denominator, Quality quality) {
		public Value {
			Objects.requireNonNull(numerator, "numerator");
			Objects.requireNonNull(denominator, "denominator");
			Objects.requireNonNull(quality, "quality");
			if (denominator.signum() <= 0) throw new IllegalArgumentException("denominator must be positive");
		}
	}

	/** Formatted text, or an explicitly unavailable field with no text. */
	@NotNullByDefault
	public record Result(boolean available, String text) {
		public Result {
			Objects.requireNonNull(text, "text");
			if (available == text.isEmpty()) throw new IllegalArgumentException("result availability and text disagree");
		}
	}

	public static Value integer(long value, Quality quality) {
		return new Value(BigInteger.valueOf(value), BigInteger.ONE, quality);
	}

	/** Dimensionless number: full integer digits, at most two decimals. */
	public static Result number(@Nullable Value value) {
		if (value == null) return UNAVAILABLE;
		@Nullable String text = scaled(value, BigInteger.ONE, UNBOUNDED_DIGITS);
		return text == null ? UNAVAILABLE : new Result(true, text);
	}

	/** Duration supplied in nanoseconds. The raw magnitude selects ms or s. */
	public static Result timeNs(@Nullable Value value) {
		if (value == null) return UNAVAILABLE;
		boolean seconds = value.numerator().abs().compareTo(NANOS_PER_SECOND.multiply(value.denominator())) >= 0;
		BigInteger divisor = seconds ? NANOS_PER_SECOND : NANOS_PER_MILLISECOND;
		@Nullable String text = scaled(value, divisor, UNBOUNDED_DIGITS);
		if (text == null) return UNAVAILABLE;
		return new Result(true, text + (seconds ? "s" : "ms"));
	}

	/** A byte quantity using base-1024 units and the four-digit capacity. */
	public static Result bytes(@Nullable Value value) {
		if (value == null) return UNAVAILABLE;
		return byteSeries(List.of(value));
	}

	/**
	 * A dimensionless or byte pair. The pair is only laid out; no division is
	 * performed here, so a zero cap remains displayable.
	 */
	public static Result ratio(@Nullable Value used, @Nullable Value cap, boolean byteUnits) {
		if (used == null || cap == null) return UNAVAILABLE;
		if (byteUnits) return byteSeries(List.of(used, cap));
		@Nullable String first = scaled(used, BigInteger.ONE, UNBOUNDED_DIGITS);
		@Nullable String second = scaled(cap, BigInteger.ONE, UNBOUNDED_DIGITS);
		if (first == null || second == null) return UNAVAILABLE;
		return new Result(true, first + "/" + second);
	}

	/** Three byte quantities in one unit selected by the largest magnitude. */
	public static Result byteTriplet(@Nullable Value retained, @Nullable Value occupied, @Nullable Value cap) {
		if (retained == null || occupied == null || cap == null) return UNAVAILABLE;
		return byteSeries(List.of(retained, occupied, cap));
	}

	/**
	 * An exact per-second rate. A saturated total is not a usable rate, and a
	 * non-positive elapsed interval is undefined.
	 */
	public static Result ratePerSecond(@Nullable Value total, long elapsedNs, boolean byteUnits) {
		if (total == null || elapsedNs <= 0 || total.quality() == Quality.SATURATED) return UNAVAILABLE;
		Value rate = new Value(total.numerator().multiply(NANOS_PER_SECOND),
			total.denominator().multiply(BigInteger.valueOf(elapsedNs)), total.quality());
		Result magnitude = byteUnits ? bytes(rate) : number(rate);
		if (!magnitude.available()) return UNAVAILABLE;
		return new Result(true, magnitude.text() + "/s");
	}

	/**
	 * A percentage whose denominator must be an exact positive value. A lower-
	 * bound numerator keeps its lower-bound meaning; a saturated numerator has no
	 * honest percentage. Values over 100 and negative values are not clamped.
	 */
	public static Result percent(@Nullable Value numerator, @Nullable Value denominator) {
		if (numerator == null || denominator == null) return UNAVAILABLE;
		if (denominator.quality() != Quality.EXACT || denominator.numerator().signum() <= 0) return UNAVAILABLE;
		if (numerator.quality() == Quality.SATURATED) return UNAVAILABLE;
		Quality quality = numerator.quality() == Quality.LOWER_BOUND ? Quality.LOWER_BOUND : Quality.EXACT;
		Value percentage = new Value(numerator.numerator().multiply(denominator.denominator()).multiply(PERCENT),
			numerator.denominator().multiply(denominator.numerator()), quality);
		@Nullable String text = scaled(percentage, BigInteger.ONE, UNBOUNDED_DIGITS);
		return text == null ? UNAVAILABLE : new Result(true, text + "%");
	}

	private static Result byteSeries(@Unmodifiable List<Value> values) {
		int largest = 0;
		for (int index = 1; index < values.size(); index++) {
			if (compareAbsolute(values.get(index), values.get(largest)) > 0) largest = index;
		}
		int unit = byteUnit(values.get(largest));
		BigInteger factor = BYTE_STEP.pow(unit);
		List<String> parts = new ArrayList<>(values.size());
		for (Value value : values) {
			@Nullable String part = scaled(value, factor, MAX_BYTE_DIGITS);
			if (part == null) return UNAVAILABLE;
			parts.add(part);
		}
		return new Result(true, String.join("/", parts) + BYTE_UNITS[unit]);
	}

	private static int byteUnit(Value value) {
		BigInteger absolute = value.numerator().abs();
		BigInteger factor = BigInteger.ONE;
		int unit = 0;
		while (unit < BYTE_UNITS.length - 1
			&& absolute.compareTo(BYTE_PROMOTION_LIMIT.multiply(factor).multiply(value.denominator())) > 0) {
			factor = factor.multiply(BYTE_STEP);
			unit++;
		}
		return unit;
	}

	private static int compareAbsolute(Value left, Value right) {
		return left.numerator().abs().multiply(right.denominator())
			.compareTo(right.numerator().abs().multiply(left.denominator()));
	}

	/**
	 * Formats one rational magnitude after dividing by an exact unit factor.
	 * Each precision is recomputed from the original rational so a carry is never
	 * rounded twice. Byte magnitudes return null when even zero decimals exceed the
	 * shared four-digit capacity; no unit above EB is invented.
	 */
	private static @Nullable String scaled(Value value, BigInteger divisor, int maximumDigits) {
		RoundingMode mode = value.quality() == Quality.EXACT ? RoundingMode.HALF_UP : RoundingMode.FLOOR;
		BigDecimal numerator = new BigDecimal(value.numerator());
		BigDecimal denominator = new BigDecimal(value.denominator().multiply(divisor));
		BigDecimal rounded = numerator.divide(denominator, MAX_DECIMALS, mode);
		if (value.numerator().signum() != 0 && rounded.signum() == 0) {
			// A lower bound must not become a false `<0.01` upper statement. A
			// positive floor is honestly zero at the displayed precision.
			if (value.quality() != Quality.EXACT) return ">=0";
			return value.numerator().signum() > 0 ? "<0.01" : ">-0.01";
		}
		for (int decimals = MAX_DECIMALS; decimals >= 0; decimals--) {
			if (decimals < MAX_DECIMALS) rounded = numerator.divide(denominator, decimals, mode);
			String plain = plain(rounded);
			if (digitCount(plain) <= maximumDigits) {
				return value.quality() == Quality.EXACT ? plain : ">=" + plain;
			}
		}
		return null;
	}

	private static String plain(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private static int digitCount(String plain) {
		int digits = 0;
		boolean significant = false;
		for (int index = 0; index < plain.length(); index++) {
			char character = plain.charAt(index);
			if (character == '-' || character == '.') continue;
			if (!significant && character == '0') continue;
			significant = true;
			digits++;
		}
		return digits;
	}
}
