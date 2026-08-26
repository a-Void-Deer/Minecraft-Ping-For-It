package nx.pingwheel.common.integration.sable.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.sable.server.SableExternalBlockLocator;
import nx.pingwheel.common.integration.sable.SableDiagnostics;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.name.TargetNameComposer;
import nx.pingwheel.common.resolve.BlockEntityClassification;

/** Direct Companion 1.6.0 calls, isolated from the common client runtime. */
final class SableClientCompanionAccess {

	private static final double RAY_EPSILON = 0.35D;

	private final SableCompanion companion;
	private final SableClientInternalAccess internal;
	private final SableDiagnostics diagnostics;

	private SableClientCompanionAccess(
		SableCompanion companion, SableClientInternalAccess internal, SableDiagnostics diagnostics
	) {
		this.companion = companion;
		this.internal = internal;
		this.diagnostics = diagnostics;
	}

	static SableClientCompanionAccess create() throws ReflectiveOperationException {
		return create(SableDiagnostics.global());
	}

	static SableClientCompanionAccess create(SableDiagnostics diagnostics)
		throws ReflectiveOperationException {
		SableClientInternalAccess internal;

		try {
			internal = SableClientInternalAccess.create();
		} catch (ReflectiveOperationException | RuntimeException failure) {
			// The stable Companion projection remains useful even when Sable's
			// internal client container shape has drifted. Candidate/render/name
			// routes fail soft below while the legacy location fallback survives.
			diagnostics.captureException(
				"provider-init",
				"internal-access-unavailable",
				failure,
				"operation", "SableClientInternalAccess.create");
			internal = null;
		} catch (LinkageError failure) {
			diagnostics.captureException(
				"provider-init",
				"internal-access-linkage-error",
				failure,
				"operation", "SableClientInternalAccess.create");
			throw failure;
		}

		try {
			return new SableClientCompanionAccess(
				SableCompanion.INSTANCE,
				internal,
				diagnostics);
		} catch (LinkageError failure) {
			diagnostics.captureException(
				"provider-init",
				"companion-linkage-error",
				failure,
				"operation", "SableCompanion.INSTANCE");
			throw failure;
		}
	}

	boolean hasInternalAccess() {
		return internal != null;
	}

