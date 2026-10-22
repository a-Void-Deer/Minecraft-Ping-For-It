package nx.pingwheel.common.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only prediction guard; an uninstalled accessor makes local preview unavailable. */
@Mixin(ClientLevel.class)
public interface ClientLevelPreviewAccessor {
	@Accessor("blockStatePredictionHandler")
	BlockStatePredictionHandler pingforit$predictionHandler();
}
