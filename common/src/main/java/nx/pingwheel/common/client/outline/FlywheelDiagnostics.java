package nx.pingwheel.common.client.outline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.Predicate;

import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.DiagnosticFaultReporter.Stage;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
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
 * context lazily; production disclosure remains owned by the shared profile.</p>
 */
@NotNullByDefault
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
	@NotNullByDefault
	public static final class Event {
		private final Supplier<@Nullable String> target;
		private final FlywheelDiagnosticReason reason;
		private final EventKind kind;
		private final long timestampMillis;
		private final Supplier<@Nullable String> details;
		private final @Nullable Throwable failure;
		private final LoggingProfile profile;
		private @Nullable String targetText;
		private @Nullable String detailText;

		private Event(Supplier<@Nullable String> target, FlywheelDiagnosticReason reason, EventKind kind,
			long timestampMillis, @Nullable Supplier<@Nullable String> details, @Nullable Throwable failure,
			LoggingProfile profile) {
			this.target = target;
			this.reason = reason;
			this.kind = kind;
			this.timestampMillis = timestampMillis;
			this.details = details == null ? () -> "" : details;
			this.failure = failure;
			this.profile = profile;
		}

		public String target() {
			if (targetText == null) targetText = collect(target, "<unknown-target>");
			return targetText;
		}
		public String details() {
			if (detailText == null) detailText = collect(details, "");
			return detailText;
		}
		private String collect(Supplier<@Nullable String> supplier, String absent) {
			try { return Objects.requireNonNullElse(supplier.get(), absent); }
			catch (RuntimeException | LinkageError failure) {
				Diagnostics.faults().report(DiagnosticSide.CLIENT, "create-flywheel", Stage.CONTEXT, profile, failure);
				return "[context unavailable]";
			}
		}
		public FlywheelDiagnosticReason reason() { return reason; }
		public EventKind kind() { return kind; }
		public long timestampMillis() { return timestampMillis; }
		public @Nullable Throwable failure() { return failure; }
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
		return new FlywheelDiagnostics(Clock.system(), event -> logGlobally(Diagnostics.client(), event),
			level -> selection(Diagnostics.client(), level).isEnabled(level));
	}

	/**
	 * Reports the current reason. Returns {@code true} when the event sink was
	 * invoked, which is useful for focused tests and does not affect rendering.
	 */
	public boolean report(
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
	public boolean report(
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
	public boolean report(
		@Nullable Object targetKey,
		FlywheelDiagnosticReason reason,
		Supplier<@Nullable String> target,
		@Nullable Supplier<@Nullable String> details,
		@Nullable Throwable failure
	) {
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(target, "target");
		Object key = targetKey == null ? UNKNOWN_TARGET_KEY : targetKey;
		LoggingProfile profile = Diagnostics.profile(DiagnosticSide.CLIENT);
		long now;
		@Nullable EventKind kind;
		boolean emit;
		try {
			now = clock.nowMillis();
		} catch (RuntimeException | LinkageError failureAtClock) {
			Diagnostics.faults().report(DiagnosticSide.CLIENT, "create-flywheel", Stage.CHANNEL_CLOCK, profile, failureAtClock);
			return false;
		}
		try {
			emit = enabled.test(severity(reason, failure));
		} catch (RuntimeException | LinkageError failureAtEnabled) {
			Diagnostics.faults().report(DiagnosticSide.CLIENT, "create-flywheel", Stage.ENABLED, profile, failureAtEnabled);
			emit = false;
		}
		synchronized (this) {
			@Nullable State previous = states.get(key);
			kind = null;
			if (previous == null) kind = EventKind.FIRST;
			else if (previous.reason() != reason) kind = reason == FlywheelDiagnosticReason.RENDERED
				&& previous.reason() != FlywheelDiagnosticReason.RENDERED ? EventKind.RECOVERY : EventKind.REASON_TRANSITION;
			else if (!previous.admitted()) kind = EventKind.FIRST;
			else if (now - previous.lastEmissionMillis() >= HEARTBEAT_MILLIS) kind = EventKind.HEARTBEAT;
			if (kind == null) return false;
			states.put(key, new State(reason, emit ? now : previous == null ? now : previous.lastEmissionMillis(), emit));
			evictIfNecessary();
		}
		if (!emit) {
			return false;
		}
		try {
			Event event = new Event(target, reason, kind, now, details, failure, profile);
			eventSink.accept(event);
		} catch (RuntimeException | LinkageError sinkFailure) {
			// An admitted sink failure stays consumed and never recurses.
			Diagnostics.faults().report(DiagnosticSide.CLIENT, "create-flywheel", Stage.SINK, profile, sinkFailure);
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

	private static void logGlobally(DiagnosticLogger diagnostics, Event event) {
		Supplier<String> message = () -> "create/flywheel silhouette diagnostic: kind=" + event.kind()
			+ "; reason=" + event.reason().diagnosticId()
			+ "; target=" + event.target()
			+ "; details=" + event.details();

		Level level = severity(event.reason(), event.failure());
		if (event.failure() == null) {
			selection(diagnostics, level).event(level, "create-flywheel", "render-outcome", message);
		} else {
			selection(diagnostics, level).failure(level, "create-flywheel", "render-failure", message, event.failure());
		}
	}

	/** The terminal and domain admission must use the same severity/profile classification. */
	public static Level severity(FlywheelDiagnosticReason reason, @Nullable Throwable failure) {
		if (reason == FlywheelDiagnosticReason.MASK_EMISSION_FAILED
			|| reason == FlywheelDiagnosticReason.MASK_PARTIAL_EMISSION) return Level.ERROR;
		if (failure != null) return Level.WARN;
		return switch (reason) {
			case RENDERED, ADAPTER_NOT_REGISTERED, MODE_OR_POLICY, CONTEXT_UNAVAILABLE,
				MANAGER_NULL, VISUAL_NULL, NO_INSTANCES, UNSUPPORTED_TYPE -> Level.DEBUG;
			default -> Level.WARN;
		};
	}

	private static DiagnosticLogger selection(DiagnosticLogger logger, Level level) {
		return level == Level.DEBUG ? logger.atDebug() : logger.atNormal();
	}
}
