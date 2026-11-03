package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.IdentityHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.EmptyFluid;
import net.minecraft.world.level.material.Fluids;
import nx.pingwheel.common.platform.FluidRegistries;
import nx.pingwheel.common.platform.IPlatformFluidRegistryService;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryFluidCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryFluidRegistryLookupTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void mappedWaterIsExactAndUnregisteredOrEmptyCannotUseDefaultKey() throws ReflectiveOperationException {
		assertEquals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("water"),
			java.util.ServiceLoader.load(IPlatformFluidRegistryService.class).findFirst().orElseThrow().registeredKey(Fluids.WATER, (work, bytes) -> {}));
		var value = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.WATER, null), Long.MAX_VALUE),
			RegistryAccess.EMPTY, 1024, new InventoryFluidCodec.Catalog(), new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(1024),
			InventoryCatalogAdmission.workUpperBound(1024)));
		assertNotNull(value); assertFalse(value.aggregateRequired()); assertEquals("minecraft:water", value.resourceId()); assertEquals(Long.MAX_VALUE, value.amount());
		// A frozen vanilla registry refuses intrusive construction. The object is used only
		// as an unregistered identity; no native state/getter is needed for this negative.
		var unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
		var hostile = (HostileFluid) unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), HostileFluid.class);
		assertNull(InventoryFluidRegistryLookup.mappedKey(hostile, (work, bytes) -> {}));
		assertNull(InventoryFluidRegistryLookup.mappedKey(Fluids.EMPTY, (work, bytes) -> {})); assertEquals(0, hostile.calls);
	}
	@Test void retainedIdentityTableCapacityRatherThanSizePaysForTheLookup() {
		var map = new IdentityHashMap<Object, Object>(32768);
		map.put(Fluids.WATER, "holder");
		long[] first = {0}; assertEquals("holder", InventoryFluidRegistryLookup.identityHolder(map, Fluids.WATER, (work, bytes) -> first[0] += work));
		var small = new IdentityHashMap<Object, Object>(); small.put(Fluids.WATER, "holder");
		long[] second = {0}; assertEquals("holder", InventoryFluidRegistryLookup.identityHolder(small, Fluids.WATER, (work, bytes) -> second[0] += work));
		assertTrue(first[0] > second[0] + 1_000_000, "one retained entry does not justify a size-only probe bound");
		map.clear(); long[] empty = {0};
		assertNull(InventoryFluidRegistryLookup.identityHolder(map, Fluids.WATER, (work, bytes) -> empty[0] += work));
		assertTrue(empty[0] < second[0], "empty lookup is skipped entirely");
	}
	@Test void callerRootExhaustionStopsBeforeLookupAndIsNeverAnAggregate() {
		var budget = InventoryItemBudget.capture(1024, 1, InventoryItemBudget.retainedUpperBound(1024), 32);
		budget.begin(1024);
		try {
			assertThrows(InventoryItemBudget.CaptureLimitException.class,
				() -> InventoryFluidRegistryLookup.mappedKey(Fluids.WATER, (work, allocation) -> { budget.work(work); budget.allocate(allocation, 0, 0, 0); }));
			assertEquals(0, budget.rootAllocatedBytes()); assertEquals(0, budget.attemptedWork());
		} finally { budget.end(); }
		var sentinel = new InventoryItemBudget.RootLimitException("root sentinel");
		assertSame(sentinel, assertThrows(InventoryItemBudget.RootLimitException.class,
			() -> FluidRegistries.registeredKey(Fluids.WATER, (work, bytes) -> {}, (fluid, admission) -> { throw sentinel; })));
		assertNull(FluidRegistries.registeredKey(Fluids.WATER, (work, bytes) -> fail("absent provider has no lookup"), null));
	}
	@Test void foreignRawHolderAndWrongRegistryKeyAreRefusedBeforeNativeValueCallbacks() {
		assertNull(InventoryFluidRegistryLookup.holderKey(new Object(), Fluids.WATER, (work, bytes) -> {}));
		var itemHolder = BuiltInRegistries.ITEM.getHolder(net.minecraft.resources.ResourceLocation.withDefaultNamespace("stone")).orElseThrow();
		assertNull(InventoryFluidRegistryLookup.holderKey(itemHolder, Fluids.WATER, (work, bytes) -> {}));
	}
	@NotNullByDefault
	private static final class HostileFluid extends EmptyFluid {
		private int calls;
		@Override public int hashCode() { calls++; throw new AssertionError("fluid hashCode"); }
		@Override public boolean equals(@Nullable Object other) { calls++; throw new AssertionError("fluid equals"); }
	}
}
