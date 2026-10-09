package nx.pingwheel.common.chat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentChatTemplateTest {

	private static final Component AUTHOR = Component.literal("Steve");
	private static final Component TARGET = Component.literal("Chest");
	private static final Component CONTENT = Component.literal("Gunpowder x96");

	private static PingType type(String id) {
		return PingTypeCatalog.builtIn().findById(id).orElseThrow();
	}

	@Test
	void allFourTokensCanBeReorderedByTheLocalizedTemplate() {
		Component message = ContentChatTemplate.build("{content} <- {type} <- {target} <- {author}",
			AUTHOR, type("take"), TARGET, CONTENT);

		List<Component> parts = message.getSiblings();
		assertEquals(7, parts.size());
		assertSame(CONTENT, parts.get(0));
		assertEquals(" <- ", parts.get(1).getString());
		assertEquals("presentation.pingforit.type.take.display", translatableKey(parts.get(2)));
		assertEquals(" <- ", parts.get(3).getString());
		assertSame(TARGET, parts.get(4));
		assertEquals(" <- ", parts.get(5).getString());
		assertSame(AUTHOR, parts.get(6));
		assertNull(message.getStyle().getColor(), "the message root stays uncolored");
	}

	@Test
	void onlyTheTypeTokenCarriesTheAnnotationTextColor() {
		PingType annotation = type("request");
		Component message = ContentChatTemplate.build("{author}|{type}|{target}|{content}",
			AUTHOR, annotation, TARGET, CONTENT);

		assertOnlyTypeColored(message, null, annotation);
	}

	@Test
	void repeatedTokensAreAllowedAndEscapedBracesStayLiteral() {
		Component message = ContentChatTemplate.build(
			"{{{author}}} {{literal}} {type} {target} {content} {author}",
			AUTHOR, type("attention"), TARGET, CONTENT);

		List<Component> parts = message.getSiblings();
		assertEquals(10, parts.size());
		assertEquals("{", parts.get(0).getString());
		assertSame(AUTHOR, parts.get(1));
		assertEquals("} {literal} ", parts.get(2).getString());
		assertEquals("presentation.pingforit.type.attention.display", translatableKey(parts.get(3)));
		assertEquals(" ", parts.get(4).getString());
		assertSame(TARGET, parts.get(5));
		assertEquals(" ", parts.get(6).getString());
		assertSame(CONTENT, parts.get(7));
		assertEquals(" ", parts.get(8).getString());
		assertSame(AUTHOR, parts.get(9));
	}

	@Test
	void literalPercentSequencesAreNeverFormattedAgain() {
		Component message = ContentChatTemplate.build("{author} 100%% %s {type} {target} {content}",
			AUTHOR, type("attention"), TARGET, CONTENT);

		String collected = collectText(message);
		assertTrue(collected.contains("100%%"), "a literal double percent stays literal");
		assertTrue(collected.contains(" %s "), "a literal percent-s stays literal");
	}

	@Test
	void everyRequiredTokenMustOccurAndUnknownOrUnmatchedTemplatesFallBackToTheSafeDefault() {
		for (String template : List.of(
			"{type} {target} {content}",
			"{author} {target} {content}",
			"{author} {type} {content}",
			"{author} {type} {target}",
			"{{author}} {type} {target} {content}",
			"{author} {pingType} {target} {content}",
			"{author} {type} {target} {content",
			"{author} {type} {target} {content} }")) {
			Component message = ContentChatTemplate.build(template, AUTHOR, type("attention"), TARGET, CONTENT);
			assertSafeDefault(message, type("attention"));
		}

		Component nullTemplate = ContentChatTemplate.build(null, AUTHOR, type("attention"), TARGET, CONTENT);
		assertSafeDefault(nullTemplate, type("attention"));
	}

	@Test
	void parseReportsMalformedTemplatesInsteadOfBuildingAPartialLine() {
		assertTrue(ContentChatTemplate.parse("{author} {type} {target}", AUTHOR, type("attention"), TARGET, CONTENT)
			.isEmpty());
		assertTrue(ContentChatTemplate.parse(null, AUTHOR, type("attention"), TARGET, CONTENT).isEmpty());
		assertTrue(ContentChatTemplate.parse("{author} {type} {target} {content}",
			AUTHOR, type("attention"), TARGET, CONTENT).isPresent());
		assertThrows(NullPointerException.class,
			() -> ContentChatTemplate.parse("{author} {type} {target} {content}", null, type("attention"), TARGET, CONTENT));
	}

	@Test
	void overrideSelectionReusesTheSelectedLocaleOnlyPresencePolicy() {
		PingType request = type("request");
		Map<String, String> selectedLocale = Map.of(
			ContentChatTemplate.TEMPLATE_KEY, "base",
			ContentChatTemplate.templateOverrideKey(request), "override");
		Map<String, String> mergedFallbackOnly = Map.of(ContentChatTemplate.TEMPLATE_KEY, "base");

		assertEquals(ContentChatTemplate.templateOverrideKey(request),
			ContentChatTemplate.selectTemplateKey(request, selectedLocale::containsKey));
		assertEquals(ContentChatTemplate.TEMPLATE_KEY,
			ContentChatTemplate.selectTemplateKey(request, mergedFallbackOnly::containsKey));
	}

	@Test
	void listTemplatesOwnTheirGrammarWithoutAFirstTypeForTheWholeSet() {
		Component entry = ContentChatTemplate.buildEntry("{{{content}}} <- {type} 100%% %s", type("danger"), CONTENT);
		Component message = ContentChatTemplate.buildMultiple("{content} <- {target} <- {{{author}}}", AUTHOR, TARGET, entry);
		assertTrue(collectText(message).contains("100%% %s"));
		assertTrue(containsIdentity(message, AUTHOR));
		assertTrue(containsIdentity(message, TARGET));
		assertTrue(containsIdentity(message, CONTENT));
		assertEquals(Set.of("presentation.pingforit.type.danger.display"), collectKeys(message),
			"the outer template adds no main/default annotation");
		assertOnlyTypeColored(message, null, type("danger"));
	}

	@Test
	void invalidListTemplatesFallBackWithinTheirOwnFamilyAndPreserveEveryEntry() {
		for (String template : java.util.Arrays.asList(null, "{type}", "{content}", "{type} {content} {target}",
			"{type} {content", "{type} {content} }")) {
			Component entry = ContentChatTemplate.buildEntry(template, type("danger"), CONTENT);
			assertTrue(containsIdentity(entry, CONTENT));
			assertEquals(Set.of("presentation.pingforit.type.danger.display"), collectKeys(entry));
		}
		Component entry = ContentChatTemplate.buildEntry(null, type("danger"), CONTENT);
		for (String template : java.util.Arrays.asList(null, "{author} {target}", "{target} {content}",
			"{author} {content}", "{author} {target} {content} {type}", "{author} {target} {content", "}")) {
			Component message = ContentChatTemplate.buildMultiple(template, AUTHOR, TARGET, entry);
			assertTrue(containsIdentity(message, AUTHOR));
			assertTrue(containsIdentity(message, TARGET));
			assertTrue(containsIdentity(message, CONTENT));
			assertEquals(Set.of("presentation.pingforit.type.danger.display"), collectKeys(message));
		}
	}

	@Test
	void entryOverridesUseOnlySelectedLocalePresenceAndOldLambdaSourcesRemainCompatible() {
		PingType danger = type("danger");
		assertEquals(ContentChatTemplate.entryOverrideKey(danger), ContentChatTemplate.selectEntryKey(danger,
			key -> key.equals(ContentChatTemplate.entryOverrideKey(danger))));
		assertEquals(ContentChatTemplate.ENTRY_TEMPLATE_KEY, ContentChatTemplate.selectEntryKey(danger, key -> false));
		ContentChatComposer.TemplateSource legacy = ignored -> "{author} {type} {target} {content}";
		assertEquals(ContentChatTemplate.DEFAULT_MULTIPLE_TEMPLATE, legacy.multipleTemplate());
		assertEquals(ContentChatTemplate.DEFAULT_ENTRY_TEMPLATE, legacy.entryTemplate(danger));
	}

	private static void assertSafeDefault(Component message, PingType annotation) {
		assertTrue(containsIdentity(message, AUTHOR), "the safe default keeps the author");
		assertTrue(containsIdentity(message, TARGET), "the safe default keeps the target");
		assertTrue(containsIdentity(message, CONTENT), "the safe default keeps the content");
		assertTrue(collectKeys(message).contains("presentation.pingforit.type." + annotation.id() + ".display"),
			"the safe default keeps the annotation phrase");
		assertFalse(collectKeys(message).contains("pingforit.chat.pingmsg"),
			"the content family never falls back to the whole-message family");
	}

	private static String translatableKey(Component component) {
		assertInstanceOf(TranslatableContents.class, component.getContents());
		return ((TranslatableContents) component.getContents()).getKey();
	}

	private static void assertOnlyTypeColored(Component component, Integer inherited, PingType annotation) {
		Integer effective = component.getStyle().getColor() == null
			? inherited : Integer.valueOf(component.getStyle().getColor().getValue());
		boolean isType = component.getContents() instanceof TranslatableContents contents
			&& contents.getKey().equals("presentation.pingforit.type." + annotation.id() + ".display");
		if (isType) assertEquals(annotation.textColor(), effective, "only the type token is colored");
		else assertNull(effective, () -> "unexpected effective color on: " + component.getContents());

		if (component.getContents() instanceof TranslatableContents contents)
			for (Object arg : contents.getArgs())
				if (arg instanceof Component child) assertOnlyTypeColored(child, effective, annotation);
		for (Component sibling : component.getSiblings()) assertOnlyTypeColored(sibling, effective, annotation);
	}

	private static boolean containsIdentity(Component component, Component expected) {
		if (component == expected) return true;
		if (component.getContents() instanceof TranslatableContents contents)
			for (Object arg : contents.getArgs())
				if (arg instanceof Component child && containsIdentity(child, expected)) return true;
		for (Component sibling : component.getSiblings())
			if (containsIdentity(sibling, expected)) return true;
		return false;
	}

	private static Set<String> collectKeys(Component component) {
		Set<String> keys = new HashSet<>();
		if (component.getContents() instanceof TranslatableContents contents) {
			keys.add(contents.getKey());
			for (Object arg : contents.getArgs())
				if (arg instanceof Component child) keys.addAll(collectKeys(child));
		}
		for (Component sibling : component.getSiblings()) keys.addAll(collectKeys(sibling));
		return keys;
	}

	/** Raw tree text independent of any injected language. */
	private static String collectText(Component component) {
		StringBuilder text = new StringBuilder();
		collectText(component, text);
		return text.toString();
	}

	private static void collectText(Component component, StringBuilder text) {
		if (component.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents plain)
			text.append(plain.text());
		else if (component.getContents() instanceof TranslatableContents contents) {
			text.append(contents.getKey());
			for (Object arg : contents.getArgs()) {
				if (arg instanceof Component child) collectText(child, text);
				else text.append(arg);
			}
		}
		for (Component sibling : component.getSiblings()) collectText(sibling, text);
	}
}
