package nx.pingwheel.common.client.outline;

import java.util.Objects;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * Precision-safe local-to-world transform for geometry owned by an external
 * entity/block environment.
 *
 * <p>The world-space translation is evaluated in double precision and is
 * reduced by the camera before it is placed in a {@link PoseStack}. Only the
 * linear part of the transform is converted to float matrices, which keeps
 * large environment coordinates out of Minecraft's float pose matrix.</p>
 *
 * <p>This class intentionally has no dependency on an external geometry
 * provider or rendering backend. It is an inert compatibility seam for
 * callers that already have a local-to-world matrix.</p>
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

	/**
	 * Returns a copy of the captured transform. Mutating the returned matrix
	 * cannot change this transform.
	 */
	public Matrix4d localToWorld() {
		return new Matrix4d(localToWorld);
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
		Vec3 localModelOffset
	) {
		Objects.requireNonNull(plotBlockPos, "plotBlockPos");
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		Vec3 effectiveOffset = localModelOffset == null ? Vec3.ZERO : localModelOffset;

		Vector3d localOrigin = new Vector3d(
			plotBlockPos.getX() + effectiveOffset.x,
			plotBlockPos.getY() + effectiveOffset.y,
			plotBlockPos.getZ() + effectiveOffset.z);
		transformPosition(localOrigin);
		localOrigin.sub(cameraPosition.x, cameraPosition.y, cameraPosition.z);

		PoseStack poseStack = new PoseStack();
		// This is camera-relative already. PoseStack's double overload only
		// receives the small post-subtraction values, never absolute plot data.
		poseStack.translate(localOrigin.x, localOrigin.y, localOrigin.z);
		poseStack.last().pose().mul(linearMatrix);
		poseStack.last().normal().mul(normalMatrix);
		return poseStack;
	}

	/** Alias emphasizing that the returned pose is camera-relative. */
	public PoseStack createCameraRelativePoseStack(BlockPos plotBlockPos, Vec3 cameraPosition) {
		return createPoseStack(plotBlockPos, cameraPosition);
	}

	/** Alias for callers that use the explicit local-offset form. */
	public PoseStack createCameraRelativePoseStack(
		BlockPos plotBlockPos,
		Vec3 cameraPosition,
		Vec3 localModelOffset
	) {
		return createPoseStack(plotBlockPos, cameraPosition, localModelOffset);
	}

	/**
	 * Converts a local vertex and its integer environment origin to a
	 * camera-relative vertex. All arithmetic before the final render vertex is
	 * performed in double precision.
	 */
	public Vector3f cameraRelativeVertex(
		Vec3 localVertex,
		BlockPos environmentOrigin,
		Vec3 cameraPosition
	) {
		Objects.requireNonNull(localVertex, "localVertex");
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		return cameraRelativeVertex(
			new Vector3d(localVertex.x, localVertex.y, localVertex.z),
			environmentOrigin,
			new Vector3d(cameraPosition.x, cameraPosition.y, cameraPosition.z));
	}

	/**
	 * JOML form of {@link #cameraRelativeVertex(Vec3, BlockPos, Vec3)} for
	 * geometry pipelines that already use double vectors.
	 */
	public Vector3f cameraRelativeVertex(
		Vector3dc localVertex,
		BlockPos environmentOrigin,
		Vector3dc cameraPosition
	) {
		return cameraRelativeVertex(localVertex, (Vec3i) environmentOrigin, cameraPosition);
	}

	/**
	 * Integer-vector form for environment origins that are not represented by a
	 * block position (for example, a renderer's chunk/plot origin).
	 */
	public Vector3f cameraRelativeVertex(
		Vector3dc localVertex,
		Vec3i environmentOrigin,
		Vector3dc cameraPosition
	) {
		return cameraRelativeEnvironmentVertex(localVertex, environmentOrigin, cameraPosition);
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
		Vector3dc cameraPosition
	) {
		Objects.requireNonNull(localVertex, "localVertex");
		Objects.requireNonNull(environmentOrigin, "environmentOrigin");
		Objects.requireNonNull(cameraPosition, "cameraPosition");

		Vector3d worldPosition = new Vector3d(
			environmentOrigin.getX() + localVertex.x(),
			environmentOrigin.getY() + localVertex.y(),
			environmentOrigin.getZ() + localVertex.z());
		transformPosition(worldPosition);
		worldPosition.sub(cameraPosition.x(), cameraPosition.y(), cameraPosition.z());
		return new Vector3f(
			(float) worldPosition.x,
			(float) worldPosition.y,
			(float) worldPosition.z);
	}

	/** Minecraft camera-position convenience form of the embedded route. */
	public Vector3f cameraRelativeEnvironmentVertex(
		Vector3dc localVertex,
		Vec3i environmentOrigin,
		Vec3 cameraPosition
	) {
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		return cameraRelativeEnvironmentVertex(
			localVertex,
			environmentOrigin,
			new Vector3d(cameraPosition.x, cameraPosition.y, cameraPosition.z));
	}

	/** Minecraft-vector convenience form of the embedded route. */
	public Vector3f cameraRelativeEnvironmentVertex(
		Vec3 localVertex,
		Vec3i environmentOrigin,
		Vec3 cameraPosition
	) {
		Objects.requireNonNull(localVertex, "localVertex");
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		return cameraRelativeEnvironmentVertex(
			new Vector3d(localVertex.x, localVertex.y, localVertex.z),
			environmentOrigin,
			new Vector3d(cameraPosition.x, cameraPosition.y, cameraPosition.z));
	}

	/** Convenience JOML-vector form with a Minecraft camera position. */
	public Vector3f cameraRelativeVertex(
		Vector3dc localVertex,
		BlockPos environmentOrigin,
		Vec3 cameraPosition
	) {
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		return cameraRelativeEnvironmentVertex(
			localVertex,
			(Vec3i) environmentOrigin,
			new Vector3d(cameraPosition.x, cameraPosition.y, cameraPosition.z));
	}

	/** JOML-vector form with a Minecraft camera position and integer origin. */
	public Vector3f cameraRelativeVertex(
		Vector3dc localVertex,
		Vec3i environmentOrigin,
		Vec3 cameraPosition
	) {
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		return cameraRelativeEnvironmentVertex(
			localVertex,
			environmentOrigin,
			new Vector3d(cameraPosition.x, cameraPosition.y, cameraPosition.z));
	}

	/** Minecraft-vector convenience overload for a general integer origin. */
	public Vector3f cameraRelativeVertex(
		Vec3 localVertex,
		Vec3i environmentOrigin,
		Vec3 cameraPosition
	) {
		Objects.requireNonNull(localVertex, "localVertex");
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		return cameraRelativeEnvironmentVertex(
			new Vector3d(localVertex.x, localVertex.y, localVertex.z),
			environmentOrigin,
			new Vector3d(cameraPosition.x, cameraPosition.y, cameraPosition.z));
	}

	/** Returns the converted vertex using a name common to geometry adapters. */
	public Vector3f transformVertex(
		Vector3dc localVertex,
		BlockPos environmentOrigin,
		Vector3dc cameraPosition
	) {
		return cameraRelativeVertex(localVertex, environmentOrigin, cameraPosition);
	}

	/** Minecraft-vector convenience overload of {@link #transformVertex}. */
	public Vector3f transformVertex(
		Vec3 localVertex,
		BlockPos environmentOrigin,
		Vec3 cameraPosition
	) {
		return cameraRelativeVertex(localVertex, environmentOrigin, cameraPosition);
	}

	/** Integer-vector convenience overload of {@link #transformVertex}. */
	public Vector3f transformVertex(
		Vector3dc localVertex,
		Vec3i environmentOrigin,
		Vector3dc cameraPosition
	) {
		return cameraRelativeVertex(localVertex, environmentOrigin, cameraPosition);
	}

	/** Integer-vector convenience overload with a Minecraft camera position. */
	public Vector3f transformVertex(
		Vector3dc localVertex,
		Vec3i environmentOrigin,
		Vec3 cameraPosition
	) {
		return cameraRelativeVertex(localVertex, environmentOrigin, cameraPosition);
	}

	/** Minecraft-vector convenience overload for an integer environment origin. */
	public Vector3f transformVertex(
		Vec3 localVertex,
		Vec3i environmentOrigin,
		Vec3 cameraPosition
	) {
		return cameraRelativeVertex(localVertex, environmentOrigin, cameraPosition);
	}

	private void transformPosition(Vector3d position) {
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
