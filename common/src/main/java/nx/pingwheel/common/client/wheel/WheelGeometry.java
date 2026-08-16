package nx.pingwheel.common.client.wheel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.interaction.wheel.WheelSelection;

/**
 * Immutable, configurable pure-JDK geometry for the ping wheel.
 *
 * <p>The wheel is an annulus between {@link #innerRadius()} and
 * {@link #outerRadius()} centered at the GUI center. Angle 0 points straight
 * up and angles grow clockwise (GUI space, y down), so the point at angle
 * {@code a} and radius {@code r} is {@code (r·sin(a), -r·cos(a))} relative to
 * the center.
 *
 * <p>{@link #sectors(List)} partitions the full ring into one ordered sector
 * per ping type (a single ping type spans the full ring), and
 * {@link #select(double, double, List)} maps an offset from the wheel center
 * onto a {@link WheelSelection} deterministically:
 * <ul>
 *   <li>distance {@code <= innerRadius}: {@link WheelSelection#CENTER};</li>
 *   <li>distance {@code > outerRadius}: {@link WheelSelection#NONE};</li>
 *   <li>otherwise: the sector whose angular wedge contains the point; an angle
 *       exactly on a sector boundary belongs to the sector starting at that
 *       boundary.</li>
 * </ul>
 *
 * <p>No Minecraft types are used anywhere in this class, so all geometry is
 * unit-testable without a game client.
 */
public final class WheelGeometry {

	public static final double TWO_PI = Math.PI * 2.0;

	/** The default inner (cancel center) radius in GUI pixels. */
	public static final double DEFAULT_INNER_RADIUS = 14.0;

	/** The default outer ring radius in GUI pixels. */
	public static final double DEFAULT_OUTER_RADIUS = 39.0;

	private final double innerRadius;
	private final double outerRadius;

	/**
	 * Creates a wheel geometry with the default radii (14/39).
	 */
	public WheelGeometry() {
		this(DEFAULT_INNER_RADIUS, DEFAULT_OUTER_RADIUS);
	}

	/**
	 * Creates a wheel geometry with {@code 0 < innerRadius < outerRadius}.
	 */
	public WheelGeometry(double innerRadius, double outerRadius) {
		if (!Double.isFinite(innerRadius) || !Double.isFinite(outerRadius)) {
			throw new IllegalArgumentException(
				"radii must be finite: inner=" + innerRadius + " outer=" + outerRadius);
		}

		if (innerRadius <= 0.0) {
			throw new IllegalArgumentException("innerRadius must be positive: " + innerRadius);
		}

		if (outerRadius <= innerRadius) {
			throw new IllegalArgumentException(
				"outerRadius must be greater than innerRadius: outer=" + outerRadius
					+ " inner=" + innerRadius);
		}

		this.innerRadius = innerRadius;
		this.outerRadius = outerRadius;
	}

	public double innerRadius() {
		return innerRadius;
	}

	public double outerRadius() {
		return outerRadius;
	}

	/**
	 * The radius halfway between the inner and outer radii, where sector icons
	 * and midpoints are placed.
	 */
	public double midRadius() {
		return (innerRadius + outerRadius) * 0.5;
	}

	/**
	 * The frozen, ordered sector partition for {@code pingTypes}: sector
	 * {@code i} covers {@code [i·span, (i+1)·span)} where
	 * {@code span = 2π / count}, and the last sector ends exactly at
	 * {@code 2π}. Adjacent sectors share the exact same boundary angle, so the
	 * partition has no gaps, and a single ping type spans the full ring.
	 *
	 * @param pingTypes the non-empty, ordered ping type list
	 * @throws NullPointerException     if {@code pingTypes} is null or contains
	 *                                  a null element
	 * @throws IllegalArgumentException if {@code pingTypes} is empty
	 */
	public List<WheelSector> sectors(List<PingType> pingTypes) {
		Objects.requireNonNull(pingTypes, "pingTypes");

		List<PingType> frozen = List.copyOf(pingTypes);

		if (frozen.isEmpty()) {
			throw new IllegalArgumentException("pingTypes must not be empty");
		}

		double span = TWO_PI / frozen.size();
		List<WheelSector> result = new ArrayList<>(frozen.size());

		for (int i = 0; i < frozen.size(); i++) {
			double start = i * span;
			double end = i + 1 < frozen.size() ? (i + 1) * span : TWO_PI;
			result.add(new WheelSector(i, frozen.get(i), start, end));
		}

		return List.copyOf(result);
	}

	/**
	 * Determines the wheel selection for an offset from the wheel center.
	 *
	 * <p>Boundaries are deterministic: a point at exactly {@code innerRadius}
	 * selects the center, a point at exactly {@code outerRadius} still selects
	 * the sector containing it, and an angle exactly on a sector boundary
	 * belongs to the sector starting at that boundary.
	 *
	 * @param dx        the horizontal offset from the wheel center (GUI space)
	 * @param dy        the vertical offset from the wheel center (GUI space)
	 * @param pingTypes the non-empty, ordered ping type list
	 * @return {@link WheelSelection#CENTER}, {@link WheelSelection#NONE}, or
	 *         the selected sector
	 * @throws NullPointerException     if {@code pingTypes} is null
	 * @throws IllegalArgumentException if {@code pingTypes} is empty or either
	 *                                  offset is not finite
	 */
	public WheelSelection select(double dx, double dy, List<PingType> pingTypes) {
		Objects.requireNonNull(pingTypes, "pingTypes");

		if (pingTypes.isEmpty()) {
			throw new IllegalArgumentException("pingTypes must not be empty");
		}

		if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
			throw new IllegalArgumentException("offsets must be finite: dx=" + dx + " dy=" + dy);
		}

