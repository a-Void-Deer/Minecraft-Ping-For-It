package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.LockCode;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Source-owned capture behavior over the production view and NBT capture algorithms. */
class InventoryMinecraftSnapshotCaptureTest {
	private static final UUID OWNER = new UUID(4, 4);
	private static final BlockPos FIRST = BlockPos.ZERO;

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void singleChestUsesRealVanillaNbtAndDoesNotSaveForViewOrLiveSelection() {
		World world = new World();
		Chest first = world.addChest(FIRST, Blocks.CHEST.defaultBlockState(), Items.DIAMOND, 6);
		InventorySourceInput input = input("minecraft:chest", FIRST);
		InventoryMinecraftSources.View view = view(world, input);
		InventoryMinecraftSources.Source source = source(world, input, view, () -> view(world, input));

		assertTrue(source.valid(), "view resolution and revalidation do not serialize the chest");
		assertEquals("minecraft:diamond", source.read(0).key().itemId(), "the live selection witness still reads normally");
		assertEquals(0, first.saves.get(), "live selection must not save custom NBT");
		assertEquals(1, first.itemReads, "the live witness is a real Container.getItem read");

		InventorySourceAccess.SnapshotPlan plan = source.snapshotPlan().orElseThrow();
		assertEquals(0, first.saves.get(), "snapshot plan discovery remains lazy");
		InventoryNbtSnapshot snapshot = (InventoryNbtSnapshot) plan.capture().orElseThrow();
		try (snapshot) {
			assertEquals(27, snapshot.slots());
			assertEquals(1, first.saves.get(), "one source capture performs one saveCustomOnly call");
			assertEquals(1, first.nonEmptySaves.get());
			assertEquals(0, first.implicitComponentCollections.get(), "custom NBT save does not collect implicit data components");
			assertEquals(1, first.itemReads, "snapshot creation never calls the live Container.getItem path");
			assertEquals(InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED, snapshot.evidence());
			assertEquals("minecraft:diamond", snapshot.read(0).key().itemId());
			assertEquals(6L, snapshot.read(0).count());
			assertNull(snapshot.read(1));
			assertEquals(1, first.itemReads, "reading the detached snapshot never goes back to the container");
		}
	}

	@Test void doubleChestCapturesBothMembersInCanonicalOrderWithIndependentLocalSlotZero() {
		World world = new World();
		BlockState leftState = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST)
			.setValue(ChestBlock.TYPE, ChestType.LEFT);
		BlockPos secondPos = FIRST.relative(ChestBlock.getConnectedDirection(leftState));
		BlockState rightState = leftState.setValue(ChestBlock.TYPE, ChestType.RIGHT);
		Chest first = world.addChest(FIRST, leftState, Items.STONE, 3);
		Chest second = world.addChest(secondPos, rightState, Items.DIRT, 7);
		InventorySourceInput input = input("minecraft:chest", secondPos);
		InventoryMinecraftSources.View view = view(world, input);
		InventoryMinecraftSources.Source source = source(world, input, view, () -> view(world, input));

		assertEquals(54, view.slots().size());
		assertEquals(List.of(FIRST, secondPos), view.snapshotLayout().members().stream().map(InventorySnapshotLayout.Member::position).toList());
		for (var member : view.snapshotLayout().members())
			assertEquals(java.util.stream.IntStream.range(0, 27).boxed().toList(), member.segments().getFirst().visibleSlots());
		assertEquals(0, first.saves.get());
		assertEquals(0, second.saves.get());

