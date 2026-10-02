package nx.pingwheel.common.render;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** GUI-item-only alpha routes. No world render type, global hook or persistent GPU allocation. */
final class SpatialItemIconRenderer {

	private SpatialItemIconRenderer() {}

	static void draw(GuiGraphics graphics, ItemStack stack, int x, int y, float tint, float alpha) {
		if (stack.isEmpty() || alpha <= 0.0f) {
			return;
		}
		Minecraft game = Minecraft.getInstance();
		var renderer = game.getItemRenderer();
		var model = renderer.getModel(stack, game.level, game.player, 0);
		// The isolated pass cannot flush or tint already queued panel/text data.
		graphics.flush();
		float[] previousColor = RenderSystem.getShaderColor().clone();
		boolean flat = !model.usesBlockLight();
		PoseStack pose = iconPose(graphics.pose(), x, y);
		Buffers buffers = new Buffers(alpha, tint);
		runPass(() -> {
				if (flat) Lighting.setupForFlatItems();
				renderer.render(stack, ItemDisplayContext.GUI, false, pose, buffers,
					15728880, OverlayTexture.NO_OVERLAY, model);
			}, () -> {
				RenderSystem.disableDepthTest();
				buffers.flush();
			}, buffers::close, () -> {
				try {
					RenderSystem.enableDepthTest();
					if (flat) Lighting.setupFor3DItems();
				} finally {
					RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
				}
			});
	}

	/** Failure never flushes partial item geometry; resource/state cleanup still runs. */
	static void runPass(Runnable render, Runnable flush, Runnable dispose, Runnable restore) {
		try {
			render.run();
			flush.run();
		} finally {
			try {
				dispose.run();
			} finally {
				restore.run();
			}
		}
	}

	/** Own pose, so even an item renderer throwing after a push cannot corrupt the GUI pose. */
	static PoseStack iconPose(PoseStack guiPose, int x, int y) {
		PoseStack icon = new PoseStack();
		// Use the host multiplication API so non-uniform GUI scaling carries
		// its untrusted-normal flag as well as its matrices into the item pose.
		icon.mulPose(guiPose.last().pose());
		icon.translate(x + 8.0f, y + 8.0f, 150.0f);
		icon.scale(16.0f, -16.0f, 16.0f);
		return icon;
	}

	/** Each route gets a fixed buffer: foil and base consumers may be written interleaved. */
	private static final class Buffers implements MultiBufferSource, AutoCloseable {
		private final LinkedHashMap<RenderType, ByteBufferBuilder> fixed = new LinkedHashMap<>();
		private final ByteBufferBuilder shared = new ByteBufferBuilder(1536);
		private final MultiBufferSource.BufferSource source = MultiBufferSource.immediateWithBuffers(fixed, shared);
		private final MultiBufferSource mapped;

		Buffers(float alpha, float tint) {
			mapped = alphaBuffers(source, alpha, tint,
				type -> fixed.put(type, new ByteBufferBuilder(type.bufferSize())));
		}

		@Override
		public VertexConsumer getBuffer(RenderType requested) {
			return mapped.getBuffer(requested);
		}

		void flush() {
			// Native getFoilBuffer asks for glint BEFORE the base consumer. Its
			// EQUAL depth test needs the base written first, not insertion order.
			flushRoutes(fixed.keySet(), source::endBatch);
		}

		@Override
		public void close() {
			// Failure discards unsent geometry rather than submitting it after color restoration.
			for (ByteBufferBuilder buffer : fixed.values()) {
				buffer.close();
			}
			shared.close();
		}
	}

	/** Native requests are remapped before any buffer is obtained, including custom native item models. */
	static MultiBufferSource alphaBuffers(MultiBufferSource destination, float alpha, float tint,
		Consumer<AlphaType> prepare) {
		Map<RenderType, AlphaType> routes = new IdentityHashMap<>();
		return requested -> {
			AlphaType route = routes.computeIfAbsent(requested, type -> {
				AlphaType mapped = new AlphaType(type, alpha, tint);
				prepare.accept(mapped);
				return mapped;
			});
			return destination.getBuffer(route);
		};
	}

