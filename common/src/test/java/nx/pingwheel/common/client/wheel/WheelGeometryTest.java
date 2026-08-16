package nx.pingwheel.common.client.wheel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.interaction.wheel.WheelSelection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelGeometryTest {

	private static final double TWO_PI = Math.PI * 2.0;
	private static final double MID_RADIUS = 53.0; // (28 + 78) / 2

	@Test
	void defaultRadiiAre28And78() {
		WheelGeometry geometry = new WheelGeometry();

		assertEquals(28.0, geometry.innerRadius());
		assertEquals(78.0, geometry.outerRadius());
		assertEquals(53.0, geometry.midRadius());
	}

	@Test
	void customRadiiAreExposed() {
		WheelGeometry geometry = new WheelGeometry(10.0, 20.0);

		assertEquals(10.0, geometry.innerRadius());
		assertEquals(20.0, geometry.outerRadius());
		assertEquals(15.0, geometry.midRadius());
	}

	@Test
	void rejectsInvalidRadii() {
		assertThrows(IllegalArgumentException.class, () -> new WheelGeometry(0.0, 78.0));
		assertThrows(IllegalArgumentException.class, () -> new WheelGeometry(-1.0, 78.0));
		assertThrows(IllegalArgumentException.class, () -> new WheelGeometry(28.0, 28.0));
		assertThrows(IllegalArgumentException.class, () -> new WheelGeometry(78.0, 28.0));
		assertThrows(IllegalArgumentException.class, () -> new WheelGeometry(Double.NaN, 78.0));
		assertThrows(IllegalArgumentException.class, () -> new WheelGeometry(28.0, Double.POSITIVE_INFINITY));
	}

	@Test
	void sectorsMatchDeclarationOrderAndCount() {
		List<PingType> pingTypes = builtInPingTypes();
		List<WheelSector> sectors = new WheelGeometry().sectors(pingTypes);

		assertEquals(pingTypes.size(), sectors.size());

		for (int i = 0; i < pingTypes.size(); i++) {
			assertEquals(i, sectors.get(i).index());
			assertEquals(pingTypes.get(i), sectors.get(i).pingType());
		}
	}

	@Test
	void sectorsPartitionFullRingWithoutGaps() {
		List<WheelSector> sectors = new WheelGeometry().sectors(builtInPingTypes());

		assertEquals(0.0, sectors.get(0).startAngleRadians());
		assertEquals(TWO_PI, sectors.get(sectors.size() - 1).endAngleRadians());

		double totalSpan = 0.0;

		for (int i = 0; i < sectors.size(); i++) {
			WheelSector sector = sectors.get(i);

			totalSpan += sector.endAngleRadians() - sector.startAngleRadians();

			if (i + 1 < sectors.size()) {
				// Adjacent sectors share the exact same boundary angle.
				assertEquals(sector.endAngleRadians(), sectors.get(i + 1).startAngleRadians(), 0.0);
			}
		}

		assertEquals(TWO_PI, totalSpan, 1e-12);

		double span = TWO_PI / sectors.size();

		for (int i = 0; i < sectors.size(); i++) {
			assertEquals(i * span, sectors.get(i).startAngleRadians(), 1e-12);
		}
	}

	@Test
	void singlePingTypeSpansFullRing() {
		List<WheelSector> sectors = new WheelGeometry().sectors(List.of(pingType("attention")));

		assertEquals(1, sectors.size());
		assertEquals(0, sectors.get(0).index());
		assertEquals(0.0, sectors.get(0).startAngleRadians());
		assertEquals(TWO_PI, sectors.get(0).endAngleRadians());
	}

	@Test
	void sectorBorderColorCarriesTypeOutlineColorForBothBorders() {
		List<PingType> pingTypes = fourPingTypes();
		List<WheelSector> sectors = new WheelGeometry().sectors(pingTypes);

		for (WheelSector sector : sectors) {
			int borderColor = 0xFF000000 | sector.outlineColor();

			// The same opaque type color backs both the inner and outer borders.
			assertEquals(0xFF, (borderColor >>> 24) & 0xFF);
			assertEquals(sector.pingType().outlineColor(), borderColor & 0xFFFFFF);
			assertEquals(sector.outlineColor(), borderColor & 0xFFFFFF);
		}

		// All four built-in sectors carry distinct colors matching the catalog.
		assertEquals(4, sectors.stream().map(WheelSector::outlineColor).distinct().count());
		assertEquals(
			pingTypes.stream().map(PingType::outlineColor).toList(),
			sectors.stream().map(WheelSector::outlineColor).toList());
	}

	@Test
	void sectorsAreImmutableAndDefensive() {
		List<PingType> mutableInput = new ArrayList<>(builtInPingTypes());
		List<WheelSector> sectors = new WheelGeometry().sectors(mutableInput);

		assertThrows(UnsupportedOperationException.class, () -> sectors.add(sectors.get(0)));

		PingType replacement = pingType("loot");
		mutableInput.set(0, replacement);

		assertEquals(builtInPingTypes().get(0), sectors.get(0).pingType());

		List<WheelSector> second = new WheelGeometry().sectors(builtInPingTypes());
		assertEquals(sectors, second);
	}

	@Test
	void sectorsRejectNullAndEmptyAndNullElements() {
		WheelGeometry geometry = new WheelGeometry();

		assertThrows(NullPointerException.class, () -> geometry.sectors(null));
		assertThrows(IllegalArgumentException.class, () -> geometry.sectors(List.of()));

		List<PingType> withNull = new ArrayList<>(builtInPingTypes());
		withNull.set(1, null);
		assertThrows(NullPointerException.class, () -> geometry.sectors(withNull));
	}

	@Test
	void selectCenterForInnerArea() {
		WheelGeometry geometry = new WheelGeometry();
		List<PingType> pingTypes = builtInPingTypes();

		assertSame(WheelSelection.CENTER, geometry.select(0.0, 0.0, pingTypes));
		assertSame(WheelSelection.CENTER, geometry.select(10.0, 10.0, pingTypes));
		// The inner radius itself is the center boundary.
		assertSame(WheelSelection.CENTER, geometry.select(28.0, 0.0, pingTypes));
		assertSame(WheelSelection.CENTER, geometry.select(0.0, -28.0, pingTypes));
		assertSame(WheelSelection.CENTER, geometry.select(27.999, 0.0, pingTypes));
	}

	@Test
	void selectNoneOutsideOuterRadius() {
		WheelGeometry geometry = new WheelGeometry();
		List<PingType> pingTypes = builtInPingTypes();

		assertSame(WheelSelection.NONE, geometry.select(78.001, 0.0, pingTypes));
		assertSame(WheelSelection.NONE, geometry.select(0.0, -78.001, pingTypes));
		assertSame(WheelSelection.NONE, geometry.select(500.0, 500.0, pingTypes));
	}

	@Test
	void selectOuterRadiusBoundaryStillSelectsSector() {
		WheelGeometry geometry = new WheelGeometry();
		List<PingType> pingTypes = builtInPingTypes();

		// Exactly on the outer radius (right side): sector 1 (danger).
		assertEquals(pingType("danger"), sectorType(geometry.select(78.0, 0.0, pingTypes)));
		assertEquals(pingType("attention"), sectorType(geometry.select(0.0, -78.0, pingTypes)));
	}

	@Test
	void selectCardinalAngles() {
		WheelGeometry geometry = new WheelGeometry();
		List<PingType> pingTypes = fourPingTypes();

		assertEquals(pingType("attention"), sectorType(geometry.select(0.0, -MID_RADIUS, pingTypes)));
		assertEquals(pingType("danger"), sectorType(geometry.select(MID_RADIUS, 0.0, pingTypes)));
		assertEquals(pingType("go_to"), sectorType(geometry.select(0.0, MID_RADIUS, pingTypes)));
		assertEquals(pingType("loot"), sectorType(geometry.select(-MID_RADIUS, 0.0, pingTypes)));
	}

	@Test
	void selectSectorBoundaryBelongsToStartingSector() {
		WheelGeometry geometry = new WheelGeometry();
		List<PingType> pingTypes = fourPingTypes();
		double halfPi = Math.PI / 2.0;

		// Exactly on the boundary angle π/2 (right side): sector 1 starts there.
		assertEquals(pingType("danger"), sectorType(geometry.select(MID_RADIUS, 0.0, pingTypes)));
		// Just before the boundary: sector 0.
		assertEquals(
			pingType("attention"),
			sectorType(geometry.select(
				Math.sin(halfPi - 0.01) * MID_RADIUS,
				-Math.cos(halfPi - 0.01) * MID_RADIUS,
				pingTypes)));
		// Just after the boundary: sector 1.
		assertEquals(
			pingType("danger"),
			sectorType(geometry.select(
				Math.sin(halfPi + 0.01) * MID_RADIUS,
				-Math.cos(halfPi + 0.01) * MID_RADIUS,
				pingTypes)));
	}

	@Test
	void selectOnePingTypeSelectsItAcrossFullRing() {
		WheelGeometry geometry = new WheelGeometry();
		List<PingType> pingTypes = List.of(pingType("attention"));

		assertEquals(pingType("attention"), sectorType(geometry.select(0.0, -MID_RADIUS, pingTypes)));
		assertEquals(pingType("attention"), sectorType(geometry.select(MID_RADIUS, 0.0, pingTypes)));
		assertEquals(pingType("attention"), sectorType(geometry.select(-30.0, 30.0, pingTypes)));
		assertSame(WheelSelection.CENTER, geometry.select(0.0, 0.0, pingTypes));
		assertSame(WheelSelection.NONE, geometry.select(0.0, -79.0, pingTypes));
		assertEquals(pingType("attention"), sectorType(geometry.select(0.0, -78.0, pingTypes)));
	}

	@Test
	void selectRejectsInvalidInputs() {
		WheelGeometry geometry = new WheelGeometry();
		List<PingType> pingTypes = builtInPingTypes();

		assertThrows(NullPointerException.class, () -> geometry.select(1.0, 1.0, null));
		assertThrows(IllegalArgumentException.class, () -> geometry.select(0.0, 0.0, List.of()));
		assertThrows(IllegalArgumentException.class, () -> geometry.select(Double.NaN, 1.0, pingTypes));
		assertThrows(IllegalArgumentException.class, () -> geometry.select(1.0, Double.POSITIVE_INFINITY, pingTypes));
	}

	@Test
	void selectIsDeterministicAcrossInstancesAndRuns() {
		WheelGeometry first = new WheelGeometry();
		WheelGeometry second = new WheelGeometry();
		List<PingType> pingTypes = builtInPingTypes();

		for (double x = -80.0; x <= 80.0; x += 7.5) {
			for (double y = -80.0; y <= 80.0; y += 7.5) {
				assertEquals(first.select(x, y, pingTypes), second.select(x, y, pingTypes));
				assertEquals(first.select(x, y, pingTypes), first.select(x, y, pingTypes));
			}
		}
	}

	@Test
	void pointAtMapsClockwiseAnglesFromTop() {
		WheelGeometry geometry = new WheelGeometry();

		assertPointEquals(0.0, -10.0, geometry.pointAt(0.0, 10.0));
		assertPointEquals(10.0, 0.0, geometry.pointAt(Math.PI / 2.0, 10.0));
		assertPointEquals(0.0, 10.0, geometry.pointAt(Math.PI, 10.0));
		assertPointEquals(-10.0, 0.0, geometry.pointAt(Math.PI * 1.5, 10.0));
	}

	@Test
	void midpointLiesAtMidAngleAndMidRadius() {
		WheelGeometry geometry = new WheelGeometry();
		List<WheelSector> sectors = geometry.sectors(fourPingTypes());

		WheelPoint midpoint = geometry.midpoint(sectors.get(0));

		double expectedAngle = Math.PI / 4.0; // sector 0 spans [0, π/2)
		assertPointEquals(
			Math.sin(expectedAngle) * MID_RADIUS,
			-Math.cos(expectedAngle) * MID_RADIUS,
			midpoint);
	}

	@Test
	void midpointRejectsNullSector() {
		assertThrows(NullPointerException.class, () -> new WheelGeometry().midpoint(null));
	}

	@Test
	void arcPointsSampleProportionallyWithEndpoints() {
		WheelGeometry geometry = new WheelGeometry();
		WheelSector sector = geometry.sectors(fourPingTypes()).get(0);

		List<WheelPoint> points = geometry.arcPoints(sector, MID_RADIUS, 64);

		assertEquals(16, points.size());
		assertPointEquals(
			geometry.pointAt(sector.startAngleRadians(), MID_RADIUS),
			points.get(0));
		assertPointEquals(
			geometry.pointAt(sector.endAngleRadians(), MID_RADIUS),
			points.get(points.size() - 1));

		// All sampled points lie on the requested radius.
		for (WheelPoint point : points) {
			assertEquals(MID_RADIUS, Math.hypot(point.x(), point.y()), 1e-9);
		}
	}

	@Test
	void arcPointsOfFullRingSectorCloseTheLoop() {
		WheelGeometry geometry = new WheelGeometry();
		WheelSector sector = geometry.sectors(List.of(pingType("attention"))).get(0);

		List<WheelPoint> points = geometry.arcPoints(sector, MID_RADIUS, 72);

		assertEquals(72, points.size());
		assertPointEquals(points.get(0), points.get(points.size() - 1));
	}

	@Test
	void arcPointsNeverFallBelowTwoSamples() {
		WheelGeometry geometry = new WheelGeometry();
		List<WheelSector> sectors = geometry.sectors(eightPingTypes());

		for (WheelSector sector : sectors) {
			assertEquals(2, geometry.arcPoints(sector, MID_RADIUS, 2).size());
		}
	}

	@Test
	void arcPointSamplingIsConsistentAcrossResolutions() {
		WheelGeometry geometry = new WheelGeometry();
		WheelSector sector = geometry.sectors(fourPingTypes()).get(0); // span π/2

		List<WheelPoint> coarse = geometry.arcPoints(sector, MID_RADIUS, 72);
		List<WheelPoint> fine = geometry.arcPoints(sector, MID_RADIUS, 144);
		List<WheelPoint> odd = geometry.arcPoints(sector, MID_RADIUS, 12); // 3 samples

		// All resolutions share the exact same sector boundary endpoints.
		assertPointEquals(coarse.get(0), fine.get(0));
		assertPointEquals(coarse.get(coarse.size() - 1), fine.get(fine.size() - 1));
		assertPointEquals(fine.get(0), odd.get(0));
		assertPointEquals(fine.get(fine.size() - 1), odd.get(odd.size() - 1));

		// An odd sample count has its middle sample exactly at the sector
		// midpoint angle — the same point where the icon is drawn.
		assertEquals(3, odd.size());
		assertPointEquals(geometry.midpoint(sector), odd.get(odd.size() / 2));

		// Every resolution is uniformly spaced across the sector span, so the
		// rendered arc segments have a constant angular step.
		assertUniformSampling(geometry, sector, coarse);
		assertUniformSampling(geometry, sector, fine);
	}

	private static void assertUniformSampling(
		WheelGeometry geometry,
		WheelSector sector,
		List<WheelPoint> points
	) {
		double expectedStep = (sector.endAngleRadians() - sector.startAngleRadians()) / (points.size() - 1);

		for (int i = 0; i < points.size(); i++) {
			assertPointEquals(
				geometry.pointAt(sector.startAngleRadians() + expectedStep * i, MID_RADIUS),
				points.get(i));
		}
	}

	@Test
	void arcPointsRejectInvalidInputs() {
		WheelGeometry geometry = new WheelGeometry();
		WheelSector sector = geometry.sectors(builtInPingTypes()).get(0);

		assertThrows(NullPointerException.class, () -> geometry.arcPoints(null, 10.0, 8));
		assertThrows(IllegalArgumentException.class, () -> geometry.arcPoints(sector, 0.0, 8));
		assertThrows(IllegalArgumentException.class, () -> geometry.arcPoints(sector, -1.0, 8));
		assertThrows(IllegalArgumentException.class, () -> geometry.arcPoints(sector, Double.NaN, 8));
		assertThrows(IllegalArgumentException.class, () -> geometry.arcPoints(sector, 10.0, 1));
	}

	@Test
	void circlePointsSampleFullCircleOnRadius() {
		WheelGeometry geometry = new WheelGeometry();

		List<WheelPoint> points = geometry.circlePoints(10.0, 48);

		assertEquals(48, points.size());
		assertPointEquals(0.0, -10.0, points.get(0));

		for (WheelPoint point : points) {
			assertEquals(10.0, Math.hypot(point.x(), point.y()), 1e-9);
		}
	}

	@Test
	void circlePointsRejectInvalidInputs() {
		WheelGeometry geometry = new WheelGeometry();

		assertThrows(IllegalArgumentException.class, () -> geometry.circlePoints(0.0, 8));
		assertThrows(IllegalArgumentException.class, () -> geometry.circlePoints(Double.POSITIVE_INFINITY, 8));
		assertThrows(IllegalArgumentException.class, () -> geometry.circlePoints(10.0, 1));
	}

	private static PingType sectorType(WheelSelection selection) {
		assertTrue(selection instanceof WheelSelection.Sector, "expected sector, got " + selection);
		return ((WheelSelection.Sector) selection).pingType();
	}

	private static void assertPointEquals(WheelPoint expected, WheelPoint actual) {
		assertPointEquals(expected.x(), expected.y(), actual);
	}

	private static void assertPointEquals(double x, double y, WheelPoint actual) {
		assertEquals(x, actual.x(), 1e-9);
		assertEquals(y, actual.y(), 1e-9);
	}

	private static List<PingType> builtInPingTypes() {
		return PingTypeCatalog.builtIn().entries();
	}

	/**
	 * The confirmed original four ping types: geometry tests that assert
	 * concrete 4-sector angles/counts pin their input to this fixed wheel so
	 * the assertions stay exact regardless of built-in catalog growth.
	 */
	private static List<PingType> fourPingTypes() {
		return List.of(pingType("attention"), pingType("danger"), pingType("go_to"), pingType("loot"));
	}

	private static List<PingType> eightPingTypes() {
		List<PingType> pingTypes = new ArrayList<>();

		for (int i = 0; i < 8; i++) {
			pingTypes.add(new PingType(
				"t" + i,
				"pingforit.ping_type.t" + i + ".phrase",
				"pingforit.ping_type.t" + i,
				0x100000 + i,
				0x200000 + i,
				Optional.empty()));
		}

		return pingTypes;
	}

	private static PingType pingType(String id) {
		return PingTypeCatalog.builtIn().findById(id).orElseThrow();
	}
}
