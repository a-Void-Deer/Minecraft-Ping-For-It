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
import org.objectweb.asm.Handle;
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

		assertTrue(
			invocationIndex(
				methodInvocations("nx.pingwheel.common.mixin.LevelRendererMixin", "onStartRenderLevel"),
				"nx/pingwheel/common/client/outline/BlockOutlineFrameState",
				"capture") >= 0,
				"world start hook must capture the applied frame transform");
		assertEquals(
			-1,
			invocationIndex(
				allMethodInvocations("nx.pingwheel.common.mixin.LevelRendererMixin"),
				"nx/pingwheel/common/CommonClient",
				"renderBlockOutlines"),
			"LevelRenderer must not submit no-depth shape lines before return hooks/composites");
		assertEquals(
			-1,
			methodNames("nx.pingwheel.common.mixin.LevelRendererMixin").indexOf("pingForItClearBlockOutlineFrame"),
			"LevelRenderer RETURN must not discard the frame before GameRenderer consumes it");
		assertEquals(
			-1,
			invocationIndex(
				allMethodInvocations("nx.pingwheel.common.mixin.LevelRendererMixin"),
				"nx/pingwheel/common/client/outline/BlockOutlineFrameState",
				"clear"),
			"LevelRenderer must not clear the frame before the post-world consumer");

		List<MethodCall> gameRendererCalls = methodInvocations(
			"nx.pingwheel.common.mixin.GameRendererMixin", "pingforit$renderBlockOutlines");
		int consumeIndex = invocationIndex(
			gameRendererCalls,
			"nx/pingwheel/common/client/outline/BlockOutlineFrameState",
			"consume");
		int blockOutlinesIndex = invocationIndex(
			gameRendererCalls, "nx/pingwheel/common/CommonClient", "renderBlockOutlines");
		assertTrue(
			consumeIndex >= 0,
			"post-world GameRenderer hook must consume, rather than retain, its frame transform");
		assertEquals(
			1,
			invocationCount(
				gameRendererCalls,
				"nx/pingwheel/common/client/outline/BlockOutlineFrameState",
				"consume"),
			"post-world hook must consume exactly one frame snapshot");
		assertTrue(
			blockOutlinesIndex > consumeIndex,
			"post-world GameRenderer hook must render only after it consumes the frame");
		assertTrue(
			invocationIndex(
				methodInvocations("nx.pingwheel.common.CommonClient", "renderBlockOutlines"),
				"nx/pingwheel/common/client/outline/BlockOutlineFrameState",
				"renderWithFrame") >= 0,
			"late VoxelShape batch must execute inside the captured transform scope");

		List<Object> injectionValues = annotationValues(
			"nx.pingwheel.common.mixin.GameRendererMixin", "pingforit$renderBlockOutlines",
			"Lorg/spongepowered/asm/mixin/injection/Inject;");
		assertTrue(injectionValues.contains("renderLevel(Lnet/minecraft/client/DeltaTracker;)V"));
		assertTrue(injectionValues.contains(
			"Lnet/minecraft/client/renderer/LevelRenderer;renderLevel("
				+ "Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;"
				+ "Lnet/minecraft/client/renderer/GameRenderer;"
				+ "Lnet/minecraft/client/renderer/LightTexture;"
				+ "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"));
		assertTrue(injectionValues.contains("AFTER"));
		assertTrue(injectionValues.contains(1), "post-world injection must require its exact anchor");

		List<MethodCall> clearCalls = methodInvocations(
			"nx.pingwheel.common.mixin.GameRendererMixin", "pingforit$clearBlockOutlineFrame");
		assertTrue(invocationIndex(
			clearCalls,
			"nx/pingwheel/common/client/outline/BlockOutlineFrameState",
			"clear") >= 0,
			"GameRenderer HEAD must discard an abandoned prior frame");
		List<Object> clearInjectionValues = annotationValues(
			"nx.pingwheel.common.mixin.GameRendererMixin", "pingforit$clearBlockOutlineFrame",
			"Lorg/spongepowered/asm/mixin/injection/Inject;");
		assertTrue(clearInjectionValues.contains("HEAD"));
		assertTrue(clearInjectionValues.contains(1));
	}

	@Test
	void compiledFallbackConsumesThePresentationSubjectsInsteadOfSourceSnapshot() {
		List<MethodCall> rendererCalls = methodInvocations(
			"nx.pingwheel.common.client.outline.BlockOutlineRenderer", "render");

		assertTrue(invocationIndex(
			rendererCalls,
			"nx/pingwheel/common/client/outline/BlockPresentation",
			"sourceSpec") >= 0);
		assertTrue(invocationIndex(
			rendererCalls,
			"nx/pingwheel/common/client/outline/BlockPresentation",
			"renderSubjects") >= 0);
		assertTrue(invocationIndex(
			rendererCalls,
			"nx/pingwheel/common/client/outline/BlockRenderSubject",
			"blockPos") >= 0);
		assertTrue(invocationIndex(
			rendererCalls,
			"nx/pingwheel/common/client/outline/BlockRenderSubject",
			"blockState") >= 0);
		assertEquals(-1, invocationIndex(
			rendererCalls,
			"nx/pingwheel/common/client/outline/BlockOutlineState",
			"snapshot"),
			"ordinary fallback must not reconstruct source entries");

		List<MethodCall> modelRendererCalls = methodInvocations(
			"nx.pingwheel.common.client.outline.VirtualBlockDisplayRenderer", "render");
		assertTrue(invocationIndex(
			rendererCalls,
			"nx/pingwheel/common/client/outline/BlockPresentationSubjectValidation",
			"isLoadedAndCurrent") >= 0);

		String virtualRenderer =
			"nx/pingwheel/common/client/outline/VirtualBlockDisplayRenderer";
		assertTrue(invocationIndex(
			modelRendererCalls,
			virtualRenderer,
			"renderPresentationSubjects") >= 0,
			"VirtualBlockDisplayRenderer.render must delegate ordinary presentations to its subject helper");

		List<MethodCall> presentationCalls = methodInvocations(
			"nx.pingwheel.common.client.outline.VirtualBlockDisplayRenderer",
			"renderPresentationSubjects");
		int dispatchIndex = invocationIndex(
			presentationCalls,
			"nx/pingwheel/common/client/outline/BlockPresentationSubjectDispatcher",
			"dispatch",
			"(Lnx/pingwheel/common/client/outline/BlockPresentation;"
				+ "Ljava/util/function/Predicate;Ljava/util/function/Consumer;"
				+ "Lnx/pingwheel/common/client/outline/BlockPresentationSubjectDispatcher$SubjectGeometryRenderer;)V");
		assertTrue(dispatchIndex >= 0,
			"renderPresentationSubjects must pass its live predicate and callbacks to the dispatcher");
		assertEquals(-1, invocationIndex(
			presentationCalls,
			"nx/pingwheel/common/client/outline/BlockPresentationSubjectValidation",
			"isLoadedAndCurrent"),
			"renderPresentationSubjects must not perform an ignored validation preflight");

		List<LambdaBinding> predicateBindings = lambdaBindings(
			"nx.pingwheel.common.client.outline.VirtualBlockDisplayRenderer",
			"renderPresentationSubjects",
			"Ljava/util/function/Predicate;");
		assertEquals(1, predicateBindings.size(),
			"renderPresentationSubjects must bind exactly one Predicate for dispatcher validation");
		LambdaBinding validationBinding = predicateBindings.get(0);
		assertEquals(
			"(Lnet/minecraft/client/multiplayer/ClientLevel;)Ljava/util/function/Predicate;",
			validationBinding.callSiteDescriptor());
		assertEquals(virtualRenderer, validationBinding.implementation().owner());
		assertEquals(
			"(Lnet/minecraft/client/multiplayer/ClientLevel;"
				+ "Lnx/pingwheel/common/client/outline/BlockRenderSubject;)Z",
			validationBinding.implementation().descriptor());
		assertTrue(invocationIndex(
			methodInvocations(
				"nx.pingwheel.common.client.outline.VirtualBlockDisplayRenderer",
				validationBinding.implementation().name()),
			"nx/pingwheel/common/client/outline/BlockPresentationSubjectValidation",
			"isLoadedAndCurrent",
			"(Lnet/minecraft/client/multiplayer/ClientLevel;"
				+ "Lnx/pingwheel/common/client/outline/BlockRenderSubject;)Z") >= 0,
			"the Predicate actually bound by renderPresentationSubjects must perform live subject validation");

		List<MethodCall> dispatcherCalls = methodInvocations(
			"nx.pingwheel.common.client.outline.BlockPresentationSubjectDispatcher", "dispatch");
		int validationIndex = invocationIndex(
			dispatcherCalls, "java/util/function/Predicate", "test");
		int coverageIndex = invocationIndex(
			dispatcherCalls,
			"nx/pingwheel/common/client/outline/BlockPresentationCoverageTracker",
			"covers");
		int coveredSuccessIndex = invocationIndex(
			dispatcherCalls, "java/util/function/Consumer", "accept");
		int geometryIndex = invocationIndex(
			dispatcherCalls,
			"nx/pingwheel/common/client/outline/BlockPresentationSubjectDispatcher$SubjectGeometryRenderer",
			"render");
		int renderedSuccessIndex = invocationIndexAfter(
			dispatcherCalls, "java/util/function/Consumer", "accept", coveredSuccessIndex + 1);
		assertTrue(validationIndex >= 0, "dispatcher must test live validity for each subject");
		assertTrue(coverageIndex > validationIndex,
			"dispatcher must validate before source-conditioned coverage can record success");
		assertTrue(coveredSuccessIndex > coverageIndex,
			"covered subjects may record success only after live validation and coverage checks");
		assertTrue(geometryIndex > validationIndex,
			"dispatcher must validate before invoking the normal geometry callback");
		assertTrue(renderedSuccessIndex > geometryIndex,
			"normal subjects may record success only after their renderer reports success");
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

	private static List<MethodCall> allMethodInvocations(String className) {
		List<MethodCall> calls = new ArrayList<>();
		readClass(className).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(
				int access, String name, String descriptor, String signature, String[] exceptions
			) {
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

	private static List<LambdaBinding> lambdaBindings(
		String className, String methodName, String functionalInterfaceDescriptor
	) {
		List<LambdaBinding> bindings = new ArrayList<>();
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
					public void visitInvokeDynamicInsn(
						String name,
						String descriptor,
						Handle bootstrapMethodHandle,
						Object... bootstrapMethodArguments
					) {
						if (!descriptor.endsWith(functionalInterfaceDescriptor)
							|| !"java/lang/invoke/LambdaMetafactory".equals(bootstrapMethodHandle.getOwner())) {
							return;
						}

						for (Object argument : bootstrapMethodArguments) {
							if (argument instanceof Handle implementation) {
								bindings.add(new LambdaBinding(
									descriptor,
									new MethodHandleRef(
										implementation.getOwner(),
										implementation.getName(),
										implementation.getDesc())));
							}
						}
					}
				};
			}
		}, 0);
		return bindings;
	}

	private static List<String> methodNames(String className) {
		List<String> names = new ArrayList<>();
		readClass(className).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(
				int access, String name, String descriptor, String signature, String[] exceptions
			) {
				names.add(name);
				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return names;
	}

	private static List<Object> annotationValues(
		String className, String methodName, String annotationDescriptor
	) {
		List<Object> values = new ArrayList<>();
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
							? new AnnotationValueVisitor(values) : null;
					}
				};
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return values;
	}

	private static int invocationIndex(List<MethodCall> calls, String owner, String name) {
		return invocationIndexAfter(calls, owner, name, 0);
	}

	private static int invocationIndex(
		List<MethodCall> calls, String owner, String name, String descriptor
	) {
		for (int index = 0; index < calls.size(); index++) {
			MethodCall call = calls.get(index);
			if (owner.equals(call.owner())
				&& name.equals(call.name())
				&& descriptor.equals(call.descriptor())) {
				return index;
			}
		}
		return -1;
	}

	private static int invocationIndexAfter(
		List<MethodCall> calls, String owner, String name, int startIndex
	) {
		for (int index = startIndex; index < calls.size(); index++) {
			MethodCall call = calls.get(index);
			if (owner.equals(call.owner()) && name.equals(call.name())) {
				return index;
			}
		}
		return -1;
	}

	private static int invocationCount(List<MethodCall> calls, String owner, String name) {
		int count = 0;
		for (MethodCall call : calls) {
			if (owner.equals(call.owner()) && name.equals(call.name())) {
				count++;
			}
		}
		return count;
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
	private record MethodHandleRef(String owner, String name, String descriptor) {}
	private record LambdaBinding(String callSiteDescriptor, MethodHandleRef implementation) {}

	private static final class AnnotationValueVisitor extends AnnotationVisitor {
		private final List<Object> values;

		private AnnotationValueVisitor(List<Object> values) {
			super(Opcodes.ASM9);
			this.values = values;
		}

		@Override
		public void visit(String name, Object value) {
			values.add(value);
		}

		@Override
		public void visitEnum(String name, String descriptor, String value) {
			values.add(value);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String name, String descriptor) {
			return new AnnotationValueVisitor(values);
		}

		@Override
		public AnnotationVisitor visitArray(String name) {
			return new AnnotationValueVisitor(values);
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
