package nx.pingwheel.neoforge.integration.create;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.backend.engine.AbstractInstancer;
import dev.engine_room.flywheel.backend.engine.InstanceHandleImpl;
import dev.engine_room.flywheel.backend.engine.embed.GlobalEnvironment;
import dev.engine_room.flywheel.backend.mixin.LevelRendererAccessor;
import dev.engine_room.flywheel.impl.visualization.VisualManagerImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.vertex.PosVertexView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.Global;
import nx.pingwheel.common.client.outline.DeferredEntityBlockGeometryState;
import nx.pingwheel.common.client.outline.EntityBlockGeometryContext;
import nx.pingwheel.common.client.outline.EntityBlockGeometryLine;
import nx.pingwheel.common.client.outline.EntityBlockGeometryLineSink;
import nx.pingwheel.common.client.outline.EntityBlockGeometryOutcome;
import nx.pingwheel.common.client.outline.EntityBlockGeometrySource;
import nx.pingwheel.common.client.outline.FlywheelTransformMath;
import nx.pingwheel.common.client.outline.FlywheelModelBudget;
import nx.pingwheel.common.client.outline.WireframeEdgeExtractor;

import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.processing.burner.ScrollInstance;
import com.simibubi.create.content.processing.burner.ScrollTransformedInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import org.joml.Quaternionfc;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/**
 * NeoForge-only Create 6.0.10/Flywheel 1.0.6 wireframe source.
 *
 * <p>This class is loaded reflectively only after NeoForge has confirmed both
 * optional mod ids. It never retains a Flywheel visual, instance, transform,
 * or manager. A frame lookup obtains the live visual, collects its live
 * crumbling instances, and reads the exact model from each instance's
 * {@code AbstractInstancer.recreate.key.model}. Geometry is collected into a
 * common deferred sink and published only after every instance and mesh has
 * completed validation.</p>
 *
 * <p>The edge route intentionally retains triangle diagonals. Removing
 * coplanar diagonals would require a material/face topology policy that is not
 * part of the Flywheel model contract.</p>
 */
public final class CreateFlywheelGeometryAdapter {
	public static final String SOURCE_ID = "create:flywheel_wireframe";
	private static final int MAX_INSTANCES_PER_TARGET = 1_024;

	private static EntityBlockGeometrySourceRegistryHandle registration;

	private CreateFlywheelGeometryAdapter() {}

	/** Called reflectively after the optional-mod checks have passed. */
	public static synchronized void register() {
		if (registration == null) {
			registration = new EntityBlockGeometrySourceRegistryHandle(
				nx.pingwheel.common.client.outline.EntityBlockGeometrySourceRegistry.INSTANCE
					.register(new CreateFlywheelGeometrySource()));
		}
	}

	/** Called reflectively during client teardown; safe to call repeatedly. */
	public static synchronized void close() {
		EntityBlockGeometrySourceRegistryHandle retained = registration;
		try {
			if (retained != null) {
				retained.close();
			}
		} finally {
			// A teardown failure must not permanently suppress a later
			// registration attempt on the next connection.
			registration = null;
		}
	}

	/** Small wrapper keeps the registry type out of the source's hot path. */
	private static final class EntityBlockGeometrySourceRegistryHandle implements AutoCloseable {
		private final nx.pingwheel.common.client.outline.EntityBlockGeometrySourceRegistry.Registration handle;

		private EntityBlockGeometrySourceRegistryHandle(
			nx.pingwheel.common.client.outline.EntityBlockGeometrySourceRegistry.Registration handle) {
			this.handle = handle;
		}

		@Override
		public void close() {
			handle.close();
		}
	}

	private static final class CreateFlywheelGeometrySource implements EntityBlockGeometrySource {
		private final ModelEdgeCache edgeCache = new ModelEdgeCache();

		@Override
		public String id() {
			return SOURCE_ID;
		}

