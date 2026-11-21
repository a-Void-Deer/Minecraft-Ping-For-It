package nx.pingwheel.common.client.duration;

import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.ClientConfigBounds;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Selects the display time domain without conflating a custom duration with an
 * equal-length server lifetime.
 */
@NotNullByDefault
public record ClientMarkerDisplayDuration(boolean followsServer, long localTicks) {

	private static final ClientMarkerDisplayDuration FOLLOW_SERVER = new ClientMarkerDisplayDuration(true, 0L);

	public ClientMarkerDisplayDuration {
		if (localTicks < 0L || (followsServer && localTicks != 0L)) {
			throw new IllegalArgumentException("invalid marker display duration");
		}
	}

	/** Resolves the configured sentinel or a custom duration once, on first insertion. */
	public static ClientMarkerDisplayDuration fromConfig(int configuredSeconds) {
		int effectiveSeconds = ClientConfigBounds.clampMarkerDisplayDuration(configuredSeconds);
		return effectiveSeconds == ClientConfigBounds.FOLLOW_SERVER_MARKER_DISPLAY_DURATION
			? FOLLOW_SERVER : customTicks((long) effectiveSeconds * ClientConfig.TPS);
	}

	/** An explicitly local duration; a zero-tick test or integration duration is not the config sentinel. */
	public static ClientMarkerDisplayDuration customTicks(long ticks) {
		return new ClientMarkerDisplayDuration(false, ticks);
	}
}
