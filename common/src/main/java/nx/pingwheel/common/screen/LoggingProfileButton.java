package nx.pingwheel.common.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A native button with one extra gesture: Shift plus the ordinary left click
 * only reveals this row's Debug candidate for the current settings session.
 * Keyboard activation and the mouse wheel keep the ordinary cycle, so they can
 * never unlock Debug. The row value and all persistence callbacks belong to
 * the supplied select action.
 */
@NotNullByDefault
final class LoggingProfileButton extends Button {
	private final Supplier<Component> caption;
	private final Supplier<LoggingProfile> current;
	private final BooleanSupplier unlocked;
	private final Runnable reveal;
	private final Consumer<LoggingProfile> select;
	private final BooleanSupplier shiftDown;

	LoggingProfileButton(
		Supplier<Component> caption,
		Supplier<LoggingProfile> current,
		BooleanSupplier unlocked,
		Runnable reveal,
		Consumer<LoggingProfile> select,
		BooleanSupplier shiftDown
	) {
		super(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT,
			caption.get(), ignored -> {}, DEFAULT_NARRATION);
		this.caption = caption;
		this.current = current;
		this.unlocked = unlocked;
		this.reveal = reveal;
		this.select = select;
		this.shiftDown = shiftDown;
	}

	@Override
	public void onPress() {
		if (!this.active) {
			return;
		}
		this.select.accept(LoggingProfileSession.next(this.current.get(), this.unlocked.getAsBoolean()));
		this.refreshCaption();
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		if (!this.active) {
			return;
		}
		if (this.shiftDown.getAsBoolean()) {
			this.reveal.run();
			this.refreshCaption();
			return;
		}
		this.onPress();
	}

	private void refreshCaption() {
		this.setMessage(this.caption.get());
	}
}
