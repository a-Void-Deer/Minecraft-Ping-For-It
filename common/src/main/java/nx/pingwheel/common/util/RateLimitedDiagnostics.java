package nx.pingwheel.common.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Bounded diagnostics for frequently retried, fail-soft operations.
 *
 * <p>Keys contain only bounded scalar metadata, never a throwable, game object
 * or context supplier. Context is evaluated only for an admitted, enabled event.
 * New fingerprints cannot evict active entries and thereby bypass the limit.
 */
public final class RateLimitedDiagnostics {

	// Enough independent provider/stage failures for useful diagnostics while
	// keeping retained state and each cleanup scan small under request churn.
	static final int CAPACITY = 64;
	static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
	private static final int MAX_KEY_FIELD_CHARACTERS = 256;
	private static final String TRUNCATED = "[truncated]";
	private static volatile RateLimitedDiagnostics production = new RateLimitedDiagnostics(
		System::nanoTime, new Sink() {
			@Override
			public boolean isEnabled(Level level) {
				return LoggerHolder.LOGGER.isEnabled(level);
			}

			@Override
			public void log(Level level, String message) {
				LoggerHolder.LOGGER.log(level, message);
			}
		}
	);

	private final LongSupplier clock;
	private final Sink sink;
	private final Map<Fingerprint, Bucket> buckets = new HashMap<>();
	private boolean overflowReported;
	private long lastOverflowReport;
	private long overflowSuppressed;

