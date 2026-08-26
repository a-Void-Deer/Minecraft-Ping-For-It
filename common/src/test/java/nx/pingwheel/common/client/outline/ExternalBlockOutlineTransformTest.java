package nx.pingwheel.common.client.outline;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalBlockOutlineTransformTest {

	@Test
	void appliesNonIdentityProviderPoseToLocalBlockInCameraRelativeSpace() {
		PoseStack stack = new PoseStack();
		Matrix4f pose = new Matrix4f().translation(10.0F, 20.0F, 30.0F);

		ExternalBlockOutlineTransform.apply(
			stack, pose, new BlockPos(2, 3, 4), new Vec3(1.0, 2.0, 3.0));

		Vector3f transformed = stack.last().pose().transformPosition(new Vector3f());

		assertEquals(11.0F, transformed.x, 0.0001F);
		assertEquals(21.0F, transformed.y, 0.0001F);
		assertEquals(31.0F, transformed.z, 0.0001F);
	}

	@Test
	void composesRotationAndScaleInCameraRelativeOrderAndUpdatesNormals() {
		PoseStack stack = new PoseStack();
		BlockPos localBlockPos = new BlockPos(2, 3, 4);
		Vec3 camera = new Vec3(1.0, 2.0, 3.0);
		Matrix4f pose = new Matrix4f()
			.translation(10.0F, 20.0F, 30.0F)
			.rotateZ((float) Math.toRadians(90.0))
			.scale(2.0F, 3.0F, 4.0F);

		ExternalBlockOutlineTransform.apply(stack, pose, localBlockPos, camera);

		Matrix4f expectedPose = new Matrix4f()
			.translation((float) -camera.x, (float) -camera.y, (float) -camera.z)
			.mul(pose)
			.translate(localBlockPos.getX(), localBlockPos.getY(), localBlockPos.getZ());
		Vector3f localPoint = new Vector3f(0.25F, 0.5F, 0.75F);
		Vector3f expectedPoint = expectedPose.transformPosition(new Vector3f(localPoint));
		Vector3f actualPoint = stack.last().pose().transformPosition(new Vector3f(localPoint));

		assertEquals(expectedPoint.x, actualPoint.x, 0.0001F);
		assertEquals(expectedPoint.y, actualPoint.y, 0.0001F);
		assertEquals(expectedPoint.z, actualPoint.z, 0.0001F);

		// Translation and local block placement do not affect normals. PoseStack
		// uses the inverse-transpose of the non-uniform provider scale followed by
		// its rotation, which is the normal matrix required by the line route.
		Matrix3f expectedNormalMatrix = new Matrix3f(pose).invert().transpose();
		Vector3f expectedNormal = expectedNormalMatrix.transform(new Vector3f(1.0F, 0.0F, 0.0F));
		Vector3f actualNormal = stack.last().normal().transform(new Vector3f(1.0F, 0.0F, 0.0F));

		assertEquals(expectedNormal.x, actualNormal.x, 0.0001F);
		assertEquals(expectedNormal.y, actualNormal.y, 0.0001F);
		assertEquals(expectedNormal.z, actualNormal.z, 0.0001F);
	}

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

	private static Vec3 transform(Matrix4d matrix, BlockPos localBlockPos, Vec3 vertex) {
		double x = localBlockPos.getX() + vertex.x;
		double y = localBlockPos.getY() + vertex.y;
		double z = localBlockPos.getZ() + vertex.z;
		return new Vec3(
			matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30(),
			matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31(),
			matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32());
	}
}
