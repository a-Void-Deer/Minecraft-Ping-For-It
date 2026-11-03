package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.OptionalDouble;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Loader-neutral custom line render type for block outlines.
 *
 * <p>Mirrors the vanilla 1.21.1 {@link RenderType#lines()} definition —
 * {@link DefaultVertexFormat#POSITION_COLOR_NORMAL}, {@link
 * VertexFormat.Mode#LINES}, the 1536-vertex transient buffer size, the
 * {@code rendertype_lines} shader, view-offset-z layering, translucent
 * transparency, the main target, no culling, and a composite state without
 * outline property — with the three intended product deltas:
 * <ul>
 *   <li>{@link RenderStateShard#NO_DEPTH_TEST} instead of the default
 *       depth test, so the lines stay visible through intervening
 *       blocks;</li>
 *   <li>a fixed {@link RenderStateShard.LineStateShard} of {@value #LINE_WIDTH}
 *       px — approximately 1.5&times; the vanilla selection-line baseline of
 *       roughly 2.5 px — instead of the vanilla automatic width;</li>
 *   <li>{@link RenderStateShard#COLOR_WRITE} instead of the vanilla
 *       color-depth write mask, so this batch never mutates the scene or
 *       FBO depth buffer.</li>
 * </ul>
 *
 * <p>Only public 1.21.1 APIs are used: the shards are the public/protected
 * static shards inherited from {@link RenderStateShard} (protected members
 * are accessible from this subclass), the composite state is built through
 * the public {@link RenderType.CompositeState#builder()}, and the instance
 * is created through the public {@link RenderType} constructor. The setup
 * and clear runnables replay exactly the same ordered shard list the vanilla
 * {@code CompositeRenderType} derives from its composite state, because the
 * vanilla {@code create} factory and the composite state's shard list are
 * not public API. The type is an immutable singleton and performs no
 * {@code RenderSystem} work at construction or class-load time; render state
 * is applied only when the buffer source draws the batch.
 *
 * <p>Thread safety: immutable singleton, safe for any thread; the batch is
 * only ever acquired and flushed by the main-thread render pass.
 */
public final class BlockOutlineRenderType extends RenderType {

	/**
	 * Fixed line width in pixels: 3.75 px, approximately 1.5&times; the
	 * vanilla selection-line baseline of roughly 2.5 px.
	 */
	public static final double LINE_WIDTH = 3.75D;

	/** Render type name used for the composite state and buffer bookkeeping. */
	private static final String NAME = "ping_block_outline";

	private static final RenderStateShard.LineStateShard LINE_STATE =
		new RenderStateShard.LineStateShard(OptionalDouble.of(LINE_WIDTH));

	/**
	 * Immutable description of the product-required VoxelShape outline state.
	 * The actual composite state and setup/clear shard list are both built from
	 * this descriptor, so the package-private test seam can pin the live
	 * production configuration without reflecting into vanilla's private
	 * {@link RenderType.CompositeState} fields.
	 */
	static record OutlineStateDescriptor(
		VertexFormat.Mode mode,
		double lineWidth,
		String shaderName,
		RenderStateShard.ShaderStateShard shaderState,
		RenderStateShard.LineStateShard lineState,
		RenderStateShard.LayeringStateShard layeringState,
		RenderStateShard.TransparencyStateShard transparencyState,
		RenderStateShard.OutputStateShard outputState,
		RenderStateShard.CullStateShard cullState,
		RenderStateShard.DepthTestStateShard depthTestState,
		RenderStateShard.WriteMaskStateShard writeMaskState
	) {}

	private static final OutlineStateDescriptor OUTLINE_STATE = new OutlineStateDescriptor(
		VertexFormat.Mode.LINES,
		LINE_WIDTH,
		"rendertype_lines",
		RENDERTYPE_LINES_SHADER,
		LINE_STATE,
		VIEW_OFFSET_Z_LAYERING,
		TRANSLUCENT_TRANSPARENCY,
		MAIN_TARGET,
		NO_CULL,
		NO_DEPTH_TEST,
		COLOR_WRITE);

	private final OutlineStateDescriptor descriptor;

	/**
	 * The composite state mirroring {@code RenderType.lines()} plus the
	 * depth-test, line-width, and write-mask deltas above. Kept for parity
	 * with the vanilla construction and exposed via {@link #state()} as a
	 * non-reflective test seam.
	 */
	private static final RenderType.CompositeState STATE = RenderType.CompositeState.builder()
		.setShaderState(OUTLINE_STATE.shaderState())
		.setLineState(OUTLINE_STATE.lineState())
		.setLayeringState(OUTLINE_STATE.layeringState())
		.setTransparencyState(OUTLINE_STATE.transparencyState())
		.setOutputState(OUTLINE_STATE.outputState())
		.setCullState(OUTLINE_STATE.cullState())
		.setDepthTestState(OUTLINE_STATE.depthTestState())
		.setWriteMaskState(OUTLINE_STATE.writeMaskState())
		.createCompositeState(false);

	/**
	 * The exact ordered shard application list the vanilla 1.21.1
	 * {@code CompositeRenderType} derives from a composite state (texture,
	 * shader, transparency, depth test, cull, lightmap, overlay, layering,
	 * output, texturing, write mask, color logic, line; verified against
	 * the 1.21.1 artifact). Every entry is the same instance that
	 * participates in {@link #STATE}, so the applied render state is
	 * identical to vanilla's.
	 */
	private static final List<RenderStateShard> SHARDS = List.of(
		NO_TEXTURE,
		OUTLINE_STATE.shaderState(),
		OUTLINE_STATE.transparencyState(),
		OUTLINE_STATE.depthTestState(),
		OUTLINE_STATE.cullState(),
		NO_LIGHTMAP,
		NO_OVERLAY,
		OUTLINE_STATE.layeringState(),
		OUTLINE_STATE.outputState(),
		DEFAULT_TEXTURING,
		OUTLINE_STATE.writeMaskState(),
		NO_COLOR_LOGIC,
		OUTLINE_STATE.lineState()
	);

	/**
	 * The immutable block-outline render type singleton. The block outline
	 * pass acquires this type from the frame's {@code BufferSource} and
	 * flushes it explicitly; vanilla's {@code lines()} batch is untouched.
	 */
	public static final RenderType BLOCK_OUTLINE = new BlockOutlineRenderType();

	private BlockOutlineRenderType() {
		this(OUTLINE_STATE);
	}

	private BlockOutlineRenderType(OutlineStateDescriptor descriptor) {
		super(
			NAME,
			DefaultVertexFormat.POSITION_COLOR_NORMAL,
			descriptor.mode(),
			1536,
			false,
			false,
			() -> SHARDS.forEach(RenderStateShard::setupRenderState),
			() -> SHARDS.forEach(RenderStateShard::clearRenderState));
		this.descriptor = descriptor;
	}

	/**
	 * Package-private production descriptor seam for focused render-state
	 * regression tests. The public render type remains the singleton above.
	 */
	OutlineStateDescriptor descriptor() {
		return descriptor;
	}

	/**
	 * Package-private descriptor seam for tests; the returned descriptor is the
	 * same immutable instance used to construct the composite state and type.
	 */
	static OutlineStateDescriptor outlineState() {
		return OUTLINE_STATE;
	}

	/**
	 * The composite state backing {@link #BLOCK_OUTLINE}. Mainly a test seam
	 * to assert the state is present and non-null without reflection; loader
	 * code outside this package must not depend on the exact type, which is
	 * not part of the vanilla public surface.
	 */
	static RenderType.CompositeState state() {
		return STATE;
	}
}
