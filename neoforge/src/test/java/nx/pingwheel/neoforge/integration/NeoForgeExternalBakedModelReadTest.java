package nx.pingwheel.neoforge.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import nx.pingwheel.common.client.outline.ExternalBlockReadView;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Uses the actual NeoForge AO face renderer, but not a running client or optional mod. */
@NotNullByDefault
class NeoForgeExternalBakedModelReadTest {

	private static final BlockPos UPPER = new BlockPos(21_000_002, 63, 20_000_003);
	private static final BlockPos ABOVE_PLOT = UPPER.above();
	private static final BlockPos RESIDENT_POS = UPPER.east();
	private static final ModelProperty<Boolean> RESIDENT_PROPERTY = new ModelProperty<>();
	private static final ModelData RESIDENT_DATA = ModelData.builder().with(RESIDENT_PROPERTY, true).build();

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void upperDoorModelDataAndNativeAoUseTheSameLoadedPlotAtItsTopEdge() {
		BlockState lower = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
		BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
		var parent = new LightingOnlyParent();
		var resident = new ModelDataChest();
		Set<BlockPos> stateProbes = new HashSet<>();
		Set<BlockPos> entityProbes = new HashSet<>();
		var plot = new ExternalBlockReadView(parent, pos -> {
			stateProbes.add(pos.immutable());
			if (UPPER.equals(pos)) return upper;
			if (UPPER.below().equals(pos)) return lower;
			return RESIDENT_POS.equals(pos) ? resident.getBlockState() : null;
		}, pos -> {
			entityProbes.add(pos.immutable());
			return RESIDENT_POS.equals(pos) ? resident : null;
		});
		var model = new UpperFaceModel(resident);
		var vertices = new QuadCounter();
		NeoForgeWorldAwareBlockModelOutlineAdapter.renderModel(parent, plot, UPPER, upper, new PoseStack(),
			new AoDispatcher(model), ignored -> vertices);

		assertEquals(1, vertices.quads, "AO finishes submitting the upper face");
		assertTrue(stateProbes.contains(ABOVE_PLOT.north()), "real AO probed beyond the plot, not just the root");
		assertTrue(entityProbes.contains(ABOVE_PLOT), "model data exercised the out-of-plot BE lookup");
		assertTrue(parent.litPositions.contains(UPPER), "loaded cells keep native lighting");
		assertFalse(parent.litPositions.contains(ABOVE_PLOT), "out-of-plot light never reaches the parent");
	}

	@NotNullByDefault
	private static final class AoDispatcher extends BlockRenderDispatcher {
		private final UpperFaceModel model;
		private final ModelBlockRenderer nativeAo = new ModelBlockRenderer(new BlockColors());

		private AoDispatcher(UpperFaceModel model) {
			super(null, null, new BlockColors()); // Only the native CPU face renderer is exercised.
			this.model = model;
		}

		@Override
		public BakedModel getBlockModel(BlockState state) {
			return model;
		}

		@Override
		public void renderBatched(BlockState state, BlockPos pos, BlockAndTintGetter view, PoseStack pose,
			VertexConsumer vertices, boolean checkSides, RandomSource random, ModelData data, @Nullable RenderType layer) {
			assertSame(model.dataView, view, "model data and native rendering must share one view");
			assertSame(RESIDENT_DATA, data);
			// Select the native AO renderer directly; the loader's optional lighting config is not initialized here.
			nativeAo.tesselateWithAO(view, model, state, pos, pose, vertices, checkSides, random,
				state.getSeed(pos), OverlayTexture.NO_OVERLAY, data, layer);
		}
	}

	@NotNullByDefault
	private static final class UpperFaceModel implements BakedModel {
		private final BlockEntity resident;
		private final BakedQuad upperQuad = topFace();
		private @Nullable BlockAndTintGetter dataView;

		private UpperFaceModel(BlockEntity resident) {
			this.resident = resident;
		}

