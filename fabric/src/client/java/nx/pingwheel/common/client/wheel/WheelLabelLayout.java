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

	/** Default maximum sector-label scale, retained for the original overloads. */
	public static final double DEFAULT_MAX_TEXT_SCALE = BASE_TEXT_SCALE;

	/** The vanilla 1.21.1 font line height used by the wheel renderer. */
	public static final double DEFAULT_LABEL_LINE_HEIGHT = 9.0;

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

	/** Half the current wheel icon's axis-aligned box, in GUI pixels. */
	private static final double ICON_BOX_HALF_SIZE = 4.0;

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

			if (!Double.isFinite(scale) || scale <= 0.0) {
				throw new IllegalArgumentException("scale must be positive and finite: " + scale);
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
	 * Computes the pre-scale origin for a label drawn at local coordinate
	 * {@code (0, 0)}.  Keeping the scaled half extents here lets the renderer
	 * use the same floating-point transform for odd-sized labels as the layout
	 * containment calculations.
	 */
	public static WheelPoint labelOrigin(
		WheelPoint labelAnchor,
		int textWidth,
		double lineHeight,
		double scale
	) {
		Objects.requireNonNull(labelAnchor, "labelAnchor");

		if (textWidth < 0) {
			throw new IllegalArgumentException("textWidth must not be negative: " + textWidth);
		}

		if (!Double.isFinite(lineHeight) || lineHeight <= 0.0) {
			throw new IllegalArgumentException("lineHeight must be positive and finite: " + lineHeight);
		}

		if (!Double.isFinite(scale) || scale <= 0.0) {
			throw new IllegalArgumentException("scale must be positive and finite: " + scale);
		}

		return new WheelPoint(
			labelAnchor.x() - textWidth * scale * 0.5,
			labelAnchor.y() - lineHeight * scale * 0.5);
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
		return layout(
			geometry,
			sectors,
			textWidths,
			DEFAULT_LABEL_LINE_HEIGHT,
			DEFAULT_MAX_TEXT_SCALE);
	}

	/**
	 * Lays out labels using the supplied rendered line height.  The additional
	 * height-aware cap is kept here, rather than changing the renderer, because
	 * the renderer's vanilla font line height is stable at {@link
	 * #DEFAULT_LABEL_LINE_HEIGHT} for the target version.
	 */
	public static List<Placement> layout(
		WheelGeometry geometry,
		List<WheelSector> sectors,
		List<Integer> textWidths,
		double lineHeight
	) {
		return layout(
			geometry,
			sectors,
			textWidths,
			lineHeight,
			DEFAULT_MAX_TEXT_SCALE);
	}

	/**
	 * Lays out labels using the supplied rendered line height and maximum GUI
	 * text scale.  The maximum is deliberately supplied by the caller so a
	 * configured font size larger than the historical half-scale is not silently
	 * reduced back to {@link #BASE_TEXT_SCALE}.
	 */
	public static List<Placement> layout(
		WheelGeometry geometry,
		List<WheelSector> sectors,
		List<Integer> textWidths,
		double lineHeight,
		double maxScale
	) {
		Objects.requireNonNull(geometry, "geometry");
		Objects.requireNonNull(sectors, "sectors");
		Objects.requireNonNull(textWidths, "textWidths");

		if (!Double.isFinite(lineHeight) || lineHeight <= 0.0) {
			throw new IllegalArgumentException("lineHeight must be positive and finite: " + lineHeight);
		}

		if (!Double.isFinite(maxScale) || maxScale <= 0.0) {
			throw new IllegalArgumentException("maxScale must be positive and finite: " + maxScale);
		}

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
				? maxScale
				: Math.min(maxScale, maxWidth / measuredWidth);
			scale = Math.min(
				scale,
				containedScaleCap(
					geometry,
					sector,
					midpointAngle,
					measuredWidth,
					lineHeight));

			// The wheel geometry keeps the label anchor inside the outer circle,
			// so a positive scale always exists.  Retain that invariant even when
			// a caller supplies degenerate numeric inputs near a floating-point
			// boundary.
			if (!Double.isFinite(scale) || scale <= 0.0) {
				scale = Math.nextUp(0.0);
			}

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
	 * Finds the largest scale for which every label corner stays in the outer
	 * circle, in the sector (including the existing angular inset), and outside
	 * the icon box.  The circle limit is the positive root of one quadratic per
	 * corner; the angular and icon limits are linear because the corner moves
	 * linearly with scale.
	 */
	private static double containedScaleCap(
		WheelGeometry geometry,
		WheelSector sector,
		double midpointAngle,
		int textWidth,
		double lineHeight
	) {
		double labelRadius = labelRadius(geometry);
		double cap = outerCircleScaleCap(
			labelRadius,
			geometry.outerRadius(),
			midpointAngle,
			textWidth,
			lineHeight);
		cap = Math.min(cap, sectorScaleCap(
			sector,
			labelRadius,
			midpointAngle,
			textWidth,
			lineHeight));
		cap = Math.min(cap, iconSeparationScaleCap(
			geometry,
			midpointAngle,
			textWidth,
			lineHeight));

		return cap;
	}

	private static double outerCircleScaleCap(
		double labelRadius,
		double outerRadius,
		double midpointAngle,
		int textWidth,
		double lineHeight
	) {
		double halfWidth = textWidth * 0.5;
		double halfHeight = lineHeight * 0.5;
		double quadratic = halfWidth * halfWidth + halfHeight * halfHeight;

		if (!Double.isFinite(quadratic) || quadratic <= 0.0) {
			return Double.POSITIVE_INFINITY;
		}

		double constant = labelRadius * labelRadius - outerRadius * outerRadius;
		if (!Double.isFinite(constant) || constant >= 0.0) {
			return Math.nextUp(0.0);
		}

		double sine = Math.sin(midpointAngle);
		double cosine = Math.cos(midpointAngle);
		double cap = Double.POSITIVE_INFINITY;

		for (int horizontalSign : new int[] {-1, 1}) {
			for (int verticalSign : new int[] {-1, 1}) {
				double linear = labelRadius * (
					horizontalSign * textWidth * sine
					- verticalSign * lineHeight * cosine);
				double cornerCap = positiveQuadraticRoot(quadratic, linear, constant);
				cap = Math.min(cap, cornerCap);
			}
		}

		return cap;
	}

	private static double positiveQuadraticRoot(double quadratic, double linear, double constant) {
		if (!Double.isFinite(quadratic) || quadratic <= 0.0
			|| !Double.isFinite(linear) || !Double.isFinite(constant)
			|| constant >= 0.0) {
			return Math.nextUp(0.0);
		}

		double discriminant = linear * linear - 4.0 * quadratic * constant;
		if (!Double.isFinite(discriminant)) {
			return Math.nextUp(0.0);
		}

		double squareRoot = Math.sqrt(Math.max(0.0, discriminant));
		// This form avoids cancellation when the positive root is the small
		// root of a wide-label quadratic.
		double q = -0.5 * (linear + Math.copySign(squareRoot, linear));
		double root = q == 0.0
			? (-linear + squareRoot) / (2.0 * quadratic)
			: (linear >= 0.0 ? constant / q : q / quadratic);

		return Double.isFinite(root) && root > 0.0 ? root : Math.nextUp(0.0);
	}

	private static double sectorScaleCap(
		WheelSector sector,
		double labelRadius,
		double midpointAngle,
		int textWidth,
		double lineHeight
	) {
		double span = sector.endAngleRadians() - sector.startAngleRadians();
		// A single sector intentionally permits every angle.  For a wider-than-
		// half ring, cap against a conservative half-ring centered on the sector
		// midpoint; that half-ring is still wholly within the actual sector.
		if (!Double.isFinite(span) || span >= WheelGeometry.TWO_PI - 1.0e-12) {
			return Double.POSITIVE_INFINITY;
		}

		double usableSpan = Math.min(
			span,
			Math.PI) - LABEL_ANGULAR_INSET_RADIANS * 2.0;
		double halfSpan = Math.max(0.0, usableSpan * 0.5);
		double midpoint = midpointAngle;
		double lowerAngle = midpoint - halfSpan;
		double upperAngle = midpoint + halfSpan;

		double anchorX = labelRadius * Math.sin(midpointAngle);
		double anchorY = -labelRadius * Math.cos(midpointAngle);
		double halfWidth = textWidth * 0.5;
		double halfHeight = lineHeight * 0.5;
		double cap = Double.POSITIVE_INFINITY;

		for (int horizontalSign : new int[] {-1, 1}) {
			for (int verticalSign : new int[] {-1, 1}) {
				double offsetX = horizontalSign * halfWidth;
				double offsetY = verticalSign * halfHeight;

				cap = Math.min(cap, nonNegativeBoundaryCap(
					boundaryCross(lowerAngle, anchorX, anchorY),
					boundaryCross(lowerAngle, offsetX, offsetY)));
				cap = Math.min(cap, nonPositiveBoundaryCap(
					boundaryCross(upperAngle, anchorX, anchorY),
					boundaryCross(upperAngle, offsetX, offsetY)));
			}
		}

		return cap;
	}

	private static double boundaryCross(double angle, double x, double y) {
		return Math.sin(angle) * y + Math.cos(angle) * x;
	}

	private static double nonNegativeBoundaryCap(double value, double slope) {
		if (!Double.isFinite(value) || !Double.isFinite(slope)) {
			return Math.nextUp(0.0);
		}

		if (value < 0.0) {
			return Math.nextUp(0.0);
		}

		if (slope >= 0.0) {
			return Double.POSITIVE_INFINITY;
		}

		double cap = value / -slope;
		return Double.isFinite(cap) && cap > 0.0 ? cap : Math.nextUp(0.0);
	}

	private static double nonPositiveBoundaryCap(double value, double slope) {
		if (!Double.isFinite(value) || !Double.isFinite(slope)) {
			return Math.nextUp(0.0);
		}

		if (value > 0.0) {
			return Math.nextUp(0.0);
		}

		if (slope <= 0.0) {
			return Double.POSITIVE_INFINITY;
		}

		double cap = -value / slope;
		return Double.isFinite(cap) && cap > 0.0 ? cap : Math.nextUp(0.0);
	}

	private static double iconSeparationScaleCap(
		WheelGeometry geometry,
		double midpointAngle,
		int textWidth,
		double lineHeight
	) {
		double iconRadius = iconRadius(geometry);
		double labelRadius = labelRadius(geometry);
		double sine = Math.sin(midpointAngle);
		double cosine = -Math.cos(midpointAngle);
		double labelCenterX = labelRadius * sine;
		double labelCenterY = labelRadius * cosine;
		double iconCenterX = iconRadius * sine;
		double iconCenterY = iconRadius * cosine;
		double halfWidth = textWidth * 0.5;
		double halfHeight = lineHeight * 0.5;
		double cap = Math.max(
			axisSeparationScaleCap(Math.abs(labelCenterX - iconCenterX), halfWidth),
			axisSeparationScaleCap(Math.abs(labelCenterY - iconCenterY), halfHeight));

		return Double.isFinite(cap) && cap > 0.0 ? cap : Math.nextUp(0.0);
	}

	private static double axisSeparationScaleCap(double centerDistance, double labelHalfExtent) {
		if (!Double.isFinite(centerDistance) || !Double.isFinite(labelHalfExtent)) {
			return Math.nextUp(0.0);
		}

		if (centerDistance <= ICON_BOX_HALF_SIZE) {
			return 0.0;
		}

		if (labelHalfExtent <= 0.0) {
			return Double.POSITIVE_INFINITY;
		}

		double cap = (centerDistance - ICON_BOX_HALF_SIZE) / labelHalfExtent;
		return Double.isFinite(cap) && cap > 0.0 ? cap : Math.nextUp(0.0);
	}

	/**
	 * The target label's fitted GUI placement.  Coordinates are pre-scale
	 * translation coordinates: the renderer translates to {@link #x()} and
	 * {@link #topY()}, then applies {@link #scale()} to the vanilla font.
	 */
	public record TargetLabelPlacement(
		double x,
		double topY,
		double scale,
		double renderedWidth,
		double renderedHeight
	) {
		public TargetLabelPlacement {
			if (!Double.isFinite(x) || !Double.isFinite(topY)) {
				throw new IllegalArgumentException("placement coordinates must be finite");
			}

			if (!Double.isFinite(scale) || scale < 0.0) {
				throw new IllegalArgumentException("scale must be finite and non-negative: " + scale);
			}

			if (!Double.isFinite(renderedWidth) || renderedWidth < 0.0
				|| !Double.isFinite(renderedHeight) || renderedHeight < 0.0) {
				throw new IllegalArgumentException("rendered dimensions must be finite and non-negative");
			}
		}
	}

	/**
	 * Fits the target label to the available GUI bounds while preserving its
	 * requested size whenever the label fits above the configured wheel.  The
	 * vertical cap uses the wheel's outer radius and target-label gap; the
	 * horizontal cap keeps the centered label inside the GUI.  If an unusually
	 * small GUI leaves no room above the wheel, the label is clamped to the top
	 * of the GUI rather than being clipped.
	 */
	public static TargetLabelPlacement targetLabelFit(
		double centerX,
		double centerY,
		double guiWidth,
		double guiHeight,
		double outerRadius,
		int textWidth,
		double lineHeight,
		double requestedScale
	) {
		if (!Double.isFinite(centerX) || !Double.isFinite(centerY)
			|| !Double.isFinite(guiWidth) || !Double.isFinite(guiHeight)
			|| guiWidth < 0.0 || guiHeight < 0.0) {
			throw new IllegalArgumentException("GUI coordinates and dimensions must be finite and non-negative");
		}

		if (!Double.isFinite(outerRadius) || outerRadius <= 0.0) {
			throw new IllegalArgumentException("outerRadius must be positive and finite: " + outerRadius);
		}

		if (textWidth < 0) {
			throw new IllegalArgumentException("textWidth must not be negative: " + textWidth);
		}

		if (!Double.isFinite(lineHeight) || lineHeight <= 0.0) {
			throw new IllegalArgumentException("lineHeight must be positive and finite: " + lineHeight);
		}

		if (!Double.isFinite(requestedScale) || requestedScale < 0.0) {
			throw new IllegalArgumentException(
				"requestedScale must be finite and non-negative: " + requestedScale);
		}

		double scale = requestedScale;

		if (textWidth > 0) {
			double horizontalRoom = Math.max(0.0, 2.0 * Math.min(centerX, guiWidth - centerX));
			scale = Math.min(scale, horizontalRoom / textWidth);
		}

		// Prefer the configured size when the label can sit above the wheel with
		// its existing gap.  The fallback cap still keeps the label inside a very
		// small GUI when the wheel itself occupies that entire vertical space.
		double roomAboveWheel = centerY - outerRadius - TARGET_LABEL_GAP;
		if (roomAboveWheel > 0.0) {
			scale = Math.min(scale, roomAboveWheel / lineHeight);
		} else {
			scale = Math.min(scale, guiHeight / lineHeight);
		}

		scale = Math.max(0.0, scale);
		double renderedWidth = textWidth * scale;
		double renderedHeight = lineHeight * scale;
		double x = centerX - renderedWidth * 0.5;
		double maxX = Math.max(0.0, guiWidth - renderedWidth);
		x = Math.clamp(x, 0.0, maxX);

		double topY = centerY - outerRadius - TARGET_LABEL_GAP - renderedHeight;
		double maxY = Math.max(0.0, guiHeight - renderedHeight);
		topY = Math.clamp(topY, 0.0, maxY);

		return new TargetLabelPlacement(x, topY, scale, renderedWidth, renderedHeight);
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
