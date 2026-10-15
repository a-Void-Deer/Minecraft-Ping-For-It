package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Call-boundary behavior, not a snapshot of the production cost coefficients. */
class InventoryRootCensusBoundaryTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void contextMetadataMustFitBeforeAnySuppliedRootMapPort() {
		var reads = new AtomicInteger();
		var raw = new InventoryResidentAccessFields() {
			@Override public Map<? extends ResourceKey<?>, ? extends Registry<?>> registries(RegistryAccess.ImmutableRegistryAccess access) {
				reads.incrementAndGet(); throw new AssertionError("root map touched before metadata admission");
			}
		};
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		var bounds = new InventoryComponentBounds(budget, raw);
		// Leave one modeled byte: not enough for even an object header, much
		// less context+three arrays. No counter equality is used as an oracle.
		budget.allocate(2L * 1024 * 1024 - budget.rootAllocatedBytes() - 1, 0, 0, 0);
		assertThrows(InventoryItemBudget.RootLimitException.class,
			() -> InventoryRegistryContext.capture(RegistryAccess.EMPTY, raw, bounds, budget));
		assertEquals(0, reads.get()); budget.end();
	}

	@Test void captureExhaustionEscapesRatherThanReturningAnAggregate() {
		var callbacks = new AtomicInteger();
		HolderLookup.Provider provider = new HolderLookup.Provider() {
			@Override public Stream<ResourceKey<? extends Registry<?>>> listRegistries() { callbacks.incrementAndGet(); throw new AssertionError(); }
			@Override public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) { callbacks.incrementAndGet(); throw new AssertionError(); }
		};
		var budget = InventoryItemBudget.capture(4096, 2, Long.MAX_VALUE, 1);
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> InventoryItemCodec.freeze(
			new InventoryResidentItem.Stack(new ItemStack(Items.STONE)), provider, 4096, budget,
			new InventoryResidentAccessFields(), new InventoryTestNativeAccess()));
		assertEquals(0, callbacks.get());
		// The finally boundary ended the failed root, rather than stranding active.
		assertDoesNotThrow(() -> budget.begin(4096)); budget.end();
	}

	@Test void repeatedReferencesPayForEachOwnedOccurrenceAndCyclesDoNot() {
		var shared = new CompoundTag(); shared.put("leaf", IntTag.valueOf(1));
		var repeated = new CompoundTag(); repeated.put("a", shared); repeated.put("b", shared);
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		var bounds = new InventoryComponentBounds(budget, new InventoryResidentAccessFields());
		var owned = bounds.snapshotNbt(repeated);
		assertNotSame(owned.fields().get(0).value(), owned.fields().get(1).value(),
			"a DAG is expanded per occurrence, not collapsed by identity");
		long paid = budget.rootAllocatedBytes(); bounds.snapshotNbt(repeated);
		assertTrue(budget.rootAllocatedBytes() > paid, "a second conversion is not a scratch refund");
		shared.put("self", shared);
		var refusal = assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.snapshotNbt(shared));
		assertEquals(0, refusal.getStackTrace().length);
		shared.remove("self"); assertDoesNotThrow(() -> bounds.snapshotNbt(shared), "finally clears the active path");
		budget.end();
	}

	@Test void arithmeticRefusalIsStacklessAtSignedBoundaries() {
		assertEquals(Long.MIN_VALUE, InventoryItemBudget.add(Long.MIN_VALUE, 0));
		assertEquals(Long.MAX_VALUE, InventoryItemBudget.multiply(Long.MAX_VALUE, 1));
		for (long[] operands : new long[][] {{Long.MAX_VALUE, 1}, {Long.MIN_VALUE, -1}}) {
			assertEquals(0, assertThrows(InventoryItemBudget.RootLimitException.class,
				() -> InventoryItemBudget.add(operands[0], operands[1])).getStackTrace().length);
		}
		for (long[] operands : new long[][] {{Long.MAX_VALUE, 2}, {Long.MIN_VALUE, -1}, {-1, Long.MIN_VALUE}}) {
			assertEquals(0, assertThrows(InventoryItemBudget.RootLimitException.class,
				() -> InventoryItemBudget.multiply(operands[0], operands[1])).getStackTrace().length);
		}
	}

	@Test void catalogCallerAllowanceCannotBeSpentInsideIdentityPhase() {
		int cap = InventoryItemCodec.MAX_DISPLAY_BYTES;
		var budget = InventoryItemBudget.root(cap); budget.begin(cap);
		budget.work(12L * 1024 * 1024);
		assertThrows(InventoryItemBudget.RootLimitException.class, () -> budget.work(1),
			"a larger exported freeze+read reservation does not widen native identity");
		budget.beginDisplay(); budget.work(1); budget.end();
		assertTrue(InventoryItemBudget.workUpperBound(cap) > InventoryItemBudget.freezeWorkUpperBound(cap),
			"the strict Catalog OLD parse/hash operation is an independent caller term");
	}
}
