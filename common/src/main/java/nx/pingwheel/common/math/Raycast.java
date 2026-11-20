package nx.pingwheel.common.math;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import com.google.common.collect.Iterators;
import org.jetbrains.annotations.Nullable;
import java.util.function.Supplier;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.MinecraftTargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.*;

import static nx.pingwheel.common.CommonClient.Game;

public class Raycast {
	private static final DiagnosticChannel ENTITY_SCAN_FAILURE =
		DiagnosticChannel.interval("precise-entity-scan", Duration.ofSeconds(10));
	private static final DiagnosticChannel BLOCK_SCAN_FAILURE =
		DiagnosticChannel.interval("precise-block-scan", Duration.ofSeconds(10));

	private Raycast() {}

	/** Caps the represented segment without extending an already in-range ray. */
	public static Vec3 boundedEndpoint(Vec3 origin, Vec3 direction, double maxDistance) {
		Vec3 end = origin.add(direction.scale(maxDistance));
		double rangeSquared = maxDistance * maxDistance;
		if (!(origin.distanceToSqr(end) > rangeSquared) || !isFinite(end) || maxDistance < 0.0) return end;

		// Correct an overlong look vector first. Even a unit vector can still
		// overshoot after its displacement is rounded into world coordinates.
		double length = Math.hypot(Math.hypot(direction.x, direction.y), direction.z);
		double high = Math.min(maxDistance, maxDistance / length);
		end = origin.add(direction.scale(high));
		if (origin.distanceToSqr(end) <= rangeSquared) return end;

		// Find the last in-range represented point along this ray. For positive
		// parameters, each rounded coordinate's distance from the eye is monotone.
		double low = 0.0;
		Vec3 inside = origin;
		while (true) {
			double middle = low + (high - low) * 0.5;
			if (middle <= low || middle >= high) return inside;
			Vec3 point = origin.add(direction.scale(middle));
			if (origin.distanceToSqr(point) <= rangeSquared) {
				low = middle;
				inside = point;
			} else {
				high = middle;
			}
		}
	}

	/**
	 * Traces using an explicitly supplied origin and direction. This method is
	 * used by ping capture so the exact press-time ray is shared by the vanilla
	 * hit test and every fallback path instead of being reconstructed from a
	 * later camera state.
	 */
	public static @Nullable HitResult traceDirectional(
		Vec3 rayStartVec,
		Vec3 direction,
		double maxDistance,
		boolean markTransparentBlocks
	) {
		return traceDirectionalDetailed(
			rayStartVec,
			direction,
			maxDistance,
			RaycastPolicy.from(markTransparentBlocks, false, false))
			.map(RaycastSelection::hitResult)
			.orElse(null);
	}

	/**
	 * Traces using a press-time target-selection policy.  The policy is applied
	 * before nearest-entity distance comparison, while spectator filtering is
	 * retained for every mode.
	 */
	public static @Nullable HitResult traceDirectional(
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

		@Nullable Entity cameraEntity = Game == null ? null : Game.cameraEntity;

		if (cameraEntity == null || cameraEntity.level() == null) {
			return java.util.Optional.empty();
		}

		var rayEndVec = boundedEndpoint(rayStartVec, direction, maxDistance);

		if (!isFinite(rayStartVec) || !isFinite(rayEndVec)) {
			return java.util.Optional.empty();
		}

		return traceDirectionalDetailed(cameraEntity, rayStartVec, rayEndVec, direction, policy,
			EntityLocalGeometryRegistry.INSTANCE.snapshot());
	}

	private static Optional<RaycastSelection> traceDirectionalDetailed(
		Entity cameraEntity, Vec3 rayStartVec, Vec3 rayEndVec, Vec3 direction,
		RaycastPolicy policy, EntityLocalGeometryRegistry.Snapshot localGeometrySnapshot
	) {
		try (@Nullable PickingFrameContext frames = cameraEntity.level() instanceof ClientLevel client
			? PickingFrameContext.open(client) : null) {
			return traceDirectionalDetailed(cameraEntity, rayStartVec, rayEndVec, direction, policy,
				localGeometrySnapshot, frames);
		}
	}

