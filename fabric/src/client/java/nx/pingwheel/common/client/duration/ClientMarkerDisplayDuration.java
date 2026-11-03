package nx.pingwheel.common.client.duration;

import java.util.Objects;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.ClientConfigBounds;
import nx.pingwheel.common.marker.MarkerSnapshot;

/**
 * Resolves the local visual lifetime for one received marker.
 *
 * <p>The follow-server sentinel is resolved from the marker snapshot itself,
 * rather than from the latest connection policy. This keeps a marker's
 * already-frozen server lifetime correct across policy updates and packet
 * arrival races.
 */
public final class ClientMarkerDisplayDuration {

	private ClientMarkerDisplayDuration() {}

	/**
	 * Returns the display lifetime in client ticks for a received marker.
	 * Invalid persisted values are clamped defensively before being used.
	 */
	public static long durationTicks(int configuredSeconds, MarkerSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");

		int effectiveSeconds = ClientConfigBounds.clampMarkerDisplayDuration(configuredSeconds);
		if (effectiveSeconds == ClientConfigBounds.FOLLOW_SERVER_MARKER_DISPLAY_DURATION) {
			return ClientMarker.serverDurationTicks(snapshot);
		}

		return (long) effectiveSeconds * ClientConfig.TPS;
	}
}
