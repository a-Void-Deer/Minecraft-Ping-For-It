package nx.pingwheel.common.client.outline;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import nx.pingwheel.common.marker.TargetKey;

/**
 * One live block render target produced by a block presentation.
 *
 * <p>A subject owns all state needed by a later client render attempt. In
 * particular, the expected registry id is retained independently of the live
 * state so the render route can validate the subject before using it.</p>
 */
public record BlockRenderSubject(
	String subjectId,
	BlockPos blockPos,
	BlockState blockState,
	String expectedBlockRegistryId,
	String renderTargetTypeId,
	BlockPresentationRelation relation
) {

	public BlockRenderSubject {
		Objects.requireNonNull(subjectId, "subjectId");
		Objects.requireNonNull(blockPos, "blockPos");
		Objects.requireNonNull(blockState, "blockState");
		Objects.requireNonNull(expectedBlockRegistryId, "expectedBlockRegistryId");
		Objects.requireNonNull(renderTargetTypeId, "renderTargetTypeId");
		Objects.requireNonNull(relation, "relation");

		if (subjectId.isBlank()) {
			throw new IllegalArgumentException("subjectId must not be blank");
		}

		if (expectedBlockRegistryId.isBlank()) {
			throw new IllegalArgumentException("expectedBlockRegistryId must not be blank");
		}

		if (!renderTargetTypeId.equals("block") && !renderTargetTypeId.equals("entity_block")) {
			throw new IllegalArgumentException(
				"renderTargetTypeId must be block or entity_block");
		}
	}

	/** Alias emphasizing that the position is the subject's render position. */
	public BlockPos renderPos() {
		return blockPos;
	}

	/** Alias for callers that use the Minecraft state terminology. */
	public BlockState state() {
		return blockState;
	}

	/** Stable success key for this subject under one captured source. */
	public BlockPresentationSuccessKey successKey(TargetKey.BlockKey sourceKey) {
		return new BlockPresentationSuccessKey(sourceKey, subjectId);
	}

	/** Stable success key for this subject under one captured source spec. */
	public BlockPresentationSuccessKey successKey(BlockOutlineSpec sourceSpec) {
		Objects.requireNonNull(sourceSpec, "sourceSpec");
		return successKey(sourceSpec.blockKey());
	}
}