		@Override
		public EntityBlockGeometryOutcome attempt(EntityBlockGeometryContext context) {
			EntityBlockGeometryLineSink sink = context == null
				? EntityBlockGeometryLineSink.NOOP : context.lineSink();
			try {
				if (context == null || context.level() == null || context.blockPos() == null
					|| context.targetKey() == null || sink == EntityBlockGeometryLineSink.NOOP) {
					return EntityBlockGeometryOutcome.EMPTY;
				}

				VisualizationManagerImpl manager = VisualizationManagerImpl.get(context.level());
				if (manager == null) {
					return EntityBlockGeometryOutcome.EMPTY;
				}

				BlockEntityVisual<?> visual = liveVisualAt(manager, context.blockPos());
				if (visual == null) {
					return EntityBlockGeometryOutcome.EMPTY;
				}

				List<Instance> instances = collectLiveInstances(visual);
				if (instances.isEmpty()) {
					return EntityBlockGeometryOutcome.EMPTY;
				}
				if (instances.size() > MAX_INSTANCES_PER_TARGET) {
					return EntityBlockGeometryOutcome.FAILED;
				}

				Vec3i renderOrigin = manager.renderOrigin();
				Vec3 camera = context.cameraPosition();
				float originX = (float) (renderOrigin.getX() - camera.x);
				float originY = (float) (renderOrigin.getY() - camera.y);
				float originZ = (float) (renderOrigin.getZ() - camera.z);
				int ticks = context.levelRenderer() instanceof LevelRendererAccessor accessor
					? accessor.flywheel$getTicks() : (int) context.level().getGameTime();
				float renderTicks = ticks + context.partialTick();
				float renderSeconds = renderTicks / 20.0F;
				List<InstanceEdges> instanceEdges = new ArrayList<>(instances.size());
				long transformedSegmentCount = 0;

				for (Instance instance : instances) {
					if (!(instance.handle() instanceof InstanceHandleImpl<?> handle)
						|| !(handle.state instanceof AbstractInstancer<?> instancer)
						|| instancer.environment != GlobalEnvironment.INSTANCE
						|| instancer.recreate == null || instancer.recreate.key() == null
						|| instancer.recreate.key().model() == null) {
						return EntityBlockGeometryOutcome.FAILED;
					}
					if (!supportedInstanceType(instance)) {
						return EntityBlockGeometryOutcome.FAILED;
					}

					Model model = instancer.recreate.key().model();
					ModelEdges edges = edgeCache.get(model);
					try {
						transformedSegmentCount = Math.addExact(
							transformedSegmentCount, edges.lineCount());
					} catch (ArithmeticException overflow) {
						return EntityBlockGeometryOutcome.FAILED;
					}
					if (transformedSegmentCount > DeferredEntityBlockGeometryState.MAX_LINES_PER_TARGET) {
						// Preflight the complete target before allocating any transformed
						// vertex arrays or the output line list.
						return EntityBlockGeometryOutcome.FAILED;
					}
					instanceEdges.add(new InstanceEdges(instance, edges));
				}

				if (transformedSegmentCount == 0) {
					return EntityBlockGeometryOutcome.EMPTY;
				}

				int lineCapacity;
				try {
					lineCapacity = Math.toIntExact(transformedSegmentCount);
				} catch (ArithmeticException overflow) {
					return EntityBlockGeometryOutcome.FAILED;
				}
				List<EntityBlockGeometryLine> lines = new ArrayList<>(lineCapacity);

				for (InstanceEdges entry : instanceEdges) {
					appendInstanceLines(
						lines, entry.edges(), entry.instance(), originX, originY, originZ,
						renderTicks, renderSeconds);
				}

				if (lines.isEmpty()) {
					return EntityBlockGeometryOutcome.EMPTY;
				}

				for (EntityBlockGeometryLine line : lines) {
					if (!sink.addLine(line.x0(), line.y0(), line.z0(),
						line.x1(), line.y1(), line.z1())) {
						sink.abort();
						return EntityBlockGeometryOutcome.FAILED;
					}
				}

				return sink.commit()
					? EntityBlockGeometryOutcome.RENDERED
					: EntityBlockGeometryOutcome.FAILED;
			} catch (Exception | LinkageError | AssertionError failure) {
				sink.abort();
				Global.warnException("create flywheel geometry source failed; category=attempt", failure);
				return EntityBlockGeometryOutcome.FAILED;
			}
		}

		private static BlockEntityVisual<?> liveVisualAt(VisualizationManagerImpl manager, BlockPos pos) {
			if (!(manager.blockEntities() instanceof VisualManagerImpl<?, ?> visualManager)
				|| !(visualManager.getStorage() instanceof BlockEntityStorage storage)) {
				return null;
			}
			return storage.visualAtPos(pos.asLong());
		}

