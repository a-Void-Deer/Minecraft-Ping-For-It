package nx.pingwheel.common.screen;

import java.util.List;
import java.util.function.ToIntFunction;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import nx.pingwheel.common.screen.SettingsNavigationModel.Scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTitleWidgetTest {
	private static final int FONT_LINE_HEIGHT = 9;
	private static final ToIntFunction<String> SIX_PIXELS_PER_CHARACTER = text -> text.length() * 6;

	@Test
	void rootTitleUsesOnlyTheBaseSegment() {
		MutableComponent base = Component.literal("Ping For It Configuration");

		assertSame(base, SettingsTitleWidget.composeTitle(base, null, null));
	}

	@Test
	void leafTitleJoinsBaseScopeAndCategoryWithTheNeutralSeparator() {
		MutableComponent base = Component.literal("Ping For It Configuration");

		Component title = SettingsTitleWidget.composeTitle(
			base,
			Component.literal("Server"),
			Component.literal("Send Rate"));

		assertEquals("Ping For It Configuration -> Server -> Send Rate", title.getString());
	}

	@Test
	void layoutAppliesExplicitSizeAndPositionInsteadOfMisorderedRectangleArguments() {
		SettingsTitleWidget title = new SettingsTitleWidget(null);
		title.setMessage(Component.literal("Ping For It Configuration"));

		int blockHeight = title.layout(320, 27, FONT_LINE_HEIGHT, SIX_PIXELS_PER_CHARACTER);

		assertEquals(0, title.getX());
		assertEquals(27, title.getY());
		assertEquals(320, title.getWidth(), "the title must span the screen instead of collapsing to zero width");
		assertEquals(blockHeight, title.getHeight());
		assertEquals(320, title.getRectangle().width());
		assertTrue(blockHeight >= SettingsTitleWidget.scaledLineHeight(FONT_LINE_HEIGHT));
	}

	@Test
	void longBreadcrumbWrapsToWholeScaledRowsAndGrowsTheReservedHeader() {
		SettingsTitleWidget title = new SettingsTitleWidget(null);
		title.setMessage(Component.literal(
			"Ping For It Configuration -> Client -> Marker Display And Target Selection"));

		int blockHeight = title.layout(320, 27, FONT_LINE_HEIGHT, SIX_PIXELS_PER_CHARACTER);
		int rows = title.lineCount();
		int scaledRowHeight = SettingsTitleWidget.scaledLineHeight(FONT_LINE_HEIGHT);

		assertTrue(rows >= 2, "a long breadcrumb must wrap");
		assertTrue(
			blockHeight >= SettingsTitleWidget.titleBlockHeight(rows, scaledRowHeight),
			"the block height must fit every wrapped scaled row");
		assertTrue(
			SettingsScreenLayout.headerHeightFor(false, 27, blockHeight)
				>= 27 + blockHeight + SettingsScreenLayout.TITLE_BOTTOM_MARGIN,
			"the root header must not hide the wrapped title");
		assertTrue(
			SettingsScreenLayout.headerHeightFor(true, 6, blockHeight)
				>= 6 + blockHeight + SettingsScreenLayout.TITLE_BOTTOM_MARGIN,
			"a leaf header must not hide the wrapped title");
	}

	@Test
	void shortTitleKeepsTheEstablishedRootAndLeafHeaderMinimums() {
		int shortBlock = SettingsTitleWidget.titleBlockHeight(1, SettingsTitleWidget.scaledLineHeight(FONT_LINE_HEIGHT));

		assertEquals(SettingsScreenLayout.ROOT_HEADER_HEIGHT, SettingsScreenLayout.headerHeightFor(false, 27, shortBlock));
		assertEquals(SettingsScreenLayout.LEAF_HEADER_HEIGHT, SettingsScreenLayout.headerHeightFor(true, 6, shortBlock));
	}

	@Test
	void wordWrappingKeepsEveryLineInsideTheMeasurementLimit() {
		String text = "Ping For It Configuration -> Client -> Marker Display And Target Selection";

		List<String> lines = SettingsTitleWidget.wrapLines(text, 90, SIX_PIXELS_PER_CHARACTER);

		assertTrue(lines.size() >= 2);
		assertTrue(lines.stream().allMatch(line -> SIX_PIXELS_PER_CHARACTER.applyAsInt(line) <= 90));
		assertEquals(text, String.join(" ", lines));
	}

	@Test
	void aSingleWordWiderThanTheLineBreaksByCodePoint() {
		String word = "Konfigurationseinstellungen";

		List<String> lines = SettingsTitleWidget.wrapLines(word, 60, SIX_PIXELS_PER_CHARACTER);

		assertTrue(lines.size() >= 2);
		assertTrue(lines.stream().allMatch(line -> SIX_PIXELS_PER_CHARACTER.applyAsInt(line) <= 60));
		assertEquals(word, String.join("", lines));
	}

	@Test
	void spaceLessCjkBreadcrumbWrapsWithoutLosingText() {
		String text = "Ping For It 配置 -> 客户端 -> 标记显示与目标选择";

		List<String> lines = SettingsTitleWidget.wrapLines(text, 60, SIX_PIXELS_PER_CHARACTER);

		assertTrue(lines.size() >= 2);
		assertTrue(lines.stream().allMatch(
			line -> SIX_PIXELS_PER_CHARACTER.applyAsInt(line) <= 60
				|| line.codePointCount(0, line.length()) == 1));
		assertEquals(text.replace(" ", ""), String.join("", lines).replace(" ", ""));
	}

	@Test
	void eachScopeTabResolvesItsOwnImmutableScopeLabelAcrossNavigation() {
		SettingsScreen.ScopeTab clientTab = new SettingsScreen.ScopeTab(Scope.CLIENT);
		SettingsScreen.ScopeTab serverTab = new SettingsScreen.ScopeTab(Scope.SERVER);

		assertScopeTabTitlesStayDistinct(clientTab, serverTab);

		SettingsNavigationModel navigation = new SettingsNavigationModel();
		navigation.selectScope(Scope.SERVER);
		navigation.openCategory(Category.SEND_RATE);
		assertScopeTabTitlesStayDistinct(clientTab, serverTab);

		navigation.back();
		navigation.selectScope(Scope.CLIENT);
		navigation.openCategory(Category.MARKER_DISPLAY);
		assertScopeTabTitlesStayDistinct(clientTab, serverTab);
	}

	private static void assertScopeTabTitlesStayDistinct(
		SettingsScreen.ScopeTab clientTab,
		SettingsScreen.ScopeTab serverTab
	) {
		assertTranslatableKey("settings.pingforit.client_settings", clientTab.getTabTitle());
		assertTranslatableKey("settings.pingforit.server_settings", serverTab.getTabTitle());
	}

	private static void assertTranslatableKey(String expectedKey, Component title) {
		ComponentContents contents = title.getContents();
		assertTrue(
			contents instanceof TranslatableContents,
			() -> "expected a translatable tab title, got: " + contents);
		assertEquals(expectedKey, ((TranslatableContents) contents).getKey());
	}
}
