package nx.pingwheel.common.client.outline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import nx.pingwheel.common.Global;

/**
 * Rate-controlled diagnostic transition state for the optional Flywheel
 * adapter.
 *
 * <p>One event is emitted for a target's first reason, every reason transition,
 * recovery to {@link FlywheelDiagnosticReason#RENDERED}, and a persistent
 * reason heartbeat. The event contains the caller's complete diagnostic
 * details and throwable; this class intentionally does not redact or rewrite
 * either value. The adapter supplies the detailed target/component/material
 * context, while this class only controls event frequency.</p>
 */
public final class FlywheelDiagnostics {
	public static final long HEARTBEAT_MILLIS = 5_000L;
	public static final int MAX_TRACKED_TARGETS = 512;
	private static final Object UNKNOWN_TARGET_KEY = new Object();

	private final Clock clock;
	private final EventSink eventSink;
	private final Map<Object, State> states = new LinkedHashMap<>();

	/** Clock seam for deterministic transition/heartbeat tests. */
	@FunctionalInterface
	public interface Clock {
		long nowMillis();

		static Clock system() {
			return System::currentTimeMillis;
		}
	}

	/** Injectable event sink used by production logging and focused tests. */
	@FunctionalInterface
	public interface EventSink {
		void accept(Event event);
	}

	public enum EventKind {
		FIRST,
		REASON_TRANSITION,
		HEARTBEAT,
		RECOVERY
	}

	/** One emitted diagnostic record, including the original throwable. */
	public record Event(
		String target,
		FlywheelDiagnosticReason reason,
		EventKind kind,
		long timestampMillis,
		String details,
		Throwable failure
	) {
		public Event {
			target = target == null ? "<unknown-target>" : target;
			reason = Objects.requireNonNull(reason, "reason");
			kind = Objects.requireNonNull(kind, "kind");
			details = details == null ? "" : details;
		}
	}

	private record State(FlywheelDiagnosticReason reason, long lastEmissionMillis) {}

	public FlywheelDiagnostics(Clock clock, EventSink eventSink) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
	}

	/** Creates the production logger without initializing it until an event is emitted. */
	public static FlywheelDiagnostics global() {
		return new FlywheelDiagnostics(Clock.system(), FlywheelDiagnostics::logGlobally);
	}

	/**
	 * Reports the current reason. Returns {@code true} when the event sink was
	 * invoked, which is useful for focused tests and does not affect rendering.
	 */
	public synchronized boolean report(
		String target,
		FlywheelDiagnosticReason reason,
		String details,
		Throwable failure
	) {
		return report(target, reason, () -> target, () -> details, failure);
	}

	/**
	 * Compatibility overload for callers that already have a cheap target key.
	 * The detail supplier is not evaluated unless this report is emitted.
	 */
	public synchronized boolean report(
		String target,
		FlywheelDiagnosticReason reason,
		Supplier<String> details,
		Throwable failure
	) {
		return report(target, reason, () -> target, details, failure);
	}

	/**
	 * Reports a diagnostic using a cheap identity key and lazy complete details.
	 *
	 * <p>The state transition is decided before either supplier is evaluated.
	 * This keeps repeated render attempts from walking live instances, models,
	 * materials, or exception text when the event is suppressed.</p>
	 */
	public synchronized boolean report(
		Object targetKey,
		FlywheelDiagnosticReason reason,
		Supplier<String> target,
		Supplier<String> details,
		Throwable failure
	) {
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(target, "target");
		Object key = targetKey == null ? UNKNOWN_TARGET_KEY : targetKey;
		long now = clock.nowMillis();
		State previous = states.get(key);
		EventKind kind;

		if (previous == null) {
			kind = EventKind.FIRST;
		} else if (previous.reason() != reason) {
			kind = reason == FlywheelDiagnosticReason.RENDERED
				&& previous.reason() != FlywheelDiagnosticReason.RENDERED
				? EventKind.RECOVERY : EventKind.REASON_TRANSITION;
		} else if (now - previous.lastEmissionMillis() >= HEARTBEAT_MILLIS) {
			kind = EventKind.HEARTBEAT;
		} else {
			return false;
		}

		Event event = new Event(target.get(), reason, kind, now,
			details == null ? "" : details.get(), failure);
		states.put(key, new State(reason, now));
		evictIfNecessary();
		eventSink.accept(event);
		return true;
	}

	private void evictIfNecessary() {
		while (states.size() > MAX_TRACKED_TARGETS) {
			states.remove(states.keySet().iterator().next());
		}
	}

	/** Clears all rate-control state, normally when the optional adapter closes. */
	public synchronized void clear() {
		states.clear();
	}

	/** Number of target states retained; exposed for headless tests only. */
	public synchronized int trackedTargetCount() {
		return states.size();
	}

	private static void logGlobally(Event event) {
		String message = "create/flywheel silhouette diagnostic: kind=" + event.kind()
			+ "; reason=" + event.reason().diagnosticId()
			+ "; target=" + event.target()
			+ "; details=" + event.details();

		if (event.reason() == FlywheelDiagnosticReason.RENDERED) {
			if (event.failure() == null) {
				Global.LOGGER.debug(message);
			} else {
				Global.LOGGER.debug(message, event.failure());
			}
		} else if (event.failure() == null) {
			Global.LOGGER.warn(message);
		} else {
			// Attach the original throwable rather than converting it to a
			// privacy-filtered summary: diagnostics are explicitly opt-in and
			// heartbeat rate-controlled at the source.
			Global.LOGGER.warn(message, event.failure());
		}
	}
}
