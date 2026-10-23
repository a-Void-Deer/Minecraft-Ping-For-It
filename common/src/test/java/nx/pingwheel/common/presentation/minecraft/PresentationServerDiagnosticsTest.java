package nx.pingwheel.common.presentation.minecraft;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.*;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class PresentationServerDiagnosticsTest {
	private static final String ID = "test:diagnostic", FIELD = "test:value";
	private static final PresentationSection FRESH = new PresentationSection(ID, 1,
		Map.of(FIELD, new PresentationValue.NumberValue(9)), false);
	private static final UUID OWNER = new UUID(0, 1);

	@Test void collectorAndSanitizeFailuresRetainDemandedStaleAndAdvanceCadence() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var policy = PresentationSettings.serverDefaults();
			var collector = new Adapter(); collector.failure = new IllegalStateException("collector failed", new RuntimeException("collector cause"));
			var lease = lease();
			var source = new PresentationServer.Source(ID, 1); source.value = FRESH; lease.sources.put(ID, source);
			var work = new PresentationAdapter.CaptureBudget(20);
			for (long tick = 0; tick < 3; tick++) PresentationServer.captureSource(lease, collector, policy, tick, work,
				Set.of(FIELD), demand -> fail("optional collector only"));
			assertEquals(PresentationServer.Source.Observation.FAILED, source.observation);
			assertEquals(new PresentationSection(ID, 1, FRESH.fields(), true), source.value);
			assertTrue(source.nextSample > 2); assertEquals(3, collector.reads);
			var sanitize = new Adapter(); sanitize.brokenFields = true;
			PresentationServer.captureSource(lease, sanitize, policy, 10, work, Set.of(FIELD), demand -> fail("optional only"));
			assertEquals(new PresentationSection(ID, 1, FRESH.fields(), true), source.value);
			assertEquals(2, capture.events().size());
			assertTrue(capture.messages().stream().anyMatch(line -> line.contains("collector cause")));
			assertTrue(capture.messages().stream().anyMatch(line -> line.contains("sanitize cause")));
			assertTrue(capture.events().stream().allMatch(event -> event.getLevel() == Level.WARN));
		}
	}

	@Test void propertyCaptureFailureRejectsWholeAdmissionAndReportsOriginalCause() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var registry = new PresentationRegistry(); registry.register(new Adapter());
			var intent = PresentationPropertyIntent.observed(PresentationPropertyRef.root(ID, FIELD), new PresentationValue.NumberValue(2));
			for (int attempt = 0; attempt < 3; attempt++) {
				var result = ServerPropertyAdmission.admit(List.of(intent), registry, Map.of(ID, Set.of(FIELD)), 4,
					new ServerPropertyAdmission.Context("minecraft:stone", Set.of()), (adapter, roots, budget) -> {
						throw new IllegalStateException("admission failed", new RuntimeException("admission cause"));
					});
				assertEquals(MarkerRejectReason.INVALID_REQUEST, result.rejection());
				assertTrue(result.sourceSeeds().isEmpty()); assertTrue(result.selections().isEmpty());
			}
			assertEquals(1, capture.events().size()); assertTrue(capture.messages().getFirst().contains("admission cause"));
		}
	}

	@Test void transportFailureRetainsTwoAttemptsRevisionAndEmptyStaleFallback() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var session = new PresentationServer.Session(7, Map.of(ID, 1), Map.of(ID, new Adapter().fields()));
			session.view = 1;
			var attempts = new ArrayList<PresentationS2CPacket>();
			var sent = new LinkedHashMap<String, PresentationSection>();
			PresentationServer.sendProjectedSection(session, new MarkerId(1), new Adapter(), FRESH, sent, packet -> {
				attempts.add(packet);
				if (attempts.size() == 1) throw new IllegalStateException("transport failed", new RuntimeException("transport cause"));
			});
			assertEquals(List.of(1L, 2L), attempts.stream().map(PresentationS2CPacket::revision).toList());
			assertEquals(2, session.revision);
			var empty = new PresentationSection(ID, 1, Map.of(), true);
			assertEquals(empty, sent.get(ID)); assertEquals(empty, decode(attempts.getLast()));
			assertEquals(1, capture.events().size());
			assertTrue(capture.messages().getFirst().contains("stage=transport"));
			assertTrue(capture.messages().getFirst().contains("transport cause"));
		}
	}

	@Test void oversizedSectionFailsBeforeFirstTransportAndFallbackUsesSecondRevision() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var values = new LinkedHashMap<String, PresentationValue>();
			for (int i = 0; i < PresentationLimits.MAX_ENTRIES; i++) values.put("key" + i,
				new PresentationValue.Text("x".repeat(PresentationLimits.MAX_TEXT_BYTES)));
			var oversized = new PresentationSection(ID, 1, Map.of(FIELD, new PresentationValue.RecordValue(values)), false);
			var session = new PresentationServer.Session(7, Map.of(ID, 1), Map.of()); session.view = 1;
			var packets = new ArrayList<PresentationS2CPacket>(); var sent = new LinkedHashMap<String, PresentationSection>();
			PresentationServer.sendProjectedSection(session, new MarkerId(1), new Adapter(), oversized, sent, packets::add);
			assertEquals(1, packets.size()); assertEquals(2, packets.getFirst().revision()); assertEquals(2, session.revision);
			assertEquals(new PresentationSection(ID, 1, Map.of(), true), decode(packets.getFirst()));
			assertTrue(capture.messages().getFirst().contains("stage=section-construction"));
		}
	}

	@Test void failedFallbackStillPropagatesOriginalTransportFailureWithoutThirdAttempt() {
		try (var capture = new PresentationDiagnosticCapture()) {
			var session = new PresentationServer.Session(7, Map.of(ID, 1), Map.of()); session.view = 1;
			var sent = new LinkedHashMap<String, PresentationSection>(); var attempts = new ArrayList<PresentationS2CPacket>();
			var failure = new IllegalStateException("both transport attempts failed");
			assertSame(failure, assertThrows(IllegalStateException.class, () -> PresentationServer.sendProjectedSection(
				session, new MarkerId(1), new Adapter(), FRESH, sent, packet -> { attempts.add(packet); throw failure; })));
			assertEquals(2, attempts.size()); assertEquals(2, session.revision); assertTrue(sent.isEmpty());
			assertEquals(1, capture.events().size());
		}
	}

	private static PresentationSection decode(PresentationS2CPacket packet) {
		var bytes = new FriendlyByteBuf(Unpooled.wrappedBuffer(packet.sectionBytes()));
		try { return PresentationCodec.read(bytes, id -> true); } finally { bytes.release(); }
	}
	private static PresentationServer.Lease lease() {
		var type = TargetTypeCatalog.builtIn().findById("block").orElseThrow();
		return new PresentationServer.Lease(new ServerMarker(new MarkerId(1), OWNER,
			new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:stone"), type, type.defaultPingType(),
			new MarkerAnchor(1, 2, 3), 0, 1000, List.of(OWNER)), "Owner");
	}
	@NotNullByDefault
	private static final class Adapter implements PresentationAdapter {
		private @Nullable RuntimeException failure;
		private boolean brokenFields;
		private int reads;
		@Override public String adapterId() { return ID; }
		@Override public String modId() { return "test"; }
		@Override public int schema() { return 1; }
		@Override public int minUpdateIntervalTicks() { return 5; }
		@Override public @Unmodifiable List<PresentationField> fields() {
			if (brokenFields) throw new IllegalStateException("sanitize failed", new RuntimeException("sanitize cause"));
			return List.of(new PresentationField(FIELD, PresentationField.Kind.NUMBER, true, 0, "Value"));
		}
		@Override public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
			reads++; if (failure != null) throw failure; return FRESH;
		}
	}
}
