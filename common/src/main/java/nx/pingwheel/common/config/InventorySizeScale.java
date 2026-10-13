package nx.pingwheel.common.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The confirmed quantized size scales for the two finite inventory display and
 * frame byte multipliers. A persisted value is an exact {@link BigDecimal}
 * scalar with no unlimited mode:
 *
 * <ul>
 *   <li>{@code DISPLAY} {@code 0.25..32}: the 44 legal display sizes
 *       {@code 1..8} KiB step {@code 1}, {@code 10..32} KiB step {@code 2},
 *       and {@code 36..128} KiB step {@code 4}, so the multiplier is the size
 *       in KiB divided by four;</li>
 *   <li>{@code FRAME} {@code 0.25..16} step {@code 0.25}: the 64
 *       quarter-multiplier positions of the 32 KiB frame base.</li>
 * </ul>
 *
 * <p>Both scales share the quarter-multiplier wire quantum, so a legal value is
 * an exact positive integer number of quarter-multipliers. The display scale is
 * not contiguous above 8 KiB, so wire quanta and ordered slider positions are
 * distinct lookups: {@link #quantaOf(BigDecimal)} / {@link #fromQuanta(int)}
 * address the wire quanta, while {@link #indexOf(BigDecimal)} /
 * {@link #valueAt(int)} address the ordered legal values.
 *
 * <p>Normalization uses exact {@link BigDecimal} arithmetic only: a missing or
 * null value gains the 1x default, out-of-range finite values clamp to the
 * endpoints, and any other value snaps to the nearest legal value with half-up
 * tie breaking, matching the established exact-grid convention.
 */
public enum InventorySizeScale {

	DISPLAY(InventoryLimits.DISPLAY_BASE_BYTES, displayQuanta()),
	FRAME(InventoryLimits.FRAME_BASE_BYTES, frameQuanta());

	private static final BigDecimal FOUR = new BigDecimal("4");
	private static final int QUANTA_PER_WHOLE = 4;

	private final int baseBytes;
	/** Ordered legal quarter-multiplier quanta, ascending. */
	private final int[] quanta;
	private final List<BigDecimal> legalValues;
	private final BigDecimal minimumMultiplier;
	private final BigDecimal maximumMultiplier;

	InventorySizeScale(int baseBytes, int[] quanta) {
		this.baseBytes = baseBytes;
		this.quanta = quanta;
		var values = new ArrayList<BigDecimal>(quanta.length);
		BigDecimal quantum = BigDecimal.valueOf(4);
		for (int value : quanta) {
			values.add(ByteMultiplierGrid.canonical(BigDecimal.valueOf(value).divide(quantum)));
		}
		this.legalValues = Collections.unmodifiableList(values);
		this.minimumMultiplier = legalValues.get(0);
		this.maximumMultiplier = legalValues.get(legalValues.size() - 1);
	}

	public BigDecimal minimumMultiplier() { return minimumMultiplier; }
	public BigDecimal maximumMultiplier() { return maximumMultiplier; }
	/** Missing or null persisted values gain the 1x default. */
	public BigDecimal defaultMultiplier() { return BigDecimal.ONE; }
	public int baseBytes() { return baseBytes; }

	/** Immutable ordered legal values, ascending; UI slider positions are these indices. */
	public List<BigDecimal> legalValues() { return legalValues; }

	/** True only for an exact legal value on this scale; never clamps or rounds. */
	public boolean isLegal(BigDecimal supplied) {
		if (supplied == null) return false;
		try {
			return isLegalQuanta(supplied.multiply(FOUR).intValueExact());
		} catch (ArithmeticException notExact) {
			return false;
		}
	}

	/** True for an exact legal quarter-multiplier quantum on this scale. */
	public boolean isLegalQuanta(int value) {
		return Arrays.binarySearch(quanta, value) >= 0;
	}

	/**
	 * Snaps any supplied finite value onto this scale: null gains the default,
	 * out-of-range values clamp to the endpoint, and other values snap to the
	 * nearest legal value with half-up tie breaking.
	 */
	public BigDecimal normalize(BigDecimal supplied) {
		if (supplied == null) return defaultMultiplier();
		if (supplied.compareTo(minimumMultiplier) < 0) return minimumMultiplier;
		if (supplied.compareTo(maximumMultiplier) > 0) return maximumMultiplier;

		BigDecimal scaled = supplied.multiply(FOUR);
		int lower = quanta[floorIndex(scaled)];
		int upper = quanta[ceilIndex(scaled)];
		BigDecimal lowerDistance = scaled.subtract(BigDecimal.valueOf(lower));
		BigDecimal upperDistance = BigDecimal.valueOf(upper).subtract(scaled);
		return fromQuanta(lowerDistance.compareTo(upperDistance) < 0 ? lower : upper);
	}

	/** The exact quarter-multiplier quanta of a supplied value; never rounds. */
	public int quantaOf(BigDecimal supplied) {
		if (supplied == null) throw new IllegalArgumentException("multiplier must not be null");
		return supplied.multiply(FOUR).intValueExact();
	}

	/** The exact multiplier for a legal quarter-multiplier quantum; throws on an illegal one. */
	public BigDecimal fromQuanta(int value) {
		int position = Arrays.binarySearch(quanta, value);
		if (position < 0) throw new IllegalArgumentException("illegal " + name() + " quanta: " + value);
		return legalValues.get(position);
	}

	/** Exact bytes for a legal multiplier; the base and quarter quantum divide exactly. */
	public long bytes(BigDecimal multiplier) {
		if (!isLegal(multiplier)) throw new IllegalArgumentException("illegal " + name() + " multiplier: " + multiplier);
		return (long) baseBytes * quantaOf(multiplier) / QUANTA_PER_WHOLE;
	}

	/** The ordered slider position of a supplied value, after normalization. */
	public int indexOf(BigDecimal supplied) {
		return Collections.binarySearch(legalValues, normalize(supplied));
	}

	/** The legal value at an ordered slider position, clamped into range. */
	public BigDecimal valueAt(int position) {
		return legalValues.get(Math.clamp(position, 0, legalValues.size() - 1));
	}

	/** The next legal value above the supplied one, saturating at the maximum. */
	public BigDecimal next(BigDecimal supplied) {
		int position = indexOf(supplied);
		return position >= legalValues.size() - 1 ? maximumMultiplier : legalValues.get(position + 1);
	}

	/** The previous legal value below the supplied one, saturating at the minimum. */
	public BigDecimal previous(BigDecimal supplied) {
		int position = indexOf(supplied);
		return position <= 0 ? minimumMultiplier : legalValues.get(position - 1);
	}

	/**
	 * The largest legal display multiplier whose bytes fit the frame budget
	 * minus the fixed metadata reserve. The frame is normalized first and is
	 * never raised by the display budget.
	 */
	public static BigDecimal maximumDisplayMultiplier(BigDecimal frameMultiplier) {
		BigDecimal frame = FRAME.normalize(frameMultiplier);
		long limitBytes = FRAME.bytes(frame) - InventoryLimits.FRAME_METADATA_RESERVE_BYTES;
		long maximumQuanta = limitBytes / (InventoryLimits.DISPLAY_BASE_BYTES / QUANTA_PER_WHOLE);
		if (maximumQuanta < DISPLAY.quanta[0]) return DISPLAY.minimumMultiplier;

		int capped = (int) Math.min(maximumQuanta, DISPLAY.quanta[DISPLAY.quanta.length - 1]);
		int position = DISPLAY.floorIndex(BigDecimal.valueOf(capped));
		return DISPLAY.fromQuanta(DISPLAY.quanta[position]);
	}

	/** True when both values are legal and {@code displayBytes + reserve <= frameBytes}. */
	public static boolean isPairLegal(BigDecimal displayMultiplier, BigDecimal frameMultiplier) {
		return DISPLAY.isLegal(displayMultiplier)
			&& FRAME.isLegal(frameMultiplier)
			&& DISPLAY.bytes(displayMultiplier) + InventoryLimits.FRAME_METADATA_RESERVE_BYTES
				<= FRAME.bytes(frameMultiplier);
	}

	/**
	 * The immutable reconciled display/frame pair. {@code displayReduced} is
	 * true only when the frame-compatibility rule lowered the display below its
	 * own normalized value.
	 */
	public record SizePair(BigDecimal displayMultiplier, BigDecimal frameMultiplier, boolean displayReduced) {
		public SizePair {
			displayMultiplier = ByteMultiplierGrid.canonical(displayMultiplier);
			frameMultiplier = ByteMultiplierGrid.canonical(frameMultiplier);
		}
	}

	/**
	 * Normalizes the frame first and then the display, and lowers the display to
	 * the largest legal value the frame can hold. The frame is never raised.
	 */
	public static SizePair reconcile(BigDecimal displayMultiplier, BigDecimal frameMultiplier) {
		BigDecimal frame = FRAME.normalize(frameMultiplier);
		BigDecimal display = DISPLAY.normalize(displayMultiplier);
		BigDecimal maximumDisplay = maximumDisplayMultiplier(frame);
		if (display.compareTo(maximumDisplay) > 0) {
			return new SizePair(maximumDisplay, frame, true);
		}
		return new SizePair(display, frame, false);
	}

	private int floorIndex(BigDecimal scaledQuanta) {
		int floor = scaledQuanta.setScale(0, RoundingMode.FLOOR).intValueExact();
		int position = Arrays.binarySearch(quanta, floor);
		return position >= 0 ? position : -position - 2;
	}

	private int ceilIndex(BigDecimal scaledQuanta) {
		int ceil = scaledQuanta.setScale(0, RoundingMode.CEILING).intValueExact();
		int position = Arrays.binarySearch(quanta, ceil);
		return position >= 0 ? position : -position - 1;
	}

	private static int[] displayQuanta() {
		int[] values = new int[44];
		int index = 0;
		for (int kib = 1; kib <= 8; kib++) values[index++] = kib;
		for (int kib = 10; kib <= 32; kib += 2) values[index++] = kib;
		for (int kib = 36; kib <= InventoryLimits.MAX_DISPLAY_BYTES / 1024; kib += 4) values[index++] = kib;
		if (index != values.length) throw new IllegalStateException("display quanta grid changed");
		return values;
	}

	private static int[] frameQuanta() {
		// Literal 4: enum constant arguments run before the static fields initialize.
		int[] values = new int[InventoryLimits.MAX_FRAME_BYTES / InventoryLimits.FRAME_BASE_BYTES * 4];
		for (int index = 0; index < values.length; index++) values[index] = index + 1;
		return values;
	}
}
