package nx.pingwheel.common.client.outline;

import java.util.Objects;

/**
 * Pure, deterministic renderer route policy for a block outline spec.
 *
 * <p>Given the authoritative marker {@code targetTypeId} of a winning block
 * marker and whether the current block state is a whitelisted ordinary block,
 * the policy picks exactly one of:
 * <ul>
 *   <li>{@link #ENTITY_BLOCK} — the target block actually owns a Minecraft
 *       {@code BlockEntity}: render its real {@code BlockEntityRenderer}
 *       geometry into the outline pass;</li>
 *   <li>{@link #BLOCK_DISPLAY} — the target is a plain {@code block} and the
 *       current state passes the ordinary-block display whitelist: render the
 *       vanilla model glow through a virtual {@code BlockDisplay};</li>
 *   <li>{@link #VOXEL} — everything else, including the pure location
 *       fallback, unknown target types, and non-whitelisted ordinary blocks:
 *       the late custom VoxelShape outline.</li>
 * </ul>
 *
 * <p>Only the confirmed built-in target type ids {@code block} and
 * {@code entity_block} participate in block rendering at all; every other
 * target type resolves to {@link #VOXEL}. The whitelist is deliberately
 * evaluated lazily by the caller and never applied to {@code entity_block}
 * targets: this policy short-circuits the {@code entity_block} id before the
 * whitelist result is consulted.
 *
 * <p>This class is pure JDK and fully headless-testable; no Minecraft classes
 * are referenced.
 */
public enum BlockModelOutlineRoute {

	/** The target owns a Minecraft {@code BlockEntity}. */
	ENTITY_BLOCK,

	/** The target is a whitelisted ordinary block rendered via {@code BlockDisplay}. */
	BLOCK_DISPLAY,

	/** The target falls back to the custom VoxelShape outline. */
	VOXEL;

	/** The confirmed built-in id of the generic block target type. */
	public static final String TARGET_TYPE_BLOCK = "block";

	/** The confirmed built-in id of the block-entity target type. */
	public static final String TARGET_TYPE_ENTITY_BLOCK = "entity_block";

	/**
	 * Whether {@code targetTypeId} is one of the ids that participate in
	 * block rendering at all ({@code block} or {@code entity_block}).
	 */
	public static boolean acceptsForBlockRendering(String targetTypeId) {
		Objects.requireNonNull(targetTypeId, "targetTypeId");
		return TARGET_TYPE_BLOCK.equals(targetTypeId)
			|| TARGET_TYPE_ENTITY_BLOCK.equals(targetTypeId);
	}

	/**
	 * Resolves the renderer route for the winning block marker.
	 *
	 * <p>{@code entity_block} wins unconditionally — the ordinary-block
	 * whitelist is never applied to it. {@code block} becomes
	 * {@link #BLOCK_DISPLAY} only when {@code whitelistMatches} is true.
	 * Any other target type id (including {@code location}, {@code entity},
	 * {@code dropped_item}, and unknown ids) is {@link #VOXEL}.
	 */
	public static BlockModelOutlineRoute route(String targetTypeId, boolean whitelistMatches) {
		Objects.requireNonNull(targetTypeId, "targetTypeId");

		if (TARGET_TYPE_ENTITY_BLOCK.equals(targetTypeId)) {
			return ENTITY_BLOCK;
		}

		if (TARGET_TYPE_BLOCK.equals(targetTypeId) && whitelistMatches) {
			return BLOCK_DISPLAY;
		}

		return VOXEL;
	}
}
