package nx.pingwheel.common.presentation;

import java.util.Set;

/**
 * One target selector: either a plain registry ID or a {@code #}-prefixed tag
 * ID, with independent namespace and path wildcards. The selection context
 * supplies the actual registry ID and the target's tag IDs; a {@code #}
 * selector never falls back to matching the registry ID, and a plain selector
 * never matches a tag name.
 *
 * <p>Grammar: an optional leading {@code #}, then {@code namespace:path} with
 * exactly one colon. Each part accepts lowercase letters, digits, {@code _},
 * {@code -}, {@code .} and {@code *}; the path part may also contain {@code /}.
 * A {@code *} matches zero or more characters inside its part and never crosses
 * the colon. Examples: {@code #*:iron_ingot}, {@code #create:*},
 * {@code cyclic:*}, {@code *:*}.
 */
public final class PresentationTargetSelector implements Comparable<PresentationTargetSelector> {
	public static final int MAX_LENGTH = 193;

	private final String selector;
	private final boolean tag;
	private final PresentationPattern namespace;
	private final PresentationPattern path;

	private PresentationTargetSelector(String selector, boolean tag, PresentationPattern namespace, PresentationPattern path) {
		this.selector = selector;
		this.tag = tag;
		this.namespace = namespace;
		this.path = path;
	}

	/** Strictly validates and compiles one selector. */
	public static PresentationTargetSelector of(String selector) {
		if (!isValid(selector)) throw new IllegalArgumentException("invalid target selector: " + selector);
		boolean tag = selector.charAt(0) == '#';
		String body = tag ? selector.substring(1) : selector;
		int colon = body.indexOf(':');
		return new PresentationTargetSelector(
			selector,
			tag,
			PresentationPattern.compile(body.substring(0, colon)),
			PresentationPattern.compile(body.substring(colon + 1)));
	}

	public static boolean isValid(String selector) {
		if (selector == null || selector.isEmpty()) return false;
		if (selector.length() > MAX_LENGTH) return false;
		boolean tag = selector.charAt(0) == '#';
		String body = tag ? selector.substring(1) : selector;
		int colon = body.indexOf(':');
		if (colon < 1 || colon == body.length() - 1 || body.indexOf(':', colon + 1) >= 0) return false;
		for (int i = 0; i < body.length(); i++) {
			if (i == colon) continue;
			char c = body.charAt(i);
			boolean allowed = c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
				|| c == '_' || c == '-' || c == '.' || c == '*'
				|| i > colon && c == '/';
			if (!allowed) return false;
		}
		return true;
	}

	public String selector() {
		return selector;
	}

	public boolean isTag() {
		return tag;
	}

	/** True only for plain selectors and a valid, matching registry ID. */
	public boolean matchesRegistryId(String registryId) {
		return !tag && isRegistryId(registryId) && matchesBody(registryId);
	}

	/** True only for tag selectors and a valid, matching tag ID. */
	public boolean matchesTag(String tagId) {
		return tag && isRegistryId(tagId) && matchesBody(tagId);
	}

	/** Plain selectors match the registry ID; tag selectors match any provided tag ID. */
	public boolean matches(String registryId, Set<String> tagIds) {
		if (matchesRegistryId(registryId)) return true;
		if (!tag || tagIds == null) return false;
		for (String tagId : tagIds) {
			if (matchesTag(tagId)) return true;
		}
		return false;
	}

	private boolean matchesBody(String id) {
		int colon = id.indexOf(':');
		return namespace.matches(id, 0, colon) && path.matches(id, colon + 1, id.length());
	}

	private static boolean isRegistryId(String id) {
		if (id == null) return false;
		int colon = id.indexOf(':');
		if (colon < 1 || colon == id.length() - 1 || id.indexOf(':', colon + 1) >= 0) return false;
		for (int i = 0; i < id.length(); i++) {
			if (i == colon) continue;
			char c = id.charAt(i);
			boolean allowed = c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
				|| c == '_' || c == '-' || c == '.'
				|| i > colon && c == '/';
			if (!allowed) return false;
		}
		return true;
	}

	@Override
	public int compareTo(PresentationTargetSelector other) {
		return selector.compareTo(other.selector);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof PresentationTargetSelector candidate && selector.equals(candidate.selector);
	}

	@Override
	public int hashCode() {
		return selector.hashCode();
	}

	@Override
	public String toString() {
		return selector;
	}
}
