package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real common scope plus the actual NeoForge HandlerAccess/InvWrapper read boundary. */
@NotNullByDefault
class ResidentScopeReadBoundaryLoaderTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@NotNullByDefault
	private static final class CountingBacking extends SimpleContainer {
		int reads;
		CountingBacking() { super(1); }
		@Override public ItemStack getItem(int slot) { reads++; return super.getItem(slot); }
	}

	@NotNullByDefault
	private static final class RemovalHookRoot extends BeehiveBlockEntity {
		private @Nullable Runnable hook;
		RemovalHookRoot(BlockPos pos, BlockState state) { super(pos, state); }
		void arm(Runnable value) { hook = value; }
		@Override public boolean isRemoved() {
			boolean before = super.isRemoved();
			@Nullable Runnable pending = hook;
			hook = null;
			if (pending != null) pending.run();
			return before;
		}
	}

	@NotNullByDefault
	private static final class HookWrapper extends InvWrapper {
		private final RemovalHookRoot root;
		int calls, armAt = Integer.MAX_VALUE;
		Runnable mutation = () -> {};
		HookWrapper(Container backing, RemovalHookRoot root) { super(backing); this.root = root; }
		@Override public Container getInv() {
			if (++calls == armAt) root.arm(mutation);
			return super.getInv();
		}
	}

	@NotNullByDefault
	private static final class RootWorld implements InventoryMinecraftSources.SourceWorld {
		private RemovalHookRoot root;
		boolean allowed = true, owner = true;
		int metadata, keys;
		RootWorld(RemovalHookRoot root) { this.root = root; }
		@Override public boolean isLoaded(BlockPos pos) { metadata++; return allowed; }
		@Override public BlockState blockState(BlockPos pos) { metadata++; return root.getBlockState(); }
		@Override public net.minecraft.world.level.block.entity.BlockEntity blockEntity(BlockPos pos) { metadata++; return root; }
		@Override public boolean onServerThread() { return true; }
		@Override public @Nullable ItemStack heldKey() { keys++; return owner ? ItemStack.EMPTY : null; }
		@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
	}

	private static IPlatformInventoryService.Access access(HookWrapper wrapper) throws Exception {
		Class<?> handler = Class.forName("nx.pingwheel.neoforge.platform.PlatformInventoryServiceImpl$HandlerAccess");
		Constructor<?> constructor = handler.getDeclaredConstructor(IItemHandler.class, BlockPos.class, Predicate.class);
		constructor.setAccessible(true);
		return (IPlatformInventoryService.Access) constructor.newInstance(wrapper, BlockPos.ZERO, (Predicate<BlockPos>) ignored -> true);
	}
	private static InventorySourceInput input() {
		return new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:beehive"),
			new UUID(29, 1), BlockFace.NORTH);
	}

	@Test void actualWrapperRejectsRevocationInsideAuthorityAfterEachGetInvBeforeContent() throws Exception {
		for (int stage = 1; stage <= 3; stage++) for (String change : List.of("remove", "identity", "owner", "scope", "reentry")) {
			var backing = new CountingBacking();
			backing.setItem(0, new ItemStack(Items.STONE, 3));
			var root = new RemovalHookRoot(BlockPos.ZERO, Blocks.BEEHIVE.defaultBlockState());
			var world = new RootWorld(root);
			var wrapper = new HookWrapper(backing, root);
			var access = access(wrapper);
			var view = InventoryMinecraftSources.view(world, input(), (pos, face) -> Optional.of(access));
			var scoped = java.util.Objects.requireNonNull(view.access());
			wrapper.calls = 0; wrapper.armAt = stage;
			wrapper.mutation = switch (change) {
				case "remove" -> root::setRemoved;
				case "identity" -> () -> world.root = new RemovalHookRoot(BlockPos.ZERO, Blocks.BEEHIVE.defaultBlockState());
				case "owner" -> () -> world.owner = false;
				case "scope" -> () -> world.allowed = false;
				default -> () -> { root.setRemoved(); assertThrows(IllegalStateException.class, () -> scoped.readResident(0)); };
			};
			assertThrows(IllegalStateException.class, () -> scoped.readResident(0), change + " at getInv " + stage);
			if (!change.equals("reentry")) assertEquals(stage, wrapper.calls, "must reach the selected callback, not fail during setup");
			assertEquals(0, backing.reads, change + " must refuse before content, not only discard afterwards");
		}
	}

	@Test void refusedCallerAllowanceDoesNotInvokeWorldKeyOrNativeWrapperAndPaidPrefixIsNotRefunded() throws Exception {
		var backing = new CountingBacking();
		var root = new RemovalHookRoot(BlockPos.ZERO, Blocks.BEEHIVE.defaultBlockState());
		var world = new RootWorld(root);
		var wrapper = new HookWrapper(backing, root);
		var access = access(wrapper);
		var input = input();
		var view = InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.of(access));
		try (var source = new InventoryMinecraftSources.Source(input, view, () -> true,
			() -> InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.of(access)), entry -> null, world, null)) {
			var cost = source.evidenceValidationCost();
			var refused = new InventoryValidationBudget.Allowance(new InventoryValidationBudget.Cost(
				cost.allocationBytes(), cost.workspaceBytes(), cost.workUnits() - 1));
			world.metadata = 0; world.keys = 0; wrapper.calls = 0;
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> source.valid(refused));
			assertEquals(0, world.metadata); assertEquals(0, world.keys); assertEquals(0, wrapper.calls); assertEquals(0, backing.reads);
			var prefix = new InventoryValidationBudget.Allowance(cost);
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> source.read(0, null, prefix));
			assertEquals(cost.workUnits(), prefix.attemptedWorkUnits(), "the admitted validation prefix stays paid on read defer");
			assertTrue(world.metadata > 0); assertTrue(world.keys > 0); assertEquals(0, backing.reads);
			int calls = wrapper.calls, metadata = world.metadata, keys = world.keys;
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> source.valid(prefix));
			assertEquals(calls, wrapper.calls); assertEquals(metadata, world.metadata); assertEquals(keys, world.keys);
		}
	}
}
