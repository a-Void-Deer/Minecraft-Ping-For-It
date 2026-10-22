package nx.pingwheel.neoforge.integration.create.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.presentation.PresentationRegistry;

class CreatePresentationRegistrationTest {
	@Test
	void registersCompatibleManifestOncePerRegistry() {
		PresentationRegistry client = new PresentationRegistry();
		CreatePresentationAdapter manifest = new CreatePresentationAdapter();
		assertTrue(CreatePresentationRegistration.register(client, manifest));
		assertSame(manifest, client.get(CreatePresentationAdapter.ADAPTER_ID));
		assertFalse(CreatePresentationRegistration.register(client, new CreatePresentationAdapter()));
		assertEquals(1, client.all().size());

		PresentationRegistry server = new PresentationRegistry();
		CreatePresentationAdapter source = new CreatePresentationAdapter((target, demand, budget) -> null);
		assertTrue(CreatePresentationRegistration.register(server, source));
		assertSame(source, server.get(CreatePresentationAdapter.ADAPTER_ID));
	}

	@Test
	void absentOptionalAdapterDoesNotRegister() {
		PresentationRegistry registry = new PresentationRegistry();
		assertFalse(CreatePresentationRegistration.register(registry, null));
		assertTrue(registry.all().isEmpty());
	}
}
