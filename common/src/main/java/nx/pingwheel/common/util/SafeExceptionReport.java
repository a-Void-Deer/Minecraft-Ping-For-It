package nx.pingwheel.common.util;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Pure, bounded exception diagnostics with unredacted throwable messages.
 *
 * <p>Includes types, messages, source frames, causes and suppressed exceptions.
 * Message control characters are escaped, not removed. This class never calls
 * {@code getLocalizedMessage()}, {@code toString()}, or a crash-report API.
 * Throwable accessors are treated as hostile and fail soft to explicit markers.
 */
@NotNullByDefault
public final class SafeExceptionReport {
	/** Maximum relationship depth, counting the top node as depth zero. */
	public static final int MAX_CAUSE_DEPTH = 16;
	/** Maximum suppressed nodes inspected for one throwable. */
	public static final int MAX_SUPPRESSED_PER_NODE = 8;
	/** Maximum source frames included for one throwable. */
	public static final int MAX_FRAMES_PER_NODE = 24;
	/** Maximum source frames included in the complete report. */
	public static final int MAX_TOTAL_FRAMES = 128;
	/** Maximum distinct throwables inspected in the complete report. */
	public static final int MAX_THROWABLE_NODES = 64;
	/** Maximum characters in a throwable-only report. */
	public static final int MAX_CHARACTERS = 12000;
	/** Maximum characters used for one relationship node. */
	public static final int MAX_NODE_CHARACTERS = 2400;
	/** Separate message budget leaves room for the node's source frames. */
	public static final int MAX_MESSAGE_CHARACTERS = 800;
	/** Context cannot consume the exception's source-frame budget. */
	public static final int MAX_CONTEXT_CHARACTERS = 1200;

	private static final String TRUNCATED = "[truncated]";
	private static final String NODE_TRUNCATED = "    [node truncated]\n";
	private static final String FRAMES_NODE_TRUNCATED = "    [frames truncated: node limit]\n";
	private static final String FRAMES_TOTAL_TRUNCATED = "    [frames truncated: total limit]\n";
	private static final String DEPTH_TRUNCATED = "[depth limit]";
	private static final String CYCLE = "[cycle]";
	private static final String UNAVAILABLE = "[unavailable]";
	private static final String NULL_VALUE = "[null]";
	private static final FailureObserver NO_OBSERVER = (accessor, failure) -> {};

	/** Controlled accessor labels only; the failure is attempt-local and must not be retained. */
	@NotNullByDefault
	@FunctionalInterface
	public interface FailureObserver {
		void failed(String accessor, Throwable failure);
	}

	private SafeExceptionReport() {}

	/**
	 * Formats {@code throwable}, including its original message, without invoking
	 * throwable string conversion. The bounded result is one log message argument;
	 * callers must not also attach the throwable for unbounded logger rendering.
	 */
	public static String format(@Nullable Throwable throwable) {
		return format(throwable, MAX_CHARACTERS, NO_OBSERVER);
	}

	public static String format(@Nullable Throwable throwable, FailureObserver observer) {
		return format(throwable, MAX_CHARACTERS, observer);
	}

	private static String format(@Nullable Throwable throwable, int maximum, FailureObserver observer) {
		BoundedText report = new BoundedText(maximum, TRUNCATED);

		if (throwable == null) {
			report.append("[null throwable]");
			return report.value();
		}

		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		FrameBudget frameBudget = new FrameBudget(MAX_TOTAL_FRAMES);
		renderNode(report, throwable, "exception", 0, visited, frameBudget, observer);
		return report.value();
	}

	/**
	 * Formats caller-provided detached scalar context and a throwable as one
	 * bounded message. Ordinary text is not redacted; control characters in the
	 * context are escaped, and report structure retains real line breaks.
	 */
	public static String formatWithContext(@Nullable String context, @Nullable Throwable throwable) {
		return formatWithContext(context, throwable, NO_OBSERVER);
	}

	public static String formatWithContext(@Nullable String context, @Nullable Throwable throwable, FailureObserver observer) {
		String boundedContext = boundedScalar(
			context == null ? "[null context]" : context,
			MAX_CONTEXT_CHARACTERS, "[context truncated]"
		);
		String separator = ":\n";
		return boundedContext + separator
			+ format(throwable, MAX_CHARACTERS - boundedContext.length() - separator.length(), observer);
	}

