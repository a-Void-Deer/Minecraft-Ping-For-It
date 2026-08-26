package nx.pingwheel.common.client.outline;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
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
}
