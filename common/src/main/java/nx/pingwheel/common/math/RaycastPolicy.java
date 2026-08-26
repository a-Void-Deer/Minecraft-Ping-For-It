package nx.pingwheel.common.math;

import java.util.Objects;

/**
 * Immutable press-time policy for target ray selection.
 *
 * <p>Shift controls only the block clip mode. Ctrl independently includes
 * blacklisted entities and fluids in the raycast.</p>
 */
public record RaycastPolicy(
	BlockMode blockMode,
	FluidMode fluidMode,
	boolean includeIgnoredEntities
) {

	public RaycastPolicy {
		Objects.requireNonNull(blockMode, "blockMode");
		Objects.requireNonNull(fluidMode, "fluidMode");
	}

	/** Builds the policy sampled from the two target-selection hold mappings. */
	public static RaycastPolicy from(
		boolean selectTransparentBlocks,
		boolean includeIgnoredEntities
	) {
		return new RaycastPolicy(
			selectTransparentBlocks ? BlockMode.OUTLINE : BlockMode.VISUAL,
			includeIgnoredEntities ? FluidMode.ANY : FluidMode.NONE,
			includeIgnoredEntities);
	}

	public enum BlockMode {
		OUTLINE,
		VISUAL
	}

	public enum FluidMode {
		NONE,
		ANY
	}
}
