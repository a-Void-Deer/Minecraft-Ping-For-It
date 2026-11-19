package nx.pingwheel.common.presentation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.client.PresentationView;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class PresentationDiagnosticsTest {
	@Test void clientAndServerProfilesRemainIndependentInOneJvm() {
		try (var capture = new PresentationDiagnosticCapture()) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
				nx.pingwheel.common.diagnostics.LoggingProfile.NORMAL);
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.CLIENT,
				nx.pingwheel.common.diagnostics.LoggingProfile.DEEP);
			var rules = new com.google.gson.Gson().fromJson("{\"black\":[\"invalid selector\"]}", PresentationSettings.RuleSet.class);
			assertFalse(rules.policy(nx.pingwheel.common.diagnostics.Diagnostics.server()).allows(PresentationBasic.NAME, true));
			assertTrue(capture.events().isEmpty());
			assertFalse(rules.policy(nx.pingwheel.common.diagnostics.Diagnostics.client()).allows(PresentationBasic.NAME, true));
			assertEquals(1, capture.events().size());
			assertTrue(capture.messages().getFirst().contains("side=CLIENT"));
		}
	}

	@Test void distinctMarkerHistoryStopsAdmissionAtTheCommittedBoundAndOnlyRealResetReopensIt() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var store = new PresentationStore();
			store.reset(1);
			// The committed per-connection bound counts live markers plus whole-marker
			// tombstones; fill every remaining slot with the lighter tombstone form.
			long limit = 1 << 20;
			store.initial(1, 0, 0, "block", NAME, BASIC);
			for (long id = 1; id < limit; id++) store.clear(1, id, 1, false);
			assertTrue(capture.events().isEmpty(), "filling the final slot still accepts all history");
			assertTrue(store.isKnown(limit - 1), "the last admissible distinct marker is retained");
			var profile = nx.pingwheel.common.diagnostics.Diagnostics.profile(nx.pingwheel.common.diagnostics.DiagnosticSide.CLIENT);
			try {
				// NORMAL still receives this selected warning. Its state is gameplay history,
				// so generation changes may never rearm acceptance or emission.
				nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.CLIENT,
					nx.pingwheel.common.diagnostics.LoggingProfile.NORMAL);
				store.initial(1, 0, limit, "block", NAME, BASIC);
				store.generation(1, 1);
				store.initial(1, 1, limit + 1, "block", NAME, BASIC);
				assertFalse(store.isKnown(limit), "the next distinct marker is refused at the bound");
				assertFalse(store.isKnown(limit + 1), "a generation cannot free marker history");
				assertEquals(BASIC, store.sections(0).get(PresentationBasic.ID).section(),
					"an existing marker stays usable after history exhaustion");
				store.initial(1, 1, 1, "block", NAME, BASIC);
				assertTrue(store.isKnown(1), "a retained tombstone survives exhaustion");
				assertTrue(store.sections(1).isEmpty(), "a retained tombstone still rejects a late initial");
				assertEquals(1, capture.events().size(), "exhaustion warns once until a real reset");
				assertEquals(Level.WARN, capture.events().getFirst().getLevel());
				assertNull(capture.events().getFirst().getThrown());
				assertFalse(capture.messages().getFirst().contains("Exception"));
				assertFalse(capture.messages().getFirst().contains("epoch="));
			} finally { nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.CLIENT, profile); }
			store.evict(0);
			store.initial(1, 1, limit + 2, "block", NAME, BASIC);
			assertFalse(store.isKnown(limit + 2), "eviction cannot restore admission");
			store.reset(2);
			store.initial(2, 0, limit + 2, "block", NAME, BASIC);
			assertEquals(BASIC, store.sections(limit + 2).get(PresentationBasic.ID).section(),
				"a true new session reopens admission");
			for (long id = 0; id < limit - 1; id++) store.clear(2, id, 1, false);
			store.clear(2, limit + 3, 1, false);
			assertFalse(store.isKnown(limit + 3), "history exhaustion fails closed again after a reset");
			assertEquals(2, capture.events().size(), "a true reset rearms the warning gate");
		}
	}
	private static final PresentationSection BASIC = new PresentationSection(PresentationBasic.ID, 1,
		Map.of(PresentationBasic.NAME, new PresentationValue.Text("name")), false);
	private static final PresentationPropertyRef NAME = PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME);

	@Test void sectionHistoryGateStaysDefensiveAndDoesNotWarnOrDisableExistingSections() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var store = new PresentationStore(); store.reset(1); store.initial(1, 0, 1, "block", NAME, BASIC);
			for (int id = 0; id < 127; id++) assertTrue(store.clearSection(1, 0, 1, "test:adapter" + id, 1));
			assertFalse(store.clearSection(1, 0, 1, "test:overflow", 1));
			assertTrue(store.replace(1, 0, 1, 2, BASIC));
			assertTrue(capture.events().isEmpty());
		}
	}

	@Test void throwingPermissionProviderStillDeniesAndWarnsWithOriginalCause() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var failure = new IllegalStateException("permission failed", new RuntimeException("permission root cause"));
			PresentationAuthorization.setProvider((recipient, field, actual, required) -> { throw failure; });
			try {
				var field = PresentationBasic.fields().getFirst();
				for (int attempt = 0; attempt < 5; attempt++) assertFalse(PresentationAuthorization.fieldAllowed(
					PresentationSettings.serverDefaults(), new UUID(0, attempt), 4, field, "block"));
				assertEquals(1, capture.events().size());
				assertEquals(Level.WARN, capture.events().getFirst().getLevel());
				assertTrue(capture.messages().getFirst().contains("permission root cause"));
			} finally { PresentationAuthorization.setProvider(null); }
		}
	}

	@Test void invalidCompiledPolicyStillDeniesAndReportsItsOriginalFailure() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var settings = new com.google.gson.Gson().fromJson(
				"{\"targetTypes\":{\"block\":{\"black\":[\"invalid selector\"]}}}", PresentationSettings.class);
			for (int attempt = 0; attempt < 5; attempt++) assertFalse(PresentationAuthorization.fieldAllowed(
				settings, new UUID(0, 1), 4, PresentationBasic.fields().getFirst(), "block"));
			assertEquals(1, capture.events().size());
			assertTrue(capture.messages().getFirst().contains("presentation/policy-compile"));
			assertTrue(capture.messages().getFirst().contains("IllegalArgumentException"));
		}
	}

	@Test void oversizedPreencodingStillReturnsEmptyStaleAndWarns() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var values = new LinkedHashMap<String, PresentationValue>();
			for (int id = 0; id < PresentationLimits.MAX_ENTRIES; id++) values.put("key" + id,
				new PresentationValue.Text("x".repeat(PresentationLimits.MAX_TEXT_BYTES)));
			var oversized = new PresentationSection(PresentationBasic.ID, 1,
				Map.of(PresentationBasic.BLOCK_STATE, new PresentationValue.RecordValue(values)), false);
			for (int attempt = 0; attempt < 5; attempt++) assertEquals(new PresentationSection(PresentationBasic.ID, 1, Map.of(), true), PresentationCodec.bounded(oversized));
			assertSame(BASIC, PresentationCodec.bounded(BASIC));
			assertEquals(1, capture.events().size());
			assertTrue(capture.messages().getFirst().contains("presentation/preencode"));
		}
	}

	@Test void normalOversizedPreencodingKeepsSafeFallbackWithoutRawFields() {
		try (var capture = new PresentationDiagnosticCapture()) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(
				nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
				nx.pingwheel.common.diagnostics.LoggingProfile.NORMAL);
			var values = new LinkedHashMap<String, PresentationValue>();
			for (int id = 0; id < PresentationLimits.MAX_ENTRIES; id++) values.put("secret" + id,
				new PresentationValue.Text("x".repeat(PresentationLimits.MAX_TEXT_BYTES)));
			var oversized = new PresentationSection(PresentationBasic.ID, 1,
				Map.of(PresentationBasic.BLOCK_STATE, new PresentationValue.RecordValue(values)), false);
			assertEquals(new PresentationSection(PresentationBasic.ID, 1, Map.of(), true), PresentationCodec.bounded(oversized));
			assertEquals(1, capture.events().size());
			assertEquals(Level.WARN, capture.events().getFirst().getLevel());
			String report = capture.messages().getFirst();
			assertTrue(report.contains("site=presentation/preencode"));
			assertTrue(report.contains("handling=empty-stale-fallback"));
			assertFalse(report.contains("fields="));
			assertFalse(report.contains("secret"));
		}
	}

	@Test void normalHudProviderFailureKeepsEmptyLabelsWithoutRawCause() {
		try (var capture = new PresentationDiagnosticCapture()) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(
				nx.pingwheel.common.diagnostics.DiagnosticSide.CLIENT,
				nx.pingwheel.common.diagnostics.LoggingProfile.NORMAL);
			var client = new ClientPresentation(packet -> fail("HUD must not send"));
			client.registerProvider("throwing", view -> { throw new IllegalStateException("HUD failed", new RuntimeException("HUD cause")); });
			assertTrue(client.selectProvider("throwing"));
			assertEquals(List.of(), client.labels(PresentationView.empty()));
			assertEquals(1, capture.events().size());
			String report = capture.messages().getFirst();
			assertTrue(report.contains("site=presentation/hud-provider"));
			assertTrue(report.contains("handling=empty-labels"));
			assertFalse(report.contains("HUD cause"));
			assertFalse(report.contains("provider="));
		}
	}

	@Test void throwingHudProviderKeepsAllEmptyFallbackWithBoundedWarning() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var client = new ClientPresentation(packet -> fail("HUD must not send"));
			client.registerProvider("throwing", view -> { throw new IllegalStateException("HUD failed", new RuntimeException("HUD cause")); });
			assertTrue(client.selectProvider("throwing"));
			for (int attempt = 0; attempt < 5; attempt++) assertEquals(List.of(), client.labels(PresentationView.empty()));
			assertEquals(1, capture.events().size());
			assertTrue(capture.messages().getFirst().contains("HUD cause"));
		}
	}
}
