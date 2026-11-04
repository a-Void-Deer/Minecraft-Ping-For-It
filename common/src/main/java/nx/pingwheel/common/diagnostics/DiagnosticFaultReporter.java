package nx.pingwheel.common.diagnostics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Independent, monotonic self-fault observations. Never retains a throwable. */
@NotNullByDefault
public final class DiagnosticFaultReporter {
	@NotNullByDefault
	public enum Stage { SETUP, ENABLED, CHANNEL_CLOCK, ADMISSION, CONTEXT, ACCESSOR, FORMAT, SINK, REENTRY }
	@NotNullByDefault
	public enum Kind { RUNTIME, LINKAGE, REENTRY }

	private final ConcurrentHashMap<Key, State> states = new ConcurrentHashMap<>();
	private final ThreadLocal<Boolean> reporting = ThreadLocal.withInitial(() -> false);
	private final AtomicLong recursive = new AtomicLong();
	private final AtomicLong formattingFailures = new AtomicLong();
	private final AtomicBoolean formatFallbackAttempted = new AtomicBoolean();
	private final DiagnosticEmergencyWriter writer;

	DiagnosticFaultReporter(DiagnosticEmergencyWriter writer) {
		this.writer = writer;
	}

	/**
	 * Records one self-fault from an owning subsystem or loader diagnostic gate.
	 * The site is a stable controlled code, not dynamic provider/request text.
	 * NORMAL retains only side/site/stage/kind; higher profiles may add the
	 * failure class and bounded message. Reentrancy and state are thread-safe,
	 * and no throwable is retained.
	 */
	public void report(DiagnosticSide side, String site, Stage stage, LoggingProfile profile, @Nullable Throwable failure) {
		Objects.requireNonNull(side, "side");
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(profile, "profile");
		reportOccurrences(side, DiagnosticText.controlled(site), stage, profile, failure, 1);
	}

	void reportOccurrences(DiagnosticSide side, String site, Stage stage, LoggingProfile profile,
		@Nullable Throwable failure, long count) {
		if (reporting.get()) {
			recursive.updateAndGet(value -> add(value, count));
			return;
		}
		reporting.set(true);
		try {
			Kind kind = stage == Stage.REENTRY ? Kind.REENTRY
				: failure instanceof LinkageError ? Kind.LINKAGE : Kind.RUNTIME;
			Key key = new Key(side, site, stage, kind);
			State state = states.computeIfAbsent(key, ignored -> new State());
			state.occurrences.updateAndGet(value -> add(value, count));
			int desired = profile == LoggingProfile.NORMAL ? 1 : 3;
			int previous = state.attempted.getAndUpdate(value -> value | desired);
			if ((previous & desired) == desired) {
				state.repeats.updateAndGet(value -> add(value, count));
				return;
			}
			if (count > 1) state.repeats.updateAndGet(value -> add(value, count - 1));
			String record;
			try {
				record = "[PingForIt diagnostic-fault] side=" + side.name() + " site=" + site
					+ " stage=" + stage.name() + " kind=" + kind.name();
				if (profile != LoggingProfile.NORMAL && failure != null) {
					// Deliberately not SafeExceptionReport: no stack/cause/suppressed graph,
					// no localized message or throwable/Object string conversion.
					record += " class=" + DiagnosticText.scalar(failure.getClass().getName(), 192)
						+ " message=" + DiagnosticText.scalar(failure.getMessage(), 256);
				}
			} catch (Throwable formattingFailure) {
				formattingFailures.updateAndGet(DiagnosticFaultReporter::increment);
				if (!formatFallbackAttempted.compareAndSet(false, true)) return;
				record = "[PingForIt diagnostic-fault] self-format-unavailable";
			}
			writer.write(record);
		} finally {
			reporting.remove();
		}
	}

	/** Detached read-only observations, suitable for tests or a later summary UI. */
	public @Unmodifiable List<Observation> observations() {
		return states.entrySet().stream().map(entry -> new Observation(entry.getKey(),
			entry.getValue().occurrences.get(), entry.getValue().repeats.get(),
			entry.getValue().attempted.get())).toList();
	}

	public long recursiveFaults() { return recursive.get(); }
	public long formattingFailures() { return formattingFailures.get(); }
	public long writerFailures() { return writer.failures(); }

	static long increment(long value) {
		return value == Long.MAX_VALUE ? value : value + 1;
	}

	static long add(long value, long amount) {
		return Long.MAX_VALUE - value < amount ? Long.MAX_VALUE : value + amount;
	}

	@NotNullByDefault
	public record Key(DiagnosticSide side, String site, Stage stage, Kind kind) {}

	/** Attempt bits: bit 0 safe summary, bit 1 detailed scalar; not a delivery receipt. */
	@NotNullByDefault
	public record Observation(Key key, long occurrences, long repeats, int attemptedDisclosure) {}

	@NotNullByDefault
	private static final class State {
		private final AtomicLong occurrences = new AtomicLong();
		private final AtomicLong repeats = new AtomicLong();
		private final AtomicInteger attempted = new AtomicInteger();
	}
}
