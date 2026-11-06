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

/** Domain-owned first/transition/recovery/heartbeat admission for the optional Flywheel source. */
@NotNullByDefault
public final class FlywheelDiagnostics {
	public static final long HEARTBEAT_MILLIS = 5_000L;
	public static final int MAX_TRACKED_TARGETS = 512;
	private static final Object UNKNOWN_TARGET_KEY = new Object();
	private final Clock clock;
	private final EventSink eventSink;
	private final Predicate<Level> enabled;
	private final Map<Object, State> states = new LinkedHashMap<>();

	@FunctionalInterface
	public interface Clock {
		long nowMillis();
		static Clock system() { return () -> System.nanoTime() / 1_000_000L; }
	}

	@FunctionalInterface
	public interface EventSink { void accept(Event event); }

	public enum EventKind { FIRST, REASON_TRANSITION, HEARTBEAT, RECOVERY }

	/** Finite domain vocabulary, not exception classes or provider-controlled identity. */
	public enum FailureCategory { NONE, AVAILABILITY, INSTANCE_RESOLUTION, RENDER, PARTIAL_EMISSION }

	/** Suppliers are synchronous, attempt-local, and collected at most once. */
	@NotNullByDefault
	public static final class Event {
		private final Supplier<@Nullable String> target;
		private final FlywheelDiagnosticReason reason;
		private final EntityBlockGeometryOutcome outcome;
		private final FailureCategory category;
		private final EventKind kind;
		private final long timestampMillis;
		private final Supplier<@Nullable String> details;
		private final @Nullable Throwable failure;
		private final LoggingProfile profile;
		private @Nullable String targetText;
		private @Nullable String detailText;

		private Event(Supplier<@Nullable String> target, FlywheelDiagnosticReason reason,
			EntityBlockGeometryOutcome outcome, FailureCategory category, EventKind kind, long timestampMillis,
			@Nullable Supplier<@Nullable String> details, @Nullable Throwable failure, LoggingProfile profile) {
			this.target = target;
			this.reason = reason;
			this.outcome = outcome;
			this.category = category;
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
		public EntityBlockGeometryOutcome outcome() { return outcome; }
		public FailureCategory category() { return category; }
		public EventKind kind() { return kind; }
		public long timestampMillis() { return timestampMillis; }
		public @Nullable Throwable failure() { return failure; }
	}

	private record State(FlywheelDiagnosticReason reason, EntityBlockGeometryOutcome outcome,
		FailureCategory category, long lastEmissionMillis, boolean admitted) {
		boolean matches(FlywheelDiagnosticReason nextReason, EntityBlockGeometryOutcome nextOutcome, FailureCategory nextCategory) {
			return reason == nextReason && outcome == nextOutcome && category == nextCategory;
		}
	}

	public FlywheelDiagnostics(Clock clock, EventSink eventSink) { this(clock, eventSink, level -> true); }

	public FlywheelDiagnostics(Clock clock, EventSink eventSink, Predicate<Level> enabled) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
		this.enabled = Objects.requireNonNull(enabled, "enabled");
	}

	public static FlywheelDiagnostics global() {
		return new FlywheelDiagnostics(Clock.system(), event -> logGlobally(Diagnostics.client(), event),
			level -> selection(Diagnostics.client(), level).isEnabled(level));
	}

	public boolean report(@Nullable String target, FlywheelDiagnosticReason reason,
		@Nullable String details, @Nullable Throwable failure) {
		return report(target, reason, () -> target, () -> details, failure);
	}

	public boolean report(@Nullable String target, FlywheelDiagnosticReason reason,
		@Nullable Supplier<@Nullable String> details, @Nullable Throwable failure) {
		return report(target, reason, () -> target, details, failure);
	}

	/** Compatibility entry; production callers supply their actual business outcome. */
	public boolean report(@Nullable Object targetKey, FlywheelDiagnosticReason reason,
		Supplier<@Nullable String> target, @Nullable Supplier<@Nullable String> details, @Nullable Throwable failure) {
		return report(targetKey, reason, inferredOutcome(reason, failure), target, details, failure);
	}

	public boolean report(@Nullable Object targetKey, FlywheelDiagnosticReason reason, EntityBlockGeometryOutcome outcome,
		Supplier<@Nullable String> target, @Nullable Supplier<@Nullable String> details, @Nullable Throwable failure) {
		return report(targetKey, reason, outcome, category(reason, outcome, failure), target, details, failure);
	}

