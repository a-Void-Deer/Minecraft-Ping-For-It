package nx.pingwheel.common.diagnostics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Explicit, caller-owned admission. Retain a channel in a static final field.
 * Object identity is the channel identity; its name is only a controlled label.
 * Each side has independent state, shared across severity and profile changes.
 * No throwable, deferred context or domain object is retained by interval state.
 */
@NotNullByDefault
public final class DiagnosticChannel {
	private final String name;
	private final long intervalNanos;
	private final LongSupplier clock;
	private final @Nullable BooleanSupplier domainGate;
	private final State client = new State();
	private final State server = new State();
	private final State bootstrap = new State();

	private DiagnosticChannel(String name, long intervalNanos, LongSupplier clock,
		@Nullable BooleanSupplier domainGate) {
		this.name = DiagnosticText.controlled(name);
		this.intervalNanos = intervalNanos;
		this.clock = Objects.requireNonNull(clock, "clock");
		this.domainGate = domainGate;
	}

	public static DiagnosticChannel interval(String name, Duration interval) {
		return interval(name, interval, System::nanoTime);
	}

	/** Clock injection for deterministic admission tests; no implicit default interval. */
	public static DiagnosticChannel interval(String name, Duration interval, LongSupplier clock) {
		long nanos = interval.toNanos();
		if (nanos <= 0) {
			throw new IllegalArgumentException("Diagnostic interval must be positive");
		}
		return new DiagnosticChannel(name, nanos, clock, null);
	}

	/**
	 * Runs a domain-owned gate once after the same terminal's policy/backend check.
	 * There is no additional interval, clock or capacity gate. Do not capture a
	 * world/provider in a long-lived channel; domain lifecycle remains caller-owned.
	 */
	public static DiagnosticChannel domain(String name, BooleanSupplier admission) {
		return new DiagnosticChannel(name, 0, System::nanoTime, Objects.requireNonNull(admission, "admission"));
	}

	String name() {
		return name;
	}

	Admission admit(DiagnosticSide side) {
		if (domainGate != null) {
			return domainGate.getAsBoolean() ? new Admission(true, 0) : new Admission(false, 0);
		}
		State state = switch (side) {
			case CLIENT -> client;
			case SERVER -> server;
			case BOOTSTRAP -> bootstrap;
		};
		synchronized (state) {
			long now;
			try {
				now = clock.getAsLong();
			} catch (RuntimeException | LinkageError failure) {
				throw new ClockFailure(failure);
			}
			if (state.reported && now - state.lastReport < intervalNanos) {
				state.suppressed = DiagnosticFaultReporter.increment(state.suppressed);
				return new Admission(false, 0);
			}
			long suppressed = state.suppressed;
			state.suppressed = 0;
			state.lastReport = now;
			state.reported = true;
			return new Admission(true, suppressed);
		}
	}

	@NotNullByDefault
	record Admission(boolean allowed, long suppressed) {}

	@NotNullByDefault
	static final class ClockFailure extends RuntimeException {
		private final Throwable original;

		private ClockFailure(Throwable original) {
			super(null, null, false, false);
			this.original = original;
		}

		Throwable original() {
			return original;
		}
	}

	@NotNullByDefault
	private static final class State {
		private boolean reported;
		private long lastReport;
		private long suppressed;
	}
}
