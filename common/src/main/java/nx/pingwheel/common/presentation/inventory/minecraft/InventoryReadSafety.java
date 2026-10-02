package nx.pingwheel.common.presentation.inventory.minecraft;

import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;

/** Side-effect-free preflight, deliberately never collecting implicit components. */
public final class InventoryReadSafety {
	private InventoryReadSafety() {}
	public static boolean readable(BlockEntity entity, ItemStack heldKey) {
		if (entity == null || entity.isRemoved()) return false;
		if (entity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) return false;
		if (entity instanceof BaseContainerBlockEntity) {
			if (!(entity instanceof BaseContainerLockAccessor access)) return false;
			return access.pingforit$inventoryLock().unlocksWith(heldKey);
		}
		return true;
	}
}
