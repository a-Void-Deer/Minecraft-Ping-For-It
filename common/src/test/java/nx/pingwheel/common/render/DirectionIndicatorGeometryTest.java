package nx.pingwheel.common.render;

import net.minecraft.world.phys.Vec2;
import nx.pingwheel.common.math.ScreenPos;
import org.jetbrains.annotations.NotNullByDefault;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CPU geometry checks for off-screen marker bearings and safe-rectangle
 * landings. The behind-camera samples are produced by a real perspective
 * projection, so their negative depth and mirrored screen position exercise
 * the same input contract as the overlay renderer.
 */
@NotNullByDefault
class DirectionIndicatorGeometryTest {

	private static final float GUI_WIDTH = 427f;
	private static final float GUI_HEIGHT = 240f;
	private static final float FOV_DEGREES = 70f;
	private static final Vec2 SCREEN = new Vec2(GUI_WIDTH, GUI_HEIGHT);
	private static final Vec2 DEFAULT_SAFE_TOP_LEFT = new Vec2(5f, 5f);
	private static final Vec2 DEFAULT_SAFE_BOTTOM_RIGHT = new Vec2(422f, 180f);

	@Test
	void behindBelowLandsOnTheBottomEdgeOfTheDefaultAsymmetricSafeZone() {
		var projected = project(0f, -1f, 10f);

		assertTrue(projected.isBehindCamera());
		assertEquals(102.862f, projected.y, 0.001f);
		assertLanding(projected, DEFAULT_SAFE_TOP_LEFT, DEFAULT_SAFE_BOTTOM_RIGHT,
			(float)(Math.PI / 2.0), 213.5f, 180f);
	}

	@Test
	void behindAboveLandsOnTheTopEdgeOfTheDefaultAsymmetricSafeZone() {
		var projected = project(0f, 1f, 10f);

		assertTrue(projected.isBehindCamera());
		assertEquals(137.138f, projected.y, 0.001f);
		assertLanding(projected, DEFAULT_SAFE_TOP_LEFT, DEFAULT_SAFE_BOTTOM_RIGHT,
			(float)(-Math.PI / 2.0), 213.5f, 5f);
	}

	@Test
	void behindHorizontalTargetsMirrorAroundTheProjectionCenterWithAsymmetricSideInsets() {
		var safeTopLeft = new Vec2(40f, 5f);
		var safeBottomRight = new Vec2(422f, 180f);

		var behindRight = project(1f, 0f, 10f);
		assertTrue(behindRight.isBehindCamera());
		assertEquals(196.362f, behindRight.x, 0.001f);
		assertLanding(behindRight, safeTopLeft, safeBottomRight, 0f, 422f, 92.5f);

		var behindLeft = project(-1f, 0f, 10f);
		assertTrue(behindLeft.isBehindCamera());
		assertLanding(behindLeft, safeTopLeft, safeBottomRight, (float)Math.PI, 40f, 92.5f);
	}

	@Test
	void behindRightAboveKeepsItsDiagonalBearingOnTheTopEdge() {
		var projected = project(1f, 1f, 10f);

		assertTrue(projected.isBehindCamera());
		assertLanding(projected, new Vec2(40f, 5f), new Vec2(422f, 180f),
			(float)(-Math.PI / 4.0), 318.5f, 5f);
	}

	@Test
	void forwardOffscreenTargetsKeepTheirEstablishedSafeZoneCenterBearing() {
		var safeTopLeft = new Vec2(5f, 5f);
		var safeBottomRight = new Vec2(422f, 180f);

		assertLanding(new ScreenPos(500f, 120f, 1f), safeTopLeft, safeBottomRight,
			0.0956929f, 422f, 112.513f);
		assertLanding(new ScreenPos(213.5f, 260f, 1f), safeTopLeft, safeBottomRight,
			(float)(Math.PI / 2.0), 213.5f, 180f);
	}

	@Test
	void symmetricInsetsPreserveTheBehindBearing() {
		var projected = project(0f, -1f, 10f);

		assertLanding(projected, new Vec2(5f, 5f), new Vec2(422f, 235f),
			(float)(Math.PI / 2.0), 213.5f, 235f);
	}

	private static void assertLanding(ScreenPos screenPos, Vec2 safeTopLeft, Vec2 safeBottomRight,
		float expectedAngle, float expectedX, float expectedY) {
		var placement = DirectionIndicatorGeometry.place(screenPos, SCREEN, safeTopLeft, safeBottomRight);

		assertEquals(expectedAngle, placement.angle(), 1e-5f);
		assertEquals(expectedX, placement.edgePosition().x, 1e-3f);
		assertEquals(expectedY, placement.edgePosition().y, 1e-3f);
	}

	private static ScreenPos project(float x, float y, float z) {
		var projection = new Matrix4f().perspective((float)Math.toRadians(FOV_DEGREES),
			GUI_WIDTH / GUI_HEIGHT, 0.05f, 512f);
		var clip = projection.transform(new Vector4f(x, y, z, 1f));
		var depth = clip.w;

		if (depth != 0f) {
			clip.div(depth);
		}

		return new ScreenPos(
			GUI_WIDTH * (0.5f + clip.x * 0.5f),
			GUI_HEIGHT * (0.5f - clip.y * 0.5f),
			depth);
	}
}
