package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.server.Bootstrap;
import nx.pingwheel.common.client.outline.OutlineOnlyBufferSource.NoOpVertexConsumer;
import nx.pingwheel.common.client.outline.OutlineOnlyBufferSource.VertexCountingConsumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static nx.pingwheel.common.client.outline.OutlineOnlyBufferSource.Decision.AS_IS;
import static nx.pingwheel.common.client.outline.OutlineOnlyBufferSource.Decision.NO_OP;
import static nx.pingwheel.common.client.outline.OutlineOnlyBufferSource.Decision.OUTLINE_VARIANT;

/**
 * Headless tests for the outline-only buffer adapter seams: the pure render
 * type resolution decision, the counting/fixed-color consumers, the no-op
 * rejection for outline-less geometry, and the routing of the
 * resolved render type into the supplied caller-owned source (the focused
 * tests use a local recording source).
 *
 * <p>The counting and no-op consumers are tested directly with recording
 * delegates — no game client. The two routing tests need live vanilla
 * {@code RenderType} instances, whose class initializer reaches the vanilla
 * block/item registries; those run the plain vanilla registry bootstrap once
 * (exactly like {@code ClientTargetNameDecoderTest} and friends), never a
 * game client, language resources, or reflection.
 */
class OutlineOnlyBufferSourceTest {

	@BeforeAll
	static void bootStrap() {
		// Vanilla RenderType construction touches BuiltInRegistries through
		// its class initializer, so the registry bootstrap must run once,
		// exactly like the game does before using these classes. The version
		// must be detected first because Bootstrap.bootStrap() itself does
		// not do it.
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * Minimal recording {@link VertexConsumer} delegate; mirrors the vanilla
	 * interface's abstract methods only (the rest are interface defaults)
	 * and records call order, colors, and per-setter invocation counts.
	 */
	private static final class RecordingVertexConsumer implements VertexConsumer {

		private final boolean throwOnAddVertex;
		private final boolean throwOnColor;
		private int vertices;
		private final List<int[]> colors = new ArrayList<>();
		private final List<String> calls = new ArrayList<>();
		private int uvCalls;
		private int uv1Calls;
		private int uv2Calls;
		private int normalCalls;

		private RecordingVertexConsumer() {
			this(false, false);
		}

		private RecordingVertexConsumer(boolean throwOnAddVertex, boolean throwOnColor) {
			this.throwOnAddVertex = throwOnAddVertex;
			this.throwOnColor = throwOnColor;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			if (throwOnAddVertex) {
				throw new IllegalStateException("delegate addVertex failed");
			}
			vertices++;
			calls.add("addVertex");
			return this;
		}

		@Override
		public VertexConsumer setColor(int red, int green, int blue, int alpha) {
			colors.add(new int[] { red, green, blue, alpha });
			calls.add("setColor");
			if (throwOnColor) {
				throw new IllegalStateException("delegate setColor failed");
			}
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			uvCalls++;
			calls.add("setUv");
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			uv1Calls++;
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			uv2Calls++;
			return this;
		}

		@Override
		public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
			normalCalls++;
			return this;
		}
	}

	/**
	 * Minimal fake attempt-local {@link MultiBufferSource}: records every
	 * render type requested and hands out one shared recording consumer.
	 */
	private static final class RecordingBufferSource implements MultiBufferSource {

		private final List<RenderType> requested = new ArrayList<>();
		private final RecordingVertexConsumer delegate;

		private RecordingBufferSource() {
			this(new RecordingVertexConsumer());
		}

		private RecordingBufferSource(RecordingVertexConsumer delegate) {
			this.delegate = delegate;
		}

		@Override
		public VertexConsumer getBuffer(RenderType renderType) {
			requested.add(renderType);
			return delegate;
		}
	}

	// --- pure resolution decision ---