		private static List<Instance> collectLiveInstances(BlockEntityVisual<?> visual) {
			// Keep one sentinel beyond the limit so the caller can distinguish an
			// over-limit visual without allowing a corrupt visual to force an
			// unbounded Java list allocation.
			List<Instance> instances = new ArrayList<>(MAX_INSTANCES_PER_TARGET + 1);
			visual.collectCrumblingInstances(instance -> {
				if (instance == null || !(instance.handle() instanceof InstanceHandleImpl<?> handle)
					|| !(handle.state instanceof AbstractInstancer<?>)) {
					return;
				}
				if (instances.size() <= MAX_INSTANCES_PER_TARGET) {
					instances.add(instance);
				}
			});
			return instances;
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

		private static void appendInstanceLines(
			List<EntityBlockGeometryLine> output,
			ModelEdges model,
			Instance instance,
			float originX,
			float originY,
			float originZ,
			float renderTicks,
			float renderSeconds
		) throws GeometryFormatException {
			for (MeshEdges mesh : model.meshes()) {
				FlywheelTransformMath.Point[] transformed = new FlywheelTransformMath.Point[mesh.vertexCount()];
				for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
					FlywheelTransformMath.Point local = mesh.point(vertex);
					transformed[vertex] = transformPoint(local, instance, renderTicks, renderSeconds);
					if (!finite(transformed[vertex])) {
						throw new GeometryFormatException();
					}
				}

				for (WireframeEdgeExtractor.Edge edge : mesh.edges()) {
					FlywheelTransformMath.Point first = transformed[edge.first()];
					FlywheelTransformMath.Point second = transformed[edge.second()];
					EntityBlockGeometryLine line = new EntityBlockGeometryLine(
						first.x() + originX, first.y() + originY, first.z() + originZ,
						second.x() + originX, second.y() + originY, second.z() + originZ);
					if (!line.isFiniteNonZero()) {
						throw new GeometryFormatException();
					}
					output.add(line);
					if (output.size() > DeferredEntityBlockGeometryState.MAX_LINES_PER_TARGET) {
						throw new GeometryFormatException();
					}
				}
			}
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
				// The exact scroll formula changes UVs only; the position route is
				// the same centered quaternion transform used by scrolling.vert.
				FlywheelTransformMath.scrollOffset(
					scrolling.speedU, scrolling.offsetU, scrolling.scaleU, renderTicks);
				return FlywheelTransformMath.scrolling(
					point,
					new FlywheelTransformMath.Point(scrolling.x, scrolling.y, scrolling.z),
					quaternion(scrolling.rotation));
			}

			if (type == AllInstanceTypes.SCROLLING_TRANSFORMED
				&& instance instanceof ScrollTransformedInstance scrollingTransformed) {
				FlywheelTransformMath.scrollOffset(
					scrollingTransformed.speedU, scrollingTransformed.offsetU,
					scrollingTransformed.scaleU, renderTicks);
				return FlywheelTransformMath.scrollingTransformed(
					point, matrix(scrollingTransformed.pose));
			}

			throw new GeometryFormatException();
		}

		private static float decodeAxis(byte value) {
			// Flywheel stores normalized components in a signed byte.  -128 is
			// outside the representable [-1, 1] / 127 range; clamp it rather than
			// allowing corrupt data to escape into the transform.
			return Math.max(-1.0F, value / 127.0F);
		}

		private static FlywheelTransformMath.Quaternion quaternion(Quaternionfc quaternion) {
			return new FlywheelTransformMath.Quaternion(
				quaternion.x(), quaternion.y(), quaternion.z(), quaternion.w());
		}

		private static FlywheelTransformMath.Matrix4 matrix(Matrix4fc matrix) {
			return new FlywheelTransformMath.Matrix4(
				matrix.m00(), matrix.m01(), matrix.m02(), matrix.m03(),
				matrix.m10(), matrix.m11(), matrix.m12(), matrix.m13(),
				matrix.m20(), matrix.m21(), matrix.m22(), matrix.m23(),
				matrix.m30(), matrix.m31(), matrix.m32(), matrix.m33());
		}

		private static boolean finite(FlywheelTransformMath.Point point) {
			return Float.isFinite(point.x()) && Float.isFinite(point.y()) && Float.isFinite(point.z());
		}

