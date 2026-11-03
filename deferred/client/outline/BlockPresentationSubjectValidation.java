package nx.pingwheel.common.client.outline;

import java.util.Objects;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Validation shared by the model and late native-shape presentation passes.
 *
 * <p>{@link ClientLevel#getBlockState(net.minecraft.core.BlockPos)} reads the
 * client chunk cache; it does not synchronously request or load a server
 * chunk. The explicit {@link ClientLevel#hasChunkAt} guard remains important
 * because both render passes must skip an unloaded subject without querying
 * it. Resolver neighbor reads therefore remain loader-neutral and do not need
 * a widened context API.</p>
 */
final class BlockPresentationSubjectValidation {

	private BlockPresentationSubjectValidation() {}

	/** Returns false for an unloaded subject or a live block-type replacement. */
	static boolean isLoadedAndCurrent(ClientLevel level, BlockRenderSubject subject) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(subject, "subject");

		if (!level.hasChunkAt(subject.blockPos())) {
			return false;
		}

		return hasExpectedRegistryId(subject, level.getBlockState(subject.blockPos()));
	}

	/** Checks the registry id of a live state against the frozen subject identity. */
	static boolean hasExpectedRegistryId(BlockRenderSubject subject, BlockState liveState) {
		Objects.requireNonNull(subject, "subject");
		Objects.requireNonNull(liveState, "liveState");

		var actualRegistryKey = BuiltInRegistries.BLOCK.getKey(liveState.getBlock());
		return actualRegistryKey != null
			&& subject.expectedBlockRegistryId().equals(actualRegistryKey.toString());
	}
}