	Optional<TargetSnapshot> capture(
		ClientLevel level, BlockHitResult hit, Vec3 rayStart, Vec3 rayEnd
	) throws ReflectiveOperationException {
		if (internal == null) {
			diagnostics.capture(
				"candidate-query",
				"internal-access-unavailable",
				"hit", hit,
				"ray_start", rayStart,
				"ray_end", rayEnd);
			return Optional.empty();
		}

		BlockPos localPos = hit.getBlockPos();
		Vec3 localHit = hit.getLocation();
		Vec3 ray = rayEnd.subtract(rayStart);
		double rayLengthSquared = ray.lengthSqr();

		if (rayLengthSquared <= 0.0D || !finite(localHit)) {
			diagnostics.capture(
				"capture",
				"invalid-ray-or-hit",
				"local_hit", localHit,
				"ray_start", rayStart,
				"ray_end", rayEnd,
				"ray_direction", ray,
				"ray_length_squared", rayLengthSquared);
			return Optional.empty();
		}

		UUID containingSubLevelId = containingSubLevelId(level, localHit);
		List<SubLevelCandidate> candidates = new ArrayList<>();
		BoundingBox3d rayBounds = new BoundingBox3d(rayStart, rayEnd);
		int queriedCandidates = 0;

		diagnostics.capture(
			"candidate-query",
			"start",
			"ray_bounds", rayBounds,
			"local_hit", localHit,
			"containing_sublevel_uuid", containingSubLevelId);

		for (SubLevelAccess access : companion.getAllIntersecting(level, rayBounds)) {
			queriedCandidates++;

			try {
			if (!(access instanceof ClientSubLevelAccess clientAccess)) {
				diagnostics.capture(
					"candidate-rejection",
					"unsupported-access",
					"candidate_index", queriedCandidates,
					"candidate_class", access == null ? null : access.getClass().getName());
				continue;
			}

			UUID subLevelId = clientAccess.getUniqueId();

			if (subLevelId == null) {
				diagnostics.capture(
					"candidate-rejection",
					"sublevel-id-missing",
					"candidate_index", queriedCandidates,
					"candidate_class", clientAccess.getClass().getName());
				continue;
			}

			// Companion's containing lookup is a positive plot-grid identity check
			// when it is available. It cannot distinguish an ordinary parent-world
			// block occupying the same reserved plot, so the geometric checks below
			// remain deliberately fail-soft rather than inventing an identity.
			if (containingSubLevelId != null && !containingSubLevelId.equals(subLevelId)) {
				diagnostics.capture(
					"candidate-rejection",
					"containment-mismatch",
					"candidate_index", queriedCandidates,
					"sublevel_uuid", subLevelId,
					"containing_sublevel_uuid", containingSubLevelId,
					"local_hit", localHit);
				continue;
			}

			SableClientInternalAccess.Resolution resolution =
				internal.resolve(level, subLevelId, localPos);

			if (resolution.value().isEmpty()) {
				diagnostics.capture(
					"candidate-rejection",
					resolution.failureReason(),
					"candidate_index", queriedCandidates,
					"sublevel_uuid", subLevelId,
					"local_block_pos", localPos);
				continue;
			}

			SableClientInternalAccess.ResolvedSubLevel resolved = resolution.value().orElseThrow();
			BlockState state = resolved.state();
			ResourceLocation registryId = BuiltInRegistries.BLOCK.getKey(state.getBlock());

			if (state.isAir() || state.getBlock() == Blocks.AIR) {
				diagnostics.capture(
					"candidate-rejection",
					"air-state",
					"candidate_index", queriedCandidates,
					"sublevel_uuid", subLevelId,
					"local_block_pos", localPos,
					"block_state", state);
				continue;
			}

			if (registryId == null) {
				diagnostics.capture(
					"candidate-rejection",
					"registry-resolution-failure",
					"candidate_index", queriedCandidates,
					"sublevel_uuid", subLevelId,
					"local_block_pos", localPos,
					"block_state", state);
				continue;
			}

			diagnostics.capture(
				"registry-resolution",
				"success",
				"candidate_index", queriedCandidates,
				"sublevel_uuid", subLevelId,
				"local_block_pos", localPos,
				"block_registry_id", registryId);

			Pose3dc logicalPose = clientAccess.logicalPose();

			if (logicalPose == null) {
				diagnostics.capture(
					"candidate-rejection",
					"logical-pose-missing",
					"candidate_index", queriedCandidates,
					"sublevel_uuid", subLevelId,
					"local_block_pos", localPos,
					"block_registry_id", registryId);
				continue;
			}

			Vec3 worldHit = logicalPose.transformPosition(localHit);
			RayProjection rayProjection = rayPoint(rayStart, ray, rayLengthSquared, worldHit);

			if (!rayProjection.accepted()) {
				diagnostics.capture(
					"candidate-rejection",
					rayProjection.reason(),
					"candidate_index", queriedCandidates,
					"sublevel_uuid", subLevelId,
					"local_block_pos", localPos,
					"local_hit", localHit,
					"logical_world_hit", worldHit,
					"projection", rayProjection.projection(),
					"perpendicular_distance_squared", rayProjection.perpendicularDistanceSquared());
				continue;
			}

			boolean hasBlockEntity;
			try {
				hasBlockEntity = BlockEntityClassification.hasBlockEntity(state);
			} catch (RuntimeException failure) {
				diagnostics.captureException(
					"candidate-rejection",
					"classification-resolution-failure",
					failure,
					"candidate_index", queriedCandidates,
					"sublevel_uuid", subLevelId,
					"local_block_pos", localPos,
					"block_registry_id", registryId,
					"block_state", state);
				continue;
			}

			diagnostics.capture(
				"classification",
				"resolved",
				"candidate_index", queriedCandidates,
				"sublevel_uuid", subLevelId,
				"block_registry_id", registryId,
				"block_entity", hasBlockEntity);

			SableExternalBlockLocator locator;
			try {
				locator = new SableExternalBlockLocator(subLevelId, localPos);
				locator.encode();
			} catch (RuntimeException failure) {
				diagnostics.captureException(
					"candidate-rejection",
					"provider-locator-parse-failure",
					failure,
					"candidate_index", queriedCandidates,
					"sublevel_uuid", subLevelId,
					"local_block_pos", localPos,
					"block_registry_id", registryId);
				continue;
			}

			candidates.add(new SubLevelCandidate(
				clientAccess,
				resolved,
				subLevelId,
				registryId,
				rayProjection.point(),
				worldHit,
				localHit,
				locator,
				hasBlockEntity));
			} catch (ReflectiveOperationException | RuntimeException failure) {
				diagnostics.captureException(
					"candidate-rejection",
					"candidate-exception",
					failure,
					"candidate_index", queriedCandidates,
					"candidate", access,
					"local_block_pos", localPos,
					"local_hit", localHit);
			} catch (LinkageError failure) {
				diagnostics.captureException(
					"candidate-rejection",
					"candidate-linkage-error",
					failure,
					"candidate_index", queriedCandidates,
					"candidate", access,
					"local_block_pos", localPos,
					"local_hit", localHit);
				throw failure;
			}
		}

		diagnostics.capture(
			"candidate-query",
			"complete",
			"queried_count", queriedCandidates,
			"accepted_count", candidates.size(),
			"containing_sublevel_uuid", containingSubLevelId);

		candidates.sort(SUB_LEVEL_CANDIDATE_ORDER);

		if (candidates.isEmpty()) {
			diagnostics.capture(
				"capture",
				"no-winner",
				"queried_count", queriedCandidates,
				"accepted_count", candidates.size(),
				"containing_sublevel_uuid", containingSubLevelId);
			return Optional.empty();
		}

		if (candidates.size() > 1
			&& SUB_LEVEL_CANDIDATE_ORDER.compare(candidates.get(0), candidates.get(1)) == 0) {
			diagnostics.capture(
				"capture",
				"ambiguity",
				"queried_count", queriedCandidates,
				"accepted_count", candidates.size(),
				"first_sublevel_uuid", candidates.get(0).subLevelId(),
				"second_sublevel_uuid", candidates.get(1).subLevelId());
			return Optional.empty();
		}

		SubLevelCandidate best = candidates.get(0);

		diagnostics.capture(
			"capture",
			"success",
			"provider", SableClientProvider.PROVIDER_ID,
			"sublevel_uuid", best.subLevelId(),
			"local_block_pos", localPos,
			"local_hit", best.localHit(),
			"logical_world_hit", best.worldHit(),
			"render_derived_world_hit", best.worldHit(),
			"world_hit_source", "logicalPose",
			"block_registry_id", best.registryId(),
			"block_entity", best.hasBlockEntity(),
			"provider_locator", best.locator().encode(),
			"ray_projection", best.rayPoint().projection(),
			"ray_perpendicular_distance_squared", best.rayPoint().perpendicularDistanceSquared());

		return Optional.of(TargetSnapshotFactory.externalBlockCandidate(
			level.dimension().location().toString(),
			SableClientProvider.PROVIDER_ID,
			best.registryId().toString(),
			best.locator().encode(),
			best.hasBlockEntity()));
	}

