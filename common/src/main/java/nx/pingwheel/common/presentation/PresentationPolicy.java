package nx.pingwheel.common.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** White > black > manifest default (or deny all unmatched in whitelist-only mode). */
public final class PresentationPolicy {
	private final List<String> white;
	private final List<String> black;
	private final boolean whitelistOnly;
	private final List<Selector> whiteSelectors;
	private final List<Selector> blackSelectors;

	public PresentationPolicy(List<String> white, List<String> black, boolean whitelistOnly) {
		this.white = List.copyOf(white);
		this.black = List.copyOf(black);
		if (this.white.size() > 128 || this.black.size() > 128)
			throw new IllegalArgumentException("too many selectors");
		this.whiteSelectors = compile(this.white);
		this.blackSelectors = compile(this.black);
		this.whitelistOnly = whitelistOnly;
	}

	public List<String> white() { return white; }
	public List<String> black() { return black; }
	public boolean whitelistOnly() { return whitelistOnly; }

	public static PresentationPolicy acceptAll() { return new PresentationPolicy(List.of(), List.of(), false); }

	public boolean allows(String id, boolean manifestDefault) {
		PresentationIds.validate(id);
		int colon = id.indexOf(':');
		for (Selector selector : whiteSelectors) if (selector.matches(id, colon)) return true;
		for (Selector selector : blackSelectors) if (selector.matches(id, colon)) return false;
		return !whitelistOnly && manifestDefault;
	}

	private static List<Selector> compile(List<String> selectors) {
		var result = new ArrayList<Selector>(selectors.size());
		for (String selector : selectors) {
			if (selector.length() > 193) throw new IllegalArgumentException("invalid selector: " + selector);
			int colon = selector.indexOf(':');
			if (colon < 1 || colon == selector.length() - 1 || selector.indexOf(':', colon + 1) >= 0)
				throw new IllegalArgumentException("invalid selector: " + selector);
			for (int i = 0; i < selector.length(); i++) {
				char c = selector.charAt(i);
				if (i == colon) continue;
				boolean allowed = c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
					|| c == '_' || c == '-' || c == '.' || c == '*'
					|| i > colon && c == '/';
				if (!allowed) throw new IllegalArgumentException("invalid selector: " + selector);
			}
			result.add(new Selector(Glob.compile(selector.substring(0, colon)), Glob.compile(selector.substring(colon + 1))));
		}
		return List.copyOf(result);
	}

	private record Selector(Glob namespace, Glob path) {
		boolean matches(String id, int colon) {
			return namespace.matches(id, 0, colon) && path.matches(id, colon + 1, id.length());
		}
	}

	/** Literal runs searched with precomputed KMP failure tables, without regex or backtracking. */
	private record Glob(List<Literal> pieces, boolean startsWithStar, boolean endsWithStar) {
		static Glob compile(String pattern) {
			var pieces = new ArrayList<Literal>();
			for (String part : pattern.split("\\*", -1))
				if (!part.isEmpty()) pieces.add(new Literal(part));
			return new Glob(List.copyOf(pieces), pattern.charAt(0) == '*', pattern.charAt(pattern.length() - 1) == '*');
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

	@Override public boolean equals(Object other) {
		return other instanceof PresentationPolicy policy && whitelistOnly == policy.whitelistOnly
			&& white.equals(policy.white) && black.equals(policy.black);
	}

	@Override public int hashCode() { return Objects.hash(white, black, whitelistOnly); }

	@Override public String toString() {
		return "PresentationPolicy[white=" + white + ", black=" + black + ", whitelistOnly=" + whitelistOnly + "]";
	}
}
