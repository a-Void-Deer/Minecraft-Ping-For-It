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
	private List<ExternalBlockServerProvider> previousProviders;

	@BeforeEach
	void saveGlobalProviderState() {
		previousProviders = registry.providers();
	}

	@AfterEach
	void restoreGlobalProviderState() {
		registry.clear();
		previousProviders.forEach(registry::register);
	}

	@Test
	void noProviderIsRegisteredOnThisBranch() {
		assertTrue(registry.isEmpty(),
			"1.20.1 ships no external-block provider; the generic protocol stays available");
	}
}
