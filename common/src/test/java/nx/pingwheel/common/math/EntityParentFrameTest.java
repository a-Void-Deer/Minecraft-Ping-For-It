package nx.pingwheel.common.math;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNullByDefault;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class EntityParentFrameTest {
	@Test void remotePlotPlacementKeepsFractionalPrecisionAndCopiesBothInputs() {
		Quaterniond orientation = new Quaterniond().rotationY(Math.PI / 2);
		Matrix4d matrix = new Matrix4d().translation(12.25, 64.125, -9.5).rotate(orientation)
			.scale(2, 3, .5).translate(-30_000_000, 0, -30_000_000);
		EntityParentFrame frame = EntityParentFrame.copyOf(matrix, orientation);
		matrix.identity(); orientation.identity();
		Vec3 nativePoint = new Vec3(30_000_000.125, .25, 30_000_000.5);
		Vec3 worldPoint = frame.toWorld(nativePoint);
		assertPoint(new Vec3(12.5, 64.875, -9.75), worldPoint, 1E-8);
		assertPoint(nativePoint, frame.toNative(worldPoint), 1E-8);
		frame.rotationCopy().identity();
		assertEquals(Math.PI / 2, frame.rotationCopy().angle(), 1E-12);
	}

	@Test void parentScaleMovesAnchorButDoesNotScaleContraptionLocalModel() {
		EntityParentFrame frame = EntityParentFrame.copyOf(new Matrix4d().translation(10, 20, 30)
			.rotateZ(Math.PI / 2).scale(2, 3, 4), new Quaterniond().rotationZ(Math.PI / 2));
		Matrix4f local = new Matrix4f().translation(1, 0, 0).scale(2);
		Matrix4d placed = frame.modelToWorld(new Vec3(1, 2, 3), local);
		Vector3d origin = placed.transformPosition(new Vector3d());
		Vector3d tip = placed.transformPosition(new Vector3d(1, 0, 0));
		assertPoint(new Vec3(4, 23, 42), new Vec3(origin.x, origin.y, origin.z), 1E-12);
		assertPoint(new Vec3(4, 25, 42), new Vec3(tip.x, tip.y, tip.z), 1E-12);
		assertEquals(2, origin.distance(tip), 1E-12, "parent scale must not multiply the local rotor");
	}

	@Test void invalidFramesCannotBecomeWorldCoordinates() {
		assertThrows(IllegalArgumentException.class, () -> EntityParentFrame.copyOf(new Matrix4d().scale(0), new Quaterniond()));
		assertThrows(IllegalArgumentException.class, () -> EntityParentFrame.copyOf(new Matrix4d().m03(1), new Quaterniond()));
		assertThrows(IllegalArgumentException.class, () -> EntityParentFrame.copyOf(new Matrix4d().m30(Double.NaN), new Quaterniond()));
		assertThrows(IllegalArgumentException.class, () -> EntityParentFrame.copyOf(new Matrix4d(), new Quaterniond(0, 0, 0, 0)));
		assertThrows(IllegalArgumentException.class, () -> EntityParentFrame.copyOf(new Matrix4d(), new Quaterniond(Double.NaN, 0, 0, 1)));
	}
	private static void assertPoint(Vec3 expected, Vec3 actual, double tolerance) {
		assertEquals(expected.x, actual.x, tolerance); assertEquals(expected.y, actual.y, tolerance);
		assertEquals(expected.z, actual.z, tolerance);
	}
}
