package nx.pingwheel.common.client.outline;

import java.util.Objects;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;

/**
 * An immutable outline specification for one pinged block.
 *
 * <p>A spec carries the authoritative marker that currently controls the
 * visible outline, the frozen block identity ({@link TargetKey.BlockKey}:
 * dimension + exact position + block registry id, so a {@code BlockState}-only
 * change never changes it while a block type replacement does), the
 * authoritative marker {@code targetTypeId} (used by the renderer to route
 * between the {@code entity_block} BlockEntity pass, the ordinary
 * {@code block} BlockDisplay pass, and the VoxelShape fallback), the ping type
 * id the color was resolved from, and the fully opaque ARGB outline color.
 *
 * <p>The compact constructor is strict: every reference must be non-null and
 * neither id may be blank. The color is forced opaque via
 * {@code 0xFF000000 | (color & 0x00FFFFFF)}, discarding any caller-supplied
 * alpha, so a call site can never produce a transparent outline.
 */
public record BlockOutlineSpec(
	MarkerId markerId,
	TargetKey.BlockKey blockKey,
	String targetTypeId,
	String pingTypeId,
	int argbColor
) {

	public BlockOutlineSpec {
		Objects.requireNonNull(markerId, "markerId");
		Objects.requireNonNull(blockKey, "blockKey");
		Objects.requireNonNull(targetTypeId, "targetTypeId");
		Objects.requireNonNull(pingTypeId, "pingTypeId");

		if (targetTypeId.isBlank()) {
			throw new IllegalArgumentException("targetTypeId must not be blank");
		}

		if (pingTypeId.isBlank()) {
			throw new IllegalArgumentException("pingTypeId must not be blank");
		}

		argbColor = 0xFF000000 | (argbColor & 0x00FFFFFF);
	}
}