		double distance = Math.hypot(dx, dy);

		if (distance <= innerRadius) {
			return WheelSelection.CENTER;
		}

		if (distance > outerRadius) {
			return WheelSelection.NONE;
		}

		double span = TWO_PI / pingTypes.size();
		int index = (int) (clockwiseAngle(dx, dy) / span);

		// Floating point guard near 2π: clamp instead of indexing out of bounds.
		if (index >= pingTypes.size()) {
			index = pingTypes.size() - 1;
		}

		return WheelSelection.sector(pingTypes.get(index));
	}

	/**
	 * The point at {@code angleRadians} (clockwise from top) and
	 * {@code radius} relative to the wheel center.
	 *
	 * @throws IllegalArgumentException if either argument is not finite or
	 *                                  {@code radius} is not positive
	 */
	public WheelPoint pointAt(double angleRadians, double radius) {
		if (!Double.isFinite(angleRadians) || !Double.isFinite(radius)) {
			throw new IllegalArgumentException(
				"arguments must be finite: angle=" + angleRadians + " radius=" + radius);
		}

		if (radius <= 0.0) {
			throw new IllegalArgumentException("radius must be positive: " + radius);
		}

		return new WheelPoint(
			Math.sin(angleRadians) * radius,
			-Math.cos(angleRadians) * radius);
	}

	/**
	 * The midpoint of a sector's angular span at {@link #midRadius()}, relative
	 * to the wheel center. This is where the sector's icon is drawn.
	 */
	public WheelPoint midpoint(WheelSector sector) {
		Objects.requireNonNull(sector, "sector");

		return pointAt(
			(sector.startAngleRadians() + sector.endAngleRadians()) * 0.5,
			midRadius());
	}

	/**
	 * Deterministically sampled points along a sector's arc at {@code radius},
	 * including both endpoints, with the sample count proportional to the
	 * sector's angular span so that the total stays at
	 * {@code samplesPerFullRing} across a full ring.
	 *
	 * @param samplesPerFullRing at least 2; the total number of angular
	 *                           samples a full ring would receive
	 * @throws NullPointerException     if {@code sector} is null
	 * @throws IllegalArgumentException if {@code radius} is not finite or not
	 *                                  positive, or
	 *                                  {@code samplesPerFullRing} is below 2
	 */
	public List<WheelPoint> arcPoints(WheelSector sector, double radius, int samplesPerFullRing) {
		Objects.requireNonNull(sector, "sector");
		validateRadius(radius);

		if (samplesPerFullRing < 2) {
			throw new IllegalArgumentException(
				"samplesPerFullRing must be at least 2: " + samplesPerFullRing);
		}

		double span = sector.endAngleRadians() - sector.startAngleRadians();
		int count = Math.max(2, (int) Math.round(span / TWO_PI * samplesPerFullRing));
		List<WheelPoint> points = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			double angle = sector.startAngleRadians() + span * ((double) i / (count - 1));
			points.add(pointAt(angle, radius));
		}

		return List.copyOf(points);
	}

	/**
	 * Deterministically sampled points around a full circle at {@code radius},
	 * starting at angle 0 (top) and including the closing point back at the
	 * start position.
	 *
	 * @param samplesPerFullRing at least 2; the total number of samples
	 * @throws IllegalArgumentException if {@code radius} is not finite or not
	 *                                  positive, or
	 *                                  {@code samplesPerFullRing} is below 2
	 */
	public List<WheelPoint> circlePoints(double radius, int samplesPerFullRing) {
		validateRadius(radius);

		if (samplesPerFullRing < 2) {
			throw new IllegalArgumentException(
				"samplesPerFullRing must be at least 2: " + samplesPerFullRing);
		}

		List<WheelPoint> points = new ArrayList<>(samplesPerFullRing);

		for (int i = 0; i < samplesPerFullRing; i++) {
			points.add(pointAt(TWO_PI * i / samplesPerFullRing, radius));
		}

		return List.copyOf(points);
	}

	/**
	 * The angle of {@code (dx, dy)} measured clockwise from straight up (GUI
	 * space, y down), normalized to {@code [0, 2π)}.
	 */
	private static double clockwiseAngle(double dx, double dy) {
		double angle = Math.atan2(dx, -dy);
		return angle < 0.0 ? angle + TWO_PI : angle;
	}

	private static void validateRadius(double radius) {
		if (!Double.isFinite(radius)) {
			throw new IllegalArgumentException("radius must be finite: " + radius);
		}

		if (radius <= 0.0) {
			throw new IllegalArgumentException("radius must be positive: " + radius);
		}
	}
}