	private static Optional<RaycastSelection> traceDirectionalDetailed(Entity cameraEntity, Vec3 rayStartVec,
		Vec3 rayEndVec, Vec3 direction, RaycastPolicy policy,
		EntityLocalGeometryRegistry.Snapshot localGeometrySnapshot, @Nullable PickingFrameContext frames) {
		var boundingBox = cameraEntity
			.getBoundingBox()
			.expandTowards(rayEndVec.subtract(rayStartVec))
			.inflate(1.0, 1.0, 1.0);
		var clip = PingClipContext.create(rayStartVec, rayEndVec, policy, cameraEntity);
		RaycastSelection worldSelection = frames == null
			? RaycastSelection.withoutLocalGeometry(cameraEntity.level().clip(clip)) : frames.clip(clip);

		CollisionContext collisionContext = CollisionContext.of(cameraEntity);
		EntityLocalRaycastRequest request = new EntityLocalRaycastRequest(
			rayStartVec,
			rayEndVec,
			policy,
			1.0f,
			collisionContext,
			cameraEntity.position(), frames == null
				? nx.pingwheel.common.integration.sable.SableEntityFrames::logical : frames::entityFrame);
		@Nullable EntitySelection entitySelection = traceEntity(
			cameraEntity,
			rayStartVec,
			rayEndVec,
			direction,
			boundingBox,
			policy,
			localGeometrySnapshot,
			request);

		return selectNearestWorldOrEntity(
			rayStartVec,
			worldSelection,
			entitySelection == null
				? java.util.Optional.empty()
				: java.util.Optional.of(new RaycastSelection(entitySelection.hitResult(), entitySelection.localHit())));
	}

	/**
	 * Captures the native reference and supplemental candidates on the game thread. Use
	 * {@code ordinarySelection()} in the existing native/Sable/DH
	 * pipeline, then carry the optional {@code candidates()} to coordinator.complete(token,
	 * finalSnapshot, pressRay, candidates). Do not call this API from release or a DH callback.
	 * The native reference and supplemental scans retain their separate frozen ranges.
	 */
	public static Optional<SelectorTrace> traceDirectionalCandidates(
		InteractionToken token, CapturedRay ray, double nativeDistance, double pingDistance,
		RaycastPolicy policy, CandidateBlockCapture blockCapture
	) {
		@Nullable Entity camera = Game == null ? null : Game.cameraEntity;
		if (camera == null || camera.level() == null) return Optional.empty();
		return traceDirectionalCandidates(camera, token, ray, nativeDistance, pingDistance, policy, blockCapture);
	}

	/** Production native mapping plus independent transformed Sable supplementation. */
	public static Optional<SelectorTrace> traceDirectionalCandidates(
		InteractionToken token, CapturedRay ray, double nativeDistance, double pingDistance,
		RaycastPolicy policy
	) {
		return traceDirectionalCandidates(token, ray, nativeDistance, pingDistance, policy,
			nx.pingwheel.common.integration.sable.client.SableClientProvider.candidateBlocks());
	}

	/** Explicit game-thread camera adapter, useful to integrations without a second client-state owner. */
	public static Optional<SelectorTrace> traceDirectionalCandidates(
		Entity camera, InteractionToken token, CapturedRay ray, double nativeDistance, double pingDistance,
		RaycastPolicy policy, CandidateBlockCapture blockCapture
	) {
		Objects.requireNonNull(camera, "camera");
		Objects.requireNonNull(token, "token");
		Objects.requireNonNull(ray, "ray");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(blockCapture, "blockCapture");
		if (camera.level() == null) return Optional.empty();
		if (!Double.isFinite(nativeDistance) || !Double.isFinite(pingDistance)
			|| nativeDistance < 0 || pingDistance < nativeDistance) {
			throw new IllegalArgumentException("native distance must be within frozen Ping range");
		}
		Vec3 start = vec(ray.origin());
		Vec3 direction = vec(ray.direction());
		Vec3 nativeEnd = boundedEndpoint(start, direction, nativeDistance);
		// The supplemental scan keeps its separate full Ping-distance extent.
		Vec3 supplementalDirection = direction.scale(1.0 / Math.hypot(Math.hypot(direction.x, direction.y), direction.z));
		Vec3 end = boundedEndpoint(start, supplementalDirection, pingDistance);
		if (!isFinite(nativeEnd) || !isFinite(end)) return Optional.empty();
		var owners = EntityLocalGeometryRegistry.INSTANCE.snapshot();
		try (@Nullable PickingFrameContext frames = camera.level() instanceof ClientLevel client
			? PickingFrameContext.open(client) : null) {
			return traceDirectionalCandidates(camera, token, ray, nativeDistance, pingDistance, policy,
				blockCapture, owners, frames, start, direction, nativeEnd, end);
		}
	}

