package nx.pingwheel.common.config;

import java.util.Optional;

/**
 * A validated, dirty-field-only server settings update.  Keeping this value
 * independent of networking makes permission and merge behavior testable
 * without constructing a Minecraft server.
 */
public record ServerConfigUpdate(
	int changedFields,
	ChannelMode defaultChannelMode,
	boolean playerTrackingEnabled,
	int msToRegenerate,
	int rateLimit
) {
	public static final int DEFAULT_CHANNEL_MODE = 1;
	public static final int PLAYER_TRACKING_ENABLED = 1 << 1;
	public static final int MS_TO_REGENERATE = 1 << 2;
	public static final int RATE_LIMIT = 1 << 3;
	public static final int ALL_FIELDS = DEFAULT_CHANNEL_MODE
		| PLAYER_TRACKING_ENABLED
		| MS_TO_REGENERATE
		| RATE_LIMIT;

	public boolean isValid() {
		return changedFields != 0
			&& (changedFields & ~ALL_FIELDS) == 0
			&& defaultChannelMode != null
			&& msToRegenerate >= 0
			&& rateLimit >= 0;
	}

	public static Optional<ServerConfigUpdate> validated(
		int changedFields,
		ChannelMode defaultChannelMode,
		boolean playerTrackingEnabled,
		int msToRegenerate,
		int rateLimit) {
		var update = new ServerConfigUpdate(
			changedFields,
			defaultChannelMode,
			playerTrackingEnabled,
			msToRegenerate,
			rateLimit);
		return update.isValid() ? Optional.of(update) : Optional.empty();
	}

	/**
	 * Applies only the fields selected by the bitmask.  Untouched fields come
	 * from the authoritative snapshot, so concurrent edits to another field are
	 * preserved.
	 */
	public ServerConfigSnapshot applyTo(ServerConfigSnapshot current) {
		if (!isValid() || current == null) {
			return current;
		}

		return new ServerConfigSnapshot(
			current.canEdit(),
			(changedFields & DEFAULT_CHANNEL_MODE) != 0 ? defaultChannelMode : current.defaultChannelMode(),
			(changedFields & PLAYER_TRACKING_ENABLED) != 0 ? playerTrackingEnabled : current.playerTrackingEnabled(),
			(changedFields & MS_TO_REGENERATE) != 0 ? msToRegenerate : current.msToRegenerate(),
			(changedFields & RATE_LIMIT) != 0 ? rateLimit : current.rateLimit());
	}
}
