package nx.pingwheel.common.client.outline;

import java.util.Optional;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import nx.pingwheel.common.mixin.RenderPipelinesAccessor;
import nx.pingwheel.common.mixin.RenderTypeAccessor;

import static nx.pingwheel.common.Global.LOGGER;

/**
 * M7-prep E2: custom line pipeline with depth testing disabled.
 *
 * <p>Upstream's outline spec is 3.75px / NO_DEPTH_TEST / TRANSLUCENT / NO_CULL /
 * COLOR_WRITE on {@code rendertype_lines}. Every built-in {@code RenderTypes.lines*}
 * keeps depth testing enabled, so a custom {@code RenderPipeline} is the only way to
 * reproduce the "visible through walls" behaviour. Visibility of the built-in
 * factories is opened through two mixin invokers ({@link RenderTypeAccessor},
 * {@link RenderPipelinesAccessor}) rather than an access widener.
 *
 * <p>{@code RenderSetup} mirrors vanilla {@code RenderTypes.lines()} in the two
 * non-pipeline dimensions that are easy to miss: the
 * {@code VIEW_OFFSET_Z_LAYERING} layering transform and the translucent OIT set.
 *
 * <p>Bucket routing is owned by {@code SubmitNodeCollectionMixin} (decision D2):
 * because this class always submits an opaque alpha (255), vanilla would drop it into
 * the solid bucket and ignore {@code afterTerrain}; the mixin recognises this render
 * type by identity and routes it to the requested bucket instead.
 *
 * <p>Failure is isolated: the first failed attempt logs one warning and every later
 * call returns {@code null}. {@link AssertionError} is caught on purpose — when the
 * accessor mixins did not apply, the invoker stubs throw exactly that, and the client
 * must degrade instead of crashing.
 */
public final class PingOutlineRenderType {

	private static final String PIPELINE_ID = "pipeline/block_outline";
	private static final String TYPE_NAME = "ping_block_outline";

	private static RenderType cached;
	private static boolean failed = false;

	private PingOutlineRenderType() {}

	/**
	 * Identity check used by the bucket-routing mixin.
	 *
	 * <p>Deliberately allocation-free and side-effect-free: it must never trigger
	 * pipeline creation, because it runs for every submitted shape outline.
	 */
	public static boolean isCustom(RenderType type) {
		return type == cached;
	}

	/**
	 * Builds (once) the custom outline render type.
	 *
	 * @return the render type, or {@code null} when it could not be created; callers
	 *         must fall back to a built-in line render type.
	 */
	public static RenderType create() {
		if (cached != null || failed) {
			return cached;
		}

		try {
			RenderPipeline pipeline = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("pingforit", PIPELINE_ID))
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayouts.PROJECTION)
				.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
				.withBindGroupLayout(BindGroupLayouts.FOG)
				.withVertexShader("core/rendertype_lines")
				.withFragmentShader("core/rendertype_lines")
				.withCull(false)
				.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH)
				.withPrimitiveTopology(PrimitiveTopology.LINES)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withDepthStencilState(Optional.<DepthStencilState>empty())
				.build();

			RenderPipelinesAccessor.pingforit$register(pipeline);

			RenderSetup setup = RenderSetup.builder(pipeline)
				.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
				.setOitPipelines(RenderPipelines.OIT_LINES_TRANSLUCENT)
				.createRenderSetup();

			cached = RenderTypeAccessor.pingforit$create(TYPE_NAME, setup);

			LOGGER.info("ping outline pipeline registered (depth test disabled, cull off, translucent)");
			return cached;
		} catch (RuntimeException | LinkageError | AssertionError failure) {
			failed = true;
			cached = null;
			LOGGER.warn("custom outline pipeline unavailable ({}); block outline will be disabled",
				failure.getClass().getSimpleName());
			return null;
		}
	}
}
