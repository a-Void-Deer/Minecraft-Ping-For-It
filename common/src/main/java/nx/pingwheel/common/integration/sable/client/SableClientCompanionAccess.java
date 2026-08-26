package nx.pingwheel.common.integration.sable.client;

import java.util.Optional;
import java.util.UUID;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.name.TargetNameComposer;
import nx.pingwheel.common.resolve.BlockEntityClassification;

/** Direct Companion 1.6.0 calls, isolated from the common client runtime. */
final class SableClientCompanionAccess {

	private static final double RAY_EPSILON = 0.35D;

	private final SableCompanion companion;
	private final SableClientInternalAccess internal;

	private SableClientCompanionAccess(
		SableCompanion companion, SableClientInternalAccess internal
	) {
		this.companion = companion;
		this.internal = internal;
	}

	static SableClientCompanionAccess create() throws ReflectiveOperationException {
		SableClientInternalAccess internal;

		try {
			internal = SableClientInternalAccess.create();
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// The stable Companion projection remains useful even when Sable's
			// internal client container shape has drifted. Candidate/render/name
			// routes fail soft below while the legacy location fallback survives.
			internal = null;
		}

		return new SableClientCompanionAccess(
			SableCompanion.INSTANCE,
			internal);
	}

	Optional<TargetSnapshot> capture(
		ClientLevel level, BlockHitResult hit, Vec3 rayStart, Vec3 rayEnd
	) throws ReflectiveOperationException {
		if (internal == null) {
			return Optional.empty();
		}

		BlockPos localPos = hit.getBlockPos();
		Vec3 localHit = hit.getLocation();
		Vec3 ray = rayEnd.subtract(rayStart);
		double rayLengthSquared = ray.lengthSqr();

		if (rayLengthSquared <= 0.0D || !finite(localHit)) {
			return Optional.empty();
		}

		SubLevelCandidate best = null;
		BoundingBox3d rayBounds = new BoundingBox3d(rayStart, rayEnd);

		for (SubLevelAccess access : companion.getAllIntersecting(level, rayBounds)) {
			if (!(access instanceof ClientSubLevelAccess clientAccess)) {
				continue;
			}

			UUID subLevelId = clientAccess.getUniqueId();

			if (subLevelId == null) {
				continue;
			}

			Optional<SableClientInternalAccess.ResolvedSubLevel> resolved =
				internal.resolve(level, subLevelId, localPos);

			if (resolved.isEmpty()) {
				continue;
			}

			BlockState state = resolved.get().state();
			ResourceLocation registryId = BuiltInRegistries.BLOCK.getKey(state.getBlock());

			if (registryId == null || state.isAir() || state.getBlock() == Blocks.AIR) {
				continue;
			}

			Vec3 worldHit = clientAccess.logicalPose().transformPosition(localHit);
			RayPoint rayPoint = rayPoint(rayStart, ray, rayLengthSquared, worldHit);

			if (rayPoint == null) {
				continue;
			}

			SubLevelCandidate candidate = new SubLevelCandidate(
				clientAccess,
				resolved.get(),
				subLevelId,
				registryId,
				rayPoint.distanceSquared());

			if (best == null || candidate.distanceSquared() < best.distanceSquared()) {
				best = candidate;
			}
		}

		if (best == null) {
			return Optional.empty();
		}

		SableExternalBlockLocator locator = new SableExternalBlockLocator(best.subLevelId(), localPos);
		boolean hasBlockEntity = BlockEntityClassification.hasBlockEntity(best.resolved().state());

		return Optional.of(TargetSnapshotFactory.externalBlockCandidate(
			level.dimension().location().toString(),
			SableClientProvider.PROVIDER_ID,
			best.registryId().toString(),
			locator.encode(),
			hasBlockEntity));
	}

	Vec3 projectOutOfSubLevel(ClientLevel level, Vec3 hitPosition) {
		// Companion's projection method returns the input position unchanged for
		// a parent-level point. Preserve the old adapter's positive containment
		// check so ordinary blocks never get downgraded to locations merely
		// because Sable is present.
		if (companion.getContainingClient(hitPosition) == null) {
			return null;
		}

		return companion.projectOutOfSubLevel(level, hitPosition);
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
		Optional<SableClientInternalAccess.ResolvedSubLevel> resolved =
			internal.resolve(parent, locator.subLevelId(), locator.blockPos());

		if (resolved.isEmpty()) {
			return Optional.empty();
		}

		SableClientInternalAccess.ResolvedSubLevel stateData = resolved.get();
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
		Optional<SableClientInternalAccess.ResolvedSubLevel> resolved =
			internal.resolve(parent, locator.subLevelId(), locator.blockPos());

		if (resolved.isEmpty()) {
			return Optional.empty();
		}

		BlockState state = resolved.get().state();
		ResourceLocation actualId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		ResourceLocation expectedId = ResourceLocation.tryParse(target.expectedBlockRegistryId());

		if (actualId == null || expectedId == null || !actualId.equals(expectedId)
			|| state.isAir() || state.getBlock() == Blocks.AIR
			|| !(resolved.get().subLevel() instanceof ClientSubLevelAccess clientAccess)) {
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
		Optional<SableClientInternalAccess.ResolvedSubLevel> resolved =
			internal.resolve(parent, locator.subLevelId(), locator.blockPos());

		if (resolved.isEmpty()) {
			return Optional.empty();
		}

		BlockState state = resolved.get().state();
		ResourceLocation actualId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		ResourceLocation expectedId = ResourceLocation.tryParse(target.expectedBlockRegistryId());

		if (actualId == null || expectedId == null || !actualId.equals(expectedId)
			|| state.isAir() || state.getBlock() == Blocks.AIR) {
			return Optional.empty();
		}

		Component baseName = state.getBlock().getName();
		BlockEntity blockEntity = resolved.get().level().getBlockEntity(locator.blockPos());

		if (blockEntity instanceof Nameable nameable && nameable.hasCustomName()) {
			Component customName = nameable.getCustomName();

			if (customName != null) {
				return Optional.of(TargetNameComposer.compose(customName, baseName));
			}
		}

		return Optional.of(baseName);
	}

	private static RayPoint rayPoint(Vec3 origin, Vec3 ray, double rayLengthSquared, Vec3 point) {
		Vec3 fromOrigin = point.subtract(origin);
		double normalizedDistance = fromOrigin.dot(ray) / rayLengthSquared;

		if (normalizedDistance < -RAY_EPSILON || normalizedDistance > 1.0D + RAY_EPSILON) {
			return null;
		}

		Vec3 nearest = origin.add(ray.scale(normalizedDistance));
		double distanceSquared = point.distanceToSqr(nearest);

		return distanceSquared > RAY_EPSILON * RAY_EPSILON
			? null : new RayPoint(origin.distanceToSqr(point));
	}

	private static boolean finite(Vec3 value) {
		return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
	}

	private record RayPoint(double distanceSquared) {
	}

	private record SubLevelCandidate(
		ClientSubLevelAccess access,
		SableClientInternalAccess.ResolvedSubLevel resolved,
		UUID subLevelId,
		ResourceLocation registryId,
		double distanceSquared
	) {
	}
}
