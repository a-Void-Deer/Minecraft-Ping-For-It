package nx.pingwheel.common.client.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.PingTypeCatalog;

class WheelLabelLayoutTest {

	private static final WheelGeometry GEOMETRY = new WheelGeometry();

	@Test
	void iconAndLabelStayOnTheSameSectorMidpoint() {
		List<WheelSector> sectors = GEOMETRY.sectors(PingTypeCatalog.builtIn().entries());
		List<WheelLabelLayout.Placement> placements = WheelLabelLayout.layout(
			GEOMETRY,
			sectors,
			List.of(10, 10, 10, 10, 10, 10, 10));

		for (int i = 0; i < placements.size(); i++) {
			WheelLabelLayout.Placement placement = placements.get(i);
			WheelPoint icon = placement.iconAnchor();
			WheelPoint label = placement.labelAnchor();

			assertTrue(icon.x() * label.y() - icon.y() * label.x() == 0.0
				|| Math.abs(icon.x() * label.y() - icon.y() * label.x()) < 1.0e-9);
			assertTrue(Math.hypot(icon.x(), icon.y()) < Math.hypot(label.x(), label.y()));
			assertEquals(sectors.get(i).pingType(), placement.pingType());
		}
	}

	@Test
	void labelsHaveUniqueSectorAssociationsAndBoundedScales() {
		List<WheelSector> sectors = GEOMETRY.sectors(PingTypeCatalog.builtIn().entries());
		List<WheelLabelLayout.Placement> placements = WheelLabelLayout.layout(
			GEOMETRY,
			sectors,
			List.of(10, 10, 10, 10, 10, 10, 1000));
		Set<Integer> indices = new HashSet<>();

		for (WheelLabelLayout.Placement placement : placements) {
			indices.add(placement.sectorIndex());
			assertTrue(placement.scale() > 0.0);
			assertTrue(placement.scale() <= WheelLabelLayout.BASE_TEXT_SCALE);
			assertTrue(placement.renderedWidth() <= placement.maxWidth() + 1.0e-9);
		}

		assertEquals(sectors.size(), indices.size());
		assertTrue(placements.get(6).scale() < WheelLabelLayout.BASE_TEXT_SCALE);
	}

	@Test
	void configuredMaximumScaleCanExceedTheHistoricalHalfScale() {
		List<WheelSector> sectors = GEOMETRY.sectors(List.of(PingTypeCatalog.builtIn().entries().get(0)));

		for (double configuredScale : List.of(0.25, 0.5, 1.0)) {
			WheelLabelLayout.Placement placement = WheelLabelLayout.layout(
				GEOMETRY,
				sectors,
				List.of(1),
				WheelLabelLayout.DEFAULT_LABEL_LINE_HEIGHT,
				configuredScale).get(0);

			assertEquals(configuredScale, placement.scale(), 1.0e-12);
		}
	}

	@Test
	void labelOriginCentersOddDimensionsAtTheAnchor() {
		WheelPoint anchor = new WheelPoint(37.25, -12.75);
		WheelPoint origin = WheelLabelLayout.labelOrigin(anchor, 9, 9.0, 0.5);

		assertEquals(anchor.x(), origin.x() + 9 * 0.5 * 0.5, 1.0e-12);
		assertEquals(anchor.y(), origin.y() + 9 * 0.5 * 0.5, 1.0e-12);
	}

	@Test
	void realisticAndPessimisticLabelsStayInsideTheirCircleAndSector() {
		List<WheelSector> sectors = GEOMETRY.sectors(PingTypeCatalog.builtIn().entries());

		for (List<Integer> widths : List.of(
			List.of(54, 44, 36, 36, 54, 36, 42),
			List.of(68, 72, 64, 60, 70, 58, 66))) {
			List<WheelLabelLayout.Placement> placements = WheelLabelLayout.layout(
				GEOMETRY,
				sectors,
				widths,
				WheelLabelLayout.DEFAULT_LABEL_LINE_HEIGHT);

			for (int i = 0; i < placements.size(); i++) {
				WheelLabelLayout.Placement placement = placements.get(i);
				WheelSector sector = sectors.get(i);
				List<WheelPoint> corners = labelCorners(placement, WheelLabelLayout.DEFAULT_LABEL_LINE_HEIGHT);

				assertTrue(placement.scale() > 0.0);
				assertTrue(placement.scale() <= WheelLabelLayout.BASE_TEXT_SCALE);
				for (WheelPoint corner : corners) {
					assertTrue(
						Math.hypot(corner.x(), corner.y()) <= GEOMETRY.outerRadius() + 1.0e-8,
						"corner outside outer circle: " + corner);
					double angle = clockwiseAngle(corner);
					assertTrue(angle >= sector.startAngleRadians() - 1.0e-8);
					assertTrue(angle <= sector.endAngleRadians() + 1.0e-8);
				}

				assertNoIconBoxOverlap(placement, WheelLabelLayout.DEFAULT_LABEL_LINE_HEIGHT);
			}
		}
	}

