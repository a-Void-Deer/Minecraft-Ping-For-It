package nx.pingwheel.neoforge.integration.create;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.engine.AbstractInstancer;
import dev.engine_room.flywheel.backend.engine.InstanceHandleImpl;
import dev.engine_room.flywheel.backend.engine.embed.GlobalEnvironment;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectInstancer;
import dev.engine_room.flywheel.backend.mixin.LevelRendererAccessor;
import dev.engine_room.flywheel.impl.visualization.VisualManagerImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import nx.pingwheel.common.Global;
import nx.pingwheel.common.client.outline.EntityBlockGeometryContext;
import nx.pingwheel.common.client.outline.EntityBlockGeometryOutcome;
import nx.pingwheel.common.client.outline.EntityBlockGeometrySource;
import nx.pingwheel.common.client.outline.EntityBlockGeometrySourceRegistry;
import nx.pingwheel.common.client.outline.FlywheelDiagnosticReason;
import nx.pingwheel.common.client.outline.FlywheelDiagnostics;
import nx.pingwheel.common.client.outline.FlywheelMeshCache;
import nx.pingwheel.common.client.outline.FlywheelModelBudget;
import nx.pingwheel.common.client.outline.FlywheelRenderClock;
import nx.pingwheel.common.client.outline.FlywheelSilhouetteMask;
import nx.pingwheel.common.client.outline.FlywheelTransformMath;
import nx.pingwheel.common.util.WeakIdentityCache;

import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.processing.burner.ScrollInstance;
import com.simibubi.create.content.processing.burner.ScrollTransformedInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.lwjgl.system.MemoryUtil;

/**
 * NeoForge-only Create 6.0.10/Flywheel 1.0.6 vanilla silhouette-mask source.
 *
 * <p>The class is resolved only after the optional mod-id checks pass. The
 * source obtains live Visual/Instance/Model state for every attempt, retains
 * only weak-model keyed immutable mesh data, builds a complete current-frame
 * camera-relative plan, stages indexed triangles in fixed texture batches, and
 * commits them to the vanilla outline buffer only after encoding completes.
 * Flywheel's normal renderer remains untouched.</p>
 */
public final class CreateFlywheelGeometryAdapter {
	public static final String SOURCE_ID = "create:flywheel_silhouette_mask";
	private static final int MAX_INSTANCES_PER_TARGET = 1_024;

	private static EntityBlockGeometrySourceRegistryHandle registration;

	private CreateFlywheelGeometryAdapter() {}

	/** Called reflectively after the optional-mod checks have passed. */
	public static synchronized void register() {
		if (registration != null) {
			return;
		}

		CreateFlywheelGeometrySource source = new CreateFlywheelGeometrySource();
		EntityBlockGeometrySourceRegistry.Registration handle =
			nx.pingwheel.common.client.outline.EntityBlockGeometrySourceRegistry.INSTANCE
				.register(source);
		registration = new EntityBlockGeometrySourceRegistryHandle(handle, source);
		if (handle.accepted()) {
			Global.LOGGER.info("create/flywheel source registration succeeded: id={} handleState={}",
				SOURCE_ID, registration.state());
		} else {
			Global.LOGGER.warn("create/flywheel source registration failed: id={} handleState={}",
				SOURCE_ID, registration.state());
		}
	}

	/** Called reflectively during client teardown; safe to call repeatedly. */
	public static synchronized void close() {
		EntityBlockGeometrySourceRegistryHandle retained = registration;
		try {
			if (retained != null) {
				retained.close();
				Global.LOGGER.info("create/flywheel source registration: id={} handleState=closed", SOURCE_ID);
			}
		} finally {
			registration = null;
		}
	}

	/** Reflection-only state probe used by the loader registration diagnostics. */
	public static synchronized String registrationState() {
		return registration == null ? "not-registered" : registration.state();
	}

	private static final class EntityBlockGeometrySourceRegistryHandle implements AutoCloseable {
		private final EntityBlockGeometrySourceRegistry.Registration handle;
		private final CreateFlywheelGeometrySource source;

		private EntityBlockGeometrySourceRegistryHandle(
			EntityBlockGeometrySourceRegistry.Registration handle,
			CreateFlywheelGeometrySource source
		) {
			this.handle = handle;
			this.source = source;
		}

		private String state() {
			return handle.accepted() ? "registered" : "rejected";
		}

		@Override
		public void close() {
			try {
				handle.close();
			} finally {
				source.close();
			}
		}
	}

	private static final class CreateFlywheelGeometrySource implements EntityBlockGeometrySource {
		private final ModelGeometryCache modelCache = new ModelGeometryCache();
		private final FlywheelDiagnostics diagnostics = FlywheelDiagnostics.global();
		private final MaskFrameBudget frameBudget = new MaskFrameBudget();

		@Override
		public String id() {
			return SOURCE_ID;
		}

		private void close() {
			diagnostics.clear();
			modelCache.clear();
		}

