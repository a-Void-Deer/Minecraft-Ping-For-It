package nx.pingwheel.common.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * An {@link EditBox} that remembers its own cursor and highlight positions.
 *
 * <p>Minecraft 1.21.1 publishes {@code setHighlightPos(int)} but no public
 * highlight getter, and {@code isEditable()} is private. The cursor and
 * highlight fields are written only by the public
 * {@link EditBox#setCursorPosition(int)} and {@link EditBox#setHighlightPos(int)}
 * methods, so overriding those two captures every keyboard, mouse, and
 * programmatic movement without reflection or mixins. The tracked highlight is
 * clamped to the current text exactly like the vanilla setter, so a shorter or
 * cleared text can never leave the caret out of range.
 */
final class PresentationSelectorEditBox extends EditBox {
	private int trackedCursor;
	private int trackedHighlight;

	PresentationSelectorEditBox(Font font, int x, int y, int width, int height, Component narration) {
		super(font, x, y, width, height, narration);
	}

	@Override
	public void setCursorPosition(int position) {
		super.setCursorPosition(position);
		this.trackedCursor = super.getCursorPosition();
	}

	@Override
	public void setHighlightPos(int position) {
		super.setHighlightPos(position);
		this.trackedHighlight = Math.max(0, Math.min(position, this.getValue().length()));
	}

	int trackedCursor() {
		return this.trackedCursor;
	}

	int trackedHighlight() {
		return this.trackedHighlight;
	}

	/** Applies a recorded range through the vanilla clamping setters. */
	void restoreSelection(int cursor, int highlight) {
		this.setCursorPosition(cursor);
		this.setHighlightPos(highlight);
	}
}
