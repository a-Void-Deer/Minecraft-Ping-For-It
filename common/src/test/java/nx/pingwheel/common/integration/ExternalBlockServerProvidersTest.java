package nx.pingwheel.common.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProviderRegistry;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalBlockServerProvidersTest {

	private final ExternalBlockServerProviderRegistry registry = ExternalBlockServerProviders.registry();
	private boolean previousHasSable;
	private List<ExternalBlockServerProvider> previousProviders;

	@BeforeEach
	void saveGlobalProviderState() {
		previousHasSable = ModContext.HasSable;
		previousProviders = registry.providers();
	}

	@AfterEach
	void restoreGlobalProviderState() {
		registry.clear();
		previousProviders.forEach(registry::register);
		ModContext.HasSable = previousHasSable;
	}

	@Test
	void disabledOptionalIntegrationDoesNotRegisterOrLoadAProvider() {
		ModContext.HasSable = false;
		ExternalBlockServerProviders.configure(false);

		assertTrue(registry.isEmpty());
	}
}
