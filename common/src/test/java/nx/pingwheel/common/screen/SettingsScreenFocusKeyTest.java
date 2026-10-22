package nx.pingwheel.common.screen;

import java.util.Map;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class SettingsScreenFocusKeyTest {

	@Test
	void missingListAndMissingFocusResolveWithoutRequestingAListChild() {
		final boolean[] childRequested = { false };

		String key = SettingsScreen.resolveFocusKey(
			null,
			null,
			() -> {
				childRequested[0] = true;
				throw new AssertionError("the list child must not be requested while the list is missing");
			},
			Map.of());

		assertNull(key);
		assertFalse(childRequested[0], "the missing list must short-circuit before its child lookup");
	}

	@Test
	void focusedListContainerResolvesItsFocusedChildKey() {
		final AbstractWidget list = widget();
		final AbstractWidget child = widget();

		String key = SettingsScreen.resolveFocusKey(list, list, () -> child, Map.of("child", child));

		assertEquals("child", key);
	}

	@Test
	void directlyFocusedWidgetKeepsItsKeyWithoutConsultingTheListChild() {
		final boolean[] childRequested = { false };
		final AbstractWidget list = widget();
		final AbstractWidget direct = widget();

		String key = SettingsScreen.resolveFocusKey(
			direct,
			list,
			() -> {
				childRequested[0] = true;
				return null;
			},
			Map.of("direct", direct));

		assertEquals("direct", key);
		assertFalse(childRequested[0]);
	}

	@Test
	void independentlyFocusedKeyedWidgetStillResolvesWhenNothingIsFocused() {
		final AbstractWidget list = widget();
		final AbstractWidget focused = widget();
		focused.setFocused(true);

		String key = SettingsScreen.resolveFocusKey(null, list, () -> null, Map.of("focused", focused));

		assertEquals("focused", key);
	}

	private static AbstractWidget widget() {
		return Button.builder(Component.literal("focus"), ignored -> {}).bounds(0, 0, 20, 20).build();
	}
}
