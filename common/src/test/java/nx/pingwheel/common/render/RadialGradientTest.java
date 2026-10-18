package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Records the production vertex emitter, not a second gradient algorithm.
 * Oracles protect radial alpha, final-only quantization, bearing convention,
 * shared-edge topology and a finite span-sized work budget; no GPU is involved.
 */
class RadialGradientTest {

	private static final double RADIUS = 80.0;
	private static final int RGB = 0x1234A6E8;
	private static final Comparator<Vertex> POSITION = Comparator.comparingDouble(Vertex::x)
		.thenComparingDouble(Vertex::y);

	private record Vertex(float x, float y, int color) {
		double radius() { return Math.hypot(x, y); }
		int alpha() { return color >>> 24; }
	}
	private record Edge(Vertex first, Vertex second) {}

	@Test
	void alphaDependsOnlyOnRadiusAndPreservesCoverageAtTheCenterWithATransparentRim() {
		var vertices = mesh(RADIUS, 332.5, 55.0, RGB, 0.30, 100.0);
		assertFalse(vertices.isEmpty());
		var radial = vertices.stream().sorted(Comparator.comparingDouble(Vertex::radius)).toList();
		assertEquals(0.0, radial.getFirst().radius());
		assertEquals(77, radial.getFirst().alpha(), "30% coverage retains its unrounded center strength");
		assertEquals(RADIUS, radial.getLast().radius(), 1.0e-5);
		assertEquals(0, radial.getLast().alpha(), "the actual outer edge is transparent");
		boolean intermediate = false;
		for (int i = 0; i < radial.size(); i++) {
			Vertex vertex = radial.get(i);
			assertEquals(RGB & 0x00FFFFFF, vertex.color() & 0x00FFFFFF,
				"alpha falloff must not whiten, tint or replace the supplied RGB");
			if (vertex.radius() < 1.0e-5) assertEquals(77, vertex.alpha());
			if (Math.abs(vertex.radius() - RADIUS) < 1.0e-5) assertEquals(0, vertex.alpha());
			intermediate |= vertex.alpha() > 0 && vertex.alpha() < 77;
			if (i == 0) continue;
			Vertex previous = radial.get(i - 1);
			assertTrue(vertex.alpha() <= previous.alpha(), "alpha cannot increase toward the rim");
			if (Math.abs(vertex.radius() - previous.radius()) < 1.0e-5) {
				assertEquals(previous.alpha(), vertex.alpha(), "equal radii have equal alpha across all bearings");
			}
		}
		assertTrue(intermediate, "the fade is not a flat fill or an abrupt opaque/transparent split");
	}

	@Test
	void coverageAndOpacityRemainFractionalUntilTheFinalVertexByte() {
		var halfOpacity = mesh(RADIUS, 0, 90, RGB, 0.30, 50);
		var halfCoverage = mesh(RADIUS, 0, 90, RGB, 0.15, 100);
		assertEquals(halfCoverage, halfOpacity,
			"equivalent fractional strengths must agree at every ring, not just the center");
		assertEquals(38, halfOpacity.getFirst().alpha(),
			"pre-rounding 30% to 77 and then halving would incorrectly yield 39");
		var fullOpacity = mesh(RADIUS, 0, 90, RGB, 0.30, 100);
		Vertex midpoint = closestToRadius(fullOpacity, RADIUS / 2);
		assertEquals(RADIUS / 2, midpoint.radius(), 1.0e-5);
		assertEquals(38, midpoint.alpha(),
			"the smoothstep midpoint halves fractional 76.5, not a pre-rounded center byte of 77");
		assertEquals(mesh(RADIUS, 0, 90, RGB, 1, 100), mesh(RADIUS, 0, 90, RGB, 4, 200),
			"finite out-of-range coverage and opacity are clamped before multiplication");
		assertEquals(mesh(RADIUS, 0, 90, RGB, 0.30, 100),
			mesh(RADIUS, 0, 90, RGB ^ 0xFF000000, 0.30, 100), "the old base alpha is not another multiplier");
	}

	@Test
	void fadeHasSoftInnerAndOuterShouldersRatherThanALinearRamp() {
		var vertices = mesh(RADIUS, 0, 90, RGB, 1, 100);
		Vertex inner = closestToRadius(vertices, RADIUS * 0.125);
		Vertex outer = closestToRadius(vertices, RADIUS * 0.875);
		// Inverse smoothstep stays above the straight chord near the center and
		// below it near the rim. Broad inequalities avoid pinning mesh resolution.
		assertTrue(inner.radius() > 0 && inner.radius() < RADIUS / 4);
		assertTrue(outer.radius() > RADIUS * 3 / 4 && outer.radius() < RADIUS);
		assertTrue(inner.alpha() > 255 * (1 - inner.radius() / RADIUS) + 5);
		assertTrue(outer.alpha() < 255 * (1 - outer.radius() / RADIUS) - 5);
	}