	RateLimitedDiagnostics(LongSupplier clock, Sink sink) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.sink = Objects.requireNonNull(sink, "sink");
	}

	/**
	 * Test-only seam: installs a replacement shared limiter and returns the
	 * previously installed instance so a scoped test can restore it. Production
	 * callers always use the shared instance; this is neither a public API nor a
	 * user-facing reset.
	 */
	static RateLimitedDiagnostics swapProductionForTesting(RateLimitedDiagnostics replacement) {
		RateLimitedDiagnostics previous = production;
		production = Objects.requireNonNull(replacement, "replacement");
		return previous;
	}

	/** Logs a bounded DEBUG report with the same admission contract as {@link #warn}. */
	public static void debug(String operation, String scope, Supplier<String> context, Throwable failure) {
		production.report(Level.DEBUG, operation, scope, context, failure);
	}

	/**
	 * Logs a bounded WARN report. Operation and scope must be stable stage and
	 * provider/target-class/registry names, not request or round identifiers.
	 * Context supplies detached scalar detail, including unrestricted payload text.
	 */
	public static void warn(String operation, String scope, Supplier<String> context, Throwable failure) {
		production.report(Level.WARN, operation, scope, context, failure);
	}

	/** Logs a bounded ERROR report with the same admission contract as {@link #warn}. */
	public static void error(String operation, String scope, Supplier<String> context, Throwable failure) {
		production.report(Level.ERROR, operation, scope, context, failure);
	}

	void report(Level level, String operation, String scope, Supplier<String> context, Throwable failure) {
		try {
			if (!sink.isEnabled(level)) {
				return;
			}

			Fingerprint fingerprint = new Fingerprint(
				level, bounded(operation), bounded(scope),
				failure == null ? "[null throwable]" : bounded(failure.getClass().getName()),
				firstFrame(failure)
			);
			Admission admission = admit(fingerprint);

			if (admission.overflowSummary) {
				sink.log(level, "Diagnostic capacity reached; new fingerprints suppressed="
					+ admission.suppressed + " capacity=" + CAPACITY);
				return;
			}

			if (!admission.allowed) {
				return;
			}

			String detail;
			try {
				detail = context == null ? "[null context]" : context.get();
			} catch (RuntimeException | LinkageError contextFailure) {
				// Keep the original failure: a broken context accessor must not hide it.
				detail = "[context unavailable: " + bounded(contextFailure.getClass().getName()) + "]";
			}

			String diagnosticContext = "operation=" + fingerprint.operation
				+ " scope=" + fingerprint.scope
				+ " suppressed=" + admission.suppressed
				+ " context=" + bounded(detail, SafeExceptionReport.MAX_CONTEXT_CHARACTERS);
			sink.log(level, SafeExceptionReport.formatWithContext(diagnosticContext, failure));
		} catch (RuntimeException | LinkageError ignored) {
			// Logging infrastructure is fail-soft. Fatal JVM/resource errors are
			// deliberately not caught at this boundary.
		}
	}

	private synchronized Admission admit(Fingerprint fingerprint) {
		long now = clock.getAsLong();
		Bucket existing = buckets.get(fingerprint);

		if (existing != null) {
			if (now - existing.lastReport < INTERVAL_NANOS) {
				existing.suppressed = increment(existing.suppressed);
				return new Admission(false, false, 0);
			}

			long suppressed = existing.suppressed;
			existing.lastReport = now;
			existing.suppressed = 0;
			return new Admission(true, false, suppressed);
		}

		buckets.entrySet().removeIf(entry -> now - entry.getValue().lastReport >= INTERVAL_NANOS);

		if (buckets.size() < CAPACITY) {
			buckets.put(fingerprint, new Bucket(now));
			return new Admission(true, false, 0);
		}

		overflowSuppressed = increment(overflowSuppressed);
		if (!overflowReported || now - lastOverflowReport >= INTERVAL_NANOS) {
			overflowReported = true;
			lastOverflowReport = now;
			long suppressed = overflowSuppressed;
			overflowSuppressed = 0;
			return new Admission(false, true, suppressed);
		}

		return new Admission(false, false, 0);
	}

	synchronized int bucketCount() {
		return buckets.size();
	}

	private static long increment(long value) {
		return value == Long.MAX_VALUE ? value : value + 1;
	}

	private static String firstFrame(Throwable failure) {
		if (failure == null) {
			return "[no stack]";
		}

		try {
			StackTraceElement[] frames = failure.getStackTrace();
			if (frames != null) {
				int count = Math.min(frames.length, SafeExceptionReport.MAX_FRAMES_PER_NODE);
				for (int index = 0; index < count; index++) {
					StackTraceElement frame = frames[index];
					if (frame != null) {
						return bounded(frame.getClassName()) + "." + bounded(frame.getMethodName())
							+ "(" + bounded(frame.getFileName()) + ":" + frame.getLineNumber() + ")";
					}
				}
			}
		} catch (RuntimeException | LinkageError ignored) {
			return "[stack unavailable]";
		}
		return "[no stack]";
	}

	private static String bounded(String text) {
		if (text == null || text.length() <= MAX_KEY_FIELD_CHARACTERS) {
			return text == null ? "[null]" : text;
		}
		// Retain a bounded discriminator as well as the readable prefix: two
		// long provider/class names should not collapse merely at the text cap.
		String suffix = TRUNCATED + "#" + Integer.toHexString(text.hashCode());
		return text.substring(0, MAX_KEY_FIELD_CHARACTERS - suffix.length()) + suffix;
	}

	private static String bounded(String text, int maximum) {
		if (text == null) {
			return "[null]";
		}
		return text.length() <= maximum ? text
			: text.substring(0, maximum - TRUNCATED.length()) + TRUNCATED;
	}

	interface Sink {
		boolean isEnabled(Level level);
		void log(Level level, String message);
	}

	private record Fingerprint(Level level, String operation, String scope, String type, String frame) {}
	private record Admission(boolean allowed, boolean overflowSummary, long suppressed) {}

	private static final class Bucket {
		private long lastReport;
		private long suppressed;

		private Bucket(long lastReport) {
			this.lastReport = lastReport;
		}
	}

	private static final class LoggerHolder {
		private static final Logger LOGGER = LogManager.getLogger("pingforit");
	}
}
