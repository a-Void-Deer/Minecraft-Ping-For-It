package nx.pingwheel.common.client.outline;

/**
 * Keeps the two client render clocks distinct. Built-in BER/baked rendering
 * uses the ordinary world partial tick, while rotating/scrolling Flywheel
 * mask math uses the exact Flywheel clock captured from
 * {@code getGameTimeDeltaPartialTick(false)}.
 */
public final class FlywheelRenderClock {
	private FlywheelRenderClock() {}

	/** Returns the exact optional-render clock without changing built-in timing. */
	public static float maskPartialTick(float builtInPartialTick, float flywheelPartialTick) {
		return flywheelPartialTick;
	}
}
