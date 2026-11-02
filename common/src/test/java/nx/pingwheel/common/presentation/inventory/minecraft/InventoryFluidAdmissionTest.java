package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Map;
import nx.pingwheel.common.platform.FluidAddressWitness;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryFluidAdmissionTest {
	private static final CostLedger.Counter WORK = new CostLedger.Counter("fluid", CostLedger.Unit.WORK);
	private static final InventoryValidationBudget.Cost OWNED = new InventoryValidationBudget.Cost(128, 64, 8);
	private static final InventoryValidationBudget.Cost MEMBER = new InventoryValidationBudget.Cost(32, 16, 3);
	private static final InventoryValidationBudget.Cost AUTHORITY = new InventoryValidationBudget.Cost(64, 32, 5);
	private static IPlatformFluidService.OperationProfile profile(long retained) { return new IPlatformFluidService.OperationProfile(OWNED, 2, 3, retained); }
	@Test void callbackCountsAreIndependentAndZeroCountsDoNotReserveUnusedCallbackScratch() {
		var cost = profile(0).cost(MEMBER, AUTHORITY);
		assertEquals(384, cost.allocationBytes()); assertEquals(96, cost.workspaceBytes()); assertEquals(29, cost.workUnits());
		assertEquals(OWNED, new IPlatformFluidService.OperationProfile(OWNED, 0, 0, 0).cost(MEMBER, AUTHORITY));
		assertEquals(IPlatformFluidService.OperationProfile.NONE, profile(500).times(0));
	}
	@Test void nestedStagesAndAdditionalGrantsShareOneCumulativeAllowanceWithoutRefunds() {
		var ledger = new CostLedger(Map.of(WORK, 10_000L)); var memory = new RetainedMemoryLedger(1_000_000);
		InventoryValidationBudget.Allowance original;
		try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY)) {
			try (var first = admission.enter(profile(0))) {
				original = first.allowance(); original.debit(OWNED); original.debit(MEMBER.times(2)); original.debit(AUTHORITY.times(3));
				long spent = original.attemptedWorkUnits();
				try (var second = admission.enter(profile(0))) {
					assertSame(original, second.allowance()); assertTrue(original.attemptedWorkUnits() > spent);
					second.allowance().debit(profile(0).cost(MEMBER, AUTHORITY));
				}
				first.requireAdditional(OWNED); original.debit(OWNED);
				assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> original.debit(OWNED));
				assertTrue(memory.reserved() > 0); assertEquals(0, ledger.used(WORK));
			}
		}
		assertEquals(original.attemptedWorkUnits(), ledger.used(WORK)); assertEquals(ledger.limit(WORK) - ledger.used(WORK), ledger.remaining(WORK));
		assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
	}
	@Test void refusedShapeAndLockGrowthKeepPaidPrefixAndReleaseAllFailedAdmissionTickets() {
		var ledger = new CostLedger(Map.of(WORK, 200L)); var memory = new RetainedMemoryLedger(1_000_000);
		long spent;
		try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY);
			var operation = admission.enter(new IPlatformFluidService.OperationProfile(OWNED, 0, 0, 0))) {
			operation.allowance().debit(OWNED); long reserved = memory.reserved(); spent = operation.allowance().attemptedWorkUnits();
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> admission.enter(profile(500)));
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> operation.requireAdditional(OWNED));
			assertEquals(reserved, memory.reserved()); assertEquals(spent, operation.allowance().attemptedWorkUnits());
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> operation.allowance().debit(OWNED));
		}
		assertEquals(spent, ledger.used(WORK)); assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
	}
	@Test void retainedMetadataTransfersOnceAndOutlivesOperationsWithoutPinningWorkspace() {
		var ledger = new CostLedger(Map.of(WORK, 1000L)); var memory = new RetainedMemoryLedger(1_000_000);
		InventoryFluidAdmission.Ownership ownership;
		try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY)) {
			try (var probe = admission.enter(profile(100)); var finish = admission.enter(profile(200))) {
				assertSame(probe.allowance(), finish.allowance());
			}
			ownership = admission.retain(); assertEquals(1068, ownership.bytes()); assertEquals(1068, memory.retained());
			assertThrows(IllegalStateException.class, admission::retain);
		}
		assertEquals(0, memory.reserved()); assertEquals(1068, memory.retained()); ownership.close(); ownership.close();
		assertEquals(0, memory.retained());
	}
	@Test void failedFinishAndMissingTransferReleaseMetadataAndClosedOperationsCannotBeReused() {
		var ledger = new CostLedger(Map.of(WORK, 1000L)); var memory = new RetainedMemoryLedger(1_000_000);
		IPlatformFluidService.Operation saved;
		try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY)) {
			saved = admission.enter(profile(100)); saved.allowance().debit(OWNED); saved.close(); saved.close();
			assertThrows(IllegalStateException.class, saved::allowance);
			assertThrows(IllegalStateException.class, () -> saved.requireAdditional(OWNED));
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
		assertTrue(ledger.used(WORK) > 0); assertThrows(IllegalStateException.class, saved::allowance);
	}
	@Test void memoryRefusalAndArithmeticOverflowRunNoWorkAndLeakNoTickets() {
		var ledger = new CostLedger(Map.of(WORK, 1000L)); var memory = new RetainedMemoryLedger(3000);
		try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY)) {
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> admission.enter(profile(4000)));
			assertThrows(ArithmeticException.class, () -> admission.enter(new IPlatformFluidService.OperationProfile(OWNED, Long.MAX_VALUE, 0, 0)));
		}
		assertEquals(0, ledger.used(WORK)); assertEquals(1000, ledger.remaining(WORK)); assertEquals(0, memory.reserved());
	}
	@Test void sharedCallerAllowanceSeparatesOuterAndInnerSettlementWithoutRefillOrDoubleCharge() {
		var allowance = new InventoryValidationBudget.Allowance(OWNED); allowance.debit(OWNED);
		var ledger = new CostLedger(Map.of(WORK, 1000L)); var memory = new RetainedMemoryLedger(100_000);
		long before = allowance.attemptedWorkUnits();
		try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance);
			var operation = admission.enter(new IPlatformFluidService.OperationProfile(OWNED, 0, 0, 0))) {
			assertSame(allowance, operation.allowance()); allowance.debit(OWNED);
		}
		assertEquals(allowance.attemptedWorkUnits() - before, ledger.used(WORK));
		assertEquals(ledger.used(WORK), allowance.independentlyChargedWork());
		assertEquals(before, allowance.attemptedWorkUnits() - allowance.independentlyChargedWork());
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> allowance.debit(OWNED));
		assertEquals(0, memory.reserved());
	}
	@Test void runtimeFactoryUsesTheExistingGlobalLedgerAndCurrentMemoryCap() {
		var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(input -> java.util.Optional.empty(), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings);
			try (var admission = runtime.fluidAdmission(MEMBER, AUTHORITY); var operation = admission.enter(profile(0))) {
				operation.allowance().debit(OWNED); assertTrue(runtime.memory().reserved() > 0);
			}
			assertEquals(0, runtime.memory().reserved()); runtime.memory().setCap(0);
			try (var admission = runtime.fluidAdmission(MEMBER, AUTHORITY)) {
				assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> admission.enter(profile(0)));
			}
		}
	}
	@Test void sameLedgerOuterAndSequentialInnerStagesSettleExactlyOnceAndPreserveUnusedOuterHeadroom() {
		var ledger = new CostLedger(Map.of(WORK, 10_000L)); var memory = new RetainedMemoryLedger(100_000);
		var outerCost = OWNED.times(3); var outer = ledger.tryReserve(Map.of(WORK, outerCost.workUnits())).orElseThrow();
		var allowance = new InventoryValidationBudget.Allowance(outerCost); allowance.debit(OWNED);
		for (int stage = 0; stage < 2; stage++) {
			try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance);
				var operation = admission.enter(new IPlatformFluidService.OperationProfile(OWNED, 0, 0, 0))) {
				operation.allowance().debit(OWNED);
			}
			assertEquals(0, memory.reserved());
		}
		allowance.debit(OWNED); // Inner close must neither consume nor refill the original caller grant.
		long own = allowance.attemptedWorkUnits() - allowance.independentlyChargedWork();
		outer.commit(Map.of(WORK, own)); outer.close();
		assertEquals(allowance.attemptedWorkUnits(), ledger.used(WORK)); assertEquals(2 * OWNED.workUnits(), own);
		allowance.debit(OWNED); assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> allowance.debit(OWNED));
	}
	@Test void sequentialScratchReusesPeakWhileNestedOperationsRetainOverlapAndEveryAttemptStaysPaid() {
		var cost = new InventoryValidationBudget.Cost(20_000, 20_000, 1);
		var ledger = new CostLedger(Map.of(WORK, 10_000L)); var memory = new RetainedMemoryLedger(100_000);
		try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY)) {
			for (int index = 0; index < 3; index++) try (var operation = admission.enter(new IPlatformFluidService.OperationProfile(cost, 0, 0, 0))) {
				operation.allowance().debit(cost); assertEquals(20_000 + (index + 1) * 2048, memory.reserved());
			}
			try (var parent = admission.enter(new IPlatformFluidService.OperationProfile(cost, 0, 0, 0));
				var child = admission.enter(new IPlatformFluidService.OperationProfile(cost, 0, 0, 0))) {
				parent.allowance().debit(cost); child.allowance().debit(cost);
				assertEquals(40_000 + 5 * 2048, memory.reserved());
			}
		}
		assertEquals(5 * 129, ledger.used(WORK)); assertEquals(0, memory.reserved());
	}
	@Test void parentOperationAndMetadataCloseOrdersNeverLeakOrResurrectAdmission() {
		for (boolean parentFirst : new boolean[] {false, true}) for (boolean ownershipFirst : new boolean[] {false, true}) {
			var ledger = new CostLedger(Map.of(WORK, 10_000L)); var memory = new RetainedMemoryLedger(100_000);
			var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY);
			var parent = admission.enter(profile(100)); var child = admission.enter(profile(200));
			parent.allowance().debit(OWNED); child.allowance().debit(OWNED);
			var ownership = admission.retain();
			if (ownershipFirst) ownership.close();
			if (parentFirst) { admission.close(); parent.close(); child.close(); }
			else { parent.close(); child.allowance().debit(MEMBER); child.close(); admission.close(); }
			assertThrows(IllegalStateException.class, parent::allowance); assertThrows(IllegalStateException.class, child::allowance);
			admission.close(); ownership.close(); ownership.close(); assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
			assertEquals(ledger.limit(WORK) - ledger.used(WORK), ledger.remaining(WORK));
		}
	}
	@Test void originalWitnessCaptureAndRepeatedComparisonsSettleSameLedgerOuterDeltaExactlyOnceOnEveryExit() {
		for (int exit = 0; exit < 3; exit++) {
			var ledger = new CostLedger(Map.of(WORK, 100_000L)); var memory = new RetainedMemoryLedger(100_000);
			var outerCost = OWNED.times(3); var allowance = new InventoryValidationBudget.Allowance(outerCost);
			try (var outer = ledger.tryReserve(Map.of(WORK, outerCost.workUnits())).orElseThrow()) {
				allowance.debit(OWNED); long outerBefore = allowance.attemptedWorkUnits();
				Object first = new Object(), second = new Object(); FluidAddressWitness witness;
				InventoryFluidAdmission.Ownership ownership;
				try (var capture = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance)) {
					var profile = FluidAddressWitness.profile(IPlatformFluidService.WitnessUse.CAPTURE, 2);
					try (var operation = capture.enter(profile)) {
						assertSame(allowance, operation.allowance()); allowance.requireAvailable(profile.ownedCost());
						witness = FluidAddressWitness.capture(FluidAddressWitness.Schema.COMMON_MEMBERS_V1, new Object[] {first, second}, operation);
					}
					ownership = capture.retain();
				}
				try (ownership) {
					long pinned = ownership.bytes(); assertEquals(FluidAddressWitness.retainedBytes(2) + 512, pinned);
					assertEquals(0, memory.reserved()); assertEquals(pinned, memory.retained());
					for (int pass = 0; pass < 3; pass++) {
						long before = allowance.attemptedWorkUnits(); long chargedBefore = ledger.used(WORK);
						try (var compare = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance);
							var operation = compare.enter(FluidAddressWitness.profile(IPlatformFluidService.WitnessUse.COMPARE, 2))) {
							assertSame(allowance, operation.allowance());
							assertEquals(IPlatformFluidService.WitnessMatch.SAME,
								witness.compare(FluidAddressWitness.Schema.COMMON_MEMBERS_V1, new Object[] {first, second}, operation));
							if (pass == 2 && exit == 1) throw new ExpectedExit();
							if (pass == 2 && exit == 2) {
								long attempted = allowance.attemptedWorkUnits(), reserved = memory.reserved();
								assertThrows(InventoryFluidAdmission.Deferred.class,
									() -> operation.requireAdditional(new InventoryValidationBudget.Cost(1_000_000, 1_000_000, 1)));
								assertEquals(attempted, allowance.attemptedWorkUnits()); assertEquals(reserved, memory.reserved());
							}
						} catch (ExpectedExit expected) { assertEquals(1, exit); }
						assertEquals(allowance.attemptedWorkUnits() - before, ledger.used(WORK) - chargedBefore);
						assertEquals(0, memory.reserved()); assertEquals(pinned, memory.retained());
					}
				}
				assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
				assertEquals(allowance.attemptedWorkUnits() - outerBefore, allowance.independentlyChargedWork());
				allowance.debit(OWNED); long outerDelta = allowance.attemptedWorkUnits() - allowance.independentlyChargedWork();
				assertEquals(2 * OWNED.workUnits(), outerDelta); outer.commit(Map.of(WORK, outerDelta));
				assertEquals(allowance.attemptedWorkUnits(), ledger.used(WORK));
				allowance.debit(OWNED); assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> allowance.debit(OWNED));
			}
			assertEquals(ledger.limit(WORK) - ledger.used(WORK), ledger.remaining(WORK));
		}
	}
	@Test void distinctAdmissionsShareAllowanceButLifoSettlementPaysOnlyTheirOwnAttemptedWork() {
		var ledger = new CostLedger(Map.of(WORK, 10_000L)); var memory = new RetainedMemoryLedger(100_000);
		var allowance = new InventoryValidationBudget.Allowance(OWNED.times(2));
		try (var outer = ledger.tryReserve(Map.of(WORK, 16L)).orElseThrow()) {
			allowance.debit(OWNED);
			try (var parent = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance)) {
				try (var operation = parent.enter(new IPlatformFluidService.OperationProfile(OWNED, 0, 0, 0))) { operation.allowance().debit(OWNED); }
				try (var child = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance)) {
					try (var operation = child.enter(new IPlatformFluidService.OperationProfile(OWNED, 0, 0, 0))) { operation.allowance().debit(OWNED); }
					assertThrows(IllegalStateException.class, () -> parent.enter(profile(0)), "a suspended parent cannot grant through its child");
				}
				assertEquals(136, ledger.used(WORK)); assertEquals(136, allowance.independentlyChargedWork());
				assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> allowance.debit(OWNED), "settled child cannot refill the parent");
			}
			assertEquals(272, ledger.used(WORK)); assertEquals(272, allowance.independentlyChargedWork());
			assertEquals(280, allowance.attemptedWorkUnits());
			assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
			allowance.debit(OWNED); assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> allowance.debit(OWNED));
			outer.commit(Map.of(WORK, allowance.attemptedWorkUnits() - allowance.independentlyChargedWork()));
			assertEquals(allowance.attemptedWorkUnits(), ledger.used(WORK));
		}
	}
	@Test void outOfOrderDistinctAdmissionCloseFreezesSpendingAndCannotResurrectParentGrantOrWorkspace() {
		var ledger = new CostLedger(Map.of(WORK, 10_000L)); var memory = new RetainedMemoryLedger(100_000);
		var allowance = new InventoryValidationBudget.Allowance(OWNED); allowance.debit(OWNED);
		try (var outer = ledger.tryReserve(Map.of(WORK, OWNED.workUnits())).orElseThrow()) {
		var large = new InventoryValidationBudget.Cost(10_000, 10_000, 1000);
		var parent = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance);
		try (var operation = parent.enter(new IPlatformFluidService.OperationProfile(large, 0, 0, 0))) { operation.allowance().debit(large); }
		var child = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance);
		var current = child.enter(new IPlatformFluidService.OperationProfile(OWNED, 0, 0, 0)); current.allowance().debit(OWNED);
		assertThrows(IllegalStateException.class, parent::close);
		long attempted = allowance.attemptedWorkUnits();
		assertThrows(IllegalStateException.class, () -> allowance.debit(new InventoryValidationBudget.Cost(5000, 5000, 500)));
		assertThrows(IllegalStateException.class, current::allowance);
		assertThrows(IllegalStateException.class, () -> child.enter(profile(0)));
		assertThrows(IllegalStateException.class, child::retain);
		assertThrows(IllegalStateException.class, child::close); current.close(); parent.close(); child.close();
		assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
		assertEquals(attempted, allowance.attemptedWorkUnits()); assertEquals(1264, ledger.used(WORK));
		assertEquals(ledger.used(WORK), allowance.independentlyChargedWork());
		assertThrows(IllegalStateException.class, () -> allowance.debit(new InventoryValidationBudget.Cost(5000, 5000, 500)));
		assertThrows(IllegalStateException.class, () -> allowance.admitAdditional(large));
		assertThrows(IllegalStateException.class, () -> new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance));
		outer.commit(Map.of(WORK, allowance.attemptedWorkUnits() - allowance.independentlyChargedWork()));
		assertEquals(attempted, ledger.used(WORK));
		}
	}
	@Test void unusedParentHeadroomCannotHideDoubleSettlementOfACompletedChild() {
		var ledger = new CostLedger(Map.of(WORK, 10_000L)); var memory = new RetainedMemoryLedger(100_000);
		var allowance = new InventoryValidationBudget.Allowance(OWNED); allowance.debit(OWNED);
		var headroom = new InventoryValidationBudget.Cost(128, 64, 200);
		try (var parent = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance);
			var operation = parent.enter(new IPlatformFluidService.OperationProfile(headroom, 0, 0, 0))) {
			operation.allowance().debit(OWNED);
			try (var child = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance);
				var childOperation = child.enter(new IPlatformFluidService.OperationProfile(OWNED, 0, 0, 0))) { childOperation.allowance().debit(OWNED); }
		}
		assertEquals(280, allowance.attemptedWorkUnits()); assertEquals(272, allowance.independentlyChargedWork());
		assertEquals(272, ledger.used(WORK)); assertEquals(0, memory.reserved());
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> allowance.debit(OWNED));
	}
	@Test void everyThreeOwnerClosePermutationDrainsExactlyOnceAndOnlyLifoRemainsServiceable() {
		for (int[] order : new int[][] {{2,1,0}, {2,0,1}, {1,2,0}, {1,0,2}, {0,2,1}, {0,1,2}}) {
			var ledger = new CostLedger(Map.of(WORK, 10_000L)); var memory = new RetainedMemoryLedger(100_000);
			var allowance = new InventoryValidationBudget.Allowance(OWNED);
			InventoryFluidAdmission[] owners = new InventoryFluidAdmission[3];
			InventoryFluidAdmission.Ownership[] retained = new InventoryFluidAdmission.Ownership[3];
			for (int index = 0; index < 3; index++) {
				owners[index] = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance);
				try (var operation = owners[index].enter(new IPlatformFluidService.OperationProfile(OWNED, 0, 0, 100))) { operation.allowance().debit(OWNED); }
				retained[index] = owners[index].retain();
			}
			boolean failed = false; int expected = 2;
			for (int index : order) {
				failed |= index != expected--;
				if (failed) assertThrows(IllegalStateException.class, owners[index]::close); else owners[index].close();
				owners[index].close(); retained[index].close(); retained[index].close();
			}
			assertEquals(408, ledger.used(WORK)); assertEquals(408, allowance.independentlyChargedWork());
			assertEquals(408, allowance.attemptedWorkUnits()); assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
			if (failed) assertThrows(IllegalStateException.class, () -> allowance.debit(OWNED));
			else { allowance.debit(OWNED); assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> allowance.debit(OWNED)); }
		}
	}
	@Test void deniedChildCannotBorrowOuterOrParentHeadroomAndClosingItPreservesBothBudgets() {
		var ledger = new CostLedger(Map.of(WORK, 10_000L)); var memory = new RetainedMemoryLedger(100_000);
		var large = new InventoryValidationBudget.Cost(10_000, 10_000, 1000);
		var allowance = new InventoryValidationBudget.Allowance(OWNED);
		try (var parent = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance);
			var operation = parent.enter(new IPlatformFluidService.OperationProfile(large, 0, 0, 0))) {
			long before = allowance.attemptedWorkUnits();
			try (var child = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance)) {
				assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> allowance.debit(OWNED));
				memory.setCap(0); assertThrows(InventoryFluidAdmission.Deferred.class, () -> child.enter(profile(0))); memory.setCap(100_000);
			}
			assertEquals(before, allowance.attemptedWorkUnits()); operation.allowance().debit(large);
		}
		assertEquals(1128, ledger.used(WORK)); assertEquals(0, memory.reserved()); allowance.debit(OWNED);
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> allowance.debit(OWNED));
	}
	@Test void distinctAdmissionScratchOverlapsAndExceptionalChildClosePreservesParentAdditionalGrant() {
		var ledger = new CostLedger(Map.of(WORK, 10_000L)); var memory = new RetainedMemoryLedger(100_000);
		var allowance = new InventoryValidationBudget.Allowance(OWNED);
		var large = new InventoryValidationBudget.Cost(20_000, 20_000, 1);
		try (var parent = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance);
			var operation = parent.enter(new IPlatformFluidService.OperationProfile(large, 0, 0, 0))) {
			operation.allowance().debit(large); long before = memory.reserved();
			assertEquals(22_048, before);
			try {
				try (var child = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY, allowance);
					var inner = child.enter(new IPlatformFluidService.OperationProfile(large, 0, 0, 0))) {
					inner.allowance().debit(large); assertEquals(44_096, memory.reserved()); throw new ExpectedExit();
				}
			} catch (ExpectedExit expected) { assertEquals(before, memory.reserved()); }
			operation.requireAdditional(OWNED); operation.allowance().debit(OWNED);
			assertEquals(129, allowance.independentlyChargedWork());
		}
		assertEquals(394, ledger.used(WORK)); assertEquals(394, allowance.attemptedWorkUnits());
		assertEquals(394, allowance.independentlyChargedWork()); assertEquals(0, memory.reserved());
		allowance.debit(OWNED); assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> allowance.debit(OWNED));
	}
	@NotNullByDefault
	private static final class ExpectedExit extends RuntimeException { private static final long serialVersionUID = 1L; }
}
