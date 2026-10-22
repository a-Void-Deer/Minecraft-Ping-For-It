package nx.pingwheel.common.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import nx.pingwheel.common.presentation.preview.client.PreviewPredictionView;

/** Only exposes membership, not the mutable prediction map or predicted values. */
@Mixin(BlockStatePredictionHandler.class)
public abstract class BlockStatePredictionPreviewAccessor implements PreviewPredictionView {
	@Shadow @Final private Long2ObjectOpenHashMap<?> serverVerifiedStates;
	public final boolean pingforit$hasPendingPrediction(BlockPos pos) {
		return serverVerifiedStates.containsKey(pos.asLong());
	}
}