		@Override
		public EntityBlockGeometryOutcome attempt(EntityBlockGeometryContext context) {
			if (context == null || context.level() == null || context.blockPos() == null
				|| context.targetKey() == null) {
				return empty(context, FlywheelDiagnosticReason.CONTEXT_UNAVAILABLE,
					null, null, null, () -> "level/position/target is unavailable");
			}

		VisualizationManagerImpl manager = null;
		BlockEntityVisual<?> visual = null;
		List<Instance> instances = null;
		try {
			try {
				manager = VisualizationManagerImpl.get(context.level());
			} catch (Exception | LinkageError | AssertionError failure) {
				return failed(context, FlywheelDiagnosticReason.MANAGER_NULL,
					null, null, null, () -> "manager lookup failed", failure);
			}

			if (manager == null) {
				return empty(context, FlywheelDiagnosticReason.MANAGER_NULL,
					null, null, null, () -> "live visualization manager is null");
			}

			BlockEntityVisualLookup visualLookup;
			try {
				visualLookup = liveVisualAt(manager, context.blockPos());
			} catch (Exception | LinkageError | AssertionError failure) {
				return failed(context, FlywheelDiagnosticReason.VISUAL_NULL,
					manager, null, null, () -> "live visual lookup failed", failure);
			}
			if (visualLookup.storageUnavailable()) {
				return empty(context, FlywheelDiagnosticReason.MANAGER_STORAGE_UNAVAILABLE,
					manager, null, null, visualLookup.details());
			}

			if (visualLookup.visual() == null) {
				return empty(context, FlywheelDiagnosticReason.VISUAL_NULL,
					manager, null, null,
					() -> "no live visual at target position; " + visualLookup.details().get());
			}

			visual = visualLookup.visual();
			try {
				instances = collectLiveInstances(visual);
			} catch (Exception | LinkageError | AssertionError failure) {
				return failed(context, FlywheelDiagnosticReason.VISUAL_NULL,
					manager, visual, null, () -> "collectCrumblingInstances failed", failure);
			}

			if (instances.isEmpty()) {
				return empty(context, FlywheelDiagnosticReason.NO_INSTANCES,
					manager, visual, instances, () -> "visual returned no instances");
			}


			List<ResolvedInstance> liveInstances = new ArrayList<>(instances.size());
			int nonLiveInstanceCount = 0;
			int resolutionFailureCount = 0;
			for (Instance instance : instances) {
				try {
					LiveInstanceResolution resolution = resolveLiveInstance(instance);
					if (resolution == null) {
						nonLiveInstanceCount++;
						continue;
					}
					liveInstances.add(new ResolvedInstance(
						instance, resolution.instancer(), resolution.model()));
				} catch (Exception | LinkageError | AssertionError ignored) {
					// A stale optional instance must not prevent valid sibling instances
					// from contributing geometry. The state class is retained in the
					// diagnostic list below for the next-frame retry.
					nonLiveInstanceCount++;
					resolutionFailureCount++;
				}
			}

			if (liveInstances.isEmpty()) {
				final int collectedInstanceCount = instances.size();
				final int skippedInstanceCount = nonLiveInstanceCount;
				final int failedResolutionCount = resolutionFailureCount;
				return empty(context, FlywheelDiagnosticReason.NO_INSTANCES,
					manager, visual, instances,
					() -> "no live instances; collectedInstanceCount=" + collectedInstanceCount
						+ "; nonLiveInstanceCount=" + skippedInstanceCount
						+ "; resolutionFailureCount=" + failedResolutionCount
						+ "; retryNextFrame=true");
			}

			if (liveInstances.size() > MAX_INSTANCES_PER_TARGET) {
				final int instanceCount = liveInstances.size();
				return empty(context, FlywheelDiagnosticReason.BUDGET_EXCEEDED,
					manager, visual, instances,
					() -> "instanceCount=" + instanceCount + "; maxInstances=" + MAX_INSTANCES_PER_TARGET);
			}

			Vec3i renderOrigin;
			try {
				renderOrigin = manager.renderOrigin();
			} catch (Exception | LinkageError | AssertionError failure) {
				return failed(context, FlywheelDiagnosticReason.TRANSFORM_UNAVAILABLE,
					manager, visual, instances, () -> "render origin lookup failed", failure);
			}

			Vec3 camera = context.cameraPosition();
			if (renderOrigin == null || camera == null
				|| !Double.isFinite(camera.x()) || !Double.isFinite(camera.y())
				|| !Double.isFinite(camera.z())) {
				return empty(context, FlywheelDiagnosticReason.TRANSFORM_UNAVAILABLE,
					manager, visual, instances,
					() -> "camera/renderOrigin is unavailable or non-finite");
			}

			float originX = (float) (renderOrigin.getX() - camera.x());
			float originY = (float) (renderOrigin.getY() - camera.y());
			float originZ = (float) (renderOrigin.getZ() - camera.z());
			if (!Float.isFinite(originX) || !Float.isFinite(originY) || !Float.isFinite(originZ)) {
				return empty(context, FlywheelDiagnosticReason.TRANSFORM_UNAVAILABLE,
					manager, visual, instances, () -> "camera-relative render origin is non-finite");
			}

			int ticks;
			try {
				ticks = context.levelRenderer() instanceof LevelRendererAccessor accessor
					? accessor.flywheel$getTicks() : (int) context.level().getGameTime();
			} catch (Exception | LinkageError | AssertionError failure) {
				return failed(context, FlywheelDiagnosticReason.TRANSFORM_UNAVAILABLE,
					manager, visual, instances, () -> "render tick lookup failed", failure);
			}

			float maskPartialTick = FlywheelRenderClock.maskPartialTick(
				context.partialTick(), context.flywheelPartialTick());
			float renderTicks = ticks + maskPartialTick;
			float renderSeconds = renderTicks / 20.0F;
			if (!Float.isFinite(renderTicks) || !Float.isFinite(renderSeconds)) {
				return empty(context, FlywheelDiagnosticReason.TRANSFORM_UNAVAILABLE,
					manager, visual, instances, () -> "render tick values are non-finite");
			}

			List<InstanceGeometry> geometries = new ArrayList<>(liveInstances.size());
			long triangleCount = 0L;

			for (ResolvedInstance resolved : liveInstances) {
				Instance instance = resolved.instance();
				Model model = resolved.model();
				ModelGeometry geometry;
				try {
					geometry = modelCache.get(model);
				} catch (GeometryFormatException failure) {
					return failed(context, failure.reason(), manager, visual, instances,
						() -> "model extraction=" + failure.getMessage(), failure);
				}

				try {
					triangleCount = Math.addExact(triangleCount, geometry.triangleCount());
				} catch (ArithmeticException overflow) {
					return empty(context, FlywheelDiagnosticReason.BUDGET_EXCEEDED,
						manager, visual, instances, () -> "target triangle count overflow");
				}

				if (!FlywheelModelBudget.trianglesWithinBudget(
					triangleCount, FlywheelModelBudget.MAX_TRIANGLES_PER_TARGET)) {
					final long currentTriangleCount = triangleCount;
					return empty(context, FlywheelDiagnosticReason.BUDGET_EXCEEDED,
						manager, visual, instances,
						() -> "triangleCount=" + currentTriangleCount + "; maxTargetTriangles="
							+ FlywheelModelBudget.MAX_TRIANGLES_PER_TARGET);
				}

				geometries.add(new InstanceGeometry(instance, geometry));
			}

			if (triangleCount == 0L) {
				return empty(context, FlywheelDiagnosticReason.NO_INSTANCES,
					manager, visual, instances, () -> "instances have no indexed triangles");
			}

			FlywheelSilhouetteMask.RenderPlan<ResourceLocation> plan;
			try {
				plan = buildPlan(geometries, originX, originY, originZ, renderTicks, renderSeconds);
			} catch (GeometryFormatException failure) {
				return failed(context, failure.reason(), manager, visual, instances,
					() -> "current-frame plan construction=" + failure.getMessage(), failure);
			}

			int plannedTriangles;
			try {
				plannedTriangles = plan.triangleCount();
			} catch (ArithmeticException overflow) {
				return empty(context, FlywheelDiagnosticReason.BUDGET_EXCEEDED,
					manager, visual, instances, () -> "render-plan triangle count overflow");
			}

			if (plannedTriangles <= 0) {
				return empty(context, FlywheelDiagnosticReason.NO_INSTANCES,
					manager, visual, instances, () -> "current-frame plan is empty");
			}

			if (!frameBudget.reserve(context.frameId(), plannedTriangles)) {
				return empty(context, FlywheelDiagnosticReason.BUDGET_EXCEEDED,
					manager, visual, instances,
					() -> "frameTriangleCount=" + frameBudget.count() + "; requested=" + plannedTriangles
						+ "; maxFrameTriangles=" + FlywheelModelBudget.MAX_TRIANGLES_PER_FRAME);
			}

			try {
				int emittedTriangles = emitMask(plan, context.argbColor());
				if (emittedTriangles <= 0 || emittedTriangles != plannedTriangles) {
					frameBudget.release(plannedTriangles);
					return empty(context, FlywheelDiagnosticReason.MASK_EMISSION_FAILED,
						manager, visual, instances,
						() -> "emittedTriangles=" + emittedTriangles + "; plannedTriangles=" + plannedTriangles);
				}
			} catch (FlywheelSilhouetteMask.CommitFailure failure) {
				Throwable originalFailure = failure.getCause() == null ? failure : failure.getCause();
				if (failure.verticesWritten() > 0) {
					report(context, FlywheelDiagnosticReason.MASK_PARTIAL_EMISSION,
						manager, visual, instances,
						() -> "shared OutlineBufferSource commit was partial; verticesWritten="
							+ failure.verticesWritten() + "; plannedTriangles=" + plannedTriangles
							+ "; voxelFallbackSuppressed=true; retryNextFrame=true",
						originalFailure);
					return EntityBlockGeometryOutcome.RENDERED;
				}
				frameBudget.release(plannedTriangles);
				return failed(context, FlywheelDiagnosticReason.MASK_EMISSION_FAILED,
					manager, visual, instances,
					() -> "shared OutlineBufferSource commit failed before first vertex; plannedTriangles="
						+ plannedTriangles + "; voxelFallbackApplied=true",
					originalFailure);
			} catch (Exception | LinkageError | AssertionError failure) {
				frameBudget.release(plannedTriangles);
				return failed(context, FlywheelDiagnosticReason.MASK_EMISSION_FAILED,
					manager, visual, instances, () -> "vanilla outline mask emission failed", failure);
			}

			report(context, FlywheelDiagnosticReason.RENDERED, manager, visual, instances,
				() -> "planTriangles=" + plannedTriangles + "; maskVertices="
					+ plannedTriangles * FlywheelModelBudget.MASK_VERTICES_PER_TRIANGLE, null);
			return EntityBlockGeometryOutcome.RENDERED;
			} catch (Exception | LinkageError | AssertionError failure) {
				return failed(context, FlywheelDiagnosticReason.MASK_EMISSION_FAILED,
					manager, visual, instances, () -> "unclassified source failure", failure);
			}
	}

