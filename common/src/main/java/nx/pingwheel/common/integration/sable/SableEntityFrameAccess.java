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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;

/** Loaded only after the server-safe facade's optional and exact-ID gates. */
@NotNullByDefault
final class SableEntityFrameAccess {
	private SableEntityFrameAccess() {}
	static EntityFrameResolution logical(Entity entity) {
		return logical(entity, value -> SableCompanion.INSTANCE.getContaining(value.level(), (Position) value.position()));
	}

	@FunctionalInterface
	@NotNullByDefault
	interface Lookup { @Nullable SubLevelAccess containing(Entity entity); }

	static EntityFrameResolution logical(Entity entity, Lookup lookup) {
		@Nullable SubLevelAccess containing = lookup.containing(entity);
		if (containing == null) return EntityFrameResolution.world();
		@Nullable UUID id = containing.getUniqueId();
		@Nullable Pose3dc pose = containing.logicalPose();
		if (id == null || pose == null) return EntityFrameResolution.unavailable();
		Pose3d copy = new Pose3d();
		copy.position().set(pose.position());
		copy.orientation().set(pose.orientation());
		copy.rotationPoint().set(pose.rotationPoint());
		copy.scale().set(pose.scale());
		Matrix4d matrix = copy.bakeIntoMatrix(new Matrix4d());
		return EntityFrameResolution.contained(EntityParentFrame.copyOf(matrix, copy.orientation()), id);
	}
}
