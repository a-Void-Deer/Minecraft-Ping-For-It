package nx.pingwheel.common.presentation.inventory.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** UNRUN: method-family, backing and scope counterexamples. */
class InventoryContainerReadsTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void inheritedNativeGraphQualifiesButOverriddenProtectedGetItemsDoesNotRun() {
		var inherited = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState()) {
			@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) { throw new AssertionError("save"); }
		};
		assertTrue(InventoryContainerReads.audited(inherited, ignored -> true));
		var changed = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState()) {
			@Override protected NonNullList<ItemStack> getItems() { throw new AssertionError("protected helper"); }
		};
		assertThrows(AssertionError.class, () -> changed.getItem(0)); // positive control
		assertFalse(InventoryContainerReads.audited(changed, ignored -> true));
	}
	@Test void everyKnownDelegateIsAuthorizedEvenWhenFirstDelegateIsExternal() {
		var external = new SimpleContainer(1) { @Override public ItemStack getItem(int slot) { throw new AssertionError("external getter"); } };
		var foreign = new SimpleContainer(1); var wrapper = new CompoundContainer(external, foreign);
		assertThrows(InventorySourceUnavailableException.class, () -> InventoryContainerReads.audited(wrapper, value -> value != foreign));
		assertFalse(InventoryContainerReads.audited(wrapper, ignored -> true), "unknown reader remains external trust, not audited");
		var nativeWrapper = new CompoundContainer(new SimpleContainer(1), new SimpleContainer(1));
		assertTrue(InventoryContainerReads.audited(nativeWrapper, ignored -> true));
	}
	@Test void pendingLootIsExpectedUnavailabilityWithoutReadingOrGeneratingContents() {
		// Use the inherited native read graph so auditing reaches its loot gate.
		var nativeChest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
		var loot = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
			net.minecraft.resources.ResourceLocation.parse("minecraft:chests/simple_dungeon"));
		nativeChest.setLootTable(loot);
		assertThrows(InventorySourceUnavailableException.class, () -> InventoryContainerReads.audited(nativeChest, ignored -> true));
		assertEquals(loot, nativeChest.getLootTable(), "classification cannot unpack pending loot");
	}
	@Test void cyclesAreRefusedBeforeAnyNativeGetterCanRecurse() throws Exception {
		var cyclic = new CompoundContainer(new SimpleContainer(1), new SimpleContainer(1));
		for (var field : CompoundContainer.class.getDeclaredFields()) if (field.getType() == Container.class) {
			assertTrue(field.trySetAccessible()); field.set(cyclic, cyclic); break;
		}
		assertThrows(IllegalStateException.class, () -> InventoryContainerReads.audited(cyclic, ignored -> true));
	}
	@Test void addedWorldlyMappingCannotInheritTheCompoundReadCertificate() {
		class Sided extends CompoundContainer implements net.minecraft.world.WorldlyContainer {
			Sided() { super(new SimpleContainer(1), new SimpleContainer(1)); }
			@Override public int[] getSlotsForFace(net.minecraft.core.Direction face) { throw new AssertionError("unknown mapping"); }
			@Override public boolean canPlaceItemThroughFace(int slot, ItemStack item, net.minecraft.core.Direction face) { throw new AssertionError("write query"); }
			@Override public boolean canTakeItemThroughFace(int slot, ItemStack item, net.minecraft.core.Direction face) { throw new AssertionError("write query"); }
		}
		var value = new Sided();
		assertThrows(AssertionError.class, () -> value.getSlotsForFace(net.minecraft.core.Direction.NORTH));
		assertFalse(InventoryContainerReads.audited(value, ignored -> true));
	}
	@Test void reusableScratchResetsAfterFailureAndNeverRetainsARevokedScopeDecision() throws Exception {
		var first = new SimpleContainer(1); var second = new SimpleContainer(1);
		var valid = new CompoundContainer(first, second); var scratch = new InventoryContainerReads.Scratch();
		for (int pass = 0; pass < 40; pass++) assertTrue(InventoryContainerReads.audited(valid, ignored -> true, scratch), "node counters reset on every operation");
		assertThrows(InventorySourceUnavailableException.class, () -> InventoryContainerReads.audited(valid, value -> value != second, scratch));
		assertTrue(InventoryContainerReads.audited(valid, ignored -> true, scratch));
		var cyclic = new CompoundContainer(first, second);
		for (var field : CompoundContainer.class.getDeclaredFields()) if (field.getType() == Container.class) {
			assertTrue(field.trySetAccessible()); field.set(cyclic, cyclic); break;
		}
		assertThrows(IllegalStateException.class, () -> InventoryContainerReads.audited(cyclic, ignored -> true, scratch));
		assertTrue(InventoryContainerReads.audited(valid, ignored -> true, scratch), "failure path cannot poison the next graph walk");
	}
}
