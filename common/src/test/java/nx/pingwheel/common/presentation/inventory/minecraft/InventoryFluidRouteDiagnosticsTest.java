package nx.pingwheel.common.presentation.inventory.minecraft;

import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryFluidRouteDiagnosticsTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void successfulPublicRoutesDeduplicateWithoutEvictionAndExplicitStructuresNeverReport() {
		InventoryFluidRouteDiagnostics.clear(); var server = DoubleChestFixture.allocate(InventoryFluidWorldTest.Server.class);
		try (var capture = new IntegrationDiagnosticCapture(Level.INFO)) {
			InventoryFluidRouteDiagnostics.used(server, new IPlatformFluidService.RouteInfo(IPlatformFluidService.TopologyEvidence.EXPLICIT_STRUCTURE, "create.explicit"));
			assertTrue(capture.events().isEmpty());
			for (int i = 0; i < 300; i++) {
				var route = new IPlatformFluidService.RouteInfo(IPlatformFluidService.TopologyEvidence.PUBLIC_ADDRESS_ONLY, "provider." + i);
				InventoryFluidRouteDiagnostics.used(server, route); InventoryFluidRouteDiagnostics.used(server, route);
			}
			assertEquals(257, capture.events().size());
			for (var event : capture.events()) { assertEquals(Level.INFO, event.getLevel()); String text = event.getMessage().getFormattedMessage();
				assertFalse(text.contains("position=")); assertFalse(text.contains("readOwner=")); assertFalse(text.contains("create.explicit")); }
			InventoryFluidRouteDiagnostics.clear();
			InventoryFluidRouteDiagnostics.used(server, new IPlatformFluidService.RouteInfo(IPlatformFluidService.TopologyEvidence.PUBLIC_ADDRESS_ONLY, "provider.0"));
			assertEquals(258, capture.events().size());
		} finally { InventoryFluidRouteDiagnostics.clear(); }
	}
}
