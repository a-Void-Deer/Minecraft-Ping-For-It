package nx.pingwheel.common.math;

import java.util.Objects;

/**
 * Immutable press-time policy for target ray selection.
 *
 * <p>The fluid mode deliberately has only the safe, required value.  The
 * modifier for ignored entities is independent from block selection so Ctrl
 * cannot accidentally change the block or fluid raycast mode.</p>
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
			FluidMode.NONE,
			includeIgnoredEntities);
	}

	public enum BlockMode {
		OUTLINE,
		VISUAL
	}

	public enum FluidMode {
		NONE
	}
}