	Vec3 projectOutOfSubLevel(ClientLevel level, Vec3 hitPosition) {
		// Only project a position that Companion identifies as belonging to a
		// client sub-level plot. This is a plot lookup, not proof that an ordinary
		// parent-world block in a reserved plot belongs to that sub-level.
		Position position = hitPosition;
		SubLevelAccess containing = companion.getContaining(level, position);
		UUID containingId = containing == null ? null : containing.getUniqueId();

		diagnostics.capture(
			"containment",
			"lookup",
			"api", "getContaining(level,position)",
			"position", hitPosition,
			"selected_sublevel_uuid", containingId);

		if (containing == null) {
			diagnostics.capture(
				"projection",
				"no-containing-sublevel",
				"position", hitPosition);
			return null;
		}

		Vec3 projected = companion.projectOutOfSubLevel(level, position);
		diagnostics.capture(
			"projection",
			projected == null ? "no-result" : "success",
			"position", hitPosition,
			"projected_position", projected,
			"sublevel_uuid", containingId);
		return projected;
	}

	Optional<SableClientProvider.ExternalBlockPresentation> resolve(
		ClientLevel parent,
		Target.ExternalBlockTarget target,
		float partialTick,
		CollisionContext collisionContext
	) throws ReflectiveOperationException {
		if (internal == null) {
			return Optional.empty();
		}

		SableExternalBlockLocator locator = SableExternalBlockLocator.parse(target.providerLocator())
			.orElseThrow(() -> new IllegalArgumentException("invalid external locator"));
		SableClientInternalAccess.Resolution resolution =
			internal.resolve(parent, locator.subLevelId(), locator.blockPos());

		if (resolution.value().isEmpty()) {
			return Optional.empty();
		}

		SableClientInternalAccess.ResolvedSubLevel stateData = resolution.value().orElseThrow();
		BlockState state = stateData.state();
		ResourceLocation actualId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		ResourceLocation expectedId = ResourceLocation.tryParse(target.expectedBlockRegistryId());

		if (actualId == null || expectedId == null || !actualId.equals(expectedId)
			|| state.isAir() || state.getBlock() == Blocks.AIR) {
			return Optional.empty();
		}

		if (!(stateData.subLevel() instanceof ClientSubLevelAccess clientAccess)) {
			return Optional.empty();
		}

		Pose3dc renderPose = clientAccess.renderPose(partialTick);
		Vec3 localCenter = new Vec3(
			locator.x() + 0.5D, locator.y() + 0.5D, locator.z() + 0.5D);
		Vec3 worldCenter = renderPose.transformPosition(localCenter);
		VoxelShape shape = state.getShape(stateData.level(), locator.blockPos(), collisionContext);
		Matrix4f poseMatrix = new Matrix4f(renderPose.bakeIntoMatrix(new Matrix4d()));

		if (!finite(worldCenter)) {
			return Optional.empty();
		}

		return Optional.of(new SableClientProvider.ExternalBlockPresentation(
			worldCenter, locator.blockPos(), state, shape, poseMatrix));
	}

