package nx.pingwheel.common.client.outline;

import java.util.Objects;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Captures the render-level transform that the post-world-composite
 * VoxelShape pass belongs to. The late pass deliberately consumes the
 * snapshot once so an aborted or skipped frame can never be rendered using it
 * later.
 */
public final class BlockOutlineFrameState {

	public static final BlockOutlineFrameState INSTANCE = new BlockOutlineFrameState();

	private FrameTransform frame;

	private BlockOutlineFrameState() {}

	/** Captures defensive copies of the applied world transform for this frame. */
	public void capture(
		Matrix4f modelViewMatrix,
		Matrix4f projectionMatrix,
		VertexSorting vertexSorting,
		Vec3 cameraPosition,
		float partialTick
	) {
		this.frame = new FrameTransform(
			modelViewMatrix, projectionMatrix, vertexSorting, cameraPosition, partialTick);
	}

	/**
	 * Returns the current frame exactly once. Clearing before the caller draws
	 * also makes exceptional draw exits unable to retain stale frame state.
	 */
	public FrameTransform consume() {
		FrameTransform captured = this.frame;
		this.frame = null;
		return captured;
	}

	/** Clears a frame that did not reach the late render hook. */
	public void clear() {
		this.frame = null;
	}

	/**
	 * Runs a late draw with the captured world transform rather than whichever
	 * mutable RenderSystem state happens to be ambient at the late hook.
	 */
	public static void renderWithFrame(FrameTransform frame, Runnable renderer) {
		renderWithFrame(frame, RenderSystemAccess.INSTANCE, renderer);
	}

	/**
	 * Runs a camera-relative draw with the given camera-rotation view matrix as
	 * the global model view. The ambient projection is deliberately left
	 * untouched: during the world pass the projection is already the world
	 * projection, and the model-outline flushes must only add the missing
	 * camera rotation.
	 *
	 * <p>The previous model view is restored on every exit path, including an
	 * exceptional one, so a failed attempt can never leak a rotated view into
	 * the vanilla outline flush that follows.</p>
	 */
	public static void withCameraRotation(Matrix4f cameraRotation, Runnable renderer) {
		try (CameraRotationScope ignored = openCameraRotation(cameraRotation)) {
			renderer.run();
		}
	}

	/**
	 * Opens a camera-rotation model-view scope that covers every submission
	 * made while it is open, including automatic flushes performed by
	 * {@code MultiBufferSource.BufferSource.getBuffer()} when the render type
	 * changes. The previous model view is restored on
	 * {@link CameraRotationScope#close()}.
	 */
	public static CameraRotationScope openCameraRotation(Matrix4f cameraRotation) {
		return new CameraRotationScope(cameraRotation, RenderSystemAccess.INSTANCE);
	}

	/** Visible to focused package tests through a small render-state seam. */
	static CameraRotationScope openCameraRotation(
		Matrix4f cameraRotation, RenderStateAccess renderState
	) {
		return new CameraRotationScope(cameraRotation, renderState);
	}

	/** Visible to focused package tests through a small render-state seam. */
	static void withCameraRotation(
		Matrix4f cameraRotation, RenderStateAccess renderState, Runnable renderer
	) {
		Objects.requireNonNull(renderer, "renderer");

		try (CameraRotationScope ignored = openCameraRotation(cameraRotation, renderState)) {
			renderer.run();
		}
	}

	/** Scope handle returned by {@link #openCameraRotation(Matrix4f)}. */
	public static final class CameraRotationScope implements AutoCloseable {
		private final RenderStateAccess renderState;
		private boolean closed;

		private CameraRotationScope(Matrix4f cameraRotation, RenderStateAccess renderState) {
			Objects.requireNonNull(cameraRotation, "cameraRotation");
			this.renderState = Objects.requireNonNull(renderState, "renderState");

			this.renderState.pushModelView();

			try {
				this.renderState.setModelView(cameraRotation);
				this.renderState.applyModelView();
			} catch (RuntimeException | Error failure) {
				close();
				throw failure;
			}
		}

