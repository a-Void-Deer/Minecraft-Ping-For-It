package nx.pingwheel.common.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Pure, bounded exception diagnostics that never inspect throwable payloads.
 *
 * <p>Only class names, relationships, and selected source-frame fields are
 * included. In particular, this class never calls {@code getMessage()},
 * {@code getLocalizedMessage()}, {@code toString()}, or a crash-report API.
 * Throwable accessors are treated as hostile and fail soft to a fixed marker.
 */
public final class SafeExceptionReport {
	/** Maximum relationship depth, counting the top node as depth zero. */
	public static final int MAX_CAUSE_DEPTH = 16;
	/** Maximum suppressed nodes inspected for one throwable. */
	public static final int MAX_SUPPRESSED_PER_NODE = 8;
	/** Maximum source frames included for one throwable. */
	public static final int MAX_FRAMES_PER_NODE = 24;
	/** Maximum source frames included in the complete report. */
	public static final int MAX_TOTAL_FRAMES = 128;
	/** Maximum characters in a throwable-only report. */
	public static final int MAX_CHARACTERS = 12000;
	/** Maximum characters used for one relationship node. */
	public static final int MAX_NODE_CHARACTERS = 2400;

	private static final String TRUNCATED = "[truncated]";
	private static final String NODE_TRUNCATED = "    [node truncated]\n";
	private static final String FRAMES_NODE_TRUNCATED = "    [frames truncated: node limit]\n";
	private static final String FRAMES_TOTAL_TRUNCATED = "    [frames truncated: total limit]\n";
	private static final String DEPTH_TRUNCATED = "[depth limit]";
	private static final String CYCLE = "[cycle]";
	private static final String UNAVAILABLE = "[unavailable]";
	private static final String NULL_VALUE = "[null]";

	private SafeExceptionReport() {}

	/**
	 * Formats {@code throwable} without reading its message or converting it to
	 * text. The result is bounded, deterministic, and safe to pass as one log
	 * message argument.
	 */
	public static String format(Throwable throwable) {
		BoundedText report = new BoundedText(MAX_CHARACTERS, TRUNCATED);

		if (throwable == null) {
			report.append("[null throwable]");
			return report.value();
		}

		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		FrameBudget frameBudget = new FrameBudget(MAX_TOTAL_FRAMES);
		renderNode(report, throwable, "exception", 0, visited, frameBudget);
		return report.value();
	}

	/**
	 * Formats a safe caller-provided context and a throwable as one complete
	 * message. Callers must supply only constant text or safe scalar values;
	 * this method never derives context from game, network, or throwable data.
	 */
	public static String formatWithContext(String constantContext, Throwable throwable) {
		BoundedText message = new BoundedText(MAX_CHARACTERS, TRUNCATED);
		message.append(constantContext == null ? "[null context]" : constantContext);
		message.append(": ");
		message.append(format(throwable));
		return message.value();
	}

	private static void renderNode(
		BoundedText report,
		Throwable throwable,
		String relationship,
		int depth,
		Set<Throwable> visited,
		FrameBudget frameBudget
	) {
		if (report.isTruncated()) {
			return;
		}

		if (!visited.add(throwable)) {
			appendRelationship(report, relationship, CYCLE, safeClassName(throwable));
			return;
		}

		BoundedText node = new BoundedText(MAX_NODE_CHARACTERS, NODE_TRUNCATED);
		node.append(relationship);
		node.append(": ");
		node.append(safeClassName(throwable));
		node.append("\n");
		appendFrames(node, throwable, frameBudget);
		report.append(node.value());

		if (report.isTruncated()) {
			return;
		}

		if (depth >= MAX_CAUSE_DEPTH) {
			appendRelationship(report, "relations", DEPTH_TRUNCATED, null);
			return;
		}

		CauseLookup cause = lookupCause(throwable);

		if (cause.failed) {
			appendRelationship(report, "cause", UNAVAILABLE, null);
		} else if (cause.value != null) {
			renderNode(report, cause.value, "cause", depth + 1, visited, frameBudget);
		}

		if (report.isTruncated()) {
			return;
		}

		SuppressedLookup suppressed = lookupSuppressed(throwable);

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

			Throwable value = suppressed.values[index];

			if (value == null) {
				appendRelationship(report, "suppressed[" + index + "]", NULL_VALUE, null);
			} else {
				renderNode(report, value, "suppressed[" + index + "]", depth + 1, visited, frameBudget);
			}
		}

