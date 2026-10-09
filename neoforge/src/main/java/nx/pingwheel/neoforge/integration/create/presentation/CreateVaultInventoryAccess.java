package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.items.IItemHandler;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotSchemas;

/** Lazy tested-shape Vault access; never asks Create to initialize its combined capability. */
public final class CreateVaultInventoryAccess {
	private static final String TYPE = "com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity";
	private CreateVaultInventoryAccess() {}
	public static boolean recognizes(BlockEntity entity) { return entity != null && TYPE.equals(entity.getClass().getName()); }
	public static Optional<IPlatformInventoryService.Access> find(ServerLevel level, BlockPos original, Direction face) {
		if (face == null || !CreatePresentationAvailability.available()) return Optional.empty();
		return find(new MinecraftWorld(level), original, face);
	}
	/** Root, controller and every member must pass the gate before its state, relation or local handler is read. */
	public static Optional<IPlatformInventoryService.Access> find(ServerLevel level, BlockPos original, Direction face,
		Predicate<BlockPos> memberGate) {
		if (face == null || memberGate == null || !CreatePresentationAvailability.available()) return Optional.empty();
		return find(new MinecraftWorld(level), original, face, memberGate);
	}
	static Optional<IPlatformInventoryService.Access> find(World world, BlockPos original, Direction face, Predicate<BlockPos> memberGate) {
		if (face == null || world == null || memberGate == null) return Optional.empty();
		return find(new GatedWorld(world, memberGate), original, face);
	}
	/** Same bounded topology and segmented read path with a detached test port, not a second provider. */
	interface World {
		String dimension();
		Member loaded(BlockPos pos) throws ReflectiveOperationException;
	}
	interface Member {
		String blockId();
		default String blockEntityId() { return "create:item_vault"; }
		BlockPos controller() throws ReflectiveOperationException;
		boolean isController() throws ReflectiveOperationException;
		int width() throws ReflectiveOperationException;
		int length() throws ReflectiveOperationException;
		Direction.Axis axis() throws ReflectiveOperationException;
		IItemHandler local() throws ReflectiveOperationException;
	}
	/** Per-operation scope checks, including reads through an already acquired member or local handler. */
	private record GatedWorld(World delegate, Predicate<BlockPos> memberGate) implements World {
		void require(BlockPos pos) {
			if (pos == null || !memberGate.test(pos)) throw new IllegalStateException("Vault member outside the read scope");
		}
		@Override public String dimension() { return delegate.dimension(); }
		@Override public Member loaded(BlockPos pos) throws ReflectiveOperationException {
			require(pos);
			return new GatedMember(this, pos.immutable(), delegate.loaded(pos));
		}
	}
	private record GatedMember(GatedWorld world, BlockPos pos, Member delegate) implements Member {
		@Override public String blockId() { world.require(pos); return delegate.blockId(); }
		@Override public String blockEntityId() { world.require(pos); return delegate.blockEntityId(); }
		@Override public BlockPos controller() throws ReflectiveOperationException { world.require(pos); return delegate.controller(); }
		@Override public boolean isController() throws ReflectiveOperationException { world.require(pos); return delegate.isController(); }
		@Override public int width() throws ReflectiveOperationException { world.require(pos); return delegate.width(); }
		@Override public int length() throws ReflectiveOperationException { world.require(pos); return delegate.length(); }
		@Override public Direction.Axis axis() throws ReflectiveOperationException { world.require(pos); return delegate.axis(); }
		@Override public IItemHandler local() throws ReflectiveOperationException {
			world.require(pos);
			return new GatedHandler(world, pos, delegate.local());
		}
	}
	private record GatedHandler(GatedWorld world, BlockPos pos, IItemHandler delegate) implements IItemHandler {
		@Override public int getSlots() { world.require(pos); return delegate.getSlots(); }
		@Override public net.minecraft.world.item.ItemStack getStackInSlot(int slot) { world.require(pos); return delegate.getStackInSlot(slot); }
		@Override public int getSlotLimit(int slot) { world.require(pos); return delegate.getSlotLimit(slot); }
		@Override public boolean isItemValid(int slot, net.minecraft.world.item.ItemStack stack) { world.require(pos); return delegate.isItemValid(slot, stack); }
		@Override public net.minecraft.world.item.ItemStack insertItem(int slot, net.minecraft.world.item.ItemStack stack, boolean simulate) {
			throw new UnsupportedOperationException("read-only Vault inventory");
		}
		@Override public net.minecraft.world.item.ItemStack extractItem(int slot, int amount, boolean simulate) {
			throw new UnsupportedOperationException("read-only Vault inventory");
		}
	}
	static Optional<IPlatformInventoryService.Access> find(World world, BlockPos original, Direction face) {
		if (face == null) return Optional.empty();
		try { return Optional.of(new Segmented(world, original, layout(world, original))); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return Optional.empty(); }
	}
	private record Layout(BlockPos controller, int width, int length, Direction.Axis axis, List<BlockPos> members, List<Integer> slotCounts, int slots) {}
	private static Object call(BlockEntity entity, String name) throws ReflectiveOperationException { return entity.getClass().getMethod(name).invoke(entity); }
	private record MinecraftWorld(ServerLevel level) implements World {
		@Override public String dimension() { return level.dimension().location().toString(); }
		@Override public Member loaded(BlockPos pos) {
			if (!level.isLoaded(pos)) throw new IllegalStateException("Vault member unloaded");
			BlockEntity entity = level.getBlockEntity(pos);
			if (!recognizes(entity) || entity.isRemoved()) throw new IllegalStateException("Vault member unavailable");
			return new MinecraftMember(entity);
		}
	}
	private record MinecraftMember(BlockEntity entity) implements Member {
		@Override public String blockId() { return BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString(); }
		@Override public String blockEntityId() { return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(); }
		@Override public BlockPos controller() throws ReflectiveOperationException { return (BlockPos) call(entity, "getController"); }
		@Override public boolean isController() throws ReflectiveOperationException { return (Boolean) call(entity, "isController"); }
		@Override public int width() throws ReflectiveOperationException { return (Integer) call(entity, "getWidth"); }
		@Override public int length() throws ReflectiveOperationException { return (Integer) call(entity, "getHeight"); }
		@Override public Direction.Axis axis() throws ReflectiveOperationException { return (Direction.Axis) call(entity, "getMainConnectionAxis"); }
		@Override public IItemHandler local() throws ReflectiveOperationException {
			Object inventory = call(entity, "getInventoryOfBlock");
			if (!(inventory instanceof IItemHandler handler)) throw new IllegalStateException("Vault local inventory shape");
			return handler;
		}
	}
	private static Layout layout(World world, BlockPos original) throws ReflectiveOperationException {
		Member part = world.loaded(original);
		BlockPos controller = part.controller();
		Member master = world.loaded(controller); // before any controller-BE or aggregate call
		if (!master.isController() || !controller.equals(master.controller()) || !"create:item_vault".equals(master.blockId())) throw new IllegalStateException("Vault controller relation");
		int width = master.width(), length = master.length();
		Direction.Axis axis = master.axis();
		if (width < 1 || width > 3 || length < 1 || length > 3 * width || (axis != Direction.Axis.X && axis != Direction.Axis.Z))
			throw new IllegalStateException("Vault bounded topology");
		List<BlockPos> members = new ArrayList<>(width * width * length);
		for (int x = 0; x < (axis == Direction.Axis.X ? length : width); x++) for (int y = 0; y < width; y++)
			for (int z = 0; z < (axis == Direction.Axis.Z ? length : width); z++) {
				BlockPos pos = controller.offset(x, y, z); Member member = world.loaded(pos);
				if (!member.blockId().equals(master.blockId()) || !controller.equals(member.controller())
					|| member.axis() != axis) throw new IllegalStateException("Vault member relation");
				members.add(pos);
			}
		if (!members.contains(original)) throw new IllegalStateException("original Vault outside controller");
		int slots = 0;
		List<Integer> counts = new ArrayList<>(members.size());
		for (BlockPos pos : members) {
			int count = world.loaded(pos).local().getSlots();
			if (count < 0 || count > 4096 - slots) throw new IllegalStateException("Vault finite slots");
			slots += count;
			counts.add(count);
		}
		return new Layout(controller, width, length, axis, List.copyOf(members), List.copyOf(counts), slots);
	}
	private static final class Segmented implements IPlatformInventoryService.Access {
		final World world; final BlockPos original; final Layout expected;
		Segmented(World world, BlockPos original, Layout expected) { this.world = world; this.original = original; this.expected = expected; }
		@Override public Optional<String> alias() { return Optional.of(world.dimension() + "|vault|" + expected.controller.toShortString() + "|create:item_vault"); }
		@Override public boolean valid() { try { return expected.equals(layout(world, original)); } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; } }
		@Override public Optional<InventorySnapshotLayout> snapshotLayout() {
			if (!valid()) return Optional.empty();
			try {
				List<InventorySnapshotLayout.Member> members = new ArrayList<>(expected.members.size());
				for (int index = 0; index < expected.members.size(); index++) {
					BlockPos position = expected.members.get(index);
					Member member = world.loaded(position);
					int localSlots = expected.slotCounts.get(index);
					List<Integer> visibleSlots = java.util.stream.IntStream.range(0, localSlots).boxed().toList();
					var segment = new InventorySnapshotLayout.Segment("Inventory.Items", InventorySnapshotSchemas.ITEMS, localSlots, visibleSlots);
					members.add(new InventorySnapshotLayout.Member(position, member.blockId(), member.blockEntityId(),
						position.equals(expected.controller) ? "controller" : "member", List.of(segment)));
				}
				String layoutData = "axis=" + expected.axis.name() + ";width=" + expected.width + ";length=" + expected.length;
				return Optional.of(new InventorySnapshotLayout(alias().orElseThrow(), layoutData, expected.controller, members));
			} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return Optional.empty(); }
		}
		@Override public int slots() { return expected.slots; }
		@Override public boolean stableCursor() { return true; }
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public IPlatformInventoryService.Entry read(int slot) {
			if (!valid()) throw new IllegalStateException("Vault topology changed");
			java.util.Objects.checkIndex(slot, slots());
			try {
				for (int index = 0; index < expected.members.size(); index++) {
					int count = expected.slotCounts.get(index);
					if (slot < count) {
						IItemHandler live = world.loaded(expected.members.get(index)).local();
						if (live.getSlots() != count) throw new IllegalStateException("Vault mapping changed");
						var stack = live.getStackInSlot(slot);
						return stack.isEmpty() ? IPlatformInventoryService.Entry.empty() : new IPlatformInventoryService.Entry(stack.copyWithCount(1), stack.getCount());
					}
					slot -= count;
				}
			} catch (ReflectiveOperationException failure) { throw new IllegalStateException("Vault local read unavailable", failure); }
			throw new IllegalStateException("Vault mapping changed");
		}
		@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> consumer) {
			for (int i = 0; i < Math.min(limit, slots()); i++) consumer.accept(read(i)); return slots() <= limit;
		}
	}
}
