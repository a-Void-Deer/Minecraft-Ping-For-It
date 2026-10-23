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
	private static final PresentationSection BASIC = new PresentationSection(PresentationBasic.ID, 1,
		Map.of(PresentationBasic.NAME, new PresentationValue.Text("name")), false);
	private static final PresentationPropertyRef NAME = PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME);

	@Test void historyTransitionWarnsOnceAndOnlyResetRearmsAdmissionAndWarning() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var store = new PresentationStore();
			store.reset(1);
			store.initial(1, 0, 0, "block", NAME, BASIC);
			for (long id = 1; id < 8192; id++) store.clear(1, id, 1, false);
			assertTrue(capture.events().isEmpty(), "filling the final slot still accepts all history");
			store.initial(1, 0, 8192, "block", NAME, BASIC);
			for (long id = 8193; id < 8300; id++) store.initial(1, 0, id, "block", NAME, BASIC);
			assertFalse(store.isKnown(8192));
			assertEquals(BASIC, store.sections(0).get(PresentationBasic.ID).section());
			store.evict(0);
			store.generation(1, 1);
			store.initial(1, 1, 8400, "block", NAME, BASIC);
			assertFalse(store.isKnown(8400), "a generation cannot free marker history");
			assertEquals(1, capture.events().size());
			assertEquals(Level.WARN, capture.events().getFirst().getLevel());
			assertNull(capture.events().getFirst().getThrown());
			assertFalse(capture.messages().getFirst().contains("Exception"));
			store.reset(2);
			store.initial(2, 0, 8400, "block", NAME, BASIC);
			assertEquals(BASIC, store.sections(8400).get(PresentationBasic.ID).section());
			for (long id = 0; id < 8191; id++) store.clear(2, id, 1, false);
			store.clear(2, 9000, 1, false);
			assertEquals(2, capture.events().size(), "real recovery rearms the domain gate within ten seconds");
		}
	}

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