		@Override
		public ModelData getModelData(BlockAndTintGetter view, BlockPos pos, BlockState state, ModelData data) {
			dataView = view;
			assertSame(ModelData.EMPTY, data, "a valid upper half does not require its own BE");
			assertEquals(DoubleBlockHalf.LOWER, view.getBlockState(pos.below()).getValue(DoorBlock.HALF));
			assertTrue(view.getBlockState(ABOVE_PLOT).isAir());
			assertTrue(view.getFluidState(ABOVE_PLOT).isEmpty());
			assertNull(view.getBlockEntity(ABOVE_PLOT));
			assertSame(ModelData.EMPTY, view.getModelData(ABOVE_PLOT));
			assertSame(resident, view.getBlockEntity(RESIDENT_POS));
			return view.getModelData(RESIDENT_POS);
		}

		@Override
		public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
			assertSame(RESIDENT_DATA, data);
			return ChunkRenderTypeSet.of(RenderType.cutout());
		}

		@Override
		public @Unmodifiable List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) {
			return side == Direction.UP ? List.of(upperQuad) : List.of();
		}

		@Override public boolean useAmbientOcclusion() { return true; }
		@Override public boolean isGui3d() { return false; }
		@Override public boolean usesBlockLight() { return true; }
		@Override public boolean isCustomRenderer() { return false; }
		@Override public TextureAtlasSprite getParticleIcon() { return fail("the face does not use particles"); }
		@Override public ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }

		private static BakedQuad topFace() {
			int stride = DefaultVertexFormat.BLOCK.getVertexSize() / Integer.BYTES;
			int[] vertices = new int[stride * 4];
			for (int vertex = 0; vertex < 4; vertex++) {
				int offset = vertex * stride;
				vertices[offset] = Float.floatToRawIntBits(vertex >= 2 ? 1.0F : 0.0F);
				vertices[offset + 1] = Float.floatToRawIntBits(1.0F);
				vertices[offset + 2] = Float.floatToRawIntBits(vertex == 1 || vertex == 2 ? 1.0F : 0.0F);
			}
			@Nullable TextureAtlasSprite unusedSprite = null;
			return new BakedQuad(vertices, -1, Direction.UP, unusedSprite, true);
		}
	}

	@NotNullByDefault
	private static final class ModelDataChest extends ChestBlockEntity {
		private ModelDataChest() {
			super(RESIDENT_POS, Blocks.CHEST.defaultBlockState());
		}

		@Override public ModelData getModelData() { return RESIDENT_DATA; }
	}

	@NotNullByDefault
	private static final class LightingOnlyParent implements BlockAndTintGetter {
		private final Set<BlockPos> litPositions = new HashSet<>();

		@Override
		public int getBrightness(LightLayer layer, BlockPos pos) {
			if (!UPPER.equals(pos) && !UPPER.below().equals(pos) && !RESIDENT_POS.equals(pos)) {
				throw new ArrayIndexOutOfBoundsException("light sample outside the loaded plot: " + pos);
			}
			litPositions.add(pos.immutable());
			return 7;
		}

		@Override public BlockState getBlockState(BlockPos pos) { return fail("unscoped parent state"); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return fail("unscoped parent BE"); }
		@Override public FluidState getFluidState(BlockPos pos) { return fail("unscoped parent fluid"); }
		@Override public ModelData getModelData(BlockPos pos) { return fail("unscoped parent model-data refresh"); }
		@Override public LevelLightEngine getLightEngine() { return fail("unscoped parent light engine"); }
		@Override public float getShade(Direction side, boolean shade) { return 1.0F; }
		@Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return fail("unexpected tint lookup"); }
		@Override public int getHeight() { return 384; }
		@Override public int getMinBuildHeight() { return -64; }
	}

	@NotNullByDefault
	private static final class QuadCounter implements VertexConsumer {
		private int quads;

		@Override
		public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] brightness,
			float red, float green, float blue, float alpha, int[] light, int overlay, boolean readExistingColor) {
			quads++;
		}

		@Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
		@Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
		@Override public VertexConsumer setUv(float u, float v) { return this; }
		@Override public VertexConsumer setUv1(int u, int v) { return this; }
		@Override public VertexConsumer setUv2(int u, int v) { return this; }
		@Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
	}
}
