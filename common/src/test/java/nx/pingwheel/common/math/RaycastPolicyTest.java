package nx.pingwheel.common.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RaycastPolicyTest {

	@Test
	void allEightToggleCombinationsRemainIndependent() {
		for (boolean markTransparent : new boolean[] {false, true}) {
			for (boolean markBlacklisted : new boolean[] {false, true}) {
				for (boolean markFluids : new boolean[] {false, true}) {
					RaycastPolicy policy = RaycastPolicy.from(markTransparent, markBlacklisted, markFluids);

					assertEquals(
						markTransparent ? RaycastPolicy.BlockMode.OUTLINE : RaycastPolicy.BlockMode.VISUAL,
						policy.blockMode());
					assertEquals(
						markFluids ? RaycastPolicy.FluidMode.ANY : RaycastPolicy.FluidMode.NONE,
						policy.fluidMode());
					assertEquals(markBlacklisted, policy.includeIgnoredEntities());
				}
			}
		}
	}
}
