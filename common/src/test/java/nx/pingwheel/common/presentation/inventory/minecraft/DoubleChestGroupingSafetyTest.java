package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;
import nx.pingwheel.common.client.outline.BlockOutlineSpec;
import nx.pingwheel.common.client.outline.BlockPresentationResolverRegistry;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MinecraftTargetGroupingResolver;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class DoubleChestGroupingSafetyTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@NotNullByDefault
	private static final class Chest extends ChestBlockEntity implements BaseContainerLockAccessor {
		LockCode lock = LockCode.NO_LOCK;
		@Nullable ResourceKey<LootTable> loot;
		int reads, lootChecks;
		Chest(BlockPos pos, BlockState state) {
			super(state.is(Blocks.TRAPPED_CHEST) ? BlockEntityType.TRAPPED_CHEST : BlockEntityType.CHEST, pos, state);
		}
		@Override public LockCode pingforit$inventoryLock() { return lock; }
		@Override public @Nullable ResourceKey<LootTable> getLootTable() { lootChecks++; return loot; }
		@Override public ItemStack getItem(int slot) { reads++; return new ItemStack(Items.STONE, 3); }
		@Override public void unpackLootTable(@Nullable net.minecraft.world.entity.player.Player player) { throw new AssertionError("no loot generation"); }
	}
	@NotNullByDefault
	private static final class World implements InventoryMinecraftSources.SourceWorld {
		final DoubleChestFixture topology;
		final Map<BlockPos, Chest> chests = new HashMap<>();
		final AtomicInteger providers = new AtomicInteger();
		World(DoubleChestFixture topology) { this.topology = topology; topology.states.forEach((pos, state) -> chests.put(pos, new Chest(pos, state))); }
		@Override public boolean isLoaded(BlockPos pos) { return topology.states.containsKey(pos) && !topology.unavailable.contains(pos); }
		@Override public BlockState blockState(BlockPos pos) { return topology.states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
		@Override public @Nullable BlockEntity blockEntity(BlockPos pos) { return chests.get(pos); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
		@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
		@Override public boolean audited(net.minecraft.world.Container container) { return container instanceof Chest; }
		InventoryMinecraftSources.View view(InventorySourceInput input) {
			return InventoryMinecraftSources.view(this, input, (pos, face) -> { providers.incrementAndGet(); throw new AssertionError("no capability fallback for vanilla chest"); });
		}
		int reads() { return chests.values().stream().mapToInt(chest -> chest.reads).sum(); }
	}
	@Test void bothKindsEitherHitProduceActualProductionPairViewAndReadBothHalvesWithoutCapabilities() {
		for (var block : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST)) for (boolean otherHit : List.of(false, true)) {
			var f = new DoubleChestFixture(block, Direction.WEST); var world = new World(f);
			var target = f.target(otherHit ? f.right : f.left); var input = new InventorySourceInput(target, DoubleChestFixture.OWNER, BlockFace.DOWN);
			var key = new MinecraftTargetGroupingResolver(f.server).resolve(input.readOwner(), target); assertInstanceOf(TargetKey.DoubleChestKey.class, key);
			var view = world.view(input); assertEquals(2, view.entities().size()); assertEquals(54, view.slots().size());
			assertEquals(0, world.reads(), "view/group metadata are not contents");
			try (var source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> world.view(input), entry ->
				new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), entry.amount(), "Stone", null, false))) {
				assertTrue(source.valid()); assertEquals(54, source.slots());
				for (int slot = 0; slot < 54; slot++) assertEquals(3, source.read(slot).count());
				assertEquals(54, world.reads()); assertTrue(world.chests.values().stream().allMatch(chest -> chest.reads == 27));
			}
			assertEquals(0, world.providers.get()); assertEquals(key, new MinecraftTargetGroupingResolver(f.server).resolve(input.readOwner(), target));
		}
	}
	@Test void groupingAndDisplayNeverAuthorizeLockedLootOrUnavailableSiblingInventory() {
		for (var block : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST)) for (boolean otherHit : List.of(false, true))
			for (String denial : List.of("locked-root", "locked-sibling", "loot-root", "loot-sibling", "unloaded-sibling", "wrong-sibling")) {
			var f = new DoubleChestFixture(block, Direction.NORTH); var world = new World(f); BlockPos hit = otherHit ? f.right : f.left;
			BlockPos sibling = otherHit ? f.left : f.right; var target = f.target(hit);
			var input = new InventorySourceInput(target, DoubleChestFixture.OWNER, BlockFace.SOUTH);
			var denied = world.chests.get(denial.endsWith("root") ? hit : sibling);
			if (denial.startsWith("locked")) denied.lock = new LockCode("secret");
			if (denial.startsWith("loot")) denied.loot = ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse("minecraft:chests/simple_dungeon"));
			// Locks/loot do not prevent a normal whole-block grouped marker or presentation.
			assertInstanceOf(TargetKey.DoubleChestKey.class, new MinecraftTargetGroupingResolver(f.server).resolve(input.readOwner(), target));
			var spec = new BlockOutlineSpec(new MarkerId(1), (TargetKey.BlockKey) TargetKey.from(target), "entity_block", "attention", -1);
			assertEquals(2, new BlockPresentationResolverRegistry().resolve(f.client, spec).renderSubjects().size());
			assertTrue(world.chests.values().stream().allMatch(chest -> chest.lootChecks == 0));
			if (denial.equals("unloaded-sibling")) f.unavailable.add(sibling);
			if (denial.equals("wrong-sibling")) f.states.put(sibling, Blocks.BARREL.defaultBlockState());
			assertThrows(InventorySourceUnavailableException.class, () -> world.view(input), denial);
			assertEquals(0, world.reads()); assertEquals(0, world.providers.get());
		}
	}
}
