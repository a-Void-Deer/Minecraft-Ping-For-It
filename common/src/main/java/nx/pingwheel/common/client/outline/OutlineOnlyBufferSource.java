package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;

/**
 * Outline-only {@link MultiBufferSource} adapter around a caller-owned,
 * attempt-local buffer source.
 *
 * <p>Every render attempt through this adapter resolves the incoming
 * {@link RenderType} to its outline-only counterpart and issues it straight
 * into the supplied local source, so the silhouette geometry is emitted
 * nowhere else. The adapter never touches any shared or frame-level vanilla
 * buffer: the supplied source <em>must be attempt-local</em> (typically a
 * fresh {@code MultiBufferSource.immediate(...)} over a fresh
 * {@code ByteBufferBuilder} owned by the same attempt), so a failed attempt
 * that left an incomplete vertex is discarded together with its buffer
 * instead of corrupting a buffer some other code path will later flush.
 *
 * <p>Resolution rules for an incoming render type:
 * <ul>
 *   <li>{@link RenderType#isOutline()}: used as-is;</li>
 *   <li>otherwise, when {@link RenderType#outline()} is present: that exact
 *       texture-specific outline type is used;</li>
 *   <li>otherwise, when {@code allowBlocksAtlasFallback} is true (ordinary
 *       {@code block} BlockDisplay route only):
 *       {@link RenderType#outline(net.minecraft.resources.ResourceLocation)}
 *       over the blocks atlas is used;</li>
 *   <li>otherwise (actual {@code BlockEntity} route with no outline variant):
 *       a no-op {@link VertexConsumer} is returned so the emitted vertex count
 *       stays zero and the caller falls back to the VoxelShape outline.</li>
 * </ul>
 *
 * <p>Every vertex that reaches the local source through this adapter is
 * counted; {@link #vertexCount()} reports the total after a render attempt,
 * and a zero count means the route produced no silhouette.
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
 * <p>The pure decision core is {@link #decide(boolean, boolean, boolean)},
 * which operates on extracted {@link RenderType} facts and is headless-
 * testable; {@link #resolve} applies it to a live render type. The counting
 * and no-op consumers are package-private test seams.
 *
 * <p>Thread safety: main-thread render pass only, one instance per render
 * attempt, never reused or pooled.
 */
public final class OutlineOnlyBufferSource implements MultiBufferSource {

	private final MultiBufferSource source;
	private final boolean allowBlocksAtlasFallback;
	private final int markerColor;
	private final List<VertexCountingConsumer> issuedConsumers = new ArrayList<>();

	/**
	 * @param source                    the attempt-local buffer source to
	 *                                  issue resolved outline render types
	 *                                  into; must be created fresh for this
	 *                                  attempt and never shared across
	 *                                  attempts or frames
	 * @param allowBlocksAtlasFallback  whether render types without an outline
	 *                                  variant fall back to
	 *                                  {@code RenderType.outline(TextureAtlas.LOCATION_BLOCKS)}
	 *                                  (ordinary BlockDisplay route) instead
	 *                                  of a no-op (actual BlockEntity route)
	 * @param markerColor               the opaque marker color (ARGB; the
	 *                                  alpha is ignored and forced to 255)
	 *                                  applied to every emitted vertex
	 */
	public OutlineOnlyBufferSource(MultiBufferSource source, boolean allowBlocksAtlasFallback, int markerColor) {
		this.source = Objects.requireNonNull(source, "source");
		this.allowBlocksAtlasFallback = allowBlocksAtlasFallback;
		this.markerColor = markerColor;
	}

	@Override
	public VertexConsumer getBuffer(RenderType renderType) {
		Objects.requireNonNull(renderType, "renderType");

		RenderType outlineType = resolve(renderType, allowBlocksAtlasFallback);

		if (outlineType == null) {
			// No outline variant exists and no fallback is allowed: the route
			// cannot silhouette this geometry, so swallow it without counting.
			return NoOpVertexConsumer.INSTANCE;
		}

		VertexCountingConsumer consumer = new VertexCountingConsumer(
			source.getBuffer(outlineType),
			(markerColor >> 16) & 0xFF,
			(markerColor >> 8) & 0xFF,
			markerColor & 0xFF);
		issuedConsumers.add(consumer);
		return consumer;
	}

	/**
	 * The total number of vertices emitted through this adapter since its
	 * construction, across every render type issued.
	 */
	public int vertexCount() {
		int total = 0;

		for (VertexCountingConsumer consumer : issuedConsumers) {
			total += consumer.vertices;
		}

		return total;
	}

	/**
	 * The pure resolution decision, stated over the extracted {@link RenderType}
	 * facts so it is fully headless-testable:
	 * <ul>
	 *   <li>{@code isOutline} — {@code RenderType#isOutline()};</li>
	 *   <li>{@code hasOutlineVariant} — whether
	 *       {@code RenderType#outline()} is present;</li>
	 *   <li>{@code allowBlocksAtlasFallback} — the adapter's fallback mode
	 *       (ordinary BlockDisplay route vs actual BlockEntity route).</li>
	 * </ul>
	 */
	static Decision decide(boolean isOutline, boolean hasOutlineVariant, boolean allowBlocksAtlasFallback) {
		if (isOutline) {
			return Decision.AS_IS;
		}

		if (hasOutlineVariant) {
			return Decision.OUTLINE_VARIANT;
		}

		if (allowBlocksAtlasFallback) {
			return Decision.BLOCKS_ATLAS;
		}

		return Decision.NO_OP;
	}

	/**
	 * The outline-only {@link RenderType} to issue for {@code renderType}, or
	 * {@code null} for a no-op. No render state is touched here.
	 */
	static RenderType resolve(RenderType renderType, boolean allowBlocksAtlasFallback) {
		return switch (decide(renderType.isOutline(), renderType.outline().isPresent(), allowBlocksAtlasFallback)) {
			case AS_IS -> renderType;
			case OUTLINE_VARIANT -> renderType.outline().get();
			case BLOCKS_ATLAS -> RenderType.outline(TextureAtlas.LOCATION_BLOCKS);
			case NO_OP -> null;
		};
	}

	/**
	 * The pure per-render-type resolution outcome.
	 */
	enum Decision {

		/** The incoming type is itself an outline type: use it as-is. */
		AS_IS,

		/** The incoming type exposes a texture-specific outline variant: use it. */
		OUTLINE_VARIANT,

		/** No variant exists but the blocks-atlas fallback is allowed: use it. */
		BLOCKS_ATLAS,

		/** No variant and no fallback: emit nothing, count zero. */
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
		private int vertices;

		VertexCountingConsumer(VertexConsumer delegate, int red, int green, int blue) {
			this.delegate = Objects.requireNonNull(delegate, "delegate");
			this.red = red;
			this.green = green;
			this.blue = blue;
		}

		/** The number of vertices forwarded so far. */
		int vertices() {
			return vertices;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			vertices++;
			delegate.addVertex(x, y, z);
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
