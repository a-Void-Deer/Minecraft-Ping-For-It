package nx.pingwheel.common.client.outline;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
