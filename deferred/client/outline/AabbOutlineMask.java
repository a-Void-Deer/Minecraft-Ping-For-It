package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.Objects;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Generic, pure builder of a camera-relative box silhouette mask: exactly six
 * outward-facing quads (24 vertices) derived from a finite, non-empty
 * {@link AABB}, with every corner translated by the negative camera position.
 *
 * <p>This type performs no render state work and has no dependency on any
 * optional mod; it is suitable as the base geometry for a future modded
 * entity-outline source (e.g. an AABB/selection-box silhouette route) and is
 * fully headless-testable. Each {@link Quad} carries its four corners in
 * outward-facing counter-clockwise order when viewed from outside the box, so
 * downstream silhouette rendering can rely on consistent winding. The box must
 * be finite (every coordinate a real number) and non-empty (positive extent on
 * all three axes).</p>
 */
public final class AabbOutlineMask {

	private final List<Quad> quads;

	private AabbOutlineMask(List<Quad> quads) {
		this.quads = List.copyOf(quads);
	}

	/**
	 * Builds the six-quad camera-relative mask for {@code box}.
	 *
	 * @throws IllegalArgumentException when the box is not finite or is empty
	 */
	public static AabbOutlineMask cameraRelative(AABB box, Vec3 camera) {
		Objects.requireNonNull(box, "box");
		Objects.requireNonNull(camera, "camera");
		requireFiniteNonEmpty(box);

		double x0 = box.minX - camera.x;
		double y0 = box.minY - camera.y;
		double z0 = box.minZ - camera.z;
		double x1 = box.maxX - camera.x;
		double y1 = box.maxY - camera.y;
		double z1 = box.maxZ - camera.z;

		// Eight corners; outward-facing CCW quads per face (see class javadoc).
		Vec3 p000 = new Vec3(x0, y0, z0);
		Vec3 p100 = new Vec3(x1, y0, z0);
		Vec3 p010 = new Vec3(x0, y1, z0);
		Vec3 p110 = new Vec3(x1, y1, z0);
		Vec3 p001 = new Vec3(x0, y0, z1);
		Vec3 p101 = new Vec3(x1, y0, z1);
		Vec3 p011 = new Vec3(x0, y1, z1);
		Vec3 p111 = new Vec3(x1, y1, z1);

		return new AabbOutlineMask(List.of(
			Quad.of(p100, p110, p111, p101), // +X
			Quad.of(p001, p011, p010, p000), // -X
			Quad.of(p010, p011, p111, p110), // +Y
			Quad.of(p000, p100, p101, p001), // -Y
			Quad.of(p001, p101, p111, p011), // +Z
			Quad.of(p000, p010, p110, p100)  // -Z
		));
	}

	private static void requireFiniteNonEmpty(AABB box) {
		if (!Double.isFinite(box.minX) || !Double.isFinite(box.minY) || !Double.isFinite(box.minZ)
			|| !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)) {
			throw new IllegalArgumentException("box must be finite: " + box);
		}

		if (box.minX >= box.maxX || box.minY >= box.maxY || box.minZ >= box.maxZ) {
			throw new IllegalArgumentException("box must be non-empty: " + box);
		}
	}

	/** The immutable, registration-order list of exactly six quads. */
	public List<Quad> quads() {
		return quads;
	}

	/**
	 * One quad of the mask: four camera-relative corners.
	 */
	public record Quad(
		float x0, float y0, float z0,
		float x1, float y1, float z1,
		float x2, float y2, float z2,
		float x3, float y3, float z3
	) {
		/** Builds a quad from four vertices. */
		public static Quad of(Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
			return new Quad(
				(float) a.x, (float) a.y, (float) a.z,
				(float) b.x, (float) b.y, (float) b.z,
				(float) c.x, (float) c.y, (float) c.z,
				(float) d.x, (float) d.y, (float) d.z);
		}
	}
}
