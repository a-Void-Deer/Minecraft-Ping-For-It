package nx.pingwheel.neoforge.integration;

import java.util.Objects;
import java.util.function.Function;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import nx.pingwheel.common.client.outline.EntityBlockGeometryContext;
import nx.pingwheel.common.client.outline.EntityBlockGeometryTransform;
import nx.pingwheel.common.client.outline.ExternalBlockReadView;
import nx.pingwheel.common.client.outline.ExternalBlockRenderView;
import nx.pingwheel.common.client.outline.OutlineOnlyBufferSource;
import nx.pingwheel.common.client.outline.WorldAwareBlockModelOutlineAdapter;
import nx.pingwheel.common.client.outline.WorldAwareBlockModelOutlineAdapterRegistry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge's generic world-aware baked-model backend for entity-block outlines.
 *
 * <p>This is loader infrastructure, not an optional-mod integration.  The
 * common renderer owns the attempt-local buffer and its lifecycle; this
 * adapter only dispatches the live model and keeps no world, session, or
 * buffer state.</p>
 */
public final class NeoForgeWorldAwareBlockModelOutlineAdapter
	implements WorldAwareBlockModelOutlineAdapter {
	public static final String SOURCE_ID = "pingforit:neoforge_world_baked_model";

	private static final NeoForgeWorldAwareBlockModelOutlineAdapter INSTANCE =
		new NeoForgeWorldAwareBlockModelOutlineAdapter();

	/* Retained for the client process lifetime; connections must not close it. */
	private static WorldAwareBlockModelOutlineAdapterRegistry.Registration registration;

	private NeoForgeWorldAwareBlockModelOutlineAdapter() {
	}

	/** Registers this loader backend once for the lifetime of the client process. */
	public static synchronized void register() {
		if (registration != null) {
			return;
		}

		WorldAwareBlockModelOutlineAdapterRegistry.Registration candidate =
			WorldAwareBlockModelOutlineAdapterRegistry.INSTANCE.register(INSTANCE);
		if (candidate.accepted()) {
			registration = candidate;
		} else {
			candidate.close();
		}
	}

	@Override
	public String id() {
		return SOURCE_ID;
	}

	@Override
	public boolean handles(@Nullable EntityBlockGeometryContext context) {
		return context != null && context.hasLiveBakedModelState();
	}

	@Override
	public void render(EntityBlockGeometryContext context, OutlineOnlyBufferSource buffer) {
		BlockPos pos = Objects.requireNonNull(context.blockPos());
		renderModel(Objects.requireNonNull(context.level()), context.externalReadView(), pos,
			Objects.requireNonNull(context.blockState()), createPoseStack(context, pos, context.cameraPosition()),
			Minecraft.getInstance().getBlockRenderer(), renderType -> buffer.getBuffer(renderType));
	}

	static void renderModel(
		BlockAndTintGetter level, @Nullable ExternalBlockReadView plot, BlockPos pos, BlockState state, PoseStack poseStack,
		BlockRenderDispatcher dispatcher, Function<RenderType, VertexConsumer> outlineBuffer
	) {
		BlockAndTintGetter modelView = plot == null ? level : new ScopedModelView(plot, level);
		BakedModel model = dispatcher.getBlockModel(state);
		long seed = state.getSeed(pos);

		@Nullable ModelData modelData = plot == null ? level.getModelData(pos) : modelView.getModelData(pos);
		if (modelData == null) {
			modelData = ModelData.EMPTY;
		}
		modelData = model.getModelData(modelView, pos, state, modelData);
		if (modelData == null) {
			modelData = ModelData.EMPTY;
		}

		RandomSource renderTypesRandom = RandomSource.create(seed);
		for (RenderType originalRenderType : model.getRenderTypes(state, renderTypesRandom, modelData)) {
			poseStack.pushPose();
			try {
				VertexConsumer consumer = outlineBuffer.apply(originalRenderType);
				dispatcher.renderBatched(
					state,
					pos,
					modelView,
					poseStack,
					consumer,
					false,
					RandomSource.create(seed),
					modelData,
					originalRenderType);
			} finally {
				poseStack.popPose();
			}
		}
	}

	@NotNullByDefault
	private static final class ScopedModelView extends ExternalBlockRenderView {
		private final BlockAndTintGetter shading;

		private ScopedModelView(ExternalBlockReadView plot, BlockAndTintGetter shading) {
			super(plot, shading);
			this.shading = shading;
		}

		@Override
		public ModelData getModelData(BlockPos pos) {
			// The level manager can refresh an entire section of BEs. Only the
			// plot's positively scoped resident entity may seed external model data.
			@Nullable BlockEntity resident = getBlockEntity(pos);
			@Nullable ModelData data = resident == null ? null : resident.getModelData();
			return data == null ? ModelData.EMPTY : data;
		}

		@Override
		public float getShade(float x, float y, float z, boolean shade) {
			return shading.getShade(x, y, z, shade);
		}
	}

	private static PoseStack createPoseStack(
		EntityBlockGeometryContext context,
		BlockPos pos,
		Vec3 cameraPosition
	) {
		@Nullable EntityBlockGeometryTransform transform = context.transform();
		if (transform != null) {
			return transform.createPoseStack(pos, cameraPosition, null);
		}

		PoseStack poseStack = new PoseStack();
		poseStack.translate(
			pos.getX() - cameraPosition.x,
			pos.getY() - cameraPosition.y,
			pos.getZ() - cameraPosition.z);
		// World-aware tesselation owns the block-state offset.
		return poseStack;
	}
}
