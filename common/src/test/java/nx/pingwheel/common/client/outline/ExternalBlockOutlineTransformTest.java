package nx.pingwheel.common.client.outline;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalBlockOutlineTransformTest {

	@Test
	void keepsSableOutlineAtHugePlotCoordinatesWithoutEarlyFloatTranslation() {
		BlockPos localBlockPos = new BlockPos(7, -2, 11);
		Vec3[] cameras = {
			new Vec3(20_481_029.25, 10.5, 20_481_034.25),
			new Vec3(29_999_997.5, 10.25, 30_000_004.75)
		};

		for (int index = 0; index < cameras.length; index++) {
			double plotCoordinate = index == 0 ? 20_481_032.0 : 30_000_000.0;
			Vec3 camera = cameras[index];
			Matrix4d renderPose = new Matrix4d()
				.translation(plotCoordinate + 0.375, 12.25, plotCoordinate - 0.625)
				.translate(3.25, -1.5, 2.75)
				.rotateY(Math.toRadians(90.0))
				.scale(1.25, 0.75, 2.0)
				.translate(-3.25, 1.5, -2.75);
			Matrix4f orientationScale =
				ExternalBlockOutlineTransform.orientationScale(renderPose);
			Vec3 worldOrigin = ExternalBlockOutlineTransform.worldBlockOrigin(
				renderPose, localBlockPos);

			Vec3[] vertices = {
				new Vec3(0.0, 0.0, 0.0),
				new Vec3(1.0, 1.0, 1.0),
				new Vec3(0.5, 0.5, 0.5)
			};
			for (Vec3 vertex : vertices) {
				Vec3 expected = transform(renderPose, localBlockPos, vertex).subtract(camera);
				Vec3 actual = ExternalBlockOutlineTransform.transformVertex(
					worldOrigin, orientationScale, camera, vertex);

				assertEquals(expected.x, actual.x, 0.000001D);
				assertEquals(expected.y, actual.y, 0.000001D);
				assertEquals(expected.z, actual.z, 0.000001D);
			}

			PoseStack stack = new PoseStack();
			ExternalBlockOutlineTransform.apply(
				stack, worldOrigin, orientationScale, camera);
			Vector3f renderedCenter = stack.last().pose().transformPosition(new Vector3f(
				0.5F, 0.5F, 0.5F));
			Vec3 expectedCenter = transform(renderPose, localBlockPos, vertices[2]).subtract(camera);
			assertEquals(expectedCenter.x, renderedCenter.x, 0.00001D);
			assertEquals(expectedCenter.y, renderedCenter.y, 0.00001D);
			assertEquals(expectedCenter.z, renderedCenter.z, 0.00001D);

			Matrix4f earlyFloatPose = new Matrix4f(renderPose);
			Vec3 oldCorner = new Vec3(earlyFloatPose.transformPosition(new Vector3f(
				(float) localBlockPos.getX(),
				(float) localBlockPos.getY(),
				(float) localBlockPos.getZ())));
			Vec3 expectedCorner = transform(renderPose, localBlockPos, vertices[0]);
			double oldError = oldCorner.distanceTo(expectedCorner);
			assertTrue(oldError > 0.25D,
				"the regression must distinguish the old absolute-float path");
		}
	}

	@Test
	void appliesLocalModelOffsetBeforeRotationAndScaleAtHugeCoordinates() {
		BlockPos localBlockPos = new BlockPos(1_234_567, -234_567, 3_456_789);
		Vec3 modelOffset = new Vec3(0.375, -0.125, 0.625);
		Matrix4d renderPose = new Matrix4d()
			.translation(40_000_000.375, 96.25, -40_000_000.625)
			.translate(12.5, -3.25, 7.75)
			.rotateY(Math.toRadians(37.0))
			.rotateZ(Math.toRadians(-19.0))
			.scale(1.25, 0.75, 1.5)
			.translate(-12.5, 3.25, -7.75);
		Vec3 worldOrigin = ExternalBlockOutlineTransform.worldBlockOrigin(
			renderPose, localBlockPos, modelOffset);
		Vec3 camera = worldOrigin.add(0.25, -0.5, 0.75);
		Matrix4f orientationScale = ExternalBlockOutlineTransform.orientationScale(renderPose);
		PoseStack stack = new PoseStack();

		ExternalBlockOutlineTransform.apply(stack, worldOrigin, orientationScale, camera);

		Vec3 localVertex = new Vec3(0.25, 0.5, 0.75);
		Vector3f actual = stack.last().pose().transformPosition(new Vector3f(
			(float) localVertex.x, (float) localVertex.y, (float) localVertex.z));
		Vec3 expected = transform(
			renderPose,
			localBlockPos.getX() + modelOffset.x + localVertex.x,
			localBlockPos.getY() + modelOffset.y + localVertex.y,
			localBlockPos.getZ() + modelOffset.z + localVertex.z).subtract(camera);

		assertEquals(expected.x, actual.x, 0.00001D);
		assertEquals(expected.y, actual.y, 0.00001D);
		assertEquals(expected.z, actual.z, 0.00001D);
	}

	private static Vec3 transform(Matrix4d matrix, BlockPos localBlockPos, Vec3 vertex) {
		return transform(
			matrix,
			localBlockPos.getX() + vertex.x,
			localBlockPos.getY() + vertex.y,
			localBlockPos.getZ() + vertex.z);
	}

	private static Vec3 transform(Matrix4d matrix, double x, double y, double z) {
		return new Vec3(
			matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30(),
			matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31(),
			matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32());
	}
}
