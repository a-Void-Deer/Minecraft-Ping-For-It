package nx.pingwheel.common.screen;

/**
 * Screen-space layout for the settings screen's non-scrolling channel control.
 *
 * <p>The footer height is deliberately larger than the vanilla options footer:
 * the inherited Done button and the channel label/edit box both need a stable
 * region at the bottom of the screen.</p>
 */
public record SettingsScreenLayout(
	int listTop,
	int listBottom,
	int footerTop,
	int footerBottom,
	int channelX,
	int channelY,
	int channelLabelY
) {
	public static final int FOOTER_HEIGHT = 100;
	public static final int CHANNEL_WIDTH = 200;
	public static final int CHANNEL_HEIGHT = 20;

	private static final int CHANNEL_TOP_MARGIN = 20;
	private static final int CHANNEL_LABEL_OFFSET = 12;

	/**
	 * Keeps the requested footer inside the area below the screen header. The
	 * normal supported GUI sizes retain the complete fixed footer; this clamp
	 * prevents a negative OptionsList viewport on unusually short screens.
	 */
	public static int footerHeightFor(int screenHeight, int headerHeight) {
		final int safeScreenHeight = Math.max(0, screenHeight);
		final int safeHeaderHeight = clamp(headerHeight, 0, safeScreenHeight);
		return Math.min(FOOTER_HEIGHT, safeScreenHeight - safeHeaderHeight);
	}

	/**
	 * Calculates both sides of the OptionsList/footer boundary and the channel
	 * control position. The inputs mirror the mapped HeaderAndFooterLayout API
	 * used by OptionsList.updateSize(int, HeaderAndFooterLayout).
	 */
	public static SettingsScreenLayout calculate(int screenWidth, int screenHeight, int headerHeight, int footerHeight) {
		final int safeScreenWidth = Math.max(0, screenWidth);
		final int safeScreenHeight = Math.max(0, screenHeight);
		final int safeHeaderHeight = clamp(headerHeight, 0, safeScreenHeight);
		final int safeFooterHeight = Math.min(
			Math.max(0, footerHeight),
			safeScreenHeight - safeHeaderHeight
		);
		final int footerTop = safeScreenHeight - safeFooterHeight;
		final int footerBottom = safeScreenHeight;
		final int listTop = safeHeaderHeight;
		final int listBottom = Math.max(listTop, footerTop);
		final int channelX = Math.max(0, (safeScreenWidth - CHANNEL_WIDTH) / 2);
		final int maximumChannelY = Math.max(footerTop, footerBottom - CHANNEL_HEIGHT);
		final int channelY = clamp(footerTop + CHANNEL_TOP_MARGIN, footerTop, maximumChannelY);
		final int channelLabelY = Math.max(footerTop, channelY - CHANNEL_LABEL_OFFSET);

		return new SettingsScreenLayout(
			listTop,
			listBottom,
			footerTop,
			footerBottom,
			channelX,
			channelY,
			channelLabelY
		);
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(value, maximum));
	}
}
