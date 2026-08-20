package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywheelSilhouetteMaskTest {
	@Test
	void indexedTriangleBecomesAWindingPreservingDegenerateQuad() {
		FlywheelSilhouetteMask.Vertex first = new FlywheelSilhouetteMask.Vertex(1, 2, 3, .1F, .2F);
		FlywheelSilhouetteMask.Vertex second = new FlywheelSilhouetteMask.Vertex(4, 5, 6, .3F, .4F);
		FlywheelSilhouetteMask.Vertex third = new FlywheelSilhouetteMask.Vertex(7, 8, 9, .5F, .6F);
		FlywheelSilhouetteMask.RenderPlan<String> plan = new FlywheelSilhouetteMask.RenderPlan<>(List.of(
			new FlywheelSilhouetteMask.TextureBatch<>("texture", List.of(
				new FlywheelSilhouetteMask.Triangle(first, second, third)))));
		List<FlywheelSilhouetteMask.Vertex> emitted = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();

		int triangles = FlywheelSilhouetteMask.emit(plan, 0x80112233, texture ->
			(vertex, color) -> {
				assertEquals("texture", texture);
				emitted.add(vertex);
				colors.add(color);
			});

		assertEquals(1, triangles);
		assertEquals(List.of(first, second, third, third), emitted);
		assertEquals(List.of(0x80112233, 0x80112233, 0x80112233, 0x80112233), colors);
	}

	@Test
	void multipleTextureBatchesRemainInDeclaredGroupedOrder() {
		FlywheelSilhouetteMask.Vertex first = new FlywheelSilhouetteMask.Vertex(0, 0, 0, 0, 0);
		FlywheelSilhouetteMask.Vertex second = new FlywheelSilhouetteMask.Vertex(1, 0, 0, 1, 0);
		FlywheelSilhouetteMask.Vertex third = new FlywheelSilhouetteMask.Vertex(0, 1, 0, 0, 1);
		FlywheelSilhouetteMask.RenderPlan<String> plan = new FlywheelSilhouetteMask.RenderPlan<>(List.of(
			new FlywheelSilhouetteMask.TextureBatch<>("first", List.of(
				new FlywheelSilhouetteMask.Triangle(first, second, third))),
			new FlywheelSilhouetteMask.TextureBatch<>("second", List.of(
				new FlywheelSilhouetteMask.Triangle(third, second, first)))));
		List<String> opened = new ArrayList<>();
		List<FlywheelSilhouetteMask.Vertex> emitted = new ArrayList<>();

		assertEquals(2, FlywheelSilhouetteMask.emit(plan, 0xFFABCDEF, texture -> {
			opened.add(texture);
			return (vertex, ignored) -> emitted.add(vertex);
		}));

		assertEquals(List.of("first", "second"), opened);
		assertEquals(8, emitted.size());
		assertSame(third, emitted.get(3));
		assertSame(first, emitted.get(7));
	}

	@Test
	void encodedCommitCarriesTheExactArgbInsteadOfAStaleConsumerColor() {
		FlywheelSilhouetteMask.Vertex first = new FlywheelSilhouetteMask.Vertex(0, 0, 0, 0, 0);
		FlywheelSilhouetteMask.Vertex second = new FlywheelSilhouetteMask.Vertex(1, 0, 0, 1, 0);
		FlywheelSilhouetteMask.Vertex third = new FlywheelSilhouetteMask.Vertex(0, 1, 0, 0, 1);
		FlywheelSilhouetteMask.RenderPlan<String> plan = new FlywheelSilhouetteMask.RenderPlan<>(List.of(
			new FlywheelSilhouetteMask.TextureBatch<>("texture", List.of(
				new FlywheelSilhouetteMask.Triangle(first, second, third)))));
		List<Integer> colors = new ArrayList<>();

		FlywheelSilhouetteMask.EncodedPlan<String> encoded =
			FlywheelSilhouetteMask.encode(plan, 0x7F123456);
		assertEquals(1, FlywheelSilhouetteMask.commit(encoded, texture ->
			(vertex, color) -> colors.add(color)));

		assertEquals(List.of(0x7F123456, 0x7F123456, 0x7F123456, 0x7F123456), colors);
	}

	@Test
	void throwingPreCommitBuilderLeavesTheSharedCommitSinkUntouched() {
		FlywheelSilhouetteMask.Vertex first = new FlywheelSilhouetteMask.Vertex(0, 0, 0, 0, 0);
		FlywheelSilhouetteMask.Vertex second = new FlywheelSilhouetteMask.Vertex(1, 0, 0, 1, 0);
		FlywheelSilhouetteMask.Vertex third = new FlywheelSilhouetteMask.Vertex(0, 1, 0, 0, 1);
		FlywheelSilhouetteMask.RenderPlan<String> plan = new FlywheelSilhouetteMask.RenderPlan<>(List.of(
			new FlywheelSilhouetteMask.TextureBatch<>("texture", List.of(
				new FlywheelSilhouetteMask.Triangle(first, second, third)))));
		List<FlywheelSilhouetteMask.Vertex> committed = new ArrayList<>();

		assertThrows(IllegalStateException.class, () -> FlywheelSilhouetteMask.encode(
			plan, 0xFF102030, (texture, vertex, color) -> {
				throw new IllegalStateException("test pre-commit failure");
			}));

		assertTrue(committed.isEmpty());
	}

	@Test
	void throwingSecondBatchConsumerIsResolvedBeforeAnySharedVertexIsWritten() {
		FlywheelSilhouetteMask.Vertex first = new FlywheelSilhouetteMask.Vertex(0, 0, 0, 0, 0);
		FlywheelSilhouetteMask.Vertex second = new FlywheelSilhouetteMask.Vertex(1, 0, 0, 1, 0);
		FlywheelSilhouetteMask.Vertex third = new FlywheelSilhouetteMask.Vertex(0, 1, 0, 0, 1);
		FlywheelSilhouetteMask.RenderPlan<String> plan = new FlywheelSilhouetteMask.RenderPlan<>(List.of(
			new FlywheelSilhouetteMask.TextureBatch<>("first", List.of(
				new FlywheelSilhouetteMask.Triangle(first, second, third))),
			new FlywheelSilhouetteMask.TextureBatch<>("second", List.of(
				new FlywheelSilhouetteMask.Triangle(third, second, first)))));
		List<FlywheelSilhouetteMask.Vertex> committed = new ArrayList<>();
		FlywheelSilhouetteMask.EncodedPlan<String> encoded =
			FlywheelSilhouetteMask.encode(plan, 0xFF102030);

		FlywheelSilhouetteMask.CommitFailure failure = assertThrows(
			FlywheelSilhouetteMask.CommitFailure.class, () -> FlywheelSilhouetteMask.commit(
			encoded,
			texture -> {
				if ("second".equals(texture)) {
					throw new IllegalStateException("test buffer lookup failure");
				}
				return (vertex, color) -> committed.add(vertex);
			}));

		assertEquals(0, failure.verticesWritten());
		assertTrue(committed.isEmpty());
	}

	@Test
	void commitFailureBeforeFirstVertexReportsZeroWritten() {
		FlywheelSilhouetteMask.EncodedPlan<String> encoded = encodedSingleTriangle();

		FlywheelSilhouetteMask.CommitFailure failure = assertThrows(
			FlywheelSilhouetteMask.CommitFailure.class,
			() -> FlywheelSilhouetteMask.commit(encoded,
				texture -> (vertex, color) -> {
					throw new IllegalStateException("before first vertex");
				}));

		assertEquals(0, failure.verticesWritten());
	}

	@Test
	void commitFailureAfterFirstVertexReportsPartialWrite() {
		FlywheelSilhouetteMask.EncodedPlan<String> encoded = encodedSingleTriangle();
		List<FlywheelSilhouetteMask.Vertex> committed = new ArrayList<>();

		FlywheelSilhouetteMask.CommitFailure failure = assertThrows(
			FlywheelSilhouetteMask.CommitFailure.class,
			() -> FlywheelSilhouetteMask.commit(encoded, texture -> (vertex, color) -> {
				if (!committed.isEmpty()) {
					throw new IllegalStateException("after first vertex");
				}
				committed.add(vertex);
			}));

		assertEquals(1, failure.verticesWritten());
		assertEquals(1, committed.size());
	}

	@Test
	void degenerateTrianglesAreFilteredAndCannotReportVisibleGeometry() {
		FlywheelSilhouetteMask.Vertex first = new FlywheelSilhouetteMask.Vertex(1, 1, 1, 0, 0);
		FlywheelSilhouetteMask.Vertex second = new FlywheelSilhouetteMask.Vertex(2, 2, 2, 1, 0);
		FlywheelSilhouetteMask.Vertex third = new FlywheelSilhouetteMask.Vertex(3, 3, 3, 0, 1);
		FlywheelSilhouetteMask.RenderPlan<String> plan = new FlywheelSilhouetteMask.RenderPlan<>(List.of(
			new FlywheelSilhouetteMask.TextureBatch<>("texture", List.of(
				new FlywheelSilhouetteMask.Triangle(first, second, third)))));

		FlywheelSilhouetteMask.RenderPlan<String> filtered =
			FlywheelSilhouetteMask.filterVisibleTriangles(plan);
		assertTrue(filtered.isEmpty());
		assertEquals(0, filtered.triangleCount());
		assertEquals(0, FlywheelSilhouetteMask.emit(filtered, 0xFFABCDEF,
			texture -> (vertex, color) -> { throw new AssertionError("must not emit"); }));
	}

	private static FlywheelSilhouetteMask.EncodedPlan<String> encodedSingleTriangle() {
		FlywheelSilhouetteMask.Vertex first = new FlywheelSilhouetteMask.Vertex(0, 0, 0, 0, 0);
		FlywheelSilhouetteMask.Vertex second = new FlywheelSilhouetteMask.Vertex(1, 0, 0, 1, 0);
		FlywheelSilhouetteMask.Vertex third = new FlywheelSilhouetteMask.Vertex(0, 1, 0, 0, 1);
		FlywheelSilhouetteMask.RenderPlan<String> plan = new FlywheelSilhouetteMask.RenderPlan<>(List.of(
			new FlywheelSilhouetteMask.TextureBatch<>("texture", List.of(
				new FlywheelSilhouetteMask.Triangle(first, second, third)))));
		return FlywheelSilhouetteMask.encode(plan, 0xFF102030);
	}
}
