package nx.pingwheel.common.math;

import java.util.Objects;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNullByDefault;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4fc;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** Detached parent placement. No mutable provider matrix or quaternion escapes. */
@NotNullByDefault
public final class EntityParentFrame {

	private final Matrix4d nativeToWorld;
	private final Matrix4d worldToNative;
	private final Quaterniond orientation;

	private EntityParentFrame(Matrix4dc nativeToWorld, Quaterniondc orientation) {
		this.nativeToWorld = new Matrix4d(nativeToWorld);
		if (!this.nativeToWorld.isFinite() || this.nativeToWorld.m03() != 0
			|| this.nativeToWorld.m13() != 0 || this.nativeToWorld.m23() != 0
			|| this.nativeToWorld.m33() != 1) {
			throw new IllegalArgumentException("parent transform must be finite and affine");
		}
		double determinant = this.nativeToWorld.determinant();
		if (!Double.isFinite(determinant) || determinant == 0) {
			throw new IllegalArgumentException("parent transform must be invertible");
		}
		this.worldToNative = new Matrix4d(this.nativeToWorld).invert();
		if (!worldToNative.isFinite()) throw new IllegalArgumentException("parent inverse must be finite");
		double lengthSquared = orientation.lengthSquared();
		if (!Double.isFinite(lengthSquared) || lengthSquared == 0) {
			throw new IllegalArgumentException("parent orientation must be finite and nonzero");
		}
		this.orientation = new Quaterniond(orientation).normalize();
	}

	public static EntityParentFrame identity() {
		return new EntityParentFrame(new Matrix4d(), new Quaterniond());
	}

	public static EntityParentFrame copyOf(Matrix4dc nativeToWorld, Quaterniondc orientation) {
		return new EntityParentFrame(Objects.requireNonNull(nativeToWorld), Objects.requireNonNull(orientation));
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

	private static Vec3 transform(Matrix4dc matrix, Vec3 point) {
		Objects.requireNonNull(point);
		if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
			throw new IllegalArgumentException("point must be finite");
		}
		Vector3d result = matrix.transformPosition(point.x, point.y, point.z, new Vector3d());
		if (!result.isFinite()) throw new IllegalArgumentException("transformed point must be finite");
		return new Vec3(result.x, result.y, result.z);
	}
}
