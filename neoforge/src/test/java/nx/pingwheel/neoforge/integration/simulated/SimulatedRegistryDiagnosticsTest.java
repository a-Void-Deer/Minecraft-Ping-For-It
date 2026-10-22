package nx.pingwheel.neoforge.integration.simulated;

import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.neoforge.integration.create.CreateDiagnosticCapture;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class SimulatedRegistryDiagnosticsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test
	void anAbsentOptionalEntryIsSilentWhileAThrowingLookupReportsItsCauseAndStillRetries() {
		ResourceLocation id = ResourceLocation.parse("simulated:diagnostic_test_entry");
		try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture()) {
			assertNull(SimulatedDockingConnectorPresentationResolver.lookupEntry("simulated-test-lookup", id, Optional::empty));
			assertTrue(capture.events.isEmpty());
			AtomicInteger attempts = new AtomicInteger();
			for (int attempt = 0; attempt < 2; attempt++) {
				assertNull(SimulatedDockingConnectorPresentationResolver.lookupEntry("simulated-test-lookup", id, () -> {
					attempts.incrementAndGet();
					throw new IllegalStateException("registry original", new IllegalArgumentException("registry cause"));
				}));
			}
			assertEquals(2, attempts.get());
			assertEquals(1, capture.events.size());
			String message = capture.events.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("registry original"));
			assertTrue(message.contains("registry cause"));
			assertTrue(message.contains(id.toString()));
			assertNull(capture.events.getFirst().getThrown());
		}
	}
}
