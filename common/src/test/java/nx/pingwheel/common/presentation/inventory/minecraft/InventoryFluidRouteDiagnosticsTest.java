package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Field;
import java.util.Set;

import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.presentation.PresentationDiagnosticCapture;
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
	@Test void belowMinimumProfileDoesNotConsumeOnceGateAndFirstDeepAttemptStillReports() {
		InventoryFluidRouteDiagnostics.clear(); var server = DoubleChestFixture.allocate(InventoryFluidWorldTest.Server.class);
		try (var capture = new PresentationDiagnosticCapture(Level.INFO)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.NORMAL);
			var route = new IPlatformFluidService.RouteInfo(IPlatformFluidService.TopologyEvidence.PUBLIC_ADDRESS_ONLY, "provider.profile");
			InventoryFluidRouteDiagnostics.used(server, route);
			assertTrue(capture.events().isEmpty());
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			InventoryFluidRouteDiagnostics.used(server, route);
			assertEquals(1, capture.events().size(), "the first DEEP opportunity must still report the deferred route");
			InventoryFluidRouteDiagnostics.used(server, route);
			assertEquals(1, capture.events().size());
		} finally { InventoryFluidRouteDiagnostics.clear(); }
	}

	@Test void backendDisabledAtEligibleProfileStillConsumesOnceGateWithoutLogging() throws ReflectiveOperationException {
		InventoryFluidRouteDiagnostics.clear(); var server = DoubleChestFixture.allocate(InventoryFluidWorldTest.Server.class);
		try (var capture = new PresentationDiagnosticCapture(Level.OFF)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			InventoryFluidRouteDiagnostics.used(server,
				new IPlatformFluidService.RouteInfo(IPlatformFluidService.TopologyEvidence.PUBLIC_ADDRESS_ONLY, "provider.backend"));
			Field classes = InventoryFluidRouteDiagnostics.class.getDeclaredField("classes"); classes.setAccessible(true);
			assertTrue(((Set<?>) classes.get(null)).contains("provider.backend"),
				"an eligible but backend-disabled report preserves the existing once semantics");
			assertTrue(capture.events().isEmpty());
		} finally { InventoryFluidRouteDiagnostics.clear(); }
	}

	@Test void successfulPublicRoutesDeduplicateWithoutEvictionAndExplicitStructuresNeverReport() {
		InventoryFluidRouteDiagnostics.clear(); var server = DoubleChestFixture.allocate(InventoryFluidWorldTest.Server.class);
		try (var capture = new PresentationDiagnosticCapture(Level.INFO)) {
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
