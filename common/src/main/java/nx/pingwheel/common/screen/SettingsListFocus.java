package nx.pingwheel.common.screen;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Focus port shared by normal restoration and explicit validation-row revelation. */
final class SettingsListFocus {
	private SettingsListFocus() {}

	static <E> boolean focus(Iterable<E> entries, Predicate<E> containsWidget,
		Consumer<E> focusEntry, Consumer<E> ensureVisible, boolean reveal) {
		for (E entry : entries) {
			if (containsWidget.test(entry)) {
				focusEntry.accept(entry);
				// Native focus only ensures visibility for keyboard input. Validation must
				// reveal the field even when the failed close originated from a mouse click.
				if (reveal) ensureVisible.accept(entry);
				return true;
			}
		}
		return false;
	}
}
