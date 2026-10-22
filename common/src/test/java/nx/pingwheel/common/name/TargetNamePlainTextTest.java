package nx.pingwheel.common.name;

import java.nio.charset.StandardCharsets;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.presentation.PresentationLimits;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TargetNamePlainTextTest {
	@Test void utf8BoundKeepsWholeSupplementaryCodePointsAtEveryCut() {
		String text = "A\uD83D\uDE00\u4E2DZ";
		Component custom = Component.literal(text);
		String[] prefixes = {"", "A", "A", "A", "A", "A\uD83D\uDE00", "A\uD83D\uDE00",
			"A\uD83D\uDE00", "A\uD83D\uDE00\u4E2D", text};
		for (int bytes = 0; bytes < prefixes.length; bytes++) {
			String bounded = TargetNameComposer.plainText(custom, bytes);
			assertEquals(prefixes[bytes], bounded, "UTF-8 bound " + bytes);
			assertTrue(bounded.getBytes(StandardCharsets.UTF_8).length <= bytes);
			assertEquals(bounded, new String(bounded.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
				"a truncated surrogate must not turn into a replacement byte");
		}
	}

	@Test void presentationLimitBoundsEmojiWithoutLosingLiteralPunctuationOrAddingBaseText() {
		String prefix = "\"literal (name)\" ";
		String text = prefix + "\uD83D\uDE00".repeat(PresentationLimits.MAX_TEXT_BYTES);
		String bounded = TargetNameComposer.plainText(Component.literal(text), PresentationLimits.MAX_TEXT_BYTES);
		int emojiCount = (PresentationLimits.MAX_TEXT_BYTES - prefix.getBytes(StandardCharsets.UTF_8).length) / 4;
		assertEquals(prefix + "\uD83D\uDE00".repeat(emojiCount), bounded);
		assertTrue(bounded.getBytes(StandardCharsets.UTF_8).length <= PresentationLimits.MAX_TEXT_BYTES);
		assertEquals(emojiCount, bounded.codePoints().filter(point -> point == 0x1F600).count());
		assertEquals("", TargetNameComposer.plainText(Component.empty(), PresentationLimits.MAX_TEXT_BYTES));
	}
}
