package nx.pingwheel.common.interaction;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import nx.pingwheel.common.resolve.BlockEntityClassification;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.EntityLocalGeometryMetadata;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.math.EntityLocalHit;
import nx.pingwheel.common.math.RaycastSelection;

/**
 * Converts a vanilla {@link Level} + {@link HitResult} pair into a frozen
 * {@link TargetSnapshot} on the common side.
 *
 * <p>This adapter imports no client-only Minecraft types, so it can live in the
 * common source set. It must be invoked on the game/client thread because it
 * reads the level's block state; the token guard later used by
 * {@link PingCaptureCoordinator} is thread-safe regardless of the thread that
 * produces the snapshot.
 *
 * <p>Behavior:
 * <ul>
 *   <li>{@link EntityHitResult} -> entity snapshot keyed by the canonical
 *       entity locator, carrying the canonical entity type id as match context
 *       (or no type when the registry key is unexpectedly absent, so the
 *       generic entity type can still match);</li>
 *   <li>{@link BlockHitResult} -> block snapshot keyed by dimension id +
 *       position + block registry id, independent of {@code BlockState}
 *       properties, carrying the {@code EntityBlock} classification so the
 *       {@code entity_block} target type can outrank the generic block, plus
 *       the capture-only face from the actual block-hit direction;</li>
 *   <li>unavailable/unloaded block data (including distant async hits that
 *       report as {@code MISS}) -> degrade to a location snapshot at the hit
 *       location rather than guessing a block identity or a face;</li>
 *   <li>{@code MISS} / anything else -> location snapshot at the hit
 *       location.</li>
 * </ul>
 */
public final class MinecraftTargetSnapshotFactory {

	private MinecraftTargetSnapshotFactory() {}

	/**
	 * Builds a snapshot from a level and the current hit result.
	 *
	 * <p>Must be called on the game/client thread (see class javadoc).
	 */
	public static TargetSnapshot from(Level level, HitResult hitResult) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(hitResult, "hitResult");

		String dimensionId = level.dimension().location().toString();

		if (hitResult.getType() == HitResult.Type.BLOCK) {
			var blockPos = ((BlockHitResult) hitResult).getBlockPos();

			if (level.isLoaded(blockPos)) {
				var block = level.getBlockState(blockPos).getBlock();
				var blockKey = BuiltInRegistries.BLOCK.getKey(block);

				// Air is never a meaningful ping target, so treat it like unavailable
				// data and degrade to a location instead of emitting an air block target.
				if (blockKey != null && block != Blocks.AIR) {
					return fromHitResult(
						dimensionId,
						hitResult,
						Optional.of(blockKey.toString()),
						BlockEntityClassification.hasBlockEntity(block));
				}
			}
		}

