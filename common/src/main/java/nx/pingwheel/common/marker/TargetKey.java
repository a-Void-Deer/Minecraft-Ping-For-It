package nx.pingwheel.common.marker;

import java.util.Objects;
import java.util.UUID;

import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;

/**
 * A complete, frozen identity for a concrete ping target.
 *
 * <p>This mirrors {@link Target} but is owned by the marker layer so the
 * server-side same-target winner comparison has one stable, self-contained key
 * type. Every variant carries the full target identity:
 * <ul>
	 *   <li>{@link EntityKey}: dimension + {@link EntityLocator} (stable across movement and
	 *       same-dimension teleports, and deliberately position-free);</li>
	 *   <li>{@link BlockKey}: dimension + exact integer position + block registry
	 *       id (so a different block type at the same position is a different
	 *       key, while a {@code BlockState}-only change is not);</li>
	 *   <li>{@link ExternalBlockKey}: dimension + provider + stable target id +
	 *       expected block registry id, without the provider locator or
	 *       classification;</li>
	 *   <li>{@link LocationKey}: dimension + exact finite coordinates.</li>
 * </ul>
 *
 * <p>Only JDK types are used here; there are no {@code net.minecraft}
 * references, so keys can be constructed and compared in tests.
 */
public sealed interface TargetKey permits TargetKey.EntityKey, TargetKey.BlockKey, TargetKey.ExternalBlockKey, TargetKey.LocationKey {

	String dimensionId();

	/**
	 * Derives the key for a captured {@link Target}.
	 */
	static TargetKey from(Target target) {
		Objects.requireNonNull(target, "target");

		return switch (target) {
			case Target.EntityTarget entity ->
				new EntityKey(entity.dimensionId(), entity.locator());
			case Target.BlockTarget block ->
				new BlockKey(block.dimensionId(), block.x(), block.y(), block.z(), block.blockRegistryId());
			case Target.ExternalBlockTarget external -> {
				if (!external.isCommitted()) {
					throw new IllegalArgumentException("an uncommitted external target has no marker key");
				}

				yield new ExternalBlockKey(
					external.dimensionId(),
					external.providerId(), external.stableTargetId(), external.expectedBlockRegistryId());
			}
			case Target.LocationTarget location ->
				new LocationKey(location.dimensionId(), location.x(), location.y(), location.z());
		};
	}

	record EntityKey(String dimensionId, EntityLocator locator) implements TargetKey {

		public EntityKey {
			requireDimensionId(dimensionId);
			Objects.requireNonNull(locator, "locator");
		}

		/** UUID convenience constructor retained for existing marker callers. */
		public EntityKey(String dimensionId, UUID entityId) {
			this(dimensionId, EntityLocator.uuid(entityId));
		}
	}

	record BlockKey(String dimensionId, int x, int y, int z, String blockRegistryId) implements TargetKey {

		public BlockKey {
			requireDimensionId(dimensionId);
			Objects.requireNonNull(blockRegistryId, "blockRegistryId");

			if (blockRegistryId.isBlank()) {
				throw new IllegalArgumentException("blockRegistryId must not be blank");
			}
		}
	}

	/**
	 * Stable identity for a committed external block target.
	 *
	 * <p>The provider locator, world anchor, and block-entity classification are
	 * intentionally absent. A provider may update its locator without changing
	 * the marker's same-target key.
	 */
	record ExternalBlockKey(
		String dimensionId,
		String providerId,
		String stableTargetId,
		String expectedBlockRegistryId
	) implements TargetKey {

		public ExternalBlockKey {
			requireDimensionId(dimensionId);
			requireIdentifier("providerId", providerId);
			requireIdentifier("stableTargetId", stableTargetId);
			requireIdentifier("expectedBlockRegistryId", expectedBlockRegistryId);
		}

		private static String requireIdentifier(String name, String value) {
			Objects.requireNonNull(value, name);

			if (value.isBlank()) {
				throw new IllegalArgumentException(name + " must not be blank");
			}

			if (value.length() > Target.ExternalBlockTarget.MAX_IDENTIFIER_LENGTH) {
				throw new IllegalArgumentException(name + " exceeds the maximum length");
			}

			return value;
		}
	}

	record LocationKey(String dimensionId, double x, double y, double z) implements TargetKey {

		public LocationKey {
			requireDimensionId(dimensionId);
			requireFinite("x", x);
			requireFinite("y", y);
			requireFinite("z", z);
		}
	}

	/**
	 * Validates a dimension resource identifier: non-null and non-blank.
	 */
	static String requireDimensionId(String dimensionId) {
		Objects.requireNonNull(dimensionId, "dimensionId");

		if (dimensionId.isBlank()) {
			throw new IllegalArgumentException("dimensionId must not be blank");
		}

		return dimensionId;
	}

	/**
	 * Validates a coordinate value: must be finite (not NaN or +/-Infinity).
	 */
	static double requireFinite(String name, double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(name + " must be finite, got " + value);
		}

		return value;
	}
}
