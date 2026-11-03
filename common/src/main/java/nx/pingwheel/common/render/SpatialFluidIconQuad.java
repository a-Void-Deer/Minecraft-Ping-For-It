package nx.pingwheel.common.render;

import java.util.List;
import java.util.Objects;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** One unlit GUI quad, isolated from both queued GUI geometry and item/glint routes. */
@NotNullByDefault
final class SpatialFluidIconQuad {

	private SpatialFluidIconQuad() {}

	static @Nullable ShaderInstance shader() { return GameRenderer.getRendertypeEntityTranslucentEmissiveShader(); }
	static VertexFormat format() { return DefaultVertexFormat.NEW_ENTITY; }

	static void draw(GuiGraphics graphics, TextureAtlasSprite sprite, int x, int y,
		float red, float green, float blue, float alpha) {
		draw(graphics, sprite, x, y, red, green, blue, alpha, new NativePass());
	}

	/** The actual buffer/flush/restore lifecycle; the port replaces only GL work. */
	static void draw(GuiGraphics graphics, TextureAtlasSprite sprite, int x, int y,
		float red, float green, float blue, float alpha, Pass pass) {
		if (alpha <= 0) return;
		Runnable restore = pass.capture();
		try {
			// Capture before flush: GuiGraphics.flush itself enables depth testing.
			graphics.flush();
			try (var storage = new ByteBufferBuilder(4 * format().getVertexSize())) {
				var vertices = new BufferBuilder(storage, VertexFormat.Mode.QUADS, format());
				emit(vertices, graphics.pose().last().pose(), sprite, x, y, red, green, blue);
				try (var mesh = vertices.buildOrThrow()) {
					ShaderInstance shader = Objects.requireNonNull(shader(), "fluid GUI shader unavailable");
					Runnable restoreUniforms = captureUniforms(shader);
					try { pass.submit(shader, sprite.atlasLocation(), mesh, () -> configureShader(shader, alpha)); }
					finally { restoreUniforms.run(); }
				}
			}
		} finally {
			restore.run();
		}
	}

	private static void emit(VertexConsumer vertices, Matrix4f pose, TextureAtlasSprite sprite, int x, int y,
		float red, float green, float blue) {
		vertex(vertices, pose, x, y, sprite.getU0(), sprite.getV0(), red, green, blue);
		vertex(vertices, pose, x, y + 16, sprite.getU0(), sprite.getV1(), red, green, blue);
		vertex(vertices, pose, x + 16, y + 16, sprite.getU1(), sprite.getV1(), red, green, blue);
		vertex(vertices, pose, x + 16, y, sprite.getU1(), sprite.getV0(), red, green, blue);
	}

