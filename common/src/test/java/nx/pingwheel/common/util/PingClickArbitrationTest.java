package nx.pingwheel.common.util;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertFalse;
import static nx.pingwheel.common.testing.Assertions.assertTrue;

class PingClickArbitrationTest {

	@Test
	void sharedEligibleClaimsWhenOnlyPickMappingReceivedTheEdge() {
		PingClickArbitration.Plan plan = PingClickArbitration.plan(true, true);

		assertTrue(plan.consumePick());
		assertTrue(plan.consumeCustom());
		assertTrue(plan.claims(true, false));
	}

	@Test
	void sharedEligibleClaimsWhenOnlyCustomMappingReceivedTheEdge() {
		PingClickArbitration.Plan plan = PingClickArbitration.plan(true, true);

		assertTrue(plan.claims(false, true));
		assertTrue(plan.claims(true, true));
	}

	@Test
	void sharedIneligibleLeavesPickForVanillaAndDrainsCustomWithoutClaiming() {
		PingClickArbitration.Plan plan = PingClickArbitration.plan(true, false);

		assertFalse(plan.consumePick());
		assertTrue(plan.consumeCustom());
		assertFalse(plan.claims(false, true));
	}

	@Test
	void dedicatedBindingConsumesAndClaimsOnlyItsCustomMapping() {
		PingClickArbitration.Plan plan = PingClickArbitration.plan(false, true);

		assertFalse(plan.consumePick());
		assertTrue(plan.consumeCustom());
		assertTrue(plan.claims(false, true));
		assertFalse(plan.claims(false, false));
	}
}
