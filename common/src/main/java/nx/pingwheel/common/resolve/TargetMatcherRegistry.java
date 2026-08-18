package nx.pingwheel.common.resolve;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable binding from {@link nx.pingwheel.common.domain.TargetType} id to
 * a {@link TargetMatcher}.
 *
 * <p>Binding is by stable string id; the resolver later looks a matcher up per
 * target type and skips types that have no matcher bound. Binding the same id
 * twice is rejected so the mapping stays deterministic. Lookup is
 * {@link Optional}-based rather than null-returning.
 */
public final class TargetMatcherRegistry {

	private final Map<String, TargetMatcher> byTargetTypeId;

	private TargetMatcherRegistry(Map<String, TargetMatcher> byTargetTypeId) {
		this.byTargetTypeId = byTargetTypeId;
	}

	/**
	 * Creates a new builder for a matcher registry.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Looks up the matcher bound to {@code targetTypeId}; empty if none.
	 */
	public Optional<TargetMatcher> find(String targetTypeId) {
		Objects.requireNonNull(targetTypeId, "targetTypeId");

		return Optional.ofNullable(byTargetTypeId.get(targetTypeId));
	}

	/**
	 * Mutable builder that rejects duplicate and invalid bindings before
	 * producing the immutable registry.
	 */
	public static final class Builder {

		private final Map<String, TargetMatcher> bindings = new LinkedHashMap<>();

		private Builder() {}

		/**
		 * Binds {@code matcher} to {@code targetTypeId}. Rejects a null/blank
		 * id, a null matcher, or a duplicate id.
		 */
		public Builder bind(String targetTypeId, TargetMatcher matcher) {
			Objects.requireNonNull(targetTypeId, "targetTypeId");
			Objects.requireNonNull(matcher, "matcher");

			if (targetTypeId.isBlank()) {
				throw new IllegalArgumentException("targetTypeId must not be blank");
			}

			if (bindings.putIfAbsent(targetTypeId, matcher) != null) {
				throw new IllegalArgumentException("duplicate matcher binding for target type id: " + targetTypeId);
			}

			return this;
		}

		/**
		 * Produces the immutable registry.
		 */
		public TargetMatcherRegistry build() {
			// Lookup-only map: resolution/declaration ordering is carried by the
			// resolver's target type list, never by this map's iteration order.
			return new TargetMatcherRegistry(Map.copyOf(bindings));
		}
	}
}