	private static BlockEntityVisualLookup liveVisualAt(VisualizationManagerImpl manager, BlockPos pos) {
		if (!(manager.blockEntities() instanceof VisualManagerImpl<?, ?> visualManager)) {
			return new BlockEntityVisualLookup(null, true,
				() -> "managerBlockEntitiesClass=" + className(manager.blockEntities()));
		}
		if (!(visualManager.getStorage() instanceof BlockEntityStorage storage)) {
			return new BlockEntityVisualLookup(null, true,
				() -> "visualManagerClass=" + className(visualManager)
					+ "; storageClass=" + className(visualManager.getStorage()));
		}
		return new BlockEntityVisualLookup(storage.visualAtPos(pos.asLong()), false,
			() -> "visualManager=" + identity(visualManager) + "; storage=" + identity(storage));
	}

	private static List<Instance> collectLiveInstances(BlockEntityVisual<?> visual) {
		// One sentinel beyond the limit distinguishes an over-budget visual while
		// keeping the callback allocation bounded.
		List<Instance> instances = new ArrayList<>(MAX_INSTANCES_PER_TARGET + 1);
		visual.collectCrumblingInstances(instance -> {
			// Optional visual parts can disappear independently of the visual. A
			// null callback entry is not a malformed valid instance and must not
			// poison the rest of the target's geometry.
			if (instance != null && instances.size() <= MAX_INSTANCES_PER_TARGET) {
				instances.add(instance);
			}
		});
		return Collections.unmodifiableList(instances);
	}

