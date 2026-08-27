package nx.pingwheel.common.config;

/**
 * Pure boundary clamps for the server-authoritative marker settings in
 * {@link ServerConfig}.
 *
 * <p>This class is deliberately free of any Minecraft, loader, or file-system
 * dependency so the clamp boundaries can be tested without a game client or a
 * platform service loader. {@link ServerConfig#validate()} delegates here;
 * Gson accepts the legacy {@code pingDuration} key through the config field's
 * alternate name while writing the new {@code syncDuration} key.
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

	/** The default synchronized marker duration, in seconds. */
	public static final int DEFAULT_SYNC_DURATION = 7;

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
	public static int clampSyncDuration(int value) {
		return Math.clamp(value, MIN_PING_DURATION, MAX_PING_DURATION);
	}

	/**
	 * Compatibility alias for callers that still use the pre-split terminology.
	 */
	@Deprecated
	public static int clampPingDuration(int value) {
		return clampSyncDuration(value);
	}

	/**
	 * Clamps a ping distance in blocks to {@code [1, 2048]}.
	 */
	public static int clampPingDistance(int value) {
		return Math.clamp(value, MIN_PING_DISTANCE, MAX_PING_DISTANCE);
	}
}
