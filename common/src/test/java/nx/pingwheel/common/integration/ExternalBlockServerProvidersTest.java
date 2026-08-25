package nx.pingwheel.common.integration;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProviderRegistry;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalBlockServerProvidersTest {

	@Test
	void disabledOptionalIntegrationDoesNotRegisterOrLoadAProvider() {
		boolean previous = ModContext.HasSable;

		try {
			ModContext.HasSable = false;
			ExternalBlockServerProviders.configure(false);

			ExternalBlockServerProviderRegistry registry = ExternalBlockServerProviders.registry();
			assertTrue(registry.isEmpty());
		} finally {
			ModContext.HasSable = previous;
		}
	}
}
