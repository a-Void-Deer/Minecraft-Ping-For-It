package nx.pingwheel.common.registry;

import java.util.Objects;

/**
 * An immutable reference to a single optional registry entry, identified by a
 * stable registry id and a stable entry id.
 *
 * <p>No lookup happens at construction time and no optional-mod class is
 * referenced here. Presence is only resolved when {@link #isPresent} is called
 * with an injected {@link RegistryLookup}, so merely creating a reference
 * cannot fail when the optional content is absent.
 */
public record OptionalRegistryRef(String registryId, String entryId) {

	public OptionalRegistryRef {
		requireNonBlank(registryId, "registryId");
		requireNonBlank(entryId, "entryId");
	}

	/**
	 * Whether this referenced entry exists according to {@code lookup}.
	 * Performs the lookup only at this call site.
	 */
	public boolean isPresent(RegistryLookup lookup) {
		Objects.requireNonNull(lookup, "lookup");

		return lookup.contains(registryId, entryId);
	}

	private static String requireNonBlank(String value, String name) {
		Objects.requireNonNull(value, name);

		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}

		return value;
	}
}
