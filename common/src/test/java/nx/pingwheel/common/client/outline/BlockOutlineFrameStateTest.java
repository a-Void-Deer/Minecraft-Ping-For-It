package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frame-coherence regression for the late native-edge route. The fake render
 * state stands in for RenderSystem and records the matrix used when a native
 * edge endpoint is emitted; no game renderer or mutable Camera is needed.
 */
class BlockOutlineFrameStateTest {

	private static final float EPSILON = 0.0001F;

	@Test
	void lateDrawUsesItsCapturedFrameAcrossRapidCameraAndTargetChanges() {
		VertexSorting sorting = VertexSorting.byDistance(0.0F, 0.0F, 0.0F);
		Vec3 firstCamera = new Vec3(20_000_000.25D, 64.5D, -19_999_999.75D);
		Vec3 firstTargetOrigin = new Vec3(20_000_006.0D, 66.0D, -19_999_996.0D);
		Matrix4f firstView = new Matrix4f().rotateY(0.8F).rotateX(-0.3F);
		Matrix4f firstProjection = new Matrix4f().scaling(0.25F, 0.5F, 0.75F);

		BlockOutlineFrameState.FrameTransform first = frame(
			firstView, firstProjection, sorting, firstCamera, 0.25F);
		FakeRenderState renderState = new FakeRenderState(
			new Matrix4f().translation(-500.0F, 100.0F, 20.0F),
			new Matrix4f().scaling(4.0F),
			VertexSorting.byDistance(7.0F, 8.0F, 9.0F));
		List<Vector4f> emitted = new ArrayList<>();

		// Simulate a later hook changing the ambient frame and a Camera moving
		// again before the late line buffer is flushed. Neither source is part
		// of the immutable first-frame contract.
		renderState.modelView = new Matrix4f().rotateZ(1.4F);
		renderState.projection = new Matrix4f().scaling(8.0F, 7.0F, 6.0F);
		Vec3 mutableCameraAfterCapture = new Vec3(-400.0D, 80.0D, 1200.0D);
		Matrix4f ambientModelView = new Matrix4f(renderState.modelView);
		Matrix4f ambientProjection = new Matrix4f(renderState.projection);
		VertexSorting ambientSorting = renderState.sorting;

		BlockOutlineFrameState.renderWithFrame(first, renderState, () ->
			emitNativeEdgeEndpoints(
				firstTargetOrigin,
				first.cameraPosition(),
				renderState.modelView,
				renderState.projection,
				emitted));

		assertContainsEndpoint(
			emitted,
			project(firstTargetOrigin.add(0.25D, 0.25D, 0.25D), firstCamera, firstView, firstProjection));
		assertFalse(containsEndpoint(
			emitted,
			project(
				firstTargetOrigin.add(0.25D, 0.25D, 0.25D),
				mutableCameraAfterCapture,
				ambientModelView,
				ambientProjection)),
			"a mutable late camera/global transform must not control the first frame");
		assertRestored(renderState, ambientModelView, ambientProjection, ambientSorting);

		Vec3 secondCamera = new Vec3(20_000_002.75D, 65.0D, -20_000_001.5D);
		Vec3 secondTargetOrigin = new Vec3(20_000_009.0D, 67.0D, -19_999_993.0D);
		Matrix4f secondView = new Matrix4f().rotateY(-1.15F).rotateX(0.55F);
		Matrix4f secondProjection = new Matrix4f().scaling(0.8F, 0.6F, 0.4F);
		BlockOutlineFrameState.FrameTransform second = frame(
			secondView, secondProjection, sorting, secondCamera, 0.75F);

		int secondFrameStart = emitted.size();
		BlockOutlineFrameState.renderWithFrame(second, renderState, () ->
			emitNativeEdgeEndpoints(
				secondTargetOrigin,
				second.cameraPosition(),
				renderState.modelView,
				renderState.projection,
				emitted));

		assertContainsEndpoint(
			emitted.subList(secondFrameStart, emitted.size()),
			project(secondTargetOrigin.add(0.25D, 0.25D, 0.25D), secondCamera, secondView, secondProjection));
		assertFalse(emitted.get(0).equals(emitted.get(secondFrameStart)),
			"the following frame must adopt its moved target and rotated camera");
		assertRestored(renderState, ambientModelView, ambientProjection, ambientSorting);
	}

	@Test
	void snapshotsAreDefensiveConsumedOnceAndRestoreStateAfterFailure() {
		Matrix4f modelView = new Matrix4f().rotateY(0.35F);
		Matrix4f projection = new Matrix4f().scaling(0.7F);
		VertexSorting sorting = VertexSorting.byDistance(1.0F, 2.0F, 3.0F);
		BlockOutlineFrameState state = BlockOutlineFrameState.INSTANCE;
		state.clear();
		state.capture(modelView, projection, sorting, new Vec3(10.0D, 20.0D, 30.0D), 0.5F);
		modelView.identity();
		projection.identity();

		BlockOutlineFrameState.FrameTransform captured = state.consume();
		assertTrue(captured.modelViewMatrix().equals(new Matrix4f().rotateY(0.35F)));
		assertTrue(captured.projectionMatrix().equals(new Matrix4f().scaling(0.7F)));
		assertNull(state.consume(), "a late frame can be consumed only once");

		VertexSorting previousSorting = VertexSorting.byDistance(-1.0F, -2.0F, -3.0F);
		FakeRenderState renderState = new FakeRenderState(
			new Matrix4f().translation(5.0F, 6.0F, 7.0F),
			new Matrix4f().scaling(3.0F),
			previousSorting);
		assertThrows(IllegalStateException.class, () ->
			BlockOutlineFrameState.renderWithFrame(captured, renderState, () -> {
				throw new IllegalStateException("simulated late batch failure");
			}));

		assertRestored(
			renderState,
			new Matrix4f().translation(5.0F, 6.0F, 7.0F),
			new Matrix4f().scaling(3.0F),
			previousSorting);
		assertEquals(1, renderState.pushes);
		assertEquals(1, renderState.pops);
		assertEquals(2, renderState.applies);
	}

