package nx.pingwheel.common.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.monitor.client.MonitorLayoutState;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryMonitorHudCacheTest {

	@Test void retainedLayoutInvalidatesViewWidthFontLanguageAndSameFontGlyphOptions() {
		var key = new MonitorLayoutState();
		var view = view();
		var font = font();
		var language = new TestLanguage();
		assertTrue(key.update(view, 6, 427, font, language, false, false));
		assertFalse(key.update(view, 6, 427, font, language, false, false));
		assertTrue(key.update(view, 6, 320, font, language, false, false));
		assertFalse(key.update(view, 6, 320, font, language, false, false), "pure scale/height changes are not layout identities");
		assertTrue(key.update(view, 6, 320, font, language, true, false), "Force Unicode mutates glyph metrics without replacing Font");
		assertFalse(key.update(view, 6, 320, font, language, true, false));
		assertTrue(key.update(view, 6, 320, font, language, true, true), "Japanese variants also mutate the retained Font");
		font = font();
		assertTrue(key.update(view, 6, 320, font, language, true, true));
		language = new TestLanguage();
		assertTrue(key.update(view, 6, 320, font, language, true, true), "same-locale resource reload installs a new Language");
		view = view();
		assertTrue(key.update(view, 6, 320, font, language, true, true), "new immutable view rebuilds even if record-equal");
		assertFalse(key.update(view, 6, 320, font, language, true, true));
		key.reset();
		assertTrue(key.update(view, 6, 320, font, language, true, true), "disconnect cannot retain prepared identity");
	}

	private static ClientMonitorState.View view() {
		return new ClientMonitorState.View(true, Metric.defaults(), ClientMonitorState.Status.OFF,
			ClientMonitorState.Status.OFF, ClientMonitorState.Status.DEPENDENCY_OFF, null, null);
	}

	@Test void nativeSameLocaleResourceReloadProvidesAChangedLanguageSignalToTheRetainedKey() throws ReflectiveOperationException {
		Language previous = Language.getInstance();
		var i18nLanguage = net.minecraft.client.resources.language.I18n.class.getDeclaredMethod("setLanguage", Language.class);
		i18nLanguage.setAccessible(true);
		try (var resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, java.util.List.of())) {
			var manager = new LanguageManager("en_us", ignored -> {});
			manager.setSelected("en_us");
			var font = font();
			var key = new MonitorLayoutState();
			var view = view();
			manager.onResourceManagerReload(resources);
			Language first = Language.getInstance();
			assertTrue(key.update(view, 6, 427, font, first, false, false));
			assertFalse(key.update(view, 6, 427, font, first, false, false));
			manager.onResourceManagerReload(resources);
			Language second = Language.getInstance();
			assertNotSame(first, second);
			assertEquals("en_us", manager.getSelected());
			assertTrue(key.update(view, 6, 427, font, second, false, false));
		} finally { Language.inject(previous); i18nLanguage.invoke(null, previous); }
	}

	@NotNullByDefault
	private static final class TestLanguage extends Language {
		@Override public String getOrDefault(String key, String fallback) { return fallback; }
		@Override public boolean has(String key) { return false; }
		@Override public boolean isDefaultRightToLeft() { return false; }
		@Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
	}

	private static Font font() { return new Font(ignored -> { throw new AssertionError("identity cache must not request glyphs"); }, false); }
}
