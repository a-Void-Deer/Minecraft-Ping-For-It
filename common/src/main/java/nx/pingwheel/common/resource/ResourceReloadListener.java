package nx.pingwheel.common.resource;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;

import nx.pingwheel.common.client.resource.PingTextureState;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static nx.pingwheel.common.resource.ResourceConstants.PING_TEXTURE_ID;

public class ResourceReloadListener implements PreparableReloadListener {

	@Override
	public CompletableFuture<Void> reload(SharedState sharedState, Executor loadExecutor, PreparationBarrier barrier, Executor applyExecutor) {
		return reloadTextures(barrier, sharedState.resourceManager(), loadExecutor, applyExecutor);
	}

	private static int numCustomTextures;

	public static boolean hasCustomTexture() {
		return numCustomTextures > 1;
	}

	public static CompletableFuture<Void> reloadTextures(PreparationBarrier helper, ResourceManager resourceManager, Executor loadExecutor, Executor applyExecutor) {
		return CompletableFuture
			.supplyAsync(() -> resourceManager.getResourceStack(PING_TEXTURE_ID).size(), loadExecutor)
			.thenCompose(helper::wait)
			.thenAcceptAsync(count -> {
				numCustomTextures = count;
				PingTextureState.setHasCustomTexture(count > 1);
			}, applyExecutor);
	}
}
