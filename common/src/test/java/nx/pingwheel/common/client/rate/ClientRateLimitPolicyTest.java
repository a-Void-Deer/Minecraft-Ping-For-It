package nx.pingwheel.common.client.rate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import nx.pingwheel.common.config.ServerConfig;

class ClientRateLimitPolicyTest {

	@Test
	void initialCourtesyPolicyFollowsFreshServerPolicyWithoutSharingLaterEdits() {
		ServerConfig server = new ServerConfig();
		server.validate();
		ClientRateLimitPolicy fallback = ClientRateLimitPolicy.DEFAULT;
		assertEquals(server.getRateLimit(), fallback.rateLimit());
		assertEquals(server.getMsToRegenerate(), fallback.msToRegenerate());
		ClientCreateRateLimiter limiter = new ClientCreateRateLimiter(() -> 0);
		assertEquals(fallback, limiter.policy());
		server.setRateLimit(17);
		server.setMsToRegenerate(250);
		limiter.applyPolicy(new ClientRateLimitPolicy(server.getRateLimit(), server.getMsToRegenerate()));
		assertEquals(new ClientRateLimitPolicy(17, 250), limiter.policy());
		assertEquals(fallback, new ClientCreateRateLimiter(() -> 0).policy());
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
