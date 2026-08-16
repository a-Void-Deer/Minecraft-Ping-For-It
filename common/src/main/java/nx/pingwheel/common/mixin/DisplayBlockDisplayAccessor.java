package nx.pingwheel.common.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Registered client mixin accessor for the private 1.21.1
 * {@code Display.BlockDisplay#setBlockState(BlockState)}.
 *
 * <p>The virtual ordinary-block display renderer needs to swap the block state
 * of its cached display entity every frame; the setter is private, so the
 * invoker below is the loader-neutral access path (no reflection, no access
 * transformer, no access widener). The invoker stores the state through the
 * display's own synced entity data, so the subsequent {@code tick()} call can
 * compute a matching {@code blockRenderState} exactly as for a real block
 * display entity.
 */
@Mixin(Display.BlockDisplay.class)
public interface DisplayBlockDisplayAccessor {

	@Invoker("setBlockState")
	void pingForItSetBlockState(BlockState blockState);
}
