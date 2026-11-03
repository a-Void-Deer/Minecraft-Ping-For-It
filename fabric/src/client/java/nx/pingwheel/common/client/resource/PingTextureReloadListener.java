package nx.pingwheel.common.client.resource;

import java.util.List;

import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.server.packs.resources.Resource;

import nx.pingwheel.common.resource.ResourceConstants;

import static nx.pingwheel.common.Global.LOGGER;

/**
 * M8b: Fabric resource-loader <strong>v1</strong> listener that detects a replaced
 * {@code ping.png}.
 *
 * <p>Registered through {@code ResourceLoader.registerReloadListener}, never the v0
 * {@code ResourceManagerHelper} — v0 registration leaves the reload permanently
 * incomplete and hangs the loading overlay (verified in this project).
 *
 * <p>{@code prepare} only counts the stack; the decision itself is made in {@code apply}
 * on the main thread. A failure here must never break resource loading, so it warns once
 * and reports "no custom texture".
 */
public final class PingTextureReloadListener extends SimpleReloadListener<Integer> {

	private static boolean failureLogged = false;

	@Override
	protected Integer prepare(SharedState sharedState) {
		try {
			List<Resource> stack = sharedState.resourceManager()
				.getResourceStack(ResourceConstants.PING_TEXTURE_ID);

			return stack == null ? 0 : stack.size();
		} catch (RuntimeException failure) {
			if (!failureLogged) {
				failureLogged = true;
				LOGGER.warn("ping texture reload: could not read the texture stack, assuming the shipped placeholder ({})",
					failure.getClass().getSimpleName());
			}

			return 1;
		}
	}

	@Override
	protected void apply(Integer size, SharedState sharedState) {
		// > 1 means a pack on top of the shipped 1x1 transparent placeholder.
		PingTextureState.setHasCustomTexture(size != null && size > 1);
	}
}
