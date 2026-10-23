package nx.pingwheel.neoforge.integration.create;

import java.util.Objects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import nx.pingwheel.common.Global;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;
import nx.pingwheel.common.client.outline.AabbOutlineMask;
import nx.pingwheel.common.client.outline.EntityBlockGeometryOutcome;
import nx.pingwheel.common.client.outline.EntityOutlineContext;
import nx.pingwheel.common.client.outline.EntityOutlineSource;
import nx.pingwheel.common.client.outline.EntityOutlineSourceRegistry;
import nx.pingwheel.common.client.outline.OutlineOnlyBufferSource;
import nx.pingwheel.common.math.EntitySelectionBlacklist;

/**
 * NeoForge-only Create entity silhouette source.
 *
 * <p>This class deliberately contains the optional Create references at the
 * integration boundary and probes Flywheel through a cached reflective method.
 * {@link nx.pingwheel.neoforge.NeoClient} only resolves it after the Create
 * mod-id check, so the ordinary client path can load without either optional
 * jar.</p>
 */
public final class CreateEntityOutlineAdapter {
	public static final String SOURCE_ID = "pingforit:create_entity_outline";

	private static final int MAX_RENDER_VERTICES = 262_144;
	private static final int EXPECTED_QUADS = 6;
	private static final int EXPECTED_VERTICES = 24;
	private static final String FLYWHEEL_VISUALIZATION_MANAGER =
		"dev.engine_room.flywheel.api.visualization.VisualizationManager";
	private static final String SUPPORTS_VISUALIZATION_METHOD = "supportsVisualization";
	private static final RenderType SUPER_GLUE_OUTLINE = RenderType.outline(
		ResourceLocation.fromNamespaceAndPath("create", "textures/special/glue.png"));
	private static final CreateEntityOutlineBackendProbe BACKEND_PROBE =
		new CreateEntityOutlineBackendProbe(FLYWHEEL_VISUALIZATION_MANAGER, SUPPORTS_VISUALIZATION_METHOD);

	private static @Nullable EntityOutlineSourceRegistry.Registration registration;
	private static @Nullable EntitySelectionBlacklist.Registration selectionBlacklistRegistration;
	private static @Nullable CreateEntityOutlineSource source;

	private CreateEntityOutlineAdapter() {}

	/** Called reflectively after NeoForge has confirmed that Create is loaded. */
	public static synchronized void register() {
		if (registration != null) {
			return;
		}

		CreateEntityOutlineSource candidate = new CreateEntityOutlineSource();
		@Nullable EntityOutlineSourceRegistry.Registration outlineHandle = null;
		@Nullable EntitySelectionBlacklist.Registration blacklistHandle = null;
		try {
			outlineHandle = EntityOutlineSourceRegistry.INSTANCE.register(candidate);
			blacklistHandle = EntitySelectionBlacklist.INSTANCE.register(
				entity -> entity instanceof SuperGlueEntity);
			source = candidate;
			registration = outlineHandle;
			selectionBlacklistRegistration = blacklistHandle;
		} catch (RuntimeException | Error failure) {
			try {
				if (blacklistHandle != null) {
					blacklistHandle.close();
				}
			} finally {
				try {
					if (outlineHandle != null) {
						outlineHandle.close();
					}
				} finally {
					candidate.close();
				}
			}
			throw failure;
		}

		if (outlineHandle.accepted()) {
			Global.LOGGER.info("Create entity outline source registration succeeded: id={} handleState={}",
				SOURCE_ID, registrationState());
		} else {
			Diagnostics.event(Level.WARN, "create-entity-register", SOURCE_ID, "registration-rejected",
				() -> "handleState=" + registrationState());
		}
	}

