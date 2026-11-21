package nx.pingwheel.common.client.outline;

import java.util.Objects;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import nx.pingwheel.common.marker.TargetKey;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

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
	 * <p>The caller reapplies the captured camera-relative world model-view
	 * matrix only around this post-world-composite draw. This pass builds
	 * camera-relative vertices from the supplied frame camera position with its
	 * own identity {@link PoseStack} and writes them
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
	 * snapshot, so an external subject's success does not suppress a sibling.
	 */
	public static void render(
		ClientLevel level,
		Camera camera,
		Vec3 cameraPosition,
		VertexConsumer lines,
		BlockModelOutlineState frameState
	) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(camera, "camera");
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		Objects.requireNonNull(lines, "lines");
		Objects.requireNonNull(frameState, "frameState");

		String dimensionId = level.dimension().location().toString();
		@Nullable Entity cameraEntity = camera.getEntity();
		CollisionContext collisionContext =
			cameraEntity == null ? CollisionContext.empty() : CollisionContext.of(cameraEntity);
		PoseStack poseStack = new PoseStack();
		@Unmodifiable Set<BlockPresentationSuccessKey> modelOutlineKeys = frameState.successKeys();

		for (BlockPresentation presentation : frameState.presentations()) {
			BlockOutlineSpec spec = presentation.sourceSpec();
			TargetKey.BlockKey blockKey = spec.blockKey();

			if (!blockKey.dimensionId().equals(dimensionId)) {
				continue;
			}

			for (BlockRenderSubject subject : presentation.renderSubjects()) {
				BlockPos pos = subject.blockPos();

				if (!BlockPresentationSubjectValidation.isLoadedAndCurrent(level, subject)) {
					continue;
				}

				BlockState blockState = subject.blockState();

				// Native/model success suppresses only VoxelShape generation. The
				// optional Flywheel source writes its vanilla outline mask before the
				// entity-outline batch ends, so it needs no late line state here.
				if (modelOutlineKeys.contains(subject.successKey(spec))) {
					continue;
				}

				@Nullable VoxelShape shape = blockState.getShape(level, pos, collisionContext);

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

		@Unmodifiable Set<ExternalBlockFramePresentation.SuccessKey> externalSuccesses = frameState.externalSuccessKeys();
		for (ExternalBlockFramePresentation presentation : frameState.externalPresentations()) {
			if (presentation.isFor(level, dimensionId)) {
				renderExternal(presentation, externalSuccesses, collisionContext, cameraPosition, lines);
			}
		}
	}

	static void renderExternal(ExternalBlockFramePresentation presentation,
		Set<ExternalBlockFramePresentation.SuccessKey> succeeded, CollisionContext context,
		Vec3 cameraPosition, VertexConsumer lines) {
		presentation.visitFallbackShapes(succeeded, context, (subject, shape) ->
			VoxelShapeRenderUtil.renderEdges(presentation.subjectPose(subject, cameraPosition), lines, shape,
				0.0, 0.0, 0.0, presentation.spec().argbColor()));
	}
}