	private static Optional<SelectorTrace> traceDirectionalCandidates(Entity camera, InteractionToken token,
		CapturedRay ray, double nativeDistance, double pingDistance, RaycastPolicy policy,
		CandidateBlockCapture blockCapture, EntityLocalGeometryRegistry.Snapshot owners,
		@Nullable PickingFrameContext frames, Vec3 start, Vec3 direction, Vec3 nativeEnd, Vec3 end) {
		RaycastSelection ordinary = traceDirectionalDetailed(camera, start, nativeEnd, direction, policy, owners, frames)
			.orElseThrow();
		CandidateCollector collector = new CandidateCollector();
		EnumSet<PreciseTargetType> certified = EnumSet.noneOf(PreciseTargetType.class);
		Level level = camera.level();
		CollisionContext collisionContext = CollisionContext.of(camera);
		EntityLocalRaycastRequest request = new EntityLocalRaycastRequest(start, end, policy,
			1.0f, collisionContext, camera.position(), frames == null
				? nx.pingwheel.common.integration.sable.SableEntityFrames::logical : frames::entityFrame);
		AABB bounds = new AABB(start, end).inflate(1);
		try {
			if (level instanceof ClientLevel clientLevel) {
				// Use the lazy native lookup, not a filtered getEntities query or section walk.
				collectVisibleEntityCandidates(clientLevel.entitiesForRendering(), bounds, start, end,
					policy, owners, request, collector, level.dimension().location().toString(), camera, direction);
				certified.add(PreciseTargetType.DROPPED_ITEM);
				certified.add(PreciseTargetType.ENTITY);
			}
		} catch (RuntimeException | LinkageError failure) {
			// Discard certification, not the independently completed ordinary trace.
			reportEntityScanIncomplete(token, () -> level.dimension().location().toString(),
				nativeDistance, pingDistance, policy, failure);
		}
		try {
			boolean[] providerComplete = {true};
			ClipContext context = PingClipContext.create(start, end, policy, collisionContext);
			LoadedCandidateBlockView view = new LoadedCandidateBlockView(level);
			boolean complete = NativeBlockCandidateScan.scan(view, level::isLoaded,
				context, hit -> {
					CandidateBlockCapture.Result result = blockCapture.capture(level, hit, start, end);
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
				collisionContext, camera.position(), collector);
			if (complete && providerComplete[0] && view.complete() && externalComplete) {
				certified.add(PreciseTargetType.ENTITY_BLOCK);
				certified.add(PreciseTargetType.BLOCK);
			}
		} catch (RuntimeException | LinkageError failure) {
			// A partial native/provider scan cannot certify a nearest block.
			reportBlockScanIncomplete(token, () -> level.dimension().location().toString(),
				nativeDistance, pingDistance, policy, failure);
		}
		return Optional.of(selectorTrace(token, ray, pingDistance, ordinary, collector, certified));
	}

	static SelectorTrace selectorTrace(InteractionToken token, CapturedRay ray, double pingDistance,
		RaycastSelection ordinary, CandidateCollector collector, java.util.Set<PreciseTargetType> certified) {
		return new SelectorTrace(ordinary, ordinary.worldPoint().map(point ->
			new FrozenCandidateAcquisition(token, ray, pingDistance, world(point), collector.evidence(), certified)));
	}

	/** Contains transient ordinary HitResult only; retain candidates(), never this object across callbacks. */
	public record SelectorTrace(RaycastSelection ordinarySelection, Optional<FrozenCandidateAcquisition> candidates) {}

	static void reportEntityScanIncomplete(InteractionToken token, Supplier<String> dimensionId,
		double nativeDistance, double pingDistance, RaycastPolicy policy, Throwable failure) {
		Diagnostics.client().atNormal().rateLimited(ENTITY_SCAN_FAILURE).failure(org.apache.logging.log4j.Level.WARN,
			"precise/entity-scan", "entity-slots-incomplete",
			() -> "token=" + token.sequence() + " dimension=" + dimensionId.get()
				+ " nativeRange=" + nativeDistance + " pingRange=" + pingDistance
				+ " policy=" + policy, failure);
	}

	static void reportBlockScanIncomplete(InteractionToken token, Supplier<String> dimensionId,
		double nativeDistance, double pingDistance, RaycastPolicy policy, Throwable failure) {
		Diagnostics.client().atNormal().rateLimited(BLOCK_SCAN_FAILURE).failure(org.apache.logging.log4j.Level.WARN,
			"precise/block-scan", "block-slots-incomplete",
			() -> "token=" + token.sequence() + " dimension=" + dimensionId.get()
				+ " nativeRange=" + nativeDistance + " pingRange=" + pingDistance
				+ " policy=" + policy, failure);
	}

	static void collectEntityCandidates(Iterable<@Nullable Entity> entities, Vec3 start, Vec3 end,
		RaycastPolicy policy, EntityLocalGeometryRegistry.Snapshot owners, EntityLocalRaycastRequest request,
		CandidateCollector collector, String dimensionId, @Nullable Entity excluded
	) {
		Vec3 direction = end.subtract(start);
		for (@Nullable Entity entity : entities) {
			if (entity == null || entity == excluded) continue;
			collectEntityCandidate(entity, start, end, direction, policy, owners, request, collector, dimensionId);
		}
	}

	/** Same native lookup enumeration as production, before bounding-box or selection filters. */
	static void collectVisibleEntityCandidates(Iterable<@Nullable Entity> entities, AABB bounds, Vec3 start, Vec3 end,
		RaycastPolicy policy, EntityLocalGeometryRegistry.Snapshot owners, EntityLocalRaycastRequest request,
		CandidateCollector collector, String dimensionId, @Nullable Entity excluded
	) {
		collectVisibleEntityCandidates(entities, bounds, start, end, policy, owners, request, collector, dimensionId,
			excluded, end.subtract(start));
	}

	private static void collectVisibleEntityCandidates(Iterable<@Nullable Entity> entities, AABB bounds,
		Vec3 start, Vec3 end, RaycastPolicy policy, EntityLocalGeometryRegistry.Snapshot owners,
		EntityLocalRaycastRequest request, CandidateCollector collector, String dimensionId,
		@Nullable Entity excluded, Vec3 direction
	) {
		NativeEntityCandidateScan.scan(entities, bounds, excluded, entity -> loadedExact(owners, entity), entity ->
			collectEntityCandidate(entity, start, end, direction, policy, owners, request, collector, dimensionId));
	}

	private static void collectEntityCandidate(Entity entity, Vec3 start, Vec3 end, Vec3 direction, RaycastPolicy policy,
		EntityLocalGeometryRegistry.Snapshot owners, EntityLocalRaycastRequest request,
		CandidateCollector collector, String dimensionId) {
		// Reuses the ordinary ownership/non-hit/filter/endpoint path, without coarse revival.
		traceEntityCandidates(List.of(entity), start, end, direction, policy, owners, request).ifPresent(hit -> {
			var snapshot = MinecraftTargetSnapshotFactory.fromEntityCandidateSelection(dimensionId, hit);
			collector.add(new CandidateEvidence(snapshot, start.distanceTo(hit.hitResult().getLocation())));
		});
	}

	private static Vec3 vec(WorldVector value) { return new Vec3(value.x(), value.y(), value.z()); }
	private static WorldVector world(Vec3 value) { return new WorldVector(value.x, value.y, value.z); }

	/** Package-private production seam for the final world/entity comparison. */
	static java.util.Optional<RaycastSelection> selectNearestWorldOrEntity(
		Vec3 rayStart,
		HitResult worldHit,
		java.util.Optional<RaycastSelection> entitySelection
	) {
		return selectNearestWorldOrEntity(rayStart, RaycastSelection.withoutLocalGeometry(worldHit), entitySelection);
	}

	static Optional<RaycastSelection> selectNearestWorldOrEntity(Vec3 rayStart,
		RaycastSelection worldSelection, Optional<RaycastSelection> entitySelection) {
		Objects.requireNonNull(rayStart, "rayStart");
		Objects.requireNonNull(worldSelection, "worldSelection");
		Objects.requireNonNull(entitySelection, "entitySelection");

		// An unproven native block cannot be compared with an entity. Preserve the native
		// result rather than turning optional frame failure into a fabricated entity winner.
		if (entitySelection.isEmpty() || worldSelection.worldPoint().isEmpty()
			|| worldSelection.worldPoint().filter(point -> rayStart.distanceToSqr(point)
				< rayStart.distanceToSqr(entitySelection.orElseThrow().worldPoint().orElseThrow())).isPresent()) {
			return java.util.Optional.of(worldSelection);
		}

		return entitySelection;
	}

	private static @Nullable EntitySelection traceEntity(Entity entity,
										   Vec3 min,
										   Vec3 max,
										   Vec3 direction,
										   AABB box,
										   RaycastPolicy policy,
									   EntityLocalGeometryRegistry.Snapshot localGeometrySnapshot,
									   EntityLocalRaycastRequest request) {
		Iterable<Entity> nativeCandidates = entity.level().getEntities(entity, box, targetEntity ->
				!targetEntity.isSpectator()
					&& (policy.includeIgnoredEntities()
						|| !EntitySelectionBlacklist.INSTANCE.isBlacklisted(targetEntity)));
		Iterable<Entity> loaded = entity.level() instanceof ClientLevel client ? client.entitiesForRendering() : List.of();
		return traceEntityCandidates(
			ordinaryCandidates(nativeCandidates, loaded, entity, localGeometrySnapshot, policy),
			min,
			max,
			direction,
			policy,
			localGeometrySnapshot,
			request).map(selection -> new EntitySelection(
			(EntityHitResult) selection.hitResult(), selection.entityLocalHit())).orElse(null);
	}

	/** Preserve the native query order, then admit only exact owners from the lazy raw loaded view. */
	static Iterable<Entity> ordinaryCandidates(Iterable<Entity> nativeCandidates, Iterable<Entity> loaded,
		Entity excluded, EntityLocalGeometryRegistry.Snapshot owners, RaycastPolicy policy) {
		Iterable<Entity> supplemental = owners.hasLoadedExactOwners() ? loaded : List.of();
		return () -> {
			Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			return Iterators.filter(Iterators.concat(nativeCandidates.iterator(),
				Iterators.filter(supplemental.iterator(), entity -> entity != null && entity != excluded
					&& !entity.isSpectator() && (policy.includeIgnoredEntities()
						|| !EntitySelectionBlacklist.INSTANCE.isBlacklisted(entity)) && loadedExact(owners, entity))),
				entity -> entity != null && seen.add(entity));
		};
	}

	private static boolean loadedExact(EntityLocalGeometryRegistry.Snapshot owners, Entity entity) {
		@Nullable EntityLocalGeometryRegistry.Claim owner = owners.firstOwner(entity);
		return owner != null && owner.admission() == EntityLocalGeometryRegistry.CandidateAdmission.LOADED_EXACT;
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
		return traceEntityCandidates(candidates, min, max, max.subtract(min), policy, localGeometrySnapshot, request);
	}

	private static Optional<RaycastSelection> traceEntityCandidates(
		Iterable<Entity> candidates,
		Vec3 min,
		Vec3 max,
		Vec3 direction,
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
		@Nullable EntitySelection minHitResult = null;

		for (Entity ent : candidates) {
			if (ent == null || ent.isSpectator()
				|| (!policy.includeIgnoredEntities() && EntitySelectionBlacklist.INSTANCE.isBlacklisted(ent))) {
				continue;
			}

			@Nullable EntityLocalGeometryRegistry.Claim owner = localGeometrySnapshot.firstOwner(ent);

			if (owner != null) {
				// A claimed entity may start inside the ray, where AABB.clip does not
				// provide the entry signal needed by some Minecraft versions. Do not
				// invoke a potentially expensive owner scan unless its broad geometry
				// is actually relevant, but never use this unit/AABB check as the
				// narrowphase itself: protruding native local shapes remain eligible.
				if (owner.admission() == EntityLocalGeometryRegistry.CandidateAdmission.NATIVE_BOUNDS) {
					var claimedBroadBox = ent.getBoundingBox().inflate(ent.getPickRadius()).inflate(0.25);
					if (claimedBroadBox.clip(min, max).isEmpty() && !claimedBroadBox.contains(min)) continue;
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
				if (minDist <= 0.0 || !acceptsInsideEntityContact(targetBoundingBox, min, direction)) continue;
				hitPos = Optional.of(min);
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

	private static boolean acceptsInsideEntityContact(AABB box, Vec3 start, Vec3 direction) {
		// Faces still use the native clip rule; AABB.contains includes the min faces only.
		if (!(box.minX < start.x && start.x < box.maxX
			&& box.minY < start.y && start.y < box.maxY
			&& box.minZ < start.z && start.z < box.maxZ)) return false;

		Vec3 toCenter = box.getCenter().subtract(start);
		if (toCenter.x == 0.0 && toCenter.y == 0.0 && toCenter.z == 0.0) return true; // Cone apex.
		if (!isFinite(direction) || !isFinite(toCenter)) return false;

		// cos(60°) = 1/2: require a positive projection and compare (2 * dot)^2 with |d|²|c|².
		double twiceProjection = 2.0 * direction.dot(toCenter);
		double projectionSquared = twiceProjection * twiceProjection;
		double directionSquared = direction.lengthSqr();
		double centerSquared = toCenter.lengthSqr();
		double lengthProduct = directionSquared * centerSquared;
		if (directionSquared >= Double.MIN_NORMAL && centerSquared >= Double.MIN_NORMAL
			&& lengthProduct >= Double.MIN_NORMAL && Double.isFinite(lengthProduct) && Double.isFinite(projectionSquared)) {
			// With normal norm sums/product, the three-term dot has absolute error < 4u*|d||c|
			// and the norm product relative error < 22u (u = 2^-53). Including squaring and
			// subtraction, 64 ulps of the larger squared term conservatively cover both sides.
			double difference = projectionSquared - lengthProduct;
			double roundoff = 64 * Math.ulp(Math.max(projectionSquared, lengthProduct));
			if (Math.abs(difference) > roundoff) return twiceProjection > 0.0 && difference > 0.0;
		}
		return exactInsideEntityCone(direction, toCenter);
	}

	private static boolean exactInsideEntityCone(Vec3 direction, Vec3 toCenter) {
		// Preserve the exact binary inputs, including a center one representable step off the edge.
		var dx = new BigDecimal(direction.x);
		var dy = new BigDecimal(direction.y);
		var dz = new BigDecimal(direction.z);
		var cx = new BigDecimal(toCenter.x);
		var cy = new BigDecimal(toCenter.y);
		var cz = new BigDecimal(toCenter.z);
		var projection = dx.multiply(cx).add(dy.multiply(cy)).add(dz.multiply(cz));
		return projection.signum() > 0 && projection.pow(2).multiply(BigDecimal.valueOf(4)).compareTo(
			exactLengthSquared(dx, dy, dz).multiply(exactLengthSquared(cx, cy, cz))) >= 0;
	}

	private static BigDecimal exactLengthSquared(BigDecimal x, BigDecimal y, BigDecimal z) {
		return x.pow(2).add(y.pow(2)).add(z.pow(2));
	}

	private static boolean isValidNormalizedParameter(double t) {
		return Double.isFinite(t) && t >= 0.0 && t < 1.0;
	}

	private static boolean isFinite(Vec3 value) {
		return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
	}

	private record EntitySelection(EntityHitResult hitResult, java.util.Optional<EntityLocalHit> localHit) {}
}
