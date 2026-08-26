package nx.pingwheel.common.client.outline;

import java.util.Objects;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Composes a provider render pose with the active camera-relative world
 * transform and a local block position. The caller remains responsible for
 * submitting native shape edges to the line buffer.
 */
public final class ExternalBlockOutlineTransform {

	private ExternalBlockOutlineTransform() {
	}

	/**
	 * Applies {@code camera^-1 * renderPose * localBlockTranslation} to the
	 * supplied pose stack. The render pose is copied by the provider before it
	 * reaches this method, so no provider state is mutated.
	 */
	public static void apply(
		PoseStack poseStack, Matrix4f renderPose, BlockPos localBlockPos, Vec3 cameraPosition
	) {
		Objects.requireNonNull(poseStack, "poseStack");
		Objects.requireNonNull(renderPose, "renderPose");
		Objects.requireNonNull(localBlockPos, "localBlockPos");
		Objects.requireNonNull(cameraPosition, "cameraPosition");

		poseStack.translate(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);
		poseStack.mulPose(renderPose);
		poseStack.translate(localBlockPos.getX(), localBlockPos.getY(), localBlockPos.getZ());
	}
}
