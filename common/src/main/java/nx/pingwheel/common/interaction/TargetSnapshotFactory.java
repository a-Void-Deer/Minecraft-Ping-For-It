package nx.pingwheel.common.interaction;

import java.util.UUID;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;

/**
 * A pure-JDK factory that builds {@link TargetSnapshot}s from extracted,
 * already-decoded primitives.
 *
 * <p>This factory keeps the capture layer independent of Minecraft classes:
 * adapters (such as {@link MinecraftTargetSnapshotFactory}) extract stable
 * identity fields on the game thread and hand them here as plain values. All
 * validation is delegated to the existing {@link Target} /
 * {@link TargetMatchContext} constructors.
 *
 * <ul>
 *   <li>an entity snapshot carries dimension id + UUID identity, plus an
 *       optional entity type id used only as transient match context;</li>
 *   <li>a block snapshot carries dimension id + position + block registry id
 *       and deliberately excludes {@code BlockState};</li>
 *   <li>a location snapshot carries dimension id + finite coordinates.</li>
 * </ul>
 */
public final class TargetSnapshotFactory {

	private TargetSnapshotFactory() {}

	/**
	 * An entity snapshot with an explicit (non-blank) entity type id.
	 */
	public static TargetSnapshot entity(String dimensionId, UUID entityId, String entityTypeId) {
		return new TargetSnapshot(
			new Target.EntityTarget(dimensionId, entityId),
			TargetMatchContext.entityType(entityTypeId));
	}

	/**
	 * An entity snapshot with no entity type id, so only the generic entity
	 * target type is expected to match during resolution.
	 */
	public static TargetSnapshot entity(String dimensionId, UUID entityId) {
		return new TargetSnapshot(
			new Target.EntityTarget(dimensionId, entityId),
			TargetMatchContext.none());
	}

	/**
	 * A block snapshot carrying dimension, position, and block registry id, but
	 * no {@code BlockState}.
	 */
	public static TargetSnapshot block(String dimensionId, int x, int y, int z, String blockRegistryId) {
		return new TargetSnapshot(
			new Target.BlockTarget(dimensionId, x, y, z, blockRegistryId),
			TargetMatchContext.none());
	}

	/**
	 * A pure location snapshot carrying dimension and finite coordinates.
	 */
	public static TargetSnapshot location(String dimensionId, double x, double y, double z) {
		return new TargetSnapshot(
			new Target.LocationTarget(dimensionId, x, y, z),
			TargetMatchContext.none());
	}
}
