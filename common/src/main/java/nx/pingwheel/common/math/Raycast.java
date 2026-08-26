package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

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
		boolean selectTransparentBlocks
	) {
		return traceDirectional(
			rayStartVec,
			direction,
			maxDistance,
			RaycastPolicy.from(selectTransparentBlocks, false));
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
		Objects.requireNonNull(rayStartVec, "rayStartVec");
		Objects.requireNonNull(direction, "direction");
		Objects.requireNonNull(policy, "policy");

		var cameraEntity = Game.cameraEntity;

		if (cameraEntity == null || cameraEntity.level() == null) {
			return null;
		}

		var rayEndVec = rayStartVec.add(direction.scale(maxDistance));
		var boundingBox = cameraEntity
			.getBoundingBox()
			.expandTowards(direction.scale(maxDistance))
			.inflate(1.0, 1.0, 1.0);
		var fluidMode = switch (policy.fluidMode()) {
			case NONE -> ClipContext.Fluid.NONE;
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

		var entityHitResult = traceEntity(
			cameraEntity,
			rayStartVec,
			rayEndVec,
			boundingBox,
			policy);

		if (entityHitResult == null) {
			return blockHitResult;
		}

		if (rayStartVec.distanceToSqr(blockHitResult.getLocation()) < rayStartVec.distanceToSqr(entityHitResult.getLocation())) {
			return blockHitResult;
		}

		return entityHitResult;
	}

	private static EntityHitResult traceEntity(Entity entity,
											   Vec3 min,
											   Vec3 max,
											   AABB box,
											   RaycastPolicy policy) {
		var minDist = min.distanceToSqr(max);
		EntityHitResult minHitResult = null;

		Predicate<Entity> predicate = targetEntity ->
			!targetEntity.isSpectator()
				&& (policy.includeIgnoredEntities()
					|| !EntitySelectionBlacklist.INSTANCE.isBlacklisted(targetEntity));

		for (var ent : entity.level().getEntities(entity, box, predicate)) {
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
				minHitResult = hitResult;
			}
		}

		return minHitResult;
	}
}
