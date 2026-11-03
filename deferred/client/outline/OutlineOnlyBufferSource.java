package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Outline-only {@link MultiBufferSource} adapter around a caller-owned
 * buffer source.
 *
 * <p>Every render attempt through this adapter resolves the incoming
 * {@link RenderType} to its outline-only counterpart and issues it straight
 * into the supplied source, so the silhouette geometry is emitted nowhere
 * else. An attempt-local source is a valid and preferred isolation strategy
 * (typically a fresh {@code MultiBufferSource.immediate(...)} over a fresh
 * {@code ByteBufferBuilder} owned by the same attempt), so a failed attempt
 * that left an incomplete vertex can be discarded together with its buffer.
 * The caller may also provide its own shared/frame-level vanilla
 * {@code OutlineBufferSource}; in that mode partial writes cannot be rolled
 * back, so any recoverable exception after a committed vertex is treated as
 * {@code RENDERED}. The adapter never flushes that shared source or takes
 * ownership of its lifetime.
 *
 * <p>Resolution rules for an incoming render type:
 * <ul>
 *   <li>{@link RenderType#isOutline()}: used as-is;</li>
	 *   <li>otherwise, when the input is textured model-compatible geometry
	 *       (exactly {@link VertexFormat.Mode#QUADS} and supplying UV0), an
	 *       available exact texture-specific outline type is used;</li>
	 *   <li>otherwise, when a fallback texture is configured and the input is
	 *       textured model-compatible geometry, {@link RenderType#outline(ResourceLocation)}
	 *       is used;</li>
	 *   <li>otherwise, a no-op {@link VertexConsumer} is returned. The block-atlas
	 *       fallback is for textured model/terrain geometry only; line, debug,
	 *       hitbox, shadow, and other no-UV geometry must never be mapped into the
	 *       textured outline format.</li>
 * </ul>
 *
 * <p>Every vertex that reaches the local source through this adapter is
 * counted, including fallback geometry, so {@link #vertexCount()} reports only
 * silhouette vertices. A zero count means the route produced no silhouette.
 * When a non-negative max-vertex budget was supplied, the adapter throws the
 * dedicated recoverable {@link BudgetExceededException} once the next write
 * would push the total past the cap.</p>
 *
 * <p>Color: the adapter applies one opaque marker color, supplied once at
 * construction, to every vertex itself — exactly mirroring the vanilla 1.21.1
 * {@code EntityOutlineGenerator}: {@code addVertex} delegates the position
 * and then sets the fixed opaque color, any renderer {@code setColor} call is
 * swallowed, {@code setUv} forwards, and {@code setUv1}/{@code setUv2}/
 * {@code setNormal} are ignored. UVs and texture alpha pass through
 * untouched, so the vanilla {@code rendertype_outline} silhouette
 * (alpha-based discard, vertex-color tint) is produced exactly as for
 * glowing entities.
 *
 * <p>The pure decision core is {@link #decide(boolean, boolean)},
 * which operates on extracted {@link RenderType} facts and is headless-
 * testable; {@link #resolve} applies it to a live render type. The counting
 * and no-op consumers are package-private test seams.
 *
 * <p>Thread safety: main-thread render pass only, one instance per render
 * attempt, never reused or pooled.
 */
public final class OutlineOnlyBufferSource implements MultiBufferSource {

	private final MultiBufferSource source;
	private final int markerColor;
	private final ResourceLocation fallbackTexture;
	private final int maxVertices;
	private final List<VertexCountingConsumer> issuedConsumers = new ArrayList<>();
	private int totalVertices;

	/**
	 * @param source                    the caller-owned buffer source to issue
	 *                                  resolved outline render types into; an
	 *                                  attempt-local source is preferred for
	 *                                  rollback isolation, while a shared
	 *                                  {@code OutlineBufferSource} is also valid
	 *                                  when the caller owns flushing and treats
	 *                                  partial committed writes as rendered
	 * @param markerColor               the opaque marker color (ARGB; the
	 *                                  alpha is ignored and forced to 255)
	 *                                  applied to every emitted vertex
	 */
	public OutlineOnlyBufferSource(MultiBufferSource source, int markerColor) {
		this(source, markerColor, null, -1);
	}

	/**
	 * @param source                    the caller-owned buffer source to issue
	 *                                  resolved outline render types into; an
	 *                                  attempt-local source is preferred for
	 *                                  rollback isolation, while a shared
	 *                                  {@code OutlineBufferSource} is also valid
	 *                                  when the caller owns flushing and treats
	 *                                  partial committed writes as rendered
	 * @param markerColor               the opaque marker color (ARGB; the
	 *                                  alpha is ignored and forced to 255)
	 *                                  applied to every emitted vertex
	 * @param fallbackTexture           when non-null, outline-less geometry
	 *                                  resolves to
	 *                                  {@link RenderType#outline(ResourceLocation)}
	 *                                  for this texture instead of being
	 *                                  rejected with a no-op consumer, so a
	 *                                  textured QUADS model pass without an
	 *                                  outline variant still produces a
	 *                                  silhouette mask; when {@code null} the
	 *                                  original no-op rejection is kept
	 * @param maxVertices               the hard total vertex budget for this
	 *                                  adapter; once exactly this many vertices
	 *                                  have been written, the next write throws
	 *                                  {@link BudgetExceededException}. Use
	 *                                  {@code -1} for an unbounded adapter.
	 */
	public OutlineOnlyBufferSource(
		MultiBufferSource source,
		int markerColor,
		ResourceLocation fallbackTexture,
		int maxVertices
	) {
		this.source = Objects.requireNonNull(source, "source");
		this.markerColor = markerColor;
		this.fallbackTexture = fallbackTexture;
		if (maxVertices < -1) {
			throw new IllegalArgumentException("maxVertices must be -1 (unbounded) or non-negative: " + maxVertices);
		}
		this.maxVertices = maxVertices;
	}

	@Override
	public VertexConsumer getBuffer(RenderType renderType) {
		Objects.requireNonNull(renderType, "renderType");

		RenderType outlineType = resolve(renderType, fallbackTexture);

		if (outlineType == null) {
			// Line, debug, hitbox, shadow, and other no-UV geometry cannot
			// be emitted into the textured outline format, so swallow it
			// without asking the caller-owned source for a buffer.
			return NoOpVertexConsumer.INSTANCE;
		}

		VertexCountingConsumer consumer = new VertexCountingConsumer(
			source.getBuffer(outlineType),
			(markerColor >> 16) & 0xFF,
			(markerColor >> 8) & 0xFF,
			markerColor & 0xFF,
			this);
		issuedConsumers.add(consumer);
		return consumer;
	}

	/**
	 * The total number of vertices emitted through this adapter since its
	 * construction, across every render type issued (including any fallback
	 * geometry).
	 */
	public int vertexCount() {
		return totalVertices;
	}

	/**
	 * Checks the shared budget before an issued consumer forwards a vertex.
	 * This is deliberately separate from {@link #commitVertexWrite()}: the
	 * delegate may reject the vertex, in which case nothing was committed.
	 */
	void noteVertexWrite() {
		long next = (long) totalVertices + 1L;
		if (maxVertices >= 0 && next > maxVertices) {
			// The public exception API uses int counts. Saturate the diagnostic at
			// MAX_VALUE rather than overflowing when the hard cap is MAX_VALUE.
			int attemptedVertices = next > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) next;
			throw new BudgetExceededException(attemptedVertices, maxVertices);
		}
	}

	/** Records a vertex after the delegate has accepted it. */
	void commitVertexWrite() {
		totalVertices++;
	}

	/**
	 * Dedicated, recoverable budget exception: a source that keeps writing
	 * past {@link #maxVertices} hits this instead of a generic error, so the
	 * entity-outline runner can treat it like any other recoverable source
	 * failure (a partial shared-buffer commit is still reported as rendered
	 * for that frame and retried on the next).
	 */
	public static final class BudgetExceededException extends RuntimeException {
		private final int attemptedVertices;
		private final int maxVertices;

		private BudgetExceededException(int attemptedVertices, int maxVertices) {
			super("outline vertex budget exceeded; attempted=" + attemptedVertices + "; max=" + maxVertices);
			this.attemptedVertices = attemptedVertices;
			this.maxVertices = maxVertices;
		}

		/** The total vertex count whose write triggered the budget. */
		public int attemptedVertices() {
			return attemptedVertices;
		}

		/** The configured hard cap. */
		public int maxVertices() {
			return maxVertices;
		}
	}

	/** Compatibility convenience for callers that do not configure a fallback. */
	static Decision decide(boolean isOutline, boolean hasOutlineVariant) {
		return decide(isOutline, hasOutlineVariant, false, true, true);
	}

	/**
	 * The pure resolution decision, stated over extracted {@link RenderType}
	 * facts so it is fully headless-testable:
	 * <ul>
	 *   <li>{@code isOutline} — {@code RenderType#isOutline()};</li>
	 *   <li>{@code hasOutlineVariant} — whether
	 *       {@code RenderType#outline()} is present;</li>
	 *   <li>{@code fallbackConfigured} — whether a block-atlas fallback is
	 *       available;</li>
	 *   <li>{@code hasUv0} — whether the incoming format supplies UV0;</li>
	 *   <li>{@code isQuads} — whether the incoming mode is exactly {@link
	 *       VertexFormat.Mode#QUADS};</li>
	 * </ul>
	 */
	static Decision decide(
		boolean isOutline,
		boolean hasOutlineVariant,
		boolean fallbackConfigured,
		boolean hasUv0,
		boolean isQuads
	) {
		if (isOutline) {
			return Decision.AS_IS;
		}

		if (!isQuads || !hasUv0) {
			return Decision.NO_OP;
		}

		if (hasOutlineVariant) {
			return Decision.OUTLINE_VARIANT;
		}

		if (fallbackConfigured) {
			return Decision.FALLBACK;
		}

		return Decision.NO_OP;
	}

	/**
	 * The outline-only {@link RenderType} to issue for {@code renderType}, or
	 * {@code null} for a no-op. No render state is touched here.
	 */
	static RenderType resolve(RenderType renderType) {
		return resolve(renderType, null);
	}

	/**
	 * Resolves an incoming render type, optionally using a textured fallback for
	 * textured QUADS model-compatible geometry.
	 */
	private static RenderType resolve(RenderType renderType, ResourceLocation fallbackTexture) {
		return switch (decide(
			renderType.isOutline(),
			renderType.outline().isPresent(),
			fallbackTexture != null,
			hasUv0(renderType),
			renderType.mode() == VertexFormat.Mode.QUADS)) {
			case AS_IS -> renderType;
			case OUTLINE_VARIANT -> renderType.outline().get();
			case FALLBACK -> RenderType.outline(fallbackTexture);
			case NO_OP -> null;
		};
	}

	private static boolean hasUv0(RenderType renderType) {
		return renderType.format().getElements().stream().anyMatch(element ->
			element.usage() == VertexFormatElement.Usage.UV && element.index() == 0);
	}

	/**
	 * The pure per-render-type resolution outcome.
	 */
	enum Decision {

		/** The incoming type is itself an outline type: use it as-is. */
		AS_IS,

		/** The incoming type exposes a texture-specific outline variant: use it. */
		OUTLINE_VARIANT,

		/** The incoming model type uses the configured textured fallback. */
		FALLBACK,

		/** The incoming type is not safe for an outline mask: emit nothing. */
		NO_OP
	}

	/**
	 * Forwards every vertex to the delegate while counting {@code addVertex}
	 * invocations and applying the fixed opaque marker color — exactly like
	 * the vanilla 1.21.1 {@code EntityOutlineGenerator}:
	 * <ul>
	 *   <li>{@code addVertex} delegates the position and then sets the fixed
	 *       opaque color on the delegate;</li>
	 *   <li>{@code setColor} is swallowed (the marker color is applied by
	 *       {@code addVertex} instead);</li>
	 *   <li>{@code setUv} forwards;</li>
	 *   <li>{@code setUv1}, {@code setUv2}, and {@code setNormal} are
	 *       swallowed.</li>
	 * </ul>
	 *
	 * <p>Every method returns {@code this} — exactly like the vanilla
	 * generator — so chained calls within one vertex emission stay inside the
	 * counter; the interface defaults funnel the remaining vertex-producing
	 * overloads into the abstract {@code addVertex}, so no path can bypass
	 * the count.
	 *
	 * <p>Package-private test seam: tests wrap a recording delegate and assert
	 * exact counts, forwarding, and color semantics without any game client.
	 */
	static final class VertexCountingConsumer implements VertexConsumer {

		private final VertexConsumer delegate;
		private final int red;
		private final int green;
		private final int blue;
		private final OutlineOnlyBufferSource adapter;
		private int vertices;

		/** Standalone consumer (no shared adapter budget); used by focused tests. */
		VertexCountingConsumer(VertexConsumer delegate, int red, int green, int blue) {
			this(delegate, red, green, blue, null);
		}

		VertexCountingConsumer(
			VertexConsumer delegate,
			int red,
			int green,
			int blue,
			OutlineOnlyBufferSource adapter
		) {
			this.delegate = Objects.requireNonNull(delegate, "delegate");
			this.red = red;
			this.green = green;
			this.blue = blue;
			this.adapter = adapter;
		}

		/** The number of vertices forwarded so far. */
		int vertices() {
			return vertices;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			if (adapter != null) {
				adapter.noteVertexWrite();
			}
			delegate.addVertex(x, y, z);
			vertices++;
			if (adapter != null) {
				adapter.commitVertexWrite();
			}
			delegate.setColor(red, green, blue, 255);
			return this;
		}

		@Override
		public VertexConsumer setColor(int red, int green, int blue, int alpha) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			delegate.setUv(u, v);
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
		public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
			return this;
		}
	}

	/**
	 * A stateless {@link VertexConsumer} that accepts and discards every
	 * vertex and setter call. Emits nothing, counts nothing.
	 *
	 * <p>Package-private test seam.
	 */
	static final class NoOpVertexConsumer implements VertexConsumer {

		static final NoOpVertexConsumer INSTANCE = new NoOpVertexConsumer();

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
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
		public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
			return this;
		}
	}
}
