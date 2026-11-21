package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.function.UnaryOperator;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNullByDefault;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4fc;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Detached parent placement. No mutable provider transform is retained or exposed. */
@NotNullByDefault
public final class EntityParentFrame {

	private final UnaryOperator<Vector3d> nativeToWorld;
	private final UnaryOperator<Vector3d> worldToNative;
	private final Quaterniond orientation;

	private EntityParentFrame(UnaryOperator<Vector3d> nativeToWorld, UnaryOperator<Vector3d> worldToNative,
		Quaterniond orientation) {
		this.nativeToWorld = nativeToWorld;
		this.worldToNative = worldToNative;
		this.orientation = orientation;
	}

	public static EntityParentFrame identity() {
		return copyOf(new Matrix4d(), new Quaterniond());
	}

	public static EntityParentFrame copyOf(Matrix4dc nativeToWorld, Quaterniondc orientation) {
		Matrix4d forward = new Matrix4d(Objects.requireNonNull(nativeToWorld));
		if (!forward.isFinite() || forward.m03() != 0 || forward.m13() != 0 || forward.m23() != 0 || forward.m33() != 1) {
			throw new IllegalArgumentException("parent transform must be finite and affine");
		}
		double determinant = forward.determinant();
		if (!Double.isFinite(determinant) || determinant == 0) {
			throw new IllegalArgumentException("parent transform must be invertible");
		}
		Matrix4d inverse = new Matrix4d(forward).invert();
		if (!inverse.isFinite()) throw new IllegalArgumentException("parent inverse must be finite");
		return new EntityParentFrame(forward::transformPosition, inverse::transformPosition, normalizedOrientation(orientation));
	}

	/** Copy a pose that scales and rotates about the native pivot before placing it at the world pivot. */
	public static EntityParentFrame copyOfPivoted(Vector3dc nativePivot, Vector3dc worldPivot,
		Quaterniondc orientation, Vector3dc scale) {
		Vector3d nativeOrigin = new Vector3d(nativePivot);
		Vector3d worldOrigin = new Vector3d(worldPivot);
		Vector3d nativeScale = new Vector3d(scale);
		Vector3d inverseScale = new Vector3d(1.0 / nativeScale.x, 1.0 / nativeScale.y, 1.0 / nativeScale.z);
		Quaterniond poseRotation = new Quaterniond(orientation);
		Quaterniond modelRotation = normalizedOrientation(poseRotation);
		if (!nativeOrigin.isFinite() || !worldOrigin.isFinite() || !nativeScale.isFinite()
			|| !inverseScale.isFinite() || !Double.isFinite(1.0 / poseRotation.lengthSquared())) {
			throw new IllegalArgumentException("parent pose and inverse must be finite");
		}
		// Subtract the source pivot before applying the linear transform to avoid cancellation at remote coordinates.
		return new EntityParentFrame(
			point -> poseRotation.transform(point.sub(nativeOrigin).mul(nativeScale)).add(worldOrigin),
			point -> poseRotation.transformInverse(point.sub(worldOrigin)).mul(inverseScale).add(nativeOrigin),
			modelRotation);
	}

	private static Quaterniond normalizedOrientation(Quaterniondc orientation) {
		double lengthSquared = orientation.lengthSquared();
		if (!Double.isFinite(lengthSquared) || lengthSquared == 0) {
			throw new IllegalArgumentException("parent orientation must be finite and nonzero");
		}
		return new Quaterniond(orientation).normalize();
	}

	public Vec3 toWorld(Vec3 point) { return transform(nativeToWorld, point); }
	public Vec3 toNative(Vec3 point) { return transform(worldToNative, point); }
	public Quaterniond rotationCopy() { return new Quaterniond(orientation); }

	/** Retained contraptions inherit full parent placement at their anchor, but only its rotation locally. */
	public Matrix4d modelToWorld(Vec3 nativeAnchor, Matrix4fc localModel) {
		Vec3 anchor = toWorld(nativeAnchor);
		Matrix4d local = new Matrix4d(Objects.requireNonNull(localModel));
		if (!local.isFinite()) throw new IllegalArgumentException("local model must be finite");
		return new Matrix4d().translation(anchor.x, anchor.y, anchor.z).rotate(orientation).mul(local);
	}

	private static Vec3 transform(UnaryOperator<Vector3d> transform, Vec3 point) {
		Objects.requireNonNull(point);
		if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
			throw new IllegalArgumentException("point must be finite");
		}
		Vector3d result = transform.apply(new Vector3d(point.x, point.y, point.z));
		if (!result.isFinite()) throw new IllegalArgumentException("transformed point must be finite");
		return new Vec3(result.x, result.y, result.z);
	}
}
