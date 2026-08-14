package nx.pingwheel.common.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The concrete object captured when the ping key is pressed.
 *
 * <p>A target identity is deliberately frozen at capture time:
 * <ul>
 *   <li>an entity is identified by dimension id + UUID only (it carries no
 *       mutable position, so movement/teleport within a dimension never changes
 *       identity);</li>
 *   <li>a block is identified by dimension + position + block registry id and
 *       deliberately excludes {@code BlockState};</li>
 *   <li>a pure location is the lowest-priority fallback.</li>
 * </ul>
 *
 * <p>Dimension identity is a stable, non-blank {@link String} resource
 * identifier such as {@code minecraft:overworld}. Later adapters derive it via
 * {@code ResourceKey<Level>.location().toString()}. Using the string identifier
 * (rather than a bare numeric hash code) avoids a hash-collision behavior from
 * becoming the authoritative dimension identity.
 *
 * <p>Only JDK types are used here; there are no {@code net.minecraft}
 * references, so this hierarchy can be tested without a game client.
 */
public sealed interface Target permits Target.EntityTarget, Target.BlockTarget, Target.LocationTarget {

	String dimensionId();

	TargetKind kind();

	record EntityTarget(String dimensionId, UUID entityId) implements Target {

		public EntityTarget {
			Target.requireDimensionId(dimensionId);
			Objects.requireNonNull(entityId, "entityId");
		}

		@Override
		public TargetKind kind() {
			return TargetKind.ENTITY;
		}
	}

	record BlockTarget(String dimensionId, int x, int y, int z, String blockRegistryId) implements Target {

		public BlockTarget {
			Target.requireDimensionId(dimensionId);
			Objects.requireNonNull(blockRegistryId, "blockRegistryId");

			if (blockRegistryId.isBlank()) {
				throw new IllegalArgumentException("blockRegistryId must not be blank");
			}
		}

		@Override
		public TargetKind kind() {
			return TargetKind.BLOCK;
		}
	}

	record LocationTarget(String dimensionId, double x, double y, double z) implements Target {

		public LocationTarget {
			Target.requireDimensionId(dimensionId);
			Target.requireFinite("x", x);
			Target.requireFinite("y", y);
			Target.requireFinite("z", z);
		}

		@Override
		public TargetKind kind() {
			return TargetKind.LOCATION;
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
