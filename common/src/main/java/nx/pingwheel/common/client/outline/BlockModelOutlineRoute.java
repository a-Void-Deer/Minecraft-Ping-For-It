package nx.pingwheel.common.client.outline;

import java.util.Objects;

/**
 * Pure, deterministic renderer route policy for a block outline spec.
 *
 * <p>Given the authoritative marker {@code targetTypeId} of a winning block
 * marker and whether the current block state passed the compiled native-glow
 * policy (whitelist match and blacklist miss), the policy picks exactly one
 * of:
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
 * target type resolves to {@link #VOXEL}. The caller evaluates the immutable
 * configured policy and passes its effective result here; both concrete block
 * target types therefore obey the same whitelist / blacklist decision.
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
 * <p>{@code entity_block} becomes {@link #ENTITY_BLOCK} only when the
 * effective native-glow decision is true. {@code block} becomes
 * {@link #BLOCK_DISPLAY} only under the same condition.
	 * Any other target type id (including {@code location}, {@code entity},
	 * {@code dropped_item}, and unknown ids) is {@link #VOXEL}.
	 */
	public static BlockModelOutlineRoute route(String targetTypeId, boolean nativeGlowMatches) {
		Objects.requireNonNull(targetTypeId, "targetTypeId");

		if (TARGET_TYPE_ENTITY_BLOCK.equals(targetTypeId) && nativeGlowMatches) {
			return ENTITY_BLOCK;
		}

		if (TARGET_TYPE_BLOCK.equals(targetTypeId) && nativeGlowMatches) {
			return BLOCK_DISPLAY;
		}

		return VOXEL;
	}

	/**
	 * Resolves the native model route for a provider-owned block.
	 *
	 * <p>The outer native-glow decision is still supplied by the same compiled
	 * {@code BlockDisplayPolicy} used for ordinary blocks. Provider-owned
	 * {@code entity_block} targets deliberately do not enter the fixed
	 * BlockEntityRenderer route: a live local block state may safely provide a
	 * baked model, while a long-lived BlockEntity instance at a plot position is
	 * not safe to retain. A non-model state therefore remains on the native
	 * VoxelShape fallback even when the entity-block whitelist gate itself
	 * matches.</p>
	 *
	 * @param targetTypeId      authoritative target type id
	 * @param nativeGlowMatches result of the shared block display policy
	 * @param modelState        whether the live state has {@code MODEL} render
	 *                          shape
	 */
	public static BlockModelOutlineRoute routeExternal(
		String targetTypeId, boolean nativeGlowMatches, boolean modelState
	) {
		Objects.requireNonNull(targetTypeId, "targetTypeId");

		if (!modelState || !nativeGlowMatches) {
			return VOXEL;
		}

		return acceptsForBlockRendering(targetTypeId) ? BLOCK_DISPLAY : VOXEL;
	}
}
