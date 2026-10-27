package nx.pingwheel.common.screen;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarrationThunk;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.config.PlayerInfoMode;
import nx.pingwheel.common.config.TeamColorMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the screen's real enum formatters through native OptionInstance/CycleButton label and narration. */
@NotNullByDefault
class SettingsEnumOptionTest {
	@Test
	void eachEnumControlHasExactlyOneNativeCaptionAndKeepsDynamicHelpWhenItsDisplayedValueChanges() {
		Language previous = Language.getInstance();
		Language.inject(new TestLanguage(previous, Map.of(
			"options.generic_value", "%1$s: %2$s",
			"gui.narrate.button", "Button %s",
			"settings.pingforit.format.name_value", "%1$s: %2$s",
			"settings.pingforit.player_info_mode", "Author label",
			"settings.pingforit.team_color_mode", "Team formatting",
			"settings.pingforit.entity_block_render_mode", "Entity geometry")));
		try {
			var config = new ClientConfig();
			config.setPlayerInfoMode(PlayerInfoMode.ALWAYS);
			config.setTeamColorMode(TeamColorMode.FULL);
			config.setEntityBlockRenderMode(EntityBlockRenderMode.ALL);
			var screen = new SettingsScreen(config, null);
			assertEnum(screen.getPlayerInfoModeOption(), "settings.pingforit.player_info_mode", "Author label",
				PlayerInfoMode.DISABLED, PlayerInfoMode.ALWAYS, PlayerInfoMode.COMPACT);
			assertEnum(screen.getTeamColorModeOption(), "settings.pingforit.team_color_mode", "Team formatting",
				TeamColorMode.values());
			assertEnum(screen.getEntityBlockRenderModeOption(), "settings.pingforit.entity_block_render_mode", "Entity geometry",
				EntityBlockRenderMode.values());
			assertEquals(PlayerInfoMode.ALWAYS, config.getPlayerInfoMode());
			assertEquals(TeamColorMode.FULL, config.getTeamColorMode());
			assertEquals(EntityBlockRenderMode.ALL, config.getEntityBlockRenderMode());
		} finally {
			Language.inject(previous);
		}
	}

	@SafeVarargs
	private static <E extends Enum<E>> void assertEnum(OptionInstance<E> option, String settingKey, String caption,
		E @Unmodifiable ... values) {
		// Native enum construction does not dereference Options; setValue updates
		// native labels/tooltips without invoking the persistence callback or GLFW.
		CycleButton<?> nativeButton = assertInstanceOf(CycleButton.class, option.createButton(null, 0, 0, 310));
		CycleButton<E> button = typedButton(nativeButton);
		assertCaption(button, caption, option.get());
		for (E value : values) {
			button.setValue(value);
			assertCaption(button, caption, value);
			var tooltip = assertInstanceOf(Tooltip.class, button.getTooltip());
			Component help = tooltipMessage(tooltip);
			assertEquals(settingKey + "." + value + ".tooltip",
				assertInstanceOf(TranslatableContents.class, help.getContents()).getKey());
		}
	}

	private static <E extends Enum<E>> void assertCaption(CycleButton<E> button, String caption, E value) {
		String expected = caption + ": " + Component.translatable("value.pingforit." + value).getString();
		assertEquals(expected, button.getMessage().getString());
		assertEquals("Button " + expected, button.createDefaultNarrationMessage().getString());
	}

	@SuppressWarnings("unchecked")
	private static <E> CycleButton<E> typedButton(CycleButton<?> button) {
		return (CycleButton<E>) button;
	}

	private static Component tooltipMessage(Tooltip tooltip) {
		var collector = new TooltipNarration();
		tooltip.updateNarration(collector);
		return collector.message;
	}

	@NotNullByDefault
	private static final class TooltipNarration implements NarrationElementOutput {
		private Component message = Component.empty();

		@Override
		public void add(NarratedElementType type, NarrationThunk<?> contents) {
			throw new AssertionError("the native tooltip should narrate its Component directly");
		}

		@Override
		public void add(NarratedElementType type, Component contents) {
			this.message = contents;
		}

		@Override
		public NarrationElementOutput nest() {
			return this;
		}
	}

	@NotNullByDefault
	private static final class TestLanguage extends Language {
		private final Language fallback;
		private final @Unmodifiable Map<String, String> translations;

		private TestLanguage(Language fallback, Map<String, String> translations) {
			this.fallback = fallback;
			this.translations = Map.copyOf(translations);
		}

		@Override
		public String getOrDefault(String key, String defaultValue) {
			return this.translations.getOrDefault(key, this.fallback.getOrDefault(key, defaultValue));
		}

		@Override
		public boolean has(String key) {
			return this.translations.containsKey(key) || this.fallback.has(key);
		}

		@Override
		public boolean isDefaultRightToLeft() {
			return false;
		}

		@Override
		public FormattedCharSequence getVisualOrder(FormattedText text) {
			return this.fallback.getVisualOrder(text);
		}
	}
}
