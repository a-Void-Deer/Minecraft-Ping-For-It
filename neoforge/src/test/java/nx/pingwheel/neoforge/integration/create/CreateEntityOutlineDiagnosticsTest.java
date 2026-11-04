package nx.pingwheel.neoforge.integration.create;

import nx.pingwheel.common.client.outline.EntityBlockGeometryOutcome;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class CreateEntityOutlineDiagnosticsTest {
	@BeforeAll static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test void normalAndSuppressedEntityReportsNeverCollectUuidProbeOrOriginalFailure() {
		var entity = VaultOptionalHarness.allocate(CountingEntity.class);
		var failure = new CountingFailure();
		var probe = new AtomicInteger();
		long[] now = {0};
		var diagnostics = new CreateEntityOutlineDiagnostics(() -> { probe.incrementAndGet(); return "probe private"; }, () -> now[0]);
		try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture(Level.ALL, LoggingProfile.NORMAL)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			diagnostics.report(entity, "renderer-failed-before-vertices", "on", true, 0, failure, EntityBlockGeometryOutcome.FAILED);
			assertEquals(0, entity.uuidReads); assertEquals(0, probe.get()); assertEquals(0, failure.reads);
			assertEquals(Level.ERROR, capture.events.getFirst().getLevel());
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			diagnostics.report(entity, "renderer-failed-before-vertices", "on", true, 0, failure, EntityBlockGeometryOutcome.FAILED);
			assertEquals(0, entity.uuidReads); assertEquals(0, probe.get()); assertEquals(0, failure.reads);
			now[0] = CreateEntityOutlineDiagnosticGate.HEARTBEAT_NANOS;
			diagnostics.report(entity, "renderer-failed-before-vertices", "on", true, 0, failure, EntityBlockGeometryOutcome.FAILED);
			assertEquals(1, entity.uuidReads); assertEquals(1, probe.get()); assertTrue(failure.reads > 0);
			assertEquals(2, capture.events.size());
			assertTrue(capture.events.getLast().getMessage().getFormattedMessage().contains("original private"));
			assertNull(capture.events.getLast().getThrown());
		}
	}

	@Test void belowDebugRecoveryIsObservedWithoutDetailOrResettingTheDomainGate() {
		long[] now = {0};
		var probe = new AtomicInteger();
		var diagnostics = new CreateEntityOutlineDiagnostics(() -> { probe.incrementAndGet(); return "probe"; }, () -> now[0]);
		try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture(Level.ALL, LoggingProfile.NORMAL)) {
			diagnostics.report(null, "renderer-failed-before-vertices", "on", true, 0, new IllegalStateException("original"), EntityBlockGeometryOutcome.FAILED);
			now[0] = 2_000_000_000L;
			diagnostics.report(null, "rendered", "on", true, 24, null, EntityBlockGeometryOutcome.RENDERED);
			diagnostics.report(null, "renderer-failed-before-vertices", "on", true, 0, new IllegalStateException("original"), EntityBlockGeometryOutcome.FAILED);
			assertEquals(1, capture.events.size()); assertEquals(0, probe.get());
			now[0] += CreateEntityOutlineDiagnosticGate.MIN_INTERVAL_NANOS;
			diagnostics.report(null, "renderer-failed-before-vertices", "on", true, 0, new IllegalStateException("original"), EntityBlockGeometryOutcome.FAILED);
			assertEquals(2, capture.events.size()); assertEquals(0, probe.get());
		}
	}

	@NotNullByDefault
	private static final class CountingEntity extends net.minecraft.world.entity.decoration.ArmorStand {
		private int uuidReads;
		private CountingEntity() { super(net.minecraft.world.entity.EntityType.ARMOR_STAND, null); }
		@Override public net.minecraft.world.entity.EntityType<?> getType() { return net.minecraft.world.entity.EntityType.ARMOR_STAND; }
		@Override public java.util.UUID getUUID() { uuidReads++; return new java.util.UUID(0, 1); }
	}

	@NotNullByDefault
	private static final class CountingFailure extends RuntimeException {
		private int reads;
		@Override public @Nullable String getMessage() { reads++; return "original private"; }
		@Override public synchronized @Nullable Throwable getCause() { reads++; return null; }
	}
}
