package nx.pingwheel.common.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProviderRegistry;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalBlockServerProvidersTest {

	@Test
	void disabledOptionalIntegrationDoesNotRegisterOrLoadAProvider() {
		boolean previous = ModContext.HasSable;
		ExternalBlockServerProviderRegistry registry = ExternalBlockServerProviders.registry();
		List<nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider> previousProviders =
			registry.providers();

		try {
			ModContext.HasSable = false;
			ExternalBlockServerProviders.configure(false);

			assertTrue(registry.isEmpty());
		} finally {
			registry.clear();
			previousProviders.forEach(registry::register);
			ModContext.HasSable = previous;
		}
	}
}
