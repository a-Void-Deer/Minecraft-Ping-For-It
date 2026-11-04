package nx.pingwheel.common.screen;

import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.EnumSet;

/**
 * Root-session state for the two independent logging-profile rows. The unlock
 * flags deliberately live here rather than in a widget or the server draft:
 * they survive page rebuilds, navigation, and resizing, and an unlock never
 * changes a value, marks the server draft dirty, or emits a packet.
 */
@NotNullByDefault
public final class LoggingProfileSession {
	@NotNullByDefault
	public enum Side {
		CLIENT,
		SERVER
	}

	private final EnumSet<Side> unlocked = EnumSet.noneOf(Side.class);

	public boolean isUnlocked(Side side) {
		return unlocked.contains(side);
	}

	/** Records the reveal for this row only; repeating it changes nothing. */
	public boolean unlock(Side side) {
		return unlocked.add(side);
	}

	/** The next ordinary activation; Debug remains reachable only after this row was unlocked. */
	public LoggingProfile next(LoggingProfile current, Side side) {
		return next(current, isUnlocked(side));
	}

	public static LoggingProfile next(LoggingProfile current, boolean unlocked) {
		if (unlocked) {
			return switch (current) {
				case NORMAL -> LoggingProfile.DEEP;
				case DEEP -> LoggingProfile.DEBUG;
				case DEBUG -> LoggingProfile.NORMAL;
			};
		}
		return current == LoggingProfile.NORMAL ? LoggingProfile.DEEP : LoggingProfile.NORMAL;
	}

	/** Clears the root session on a completed root leave or a confirmed reset. */
	public void clear() {
		unlocked.clear();
	}
}
