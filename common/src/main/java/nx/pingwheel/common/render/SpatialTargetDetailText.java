package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.StringDecomposer;

/** Bounded, styled display text for the selector's external candidate-name preview. */
final class SpatialTargetDetailText {

	static final int MAX_CODE_POINTS = 32;

	private SpatialTargetDetailText() {}

	/** Counts visible code points after vanilla legacy formatting; overflow is 31 plus an ellipsis. */
	static Component limit(Component source) {
		var points = new Points();
		source.visit((style, text) -> points.append(style, text) ? Optional.empty() : Optional.of(Boolean.TRUE), Style.EMPTY);
		points.finish();
		boolean truncated = points.values.size() > MAX_CODE_POINTS;
		int count = truncated ? MAX_CODE_POINTS - 1 : points.values.size();
		var result = Component.empty();
		var run = new StringBuilder();
		Style runStyle = Style.EMPTY;
		for (int i = 0; i < count; i++) {
			Point point = points.values.get(i);
			if (!point.style().equals(runStyle) && !run.isEmpty()) {
				result.append(Component.literal(run.toString()).withStyle(runStyle));
				run.setLength(0);
			}
			runStyle = point.style();
			run.appendCodePoint(point.codePoint());
		}
		if (truncated) run.append('\u2026');
		if (!run.isEmpty()) result.append(Component.literal(run.toString()).withStyle(runStyle));
		return result;
	}

	private record Point(int codePoint, Style style) {}

	/** Stops at the 33rd visible code point, without flattening or peeking at the full source. */
	private static final class Points {
		private final List<Point> values = new ArrayList<>(MAX_CODE_POINTS + 1);
		private char pendingHigh;
		private Style pendingStyle;

		boolean append(Style style, String text) {
			if (text.isEmpty()) return true;
			if (pendingHigh != 0 && !Character.isLowSurrogate(text.charAt(0))) {
				pendingHigh = 0;
				if (!add(0xFFFD, pendingStyle)) return false;
			}
			// Each segment starts and resets to its own resolved style, as vanilla
			// does. Never carry a section sign or a legacy formatting flag across
			// a Component boundary. Only a physically split surrogate pair joins.
			return StringDecomposer.iterateFormatted(text, style, (index, resolvedStyle, codePoint) -> {
				if (index == 0 && pendingHigh != 0) {
					char high = pendingHigh;
					pendingHigh = 0;
					return add(Character.toCodePoint(high, text.charAt(0)), pendingStyle);
				}
				if (index == text.length() - 1 && Character.isHighSurrogate(text.charAt(index))) {
					pendingHigh = text.charAt(index);
					pendingStyle = resolvedStyle;
					return true;
				}
				return add(codePoint, resolvedStyle);
			});
		}

		void finish() {
			if (pendingHigh != 0 && values.size() <= MAX_CODE_POINTS) add(0xFFFD, pendingStyle);
		}

		private boolean add(int codePoint, Style style) {
			values.add(new Point(codePoint, style));
			return values.size() <= MAX_CODE_POINTS;
		}
	}
}
