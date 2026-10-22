package nx.pingwheel.common.presentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiled {@code *}-wildcard pattern over one selector part. Literal runs are
 * searched with precomputed KMP failure tables, without regex or backtracking,
 * so overlapping stars cannot cause catastrophic runtime.
 *
 * <p>This is the single wildcard owner shared by field-ID selectors
 * ({@link PresentationPolicy}) and target selectors
 * ({@link PresentationTargetSelector}). A {@code *} matches zero or more
 * characters inside its part; the caller keeps the namespace and path parts
 * separate, so a wildcard never crosses the {@code :} separator.
 */
final class PresentationPattern {
	private final List<Literal> pieces;
	private final boolean startsWithStar;
	private final boolean endsWithStar;

	private PresentationPattern(List<Literal> pieces, boolean startsWithStar, boolean endsWithStar) {
		this.pieces = pieces;
		this.startsWithStar = startsWithStar;
		this.endsWithStar = endsWithStar;
	}

	static PresentationPattern compile(String pattern) {
		var pieces = new ArrayList<Literal>();
		for (String part : pattern.split("\\*", -1))
			if (!part.isEmpty()) pieces.add(new Literal(part));
		return new PresentationPattern(
			List.copyOf(pieces),
			pattern.charAt(0) == '*',
			pattern.charAt(pattern.length() - 1) == '*');
	}

	boolean matches(String text, int start, int end) {
		if (pieces.isEmpty()) return true; // the entire part is wildcards
		int cursor = start;
		int first = 0;
		int last = pieces.size();
		if (!startsWithStar) {
			String prefix = pieces.get(first++).text();
			if (!text.startsWith(prefix, cursor) || cursor + prefix.length() > end) return false;
			cursor += prefix.length();
		}
		int boundary = end;
		boolean suffixMatched = false;
		if (!endsWithStar && last > first) {
			String suffix = pieces.get(--last).text();
			boundary -= suffix.length();
			if (boundary < cursor || !text.startsWith(suffix, boundary)) return false;
			suffixMatched = true;
		}
		for (int i = first; i < last; i++) {
			cursor = pieces.get(i).findEnd(text, cursor, boundary);
			if (cursor < 0) return false;
		}
		return endsWithStar || suffixMatched || cursor == end;
	}

	private record Literal(String text, int[] failure) {
		Literal(String text) { this(text, prefixTable(text)); }

		private static int[] prefixTable(String text) {
			int[] table = new int[text.length()];
			for (int i = 1, match = 0; i < text.length(); i++) {
				while (match > 0 && text.charAt(i) != text.charAt(match)) match = table[match - 1];
				if (text.charAt(i) == text.charAt(match)) match++;
				table[i] = match;
			}
			return table;
		}

		int findEnd(String input, int start, int end) {
			for (int i = start, match = 0; i < end; i++) {
				while (match > 0 && input.charAt(i) != text.charAt(match)) match = failure[match - 1];
				if (input.charAt(i) == text.charAt(match)) match++;
				if (match == text.length()) return i + 1;
			}
			return -1;
		}
	}
}
