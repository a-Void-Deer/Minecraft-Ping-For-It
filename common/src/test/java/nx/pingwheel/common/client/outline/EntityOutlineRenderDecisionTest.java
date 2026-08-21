package nx.pingwheel.common.client.outline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOutlineRenderDecisionTest {

	@Test
	void sourceSuppressionRequiresBothSuccessfulRequestAndClaim() {
		assertFalse(EntityOutlineRenderDecision.shouldShowPingOutline(true, true, true));
		assertTrue(EntityOutlineRenderDecision.shouldShowPingOutline(true, false, true));
		assertTrue(EntityOutlineRenderDecision.shouldShowPingOutline(true, true, false));
	}

	@Test
	void unmarkedEntitiesRemainOutsideThePingRoute() {
		assertFalse(EntityOutlineRenderDecision.shouldShowPingOutline(false, true, true));
		assertFalse(EntityOutlineRenderDecision.shouldShowPingOutline(false, false, false));
	}
}
