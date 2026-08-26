package nx.pingwheel.common.client.outline;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
