package nx.pingwheel.common.presentation.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** DEBUG-only expected source-unavailability reports at the consuming Runtime boundaries. */
@NotNullByDefault
class InventoryRuntimeSourceDiagnosticsTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	@Test void debugInputProbeReportsCauseAndContextOncePerConsumerRejectionTransition() {
		var settings = InventorySettings.serverDefaults();
		var calls = new AtomicInteger();
		var failure = new InventorySourceUnavailableException("source denied",
			() -> { calls.incrementAndGet(); return "root=1,2,3 controller=creative-tank shape=tank member=4"; });
		try (var runtime = runtime(value -> { throw failure; }, settings);
			var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			var consumer = runtime.attach(input(), new InventoryRuntime.PreviewSubject(input().readOwner())).orElseThrow();
			assertEquals(Optional.of(false), runtime.probe(consumer));
			assertEquals(1, logs.events().size());
			assertNull(logs.events().getFirst().getThrown(), "only the bounded report reaches Log4j");
			String message = logs.messages();
			assertTrue(message.contains("site=inventory/runtime/input-probe"), message);
			assertTrue(message.contains("handling=source-unavailable"), message);
			assertTrue(message.contains("operation=runtime/input-probe"), message);
			assertTrue(message.contains("stage=input-valid"), message);
			assertTrue(message.contains("face=NORTH"), message);
			assertTrue(message.contains("context=root=1,2,3 controller=creative-tank shape=tank member=4"), message);
			assertEquals(1, calls.get());
			assertEquals(Optional.of(false), runtime.probeRecovery(consumer));
			assertEquals(1, logs.events().size(), "the consumer owns its repeated recovery rejection");
			assertEquals(1, calls.get(), "a suppressed report must not evaluate the lazy context");
		}
	}

	@Test void itemAbsenceCannotSuppressTheFollowingFluidFailureOrAnotherTanksFirstFailure() {
		var settings = InventorySettings.serverDefaults();
		try (var runtime = runtime(value -> {
			if (value.resourceKind() == InventoryResourceKind.ITEM) return Optional.empty();
			throw new InventorySourceUnavailableException("Create creative/foreign fluid tank", () -> "ordinary=false");
		}, settings); var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			assertEquals(Optional.of(false), runtime.probe(input()));
			var fluid = new InventorySourceInput(input().target(), input().readOwner(), input().face(), InventoryResourceKind.FLUID);
			assertEquals(Optional.of(false), runtime.probe(fluid));
			var other = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 2, 64, -2, "create:fluid_tank"),
				input().readOwner(), input().face(), InventoryResourceKind.FLUID);
			assertEquals(Optional.of(false), runtime.probe(other));
			assertEquals(3, logs.events().size(), "no shared interval can consume another lane/source's first failure");
			assertTrue(logs.messages().contains("stage=resolve-null"));
			assertEquals(2, logs.events().stream().filter(event -> event.getMessage().getFormattedMessage().contains("context=ordinary=false")).count());
		}
	}

	@Test void normalAndDeepProfilesSkipTheDebugReportAndDoNotEvaluateContext() {
		var settings = InventorySettings.serverDefaults();
		for (var profile : List.of(LoggingProfile.NORMAL, LoggingProfile.DEEP)) {
			var calls = new AtomicInteger();
			var failure = new InventorySourceUnavailableException("source denied",
				() -> { calls.incrementAndGet(); return "context=" + calls.get(); });
			try (var runtime = runtime(value -> { throw failure; }, settings);
				var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
				Diagnostics.setProfile(DiagnosticSide.SERVER, profile);
				assertEquals(Optional.of(false), runtime.probe(input()));
				assertTrue(logs.events().isEmpty(), profile + " must not disclose a DEBUG-only report");
				assertEquals(0, calls.get(), profile + " must not evaluate the lazy context");
			}
		}
	}

	@Test void disabledBackendSkipsFormattingAndContextEvenAtDebug() {
		var settings = InventorySettings.serverDefaults();
		var calls = new AtomicInteger();
		var failure = new InventorySourceUnavailableException("source denied",
			() -> { calls.incrementAndGet(); return "root=disabled"; });
		try (var runtime = runtime(value -> { throw failure; }, settings);
			var logs = new InventoryDiagnosticsTestCapture(Level.OFF)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			assertEquals(Optional.of(false), runtime.probe(input()));
			assertTrue(logs.events().isEmpty());
			assertEquals(0, calls.get());
		}
	}

	@Test void sourceNullAndValidFalseCarryTheirOwnStages() {
		var settings = InventorySettings.serverDefaults();
		try (var runtime = runtime(value -> Optional.empty(), settings);
			var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			assertEquals(Optional.of(false), runtime.probe(input()));
			String message = logs.messages();
			assertTrue(message.contains("stage=resolve-null"), message);
			assertTrue(message.contains("source=unresolved"), message);
		}
		var input = input();
		var source = new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1);
		source.valid = false;
		try (var runtime = runtime(value -> Optional.of(source), settings);
			var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			assertEquals(Optional.of(false), runtime.probe(input));
			String message = logs.messages();
			assertTrue(message.contains("stage=valid-false"), message);
			assertTrue(message.contains("source=" + source.key()), message);
		}
	}

	private static InventoryRuntime runtime(Function<InventorySourceInput, Optional<InventorySourceAccess.Source>> resolver,
		InventorySettings settings) {
		var runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes());
		runtime.advance(0, settings);
		return runtime;
	}

	private static InventorySourceInput input() {
		return new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 64, -2, "minecraft:chest"),
			new UUID(7, 8), BlockFace.NORTH);
	}
}
