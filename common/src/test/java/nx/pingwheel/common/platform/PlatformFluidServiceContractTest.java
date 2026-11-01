package nx.pingwheel.common.platform;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import nx.pingwheel.common.presentation.inventory.InventoryQuantities;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class PlatformFluidServiceContractTest {
	@Test void absentLoaderIsANoopAndQuantitiesCannotInventANonemptyFluid() {
		assertSame(IPlatformFluidService.NONE, IPlatformFluidService.INSTANCE);
		assertEquals(InventoryValidationBudget.NONE, IPlatformFluidService.NONE.resolutionCost());
		assertEquals(0, IPlatformFluidService.Entry.empty().amount());
		assertNull(IPlatformFluidService.Entry.empty().resident());
		assertThrows(IllegalArgumentException.class, () -> new IPlatformFluidService.Entry(null, 81));
		assertThrows(ArithmeticException.class, () -> InventoryQuantities.fromMillibuckets(Long.MAX_VALUE));
	}
	@Test void indexedObserverCountsBlanksAndStopsWithoutInventingCompletion() {
		AtomicInteger reads = new AtomicInteger(), visits = new AtomicInteger();
		var access = new Indexed(reads);
		assertEquals(new IPlatformFluidService.Visit(2, false), access.observe(2, entry -> { visits.incrementAndGet(); return true; }));
		assertEquals(2, reads.get()); assertEquals(2, visits.get());
		assertEquals(new IPlatformFluidService.Visit(1, false), access.observe(3, entry -> false));
		assertEquals(3, reads.get());
		assertEquals(new IPlatformFluidService.Visit(3, true), access.observe(3, entry -> true));
		assertEquals(6, reads.get(), "a later observation starts at zero; no invented cursor");
		assertThrows(IllegalArgumentException.class, () -> access.observe(-1, entry -> true));
	}
	@Test void refusedAdmissionRunsNoValidationGetterOrVisitorAndSpentAttemptStaysPaid() {
		AtomicInteger reads = new AtomicInteger();
		var access = new Indexed(reads);
		var allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
		assertThrows(nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget.CaptureLimitException.class,
			() -> access.consume(0, entry -> fail("visitor cannot precede admission"), allowance));
		assertEquals(0, reads.get());
		var cost = access.readCost();
		var paid = new InventoryValidationBudget.Allowance(cost);
		assertThrows(IllegalStateException.class, () -> access.consume(0, entry -> { throw new IllegalStateException("freeze failed"); }, paid));
		assertEquals(1, reads.get()); assertEquals(cost.workUnits(), paid.attemptedWorkUnits());
		assertThrows(nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget.CaptureLimitException.class,
			() -> access.consume(0, entry -> true, paid));
		assertEquals(1, reads.get());
	}
	@NotNullByDefault
	private static final class Indexed implements IPlatformFluidService.Access {
		private final AtomicInteger reads;
		private Indexed(AtomicInteger reads) { this.reads = reads; }
		@Override public InventorySnapshotLayout layout() { throw new UnsupportedOperationException("not needed by indexed iteration"); }
		@Override public Optional<String> alias() { return Optional.empty(); }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return 3; }
		@Override public InventoryValidationBudget.Cost validationCost() { return InventoryValidationBudget.NONE; }
		@Override public InventoryValidationBudget.Cost readCost() { return new InventoryValidationBudget.Cost(64, 64, 32); }
		@Override public boolean valid() { return true; }
		@Override public boolean consume(int slot, IPlatformFluidService.Visitor visitor) { reads.incrementAndGet(); return visitor.visit(IPlatformFluidService.Entry.empty()); }
	}
}
