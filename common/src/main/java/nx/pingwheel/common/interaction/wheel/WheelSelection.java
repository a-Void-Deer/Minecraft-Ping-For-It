package nx.pingwheel.common.interaction.wheel;

import java.util.Objects;

import nx.pingwheel.common.domain.PingType;

/**
 * The user's current selection within the open ping wheel.
 *
 * <p>Three mutually exclusive states exist: nothing selected
 * ({@link None}), the center cancellation action ({@link Center}), or a ping
 * type sector ({@link Sector}). {@link None} and {@link Center} are value-less
 * singletons; a {@link Sector} carries a non-null {@link PingType} which the
 * state machine validates against the frozen wheel ping type list.
 */
public sealed interface WheelSelection permits WheelSelection.None, WheelSelection.Center, WheelSelection.Sector {

	/**
	 * The shared "no selection" value.
	 */
	None NONE = None.INSTANCE;

	/**
	 * The shared "center cancellation action" value.
	 */
	Center CENTER = Center.INSTANCE;

	/**
	 * A sector selection wrapping {@code pingType}.
	 */
	static WheelSelection sector(PingType pingType) {
		return new Sector(pingType);
	}

	/**
	 * Nothing selected.
	 */
	final class None implements WheelSelection {

		public static final None INSTANCE = new None();

		private None() {}

		@Override
		public String toString() {
			return "None";
		}
	}

	/**
	 * The center cancellation action selected.
	 */
	final class Center implements WheelSelection {

		public static final Center INSTANCE = new Center();

		private Center() {}

		@Override
		public String toString() {
			return "Center";
		}
	}

	/**
	 * A ping type sector selected.
	 */
	record Sector(PingType pingType) implements WheelSelection {

		public Sector {
			Objects.requireNonNull(pingType, "pingType");
		}
	}
}
