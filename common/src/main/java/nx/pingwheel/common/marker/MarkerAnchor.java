package nx.pingwheel.common.marker;

/**
 * The world-space anchor position of a {@link ServerMarker}.
 *
 * <p>This is a pure geometric point (finite x/y/z doubles) with no dimension
 * identity of its own; the dimension is carried by the marker's
 * {@link Target}. The coordinates are always expressed in the world/dimension
 * identified by that target (for example, an entity target's position in the
 * dimension that contains the entity), so {@code (x, y, z)} only has a
 * well-defined meaning together with the marker's target. Platform adapters
 * that construct a {@code MarkerAnchor} from game state are responsible for
 * supplying coordinates in the target's dimension and must not mix dimensions.
 * Only JDK types are used here, so this value can be constructed and validated
 * without a game client.
 */
public record MarkerAnchor(double x, double y, double z) {

	public MarkerAnchor {
		requireFinite("x", x);
		requireFinite("y", y);
		requireFinite("z", z);
	}

	private static double requireFinite(String name, double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(name + " must be finite, got " + value);
		}

		return value;
	}
}