	/**
	 * Resolves only a state that still belongs to a live Flywheel instancer.
	 *
	 * <p>Direct instancing stores the instancer itself as the handle state, while
	 * the indirect backend stores an {@code InstancePage}. The public
	 * {@link IndirectInstancer#fromState(InstanceHandleImpl.State)} mapping is
	 * deliberately used instead of probing visibility: indirect live pages are
	 * not reported visible by {@code InstanceHandleImpl}.</p>
	 */
	private static AbstractInstancer<?> resolveLiveInstancer(InstanceHandleImpl.State<?> state) {
		if (state instanceof AbstractInstancer<?> instancer) {
			return instancer;
		}
		return IndirectInstancer.fromState(state);
	}

	private static LiveInstanceResolution resolveLiveInstance(Instance instance) {
		if (instance == null || !(instance.handle() instanceof InstanceHandleImpl<?> handle)) {
			return null;
		}

		AbstractInstancer<?> instancer = resolveLiveInstancer(handle.state);
		if (instancer == null || instancer.environment != GlobalEnvironment.INSTANCE) {
			return null;
		}

		AbstractInstancer.Recreate<?> recreate = instancer.recreate;
		if (recreate == null || recreate.key() == null) {
			return null;
		}

		var key = recreate.key();
		Model model = key.model();
		if (model == null) {
			return null;
		}

		InstanceType<?> instanceType = instance.type();
		if (instanceType == null || instancer.type != instanceType || key.type() != instanceType
			|| !supportedInstanceType(instance)) {
			return null;
		}

		return new LiveInstanceResolution(instancer, model);
	}

	private static boolean supportedInstanceType(Instance instance) {
		InstanceType<?> type = instance.type();
		return (type == InstanceTypes.TRANSFORMED && instance instanceof TransformedInstance)
			|| (type == InstanceTypes.ORIENTED && instance instanceof OrientedInstance)
			|| (type == AllInstanceTypes.ROTATING && instance instanceof RotatingInstance)
			|| (type == AllInstanceTypes.SCROLLING && instance instanceof ScrollInstance)
			|| (type == AllInstanceTypes.SCROLLING_TRANSFORMED
				&& instance instanceof ScrollTransformedInstance);
	}

	private static FlywheelSilhouetteMask.RenderPlan<ResourceLocation> buildPlan(
		List<InstanceGeometry> geometries,
		float originX,
		float originY,
		float originZ,
		float renderTicks,
		float renderSeconds
	) throws GeometryFormatException {
		Map<ResourceLocation, List<FlywheelSilhouetteMask.Triangle>> grouped = new LinkedHashMap<>();

		for (InstanceGeometry entry : geometries) {
			Instance instance = entry.instance();
			for (MeshGeometry mesh : entry.model().meshes()) {
				FlywheelSilhouetteMask.Vertex[] transformed =
					new FlywheelSilhouetteMask.Vertex[mesh.vertexCount()];

				for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
					FlywheelTransformMath.Point local = mesh.point(vertex);
					FlywheelTransformMath.Point position = transformPoint(
						local, instance, renderTicks, renderSeconds);
					if (!FlywheelTransformMath.isFinite(position)) {
						// A corrupt transform only removes triangles that depend on
						// this vertex. Other valid model triangles remain eligible.
						continue;
					}
					float x = position.x() + originX;
					float y = position.y() + originY;
					float z = position.z() + originZ;
					float u = mesh.u(vertex);
					float v = mesh.v(vertex);

					if (isScrolling(instance)) {
						if (instance instanceof ScrollInstance scrolling) {
							u = FlywheelTransformMath.scrollingUv(
								u, scrolling.diffU, scrolling.speedU, scrolling.offsetU,
								scrolling.scaleU, renderTicks);
							v = FlywheelTransformMath.scrollingUv(
								v, scrolling.diffV, scrolling.speedV, scrolling.offsetV,
								scrolling.scaleV, renderTicks);
						} else if (instance instanceof ScrollTransformedInstance scrollingTransformed) {
							u = FlywheelTransformMath.scrollingUv(
								u, scrollingTransformed.diffU, scrollingTransformed.speedU,
								scrollingTransformed.offsetU, scrollingTransformed.scaleU, renderTicks);
							v = FlywheelTransformMath.scrollingUv(
								v, scrollingTransformed.diffV, scrollingTransformed.speedV,
								scrollingTransformed.offsetV, scrollingTransformed.scaleV, renderTicks);
						}
					}
					if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
						|| !Float.isFinite(u) || !Float.isFinite(v)) {
						continue;
					}

					try {
						transformed[vertex] = new FlywheelSilhouetteMask.Vertex(x, y, z, u, v);
					} catch (IllegalArgumentException ignored) {
						// The finite checks above are intentionally repeated by the
						// headless Vertex value object. Treat a rejected vertex as
						// invisible rather than exposing a partial mask.
						transformed[vertex] = null;
					}
				}

				List<FlywheelSilhouetteMask.Triangle> triangles = grouped.computeIfAbsent(
					mesh.texture(), ignored -> new ArrayList<>());
				for (int index = 0; index < mesh.indices().length; index += 3) {
					int first = mesh.indices()[index];
					int second = mesh.indices()[index + 1];
					int third = mesh.indices()[index + 2];
					if (transformed[first] == null || transformed[second] == null || transformed[third] == null) {
						continue;
					}
					FlywheelSilhouetteMask.Triangle triangle = new FlywheelSilhouetteMask.Triangle(
						transformed[first], transformed[second], transformed[third]);
					if (triangle.hasVisibleArea()) {
						triangles.add(triangle);
					}
				}
			}
		}

