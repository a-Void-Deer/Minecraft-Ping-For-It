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

import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
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
	private static final DiagnosticLogger LOG = Diagnostics.client();
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

	private static final CreateRegistrationLifecycle<Registration> LIFECYCLE = new CreateRegistrationLifecycle<>();

	private CreateEntityOutlineAdapter() {}

	/** Called reflectively after NeoForge has confirmed that Create is loaded. */
	public static void register() {
		@Nullable Registration installed = LIFECYCLE.register(CreateEntityOutlineAdapter::install);
		if (installed == null) return;
		String state = installed.state();
		if (installed.outline.accepted()) {
			LOG.atNormal().info("Create entity outline source registration succeeded: id={} handleState={}",
				nx.pingwheel.common.diagnostics.SafeDiagnosticValue.code(SOURCE_ID),
				nx.pingwheel.common.diagnostics.SafeDiagnosticValue.code(state));
		} else {
			LOG.atNormal().event(Level.WARN, "create-entity-register", "registration-rejected",
				() -> "handleState=" + state);
		}
	}

	private static Registration install() {
		CreateEntityOutlineSource candidate = new CreateEntityOutlineSource();
		@Nullable EntityOutlineSourceRegistry.Registration outlineHandle = null;
		@Nullable EntitySelectionBlacklist.Registration blacklistHandle = null;
		try {
			outlineHandle = EntityOutlineSourceRegistry.INSTANCE.register(candidate);
			blacklistHandle = EntitySelectionBlacklist.INSTANCE.register(
				entity -> entity instanceof SuperGlueEntity);
			return new Registration(outlineHandle, blacklistHandle, candidate);
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
	}

	/** Called reflectively at client teardown; safe to call repeatedly. */
	public static void close() {
		LIFECYCLE.close();
		LOG.atNormal().info("Create entity outline source registration closed: id={} handleState=closed",
			nx.pingwheel.common.diagnostics.SafeDiagnosticValue.code(SOURCE_ID));
	}

	/** Reflection-only lifecycle probe used by NeoForge transition diagnostics. */
	public static String registrationState() {
		return LIFECYCLE.state();
	}

	private record Registration(EntityOutlineSourceRegistry.Registration outline,
		EntitySelectionBlacklist.Registration blacklist, CreateEntityOutlineSource source)
		implements CreateRegistrationLifecycle.Handle {
		@Override public String state() { return outline.accepted() ? "registered" : "rejected"; }

		@Override public void close() {
			try {
				blacklist.close();
			} finally {
				try {
					outline.close();
				} finally {
					source.close();
				}
			}
		}
	}

	private static final class CreateEntityOutlineSource implements EntityOutlineSource {
		private final CreateEntityOutlineDiagnostics diagnostics = new CreateEntityOutlineDiagnostics(BACKEND_PROBE::diagnosticDescription);

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

			@Nullable OutlineOnlyBufferSource outlineOnly = null;
			boolean dispatchStarted = false;
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
					dispatchStarted = true;
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

				diagnostics.report(entity, dispatchStarted ? "renderer-failed-before-vertices" : "render-setup-failed", "on", true, 0, failure,
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
			boolean emissionStarted = false;
			try {
				shared.setColor(red(color), green(color), blue(color), 255);
				VertexConsumer consumer = shared.getBuffer(SUPER_GLUE_OUTLINE);
				emissionStarted = true;
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

				diagnostics.report(entity, emissionStarted ? "glue-render-failed-before-vertices" : "glue-render-setup-failed", "not-required", false,
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

}