	/** Explicit classification for a preflight failure sharing a render-stage reason code. */
	public boolean report(@Nullable Object targetKey, FlywheelDiagnosticReason reason, EntityBlockGeometryOutcome outcome,
		FailureCategory category, Supplier<@Nullable String> target,
		@Nullable Supplier<@Nullable String> details, @Nullable Throwable failure) {
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(outcome, "outcome");
		Objects.requireNonNull(target, "target");
		Object key = targetKey == null ? UNKNOWN_TARGET_KEY : targetKey;
		LoggingProfile profile = Diagnostics.profile(DiagnosticSide.CLIENT);
		Objects.requireNonNull(category, "category");
		boolean emit;
		try { emit = enabled.test(severity(reason, category)); }
		catch (RuntimeException | LinkageError failureAtEnabled) {
			Diagnostics.faults().report(DiagnosticSide.CLIENT, "create-flywheel", Stage.ENABLED, profile, failureAtEnabled);
			emit = false;
		}
		if (!emit) {
			// Recovery remains observed below DEBUG. Repeated disabled observations
			// need neither a clock read nor a replacement state allocation.
			synchronized (this) {
				@Nullable State previous = states.get(key);
				if (previous == null || !previous.matches(reason, outcome, category)) {
					states.put(key, new State(reason, outcome, category,
						previous == null ? 0L : previous.lastEmissionMillis(), false));
					evictIfNecessary();
				}
			}
			return false;
		}
		long now;
		try { now = clock.nowMillis(); }
		catch (RuntimeException | LinkageError failureAtClock) {
			Diagnostics.faults().report(DiagnosticSide.CLIENT, "create-flywheel", Stage.CHANNEL_CLOCK, profile, failureAtClock);
			return false;
		}
		@Nullable EventKind kind;
		synchronized (this) {
			@Nullable State previous = states.get(key);
			kind = null;
			if (previous == null) kind = EventKind.FIRST;
			else if (!previous.matches(reason, outcome, category)) kind = reason == FlywheelDiagnosticReason.RENDERED
				&& previous.reason() != FlywheelDiagnosticReason.RENDERED ? EventKind.RECOVERY : EventKind.REASON_TRANSITION;
			else if (!previous.admitted()) kind = EventKind.FIRST;
			else if (now - previous.lastEmissionMillis() >= HEARTBEAT_MILLIS) kind = EventKind.HEARTBEAT;
			if (kind == null) return false;
			states.put(key, new State(reason, outcome, category, now, true));
			evictIfNecessary();
		}
		try { eventSink.accept(new Event(target, reason, outcome, category, kind, now, details, failure, profile)); }
		catch (RuntimeException | LinkageError sinkFailure) {
			Diagnostics.faults().report(DiagnosticSide.CLIENT, "create-flywheel", Stage.SINK, profile, sinkFailure);
		}
		return true;
	}

	private void evictIfNecessary() {
		while (states.size() > MAX_TRACKED_TARGETS) states.remove(states.keySet().iterator().next());
	}
	public synchronized void clear() { states.clear(); }
	public synchronized int trackedTargetCount() { return states.size(); }

	private static void logGlobally(DiagnosticLogger diagnostics, Event event) {
		Supplier<String> message = () -> "create/flywheel silhouette diagnostic: kind=" + event.kind()
			+ "; reason=" + event.reason().diagnosticId() + "; outcome=" + event.outcome() + "; category=" + event.category()
			+ "; target=" + event.target() + "; details=" + event.details();
		Level level = severity(event.reason(), event.category());
		String site = "create-flywheel/" + event.reason().diagnosticId();
		String handling = event.outcome() == EntityBlockGeometryOutcome.RENDERED
			? event.category() == FailureCategory.NONE ? "rendered" : "rendered-degraded-fallback-suppressed"
			: event.category() == FailureCategory.RENDER ? "source-render-failed-fallback-eligible" : "next-source-fallback-eligible";
		if (event.failure() == null) selection(diagnostics, level).event(level, site, handling, message);
		else selection(diagnostics, level).failure(level, site, handling, message, event.failure());
	}

	public static Level severity(FlywheelDiagnosticReason reason, @Nullable Throwable failure) {
		EntityBlockGeometryOutcome outcome = inferredOutcome(reason, failure);
		return severity(reason, category(reason, outcome, failure));
	}

	public static FailureCategory category(FlywheelDiagnosticReason reason, EntityBlockGeometryOutcome outcome,
		@Nullable Throwable failure) {
		if (reason == FlywheelDiagnosticReason.MASK_PARTIAL_EMISSION) return FailureCategory.PARTIAL_EMISSION;
		if (reason == FlywheelDiagnosticReason.MASK_EMISSION_FAILED
			|| outcome == EntityBlockGeometryOutcome.FAILED && (reason == FlywheelDiagnosticReason.MESH_EXTRACTION_FAILED
				|| reason == FlywheelDiagnosticReason.MATERIAL_INCOMPATIBLE)) return FailureCategory.RENDER;
		if (failure == null) return FailureCategory.NONE;
		return reason == FlywheelDiagnosticReason.NO_INSTANCES || reason == FlywheelDiagnosticReason.INSTANCE_RESOLUTION_FAILED
			|| outcome == EntityBlockGeometryOutcome.RENDERED ? FailureCategory.INSTANCE_RESOLUTION : FailureCategory.AVAILABILITY;
	}

	private static Level severity(FlywheelDiagnosticReason reason, FailureCategory category) {
		if (category == FailureCategory.RENDER || category == FailureCategory.PARTIAL_EMISSION) return Level.ERROR;
		if (category != FailureCategory.NONE) return Level.WARN;
		return switch (reason) {
			case RENDERED, ADAPTER_NOT_REGISTERED, MODE_OR_POLICY, CONTEXT_UNAVAILABLE,
				MANAGER_NULL, VISUAL_NULL, NO_INSTANCES, UNSUPPORTED_TYPE -> Level.DEBUG;
			default -> Level.WARN;
		};
	}

	private static EntityBlockGeometryOutcome inferredOutcome(FlywheelDiagnosticReason reason, @Nullable Throwable failure) {
		if (reason == FlywheelDiagnosticReason.RENDERED || reason == FlywheelDiagnosticReason.MASK_PARTIAL_EMISSION)
			return EntityBlockGeometryOutcome.RENDERED;
		return failure == null ? EntityBlockGeometryOutcome.EMPTY : EntityBlockGeometryOutcome.FAILED;
	}

	private static DiagnosticLogger selection(DiagnosticLogger logger, Level level) {
		return level == Level.DEBUG ? logger.atDebug() : logger.atNormal();
	}
}