	Optional<Vec3> resolvePosition(
		ClientLevel parent, Target.ExternalBlockTarget target, float partialTick
	) throws ReflectiveOperationException {
		if (internal == null) {
			return Optional.empty();
		}

		SableExternalBlockLocator locator = SableExternalBlockLocator.parse(target.providerLocator())
			.orElseThrow(() -> new IllegalArgumentException("invalid external locator"));
		SableClientInternalAccess.Resolution resolution =
			internal.resolve(parent, locator.subLevelId(), locator.blockPos());

		if (resolution.value().isEmpty()) {
			return Optional.empty();
		}

		SableClientInternalAccess.ResolvedSubLevel stateData = resolution.value().orElseThrow();
		BlockState state = stateData.state();
		ResourceLocation actualId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		ResourceLocation expectedId = ResourceLocation.tryParse(target.expectedBlockRegistryId());

		if (actualId == null || expectedId == null || !actualId.equals(expectedId)
			|| state.isAir() || state.getBlock() == Blocks.AIR
			|| !(stateData.subLevel() instanceof ClientSubLevelAccess clientAccess)) {
			return Optional.empty();
		}

		Vec3 center = clientAccess.renderPose(partialTick).transformPosition(new Vec3(
			locator.x() + 0.5D, locator.y() + 0.5D, locator.z() + 0.5D));

		return finite(center) ? Optional.of(center) : Optional.empty();
	}

