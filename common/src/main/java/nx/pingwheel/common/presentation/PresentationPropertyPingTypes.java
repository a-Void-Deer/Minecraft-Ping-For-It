package nx.pingwheel.common.presentation;

import nx.pingwheel.common.domain.PingTypeCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Code-defined allowed ping types for property assignments. The default set is
 * {@code [attention, danger]}; ordered overrides first match a target selector
 * against the actual registry ID and tag IDs, then remove and re-add ping
 * types. A later override can re-add a type an earlier one removed.
 */
public final class PresentationPropertyPingTypes {
	/** Matches the presentation wire bound for a ping type identifier. */
	public static final int MAX_PING_TYPE_LENGTH = 256;
	public static final List<String> DEFAULT_ALLOWED = List.of("attention", "danger");

	private static final PresentationPropertyPingTypes BUILT_IN = new PresentationPropertyPingTypes(List.of(
		new Override(PresentationTargetSelector.of("#c:chests"), List.of("danger"), List.of("request"))
	));

	private final List<Override> overrides;
	private final List<String> defaultAllowed;

	public PresentationPropertyPingTypes(List<Override> overrides) {
		this(overrides, DEFAULT_ALLOWED);
	}

	public PresentationPropertyPingTypes(List<Override> overrides, List<String> defaultAllowed) {
		Objects.requireNonNull(overrides, "overrides");
		Objects.requireNonNull(defaultAllowed, "defaultAllowed");
		List<Override> copied = List.copyOf(overrides);
		List<String> defaults = List.copyOf(defaultAllowed);
		validateTypes(defaults);
		for (Override override : copied) {
			if (override == null) throw new IllegalArgumentException("null property ping type override");
			validateTypes(override.remove());
			validateTypes(override.add());
			for (String removed : override.remove()) {
				if (override.add().contains(removed))
					throw new IllegalArgumentException("property ping type override removes and adds the same type");
			}
		}
		this.overrides = copied;
		this.defaultAllowed = defaults;
	}

	/** The confirmed built-in defaults and the ordered {@code #c:chests} override. */
	public static PresentationPropertyPingTypes builtIn() {
		return BUILT_IN;
	}

	public List<String> defaultAllowed() {
		return defaultAllowed;
	}

	public List<Override> overrides() {
		return overrides;
	}

	/** The ordered effective set for a target context. */
	public List<String> effective(String registryId, Set<String> tagIds) {
		List<String> result = new ArrayList<>(defaultAllowed);
		for (Override override : overrides) {
			if (!override.selector().matches(registryId, tagIds)) continue;
			result.removeAll(override.remove());
			for (String added : override.add()) {
				if (!result.contains(added)) result.add(added);
			}
		}
		return List.copyOf(result);
	}

	public boolean allows(String pingTypeId, String registryId, Set<String> tagIds) {
		return effective(registryId, tagIds).contains(pingTypeId);
	}

	/** A non-blank, bounded identifier present in the code-defined ping type catalog. */
	public static boolean isKnownPingTypeId(String pingTypeId) {
		return pingTypeId != null
			&& !pingTypeId.isBlank()
			&& pingTypeId.length() <= MAX_PING_TYPE_LENGTH
			&& PingTypeCatalog.builtIn().findById(pingTypeId).isPresent();
	}

	private static void validateTypes(List<String> pingTypeIds) {
		Objects.requireNonNull(pingTypeIds, "pingTypeIds");
		Set<String> seen = new HashSet<>();
		for (String id : pingTypeIds) {
			if (!isKnownPingTypeId(id)) throw new IllegalArgumentException("unknown property ping type: " + id);
			if (!seen.add(id)) throw new IllegalArgumentException("duplicate property ping type: " + id);
		}
	}

	/** One ordered override: matching targets remove and then add the listed types. */
	public record Override(PresentationTargetSelector selector, List<String> remove, List<String> add) {
		public Override {
			Objects.requireNonNull(selector, "selector");
			remove = List.copyOf(Objects.requireNonNull(remove, "remove"));
			add = List.copyOf(Objects.requireNonNull(add, "add"));
		}
	}
}
