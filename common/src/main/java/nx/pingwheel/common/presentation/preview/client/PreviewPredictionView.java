package nx.pingwheel.common.presentation.preview.client;

import net.minecraft.core.BlockPos;

/** Read-only membership bridge installed on the vanilla prediction handler. */
public interface PreviewPredictionView {
	boolean pingforit$hasPendingPrediction(BlockPos pos);
}
