package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.MinecraftTargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.*;

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

		return traceDirectionalDetailed(cameraEntity, rayStartVec, direction, maxDistance, policy,
			EntityLocalGeometryRegistry.INSTANCE.snapshot());
	}

	private static Optional<RaycastSelection> traceDirectionalDetailed(
		Entity cameraEntity, Vec3 rayStartVec, Vec3 direction, double maxDistance,
		RaycastPolicy policy, EntityLocalGeometryRegistry.Snapshot localGeometrySnapshot
	) {
		var rayEndVec = rayStartVec.add(direction.scale(maxDistance));

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

	/**
	 * Additive press-start API. Use {@code ordinarySelection()} in the existing native/Sable/DH
	 * pipeline, then carry {@code candidates()} to coordinator.complete(token, finalSnapshot,
	 * pressRay, Optional.of(candidates)). Do not call this API from release or a DH callback.
	 * Ordinary tracing retains its native distance and is not charged to supplemental budgets.
	 */
	public static Optional<SelectorTrace> traceDirectionalCandidates(
		InteractionToken token, CapturedRay ray, double nativeDistance, double pingDistance,
		RaycastPolicy policy, CandidateWorkLimits limits, CandidateBlockCapture blockCapture
	) {
		Entity camera = Game.cameraEntity;
		if (camera == null || camera.level() == null) return Optional.empty();
		return traceDirectionalCandidates(camera, token, ray, nativeDistance, pingDistance, policy, limits, blockCapture);
	}

	/** Production native mapping plus independent transformed Sable supplementation. */
	public static Optional<SelectorTrace> traceDirectionalCandidates(
		InteractionToken token, CapturedRay ray, double nativeDistance, double pingDistance,
		RaycastPolicy policy, CandidateWorkLimits limits
	) {
		return traceDirectionalCandidates(token, ray, nativeDistance, pingDistance, policy, limits,
			nx.pingwheel.common.integration.sable.client.SableClientProvider.candidateBlocks());
	}

	/** Explicit game-thread camera adapter, useful to integrations without a second client-state owner. */
	public static Optional<SelectorTrace> traceDirectionalCandidates(
		Entity camera, InteractionToken token, CapturedRay ray, double nativeDistance, double pingDistance,
		RaycastPolicy policy, CandidateWorkLimits limits, CandidateBlockCapture blockCapture
	) {
		Objects.requireNonNull(camera, "camera");
		Objects.requireNonNull(token, "token");
		Objects.requireNonNull(ray, "ray");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(limits, "limits");
		Objects.requireNonNull(blockCapture, "blockCapture");
		if (camera.level() == null) return Optional.empty();
		if (!Double.isFinite(nativeDistance) || !Double.isFinite(pingDistance)
			|| nativeDistance < 0 || pingDistance < nativeDistance) {
			throw new IllegalArgumentException("native distance must be within frozen Ping range");
		}
		Vec3 start = vec(ray.origin());
		Vec3 direction = vec(ray.direction());
		// CapturedRay deliberately preserves raw direction. Only this supplemental
		// extent is normalized so a non-unit input cannot extend the Ping-distance cap.
		Vec3 supplementalDirection = direction.scale(1.0 / Math.hypot(Math.hypot(direction.x, direction.y), direction.z));
		Vec3 end = start.add(supplementalDirection.scale(pingDistance));
		if (!isFinite(end)) return Optional.empty();
		var owners = EntityLocalGeometryRegistry.INSTANCE.snapshot();
		RaycastSelection ordinary = traceDirectionalDetailed(camera, start, direction, nativeDistance, policy, owners)
			.orElseThrow();
		CandidateCollector collector = new CandidateCollector();
		CandidateWorkBudget budget = new CandidateWorkBudget(limits);
		EnumSet<PreciseTargetType> certified = EnumSet.noneOf(PreciseTargetType.class);
		Level level = camera.level();
		CollisionContext collisionContext = CollisionContext.of(camera);
		EntityLocalRaycastRequest request = new EntityLocalRaycastRequest(start, end, policy,
			1.0f, collisionContext, camera.position());
		AABB bounds = new AABB(start, end).inflate(1);
		try {
			// ClientLevel's public native view is a lazy EntityLookup.byId.values view.
			// There is no filtered getEntities or section walk before our visit counter.
			boolean complete = level instanceof ClientLevel clientLevel
				&& collectVisibleEntityCandidates(clientLevel.entitiesForRendering(), bounds, start, end,
					policy, owners, request, budget, collector, level.dimension().location().toString(), camera);
			if (complete) {
				certified.add(PreciseTargetType.DROPPED_ITEM);
				certified.add(PreciseTargetType.ENTITY);
			}
		} catch (RuntimeException | LinkageError failure) {
			// Discard certification, not the independently completed ordinary trace.
		}
		try {
			boolean[] providerComplete = {true};
			ClipContext context = new ClipContext(start, end,
				policy.blockMode() == RaycastPolicy.BlockMode.OUTLINE ? ClipContext.Block.OUTLINE : ClipContext.Block.VISUAL,
				policy.fluidMode() == RaycastPolicy.FluidMode.ANY ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE,
				collisionContext);
			LoadedCandidateBlockView view = new LoadedCandidateBlockView(level, budget);
			boolean complete = NativeBlockCandidateScan.scan(view, level::isLoaded,
				context, budget, hit -> {
					CandidateBlockCapture.Result result = blockCapture.capture(level, hit, start, end, budget);
					providerComplete[0] &= result.complete();
					result.snapshot().filter(snapshot -> !(snapshot.target() instanceof nx.pingwheel.common.domain.Target.LocationTarget))
						.ifPresent(snapshot -> {
							if (snapshot.candidateHit().isEmpty()) {
								providerComplete[0] = false;
								return;
							}
							double distance = FrozenCandidateAcquisition.distance(ray.origin(), snapshot.candidateHit().orElseThrow().worldHit());
							if (distance <= pingDistance) collector.add(new CandidateEvidence(snapshot, distance));
						});
				});
			boolean externalComplete = blockCapture.collectSupplemental(level, start, end, policy,
				collisionContext, camera.position(), budget, collector);
			if (complete && providerComplete[0] && view.complete() && externalComplete) {
				certified.add(PreciseTargetType.ENTITY_BLOCK);
				certified.add(PreciseTargetType.BLOCK);
			}
		} catch (RuntimeException | LinkageError failure) {
			// A partial native/provider scan cannot certify a nearest block.
		}
		return Optional.of(new SelectorTrace(ordinary, new FrozenCandidateAcquisition(token, ray, pingDistance,
			world(ordinary.hitResult().getLocation()), collector.evidence(), certified)));
	}

	/** Contains transient ordinary HitResult only; retain candidates(), never this object across callbacks. */
	public record SelectorTrace(RaycastSelection ordinarySelection, FrozenCandidateAcquisition candidates) {}

	static boolean collectEntityCandidates(Iterable<Entity> entities, Vec3 start, Vec3 end,
		RaycastPolicy policy, EntityLocalGeometryRegistry.Snapshot owners, EntityLocalRaycastRequest request,
		CandidateWorkBudget budget, CandidateCollector collector, String dimensionId, Entity excluded
	) {
		for (Entity entity : entities) {
			if (!budget.visitEntity()) return false;
			if (entity == null || entity == excluded) continue;
			// Reuses exactly the ordinary ownership/non-hit/filter/endpoint path, no coarse revival.
			Optional<RaycastSelection> selection = traceEntityCandidates(List.of(entity), start, end, policy, owners, request);
			selection.ifPresent(hit -> {
				var snapshot = MinecraftTargetSnapshotFactory.fromEntityCandidateSelection(dimensionId, hit);
				collector.add(new CandidateEvidence(snapshot, start.distanceTo(hit.hitResult().getLocation())));
			});
		}
		return true;
	}

	/** Same native lookup enumeration as production, before bounding-box or selection filters. */
	static boolean collectVisibleEntityCandidates(Iterable<Entity> entities, AABB bounds, Vec3 start, Vec3 end,
		RaycastPolicy policy, EntityLocalGeometryRegistry.Snapshot owners, EntityLocalRaycastRequest request,
		CandidateWorkBudget budget, CandidateCollector collector, String dimensionId, Entity excluded
	) {
		return NativeEntityCandidateScan.scan(entities, bounds, excluded, budget, entity ->
			traceEntityCandidates(List.of(entity), start, end, policy, owners, request).ifPresent(hit -> {
				var snapshot = MinecraftTargetSnapshotFactory.fromEntityCandidateSelection(dimensionId, hit);
				collector.add(new CandidateEvidence(snapshot, start.distanceTo(hit.hitResult().getLocation())));
			}));
	}

	private static Vec3 vec(WorldVector value) { return new Vec3(value.x(), value.y(), value.z()); }
	private static WorldVector world(Vec3 value) { return new WorldVector(value.x, value.y, value.z); }

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