	/** Called reflectively at client teardown; safe to call repeatedly. */
	public static synchronized void close() {
		@Nullable EntitySelectionBlacklist.Registration retainedBlacklistRegistration = selectionBlacklistRegistration;
		@Nullable EntityOutlineSourceRegistry.Registration retainedRegistration = registration;
		@Nullable CreateEntityOutlineSource retainedSource = source;
		try {
			if (retainedBlacklistRegistration != null) {
				retainedBlacklistRegistration.close();
			}
		} finally {
			try {
				if (retainedRegistration != null) {
					retainedRegistration.close();
				}
			} finally {
				try {
					if (retainedSource != null) {
						retainedSource.close();
					}
				} finally {
					selectionBlacklistRegistration = null;
					registration = null;
					source = null;
				}
			}
		}
		Global.LOGGER.info("Create entity outline source registration closed: id={} handleState=closed", SOURCE_ID);
	}

	/** Reflection-only lifecycle probe used by NeoForge transition diagnostics. */
	public static synchronized String registrationState() {
		if (registration == null) {
			return "not-registered";
		}
		return registration.accepted() ? "registered" : "rejected";
	}


	private static final class CreateEntityOutlineSource implements EntityOutlineSource {
		private final EntityDiagnostics diagnostics = new EntityDiagnostics();

		@Override
		public String id() {
			return SOURCE_ID;
		}

		@Override
		public boolean handles(Entity entity) {
			if (entity == null) {
				return false;
			}

			if (entity instanceof SuperGlueEntity) {
				return true;
			}

			if (!(entity instanceof AbstractContraptionEntity) && !(entity instanceof PackageEntity)) {
				return false;
			}

			if (CreateEntityOutlineMaskScope.active()) {
				diagnostics.report(entity, "backend-check-skipped-in-mask-scope", "unknown", true, 0, null,
					EntityBlockGeometryOutcome.EMPTY);
				return false;
			}

			try {
				CreateEntityOutlineBackendProbe.Evaluation backend = BACKEND_PROBE.evaluate(entity.level());
				if (!backend.available()) {
					diagnostics.report(entity, "backend-unavailable", backend.description(), false, 0, null,
						EntityBlockGeometryOutcome.EMPTY);
					return false;
				}
				if (!backend.enabled()) {
					diagnostics.report(entity, "backend-off", "off", false, 0, null,
						EntityBlockGeometryOutcome.EMPTY);
				}
				return backend.enabled();
			} catch (Exception | LinkageError | AssertionError failure) {
				diagnostics.report(entity, "backend-check-failed", "unknown", false, 0, failure,
					EntityBlockGeometryOutcome.FAILED);
				return false;
			}
		}

		@Override
		public EntityBlockGeometryOutcome attempt(EntityOutlineContext context) {
			if (context == null || context.entity() == null) {
				return EntityBlockGeometryOutcome.FAILED;
			}

			Entity entity = context.entity();
			if (entity instanceof SuperGlueEntity) {
				return renderSuperGlue(context);
			}

			if (entity instanceof AbstractContraptionEntity || entity instanceof PackageEntity) {
				return renderCreateEntity(context);
			}

			return EntityBlockGeometryOutcome.EMPTY;
		}

