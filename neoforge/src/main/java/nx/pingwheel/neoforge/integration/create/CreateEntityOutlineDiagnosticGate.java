package nx.pingwheel.neoforge.integration.create;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/** The entity adapter's existing bounded debounce/heartbeat gate, independent of Create classes. */
@NotNullByDefault
final class CreateEntityOutlineDiagnosticGate {

	static final int MAX_STATES = 256;
	static final long MIN_INTERVAL_NANOS = 1_000_000_000L;
	static final long HEARTBEAT_NANOS = 5_000_000_000L;
	private final LongSupplier clock;
	private final LinkedHashMap<String, State> states = new LinkedHashMap<>(16, 0.75F, true);

	CreateEntityOutlineDiagnosticGate(LongSupplier clock) {
		this.clock = clock;
	}

	synchronized boolean admit(String key, String reason, String outcome, boolean enabled, boolean debug) {
		long now = clock.getAsLong();
		@Nullable State previous = states.get(key);
		boolean log;
		if (previous == null || !previous.emitted) {
			log = true;
		} else {
			boolean currentChanged = !previous.reason.equals(reason) || !previous.outcome.equals(outcome);
			boolean pending = !previous.loggedReason.equals(reason) || !previous.loggedOutcome.equals(outcome);
			log = pending
				? now - previous.lastLoggedNanos >= MIN_INTERVAL_NANOS
					&& now - (currentChanged ? now : previous.observedSinceNanos) >= MIN_INTERVAL_NANOS
				: now - previous.lastLoggedNanos >= HEARTBEAT_NANOS;
		}
		log &= enabled;
		if (previous == null && states.size() >= MAX_STATES) {
			states.remove(states.keySet().iterator().next());
		}
		states.put(key, previous == null
			? new State(reason, outcome, log ? reason : "", log ? outcome : "", now, now, log)
			: new State(reason, outcome,
				log ? reason : !enabled && debug ? "" : previous.loggedReason,
				log ? outcome : !enabled && debug ? "" : previous.loggedOutcome,
				log ? now : previous.lastLoggedNanos,
				previous.reason.equals(reason) && previous.outcome.equals(outcome) ? previous.observedSinceNanos : now,
				previous.emitted || log));
		return log;
	}

	synchronized void clear() {
		states.clear();
	}

	synchronized int trackedCount() {
		return states.size();
	}

	@NotNullByDefault
	private record State(String reason, String outcome, String loggedReason, String loggedOutcome,
		long lastLoggedNanos, long observedSinceNanos, boolean emitted) {}
}
