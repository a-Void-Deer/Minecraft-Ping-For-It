package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.LockCode;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.platform.IPlatformInventoryService.Access;
import nx.pingwheel.common.platform.IPlatformInventoryService.Entry;
import nx.pingwheel.common.platform.IPlatformInventoryService.ResidentScope;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Current authority is not frozen with the resident membership/mapping. */
@org.jetbrains.annotations.NotNullByDefault
class ResidentScopeReadBoundaryTest {
	private static final BlockPos ROOT = BlockPos.ZERO;
	private static final BlockPos MEMBER = new BlockPos(1, 0, 0);

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@org.jetbrains.annotations.NotNullByDefault
	private static final class LockedChest extends ChestBlockEntity implements BaseContainerLockAccessor {
		private LockCode lock = LockCode.NO_LOCK;
		LockedChest(BlockPos pos, BlockState state) { super(pos, state); }
		@Override public LockCode pingforit$inventoryLock() { return lock; }
		void lock(LockCode value) { lock = value; }
	}

	@org.jetbrains.annotations.NotNullByDefault
	private static final class World implements InventoryMinecraftSources.SourceWorld {
		final Map<BlockPos, BlockEntity> entities = new HashMap<>();
		final Map<BlockPos, BlockState> states = new HashMap<>();
		int metadata;
		ItemStack key = new ItemStack(Items.STONE);
		@Override public boolean isLoaded(BlockPos pos) { metadata++; return entities.containsKey(pos) && states.containsKey(pos); }
		@Override public BlockState blockState(BlockPos pos) { metadata++; return java.util.Objects.requireNonNull(states.get(pos)); }
		@Override public @Nullable BlockEntity blockEntity(BlockPos pos) { metadata++; return entities.get(pos); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { return key; }
		@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
	}

	@org.jetbrains.annotations.NotNullByDefault
	private static final class CapturingAccess implements IPlatformInventoryService.Access {
		private final InventorySnapshotLayout layout;
		private @Nullable ResidentScope scope;
		CapturingAccess(InventorySnapshotLayout layout) { this.layout = layout; }
		@Override public Access scopedResidents(ResidentScope value) { scope = value; return this; }
		@Override public Optional<InventorySnapshotLayout> snapshotLayout() { return Optional.of(layout); }
		@Override public int slots() { return 27; }
		@Override public boolean stableCursor() { return true; }
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public Entry read(int slot) { return Entry.empty(); }
		@Override public boolean visit(int limit, Consumer<Entry> consumer) { return true; }
	}

	@Test void lookupBoundariesRetainCurrentAuthorityAndForeignDelegateRefusal() {
		var world = new World();
		var rootState = Blocks.BEEHIVE.defaultBlockState();
		var memberState = Blocks.CHEST.defaultBlockState();
		var root = new BeehiveBlockEntity(ROOT, rootState);
		world.states.put(ROOT, rootState); world.entities.put(ROOT, root);
		var chest = new LockedChest(MEMBER, memberState);
		world.states.put(MEMBER, memberState); world.entities.put(MEMBER, chest);
		var layout = new InventorySnapshotLayout("test:resident-scope", "north", ROOT, List.of(
			new InventorySnapshotLayout.Member(ROOT, "minecraft:beehive", "minecraft:beehive", "target", List.of()),
			new InventorySnapshotLayout.Member(MEMBER, "minecraft:chest", "minecraft:chest", "member", List.of(
				new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, 27,
					java.util.stream.IntStream.range(0, 27).boxed().toList())))));
		var access = new CapturingAccess(layout);
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:beehive"),
			new UUID(9, 1), BlockFace.NORTH);
		InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.of(access));
		var scope = java.util.Objects.requireNonNull(access.scope);

		int before = world.metadata;
		assertTrue(scope.valid());
		assertTrue(world.metadata > before, "explicit valid performs the current whole-source gate");

		before = world.metadata;
		assertTrue(scope.allows(chest));
		assertTrue(world.metadata > before, "the previous verdict cannot authorize a later membership lookup");

		before = world.metadata;
		assertEquals(27, java.util.Objects.requireNonNull(scope.slots(chest)).size());
		assertTrue(world.metadata > before, "mapping reuse does not reuse authority");

		var foreign = new SimpleContainer(1);
		assertFalse(scope.allows(foreign));
		assertThrows(InventorySourceUnavailableException.class, () -> scope.slots(foreign));
		assertTrue(scope.valid());
		world.entities.put(ROOT, new BeehiveBlockEntity(ROOT, rootState));
		assertFalse(scope.valid(), "a changed root member still closes the outer gate");
		world.entities.put(ROOT, root);
		world.entities.put(MEMBER, new LockedChest(MEMBER, memberState));
		assertFalse(scope.valid(), "a changed member identity still closes the outer gate");
		world.entities.put(MEMBER, chest);
		assertTrue(scope.valid());
		chest.lock(new LockCode("denied"));
		assertFalse(scope.valid(), "a current lock change still closes the outer gate");
		assertFalse(scope.allows(chest));
		assertNull(scope.slots(chest));
	}
}
