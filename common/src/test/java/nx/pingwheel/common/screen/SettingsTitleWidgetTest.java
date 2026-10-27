package nx.pingwheel.common.screen;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import nx.pingwheel.common.screen.SettingsNavigationModel.Scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class SettingsTitleWidgetTest {
	private static final int FONT_LINE_HEIGHT = 9;
	private static final ToIntFunction<String> SIX_PIXELS_PER_CHARACTER = text -> text.length() * 6;

	@Test
	void rootTitleUsesOnlyTheBaseSegment() {
		MutableComponent base = Component.literal("Ping For It Configuration");

		assertSame(base, SettingsTitleWidget.composeTitle(base, null, null));
	}

	@Test
	void scopeTitleUsesTheTwoSegmentFormatWithBothComponentsAsArguments() {
		MutableComponent base = Component.literal("Ping For It Configuration")
			.withStyle(ChatFormatting.GOLD);
		MutableComponent scope = Component.literal("Server").withStyle(ChatFormatting.GRAY);

		Component title = SettingsTitleWidget.composeTitle(base, scope, null);

		assertTitleArguments(title, "settings.pingforit.title.scope", base, scope);
	}

	@Test
	void leafTitleUsesTheBreadcrumbFormatWithAllComponentsAsArguments() {
		MutableComponent base = Component.literal("Ping For It Configuration")
			.withStyle(ChatFormatting.GOLD);
		MutableComponent scope = Component.literal("Server").withStyle(ChatFormatting.GRAY);
		MutableComponent category = Component.literal("Send Rate").withStyle(ChatFormatting.AQUA);

		Component title = SettingsTitleWidget.composeTitle(base, scope, category);

		assertTitleArguments(title, "settings.pingforit.title.breadcrumb", base, scope, category);
	}

	@Test
	void localeFormatControlsSeparatorAndArgumentOrder() {
		MutableComponent base = Component.literal("Ping For It Configuration");
		MutableComponent scope = Component.literal("Server");
		MutableComponent category = Component.literal("Send Rate");
		Map<String, String> translations = Map.of(
			"settings.pingforit.title.breadcrumb", "%3$s | %1$s | %2$s",
			"settings.pingforit.title.scope", "%2$s | %1$s");

		withLanguage(translations, () -> {
			assertEquals(
				"Send Rate | Ping For It Configuration | Server",
				SettingsTitleWidget.composeTitle(base, scope, category).getString());
			assertEquals(
				"Server | Ping For It Configuration",
				SettingsTitleWidget.composeTitle(base, scope, null).getString());
		});
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

	private static void assertTitleArguments(Component title, String expectedKey, Component... expected) {
		TranslatableContents contents = assertTranslatable(title, expectedKey);
		Object[] actual = contents.getArgs();
		assertEquals(
			expected.length,
			actual.length,
			"the localized title must keep every semantic segment as a format argument");
		for (int index = 0; index < expected.length; index++) {
			assertInstanceOf(
				Component.class,
				actual[index],
				"format argument " + index + " must remain a component instead of flattened text");
			assertEquals(
				expected[index],
				actual[index],
				"format argument " + index + " must keep the semantic segment style and hierarchy");
		}
	}

	private static void assertTranslatableKey(String expectedKey, Component title) {
		assertTranslatable(title, expectedKey);
	}

	private static TranslatableContents assertTranslatable(Component title, String expectedKey) {
		ComponentContents contents = title.getContents();
		assertTrue(
			contents instanceof TranslatableContents,
			() -> "expected a translatable component, got: " + contents);
		TranslatableContents translatable = (TranslatableContents) contents;
		assertEquals(expectedKey, translatable.getKey());
		return translatable;
	}

	private static void withLanguage(Map<String, String> translations, Runnable body) {
		Language previous = Language.getInstance();
		Language.inject(new MapLanguage(translations));
		try {
			body.run();
		} finally {
			Language.inject(previous);
		}
	}

	@NotNullByDefault
	private static final class MapLanguage extends Language {
		private final @Unmodifiable Map<String, String> translations;

		private MapLanguage(Map<String, String> translations) {
			this.translations = Map.copyOf(translations);
		}

		@Override
		public String getOrDefault(String key, String fallback) {
			return this.translations.getOrDefault(key, fallback);
		}

		@Override
		public boolean has(String key) {
			return this.translations.containsKey(key);
		}

		@Override
		public boolean isDefaultRightToLeft() {
			return false;
		}

		@Override
		public FormattedCharSequence getVisualOrder(FormattedText text) {
			return FormattedCharSequence.EMPTY;
		}
	}
}