	@Test
	void outlineRenderTypesAreUsedAsIs() {
		assertSame(AS_IS, OutlineOnlyBufferSource.decide(true, false));
		assertSame(AS_IS, OutlineOnlyBufferSource.decide(true, true));
	}

	@Test
	void textureSpecificOutlineVariantIsPreferred() {
		assertSame(OUTLINE_VARIANT, OutlineOnlyBufferSource.decide(false, true));
	}

	@Test
	void outlineLessTypesAlwaysResolveToNoOp() {
		assertSame(NO_OP, OutlineOnlyBufferSource.decide(false, false));
	}

	@Test
	void decisionCoverageIsComplete() {
		// Pin the full decision matrix so a future routing change is noticed.
		assertEquals(AS_IS, OutlineOnlyBufferSource.decide(true, true));
		assertEquals(AS_IS, OutlineOnlyBufferSource.decide(true, false));
		assertEquals(OUTLINE_VARIANT, OutlineOnlyBufferSource.decide(false, true));
		assertEquals(NO_OP, OutlineOnlyBufferSource.decide(false, false));
	}

	@Test
	void normalModelTypesResolveThroughTheirOutlineVariants() {
		assertSame(
			RenderType.solid().outline().orElseThrow(),
			OutlineOnlyBufferSource.resolve(RenderType.solid()));
		assertSame(
			RenderType.cutout().outline().orElseThrow(),
			OutlineOnlyBufferSource.resolve(RenderType.cutout()));
	}

	// --- counting ---

	@Test
	void countingConsumerCountsEveryVertexAndForwards() {
		RecordingVertexConsumer delegate = new RecordingVertexConsumer();
		VertexCountingConsumer consumer = new VertexCountingConsumer(delegate, 0xFF, 0x00, 0x00);

		assertEquals(0, consumer.vertices());

		consumer
			.addVertex(0.0F, 0.0F, 0.0F)
			.setUv(0.0F, 0.0F)
			.addVertex(1.0F, 0.0F, 0.0F)
			.setUv(1.0F, 0.0F)
			.addVertex(1.0F, 1.0F, 0.0F)
			.addVertex(0.0F, 1.0F, 0.0F);

		assertEquals(4, consumer.vertices());
		assertEquals(4, delegate.vertices);
		assertEquals(4, delegate.colors.size());

		// A fresh attempt always starts at zero.
		VertexCountingConsumer second = new VertexCountingConsumer(new RecordingVertexConsumer(), 0xFF, 0x00, 0x00);
		assertEquals(0, second.vertices());
	}

	@Test
	void countingConsumerIsPerAttemptAndStartsAtZero() {
		VertexCountingConsumer first = new VertexCountingConsumer(new RecordingVertexConsumer(), 1, 2, 3);
		VertexCountingConsumer second = new VertexCountingConsumer(new RecordingVertexConsumer(), 1, 2, 3);

		first.addVertex(0.0F, 0.0F, 0.0F);
		first.addVertex(1.0F, 0.0F, 0.0F);

		assertEquals(2, first.vertices());
		assertEquals(0, second.vertices());
	}

	// --- fixed-color semantics (mirrors vanilla EntityOutlineGenerator) ---

	@Test
	void addVertexDelegatesThenAppliesOpaqueFixedColor() {
		RecordingVertexConsumer delegate = new RecordingVertexConsumer();
		VertexCountingConsumer consumer = new VertexCountingConsumer(delegate, 0x12, 0x34, 0x56);

		consumer.addVertex(1.0F, 2.0F, 3.0F);

		assertEquals(1, delegate.vertices);
		assertEquals(1, delegate.colors.size());
		assertArrayEquals(new int[] { 0x12, 0x34, 0x56, 255 }, delegate.colors.get(0));
		assertEquals(List.of("addVertex", "setColor"), delegate.calls);
	}

