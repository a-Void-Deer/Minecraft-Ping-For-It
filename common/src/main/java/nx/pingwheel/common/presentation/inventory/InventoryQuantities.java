package nx.pingwheel.common.presentation.inventory;

import org.jetbrains.annotations.NotNullByDefault;

/** Exact nonnegative quantities. Overflow is a failed observation, never saturation or fallback identity. */
@NotNullByDefault
public final class InventoryQuantities {
	public static final long FLUID_UNITS_PER_BUCKET = 81000;
	public static final long FLUID_UNITS_PER_MILLIBUCKET = 81;
	private InventoryQuantities() {}
	public static long require(long amount) {
		if (amount < 0) throw new IllegalArgumentException("negative inventory quantity");
		return amount;
	}
	/** Fabric's native transfer unit is already U = 1/81000 bucket. */
	public static long fromFabric(long nativeAmount) { return require(nativeAmount); }
	/** Forge and NeoForge native millibuckets; admit/read independently of this checked conversion. */
	public static long fromMillibuckets(long millibuckets) { return Math.multiplyExact(require(millibuckets), FLUID_UNITS_PER_MILLIBUCKET); }
	public static long add(long first, long second) { return Math.addExact(require(first), require(second)); }
}
