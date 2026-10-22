package nx.pingwheel.common.mixin;

import net.minecraft.world.LockCode;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads only the lock; component collection can traverse inventory contents. */
@Mixin(BaseContainerBlockEntity.class)
public interface BaseContainerLockAccessor {
	@Accessor("lockKey")
	LockCode pingforit$inventoryLock();
}