		private record InstanceEdges(Instance instance, ModelEdges edges) {}
	}

	private static final class ModelEdgeCache {
		private final ReferenceQueue<Model> queue = new ReferenceQueue<>();
		private final List<CacheEntry> entries = new ArrayList<>();

		private synchronized ModelEdges get(Model model) throws GeometryFormatException {
			purge();
			for (CacheEntry entry : entries) {
				if (entry.get() == model) {
					return entry.edges;
				}
			}

			ModelEdges extracted = extract(model);
			entries.add(new CacheEntry(model, queue, extracted));
			return extracted;
		}

		private void purge() {
			CacheEntry cleared;
			while ((cleared = (CacheEntry) queue.poll()) != null) {
				entries.remove(cleared);
			}
			entries.removeIf(entry -> entry.get() == null);
		}

		private static ModelEdges extract(Model model) throws GeometryFormatException {
			List<Model.ConfiguredMesh> configuredMeshes = model.meshes();
			if (configuredMeshes == null || configuredMeshes.isEmpty()) {
				return ModelEdges.EMPTY;
			}
			if (configuredMeshes.size() > FlywheelModelBudget.MAX_MESHES_PER_MODEL) {
				throw new GeometryFormatException();
			}

			// Read only scalar mesh counts first.  This whole-model preflight must
			// succeed before extractMesh allocates native memory or Java arrays.
			List<FlywheelModelBudget.MeshCounts> counts =
				new ArrayList<>(configuredMeshes.size());
			for (Model.ConfiguredMesh configured : configuredMeshes) {
				if (configured == null || configured.mesh() == null) {
					throw new GeometryFormatException();
				}
				Mesh mesh = configured.mesh();
				counts.add(new FlywheelModelBudget.MeshCounts(
					mesh.vertexCount(), mesh.indexCount()));
			}

			FlywheelModelBudget.Preflight preflight = FlywheelModelBudget.preflight(counts);
			if (!preflight.valid()) {
				throw new GeometryFormatException();
			}

			List<MeshEdges> meshes = new ArrayList<>(preflight.meshCount());
			long totalLines = 0;
			for (Model.ConfiguredMesh configured : configuredMeshes) {
				Mesh mesh = configured.mesh();
				MeshEdges meshEdges = extractMesh(mesh);
				meshes.add(meshEdges);
				try {
					totalLines = Math.addExact(totalLines, meshEdges.edges().size());
				} catch (ArithmeticException overflow) {
					throw new GeometryFormatException();
				}
				if (!FlywheelModelBudget.edgesWithinModelBudget(totalLines)) {
					throw new GeometryFormatException();
				}
			}

			try {
				return new ModelEdges(List.copyOf(meshes), Math.toIntExact(totalLines));
			} catch (ArithmeticException overflow) {
				throw new GeometryFormatException();
			}
		}

		private static MeshEdges extractMesh(Mesh mesh) throws GeometryFormatException {
			int vertexCount = mesh.vertexCount();
			int indexCount = mesh.indexCount();
			if (vertexCount <= 0 || vertexCount > FlywheelModelBudget.MAX_VERTICES_PER_MESH
				|| indexCount <= 0 || indexCount > FlywheelModelBudget.MAX_INDICES_PER_MESH
				|| indexCount % 3 != 0) {
				throw new GeometryFormatException();
			}

			long vertexBytes;
			long indexBytes;
			int positionComponents;
			try {
				vertexBytes = Math.multiplyExact((long) vertexCount, PosVertexView.STRIDE);
				indexBytes = Math.multiplyExact((long) indexCount, Integer.BYTES);
				positionComponents = Math.toIntExact(Math.multiplyExact((long) vertexCount, 3L));
			} catch (ArithmeticException overflow) {
				throw new GeometryFormatException();
			}

			MemoryBlock vertexMemory = null;
			MemoryBlock indexMemory = null;
			try {
				vertexMemory = MemoryBlock.malloc(vertexBytes);
				PosVertexView positions = new PosVertexView();
				positions.load(vertexMemory);
				mesh.write(positions);
				float[] captured = new float[positionComponents];
				for (int i = 0; i < vertexCount; i++) {
					float x = positions.x(i);
					float y = positions.y(i);
					float z = positions.z(i);
					if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
						throw new GeometryFormatException();
					}
					int offset = Math.multiplyExact(i, 3);
					captured[offset] = x;
					captured[offset + 1] = y;
					captured[offset + 2] = z;
				}

				indexMemory = MemoryBlock.malloc(indexBytes);
				mesh.indexSequence().fill(indexMemory.ptr(), indexCount);
				int[] indices = new int[indexCount];
				for (int i = 0; i < indexCount; i++) {
					int index = MemoryUtil.memGetInt(indexMemory.ptr() + (long) i * Integer.BYTES);
					indices[i] = index;
				}

				WireframeEdgeExtractor.Extraction extraction = WireframeEdgeExtractor.extract(
					captured, indices, FlywheelModelBudget.MAX_EDGES_PER_MODEL);
				if (!extraction.valid()) {
					throw new GeometryFormatException();
				}

				return new MeshEdges(captured, extraction.edges());
			} catch (Exception | LinkageError | AssertionError failure) {
				if (failure instanceof GeometryFormatException format) {
					throw format;
				}
				throw new GeometryFormatException();
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

	private static final class CacheEntry extends WeakReference<Model> {
		private final ModelEdges edges;

		private CacheEntry(Model model, ReferenceQueue<Model> queue, ModelEdges edges) {
			super(model, queue);
			this.edges = edges;
		}
	}

	private record ModelEdges(List<MeshEdges> meshes, int lineCount) {
		private static final ModelEdges EMPTY = new ModelEdges(List.of(), 0);
	}

	private record MeshEdges(float[] positions, List<WireframeEdgeExtractor.Edge> edges) {
		private int vertexCount() {
			return positions.length / 3;
		}

		private FlywheelTransformMath.Point point(int vertex) {
			int offset = vertex * 3;
			return new FlywheelTransformMath.Point(
				positions[offset], positions[offset + 1], positions[offset + 2]);
		}
	}

	private static final class GeometryFormatException extends Exception {
		private GeometryFormatException() {
			super();
		}
	}

}
