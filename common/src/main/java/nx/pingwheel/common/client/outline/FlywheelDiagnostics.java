package nx.pingwheel.common.client.outline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.Predicate;

import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;

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
	private final Predicate<Level> enabled;
	private final Map<Object, State> states = new LinkedHashMap<>();

	/** Clock seam for deterministic transition/heartbeat tests. */
	@FunctionalInterface
	public interface Clock {
		long nowMillis();

		static Clock system() {
			return () -> System.nanoTime() / 1_000_000L;
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
		@Nullable String target,
		FlywheelDiagnosticReason reason,
		EventKind kind,
		long timestampMillis,
		@Nullable String details,
		@Nullable Throwable failure
	) {
		public Event {
			target = target == null ? "<unknown-target>" : target;
			reason = Objects.requireNonNull(reason, "reason");
			kind = Objects.requireNonNull(kind, "kind");
			details = details == null ? "" : details;
		}
	}

	private record State(FlywheelDiagnosticReason reason, long lastEmissionMillis, boolean admitted) {}

	public FlywheelDiagnostics(Clock clock, EventSink eventSink) {
		this(clock, eventSink, level -> true);
	}

	public FlywheelDiagnostics(Clock clock, EventSink eventSink, Predicate<Level> enabled) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
		this.enabled = Objects.requireNonNull(enabled, "enabled");
	}

	/** Creates the production logger without initializing it until an event is emitted. */
	public static FlywheelDiagnostics global() {
		return new FlywheelDiagnostics(Clock.system(), FlywheelDiagnostics::logGlobally,
			level -> Diagnostics.logger().isEnabled(level));
	}

	/**
	 * Reports the current reason. Returns {@code true} when the event sink was
	 * invoked, which is useful for focused tests and does not affect rendering.
	 */
	public synchronized boolean report(
		@Nullable String target,
		FlywheelDiagnosticReason reason,
		@Nullable String details,
		@Nullable Throwable failure
	) {
		return report(target, reason, () -> target, () -> details, failure);
	}

	/**
	 * Compatibility overload for callers that already have a cheap target key.
	 * The detail supplier is not evaluated unless this report is emitted.
	 */
	public synchronized boolean report(
		@Nullable String target,
		FlywheelDiagnosticReason reason,
		@Nullable Supplier<@Nullable String> details,
		@Nullable Throwable failure
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
		@Nullable Object targetKey,
		FlywheelDiagnosticReason reason,
		Supplier<@Nullable String> target,
		@Nullable Supplier<@Nullable String> details,
		@Nullable Throwable failure
	) {
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(target, "target");
		Object key = targetKey == null ? UNKNOWN_TARGET_KEY : targetKey;
		long now = clock.nowMillis();
		@Nullable State previous = states.get(key);
		EventKind kind;

		if (previous == null) {
			kind = EventKind.FIRST;
		} else if (previous.reason() != reason) {
			kind = reason == FlywheelDiagnosticReason.RENDERED
				&& previous.reason() != FlywheelDiagnosticReason.RENDERED
				? EventKind.RECOVERY : EventKind.REASON_TRANSITION;
		} else if (!previous.admitted()) {
			kind = EventKind.FIRST;
		} else if (now - previous.lastEmissionMillis() >= HEARTBEAT_MILLIS) {
			kind = EventKind.HEARTBEAT;
		} else {
			return false;
		}

		boolean emit;
		try {
			emit = enabled.test(reason == FlywheelDiagnosticReason.RENDERED && failure == null ? Level.DEBUG : Level.WARN);
		} catch (RuntimeException | LinkageError ignored) {
			emit = false;
		}
		states.put(key, new State(reason, emit ? now : previous == null ? now : previous.lastEmissionMillis(), emit));
		evictIfNecessary();
		if (!emit) {
			return false;
		}
		try {
			Event event = new Event(target.get(), reason, kind, now,
				details == null ? "" : details.get(), failure);
			eventSink.accept(event);
		} catch (RuntimeException | LinkageError ignored) {
			// An admitted sink failure stays consumed and never recurses.
		}
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
		Supplier<String> message = () -> "create/flywheel silhouette diagnostic: kind=" + event.kind()
			+ "; reason=" + event.reason().diagnosticId()
			+ "; target=" + event.target()
			+ "; details=" + event.details();

		Level level = event.reason() == FlywheelDiagnosticReason.RENDERED && event.failure() == null ? Level.DEBUG : Level.WARN;
		if (event.failure() == null) {
			Diagnostics.domainApprovedEvent(level, "create-flywheel", event.reason().diagnosticId(), event.kind().name(), message);
		} else {
			Diagnostics.domainApprovedFailure(level, "create-flywheel", event.reason().diagnosticId(), message, event.failure());
		}
	}
}
