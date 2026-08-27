package nx.pingwheel.common.render;

/** Pure layout and scaling policy for the target-selection notice. */
public final class SelectionToggleNoticeRenderPolicy {
	private SelectionToggleNoticeRenderPolicy() {}

	public static boolean isVisibleAtSize(int sizePercent) {
		return sizePercent > 0;
	}

	public static float scaleFor(int sizePercent) {
		return Math.max(0.0F, sizePercent) / 100.0F;
	}

	public static float anchorX(int guiWidth) {
		return guiWidth / 2.0F;
	}

	public static float anchorY(int guiHeight) {
		return guiHeight * 0.25F;
	}
}
