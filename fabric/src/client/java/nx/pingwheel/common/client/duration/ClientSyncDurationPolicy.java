package nx.pingwheel.common.client.duration;

import nx.pingwheel.common.config.ServerConfigBounds;

/**
 * The last valid server-authoritative marker display duration received by a
 * client connection. Client rendering/lifecycle behavior in follow-server
 * mode is derived from each marker snapshot; this value is retained for
 * connection-scoped client policy/UI state.
 */
public record ClientSyncDurationPolicy(int syncDuration) {
	public static final ClientSyncDurationPolicy DEFAULT =
		new ClientSyncDurationPolicy(ServerConfigBounds.DEFAULT_SYNC_DURATION);

	public boolean isValid() {
		return syncDuration >= ServerConfigBounds.MIN_PING_DURATION
			&& syncDuration <= ServerConfigBounds.MAX_PING_DURATION;
	}
}
