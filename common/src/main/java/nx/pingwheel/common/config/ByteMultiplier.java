package nx.pingwheel.common.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * A persisted send-byte multiplier with an explicit unlimited mode. The JSON
 * shape is {@code {"unlimited": false, "value": 0.25}}; the finite value is
 * preserved across an unlimited toggle and is normalized onto the matching
 * {@link ByteMultiplierGrid}.
 */
@Getter
@Setter
@ToString
@NotNullByDefault
public final class ByteMultiplier {

	private boolean unlimited;
	private @Nullable BigDecimal value;

	public ByteMultiplier() {}

	private ByteMultiplier(boolean unlimited, @Nullable BigDecimal value) {
		this.unlimited = unlimited;
		this.value = value;
	}

	/** A fresh finite 1x multiplier, independent of configuration default policy. */
	public static ByteMultiplier one() {
		return new ByteMultiplier(false, BigDecimal.ONE);
	}

	public static ByteMultiplier finite(@Nullable BigDecimal value) {
		return new ByteMultiplier(false, value);
	}

	/** Normalizes the finite value onto the confirmed grid; this never flips the unlimited flag. */
	public void validate(@Nullable ByteMultiplierGrid grid) {
		validate(grid, BigDecimal.ONE);
	}

	/** Normalizes the finite value, substituting the owning field's default only when absent. */
	public void validate(@Nullable ByteMultiplierGrid grid, BigDecimal defaultValue) {
		if (grid == null) throw new IllegalArgumentException("grid must not be null");
		if (value == null) value = defaultValue;
		value = grid.normalize(value);
	}

	/**
	 * Scales a code-owned base byte amount exactly, or returns the caller-owned
	 * finite guard in unlimited mode. The supported bases are binary KiB
	 * amounts and the grid quanta divide them exactly, so the integer division
	 * is lossless.
	 */
	public long appliedTo(long baseBytes, long unlimitedBytes, @Nullable ByteMultiplierGrid grid) {
		return appliedTo(baseBytes, unlimitedBytes, grid, BigDecimal.ONE);
	}

	/** Scales using the owning field's default only when the persisted value is absent. */
	public long appliedTo(long baseBytes, long unlimitedBytes, @Nullable ByteMultiplierGrid grid,
		BigDecimal defaultValue) {
		if (unlimited) return unlimitedBytes;

		BigDecimal effective = value == null ? defaultValue : value;
		long quanta = grid.quantaOf(effective);
		return baseBytes * quanta / grid.unitsPerWhole();
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) return true;
		if (!(other instanceof ByteMultiplier multiplier)) return false;
		if (unlimited != multiplier.unlimited) return false;
		if (value == null) return multiplier.value == null;
		return multiplier.value != null && value.compareTo(multiplier.value) == 0;
	}

	@Override
	public int hashCode() {
		int hash = Boolean.hashCode(unlimited);
		return 31 * hash + (value == null ? 0 : ByteMultiplierGrid.canonical(value).hashCode());
	}
}
