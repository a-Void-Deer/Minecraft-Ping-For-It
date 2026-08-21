package nx.pingwheel.common.client.outline;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AabbOutlineMaskTest {

	@Test
	void producesExactlySixQuadsWithTwentyFourVertices() {
		AabbOutlineMask mask = AabbOutlineMask.cameraRelative(
			new AABB(0, 0, 0, 2, 3, 4), Vec3.ZERO);

		List<AabbOutlineMask.Quad> quads = mask.quads();
		assertEquals(6, quads.size());
		assertEquals(24, quads.stream().mapToLong(AabbOutlineMaskTest::vertexCount).sum());
	}

	@Test
	void everyVertexIsABoxCorner() {
		AabbOutlineMask mask = AabbOutlineMask.cameraRelative(
			new AABB(1, 2, 3, 5, 7, 11), Vec3.ZERO);

		double[] xs = { 1, 5 };
		double[] ys = { 2, 7 };
		double[] zs = { 3, 11 };
		Set<List<Double>> corners = new HashSet<>();
		for (double x : xs) {
			for (double y : ys) {
				for (double z : zs) {
					corners.add(List.of(x, y, z));
				}
			}
		}

		for (AabbOutlineMask.Quad quad : mask.quads()) {
			for (List<Double> corner : quadCorners(quad)) {
				assertTrue(corners.contains(corner), "unexpected vertex: " + corner);
			}
		}
	}

	@Test
	void verticesAreCameraRelative() {
		AabbOutlineMask mask = AabbOutlineMask.cameraRelative(
			new AABB(10, 10, 10, 12, 12, 12), new Vec3(10, 10, 10));

		// Every vertex must be shifted by the negative camera position.
		for (AabbOutlineMask.Quad quad : mask.quads()) {
			for (List<Double> corner : quadCorners(quad)) {
				double x = corner.get(0);
				double y = corner.get(1);
				double z = corner.get(2);
				assertTrue(x >= 0.0 && x <= 2.0, "x out of range: " + x);
				assertTrue(y >= 0.0 && y <= 2.0, "y out of range: " + y);
				assertTrue(z >= 0.0 && z <= 2.0, "z out of range: " + z);
			}
		}
	}

	@Test
	void unitBoxMaskIsCenteredOnTheOriginForAUnitCamera() {
		AabbOutlineMask mask = AabbOutlineMask.cameraRelative(
			new AABB(0, 0, 0, 1, 1, 1), new Vec3(0.5, 0.5, 0.5));

		// All vertices must lie within the camera-relative half-open cube.
		for (AabbOutlineMask.Quad quad : mask.quads()) {
			for (List<Double> corner : quadCorners(quad)) {
				for (Double coordinate : corner) {
					assertTrue(coordinate >= -0.5 && coordinate <= 0.5, "unexpected coordinate: " + coordinate);
				}
			}
		}
	}

	@Test
	void everyFaceHasAnOutwardWindingAndNormal() {
		AabbOutlineMask mask = AabbOutlineMask.cameraRelative(
			new AABB(0, 0, 0, 2, 3, 4), Vec3.ZERO);
		double[][] outwardNormals = {
			{1, 0, 0},
			{-1, 0, 0},
			{0, 1, 0},
			{0, -1, 0},
			{0, 0, 1},
			{0, 0, -1}
		};

		for (int index = 0; index < mask.quads().size(); index++) {
			AabbOutlineMask.Quad quad = mask.quads().get(index);
			List<List<Double>> corners = quadCorners(quad);
			List<Double> first = corners.get(0);
			List<Double> second = corners.get(1);
			List<Double> third = corners.get(2);

			double edge1X = second.get(0) - first.get(0);
			double edge1Y = second.get(1) - first.get(1);
			double edge1Z = second.get(2) - first.get(2);
			double edge2X = third.get(0) - first.get(0);
			double edge2Y = third.get(1) - first.get(1);
			double edge2Z = third.get(2) - first.get(2);
			double normalX = edge1Y * edge2Z - edge1Z * edge2Y;
			double normalY = edge1Z * edge2X - edge1X * edge2Z;
			double normalZ = edge1X * edge2Y - edge1Y * edge2X;
			double[] outward = outwardNormals[index];
			double dot = normalX * outward[0] + normalY * outward[1] + normalZ * outward[2];

			assertTrue(dot > 0.0, "face " + index + " is not outward-facing");
		}
	}

	@Test
	void nonFiniteBoxIsRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> AabbOutlineMask.cameraRelative(
				new AABB(Double.NaN, 0, 0, 1, 1, 1), Vec3.ZERO));
		assertThrows(IllegalArgumentException.class,
			() -> AabbOutlineMask.cameraRelative(
				new AABB(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1), Vec3.ZERO));
	}

	@Test
	void emptyOrDegenerateBoxIsRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> AabbOutlineMask.cameraRelative(
				new AABB(1, 1, 1, 1, 2, 2), Vec3.ZERO));
		assertThrows(IllegalArgumentException.class,
			() -> AabbOutlineMask.cameraRelative(
				new AABB(0, 0, 0, 1, 1, 0), Vec3.ZERO));
	}

	@Test
	void nullArgumentsAreRejected() {
		assertThrows(NullPointerException.class,
			() -> AabbOutlineMask.cameraRelative(null, Vec3.ZERO));
		assertThrows(NullPointerException.class,
			() -> AabbOutlineMask.cameraRelative(new AABB(0, 0, 0, 1, 1, 1), null));
	}

	private static List<List<Double>> quadCorners(AabbOutlineMask.Quad quad) {
		return List.of(
			List.of((double) quad.x0(), (double) quad.y0(), (double) quad.z0()),
			List.of((double) quad.x1(), (double) quad.y1(), (double) quad.z1()),
			List.of((double) quad.x2(), (double) quad.y2(), (double) quad.z2()),
			List.of((double) quad.x3(), (double) quad.y3(), (double) quad.z3()));
	}

	private static long vertexCount(AabbOutlineMask.Quad quad) {
		return 4;
	}
}
