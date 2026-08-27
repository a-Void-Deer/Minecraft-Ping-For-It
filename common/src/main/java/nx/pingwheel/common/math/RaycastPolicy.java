package nx.pingwheel.common.math;

import java.util.Objects;

/**
 * Immutable press-time policy for target ray selection.
 *
 * <p>Each target-selection toggle controls only its corresponding raycast
 * concern. The immutable value is sampled when the ping interaction starts.</p>
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

	/** Builds the policy sampled from the persistent target-selection toggles. */
	public static RaycastPolicy from(
		boolean passThroughTransparentBlocks,
		boolean markBlacklistedTargets,
		boolean markFluids
	) {
		return new RaycastPolicy(
			passThroughTransparentBlocks ? BlockMode.VISUAL : BlockMode.OUTLINE,
			markFluids ? FluidMode.ANY : FluidMode.NONE,
			markBlacklistedTargets);
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