	private static void renderNode(
		BoundedText report,
		Throwable throwable,
		String relationship,
		int depth,
		Set<Throwable> visited,
		FrameBudget frameBudget,
		FailureObserver observer
	) {
		if (report.isTruncated()) {
			return;
		}

		if (visited.contains(throwable)) {
			appendRelationship(report, relationship, CYCLE, safeClassName(throwable, observer));
			return;
		}

		if (visited.size() >= MAX_THROWABLE_NODES) {
			appendRelationship(report, relationship, "[node limit]", null);
			return;
		}

		visited.add(throwable);

		BoundedText node = new BoundedText(MAX_NODE_CHARACTERS, NODE_TRUNCATED);
		node.append(relationship);
		node.append(": ");
		node.appendEscaped(safeClassName(throwable, observer));
		node.append(": ");
		node.append(boundedScalar(lookupMessage(throwable, observer), MAX_MESSAGE_CHARACTERS, "[message truncated]"));
		node.append("\n");
		appendFrames(node, throwable, frameBudget, observer);
		report.append(node.value());

		if (report.isTruncated()) {
			return;
		}

		if (depth >= MAX_CAUSE_DEPTH) {
			appendRelationship(report, "relations", DEPTH_TRUNCATED, null);
			return;
		}

		CauseLookup cause = lookupCause(throwable, observer);

		if (cause.failed) {
			appendRelationship(report, "cause", UNAVAILABLE, null);
		} else if (cause.value != null) {
			renderNode(report, cause.value, "cause", depth + 1, visited, frameBudget, observer);
		}

		if (report.isTruncated()) {
			return;
		}

		SuppressedLookup suppressed = lookupSuppressed(throwable, observer);

		if (suppressed.failed) {
			appendRelationship(report, "suppressed", UNAVAILABLE, null);
			return;
		}

		if (suppressed.values == null) {
			appendRelationship(report, "suppressed", UNAVAILABLE, null);
			return;
		}

		int count = Math.min(suppressed.values.length, MAX_SUPPRESSED_PER_NODE);

		for (int index = 0; index < count; index++) {
			if (report.isTruncated()) {
				return;
			}

			@Nullable Throwable value = suppressed.values[index];

			if (value == null) {
				appendRelationship(report, "suppressed[" + index + "]", NULL_VALUE, null);
			} else {
				renderNode(report, value, "suppressed[" + index + "]", depth + 1, visited, frameBudget, observer);
			}
		}

		if (suppressed.values.length > MAX_SUPPRESSED_PER_NODE) {
			appendRelationship(report, "suppressed", TRUNCATED, null);
		}
	}

	private static void appendRelationship(
		BoundedText report, String relationship, String marker, @Nullable String className
	) {
		report.append("  ");
		report.append(relationship);
		report.append(": ");
		report.append(marker);

		if (className != null) {
			report.append(" to ");
			report.appendEscaped(className);
		}

		report.append("\n");
	}

	private static void appendFrames(BoundedText node, Throwable throwable, FrameBudget frameBudget, FailureObserver observer) {
		@Nullable StackTraceElement @Nullable @Unmodifiable [] frames;

		try {
			frames = throwable.getStackTrace();
		} catch (Throwable failure) {
			observe(observer, "stack", failure);
			node.append("    [stack unavailable]\n");
			return;
		}

		if (frames == null) {
			node.append("    [stack unavailable]\n");
			return;
		}

		int count = Math.min(frames.length, MAX_FRAMES_PER_NODE);
		count = Math.min(count, frameBudget.remaining());

		for (int index = 0; index < count; index++) {
			appendFrame(node, frames[index], observer);
			frameBudget.consume();
		}

		if (frames.length > count) {
			if (frameBudget.remaining() == 0) {
				node.append(FRAMES_TOTAL_TRUNCATED);
			} else {
				node.append(FRAMES_NODE_TRUNCATED);
			}
		}
	}

	private static void appendFrame(BoundedText node, @Nullable StackTraceElement frame, FailureObserver observer) {
		node.append("    at ");

		if (frame == null) {
			node.append("[frame unavailable]\n");
			return;
		}

		node.appendEscaped(safeFrameClassName(frame, observer));
		node.append(".");
		node.appendEscaped(safeFrameMethodName(frame, observer));

		@Nullable String fileName = safeFrameFileName(frame, observer);
		int lineNumber = safeFrameLineNumber(frame, observer);

		if (fileName != null) {
			node.append(" (");
			node.appendEscaped(fileName);

			if (lineNumber >= 0) {
				node.append(":");
				node.append(Integer.toString(lineNumber));
			}

			node.append(")");
		} else if (lineNumber >= 0) {
			node.append(" (line ");
			node.append(Integer.toString(lineNumber));
			node.append(")");
		}

		node.append("\n");
	}

	private static String safeClassName(Throwable throwable, FailureObserver observer) {
		try {
			return throwable.getClass().getName();
		} catch (Throwable failure) {
			observe(observer, "class", failure);
			return UNAVAILABLE;
		}
	}

	private static String lookupMessage(Throwable throwable, FailureObserver observer) {
		try {
			@Nullable String message = throwable.getMessage();
			return message == null ? "[null message]" : message;
		} catch (RuntimeException | LinkageError failure) {
			observe(observer, "message", failure);
			return "[message unavailable]";
		}
	}

	private static String boundedScalar(String text, int maximum, String truncationMarker) {
		BoundedText scalar = new BoundedText(maximum, truncationMarker);
		scalar.appendEscaped(text);
		return scalar.value();
	}