		@Override
		public void close() {
			if (this.closed) {
				return;
			}

			this.closed = true;

			try {
				this.renderState.popModelView();
			} finally {
				this.renderState.applyModelView();
			}
		}
	}

	/** Visible to focused package tests through a small render-state seam. */
	static void renderWithFrame(
		FrameTransform frame, RenderStateAccess renderState, Runnable renderer
	) {
		Objects.requireNonNull(frame, "frame");
		Objects.requireNonNull(renderState, "renderState");
		Objects.requireNonNull(renderer, "renderer");

		Matrix4f previousProjection = new Matrix4f(renderState.projectionMatrix());
		VertexSorting previousSorting = renderState.vertexSorting();
		boolean modelViewPushed = false;

		try {
			renderState.pushModelView();
			modelViewPushed = true;
			renderState.setModelView(frame.modelViewMatrix());
			renderState.applyModelView();
			renderState.setProjection(frame.projectionMatrix(), frame.vertexSorting());
			renderer.run();
		} finally {
			try {
				renderState.setProjection(previousProjection, previousSorting);
			} finally {
				if (modelViewPushed) {
					try {
						renderState.popModelView();
					} finally {
						renderState.applyModelView();
					}
				}
			}
		}
	}

	interface RenderStateAccess {
		void pushModelView();

		void setModelView(Matrix4f matrix);

		void applyModelView();

		void popModelView();

		Matrix4f projectionMatrix();

		VertexSorting vertexSorting();

		void setProjection(Matrix4f matrix, VertexSorting vertexSorting);
	}

	private enum RenderSystemAccess implements RenderStateAccess {
		INSTANCE;

		@Override
		public void pushModelView() {
			RenderSystem.getModelViewStack().pushPose();
		}

		@Override
		public void setModelView(Matrix4f matrix) {
			RenderSystem.getModelViewStack().setIdentity();
			RenderSystem.getModelViewStack().mulPoseMatrix(matrix);
		}

		@Override
		public void applyModelView() {
			RenderSystem.applyModelViewMatrix();
		}

		@Override
		public void popModelView() {
			RenderSystem.getModelViewStack().popPose();
		}

		@Override
		public Matrix4f projectionMatrix() {
			return RenderSystem.getProjectionMatrix();
		}

		@Override
		public VertexSorting vertexSorting() {
			return RenderSystem.getVertexSorting();
		}

		@Override
		public void setProjection(Matrix4f matrix, VertexSorting vertexSorting) {
			RenderSystem.setProjectionMatrix(matrix, vertexSorting);
		}
	}

	/** Immutable data captured from one render-level invocation. */
	public static final class FrameTransform {
		private final Matrix4f modelViewMatrix;
		private final Matrix4f projectionMatrix;
		private final VertexSorting vertexSorting;
		private final Vec3 cameraPosition;
		private final float partialTick;

		private FrameTransform(
			Matrix4f modelViewMatrix,
			Matrix4f projectionMatrix,
			VertexSorting vertexSorting,
			Vec3 cameraPosition,
			float partialTick
		) {
			this.modelViewMatrix = new Matrix4f(Objects.requireNonNull(modelViewMatrix, "modelViewMatrix"));
			this.projectionMatrix = new Matrix4f(Objects.requireNonNull(projectionMatrix, "projectionMatrix"));
			this.vertexSorting = Objects.requireNonNull(vertexSorting, "vertexSorting");
			Vec3 position = Objects.requireNonNull(cameraPosition, "cameraPosition");
			this.cameraPosition = new Vec3(position.x, position.y, position.z);
			this.partialTick = partialTick;
		}

		public Matrix4f modelViewMatrix() {
			return new Matrix4f(this.modelViewMatrix);
		}

		public Matrix4f projectionMatrix() {
			return new Matrix4f(this.projectionMatrix);
		}

		public VertexSorting vertexSorting() {
			return this.vertexSorting;
		}

		public Vec3 cameraPosition() {
			return new Vec3(this.cameraPosition.x, this.cameraPosition.y, this.cameraPosition.z);
		}

		public float partialTick() {
			return this.partialTick;
		}
	}
}
