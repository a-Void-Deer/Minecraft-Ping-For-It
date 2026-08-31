package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.integration.sable.client.SableClientProvider;

/**
 * Main-thread block outline render pass.
 *
 * <p>Draws the current native {@link VoxelShape} wireframe of every ordinary
 * and provider-owned block whose model/geometry route did not emit geometry in
	 * the prepared presentation and external snapshots into the given line buffer.
 * The pass is deliberately conservative and never mutates the level:
 * <ul>
 *   <li>specs whose dimension differs from the level's are skipped;</li>
 *   <li>unloaded blocks are skipped;</li>
 *   <li>the subject state's registry id is compared exactly to its expected
	 *       id, so a replaced subject type drops the outline;</li>
	 *   <li>a same-type {@code BlockState} change is represented by the current
	 *       frame presentation instead of a cached source reconstruction;</li>
 *   <li>null or empty shapes are skipped; no full-cube fallback exists.</li>
 * </ul>
 *
 * <p>The caller (the {@code LevelRendererMixin} anchor) already has the
 * camera-relative model-view matrix applied, so this pass builds camera-
 * relative vertices with its own identity {@link PoseStack} and writes them
 * into the caller-provided custom block outline buffer ({@link
 * BlockOutlineRenderType#BLOCK_OUTLINE}). Nothing is flushed here: the
 * caller flushes exactly that custom batch after the pass returns, and the
 * vanilla {@code lines()} batch is never touched.
 */
public final class BlockOutlineRenderer {

	private BlockOutlineRenderer() {}

	/**
	 * Renders ordinary and provider-owned block outlines for one render frame.
	 * Each success set is keyed in the same domain as its corresponding outline
	 * snapshot, so a provider-owned geometry success suppresses only that
	 * external key's VoxelShape fallback.
	 *
	 * @param presentations             the immutable ordinary presentation
	 *                                 snapshot resolved for this frame
	 * @param externalModelOutlineKeys the per-frame external keys whose model or
	 *                                 entity geometry pass succeeded; fallbacks
	 *                                 for those keys are skipped
	 */
	public static void render(
		ClientLevel level,
		Camera camera,
		VertexConsumer lines,
		BlockOutlineState state,
		List<BlockPresentation> presentations,
		Set<BlockPresentationSuccessKey> modelOutlineKeys,
		Set<TargetKey.ExternalBlockKey> externalModelOutlineKeys,
		float partialTick
	) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(camera, "camera");
		Objects.requireNonNull(lines, "lines");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(presentations, "presentations");
		Objects.requireNonNull(modelOutlineKeys, "modelOutlineKeys");
		Objects.requireNonNull(externalModelOutlineKeys, "externalModelOutlineKeys");

		String dimensionId = level.dimension().location().toString();
		Entity cameraEntity = camera.getEntity();
		CollisionContext collisionContext =
			cameraEntity == null ? CollisionContext.empty() : CollisionContext.of(cameraEntity);
		Vec3 cameraPosition = camera.getPosition();
		PoseStack poseStack = new PoseStack();

		for (BlockPresentation presentation : presentations) {
			BlockOutlineSpec spec = presentation.sourceSpec();
			TargetKey.BlockKey blockKey = spec.blockKey();

			if (!blockKey.dimensionId().equals(dimensionId)) {
				continue;
			}

			for (BlockRenderSubject subject : presentation.renderSubjects()) {
				BlockPos pos = subject.blockPos();

				if (!level.hasChunkAt(pos)) {
					continue;
				}

				BlockState blockState = subject.blockState();

				var actualRegistryKey = BuiltInRegistries.BLOCK.getKey(blockState.getBlock());

				if (actualRegistryKey == null
					|| !subject.expectedBlockRegistryId().equals(actualRegistryKey.toString())) {
					continue;
				}

				// Native/model success suppresses only VoxelShape generation. The
				// optional Flywheel source writes its vanilla outline mask before the
				// entity-outline batch ends, so it needs no late line state here.
				if (modelOutlineKeys.contains(subject.successKey(spec))) {
					continue;
				}

				VoxelShape shape = blockState.getShape(level, pos, collisionContext);

				if (shape == null || shape.isEmpty()) {
					continue;
				}

				poseStack.pushPose();
				poseStack.translate(
					pos.getX() - cameraPosition.x,
					pos.getY() - cameraPosition.y,
					pos.getZ() - cameraPosition.z);

				VoxelShapeRenderUtil.renderEdges(
					poseStack, lines, shape, 0.0, 0.0, 0.0, spec.argbColor());

				poseStack.popPose();
			}
		}

		for (Map.Entry<TargetKey.ExternalBlockKey, ExternalBlockOutlineSpec> entry
			: state.externalSnapshot().entrySet()) {
			ExternalBlockOutlineSpec spec = entry.getValue();
			TargetKey.ExternalBlockKey blockKey = entry.getKey();

			if (!blockKey.dimensionId().equals(dimensionId)) {
				continue;
			}

			if (externalModelOutlineKeys.contains(blockKey)) {
				continue;
			}

			var presentation = SableClientProvider.resolvePresentation(
				level,
				spec.target(),
				partialTick,
				collisionContext);

			if (presentation.isEmpty()) {
				continue;
			}

			var resolved = presentation.get();
			poseStack.pushPose();
			// Resolve the integer corner in double precision before entering the
			// PoseStack. Only the camera-relative origin and the small linear pose
			// are allowed into float-backed render state; this avoids losing whole
			// blocks when Sable plots are around twenty million coordinates.
			ExternalBlockOutlineTransform.apply(
				poseStack,
				resolved.worldBlockOrigin(),
				resolved.orientationScale(),
				cameraPosition);
			VoxelShapeRenderUtil.renderEdges(
				poseStack, lines, resolved.shape(), 0.0, 0.0, 0.0, spec.argbColor());
			poseStack.popPose();
		}
	}
}
