package nx.pingwheel.forge.integration;

import java.util.Objects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;
import nx.pingwheel.common.client.outline.EntityBlockGeometryContext;
import nx.pingwheel.common.client.outline.EntityBlockGeometryTransform;
import nx.pingwheel.common.client.outline.ExternalBlockReadView;
import nx.pingwheel.common.client.outline.ExternalBlockRenderView;
import nx.pingwheel.common.client.outline.OutlineOnlyBufferSource;
import nx.pingwheel.common.client.outline.WorldAwareBlockModelOutlineAdapter;
import nx.pingwheel.common.client.outline.WorldAwareBlockModelOutlineAdapterRegistry;
import org.jetbrains.annotations.Nullable;

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
	public boolean handles(@Nullable EntityBlockGeometryContext context) {
		return context != null && context.hasLiveBakedModelState();
	}

	@Override
	public void render(EntityBlockGeometryContext context, OutlineOnlyBufferSource buffer) {
		ClientLevel level = Objects.requireNonNull(context.level());
		@Nullable ExternalBlockReadView plot = context.externalReadView();
		BlockAndTintGetter modelView = plot == null ? level : new ExternalBlockRenderView(plot, level) {
			@Override
			public float getShade(float x, float y, float z, boolean shade) {
				// Forge's quad lighter supplies the full normal, not just the nearest face.
				return level.getShade(x, y, z, shade);
			}
		};
		BlockPos pos = Objects.requireNonNull(context.blockPos());
		BlockState state = Objects.requireNonNull(context.blockState());
		Vec3 cameraPosition = context.cameraPosition();
		PoseStack poseStack = createPoseStack(context, pos, cameraPosition);
		BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
		BakedModel model = dispatcher.getBlockModel(state);
		long seed = state.getSeed(pos);

		@Nullable ModelData modelData = ModelData.EMPTY;
		if (plot == null) {
			@Nullable var modelDataManager = level.getModelDataManager();
			if (modelDataManager != null) {
				modelData = modelDataManager.getAt(pos);
			}
		} else {
			@Nullable BlockEntity resident = modelView.getBlockEntity(pos);
			if (resident != null) {
				modelData = resident.getModelData();
			}
		}
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
				VertexConsumer consumer = buffer.getBuffer(originalRenderType);
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
