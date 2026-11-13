package nx.pingwheel.common.integration.sable.client;

import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.integration.aeronautics.AeroRotorScope;
import nx.pingwheel.common.math.EntityFrameResolution;
import nx.pingwheel.common.math.EntityRaycastWork;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
public final class SableClientEntityFrames {
	private SableClientEntityFrames() {}
	public static EntityFrameResolution render(Entity entity, float partialTick) {
		if (!ModContext.HasSable || !AeroRotorScope.matches(entity)) return EntityFrameResolution.world();
		try { return resolve(entity, partialTick, SablePickingAccess::render); }
		catch (LinkageError unavailable) { return EntityFrameResolution.unavailable(); }
	}

	@FunctionalInterface
	@NotNullByDefault
	interface Access { EntityFrameResolution render(Entity entity, float partialTick, EntityRaycastWork work); }

	static EntityFrameResolution resolve(Entity entity, float partialTick, Access access) {
		try {
			if (!Float.isFinite(partialTick)) return EntityFrameResolution.unavailable();
			return access.render(entity, partialTick, EntityRaycastWork.unlimited());
		} catch (RuntimeException | LinkageError failure) {
			return EntityFrameResolution.unavailable();
		}
	}
}
