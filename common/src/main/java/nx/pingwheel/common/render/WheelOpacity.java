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
	 * Returns whether visual work should be performed for the configured
	 * opacity. A zero alpha can still be promoted to opaque by Minecraft's font
	 * renderer, so the wheel must skip rendering entirely at zero.
	 */
	public static boolean shouldRender(int opacityPercent) {
		return clampPercent(opacityPercent) > MIN_PERCENT;
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
