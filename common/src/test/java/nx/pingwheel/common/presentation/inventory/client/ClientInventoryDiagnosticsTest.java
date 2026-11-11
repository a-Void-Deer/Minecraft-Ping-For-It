package nx.pingwheel.common.presentation.inventory.client;

import java.util.List;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
import nx.pingwheel.common.testing.DiagnosticLogCapture;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class ClientInventoryDiagnosticsTest {
	@Test
	void everyDebugBoundFailureProducesARealReportWithoutChangingStoreAdmission() {
		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEBUG)) {
			var store = new InventoryClientStore(1, 1, 1);
			var first = InventoryClientStore.Channel.tracked(1);
			store.snapshot(first, 1, 1, java.util.Map.of("one", new InventoryClientStore.Value(1, 1, "valid")));
			for (int i = 0; i < 2; i++) assertEquals(InventoryClientStore.Outcome.REJECTED_BOUND,
				store.snapshot(InventoryClientStore.Channel.tracked(2), 1, 1, java.util.Map.of()));
			for (int i = 0; i < 2; i++) assertEquals(InventoryClientStore.Outcome.REJECTED_BOUND,
				store.apply(first, 1, 1, java.util.Map.of("two", new InventoryClientStore.Value(2, 1, "valid"))));
			assertEquals(4, logs.events().size());
			assertTrue(logs.events().stream().allMatch(event -> event.getMessage().getFormattedMessage().contains("limitHit=true")));
			assertEquals(1, store.values(first).get("one").count());
		}
	}
	@Test void actualRetentionLossIsSilentAtNormalAndVisibleAsDegradationAtDeep() {
		for (LoggingProfile profile : java.util.List.of(LoggingProfile.NORMAL, LoggingProfile.DEEP)) {
			try (var logs = new DiagnosticLogCapture(profile)) {
				var store = new InventoryClientStore(1, 1, 1);
				assertEquals(InventoryClientStore.Outcome.REJECTED_BOUND, store.snapshot(InventoryClientStore.Channel.tracked(1),
					1, 1, java.util.Map.of("a", new InventoryClientStore.Value(1, 1, "valid"), "b", new InventoryClientStore.Value(2, 1, "valid"))));
				if (profile == LoggingProfile.NORMAL) assertTrue(logs.events().isEmpty());
				else {
					assertEquals(1, logs.events().size());
					String message = logs.events().getFirst().getMessage().getFormattedMessage();
					assertTrue(message.contains("impact=degraded")); assertTrue(message.contains("handling=truncated"));
					assertTrue(message.contains("reason=entry-limit")); assertTrue(message.contains("limitHit=true"));
				}
			}
		}
	}
	@Test void independentRealCapacityPredicatesWithSameReasonDoNotThrottleEachOtherAtDeep() {
		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEEP)) {
			var store = new InventoryClientStore(1, 1, 1);
			store.snapshot(InventoryClientStore.Channel.tracked(1), 1, 1, java.util.Map.of());
			assertEquals(InventoryClientStore.Outcome.REJECTED_BOUND, store.snapshot(InventoryClientStore.Channel.tracked(2), 1, 1, java.util.Map.of()));
			assertEquals(InventoryClientStore.PartOutcome.REJECTED_BOUND, store.beginSnapshot(InventoryClientStore.Channel.tracked(3), 1, 1, 1, 0));
			assertEquals(InventoryClientStore.Outcome.REJECTED_BOUND, store.snapshot(InventoryClientStore.Channel.tracked(4), 1, 1, java.util.Map.of()));
			assertEquals(2, logs.events().size(), "snapshot and assembly capacity each emit, repeated snapshot capacity is throttled");
			assertEquals(1, logs.operation("site=client-store-snapshot-channels").size());
			assertEquals(1, logs.operation("site=client-store-assembly-channels").size());
		}
	}
	@Test void malformedPartIndexUsesItsOwnNonLimitChannel() {
		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEBUG)) {
			var store = new InventoryClientStore(1, 1, 1); var channel = InventoryClientStore.Channel.tracked(1);
			store.beginSnapshot(channel, 1, 1, 1, 0);
			for (int i = 0; i < 2; i++) assertEquals(InventoryClientStore.PartOutcome.REJECTED_BOUND,
				store.part(channel, 1, 1, -1, java.util.Map.of(), 0));
			assertEquals(1, logs.events().size(), "malformed input does not bypass interval control as a cap hit");
			String message = logs.events().getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("site=client-store-part-index")); assertTrue(message.contains("limitHit=false"));
			assertTrue(message.contains("reason=invalid-request"));
		}
	}
	@Test void wholeLocalOpenCapacityRefusalStillReportsAtNormal() {
		try (var logs = new DiagnosticLogCapture(LoggingProfile.NORMAL)) {
			var client = new ClientInventory(ignored -> {}); ClientInventoryV2Test.ready(client);
			for (int i = 0; i < ClientInventory.MAX_PREVIEW_CHANNELS; i++)
				client.open(ClientInventoryV2Test.TARGET, nx.pingwheel.common.domain.BlockFace.UP, "entity_block");
			assertEquals(ClientInventory.NO_REQUEST, client.open(ClientInventoryV2Test.TARGET, nx.pingwheel.common.domain.BlockFace.UP, "entity_block"));
			assertEquals(1, logs.events().size());
			String message = logs.events().getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("impact=ping-unavailable")); assertTrue(message.contains("handling=rejected"));
		}
	}
	@Test void actualPreviewRetentionUsesDegradationMinimumAndReportsEveryDebugOccurrence() {
		for (LoggingProfile profile : java.util.List.of(LoggingProfile.NORMAL, LoggingProfile.DEEP, LoggingProfile.DEBUG)) {
			try (var logs = new DiagnosticLogCapture(profile)) {
				var client = new ClientInventory(ignored -> {}); ClientInventoryV2Test.ready(client);
				long wire = client.open(ClientInventoryV2Test.TARGET, nx.pingwheel.common.domain.BlockFace.UP, "entity_block");
				ClientInventoryV2Test.opened(client, wire, 1);
				for (int first = 0; first < ClientInventory.MAX_PREVIEW_ENTRIES; first += 64) {
					var entries = new java.util.ArrayList<nx.pingwheel.common.network.InventoryS2CPacket.Entry>();
					for (int index = first; index < first + 64; index++) entries.add(new nx.pingwheel.common.network.InventoryS2CPacket.Entry(
						"row-" + index, "minecraft:stone", "Stone", null, index + 1L, 1, false, null));
					client.accept(nx.pingwheel.common.network.InventoryS2CPacket.data(nx.pingwheel.common.network.InventoryS2CPacket.Kind.PREVIEW,
						7, wire, null, 1, 1, first + 1L, 0, 1, false, nx.pingwheel.common.network.InventoryS2CPacket.Status.UPDATING, 0, entries).stamp(100, 1));
				}
				for (int index = 0; index < 2; index++) client.accept(nx.pingwheel.common.network.InventoryS2CPacket.data(
					nx.pingwheel.common.network.InventoryS2CPacket.Kind.PREVIEW, 7, wire, null, 1, 1, 2_000 + index, 0, 1, false,
					nx.pingwheel.common.network.InventoryS2CPacket.Status.UPDATING, 0, java.util.List.of(new nx.pingwheel.common.network.InventoryS2CPacket.Entry(
					"overflow-" + index, "minecraft:stone", "Stone", null, 99, 1, false, null))).stamp(100, 1));
				assertEquals(1024, client.preview(wire).entries().size());
				@Unmodifiable List<LogEvent> reports = logs.operation("site=client-preview-retention-entries");
				assertEquals(profile == LoggingProfile.NORMAL ? 0 : profile == LoggingProfile.DEEP ? 1 : 2, reports.size());
				assertTrue(reports.stream().allMatch(event -> {
					String message = event.getMessage().getFormattedMessage();
					return message.contains("impact=degraded") && message.contains("limitHit=true") && message.contains("reason=entry-limit");
				}));
			}
		}
	}
	@Test void independentActualTrackingConflictsHaveSeparateNonLimitChannels() {
		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEEP)) {
			var client = new ClientInventory(ignored -> {}); ClientInventoryV2Test.ready(client);
			var marker = new nx.pingwheel.common.domain.MarkerId(3);
			client.markerCreated(new nx.pingwheel.common.marker.MarkerSnapshot(marker, new java.util.UUID(1, 1), ClientInventoryV2Test.TARGET,
				"entity_block", "attention", new nx.pingwheel.common.marker.MarkerAnchor(1, 2, 3), 0, 30),
				ClientInventoryV2Test.grant(InventoryResourceKind.ITEM, InventoryResourceKind.ITEM));
			var original = new InventoryS2CPacket.Entry("selected", "minecraft:stone", "Stone", null, 1, 1, false, null);
			client.accept(ClientInventoryV2Test.data(InventoryS2CPacket.Kind.SNAPSHOT, marker, 1, original));
			var update = new InventoryS2CPacket.Entry("selected", "minecraft:stone", "Stone", null, 2, 2, false, null);
			client.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 7, 0, marker, 4, 1, 2, 0, 2, true,
				InventoryS2CPacket.Status.UNCERTAIN, 0, java.util.List.of(update)).stamp(100, 1));
			var conflicting = new InventoryS2CPacket.Entry("selected", "minecraft:stone", "Stone", null, 99, 2, false, null);
			for (int i = 0; i < 2; i++) client.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 7, 0, marker, 4, 1, 2, 0, 2, true,
				InventoryS2CPacket.Status.UNCERTAIN, 0, java.util.List.of(conflicting)).stamp(100, 1));
			client.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 7, 0, marker, 4, 1, 2, 1, 3, true,
				InventoryS2CPacket.Status.UNCERTAIN, 0, java.util.List.of(update)).stamp(100, 1));
			assertEquals(1, logs.operation("site=client-tracking-replay-conflict").size());
			assertEquals(1, logs.operation("site=client-tracking-batch-conflict").size());
			assertTrue(logs.events().stream().allMatch(event -> event.getMessage().getFormattedMessage().contains("limitHit=false")));
			assertEquals(2, client.tracking(marker).entries().getFirst().count(), "conflicts preserve the accepted absolute value");
		}
	}

	@Test
	void belowMinimumProfilesDoNotEvaluateDetail() {
		throwingDetailIgnored(() -> new DiagnosticLogCapture(LoggingProfile.NORMAL));
		throwingDetailIgnored(() -> new DiagnosticLogCapture(LoggingProfile.DEEP));
	}

	@Test
	void disabledBackendDoesNotEvaluateDetail() {
		try (var logs = new IntegrationDiagnosticCapture(Level.OFF, LoggingProfile.DEBUG)) {
			ClientInventoryDiagnostics.debug("inventory/test", "lazy", () -> {
				throw new AssertionError("disabled backend evaluated detail");
			});
			assertTrue(logs.events().isEmpty());
		}
	}

	@Test
	void enabledDebugKeepsTheFixedSiteAndReason() {
		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEBUG)) {
			ClientInventoryDiagnostics.debug("inventory/test", "lazy", () -> "value=1");
			assertEquals(1, logs.events().size());
			String message = logs.events().getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("site=inventory/test"));
			assertTrue(message.contains("handling=lazy"));
			assertTrue(message.contains("value=1"));
		}
	}

	private static void throwingDetailIgnored(java.util.function.Supplier<DiagnosticLogCapture> capture) {
		try (var logs = capture.get()) {
			ClientInventoryDiagnostics.debug("inventory/test", "lazy", () -> {
				throw new AssertionError("below-minimum profile evaluated detail");
			});
			assertTrue(logs.events().isEmpty());
		}
	}
}
