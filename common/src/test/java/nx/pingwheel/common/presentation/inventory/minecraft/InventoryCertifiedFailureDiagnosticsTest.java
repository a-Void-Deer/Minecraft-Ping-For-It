package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.inventory.InventoryDiagnosticsTestCapture;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real certified native port through runtime retirement, without relaxed authority or grants. */
@NotNullByDefault
class InventoryCertifiedFailureDiagnosticsTest {
	@BeforeAll static void bootstrap() { InventoryCertifiedReadTest.bootstrap(); }
	private static InventoryRuntime runtime(InventoryCertifiedReadTest.World world) {
		var settings = InventoryPooledBackendTest.settings(false, false);
		return new InventoryRuntime(new InventoryRuntime.SourceResolver() {
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) {
				return InventoryMinecraftSources.resolutionValidationCost(input);
			}
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted"); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
				allowance.debit(resolutionValidationCost(input)); return Optional.of(world.source(input));
			}
		}, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true);
	}
	private static InventoryCertifiedReadTest.World world() {
		var world = new InventoryCertifiedReadTest.World(); world.put(BlockPos.ZERO, Blocks.CHEST.defaultBlockState()); return world;
	}
	private static InventoryRuntime.Consumer attach(InventoryRuntime runtime, InventoryCertifiedReadTest.World world) {
		var input = world.input(BlockPos.ZERO, BlockFace.NORTH, 1);
		var consumer = runtime.attach(input, new InventoryRuntime.TrackingSubject(TargetKey.from(input.target()))).orElseThrow();
		assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
		assertNotNull(InventoryPooledBackendTest.state(consumer, "scanningAssociation")); return consumer;
	}
	@Test void actualCodecFailureRetainsOriginalGraphOnceAndChargesUncommittedPageAtEveryProfile() throws ReflectiveOperationException {
		for (var profile : LoggingProfile.values()) {
			var world = world(); var settings = InventoryPooledBackendTest.settings(false, false);
			var callbacks = new AtomicInteger();
			var causeReads = new AtomicInteger(); var messageReads = new AtomicInteger(); var stringReads = new AtomicInteger();
			try (var registration = new InventoryCertifiedReadTest.CallbackComponent(); var logs = new InventoryDiagnosticsTestCapture(); var runtime = runtime(world)) {
				Diagnostics.setProfile(DiagnosticSide.SERVER, profile);
				var stack = new ItemStack(Items.STONE, 37); stack.set(registration.type, 1);
				((ChestBlockEntity) java.util.Objects.requireNonNull(world.entities.get(BlockPos.ZERO))).setItem(1, stack);
				runtime.advance(0, settings); var consumer = attach(runtime, world);
				var round = InventoryPooledBackendTest.state(consumer, "round"); var journal = InventoryPooledBackendTest.state(round, "journal");
				var failure = new IllegalStateException("private-codec-failure", new IllegalArgumentException("private-root-cause")) {
					@Override public synchronized @Nullable Throwable getCause() { causeReads.incrementAndGet(); return super.getCause(); }
					@Override public @Nullable String getMessage() { messageReads.incrementAndGet(); return super.getMessage(); }
					@Override public String toString() { stringReads.incrementAndGet(); return "private-throwable-string"; }
				};
				registration.callback = () -> { callbacks.incrementAndGet(); throw failure; };
				var observation = runtime.step(consumer, 3).orElseThrow();
				assertEquals(CaptureResult.Availability.UNAVAILABLE, observation.result().availability()); assertTrue(observation.slots().isEmpty());
				assertEquals(1, callbacks.get()); assertEquals(0, InventoryPooledBackendTest.state(journal, "length"));
				var physical = (CostLedger) InventoryPooledBackendTest.state(runtime, "physical");
				assertEquals(2, physical.used(InventorySourceAccess.PHYSICAL));
				assertTrue(physical.used(new CostLedger.Counter("inventory/item_work", CostLedger.Unit.WORK)) > 0);
				assertEquals(1, logs.events().size()); assertEquals(Level.WARN, logs.events().getFirst().getLevel()); assertNull(logs.events().getFirst().getThrown());
				assertTrue(logs.messages().contains("site=inventory/runtime/pooled-read")); assertTrue(logs.messages().contains("handling=invalidate-observation"));
				if (profile == LoggingProfile.NORMAL) { assertFalse(logs.messages().contains("private-")); assertEquals(0, causeReads.get()); assertEquals(0, messageReads.get()); }
				else { assertEquals(1, causeReads.get()); assertTrue(logs.messages().contains("private-root-cause")); assertTrue(logs.messages().contains("stage=pooled-read")); assertTrue(logs.messages().contains("round=")); }
				assertEquals(0, stringReads.get());
			}
		}
	}
	@Test void metadataExceptionReachesFinalConsumerButTypedUnavailableRemainsQuiet() {
		for (boolean unexpected : java.util.List.of(false, true)) {
			var world = world(); var settings = InventoryPooledBackendTest.settings(false, false);
			try (var logs = new InventoryDiagnosticsTestCapture(); var runtime = runtime(world)) {
				Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
				runtime.advance(0, settings); var consumer = attach(runtime, world); int audits = world.audits;
				if (unexpected) world.afterCheck = () -> { throw new IllegalStateException("private-metadata-cause"); };
				else world.loaded = false;
				assertFalse(runtime.probe(consumer).orElseThrow()); assertEquals(audits, world.audits);
				assertEquals(unexpected ? 1 : 0, logs.events().size());
				if (unexpected) { assertTrue(logs.messages().contains("private-metadata-cause")); assertTrue(logs.messages().contains("stage=association-validate")); }
			}
		}
	}
	@Test void finalPublicationMetadataFailureReportsOnlyOnceWithoutContentReads() {
		var world = world(); var settings = InventoryPooledBackendTest.settings(false, false);
		try (var logs = new InventoryDiagnosticsTestCapture(); var runtime = runtime(world)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP); runtime.advance(0, settings); var consumer = attach(runtime, world);
			var evidence = runtime.publicationEvidence(consumer, InventoryRuntime.EvidenceScope.SCANNING).orElseThrow(); int audits = world.audits;
			try (evidence) {
				var grant = runtime.reservePublication(evidence).orElseThrow();
				world.afterCheck = () -> { throw new IllegalStateException("private-publication-cause"); };
				try (grant) { assertEquals(nx.pingwheel.common.presentation.inventory.InventorySharedRead.Check.UNAVAILABLE, grant.validate()); }
				assertEquals(audits, world.audits); assertEquals(1, logs.events().size());
				assertTrue(logs.messages().contains("handling=deny-publication")); assertTrue(logs.messages().contains("private-publication-cause"));
			}
		}
	}
	@Test void certificationConstructionReportsOnlyAtItsExactFallbackBoundary() {
		for (var profile : LoggingProfile.values()) {
			var world = world(); var input = world.input(BlockPos.ZERO, BlockFace.NORTH, 1);
			var view = InventoryMinecraftSources.view(world, input, (position, face) -> Optional.empty());
			world.afterCheck = () -> { throw new IllegalArgumentException("private-certificate-cause"); };
			try (var logs = new InventoryDiagnosticsTestCapture()) {
				Diagnostics.setProfile(DiagnosticSide.SERVER, profile);
				assertNull(InventoryCertifiedRead.create(input, view, world, () -> false)); assertEquals(0, world.audits);
				assertEquals(1, logs.events().size()); assertTrue(logs.messages().contains("handling=exact-read-fallback"));
				if (profile == LoggingProfile.NORMAL) assertFalse(logs.messages().contains("private-"));
				else assertTrue(logs.messages().contains("private-certificate-cause"));
			}
		}
	}
	@Test void nativeMetadataExceptionsCarryTheirCauseWithoutEagerOrDuplicateProviderReports() {
		var world = world();
		try (var source = world.source(world.input(BlockPos.ZERO, BlockFace.NORTH, 1)); var logs = new InventoryDiagnosticsTestCapture()) {
			var port = java.util.Objects.requireNonNull(source.sharedRead()); var failure = new IllegalStateException("private-native-metadata");
			world.afterCheck = () -> { throw failure; };
			var allowance = new InventoryValidationBudget.Allowance(port.catalogCreationCost());
			var typed = assertThrows(nx.pingwheel.common.presentation.inventory.InventorySharedRead.ReadFailure.class,
				() -> port.createCatalog(4096, allowance));
			assertSame(failure, typed.failure()); assertEquals(port.catalogCreationCost().workUnits(), allowance.attemptedWorkUnits());
			assertEquals(0, world.audits); assertTrue(logs.events().isEmpty(), "a forwarding port must leave reporting to its consumer");
		}
	}
	@Test void normalOwnerDenialKeepsAuthorizedPeersPaidPrefixWithoutAnyWarning() {
		var resolver = new InventoryPooledBackendTest.Resolver(Blocks.CHEST, false); var settings = InventoryPooledBackendTest.settings(false, false);
		try (var logs = new InventoryDiagnosticsTestCapture(); var runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.NORMAL); runtime.advance(0, settings);
			var first = InventoryPooledRuntimeTest.attach(runtime, resolver.world.input(BlockPos.ZERO, BlockFace.NORTH, 1));
			var peer = InventoryPooledRuntimeTest.attach(runtime, resolver.world.input(BlockPos.ZERO, BlockFace.SOUTH, 2));
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(first)); assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(peer));
			assertEquals(3, runtime.step(first, 3).orElseThrow().slots().size()); int audits = resolver.world.audits;
			resolver.owners.put(first.input().readOwner(), false); assertFalse(runtime.probe(first).orElseThrow());
			assertEquals(3, runtime.step(peer, 3).orElseThrow().slots().size()); assertEquals(audits, resolver.world.audits);
			assertFalse(peer.invalidated()); assertTrue(logs.events().isEmpty()); assertEquals(0, resolver.deniedReads);
		}
	}
	@Test void actualReadFailureCoupledWithOwnerRevocationRetainsCauseAndOnlyDetachesThatOwner() throws ReflectiveOperationException {
		var resolver = new InventoryPooledBackendTest.Resolver(Blocks.CHEST, false); var settings = InventoryPooledBackendTest.settings(false, false);
		try (var registration = new InventoryCertifiedReadTest.CallbackComponent(); var logs = new InventoryDiagnosticsTestCapture();
			var runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP); runtime.advance(0, settings);
			var first = InventoryPooledRuntimeTest.attach(runtime, resolver.world.input(BlockPos.ZERO, BlockFace.NORTH, 1));
			var peer = InventoryPooledRuntimeTest.attach(runtime, resolver.world.input(BlockPos.ZERO, BlockFace.SOUTH, 2));
			var chest = (ChestBlockEntity) java.util.Objects.requireNonNull(resolver.world.entities.get(BlockPos.ZERO));
			var stack = new ItemStack(Items.STONE, 17); stack.set(registration.type, 1); chest.setItem(1, stack);
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(first)); assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(peer));
			var round = InventoryPooledBackendTest.state(first, "round"); var journal = InventoryPooledBackendTest.state(round, "journal");
			registration.callback = () -> { resolver.owners.put(first.input().readOwner(), false); throw new IllegalStateException("private-revoked-codec-cause"); };
			assertEquals(CaptureResult.Availability.UNAVAILABLE, runtime.step(first, 3).orElseThrow().result().availability());
			assertEquals(0, InventoryPooledBackendTest.state(journal, "length")); assertTrue(first.invalidated()); assertFalse(peer.invalidated());
			assertEquals(1, logs.events().size()); assertTrue(logs.messages().contains("private-revoked-codec-cause"));
			assertTrue(logs.messages().contains("check=AUTHORITY_DENIED"));
			registration.callback = () -> {};
			assertEquals(3, runtime.step(peer, 3).orElseThrow().slots().size()); assertSame(round, InventoryPooledBackendTest.state(peer, "round"));
			assertEquals(5, ((CostLedger) InventoryPooledBackendTest.state(runtime, "physical")).used(InventorySourceAccess.PHYSICAL));
			assertEquals(0, resolver.deniedReads); assertEquals(1, logs.events().size());
		}
	}
}
