package nx.pingwheel.common.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Transient context carried alongside a captured {@link Target} during
 * resolution only.
 *
 * <p>It deliberately holds no identity-bearing data that participates in a
 * target's identity: the only field is an optional {@code entityTypeId} used to
 * distinguish finer entity specializations (for example {@code minecraft:item})
 * from a generic entity. That value must never be added to
 * {@link Target.EntityTarget} identity, and this context must never be
 * serialized or frozen into a {@link ResolvedTarget}.
 *
 * <p>The {@link Optional} itself must not be null, and a present value must not
 * be blank. Only JDK types are used here, so this value is testable without a
 * game client.
 *
 * <p>Phase 4 capture must populate {@code entityTypeId} when resolving an
 * entity target so finer specializations (for example {@code minecraft:item})
 * can match; when it is absent, only the generic entity fallback is expected
 * to match.
 */
public record TargetMatchContext(Optional<String> entityTypeId) {

	public TargetMatchContext {
		Objects.requireNonNull(entityTypeId, "entityTypeId");

		entityTypeId.ifPresent(value -> {
			if (value.isBlank()) {
				throw new IllegalArgumentException("entityTypeId must not be blank");
			}
		});
	}

	/**
	 * A context with no entity type information (used for block and location
	 * targets, and for generic entity resolution).
	 */
	public static TargetMatchContext none() {
		return new TargetMatchContext(Optional.empty());
	}

	/**
	 * A context carrying an explicit, non-blank entity type id.
	 */
	public static TargetMatchContext entityType(String entityTypeId) {
		return new TargetMatchContext(Optional.of(entityTypeId));
	}
}