	private static String safeFrameClassName(StackTraceElement frame, FailureObserver observer) {
		try {
			@Nullable String value = frame.getClassName();
			return value == null ? UNAVAILABLE : value;
		} catch (Throwable failure) {
			observe(observer, "frame-class", failure);
			return UNAVAILABLE;
		}
	}

	private static String safeFrameMethodName(StackTraceElement frame, FailureObserver observer) {
		try {
			@Nullable String value = frame.getMethodName();
			return value == null ? UNAVAILABLE : value;
		} catch (Throwable failure) {
			observe(observer, "frame-method", failure);
			return UNAVAILABLE;
		}
	}

	private static @Nullable String safeFrameFileName(StackTraceElement frame, FailureObserver observer) {
		try {
			@Nullable String value = frame.getFileName();
			return value;
		} catch (Throwable failure) {
			observe(observer, "frame-file", failure);
			return UNAVAILABLE;
		}
	}

	private static int safeFrameLineNumber(StackTraceElement frame, FailureObserver observer) {
		try {
			return frame.getLineNumber();
		} catch (Throwable failure) {
			observe(observer, "frame-line", failure);
			return -1;
		}
	}

	private static CauseLookup lookupCause(Throwable throwable, FailureObserver observer) {
		try {
			return new CauseLookup(throwable.getCause(), false);
		} catch (Throwable failure) {
			observe(observer, "cause", failure);
			return new CauseLookup(null, true);
		}
	}

	private static SuppressedLookup lookupSuppressed(Throwable throwable, FailureObserver observer) {
		try {
			return new SuppressedLookup(throwable.getSuppressed(), false);
		} catch (Throwable failure) {
			observe(observer, "suppressed", failure);
			return new SuppressedLookup(null, true);
		}
	}

	private static void observe(FailureObserver observer, String accessor, Throwable failure) {
		try {
			observer.failed(accessor, failure);
		} catch (RuntimeException | LinkageError ignored) {
			// An observer cannot replace the original unavailable marker or graph.
		}
	}

	@NotNullByDefault
	private static final class CauseLookup {
		private final @Nullable Throwable value;
		private final boolean failed;

		private CauseLookup(@Nullable Throwable value, boolean failed) {
			this.value = value;
			this.failed = failed;
		}
	}

	@NotNullByDefault
	private static final class SuppressedLookup {
		private final @Nullable Throwable @Nullable @Unmodifiable [] values;
		private final boolean failed;

		private SuppressedLookup(@Nullable Throwable @Nullable @Unmodifiable [] values, boolean failed) {
			this.values = values;
			this.failed = failed;
		}
	}

	@NotNullByDefault
	private static final class FrameBudget {
		private int remaining;

		private FrameBudget(int remaining) {
			this.remaining = remaining;
		}

		private int remaining() {
			return remaining;
		}

		private void consume() {
			if (remaining > 0) {
				remaining--;
			}
		}
	}

	@NotNullByDefault
	private static final class BoundedText {
		private final int maximum;
		private final String truncationMarker;
		private final StringBuilder value = new StringBuilder();
		private boolean truncated;

		private BoundedText(int maximum, String truncationMarker) {
			this.maximum = maximum;
			this.truncationMarker = truncationMarker;
		}

		private void append(@Nullable String text) {
			if (truncated) {
				return;
			}

			if (text == null) {
				text = NULL_VALUE;
			}

			int remaining = maximum - value.length();

			if (text.length() <= remaining) {
				value.append(text);
				return;
			}

			int contentLimit = Math.max(0, remaining - truncationMarker.length());
			value.append(text, 0, Math.min(text.length(), contentLimit));
			// An earlier append may have filled the budget exactly. Make room for
			// the marker even then, rather than silently losing later report data.
			value.setLength(Math.min(value.length(), Math.max(0, maximum - truncationMarker.length())));

			int markerLength = Math.min(truncationMarker.length(), maximum - value.length());
			value.append(truncationMarker, 0, markerLength);
			truncated = true;
		}

		private void appendEscaped(@Nullable String text) {
			if (text == null) {
				append(NULL_VALUE);
				return;
			}

			for (int index = 0; index < text.length() && !truncated; index++) {
				char character = text.charAt(index);
				switch (character) {
					case '\\' -> append("\\\\");
					case '\n' -> append("\\n");
					case '\r' -> append("\\r");
					case '\t' -> append("\\t");
					case '\b' -> append("\\b");
					case '\f' -> append("\\f");
					default -> {
						if (Character.isISOControl(character)) {
							String hex = Integer.toHexString(character);
							append("\\u" + "0".repeat(4 - hex.length()) + hex);
						} else {
							append(Character.toString(character));
						}
					}
				}
			}
		}

		private boolean isTruncated() {
			return truncated;
		}

		private String value() {
			return value.toString();
		}
	}
}