		private EntityBlockGeometryOutcome renderCreateEntity(EntityOutlineContext context) {
			Entity entity = context.entity();
			if (CreateEntityOutlineMaskScope.active()) {
				diagnostics.report(entity, "renderer-entry-already-in-scope", "unknown", true, 0, null,
					EntityBlockGeometryOutcome.FAILED);
				return EntityBlockGeometryOutcome.FAILED;
			}

			final CreateEntityOutlineBackendProbe.Evaluation backend;
			try {
				backend = BACKEND_PROBE.evaluate(entity.level());
			} catch (Exception | LinkageError | AssertionError failure) {
				diagnostics.report(entity, "backend-check-failed", "unknown", false, 0, failure,
					EntityBlockGeometryOutcome.FAILED);
				return EntityBlockGeometryOutcome.FAILED;
			}

			if (!backend.available()) {
				diagnostics.report(entity, "backend-unavailable", backend.description(), false, 0, null,
					EntityBlockGeometryOutcome.EMPTY);
				return EntityBlockGeometryOutcome.EMPTY;
			}

			if (!backend.enabled()) {
				diagnostics.report(entity, "backend-off", "off", false, 0, null,
					EntityBlockGeometryOutcome.EMPTY);
				return EntityBlockGeometryOutcome.EMPTY;
			}

			OutlineBufferSource shared = context.outlineBuffer();
			if (shared == null) {
				diagnostics.report(entity, "outline-buffer-unavailable", "on", false, 0, null,
					EntityBlockGeometryOutcome.FAILED);
				return EntityBlockGeometryOutcome.FAILED;
			}

			OutlineOnlyBufferSource outlineOnly = null;
			try {
				int color = opaqueColor(context);
				shared.setColor(red(color), green(color), blue(color), 255);
				outlineOnly = new OutlineOnlyBufferSource(
					shared, color, TextureAtlas.LOCATION_BLOCKS, MAX_RENDER_VERTICES);

				Minecraft minecraft = Objects.requireNonNull(Minecraft.getInstance(), "minecraft");
				EntityRenderDispatcher dispatcher = Objects.requireNonNull(
					minecraft.getEntityRenderDispatcher(), "entityRenderDispatcher");
				Vec3 camera = context.cameraPosition();
				double x = Mth.lerp(context.partialTick(), entity.xo, entity.getX()) - camera.x;
				double y = Mth.lerp(context.partialTick(), entity.yo, entity.getY()) - camera.y;
				double z = Mth.lerp(context.partialTick(), entity.zo, entity.getZ()) - camera.z;
				float yaw = Mth.lerp(context.partialTick(), entity.yRotO, entity.getYRot());
				if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw)) {
					diagnostics.report(entity, "nonfinite-interpolated-position", "on", true,
						outlineOnly.vertexCount(), null, EntityBlockGeometryOutcome.FAILED);
					return EntityBlockGeometryOutcome.FAILED;
				}

				try (CreateEntityOutlineMaskScope.Scope ignored = CreateEntityOutlineMaskScope.enter()) {
					PoseStack poseStack = new PoseStack();
					dispatcher.render(
						entity,
						x,
						y,
						z,
						yaw,
						context.partialTick(),
						poseStack,
						outlineOnly,
						dispatcher.getPackedLightCoords(entity, context.partialTick()));
				}

				int vertices = outlineOnly.vertexCount();
				if (vertices > 0) {
					diagnostics.report(entity, "rendered", "on", true, vertices, null,
						EntityBlockGeometryOutcome.RENDERED);
					return EntityBlockGeometryOutcome.RENDERED;
				}

