package nx.pingwheel.common.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A native button whose activation reads the live Alt/Option state for that
 * press only. Without Alt the row alternates Normal and Detailed; while Alt is
 * held the rotation temporarily includes Debug. Releasing Alt changes nothing
 * by itself, and a value already displayed as Debug stays visible until the
 * next activation selects another candidate. The row value and all
 * persistence callbacks belong to the supplied select action.
 */
@NotNullByDefault
final class LoggingProfileButton extends Button {
	private final Supplier<Component> caption;
	private final Supplier<LoggingProfile> current;
	private final Consumer<LoggingProfile> select;
	private final BooleanSupplier altDown;

	LoggingProfileButton(
		Supplier<Component> caption,
		Supplier<LoggingProfile> current,
		Consumer<LoggingProfile> select,
		BooleanSupplier altDown
	) {
		super(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT,
			caption.get(), ignored -> {}, DEFAULT_NARRATION);
		this.caption = caption;
		this.current = current;
		this.select = select;
		this.altDown = altDown;
	}

	@Override
	public void onPress() {
		if (!this.active) {
			return;
		}
		this.select.accept(next(this.current.get(), this.altDown.getAsBoolean()));
		this.refreshCaption();
	}

	static LoggingProfile next(LoggingProfile current, boolean altDown) {
		if (altDown) {
			return switch (current) {
				case NORMAL -> LoggingProfile.DEEP;
				case DEEP -> LoggingProfile.DEBUG;
				case DEBUG -> LoggingProfile.NORMAL;
			};
		}
		return current == LoggingProfile.NORMAL ? LoggingProfile.DEEP : LoggingProfile.NORMAL;
	}

	private void refreshCaption() {
		this.setMessage(this.caption.get());
	}
}
