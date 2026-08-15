package nx.pingwheel.common.config;

/**
 * Pure boundary clamps for the server-authoritative marker settings in
 * {@link ServerConfig}.
 *
 * <p>This class is deliberately free of any Minecraft, loader, or file-system
 * dependency so the clamp boundaries can be tested without a game client or a
 * platform service loader. {@link ServerConfig#validate()} delegates here;
 * Gson fills missing/stale keys with the field defaults before validation, so
 * no migration is needed.
 */
public final class ServerConfigBounds {

	/**
	 * The smallest supported server-authoritative ping duration, in seconds.
	 */
	public static final int MIN_PING_DURATION = 1;

	/**
	 * The largest supported server-authoritative ping duration, in seconds.
	 */
	public static final int MAX_PING_DURATION = 60;

	/**
	 * The smallest supported server-authoritative ping distance, in blocks.
	 */
	public static final int MIN_PING_DISTANCE = 1;

	/**
	 * The largest supported server-authoritative ping distance, in blocks.
	 */
	public static final int MAX_PING_DISTANCE = 2048;

	private ServerConfigBounds() {}

	/**
	 * Clamps a ping duration in seconds to {@code [1, 60]}.
	 */
	public static int clampPingDuration(int value) {
		return Math.clamp(value, MIN_PING_DURATION, MAX_PING_DURATION);
	}

	/**
	 * Clamps a ping distance in blocks to {@code [1, 2048]}.
	 */
	public static int clampPingDistance(int value) {
		return Math.clamp(value, MIN_PING_DISTANCE, MAX_PING_DISTANCE);
	}
}
