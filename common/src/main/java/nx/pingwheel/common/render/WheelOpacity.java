package nx.pingwheel.common.render;

/**
 * Pure alpha handling for the wheel overlay.
 *
 * <p>Wheel opacity is applied to the alpha channel only.  This keeps every
 * existing visual's relative alpha intact while allowing the complete wheel
 * to fade without changing its selection semantics or RGB colours.
 */
public final class WheelOpacity {

	private static final int MIN_PERCENT = 0;
	private static final int MAX_PERCENT = 100;

	private WheelOpacity() {}

	public static int clampPercent(int percent) {
		return Math.clamp(percent, MIN_PERCENT, MAX_PERCENT);
	}

	/**
	 * Returns {@code argb} with its alpha multiplied by {@code opacityPercent}.
	 * Alpha multiplication is rounded to the nearest integer and clamped to the
	 * ARGB channel range.
	 */
	public static int apply(int argb, int opacityPercent) {
		int percent = clampPercent(opacityPercent);

		if (percent == MAX_PERCENT) {
			return argb;
		}

		int baseAlpha = (argb >>> 24) & 0xFF;
		int alpha = Math.clamp((int) Math.round(baseAlpha * percent / 100.0), 0, 0xFF);
		return (argb & 0x00FFFFFF) | (alpha << 24);
	}
}
