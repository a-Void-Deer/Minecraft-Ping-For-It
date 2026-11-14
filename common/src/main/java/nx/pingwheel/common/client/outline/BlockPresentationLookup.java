package nx.pingwheel.common.client.outline;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;

/**
 * Frame-local lookup shared by the outline and entity-block glow passes.
 *
 * <p>A marker still carries the captured half-block identity. The presentation
 * registry expands that identity into the door/bed subjects the current frame
 * should render; this helper only builds the source spec and resolves it against
 * the live client world. It never rewrites the marker or requests a chunk.</p>
 */
final class BlockPresentationLookup {

	/** Placeholder outline colour: the lookup consumes geometry only. */
	private static final int GEOMETRY_ONLY_COLOR = 0xFFFFFFFF;

	private BlockPresentationLookup() {}

	/** Builds the outline spec of a captured block marker; empty for other targets. */
	static Optional<BlockOutlineSpec> specFor(ClientMarker marker) {
		Objects.requireNonNull(marker, "marker");

		if (!(marker.target() instanceof Target.BlockTarget)) {
			return Optional.empty();
		}

		TargetKey targetKey = TargetKey.from(marker.target());

		if (!(targetKey instanceof TargetKey.BlockKey blockKey)) {
			return Optional.empty();
		}

		return Optional.of(new BlockOutlineSpec(
			marker.id(),
			blockKey,
			marker.targetTypeId(),
			marker.pingTypeId(),
			GEOMETRY_ONLY_COLOR));
	}

	/** Resolves the presentation of a captured block marker against the client world. */
	static Optional<BlockPresentation> presentation(@Nullable ClientLevel level, ClientMarker marker) {
		Objects.requireNonNull(marker, "marker");

		if (level == null) {
			return Optional.empty();
		}

		if (!marker.target().dimensionId().equals(level.dimension().identifier().toString())) {
			return Optional.empty();
		}

		Optional<BlockOutlineSpec> spec = specFor(marker);

		if (spec.isEmpty()) {
			return Optional.empty();
		}

		BlockOutlineSpec sourceSpec = spec.get();

		if (!level.hasChunkAt(sourcePos(sourceSpec))) {
			return Optional.empty();
		}

		try {
			BlockPresentation presentation =
				BlockPresentationResolverRegistry.INSTANCE.resolve(level, sourceSpec);
			return presentation.isEmpty() ? Optional.empty() : Optional.of(presentation);
		} catch (RuntimeException | LinkageError | AssertionError failure) {
			// Presentation stays optional: a failing resolver must never hide the marker.
			return Optional.empty();
		}
	}

	private static BlockPos sourcePos(BlockOutlineSpec spec) {
		TargetKey.BlockKey blockKey = spec.blockKey();
		return new BlockPos(blockKey.x(), blockKey.y(), blockKey.z());
	}
}
