package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the native {@link VoxelShape} edge seam and line rendering.
 *
 * <p>These tests exercise the real native shape decomposition
 * ({@code Shapes.box}) without any live client: they prove that a half-slab
 * shape emits edges only up to {@code y=0.5} (no full-cube substitution),
 * that an empty shape emits nothing, that every edge is finite and
 * non-zero-length, and that {@link VoxelShapeRenderUtil#renderEdges}
 * forwards offsets and colors exactly.
 */
class VoxelShapeRenderUtilTest {

	private record Edge(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

	private static List<Edge> collectEdges(VoxelShape shape) {
		List<Edge> edges = new ArrayList<>();
		VoxelShapeRenderUtil.forEachEdge(
			shape, (minX, minY, minZ, maxX, maxY, maxZ) ->
				edges.add(new Edge(minX, minY, minZ, maxX, maxY, maxZ)));
		return edges;
	}

	// --- native shape edge seam ---

	@Test
	void slabShapeEmitsOnlyNativeEdgesUpToHalfHeight() {
		VoxelShape slab = Shapes.box(0, 0, 0, 1, 0.5, 1);
		List<Edge> edges = collectEdges(slab);

		assertEquals(12, edges.size());

		for (Edge edge : edges) {
			// Every endpoint stays inside the native slab bounds: no y=1
			// full-cube substitution is ever emitted.
			assertTrue(edge.maxY() <= 0.5, "edge exceeds slab top: " + edge);
			assertTrue(edge.minY() >= 0.0, "edge below slab bottom: " + edge);
		}

		// The top face at y=0.5 is present: maxY really is the slab's own
		// height, not a full block's.
		assertTrue(
			edges.stream().anyMatch(edge -> edge.maxY() == 0.5 && edge.minY() == 0.5),
			"no edge lies on the slab top face");
	}

	@Test
	void fullCubeUsesNativeWireframeEdgesOnly() {
		VoxelShape fullCube = Shapes.box(0, 0, 0, 1, 1, 1);
		List<Edge> edges = collectEdges(fullCube);

		// The native full-cube shape is decomposed into its 12 wireframe edges,
		// not six faces or a quad abstraction.
		assertEquals(12, edges.size());

		PoseStack poseStack = new PoseStack();
		RecordingVertexConsumer consumer = new RecordingVertexConsumer();
		VoxelShapeRenderUtil.renderEdges(
			poseStack, consumer, fullCube, 0.0, 0.0, 0.0, 0xFFFFFFFF);

		// Exactly two position vertices per native edge.
		assertEquals(24, consumer.vertices().size());
	}

	@Test
	void emptyShapeEmitsNoEdges() {
		assertTrue(collectEdges(Shapes.empty()).isEmpty());
	}

	@Test
	void everyEdgeIsFiniteNonZeroLengthAndAxisAligned() {
		VoxelShape shape = Shapes.box(0, 0, 0, 1, 0.5, 1);
		List<Edge> edges = collectEdges(shape);

		assertEquals(12, edges.size());

		for (Edge edge : edges) {
			assertTrue(Double.isFinite(edge.minX()) && Double.isFinite(edge.minY())
				&& Double.isFinite(edge.minZ()) && Double.isFinite(edge.maxX())
				&& Double.isFinite(edge.maxY()) && Double.isFinite(edge.maxZ()));

			double dx = edge.maxX() - edge.minX();
			double dy = edge.maxY() - edge.minY();
			double dz = edge.maxZ() - edge.minZ();
			double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

			assertTrue(length > 0.0, "zero-length edge: " + edge);

			// Box edges are axis-aligned: exactly one component is non-zero.
			int nonZero = 0;
			nonZero += dx == 0.0 ? 0 : 1;
			nonZero += dy == 0.0 ? 0 : 1;
			nonZero += dz == 0.0 ? 0 : 1;
			assertEquals(1, nonZero, "non-axis-aligned edge: " + edge);
		}
	}

	// --- renderEdges vertex emission ---

	/**
	 * Records the vertex stream produced by renderEdges; the lines render
	 * type only consumes position, color, and normal, so only those are
	 * captured.
	 */
	private static final class RecordingVertexConsumer implements VertexConsumer {

		private record Vertex(
			float x, float y, float z,
			int red, int green, int blue, int alpha,
			float normalX, float normalY, float normalZ
		) {}

		private final List<Vertex> vertices = new ArrayList<>();
		private boolean started;
		private float x;
		private float y;
		private float z;
		private int red;
		private int green;
		private int blue;
		private int alpha;
		private float normalX;
		private float normalY;
		private float normalZ;

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			commitCurrent();

			this.x = x;
			this.y = y;
			this.z = z;
			started = true;
			return this;
		}

		@Override
		public VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z) {
			return addVertex(x, y, z);
		}

		@Override
		public VertexConsumer setColor(int red, int green, int blue, int alpha) {
			this.red = red;
			this.green = green;
			this.blue = blue;
			this.alpha = alpha;
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setOverlay(int overlayCoords) {
			return this;
		}

		@Override
		public VertexConsumer setLight(int packedLight) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
			this.normalX = normalX;
			this.normalY = normalY;
			this.normalZ = normalZ;
			return this;
		}

		private void commitCurrent() {
			if (!started) {
				return;
			}

			vertices.add(new Vertex(
				this.x, this.y, this.z, red, green, blue, alpha,
				normalX, normalY, normalZ));
		}

		List<Vertex> vertices() {
			commitCurrent();
			started = false;
			return vertices;
		}
	}

	@Test
	void renderEdgesForwardsOffsetsAndColorExactly() {
		PoseStack poseStack = new PoseStack();
		RecordingVertexConsumer consumer = new RecordingVertexConsumer();

		VoxelShapeRenderUtil.renderEdges(
			poseStack, consumer, Shapes.box(0, 0, 0, 1, 0.5, 1),
			10.0, 20.0, 30.0, 0xFF123456);

		List<RecordingVertexConsumer.Vertex> vertices = consumer.vertices();

		// 12 edges, 2 vertices each.
		assertEquals(24, vertices.size());

		for (RecordingVertexConsumer.Vertex vertex : vertices) {
			// Offsets are forwarded exactly into the vertex positions.
			assertTrue(vertex.x() >= 10.0f && vertex.x() <= 11.0f, "x out of range: " + vertex);
			assertTrue(vertex.y() >= 20.0f && vertex.y() <= 20.5f, "y out of range: " + vertex);
			assertTrue(vertex.z() >= 30.0f && vertex.z() <= 31.0f, "z out of range: " + vertex);

			// The ARGB color is decomposed exactly, alpha included.
			assertEquals(0x12, vertex.red());
			assertEquals(0x34, vertex.green());
			assertEquals(0x56, vertex.blue());
			assertEquals(0xFF, vertex.alpha());

			// The normal is the normalized edge direction (unit length).
			double length = Math.sqrt(
				vertex.normalX() * vertex.normalX()
					+ vertex.normalY() * vertex.normalY()
					+ vertex.normalZ() * vertex.normalZ());
			assertEquals(1.0, length, 1e-6, "non-unit normal: " + vertex);
		}

		// Vertices come in edge pairs with matching normals.
		for (int i = 0; i < vertices.size(); i += 2) {
			assertEquals(vertices.get(i).normalX(), vertices.get(i + 1).normalX(), 1e-6f);
			assertEquals(vertices.get(i).normalY(), vertices.get(i + 1).normalY(), 1e-6f);
			assertEquals(vertices.get(i).normalZ(), vertices.get(i + 1).normalZ(), 1e-6f);
		}
	}

	@Test
	void renderEdgesSkipsZeroLengthEdgesDefensively() {
		PoseStack poseStack = new PoseStack();
		RecordingVertexConsumer consumer = new RecordingVertexConsumer();

		// A degenerate shape only yields zero-length edges, which must all be
		// skipped instead of producing NaN normals.
		VoxelShapeRenderUtil.renderEdges(
			poseStack, consumer, Shapes.box(2, 2, 2, 2, 2, 2),
			0.0, 0.0, 0.0, 0xFFFFFFFF);

		assertTrue(consumer.vertices().isEmpty());
	}
}
