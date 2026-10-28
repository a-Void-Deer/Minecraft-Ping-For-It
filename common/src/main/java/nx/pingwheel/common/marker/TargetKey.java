package nx.pingwheel.common.marker;

import java.util.Objects;
import java.util.UUID;

import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * A complete, frozen identity for a concrete ping target.
 *
 * <p>The physical default mirrors {@link Target}; creation-only authoritative
 * grouping can instead freeze a supported composite key. It is owned by the marker layer so the
 * server-side same-target winner comparison has one stable, self-contained key
 * type. Every variant carries the full target identity:
 * <ul>
	 *   <li>{@link EntityKey}: dimension + {@link EntityLocator} (stable across movement and
	 *       same-dimension teleports, and deliberately position-free);</li>
	 *   <li>{@link BlockKey}: dimension + exact integer position + block registry
	 *       id (so a different block type at the same position is a different
	 *       key, while a {@code BlockState}-only change is not);</li>
	 *   <li>{@link DoubleChestKey}: dimension + chest registry + canonical adjacent pair;</li>
	 *   <li>{@link VaultKey}: dimension + Vault registry + verified controller position;</li>
	 *   <li>{@link ExternalBlockKey}: dimension + provider + stable target id +
	 *       expected block registry id, without the provider locator or
	 *       classification;</li>
	 *   <li>{@link LocationKey}: dimension + exact finite coordinates.</li>
 * </ul>
 *
 * <p>Only JDK types are used here; there are no {@code net.minecraft}
 * references, so keys can be constructed and compared in tests.
 */
@NotNullByDefault
public sealed interface TargetKey permits TargetKey.EntityKey, TargetKey.BlockKey, TargetKey.DoubleChestKey,
	TargetKey.VaultKey, TargetKey.ExternalBlockKey, TargetKey.LocationKey {

	String dimensionId();

	/**
	 * Derives the exact physical key for a captured {@link Target}, never grouping.
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

	/**
	 * Checks the detached carrier, not live topology. Only the creation resolver
	 * can establish Vault membership; clients must never infer authority from it.
	 */
	static TargetKey requireCompatible(Target target, TargetKey key) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(key, "targetKey");
		boolean compatible = switch (key) {
			case DoubleChestKey chest -> target instanceof Target.BlockTarget block
				&& chest.dimensionId().equals(block.dimensionId())
				&& chest.blockRegistryId().equals(block.blockRegistryId())
				&& chest.contains(block.x(), block.y(), block.z());
			case VaultKey vault -> target instanceof Target.BlockTarget block
				&& vault.dimensionId().equals(block.dimensionId())
				&& vault.blockRegistryId().equals(block.blockRegistryId());
			default -> key.equals(from(target));
		};
		if (!compatible) throw new IllegalArgumentException("targetKey does not match committed target");
		return key;
	}

	/** Both members, strictly lexicographic xyz ordered; topology is creation-time evidence. */
	@NotNullByDefault
	record DoubleChestKey(String dimensionId, int firstX, int firstY, int firstZ,
		int secondX, int secondY, int secondZ, String blockRegistryId) implements TargetKey {
		public DoubleChestKey {
			requireDimensionId(dimensionId);
			Objects.requireNonNull(blockRegistryId, "blockRegistryId");
			if (!blockRegistryId.equals("minecraft:chest") && !blockRegistryId.equals("minecraft:trapped_chest"))
				throw new IllegalArgumentException("double chest requires an ordinary or trapped chest");
			long dx = (long) secondX - firstX, dz = (long) secondZ - firstZ;
			if (firstY != secondY || Math.abs(dx) + Math.abs(dz) != 1
				|| !(firstX < secondX || firstX == secondX && firstZ < secondZ))
				throw new IllegalArgumentException("double chest members must be canonical horizontal neighbors");
		}
		public boolean contains(int x, int y, int z) {
			return x == firstX && y == firstY && z == firstZ || x == secondX && y == secondY && z == secondZ;
		}
	}

	/** Verified controller identity only; extents and members are not identity. */
	@NotNullByDefault
	record VaultKey(String dimensionId, int controllerX, int controllerY, int controllerZ,
		String blockRegistryId) implements TargetKey {
		public VaultKey {
			requireDimensionId(dimensionId);
			if (!Objects.requireNonNull(blockRegistryId, "blockRegistryId").equals("create:item_vault"))
				throw new IllegalArgumentException("Vault key requires create:item_vault");
		}
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
