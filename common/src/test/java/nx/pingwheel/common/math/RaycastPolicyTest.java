package nx.pingwheel.common.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaycastPolicyTest {

	@Test
	void shiftSelectsOutlineWithoutFluidSelection() {
		RaycastPolicy policy = RaycastPolicy.from(true, false);

		assertEquals(RaycastPolicy.BlockMode.OUTLINE, policy.blockMode());
		assertEquals(RaycastPolicy.FluidMode.NONE, policy.fluidMode());
		assertFalse(policy.includeIgnoredEntities());
	}

	@Test
	void defaultSelectionUsesVisualWithoutFluidSelection() {
		RaycastPolicy policy = RaycastPolicy.from(false, false);

		assertEquals(RaycastPolicy.BlockMode.VISUAL, policy.blockMode());
		assertEquals(RaycastPolicy.FluidMode.NONE, policy.fluidMode());
		assertFalse(policy.includeIgnoredEntities());
	}

	@Test
	void ctrlOnlyChangesWhetherIgnoredEntitiesAreIncluded() {
		RaycastPolicy withoutCtrl = RaycastPolicy.from(false, false);
		RaycastPolicy withCtrl = RaycastPolicy.from(false, true);

		assertEquals(withoutCtrl.blockMode(), withCtrl.blockMode());
		assertEquals(withoutCtrl.fluidMode(), withCtrl.fluidMode());
		assertFalse(withoutCtrl.includeIgnoredEntities());
		assertTrue(withCtrl.includeIgnoredEntities());
	}
}
