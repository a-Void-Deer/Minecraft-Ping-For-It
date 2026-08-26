package nx.pingwheel.common.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaycastPolicyTest {

	@Test
	void noModifiersUseVisualWithoutFluidSelectionOrIgnoredEntities() {
		RaycastPolicy policy = RaycastPolicy.from(false, false);

		assertEquals(RaycastPolicy.BlockMode.VISUAL, policy.blockMode());
		assertEquals(RaycastPolicy.FluidMode.NONE, policy.fluidMode());
		assertFalse(policy.includeIgnoredEntities());
	}

	@Test
	void shiftUsesOutlineWithoutFluidSelectionOrIgnoredEntities() {
		RaycastPolicy policy = RaycastPolicy.from(true, false);

		assertEquals(RaycastPolicy.BlockMode.OUTLINE, policy.blockMode());
		assertEquals(RaycastPolicy.FluidMode.NONE, policy.fluidMode());
		assertFalse(policy.includeIgnoredEntities());
	}

	@Test
	void ctrlUsesVisualWithFluidSelectionAndIgnoredEntities() {
		RaycastPolicy policy = RaycastPolicy.from(false, true);

		assertEquals(RaycastPolicy.BlockMode.VISUAL, policy.blockMode());
		assertEquals(RaycastPolicy.FluidMode.ANY, policy.fluidMode());
		assertTrue(policy.includeIgnoredEntities());
	}

	@Test
	void shiftAndCtrlUseOutlineWithFluidSelectionAndIgnoredEntities() {
		RaycastPolicy policy = RaycastPolicy.from(true, true);

		assertEquals(RaycastPolicy.BlockMode.OUTLINE, policy.blockMode());
		assertEquals(RaycastPolicy.FluidMode.ANY, policy.fluidMode());
		assertTrue(policy.includeIgnoredEntities());
	}
}
