package nx.pingwheel.common.client.spatial;

import java.util.List;
import java.util.Objects;

/**
 * Immutable radial menu tree.
 *
 * <p>A menu owns an ordered list of {@link Choice entries}. An entry either
 * commits an opaque {@code action}, opens a child {@link SpatialMenu}, or is a
 * navigation ({@code back}), reserved, or disabled entry. The action string is
 * deliberately opaque here: a later Minecraft adapter maps it to ping types,
 * target selections, toggles, or cancellation without this model depending on
 * any game class.
 *
 * <p>Root entries carry the fixed geometry supplied by the caller through
 * {@link Choice#withSector(double, double)} (for example the eight-way
 * cardinal-55 / diagonal-35 layout). Every non-root menu computes equal sectors
 * from its actual entry count, including the automatically appended Back
 * entry, so no caller geometry is duplicated. {@code bearing} is degrees,
 * {@code 0} is up and positive is clockwise.
 */
public record SpatialMenu(String id, List<Choice> choices) {

	public SpatialMenu {
		Objects.requireNonNull(id, "id");
		choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
	}

	/** Convenience factory for a menu with the given ordered entries. */
	public static SpatialMenu of(String id, Choice... choices) {
		return new SpatialMenu(id, List.of(choices));
	}

	/**
	 * One selectable entry. {@code bearing}/{@code span} are only meaningful
	 * for caller-fixed geometry (root entries); they are null for equal-sector
	 * child menus and must be provided together.
	 */
	public record Choice(
		String id,
		String label,
		String action,
		SpatialMenu children,
		boolean back,
		boolean disabled,
		boolean reserved,
		Double bearing,
		Double span
	) {

		public Choice {
			Objects.requireNonNull(id, "id");

			if ((bearing == null) != (span == null)) {
				throw new IllegalArgumentException("bearing and span must be provided together: " + id);
			}

			if (span != null && !(span > 0.0)) {
				throw new IllegalArgumentException("span must be positive: " + id);
			}

			if (back && (action != null || children != null)) {
				throw new IllegalArgumentException("back entry cannot carry an action or children: " + id);
			}

			if (reserved && children != null) {
				throw new IllegalArgumentException("reserved entry cannot carry children: " + id);
			}
		}

		/** A leaf entry that commits {@code action} on release. */
		public static Choice leaf(String id, String label, String action) {
			return new Choice(id, label, action, null, false, false, false, null, null);
		}

		/** A branch entry with no default release action. */
		public static Choice branch(String id, String label, SpatialMenu children) {
			return new Choice(id, label, null, children, false, false, false, null, null);
		}

		/** A branch entry that also commits {@code action} on release. */
		public static Choice branch(String id, String label, String action, SpatialMenu children) {
			return new Choice(id, label, action, children, false, false, false, null, null);
		}

		/** A focusable entry that never commits or opens anything. */
		public static Choice reserved(String id, String label) {
			return new Choice(id, label, null, null, false, false, true, null, null);
		}

		/** A focusable entry whose action and children are ignored. */
		public static Choice disabled(String id, String label) {
			return new Choice(id, label, null, null, false, true, false, null, null);
		}

		/** The navigation entry synthesized by the controller for a submenu. */
		public static Choice back(String id) {
			return new Choice(id, "pingforit.spatial.back", null, null, true, false, false, null, null);
		}

		/** Returns a copy carrying caller-fixed root geometry. */
		public Choice withSector(double bearing, double span) {
			return new Choice(id, label, action, children, back, disabled, reserved, bearing, span);
		}

		/** Whether this entry opens a menu with at least one choice. */
		public boolean hasChildren() {
			return children != null && !children.choices().isEmpty();
		}

		/** Whether this entry carries caller-fixed geometry. */
		public boolean hasSector() {
			return bearing != null && span != null;
		}
	}
}
