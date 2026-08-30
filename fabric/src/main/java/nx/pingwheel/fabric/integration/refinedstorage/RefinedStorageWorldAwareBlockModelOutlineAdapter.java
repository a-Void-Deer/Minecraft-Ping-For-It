package nx.pingwheel.fabric.integration.refinedstorage;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.client.outline.EntityBlockGeometryContext;
import nx.pingwheel.common.client.outline.OutlineOnlyBufferSource;
import nx.pingwheel.common.client.outline.RefinedStorageCableBlockMatcher;
import nx.pingwheel.common.client.outline.WorldAwareBlockModelOutlineAdapter;
import nx.pingwheel.common.client.outline.WorldAwareBlockModelOutlineAdapterRegistry;
import nx.pingwheel.common.marker.TargetKey;

/**
 * Fabric's world-aware baked-model route for Refined Storage 2 cables.
 *
 * <p>Indigo must receive the real level and position here: Refined Storage's
 * {@code CableBakedModel} obtains its connection data from
 * {@code FabricBlockView#getBlockEntityRenderData(pos)} during quad emission.
 * No connection data is read or reconstructed by this adapter.</p>
 */
@Environment(EnvType.CLIENT)
public final class RefinedStorageWorldAwareBlockModelOutlineAdapter
	implements WorldAwareBlockModelOutlineAdapter {
	public static final String SOURCE_ID = "pingforit:refined_storage_cable";

	private static final RefinedStorageWorldAwareBlockModelOutlineAdapter INSTANCE =
		new RefinedStorageWorldAwareBlockModelOutlineAdapter();
	private static WorldAwareBlockModelOutlineAdapterRegistry.Registration registration;

	private RefinedStorageWorldAwareBlockModelOutlineAdapter() {
	}

	/** Registers exactly once until the retained registration is closed. */
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

	/** Closes the exact registration handle and permits the next session to re-register. */
	public static synchronized void close() {
		WorldAwareBlockModelOutlineAdapterRegistry.Registration current = registration;
		registration = null;
		if (current != null) {
			current.close();
		}
	}

	@Override
	public String id() {
		return SOURCE_ID;
	}

	@Override
	public boolean handles(EntityBlockGeometryContext context) {
		if (context == null || context.transform() != null
			|| !(context.targetKey() instanceof TargetKey.BlockKey blockKey)) {
			return false;
		}

		ClientLevel level = context.level();
		BlockPos pos = context.blockPos();
		BlockState state = context.blockState();
		if (level == null || pos == null || state == null || !level.hasChunkAt(pos)) {
			return false;
		}

		if (blockKey.dimensionId() == null || blockKey.blockRegistryId() == null
			|| !blockKey.dimensionId().equals(level.dimension().location().toString())
			|| blockKey.x() != pos.getX()
			|| blockKey.y() != pos.getY()
			|| blockKey.z() != pos.getZ()) {
			return false;
		}

		BlockState liveState = level.getBlockState(pos);
		if (liveState == null || liveState.getBlock() != state.getBlock()) {
			return false;
		}

		var blockId = BuiltInRegistries.BLOCK.getKey(liveState.getBlock());
		return blockId != null
			&& blockKey.blockRegistryId().equals(blockId.toString())
			&& state.getRenderShape() == RenderShape.MODEL
			&& RefinedStorageCableBlockMatcher.matches(blockId, liveState.getBlock().getClass());
	}

	@Override
	public void render(EntityBlockGeometryContext context, OutlineOnlyBufferSource buffer) {
		ClientLevel level = context.level();
		BlockPos pos = context.blockPos();
		BlockState state = context.blockState();
		Vec3 cameraPosition = context.cameraPosition();
		BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();

		PoseStack poseStack = new PoseStack();
		poseStack.pushPose();
		try {
			poseStack.translate(
				pos.getX() - cameraPosition.x,
				pos.getY() - cameraPosition.y,
				pos.getZ() - cameraPosition.z);
			VertexConsumer consumer = buffer.getBuffer(RenderType.outline(TextureAtlas.LOCATION_BLOCKS));
			dispatcher.renderBatched(
				state,
				pos,
				level,
				poseStack,
				consumer,
				false,
				RandomSource.create(state.getSeed(pos)));
		} finally {
			poseStack.popPose();
		}
	}
}
