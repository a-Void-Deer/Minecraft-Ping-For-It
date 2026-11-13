package nx.pingwheel.common.math;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
@FunctionalInterface
public interface EntityParentFrameLookup {
	EntityFrameResolution resolve(Entity entity, EntityRaycastWork work);
}
