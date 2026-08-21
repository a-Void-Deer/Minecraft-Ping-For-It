package nx.pingwheel.common.mixin;

import net.minecraft.world.level.LevelAccessor;
import nx.pingwheel.neoforge.integration.create.CreateEntityOutlineMaskScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional Flywheel boundary for the explicit Create entity outline pass.
 * The target is a string on purpose: Flywheel is not a runtime dependency of
 * the common or NeoClient class-loading path.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.api.visualization.VisualizationManager", remap = false)
public class CreateVisualizationManagerMixin {
	@Inject(
		method = "supportsVisualization(Lnet/minecraft/world/level/LevelAccessor;)Z",
		at = @At("HEAD"),
		cancellable = true,
		require = 0,
		remap = false
	)
	private static void pingForItDisableVisualization(
		LevelAccessor level,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (CreateEntityOutlineMaskScope.active()) {
			cir.setReturnValue(false);
		}
	}
}
