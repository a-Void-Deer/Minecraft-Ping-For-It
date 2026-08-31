package nx.pingwheel.fabric.integration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.client.outline.EntityBlockGeometryContext;
import nx.pingwheel.common.client.outline.EntityBlockGeometryTransform;
import nx.pingwheel.common.client.outline.OutlineOnlyBufferSource;
import nx.pingwheel.common.client.outline.WorldAwareBlockModelOutlineAdapter;
import nx.pingwheel.common.client.outline.WorldAwareBlockModelOutlineAdapterRegistry;

/**
 * Fabric's generic world-aware baked-model backend for entity-block outlines.
 *
 * <p>This is loader infrastructure rather than an optional-mod integration.
 * The common renderer retains the attempt-local buffer and commits it after
 * this backend returns.  The backend only dispatches the live model and keeps
 * no world, session, or buffer state.</p>
 */
public final class FabricWorldAwareBlockModelOutlineAdapter
	implements WorldAwareBlockModelOutlineAdapter {
	public static final String SOURCE_ID = "pingforit:fabric_world_baked_model";

	private static final FabricWorldAwareBlockModelOutlineAdapter INSTANCE =
		new FabricWorldAwareBlockModelOutlineAdapter();

	/*
	 * This backend has no optional or session-owned state.  Its registration is
	 * intentionally retained for the client process lifetime; re-registering on
	 * connection events would only create unnecessary lifecycle churn.
	 */
	private static WorldAwareBlockModelOutlineAdapterRegistry.Registration registration;

	private FabricWorldAwareBlockModelOutlineAdapter() {
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

		// A provider context may use a local level, but the state still has to be
		// the live block type at the supplied position before world rendering.
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
		VertexConsumer consumer = buffer.getBuffer(RenderType.outline(TextureAtlas.LOCATION_BLOCKS));

		dispatcher.renderBatched(
			state,
			pos,
			level,
			poseStack,
			consumer,
			false,
			RandomSource.create(state.getSeed(pos)));
	}

	private static PoseStack createPoseStack(
		EntityBlockGeometryContext context,
		BlockPos pos,
		Vec3 cameraPosition
	) {
		EntityBlockGeometryTransform transform = context.transform();
		if (transform != null) {
			/*
			 * Keep this pose at the transformed block origin.  The world-aware
			 * tesselation appends BlockState#getOffset to the pose itself.  Since
			 * PoseStack translation is post-multiplied, that later local
			 * translation is evaluated under this transform exactly once.
			 */
			return transform.createPoseStack(pos, cameraPosition, null);
		}

		PoseStack poseStack = new PoseStack();
		// Ordinary entity-block contexts are rooted at the integer block origin.
		poseStack.translate(
			pos.getX() - cameraPosition.x,
			pos.getY() - cameraPosition.y,
			pos.getZ() - cameraPosition.z);
		// Do not apply BlockState#getOffset here: world-aware tesselation owns it.
		return poseStack;
	}
}