	@Test
	void targetLabelLeavesGapAboveOuterRadius() {
		double centerY = 200.0;
		int lineHeight = 9;
		int top = WheelLabelLayout.targetLabelTopY(centerY, GEOMETRY.outerRadius(), lineHeight);

		assertTrue(top + lineHeight <= centerY - GEOMETRY.outerRadius() - WheelLabelLayout.TARGET_LABEL_GAP);
	}

	@Test
	void targetLabelFitPreservesConfiguredSizeWhenItFits() {
		WheelLabelLayout.TargetLabelPlacement placement = WheelLabelLayout.targetLabelFit(
			160.0,
			100.0,
			320.0,
			200.0,
			39.0,
			100,
			9.0,
			2.0);

		assertEquals(2.0, placement.scale(), 1.0e-12);
		assertEquals(60.0, placement.x(), 1.0e-12);
		assertEquals(39.0, placement.topY(), 1.0e-12);
		assertTrue(placement.x() >= 0.0);
		assertTrue(placement.x() + placement.renderedWidth() <= 320.0);
		assertTrue(placement.topY() + placement.renderedHeight() <= 100.0 - 39.0 - WheelLabelLayout.TARGET_LABEL_GAP);
	}

	@Test
	void targetLabelFitCapsWidthAndTopForLargeVisuals() {
		WheelLabelLayout.TargetLabelPlacement placement = WheelLabelLayout.targetLabelFit(
			160.0,
			80.0,
			320.0,
			160.0,
			75.0,
			600,
			9.0,
			2.0);

		assertTrue(placement.scale() < 2.0);
		assertTrue(placement.x() >= 0.0);
		assertTrue(placement.x() + placement.renderedWidth() <= 320.0 + 1.0e-9);
		assertTrue(placement.topY() >= 0.0);
		assertTrue(placement.topY() + placement.renderedHeight() <= 160.0 + 1.0e-9);
	}

	@Test
	void smallestConfiguredGeometryStillProvidesUsableLabels() {
		WheelGeometry geometry = new WheelGeometry(6.0, 20.0);
		List<WheelSector> sectors = geometry.sectors(PingTypeCatalog.builtIn().entries());
		List<WheelLabelLayout.Placement> placements = WheelLabelLayout.layout(
			geometry,
			sectors,
			List.of(54, 44, 36, 36, 54, 36, 42));

		assertEquals(6.0, geometry.innerRadius());
		assertEquals(20.0, geometry.outerRadius());
		for (WheelLabelLayout.Placement placement : placements) {
			assertTrue(placement.scale() > 0.0);
			for (WheelPoint corner : labelCorners(placement, WheelLabelLayout.DEFAULT_LABEL_LINE_HEIGHT)) {
				assertTrue(Math.hypot(corner.x(), corner.y()) <= geometry.outerRadius() + 1.0e-8);
			}
		}
	}

	private static List<WheelPoint> labelCorners(
		WheelLabelLayout.Placement placement,
		double lineHeight
	) {
		double halfWidth = placement.textWidth() * placement.scale() * 0.5;
		double halfHeight = lineHeight * placement.scale() * 0.5;
		WheelPoint anchor = placement.labelAnchor();

		return List.of(
			new WheelPoint(anchor.x() - halfWidth, anchor.y() - halfHeight),
			new WheelPoint(anchor.x() - halfWidth, anchor.y() + halfHeight),
			new WheelPoint(anchor.x() + halfWidth, anchor.y() - halfHeight),
			new WheelPoint(anchor.x() + halfWidth, anchor.y() + halfHeight));
	}

	private static double clockwiseAngle(WheelPoint point) {
		double angle = Math.atan2(point.x(), -point.y());
		return angle < 0.0 ? angle + WheelGeometry.TWO_PI : angle;
	}

	private static void assertNoIconBoxOverlap(
		WheelLabelLayout.Placement placement,
		double lineHeight
	) {
		WheelPoint icon = placement.iconAnchor();
		WheelPoint label = placement.labelAnchor();
		double iconHalfSize = 4.0;
		double labelHalfWidth = placement.textWidth() * placement.scale() * 0.5;
		double labelHalfHeight = lineHeight * placement.scale() * 0.5;

		boolean separatedHorizontally = label.x() - labelHalfWidth >= icon.x() + iconHalfSize
			|| label.x() + labelHalfWidth <= icon.x() - iconHalfSize;
		boolean separatedVertically = label.y() - labelHalfHeight >= icon.y() + iconHalfSize
			|| label.y() + labelHalfHeight <= icon.y() - iconHalfSize;

		assertTrue(
			separatedHorizontally || separatedVertically,
			"label overlaps icon: label=" + label + " icon=" + icon);
	}
}
