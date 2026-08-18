package nx.pingwheel.common.client.rate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientRateLimitPolicyTest {

	@Test
	void defaultPolicyHasTheConfirmedValues() {
		assertEquals(5, ClientRateLimitPolicy.DEFAULT.rateLimit());
		assertEquals(1000, ClientRateLimitPolicy.DEFAULT.msToRegenerate());
		assertTrue(ClientRateLimitPolicy.DEFAULT.enabled());
	}

	@Test
	void negativeValuesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new ClientRateLimitPolicy(-1, 1000));
		assertThrows(IllegalArgumentException.class, () -> new ClientRateLimitPolicy(5, -1));
	}

	@Test
	void eitherZeroDisablesThePolicy() {
		assertFalse(new ClientRateLimitPolicy(0, 1000).enabled());
		assertFalse(new ClientRateLimitPolicy(5, 0).enabled());
	}
}
