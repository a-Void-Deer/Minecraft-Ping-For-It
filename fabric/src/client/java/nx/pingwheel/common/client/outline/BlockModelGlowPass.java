package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.EntityBlockRenderMode;

import static nx.pingwheel.common.Global.LOGGER;

/**
 * M7c C1/C2: native model glow through the outline channel only, for two sources of
 * geometry:
 * <ol>
 *   <li>{@code block} winners — always the baked model;</li>
 *   <li>{@code entity_block} winners whose block entity did not glow through its own BER
 *       model this frame (a furnace has no renderer in 26.3, a campfire's renderer submits
 *       only items) — the baked model is the upstream fallback there.</li>
 * </ol>
 * All permitted sources run in order without short-circuiting (geometry_sources.md): a
 * dynamic BER and a static baked model may both contribute, so one successful source does
 * not stop the other on the same subject. The combined result suppresses the VoxelShape
 * fallback only when at least one source actually reported {@code RENDERED}.
 *
 * <p>The first version of this pass used {@code submitMovingBlock(pose, state, color)},
 * which was wrong: that helper submits the ordinary model first (with
 * {@code outlineColor == 0}) and only then an outline submit, so a non-zero colour made
 * the block render twice — translucent blocks double-blended darker and every block paid
 * a second tessellation. It was also not equivalent to upstream's
 * {@code VirtualBlockDisplay}, which writes the outline buffer only.
 *
 * <p>This pass instead submits the model parts with an <em>outline</em> {@link RenderType}:
 * <ul>
 *   <li>{@code RenderType.outline()} of a moving-block type, so the type reports
 *       {@code isOutline() == true};</li>
 *   <li>{@code submitBlockModel} skips the ordinary submit for outline types and routes
 *       straight to {@code getOutlineRenderType(type)}, which returns the outline type
 *       itself — one outline submission, no duplicate model draw;</li>
 *   <li>the outline path ignores tint/light/overlay, so {@code EMPTY_TINTS} and a
 *       full-bright value are used, matching upstream's outline-buffer write.</li>
 * </ul>
 *
 * <p><strong>Suppression is decided beforehand.</strong> {@code submitBlockModel} is
 * {@code void}, so success cannot be observed afterwards. Accepting a marker here means
 * {@link BlockOutlinePass} must skip its VoxelShape submission for that same marker.
 * Anything declined (another target type, a non-{@code MODEL} render shape, the
 * {@code VOXEL_SHAPE_ONLY} entity-block mode, blacklist hit, whitelist miss, empty parts,
 * missing outline type) keeps the M7a wireframe.
 *
 * <p>Failure policy: one {@code WARN}, then {@code glowDisabled} latches for the session
 * so every marker falls back to the wireframe. The latch is deliberately not reset (known
 * limitation, tracked for M7c C3).
 */
public final class BlockModelGlowPass {

	private static final String TARGET_TYPE_BLOCK = "block";
	private static final String TARGET_TYPE_ENTITY_BLOCK = "entity_block";

	/** Vanilla full-bright packed light value; the outline path ignores it anyway. */
	private static final int FULL_BRIGHT = 15728880;

	private static boolean glowDisabled = false;
	private static boolean failureLogged = false;
	private static boolean activationLogged = false;
	private static boolean outlineTypeResolved = false;
	private static RenderType outlineType = null;

	private BlockModelGlowPass() {}

