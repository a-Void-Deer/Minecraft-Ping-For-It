package nx.pingwheel.common.client.outline;

import java.util.Objects;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
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
	 * Applies a Sable block transform after its absolute world translation has
	 * already been evaluated in double precision.  Only the small camera
	 * relative origin is translated on the pose stack; the supplied matrix must
	 * contain orientation and scale, but no world-space translation.
	 */
	public static void apply(
		PoseStack poseStack,
		Vec3 worldBlockOrigin,
		Matrix4f orientationScale,
		Vec3 cameraPosition
	) {
		Objects.requireNonNull(poseStack, "poseStack");
		Objects.requireNonNull(worldBlockOrigin, "worldBlockOrigin");
		Objects.requireNonNull(orientationScale, "orientationScale");
		Objects.requireNonNull(cameraPosition, "cameraPosition");

		poseStack.translate(
			worldBlockOrigin.x - cameraPosition.x,
			worldBlockOrigin.y - cameraPosition.y,
			worldBlockOrigin.z - cameraPosition.z);
		poseStack.mulPose(orientationScale);
	}

	/**
	 * Transforms a local integer block corner without first narrowing the
	 * absolute render pose. This is the Sable path's authoritative origin.
	 */
	public static Vec3 worldBlockOrigin(Matrix4d renderPose, BlockPos localBlockPos) {
		Objects.requireNonNull(renderPose, "renderPose");
		Objects.requireNonNull(localBlockPos, "localBlockPos");

		return worldBlockOrigin(renderPose, localBlockPos, Vec3.ZERO);
	}

	/**
	 * Transforms the local block corner plus a live vanilla model offset in
	 * double precision. The offset is part of the local point before the
	 * provider rotation/scale is applied, matching the position at which the
	 * baked model appears in the sub-level.
	 */
	public static Vec3 worldBlockOrigin(
		Matrix4d renderPose, BlockPos localBlockPos, Vec3 modelOffset
	) {
		Objects.requireNonNull(renderPose, "renderPose");
		Objects.requireNonNull(localBlockPos, "localBlockPos");
		Objects.requireNonNull(modelOffset, "modelOffset");

		return transformPosition(
			renderPose,
			localBlockPos.getX() + modelOffset.x,
			localBlockPos.getY() + modelOffset.y,
			localBlockPos.getZ() + modelOffset.z);
	}

	/** Returns a camera-relative transformed shape vertex using double arithmetic. */
	public static Vec3 transformVertex(
		Vec3 worldBlockOrigin,
		Matrix4f orientationScale,
		Vec3 cameraPosition,
		Vec3 localVertex
	) {
		Objects.requireNonNull(worldBlockOrigin, "worldBlockOrigin");
		Objects.requireNonNull(orientationScale, "orientationScale");
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		Objects.requireNonNull(localVertex, "localVertex");

		Vec3 transformedVertex = worldBlockOrigin.add(
			transformLinear(
				orientationScale,
				localVertex.x,
				localVertex.y,
				localVertex.z));
		return transformedVertex.subtract(cameraPosition);
	}

	/**
	 * Narrows only the small linear part of a double-precision render pose.
	 * Absolute translation and rotation-point coordinates never enter a
	 * float-backed matrix.
	 */
	public static Matrix4f orientationScale(Matrix4d renderPose) {
		Objects.requireNonNull(renderPose, "renderPose");

		return new Matrix4f()
			.m00((float) renderPose.m00())
			.m01((float) renderPose.m01())
			.m02((float) renderPose.m02())
			.m10((float) renderPose.m10())
			.m11((float) renderPose.m11())
			.m12((float) renderPose.m12())
			.m20((float) renderPose.m20())
			.m21((float) renderPose.m21())
			.m22((float) renderPose.m22())
			.m30(0.0F)
			.m31(0.0F)
			.m32(0.0F)
			.m33(1.0F);
	}

	private static Vec3 transformPosition(
		Matrix4d matrix, double x, double y, double z
	) {
		return new Vec3(
			matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30(),
			matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31(),
			matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32());
	}

	private static Vec3 transformLinear(
		Matrix4f matrix, double x, double y, double z
	) {
		return new Vec3(
			matrix.m00() * x + matrix.m10() * y + matrix.m20() * z,
			matrix.m01() * x + matrix.m11() * y + matrix.m21() * z,
			matrix.m02() * x + matrix.m12() * y + matrix.m22() * z);
	}
}
