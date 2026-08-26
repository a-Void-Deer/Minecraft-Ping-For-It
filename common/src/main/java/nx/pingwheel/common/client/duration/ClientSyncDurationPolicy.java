package nx.pingwheel.common.client.duration;

import nx.pingwheel.common.config.ServerConfigBounds;

/**
 * The last valid server-authoritative marker display duration received by a
 * client connection.  Client rendering/lifecycle behavior is deliberately
 * not derived from this value in this stage.
 */
public record ClientSyncDurationPolicy(int syncDuration) {
	public static final ClientSyncDurationPolicy DEFAULT =
		new ClientSyncDurationPolicy(ServerConfigBounds.DEFAULT_SYNC_DURATION);

	public boolean isValid() {
		return syncDuration >= ServerConfigBounds.MIN_PING_DURATION
			&& syncDuration <= ServerConfigBounds.MAX_PING_DURATION;
	}
}