		if (suppressed.values.length > MAX_SUPPRESSED_PER_NODE) {
			appendRelationship(report, "suppressed", TRUNCATED, null);
		}
	}

	private static void appendRelationship(
		BoundedText report, String relationship, String marker, String className
	) {
		report.append("  ");
		report.append(relationship);
		report.append(": ");
		report.append(marker);

		if (className != null) {
			report.append(" to ");
			report.append(className);
		}

		report.append("\n");
	}

	private static void appendFrames(BoundedText node, Throwable throwable, FrameBudget frameBudget) {
		StackTraceElement[] frames;

		try {
			frames = throwable.getStackTrace();
		} catch (Throwable ignored) {
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
			appendFrame(node, frames[index]);
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

	private static void appendFrame(BoundedText node, StackTraceElement frame) {
		node.append("    at ");

		if (frame == null) {
			node.append("[frame unavailable]\n");
			return;
		}

		node.append(safeFrameClassName(frame));
		node.append(".");
		node.append(safeFrameMethodName(frame));

		String fileName = safeFrameFileName(frame);
		int lineNumber = safeFrameLineNumber(frame);

		if (fileName != null) {
			node.append(" (");
			node.append(fileName);

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

	private static String safeClassName(Throwable throwable) {
		try {
			return throwable.getClass().getName();
		} catch (Throwable ignored) {
			return UNAVAILABLE;
		}
	}

	private static String safeFrameClassName(StackTraceElement frame) {
		try {
			String value = frame.getClassName();
			return value == null ? UNAVAILABLE : value;
		} catch (Throwable ignored) {
			return UNAVAILABLE;
		}
	}

	private static String safeFrameMethodName(StackTraceElement frame) {
		try {
			String value = frame.getMethodName();
			return value == null ? UNAVAILABLE : value;
		} catch (Throwable ignored) {
			return UNAVAILABLE;
		}
	}

	private static String safeFrameFileName(StackTraceElement frame) {
		try {
			String value = frame.getFileName();
			return value;
		} catch (Throwable ignored) {
			return UNAVAILABLE;
		}
	}

	private static int safeFrameLineNumber(StackTraceElement frame) {
		try {
			return frame.getLineNumber();
		} catch (Throwable ignored) {
			return -1;
		}
	}

	private static CauseLookup lookupCause(Throwable throwable) {
		try {
			return new CauseLookup(throwable.getCause(), false);
		} catch (Throwable ignored) {
			return new CauseLookup(null, true);
		}
	}

	private static SuppressedLookup lookupSuppressed(Throwable throwable) {
		try {
			return new SuppressedLookup(throwable.getSuppressed(), false);
		} catch (Throwable ignored) {
			return new SuppressedLookup(null, true);
		}
	}

	private static final class CauseLookup {
		private final Throwable value;
		private final boolean failed;

		private CauseLookup(Throwable value, boolean failed) {
			this.value = value;
			this.failed = failed;
		}
	}

	private static final class SuppressedLookup {
		private final Throwable[] values;
		private final boolean failed;

		private SuppressedLookup(Throwable[] values, boolean failed) {
			this.values = values;
			this.failed = failed;
		}
	}

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

	private static final class BoundedText {
		private final int maximum;
		private final String truncationMarker;
		private final StringBuilder value = new StringBuilder();
		private boolean truncated;

		private BoundedText(int maximum, String truncationMarker) {
			this.maximum = maximum;
			this.truncationMarker = truncationMarker;
		}

		private void append(String text) {
			if (truncated) {
				return;
			}

			if (text == null) {
				text = NULL_VALUE;
			}

			int remaining = maximum - value.length();

			if (text.length() <= remaining) {
				appendSanitized(text, text.length());
				return;
			}

			int contentLimit = Math.max(0, remaining - truncationMarker.length());
			appendSanitized(text, contentLimit);

			int markerLength = Math.min(truncationMarker.length(), maximum - value.length());
			value.append(truncationMarker, 0, markerLength);
			truncated = true;
		}

		private void appendSanitized(String text, int characterLimit) {
			int count = Math.min(text.length(), Math.max(0, characterLimit));

			for (int index = 0; index < count; index++) {
				char character = text.charAt(index);
				value.append(character < 0x20 || character == 0x7F ? '?' : character);
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
