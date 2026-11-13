package nx.pingwheel.common.integration.sable.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import it.unimi.dsi.fastutil.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Position;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.integration.aeronautics.AeroRotorScope;
import nx.pingwheel.common.math.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;

/** Optional query-local Companion implementation. Internal pose-stack discovery is additive. */
@NotNullByDefault
public final class SablePickingAccess implements PickingFrameContext.Access {
	private final java.util.function.Function<Position, @Nullable SubLevelAccess> containing;
	private final @Nullable PoseStack poseStack;
	private final Map<UUID, FrozenPose> poses = new HashMap<>();
	private final Set<UUID> clipPoses = new HashSet<>();
	private boolean scopeAvailable = true;

	public SablePickingAccess(ClientLevel level) {
		containing = position -> SableCompanion.INSTANCE.getContaining(level, position);
		@Nullable PoseStack discovered;
		try {
			Class<?> extension = Class.forName("dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension",
				false, SablePickingAccess.class.getClassLoader());
			if (!extension.isInstance(level)) throw new IllegalStateException("Sable pose extension unavailable");
			Method pushMethod = extension.getMethod("sable$pushPoseSupplier", Function.class);
			Method popMethod = extension.getMethod("sable$popPoseSupplier");
			discovered = new PoseStack(supplier -> invoke(pushMethod, level, supplier), () -> invoke(popMethod, level));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
			// Internal clip scoping is independent of the stable entity pose capability.
			discovered = null;
		}
		poseStack = discovered;
	}

	SablePickingAccess(java.util.function.Function<Position, @Nullable SubLevelAccess> containing,
		Consumer<Function<Object, Pose3dc>> push, Runnable pop) {
		this.containing = containing; this.poseStack = new PoseStack(push, pop);
	}

	SablePickingAccess(java.util.function.Function<Position, @Nullable SubLevelAccess> containing) {
		this.containing = containing; this.poseStack = null;
	}

	@Override public PickingFrameContext.Scope openScope() {
		clipPoses.clear();
		if (poseStack == null) {
			scopeAvailable = false;
			return () -> {};
		}
		try {
			Function<Object, Pose3dc> supplier = value -> {
				try {
					if (!(value instanceof SubLevelAccess subLevel)) throw new IllegalStateException("unsupported Sable sublevel");
					EntityRaycastWork work = EntityRaycastWork.unlimited();
					work.callProvider();
					UUID id = Objects.requireNonNull(subLevel.getUniqueId());
					FrozenPose frozen = freeze(subLevel, id, work);
					clipPoses.add(id);
					return frozen.pose();
				} catch (RuntimeException | LinkageError unavailable) {
					scopeAvailable = false;
					throw new PickingFrameContext.Unavailable(unavailable);
				}
			};
			poseStack.push().accept(supplier);
			return poseStack.pop()::run;
		} catch (RuntimeException | LinkageError unavailable) {
			scopeAvailable = false;
			return () -> {};
		}
	}

	@Override public RaycastSelection normalize(BlockHitResult hit) {
		if (hit.getType() != HitResult.Type.BLOCK) return RaycastSelection.withoutLocalGeometry(hit);
		try {
			@Nullable SubLevelAccess owner = containing.apply(Vec3.atCenterOf(hit.getBlockPos()));
			if (owner == null) return RaycastSelection.withoutLocalGeometry(hit);
			// Native containment remains independently useful when frozen clip scoping is unavailable.
			// Only provider-local hits need the successfully observed frozen pose.
			if (!scopeAvailable) return unknown(hit);
			@Nullable UUID id = owner.getUniqueId();
			if (id == null) return unknown(hit);
			// Only a pose actually requested by this clip can prove its returned local coordinates.
			@Nullable FrozenPose frozen = poses.get(id);
			if (frozen == null || !clipPoses.contains(id)) return unknown(hit);
			var proof = new SableBlockHitFrame(id, hit.getBlockPos(), hit.getLocation(), frozen.frame());
			return new RaycastSelection(hit, Optional.empty(), Optional.of(frozen.frame().toWorld(hit.getLocation())), Optional.of(proof));
		} catch (RuntimeException | LinkageError unavailable) { return unknown(hit); }
	}

	@Override public EntityFrameResolution entityFrame(Entity entity, EntityRaycastWork work) {
		if (!AeroRotorScope.matches(entity)) return EntityFrameResolution.world();
		try {
			work.callProvider();
			@Nullable SubLevelAccess owner = containing.apply(entity.position());
			if (owner == null) return EntityFrameResolution.world();
			work.callProvider();
			@Nullable UUID id = owner.getUniqueId();
			if (id == null) return EntityFrameResolution.unavailable();
			return EntityFrameResolution.contained(freeze(owner, id, work).frame(), id);
		} catch (EntityRaycastWork.Exhausted exhausted) { throw exhausted; }
		catch (RuntimeException | LinkageError unavailable) { return EntityFrameResolution.unavailable(); }
	}

	private FrozenPose freeze(SubLevelAccess access, UUID id, EntityRaycastWork work) {
		@Nullable FrozenPose existing = poses.get(id);
		if (existing != null) return existing;
		work.callProvider();
		Pose3dc pose = access.logicalPose();
		Pose3d copy = copyPose(pose, work);
		FrozenPose result = new FrozenPose(copy, EntityParentFrame.copyOf(copy.bakeIntoMatrix(new Matrix4d()), copy.orientation()));
		poses.put(id, result);
		return result;
	}

	static EntityFrameResolution render(Entity entity, float partialTick, EntityRaycastWork work) {
		work.callProvider();
		@Nullable SubLevelAccess containing = SableCompanion.INSTANCE.getContaining(entity.level(), (Position) entity.position());
		if (containing == null) return EntityFrameResolution.world();
		if (!(containing instanceof ClientSubLevelAccess client)) return EntityFrameResolution.unavailable();
		work.callProvider();
		@Nullable UUID id = client.getUniqueId();
		work.callProvider();
		Pose3d copy = copyPose(client.renderPose(partialTick), work);
		return id == null ? EntityFrameResolution.unavailable()
			: EntityFrameResolution.contained(EntityParentFrame.copyOf(copy.bakeIntoMatrix(new Matrix4d()), copy.orientation()), id);
	}

	private static Pose3d copyPose(Pose3dc pose, EntityRaycastWork work) {
		Pose3d copy = new Pose3d();
		work.callProvider(); copy.position().set(pose.position());
		work.callProvider(); copy.orientation().set(pose.orientation());
		work.callProvider(); copy.rotationPoint().set(pose.rotationPoint());
		work.callProvider(); copy.scale().set(pose.scale());
		return copy;
	}
	private static RaycastSelection unknown(BlockHitResult hit) {
		return new RaycastSelection(hit, Optional.empty(), Optional.empty(), Optional.empty());
	}
	private static void invoke(Method method, Object receiver, Object... arguments) {
		try { method.invoke(receiver, arguments); }
		catch (InvocationTargetException failure) {
			@Nullable Throwable cause = failure.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			if (cause instanceof Error error) throw error;
			throw new IllegalStateException("Sable pose scope failed", cause);
		} catch (ReflectiveOperationException failure) { throw new IllegalStateException("Sable pose scope unavailable", failure); }
	}
	@NotNullByDefault
	private record FrozenPose(Pose3d pose, EntityParentFrame frame) {}
	@NotNullByDefault
	private record PoseStack(Consumer<Function<Object, Pose3dc>> push, Runnable pop) {}
}
