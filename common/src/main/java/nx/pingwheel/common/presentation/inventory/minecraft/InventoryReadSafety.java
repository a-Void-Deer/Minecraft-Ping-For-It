package nx.pingwheel.common.presentation.inventory.minecraft;

import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Check;
import org.jetbrains.annotations.Nullable;

/** Side-effect-free preflight, deliberately never collecting implicit components. */
public final class InventoryReadSafety {
	private InventoryReadSafety() {}
	public static boolean readable(@Nullable BlockEntity entity, @Nullable ItemStack heldKey) {
		return check(entity, heldKey) == Check.VALID;
	}
	/** Null key means the read owner is absent, not an empty held item. */
	public static Check check(@Nullable BlockEntity entity, @Nullable ItemStack heldKey) {
		if (entity == null || entity.isRemoved()) return Check.UNAVAILABLE;
		if (entity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) return Check.UNAVAILABLE;
		if (heldKey == null) return Check.AUTHORITY_DENIED;
		if (entity instanceof BaseContainerBlockEntity) {
			if (!(entity instanceof BaseContainerLockAccessor access)) return Check.UNAVAILABLE;
			return lockCheck(access.pingforit$inventoryLock(), heldKey);
		}
		return Check.VALID;
	}
	/** Lock-only seam; the caller still checks entity availability and pending loot first. */
	public static Check lockCheck(@Nullable LockCode lock, @Nullable ItemStack heldKey) {
		if (heldKey == null) return Check.AUTHORITY_DENIED;
		if (lock == null) return Check.UNAVAILABLE;
		// An empty lock key is already readable; it must not allocate a root
		// budget for repeated per-slot frozen-topology checks.
		if (lock.key().isEmpty()) return Check.VALID;
		return InventoryItemCodec.lockMatchesResident(heldKey, lock.key(),
			InventoryItemBudget.root(InventoryItemCodec.minimumDisplayBytes())) ? Check.VALID : Check.AUTHORITY_DENIED;
	}
}
