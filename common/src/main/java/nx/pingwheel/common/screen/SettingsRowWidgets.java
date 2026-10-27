package nx.pingwheel.common.screen;

import net.minecraft.client.gui.components.AbstractWidget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Native-widget geometry only: never reads or changes an option value. */
@NotNullByDefault
final class SettingsRowWidgets {
	private SettingsRowWidgets() {
	}

	static boolean fitsPair(SettingsScreenLayout.Content content, AbstractWidget left, AbstractWidget right,
		ToIntFunction<AbstractWidget> measure) {
		return content.fitsPair(measure.applyAsInt(left), measure.applyAsInt(right));
	}

	/** Expands declared pairs without losing their membership or rebuilding their native widgets. */
	static @Unmodifiable List<@Unmodifiable List<AbstractWidget>> rowsFor(SettingsScreenLayout.Content content,
		List<? extends @Unmodifiable List<AbstractWidget>> declaredRows, ToIntFunction<AbstractWidget> measure) {
		var rows = new ArrayList<@Unmodifiable List<AbstractWidget>>();
		for (@Unmodifiable List<AbstractWidget> row : declaredRows) {
			if (row.isEmpty() || row.size() > 2) throw new IllegalArgumentException("not a settings row");
			if (row.size() == 2 && !fitsPair(content, row.getFirst(), row.getLast(), measure)) {
				rows.add(List.of(row.getFirst()));
				rows.add(List.of(row.getLast()));
			} else {
				rows.add(List.copyOf(row));
			}
		}
		return List.copyOf(rows);
	}

	/** The input list is borrowed; its widgets stay owned by the native list entry. */
	static void position(SettingsScreenLayout.Content content, int top, @Unmodifiable List<? extends AbstractWidget> widgets) {
		if (widgets.isEmpty() || widgets.size() > 2) throw new IllegalArgumentException("not a settings row");
		int width = widgets.size() == 1 ? content.width() : content.halfWidth();
		for (int index = 0; index < widgets.size(); index++) {
			AbstractWidget widget = widgets.get(index);
			widget.setWidth(width);
			widget.setPosition(content.left() + index * (width + SettingsScreenLayout.COLUMN_GAP), top);
			widget.visible = width > 0;
		}
	}
}
