package nx.pingwheel.common.client.resource;

/**
 * M8b: whether a resource pack replaced the shipped {@code ping.png} placeholder.
 *
 * <p>The shipped asset is a 1x1 fully transparent placeholder, so a stack deeper than one
 * entry means something is overriding it. Written from the resource-reload thread's apply
 * phase (main thread) and read from HUD rendering, hence {@code volatile}.
 */
public final class PingTextureState {

	private static volatile boolean hasCustomTexture = false;

	private PingTextureState() {}

	public static boolean hasCustomTexture() {
		return hasCustomTexture;
	}

	public static void setHasCustomTexture(boolean value) {
		hasCustomTexture = value;
	}
}
