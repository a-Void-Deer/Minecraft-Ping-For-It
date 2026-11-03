package nx.pingwheel.common.render;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** Real stock shader assets plus production mesh/uniform/lifecycle seams; deliberately not GPU evidence. */
@NotNullByDefault
class SpatialFluidIconQuadTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void fivePercentOpacityStillContributesForAnOpaqueTexel() throws Exception {
		String old = asset("position_tex_color.fsh").replaceAll("\\s+", " ");
		assertTrue(old.contains("texture(Sampler0, texCoord0) * vertexColor; if (color.a < 0.1)"));
		assertTrue(old.indexOf("discard;") < old.indexOf("fragColor = color * ColorModulator;"));
		assertEquals(0, stockAlpha(old, 1, .05f, 1), "RED pipe: texture 1 × vertex .05 is discarded");
		try (var fixture = new ShaderFixture(); var contents = contents()) {
			String fragment = fragmentFor(fixture.shader);
			var graphics = graphics();
			var pass = new RecordingPass(fixture.shader, graphics);
			SpatialFluidIconQuad.draw(graphics, new Sprite(contents), 0, 0, 1, 1, 1, .05f, pass);
			assertEquals(.05f, stockAlpha(fragment, 1, pass.vertexAlpha, pass.modulatorAlpha), 1e-7f);
		}
	}
	@Test void textureCleanupFailureCannotSkipColorDepthBlendOrActiveTextureRestoration() throws Exception {
		var state = new RecordingState();
		state.integers.put(GL11.GL_DEPTH_WRITEMASK, 1); state.integers.put(GL11.GL_DEPTH_FUNC, GL11.GL_GREATER);
		state.integers.put(GL20.GL_CURRENT_PROGRAM, 0); state.integers.put(GL30.GL_VERTEX_ARRAY_BINDING, 0); state.integers.put(GL15.GL_ARRAY_BUFFER_BINDING, 0);
		state.integers.put(GL14.GL_BLEND_SRC_RGB, GL11.GL_ONE); state.integers.put(GL14.GL_BLEND_DST_RGB, GL11.GL_ZERO);
		state.integers.put(GL14.GL_BLEND_SRC_ALPHA, GL11.GL_ONE); state.integers.put(GL14.GL_BLEND_DST_ALPHA, GL11.GL_ZERO);
		state.enabled.put(GL11.GL_DEPTH_TEST, false); state.enabled.put(GL11.GL_BLEND, true); state.enabled.put(GL11.GL_CULL_FACE, false);
		state.active = GL13.GL_TEXTURE0 + 5;
		var expectedColor = state.color.clone();
		Runnable restore = SpatialFluidIconQuad.captureState(state);
		state.color[3] = .05f; state.enabled.replaceAll((key, value) -> !value); state.failTexture = true;
		assertThrows(IllegalStateException.class, restore::run);
		assertArrayEquals(expectedColor, state.color);
		assertFalse(state.enabled.get(GL11.GL_DEPTH_TEST)); assertTrue(state.enabled.get(GL11.GL_BLEND)); assertFalse(state.enabled.get(GL11.GL_CULL_FACE));
		assertEquals(GL13.GL_TEXTURE0 + 5, state.active);
	}

	@Test void actualSelectedStockPipelineHasTextureOnlyCutoffBeforeTransitionModulation() throws Exception {
		try (var fixture = new ShaderFixture()) {
			assertSame(fixture.shader, SpatialFluidIconQuad.shader(), "exercise the production GameRenderer shader getter");
			assertSame(DefaultVertexFormat.NEW_ENTITY, SpatialFluidIconQuad.format());
			String fragment = fragmentFor(fixture.shader).replaceAll("\\s+", " ");
			assertTrue(fragment.contains("vec4 color = texture(Sampler0, texCoord0); if (color.a < 0.1) { discard; } color *= vertexColor * ColorModulator;"));
			assertTrue(fragment.contains("mix(overlayColor.rgb, color.rgb, overlayColor.a)"));
			assertTrue(fragment.contains("linear_fog_fade(vertexDistance, FogStart, FogEnd)"));
			String vertex = asset(fixture.shader.getName() + ".vsh");
			for (String input : List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal")) {
				assertTrue(vertex.matches("(?s).*in \\w+ " + input + ";.*"), "NEW_ENTITY input: " + input);
			}
			assertTrue(vertex.contains("minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color)"));
			assertTrue(vertex.contains("texelFetch(Sampler1, UV1, 0)"));
			assertFalse(vertex.contains("texelFetch(Sampler2"), "emissive GUI quad has no world lightmap color");
			String fog = resource("/assets/minecraft/shaders/include/fog.glsl").replaceAll("\\s+", " ");
			assertTrue(fog.contains("if (vertexDistance <= fogStart) { return 1.0; }"));
		}
	}

	@Test void minecraftGetterReallyLoadsTheStockNewEntityShaderAndNativeSubmissionConfiguresAfterDefaults() throws Exception {
		var renderer = bytecode(SpatialFluidIconRenderer.class);
		var entry = renderer.methods.stream().filter(method -> method.name.equals("draw")
			&& org.objectweb.asm.Type.getArgumentTypes(method.desc).length == 6).findFirst().orElseThrow();
		assertTrue(java.util.Arrays.stream(entry.instructions.toArray()).anyMatch(instruction -> instruction instanceof InvokeDynamicInsnNode dynamic
			&& java.util.Arrays.stream(dynamic.bsmArgs).anyMatch(argument -> argument instanceof org.objectweb.asm.Handle handle
				&& handle.getOwner().equals(SpatialFluidIconQuad.class.getName().replace('.', '/')) && handle.getName().equals("draw"))),
			"default fluid draw selects the tested isolated quad, never GuiGraphics colored blit");
		// A CPU ShaderInstance cannot link GL programs; validate the actual host constructor/getter binding instead.
		var host = bytecode(GameRenderer.class);
		var getter = host.methods.stream().filter(method -> method.name.equals("getRendertypeEntityTranslucentEmissiveShader")).findFirst().orElseThrow();
		var field = java.util.Arrays.stream(getter.instructions.toArray()).filter(FieldInsnNode.class::isInstance)
			.map(FieldInsnNode.class::cast).filter(instruction -> instruction.getOpcode() == Opcodes.GETSTATIC).findFirst().orElseThrow();
		var assignment = host.methods.stream().filter(method -> java.util.Arrays.stream(method.instructions.toArray())
			.anyMatch(instruction -> instruction instanceof FieldInsnNode value && value.getOpcode() == Opcodes.PUTSTATIC && value.name.equals(field.name)))
			.findFirst().orElseThrow();
		boolean registered = false;
		for (var method : host.methods) {
			var instructions = method.instructions.toArray();
			for (int index = 0; index < instructions.length; index++) {
				if (!(instructions[index] instanceof InvokeDynamicInsnNode dynamic)
					|| java.util.Arrays.stream(dynamic.bsmArgs).noneMatch(argument -> argument instanceof org.objectweb.asm.Handle handle && handle.getName().equals(assignment.name))) continue;
				String shader = "", format = "";
				for (int previous = index - 1; previous >= 0; previous--) {
					if (instructions[previous] instanceof LdcInsnNode literal && literal.cst instanceof String text) { shader = text; break; }
					if (instructions[previous] instanceof FieldInsnNode value && value.owner.endsWith("DefaultVertexFormat")) format = value.name;
				}
				assertEquals("rendertype_entity_translucent_emissive", shader);
				assertEquals("NEW_ENTITY", format);
				registered = true;
			}
		}
		assertTrue(registered, "real GameRenderer reload binds this getter to the matching stock shader/format");
		var nativePass = bytecode(Class.forName(SpatialFluidIconQuad.class.getName() + "$NativePass"));
		var submit = nativePass.methods.stream().filter(method -> method.name.equals("submit")).findFirst().orElseThrow();
		var calls = java.util.Arrays.stream(submit.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
		int defaults = -1, configure = -1, apply = -1, draw = -1;
		for (int index = 0; index < calls.size(); index++) {
			var call = calls.get(index);
			if (call.name.equals("setDefaultUniforms")) defaults = index;
			if (call.owner.equals("java/lang/Runnable") && call.name.equals("run")) configure = index;
			if (call.name.equals("apply")) apply = index;
			if (call.name.equals("draw")) draw = index;
		}
		assertTrue(defaults >= 0 && defaults < configure && configure < apply && apply < draw,
			"default uniform upload must not overwrite transition/neutral GUI uniforms");
	}

	@Test void textureCutoffAndSmoothFadeUseActualProductionVertexAndUniformValues() throws Exception {
		try (var fixture = new ShaderFixture(); var contents = contents()) {
			String fragment = fragmentFor(fixture.shader);
			for (float opacity : new float[] {.05f, .099f, .1f, .37f, .75f, 1}) {
				var graphics = graphics();
				var pass = new RecordingPass(fixture.shader, graphics);
				SpatialFluidIconQuad.draw(graphics, new Sprite(contents), 4, 7, .65f, .4f, .2f, opacity, pass);
				assertEquals(1, pass.vertexAlpha, "transition is not quantized into the vertex alpha byte");
				assertEquals(opacity, pass.modulatorAlpha);
				for (float sampled : new float[] {0, .05f, .099f, .1f, .5f, 1}) {
					float expected = sampled < .1f ? 0 : sampled * opacity;
					assertEquals(expected, stockAlpha(fragment, sampled, pass.vertexAlpha, pass.modulatorAlpha), 1e-7f,
						"texture=" + sampled + " opacity=" + opacity);
				}
			}
			var graphics = graphics();
			var pass = new RecordingPass(fixture.shader, graphics);
			SpatialFluidIconQuad.draw(graphics, new Sprite(contents), 0, 0, 1, 1, 1, 0, pass);
			assertTrue(graphics.events.isEmpty(), "opacity zero contributes nothing and cannot flush/change GUI state");
			SpatialFluidIconQuad.configureShader(fixture.shader, 0);
			assertEquals(0, stockAlpha(fragment, 1, 1, fixture.shader.COLOR_MODULATOR.getFloatBuffer().get(3)));
		}
	}

	@Test void productionMeshKeepsSixteenPixelPoseUvTintAndAllNewEntityAttributes() throws Exception {
		try (var fixture = new ShaderFixture(); var contents = contents()) {
			var graphics = graphics();
			graphics.pose.translate(50, 70, 3);
			graphics.pose.scale(.8f, 1.2f, 1);
			var before = new Matrix4f(graphics.pose.last().pose());
			var sprite = new Sprite(contents);
			var pass = new RecordingPass(fixture.shader, graphics);
			SpatialFluidIconQuad.draw(graphics, sprite, 4, 7, .65f, .4f, .2f, .05f, pass);
			assertSame(TextureAtlas.LOCATION_BLOCKS, pass.atlas);
			assertEquals(before, graphics.pose.last().pose());
			assertTrue(graphics.pose.clear());
			assertEquals(VertexFormat.Mode.QUADS, pass.mode);
			assertEquals(4, pass.vertices);
			var format = SpatialFluidIconQuad.format();
			var bytes = ByteBuffer.wrap(pass.bytes).order(java.nio.ByteOrder.nativeOrder());
			int[][] corners = {{4, 7}, {4, 23}, {20, 23}, {20, 7}};
			float[][] uv = {{sprite.getU0(), sprite.getV0()}, {sprite.getU0(), sprite.getV1()},
				{sprite.getU1(), sprite.getV1()}, {sprite.getU1(), sprite.getV0()}};
			for (int index = 0; index < 4; index++) {
				int base = index * format.getVertexSize();
				int position = base + format.getOffset(VertexFormatElement.POSITION);
				var expected = before.transformPosition(corners[index][0], corners[index][1], 0, new Vector3f());
				assertEquals(expected.x, bytes.getFloat(position), 1e-5f);
				assertEquals(expected.y, bytes.getFloat(position + 4), 1e-5f);
				assertEquals(expected.z, bytes.getFloat(position + 8), 1e-5f);
				int color = base + format.getOffset(VertexFormatElement.COLOR);
				assertEquals((int)(.65f * 255), Byte.toUnsignedInt(bytes.get(color)));
				assertEquals(102, Byte.toUnsignedInt(bytes.get(color + 1)));
				assertEquals(51, Byte.toUnsignedInt(bytes.get(color + 2)));
				assertEquals(255, Byte.toUnsignedInt(bytes.get(color + 3)));
				int texture = base + format.getOffset(VertexFormatElement.UV0);
				assertEquals(uv[index][0], bytes.getFloat(texture)); assertEquals(uv[index][1], bytes.getFloat(texture + 4));
				int overlay = base + format.getOffset(VertexFormatElement.UV1);
				assertEquals(OverlayTexture.NO_OVERLAY & 65535, bytes.getShort(overlay));
				assertEquals(OverlayTexture.NO_OVERLAY >>> 16, bytes.getShort(overlay + 2));
				int light = base + format.getOffset(VertexFormatElement.UV2);
				assertEquals(240, bytes.getShort(light)); assertEquals(240, bytes.getShort(light + 2));
				int normal = base + format.getOffset(VertexFormatElement.NORMAL);
				assertEquals(0, bytes.get(normal)); assertEquals(0, bytes.get(normal + 1)); assertEquals(127, bytes.get(normal + 2));
			}
		}
	}

	@Test void shaderLocalNeutralLightingAndOpacityRestoreWithoutTouchingGlobalColorFogOrLights() throws Exception {
		try (var fixture = new ShaderFixture()) {
			float[] original = read(fixture.shader.COLOR_MODULATOR);
			float[] originalLight = read(fixture.shader.LIGHT0_DIRECTION);
			float[] globalColor = RenderSystem.getShaderColor().clone();
			float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
			Runnable restore = SpatialFluidIconQuad.captureUniforms(fixture.shader);
			SpatialFluidIconQuad.configureShader(fixture.shader, .05f);
			assertArrayEquals(new float[] {1, 1, 1, .05f}, read(fixture.shader.COLOR_MODULATOR));
			assertArrayEquals(new float[] {0, 0, 1}, read(fixture.shader.LIGHT0_DIRECTION));
			assertArrayEquals(new float[] {0, 0, 0}, read(fixture.shader.LIGHT1_DIRECTION));
			String light = resource("/assets/minecraft/shaders/include/light.glsl");
			assertTrue(light.contains("(0.6)")); assertTrue(light.contains("(0.4)"));
			assertTrue(light.contains("return vec4(color.rgb * lightAccum, color.a);"));
			assertEquals(1, Math.min(1, (1 + 0) * .6 + .4), 1e-7);
			assertTrue(fixture.shader.FOG_START.getFloatBuffer().get(0) > 1e10f);
			assertEquals(fixture.shader.FOG_START.getFloatBuffer().get(0), fixture.shader.FOG_END.getFloatBuffer().get(0));
			restore.run();
			assertArrayEquals(original, read(fixture.shader.COLOR_MODULATOR));
			assertArrayEquals(originalLight, read(fixture.shader.LIGHT0_DIRECTION));
			assertEquals(.2f, fixture.shader.FOG_START.getFloatBuffer().get(0));
			assertEquals(.8f, fixture.shader.FOG_END.getFloatBuffer().get(0));
			assertArrayEquals(globalColor, RenderSystem.getShaderColor());
			assertEquals(fogStart, RenderSystem.getShaderFogStart()); assertEquals(fogEnd, RenderSystem.getShaderFogEnd());
		}
	}

	@Test void submissionFlushOrderingResourceDisposalAndRestorationSurviveFailuresAndNextIcon() throws Exception {
		try (var fixture = new ShaderFixture(); var contents = contents()) {
			for (boolean failFlush : List.of(false, true)) {
				var graphics = graphics();
				graphics.failFlush = failFlush;
				var pass = new RecordingPass(fixture.shader, graphics);
				pass.failSubmit = !failFlush;
				assertThrows(IllegalStateException.class, () -> SpatialFluidIconQuad.draw(graphics, new Sprite(contents),
					0, 0, 1, 1, 1, .05f, pass));
				assertEquals(failFlush ? List.of("capture", "gui flush", "restore")
					: List.of("capture", "gui flush", "submit", "restore"), graphics.events);
				assertTrue(graphics.depth, "capture precedes GUI flush and restores caller state");
				assertEquals(.8f, graphics.alpha, "following GUI/icon cannot inherit transition alpha");
				assertArrayEquals(new float[] {.2f, .3f, .4f, .8f}, read(fixture.shader.COLOR_MODULATOR));
				assertArrayEquals(new float[] {.3f, .4f, .5f}, read(fixture.shader.LIGHT0_DIRECTION));
				if (!failFlush) assertThrows(IllegalStateException.class, () -> pass.submitted.vertexBuffer(), "isolated native buffer disposed even on submit failure");
				graphics.events.clear(); graphics.failFlush = false; pass.failSubmit = false;
				SpatialFluidIconQuad.draw(graphics, new Sprite(contents), 0, 0, 1, 1, 1, .37f, pass);
				assertEquals(List.of("capture", "gui flush", "submit", "restore"), graphics.events);
				assertEquals(.37f, pass.modulatorAlpha);
				assertEquals(.8f, graphics.alpha);
				assertArrayEquals(new float[] {.2f, .3f, .4f, .8f}, read(fixture.shader.COLOR_MODULATOR));
				assertThrows(IllegalStateException.class, () -> pass.submitted.vertexBuffer());
			}
		}
	}

	@Test void missingProductionShaderRestoresAfterFlushAndNeverSubmitsAnOpaqueFallback() throws Exception {
		try (var fixture = new ShaderFixture(); var contents = contents()) {
			set(GameRenderer.class, null, "rendertypeEntityTranslucentEmissiveShader", null);
			var graphics = graphics();
			var pass = new RecordingPass(fixture.shader, graphics);
			assertThrows(NullPointerException.class, () -> SpatialFluidIconQuad.draw(graphics, new Sprite(contents),
				0, 0, 1, 1, 1, .05f, pass));
			assertEquals(List.of("capture", "gui flush", "restore"), graphics.events);
			assertNull(pass.submitted);
			assertTrue(graphics.depth); assertEquals(.8f, graphics.alpha);
		}
	}

	@Test void actualStateSnapshotRestoresNonDefaultShaderDepthBlendBuffersAndAllSamplerSlots() throws Exception {
		try (var fixture = new ShaderFixture()) {
			for (boolean enabled : List.of(false, true)) {
				var state = new RecordingState();
				state.shader = enabled ? fixture.shader : null;
				state.enabled.put(GL11.GL_DEPTH_TEST, enabled); state.enabled.put(GL11.GL_BLEND, !enabled); state.enabled.put(GL11.GL_CULL_FACE, enabled);
				state.integers.put(GL11.GL_DEPTH_WRITEMASK, enabled ? 1 : 0);
				state.integers.put(GL11.GL_DEPTH_FUNC, GL11.GL_GREATER);
				state.integers.put(GL20.GL_CURRENT_PROGRAM, 71);
				state.integers.put(GL30.GL_VERTEX_ARRAY_BINDING, 72); state.integers.put(GL15.GL_ARRAY_BUFFER_BINDING, 73);
				state.integers.put(GL14.GL_BLEND_SRC_RGB, GL11.GL_ONE); state.integers.put(GL14.GL_BLEND_DST_RGB, GL11.GL_ZERO);
				state.integers.put(GL14.GL_BLEND_SRC_ALPHA, GL11.GL_DST_ALPHA); state.integers.put(GL14.GL_BLEND_DST_ALPHA, GL11.GL_ONE);
				state.active = GL13.GL_TEXTURE0 + 5;
				state.textures[0] = 51; state.textures[1] = 52; state.textures[2] = 53;
				state.shaderTextures[0] = 61; state.shaderTextures[1] = 62; state.shaderTextures[2] = 63;
				var expectedIntegers = new HashMap<>(state.integers); var expectedEnabled = new HashMap<>(state.enabled);
				var expectedColor = state.color.clone();
				Runnable restore = SpatialFluidIconQuad.captureState(state);
				assertEquals(GL13.GL_TEXTURE0 + 5, state.active, "capture cannot leak its sampler enumeration");
				state.integers.replaceAll((key, value) -> 0); state.enabled.replaceAll((key, value) -> !value);
				state.shader = null; state.color[3] = .05f; state.active = GL13.GL_TEXTURE0;
				java.util.Arrays.fill(state.textures, 0); java.util.Arrays.fill(state.shaderTextures, 0);
				restore.run();
				assertEquals(expectedIntegers, state.integers); assertEquals(expectedEnabled, state.enabled);
				assertSame(enabled ? fixture.shader : null, state.shader);
				assertArrayEquals(expectedColor, state.color);
				assertArrayEquals(new int[] {51, 52, 53, 0, 0, 0}, state.textures);
				assertArrayEquals(new int[] {61, 62, 63}, state.shaderTextures);
				assertEquals(GL13.GL_TEXTURE0 + 5, state.active);
				assertTrue(state.invalidated, "immediate buffer cache cannot hide the restored vertex array");
			}
		}
	}

	/** Alpha-only CPU oracle accepted only after matching the real stock fragment statements. */
	private static float stockAlpha(String fragment, float sample, float vertex, float modulator) {
		String source = fragment.replaceAll("\\s+", " ");
		boolean preVertex = source.contains("vec4 color = texture(Sampler0, texCoord0); if (color.a < 0.1)");
		if (preVertex) {
			assertTrue(source.contains("color *= vertexColor * ColorModulator;"));
			return sample < .1f ? 0 : sample * vertex * modulator;
		}
		assertTrue(source.contains("vec4 color = texture(Sampler0, texCoord0) * vertexColor; if (color.a < 0.1)"));
		return sample * vertex < .1f ? 0 : sample * vertex * modulator;
	}

	private static String fragmentFor(ShaderInstance shader) throws IOException {
		var config = JsonParser.parseString(asset(shader.getName() + ".json")).getAsJsonObject();
		return asset(config.get("fragment").getAsString() + ".fsh");
	}
	private static String asset(String name) throws IOException { return resource("/assets/minecraft/shaders/core/" + name); }
	private static ClassNode bytecode(Class<?> type) throws IOException {
		try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
			assertNotNull(stream); var node = new ClassNode(); new ClassReader(stream).accept(node, 0); return node;
		}
	}
	private static String resource(String path) throws IOException {
		try (var stream = SpatialFluidIconQuadTest.class.getResourceAsStream(path)) {
			assertNotNull(stream, "Minecraft 1.21.1 asset required: " + path);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
	private static float[] read(Uniform uniform) { float[] values = new float[uniform.getFloatBuffer().capacity()]; uniform.getFloatBuffer().get(0, values); return values; }
	private static SpriteContents contents() { return new SpriteContents(ResourceLocation.parse("test:fluid"), new FrameSize(16, 16), new NativeImage(16, 16, false), ResourceMetadata.EMPTY); }
	private static RecordingGraphics graphics() throws Exception {
		var graphics = allocate(RecordingGraphics.class);
		graphics.pose = new PoseStack(); graphics.events = new ArrayList<>(); graphics.depth = true; graphics.alpha = .8f;
		return graphics;
	}

	@NotNullByDefault
	private static final class Sprite extends TextureAtlasSprite {
		Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 64, 64, 16, 32); }
	}
	@NotNullByDefault
	private static final class RecordingGraphics extends GuiGraphics {
		private PoseStack pose;
		private List<String> events;
		private boolean depth, failFlush;
		private float alpha;
		private RecordingGraphics() { super(null, null); throw new AssertionError("allocated without native GUI"); }
		@Override public PoseStack pose() { return pose; }
		@Override public void flush() { events.add("gui flush"); depth = false; if (failFlush) throw new IllegalStateException("flush"); }
	}
	@NotNullByDefault
	private static final class RecordingPass implements SpatialFluidIconQuad.Pass {
		private final ShaderInstance shader;
		private final RecordingGraphics graphics;
		private boolean failSubmit;
		private @Nullable MeshData submitted;
		private @Nullable ResourceLocation atlas;
		private VertexFormat.@Nullable Mode mode;
		private int vertices;
		private byte[] bytes = new byte[0];
		private float vertexAlpha, modulatorAlpha;
		RecordingPass(ShaderInstance shader, RecordingGraphics graphics) { this.shader = shader; this.graphics = graphics; }
		@Override public Runnable capture() {
			graphics.events.add("capture");
			boolean depth = graphics.depth; float alpha = graphics.alpha;
			return () -> { graphics.events.add("restore"); graphics.depth = depth; graphics.alpha = alpha; };
		}
		@Override public void submit(ShaderInstance selected, ResourceLocation atlas, MeshData mesh, Runnable configure) {
			assertSame(shader, selected, "selected shader comes from the production draw, not the recording port");
			graphics.events.add("submit"); submitted = mesh; this.atlas = atlas; mode = mesh.drawState().mode(); vertices = mesh.drawState().vertexCount();
			configure.run();
			modulatorAlpha = shader.COLOR_MODULATOR.getFloatBuffer().get(3); graphics.alpha = modulatorAlpha;
			bytes = new byte[mesh.vertexBuffer().remaining()]; mesh.vertexBuffer().get(0, bytes);
			vertexAlpha = Byte.toUnsignedInt(bytes[SpatialFluidIconQuad.format().getOffset(VertexFormatElement.COLOR) + 3]) / 255f;
			if (failSubmit) throw new IllegalStateException("submit");
		}
	}

	@NotNullByDefault
	private static final class RecordingState implements SpatialFluidIconQuad.StateAccess {
		private final Map<Integer, Integer> integers = new HashMap<>();
		private final Map<Integer, Boolean> enabled = new HashMap<>();
		private @Nullable ShaderInstance shader;
		private float[] color = {.2f, .3f, .4f, .8f};
		private final int[] textures = new int[6], shaderTextures = new int[3];
		private int active;
		private boolean invalidated, failTexture;
		public int integer(int name) {
			return switch (name) {
				case GL13.GL_ACTIVE_TEXTURE -> active;
				case GL11.GL_TEXTURE_BINDING_2D -> textures[active - GL13.GL_TEXTURE0];
				default -> integers.get(name);
			};
		}
		public boolean enabled(int name) { return enabled.get(name); }
		public @Nullable ShaderInstance shader() { return shader; }
		public float[] color() { return color; }
		public int shaderTexture(int slot) { return shaderTextures[slot]; }
		public void invalidateBuffers() { invalidated = true; }
		public void bindVertexArray(int value) { integers.put(GL30.GL_VERTEX_ARRAY_BINDING, value); }
		public void bindArrayBuffer(int value) { integers.put(GL15.GL_ARRAY_BUFFER_BINDING, value); }
		public void activeTexture(int value) { active = value; }
		public void texture(int value) { if (failTexture) throw new IllegalStateException("texture restore"); textures[active - GL13.GL_TEXTURE0] = value; }
		public void shaderTexture(int slot, int value) { shaderTextures[slot] = value; }
		public void program(int value) { integers.put(GL20.GL_CURRENT_PROGRAM, value); }
		public void shader(@Nullable ShaderInstance value) { shader = value; }
		public void color(float @Unmodifiable [] value) { color = value.clone(); }
		public void depthFunction(int value) { integers.put(GL11.GL_DEPTH_FUNC, value); }
		public void depthMask(boolean value) { integers.put(GL11.GL_DEPTH_WRITEMASK, value ? 1 : 0); }
		public void enabled(int name, boolean value) { enabled.put(name, value); }
		public void blend(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
			integers.put(GL14.GL_BLEND_SRC_RGB, sourceRgb); integers.put(GL14.GL_BLEND_DST_RGB, destinationRgb);
			integers.put(GL14.GL_BLEND_SRC_ALPHA, sourceAlpha); integers.put(GL14.GL_BLEND_DST_ALPHA, destinationAlpha);
		}
	}

	/** Real production getter with a CPU-only ShaderInstance carrying real Uniform buffers. */
	@NotNullByDefault
	private static final class ShaderFixture implements AutoCloseable {
		private final ShaderInstance shader;
		private final @Nullable ShaderInstance previous;
		private final @Nullable Object previousRenderThread;
		private final @Unmodifiable List<Uniform> uniforms;
		ShaderFixture() throws ReflectiveOperationException {
			var renderThread = RenderSystem.class.getDeclaredField("renderThread"); renderThread.setAccessible(true);
			previousRenderThread = renderThread.get(null);
			set(RenderSystem.class, null, "renderThread", Thread.currentThread());
			var field = GameRenderer.class.getDeclaredField("rendertypeEntityTranslucentEmissiveShader"); field.setAccessible(true);
			previous = (ShaderInstance)field.get(null);
			shader = allocate(ShaderInstance.class);
			set(ShaderInstance.class, shader, "name", "rendertype_entity_translucent_emissive");
			var color = new Uniform("ColorModulator", 7, 4, null); color.set(.2f, .3f, .4f, .8f);
			var light0 = new Uniform("Light0_Direction", 6, 3, null); light0.set(.3f, .4f, .5f);
			var light1 = new Uniform("Light1_Direction", 6, 3, null); light1.set(.6f, .7f, .8f);
			var start = new Uniform("FogStart", 4, 1, null); start.set(.2f);
			var end = new Uniform("FogEnd", 4, 1, null); end.set(.8f);
			uniforms = List.of(color, light0, light1, start, end);
			set(ShaderInstance.class, shader, "uniformMap", Map.of("ColorModulator", color, "Light0_Direction", light0,
				"Light1_Direction", light1, "FogStart", start, "FogEnd", end));
			set(ShaderInstance.class, shader, "COLOR_MODULATOR", color); set(ShaderInstance.class, shader, "LIGHT0_DIRECTION", light0);
			set(ShaderInstance.class, shader, "LIGHT1_DIRECTION", light1); set(ShaderInstance.class, shader, "FOG_START", start); set(ShaderInstance.class, shader, "FOG_END", end);
			field.set(null, shader);
		}
		@Override public void close() throws ReflectiveOperationException {
			set(GameRenderer.class, null, "rendertypeEntityTranslucentEmissiveShader", previous);
			set(RenderSystem.class, null, "renderThread", previousRenderThread);
			uniforms.forEach(Uniform::close);
		}
	}
	private static void set(Class<?> type, @Nullable Object target, String name, @Nullable Object value) throws ReflectiveOperationException { var field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
	@SuppressWarnings("unchecked") private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
		var unsafeType = Class.forName("sun.misc.Unsafe"); var field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
		return (T)unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
	}
}
