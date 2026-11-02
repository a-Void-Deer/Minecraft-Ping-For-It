package nx.pingwheel.common.platform;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import nx.pingwheel.common.presentation.inventory.InventoryQuantities;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget.CaptureLimitException;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class PlatformFluidServiceContractTest {
	private static final CostLedger.Counter WORK = new CostLedger.Counter("fluid-test", CostLedger.Unit.WORK);
	private static InventoryFluidAdmission admission(long work) {
		return new InventoryFluidAdmission(new CostLedger(Map.of(WORK, work)), WORK, new RetainedMemoryLedger(1_000_000),
			InventoryValidationBudget.NONE, InventoryValidationBudget.NONE);
	}
	@Test void absentLoaderIsANoopAndQuantitiesCannotInventANonemptyFluid() {
		assertSame(IPlatformFluidService.NONE, IPlatformFluidService.INSTANCE);
		assertEquals(IPlatformFluidService.OperationProfile.NONE, IPlatformFluidService.NONE.probeProfile());
		assertEquals(0, IPlatformFluidService.Entry.empty().amount()); assertNull(IPlatformFluidService.Entry.empty().resident());
		assertThrows(IllegalArgumentException.class, () -> new IPlatformFluidService.Entry(null, 81));
		assertThrows(ArithmeticException.class, () -> InventoryQuantities.fromMillibuckets(Long.MAX_VALUE));
	}
	@Test void profileShapeAndRouteRejectUnboundedOrInconsistentMetadata() {
		assertThrows(IllegalArgumentException.class, () -> new IPlatformFluidService.OperationProfile(InventoryValidationBudget.NONE, -1, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new IPlatformFluidService.OperationProfile(InventoryValidationBudget.NONE, 0, -1, 0));
		assertThrows(IllegalArgumentException.class, () -> new IPlatformFluidService.OperationProfile(InventoryValidationBudget.NONE, 0, 0, -1));
		assertThrows(IllegalArgumentException.class, () -> new IPlatformFluidService.Shape(0, OptionalInt.empty(), true));
		assertThrows(IllegalArgumentException.class, () -> new IPlatformFluidService.Shape(1, OptionalInt.of(4097), true));
		assertThrows(IllegalArgumentException.class, () -> new IPlatformFluidService.Shape(1, OptionalInt.of(1), false));
		assertEquals(OptionalInt.empty(), new IPlatformFluidService.Shape(81, OptionalInt.empty(), true).indexedSlots());
		assertThrows(IllegalArgumentException.class, () -> new IPlatformFluidService.RouteInfo(IPlatformFluidService.TopologyEvidence.PUBLIC_ADDRESS_ONLY, "x\ny"));
		assertThrows(ArithmeticException.class, () -> new IPlatformFluidService.OperationProfile(InventoryValidationBudget.NONE, Long.MAX_VALUE, 0, 0)
			.plus(new IPlatformFluidService.OperationProfile(InventoryValidationBudget.NONE, 1, 0, 0)));
	}
	@Test void indexedObserverCountsBlanksAndStopsWithoutInventingCompletion() {
		var reads = new AtomicInteger(); var visits = new AtomicInteger(); var access = new Indexed(reads);
		try (var admission = admission(10_000)) {
			try (var operation = admission.enter(access.observationProfile(2))) {
				assertEquals(new IPlatformFluidService.Visit(2, false), access.observe(2, entry -> { visits.incrementAndGet(); return true; }, operation));
			}
			assertEquals(2, reads.get()); assertEquals(2, visits.get());
			try (var operation = admission.enter(access.observationProfile(3))) {
				assertEquals(new IPlatformFluidService.Visit(1, false), access.observe(3, entry -> false, operation));
			}
			assertEquals(3, reads.get());
			try (var operation = admission.enter(access.observationProfile(3))) {
				assertEquals(new IPlatformFluidService.Visit(3, true), access.observe(3, entry -> true, operation));
			}
			assertEquals(6, reads.get(), "later observation starts at zero");
			assertThrows(IllegalArgumentException.class, () -> access.observationProfile(-1));
		}
	}
	@Test void refusedAdmissionRunsNoGetterAndFailedVisitorCannotRefillCumulativeAllowance() {
		var reads = new AtomicInteger(); var access = new Indexed(reads);
		try (var admission = admission(0)) {
			assertThrows(CaptureLimitException.class, () -> admission.enter(access.readProfile())); assertEquals(0, reads.get());
		}
		try (var admission = admission(10_000); var operation = admission.enter(access.readProfile())) {
			assertThrows(IllegalStateException.class, () -> access.consume(0, entry -> { throw new IllegalStateException("freeze failed"); }, operation));
			assertEquals(1, reads.get());
			assertThrows(CaptureLimitException.class, () -> access.consume(0, entry -> true, operation)); assertEquals(1, reads.get());
		}
	}
	@Test void noUnbudgetedExecutionOverloadsRemain() {
		for (var method : IPlatformFluidService.class.getMethods()) assertFalse(method.getName().equals("find") || method.getName().equals("resolve"));
		for (var method : IPlatformFluidService.Access.class.getMethods()) if (java.util.Set.of("valid", "consume", "observe", "captureWitness", "compareWitness").contains(method.getName()))
			assertEquals(IPlatformFluidService.Operation.class, method.getParameterTypes()[method.getParameterCount() - 1]);
	}
	@Test void unmigratedWitnessDefaultsAreUnsupportedNeverEmptySuccessfulEvidence() {
		var access = new Indexed(new AtomicInteger());
		assertThrows(UnsupportedOperationException.class, () -> access.witnessProfile(IPlatformFluidService.WitnessUse.CAPTURE));
		assertThrows(UnsupportedOperationException.class, () -> access.witnessProfile(IPlatformFluidService.WitnessUse.COMPARE));
		try (var admission = admission(10_000); var operation = admission.enter(FluidAddressWitness.profile(IPlatformFluidService.WitnessUse.CAPTURE, 1))) {
			assertThrows(UnsupportedOperationException.class, () -> access.captureWitness(operation));
			var witness = FluidAddressWitness.capture(FluidAddressWitness.Schema.NEOFORGE_CAPABILITY_V1, new Object[] {access}, operation);
			assertThrows(UnsupportedOperationException.class, () -> access.compareWitness(witness, operation));
		}
	}
	@NotNullByDefault
	private static final class Indexed implements IPlatformFluidService.Access {
		private final AtomicInteger reads;
		private Indexed(AtomicInteger reads) { this.reads = reads; }
		@Override public InventorySnapshotLayout layout() { throw new UnsupportedOperationException("iteration fixture"); }
		@Override public Optional<String> alias() { return Optional.empty(); }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return 3; }
		@Override public IPlatformFluidService.RouteInfo routeInfo() { return new IPlatformFluidService.RouteInfo(IPlatformFluidService.TopologyEvidence.PUBLIC_ADDRESS_ONLY, getClass().getName()); }
		@Override public IPlatformFluidService.OperationProfile validationProfile() { return IPlatformFluidService.OperationProfile.NONE; }
		@Override public IPlatformFluidService.OperationProfile readProfile() { return new IPlatformFluidService.OperationProfile(new InventoryValidationBudget.Cost(64, 64, 32), 0, 0, 0); }
		@Override public boolean valid(IPlatformFluidService.Operation operation) { operation.allowance(); return true; }
		@Override public boolean consume(int slot, IPlatformFluidService.Visitor visitor, IPlatformFluidService.Operation operation) {
			operation.allowance().debit(readProfile().ownedCost()); reads.incrementAndGet(); return visitor.visit(IPlatformFluidService.Entry.empty());
		}
		@Override public void close() {}
	}
}
