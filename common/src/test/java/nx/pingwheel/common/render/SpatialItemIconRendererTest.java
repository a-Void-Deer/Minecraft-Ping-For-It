package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native type/shard selection and lifecycle seams, not rendered pixels or a GL context. */
class SpatialItemIconRendererTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void stoneCutoutAndTranslucentSheetsInstallAlphaStateAfterNativeState() {
		RenderType stone = ItemBlockRenderTypes.getRenderType(new ItemStack(Items.STONE), true);
		assertSame(Sheets.cutoutBlockSheet(), stone, "exercise the actual stone GUI item route");
		for (RenderType nativeType : List.of(stone, Sheets.translucentCullBlockSheet(), Sheets.translucentItemSheet())) {
			var host = new RecordingState();
			var route = new SpatialItemIconRenderer.AlphaType(nativeType, 0.025f, 0.65f, host);
			assertSame(nativeType, route.original());
			assertSame(nativeType.format(), route.format());
			assertSame(nativeType.mode(), route.mode());
			assertFalse(route.isGlintRoute());
			assertSame(ShardAccess.alphaBlend(), route.alphaBlend());
			assertSame(ShardAccess.alphaShader(), route.alphaShader());
			route.setupRenderState();
			assertEquals("native setup", host.events.getFirst());
			assertTrue(host.setupShards.contains(ShardAccess.alphaBlend()), "cutout's disabled blend is replaced inside route setup");
			assertEquals("color", host.events.getLast());
			assertEquals(0.025f, host.alpha, "sub-cutoff alpha remains modulation, not discard");
			assertEquals(0.65f, host.rgb);
			route.clearRenderState();
			assertTrue(host.clearShards.contains(ShardAccess.alphaBlend()));
		}
	}

	@Test
	void actualBufferAdapterRemapsEveryRequestedTypeBeforeDelegationAndPreparesItOnlyOnce() {
		var prepared = new ArrayList<SpatialItemIconRenderer.AlphaType>();
		var delegated = new ArrayList<RenderType>();
		var adapter = SpatialItemIconRenderer.alphaBuffers(type -> { delegated.add(type); return null; },
			0.37f, 0.65f, prepared::add);
		adapter.getBuffer(Sheets.cutoutBlockSheet());
		adapter.getBuffer(Sheets.translucentCullBlockSheet());
		adapter.getBuffer(Sheets.cutoutBlockSheet());
		assertEquals(2, prepared.size());
		assertEquals(3, delegated.size());
		assertSame(prepared.getFirst(), delegated.getFirst());
		assertSame(prepared.getFirst(), delegated.getLast());
		assertSame(Sheets.cutoutBlockSheet(), prepared.getFirst().original());
		assertSame(Sheets.translucentCullBlockSheet(), prepared.getLast().original());
		assertSame(ShardAccess.alphaBlend(), prepared.getFirst().alphaBlend());
	}

	@Test
	void baseDepthIsSubmittedBeforeGlintRegardlessOfNativeRequestOrder() {
		var glint = new SpatialItemIconRenderer.AlphaType(RenderType.glint(), 0.37f, 1.0f);
		var base = new SpatialItemIconRenderer.AlphaType(Sheets.cutoutBlockSheet(), 0.37f, 1.0f);
		var otherBase = new SpatialItemIconRenderer.AlphaType(Sheets.bedSheet(), 0.37f, 1.0f);
		var flushed = new ArrayList<RenderType>();
		SpatialItemIconRenderer.flushRoutes(List.of(glint, base, otherBase), flushed::add);
		assertEquals(List.of(base, otherBase, glint), flushed);
	}

	@Test
	void nativeSpecialItemSurfaceRoutesRetainGeometryAndGetTheSameAlphaCompatibleShader() {
		ResourceLocation skull = ResourceLocation.withDefaultNamespace("textures/entity/creeper/creeper.png");
		for (RenderType nativeType : List.of(Sheets.bedSheet(), Sheets.chestSheet(), Sheets.shulkerBoxSheet(),
			Sheets.bannerSheet(), Sheets.shieldSheet(), Sheets.solidBlockSheet(),
			RenderType.entityCutoutNoCullZOffset(skull), RenderType.entityTranslucent(skull))) {
			var host = new RecordingState();
			var route = new SpatialItemIconRenderer.AlphaType(nativeType, 0.37f, 1.0f, host);
			route.setupRenderState();
			assertSame(nativeType.format(), route.format());
			assertSame(nativeType.mode(), route.mode());
			assertEquals(nativeType.sortOnUpload(), route.sortOnUpload());
			assertSame(ShardAccess.alphaShader(), host.setupShards.getFirst());
			assertSame(ShardAccess.alphaBlend(), host.setupShards.getLast());
			assertEquals(0.37f, host.alpha);
			route.clearRenderState();
		}
	}

	@Test
	void glintKeepsItsNativeBlendButItsRgbContributionFadesWithoutAlphaCutoff() {
		for (RenderType glint : List.of(RenderType.glint(), RenderType.glintTranslucent(), RenderType.entityGlint(),
			RenderType.entityGlintDirect(), RenderType.armorEntityGlint())) {
			var host = new RecordingState();
			var route = new SpatialItemIconRenderer.AlphaType(glint, 0.25f, 1.0f, host);
			assertTrue(route.isGlintRoute());
			route.setupRenderState();
			assertTrue(host.setupShards.isEmpty(), "glint shader/texturing/depth/blend remain native");
			assertEquals(1.0f, host.alpha);
			assertEquals(0.5f, host.rgb, "SRC_COLOR blending squares RGB, so half RGB is quarter contribution");
			route.clearRenderState();
			assertTrue(host.clearShards.isEmpty());
		}
	}

	@Test
	void nativeClearFailureStillClearsTheInjectedAlphaState() {
		var host = new RecordingState();
		host.failClear = true;
		var route = new SpatialItemIconRenderer.AlphaType(Sheets.cutoutBlockSheet(), 0.37f, 1.0f, host);
		assertThrows(IllegalStateException.class, route::clearRenderState);
		assertTrue(host.clearShards.contains(ShardAccess.alphaBlend()));
	}

	@Test
	void iconPoseMatchesNativeGuiTransformWithoutMutatingOrSharingCallerPose() {
		var gui = new PoseStack();
		gui.translate(50.0f, 70.0f, 3.0f);
		gui.scale(0.8f, 1.2f, 1.0f);
		gui.mulPose(new Quaternionf().rotationZ(0.2f));
		var beforePose = new Matrix4f(gui.last().pose());
		var beforeNormal = new Matrix3f(gui.last().normal());
		var icon = SpatialItemIconRenderer.iconPose(gui, 12, 20);
		var expected = new Matrix4f(beforePose).translate(20.0f, 28.0f, 150.0f).scale(16.0f, -16.0f, 16.0f);
		assertEquals(expected, icon.last().pose());
		assertEquals(1.0f, icon.last().transformNormal(1, 1, 1, new Vector3f()).length(), 1.0e-6f);
		icon.pushPose(); // Simulate a renderer throwing without balancing its push.
		icon.scale(2.0f, 2.0f, 2.0f);
		assertEquals(beforePose, gui.last().pose());
		assertEquals(beforeNormal, gui.last().normal());
		assertTrue(gui.clear());
	}

	@Test
	void successfulPassFlushesBeforeDisposalAndStateRestoration() {
		var events = new ArrayList<String>();
		SpatialItemIconRenderer.runPass(() -> events.add("render"), () -> events.add("flush"),
			() -> events.add("dispose"), () -> events.add("restore color/light/depth"));
		assertEquals(List.of("render", "flush", "dispose", "restore color/light/depth"), events);
	}

	@Test
	void renderAndFlushFailuresStillDisposeAndRestoreAndPartialRenderIsNeverSubmitted() {
		for (boolean failRender : List.of(true, false)) {
			var events = new ArrayList<String>();
			var failure = new IllegalStateException("item failure");
			var thrown = assertThrows(IllegalStateException.class, () -> SpatialItemIconRenderer.runPass(() -> {
				events.add("render");
				if (failRender) throw failure;
			}, () -> { events.add("flush"); throw failure; }, () -> events.add("dispose"), () -> events.add("restore")));
			assertSame(failure, thrown);
			assertEquals(failRender ? List.of("render", "dispose", "restore")
				: List.of("render", "flush", "dispose", "restore"), events);
		}
	}

	@Test
	void disposalFailureCannotSkipShaderColorRestoration() {
		var events = new ArrayList<String>();
		assertThrows(IllegalStateException.class, () -> SpatialItemIconRenderer.runPass(() -> events.add("render"),
			() -> events.add("flush"), () -> { events.add("dispose"); throw new IllegalStateException(); },
			() -> events.add("restore")));
		assertEquals(List.of("render", "flush", "dispose", "restore"), events);
	}

	private static final class RecordingState implements SpatialItemIconRenderer.StateAccess {
		final List<String> events = new ArrayList<>();
		final List<RenderStateShard> setupShards = new ArrayList<>();
		final List<RenderStateShard> clearShards = new ArrayList<>();
		float rgb;
		float alpha;
		boolean failClear;
		public void setupNative(RenderType type) { events.add("native setup"); }
		public void clearNative(RenderType type) {
			events.add("native clear");
			if (failClear) throw new IllegalStateException();
		}
		public void setup(RenderStateShard shard) { setupShards.add(shard); events.add("injected setup"); }
		public void clear(RenderStateShard shard) { clearShards.add(shard); events.add("injected clear"); }
		public void color(float rgb, float alpha) { this.rgb = rgb; this.alpha = alpha; events.add("color"); }
	}

	private static final class ShardAccess extends RenderStateShard {
		private ShardAccess() { super("test", () -> {}, () -> {}); }
		static RenderStateShard alphaBlend() { return TRANSLUCENT_TRANSPARENCY; }
		static RenderStateShard alphaShader() { return RENDERTYPE_ENTITY_TRANSLUCENT_SHADER; }
	}
}
