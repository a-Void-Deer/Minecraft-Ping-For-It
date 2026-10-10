package nx.pingwheel.common.presentation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * White > black > manifest default (or deny all unmatched in whitelist-only mode).
 * The child blacklist additionally denies exact nested property references: an
 * allow selector never overrides a child deny, a deny covers only the exact
 * adapter/field/record-path tuple, and the root value of a field with denied
 * nested entries stays authorized and complete.
 */
public final class PresentationPolicy {
	private final List<String> white;
	private final List<String> black;
	private final boolean whitelistOnly;
	private final List<PresentationPropertyRef> childBlack;
	private final List<Selector> whiteSelectors;
	private final List<Selector> blackSelectors;

	public PresentationPolicy(List<String> white, List<String> black, boolean whitelistOnly) {
		this(white, black, whitelistOnly, List.of());
	}

	public PresentationPolicy(List<String> white, List<String> black, boolean whitelistOnly,
		List<PresentationPropertyRef> childBlack) {
		this.white = List.copyOf(white);
		this.black = List.copyOf(black);
		if (this.white.size() > 128 || this.black.size() > 128)
			throw new IllegalArgumentException("too many selectors");
		this.childBlack = List.copyOf(childBlack);
		if (this.childBlack.size() > PresentationSettings.MAX_CHILD_BLACK_REFS)
			throw new IllegalArgumentException("too many child deny references");
		Set<PresentationPropertyRef> unique = new HashSet<>();
		for (PresentationPropertyRef ref : this.childBlack) {
			if (ref.isRoot()) throw new IllegalArgumentException("child deny reference must be nested");
			if (!unique.add(ref)) throw new IllegalArgumentException("duplicate child deny reference");
		}
		this.whiteSelectors = compile(this.white);
		this.blackSelectors = compile(this.black);
		this.whitelistOnly = whitelistOnly;
	}

	public List<String> white() { return white; }
	public List<String> black() { return black; }
	public boolean whitelistOnly() { return whitelistOnly; }
	public List<PresentationPropertyRef> childBlack() { return childBlack; }

	/**
	 * The exact child-level predicate: true when this adapter/field/record-path
	 * tuple is not on the child blacklist. Field-level authorization stays
	 * {@link #allows(String, boolean)}; this predicate never grants a field the
	 * selector policy denies, and a field allow selector never overrides a child
	 * deny. Neither an ancestor nor a descendant path is implied.
	 */
	public boolean propertyAllowed(PresentationPropertyRef ref) {
		return !childDenied(ref);
	}

	/**
	 * The combined field-and-child predicate: the field must pass the selector
	 * policy with the given manifest default, and the exact reference must not be
	 * child-denied.
	 */
	public boolean propertyAllowed(PresentationPropertyRef ref, boolean manifestDefault) {
		return propertyAllowed(ref) && allows(ref.fieldId(), manifestDefault);
	}

	/** True when this exact tuple is on the child blacklist. */
	public boolean childDenied(PresentationPropertyRef ref) {
		Objects.requireNonNull(ref, "ref");
		return childBlack.contains(ref);
	}

	public static PresentationPolicy acceptAll() { return new PresentationPolicy(List.of(), List.of(), false); }

	public boolean allows(String id, boolean manifestDefault) {
		PresentationIds.validate(id);
		int colon = id.indexOf(':');
		for (Selector selector : whiteSelectors) if (selector.matches(id, colon)) return true;
		for (Selector selector : blackSelectors) if (selector.matches(id, colon)) return false;
		return !whitelistOnly && manifestDefault;
	}

	/**
	 * The persisted selectors of one list that match a field id, in list order.
	 * Read-only diagnostics for truthful rule explanations; evaluation itself
	 * stays in {@link #allows(String, boolean)}.
	 */
	public List<String> matchingSelectors(String id, boolean white) {
		PresentationIds.validate(id);
		int colon = id.indexOf(':');
		List<Selector> selectors = white ? whiteSelectors : blackSelectors;
		List<String> persisted = white ? this.white : this.black;
		List<String> matches = new ArrayList<>();
		for (int i = 0; i < selectors.size(); i++) {
			if (selectors.get(i).matches(id, colon)) matches.add(persisted.get(i));
		}
		return List.copyOf(matches);
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
			result.add(new Selector(
				PresentationPattern.compile(selector.substring(0, colon)),
				PresentationPattern.compile(selector.substring(colon + 1))));
		}
		return List.copyOf(result);
	}

	private record Selector(PresentationPattern namespace, PresentationPattern path) {
		boolean matches(String id, int colon) {
			return namespace.matches(id, 0, colon) && path.matches(id, colon + 1, id.length());
		}
	}

	@Override public boolean equals(Object other) {
		return other instanceof PresentationPolicy policy && whitelistOnly == policy.whitelistOnly
			&& white.equals(policy.white) && black.equals(policy.black)
			&& childBlack.equals(policy.childBlack);
	}

	@Override public int hashCode() { return Objects.hash(white, black, whitelistOnly, childBlack); }

	@Override public String toString() {
		return "PresentationPolicy[white=" + white + ", black=" + black
			+ ", whitelistOnly=" + whitelistOnly + ", childBlack=" + childBlack + "]";
	}
}
