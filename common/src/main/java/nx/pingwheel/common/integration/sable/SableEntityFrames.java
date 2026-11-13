package nx.pingwheel.common.integration.sable;

import java.util.Objects;
import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.integration.aeronautics.AeroRotorScope;
import nx.pingwheel.common.math.EntityFrameResolution;
import nx.pingwheel.common.math.EntityRaycastWork;
import org.jetbrains.annotations.NotNullByDefault;

/** Server-safe optional boundary, independent of the block/content provider's link guard. */
@NotNullByDefault
public final class SableEntityFrames {
	private SableEntityFrames() {}
	public static EntityFrameResolution logical(Entity entity) {
		return logical(entity, EntityRaycastWork.unlimited());
	}
	public static EntityFrameResolution logical(Entity entity, EntityRaycastWork work) {
		if (!ModContext.HasSable || !AeroRotorScope.matches(entity)) return EntityFrameResolution.world();
		try {
			return resolve(entity, work, true, true, SableEntityFrameAccess::logical);
		} catch (LinkageError unavailable) { return EntityFrameResolution.unavailable(); }
	}

	@FunctionalInterface
	@NotNullByDefault
	interface Access { EntityFrameResolution resolve(Entity entity, EntityRaycastWork work); }

	static EntityFrameResolution resolve(Entity entity, EntityRaycastWork work, boolean enabled,
		boolean inScope, Access access) {
		Objects.requireNonNull(entity); Objects.requireNonNull(work);
		if (!enabled || !inScope) return EntityFrameResolution.world();
		try {
			return Objects.requireNonNull(access.resolve(entity, work));
		} catch (EntityRaycastWork.Exhausted exhausted) {
			throw exhausted;
		} catch (RuntimeException | LinkageError failure) {
			return EntityFrameResolution.unavailable();
		}
	}
}
