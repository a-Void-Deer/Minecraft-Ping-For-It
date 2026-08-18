package nx.pingwheel.common.interaction;

import java.util.UUID;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.EntityCaptureMetadata;
import nx.pingwheel.common.domain.EntityLocator;
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
	 *   <li>an entity snapshot carries dimension id + an explicit entity locator, plus an
 *       optional entity type id used only as transient match context;</li>
 *   <li>a block snapshot carries dimension id + position + block registry id
 *       and deliberately excludes {@code BlockState}; its match context
 *       carries the transient {@code EntityBlock} classification when the
 *       caller can derive it, so the {@code entity_block} target type can
 *       outrank the generic block;</li>
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
			new Target.EntityTarget(dimensionId, EntityLocator.uuid(entityId)),
			TargetMatchContext.entityType(entityTypeId));
	}

	/** An entity snapshot with an explicit locator and no type id. */
	public static TargetSnapshot entity(String dimensionId, EntityLocator locator) {
		return new TargetSnapshot(
			new Target.EntityTarget(dimensionId, locator),
			TargetMatchContext.none());
	}

	/**
	 * An entity snapshot carrying capture-only identity metadata for safe debug
	 * logging. The metadata is never serialized or used for resolution.
	 */
	public static TargetSnapshot entity(
		String dimensionId,
		EntityLocator locator,
		String entityTypeId,
		EntityCaptureMetadata metadata
	) {
		return new TargetSnapshot(
			new Target.EntityTarget(dimensionId, locator),
			TargetMatchContext.entityType(entityTypeId),
			java.util.Optional.of(metadata));
	}

	/** Entity snapshot with capture-only metadata and no type id. */
	public static TargetSnapshot entity(
		String dimensionId,
		EntityLocator locator,
		EntityCaptureMetadata metadata
	) {
		return new TargetSnapshot(
			new Target.EntityTarget(dimensionId, locator),
			TargetMatchContext.none(),
			java.util.Optional.of(metadata));
	}

	/**
	 * An entity snapshot with no entity type id, so only the generic entity
	 * target type is expected to match during resolution.
	 */
	public static TargetSnapshot entity(String dimensionId, UUID entityId) {
		return entity(dimensionId, EntityLocator.uuid(entityId));
	}

	/**
	 * A block snapshot carrying dimension, position, and block registry id, but
	 * no {@code BlockState}. The block classification is unknown, so resolution
	 * fails soft to the generic {@code block} target type.
	 */
	public static TargetSnapshot block(String dimensionId, int x, int y, int z, String blockRegistryId) {
		return new TargetSnapshot(
			new Target.BlockTarget(dimensionId, x, y, z, blockRegistryId),
			TargetMatchContext.none());
	}

	/**
	 * A block snapshot carrying dimension, position, block registry id, and the
	 * transient block classification (whether the block actually owns a
	 * Minecraft {@code BlockEntity}); no {@code BlockState}. Callers must
	 * derive the classification from their own game state; it is never
	 * client-supplied over the wire.
	 */
	public static TargetSnapshot block(
		String dimensionId, int x, int y, int z, String blockRegistryId, boolean hasBlockEntity
	) {
		return new TargetSnapshot(
			new Target.BlockTarget(dimensionId, x, y, z, blockRegistryId),
			TargetMatchContext.blockEntityBlock(hasBlockEntity));
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
