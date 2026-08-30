package nx.pingwheel.common.client.outline;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.server.Bootstrap;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the through-wall native VoxelShape line route.
 * Vanilla's CompositeState fields are private in 1.21.1, so this test uses
 * the production package-private descriptor rather than brittle reflection.
 */
class BlockOutlineRenderTypeTest {

	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void productionBlockOutlineKeepsTheRequiredLineState() {
		BlockOutlineRenderType.OutlineStateDescriptor descriptor =
			BlockOutlineRenderType.outlineState();
		BlockOutlineRenderType production =
			(BlockOutlineRenderType) BlockOutlineRenderType.BLOCK_OUTLINE;

		assertNotNull(BlockOutlineRenderType.state());
		assertSame(descriptor, production.descriptor());
		assertEquals(VertexFormat.Mode.LINES, descriptor.mode());
		assertEquals(VertexFormat.Mode.LINES, production.mode());
		assertEquals(3.75D, descriptor.lineWidth(), 0.0D);
		assertEquals("rendertype_lines", descriptor.shaderName());

		assertSame(VanillaShardAccess.rendertypeLinesShader(), descriptor.shaderState());
		assertSame(VanillaShardAccess.noDepthTest(), descriptor.depthTestState());
		assertSame(VanillaShardAccess.noCull(), descriptor.cullState());
		assertSame(VanillaShardAccess.colorWrite(), descriptor.writeMaskState());
		assertSame(VanillaShardAccess.viewOffsetZLayering(), descriptor.layeringState());
	}

	@Test
	void compiledProductionVoxelRouteUsesNativeEdgesAndLateCustomComposite() {
		List<MethodCall> rendererCalls = methodInvocations(
			"nx.pingwheel.common.client.outline.BlockOutlineRenderer", "render");
		int shapeIndex = invocationIndex(
			rendererCalls,
			"net/minecraft/world/level/block/state/BlockState",
			"getShape");
		int renderEdgesIndex = invocationIndex(
			rendererCalls,
			"nx/pingwheel/common/client/outline/VoxelShapeRenderUtil",
			"renderEdges");

		assertTrue(shapeIndex >= 0, "BlockOutlineRenderer.render must query BlockState#getShape");
		assertTrue(
			renderEdgesIndex > shapeIndex,
			"BlockOutlineRenderer.render must pass the live shape to renderEdges after querying it");
		assertEquals(
			"(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/world/phys/shapes/CollisionContext;)"
				+ "Lnet/minecraft/world/phys/shapes/VoxelShape;",
			rendererCalls.get(shapeIndex).descriptor());
		assertEquals(
			"(Lcom/mojang/blaze3d/vertex/PoseStack;"
				+ "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
				+ "Lnet/minecraft/world/phys/shapes/VoxelShape;DDDI)V",
			rendererCalls.get(renderEdgesIndex).descriptor());

		List<MethodCall> renderEdgesCalls = methodInvocations(
			"nx.pingwheel.common.client.outline.VoxelShapeRenderUtil", "renderEdges");
		assertTrue(
			invocationIndex(
				renderEdgesCalls,
				"nx/pingwheel/common/client/outline/VoxelShapeRenderUtil",
				"forEachEdge") >= 0,
			"renderEdges must delegate to the native edge iteration route");

		List<MethodCall> edgeIterationCalls = methodInvocations(
			"nx.pingwheel.common.client.outline.VoxelShapeRenderUtil", "forEachEdge");
		assertTrue(
			invocationIndex(
				edgeIterationCalls,
				"net/minecraft/world/phys/shapes/VoxelShape",
				"forAllEdges") >= 0,
			"forEachEdge must invoke VoxelShape#forAllEdges");

		List<MethodCall> lateMixinCalls = methodInvocations(
			"nx.pingwheel.common.mixin.LevelRendererMixin", "onEndRenderLevel");
		int bufferSourceIndex = invocationIndex(
			lateMixinCalls, "net/minecraft/client/renderer/RenderBuffers", "bufferSource");
		int blockOutlinesIndex = invocationIndex(
			lateMixinCalls, "nx/pingwheel/common/CommonClient", "renderBlockOutlines");
		assertTrue(bufferSourceIndex >= 0, "late mixin must acquire the render buffer source");
		assertTrue(
			blockOutlinesIndex > bufferSourceIndex,
			"late mixin must render block outlines through the acquired buffer source");

		List<String> injectionValues = annotationStringValues(
			"nx.pingwheel.common.mixin.LevelRendererMixin", "onEndRenderLevel",
			"Lorg/spongepowered/asm/mixin/injection/Inject;");
		assertTrue(injectionValues.contains("renderLevel"));
		assertTrue(injectionValues.contains(
			"Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;"));
	}

