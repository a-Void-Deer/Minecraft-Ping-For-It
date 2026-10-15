package nx.pingwheel.fabric.mixin.inventory;

import net.fabricmc.fabric.api.transfer.v1.item.base.SingleStackStorage;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Raw seam only. The concrete getStack graph still needs audit or EXTERNAL_TRUST. */
@Mixin(value = SingleStackStorage.class, remap = false)
public interface SingleStackStorageResidentInvoker {
	@Invoker(value = "getStack", remap = false)
	ItemStack pingforit$residentStack();
}
