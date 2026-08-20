package nx.pingwheel.common.client.outline;

/**
 * Pure math matching the Flywheel/Create instance vertex formulas used by
 * the optional NeoForge adapter.
 *
 * <p>The shader intentionally rotates model vertices around {@code .5} for
 * Create's rotating and scrolling instances. The helper keeps those formulas
 * independent of optional classes so they can be regression-tested on every
 * loader. Texture scrolling does not change wireframe positions, but its
 * exact coordinate formula is exposed as {@link #scrollOffset} for parity
 * tests and documentation.</p>
 */
public final class FlywheelTransformMath {
	private FlywheelTransformMath() {}

	public record Point(float x, float y, float z) {}

	public record Quaternion(float x, float y, float z, float w) {}

	/** Column-major affine matrix values, matching JOML's transformPosition. */
	public record Matrix4(
		float m00, float m01, float m02, float m03,
		float m10, float m11, float m12, float m13,
		float m20, float m21, float m22, float m23,
		float m30, float m31, float m32, float m33
	) {}

	public static Point transformed(Point point, Matrix4 matrix) {
		float x = matrix.m00() * point.x() + matrix.m10() * point.y()
			+ matrix.m20() * point.z() + matrix.m30();
		float y = matrix.m01() * point.x() + matrix.m11() * point.y()
			+ matrix.m21() * point.z() + matrix.m31();
		float z = matrix.m02() * point.x() + matrix.m12() * point.y()
			+ matrix.m22() * point.z() + matrix.m32();
		float w = matrix.m03() * point.x() + matrix.m13() * point.y()
			+ matrix.m23() * point.z() + matrix.m33();

		if (w != 0.0F && w != 1.0F) {
			x /= w;
			y /= w;
			z /= w;
		}

		return new Point(x, y, z);
	}

	public static Point oriented(Point point, Point pivot, Point position, Quaternion rotation) {
		Point rotated = rotateByQuaternion(
			new Point(point.x() - pivot.x(), point.y() - pivot.y(), point.z() - pivot.z()),
			rotation);
		return new Point(
			rotated.x() + pivot.x() + position.x(),
			rotated.y() + pivot.y() + position.y(),
			rotated.z() + pivot.z() + position.z());
	}

	public static Point rotating(
		Point point,
		Point position,
		Quaternion instanceRotation,
		Point axis,
		float speed,
		float offset,
		float renderSeconds
	) {
		float degrees = offset + renderSeconds * speed;
		Quaternion kineticRotation = axisAngleDegrees(axis, degrees);
		Point centered = new Point(point.x() - 0.5F, point.y() - 0.5F, point.z() - 0.5F);
		Point rotated = rotateByQuaternion(centered, instanceRotation);
		Point kinetic = rotateByQuaternion(rotated, kineticRotation);
		return new Point(
			kinetic.x() + position.x() + 0.5F,
			kinetic.y() + position.y() + 0.5F,
			kinetic.z() + position.z() + 0.5F);
	}

	public static Point scrolling(Point point, Point position, Quaternion rotation) {
		Point centered = new Point(point.x() - 0.5F, point.y() - 0.5F, point.z() - 0.5F);
		Point rotated = rotateByQuaternion(centered, rotation);
		return new Point(
			rotated.x() + position.x() + 0.5F,
			rotated.y() + position.y() + 0.5F,
			rotated.z() + position.z() + 0.5F);
	}

	public static Point scrollingTransformed(Point point, Matrix4 pose) {
		return transformed(point, pose);
	}

	/** Exact Create shader UV scroll: fract(speed * renderTicks + offset) * scale. */
	public static float scrollOffset(float speed, float offset, float scale, float renderTicks) {
		float value = speed * renderTicks + offset;
		return (value - (float) Math.floor(value)) * scale;
	}

	public static Point rotateByQuaternion(Point vector, Quaternion quaternion) {
		float crossIX = quaternion.y() * vector.z() - quaternion.z() * vector.y();
		float crossIY = quaternion.z() * vector.x() - quaternion.x() * vector.z();
		float crossIZ = quaternion.x() * vector.y() - quaternion.y() * vector.x();

		float innerX = crossIX + quaternion.w() * vector.x();
		float innerY = crossIY + quaternion.w() * vector.y();
		float innerZ = crossIZ + quaternion.w() * vector.z();
		float outerX = quaternion.y() * innerZ - quaternion.z() * innerY;
		float outerY = quaternion.z() * innerX - quaternion.x() * innerZ;
		float outerZ = quaternion.x() * innerY - quaternion.y() * innerX;

		return new Point(
			vector.x() + 2.0F * outerX,
			vector.y() + 2.0F * outerY,
			vector.z() + 2.0F * outerZ);
	}

	private static Quaternion axisAngleDegrees(Point axis, float degrees) {
		float halfAngle = (float) Math.toRadians(degrees) * 0.5F;
		float sine = (float) Math.sin(halfAngle);
		return new Quaternion(axis.x() * sine, axis.y() * sine, axis.z() * sine,
			(float) Math.cos(halfAngle));
	}
}
