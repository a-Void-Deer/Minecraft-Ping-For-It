package nx.pingwheel.common.presentation.inventory.minecraft;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.LockCode;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryReadSafetyTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	static final class Chest extends ChestBlockEntity implements BaseContainerLockAccessor {
		LockCode lock = new LockCode("owner-key"); int itemReads, implicitReads;
		Chest() { super(BlockPos.ZERO, Blocks.CHEST.defaultBlockState()); }
		@Override public LockCode pingforit$inventoryLock() { return lock; }
		@Override public ItemStack getItem(int slot) { itemReads++; return super.getItem(slot); }
		@Override protected NonNullList<ItemStack> getItems() { implicitReads++; return super.getItems(); }
	}
	@Test void lockOnlyPreflightDoesNotCollectContentsOrGenerateLoot() {
		Chest chest = new Chest(); ItemStack key = new ItemStack(Items.STICK); key.set(DataComponents.CUSTOM_NAME, Component.literal("owner-key"));
		assertFalse(InventoryReadSafety.readable(chest, ItemStack.EMPTY));
		assertTrue(InventoryReadSafety.readable(chest, key));
		assertEquals(0, chest.itemReads); assertEquals(0, chest.implicitReads);
		chest.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
			net.minecraft.resources.ResourceLocation.parse("minecraft:chests/simple_dungeon")));
		assertFalse(InventoryReadSafety.readable(chest, key)); assertNotNull(chest.getLootTable());
		assertEquals(0, chest.itemReads); assertEquals(0, chest.implicitReads);
	}
	@Test void missingAccessorFailsClosedInsteadOfSerializingARealChest() {
		ChestBlockEntity chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
		assertFalse(InventoryReadSafety.readable(chest, ItemStack.EMPTY));
	}
	static final class Sided extends SimpleContainer implements WorldlyContainer {
		int[] mapping = {2, 0}; Direction received;
		Sided() { super(3); }
		@Override public int[] getSlotsForFace(Direction face) { received = face; return mapping; }
		@Override public boolean canPlaceItemThroughFace(int slot, ItemStack item, Direction face) { throw new AssertionError("mutation filter is not read permission"); }
		@Override public boolean canTakeItemThroughFace(int slot, ItemStack item, Direction face) { throw new AssertionError("mutation filter is not read permission"); }
	}
	@Test void worldlyMappingIsCopiedValidatedAndUsesTheChosenFaceOnly() {
		Sided sided = new Sided(); int[] slots = InventoryMinecraftSources.sidedSlots(sided, Direction.WEST);
		assertArrayEquals(new int[] {2, 0}, slots); assertEquals(Direction.WEST, sided.received);
		sided.mapping[0] = 1; assertArrayEquals(new int[] {2, 0}, slots);
		sided.mapping = new int[] {1, 1}; assertThrows(IllegalStateException.class, () -> InventoryMinecraftSources.sidedSlots(sided, Direction.NORTH));
		sided.mapping = new int[0]; assertEquals(0, InventoryMinecraftSources.sidedSlots(sided, Direction.DOWN).length);
		assertArrayEquals(new int[] {0, 1}, InventoryMinecraftSources.sidedSlots(new SimpleContainer(2), Direction.EAST));
	}
}