	private static List<MethodCall> methodInvocations(String className, String methodName) {
		List<MethodCall> calls = new ArrayList<>();
		readClass(className).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(
				int access, String name, String descriptor, String signature, String[] exceptions
			) {
				if (!methodName.equals(name)) {
					return null;
				}

				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMethodInsn(
						int opcode, String owner, String name, String descriptor, boolean isInterface
					) {
						calls.add(new MethodCall(owner, name, descriptor));
					}
				};
			}
		}, 0);
		return calls;
	}

	private static List<String> annotationStringValues(
		String className, String methodName, String annotationDescriptor
	) {
		List<String> values = new ArrayList<>();
		readClass(className).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(
				int access, String name, String descriptor, String signature, String[] exceptions
			) {
				if (!methodName.equals(name)) {
					return null;
				}

				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
						return annotationDescriptor.equals(descriptor)
							? new StringValueAnnotationVisitor(values) : null;
					}
				};
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return values;
	}

	private static int invocationIndex(List<MethodCall> calls, String owner, String name) {
		for (int index = 0; index < calls.size(); index++) {
			MethodCall call = calls.get(index);
			if (owner.equals(call.owner()) && name.equals(call.name())) {
				return index;
			}
		}
		return -1;
	}

	private static ClassReader readClass(String className) {
		String resourceName = className.replace('.', '/') + ".class";
		try (InputStream stream = BlockOutlineRenderTypeTest.class
			.getClassLoader().getResourceAsStream(resourceName)) {
			if (stream == null) {
				throw new AssertionError("Compiled production class not found: " + resourceName);
			}
			return new ClassReader(stream);
		} catch (IOException failure) {
			throw new AssertionError("Unable to read compiled production class: " + resourceName, failure);
		}
	}

	private record MethodCall(String owner, String name, String descriptor) {}

	private static final class StringValueAnnotationVisitor extends AnnotationVisitor {
		private final List<String> values;

		private StringValueAnnotationVisitor(List<String> values) {
			super(Opcodes.ASM9);
			this.values = values;
		}

		@Override
		public void visit(String name, Object value) {
			if (value instanceof String string) {
				values.add(string);
			}
		}

		@Override
		public AnnotationVisitor visitAnnotation(String name, String descriptor) {
			return new StringValueAnnotationVisitor(values);
		}

		@Override
		public AnnotationVisitor visitArray(String name) {
			return new StringValueAnnotationVisitor(values);
		}
	}

	/**
	 * The required vanilla shards are protected on RenderStateShard. A tiny
	 * test-only subclass accesses those exact singleton constants without
	 * reflection, allowing the descriptor to be pinned to NO_DEPTH_TEST,
	 * NO_CULL, COLOR_WRITE, VIEW_OFFSET_Z_LAYERING, and rendertype_lines.
	 */
	private static final class VanillaShardAccess extends RenderStateShard {

		private VanillaShardAccess() {
			super("test", () -> {}, () -> {});
		}

		private static ShaderStateShard rendertypeLinesShader() {
			return RENDERTYPE_LINES_SHADER;
		}

		private static DepthTestStateShard noDepthTest() {
			return NO_DEPTH_TEST;
		}

		private static CullStateShard noCull() {
			return NO_CULL;
		}

		private static WriteMaskStateShard colorWrite() {
			return COLOR_WRITE;
		}

		private static LayeringStateShard viewOffsetZLayering() {
			return VIEW_OFFSET_Z_LAYERING;
		}
	}
}
