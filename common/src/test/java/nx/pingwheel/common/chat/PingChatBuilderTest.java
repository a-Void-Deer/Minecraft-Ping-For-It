package nx.pingwheel.common.chat;

import java.util.List;
import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PingChatBuilderTest {

	private static Component chatFor(PingType pingType) {
		return PingChatBuilder.build("Steve", pingType, Component.translatable("pingforit.target.here"));
	}

	// --- template shape ---

	@Test
	void buildUsesTheRequestTemplateWithThreeArgs() {
		Component chat = chatFor(pingType("attention"));

		TranslatableContents contents = assertTranslatable(chat);
		assertEquals("pingforit.chat.request", contents.getKey());
		assertEquals(3, contents.getArgs().length);
	}

	@Test
	void playerArgumentIsPlainLiteralProfileName() {
		Component chat = chatFor(pingType("attention"));

		Component player = (Component) assertTranslatable(chat).getArgs()[0];

		assertEquals("Steve", player.getString());
		assertNull(player.getStyle().getColor(), "player name must not be colored");
		assertTrue(player.getStyle().isEmpty(), "player name must be plain/default-styled");
	}

	@Test
	void targetArgumentIsPassedThroughUnchangedAndUncolored() {
		Component target = Component.translatable("pingforit.target.here");
		Component chat = PingChatBuilder.build("Steve", pingType("attention"), target);

		Component passed = (Component) assertTranslatable(chat).getArgs()[2];

		assertSame(target, passed);
		assertNull(passed.getStyle().getColor(), "target name must not be colored");
	}

	@Test
	void templateStyleIsDefaultWithNoColor() {
		Component chat = chatFor(pingType("attention"));

		assertNull(chat.getStyle().getColor(), "the template must not be colored");
	}

	// --- phrase coloring ---

	@Test
	void onlyThePhraseArgumentCarriesTextColor() {
		PingType pingType = pingType("attention");
		Component chat = chatFor(pingType);

		Object[] args = assertTranslatable(chat).getArgs();

		assertNull(chat.getStyle().getColor());
		assertNull(((Component) args[0]).getStyle().getColor());
		assertNull(((Component) args[2]).getStyle().getColor());

		Component phrase = (Component) args[1];
		assertEquals(pingType.phraseKey(), assertTranslatable(phrase).getKey());
		assertEquals(pingType.textColor(), phrase.getStyle().getColor().getValue());
	}

	@Test
	void allSevenBuiltInPingTypesUseExactColorsAndPhraseKeys() {
		Map<String, Integer> expectedTextColors = Map.of(
			"attention", 0xFFAA00,
			"danger", 0xFF5555,
			"go_to", 0x55FFFF,
			"loot", 0x55FF55,
			"destroy", 0xF0A0EA,
			"take", 0x55FF55,
			"request", 0xB8B8FF);

		List<PingType> entries = PingTypeCatalog.builtIn().entries();
		assertEquals(7, entries.size());

		for (PingType pingType : entries) {
			Component chat = chatFor(pingType);
			Component phrase = (Component) assertTranslatable(chat).getArgs()[1];

			assertEquals("pingforit.ping_type." + pingType.id() + ".phrase",
				assertTranslatable(phrase).getKey());
			assertEquals(expectedTextColors.get(pingType.id()), phrase.getStyle().getColor().getValue(),
				() -> "wrong text color for " + pingType.id());
		}
	}

	// --- contract ---

	@Test
	void buildRejectsNulls() {
		PingType pingType = pingType("attention");
		Component target = Component.translatable("pingforit.target.here");

		assertThrows(NullPointerException.class, () -> PingChatBuilder.build(null, pingType, target));
		assertThrows(NullPointerException.class, () -> PingChatBuilder.build("Steve", null, target));
		assertThrows(NullPointerException.class, () -> PingChatBuilder.build("Steve", pingType, null));
	}

	private static PingType pingType(String id) {
		return PingTypeCatalog.builtIn().findById(id).orElseThrow();
	}

	private static TranslatableContents assertTranslatable(Component component) {
		assertTrue(component.getContents() instanceof TranslatableContents,
			() -> "expected translatable, got: " + component.getContents().getClass());
		return (TranslatableContents) component.getContents();
	}
}
