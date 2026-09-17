package nx.pingwheel.common.math;

import java.util.Objects;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

import static nx.pingwheel.common.CommonClient.Game;

public class Raycast {
	private Raycast() {}

	/**
	 * Traces using an explicitly supplied origin and direction. This method is
	 * used by ping capture so the exact press-time ray is shared by the vanilla
	 * hit test and every fallback path instead of being reconstructed from a
	 * later camera state.
	 */
	public static HitResult traceDirectional(
		Vec3 rayStartVec,
		Vec3 direction,
		double maxDistance,
		boolean passThroughTransparentBlocks
	) {
		return traceDirectionalDetailed(
			rayStartVec,
			direction,
			maxDistance,
			RaycastPolicy.from(passThroughTransparentBlocks, false, false))
			.map(RaycastSelection::hitResult)
			.orElse(null);
	}

	/**
	 * Traces using a press-time target-selection policy.  The policy is applied
	 * before nearest-entity distance comparison, while spectator filtering is
	 * retained for every mode.
	 */
	public static HitResult traceDirectional(
		Vec3 rayStartVec,
		Vec3 direction,
		double maxDistance,
		RaycastPolicy policy
	) {
		return traceDirectionalDetailed(rayStartVec, direction, maxDistance, policy)
			.map(RaycastSelection::hitResult)
			.orElse(null);
	}

	/**
	 * Detailed alternative to the legacy hit-result overloads. It takes one
	 * immutable local-geometry registry snapshot per ray and lets an owning
	 * callback refine each candidate before global nearest-hit selection.
	 */
	public static java.util.Optional<RaycastSelection> traceDirectionalDetailed(
		Vec3 rayStartVec,
		Vec3 direction,
		double maxDistance,
		RaycastPolicy policy
	) {
		Objects.requireNonNull(rayStartVec, "rayStartVec");
		Objects.requireNonNull(direction, "direction");
		Objects.requireNonNull(policy, "policy");

		var cameraEntity = Game.cameraEntity;

		if (cameraEntity == null || cameraEntity.level() == null) {
			return java.util.Optional.empty();
		}

		var rayEndVec = rayStartVec.add(direction.scale(maxDistance));

		if (!isFinite(rayStartVec) || !isFinite(rayEndVec)) {
			return java.util.Optional.empty();
		}

		var boundingBox = cameraEntity
			.getBoundingBox()
			.expandTowards(direction.scale(maxDistance))
			.inflate(1.0, 1.0, 1.0);
		var fluidMode = switch (policy.fluidMode()) {
			case NONE -> ClipContext.Fluid.NONE;
			case ANY -> ClipContext.Fluid.ANY;
		};

		var blockHitResult = cameraEntity.level().clip(
			new ClipContext(
				rayStartVec,
				rayEndVec,
				policy.blockMode() == RaycastPolicy.BlockMode.OUTLINE
					? ClipContext.Block.OUTLINE
					: ClipContext.Block.VISUAL,
				fluidMode,
				cameraEntity)
		);

		CollisionContext collisionContext = CollisionContext.of(cameraEntity);
		EntityLocalRaycastRequest request = new EntityLocalRaycastRequest(
			rayStartVec,
			rayEndVec,
			policy,
			1.0f,
			collisionContext,
			cameraEntity.position());
		EntityLocalGeometryRegistry.Snapshot localGeometrySnapshot = EntityLocalGeometryRegistry.INSTANCE.snapshot();
		EntitySelection entitySelection = traceEntity(
			cameraEntity,
			rayStartVec,
			rayEndVec,
			boundingBox,
			policy,
			localGeometrySnapshot,
			request);

		return selectNearestWorldOrEntity(
			rayStartVec,
			blockHitResult,
			entitySelection == null
				? java.util.Optional.empty()
				: java.util.Optional.of(new RaycastSelection(entitySelection.hitResult(), entitySelection.localHit())));
	}

	/** Package-private production seam for the final world/entity comparison. */
	static java.util.Optional<RaycastSelection> selectNearestWorldOrEntity(
		Vec3 rayStart,
		HitResult worldHit,
		java.util.Optional<RaycastSelection> entitySelection
	) {
		Objects.requireNonNull(rayStart, "rayStart");
		Objects.requireNonNull(worldHit, "worldHit");
		Objects.requireNonNull(entitySelection, "entitySelection");

		if (entitySelection.isEmpty()
			|| rayStart.distanceToSqr(worldHit.getLocation())
				< rayStart.distanceToSqr(entitySelection.orElseThrow().hitResult().getLocation())) {
			return java.util.Optional.of(RaycastSelection.withoutLocalGeometry(worldHit));
		}

		return entitySelection;
	}

