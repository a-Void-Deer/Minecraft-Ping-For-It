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
import net.neoforged.neoforge.items.ItemStackHandler;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotSchemas;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.diagnostics.Diagnostics;

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
		default boolean auditedLocal(IItemHandler handler) { return nativeLocal(handler); }
	}
	/** Per-operation scope checks, including reads through an already acquired member or local handler. */
	private record GatedWorld(World delegate, Predicate<BlockPos> memberGate) implements World {
		void require(BlockPos pos) {
			if (pos == null || !memberGate.test(pos)) throw new InventorySourceUnavailableException("Vault member outside the read scope");
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
		@Override public boolean auditedLocal(IItemHandler handler) {
			world.require(pos); return delegate.auditedLocal(handler instanceof GatedHandler gated ? gated.delegate() : handler);
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
	private static boolean nativeLocal(IItemHandler handler) {
		return nativeLocal(handler, nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess.load());
	}
	static boolean nativeLocal(IItemHandler handler, nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess raw) {
		return handler instanceof ItemStackHandler && localOwnership.get(handler.getClass())
			&& IPlatformInventoryService.ResidentReads.nativeStacks(IPlatformInventoryService.ResidentReads.field(handler,
				ItemStackHandler.class, "stacks", net.minecraft.core.NonNullList.class), raw);
	}
	/** Only declaration ownership is class-stable. Backing storage, scope and current local handler are always rechecked. */
	private static final ClassValue<Boolean> localOwnership = new ClassValue<>() {
		@Override protected Boolean computeValue(Class<?> type) {
			return inherits(type, "getSlots") && inherits(type, "getStackInSlot", int.class) && inherits(type, "validateSlotIndex", int.class);
		}
		private boolean inherits(Class<?> type, String name, Class<?>... parameters) {
			@org.jetbrains.annotations.Nullable Class<?> current = type;
			for (int depth = 0; current != null && depth < 32; depth++, current = current.getSuperclass()) {
				if (!IPlatformInventoryService.ResidentReads.reflectionShape(current, name, parameters)) return false;
				try { return current.getDeclaredMethod(name, parameters).getDeclaringClass() == ItemStackHandler.class; }
				catch (NoSuchMethodException absent) { /* bounded parent walk */ }
				catch (SecurityException unavailable) { return false; }
			}
			return false;
		}
	};
	static Optional<IPlatformInventoryService.Access> find(World world, BlockPos original, Direction face) {
		if (face == null) return Optional.empty();
		try { return Optional.of(new Segmented(world, original, layout(world, original))); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
			failure("inventory-vault-find", world, original, () -> "face=" + face, unavailable);
			return Optional.empty();
		}
	}
	private static void failure(String operation, @org.jetbrains.annotations.Nullable World world, BlockPos original, java.util.function.Supplier<String> detail, Throwable failure) {
		@org.jetbrains.annotations.Nullable Throwable reason = failure instanceof java.lang.reflect.InvocationTargetException ? failure.getCause() : failure;
		if (reason instanceof InventorySourceUnavailableException) return;
		Diagnostics.warn(operation, "create:item_vault",
			() -> "position=" + original + " worldClass=" + (world == null ? "none" : world.getClass().getName()) + " " + detail.get(), failure);
	}
	private record Layout(BlockPos controller, int width, int length, Direction.Axis axis,
		@org.jetbrains.annotations.Unmodifiable List<BlockPos> members, @org.jetbrains.annotations.Unmodifiable List<Integer> slotCounts, int slots) {}
	private static Object call(BlockEntity entity, String name) throws ReflectiveOperationException {
		IPlatformInventoryService.ResidentReads.requireReflectionShape(entity.getClass(), name);
		return entity.getClass().getMethod(name).invoke(entity);
	}
	private record MinecraftWorld(ServerLevel level) implements World {
		@Override public String dimension() { return level.dimension().location().toString(); }
		@Override public Member loaded(BlockPos pos) {
			if (!level.isLoaded(pos)) throw new InventorySourceUnavailableException("Vault member unloaded");
			BlockEntity entity = level.getBlockEntity(pos);
			if (!recognizes(entity) || entity.isRemoved()) throw new InventorySourceUnavailableException("Vault member unavailable");
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
		if (controller == null) throw new InventorySourceUnavailableException("Vault controller unavailable");
		Member master = world.loaded(controller); // before any controller-BE or aggregate call
		if (!master.isController() || !controller.equals(master.controller()) || !"create:item_vault".equals(master.blockId())) throw new InventorySourceUnavailableException("Vault controller relation");
		int width = master.width(), length = master.length();
		Direction.Axis axis = master.axis();
		if (width < 1 || width > 3 || length < 1 || length > 3 * width || (axis != Direction.Axis.X && axis != Direction.Axis.Z))
			throw new InventorySourceUnavailableException("Vault bounded topology");
		List<BlockPos> members = new ArrayList<>(width * width * length);
		for (int x = 0; x < (axis == Direction.Axis.X ? length : width); x++) for (int y = 0; y < width; y++)
			for (int z = 0; z < (axis == Direction.Axis.Z ? length : width); z++) {
				BlockPos pos = controller.offset(x, y, z); Member member = world.loaded(pos);
				if (!member.blockId().equals(master.blockId()) || !controller.equals(member.controller())
					|| member.axis() != axis) throw new InventorySourceUnavailableException("Vault member relation");
				members.add(pos);
			}
		if (!members.contains(original)) throw new InventorySourceUnavailableException("original Vault outside controller");
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
	/** Compare the frozen positions and local boundaries without rebuilding lists, offsets or boxed mappings. */
	private static boolean matches(World world, BlockPos original, Layout expected, InventorySnapshotLayout frozenLayout) throws ReflectiveOperationException {
		Member part = world.loaded(original);
		if (!expected.controller.equals(part.controller())) return false;
		Member master = world.loaded(expected.controller);
		if (!master.isController() || !expected.controller.equals(master.controller())
			|| !"create:item_vault".equals(master.blockId()) || master.width() != expected.width
			|| master.length() != expected.length || master.axis() != expected.axis) return false;
		// All members pass their live relation/membership gates before any local handler is acquired.
		for (int index = 0; index < expected.members.size(); index++) {
			Member member = world.loaded(expected.members.get(index));
			if (!frozenLayout.members().get(index).blockId().equals(member.blockId())
				|| !frozenLayout.members().get(index).blockEntityId().equals(member.blockEntityId()) || !expected.controller.equals(member.controller())
				|| member.axis() != expected.axis) return false;
		}
		for (int index = 0; index < expected.members.size(); index++)
			if (world.loaded(expected.members.get(index)).local().getSlots() != expected.slotCounts.get(index)) return false;
		return true;
	}
	private static final class Segmented implements IPlatformInventoryService.Access {
		final World world; final BlockPos original; final Layout expected;
		final String alias;
		final InventorySnapshotLayout frozenLayout;
		final int @org.jetbrains.annotations.Unmodifiable [] offsets;
		Segmented(World world, BlockPos original, Layout expected) throws ReflectiveOperationException {
			this.world = world; this.original = original; this.expected = expected;
			alias = world.dimension() + "|vault|" + expected.controller.toShortString() + "|create:item_vault";
			List<InventorySnapshotLayout.Member> members = new ArrayList<>(expected.members.size());
			offsets = new int[expected.members.size() + 1];
			int absolute = 0;
			for (int index = 0; index < expected.members.size(); index++) {
				BlockPos position = expected.members.get(index); Member member = world.loaded(position);
				int count = expected.slotCounts.get(index);
				offsets[index] = absolute; absolute += count;
				var segment = new InventorySnapshotLayout.Segment("Inventory.Items", InventorySnapshotSchemas.ITEMS, count,
					java.util.stream.IntStream.range(0, count).boxed().toList());
				members.add(new InventorySnapshotLayout.Member(position, member.blockId(), member.blockEntityId(),
					position.equals(expected.controller) ? "controller" : "member", List.of(segment)));
			}
			offsets[expected.members.size()] = absolute;
			frozenLayout = new InventorySnapshotLayout(alias, "axis=" + expected.axis.name() + ";width=" + expected.width + ";length=" + expected.length,
				expected.controller, members);
		}
		@Override public Optional<String> alias() { return Optional.of(alias); }
		/** Local handlers are validated here, not through generic Container scope evidence. */
		@Override public IPlatformInventoryService.Access scopedResidents(
			java.util.function.Supplier<? extends IPlatformInventoryService.ResidentScope> factory) {
			java.util.Objects.requireNonNull(factory, "factory");
			return this;
		}
		@Override public boolean valid() {
			try { return matches(world, original, expected, frozenLayout); }
			catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
				failure("inventory-vault-valid", world, original, () -> "expected=" + expected, unavailable);
				return false;
			}
		}
		@Override public Optional<InventorySnapshotLayout> snapshotLayout() {
			return valid() ? Optional.of(frozenLayout) : Optional.empty();
		}
		@Override public int slots() { return expected.slots; }
		private int memberIndex(int slot) {
			// Upper bound keeps empty segments from stealing a slot at the same prefix offset.
			int low = 0, high = expected.members.size();
			while (low < high) { int middle = (low + high) >>> 1; if (offsets[middle + 1] <= slot) low = middle + 1; else high = middle; }
			return low;
		}
		@Override public boolean stableCursor() { return true; }
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public boolean supportsDetachedSlotCapture() {
			if (!valid()) return false;
			try {
				for (BlockPos pos : expected.members()) { Member member = world.loaded(pos); if (!member.auditedLocal(member.local())) return false; }
				return valid();
			} catch (ReflectiveOperationException failure) { throw new IllegalStateException("Vault resident graph unavailable", failure); }
		}
		@Override public IPlatformInventoryService.ResidentEntry readResident(int slot) {
			@org.jetbrains.annotations.Nullable IPlatformInventoryService.ResidentEntry[] result = new IPlatformInventoryService.ResidentEntry[1];
			consumeResident(slot, entry -> { result[0] = entry; return true; });
			return java.util.Objects.requireNonNull(result[0]);
		}
		@Override public boolean consumeResident(int slot, IPlatformInventoryService.ResidentVisitor visitor) {
			java.util.Objects.requireNonNull(visitor, "visitor");
			if (!valid()) throw new InventorySourceUnavailableException("Vault topology changed");
			java.util.Objects.checkIndex(slot, slots());
			try {
				int index = memberIndex(slot), local = slot - offsets[index];
				int count = expected.slotCounts.get(index);
				BlockPos position = expected.members.get(index);
				Member member = world.loaded(position); IItemHandler live = member.local();
				member.auditedLocal(live); // classify before getter; false is LIVE/EXTERNAL_TRUST, not atomic
				if (live.getSlots() != count) throw new InventorySourceUnavailableException("Vault local mapping changed");
				requireMembersHeld(); // the final local metadata callback cannot authorize a now-revoked sibling
				var stack = live.getStackInSlot(local);
				boolean more = visitor.visit(IPlatformInventoryService.ResidentReads.stack(stack));
				// Even membership and metadata callbacks run only after synchronous detachment.
				requireMembersHeld();
				if (!valid()) throw new InventorySourceUnavailableException("Vault topology revoked during resident read");
				return more;
			} catch (ReflectiveOperationException failure) { throw new IllegalStateException("Vault local resident read unavailable", failure); }
		}
		/** Every frozen member position still resolves under the current member gate. */
		private void requireMembersHeld() {
			try {
				for (BlockPos position : expected.members()) world.loaded(position);
			} catch (InventorySourceUnavailableException unavailable) {
				throw unavailable;
			} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
				throw new IllegalStateException("Vault authority or topology revoked during resident read", unavailable);
			}
		}
		@Override public IPlatformInventoryService.Entry read(int slot) {
			if (!valid()) throw new InventorySourceUnavailableException("Vault topology changed");
			java.util.Objects.checkIndex(slot, slots());
			try {
				int index = memberIndex(slot), local = slot - offsets[index];
				int count = expected.slotCounts.get(index);
				IItemHandler live = world.loaded(expected.members.get(index)).local();
				if (live.getSlots() != count) throw new InventorySourceUnavailableException("Vault mapping changed");
				var stack = live.getStackInSlot(local);
				return stack.isEmpty() ? IPlatformInventoryService.Entry.empty() : new IPlatformInventoryService.Entry(stack.copyWithCount(1), stack.getCount());
			} catch (ReflectiveOperationException failure) { throw new IllegalStateException("Vault local read unavailable", failure); }
		}
		@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> consumer) {
			for (int i = 0; i < Math.min(limit, slots()); i++) consumer.accept(read(i)); return slots() <= limit;
		}
	}
}