	Optional<Component> resolveName(ClientLevel parent, Target.ExternalBlockTarget target)
		throws ReflectiveOperationException {
		if (internal == null) {
			return Optional.empty();
		}

		SableExternalBlockLocator locator = SableExternalBlockLocator.parse(target.providerLocator())
			.orElseThrow(() -> new IllegalArgumentException("invalid external locator"));
		SableClientInternalAccess.Resolution resolution =
			internal.resolve(parent, locator.subLevelId(), locator.blockPos());

		if (resolution.value().isEmpty()) {
			return Optional.empty();
		}

		SableClientInternalAccess.ResolvedSubLevel stateData = resolution.value().orElseThrow();
		BlockState state = stateData.state();
		ResourceLocation actualId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		ResourceLocation expectedId = ResourceLocation.tryParse(target.expectedBlockRegistryId());

		if (actualId == null || expectedId == null || !actualId.equals(expectedId)
			|| state.isAir() || state.getBlock() == Blocks.AIR) {
			return Optional.empty();
		}

		Component baseName = state.getBlock().getName();
		BlockEntity blockEntity = stateData.level().getBlockEntity(locator.blockPos());

		if (blockEntity instanceof Nameable nameable && nameable.hasCustomName()) {
			Component customName = nameable.getCustomName();

			if (customName != null) {
				return Optional.of(TargetNameComposer.compose(customName, baseName));
			}
		}

		return Optional.of(baseName);
	}

	private UUID containingSubLevelId(ClientLevel level, Position position) {
		SubLevelAccess containing = companion.getContaining(level, position);
		UUID containingId = containing == null ? null : containing.getUniqueId();

		diagnostics.capture(
			"containment",
			"lookup",
			"api", "getContaining(level,position)",
			"position", position,
			"selected_sublevel_uuid", containingId);

		return containingId;
	}

	private static RayProjection rayPoint(Vec3 origin, Vec3 ray, double rayLengthSquared, Vec3 point) {
		Vec3 fromOrigin = point.subtract(origin);
		double normalizedDistance = fromOrigin.dot(ray) / rayLengthSquared;

		if (normalizedDistance < -RAY_EPSILON || normalizedDistance > 1.0D + RAY_EPSILON) {
			return RayProjection.rejected(
				"out-of-segment", normalizedDistance, Double.NaN);
		}

		Vec3 nearest = origin.add(ray.scale(normalizedDistance));
		double distanceSquared = point.distanceToSqr(nearest);

		return !Double.isFinite(distanceSquared) || distanceSquared > RAY_EPSILON * RAY_EPSILON
			? RayProjection.rejected("off-ray", normalizedDistance, distanceSquared)
			: RayProjection.accepted(new RayPoint(normalizedDistance, distanceSquared));
	}

	private static boolean finite(Vec3 value) {
		return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
	}

	private record RayPoint(double projection, double perpendicularDistanceSquared) {
	}

	private record RayProjection(
		RayPoint point, String reason, double projection, double perpendicularDistanceSquared
	) {
		private static RayProjection accepted(RayPoint point) {
			return new RayProjection(
				point, "accepted", point.projection(), point.perpendicularDistanceSquared());
		}

		private static RayProjection rejected(
			String reason, double projection, double perpendicularDistanceSquared
		) {
			return new RayProjection(null, reason, projection, perpendicularDistanceSquared);
		}

		private boolean accepted() {
			return point != null;
		}
	}

	private static final Comparator<SubLevelCandidate> SUB_LEVEL_CANDIDATE_ORDER =
		Comparator.comparingDouble((SubLevelCandidate candidate) -> candidate.rayPoint().projection())
			.thenComparingDouble(candidate -> candidate.rayPoint().perpendicularDistanceSquared())
			.thenComparingDouble(candidate -> candidate.worldHit().x)
			.thenComparingDouble(candidate -> candidate.worldHit().y)
			.thenComparingDouble(candidate -> candidate.worldHit().z)
			.thenComparing(candidate -> candidate.registryId().toString());

	private record SubLevelCandidate(
		ClientSubLevelAccess access,
		SableClientInternalAccess.ResolvedSubLevel resolved,
		UUID subLevelId,
		ResourceLocation registryId,
		RayPoint rayPoint,
		Vec3 worldHit,
		Vec3 localHit,
		SableExternalBlockLocator locator,
		boolean hasBlockEntity
	) {
	}
}