	/**
	 * Attempts the model-glow route for one already-filtered winner block.
	 *
	 * @return {@code true} when the marker was submitted as a model glow and the caller
	 *         must skip its wireframe; {@code false} when the caller keeps the wireframe.
	 */
	public static boolean trySubmit(
		ClientLevel level,
		PoseStack poseStack,
		SubmitNodeCollector collector,
		ClientMarker marker,
		BlockState state,
		BlockPos pos,
		Vec3 cameraPosition,
		int argbColor
	) {
		if (glowDisabled || poseStack == null || collector == null || marker == null
			|| state == null || pos == null || cameraPosition == null) {
			return false;
		}

		final String targetTypeId = marker.targetTypeId();

		if (!TARGET_TYPE_BLOCK.equals(targetTypeId) && !TARGET_TYPE_ENTITY_BLOCK.equals(targetTypeId)) {
			return false;
		}

		// The entity-block mode is a client-local, per-attempt gate (geometry_sources.md):
		// VOXEL_SHAPE_ONLY constructs no source context and attempts no normal source, so the
		// BER pass and this baked-model source both decline and the VoxelShape route is used.
		// ALL and COMPATIBLE execute exactly these built-in sources; ALL's optional-source
		// registry is not wired until M9, so there is deliberately no extra branch for it.
		// Ordinary block rendering never reads this mode.
		if (TARGET_TYPE_ENTITY_BLOCK.equals(targetTypeId)
			&& ClientConfig.HANDLER.getConfig().getEntityBlockRenderMode() == EntityBlockRenderMode.VOXEL_SHAPE_ONLY) {
			return false;
		}

		try {
			// Both the ordinary block source and the entity-block baked source require a live
			// render shape MODEL (geometry_sources.md); it is an eligibility condition, not a
			// renderability result.
			if (state.getRenderShape() != RenderShape.MODEL) {
				return false;
			}

			BlockDisplayPolicy policy = ClientConfig.HANDLER.getConfig().getBlockDisplayPolicy();

			if (!policy.shouldUseNativeGlow(targetTypeId, state)) {
				return false;
			}

			RenderType type = resolveOutlineType();

			if (type == null) {
				return false;
			}

			Minecraft game = Minecraft.getInstance();

			if (game == null) {
				return false;
			}

			BlockStateModelSet modelSet = game.getModelManager().getBlockStateModelSet();
			BlockStateModel model = modelSet.get(state);

			// A state without blockstate geometry falls back to the missing model, which is a
			// non-empty cube; drawing it would put a plain box glow on an invisible block.
			if (model == null || model == modelSet.missingModel()) {
				return false;
			}

			List<BlockStateModelPart> parts = new ArrayList<>();
			RandomSource random = RandomSource.createThreadLocalInstance();
			random.setSeed(state.getSeed(pos));
			model.collectParts(random, parts);

			// Upstream falls back to the VoxelShape when the model yields no geometry.
			if (parts.isEmpty()) {
				return false;
			}

			Vec3 modelOffset = state.getOffset(pos);

			poseStack.pushPose();

			try {
				poseStack.translate(
					pos.getX() - cameraPosition.x + modelOffset.x,
					pos.getY() - cameraPosition.y + modelOffset.y,
					pos.getZ() - cameraPosition.z + modelOffset.z);
				collector.submitBlockModel(
					poseStack,
					type,
					List.copyOf(parts),
					BlockModelRenderState.EMPTY_TINTS,
					FULL_BRIGHT,
					OverlayTexture.NO_OVERLAY,
					argbColor);
			} finally {
				poseStack.popPose();
			}

			if (!activationLogged) {
				activationLogged = true;
				LOGGER.info("block model glow active (outline-only submitBlockModel)");
			}

			return true;
		} catch (RuntimeException | LinkageError | AssertionError failure) {
			disable("block model glow failed; falling back to wireframe for this session", failure);
			return false;
		}
	}

	/** Resolves (once) the outline render type used for the model submission. */
	private static RenderType resolveOutlineType() {
		if (outlineTypeResolved) {
			return outlineType;
		}

		outlineTypeResolved = true;

		RenderType solidOutline = RenderTypes.solidMovingBlock().outline().orElse(null);

		if (solidOutline != null) {
			outlineType = solidOutline;
			return outlineType;
		}

		RenderType translucentOutline = RenderTypes.translucentMovingBlock().outline().orElse(null);

		if (translucentOutline != null) {
			outlineType = translucentOutline;
			return outlineType;
		}

		disable("no outline moving-block render type available; falling back to wireframe for this session", null);
		return null;
	}

	private static void disable(String message, Throwable failure) {
		glowDisabled = true;

		if (failureLogged) {
			return;
		}

		failureLogged = true;

		if (failure == null) {
			LOGGER.warn(message);
		} else {
			LOGGER.warn("{} ({})", message, failure.getClass().getSimpleName());
		}
	}
}
