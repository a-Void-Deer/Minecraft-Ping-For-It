package nx.pingwheel.common.interaction;

import java.util.Objects;

import nx.pingwheel.common.interaction.cancel.WorldVector;

/**
 * The pure, press-time ray used to capture one ping interaction.
 *
 * <p>The direction is intentionally not normalized here. Minecraft's view
 * vector is already normalized, and the cancellation picker normalizes the
 * direction at the point where it performs its dot-product calculation. By
 * retaining the supplied values this type preserves the exact ray that was
 * used at the capture edge.</p>
 */
public record CapturedRay(WorldVector origin, WorldVector direction) {

	private static final CapturedRay DEFAULT = new CapturedRay(
		new WorldVector(0.0, 0.0, 0.0),
		new WorldVector(0.0, 0.0, 1.0));

	public CapturedRay {
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(direction, "direction");
		validateFinite(origin, "origin");
		validateFinite(direction, "direction");

		if (direction.x() == 0.0 && direction.y() == 0.0 && direction.z() == 0.0) {
			throw new IllegalArgumentException("direction must not be the zero vector");
		}
	}

	/**
	 * Returns the safe compatibility ray used only by legacy two-argument test
	 * seams that do not supply a press ray. Production capture always supplies
	 * the actual ray taken at key-down.
	 */
	public static CapturedRay defaultRay() {
		return DEFAULT;
	}

	private static void validateFinite(WorldVector vector, String fieldName) {
		if (!Double.isFinite(vector.x())
			|| !Double.isFinite(vector.y())
			|| !Double.isFinite(vector.z())) {
			throw new IllegalArgumentException(fieldName + " must contain only finite coordinates");
		}
	}
}