		List<FlywheelSilhouetteMask.TextureBatch<ResourceLocation>> batches =
			new ArrayList<>(grouped.size());
		for (Map.Entry<ResourceLocation, List<FlywheelSilhouetteMask.Triangle>> entry : grouped.entrySet()) {
			batches.add(new FlywheelSilhouetteMask.TextureBatch<>(
				entry.getKey(), List.copyOf(entry.getValue())));
		}
		return FlywheelSilhouetteMask.filterVisibleTriangles(
			new FlywheelSilhouetteMask.RenderPlan<>(List.copyOf(batches)));
	}

	private static int emitMask(
		FlywheelSilhouetteMask.RenderPlan<ResourceLocation> plan,
		int argbColor
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.renderBuffers() == null
			|| minecraft.renderBuffers().outlineBufferSource() == null) {
			throw new IllegalStateException("vanilla outline buffer source is unavailable");
		}

		OutlineBufferSource outlineBuffer = minecraft.renderBuffers().outlineBufferSource();
		FlywheelSilhouetteMask.EncodedPlan<ResourceLocation> encoded =
			FlywheelSilhouetteMask.encode(plan, argbColor);
		int red = (argbColor >> 16) & 0xFF;
		int green = (argbColor >> 8) & 0xFF;
		int blue = argbColor & 0xFF;

		// Encoding is complete before the first shared OutlineBufferSource
		// lookup, so recoverable failures before the first vertex have a complete
		// CPU preflight behind them. The shared vanilla buffer cannot roll back a
		// driver/runtime failure after a mid-write commit has started; commit()
		// reports how many vertices were accepted so the caller can suppress the
		// VoxelShape overlay for that frame. Fatal Errors are never caught here.
		return FlywheelSilhouetteMask.commit(encoded, texture -> {
			// OutlineBufferSource captures its team color when getBuffer creates
			// the outline consumer, so set it before that lookup and again
			// immediately before every mask vertex. The direct VertexConsumer
			// color write also pins alpha to the selected opaque ARGB color rather
			// than inheriting stale vanilla/team state.
			outlineBuffer.setColor(red, green, blue, 255);
			VertexConsumer consumer = outlineBuffer.getBuffer(RenderType.outline(texture));
			return new FlywheelSilhouetteMask.VertexEmitter() {
				@Override
				public void emit(FlywheelSilhouetteMask.Vertex vertex, int color) {
					emitTracked(vertex, color, () -> {});
				}

				@Override
				public void emitTracked(
					FlywheelSilhouetteMask.Vertex vertex,
					int color,
					FlywheelSilhouetteMask.VertexWriteObserver observer
				) {
					outlineBuffer.setColor(red, green, blue, 255);
					VertexConsumer writtenVertex = consumer.addVertex(vertex.x(), vertex.y(), vertex.z());
					observer.vertexWritten();
					writtenVertex
						.setColor(red, green, blue, 255)
						.setUv(vertex.u(), vertex.v());
				}
			};
		});
	}

	private static boolean isScrolling(Instance instance) {
		return instance.type() == AllInstanceTypes.SCROLLING
			|| instance.type() == AllInstanceTypes.SCROLLING_TRANSFORMED;
	}

	private static FlywheelTransformMath.Point transformPoint(
		FlywheelTransformMath.Point point,
		Instance instance,
		float renderTicks,
		float renderSeconds
	) throws GeometryFormatException {
		InstanceType<?> type = instance.type();

		if (type == InstanceTypes.TRANSFORMED && instance instanceof TransformedInstance transformed) {
			return FlywheelTransformMath.transformed(point, matrix(transformed.pose));
		}

		if (type == InstanceTypes.ORIENTED && instance instanceof OrientedInstance oriented) {
			return FlywheelTransformMath.oriented(
				point,
				new FlywheelTransformMath.Point(oriented.pivotX, oriented.pivotY, oriented.pivotZ),
				new FlywheelTransformMath.Point(oriented.posX, oriented.posY, oriented.posZ),
				quaternion(oriented.rotation));
		}

		if (type == AllInstanceTypes.ROTATING && instance instanceof RotatingInstance rotating) {
			return FlywheelTransformMath.rotating(
				point,
				new FlywheelTransformMath.Point(rotating.x, rotating.y, rotating.z),
				quaternion(rotating.rotation),
				new FlywheelTransformMath.Point(
					decodeAxis(rotating.rotationAxisX),
					decodeAxis(rotating.rotationAxisY),
					decodeAxis(rotating.rotationAxisZ)),
				rotating.rotationalSpeed, rotating.rotationOffset, renderSeconds);
		}

		if (type == AllInstanceTypes.SCROLLING && instance instanceof ScrollInstance scrolling) {
			return FlywheelTransformMath.scrolling(
				point,
				new FlywheelTransformMath.Point(scrolling.x, scrolling.y, scrolling.z),
				quaternion(scrolling.rotation));
		}

		if (type == AllInstanceTypes.SCROLLING_TRANSFORMED
			&& instance instanceof ScrollTransformedInstance scrollingTransformed) {
			return FlywheelTransformMath.scrollingTransformed(point, matrix(scrollingTransformed.pose));
		}

		throw new GeometryFormatException(
			FlywheelDiagnosticReason.TRANSFORM_UNAVAILABLE,
			"supported instance transform could not be read");
	}

	private static float decodeAxis(byte value) {
		return Math.min(1.0F, Math.max(-1.0F, value / 127.0F));
	}

	private static FlywheelTransformMath.Quaternion quaternion(Quaternionfc quaternion)
		throws GeometryFormatException {
		if (quaternion == null) {
			throw new GeometryFormatException(
				FlywheelDiagnosticReason.TRANSFORM_UNAVAILABLE, "instance quaternion is null");
		}
		return new FlywheelTransformMath.Quaternion(
			quaternion.x(), quaternion.y(), quaternion.z(), quaternion.w());
	}

	private static FlywheelTransformMath.Matrix4 matrix(Matrix4fc matrix)
		throws GeometryFormatException {
		if (matrix == null) {
			throw new GeometryFormatException(
				FlywheelDiagnosticReason.TRANSFORM_UNAVAILABLE, "instance pose matrix is null");
		}
		return new FlywheelTransformMath.Matrix4(
			matrix.m00(), matrix.m01(), matrix.m02(), matrix.m03(),
			matrix.m10(), matrix.m11(), matrix.m12(), matrix.m13(),
			matrix.m20(), matrix.m21(), matrix.m22(), matrix.m23(),
			matrix.m30(), matrix.m31(), matrix.m32(), matrix.m33());
	}

	private EntityBlockGeometryOutcome empty(
		EntityBlockGeometryContext context,
		FlywheelDiagnosticReason reason,
		Object manager,
		Object visual,
		List<Instance> instances,
		Supplier<String> detail
	) {
		report(context, reason, manager, visual, instances, detail, null);
		return EntityBlockGeometryOutcome.EMPTY;
	}

	private EntityBlockGeometryOutcome failed(
		EntityBlockGeometryContext context,
		FlywheelDiagnosticReason reason,
		Object manager,
		Object visual,
		List<Instance> instances,
		Supplier<String> detail,
		Throwable failure
	) {
		report(context, reason, manager, visual, instances, detail, failure);
		return EntityBlockGeometryOutcome.FAILED;
	}

	private void report(
		EntityBlockGeometryContext context,
		FlywheelDiagnosticReason reason,
		Object manager,
		Object visual,
		List<Instance> instances,
		Supplier<String> detail,
		Throwable failure
	) {
		diagnostics.report(
			targetKey(context), reason,
			() -> targetDescription(context),
			() -> diagnosticDetails(context, manager, visual, instances, detail), failure);
	}

	private static Object targetKey(EntityBlockGeometryContext context) {
		return context == null ? null : context.targetKey();
	}

	private static String targetDescription(EntityBlockGeometryContext context) {
		if (context == null || context.targetKey() == null) {
			return "<unknown-target>";
		}
		var key = context.targetKey();
		return "dimension=" + key.dimensionId() + "; position="
			+ key.x() + "," + key.y() + "," + key.z()
			+ "; blockRegistryId=" + key.blockRegistryId();
	}

	private static String diagnosticDetails(
		EntityBlockGeometryContext context,
		Object manager,
		Object visual,
		List<Instance> instances,
		Supplier<String> detail
	) {
		StringBuilder result = new StringBuilder();
		result.append(targetDescription(context));
		result.append("; blockEntityClass=").append(
			context == null ? "<null-context>" : className(context.blockEntity()));
		result.append("; manager=").append(identity(manager));
		result.append("; visual=").append(identity(visual));
		if (context != null) {
			result.append("; levelClass=").append(className(context.level()));
			result.append("; levelRendererClass=").append(className(context.levelRenderer()));
			result.append("; frameId=").append(context.frameId());
			result.append("; partialTick=").append(context.partialTick());
		}
		if (instances != null) {
			result.append("; instanceCount=").append(instances.size());
			for (int index = 0; index < instances.size(); index++) {
				result.append("; instance[").append(index).append("]=")
					.append(instanceDetails(instances.get(index)));
				result.append("; instance[").append(index).append("].model=")
					.append(modelDetails(instances.get(index)));
			}
		}
		if (detail != null) {
			String detailText = detail.get();
			if (detailText != null && !detailText.isEmpty()) {
				result.append("; ").append(detailText);
			}
		}
		return result.toString();
	}

	private static String instanceDetails(Instance instance) {
		if (instance == null) {
			return "<null>";
		}
		String type;
		try {
			type = String.valueOf(instance.type());
		} catch (Exception | LinkageError | AssertionError failure) {
			type = "<type-unavailable:" + failure + ">";
		}
		return className(instance) + "@" + Integer.toHexString(System.identityHashCode(instance))
			+ "{type=" + type + "; " + instanceStateDetails(instance) + "}";
	}

	private static String modelDetails(Instance instance) {
		if (instance == null) {
			return "<null-instance>";
		}
		try {
			LiveInstanceResolution resolution = resolveLiveInstance(instance);
			if (resolution == null) {
				return "<model-unavailable; " + instanceStateDetails(instance) + ">";
			}
			Model model = resolution.model();
			List<Model.ConfiguredMesh> meshes = model.meshes();
			StringBuilder result = new StringBuilder(identity(model))
				.append("{meshCount=").append(meshes == null ? "<null>" : meshes.size());
			if (meshes != null) {
				for (int index = 0; index < meshes.size(); index++) {
					Model.ConfiguredMesh configured = meshes.get(index);
					if (configured == null || configured.mesh() == null) {
						result.append("; mesh[").append(index).append("]=<null>");
						continue;
					}
					Material material = configured.material();
					ResourceLocation texture = material == null ? null : material.texture();
					result.append("; mesh[").append(index).append("]={vertices=")
						.append(configured.mesh().vertexCount())
						.append("; indices=").append(configured.mesh().indexCount())
						.append("; texture=").append(texture);
					if (material != null && texture != null) {
						result.append("; ").append(materialDetails(material, texture));
					}
					result.append("}");
				}
			}
			return result.append("}").toString();
		} catch (Exception | LinkageError | AssertionError failure) {
			return "<model-details-failed: " + failure + ">";
		}
	}

	private static String instanceStateDetails(Instance instance) {
		if (instance == null) {
			return "handleClass=<null>; backendStateClass=<null>; resolvedInstancerClass=<null>";
		}
		try {
			Object handleValue = instance.handle();
			if (!(handleValue instanceof InstanceHandleImpl<?> handle)) {
				return "handleClass=" + className(handleValue)
					+ "; backendStateClass=<not-InstanceHandleImpl>; resolvedInstancerClass=<null>";
			}
			InstanceHandleImpl.State<?> state = handle.state;
			AbstractInstancer<?> instancer = resolveLiveInstancer(state);
			return "handleClass=" + className(handleValue)
				+ "; backendStateClass=" + className(state)
				+ "; resolvedInstancerClass=" + className(instancer);
		} catch (Exception | LinkageError | AssertionError failure) {
			return "handleClass=<unavailable>; backendStateClass=<unavailable>; resolvedInstancerClass=<unavailable:"
				+ failure + ">";
		}
	}

	private static String identity(Object value) {
		return value == null ? "<null>" : className(value)
			+ "@" + Integer.toHexString(System.identityHashCode(value));
	}

	private static String className(Object value) {
		return value == null ? "<null>" : value.getClass().getName();
	}

	private static String materialDetails(Material material, ResourceLocation texture) {
		return "materialClass=" + className(material)
			+ "{texture=" + texture
			+ "; shaders=" + materialValue(material, "shaders", material::shaders)
			+ "; fog=" + materialValue(material, "fog", material::fog)
			+ "; cutout=" + materialValue(material, "cutout", material::cutout)
			+ "; light=" + materialValue(material, "light", material::light)
			+ "; blur=" + materialValue(material, "blur", material::blur)
			+ "; mipmap=" + materialValue(material, "mipmap", material::mipmap)
			+ "; backfaceCulling=" + materialValue(material, "backfaceCulling", material::backfaceCulling)
			+ "; polygonOffset=" + materialValue(material, "polygonOffset", material::polygonOffset)
			+ "; depthTest=" + materialValue(material, "depthTest", material::depthTest)
			+ "; transparency=" + materialValue(material, "transparency", material::transparency)
			+ "; writeMask=" + materialValue(material, "writeMask", material::writeMask)
			+ "; useOverlay=" + materialValue(material, "useOverlay", material::useOverlay)
			+ "; useLight=" + materialValue(material, "useLight", material::useLight)
			+ "; cardinalLightingMode=" + materialValue(
				material, "cardinalLightingMode", material::cardinalLightingMode)
			+ "; value=" + materialValue(material, "toString", material::toString) + "}";
	}

	private static String materialValue(Material material, String field, Supplier<?> supplier) {
		try {
			return String.valueOf(supplier.get());
		} catch (Exception | LinkageError | AssertionError failure) {
			return "<" + field + " unavailable: " + failure + ">";
		}
	}

	private record BlockEntityVisualLookup(
		BlockEntityVisual<?> visual,
		boolean storageUnavailable,
		Supplier<String> details
	) {}

	private record InstanceGeometry(Instance instance, ModelGeometry model) {}

	private record LiveInstanceResolution(AbstractInstancer<?> instancer, Model model) {}

	private record ResolvedInstance(
		Instance instance,
		AbstractInstancer<?> instancer,
		Model model
	) {}

	private static final class ModelGeometryCache {
		private final WeakIdentityCache<Model, ModelGeometry> entries = new WeakIdentityCache<>();

		private synchronized ModelGeometry get(Model model) throws GeometryFormatException {
			ModelGeometry cached = entries.get(model);
			if (cached != null) {
				return cached;
			}

			ModelGeometry extracted = extract(model);
			entries.put(model, extracted);
			return extracted;
		}

		private synchronized void clear() {
			entries.clear();
		}

		private static ModelGeometry extract(Model model) throws GeometryFormatException {
			List<Model.ConfiguredMesh> configuredMeshes;
			try {
				configuredMeshes = model.meshes();
			} catch (Exception | LinkageError | AssertionError failure) {
				throw new GeometryFormatException(
					FlywheelDiagnosticReason.MESH_EXTRACTION_FAILED,
					"model mesh list lookup failed", failure);
			}

			if (configuredMeshes == null || configuredMeshes.isEmpty()) {
				return ModelGeometry.EMPTY;
			}
			if (configuredMeshes.size() > FlywheelModelBudget.MAX_MESHES_PER_MODEL) {
				throw new GeometryFormatException(
					FlywheelDiagnosticReason.BUDGET_EXCEEDED,
					"meshCount=" + configuredMeshes.size());
			}

			List<FlywheelModelBudget.MeshCounts> counts = new ArrayList<>(configuredMeshes.size());
		List<MaterialInfo> materials = new ArrayList<>(configuredMeshes.size());
		for (int meshIndex = 0; meshIndex < configuredMeshes.size(); meshIndex++) {
			Model.ConfiguredMesh configured = configuredMeshes.get(meshIndex);
			if (configured == null || configured.mesh() == null) {
				throw new GeometryFormatException(
					FlywheelDiagnosticReason.MESH_EXTRACTION_FAILED,
					"configured mesh " + meshIndex + " or its mesh is null");
			}

			Material material = configured.material();
			if (material == null) {
				throw new GeometryFormatException(
					FlywheelDiagnosticReason.MATERIAL_INCOMPATIBLE,
					"configured mesh " + meshIndex + " has null material");
			}

			ResourceLocation texture;
			try {
				texture = material.texture();
			} catch (Exception | LinkageError | AssertionError failure) {
				throw new GeometryFormatException(
					FlywheelDiagnosticReason.MATERIAL_INCOMPATIBLE,
					"configured mesh " + meshIndex + " texture lookup failed", failure);
			}
			if (texture == null) {
				throw new GeometryFormatException(
					FlywheelDiagnosticReason.MATERIAL_INCOMPATIBLE,
					"configured mesh " + meshIndex + " has null material texture");
			}

			Mesh mesh = configured.mesh();
			counts.add(new FlywheelModelBudget.MeshCounts(mesh.vertexCount(), mesh.indexCount()));
			materials.add(new MaterialInfo(texture));
		}

		FlywheelModelBudget.Preflight preflight = FlywheelModelBudget.preflight(counts);
		if (!preflight.valid()) {
			throw new GeometryFormatException(
				FlywheelDiagnosticReason.BUDGET_EXCEEDED,
				"model preflight rejected meshCount=" + configuredMeshes.size());
		}

		List<MeshGeometry> meshes = new ArrayList<>(preflight.meshCount());
		for (int index = 0; index < configuredMeshes.size(); index++) {
			Model.ConfiguredMesh configured = configuredMeshes.get(index);
			MaterialInfo material = materials.get(index);
			meshes.add(extractMesh(configured.mesh(), material));
		}
		return new ModelGeometry(List.copyOf(meshes), preflight.triangleCount());
	}

		private static MeshGeometry extractMesh(Mesh mesh, MaterialInfo material)
			throws GeometryFormatException {
			int vertexCount = mesh.vertexCount();
			int indexCount = mesh.indexCount();
			if (vertexCount <= 0 || vertexCount > FlywheelModelBudget.MAX_VERTICES_PER_MESH
				|| indexCount <= 0 || indexCount > FlywheelModelBudget.MAX_INDICES_PER_MESH
				|| indexCount % 3 != 0) {
				throw new GeometryFormatException(
					FlywheelDiagnosticReason.MESH_EXTRACTION_FAILED,
					"invalid vertex/index counts: vertices=" + vertexCount + "; indices=" + indexCount);
			}

			long vertexBytes;
			long indexBytes;
			int positionComponents;
			int uvComponents;
			try {
				vertexBytes = Math.multiplyExact((long) vertexCount, FullVertexView.STRIDE);
				indexBytes = Math.multiplyExact((long) indexCount, Integer.BYTES);
				positionComponents = Math.toIntExact(Math.multiplyExact((long) vertexCount, 3L));
				uvComponents = Math.toIntExact(Math.multiplyExact((long) vertexCount, 2L));
			} catch (ArithmeticException overflow) {
				throw new GeometryFormatException(
					FlywheelDiagnosticReason.BUDGET_EXCEEDED,
					"mesh allocation size overflow", overflow);
			}

			MemoryBlock vertexMemory = null;
			MemoryBlock indexMemory = null;
			try {
				vertexMemory = MemoryBlock.malloc(vertexBytes);
				FullVertexView vertices = new FullVertexView();
				vertices.load(vertexMemory);
				// FullVertexView is required here: PosVertexView would leave UVs
				// unread and does not describe Flywheel's current vertex layout.
				mesh.write(vertices);

				float[] positions = new float[positionComponents];
				float[] uvs = new float[uvComponents];
				for (int vertex = 0; vertex < vertexCount; vertex++) {
					float x = vertices.x(vertex);
					float y = vertices.y(vertex);
					float z = vertices.z(vertex);
					float u = vertices.u(vertex);
					float v = vertices.v(vertex);
					if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
						|| !Float.isFinite(u) || !Float.isFinite(v)) {
						throw new GeometryFormatException(
							FlywheelDiagnosticReason.MESH_EXTRACTION_FAILED,
							"non-finite full vertex at index=" + vertex);
					}
					int positionOffset = vertex * 3;
					positions[positionOffset] = x;
					positions[positionOffset + 1] = y;
					positions[positionOffset + 2] = z;
					int uvOffset = vertex * 2;
					uvs[uvOffset] = u;
					uvs[uvOffset + 1] = v;
				}

				indexMemory = MemoryBlock.malloc(indexBytes);
				mesh.indexSequence().fill(indexMemory.ptr(), indexCount);
				int[] indices = new int[indexCount];
				for (int index = 0; index < indexCount; index++) {
					indices[index] = MemoryUtil.memGetInt(
						indexMemory.ptr() + (long) index * Integer.BYTES);
				}

				try {
					FlywheelMeshCache.Model<ResourceLocation> captured = FlywheelMeshCache.capture(
						List.of(new FlywheelMeshCache.Input<>(
							positions, uvs, indices, material.texture())));
					FlywheelMeshCache.Mesh<ResourceLocation> meshData = captured.meshes().get(0);
					return new MeshGeometry(
						meshData.positions(), meshData.uvs(), meshData.indices(), meshData.texture());
				} catch (IllegalArgumentException malformed) {
					throw new GeometryFormatException(
						FlywheelDiagnosticReason.MESH_EXTRACTION_FAILED,
						"captured mesh validation failed: " + malformed.getMessage(), malformed);
				}
			} catch (GeometryFormatException failure) {
				throw failure;
			} catch (Exception | LinkageError | AssertionError failure) {
				throw new GeometryFormatException(
					FlywheelDiagnosticReason.MESH_EXTRACTION_FAILED,
					"full vertex/index extraction failed", failure);
			} finally {
				if (indexMemory != null) {
					indexMemory.free();
				}
				if (vertexMemory != null) {
					vertexMemory.free();
				}
			}
		}
	}

	private record MaterialInfo(ResourceLocation texture) {}

	private record MeshGeometry(
		float[] positions,
		float[] uvs,
		int[] indices,
		ResourceLocation texture
	) {
		private int vertexCount() {
			return positions.length / 3;
		}

		private FlywheelTransformMath.Point point(int vertex) {
			int offset = vertex * 3;
			return new FlywheelTransformMath.Point(
				positions[offset], positions[offset + 1], positions[offset + 2]);
		}

		private float u(int vertex) {
			return uvs[vertex * 2];
		}

		private float v(int vertex) {
			return uvs[vertex * 2 + 1];
		}
	}

	private record ModelGeometry(List<MeshGeometry> meshes, int triangleCount) {
		private static final ModelGeometry EMPTY = new ModelGeometry(List.of(), 0);
	}

	private static final class GeometryFormatException extends Exception {
		private final FlywheelDiagnosticReason reason;

		private GeometryFormatException(FlywheelDiagnosticReason reason, String message) {
			super(message);
			this.reason = reason;
		}

		private FlywheelDiagnosticReason reason() {
			return reason;
		}

		private GeometryFormatException(
			FlywheelDiagnosticReason reason, String message, Throwable cause
		) {
			super(message, cause);
			this.reason = reason;
		}
	}

	private static final class MaskFrameBudget {
		private long frameId = Long.MIN_VALUE;
		private int triangles;

		private boolean reserve(long nextFrameId, int requested) {
			if (nextFrameId != frameId) {
				frameId = nextFrameId;
				triangles = 0;
			}
			if (!FlywheelModelBudget.trianglesWithinBudget(
				requested, FlywheelModelBudget.MAX_TRIANGLES_PER_FRAME)
				|| triangles > FlywheelModelBudget.MAX_TRIANGLES_PER_FRAME - requested) {
				return false;
			}
			triangles += requested;
			return true;
		}

		private void release(int count) {
			triangles = Math.max(0, triangles - count);
		}

		private int count() {
			return triangles;
		}
	}
}
}
