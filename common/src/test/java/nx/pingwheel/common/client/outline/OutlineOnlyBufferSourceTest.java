package nx.pingwheel.common.client.outline;

import com.mojang.blaze3d.vertex.VertexConsumer;
import nx.pingwheel.common.client.outline.OutlineOnlyBufferSource.Decision;
import nx.pingwheel.common.client.outline.OutlineOnlyBufferSource.NoOpVertexConsumer;
import nx.pingwheel.common.client.outline.OutlineOnlyBufferSource.VertexCountingConsumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static nx.pingwheel.common.client.outline.OutlineOnlyBufferSource.Decision.AS_IS;
import static nx.pingwheel.common.client.outline.OutlineOnlyBufferSource.Decision.BLOCKS_ATLAS;
import static nx.pingwheel.common.client.outline.OutlineOnlyBufferSource.Decision.NO_OP;
import static nx.pingwheel.common.client.outline.OutlineOnlyBufferSource.Decision.OUTLINE_VARIANT;

/**
 * Headless tests for the outline-only buffer adapter seams: the pure render
 * type resolution decision, vertex counting, and the no-op fallback for
 * outline-less block entity geometry.
 *
 * <p>Vanilla {@code RenderType} construction requires the full game bootstrap
 * (its class initializer loads the block/item registries), so no real render
 * type can be built in this headless JVM. The production adapter therefore
 * routes every decision through the pure {@code decide} core over extracted
 * {@code RenderType} facts, and the counting/no-op consumers are tested
 * directly with recording delegates — no game client, language resources, or
 * reflection.
 */
class OutlineOnlyBufferSourceTest {

	/**
	 * Minimal recording {@link VertexConsumer} delegate; mirrors the vanilla
	 * interface's abstract methods only (the rest are interface defaults).
	 */
	private static final class RecordingVertexConsumer implements VertexConsumer {

		private int vertices;

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			vertices++;
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

	// --- pure resolution decision ---

	@Test
	void outlineRenderTypesAreUsedAsIs() {
		assertSame(AS_IS, OutlineOnlyBufferSource.decide(true, false, false));
		assertSame(AS_IS, OutlineOnlyBufferSource.decide(true, true, false));
		assertSame(AS_IS, OutlineOnlyBufferSource.decide(true, false, true));
	}

	@Test
	void textureSpecificOutlineVariantIsPreferredOverBlocksAtlasFallback() {
		assertSame(OUTLINE_VARIANT, OutlineOnlyBufferSource.decide(false, true, false));
		assertSame(OUTLINE_VARIANT, OutlineOnlyBufferSource.decide(false, true, true));
	}

	@Test
	void outlineLessTypesResolveToNoOpOrBlocksAtlasFallback() {
		// Actual BlockEntity route: no outline variant and no fallback -> no-op.
		assertSame(NO_OP, OutlineOnlyBufferSource.decide(false, false, false));

		// Ordinary BlockDisplay route: falls back to the blocks-atlas outline.
		assertSame(BLOCKS_ATLAS, OutlineOnlyBufferSource.decide(false, false, true));
	}

	// --- counting ---

	@Test
	void countingConsumerCountsEveryVertexAndForwards() {
		RecordingVertexConsumer delegate = new RecordingVertexConsumer();
		VertexCountingConsumer consumer = new VertexCountingConsumer(delegate);

		assertEquals(0, consumer.vertices());

		consumer
			.addVertex(0.0F, 0.0F, 0.0F)
			.setColor(255, 255, 255, 255)
			.setUv(0.0F, 0.0F)
			.addVertex(1.0F, 0.0F, 0.0F)
			.setUv(1.0F, 0.0F)
			.addVertex(1.0F, 1.0F, 0.0F)
			.addVertex(0.0F, 1.0F, 0.0F);

		assertEquals(4, consumer.vertices());
		assertEquals(4, delegate.vertices);

		// A fresh attempt always starts at zero.
		VertexCountingConsumer second = new VertexCountingConsumer(new RecordingVertexConsumer());
		assertEquals(0, second.vertices());
	}

	@Test
	void countingConsumerIsPerAttemptAndStartsAtZero() {
		VertexCountingConsumer first = new VertexCountingConsumer(new RecordingVertexConsumer());
		VertexCountingConsumer second = new VertexCountingConsumer(new RecordingVertexConsumer());

		first.addVertex(0.0F, 0.0F, 0.0F);
		first.addVertex(1.0F, 0.0F, 0.0F);

		assertEquals(2, first.vertices());
		assertEquals(0, second.vertices());
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

	@Test
	void decisionCoverageIsComplete() {
		// Pin the full decision matrix so a future routing change is noticed.
		assertEquals(AS_IS, OutlineOnlyBufferSource.decide(true, true, true));
		assertEquals(AS_IS, OutlineOnlyBufferSource.decide(true, true, false));
		assertEquals(AS_IS, OutlineOnlyBufferSource.decide(true, false, true));
		assertEquals(AS_IS, OutlineOnlyBufferSource.decide(true, false, false));
		assertEquals(OUTLINE_VARIANT, OutlineOnlyBufferSource.decide(false, true, true));
		assertEquals(OUTLINE_VARIANT, OutlineOnlyBufferSource.decide(false, true, false));
		assertEquals(BLOCKS_ATLAS, OutlineOnlyBufferSource.decide(false, false, true));
		assertEquals(NO_OP, OutlineOnlyBufferSource.decide(false, false, false));
	}
}
