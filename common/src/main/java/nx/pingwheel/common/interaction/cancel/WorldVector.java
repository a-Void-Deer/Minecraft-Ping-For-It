package nx.pingwheel.common.interaction.cancel;

import java.util.Objects;

/**
 * An immutable, finite 3D world coordinate.
 *
 * <p>Each component is validated to be a finite double (never NaN or
 * +/-Infinity), so all downstream math (subtraction, dot product, squared
 * length) stays well defined. This is a pure JDK value with no
 * {@code net.minecraft} reference, so it can be tested without a game client.
 */
public record WorldVector(double x, double y, double z) {

	public WorldVector {
		requireFinite("x", x);
		requireFinite("y", y);
		requireFinite("z", z);
	}

	/**
	 * Returns {@code this - other}.
	 */
	public WorldVector subtract(WorldVector other) {
		Objects.requireNonNull(other, "other");
		return new WorldVector(x - other.x, y - other.y, z - other.z);
	}

	/**
	 * Returns the standard Euclidean dot product with {@code other}.
	 */
	public double dot(WorldVector other) {
		Objects.requireNonNull(other, "other");
		return x * other.x + y * other.y + z * other.z;
	}

	/**
	 * Returns the squared Euclidean length (the dot product with itself).
	 */
	public double lengthSquared() {
		return x * x + y * y + z * z;
	}

	/**
	 * Validates a coordinate value: must be finite (not NaN or +/-Infinity).
	 */
	static double requireFinite(String name, double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(name + " must be finite, got " + value);
		}

		return value;
	}
}
