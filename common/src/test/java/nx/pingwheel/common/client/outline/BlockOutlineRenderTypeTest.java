package nx.pingwheel.common.client.outline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
	void productionVoxelRouteUsesNativeEdgesAndLateCustomComposite() {
		String renderer = readSource(
			"common/src/main/java/nx/pingwheel/common/client/outline/BlockOutlineRenderer.java");
		String edgeUtil = readSource(
			"common/src/main/java/nx/pingwheel/common/client/outline/VoxelShapeRenderUtil.java");
		String levelMixin = readSource(
			"common/src/main/java/nx/pingwheel/common/mixin/LevelRendererMixin.java");

		assertTrue(renderer.contains("VoxelShapeRenderUtil.renderEdges("));
		assertTrue(renderer.contains(
			"VoxelShape shape = blockState.getShape(level, pos, collisionContext);"));
		assertTrue(edgeUtil.contains("shape.forAllEdges("));
		assertFalse(edgeUtil.contains("shape.toAabbs("));
		assertTrue(levelMixin.contains("CommonClient.INSTANCE.renderBlockOutlines("));
		assertTrue(levelMixin.contains("Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;"));
	}

	private static String readSource(String relativePath) {
		for (Path candidate : new Path[] {Path.of(relativePath), Path.of("..", relativePath)}) {
			if (!Files.isRegularFile(candidate)) {
				continue;
			}

			try {
				return Files.readString(candidate, StandardCharsets.UTF_8);
			} catch (IOException failure) {
				throw new AssertionError("Unable to read production source: " + candidate, failure);
			}
		}

		throw new AssertionError("Production source not found: " + relativePath);
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