		InventoryNbtSnapshot snapshot = (InventoryNbtSnapshot) source.snapshotPlan().orElseThrow().capture().orElseThrow();
		try (snapshot) {
			assertEquals(54, snapshot.slots());
			assertEquals(secondPos, snapshot.originalTarget(), "capture retains the originally hit half");
			assertEquals("minecraft:stone", snapshot.read(0).key().itemId());
			assertEquals(3L, snapshot.read(0).count());
			assertEquals("minecraft:dirt", snapshot.read(27).key().itemId(), "second member's local Slot 0 begins at visible index 27");
			assertEquals(7L, snapshot.read(27).count());
			assertEquals(1, first.saves.get());
			assertEquals(1, second.saves.get());
			assertEquals(0, first.itemReads + second.itemReads, "neither half is read through the live Container API");
		}
	}

	@Test void everyMemberIsPreflightedBeforeTheFirstSaveForLocksAndPendingLoot() {
		for (boolean pendingLoot : List.of(false, true)) {
			World world = doubleChestWorld();
			BlockPos secondPos = world.blocks.keySet().stream().filter(pos -> !pos.equals(FIRST)).findFirst().orElseThrow();
			Chest first = (Chest) world.blockEntities.get(FIRST);
			Chest second = (Chest) world.blockEntities.get(secondPos);
			InventorySourceInput input = input("minecraft:chest", FIRST);
			InventoryMinecraftSources.View admitted = view(world, input);
			InventoryMinecraftSources.Source source = source(world, input, admitted, () -> view(world, input));
			if (pendingLoot) {
				second.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse("minecraft:chests/simple_dungeon")));
			} else {
				second.lock = new LockCode("other-key");
			}

			assertTrue(source.snapshotPlan().orElseThrow().capture().isEmpty(),
				pendingLoot ? "pending loot on the second member rejects the whole preflight" : "a locked second member rejects the whole preflight");
			assertEquals(0, first.saves.get(), "no first-member NBT is captured before all-member safety succeeds");
			assertEquals(0, second.saves.get());
		}
	}

	@Test void topologyLossBeforeAndDuringCaptureNeverPublishesASnapshot() {
		World beforeWorld = doubleChestWorld();
		BlockPos secondPos = beforeWorld.blocks.keySet().stream().filter(pos -> !pos.equals(FIRST)).findFirst().orElseThrow();
		Chest first = (Chest) beforeWorld.blockEntities.get(FIRST);
		InventorySourceInput input = input("minecraft:chest", FIRST);
		InventoryMinecraftSources.View admitted = view(beforeWorld, input);
		InventoryMinecraftSources.Source before = source(beforeWorld, input, admitted, () -> view(beforeWorld, input));
		InventorySourceAccess.SnapshotPlan beforePlan = before.snapshotPlan().orElseThrow();
		beforeWorld.loaded.remove(secondPos);
		assertTrue(beforePlan.capture().isEmpty(), "a changed topology is rejected before any save");
		assertEquals(0, first.saves.get());

		World duringWorld = doubleChestWorld();
		BlockPos duringSecondPos = duringWorld.blocks.keySet().stream().filter(pos -> !pos.equals(FIRST)).findFirst().orElseThrow();
		Chest duringFirst = (Chest) duringWorld.blockEntities.get(FIRST);
		InventorySourceInput duringInput = input("minecraft:chest", FIRST);
		InventoryMinecraftSources.View duringView = view(duringWorld, duringInput);
		InventoryMinecraftSources.Source during = source(duringWorld, duringInput, duringView, () -> view(duringWorld, duringInput));
		duringFirst.afterSave = () -> duringWorld.loaded.remove(duringSecondPos);
		assertThrows(IllegalStateException.class, () -> during.snapshotPlan().orElseThrow().capture(),
			"post-save all-member topology validation rejects the whole multi-member snapshot");
		assertEquals(1, duringFirst.saves.get());
		assertEquals(1, ((Chest) duringWorld.blockEntities.get(duringSecondPos)).saves.get());
	}

	@Test void changingNormalContentsAfterCaptureLeavesTheFrozenSnapshotAndSourceValid() {
		World world = new World();
		Chest chest = world.addChest(FIRST, Blocks.CHEST.defaultBlockState(), Items.STONE, 4);
		InventorySourceInput input = input("minecraft:chest", FIRST);
		InventoryMinecraftSources.View view = view(world, input);
		InventoryMinecraftSources.Source source = source(world, input, view, () -> view(world, input));
		InventoryNbtSnapshot snapshot = (InventoryNbtSnapshot) source.snapshotPlan().orElseThrow().capture().orElseThrow();
		try (snapshot) {
			chest.setItem(0, new ItemStack(Items.DIAMOND, 12));
			assertTrue(source.valid(), "ordinary inventory contents changing does not invalidate fixed topology");
			assertEquals("minecraft:stone", snapshot.read(0).key().itemId());
			assertEquals(4L, snapshot.read(0).count(), "the detached contents stay frozen at the capture boundary");
			assertEquals(1, chest.saves.get(), "revalidation and snapshot reads do not save again");
		}
	}

	@Test void nonContainerCapabilityWithoutExplicitLayoutStaysLiveAndMalformedDescriptorsFailClosed() {
		World world = new World();
		BlockPos pos = new BlockPos(4, 0, 0);
		BlockState state = Blocks.BEEHIVE.defaultBlockState();
		BlockEntity entity = new BeehiveBlockEntity(pos, state);
		world.put(pos, state, entity);
		InventorySourceInput input = input("minecraft:beehive", pos);
		Access access = new Access(3, Optional.empty(), null);
		InventoryMinecraftSources.View noDescriptor = InventoryMinecraftSources.view(world, input, (ignored, face) -> Optional.of(access));
		assertNull(noDescriptor.snapshotLayout(), "provider slot count alone is not NBT face-mapping evidence");
		InventoryMinecraftSources.Source source = source(world, input, noDescriptor,
			() -> InventoryMinecraftSources.view(world, input, (ignored, face) -> Optional.of(access)));
		assertTrue(source.snapshotPlan().isEmpty(), "an absent provider layout keeps the established live route");
		assertEquals(0, access.reads.get());

		var twoSlots = segment(3, List.of(0, 1));
		var wrongTotal = layout(pos, List.of(new InventorySnapshotLayout.Member(pos, "minecraft:beehive",
			BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(), "target", List.of(twoSlots))));
		Access mismatched = new Access(3, Optional.of("test:provider"), wrongTotal);
		assertThrows(IllegalStateException.class,
			() -> InventoryMinecraftSources.view(world, input, (ignored, face) -> Optional.of(mismatched)),
			"a two-slot explicit descriptor cannot silently fall back against a three-slot live witness");
		assertEquals(0, mismatched.reads.get());

		var absentTarget = layout(new BlockPos(9, 0, 0), List.of(new InventorySnapshotLayout.Member(new BlockPos(9, 0, 0),
			"minecraft:beehive", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(), "target",
			List.of(segment(3, List.of(0, 1, 2))))));
		Access wrongMember = new Access(3, Optional.of("test:provider"), absentTarget);
		assertThrows(IllegalStateException.class,
			() -> InventoryMinecraftSources.view(world, input, (ignored, face) -> Optional.of(wrongMember)),
			"a descriptor that omits the actual target member is structurally invalid, not unsupported");
		assertEquals(0, wrongMember.reads.get());

		var unknownSchema = new InventorySnapshotLayout.Segment("Items", "test:unknown_schema", 3, List.of(0, 1, 2));
		var unknownLayout = layout(pos, List.of(new InventorySnapshotLayout.Member(pos, "minecraft:beehive",
			BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(), "target", List.of(unknownSchema))));
		Access unknown = new Access(3, Optional.of("test:provider"), unknownLayout);
		assertNull(InventoryMinecraftSources.view(world, input, (ignored, face) -> Optional.of(unknown)).snapshotLayout(),
			"an explicitly unsupported schema remains a live fallback, unlike a malformed recognized payload");
	}

	@Test void unsupportedAmbiguousItemsFallbackDiffersFromMalformedRecognizedItems() {
		World ambiguousWorld = new World();
		AmbiguousChest ambiguousChest = new AmbiguousChest(FIRST, Blocks.CHEST.defaultBlockState());
		ambiguousChest.setItem(0, new ItemStack(Items.STONE, 1));
		ambiguousChest.resetCounters();
		ambiguousWorld.put(FIRST, Blocks.CHEST.defaultBlockState(), ambiguousChest);
		InventorySourceInput input = input("minecraft:chest", FIRST);
		InventoryMinecraftSources.View ambiguousView = view(ambiguousWorld, input);
		assertTrue(source(ambiguousWorld, input, ambiguousView, () -> ambiguousView).snapshotPlan().orElseThrow().capture().isEmpty(),
			"two plausible item paths are unsupported rather than guessed");
		assertEquals(1, ambiguousChest.saves.get());

		World malformedWorld = new World();
		MalformedChest malformedChest = new MalformedChest(FIRST, Blocks.CHEST.defaultBlockState());
		malformedWorld.put(FIRST, Blocks.CHEST.defaultBlockState(), malformedChest);
		InventoryMinecraftSources.View malformedView = view(malformedWorld, input);
		assertThrows(IllegalStateException.class,
			() -> source(malformedWorld, input, malformedView, () -> malformedView).snapshotPlan().orElseThrow().capture(),
			"a recognized Items field with the wrong NBT type is not an empty or unsupported observation");
		assertEquals(1, malformedChest.saves.get());
	}

	@Test void frozenFaceWitnessPreservesSidedSlotOrderAndDetectsMappingChanges() {
		World world = new World();
		BlockState state = Blocks.CHEST.defaultBlockState();
		SidedChest chest = new SidedChest(FIRST, state);
		chest.setItem(3, new ItemStack(Items.DIAMOND, 2));
		chest.setItem(1, new ItemStack(Items.DIRT, 5));
		chest.resetCounters();
		world.put(FIRST, state, chest);
		InventorySourceInput input = input("minecraft:chest", FIRST);
		InventoryMinecraftSources.View admitted = view(world, input);
		assertEquals(List.of(3, 1), admitted.snapshotLayout().members().getFirst().segments().getFirst().visibleSlots());
		InventoryMinecraftSources.Source source = source(world, input, admitted, () -> view(world, input));
		InventoryNbtSnapshot snapshot = (InventoryNbtSnapshot) source.snapshotPlan().orElseThrow().capture().orElseThrow();
		try (snapshot) {
			assertEquals("minecraft:diamond", snapshot.read(0).key().itemId());
			assertEquals(2L, snapshot.read(0).count());
			assertEquals("minecraft:dirt", snapshot.read(1).key().itemId());
			assertEquals(5L, snapshot.read(1).count());
			chest.mapping = new int[] {1, 3};
			assertFalse(source.valid(), "the live source no longer witnesses the frozen ordered face mapping");
			assertEquals("minecraft:diamond", snapshot.read(0).key().itemId(), "mapping invalidation does not rewrite the detached capture");
		}
	}

	private static World doubleChestWorld() {
		World world = new World();
		BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST)
			.setValue(ChestBlock.TYPE, ChestType.LEFT);
		BlockPos rightPos = FIRST.relative(ChestBlock.getConnectedDirection(left));
		world.addChest(FIRST, left, Items.STONE, 3);
		world.addChest(rightPos, left.setValue(ChestBlock.TYPE, ChestType.RIGHT), Items.DIRT, 7);
		return world;
	}

	private static InventorySourceInput input(String blockId, BlockPos pos) {
		return new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ(), blockId), OWNER, BlockFace.NORTH);
	}

	private static InventoryMinecraftSources.View view(World world, InventorySourceInput input) {
		return InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.empty());
	}

	private static InventoryMinecraftSources.Source source(World world, InventorySourceInput input,
		InventoryMinecraftSources.View admitted, java.util.function.Supplier<InventoryMinecraftSources.View> liveView) {
		return new InventoryMinecraftSources.Source(input, admitted, () -> true, liveView,
			InventoryMinecraftSnapshotCaptureTest::domainItem, world, null);
	}

	private static InventoryDomainCodec.Item domainItem(IPlatformInventoryService.Entry entry) {
		if (entry.isEmpty()) return null;
		String id = BuiltInRegistries.ITEM.getKey(entry.exemplar().getItem()).toString();
		return new InventoryDomainCodec.Item(new InventoryScanner.Key(id, "plain"), entry.amount(), id, null, false);
	}

	private static InventorySnapshotLayout layout(BlockPos controller, List<InventorySnapshotLayout.Member> members) {
		return new InventorySnapshotLayout("test:provider", "face=north", controller, members);
	}

	private static InventorySnapshotLayout.Segment segment(int localSlots, List<Integer> visible) {
		return new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, localSlots, visible);
	}

	private static final class World implements InventoryMinecraftSources.SourceWorld {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
		final java.util.Set<BlockPos> loaded = new java.util.HashSet<>();
		@Override public boolean isLoaded(BlockPos pos) { return loaded.contains(pos); }
		@Override public BlockState blockState(BlockPos pos) { return blocks.get(pos); }
		@Override public BlockEntity blockEntity(BlockPos pos) { return blockEntities.get(pos); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
		@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
		void put(BlockPos pos, BlockState state, BlockEntity entity) {
			blocks.put(pos.immutable(), state);
			blockEntities.put(pos.immutable(), entity);
			loaded.add(pos.immutable());
		}
		Chest addChest(BlockPos pos, BlockState state, net.minecraft.world.item.Item item, int count) {
			Chest chest = new Chest(pos, state);
			chest.setItem(0, new ItemStack(item, count));
			chest.resetCounters();
			put(pos, state, chest);
			return chest;
		}
	}

	private static class Chest extends ChestBlockEntity implements BaseContainerLockAccessor {
		LockCode lock = LockCode.NO_LOCK;
		int itemReads;
		final AtomicInteger saves = new AtomicInteger();
		final AtomicInteger implicitComponentCollections = new AtomicInteger(), nonEmptySaves = new AtomicInteger();
		Runnable afterSave;
		Chest(BlockPos pos, BlockState state) { super(pos, state); }
		@Override public LockCode pingforit$inventoryLock() { return lock; }
		@Override public ItemStack getItem(int slot) { itemReads++; return super.getItem(slot); }
		@Override protected void collectImplicitComponents(DataComponentMap.Builder components) {
			implicitComponentCollections.incrementAndGet();
			super.collectImplicitComponents(components);
		}
		@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			saves.incrementAndGet();
			super.saveAdditional(tag, registries);
			if (tag.contains("Items")) nonEmptySaves.incrementAndGet();
			if (afterSave != null) afterSave.run();
		}
		void resetCounters() {
			itemReads = 0;
			saves.set(0);
			implicitComponentCollections.set(0);
			nonEmptySaves.set(0);
		}
	}

	private static final class MalformedChest extends Chest {
		MalformedChest(BlockPos pos, BlockState state) { super(pos, state); }
		@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			tag.put("Items", StringTag.valueOf("not a list"));
		}
	}

	private static final class AmbiguousChest extends Chest {
		AmbiguousChest(BlockPos pos, BlockState state) { super(pos, state); }
		@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			CompoundTag wrapper = new CompoundTag();
			wrapper.put("Items", new ListTag());
			tag.put("Inventory", wrapper);
		}
	}

	private static final class SidedChest extends Chest implements WorldlyContainer {
		int[] mapping = {3, 1};
		SidedChest(BlockPos pos, BlockState state) { super(pos, state); }
		@Override public int[] getSlotsForFace(Direction face) { return mapping.clone(); }
		@Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction face) { return false; }
		@Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction face) { return false; }
	}

	private static final class Access implements IPlatformInventoryService.Access {
		final int slots;
		final Optional<String> alias;
		final InventorySnapshotLayout layout;
		final AtomicInteger reads = new AtomicInteger();
		Access(int slots, Optional<String> alias, InventorySnapshotLayout layout) { this.slots = slots; this.alias = alias; this.layout = layout; }
		@Override public Optional<String> alias() { return alias; }
		@Override public Optional<InventorySnapshotLayout> snapshotLayout() { return Optional.ofNullable(layout); }
		@Override public boolean valid() { return true; }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return slots; }
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public IPlatformInventoryService.Entry read(int slot) {
			reads.incrementAndGet();
			return new IPlatformInventoryService.Entry(new ItemStack(Items.STONE), 1);
		}
		@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { return true; }
	}
}
