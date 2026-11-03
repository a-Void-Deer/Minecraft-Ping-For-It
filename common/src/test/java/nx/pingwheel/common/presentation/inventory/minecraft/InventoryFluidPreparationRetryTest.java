package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real common fluid resolution/preparation under the Runtime's shared, public budget operations. */
@NotNullByDefault
class InventoryFluidPreparationRetryTest {

	private static final CostLedger.Counter WORK = new CostLedger.Counter("inventory/item_work", CostLedger.Unit.WORK);

	@BeforeAll static void bootstrap() { InventoryFluidWorldTest.bootstrap(); }

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable, InventoryFluidSources.World {
		final InventoryFluidWorldTest.Fixture storage;
		final InventoryRuntime runtime;
		final List<InventoryValidationBudget.Allowance> allowances = new ArrayList<>();
		int resolutions;
		long siblingWork;
		Fixture(boolean external) {
			storage = new InventoryFluidWorldTest.Fixture(2, 1, external);
			runtime = new InventoryRuntime(new InventoryRuntime.SourceResolver() {
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) {
					throw new AssertionError("unadmitted resolution");
				}
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
					resolutions++;
					var source = InventoryFluidSources.resolve(Fixture.this, storage, input, cap, allowance);
					storage.lastSource = source.orElse(null);
					return source;
				}
			}, storage.settings.pendingMemoryBytes());
			advance(0);
		}
		void advance(long tick) {
			allowances.clear(); siblingWork = 0;
			runtime.advance(tick, storage.settings);
		}
		InventoryRuntime.Consumer attach(boolean tracking) {
			return runtime.attach(storage.input, tracking ? new InventoryRuntime.TrackingSubject(TargetKey.from(storage.target))
				: new InventoryRuntime.PreviewSubject(storage.input.readOwner())).orElseThrow();
		}
		void leaveOneProviderCall() {
			long available = ledger(runtime).remaining(InventorySourceAccess.PROVIDER_WORK) / InventorySourceAccess.PROVIDER_CALL_WORK;
			for (long attempt = 1; attempt < available; attempt++)
				assertEquals(Optional.of(true), runtime.preflight(() -> Optional.of(true)));
			assertEquals(InventorySourceAccess.PROVIDER_CALL_WORK, ledger(runtime).remaining(InventorySourceAccess.PROVIDER_WORK));
		}
		void exhaustItemWork() {
			long remaining = ledger(runtime).remaining(WORK);
			var cost = new InventoryValidationBudget.Cost(0, 0, remaining - 128);
			try (var admission = runtime.fluidAdmission(InventoryValidationBudget.NONE, InventoryValidationBudget.NONE);
				var operation = admission.enter(new IPlatformFluidService.OperationProfile(cost, 0, 0, 0))) {
				operation.allowance().debit(cost);
			}
			siblingWork += remaining;
			assertEquals(0, ledger(runtime).remaining(WORK));
		}
		void assertMetadataPaidOnce() {
			assertEquals(siblingWork + allowances.stream().mapToLong(InventoryValidationBudget.Allowance::attemptedWorkUnits).sum(),
				ledger(runtime).used(WORK), "each caller/fluid prefix is charged once to the same Runtime ledger");
			assertSettled(runtime);
		}
		@Override public boolean onServerThread() { return storage.onServerThread(); }
		@Override public InventoryFluidSources.@Nullable Binding bind(InventorySourceInput input, InventoryValidationBudget.Allowance allowance) {
			if (allowances.stream().noneMatch(previous -> previous == allowance)) allowances.add(allowance);
			return storage.bind(input, allowance);
		}
		@Override public @Nullable BlockEntity existingEntity(ServerLevel level, BlockPos pos) { return storage.existingEntity(level, pos); }
		@Override public BlockState blockState(ServerLevel level, BlockPos pos) { return storage.blockState(level, pos); }
		@Override public boolean loaded(ServerLevel level, BlockPos pos) { return storage.loaded(level, pos); }
		@Override public @Nullable ItemStack ownerKey(InventorySourceInput input) { return storage.ownerKey(input); }
		@Override public HolderLookup.Provider registries(ServerLevel level) { return storage.registries(level); }
		@Override public void used(IPlatformFluidService.RouteInfo route) { storage.used(route); }
		@Override public void close() {
			runtime.close(); storage.close();
			assertEquals(storage.probes, storage.closes, "every acquired native Access closes exactly once");
			assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
		}
	}

	@Test void previewRetainsAnUnpreparedRoundAndCompletesNextTickWithoutResolvingOrReadingTwice() {
		for (boolean external : new boolean[] {false, true}) try (var f = new Fixture(external)) {
			var preview = f.attach(false); f.leaveOneProviderCall();
			assertTrue(f.runtime.step(preview).isEmpty());
			assertEquals(1, f.resolutions); assertEquals(1, f.storage.witnessCaptures); assertEquals(0, f.storage.reads);
			assertEquals(0, ledger(f.runtime).used(InventorySourceAccess.PHYSICAL));
			var original = Objects.requireNonNull(f.storage.lastSource); long retained = f.runtime.memory().retained();
			assertTrue(f.runtime.step(preview).isEmpty(), "same-tick ingress cannot refund sibling contention");
			assertEquals(retained, f.runtime.memory().retained()); assertFalse(preview.invalidated()); f.assertMetadataPaidOnce();
			f.advance(1);
			assertComplete(f.runtime.step(preview).orElseThrow(), 162);
			assertSame(original, f.storage.lastSource); assertEquals(1, f.resolutions); assertEquals(1, f.storage.witnessCaptures);
			assertEquals(2, f.storage.reads); assertFalse(preview.invalidated()); assertSettled(f.runtime);
			assertEquals(2, ledger(f.runtime).used(InventorySourceAccess.PHYSICAL), "only the two actual getters spend physical addresses");
			preview.retainCompleted(); preview.close();
			f.advance(2); var fresh = f.attach(false);
			assertComplete(f.runtime.step(fresh).orElseThrow(), 162);
			assertEquals(2, f.resolutions); assertEquals(4, f.storage.reads, "fresh request observes the same storage independently");
		}
	}

	@Test void trackingReplacementKeepsItsAcceptedEvidenceWhilePreparationWaitsAndDoesNotEnterRecovery() {
		for (boolean external : new boolean[] {false, true}) try (var f = new Fixture(external)) {
			var tracking = f.attach(true); var accepted = f.runtime.step(tracking).orElseThrow(); assertComplete(accepted, 162);
			tracking.retainCompleted();
			try (var previous = f.runtime.publicationEvidence(tracking, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow()) {
				tracking.restart(); f.storage.amount = 243; f.advance(1); f.leaveOneProviderCall();
				assertTrue(f.runtime.step(tracking).isEmpty()); assertEquals(2, f.resolutions); assertEquals(2, f.storage.reads);
				assertEquals(0, ledger(f.runtime).used(InventorySourceAccess.PHYSICAL));
				var pendingSource = Objects.requireNonNull(f.storage.lastSource);
				try (var stillAccepted = f.runtime.publicationEvidence(tracking, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow()) {
					assertTrue(previous.sameProof(stillAccepted), "a preparation defer cannot discard the last accepted total's evidence");
				}
				assertComplete(accepted, 162); assertFalse(tracking.invalidated()); assertFalse(tracking.scopeTransition()); f.assertMetadataPaidOnce();
				f.advance(2);
				assertComplete(f.runtime.step(tracking).orElseThrow(), 486);
				assertSame(pendingSource, f.storage.lastSource); assertEquals(2, f.resolutions); assertEquals(2, f.storage.witnessCaptures);
				assertEquals(4, f.storage.reads); assertFalse(tracking.invalidated()); assertFalse(tracking.scopeTransition());
				assertEquals(2, ledger(f.runtime).used(InventorySourceAccess.PHYSICAL));
				try (var stillAccepted = f.runtime.publicationEvidence(tracking, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow()) {
					assertTrue(previous.sameProof(stillAccepted), "replacement completion is not acceptance until retainCompleted");
				}
				tracking.retainCompleted();
				try (var replacement = f.runtime.publicationEvidence(tracking, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow()) {
					assertFalse(previous.sameProof(replacement));
					try (var grant = f.runtime.reservePublication(replacement).orElseThrow()) { assertEquals(InventorySharedRead.Check.VALID, grant.validate()); }
				}
				assertSettled(f.runtime);
			}
		}
	}

	@Test void publicPreparationRetryStillDefersOnTheSharedWorkLedgerAndNeedsNoFreeGrantToResume() {
		try (var f = new Fixture(true)) {
			var consumer = f.attach(false); f.leaveOneProviderCall();
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, f.runtime.prepare(consumer)); f.assertMetadataPaidOnce();
			var original = Objects.requireNonNull(f.storage.lastSource);
			f.advance(1); f.exhaustItemWork();
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, f.runtime.prepare(consumer));
			assertTrue(f.runtime.step(consumer).isEmpty()); assertFalse(consumer.invalidated());
			assertEquals(1, f.resolutions); assertEquals(0, f.storage.reads); f.assertMetadataPaidOnce();
			f.advance(2);
			assertEquals(InventorySourceAccess.Preparation.READY, f.runtime.prepare(consumer));
			assertSame(original, f.storage.lastSource); assertEquals(1, f.resolutions); assertEquals(0, f.storage.reads);
			f.assertMetadataPaidOnce();
			assertComplete(f.runtime.step(consumer).orElseThrow(), 162); assertEquals(2, f.storage.reads); assertSettled(f.runtime);
		}
	}

	@Test void cursorlessPreparationRetryKeepsUnknownCoverageIncompleteInsteadOfInventingAnEmptyCompletion() {
		try (var f = new Fixture(true)) {
			f.storage.cursorless = true; f.storage.settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
			var consumer = f.attach(false); f.leaveOneProviderCall(); assertTrue(f.runtime.step(consumer).isEmpty());
			assertEquals(1, f.resolutions); assertEquals(0, f.storage.reads); f.assertMetadataPaidOnce();
			f.advance(1); var incomplete = f.runtime.step(consumer).orElseThrow();
			assertEquals(CaptureResult.Availability.READABLE, incomplete.result().availability());
			assertEquals(CaptureResult.Completeness.INCOMPLETE, incomplete.result().completeness());
			assertTrue(incomplete.result().coverage().expected().isEmpty()); assertEquals(1, incomplete.result().coverage().scanned());
			assertEquals(81, Objects.requireNonNull(incomplete.slots().getFirst()).amount());
			assertEquals(1, f.resolutions); assertEquals(1, f.storage.reads); assertFalse(consumer.invalidated());
			assertThrows(IllegalStateException.class, consumer::retainCompleted); assertSettled(f.runtime);
			f.advance(2); f.runtime.step(consumer); assertEquals(1, f.storage.reads, "a cursorless prefix cannot gain a cross-tick continuation");
			consumer.close(); f.storage.settings.setPhysicalSlotsPerTick(IntLimit.finite(2)); f.advance(3);
			var fresh = f.attach(false); assertComplete(f.runtime.step(fresh).orElseThrow(), 162);
			assertEquals(2, f.resolutions); assertEquals(3, f.storage.reads); assertSettled(f.runtime);
		}
	}

	@Test void pendingPreparationRechecksCurrentPermissionMemberAndRouteIdentityBeforeAnyContentRead() {
		for (boolean external : new boolean[] {false, true}) for (int change = 0; change < 3; change++) try (var f = new Fixture(external)) {
			var consumer = f.attach(false); f.leaveOneProviderCall(); assertTrue(f.runtime.step(consumer).isEmpty());
			Object oldRoute = f.storage.routeIdentity;
			var oldMember = Objects.requireNonNull(f.storage.entities.get(f.storage.root));
			if (change == 0) f.storage.authorized = false;
			else if (change == 1) f.storage.replaceMember(f.storage.root);
			else f.storage.routeIdentity = new Object();
			f.advance(1); var invalid = f.runtime.step(consumer).orElseThrow();
			assertNotEquals(CaptureResult.Availability.READABLE, invalid.result().availability());
			assertEquals(CaptureResult.Completeness.INCOMPLETE, invalid.result().completeness()); assertTrue(invalid.slots().isEmpty());
			assertTrue(consumer.invalidated()); assertFalse(consumer.scopeTransition()); assertEquals(1, f.resolutions); assertEquals(0, f.storage.reads);
			assertThrows(IllegalStateException.class, consumer::retainCompleted); f.assertMetadataPaidOnce();
			java.lang.ref.Reference.reachabilityFence(oldRoute); java.lang.ref.Reference.reachabilityFence(oldMember);
		}
	}

	private static void assertComplete(InventoryRuntime.Observation observation, long amount) {
		assertEquals(CaptureResult.Availability.READABLE, observation.result().availability());
		assertEquals(CaptureResult.Completeness.COMPLETE, observation.result().completeness());
		assertEquals(2, observation.result().coverage().scanned()); assertEquals(2, observation.result().coverage().expected().orElseThrow());
		assertEquals(amount, observation.slots().stream().mapToLong(value -> Objects.requireNonNull(value).amount()).sum());
	}
	private static CostLedger ledger(InventoryRuntime runtime) {
		try { var field = InventoryRuntime.class.getDeclaredField("physical"); field.setAccessible(true); return (CostLedger) Objects.requireNonNull(field.get(runtime)); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static void assertSettled(InventoryRuntime runtime) {
		assertEquals(0, runtime.memory().reserved()); var ledger = ledger(runtime);
		for (var counter : List.of(WORK, InventorySourceAccess.PHYSICAL, InventorySourceAccess.PROBES, InventorySourceAccess.PROVIDER_WORK))
			assertEquals(ledger.limit(counter) - ledger.used(counter), ledger.remaining(counter), "unsettled ticket: " + counter);
	}
}
