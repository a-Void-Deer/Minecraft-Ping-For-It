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

	// --- language templates ---

	@Test
	void namedTemplateControlsOrderAndLiteralComposition() {
		Component target = Component.translatable("pingforit.target.here");
		Component chat = PingChatBuilder.build(
			"<{targetName}> says [{pingType}] -- {playerName}!",
			"Steve", pingType("attention"), target);

		var parts = chat.getSiblings();

		assertEquals(7, parts.size());
		assertEquals("<", parts.get(0).getString());
		assertSame(target, parts.get(1));
		assertEquals("> says [", parts.get(2).getString());
		assertEquals("pingforit.ping_type.attention.phrase", assertTranslatable(parts.get(3)).getKey());
		assertEquals("] -- ", parts.get(4).getString());
		assertEquals("Steve", parts.get(5).getString());
		assertEquals("!", parts.get(6).getString());
	}

	@Test
	void namedTemplatePreservesAllComponentStylesAndOnlyColorsPhrase() {
		Component target = Component.translatable("pingforit.target.here");
		PingType pingType = pingType("request");
		Component chat = PingChatBuilder.build(
			"{playerName}|{pingType}|{targetName}", "Steve", pingType, target);

		var parts = chat.getSiblings();
		assertEquals(5, parts.size());
		assertEquals("Steve", parts.get(0).getString());
		assertTrue(parts.get(0).getStyle().isEmpty());
		assertEquals("|", parts.get(1).getString());
		assertTrue(parts.get(1).getStyle().isEmpty());
		assertEquals(pingType.phraseKey(), assertTranslatable(parts.get(2)).getKey());
		assertEquals(pingType.textColor(), parts.get(2).getStyle().getColor().getValue());
		assertEquals("|", parts.get(3).getString());
		assertTrue(parts.get(3).getStyle().isEmpty());
		assertSame(target, parts.get(4));
		assertNull(parts.get(4).getStyle().getColor());
		assertNull(chat.getStyle().getColor());
	}

	@Test
	void namedTemplateSupportsEscapedBracesAndRepeatedPlaceholders() {
		Component target = Component.translatable("pingforit.target.here");
		Component chat = PingChatBuilder.build(
			"{targetName} {{literal}} {targetName} {playerName} {pingType}",
			"Steve", pingType("attention"), target);

		var parts = chat.getSiblings();
		assertEquals(7, parts.size());
		assertSame(target, parts.get(0));
		assertEquals(" {literal} ", parts.get(1).getString());
		assertSame(target, parts.get(2));
		assertEquals(" ", parts.get(3).getString());
		assertEquals("Steve", parts.get(4).getString());
		assertEquals(" ", parts.get(5).getString());
		assertTrue(parts.get(6).getContents() instanceof TranslatableContents);
	}

	@Test
	void eachMissingNamedPlaceholderFallsBackToCurrentTranslatableShape() {
		PingType pingType = pingType("attention");
		Component target = Component.translatable("pingforit.target.here");

		for (String template : List.of(
			"{pingType} {targetName}",
			"{playerName} {targetName}",
			"{playerName} {pingType}")) {
			Component fallback = PingChatBuilder.build(template, "Steve", pingType, target);
			TranslatableContents contents = assertTranslatable(fallback);
			assertEquals("pingforit.chat.request", contents.getKey());
			assertEquals(3, contents.getArgs().length);
		}
	}

	@Test
	void nullUnknownAndUnmatchedTemplatesFallBack() {
		PingType pingType = pingType("attention");
		Component target = Component.translatable("pingforit.target.here");

		for (String template : new String[] {
			null,
			"{playerName} {pingType} {unknown} {targetName}",
			"{playerName} {pingType} {targetName",
			"{playerName} {pingType} {targetName}" + "}"}) {
			assertEquals("pingforit.chat.request",
				assertTranslatable(PingChatBuilder.build(template, "Steve", pingType, target)).getKey());
		}
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