	private static void vertex(VertexConsumer vertices, Matrix4f pose, int x, int y, float u, float v,
		float red, float green, float blue) {
		// Emissive is still NEW_ENTITY: supply every attribute, never transition alpha.
		vertices.addVertex(pose, x, y, 0).setColor(red, green, blue, 1).setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY).setLight(15728880).setNormal(0, 0, 1);
	}

	/** Shader-local values, not global lighting/color/fog mutations. */
	static void configureShader(ShaderInstance shader, float alpha) {
		shader.safeGetUniform("ColorModulator").set(1, 1, 1, alpha);
		// minecraft_mix_light: (1 + 0) * .6 + .4 = 1, independent of the GUI pose.
		shader.safeGetUniform("Light0_Direction").set(0f, 0f, 1f);
		shader.safeGetUniform("Light1_Direction").set(0f, 0f, 0f);
		shader.safeGetUniform("FogStart").set(Float.MAX_VALUE);
		shader.safeGetUniform("FogEnd").set(Float.MAX_VALUE);
	}

	static Runnable captureUniforms(ShaderInstance shader) {
		@Unmodifiable List<UniformValue> values = List.of(new UniformValue(shader.COLOR_MODULATOR),
			new UniformValue(shader.LIGHT0_DIRECTION), new UniformValue(shader.LIGHT1_DIRECTION),
			new UniformValue(shader.FOG_START), new UniformValue(shader.FOG_END));
		return () -> values.forEach(UniformValue::restore);
	}

	@NotNullByDefault
	private static final class UniformValue {
		private final @Nullable Uniform uniform;
		private final float @Unmodifiable [] values;
		UniformValue(@Nullable Uniform uniform) {
			this.uniform = uniform;
			values = uniform == null ? new float[0] : new float[uniform.getFloatBuffer().capacity()];
			if (uniform != null) uniform.getFloatBuffer().get(0, values);
		}
		void restore() { if (uniform != null) uniform.set(values); }
	}

	@NotNullByDefault
	interface Pass {
		Runnable capture();
		void submit(ShaderInstance shader, ResourceLocation atlas, MeshData mesh, Runnable configure);
	}

	@NotNullByDefault
	private static final class NativePass implements Pass {
		@Override public Runnable capture() { return captureState(NATIVE_STATE); }

		@Override public void submit(ShaderInstance shader, ResourceLocation atlas, MeshData mesh, Runnable configure) {
			try {
				var game = Minecraft.getInstance();
				game.gameRenderer.overlayTexture().setupOverlayColor();
				RenderSystem.enableBlend();
				RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
					GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
				RenderSystem.disableDepthTest();
				RenderSystem.depthMask(false);
				RenderSystem.disableCull();
				// Use the host's immediate NEW_ENTITY VBO, never the GUI or item buffer source.
				var buffer = format().getImmediateDrawVertexBuffer();
				buffer.bind();
				buffer.upload(mesh);
				shader.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(),
					RenderSystem.getProjectionMatrix(), game.getWindow());
				shader.setSampler("Sampler0", game.getTextureManager().getTexture(atlas).getId());
				configure.run();
				shader.apply();
				buffer.draw();
			} finally {
				shader.clear();
			}
		}
	}

	static Runnable captureState(StateAccess state) { return new State(state)::restore; }

	/** Host state seam tests the actual snapshot/restoration without a GL context. */
	@NotNullByDefault
	interface StateAccess {
		int integer(int name);
		boolean enabled(int name);
		@Nullable ShaderInstance shader();
		float[] color();
		int shaderTexture(int slot);
		void invalidateBuffers();
		void bindVertexArray(int value);
		void bindArrayBuffer(int value);
		void activeTexture(int value);
		void texture(int value);
		void shaderTexture(int slot, int value);
		void program(int value);
		void shader(@Nullable ShaderInstance value);
		void color(float @Unmodifiable [] value);
		void depthFunction(int value);
		void depthMask(boolean value);
		void enabled(int name, boolean value);
		void blend(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha);
	}

	private static final StateAccess NATIVE_STATE = new StateAccess() {
		public int integer(int name) { return GL11.glGetInteger(name); }
		public boolean enabled(int name) { return GL11.glIsEnabled(name); }
		public @Nullable ShaderInstance shader() { return RenderSystem.getShader(); }
		public float[] color() { return RenderSystem.getShaderColor(); }
		public int shaderTexture(int slot) { return RenderSystem.getShaderTexture(slot); }
		public void invalidateBuffers() { BufferUploader.invalidate(); }
		public void bindVertexArray(int value) { GlStateManager._glBindVertexArray(value); }
		public void bindArrayBuffer(int value) { GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, value); }
		public void activeTexture(int value) { RenderSystem.activeTexture(value); }
		public void texture(int value) { RenderSystem.bindTexture(value); }
		public void shaderTexture(int slot, int value) { RenderSystem.setShaderTexture(slot, value); }
		public void program(int value) { ProgramManager.glUseProgram(value); }
		public void shader(@Nullable ShaderInstance value) { RenderSystem.setShader(() -> value); }
		public void color(float @Unmodifiable [] value) { RenderSystem.setShaderColor(value[0], value[1], value[2], value[3]); }
		public void depthFunction(int value) { RenderSystem.depthFunc(value); }
		public void depthMask(boolean value) { RenderSystem.depthMask(value); }
		public void enabled(int name, boolean value) {
			switch (name) {
				case GL11.GL_DEPTH_TEST -> { if (value) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest(); }
				case GL11.GL_BLEND -> { if (value) RenderSystem.enableBlend(); else RenderSystem.disableBlend(); }
				case GL11.GL_CULL_FACE -> { if (value) RenderSystem.enableCull(); else RenderSystem.disableCull(); }
				default -> throw new IllegalArgumentException("unsupported fluid GUI state: " + name);
			}
		}
		public void blend(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
			RenderSystem.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
		}
	};

	/** Only state touched by GUI flushing, shader submission or this quad is restored. Scissor/pose stay untouched. */
	@NotNullByDefault
	private static final class State {
		private final StateAccess host;
		private final @Nullable ShaderInstance shader;
		private final float @Unmodifiable [] color;
		private final boolean depth, depthMask, blend, cull;
		private final int sourceRgb, destinationRgb, sourceAlpha, destinationAlpha;
		private final int depthFunction, program, vertexArray, vertexBuffer, activeTexture;
		private final int @Unmodifiable [] textures = new int[3];
		private final int @Unmodifiable [] shaderTextures = new int[3];

		State(StateAccess host) {
			this.host = host;
			shader = host.shader(); color = host.color().clone();
			depth = host.enabled(GL11.GL_DEPTH_TEST); depthMask = host.integer(GL11.GL_DEPTH_WRITEMASK) != 0;
			blend = host.enabled(GL11.GL_BLEND); cull = host.enabled(GL11.GL_CULL_FACE);
			sourceRgb = host.integer(GL14.GL_BLEND_SRC_RGB); destinationRgb = host.integer(GL14.GL_BLEND_DST_RGB);
			sourceAlpha = host.integer(GL14.GL_BLEND_SRC_ALPHA); destinationAlpha = host.integer(GL14.GL_BLEND_DST_ALPHA);
			depthFunction = host.integer(GL11.GL_DEPTH_FUNC); program = host.integer(GL20.GL_CURRENT_PROGRAM);
			vertexArray = host.integer(GL30.GL_VERTEX_ARRAY_BINDING); vertexBuffer = host.integer(GL15.GL_ARRAY_BUFFER_BINDING);
			activeTexture = host.integer(GL13.GL_ACTIVE_TEXTURE);
			try {
				for (int slot = 0; slot < textures.length; slot++) {
					host.activeTexture(GL13.GL_TEXTURE0 + slot);
					textures[slot] = host.integer(GL11.GL_TEXTURE_BINDING_2D);
					shaderTextures[slot] = host.shaderTexture(slot);
				}
			} finally { host.activeTexture(activeTexture); }
		}

		void restore() {
			try {
				host.invalidateBuffers();
				host.bindVertexArray(vertexArray);
				host.bindArrayBuffer(vertexBuffer);
				try {
					for (int slot = 0; slot < textures.length; slot++) {
						host.shaderTexture(slot, shaderTextures[slot]);
						host.activeTexture(GL13.GL_TEXTURE0 + slot);
						host.texture(textures[slot]);
					}
				} finally { host.activeTexture(activeTexture); }
			} finally {
				try { host.program(program); host.shader(shader); }
				finally {
					try { host.color(color); }
					finally {
						host.depthFunction(depthFunction);
						host.depthMask(depthMask);
						host.enabled(GL11.GL_DEPTH_TEST, depth);
						host.blend(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
						host.enabled(GL11.GL_BLEND, blend);
						host.enabled(GL11.GL_CULL_FACE, cull);
					}
				}
			}
		}
	}
}
