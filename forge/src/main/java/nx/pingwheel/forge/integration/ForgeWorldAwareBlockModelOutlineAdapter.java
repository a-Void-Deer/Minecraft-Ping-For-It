package nx.pingwheel.forge.integration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;
import nx.pingwheel.common.client.outline.EntityBlockGeometryContext;
import nx.pingwheel.common.client.outline.EntityBlockGeometryTransform;
import nx.pingwheel.common.client.outline.OutlineOnlyBufferSource;
import nx.pingwheel.common.client.outline.WorldAwareBlockModelOutlineAdapter;
import nx.pingwheel.common.client.outline.WorldAwareBlockModelOutlineAdapterRegistry;

/**
 * Forge's generic world-aware baked-model backend for entity-block outlines.
 *
 * <p>The common renderer owns the attempt-local buffer and its lifecycle. This
 * adapter only dispatches the live model and deliberately keeps no world,
 * session, or buffer state.</p>
 */
public final class ForgeWorldAwareBlockModelOutlineAdapter
	implements WorldAwareBlockModelOutlineAdapter {
	public static final String SOURCE_ID = "pingforit:forge_world_baked_model";

	private static final ForgeWorldAwareBlockModelOutlineAdapter INSTANCE =
		new ForgeWorldAwareBlockModelOutlineAdapter();

	/* Retained for the client process lifetime; connections must not re-register it. */
	private static WorldAwareBlockModelOutlineAdapterRegistry.Registration registration;

	private ForgeWorldAwareBlockModelOutlineAdapter() {
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
	public boolean handles(EntityBlockGeometryContext context) {
		if (context == null) {
			return false;
		}

		ClientLevel level = context.level();
		BlockPos pos = context.blockPos();
		BlockState state = context.blockState();
		if (level == null || pos == null || state == null
			|| state.getRenderShape() != RenderShape.MODEL
			|| !level.hasChunkAt(pos)) {
			return false;
		}

		// Only dispatch a state whose block type is still live at the target.
		BlockState liveState = level.getBlockState(pos);
		return liveState != null && liveState.getBlock() == state.getBlock();
	}

	@Override
	public void render(EntityBlockGeometryContext context, OutlineOnlyBufferSource buffer) {
		ClientLevel level = context.level();
		BlockPos pos = context.blockPos();
		BlockState state = context.blockState();
		Vec3 cameraPosition = context.cameraPosition();
		PoseStack poseStack = createPoseStack(context, pos, cameraPosition);
		BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
		BakedModel model = dispatcher.getBlockModel(state);
		long seed = state.getSeed(pos);

		ModelData modelData = level.getModelDataManager().getAt(pos);
		if (modelData == null) {
			modelData = ModelData.EMPTY;
		}
		modelData = model.getModelData(level, pos, state, modelData);

		RandomSource renderTypesRandom = RandomSource.create(seed);
		for (RenderType originalRenderType : model.getRenderTypes(state, renderTypesRandom, modelData)) {
			poseStack.pushPose();
			try {
				VertexConsumer consumer = buffer.getBuffer(originalRenderType);
				dispatcher.renderBatched(
					state,
					pos,
					level,
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

	private static PoseStack createPoseStack(
		EntityBlockGeometryContext context,
		BlockPos pos,
		Vec3 cameraPosition
	) {
		EntityBlockGeometryTransform transform = context.transform();
		if (transform != null) {
			return transform.createPoseStack(pos, cameraPosition, null);
		}

		PoseStack poseStack = new PoseStack();
		poseStack.translate(
			pos.getX() - cameraPosition.x,
			pos.getY() - cameraPosition.y,
			pos.getZ() - cameraPosition.z);
		// World-aware tesselation owns BlockState#getOffset.
		return poseStack;
	}
}
