package nx.pingwheel.neoforge.integration.create;

import net.minecraft.server.MinecraftServer;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationAdapters;
import nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationAvailability;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual optional reflection entries used by the common consumers. */
@NotNullByDefault
class CreatePresentationFactoryCompatibilityTest {
	@BeforeAll static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test void existingCommonReflectionEntriesRemainSideBoundAndDoNotLoadTheCollector() throws Exception {
		var server = VaultOptionalHarness.allocate(VaultNativeFixture.Server.class);
		try (var mods = new VaultOptionalHarness.Mods("6.0.10")) {
			assertEquals(true, CreatePresentationAvailability.class.getMethod("available").invoke(null));
			@Nullable var client = (PresentationAdapter) CreatePresentationAdapters.class.getMethod("client").invoke(null);
			@Nullable var authoritative = (PresentationAdapter) CreatePresentationAdapters.class.getMethod("server", MinecraftServer.class).invoke(null, server);
			assertNotNull(client); assertNotNull(authoritative); assertEquals(client.fields(), authoritative.fields());
			assertTrue(CreatePresentationAvailability.available(Diagnostics.server()));
			assertTrue(CreatePresentationAvailability.available(Diagnostics.client()));
		}
		try (var mods = new VaultOptionalHarness.Mods(null)) {
			assertEquals(false, CreatePresentationAvailability.class.getMethod("available").invoke(null));
			assertNull(CreatePresentationAdapters.class.getMethod("client").invoke(null));
			assertNull(CreatePresentationAdapters.class.getMethod("server", MinecraftServer.class).invoke(null, server));
		}
	}
}
