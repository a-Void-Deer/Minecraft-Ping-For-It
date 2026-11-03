package nx.pingwheel.common.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Non-interactive fixed-header title rendering a plain-text breadcrumb at a
 * slightly enlarged native-font scale and wrapping it to the available width.
 *
 * <p>The screen lays the widget out through
 * {@link #layout(int, int, int, ToIntFunction)} so the header can reserve one
 * scaled row per wrapped line.  Measurement is injected so wrap and height
 * rules stay testable without a live font.
 */
final class SettingsTitleWidget extends AbstractWidget {
	static final float TEXT_SCALE = 1.25F;
	static final int LINE_GAP = 2;
	static final String SEGMENT_SEPARATOR = " -> ";

	private static final int HORIZONTAL_MARGIN = 4;
	private static final int TITLE_TEXT_COLOR = 0xFFFFFF;
	private static final int FALLBACK_LINE_HEIGHT = 9;

	private final Font font;
	private List<String> lines = List.of();
	private int lineHeight = scaledLineHeight(FALLBACK_LINE_HEIGHT);

	SettingsTitleWidget(Font font) {
		super(0, 0, 0, 0, Component.empty());
		this.font = font;
	}

	/**
	 * Composes the localized breadcrumb from semantic segments.  A null scope
	 * means the root page and returns the base segment unchanged; the separator
	 * is neutral and never translated.
	 */
	static MutableComponent composeTitle(MutableComponent base, Component scope, Component category) {
		if (scope == null) {
			return base;
		}

		final MutableComponent title = Component.empty().append(base).append(SEGMENT_SEPARATOR).append(scope);
		if (category != null) {
			title.append(SEGMENT_SEPARATOR).append(category);
		}
		return title;
	}

	/**
	 * Wraps the current message, applies explicit bounds and returns the title
	 * block height.  Size and position use their explicit setters because the
	 * overloaded {@code setRectangle(width, height, x, y)} order is easy to
	 * misread and silently produced a zero-width, off-screen title.
	 */
	int layout(int screenWidth, int topY, int fontLineHeight, ToIntFunction<String> measure) {
		final int safeWidth = Math.max(0, screenWidth);
		final int textAreaWidth = Math.max(0, safeWidth - HORIZONTAL_MARGIN * 2);
		final int unscaledMaxWidth = (int) Math.floor(textAreaWidth / TEXT_SCALE);
		this.lines = wrapLines(this.getMessage().getString(), unscaledMaxWidth, measure);
		this.lineHeight = scaledLineHeight(fontLineHeight);
		final int blockHeight = titleBlockHeight(this.lines.size(), this.lineHeight);
		this.setSize(safeWidth, blockHeight);
		this.setPosition(0, topY);
		return blockHeight;
	}

	int lineCount() {
		return this.lines.size();
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (this.font == null || this.lines.isEmpty()) {
			return;
		}

		graphics.pose().pushPose();
		graphics.pose().translate(0.0F, this.getY(), 0.0F);
		graphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1.0F);
		for (int index = 0; index < this.lines.size(); index++) {
			final String line = this.lines.get(index);
			final float lineWidth = this.font.width(line);
			final float x = (this.getWidth() / TEXT_SCALE - lineWidth) / 2.0F;
			final float y = index * (this.lineHeight + LINE_GAP) / TEXT_SCALE;
			graphics.drawString(this.font, line, Math.round(x), Math.round(y), TITLE_TEXT_COLOR, true);
		}
		graphics.pose().popPose();
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		this.defaultButtonNarrationText(narrationElementOutput);
	}

	/**
	 * Greedy word wrap that falls back to code-point wrapping for a word wider
	 * than the line, keeping long German compounds and space-less CJK readable.
	 */
	static List<String> wrapLines(String text, int maxWidth, ToIntFunction<String> measure) {
		final List<String> lines = new ArrayList<>();
		final int limit = Math.max(1, maxWidth);
		StringBuilder current = new StringBuilder();

		for (String word : text.trim().split("\\s+")) {
			if (word.isEmpty()) {
				continue;
			}

			final String candidate = current.length() == 0 ? word : current + " " + word;
			if (measure.applyAsInt(candidate) <= limit) {
				current.setLength(0);
				current.append(candidate);
				continue;
			}

			if (current.length() > 0) {
				lines.add(current.toString());
				current.setLength(0);
			}
			appendByCodePoint(lines, current, word, limit, measure);
		}

		lines.add(current.toString());
		return List.copyOf(lines);
	}

	private static void appendByCodePoint(
		List<String> lines,
		StringBuilder current,
		String word,
		int limit,
		ToIntFunction<String> measure
	) {
		int offset = 0;
		while (offset < word.length()) {
			final int codePoint = word.codePointAt(offset);
			final String character = new String(Character.toChars(codePoint));
			if (current.length() > 0 && measure.applyAsInt(current + character) > limit) {
				lines.add(current.toString());
				current.setLength(0);
			}
			current.append(character);
			offset += Character.charCount(codePoint);
		}
	}

	static int scaledLineHeight(int fontLineHeight) {
		return Math.max(1, (int) Math.ceil(Math.max(0, fontLineHeight) * TEXT_SCALE));
	}

	static int titleBlockHeight(int lineCount, int lineHeight) {
		if (lineCount <= 0) {
			return 0;
		}
		return lineCount * lineHeight + (lineCount - 1) * LINE_GAP;
	}
}
