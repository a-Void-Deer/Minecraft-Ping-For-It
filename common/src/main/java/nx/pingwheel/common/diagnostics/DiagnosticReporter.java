package nx.pingwheel.common.diagnostics;

import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded scalar-only window state, separate from context, formatting and sinks. */
@NotNullByDefault
final class DiagnosticReporter {

	static final int CAPACITY = 64;
	static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
	private static final int MAX_KEY_FIELD_CHARACTERS = 256;
	// The logger adds its prefix after formatting. Reserve it inside the total cap.
	private static final int MAX_MESSAGE_CHARACTERS = SafeExceptionReport.MAX_CHARACTERS - Diagnostics.PREFIX.length();
	private static final String TRUNCATED = "[truncated]";
	private static final Admission REJECTED = new Admission(false, false, 0);
	private static final Admission APPROVED = new Admission(true, false, 0);

	private final LongSupplier clock;
	private final Sink sink;
	private final Window debug = new Window();
	private final Window warn = new Window();
	private final Window error = new Window();

	DiagnosticReporter(LongSupplier clock, Sink sink) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.sink = Objects.requireNonNull(sink, "sink");
	}

	void report(Level level, @Nullable String operation, @Nullable String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		emit(level, operation, scope, null, context, failure, false, true);
	}

	void event(Level level, String operation, String scope, String reason,
		@Nullable Supplier<@Nullable String> context) {
		emit(level, operation, scope, reason, context, null, true, true);
	}

	void domainApprovedFailure(Level level, String operation, String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		emit(level, operation, scope, null, context, failure, false, false);
	}

	void domainApprovedEvent(Level level, String operation, String scope, String reason,
		@Nullable Supplier<@Nullable String> context) {
		emit(level, operation, scope, reason, context, null, true, false);
	}

	private void emit(Level level, @Nullable String operation, @Nullable String scope, @Nullable String reason,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure, boolean event, boolean limited) {
		try {
			@Nullable Window window = window(level);
			if (window == null || !sink.isEnabled(level)) {
				return;
			}

			String stableOperation = boundedKey(operation);
			String stableScope = boundedKey(scope);
			String stableReason = boundedKey(reason);
			Admission admission = APPROVED;
			if (limited) {
				Fingerprint fingerprint = new Fingerprint(stableOperation, stableScope, event,
					event ? stableReason : failure == null ? "[null throwable]" : boundedKey(failure.getClass().getName()),
					event ? "" : firstFrame(failure));
				// Throwable accessors stay outside the lock. Read the monotonic clock
				// inside admission so a paused caller cannot install a stale window.
				admission = window.admit(fingerprint, clock);
			}

			if (admission.overflowSummary) {
				sink.log(level, "operation=diagnostic-capacity scope=" + level.name()
					+ " reason=capacity suppressed=" + admission.suppressed + " capacity=" + CAPACITY);
				return;
			}
			if (!admission.allowed) {
				return;
			}

			@Nullable String detail;
			try {
				detail = context == null ? "[null context]" : context.get();
			} catch (RuntimeException | LinkageError contextFailure) {
				// A failed context accessor must not replace the original failure.
				detail = "[context unavailable: " + boundedKey(contextFailure.getClass().getName()) + "]";
			}

			String header = "operation=" + escaped(stableOperation, MAX_KEY_FIELD_CHARACTERS)
				+ " scope=" + escaped(stableScope, MAX_KEY_FIELD_CHARACTERS)
				+ (event ? " reason=" + escaped(stableReason, MAX_KEY_FIELD_CHARACTERS) : "")
				+ " suppressed=" + admission.suppressed;
			String body = event ? "context=" + escaped(detail, SafeExceptionReport.MAX_CONTEXT_CHARACTERS)
				: SafeExceptionReport.formatWithContext(detail, failure);
			// Reserve the envelope before adding the body; never append unbounded
			// metadata or a Throwable after SafeExceptionReport's maximum report.
			sink.log(level, header + "\n" + bounded(body, MAX_MESSAGE_CHARACTERS - header.length() - 1));
		} catch (RuntimeException | LinkageError ignored) {
			// Facilities fail soft without recursively logging. Admission is consumed
			// even when the sink fails. Fatal JVM/resource errors still propagate.
		}
	}

	private @Nullable Window window(Level level) {
		if (level == Level.DEBUG) {
			return debug;
		}
		if (level == Level.WARN) {
			return warn;
		}
		return level == Level.ERROR ? error : null;
	}

	int bucketCount() {
		return debug.bucketCount() + warn.bucketCount() + error.bucketCount();
	}

	int bucketCount(Level level) {
		@Nullable Window window = window(level);
		return window == null ? 0 : window.bucketCount();
	}

	private static long increment(long value) {
		return value == Long.MAX_VALUE ? value : value + 1;
	}

	private static String firstFrame(@Nullable Throwable failure) {
		if (failure == null) {
			return "[no stack]";
		}
		try {
			@Nullable StackTraceElement @Nullable @Unmodifiable [] frames = failure.getStackTrace();
			if (frames != null) {
				int count = Math.min(frames.length, SafeExceptionReport.MAX_FRAMES_PER_NODE);
				for (int index = 0; index < count; index++) {
					@Nullable StackTraceElement frame = frames[index];
					if (frame != null) {
						return boundedKey(frame.getClassName()) + "." + boundedKey(frame.getMethodName())
							+ "(" + boundedKey(frame.getFileName()) + ":" + frame.getLineNumber() + ")";
					}
				}
			}
		} catch (RuntimeException | LinkageError ignored) {
			return "[stack unavailable]";
		}
		return "[no stack]";
	}

	private static String boundedKey(@Nullable String text) {
		if (text == null || text.length() <= MAX_KEY_FIELD_CHARACTERS) {
			return text == null ? "[null]" : text;
		}
		String suffix = TRUNCATED + "#" + Integer.toHexString(text.hashCode());
		return text.substring(0, MAX_KEY_FIELD_CHARACTERS - suffix.length()) + suffix;
	}

	private static String bounded(String text, int maximum) {
		return text.length() <= maximum ? text : text.substring(0, maximum - TRUNCATED.length()) + TRUNCATED;
	}

	/** Escape only scalar event/header text; all exception detail belongs to SafeExceptionReport. */
	private static String escaped(@Nullable String text, int maximum) {
		if (text == null) {
			return "[null]";
		}
		StringBuilder result = new StringBuilder();
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			String token = switch (character) {
				case '\\' -> "\\\\";
				case '\n' -> "\\n";
				case '\r' -> "\\r";
				case '\t' -> "\\t";
				case '\b' -> "\\b";
				case '\f' -> "\\f";
				default -> {
					if (Character.isISOControl(character)) {
						String hex = Integer.toHexString(character);
						yield "\\u" + "0".repeat(4 - hex.length()) + hex;
					}
					yield Character.toString(character);
				}
			};
			if (result.length() + token.length() > maximum) {
				result.setLength(Math.min(result.length(), maximum - TRUNCATED.length()));
				return result.append(TRUNCATED).toString();
			}
			result.append(token);
		}
		return result.toString();
	}

	@NotNullByDefault
	interface Sink {
		boolean isEnabled(Level level);
		void log(Level level, String message);
	}

	@NotNullByDefault
	private record Fingerprint(String operation, String scope, boolean event, String typeOrReason, String frame) {}

	@NotNullByDefault
	private record Admission(boolean allowed, boolean overflowSummary, long suppressed) {}

	@NotNullByDefault
	private static final class Bucket {
		private long lastReport;
		private long suppressed;

		private Bucket(long lastReport) {
			this.lastReport = lastReport;
		}
	}

	@NotNullByDefault
	private static final class Window {
		private final Map<Fingerprint, Bucket> buckets = new HashMap<>();
		private boolean overflowReported;
		private long lastOverflowReport;
		private long overflowSuppressed;

		private synchronized Admission admit(Fingerprint fingerprint, LongSupplier clock) {
			long now = clock.getAsLong();
			@Nullable Bucket existing = buckets.get(fingerprint);
			if (existing != null) {
				if (now - existing.lastReport < INTERVAL_NANOS) {
					existing.suppressed = increment(existing.suppressed);
					return REJECTED;
				}
				long suppressed = existing.suppressed;
				existing.lastReport = now;
				existing.suppressed = 0;
				return new Admission(true, false, suppressed);
			}

			buckets.entrySet().removeIf(entry -> now - entry.getValue().lastReport >= INTERVAL_NANOS);
			if (buckets.size() < CAPACITY) {
				buckets.put(fingerprint, new Bucket(now));
				return APPROVED;
			}

			overflowSuppressed = increment(overflowSuppressed);
			if (!overflowReported || now - lastOverflowReport >= INTERVAL_NANOS) {
				overflowReported = true;
				lastOverflowReport = now;
				long suppressed = overflowSuppressed;
				overflowSuppressed = 0;
				return new Admission(false, true, suppressed);
			}
			return REJECTED;
		}

		private synchronized int bucketCount() {
			return buckets.size();
		}
	}
}
