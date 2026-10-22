package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotSchemas;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreateVaultInventoryAccessTest {
	private static final nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess RAW = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess() {
		@Override public java.util.List<?> nonNullList(net.minecraft.core.NonNullList<?> value) {
			try { var field = net.minecraft.core.NonNullList.class.getDeclaredField("list"); field.setAccessible(true); return (java.util.List<?>) field.get(value); }
			catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
	};
	@Test void residentLocalHandlersNeverSerializeAndOnContentsChangedOnlyOverridesQualify() {
		class Local extends net.neoforged.neoforge.items.ItemStackHandler {
			Local() { super(1); }
			@Override protected void onContentsChanged(int slot) { /* same write-only override as tested Vault */ }
			@Override public net.minecraft.nbt.CompoundTag serializeNBT(net.minecraft.core.HolderLookup.Provider registries) { throw new AssertionError("serialize trap"); }
		}
		World world = World.pair(); var first = new Local(); var second = new Local();
		first.setStackInSlot(0, new ItemStack(Items.STONE, 2)); second.setStackInSlot(0, new ItemStack(Items.DIRT, 5));
		world.members.get(BlockPos.ZERO).handler = first; world.members.get(new BlockPos(1, 0, 0)).handler = second;
		assertThrows(AssertionError.class, () -> first.serializeNBT(net.minecraft.core.RegistryAccess.EMPTY)); // positive control
		var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		assertTrue(access.supportsDetachedSlotCapture());
		assertSame(first.getStackInSlot(0), ((nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentItem.Stack) access.readResident(0).item()).resident());
		assertEquals(5, access.readResident(1).amount());
		world.members.remove(new BlockPos(1, 0, 0)); assertThrows(IllegalStateException.class, () -> access.readResident(0));
	}
	@Test void residentHandoffRechecksEveryMemberAfterTheLastContentGetter() {
		World world = World.pair(); var allowed = new java.util.HashSet<>(world.members.keySet());
		var other = new BlockPos(1, 0, 0);
		world.members.get(BlockPos.ZERO).handler = new Handler(1) {
			@Override public ItemStack getStackInSlot(int slot) { allowed.remove(other); return new ItemStack(Items.STONE); }
		};
		var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH, allowed::contains).orElseThrow();
		assertThrows(IllegalStateException.class, () -> access.readResident(0));
	}
	@Test void threeLayoutsPerSlotAndFixedDiscoveryPassesConsumeTheAdmittedAllocation() {
		World world = World.pair();
		for (var member : world.members.values()) member.handler = new net.neoforged.neoforge.items.ItemStackHandler(2);
		var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		var layout = access.snapshotLayout().orElseThrow();
		world.controllerReads = 0;
		var allowance = new InventoryValidationBudget.Allowance(
			InventoryValidationBudget.discovery(layout, true, true, 0).plus(InventoryValidationBudget.capture(layout, true, true, 0)));
		// Independent source sequence: four validations (4 layouts each), three
		// fixed supports calls (2 each), then n supports+read handoffs (3 each).
		for (int pass = 0; pass < 4; pass++) {
			allowance.debit(InventoryValidationBudget.view(layout, true));
			for (int call = 0; call < 4; call++) assertTrue(access.valid());
		}
		for (int pass = 0; pass < 3; pass++) {
			allowance.debit(InventoryValidationBudget.supported(layout, true, true));
			assertTrue(access.supportsDetachedSlotCapture());
		}
		for (int slot = 0; slot < access.slots(); slot++) {
			allowance.debit(InventoryValidationBudget.read(layout, true, true));
			assertTrue(access.supportsDetachedSlotCapture()); access.readResident(slot);
		}
		assertEquals(3 * 4 + 22, world.controllerReads, "each layout independently visits the master isController once");
		assertTrue(allowance.attemptedAllocationBytes() >= world.controllerReads * InventoryValidationBudget.vaultLayout(2).allocationBytes());
		long before = world.controllerReads;
		var tiny = new InventoryValidationBudget.Allowance(new InventoryValidationBudget.Cost(0, Long.MAX_VALUE, Long.MAX_VALUE));
		assertThrows(IllegalStateException.class, () -> {
			tiny.debit(InventoryValidationBudget.supported(layout, true, true)); access.supportsDetachedSlotCapture();
		});
		assertEquals(before, world.controllerReads, "refusal happens before the next layout, even in capability preflight");
		assertTrue(access.supportsDetachedSlotCapture());
		assertEquals(before + 2, world.controllerReads, "positive control: the same preflight allocates twice without the debit");
	}
	@Test void reusableResidentReachesTheSynchronousConsumerBeforeAnyHandlerMetadataCallback() {
		World world = World.pair();
		class Reusing extends Handler {
			final ItemStack scratch = new ItemStack(Items.STONE, 7);
			int metadataCalls, metadataAtRead; boolean returned;
			Reusing() { super(1); }
			@Override public int getSlots() { metadataCalls++; if (returned) scratch.setCount(0); return 1; }
			@Override public ItemStack getStackInSlot(int slot) {
				scratch.setCount(7); returned = true; metadataAtRead = metadataCalls; return scratch;
			}
		}
		var handler = new Reusing(); world.members.get(BlockPos.ZERO).handler = handler;
		var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		assertFalse(access.supportsDetachedSlotCapture(), "the overridden getter remains LIVE/EXTERNAL_TRUST");
		ItemStack[] consumed = new ItemStack[1];
		var observed = access.observeResident(1, entry -> {
			assertEquals(handler.metadataAtRead, handler.metadataCalls, "no provider metadata may run before synchronous consumption/freezing");
			assertEquals(7L, entry.amount());
			var resident = ((nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentItem.Stack) entry.item()).resident();
			assertSame(handler.scratch, resident); assertEquals(7, resident.getCount());
			consumed[0] = resident.copy(); // detached stand-in for the common freezer, tested separately
			return true;
		});
		assertEquals(1, observed.visited());
		assertTrue(access.valid(), "all-member topology validation still runs after consumption");
		assertTrue(handler.metadataCalls > handler.metadataAtRead);
		assertTrue(handler.scratch.isEmpty(), "positive control: the next metadata callback reuses the temporary stack");
		assertEquals(7, consumed[0].getCount()); assertSame(Items.STONE, consumed[0].getItem());
		world.members.get(new BlockPos(1, 0, 0)).controller = new BlockPos(9, 0, 0);
		assertFalse(access.valid(), "a final all-member relation change must still reject publication");
	}
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	static class Handler implements IItemHandler {
		final ItemStack[] stacks;
		Handler(int slots) { stacks = new ItemStack[slots]; java.util.Arrays.fill(stacks, ItemStack.EMPTY); }
		void setStackInSlot(int slot, ItemStack stack) { stacks[slot] = stack; }
		@Override public int getSlots() { return stacks.length; }
		@Override public ItemStack getStackInSlot(int slot) { return stacks[slot]; }
		@Override public int getSlotLimit(int slot) { return 64; }
		@Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
		@Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { throw new AssertionError("read-only"); }
		@Override public ItemStack extractItem(int slot, int amount, boolean simulate) { throw new AssertionError("read-only"); }
	}
	static class Member implements CreateVaultInventoryAccess.Member {
		BlockPos controller = BlockPos.ZERO; int width = 1, length = 2; Direction.Axis axis = Direction.Axis.X;
		IItemHandler handler = new Handler(1); String block = "create:item_vault"; boolean master; int localCalls;
		@Override public String blockId() { return block; }
		@Override public BlockPos controller() { return controller; }
		@Override public boolean isController() { return master; }
		@Override public int width() { return width; }
		@Override public int length() { return length; }
		@Override public Direction.Axis axis() { return axis; }
		@Override public IItemHandler local() { localCalls++; return handler; }
		@Override public boolean auditedLocal(IItemHandler handler) { return CreateVaultInventoryAccess.nativeLocal(handler, RAW); }
	}
	static class World implements CreateVaultInventoryAccess.World {
		final Map<BlockPos, Member> members = new HashMap<>(); int probes, controllerReads;
		final List<BlockPos> loadedPositions = new java.util.ArrayList<>();
		java.util.function.Consumer<BlockPos> afterLoaded = ignored -> {};
		@Override public String dimension() { return "minecraft:overworld"; }
		@Override public Member loaded(BlockPos pos) {
			probes++; loadedPositions.add(pos); Member member = members.get(pos); if (member == null) throw new InventorySourceUnavailableException("unloaded"); afterLoaded.accept(pos); return member;
		}
		static World pair() { var world = new World(); var first = new Member() {
			@Override public boolean isController() { world.controllerReads++; return super.isController(); }
		}; first.master = true; world.members.put(BlockPos.ZERO, first); world.members.put(new BlockPos(1, 0, 0), new Member()); return world; }
	}
	@Test void aliasesAreCanonicalFromEitherHalfAndEveryReadReacquiresLocalInventory() {
		World world = World.pair(); var first = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		var second = CreateVaultInventoryAccess.find(world, new BlockPos(1, 0, 0), Direction.SOUTH).orElseThrow();
		assertEquals(first.alias(), second.alias()); assertEquals(2, first.slots()); assertTrue(first.stableCursor()); assertTrue(first.version().isEmpty());
		var member = world.members.get(new BlockPos(1, 0, 0)); var live = new Handler(1); live.setStackInSlot(0, new ItemStack(Items.STONE, 17)); member.handler = live;
		var entry = first.read(1); assertEquals(17, entry.amount()); assertEquals(1, entry.exemplar().getCount());
		entry.exemplar().setCount(50); assertEquals(17, live.getStackInSlot(0).getCount(), "read detaches provider state");
		world.members.remove(new BlockPos(1, 0, 0)); assertFalse(first.valid()); assertThrows(IllegalStateException.class, () -> first.read(0));
	}
	@Test void unloadedAndMalformedTopologyFailsBeforeAnyLocalSegmentIsRequested() {
		World world = World.pair(); world.members.remove(new BlockPos(1, 0, 0));
		assertTrue(CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).isEmpty()); assertEquals(0, world.members.get(BlockPos.ZERO).localCalls);
		world = World.pair(); world.members.get(BlockPos.ZERO).width = 4;
		assertTrue(CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).isEmpty()); assertEquals(0, world.members.get(BlockPos.ZERO).localCalls);
		world = World.pair(); world.members.get(new BlockPos(1, 0, 0)).controller = new BlockPos(8, 0, 0);
		assertTrue(CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).isEmpty()); assertEquals(0, world.members.get(BlockPos.ZERO).localCalls);
		assertTrue(CreateVaultInventoryAccess.find(world, BlockPos.ZERO, null).isEmpty());
	}
	@Test void maximum81MemberTopologyStaysBoundedAndPerSegmentChangesInvalidateEvenWithSameTotal() {
		World world = new World();
		for (int x = 0; x < 9; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) {
			var member = new Member(); member.width = 3; member.length = 9; member.master = x == 0 && y == 0 && z == 0;
			world.members.put(new BlockPos(x, y, z), member);
		}
		var access = CreateVaultInventoryAccess.find(world, new BlockPos(8, 2, 2), Direction.UP).orElseThrow();
		assertEquals(81, access.slots()); assertEquals(164, world.probes);
		world.members.get(BlockPos.ZERO).handler = new Handler(0);
		world.members.get(new BlockPos(1, 0, 0)).handler = new Handler(2);
		assertFalse(access.valid(), "stable cursor requires segment boundaries, not only total slots");
	}
	@Test void snapshotLayoutPreservesMemberOrderControllerAndLocalSlotMappings() {
		World world = World.pair();
		world.members.get(BlockPos.ZERO).handler = new Handler(2);
		world.members.get(new BlockPos(1, 0, 0)).handler = new Handler(3);
		var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		var layout = access.snapshotLayout().orElseThrow();
		assertEquals(access.alias().orElseThrow(), layout.layoutId());
		assertEquals("axis=X;width=1;length=2", layout.layoutData());
		assertEquals(BlockPos.ZERO, layout.controller());
		assertEquals(List.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), layout.members().stream().map(InventorySnapshotLayout.Member::position).toList());
		assertEquals(List.of("controller", "member"), layout.members().stream().map(InventorySnapshotLayout.Member::role).toList());
		assertEquals(List.of("create:item_vault", "create:item_vault"), layout.members().stream().map(InventorySnapshotLayout.Member::blockEntityId).toList());
		var first = layout.members().get(0).segments().get(0);
		var second = layout.members().get(1).segments().get(0);
		assertEquals("Inventory.Items", first.fieldPath());
		assertEquals(InventorySnapshotSchemas.ITEMS, first.schemaId());
		assertEquals(2, first.localSlots());
		assertEquals(List.of(0, 1), first.visibleSlots());
		assertEquals(3, second.localSlots());
		assertEquals(List.of(0, 1, 2), second.visibleSlots(), "visible indices remain member-local rather than global");
	}
	@Test void invalidCurrentLayoutDoesNotExposeSnapshotDescriptor() {
		World world = World.pair();
		var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		world.members.get(BlockPos.ZERO).width = 2;
		assertTrue(access.snapshotLayout().isEmpty());
	}
	@Test void providerScopeGatesRootControllerAndEveryMemberBeforeWorldOrLocalReads() {
		World root = World.pair();
		assertTrue(CreateVaultInventoryAccess.find(root, BlockPos.ZERO, Direction.NORTH, pos -> false).isEmpty());
		assertEquals(0, root.probes);
		World controller = World.pair(); BlockPos hit = new BlockPos(1, 0, 0);
		assertTrue(CreateVaultInventoryAccess.find(controller, hit, Direction.NORTH, pos -> pos.equals(hit)).isEmpty());
		assertEquals(List.of(hit), controller.loadedPositions, "denied controller is never loaded");
		assertEquals(0, controller.members.values().stream().mapToInt(member -> member.localCalls).sum());
		World member = World.pair();
		assertTrue(CreateVaultInventoryAccess.find(member, BlockPos.ZERO, Direction.NORTH, pos -> pos.equals(BlockPos.ZERO)).isEmpty());
		assertFalse(member.loadedPositions.contains(hit), "foreign member BE is never loaded");
		assertEquals(0, member.members.values().stream().mapToInt(part -> part.localCalls).sum(), "all structure gates precede first local content");
	}
	@Test void repeatedReadsAndSnapshotLayoutCannotUsePreviouslyAdmittedScopeAfterRevocation() {
		World world = World.pair(); var allowed = new java.util.HashSet<>(world.members.keySet());
		var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH, allowed::contains).orElseThrow();
		assertEquals(0, access.read(0).amount()); assertTrue(access.snapshotLayout().isPresent());
		int localCalls = world.members.values().stream().mapToInt(member -> member.localCalls).sum();
		allowed.remove(new BlockPos(1, 0, 0)); world.loadedPositions.clear();
		assertFalse(access.valid()); assertTrue(access.snapshotLayout().isEmpty());
		assertThrows(IllegalStateException.class, () -> access.read(0));
		assertThrows(IllegalStateException.class, () -> access.read(1));
		assertThrows(IllegalStateException.class, () -> access.observe(2));
		assertFalse(world.loadedPositions.contains(new BlockPos(1, 0, 0)));
		assertEquals(localCalls, world.members.values().stream().mapToInt(member -> member.localCalls).sum());
	}
	@Test void revocationInsideWorldLookupOrLocalAcquisitionIsRecheckedBeforeNextMemberOrHandlerRead() {
		World world = World.pair(); boolean[] allowed = {true};
		world.afterLoaded = ignored -> allowed[0] = false;
		assertTrue(CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH, pos -> allowed[0]).isEmpty());
		assertEquals(1, world.probes); assertEquals(0, world.members.get(BlockPos.ZERO).localCalls);
		World local = World.pair(); allowed[0] = true; var slotReads = new java.util.concurrent.atomic.AtomicInteger();
		local.members.put(BlockPos.ZERO, new Member() {
			{ master = true; }
			@Override public IItemHandler local() {
				allowed[0] = false;
				return new Handler(1) { @Override public int getSlots() { slotReads.incrementAndGet(); return super.getSlots(); } };
			}
		});
		assertTrue(CreateVaultInventoryAccess.find(local, BlockPos.ZERO, Direction.NORTH, pos -> allowed[0]).isEmpty());
		assertEquals(0, slotReads.get(), "local handler cannot be read after its member scope is revoked");
	}
}
