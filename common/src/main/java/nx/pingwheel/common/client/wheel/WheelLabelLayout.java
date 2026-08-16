package nx.pingwheel.common.client.wheel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import nx.pingwheel.common.domain.PingType;

/**
 * Pure layout calculations for the text and icon content of the ping wheel.
 *
 * <p>The angular partition is deliberately owned by {@link WheelGeometry} and
 * is not changed here.  Each placement keeps the icon and label on the same
 * sector midpoint, while putting the icon toward the inner annulus and the
 * label toward the outside of the annulus.  Label sizing uses a conservative
 * chord of the sector after a small angular inset; long localized names are
 * scaled down, never truncated.
 */
public final class WheelLabelLayout {

	/** The normal GUI text scale used by the radial menu. */
	public static final double BASE_TEXT_SCALE = 0.5;

	/** Fraction of the annulus used for the icon anchor radius. */
	public static final double ICON_RADIUS_FRACTION = 0.27;

	/** Fraction of the annulus used for the label anchor radius. */
	public static final double LABEL_RADIUS_FRACTION = 0.72;

	/** Small angular inset on both sides of a label's sector. */
	public static final double LABEL_ANGULAR_INSET_RADIANS = 0.04;

	/** Additional GUI-pixel inset after converting the arc to a chord. */
	public static final double LABEL_SIDE_INSET = 1.0;

	/** Gap between the target label's bottom and the wheel's outer radius. */
	public static final int TARGET_LABEL_GAP = 4;

	private WheelLabelLayout() {}

	/**
	 * A complete, sector-associated placement.  The text width is measured at
	 * vanilla scale; {@link #scale()} is the GUI scale to apply when drawing it.
	 */
	public record Placement(
		int sectorIndex,
		PingType pingType,
		WheelPoint iconAnchor,
		WheelPoint labelAnchor,
		double maxWidth,
		double scale,
		int textWidth
	) {
		public Placement {
			Objects.requireNonNull(pingType, "pingType");
			Objects.requireNonNull(iconAnchor, "iconAnchor");
			Objects.requireNonNull(labelAnchor, "labelAnchor");

			if (sectorIndex < 0) {
				throw new IllegalArgumentException("sectorIndex must not be negative: " + sectorIndex);
			}

			if (!Double.isFinite(maxWidth) || maxWidth <= 0.0) {
				throw new IllegalArgumentException("maxWidth must be positive and finite: " + maxWidth);
			}

			if (!Double.isFinite(scale) || scale <= 0.0 || scale > BASE_TEXT_SCALE) {
				throw new IllegalArgumentException("scale must be in (0, " + BASE_TEXT_SCALE + "]: " + scale);
			}

			if (textWidth < 0) {
				throw new IllegalArgumentException("textWidth must not be negative: " + textWidth);
			}
		}

		/** Width of the fully rendered label at this placement's scale. */
		public double renderedWidth() {
			return textWidth * scale;
		}
	}

	/**
	 * Lays out one placement per sector in the exact sector order supplied by
	 * the wheel geometry.
	 *
	 * @param geometry  the visible wheel geometry
	 * @param sectors   the already-partitioned, ordered wheel sectors
	 * @param textWidths vanilla-font widths, one per sector
	 */
	public static List<Placement> layout(
		WheelGeometry geometry,
		List<WheelSector> sectors,
		List<Integer> textWidths
	) {
		Objects.requireNonNull(geometry, "geometry");
		Objects.requireNonNull(sectors, "sectors");
		Objects.requireNonNull(textWidths, "textWidths");

		if (sectors.isEmpty()) {
			throw new IllegalArgumentException("sectors must not be empty");
		}

		if (sectors.size() != textWidths.size()) {
			throw new IllegalArgumentException(
				"sectors and textWidths must have equal sizes: sectors="
					+ sectors.size() + " textWidths=" + textWidths.size());
		}

		List<Placement> result = new ArrayList<>(sectors.size());
		double iconRadius = iconRadius(geometry);
		double labelRadius = labelRadius(geometry);

		for (int i = 0; i < sectors.size(); i++) {
			WheelSector sector = Objects.requireNonNull(sectors.get(i), "sector");
			Integer measuredWidth = Objects.requireNonNull(textWidths.get(i), "textWidth");

			if (measuredWidth < 0) {
				throw new IllegalArgumentException("textWidth must not be negative: " + measuredWidth);
			}

			double midpointAngle = midpointAngle(sector);
			WheelPoint iconAnchor = geometry.pointAt(midpointAngle, iconRadius);
			WheelPoint labelAnchor = geometry.pointAt(midpointAngle, labelRadius);
			double maxWidth = maxLabelWidth(geometry, sector);
			double scale = measuredWidth == 0
				? BASE_TEXT_SCALE
				: Math.min(BASE_TEXT_SCALE, maxWidth / measuredWidth);

			// maxLabelWidth is always positive, so a non-empty label always keeps
			// a positive scale and its complete localized text remains drawable.
			result.add(new Placement(
				i,
				sector.pingType(),
				iconAnchor,
				labelAnchor,
				maxWidth,
				scale,
				measuredWidth));
		}

		return List.copyOf(result);
	}

	/** Radius of the icon anchor, toward the inner edge of the annulus. */
	public static double iconRadius(WheelGeometry geometry) {
		Objects.requireNonNull(geometry, "geometry");
		return geometry.innerRadius()
			+ (geometry.outerRadius() - geometry.innerRadius()) * ICON_RADIUS_FRACTION;
	}

	/** Radius of the label anchor, toward the outer edge of the annulus. */
	public static double labelRadius(WheelGeometry geometry) {
		Objects.requireNonNull(geometry, "geometry");
		return geometry.innerRadius()
			+ (geometry.outerRadius() - geometry.innerRadius()) * LABEL_RADIUS_FRACTION;
	}

	/**
	 * Conservative horizontal-space budget for a label in a sector.  The
	 * budget is an inset chord at the label radius, capped for wide sectors.
	 */
	public static double maxLabelWidth(WheelGeometry geometry, WheelSector sector) {
		Objects.requireNonNull(geometry, "geometry");
		Objects.requireNonNull(sector, "sector");

		double span = sector.endAngleRadians() - sector.startAngleRadians();
		double usableSpan = Math.max(0.0, span - LABEL_ANGULAR_INSET_RADIANS * 2.0);
		double cappedSpan = Math.min(Math.PI, usableSpan);
		double chord = 2.0 * labelRadius(geometry) * Math.sin(cappedSpan * 0.5);

		return Math.max(1.0, chord - LABEL_SIDE_INSET * 2.0);
	}

	/**
	 * Returns the top y coordinate for a normal-scale vanilla-font target label
	 * above the wheel.  The label's bottom is at least
	 * {@link #TARGET_LABEL_GAP} pixels above the outer radius.
	 */
	public static int targetLabelTopY(double centerY, double outerRadius, int fontLineHeight) {
		if (!Double.isFinite(centerY) || !Double.isFinite(outerRadius)) {
			throw new IllegalArgumentException("centerY and outerRadius must be finite");
		}

		if (outerRadius <= 0.0) {
			throw new IllegalArgumentException("outerRadius must be positive: " + outerRadius);
		}

		if (fontLineHeight <= 0) {
			throw new IllegalArgumentException("fontLineHeight must be positive: " + fontLineHeight);
		}

		return (int) Math.floor(centerY - outerRadius - TARGET_LABEL_GAP - fontLineHeight);
	}

	private static double midpointAngle(WheelSector sector) {
		return (sector.startAngleRadians() + sector.endAngleRadians()) * 0.5;
	}
}
