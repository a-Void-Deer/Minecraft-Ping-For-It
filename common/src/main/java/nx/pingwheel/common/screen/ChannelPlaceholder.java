package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.ChannelMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Selects the empty-channel hint from the received authoritative channel mode
 * and the player's current team context. An absent snapshot, a lost connection,
 * or a mode whose empty-channel audience is not exactly Global or the player's
 * team produces no hint instead of guessing.
 */
@NotNullByDefault
final class ChannelPlaceholder {
	@NotNullByDefault
	enum Kind {
		NONE,
		GLOBAL,
		TEAM_SYNCED
	}

	private ChannelPlaceholder() {
	}

	static Kind resolve(@Nullable ChannelMode authoritativeMode, boolean connected, boolean hasTeam) {
		if (!connected) {
			return Kind.NONE;
		}
		if (authoritativeMode == ChannelMode.GLOBAL) {
			return Kind.GLOBAL;
		}
		if (authoritativeMode == ChannelMode.AUTO && hasTeam) {
			return Kind.TEAM_SYNCED;
		}

		return Kind.NONE;
	}
}