	static void flushRoutes(Iterable<RenderType> routes, Consumer<RenderType> flush) {
		for (boolean glint : new boolean[] { false, true }) {
			for (RenderType type : routes) {
				if (((AlphaType) type).isGlintRoute() == glint) flush.accept(type);
			}
		}
	}

	/** Actual route installed into the item-only buffer source, not just an outside blend toggle. */
	static final class AlphaType extends RenderType {
		private final RenderType original;
		private final float alpha;
		private final float tint;
		private final boolean glint;
		private final StateAccess state;

		AlphaType(RenderType original, float alpha, float tint) {
			this(original, alpha, tint, NATIVE_STATE);
		}

		AlphaType(RenderType original, float alpha, float tint, StateAccess state) {
			super("pingforit_spatial_item", original.format(), original.mode(), original.bufferSize(),
				original.affectsCrumbling(), original.sortOnUpload(), () -> {}, () -> {});
			this.original = original;
			this.alpha = alpha;
			this.tint = tint;
			this.glint = isGlint(original);
			this.state = state;
			if (!glint && original.format() != DefaultVertexFormat.NEW_ENTITY) {
				throw new IllegalArgumentException("unsupported GUI item vertex format: " + original.format());
			}
		}

		RenderType original() { return original; }
		boolean isGlintRoute() { return glint; }
		float surfaceAlpha() { return glint ? 1.0f : alpha; }
		float colorMultiplier() { return glint ? tint * (float) Math.sqrt(alpha) : tint; }
		RenderStateShard alphaShader() { return RENDERTYPE_ENTITY_TRANSLUCENT_SHADER; }
		RenderStateShard alphaBlend() { return TRANSLUCENT_TRANSPARENCY; }

		@Override
		public void setupRenderState() {
			state.setupNative(original);
			if (!glint) {
				// Unlike translucent-cull's shader, this shader discards texture alpha
				// BEFORE modulation. Low transition opacity therefore never cuts out
				// the entire icon. All native item surfaces use NEW_ENTITY vertices.
				state.setup(alphaShader());
				state.setup(LIGHTMAP);
				state.setup(OVERLAY);
				state.setup(alphaBlend());
			}
			// Native glint uses SRC_COLOR/ONE, not alpha blending. Scaling RGB by
			// sqrt(opacity) scales its squared additive contribution by opacity,
			// while alpha stays one so its shader's alpha cutoff does not snap.
			float rgb = colorMultiplier();
			state.color(rgb, surfaceAlpha());
		}

		@Override
		public void clearRenderState() {
			try {
				state.clearNative(original);
			} finally {
				if (!glint) {
					state.clear(OVERLAY);
					state.clear(LIGHTMAP);
					state.clear(alphaBlend());
				}
			}
		}

		@Override
		public void draw(MeshData mesh) {
			try {
				setupRenderState();
				BufferUploader.drawWithShader(mesh);
			} finally {
				clearRenderState();
			}
		}
	}

	/** Narrow host-state seam; tests exercise route setup/cleanup without creating a GL context. */
	interface StateAccess {
		void setupNative(RenderType type);
		void clearNative(RenderType type);
		void setup(RenderStateShard shard);
		void clear(RenderStateShard shard);
		void color(float rgb, float alpha);
	}

	private static final StateAccess NATIVE_STATE = new StateAccess() {
		public void setupNative(RenderType type) { type.setupRenderState(); }
		public void clearNative(RenderType type) { type.clearRenderState(); }
		public void setup(RenderStateShard shard) { shard.setupRenderState(); }
		public void clear(RenderStateShard shard) { shard.clearRenderState(); }
		public void color(float rgb, float alpha) { RenderSystem.setShaderColor(rgb, rgb, rgb, alpha); }
	};

	private static boolean isGlint(RenderType type) {
		return type == RenderType.glint() || type == RenderType.glintTranslucent()
			|| type == RenderType.entityGlint() || type == RenderType.entityGlintDirect()
			|| type == RenderType.armorEntityGlint();
	}
}
