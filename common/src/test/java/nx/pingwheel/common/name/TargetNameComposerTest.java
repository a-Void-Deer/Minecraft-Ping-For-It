package nx.pingwheel.common.name;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetNameComposerTest {

	/**
	 * Asserts that no node of the component tree carries a text color.
	 */
	private static void assertNoColor(Component component) {
		assertNull(component.getStyle().getColor(), () -> "unexpected color on: " + component);

		for (Component sibling : component.getSiblings()) {
			assertNoColor(sibling);
		}
	}

	/**
	 * The plain text of a literal contents node (no translation resolution, so
	 * no game language state is required).
	 */
	private static String literalText(Component component) {
		assertTrue(component.getContents() instanceof PlainTextContents.LiteralContents,
			() -> "expected literal contents, got: " + component.getContents().getClass());
		return ((PlainTextContents.LiteralContents) component.getContents()).text();
	}

	// --- compose ---

	@Test
	void composeFormatsCustomNameInParenthesesBeforeBase() {
		Component base = Component.translatable("minecraft.zombie");

		Component result = TargetNameComposer.compose(Component.literal("Bob"), base);

		// Structure: literal "Bob" + literal " (" + base + literal ")".
		assertEquals("Bob", literalText(result));

		List<Component> siblings = result.getSiblings();
		assertEquals(3, siblings.size());
		assertEquals(" (", literalText(siblings.get(0)));
		assertSame(base, siblings.get(1));
		assertEquals(")", literalText(siblings.get(2)));
	}

	@Test
	void composeStripsCustomStyleAndEvents() {
		Component styledCustom = Component.literal("Bob")
			.withColor(0xFF0000)
			.withStyle(style -> style.withItalic(true));

		Component result = TargetNameComposer.compose(
			styledCustom, Component.translatable("minecraft.zombie"));

		assertEquals("Bob", literalText(result));
		assertNoColor(result);
		assertTrue(result.getStyle().isEmpty(), "the composed custom text must be unstyled");
		assertTrue(result.getSiblings().get(0).getStyle().isEmpty(), "the '(' literal must be unstyled");
		assertTrue(result.getSiblings().get(2).getStyle().isEmpty(), "the ')' literal must be unstyled");
	}

	@Test
	void composeAppendsTrustedBaseUnchanged() {
		Component base = Component.translatable("minecraft.zombie");

		Component result = TargetNameComposer.compose(Component.literal("Bob"), base);

		// The base is the third child (root text + " (" + base + ")").
		assertSame(base, result.getSiblings().get(1));
		assertNoColor(result);
	}

	@Test
	void composeAddsNoColorToUnstyledBase() {
		Component result = TargetNameComposer.compose(
			Component.literal("Bob"), Component.translatable("minecraft.zombie"));

		assertNoColor(result);
	}

	@Test
	void composeRejectsNulls() {
		assertThrows(NullPointerException.class,
			() -> TargetNameComposer.compose(null, Component.translatable("a")));
		assertThrows(NullPointerException.class,
			() -> TargetNameComposer.compose(Component.literal("a"), null));
	}

	// --- here / unknown ---

	@Test
	void hereIsPlainTranslatableHereKey() {
		Component here = TargetNameComposer.here();

		assertEquals("pingforit.target.here", translatableKey(here));
		assertNoColor(here);
	}

	@Test
	void unknownIsPlainTranslatableUnknownKey() {
		Component unknown = TargetNameComposer.unknown();

		assertEquals("pingforit.target.unknown", translatableKey(unknown));
		assertNoColor(unknown);
	}

	private static String translatableKey(Component component) {
		assertTrue(component.getContents() instanceof TranslatableContents, "expected a translatable component");

		return ((TranslatableContents) component.getContents()).getKey();
	}
}
