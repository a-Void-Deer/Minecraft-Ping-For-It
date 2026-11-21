package nx.pingwheel.common.integration.sable;

import java.util.UUID;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.Position;
import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.math.EntityFrameResolution;
import nx.pingwheel.common.math.EntityParentFrame;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

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
		return EntityFrameResolution.contained(
			EntityParentFrame.copyOfPivoted(pose.rotationPoint(), pose.position(), pose.orientation(), pose.scale()), id);
	}
}
