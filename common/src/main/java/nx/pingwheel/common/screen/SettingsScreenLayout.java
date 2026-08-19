package nx.pingwheel.common.screen;

/**
 * Screen-space layout for the scrolling settings list and the two footer
 * actions.  The channel field is part of the list now; the footer contains
 * only Reset and the inherited Done button.
 */
public record SettingsScreenLayout(
	int listTop,
	int listBottom,
	int footerTop,
	int footerBottom,
	int resetX,
	int resetY,
	int doneX,
	int doneY
) {
	public static final int FOOTER_HEIGHT = 70;
	public static final int RESET_BUTTON_WIDTH = 100;
	public static final int RESET_BUTTON_HEIGHT = 20;
	public static final int DONE_BUTTON_WIDTH = 200;
	public static final int DONE_BUTTON_HEIGHT = 20;
	public static final int SMALL_WIDGET_WIDTH = 150;
	public static final int LARGE_WIDGET_WIDTH = 310;
	public static final int ROW_HEIGHT = 20;

	private static final int RESET_TOP_MARGIN = 2;

	/**
	 * Keeps the requested footer inside the area below the screen header.  The
	 * clamp also prevents a negative OptionsList viewport on unusually short
	 * screens.
	 */
	public static int footerHeightFor(int screenHeight, int headerHeight) {
		final int safeScreenHeight = Math.max(0, screenHeight);
		final int safeHeaderHeight = clamp(headerHeight, 0, safeScreenHeight);
		return Math.min(FOOTER_HEIGHT, safeScreenHeight - safeHeaderHeight);
	}

	/**
	 * Calculates both sides of the OptionsList/footer boundary and the footer
	 * action positions.  Done is centered in the footer by vanilla's
	 * HeaderAndFooterLayout; Reset is kept in a separate row above it.
	 */
	public static SettingsScreenLayout calculate(int screenWidth, int screenHeight, int headerHeight, int footerHeight) {
		final int safeScreenWidth = Math.max(0, screenWidth);
		final int safeScreenHeight = Math.max(0, screenHeight);
		final int safeHeaderHeight = clamp(headerHeight, 0, safeScreenHeight);
		final int safeFooterHeight = Math.min(
			Math.max(0, footerHeight),
			safeScreenHeight - safeHeaderHeight);
		final int footerTop = safeScreenHeight - safeFooterHeight;
		final int footerBottom = safeScreenHeight;
		final int listTop = safeHeaderHeight;
		final int listBottom = Math.max(listTop, footerTop);
		final int resetX = Math.max(0, (safeScreenWidth - RESET_BUTTON_WIDTH) / 2);
		final int resetY = clamp(
			footerTop + RESET_TOP_MARGIN,
			footerTop,
			Math.max(footerTop, footerBottom - RESET_BUTTON_HEIGHT));
		final int doneX = Math.max(0, (safeScreenWidth - DONE_BUTTON_WIDTH) / 2);
		final int doneY = clamp(
			footerTop + Math.max(0, (safeFooterHeight - DONE_BUTTON_HEIGHT) / 2),
			footerTop,
			Math.max(footerTop, footerBottom - DONE_BUTTON_HEIGHT));

		return new SettingsScreenLayout(
			listTop,
			listBottom,
			footerTop,
			footerBottom,
			resetX,
			resetY,
			doneX,
			doneY);
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(value, maximum));
	}
}
