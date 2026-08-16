package nx.pingwheel.common.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Transient context carried alongside a captured {@link Target} during
 * resolution only.
 *
 * <p>It deliberately holds no identity-bearing data that participates in a
 * target's identity. The optional {@code entityTypeId} distinguishes finer
 * entity specializations (for example {@code minecraft:item}) from a generic
 * entity; the optional {@code blockHasBlockEntity} records whether the
 * captured block actually owns a Minecraft {@code BlockEntity} (for example a
 * chest or sign) so the {@code entity_block} target type can outrank the
 * generic {@code block} type. Neither value may ever be added to
 * {@link Target.EntityTarget} / {@link Target.BlockTarget} identity, and this
 * context must never be serialized or frozen into a {@link ResolvedTarget}.
 *
 * <p>The {@link Optional}s themselves must not be null, and a present
 * {@code entityTypeId} must not be blank. Only JDK types are used here, so
 * this value is testable without a game client.
 *
 * <p>Capture must populate {@code entityTypeId} when resolving an entity
 * target and {@code blockHasBlockEntity} when resolving a block target so the
 * finer specializations can match. An absent classification fails soft: only
 * the generic entity/block fallback is expected to match. The authoritative
 * server derives both values from its own game state, never from the client.
 */
public record TargetMatchContext(Optional<String> entityTypeId, Optional<Boolean> blockHasBlockEntity) {

	public TargetMatchContext {
		Objects.requireNonNull(entityTypeId, "entityTypeId");
		Objects.requireNonNull(blockHasBlockEntity, "blockHasBlockEntity");

		entityTypeId.ifPresent(value -> {
			if (value.isBlank()) {
				throw new IllegalArgumentException("entityTypeId must not be blank");
			}
		});
	}

	/**
	 * A context with no entity type or block classification information (used
	 * for pure location targets and for generic entity/block resolution).
	 */
	public static TargetMatchContext none() {
		return new TargetMatchContext(Optional.empty(), Optional.empty());
	}

	/**
	 * A context carrying an explicit, non-blank entity type id.
	 */
	public static TargetMatchContext entityType(String entityTypeId) {
		return new TargetMatchContext(Optional.of(entityTypeId), Optional.empty());
	}

	/**
	 * A context carrying an explicit block classification: whether the
	 * captured block actually owns a Minecraft {@code BlockEntity} (that is,
	 * whether the {@code entity_block} target type may match).
	 */
	public static TargetMatchContext blockEntityBlock(boolean hasBlockEntity) {
		return new TargetMatchContext(Optional.empty(), Optional.of(hasBlockEntity));
	}
}