	@Test
	void clearDiscardsAnAbandonedPriorFrameBeforeTheNextWorldPass() {
		BlockOutlineFrameState state = BlockOutlineFrameState.INSTANCE;
		state.clear();
		state.capture(
			new Matrix4f().rotateY(0.25F),
			new Matrix4f().scaling(0.5F),
			VertexSorting.byDistance(0.0F, 0.0F, 0.0F),
			new Vec3(2.0D, 3.0D, 4.0D),
			0.25F);

		state.clear();

		assertNull(state.consume(),
			"GameRenderer HEAD cleanup must prevent an abandoned frame reaching a later pass");
	}

	private static BlockOutlineFrameState.FrameTransform frame(
		Matrix4f modelView,
		Matrix4f projection,
		VertexSorting sorting,
		Vec3 camera,
		float partialTick
	) {
		BlockOutlineFrameState state = BlockOutlineFrameState.INSTANCE;
		state.clear();
		state.capture(modelView, projection, sorting, camera, partialTick);
		return state.consume();
	}

	private static Vector4f project(
		Vec3 worldEdge, Vec3 camera, Matrix4f modelView, Matrix4f projection
	) {
		// Native VoxelShape endpoints are translated exactly once to camera
		// relative coordinates. A second subtraction would visibly fail here,
		// especially at the large origin used above.
		return new Vector4f(
			(float) (worldEdge.x - camera.x),
			(float) (worldEdge.y - camera.y),
			(float) (worldEdge.z - camera.z),
			1.0F).mul(modelView).mul(projection);
	}

	private static void emitNativeEdgeEndpoints(
		Vec3 worldOrigin,
		Vec3 camera,
		Matrix4f modelView,
		Matrix4f projection,
		List<Vector4f> output
	) {
		PoseStack poseStack = new PoseStack();
		poseStack.translate(
			worldOrigin.x - camera.x,
			worldOrigin.y - camera.y,
			worldOrigin.z - camera.z);
		RecordingVertexConsumer consumer = new RecordingVertexConsumer();
		VoxelShapeRenderUtil.renderEdges(
			poseStack,
			consumer,
			Shapes.box(0.25D, 0.25D, 0.25D, 0.75D, 0.75D, 0.75D),
			0.0D,
			0.0D,
			0.0D,
			0xFFFFFFFF);

		for (Vector3f position : consumer.positions) {
			output.add(new Vector4f(position, 1.0F).mul(modelView).mul(projection));
		}
	}

	private static void assertContainsEndpoint(List<Vector4f> endpoints, Vector4f expected) {
		assertTrue(containsEndpoint(endpoints, expected), "missing expected native edge endpoint: " + expected);
	}

	private static boolean containsEndpoint(List<Vector4f> endpoints, Vector4f expected) {
		return endpoints.stream().anyMatch(endpoint -> endpoint.distance(expected) < EPSILON);
	}

	private static void assertEndpointEquals(Vector4f expected, Vector4f actual) {
		assertEquals(expected.x, actual.x, EPSILON);
		assertEquals(expected.y, actual.y, EPSILON);
		assertEquals(expected.z, actual.z, EPSILON);
		assertEquals(expected.w, actual.w, EPSILON);
	}

	private static void assertRestored(
		FakeRenderState renderState,
		Matrix4f expectedModelView,
		Matrix4f expectedProjection,
		VertexSorting expectedSorting
	) {
		assertTrue(renderState.modelView.equals(expectedModelView));
		assertTrue(renderState.projection.equals(expectedProjection));
		assertSame(expectedSorting, renderState.sorting,
			"the previous projection sorting must be restored");
	}

	private static final class FakeRenderState implements BlockOutlineFrameState.RenderStateAccess {
		private Matrix4f modelView;
		private Matrix4f projection;
		private VertexSorting sorting;
		private Matrix4f pushedModelView;
		private int pushes;
		private int pops;
		private int applies;

		private FakeRenderState(Matrix4f modelView, Matrix4f projection, VertexSorting sorting) {
			this.modelView = new Matrix4f(modelView);
			this.projection = new Matrix4f(projection);
			this.sorting = sorting;
		}

		@Override
		public void pushModelView() {
			this.pushedModelView = new Matrix4f(this.modelView);
			this.pushes++;
		}

		@Override
		public void setModelView(Matrix4f matrix) {
			this.modelView = new Matrix4f(matrix);
		}

		@Override
		public void applyModelView() {
			this.applies++;
		}

		@Override
		public void popModelView() {
			this.modelView = this.pushedModelView;
			this.pops++;
		}

		@Override
		public Matrix4f projectionMatrix() {
			return new Matrix4f(this.projection);
		}

		@Override
		public VertexSorting vertexSorting() {
			return this.sorting;
		}

		@Override
		public void setProjection(Matrix4f matrix, VertexSorting sorting) {
			this.projection = new Matrix4f(matrix);
			this.sorting = sorting;
		}
	}

	private static final class RecordingVertexConsumer implements VertexConsumer {
		private final List<Vector3f> positions = new ArrayList<>();

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			this.positions.add(new Vector3f(x, y, z));
			return this;
		}

		@Override
		public VertexConsumer setColor(int red, int green, int blue, int alpha) {
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
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}
	}
}
