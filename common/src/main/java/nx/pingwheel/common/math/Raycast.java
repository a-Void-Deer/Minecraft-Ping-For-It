package nx.pingwheel.common.math;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

import java.util.function.Predicate;

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
		boolean hitTranslucent
	) {
		var cameraEntity = Game.cameraEntity;

		if (cameraEntity == null || cameraEntity.level() == null) {
			return null;
		}

		var rayEndVec = rayStartVec.add(direction.scale(maxDistance));
		var boundingBox = cameraEntity
			.getBoundingBox()
			.expandTowards(direction.scale(maxDistance))
			.inflate(1.0, 1.0, 1.0);

		var blockHitResult = cameraEntity.level().clip(
			new ClipContext(
				rayStartVec,
				rayEndVec,
				hitTranslucent ? ClipContext.Block.OUTLINE : ClipContext.Block.VISUAL,
				hitTranslucent ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE,
				cameraEntity)
		);

		var entityHitResult = traceEntity(
			cameraEntity,
			rayStartVec,
			rayEndVec,
			boundingBox,
			(targetEntity) -> !targetEntity.isSpectator());

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
											   Predicate<Entity> predicate) {
		var minDist = min.distanceToSqr(max);
		EntityHitResult minHitResult = null;

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
