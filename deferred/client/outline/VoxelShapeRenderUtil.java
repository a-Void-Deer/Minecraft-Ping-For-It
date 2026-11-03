package nx.pingwheel.common.client.outline;

import java.util.Objects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Loader-neutral edge iteration and line rendering for a native {@link
 * VoxelShape}, used by the block outline pass.
 *
 * <p>{@link #forEachEdge} exactly forwards every edge the native shape
 * decomposes into via {@link VoxelShape#forAllEdges} — no AABB flattening
 * ({@code toAabbs}), no full-cube substitution, and no approximation. This
 * makes slab, fence, pressure plate, and other non-full-cube shapes render
 * their exact native wireframe.
 *
 * <p>{@link #renderEdges} emits two {@link VertexConsumer} line vertices per
 * edge with a normalized direction vector as the normal and the given fully
 * opaque ARGB color. Zero-length edges are skipped defensively (they could
 * otherwise produce NaN normals).
 */
public final class VoxelShapeRenderUtil {

	private VoxelShapeRenderUtil() {}

	/**
	 * A callback receiving one edge of the shape as its two endpoints.
	 */
	@FunctionalInterface
	public interface EdgeConsumer {

		/**
		 * One shape edge from ({@code minX},{@code minY},{@code minZ}) to
		 * ({@code maxX},{@code maxY},{@code maxZ}).
		 */
		void edge(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
	}

	/**
	 * Forwards every edge of the native shape decomposition of {@code shape}
	 * to {@code consumer}, exactly once per edge and in the native order.
	 *
	 * <p>Pure and testable without any live client: the native
	 * {@link VoxelShape#forAllEdges} accessor is invoked directly, so tests
	 * can assert real shape bounds (for example that a
	 * {@code Shapes.box(0,0,0,1,0.5,1)} slab never emits an edge above
	 * {@code y=0.5}).
	 */
	public static void forEachEdge(VoxelShape shape, EdgeConsumer consumer) {
		Objects.requireNonNull(shape, "shape");
		Objects.requireNonNull(consumer, "consumer");

		shape.forAllEdges((minX, minY, minZ, maxX, maxY, maxZ) ->
			consumer.edge(minX, minY, minZ, maxX, maxY, maxZ));
	}

	/**
	 * Renders every edge of {@code shape} into {@code vertexConsumer} as
	 * position-color-normal line vertices, offset by ({@code offsetX},
	 * {@code offsetY}, {@code offsetZ}) and colored with the fully opaque
	 * {@code argbColor}.
	 *
	 * <p>Each edge is emitted as exactly two vertices through
	 * {@code vertexConsumer.addVertex(pose.last(),...)}; the normal is the
	 * edge's normalized direction. Zero-length edges are skipped
	 * defensively. No render state is touched and nothing is flushed here.
	 */
	public static void renderEdges(
		PoseStack poseStack,
		VertexConsumer vertexConsumer,
		VoxelShape shape,
		double offsetX,
		double offsetY,
		double offsetZ,
		int argbColor
	) {
		Objects.requireNonNull(poseStack, "poseStack");
		Objects.requireNonNull(vertexConsumer, "vertexConsumer");
		Objects.requireNonNull(shape, "shape");

		int red = (argbColor >> 16) & 0xFF;
		int green = (argbColor >> 8) & 0xFF;
		int blue = argbColor & 0xFF;
		int alpha = (argbColor >>> 24) & 0xFF;
		PoseStack.Pose pose = poseStack.last();

		forEachEdge(shape, (minX, minY, minZ, maxX, maxY, maxZ) -> {
			double deltaX = maxX - minX;
			double deltaY = maxY - minY;
			double deltaZ = maxZ - minZ;

			if (deltaX == 0.0 && deltaY == 0.0 && deltaZ == 0.0) {
				return;
			}

			double length = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
			float normalX = (float) (deltaX / length);
			float normalY = (float) (deltaY / length);
			float normalZ = (float) (deltaZ / length);

			vertexConsumer
				.addVertex(pose, (float) (minX + offsetX), (float) (minY + offsetY), (float) (minZ + offsetZ))
				.setColor(red, green, blue, alpha)
				.setNormal(pose, normalX, normalY, normalZ);
			vertexConsumer
				.addVertex(pose, (float) (maxX + offsetX), (float) (maxY + offsetY), (float) (maxZ + offsetZ))
				.setColor(red, green, blue, alpha)
				.setNormal(pose, normalX, normalY, normalZ);
		});
	}

}
