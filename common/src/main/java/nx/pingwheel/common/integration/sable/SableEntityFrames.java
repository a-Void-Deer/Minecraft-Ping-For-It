package nx.pingwheel.common.integration.sable;

import java.util.Objects;
import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.integration.aeronautics.AeroRotorScope;
import nx.pingwheel.common.math.EntityFrameResolution;
import org.jetbrains.annotations.NotNullByDefault;

/** Server-safe optional boundary, independent of the block/content provider's link guard. */
@NotNullByDefault
public final class SableEntityFrames {
	private SableEntityFrames() {}
	public static EntityFrameResolution logical(Entity entity) {
		if (!ModContext.HasSable || !AeroRotorScope.matches(entity)) return EntityFrameResolution.world();
		try {
			return resolve(entity, true, true, SableEntityFrameAccess::logical);
		} catch (LinkageError unavailable) { return EntityFrameResolution.unavailable(); }
	}

	@FunctionalInterface
	@NotNullByDefault
	interface Access { EntityFrameResolution resolve(Entity entity); }

	static EntityFrameResolution resolve(Entity entity, boolean enabled, boolean inScope, Access access) {
		Objects.requireNonNull(entity);
		if (!enabled || !inScope) return EntityFrameResolution.world();
		try {
			return Objects.requireNonNull(access.resolve(entity));
		} catch (RuntimeException | LinkageError failure) {
			return EntityFrameResolution.unavailable();
		}
	}
}
