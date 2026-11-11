package nx.pingwheel.common.diagnostics;

import nx.pingwheel.common.presentation.inventory.InventoryDiagnosticDetail;
import nx.pingwheel.common.presentation.inventory.InventoryDiagnosticSite;
import nx.pingwheel.common.presentation.inventory.InventoryDiagnostics;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import nx.pingwheel.common.presentation.inventory.InventoryImpact;
import nx.pingwheel.common.presentation.inventory.InventoryOperation;
import nx.pingwheel.common.presentation.inventory.InventoryOutcome;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class InventoryDiagnosticsTest {

	@Test
	void normalReportUsesFixedSummaryWithoutReadingDetail() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.NORMAL);
			AtomicInteger reads = new AtomicInteger();
			InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.REQUEST, InventoryOperation.OPEN,
				InventoryFailureReason.REQUEST_CAPACITY, InventoryImpact.PING_UNAVAILABLE, InventoryOutcome.REJECTED, true,
				() -> {
					reads.incrementAndGet();
					return new InventoryDiagnosticDetail("admission", 7, 8, "item", 9, 10, 11);
				}, new RuntimeException("normal-secret"));

			assertEquals(0, reads.get());
			assertEquals(1, fixture.sink.events.size());
			assertEquals(DiagnosticSide.SERVER, fixture.sink.events.getFirst().side());
			assertEquals(Level.WARN, fixture.sink.events.getFirst().level(),
				"A NORMAL whole-ping failure must not be hidden at the backend DEBUG level");
			String text = fixture.sink.events.getFirst().text();
			assertTrue(text.contains("site=request handling=rejected"));
			assertTrue(text.contains("operation=open"));
			assertTrue(text.contains("reason=request-capacity"));
			assertTrue(text.contains("impact=ping-unavailable"));
			assertTrue(text.contains("limitHit=true"));
			assertTrue(text.contains("failure=runtime-failure"));
			assertFalse(text.contains("normal-secret"));
			assertFalse(text.contains("request=7"));
			assertFalse(text.contains("stage="));
		}
	}

	@Test
	void deepReportKeepsSummaryAndAddsShortStageAndLaneWithoutNumericAssociations() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.CAPTURE, InventoryOperation.SNAPSHOT,
				InventoryFailureReason.SNAPSHOT_LIMIT, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, true,
				() -> new InventoryDiagnosticDetail("snapshot", 101, 202, "item", 50, 25, 25), null);

			assertEquals(1, fixture.sink.events.size());
			assertEquals(Level.DEBUG, fixture.sink.events.getFirst().level());
			String text = fixture.sink.events.getFirst().text();
			assertTrue(text.contains("operation=snapshot"));
			assertTrue(text.contains("reason=snapshot-limit"));
			assertTrue(text.contains("impact=degraded"));
			assertTrue(text.contains("limitHit=true"));
			assertTrue(text.contains("stage=snapshot"));
			assertTrue(text.contains("lane=item"));
			assertFalse(text.contains("request=101"));
			assertFalse(text.contains("commit=202"));
			assertFalse(text.contains("requested=50"));
		}
	}

	@Test
	void debugLimitReportIsEveryOccurrenceAndAddsNumericAssociations() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			AtomicInteger reads = new AtomicInteger();
			for (int index = 0; index < 4; index++) {
				int value = index;
				InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.PROVIDER, InventoryOperation.READ,
					InventoryFailureReason.PROVIDER_WORK_LIMIT, InventoryImpact.DETAIL_ONLY, InventoryOutcome.DEFERRED, true,
					() -> {
						reads.incrementAndGet();
						return new InventoryDiagnosticDetail("work", 300 + value, 400 + value, "item", 5, 4, 3);
					}, null);
			}

			assertEquals(4, reads.get());
			assertEquals(4, fixture.sink.events.size());
			assertEquals(Level.DEBUG, fixture.sink.events.getFirst().level());
			for (Event event : fixture.sink.events) {
				assertTrue(event.text().contains("bypass=debug-every-occurrence"));
				assertFalse(event.text().contains("suppressed="),
					"A bypassed interval channel must not claim an admitted suppression count");
			}
			String text = fixture.sink.events.getFirst().text();
			assertTrue(text.contains("operation=read"));
			assertTrue(text.contains("reason=provider-work-limit"));
			assertTrue(text.contains("impact=detail-only"));
			assertTrue(text.contains("limitHit=true"));
			assertTrue(text.contains("stage=work"));
			assertTrue(text.contains("request=300"));
			assertTrue(text.contains("commit=400"));
			assertTrue(text.contains("requested=5"));
			assertTrue(text.contains("used=4"));
			assertTrue(text.contains("limit=3"));
		}
	}

	@Test
	void debugNonLimitReportsStillUseTheIntervalChannel() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			AtomicInteger reads = new AtomicInteger();
			for (int index = 0; index < 2; index++) {
				InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.PUBLICATION,
					InventoryOperation.PUBLISH, InventoryFailureReason.ENTRY_LIMIT, InventoryImpact.DETAIL_ONLY,
					InventoryOutcome.DEFERRED, true, () -> {
						reads.incrementAndGet();
						return new InventoryDiagnosticDetail("entry", 1, 2, "item", 3, 4, 5);
					}, null);
			}
			assertEquals(2, fixture.sink.events.size());
			assertTrue(fixture.sink.events.stream().allMatch(event ->
				event.text().contains("bypass=debug-every-occurrence") && !event.text().contains("suppressed=")));

			InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.PUBLICATION,
				InventoryOperation.PUBLISH, InventoryFailureReason.ENTRY_LIMIT, InventoryImpact.DETAIL_ONLY,
				InventoryOutcome.DEFERRED, false, () -> {
					reads.incrementAndGet();
					return new InventoryDiagnosticDetail("entry", 1, 2, "item", 3, 4, 5);
				}, null);
			InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.PUBLICATION,
				InventoryOperation.PUBLISH, InventoryFailureReason.ENTRY_LIMIT, InventoryImpact.DETAIL_ONLY,
				InventoryOutcome.DEFERRED, false, () -> {
					reads.incrementAndGet();
					return new InventoryDiagnosticDetail("entry", 1, 2, "item", 3, 4, 5);
				}, null);

			assertEquals(3, fixture.sink.events.size(), "The first non-limit event admits and the second is suppressed");
			assertEquals(3, reads.get());
			assertTrue(fixture.sink.events.getLast().text().contains("suppressed=0"));
		}
	}

	@Test
	void independentReasonsDoNotSuppressEachOtherAtNormal() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.NORMAL);
			reportNative(InventoryFailureReason.PHYSICAL_SLOT_LIMIT);
			reportNative(InventoryFailureReason.LOGICAL_SLOT_LIMIT);
			reportNative(InventoryFailureReason.PHYSICAL_SLOT_LIMIT);

			assertEquals(2, fixture.sink.events.size());
			String text = fixture.sink.events.getFirst().text() + "\n" + fixture.sink.events.getLast().text();
			assertTrue(text.contains("reason=physical-slot-limit"));
			assertTrue(text.contains("reason=logical-slot-limit"));
		}
	}

	@Test
	void detailFailureKeepsTheSummaryAndIsContained() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			assertDoesNotThrow(() -> InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.TRANSPORT,
				InventoryOperation.ROUTE, InventoryFailureReason.TRANSPORT_FAILURE, InventoryImpact.DEGRADED,
				InventoryOutcome.FAILED, false, () -> {
					throw new IllegalStateException("detail-secret");
				}, new RuntimeException("business-original")));

			assertEquals(1, fixture.sink.events.size());
			String text = fixture.sink.events.getFirst().text();
			assertTrue(text.contains("operation=route"));
			assertTrue(text.contains("reason=transport-failure"));
			assertTrue(text.contains("impact=degraded"));
			assertTrue(text.contains("limitHit=false"));
			assertTrue(text.contains("context unavailable"));
			assertTrue(text.contains("business-original"));
			assertFalse(text.contains("detail-secret"));
			assertEquals(1, fixture.faults.observations().size());
			assertEquals(DiagnosticFaultReporter.Stage.CONTEXT, fixture.faults.observations().getFirst().key().stage());
		}
	}

	@Test
	void nullDetailKeepsTheSummaryAtDeep() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.CLIENT, InventoryOperation.SELECT,
				InventoryFailureReason.INVALID_SELECTION, InventoryImpact.DEGRADED, InventoryOutcome.REJECTED, false,
				() -> null, null);

			assertEquals(1, fixture.sink.events.size());
			String text = fixture.sink.events.getFirst().text();
			assertTrue(text.contains("operation=select"));
			assertTrue(text.contains("reason=invalid-selection"));
			assertTrue(text.contains("impact=degraded"));
			assertTrue(text.contains("limitHit=false"));
			assertFalse(text.contains("stage="));
		}
	}

	@Test
	void impactSelectsTheMinimumProfile() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.NORMAL);
			InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.CLIENT, InventoryOperation.UPDATE,
				InventoryFailureReason.QUEUE_LIMIT, InventoryImpact.DEGRADED, InventoryOutcome.DEFERRED, true,
				() -> new InventoryDiagnosticDetail("queue", 1, 2, "item", 3, 4, 5), null);
			InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.SESSION, InventoryOperation.CLOSE,
				InventoryFailureReason.SESSION_TIMEOUT, InventoryImpact.DETAIL_ONLY, InventoryOutcome.FAILED, false,
				() -> new InventoryDiagnosticDetail("timeout", 1, 2, "item", 3, 4, 5), null);
			InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.DISPLAY, InventoryOperation.DISPLAY,
				InventoryFailureReason.DISPLAY_LIMIT, InventoryImpact.PING_UNAVAILABLE, InventoryOutcome.TRUNCATED, true,
				() -> new InventoryDiagnosticDetail("display", 1, 2, "item", 3, 4, 5), null);

			assertEquals(1, fixture.sink.events.size());
			assertTrue(fixture.sink.events.getFirst().text().contains("reason=display-limit"));
			assertEquals(Level.WARN, fixture.sink.events.getFirst().level());
		}
	}

	@Test
	void explicitSeverityOverridesTheImpactDefaultWithoutChangingProfileEligibility() {
		try (Fixture fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.NORMAL);
			InventoryDiagnostics.report(DiagnosticSide.SERVER, Level.WARN, InventoryDiagnosticSite.VALIDATION,
				InventoryOperation.VALIDATE, InventoryFailureReason.NOT_FOUND, InventoryImpact.DEGRADED,
				InventoryOutcome.FAILED, false,
				() -> new InventoryDiagnosticDetail("validate", 1, 2, "item", 3, 4, 5), null);
			assertTrue(fixture.sink.events.isEmpty(), "An explicit severity cannot widen the impact minimum profile");

			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			InventoryDiagnostics.report(DiagnosticSide.SERVER, Level.WARN, InventoryDiagnosticSite.VALIDATION,
				InventoryOperation.VALIDATE, InventoryFailureReason.NOT_FOUND, InventoryImpact.DEGRADED,
				InventoryOutcome.FAILED, false,
				() -> new InventoryDiagnosticDetail("validate", 1, 2, "item", 3, 4, 5), null);

			assertEquals(1, fixture.sink.events.size());
			assertEquals(Level.WARN, fixture.sink.events.getFirst().level());
			assertTrue(fixture.sink.events.getFirst().text().contains("reason=not-found"));
		}
	}

	private static void reportNative(InventoryFailureReason reason) {
		InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.NATIVE, InventoryOperation.READ,
			reason, InventoryImpact.PING_UNAVAILABLE, InventoryOutcome.REJECTED, true,
			() -> new InventoryDiagnosticDetail("read", 1, 2, "item", 3, 4, 5), null);
	}

	@NotNullByDefault
	private record Event(DiagnosticSide side, Level level, String text) {}

	@NotNullByDefault
	private static final class Sink implements DiagnosticBackend {
		private final List<Event> events = new ArrayList<>();

		@Override public void prepare(DiagnosticSide side) {}

		@Override public boolean isEnabled(DiagnosticSide side, Level severity) {
			return true;
		}

		@Override public void log(DiagnosticSide side, Level severity, String text) {
			events.add(new Event(side, severity, text));
		}
	}

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		private final Sink sink = new Sink();
		private final DiagnosticBackend previous = Diagnostics.swapBackendForTesting(sink);
		private final DiagnosticFaultReporter faults =
			new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(ByteArrayOutputStream::new));
		private final DiagnosticFaultReporter previousFaults = Diagnostics.swapFaultsForTesting(faults);
		private final EnumMap<DiagnosticSide, LoggingProfile> profiles = new EnumMap<>(DiagnosticSide.class);

		private Fixture() {
			for (DiagnosticSide side : DiagnosticSide.values()) {
				profiles.put(side, Diagnostics.profile(side));
				Diagnostics.setProfile(side, LoggingProfile.NORMAL);
			}
		}

		@Override public void close() {
			Diagnostics.swapBackendForTesting(previous);
			Diagnostics.swapFaultsForTesting(previousFaults);
			profiles.forEach(Diagnostics::setProfile);
		}
	}
}