	private static EntitySelection traceEntity(Entity entity,
										   Vec3 min,
										   Vec3 max,
										   AABB box,
										   RaycastPolicy policy,
									   EntityLocalGeometryRegistry.Snapshot localGeometrySnapshot,
									   EntityLocalRaycastRequest request) {
		return traceEntityCandidates(
			entity.level().getEntities(entity, box, targetEntity ->
				!targetEntity.isSpectator()
					&& (policy.includeIgnoredEntities()
						|| !EntitySelectionBlacklist.INSTANCE.isBlacklisted(targetEntity))),
			min,
			max,
			policy,
			localGeometrySnapshot,
			request).map(selection -> new EntitySelection(
			(EntityHitResult) selection.hitResult(), selection.entityLocalHit())).orElse(null);
	}

	/**
	 * The common per-candidate selection path used by the live level trace.
	 * Keeping the candidate iteration here lets focused tests exercise precise
	 * owner handling without constructing a full mutable {@code Level}.
	 */
	static java.util.Optional<RaycastSelection> traceEntityCandidates(
		Iterable<Entity> candidates,
		Vec3 min,
		Vec3 max,
		RaycastPolicy policy,
		EntityLocalGeometryRegistry.Snapshot localGeometrySnapshot,
		EntityLocalRaycastRequest request
	) {
		Objects.requireNonNull(candidates, "candidates");
		Objects.requireNonNull(min, "min");
		Objects.requireNonNull(max, "max");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(localGeometrySnapshot, "localGeometrySnapshot");
		Objects.requireNonNull(request, "request");

		var minDist = min.distanceToSqr(max);
		EntitySelection minHitResult = null;

		for (Entity ent : candidates) {
			if (ent == null || ent.isSpectator()
				|| (!policy.includeIgnoredEntities() && EntitySelectionBlacklist.INSTANCE.isBlacklisted(ent))) {
				continue;
			}

			EntityLocalGeometryRegistry.Claim owner = localGeometrySnapshot.firstOwner(ent);

			if (owner != null) {
				var claimedBroadBox = ent.getBoundingBox()
					.inflate(ent.getPickRadius())
					.inflate(0.25);

				// A claimed entity may start inside the ray, where AABB.clip does not
				// provide the entry signal needed by some Minecraft versions. Do not
				// invoke a potentially expensive owner scan unless its broad geometry
				// is actually relevant, but never use this unit/AABB check as the
				// narrowphase itself: protruding native local shapes remain eligible.
				if (claimedBroadBox.clip(min, max).isEmpty() && !claimedBroadBox.contains(min)) {
					continue;
				}

				EntityLocalGeometryResult localResult = owner.trace(ent, request);

				if (localResult.outcome() != EntityLocalGeometryResult.Outcome.HIT) {
					continue;
				}

				LocalGeometryHit localGeometryHit = localResult.localHit().orElseThrow();
				double t = localGeometryHit.t();

				if (!isValidNormalizedParameter(t) || !request.hasNonDegenerateSegment()) {
					continue;
				}

				Vec3 hitPos = request.pointAt(t);

				if (!isFinite(hitPos)) {
					continue;
				}

				EntityHitResult hitResult = new EntityHitResult(ent, hitPos);
				double hitDist = min.distanceToSqr(hitPos);

				if (minDist > hitDist) {
					minDist = hitDist;
					minHitResult = new EntitySelection(
						hitResult,
						java.util.Optional.of(new EntityLocalHit(ent, owner.sourceId(), localGeometryHit)));
				}

				continue;
			}

			var targetBoundingBox = ent.getBoundingBox()
				.inflate(ent.getPickRadius())
				.inflate(0.25);
			var hitPos = targetBoundingBox.clip(min, max);

			if (hitPos.isEmpty()) {
				continue;
			}

			var hitResult = new EntityHitResult(ent, hitPos.get());
			var hitDist = min.distanceToSqr(hitResult.getLocation());

			if (minDist > hitDist) {
				minDist = hitDist;
				minHitResult = new EntitySelection(hitResult, java.util.Optional.empty());
			}
		}

		return minHitResult == null
			? java.util.Optional.empty()
			: java.util.Optional.of(new RaycastSelection(minHitResult.hitResult(), minHitResult.localHit()));
	}

	private static boolean isValidNormalizedParameter(double t) {
		return Double.isFinite(t) && t >= 0.0 && t < 1.0;
	}

	private static boolean isFinite(Vec3 value) {
		return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
	}

	private record EntitySelection(EntityHitResult hitResult, java.util.Optional<EntityLocalHit> localHit) {}
}
