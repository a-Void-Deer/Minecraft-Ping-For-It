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
	void targetLabelLeavesGapAboveOuterRadius() {
		double centerY = 200.0;
		int lineHeight = 9;
		int top = WheelLabelLayout.targetLabelTopY(centerY, GEOMETRY.outerRadius(), lineHeight);

		assertTrue(top + lineHeight <= centerY - GEOMETRY.outerRadius() - WheelLabelLayout.TARGET_LABEL_GAP);
	}
}
