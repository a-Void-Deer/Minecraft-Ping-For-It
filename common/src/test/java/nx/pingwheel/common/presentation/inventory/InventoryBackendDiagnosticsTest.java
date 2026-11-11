package nx.pingwheel.common.presentation.inventory;

import java.util.List;

import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.network.InventoryC2SPacket;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** DEBUG-only OPEN/lane/INVALID correlation through the real backend. */
@NotNullByDefault
class InventoryBackendDiagnosticsTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	@Test void acceptedOpenCreatesBothLanesAndInvalidationReportsOncePerLane() {
		try (var fixture = new InventoryBackendTest.Fixture();
			var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			fixture.settings.getPreview().setPeriodTicks(1);
			fixture.host.policies.replaceAll((player, policy) -> new InventoryBackend.Policy(policy.epoch(), policy.view(), policy.types(), policy.types()));
			fixture.open();
			var opened = siteMessages(logs, "inventory/preview/open");
			assertTrue(opened.stream().anyMatch(message -> message.contains("handling=opened")
				&& message.contains("request=1") && message.contains("face=NORTH")), logs.messages());
			var lanes = siteMessages(logs, "inventory/preview/lane");
			assertEquals(2, lanes.size(), "both closed resource lanes are opened");
			assertTrue(lanes.stream().anyMatch(message -> message.contains("kind=ITEM")
				&& message.contains("request=1") && message.contains("face=NORTH")));
			assertTrue(lanes.stream().anyMatch(message -> message.contains("kind=FLUID")
				&& message.contains("request=1") && message.contains("face=NORTH")));

			fixture.valid = false;
			fixture.backend.tick(1, fixture.settings);
			var invalidations = siteMessages(logs, "inventory/preview/invalidate");
			assertEquals(2, invalidations.size());
			assertTrue(invalidations.stream().anyMatch(message -> message.contains("kind=ITEM")
				&& message.contains("request=1") && message.contains("face=NORTH")));
			assertTrue(invalidations.stream().anyMatch(message -> message.contains("kind=FLUID")
				&& message.contains("request=1") && message.contains("face=NORTH")));
			fixture.backend.tick(2, fixture.settings);
			assertEquals(2, siteMessages(logs, "inventory/preview/invalidate").size(),
				"an already-invalid lane must not repeat its transition report");
		}
	}

	@Test void rejectedOpenNamesTheExistingRequestFence() {
		try (var fixture = new InventoryBackendTest.Fixture();
			var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			fixture.open();
			fixture.backend.handle(InventoryBackendTest.A,
				InventoryC2SPacket.open(fixture.host.epoch(InventoryBackendTest.A), 100, 1, 1,
					InventoryRuntimeTest.TARGET, BlockFace.NORTH), 1, fixture.settings);
			var rejected = siteMessages(logs, "inventory/preview/open").stream()
				.filter(message -> message.contains("handling=request")).toList();
			assertEquals(1, rejected.size(), logs.messages());
			assertTrue(rejected.getFirst().contains("stage=request-fence"));
			assertTrue(rejected.getFirst().contains("request=1"));
		}
	}

	@Test void normalAndDeepProfilesEmitNoPreviewDebug() {
		for (var profile : List.of(LoggingProfile.NORMAL, LoggingProfile.DEEP)) {
			try (var fixture = new InventoryBackendTest.Fixture();
				var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
				Diagnostics.setProfile(DiagnosticSide.SERVER, profile);
				fixture.open();
				fixture.valid = false;
				fixture.backend.tick(1, fixture.settings);
				for (LogEvent event : logs.events()) {
					String message = event.getMessage().getFormattedMessage();
					assertFalse(message.contains("site=inventory/preview/open"), profile + ": " + message);
					assertFalse(message.contains("site=inventory/preview/lane"), profile + ": " + message);
					assertFalse(message.contains("site=inventory/preview/invalidate"), profile + ": " + message);
				}
			}
		}
	}

	@Test void nonMemoryPreflightDeferralIsNotMisreportedAsMemory() {
		try (var fixture = new InventoryBackendTest.Fixture();
			var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			fixture.hello(InventoryBackendTest.A);
			while (fixture.backend.runtime().preflight(() -> java.util.Optional.of(true)).isPresent()) {}
			assertTrue(fixture.backend.runtime().memory().remaining() > 4096, "request memory is available");
			fixture.backend.handle(InventoryBackendTest.A,
				InventoryC2SPacket.open(fixture.host.epoch(InventoryBackendTest.A), 100, 1, 1,
					InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, fixture.settings);
			var rejected = logs.events().stream().map(event -> event.getMessage().getFormattedMessage())
				.filter(message -> message.contains("operation=open") && message.contains("handling=rejected")).toList();
			assertEquals(1, rejected.size(), logs.messages());
			assertTrue(rejected.getFirst().contains("stage=admission"), logs.messages());
			assertTrue(rejected.getFirst().contains("limitHit=true"), logs.messages());
			assertFalse(rejected.getFirst().contains("reason=memory-limit"), logs.messages());
			assertEquals(nx.pingwheel.common.network.InventoryS2CPacket.Kind.OPEN_REJECTED, fixture.host.packets.getLast().kind());
		}
	}

	private static List<String> siteMessages(InventoryDiagnosticsTestCapture logs, String site) {
		return logs.events().stream().map(event -> event.getMessage().getFormattedMessage())
			.filter(message -> message.contains("site=" + site)).toList();
	}
}
