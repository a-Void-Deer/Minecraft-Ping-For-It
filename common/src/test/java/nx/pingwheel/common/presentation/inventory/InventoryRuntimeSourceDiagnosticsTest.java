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
	private static RuntimeException providerLimit(boolean wrapped) {
		var failure = nx.pingwheel.common.presentation.inventory.minecraft.InventoryProviderLimits.exceeded(
			nx.pingwheel.common.presentation.inventory.minecraft.ProviderLimitSite.CONTAINER_VISIBLE_SLOTS, 16385, 16384);
		return wrapped ? new InventorySharedRead.ReadFailure(InventorySharedRead.Check.UNAVAILABLE,
			new java.lang.reflect.InvocationTargetException(failure)) : failure;
	}
	private static RuntimeException bindingProviderLimit(boolean wrapped) {
		var failure = nx.pingwheel.common.presentation.inventory.minecraft.InventoryProviderLimits.exceeded(
			nx.pingwheel.common.presentation.inventory.minecraft.ProviderLimitSite.CONTAINER_LOCAL_SLOTS, 65, 64);
		return wrapped ? new InventorySharedRead.ReadFailure(InventorySharedRead.Check.UNAVAILABLE,
			new java.lang.reflect.InvocationTargetException(failure)) : failure;
	}
	@Test void typedProviderLimitsKeepProbeReasonsWithoutGenericExceptionReports() {
		for (boolean wrapped : new boolean[] {false, true}) for (String stage : List.of("resolve", "valid")) {
			var settings = InventorySettings.serverDefaults(); var input = input();
			var source = new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1) {
				@Override public boolean valid() { if (stage.equals("valid")) throw providerLimit(wrapped); return true; }
			};
			try (var runtime = runtime(value -> { if (stage.equals("resolve")) throw providerLimit(wrapped); return Optional.of(source); }, settings);
				var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
				Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
				var decision = runtime.probeDecision(input);
				assertTrue(decision.rejected()); assertEquals(InventoryFailureReason.PHYSICAL_SLOT_LIMIT, decision.reason());
				var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
				var attempt = runtime.beginProbe(consumer);
				try (attempt) { assertEquals(InventoryFailureReason.PHYSICAL_SLOT_LIMIT, attempt.decision().reason()); }
				assertEquals(InventoryFailureReason.PHYSICAL_SLOT_LIMIT, consumer.currentReason());
				assertEquals(2, logs.events().size(), "each native guard reports once; consuming it adds no generic report");
				assertTrue(logs.events().stream().allMatch(event -> event.getLevel() == Level.DEBUG));
				assertTrue(logs.events().stream().allMatch(event -> event.getMessage().getFormattedMessage().contains("site=inventory-provider/container-visible-slots")));
				assertFalse(logs.messages().contains("exception: ")); assertFalse(logs.messages().contains("reason=provider-failure"));
			}
		}
	}
	@Test void publicationBindingValidationRetainsTypedProviderLimitWithoutReprobing() {
		for (boolean wrapped : new boolean[] {false, true}) {
			var settings = InventorySettings.serverDefaults(); var input = input();
			var validations = new AtomicInteger(); var reads = new AtomicInteger(); var resolutions = new AtomicInteger(); var failBinding = new boolean[] {false};
			try (var runtime = runtime(value -> {
				resolutions.incrementAndGet();
				return Optional.of(new InventoryRuntimeTest.Source(value, reads, 1) {
					@Override public boolean valid() { validations.incrementAndGet(); if (failBinding[0]) throw bindingProviderLimit(wrapped); return true; }
				});
			}, settings); var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
				Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
				var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
				assertTrue(runtime.probeDecision(input).accepted());
				int resolvedBeforeBinding = resolutions.get(); int validatedBeforeBinding = validations.get();
				failBinding[0] = true;
				var result = runtime.acquirePublicationBinding(consumer);
				assertEquals(InventorySharedRead.Check.UNAVAILABLE, result.check());
				assertEquals(InventoryFailureReason.PHYSICAL_SLOT_LIMIT, result.reason());
				assertNull(result.evidence()); assertNull(consumer.currentReason());
				assertEquals(resolvedBeforeBinding + 1, resolutions.get());
				assertEquals(validatedBeforeBinding + 1, validations.get());
				assertEquals(0, reads.get()); assertEquals(0, runtime.memory().reserved());
				assertEquals(1, logs.events().size(), "provider guard reports once without a generic publication failure");
				assertTrue(logs.messages().contains("inventory-provider/container-local-slots"));
				assertFalse(logs.messages().contains("exception:"));
			}
		}
	}
	@Test void publicationBindingResolutionRetainsDirectAndKnownWrappedProviderLimits() {
		for (boolean wrapped : new boolean[] {false, true}) {
			var settings = InventorySettings.serverDefaults(); var input = input(); var resolves = new AtomicInteger();
			try (var runtime = runtime(value -> { resolves.incrementAndGet(); throw providerLimit(wrapped); }, settings);
				var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
				Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
				var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
				var result = runtime.acquirePublicationBinding(consumer);
				assertEquals(InventorySharedRead.Check.UNAVAILABLE, result.check());
				assertEquals(InventoryFailureReason.PHYSICAL_SLOT_LIMIT, result.reason()); assertNull(result.evidence());
				assertNull(consumer.currentReason()); assertEquals(1, resolves.get()); assertEquals(0, runtime.memory().reserved());
				assertEquals(1, logs.events().size()); assertEquals(Level.DEBUG, logs.events().getFirst().getLevel());
			}
		}
	}
	@Test void typedProviderLimitDuringPlanPreparationKeepsReasonOnRepeatedDecision() {
		var settings = InventorySettings.serverDefaults(); var input = input();
		try (var runtime = runtime(value -> Optional.of(new InventoryRuntimeTest.Source(value, new AtomicInteger(), 1) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() { throw providerLimit(true); }
		}), settings); var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
			for (int repeat = 0; repeat < 2; repeat++) {
				var decision = runtime.prepareDecision(consumer);
				assertTrue(decision.rejected());
				assertEquals(InventoryFailureReason.PHYSICAL_SLOT_LIMIT, decision.reason());
			}
			assertEquals(1, logs.events().size(), "cached terminal preparation keeps its reason without repeating the guard");
			assertEquals(Level.DEBUG, logs.events().getFirst().getLevel());
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void sourceResolutionDistinguishesTypedProviderLimitFromLegalAbsence() {
		var input = input();
		var block = input.ordinaryTarget().orElseThrow();
		var target = new nx.pingwheel.common.presentation.PresentationAdapter.DetachedTarget(
			block.dimensionId(), "block", block.blockRegistryId(), block.x(), block.y(), block.z(), "");
		var scope = new nx.pingwheel.common.presentation.source.SourceAccess.ReadScope(input.viewKey(), java.util.Set.of("pingforit:inventory.items"));
		for (boolean absent : new boolean[] {false, true}) {
			var access = new InventorySourceAccess(input, value -> { if (absent) return Optional.empty(); throw providerLimit(true); });
			var ledger = new nx.pingwheel.common.presentation.source.CostLedger(java.util.Map.of(
				InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			try (var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
				Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
				var decision = access.resolveDecision(target, scope, ledger, null);
				assertTrue(decision.rejected());
				assertEquals(absent ? InventoryFailureReason.SOURCE_UNAVAILABLE : InventoryFailureReason.PHYSICAL_SLOT_LIMIT, decision.reason());
				assertEquals(absent ? 0 : 1, logs.events().size());
				assertTrue(logs.events().stream().allMatch(event -> event.getLevel() == Level.DEBUG));
				assertEquals(1L, ledger.used(InventorySourceAccess.PROBES), "each attempted resolution remains charged");
			}
		}
	}
	@Test void sourceResolveOpenAndNativeReadKeepAlreadyReportedProviderCause() {
		var settings = InventorySettings.serverDefaults(); var input = input();
		try (var runtime = runtime(value -> Optional.of(new InventoryRuntimeTest.Source(value, new AtomicInteger(), 1) {
			@Override public InventorySourceAccess.ReadMode readMode() { return InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL; }
			@Override public InventoryDomainCodec.Item read(int slot) { throw providerLimit(true); }
		}), settings); var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			var result = runtime.step(consumer, 1).orElseThrow();
			assertEquals(nx.pingwheel.common.presentation.source.CaptureResult.Availability.UNAVAILABLE, result.result().availability());
			assertEquals(InventoryFailureReason.PHYSICAL_SLOT_LIMIT, consumer.currentReason());
			assertEquals(1, logs.events().size()); assertEquals(Level.DEBUG, logs.events().getFirst().getLevel());
			assertFalse(logs.messages().contains("reason=provider-failure")); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void providerReportedOptionalSnapshotFallbackRemainsLiveWithoutGenericWarning() {
		var settings = InventorySettings.serverDefaults(); var reads = new AtomicInteger(); var input = input();
		try (var runtime = runtime(value -> Optional.of(new InventoryRuntimeTest.Source(value, reads, 1) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				try { throw providerLimit(false); }
				catch (nx.pingwheel.common.presentation.inventory.minecraft.InventoryProviderLimitException expected) { return Optional.empty(); }
			}
		}), settings); var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
			var result = runtime.step(consumer, 1).orElseThrow();
			assertEquals(nx.pingwheel.common.presentation.source.CaptureResult.Completeness.COMPLETE, result.result().completeness());
			assertEquals(1, reads.get()); assertNull(consumer.currentReason());
			assertEquals(1, logs.events().size()); assertEquals(Level.DEBUG, logs.events().getFirst().getLevel());
		}
		try (var runtime = runtime(value -> Optional.empty(), settings); var logs = new InventoryDiagnosticsTestCapture(Level.ALL)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			assertEquals(InventoryFailureReason.SOURCE_UNAVAILABLE, runtime.probeDecision(input).reason());
			assertFalse(logs.messages().contains("inventory-provider/")); assertFalse(logs.messages().contains("limitHit=true"));
		}
	}

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