		return fromHitResult(dimensionId, hitResult, Optional.empty(), false);
	}

	/**
	 * Package-private level-free seam that mirrors {@link #from(Level, HitResult)}
	 * after the level lookup, so focused tests can exercise hit-type dispatch,
	 * face retention and fallbacks without a mutable level. The public level
	 * overload resolves the same ordinary block data and delegates here.
	 *
	 * <p>A present registry id means an actual {@code BLOCK} hit whose ordinary
	 * block was resolved; only then is the hit direction copied as the captured
	 * face. A synthetic {@code MISS} direction, an unavailable block, an entity
	 * or a plain location never produces a face.
	 */
	static TargetSnapshot fromHitResult(
		String dimensionId,
		HitResult hitResult,
		Optional<String> ordinaryBlockRegistryId,
		boolean ordinaryBlockHasBlockEntity
	) {
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(hitResult, "hitResult");
		Objects.requireNonNull(ordinaryBlockRegistryId, "ordinaryBlockRegistryId");

		return switch (hitResult.getType()) {
			case ENTITY -> entitySnapshot(dimensionId, (EntityHitResult) hitResult);
			case BLOCK -> ordinaryBlockRegistryId
				.map(registryId -> blockSnapshot(
					dimensionId, (BlockHitResult) hitResult, registryId, ordinaryBlockHasBlockEntity))
				.orElseGet(() -> locationSnapshot(
					dimensionId, hitResult.getLocation().x, hitResult.getLocation().y, hitResult.getLocation().z));
			case MISS -> locationSnapshot(
				dimensionId, hitResult.getLocation().x, hitResult.getLocation().y, hitResult.getLocation().z);
		};
	}

	/**
	 * Converts a detailed raycast selection while retaining local geometry only
	 * when its final entity hit is the same entity snapshot target.
	 */
	public static TargetSnapshot from(Level level, RaycastSelection selection) {
		Objects.requireNonNull(selection, "selection");
		TargetSnapshot snapshot = from(level, selection.hitResult());
		return retainLocalGeometry(snapshot, selection);
	}

	/** Additive selector adapter; legacy factory callers keep their previous values. */
	public static TargetSnapshot fromCandidateSelection(Level level, RaycastSelection selection) {
		TargetSnapshot snapshot = from(level, selection);
		return withNativeCandidateHit(snapshot, selection.hitResult());
	}

	/** Additive final-hit adapter, including a guarded DH completion's actual hit point. */
	public static TargetSnapshot fromCandidateHitResult(Level level, HitResult hitResult) {
		return withNativeCandidateHit(from(level, hitResult), hitResult);
	}

	/** Level-free counterpart after a positive loaded-block registry lookup. */
	static TargetSnapshot fromCandidateHitResult(String dimensionId, HitResult hitResult,
		Optional<String> ordinaryBlockRegistryId, boolean ordinaryBlockHasBlockEntity) {
		return withNativeCandidateHit(fromHitResult(dimensionId, hitResult, ordinaryBlockRegistryId,
			ordinaryBlockHasBlockEntity), hitResult);
	}

	/** Entity candidate adapter without any world lookup; canonicalization uses the shared adapter. */
	public static TargetSnapshot fromEntityCandidateSelection(String dimensionId, RaycastSelection selection) {
		return withNativeCandidateHit(fromEntitySelection(dimensionId, selection), selection.hitResult());
	}

	private static TargetSnapshot withNativeCandidateHit(TargetSnapshot snapshot, HitResult hit) {
		var point = hit.getLocation();
		return snapshot.withCandidateHit(new nx.pingwheel.common.interaction.candidate.CandidateHit(
			new WorldVector(point.x, point.y, point.z),
			nx.pingwheel.common.interaction.candidate.CaptureEquivalenceKey.nativeTarget(snapshot.target())));
	}

	/**
	 * Package-private entity-only seam for focused tests of the detailed bridge
	 * without constructing a mutable client level. The public level overload
	 * uses the same retention function after creating its canonical snapshot.
	 */
	static TargetSnapshot fromEntitySelection(String dimensionId, RaycastSelection selection) {
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(selection, "selection");

		if (!(selection.hitResult() instanceof EntityHitResult entityHitResult)) {
			throw new IllegalArgumentException("an entity selection is required");
		}

		return retainLocalGeometry(entitySnapshot(dimensionId, entityHitResult), selection);
	}

	private static TargetSnapshot retainLocalGeometry(TargetSnapshot snapshot, RaycastSelection selection) {

		if (selection.entityLocalHit().isEmpty()
			|| !(selection.hitResult() instanceof EntityHitResult entityHitResult)
			|| !(snapshot.target() instanceof nx.pingwheel.common.domain.Target.EntityTarget)) {
			return snapshot;
		}

		EntityLocalHit localHit = selection.entityLocalHit().orElseThrow();

		if (!localHit.belongsTo(entityHitResult.getEntity())) {
			return snapshot;
		}

		var geometry = localHit.localGeometryHit();
		EntityLocalGeometryMetadata metadata = new EntityLocalGeometryMetadata(
			localHit.sourceId(),
			geometry.kind(),
			geometry.localPos().getX(),
			geometry.localPos().getY(),
			geometry.localPos().getZ(),
			geometry.blockRegistryId(),
			geometry.fluidRegistryId(),
			new WorldVector(geometry.localPoint().x, geometry.localPoint().y, geometry.localPoint().z),
			new WorldVector(selection.hitResult().getLocation().x,
				selection.hitResult().getLocation().y,
				selection.hitResult().getLocation().z));

		return new TargetSnapshot(
			snapshot.target(), snapshot.matchContext(), snapshot.entityCaptureMetadata(), Optional.of(metadata));
	}

	private static TargetSnapshot entitySnapshot(String dimensionId, EntityHitResult hitResult) {
		var captured = MinecraftEntityTargetAdapter.capture(hitResult.getEntity());

		if (captured.entityTypeId().isEmpty()) {
			return TargetSnapshotFactory.entity(
				dimensionId, captured.locator(), captured.metadata());
		}

		return TargetSnapshotFactory.entity(
			dimensionId,
			captured.locator(),
			captured.entityTypeId().orElseThrow(),
			captured.metadata());
	}

	private static TargetSnapshot blockSnapshot(
		String dimensionId,
		BlockHitResult hitResult,
		String blockRegistryId,
		boolean hasBlockEntity
	) {
		var blockPos = hitResult.getBlockPos();
		if (hitResult instanceof nx.pingwheel.common.interaction.candidate.UnobservedFaceBlockHitResult) {
			return TargetSnapshotFactory.block(dimensionId, blockPos.getX(), blockPos.getY(), blockPos.getZ(),
				blockRegistryId, hasBlockEntity);
		}

		return TargetSnapshotFactory.block(
			dimensionId,
			blockPos.getX(),
			blockPos.getY(),
			blockPos.getZ(),
			blockRegistryId,
			hasBlockEntity,
			blockFaceOf(hitResult.getDirection()));
	}

	private static BlockFace blockFaceOf(Direction direction) {
		return switch (direction) {
			case DOWN -> BlockFace.DOWN;
			case UP -> BlockFace.UP;
			case NORTH -> BlockFace.NORTH;
			case SOUTH -> BlockFace.SOUTH;
			case WEST -> BlockFace.WEST;
			case EAST -> BlockFace.EAST;
		};
	}

	private static TargetSnapshot locationSnapshot(String dimensionId, double x, double y, double z) {
		return TargetSnapshotFactory.location(dimensionId, x, y, z);
	}
}
