package nx.pingwheel.common.client.outline;

import java.util.Objects;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * Frozen local-to-world transform for provider-scoped block outlines and
 * embedded geometry such as Flywheel instances.
 *
 * <p>The world-space translation is evaluated in double precision and is
 * reduced by the camera before it is placed in a {@link PoseStack}. Only the
 * linear part of the transform is converted to float matrices, which keeps
 * large environment coordinates out of Minecraft's float pose matrix.</p>
 */
public final class EntityBlockGeometryTransform {
	private final Matrix4d localToWorld;
	private final org.joml.Matrix4f linearMatrix;
	private final Matrix3f normalMatrix;

	public EntityBlockGeometryTransform(Matrix4dc localToWorld) {
		this.localToWorld = new Matrix4d(Objects.requireNonNull(localToWorld, "localToWorld"));
		this.linearMatrix = createLinearMatrix(this.localToWorld);
		this.normalMatrix = createNormalMatrix(this.localToWorld);
	}

	/** Checks the projected integer corner; even a finite matrix can overflow at this point. */
	public static boolean hasFiniteBlockOrigin(Matrix4dc localToWorld, BlockPos blockPos) {
		Vector3d origin = new Vector3d(blockPos.getX(), blockPos.getY(), blockPos.getZ());
		transformPosition(localToWorld, origin);
		return origin.isFinite();
	}

	/** Creates a fresh camera-relative pose for a block at the plot position. */
	public PoseStack createPoseStack(BlockPos plotBlockPos, Vec3 cameraPosition) {
		return createPoseStack(plotBlockPos, cameraPosition, Vec3.ZERO);
	}

	/**
	 * Creates a fresh camera-relative pose for a block at the plot position,
	 * applying an optional model offset in plot-local coordinates.
	 */
	public PoseStack createPoseStack(
		BlockPos plotBlockPos,
		Vec3 cameraPosition,
		@Nullable Vec3 localModelOffset
	) {
		Objects.requireNonNull(plotBlockPos, "plotBlockPos");
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		Vec3 effectiveOffset = localModelOffset == null ? Vec3.ZERO : localModelOffset;

		Vector3d localOrigin = new Vector3d(
			plotBlockPos.getX() + effectiveOffset.x,
			plotBlockPos.getY() + effectiveOffset.y,
			plotBlockPos.getZ() + effectiveOffset.z);
		transformPosition(localToWorld, localOrigin);
		localOrigin.sub(cameraPosition.x, cameraPosition.y, cameraPosition.z);

		PoseStack poseStack = new PoseStack();
		// This is camera-relative already. PoseStack's double overload only
		// receives the small post-subtraction values, never absolute plot data.
		poseStack.translate(localOrigin.x, localOrigin.y, localOrigin.z);
		poseStack.last().pose().mul(linearMatrix);
		poseStack.last().normal().mul(normalMatrix);
		return poseStack;
	}

	/**
	 * Converts a Flywheel instance vertex relative to its environment origin to
	 * the camera-relative world position used by the vanilla outline buffer.
	 * The local vertex and integer origin are combined, transformed, and
	 * camera-subtracted in double precision; only the final render components
	 * are narrowed to float.
	 */
	public Vector3f cameraRelativeEnvironmentVertex(
		Vector3dc localVertex,
		Vec3i environmentOrigin,
		Vec3 cameraPosition
	) {
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		Objects.requireNonNull(localVertex, "localVertex");
		Objects.requireNonNull(environmentOrigin, "environmentOrigin");

		Vector3d worldPosition = new Vector3d(
			environmentOrigin.getX() + localVertex.x(),
			environmentOrigin.getY() + localVertex.y(),
			environmentOrigin.getZ() + localVertex.z());
		transformPosition(localToWorld, worldPosition);
		worldPosition.sub(cameraPosition.x, cameraPosition.y, cameraPosition.z);
		return new Vector3f(
			(float) worldPosition.x,
			(float) worldPosition.y,
			(float) worldPosition.z);
	}

	private static void transformPosition(Matrix4dc localToWorld, Vector3d position) {
		double x = position.x;
		double y = position.y;
		double z = position.z;
		double transformedX = localToWorld.m00() * x
			+ localToWorld.m10() * y
			+ localToWorld.m20() * z
			+ localToWorld.m30();
		double transformedY = localToWorld.m01() * x
			+ localToWorld.m11() * y
			+ localToWorld.m21() * z
			+ localToWorld.m31();
		double transformedZ = localToWorld.m02() * x
			+ localToWorld.m12() * y
			+ localToWorld.m22() * z
			+ localToWorld.m32();
		double w = localToWorld.m03() * x
			+ localToWorld.m13() * y
			+ localToWorld.m23() * z
			+ localToWorld.m33();

		if (w != 0.0D && w != 1.0D) {
			transformedX /= w;
			transformedY /= w;
			transformedZ /= w;
		}

		position.set(transformedX, transformedY, transformedZ);
	}

	private static org.joml.Matrix4f createLinearMatrix(Matrix4dc matrix) {
		return new org.joml.Matrix4f()
			.m00((float) matrix.m00())
			.m01((float) matrix.m01())
			.m02((float) matrix.m02())
			.m10((float) matrix.m10())
			.m11((float) matrix.m11())
			.m12((float) matrix.m12())
			.m20((float) matrix.m20())
			.m21((float) matrix.m21())
			.m22((float) matrix.m22());
	}

	private static Matrix3f createNormalMatrix(Matrix4dc matrix) {
		double a = matrix.m00();
		double b = matrix.m10();
		double c = matrix.m20();
		double d = matrix.m01();
		double e = matrix.m11();
		double f = matrix.m21();
		double g = matrix.m02();
		double h = matrix.m12();
		double i = matrix.m22();
		double determinant = a * (e * i - f * h)
			- b * (d * i - f * g)
			+ c * (d * h - e * g);

		if (determinant == 0.0D || !Double.isFinite(determinant)) {
			return new Matrix3f();
		}

		double inverse00 = (e * i - f * h) / determinant;
		double inverse01 = (c * h - b * i) / determinant;
		double inverse02 = (b * f - c * e) / determinant;
		double inverse10 = (f * g - d * i) / determinant;
		double inverse11 = (a * i - c * g) / determinant;
		double inverse12 = (c * d - a * f) / determinant;
		double inverse20 = (d * h - e * g) / determinant;
		double inverse21 = (b * g - a * h) / determinant;
		double inverse22 = (a * e - b * d) / determinant;

		// Matrix3f uses column-major field names. These values are the
		// transpose of the double-precision inverse above.
		return new Matrix3f()
			.m00((float) inverse00)
			.m10((float) inverse10)
			.m20((float) inverse20)
			.m01((float) inverse01)
			.m11((float) inverse11)
			.m21((float) inverse21)
			.m02((float) inverse02)
			.m12((float) inverse12)
			.m22((float) inverse22);
	}
}