	@Test
	void zeroIsUpAndPositiveSpansRunClockwiseIncludingTheBackSemicircle() {
		var upperRight = mesh(RADIUS, 0, 90, RGB, 1, 100);
		assertFalse(upperRight.isEmpty());
		for (Vertex vertex : upperRight) {
			assertTrue(vertex.x() >= -1.0e-5 && vertex.y() <= 1.0e-5);
		}
		assertPoint(upperRight, 0, -RADIUS);
		assertPoint(upperRight, RADIUS, 0);
		assertArea(upperRight, Math.PI * RADIUS * RADIUS / 4);

		var lowerRight = mesh(RADIUS, 90, 90, RGB, 1, 100);
		for (Vertex vertex : lowerRight) {
			assertTrue(vertex.x() >= -1.0e-5 && vertex.y() >= -1.0e-5);
		}
		assertPoint(lowerRight, 0, RADIUS);
		var leftBack = mesh(RADIUS, 180, 180, RGB, 1, 100);
		assertFalse(leftBack.isEmpty());
		for (Vertex vertex : leftBack) assertTrue(vertex.x() <= 1.0e-5);
		assertPoint(leftBack, 0, -RADIUS);
		assertPoint(leftBack, 0, RADIUS);
		assertPoint(leftBack, -RADIUS, 0);
		assertArea(leftBack, Math.PI * RADIUS * RADIUS / 2);
	}

	@Test
	void adjacentUnequalAndWrappedSectorsShareExactPositionsAndColorsOnTheirCommonRay() {
		var danger = mesh(RADIUS, 332.5, 55, RGB, 0.42, 67);
		var diagonal = mesh(RADIUS, 27.5, 35, RGB, 0.42, 67);
		var dangerEdge = edgeOnRay(danger, 27.5);
		var diagonalEdge = edgeOnRay(diagonal, 27.5);
		assertTrue(dangerEdge.size() > 2, "the comparison includes intermediate radial bands");
		assertEquals(dangerEdge, diagonalEdge, "a shared boundary must not crack or restart its fade");
		assertEquals(mesh(RADIUS, -27.5, 55, RGB, 0.42, 67), danger,
			"negative and wrapped starts describe the same sector");
	}

	@Test
	void fullDiscIsOneGuiWoundTilingWithOnlyTheOuterPerimeterUnpaired() {
		var vertices = mesh(RADIUS, 17.3, 360, RGB, 1, 100);
		assertFalse(vertices.isEmpty());
		assertEquals(0, vertices.size() % 4, "the native GUI route consumes complete quads");
		Map<Edge, Integer> uses = new HashMap<>();
		boolean centerDegenerate = false;
		for (int offset = 0; offset < vertices.size(); offset += 4) {
			var quad = vertices.subList(offset, offset + 4);
			assertTrue(signedArea(quad) < 0, "the winding must match vanilla GUI fill with culling enabled");
			assertEquals(quad.get(0).radius(), quad.get(1).radius(), 1.0e-5);
			assertEquals(quad.get(2).radius(), quad.get(3).radius(), 1.0e-5);
			assertTrue(quad.get(0).radius() < quad.get(2).radius(), "bands run outward, not overlapping inward discs");
			centerDegenerate |= quad.get(0).radius() == 0 && quad.get(0).equals(quad.get(1));
			for (int i = 0; i < 4; i++) {
				Vertex a = quad.get(i);
				Vertex b = quad.get((i + 1) % 4);
				if (a.equals(b)) continue;
				Edge edge = POSITION.compare(a, b) < 0 ? new Edge(a, b) : new Edge(b, a);
				uses.merge(edge, 1, Integer::sum);
			}
		}
		assertTrue(centerDegenerate, "the center is filled without painting a second overlapping primitive");
		int perimeterEdges = 0;
		for (var use : uses.entrySet()) {
			assertTrue(use.getValue() == 1 || use.getValue() == 2, "an edge cannot belong to overlapping repeated bands");
			if (use.getValue() == 2) continue;
			perimeterEdges++;
			assertEquals(RADIUS, use.getKey().first().radius(), 1.0e-5,
				"an unpaired internal edge would be a crack, including at the full-circle seam");
			assertEquals(RADIUS, use.getKey().second().radius(), 1.0e-5);
		}
		assertTrue(perimeterEdges > 0);
		assertArea(vertices, Math.PI * RADIUS * RADIUS);
	}