	@Test
	void delegateAddVertexFailureDoesNotCommitAVertex() {
		RecordingVertexConsumer delegate = new RecordingVertexConsumer(true, false);
		RecordingBufferSource source = new RecordingBufferSource(delegate);
		OutlineOnlyBufferSource adapter = new OutlineOnlyBufferSource(source, 0xFF123456);
		VertexCountingConsumer consumer = (VertexCountingConsumer) adapter.getBuffer(
			RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS));

		assertThrows(IllegalStateException.class, () -> consumer.addVertex(1.0F, 2.0F, 3.0F));

		assertEquals(0, consumer.vertices());
		assertEquals(0, adapter.vertexCount());
		assertEquals(0, delegate.vertices);
		assertEquals(0, delegate.colors.size());
	}

	@Test
	void delegateColorFailureRetainsTheCommittedVertex() {
		RecordingVertexConsumer delegate = new RecordingVertexConsumer(false, true);
		RecordingBufferSource source = new RecordingBufferSource(delegate);
		OutlineOnlyBufferSource adapter = new OutlineOnlyBufferSource(source, 0xFF123456);
		VertexCountingConsumer consumer = (VertexCountingConsumer) adapter.getBuffer(
			RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS));

		assertThrows(IllegalStateException.class, () -> consumer.addVertex(1.0F, 2.0F, 3.0F));

		assertEquals(1, consumer.vertices());
		assertEquals(1, adapter.vertexCount());
		assertEquals(1, delegate.vertices);
		assertEquals(1, delegate.colors.size());
	}

	@Test
	void wrapperSetColorIsSwallowed() {
		RecordingVertexConsumer delegate = new RecordingVertexConsumer();
		VertexCountingConsumer consumer = new VertexCountingConsumer(delegate, 0x12, 0x34, 0x56);

		consumer.setColor(9, 8, 7, 6);

		assertEquals(0, delegate.colors.size());
		assertEquals(0, delegate.calls.size());
	}

	@Test
	void uvIsForwardedButUv1Uv2AndNormalAreSwallowed() {
		RecordingVertexConsumer delegate = new RecordingVertexConsumer();
		VertexCountingConsumer consumer = new VertexCountingConsumer(delegate, 0x12, 0x34, 0x56);

		consumer.setUv(0.5F, 0.25F);
		assertEquals(1, delegate.uvCalls);

		consumer.setUv1(1, 2);
		consumer.setUv2(3, 4);
		consumer.setNormal(0.0F, 1.0F, 0.0F);

		assertEquals(0, delegate.uv1Calls);
		assertEquals(0, delegate.uv2Calls);
		assertEquals(0, delegate.normalCalls);
	}

	@Test
	void chainingPreservesTheConsumerInstance() {
		VertexCountingConsumer consumer = new VertexCountingConsumer(new RecordingVertexConsumer(), 1, 2, 3);

		assertSame(consumer, consumer.addVertex(0.0F, 0.0F, 0.0F));
		assertSame(consumer, consumer.setColor(1, 2, 3, 4));
		assertSame(consumer, consumer.setUv(0.0F, 0.0F));
		assertSame(consumer, consumer.setUv1(0, 0));
		assertSame(consumer, consumer.setUv2(0, 0));
		assertSame(consumer, consumer.setNormal(0.0F, 1.0F, 0.0F));
	}

	// --- no-op ---

	@Test
	void noOpConsumerAcceptsAndDiscardsEverything() {
		NoOpVertexConsumer consumer = NoOpVertexConsumer.INSTANCE;

		assertSame(consumer, consumer.addVertex(0.0F, 0.0F, 0.0F));
		assertSame(consumer, consumer.setColor(1, 2, 3, 4));
		assertSame(consumer, consumer.setUv(0.5F, 0.5F));
		assertSame(consumer, consumer.setUv1(1, 2));
		assertSame(consumer, consumer.setUv2(3, 4));
		assertSame(consumer, consumer.setNormal(0.0F, 1.0F, 0.0F));
	}

	// --- routing into the supplied caller-owned source ---

	/**
	 * For every render type that resolves to an outline type, the adapter
	 * must request exactly the resolved type from the supplied local source
	 * and wrap the source's consumer in the counting/fixed-color consumer.
	 * {@code RenderType.lines()} and shadows have no outline variant and are
	 * rejected, {@code RenderType.entitySolid(...)} exposes its
	 * texture-specific outline variant, and
	 * {@code RenderType.outline(...)} is itself an outline type.
	 */
	@Test
	void resolvedTypeIsSentToLocalSource() {
		RenderType lines = RenderType.lines();
		RenderType entitySolid = RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS);
		RenderType outlineType = RenderType.outline(TextureAtlas.LOCATION_BLOCKS);

		for (RenderType input : List.of(lines, entitySolid, outlineType)) {
			RenderType resolved = OutlineOnlyBufferSource.resolve(input);

			if (resolved == null) {
				continue; // The no-op branch is pinned by its own test.
			}

			RecordingBufferSource source = new RecordingBufferSource();
			OutlineOnlyBufferSource adapter = new OutlineOnlyBufferSource(source, 0xFF11AA22);

			VertexConsumer issued = adapter.getBuffer(input);

			assertNotNull(issued);
			assertEquals(1, source.requested.size());
			assertSame(resolved, source.requested.get(0));

			// The issued consumer wraps the source's own consumer: vertices
			// flow through, counted, with the fixed opaque marker color.
			issued.addVertex(0.0F, 0.0F, 0.0F);

			assertEquals(1, adapter.vertexCount());
			assertEquals(1, source.delegate.vertices);
			assertArrayEquals(new int[] { 0x11, 0xAA, 0x22, 255 }, source.delegate.colors.get(0));
		}
	}

	@Test
	void noOpNeverConsultsLocalSource() {
		for (RenderType input : List.of(
			RenderType.lines(),
			RenderType.entityShadow(TextureAtlas.LOCATION_BLOCKS))) {
			RecordingBufferSource source = new RecordingBufferSource();
			OutlineOnlyBufferSource adapter = new OutlineOnlyBufferSource(source, 0xFF0000FF);

			// Hitbox, shadow, and other debug geometry has no outline variant
			// and must never become counted model-outline geometry.
			VertexConsumer issued = adapter.getBuffer(input);

			assertSame(NoOpVertexConsumer.INSTANCE, issued);
			assertEquals(0, source.requested.size());
			assertEquals(0, adapter.vertexCount());

			issued.addVertex(1.0F, 2.0F, 3.0F);
			assertEquals(0, adapter.vertexCount());
		}
	}

	// --- fallback texture resolution ---

	@Test
	void fallbackConstructorRoutesOutlineLessGeometryThroughFallbackTexture() {
		RecordingBufferSource source = new RecordingBufferSource();
		OutlineOnlyBufferSource adapter = new OutlineOnlyBufferSource(
			source, 0xFF123456, TextureAtlas.LOCATION_BLOCKS, -1);

		// lines() has no outline variant; with a fallback texture it must
		// resolve to RenderType.outline(fallback) instead of a no-op, and the
		// fixed opaque color plus count still apply.
		VertexConsumer issued = adapter.getBuffer(RenderType.lines());

		RenderType expected = RenderType.outline(TextureAtlas.LOCATION_BLOCKS);
		assertSame(expected, source.requested.get(0));
		assertTrue(expected.isOutline());

		issued.addVertex(1.0F, 2.0F, 3.0F);
		assertEquals(1, adapter.vertexCount());
		assertEquals(1, source.delegate.vertices);
		assertArrayEquals(new int[] { 0x12, 0x34, 0x56, 255 }, source.delegate.colors.get(0));
	}

	@Test
	void fallbackConstructorCountsFallbackAndVariantGeometryTogether() {
		RecordingBufferSource source = new RecordingBufferSource();
		OutlineOnlyBufferSource adapter = new OutlineOnlyBufferSource(
			source, 0xFF0000FF, TextureAtlas.LOCATION_BLOCKS, -1);

		adapter.getBuffer(RenderType.lines()).addVertex(0.0F, 0.0F, 0.0F);
		adapter.getBuffer(RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS)).addVertex(1.0F, 1.0F, 1.0F);

		assertEquals(2, adapter.vertexCount());
	}

	@Test
	void legacyConstructorStillRejectsOutlineLessGeometry() {
		RecordingBufferSource source = new RecordingBufferSource();
		OutlineOnlyBufferSource adapter = new OutlineOnlyBufferSource(source, 0xFF0000FF);

		// The two-argument constructor keeps the original no-op behavior for
		// outline-less geometry; only the fallback constructor maps it.
		VertexConsumer issued = adapter.getBuffer(RenderType.lines());

		assertSame(NoOpVertexConsumer.INSTANCE, issued);
		assertEquals(0, source.requested.size());
		assertEquals(0, adapter.vertexCount());
	}

	// --- vertex budget ---

	@Test
	void budgetAllowsExactlyTheCapAndThrowsTheDedicatedExceptionAfterwards() {
		RecordingBufferSource source = new RecordingBufferSource();
		OutlineOnlyBufferSource adapter = new OutlineOnlyBufferSource(
			source, 0xFF0000FF, TextureAtlas.LOCATION_BLOCKS, 4);

		VertexConsumer consumer = adapter.getBuffer(RenderType.lines());
		for (int i = 0; i < 4; i++) {
			consumer.addVertex(i, 0.0F, 0.0F);
		}
		assertEquals(4, adapter.vertexCount());

		OutlineOnlyBufferSource.BudgetExceededException thrown = assertThrows(
			OutlineOnlyBufferSource.BudgetExceededException.class,
			() -> consumer.addVertex(5, 0.0F, 0.0F));
		assertEquals(5, thrown.attemptedVertices());
		assertEquals(4, thrown.maxVertices());
		assertEquals(4, ((VertexCountingConsumer) consumer).vertices());
		assertEquals(4, source.delegate.vertices);
		assertEquals(4, source.delegate.colors.size());
	}

	@Test
	void budgetIsSharedAcrossRenderTypes() {
		RecordingBufferSource source = new RecordingBufferSource();
		OutlineOnlyBufferSource adapter = new OutlineOnlyBufferSource(
			source, 0xFF0000FF, TextureAtlas.LOCATION_BLOCKS, 3);

		adapter.getBuffer(RenderType.lines()).addVertex(0.0F, 0.0F, 0.0F);
		adapter.getBuffer(RenderType.lines()).addVertex(1.0F, 0.0F, 0.0F);
		adapter.getBuffer(RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS)).addVertex(2.0F, 0.0F, 0.0F);

		assertThrows(OutlineOnlyBufferSource.BudgetExceededException.class,
			() -> adapter.getBuffer(RenderType.lines()).addVertex(3.0F, 0.0F, 0.0F));
		assertEquals(3, adapter.vertexCount());
	}

	@Test
	void unboundedAdaptersNeverThrowTheBudgetException() {
		RecordingBufferSource source = new RecordingBufferSource();
		OutlineOnlyBufferSource adapter = new OutlineOnlyBufferSource(
			source, 0xFF0000FF, TextureAtlas.LOCATION_BLOCKS, -1);

		VertexConsumer consumer = adapter.getBuffer(RenderType.lines());
		for (int i = 0; i < 1000; i++) {
			consumer.addVertex(i, 0.0F, 0.0F);
		}
		assertEquals(1000, adapter.vertexCount());
	}

	@Test
	void negativeBudgetValuesOtherThanUnboundedAreRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> new OutlineOnlyBufferSource(
				new RecordingBufferSource(), 0xFF0000FF, TextureAtlas.LOCATION_BLOCKS, -2));
	}
}
