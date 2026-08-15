package nx.pingwheel.common.render;

/**
 * Pure rendering color policy shared by the marker HUD renderers.
 *
 * <p>Decides which color feeds each part of the ping overlay without touching
 * {@link com.mojang.blaze3d.systems.RenderSystem}:
 * <ul>
 *   <li>marker point, custom texture, and direction icon colors pass the
 *       {@link nx.pingwheel.common.client.marker.MarkerView#getPingColor()}
 *       value through unchanged;</li>
 *   <li>the distance text is always opaque white, independent of
 *       {@code TeamColorMode} and the owner's team color.</li>
 * </ul>
 *
 * <p>Pure functions: safe to unit-test without a running game.
 */
public final class RenderColorPolicy {
	private RenderColorPolicy() {}

	/** Opaque white used for the distance text. */
	public static final int DISTANCE_TEXT_COLOR = 0xFFFFFFFF;

	/**
	 * The color for the marker point, custom texture, and direction icon:
	 * the ping color passed through unchanged.
	 */
	public static int markerColor(int pingColor) {
		return pingColor;
	}

	/**
	 * The color for the distance text: always opaque white, never the team
	 * color.
	 */
	public static int distanceTextColor() {
		return DISTANCE_TEXT_COLOR;
	}
}