				diagnostics.report(entity, "renderer-emitted-zero-vertices", "on", true, 0, null,
					EntityBlockGeometryOutcome.EMPTY);
				return EntityBlockGeometryOutcome.EMPTY;
			} catch (Exception | LinkageError | AssertionError failure) {
				int vertices = outlineOnly == null ? 0 : outlineOnly.vertexCount();
				if (vertices > 0) {
					diagnostics.report(entity, "partial-render-exception", "on", true, vertices, failure,
						EntityBlockGeometryOutcome.RENDERED);
					return EntityBlockGeometryOutcome.RENDERED;
				}

				diagnostics.report(entity, "renderer-failed-before-vertices", "on", true, 0, failure,
					EntityBlockGeometryOutcome.FAILED);
				return EntityBlockGeometryOutcome.FAILED;
			}
		}

		private EntityBlockGeometryOutcome renderSuperGlue(EntityOutlineContext context) {
			Entity entity = context.entity();
			OutlineBufferSource shared = context.outlineBuffer();
			if (shared == null) {
				diagnostics.report(entity, "outline-buffer-unavailable", "not-required", false, 0, null,
					EntityBlockGeometryOutcome.FAILED);
				return EntityBlockGeometryOutcome.FAILED;
			}

			Vec3 camera = context.cameraPosition();
			if (!finite(camera)) {
				diagnostics.report(entity, "nonfinite-camera", "not-required", false, 0, null,
					EntityBlockGeometryOutcome.FAILED);
				return EntityBlockGeometryOutcome.FAILED;
			}

			final AabbOutlineMask mask;
			try {
				mask = AabbOutlineMask.cameraRelative(entity.getBoundingBox(), camera);
				if (!finite(mask)) {
					throw new IllegalArgumentException("camera-relative AABB mask is non-finite");
				}
			} catch (Exception | LinkageError | AssertionError failure) {
				diagnostics.report(entity, "nonfinite-or-empty-aabb", "not-required", false, 0, failure,
					EntityBlockGeometryOutcome.FAILED);
				return EntityBlockGeometryOutcome.FAILED;
			}

			if (mask.quads().size() != EXPECTED_QUADS) {
				diagnostics.report(entity, "glue-mask-quad-count-mismatch", "not-required", false, 0, null,
					EntityBlockGeometryOutcome.FAILED);
				return EntityBlockGeometryOutcome.FAILED;
			}

			int color = opaqueColor(context);
			VertexCounter counter = new VertexCounter();
			try {
				shared.setColor(red(color), green(color), blue(color), 255);
				VertexConsumer consumer = shared.getBuffer(SUPER_GLUE_OUTLINE);
				for (AabbOutlineMask.Quad quad : mask.quads()) {
					emitQuad(consumer, quad, color, counter);
				}

				if (counter.count != EXPECTED_VERTICES) {
					diagnostics.report(entity, "glue-mask-vertex-count-mismatch", "not-required", false,
						counter.count, null, counter.count > 0
							? EntityBlockGeometryOutcome.RENDERED : EntityBlockGeometryOutcome.FAILED);
					return counter.count > 0
						? EntityBlockGeometryOutcome.RENDERED : EntityBlockGeometryOutcome.FAILED;
				}
			} catch (Exception | LinkageError | AssertionError failure) {
				if (counter.count > 0) {
					diagnostics.report(entity, "partial-glue-render-exception", "not-required", false,
						counter.count, failure, EntityBlockGeometryOutcome.RENDERED);
					return EntityBlockGeometryOutcome.RENDERED;
				}

				diagnostics.report(entity, "glue-render-failed-before-vertices", "not-required", false,
					0, failure, EntityBlockGeometryOutcome.FAILED);
				return EntityBlockGeometryOutcome.FAILED;
			}

			if (counter.count > 0) {
				diagnostics.report(entity, "rendered-glue-aabb", "not-required", false,
					counter.count, null, EntityBlockGeometryOutcome.RENDERED);
				return EntityBlockGeometryOutcome.RENDERED;
			}

			diagnostics.report(entity, "glue-mask-emitted-zero-vertices", "not-required", false,
				0, null, EntityBlockGeometryOutcome.EMPTY);
			return EntityBlockGeometryOutcome.EMPTY;
		}

		private static boolean finite(Vec3 vector) {
			return vector != null && Double.isFinite(vector.x)
				&& Double.isFinite(vector.y) && Double.isFinite(vector.z);
		}

		private static boolean finite(AabbOutlineMask mask) {
			for (AabbOutlineMask.Quad quad : mask.quads()) {
				if (!Float.isFinite(quad.x0()) || !Float.isFinite(quad.y0()) || !Float.isFinite(quad.z0())
					|| !Float.isFinite(quad.x1()) || !Float.isFinite(quad.y1()) || !Float.isFinite(quad.z1())
					|| !Float.isFinite(quad.x2()) || !Float.isFinite(quad.y2()) || !Float.isFinite(quad.z2())
					|| !Float.isFinite(quad.x3()) || !Float.isFinite(quad.y3()) || !Float.isFinite(quad.z3())) {
					return false;
				}
			}
			return true;
		}

		private static void emitQuad(
			VertexConsumer consumer,
			AabbOutlineMask.Quad quad,
			int color,
			VertexCounter counter
		) {
			emitVertex(consumer, quad.x0(), quad.y0(), quad.z0(), 0.0F, 0.0F, color, counter);
			emitVertex(consumer, quad.x1(), quad.y1(), quad.z1(), 1.0F, 0.0F, color, counter);
			emitVertex(consumer, quad.x2(), quad.y2(), quad.z2(), 1.0F, 1.0F, color, counter);
			emitVertex(consumer, quad.x3(), quad.y3(), quad.z3(), 0.0F, 1.0F, color, counter);
		}

		private static void emitVertex(
			VertexConsumer consumer,
			float x,
			float y,
			float z,
			float u,
			float v,
			int color,
			VertexCounter counter
		) {
			VertexConsumer vertex = consumer.addVertex(x, y, z);
			counter.count++;
			vertex
				.setColor(red(color), green(color), blue(color), 255)
				.setUv(u, v);
		}

		private static int opaqueColor(EntityOutlineContext context) {
			return 0xFF000000 | (context.spec().argbColor() & 0x00FFFFFF);
		}

		private static int red(int color) {
			return (color >> 16) & 0xFF;
		}

		private static int green(int color) {
			return (color >> 8) & 0xFF;
		}

		private static int blue(int color) {
			return color & 0xFF;
		}

		private void close() {
			diagnostics.clear();
		}
	}

	private static final class VertexCounter {
		private int count;
	}

	/**
	 * Bounded, lazy-detail diagnostics for the optional entity route.  The
	 * state is intentionally small and only the first event, reason changes,
	 * recovery, and a five-second heartbeat construct the complete descriptor
	 * and exception stack trace.
	 */
	private static final class EntityDiagnostics {
		private final CreateEntityOutlineDiagnosticGate gate = new CreateEntityOutlineDiagnosticGate(System::nanoTime);

		private void report(
			@Nullable Entity entity,
			String reason,
			String backend,
			boolean scope,
			int vertexCount,
			@Nullable Throwable failure,
			@Nullable EntityBlockGeometryOutcome outcome
		) {
			String key = entityKey(entity);
			String result = outcome == null ? "UNKNOWN" : outcome.name();
			Level level = failure != null || outcome == EntityBlockGeometryOutcome.FAILED
				|| (reason.equals("backend-unavailable") && !backend.equals("class-missing")) || reason.equals("outline-buffer-unavailable")
				|| reason.startsWith("nonfinite") || reason.contains("mismatch")
				? Level.WARN : Level.DEBUG;
			boolean enabled;
			try {
				enabled = Diagnostics.logger().isEnabled(level);
			} catch (RuntimeException | LinkageError ignored) {
				enabled = false;
			}
			if (!gate.admit(key, reason, result, enabled, level == Level.DEBUG)) {
				return;
			}

			if (failure == null) {
				Diagnostics.domainApprovedEvent(level, "create-entity-outline", SOURCE_ID, reason,
					() -> fullDetails(entity, reason, result, backend, scope, vertexCount));
			} else {
				Diagnostics.domainApprovedFailure(level, "create-entity-outline", SOURCE_ID,
					() -> fullDetails(entity, reason, result, backend, scope, vertexCount), failure);
			}
		}

		private void clear() {
			gate.clear();
		}

		private static String entityKey(@Nullable Entity entity) {
			if (entity == null) {
				return "<null>";
			}
			try {
				return String.valueOf(entity.getUUID());
			} catch (Exception | LinkageError | AssertionError failure) {
				return entity.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(entity));
			}
		}

		private static String fullDetails(
			@Nullable Entity entity,
			String reason,
			String outcome,
			String backend,
			boolean scope,
			int vertexCount
		) {
			StringBuilder details = new StringBuilder()
				.append("sourceId=").append(SOURCE_ID)
				.append("; reason=").append(reason)
				.append("; outcome=").append(outcome)
				.append("; backend=").append(backend)
				.append("; backendProbeState=").append(BACKEND_PROBE.diagnosticDescription())
				.append("; scope=").append(scope)
				.append("; vertexCount=").append(vertexCount);

			if (entity == null) {
				return details.append("; entity=<null>").toString();
			}

			try {
				Vec3 position = entity.position();
				details.append("; entityType=").append(entity.getType())
					.append("; entityId=").append(entity.getId())
					.append("; entityUuid=").append(entity.getUUID())
					.append("; position=").append(position)
					.append("; class=").append(entity.getClass().getName());
			} catch (Exception | LinkageError | AssertionError descriptorFailure) {
				details.append("; entityDescriptorFailure=").append(SafeExceptionReport.format(descriptorFailure));
			}

			return details.toString();
		}

	}
}