	@Test
	void workIsFiniteRadiusIndependentAndBudgetedByAngularSpan() {
		var disc = mesh(RADIUS, 0, 360, RGB, 1, 100);
		var small = mesh(RADIUS, 0, 1, RGB, 1, 100);
		assertTrue(small.size() < disc.size() / 8, "a narrow sector must not consume a full-disc angular budget");
		int rootVertices = 0;
		for (double span : new double[] { 55, 35, 55, 35, 55, 35, 55, 35 }) {
			rootVertices += mesh(RADIUS, 0, span, RGB, 1, 100).size();
		}
		assertTrue(rootVertices <= disc.size() * 1.25,
			"splitting the circle among eight choices adds only segment-rounding overhead");
		for (double radius : new double[] { Double.MIN_VALUE, 1, RADIUS, 10_000, Float.MAX_VALUE }) {
			for (double span : new double[] { 1, 55, 180, 360, Double.MAX_VALUE }) {
				var vertices = mesh(radius, Double.MAX_VALUE, span, RGB, 1, 100);
				assertFalse(vertices.isEmpty());
				assertTrue(vertices.size() < 16_384, "a mesh has a fixed finite work ceiling, not pixel-sized scanlines");
				assertEquals(mesh(RADIUS, Double.MAX_VALUE, span, RGB, 1, 100).size(), vertices.size());
				for (Vertex vertex : vertices) {
					assertTrue(Float.isFinite(vertex.x()) && Float.isFinite(vertex.y()));
					assertTrue(vertex.radius() <= radius * (1 + 1.0e-6) + Float.MIN_VALUE);
				}
			}
		}
	}

	@Test
	void zeroOpacityZeroCoverageAndInvalidInputsEmitNoGeometry() {
		for (double coverage : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY }) {
			assertTrue(mesh(RADIUS, 0, 90, RGB, coverage, 100).isEmpty());
		}
		for (double opacity : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY }) {
			assertTrue(mesh(RADIUS, 0, 90, RGB, 1, opacity).isEmpty());
		}
		assertTrue(mesh(RADIUS, 0, 90, RGB, 0.001, 100).isEmpty(), "even the peak quantizes to zero");
		for (double radius : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE }) {
			assertTrue(mesh(radius, 0, 90, RGB, 1, 100).isEmpty());
		}
		for (double span : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY }) {
			assertTrue(mesh(RADIUS, 0, span, RGB, 1, 100).isEmpty());
		}
		for (double start : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
			assertTrue(mesh(RADIUS, start, 90, RGB, 1, 100).isEmpty());
		}
	}

	private static List<Vertex> mesh(double radius, double start, double span, int rgb, double coverage, double opacity) {
		List<Vertex> vertices = new ArrayList<>();
		RadialGradient.emit(radius, start, span, rgb, coverage, opacity,
			(x, y, color) -> vertices.add(new Vertex(x, y, color)));
		return vertices;
	}

	private static Vertex closestToRadius(List<Vertex> vertices, double radius) {
		return vertices.stream().min(Comparator.comparingDouble(vertex -> Math.abs(vertex.radius() - radius))).orElseThrow();
	}

	private static void assertPoint(List<Vertex> vertices, double x, double y) {
		assertTrue(vertices.stream().anyMatch(vertex -> Math.abs(vertex.x() - x) < 1.0e-5
			&& Math.abs(vertex.y() - y) < 1.0e-5), "expected sector endpoint at (" + x + ", " + y + ")");
	}

	private static List<Vertex> edgeOnRay(List<Vertex> vertices, double degrees) {
		double x = Math.sin(Math.toRadians(degrees));
		double y = -Math.cos(Math.toRadians(degrees));
		return vertices.stream().filter(vertex -> Math.abs(vertex.x() * y - vertex.y() * x) < 1.0e-5
			&& vertex.x() * x + vertex.y() * y >= 0).distinct()
			.sorted(Comparator.comparingDouble(Vertex::radius)).toList();
	}

	private static void assertArea(List<Vertex> vertices, double circularArea) {
		double area = 0;
		for (int offset = 0; offset < vertices.size(); offset += 4) {
			area -= signedArea(vertices.subList(offset, offset + 4));
		}
		assertTrue(area > circularArea * 0.99 && area <= circularArea * 1.00001,
			"one non-overlapping inscribed sector should approximate, not repeatedly cover, its circular area");
	}

	private static double signedArea(List<Vertex> quad) {
		double twiceArea = 0;
		for (int i = 0; i < 4; i++) {
			Vertex first = quad.get(i);
			Vertex second = quad.get((i + 1) % 4);
			twiceArea += (double) first.x() * second.y() - (double) first.y() * second.x();
		}
		return twiceArea / 2;
	}
}
