package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.Global;

/**
 * A tiny, injectable debug logger for entity outline state transitions.
 *
 * <p>The state only ever emits aggregate counts of the snapshot diff (added /
 * removed / changed / total), so UUIDs, dimension ids, colors, positions, and
 * names are structurally impossible to log. Tests use {@link #noop()} or an
 * in-memory recording implementation, so the game logger ({@link Global}) is
 * never initialized in tests unless {@link #global()} is explicitly
 * requested.
 */
@FunctionalInterface
public interface EntityOutlineLogger {

	/**
	 * Emits one transition record with the snapshot diff counts.
	 */
	void transition(int added, int removed, int changed, int total);

	/**
	 * A logger that discards every record.
	 */
	static EntityOutlineLogger noop() {
		return (added, removed, changed, total) -> {
			// intentionally empty
		};
	}

	/**
	 * A logger backed by the mod's global Log4j logger.
	 *
	 * <p>The reference to {@link Global#LOGGER} is deferred into the returned
	 * lambda body, so calling this factory does not initialize {@link Global};
	 * only the first {@code transition(...)} invocation does.
	 */
	static EntityOutlineLogger global() {
		return (added, removed, changed, total) -> Global.LOGGER.debug(
			"entity outline state transition: added={} removed={} changed={} total={}",
			added, removed, changed, total);
	}
}
