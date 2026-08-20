package nx.pingwheel.common.client.outline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireframeEdgeExtractorTest {
	@Test
	void deduplicatesUnorderedTriangleEdges() {
		WireframeEdgeExtractor.Extraction extraction = WireframeEdgeExtractor.extract(
			new float[] {
				0, 0, 0,
				1, 0, 0,
				1, 1, 0,
				0, 1, 0
			},
			new int[] {0, 1, 2, 2, 1, 3},
			16);

		assertTrue(extraction.valid());
		assertEquals(5, extraction.edges().size());
		assertTrue(extraction.edges().contains(new WireframeEdgeExtractor.Edge(1, 2)));
	}

	@Test
	void malformedTrianglesAndIndicesRejectTheWholeMesh() {
		assertFalse(WireframeEdgeExtractor.extract(
			new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
			new int[] {0, 1, 3}, 16).valid());
		assertFalse(WireframeEdgeExtractor.extract(
			new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
			new int[] {0, 1, 1}, 16).valid());
		assertFalse(WireframeEdgeExtractor.extract(
			new float[] {0, 0, Float.NaN, 1, 0, 0, 0, 1, 0},
			new int[] {0, 1, 2}, 16).valid());
	}

	@Test
	void edgeBudgetRejectsRatherThanReturningAPrefix() {
		WireframeEdgeExtractor.Extraction extraction = WireframeEdgeExtractor.extract(
			new float[] {
				0, 0, 0,
				1, 0, 0,
				1, 1, 0,
				0, 1, 0
			},
			new int[] {0, 1, 2, 2, 1, 3},
			4);

		assertFalse(extraction.valid());
		assertTrue(extraction.edges().isEmpty());
	}
}
