package nx.pingwheel.common.client.outline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywheelTransformMathTest {
	private static final FlywheelTransformMath.Point UNIT_X =
		new FlywheelTransformMath.Point(1, 0, 0);
	private static final FlywheelTransformMath.Quaternion IDENTITY =
		new FlywheelTransformMath.Quaternion(0, 0, 0, 1);

	@Test
	void transformedUsesTheAffinePose() {
		FlywheelTransformMath.Point result = FlywheelTransformMath.transformed(
			UNIT_X,
			new FlywheelTransformMath.Matrix4(
				1, 0, 0, 0,
				0, 1, 0, 0,
				0, 0, 1, 0,
				2, 3, 4, 1));

		assertPoint(result, 3, 3, 4);
	}

	@Test
	void orientedRotatesAroundTheConfiguredPivotThenAddsPosition() {
		float half = (float) Math.sqrt(0.5);
		FlywheelTransformMath.Point result = FlywheelTransformMath.oriented(
			UNIT_X,
			new FlywheelTransformMath.Point(0, 0, 0),
			new FlywheelTransformMath.Point(0, 0, 0),
			new FlywheelTransformMath.Quaternion(0, 0, half, half));

		assertPoint(result, 0, 1, 0);
	}

	@Test
	void rotatingUsesOffsetPlusRenderSecondsAndCenteredVertices() {
		FlywheelTransformMath.Point result = FlywheelTransformMath.rotating(
			new FlywheelTransformMath.Point(1, 0.5F, 0.5F),
			new FlywheelTransformMath.Point(0, 0, 0),
			IDENTITY,
			new FlywheelTransformMath.Point(0, 0, 1),
				0, 90, 0);

		assertPoint(result, 0.5F, 1, 0.5F);
	}

	@Test
	void scrollingAndScrollingTransformedKeepTheirExactPositionRoutes() {
		FlywheelTransformMath.Point scrolling = FlywheelTransformMath.scrolling(
			new FlywheelTransformMath.Point(1, 0.5F, 0.5F),
			new FlywheelTransformMath.Point(0, 0, 0), IDENTITY);
		FlywheelTransformMath.Point transformed = FlywheelTransformMath.scrollingTransformed(
			UNIT_X,
			new FlywheelTransformMath.Matrix4(
				1, 0, 0, 0,
				0, 1, 0, 0,
				0, 0, 1, 0,
				0, 0, 0, 1));

		assertPoint(scrolling, 1, 0.5F, 0.5F);
		assertPoint(transformed, 1, 0, 0);
		assertEquals(0.125F, FlywheelTransformMath.scrollOffset(0.5F, 0, 0.5F, 2.5F), 0.0001F);
		assertEquals(0.675F,
			FlywheelTransformMath.scrollingUv(0.2F, 0.1F, 0.5F, 2.5F, 0.5F, 2.5F),
			0.0001F);
	}

	@Test
	void zeroTransformCollapsesTheTriangleAndItIsNotVisible() {
		FlywheelTransformMath.Matrix4 zeroTransform = new FlywheelTransformMath.Matrix4(
			0, 0, 0, 0,
			0, 0, 0, 0,
			0, 0, 0, 0,
			0, 0, 0, 1);
		FlywheelSilhouetteMask.Vertex first = vertexAfter(zeroTransform,
			new FlywheelTransformMath.Point(0, 0, 0));
		FlywheelSilhouetteMask.Vertex second = vertexAfter(zeroTransform,
			new FlywheelTransformMath.Point(1, 0, 0));
		FlywheelSilhouetteMask.Vertex third = vertexAfter(zeroTransform,
			new FlywheelTransformMath.Point(0, 1, 0));

		assertFalse(new FlywheelSilhouetteMask.Triangle(first, second, third).hasVisibleArea());
	}

	@Test
	void nonFiniteTransformIsRejectedBeforeMaskPlanning() {
		FlywheelTransformMath.Point result = FlywheelTransformMath.transformed(
			UNIT_X,
			new FlywheelTransformMath.Matrix4(
				Float.NaN, 0, 0, 0,
				0, 1, 0, 0,
				0, 0, 1, 0,
				0, 0, 0, 1));

		assertFalse(FlywheelTransformMath.isFinite(result));
	}

	private static FlywheelSilhouetteMask.Vertex vertexAfter(
		FlywheelTransformMath.Matrix4 transform,
		FlywheelTransformMath.Point point
	) {
		FlywheelTransformMath.Point result = FlywheelTransformMath.transformed(point, transform);
		assertTrue(FlywheelTransformMath.isFinite(result));
		return new FlywheelSilhouetteMask.Vertex(result.x(), result.y(), result.z(), 0, 0);
	}

	private static void assertPoint(FlywheelTransformMath.Point point, float x, float y, float z) {
		assertEquals(x, point.x(), 0.0001F);
		assertEquals(y, point.y(), 0.0001F);
		assertEquals(z, point.z(), 0.0001F);
	}
}
