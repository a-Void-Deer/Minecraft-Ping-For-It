package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryInPlaceSourceContractTest {
	private static final BlockPos ROOT = BlockPos.ZERO, MEMBER = new BlockPos(1, 0, 0);
	private static final InventorySourceInput INPUT = new InventorySourceInput(
		new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:beehive"), new UUID(95, 1), BlockFace.NORTH);
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@NotNullByDefault
	private static final class Chest extends ChestBlockEntity implements BaseContainerLockAccessor {
		LockCode lock = LockCode.NO_LOCK;
		Chest() { super(MEMBER, Blocks.CHEST.defaultBlockState()); }
		@Override public LockCode pingforit$inventoryLock() { return lock; }
	}
	@NotNullByDefault
	private static final class World implements InventoryMinecraftSources.SourceWorld {
		final Map<BlockPos, BlockEntity> entities = new HashMap<>();
		final Chest chest = new Chest();
		ItemStack key = ItemStack.EMPTY;
		boolean owner = true;
		int lookups, metadata;
		World() {
			entities.put(ROOT, new BeehiveBlockEntity(ROOT, Blocks.BEEHIVE.defaultBlockState())); entities.put(MEMBER, chest);
		}
		@Override public boolean isLoaded(BlockPos position) { metadata++; return entities.containsKey(position); }
		@Override public BlockState blockState(BlockPos position) { metadata++; return java.util.Objects.requireNonNull(entities.get(position)).getBlockState(); }
		@Override public @Nullable BlockEntity blockEntity(BlockPos position) { metadata++; return entities.get(position); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { metadata++; if (!owner) throw new InventorySourceUnavailableException("owner missing"); return key; }
		@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
		InventorySnapshotLayout layout() {
			return new InventorySnapshotLayout("test:current", "face=north", ROOT, List.of(
				new InventorySnapshotLayout.Member(ROOT, "minecraft:beehive", "minecraft:beehive", "root", List.of(
					new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, 1, List.of(0)))),
				new InventorySnapshotLayout.Member(MEMBER, "minecraft:chest", "minecraft:chest", "member", List.of())));
		}
	}
	@NotNullByDefault
	private static class Access implements IPlatformInventoryService.Access {
		final InventorySnapshotLayout layout;
		int reads, checks;
		long amount = 7;
		boolean valid = true;
		Runnable afterRead = () -> {};
		Access(InventorySnapshotLayout layout) { this.layout = layout; }
		@Override public Optional<String> alias() { return Optional.of(layout.layoutId()); }
		@Override public Optional<InventorySnapshotLayout> snapshotLayout() { return Optional.of(layout); }
		@Override public boolean valid() { checks++; return valid; }
		@Override public int slots() { return 1; }
		@Override public boolean stableCursor() { return true; }
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public IPlatformInventoryService.Entry read(int slot) { reads++; return new IPlatformInventoryService.Entry(new ItemStack(Items.STONE), amount); }
		@Override public boolean consumeResident(int slot, IPlatformInventoryService.ResidentVisitor visitor) {
			reads++; boolean more = visitor.visit(new IPlatformInventoryService.ResidentEntry(new InventoryResidentItem.Stack(new ItemStack(Items.STONE)), amount));
			afterRead.run(); return more;
		}
		@Override public boolean visit(int limit, Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("indexed only"); }
	}
	@NotNullByDefault
	private static final class Capable extends Access implements IPlatformInventoryService.InPlaceRevalidatingAccess {
		int currentChecks;
		Capable(InventorySnapshotLayout layout) { super(layout); }
		@Override public boolean revalidateCurrent(InventorySnapshotLayout original) { currentChecks++; return valid && original.equals(layout); }
		@Override public InventoryValidationBudget.Cost currentValidationCost() { return new InventoryValidationBudget.Cost(100, 100, 5); }
		@Override public InventoryValidationBudget.Cost nativeReadCost() { return new InventoryValidationBudget.Cost(100, 100, 3); }
		@Override public IPlatformInventoryService.Access scopedResidents(java.util.function.Supplier<? extends IPlatformInventoryService.ResidentScope> factory) { return this; }
	}
	private static InventoryMinecraftSources.Source source(World world, Access access) {
		var initial = InventoryMinecraftSources.view(world, INPUT, (position, face) -> { world.lookups++; return Optional.of(access); });
		return new InventoryMinecraftSources.Source(INPUT, initial, world::onServerThread,
			() -> { throw new AssertionError("opted-in source rebuilt its View"); }, entry -> null, world, null);
	}
	@Test void ownerKeysLootLockAndRemovedSiblingAreFreshForEveryInPlaceCheckAndFinalHandoff() {
		for (String boundary : List.of("key", "loot", "locked", "removed", "owner")) {
			var world = new World(); var access = new Capable(world.layout());
			var key = new ItemStack(Items.STICK); key.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
				net.minecraft.network.chat.Component.literal("key")); world.key = key; world.chest.lock = new LockCode("key");
			try (var source = source(world, access)) {
				assertNull(source.sharedRead(), "in-place validity alone cannot grant equivalence certification");
				assertTrue(source.valid());
				Runnable revoke = () -> {
					switch (boundary) {
						case "key" -> world.key = ItemStack.EMPTY;
						case "loot" -> world.chest.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse("minecraft:chests/simple_dungeon")));
						case "locked" -> world.chest.lock = new LockCode("other");
						case "removed" -> world.chest.setRemoved();
						case "owner" -> world.owner = false;
						default -> throw new AssertionError(boundary);
					}
				};
				access.afterRead = revoke;
				assertThrows(InventorySourceUnavailableException.class, () -> source.read(0), boundary);
				assertEquals(1, access.reads); assertFalse(source.valid(), boundary); assertEquals(1, world.lookups);
			}
		}
	}
	@Test void unchangedAliasSlotCountAndValidOldWrapperCannotHideGenericCapabilityReplacement() {
		var world = new World(); var first = new Access(world.layout()); var second = new Access(first.layout); second.amount = 41;
		IPlatformInventoryService.Access[] current = {first};
		InventoryMinecraftSources.ProviderAccessLookup lookup = (position, face) -> { world.lookups++; return Optional.of(current[0]); };
		var initial = InventoryMinecraftSources.view(world, INPUT, lookup);
		try (var source = new InventoryMinecraftSources.Source(INPUT, initial, world::onServerThread,
			() -> InventoryMinecraftSources.view(world, INPUT, new InventoryMinecraftSources.PhysicalRead(INPUT.ordinaryTarget().orElseThrow(), INPUT.face(), world::isLoaded),
				lookup, initial.snapshotLayout(), true), entry -> null, world, null)) {
			current[0] = second; assertEquals(41, source.read(0).count()); assertEquals(0, first.reads); assertEquals(1, second.reads);
			assertTrue(world.lookups >= 3, "unknown Access must re-resolve on pre and post checks");
			current[0] = new Access(new InventorySnapshotLayout(first.layout.layoutId(), "changed", ROOT, first.layout.members()));
			assertFalse(source.valid()); assertEquals(1, second.reads);
		}
	}
	@Test void externalBindingStillResolvesEveryOperationEvenWhenItsProviderOptsIn() {
		var world = new World(); var access = new Capable(world.layout());
		var target = Target.ExternalBlockTarget.candidate("minecraft:overworld", "sable", "minecraft:beehive", "locator", true);
		var input = new InventorySourceInput(target, INPUT.readOwner(), INPUT.face());
		var descriptor = new BlockReadSource(target, "sable", "sublevel", INPUT.ordinaryTarget().orElseThrow(), new MarkerAnchor(0, 0, 0));
		int[] bindings = {0}; boolean[] member = {true};
		var binding = new InventoryMinecraftSources.ExternalBinding(world, descriptor, position -> member[0],
			(position, face) -> Optional.of(access));
		try (var source = InventoryMinecraftSources.resolveExternal(input, () -> { bindings[0]++; return Optional.of(binding); }, () -> true, entry -> null).orElseThrow()) {
			assertNull(source.sharedRead(), "external bindings do not acquire ordinary certification");
			int before = bindings[0]; assertTrue(source.valid()); assertTrue(source.valid()); assertEquals(before + 2, bindings[0]);
			assertEquals(0, access.currentChecks); member[0] = false; assertFalse(source.valid()); assertEquals(0, access.reads);
		}
	}
}
