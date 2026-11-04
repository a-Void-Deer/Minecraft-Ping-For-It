package nx.pingwheel.common.diagnostics;

import org.jetbrains.annotations.NotNullByDefault;

/** Local disclosure policy, independent of a report's backend severity. */
@NotNullByDefault
public enum LoggingProfile {
	NORMAL, DEEP, DEBUG;

	public boolean includes(LoggingProfile minimum) {
		return ordinal() >= minimum.ordinal();
	}
}
