package nx.pingwheel.common.integration.sable;

import java.util.UUID;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.core.Position;
import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.math.EntityFrameResolution;
import nx.pingwheel.common.math.EntityParentFrame;
import nx.pingwheel.common.math.EntityRaycastWork;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;

/** Loaded only after the server-safe facade's optional and exact-ID gates. */
@NotNullByDefault
final class SableEntityFrameAccess {
	private SableEntityFrameAccess() {}
	static EntityFrameResolution logical(Entity entity, EntityRaycastWork work) {
		return logical(entity, work, value -> SableCompanion.INSTANCE.getContaining(value.level(), (Position) value.position()));
	}

	@FunctionalInterface
	@NotNullByDefault
	interface Lookup { @Nullable SubLevelAccess containing(Entity entity); }

	static EntityFrameResolution logical(Entity entity, EntityRaycastWork work, Lookup lookup) {
		work.callProvider();
		@Nullable SubLevelAccess containing = lookup.containing(entity);
		if (containing == null) return EntityFrameResolution.world();
		work.callProvider();
		@Nullable UUID id = containing.getUniqueId();
		work.callProvider();
		@Nullable Pose3dc pose = containing.logicalPose();
		if (id == null || pose == null) return EntityFrameResolution.unavailable();
		Pose3d copy = new Pose3d();
		work.callProvider(); copy.position().set(pose.position());
		work.callProvider(); copy.orientation().set(pose.orientation());
		work.callProvider(); copy.rotationPoint().set(pose.rotationPoint());
		work.callProvider(); copy.scale().set(pose.scale());
		Matrix4d matrix = copy.bakeIntoMatrix(new Matrix4d());
		return EntityFrameResolution.contained(EntityParentFrame.copyOf(matrix, copy.orientation()), id);
	}
}
