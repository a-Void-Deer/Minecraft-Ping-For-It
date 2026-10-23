package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;

/**
 * A tiny, injectable debug logger for entity outline state transitions.
 *
 * <p>The state only ever emits aggregate counts of the snapshot diff (added /
 * removed / changed / total), so UUIDs, dimension ids, colors, positions, and
 * names are structurally impossible to log. Tests use {@link #noop()} or an
 * in-memory recording implementation, so the game logger ({@link Diagnostics}) is
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
	 * <p>The reference to {@link Diagnostics} is deferred into the returned
	 * lambda body, so calling this factory does not initialize the backend;
	 * only the first {@code transition(...)} invocation does.
	 */
	static EntityOutlineLogger global() {
		return (added, removed, changed, total) -> Diagnostics.domainApprovedEvent(Level.DEBUG,
			"entity-outline-state", "aggregate", "snapshot-transition",
			() -> "entity outline state transition: added=%d removed=%d changed=%d total=%d"
				.formatted(added, removed, changed, total));
	}
}
