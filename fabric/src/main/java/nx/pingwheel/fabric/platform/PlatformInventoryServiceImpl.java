package nx.pingwheel.fabric.platform;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.base.SingleStackStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.impl.transfer.item.ItemVariantImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.Items;

import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentItem;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.diagnostics.Diagnostics;

/**
 * Fabric Transfer provider for the platform inventory bridge.
 *
 * <p>A {@link SlottedStorage} keeps stable slot indices and exposes its
 * provider version; any other {@link Storage} is enumerated in bounded passes
 * with no stable cursor. Reads revisit the live storage and detach one
 * single-item exemplar plus the exact long amount. Pending loot is rejected
 * before the lookup so no wrapper can trigger generation, and the loaded-chunk
 * gate runs before any position lookup.
 */
public final class PlatformInventoryServiceImpl implements IPlatformInventoryService {

	@Override
	public Optional<Access> find(ServerLevel level, BlockPos pos, Direction side) {
		return find(level, pos, side, ignored -> true);
	}

	@Override
	public Optional<Access> find(ServerLevel level, BlockPos pos, Direction side, Predicate<BlockPos> memberGate) {
		Objects.requireNonNull(level, "level");
		return find(new MinecraftWorld(level), pos, side, memberGate);
	}

	/** Synchronous native-lookup port; the same gated path is used in production and tests. */
	interface World {
		boolean loaded(BlockPos pos);
		BlockEntity blockEntity(BlockPos pos);
		Storage<ItemVariant> storage(BlockPos pos, Direction side);
	}
	private record MinecraftWorld(ServerLevel level) implements World {
		@Override public boolean loaded(BlockPos pos) { return level.isLoaded(pos); }
		@Override public BlockEntity blockEntity(BlockPos pos) { return level.getBlockEntity(pos); }
		@Override public Storage<ItemVariant> storage(BlockPos pos, Direction side) { return ItemStorage.SIDED.find(level, pos, side); }
	}
	static Optional<Access> find(World world, BlockPos pos, Direction side, Predicate<BlockPos> memberGate) {
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(side, "side");
		Objects.requireNonNull(memberGate, "memberGate");
		if (!memberGate.test(pos) || !world.loaded(pos) || !memberGate.test(pos)) return Optional.empty();
		BlockEntity blockEntity = world.blockEntity(pos);
		if (!memberGate.test(pos)) return Optional.empty();
		if (blockEntity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) {
			return Optional.empty();
		}
		try {
			if (!memberGate.test(pos)) return Optional.empty();
			Storage<ItemVariant> storage = world.storage(pos, side);
			if (storage == null || !memberGate.test(pos)) return Optional.empty();
			BlockPos position = pos.immutable();
			BooleanSupplier gate = () -> memberGate.test(position);
			if (storage instanceof SlottedStorage<ItemVariant> slotted) {
				return Optional.of(new SlottedAccess(slotted, gate));
			}
			return Optional.of(new EnumeratedAccess(storage, gate));
		} catch (RuntimeException | LinkageError failure) {
			if (!(failure instanceof InventorySourceUnavailableException))
				Diagnostics.warn("inventory-capability-find", "fabric:transfer",
					() -> "position=" + pos + " face=" + side + " worldClass=" + world.getClass().getName()
						+ " blockEntityClass=" + (blockEntity == null ? "none" : blockEntity.getClass().getName()), failure);
			return Optional.empty();
		}
	}

	private static OptionalLong storageVersion(Storage<ItemVariant> storage) {
		try {
			return OptionalLong.of(storage.getVersion());
		} catch (RuntimeException | LinkageError failure) {
			if (!(failure instanceof InventorySourceUnavailableException))
				Diagnostics.warn("inventory-storage-version", storage.getClass().getName(),
					() -> "storageClass=" + storage.getClass().getName(), failure);
			return OptionalLong.empty();
		}
	}

	private static void requireMember(BooleanSupplier memberGate) {
		if (!memberGate.getAsBoolean()) throw new InventorySourceUnavailableException("inventory storage outside the read scope");
	}
	private static boolean containsLocal(java.util.List<Integer> mapping, int local) {
		for (int index = 0; index < mapping.size(); index++) if (mapping.get(index) == local) return true;
		return false;
	}
	private static Entry entry(StorageView<ItemVariant> view, BooleanSupplier memberGate) {
		requireMember(memberGate);
		if (view == null || view.isResourceBlank()) { requireMember(memberGate); return Entry.empty(); }
		requireMember(memberGate);
		long amount = view.getAmount();
		requireMember(memberGate);
		if (amount < 0) throw new IllegalStateException("provider reported a negative item amount");
		if (amount == 0) return Entry.empty();
		var resource = view.getResource();
		requireMember(memberGate);
		return new Entry(resource.toStack(1), amount);
	}

	/** Fixed library field identities, not a namespace-based provider allowlist. */
	private static final class NativeSlots {
		static final @org.jetbrains.annotations.Nullable Class<?> SIDED_SLOT = libraryClass("net.fabricmc.fabric.impl.transfer.item.SidedInventorySlotWrapper");
		static final @org.jetbrains.annotations.Nullable Class<?> SLOT = libraryClass("net.fabricmc.fabric.impl.transfer.item.InventorySlotWrapper");
		static final @org.jetbrains.annotations.Nullable Class<?> INVENTORY = libraryClass("net.fabricmc.fabric.impl.transfer.item.InventoryStorageImpl");
		static final @org.jetbrains.annotations.Nullable Class<?> SIDED_INVENTORY = libraryClass("net.fabricmc.fabric.impl.transfer.item.SidedInventoryStorageImpl");
		private static @org.jetbrains.annotations.Nullable Class<?> libraryClass(String name) {
			try { return Class.forName(name, false, PlatformInventoryServiceImpl.class.getClassLoader()); }
			catch (ClassNotFoundException absent) { return null; }
			catch (LinkageError failure) {
				Diagnostics.warn("inventory-resident-class", "fabric:transfer", () -> "class=" + name, failure);
				return null;
			}
		}
	}
	private static SingleStackStorage mappedStack(StorageView<ItemVariant> view, FabricResidentStorageAccess raw, ResidentScope scope) {
		if (view == null) throw new IllegalStateException("provider returned a null storage view");
		if (view.getClass() == NativeSlots.SIDED_SLOT) {
			if (scope != null && ResidentReads.field(view, NativeSlots.SIDED_SLOT, "direction", Direction.class) != scope.face())
				throw new InventorySourceUnavailableException("Fabric wrapper changed frozen face");
			SingleStackStorage slot = raw.mappedSlot(view);
			if (slot == null || slot.getClass() != NativeSlots.SLOT) throw new IllegalStateException("Fabric sided slot unavailable");
			return slot;
		}
		return view instanceof SingleStackStorage stack ? stack : null;
	}

	private static ResidentEntry residentEntry(StorageView<ItemVariant> view, BooleanSupplier memberGate,
		FabricResidentStorageAccess raw, ResidentScope scope, int visibleSlot, boolean positional,
		nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.Scratch scratch) {
		requireMember(memberGate);
		if (view == null) throw new IllegalStateException("provider returned a null storage view");
		// This exact wrapper forwards all content reads to the already mapped slot.
		// Never ask its getResource/getUnderlyingView to undo the frozen face mapping.
		SingleStackStorage mapped = mappedStack(view, raw, scope);
		if (mapped != null) return residentStack(mapped, memberGate, raw, scope, visibleSlot, positional, scratch);

		// Do not bypass a virtualized getter: altered graphs stay EXTERNAL_TRUST.
		if (view instanceof SingleVariantStorage<?> storage
			&& ResidentReads.inherits(view, SingleVariantStorage.class, "getResource")
			&& ResidentReads.inherits(view, SingleVariantStorage.class, "getAmount")
			&& ResidentReads.inherits(view, SingleVariantStorage.class, "isResourceBlank")
			&& nativeVariant(storage.variant)) {
			long amount = storage.amount;
			if (amount < 0) throw new IllegalStateException("provider reported a negative item amount");
			if (amount == 0) return ResidentEntry.empty();
			if (!(storage.variant instanceof ItemVariant resource))
				throw new IllegalStateException("provider returned a non-item variant");
			return residentVariant(resource, amount, memberGate);
		}

		// EXTERNAL_TRUST: finitely many external getters, no claim about their
		// internals. Known SingleStackStorage conversions were intercepted above.
		boolean blank = view.isResourceBlank();
		requireMember(memberGate);
		if (blank) return ResidentEntry.empty();
		long amount = view.getAmount();
		requireMember(memberGate);
		if (amount < 0) throw new IllegalStateException("provider reported a negative item amount");
		if (amount == 0) return ResidentEntry.empty();
		ItemVariant resource = view.getResource();
		requireMember(memberGate);
		return residentVariant(resource, amount, memberGate);
	}

	private static ResidentEntry residentStack(SingleStackStorage storage, BooleanSupplier memberGate,
		FabricResidentStorageAccess raw, ResidentScope scope, int visibleSlot, boolean positional,
		nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.Scratch scratch) {
		requireMember(memberGate);
		if (storage.getClass() == NativeSlots.SLOT) {
			// Raw fields establish only the already selected slot, not permission
			// for a new member. Pending loot can never be trusted into generation.
			var inventory = raw.inventory(storage);
			requireMember(memberGate);
			if (inventory == null || raw.localSlot(storage) < 0)
				throw new IllegalStateException("Fabric mapped inventory slot unavailable");
			if (scope != null) {
				if (!scope.valid()) throw new InventorySourceUnavailableException("Fabric inventory delegate outside admitted source scope");
				nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.requireScope(inventory, scope::allows, scratch);
				var mapping = scope.slots(inventory);
				boolean held;
				if (mapping != null) held = positional
					? visibleSlot >= 0 && visibleSlot < mapping.size() && mapping.get(visibleSlot) == raw.localSlot(storage)
					: containsLocal(mapping, raw.localSlot(storage));
				else held = positional
					// No frozen member layout: the selected face itself is the
					// witness. An exact unsided mapped leaf is refused before any
					// content read unless the face exposes that local position.
					? nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.faceSlot(
						inventory, scope.face(), visibleSlot, raw.localSlot(storage))
					// Cursorless enumeration has no stable position; the local slot
					// must at least stay inside the selected face.
					: nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.faceContains(
						inventory, scope.face(), raw.localSlot(storage));
				if (!held) throw new InventorySourceUnavailableException("Fabric inventory slot outside frozen local mapping");
			} else if (inventory instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null)
				throw new InventorySourceUnavailableException("Fabric inventory delegate has pending loot");
		}
		boolean nativeBlank = ResidentReads.inherits(storage, SingleStackStorage.class, "isResourceBlank");
		boolean nativeAmount = ResidentReads.inherits(storage, SingleStackStorage.class, "getAmount");
		boolean nativeResource = ResidentReads.inherits(storage, SingleStackStorage.class, "getResource");
		if (!nativeBlank || !nativeAmount || !nativeResource) {
			// Preserve virtualized semantics under explicit EXTERNAL_TRUST. Call
			// only changed getters; an inherited getResource would construct/hash
			// ItemVariant, so obtain that value from the raw stack instead.
			var blankStack = nativeBlank ? raw.stack(storage) : null;
			requireMember(memberGate);
			boolean blank = nativeBlank ? Objects.requireNonNull(blankStack, "resident stack").isEmpty() : storage.isResourceBlank();
			requireMember(memberGate);
			if (blank) return ResidentEntry.empty();
			long amount = nativeAmount ? Objects.requireNonNull(raw.stack(storage), "resident stack").getCount() : storage.getAmount();
			requireMember(memberGate);
			if (amount < 0) throw new IllegalStateException("provider reported a negative item amount");
			if (amount == 0) return ResidentEntry.empty();
			if (!nativeResource) {
				ItemVariant resource = storage.getResource();
				requireMember(memberGate);
				return residentVariant(resource, amount, memberGate);
			}
			var stack = Objects.requireNonNull(raw.stack(storage), "resident stack");
			requireMember(memberGate);
			return new ResidentEntry(new InventoryResidentItem.Stack(stack), amount);
		}
		// InventorySlotWrapper's inspected getStack is a direct mapped Container
		// read. Other concrete getStack implementations are EXTERNAL_TRUST: the
		// base API permits copying. The invoker itself is not certification.
		var stack = raw.stack(storage);
		requireMember(memberGate);
		return ResidentReads.stack(stack);
	}

	private static ResidentEntry residentVariant(ItemVariant resource, long amount, BooleanSupplier memberGate) {
		if (resource == null) throw new IllegalStateException("provider returned a null item variant");
		requireMember(memberGate);
		// ItemVariantImpl's inherited getObject/getComponents are resident fields.
		// Unknown implementations keep the same finite EXTERNAL_TRUST getter port;
		// neither route calls toStack/getCachedStack/equals/hashCode.
		var item = resource.getItem();
		requireMember(memberGate);
		if (item == null || item == Items.AIR) throw new IllegalStateException("positive amount requires an item variant");
		var patch = resource.getComponents();
		requireMember(memberGate);
		if (patch == null) throw new IllegalStateException("provider returned a null item patch");
		return new ResidentEntry(new InventoryResidentItem.Patch(item, patch), amount);
	}

	private static boolean nativeVariant(Object resource) {
		if (!(resource instanceof ItemVariant)
			|| !ResidentReads.inherits(resource, ItemVariantImpl.class, "getObject")
			|| !ResidentReads.inherits(resource, ItemVariantImpl.class, "getComponents")
			|| !ResidentReads.inherits(resource, ItemVariantImpl.class, "isBlank")) return false;
		if (!ResidentReads.reflectionShape(resource.getClass(), "getItem")) return false;
		try { return resource.getClass().getMethod("getItem").getDeclaringClass() == ItemVariant.class; }
		catch (ReflectiveOperationException | SecurityException unavailable) { return false; }
	}

	static final class SlottedAccess implements Access {

		private final SlottedStorage<ItemVariant> storage;
		private final BooleanSupplier memberGate;
		private final FabricResidentStorageAccess raw;
		private final nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.Scratch scratch;
		private ResidentScope residentScope;

		SlottedAccess(SlottedStorage<ItemVariant> storage) {
			this(storage, () -> true);
		}
		SlottedAccess(SlottedStorage<ItemVariant> storage, BooleanSupplier memberGate) {
			this(storage, memberGate, FabricResidentStorageAccess.MIXIN);
		}
		SlottedAccess(SlottedStorage<ItemVariant> storage, BooleanSupplier memberGate, FabricResidentStorageAccess raw) {
			this.storage = Objects.requireNonNull(storage, "storage"); this.memberGate = Objects.requireNonNull(memberGate);
			this.raw = Objects.requireNonNull(raw);
			this.scratch = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.Scratch();
		}
		@Override public Access guardedBy(BooleanSupplier gate) { var access = new SlottedAccess(storage, () -> memberGate.getAsBoolean() && gate.getAsBoolean(), raw); access.residentScope = residentScope; return access; }
		@Override public Access scopedResidents(ResidentScope scope) { var access = new SlottedAccess(storage, memberGate, raw); access.residentScope = Objects.requireNonNull(scope); return access; }
		@Override public boolean valid() {
			try { return memberGate.getAsBoolean(); }
			catch (RuntimeException | LinkageError unavailable) {
				if (!(unavailable instanceof InventorySourceUnavailableException))
					Diagnostics.warn("inventory-storage-valid", storage.getClass().getName(),
						() -> "storageClass=" + storage.getClass().getName() + " gateClass=" + memberGate.getClass().getName(), unavailable);
				return false;
			}
		}

		@Override
		public int slots() {
			requireMember(memberGate);
			int slots = storage.getSlotCount();
			requireMember(memberGate);
			if (slots < 0) throw new IllegalStateException("provider reported a negative slot count");
			return slots;
		}

		@Override
		public boolean stableCursor() {
			return true;
		}

		@Override
		public OptionalLong version() {
			requireMember(memberGate); var version = storageVersion(storage); requireMember(memberGate); return version;
		}

		@Override
		public Entry read(int slot) {
			Objects.checkIndex(slot, slots());
			requireMember(memberGate);
			return entry(storage.getSlot(slot), memberGate);
		}

		@Override
		public ResidentEntry readResident(int slot) {
			Objects.checkIndex(slot, slots());
			requireMember(memberGate);
			StorageView<ItemVariant> view = storage.getSlot(slot);
			requireMember(memberGate);
			ResidentEntry entry = residentEntry(view, memberGate, raw, residentScope, slot, true, scratch);
			if (residentScope != null && !residentScope.valid()) throw new InventorySourceUnavailableException("inventory source revoked during resident read");
			return entry;
		}

		@Override
		public boolean supportsDetachedSlotCapture() {
			requireMember(memberGate);
			if (storage.getClass() == NativeSlots.INVENTORY || storage.getClass() == NativeSlots.SIDED_INVENTORY) {
				// CombinedStorage.parts is public and mutable. Exact outer class and
				// audited leaves do not certify arbitrary parts.size/get delegates.
				// Do not invoke them during classification. The LIVE reader still
				// checks each raw leaf's source authority and frozen local mapping.
				return false;
			}
			// A single-view inherited topology needs no getSlot, iterator, capacity
			// or transaction call during classification. Composite/unknown lookup
			// graphs remain LIVE/EXTERNAL_TRUST, even if their item getters are raw.
			if (!(storage instanceof SingleVariantStorage<?> single)
				|| !ResidentReads.inherits(storage, SingleVariantStorage.class, "getResource")
				|| !ResidentReads.inherits(storage, SingleVariantStorage.class, "getAmount")
				|| !ResidentReads.inherits(storage, SingleVariantStorage.class, "isResourceBlank")) return false;
			try {
				if (!ResidentReads.reflectionShape(storage.getClass(), "getSlotCount")
					|| !ResidentReads.reflectionShape(storage.getClass(), "getSlot", int.class)) return false;
				if (storage.getClass().getMethod("getSlotCount").getDeclaringClass() != SingleSlotStorage.class
					|| storage.getClass().getMethod("getSlot", int.class).getDeclaringClass() != SingleSlotStorage.class) return false;
			} catch (ReflectiveOperationException | SecurityException unavailable) { return false; }
			boolean audited = single.amount >= 0 && nativeVariant(single.variant);
			requireMember(memberGate); return audited;
		}

		@Override
		public ResidentVisit observeResident(int limit, ResidentVisitor visitor) {
			ResidentVisit observed = Access.super.observeResident(limit, visitor);
			requireMember(memberGate); return observed;
		}

		@Override
		public boolean visit(int limit, Consumer<Entry> consumer) {
			Objects.requireNonNull(consumer, "consumer");
			if (limit < 0) throw new IllegalArgumentException("negative visit limit");
			int count = slots();
			int bound = Math.min(count, limit);
			for (int slot = 0; slot < bound; slot++) {
				Entry entry = read(slot);
				if (!entry.isEmpty()) consumer.accept(entry);
			}
			requireMember(memberGate);
			return count <= limit;
		}
	}

	static final class EnumeratedAccess implements Access {

		private final Storage<ItemVariant> storage;
		private final BooleanSupplier memberGate;
		private final FabricResidentStorageAccess raw;
		private final nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.Scratch scratch;
		private ResidentScope residentScope;

		EnumeratedAccess(Storage<ItemVariant> storage) {
			this(storage, () -> true);
		}
		EnumeratedAccess(Storage<ItemVariant> storage, BooleanSupplier memberGate) {
			this(storage, memberGate, FabricResidentStorageAccess.MIXIN);
		}
		EnumeratedAccess(Storage<ItemVariant> storage, BooleanSupplier memberGate, FabricResidentStorageAccess raw) {
			this.storage = Objects.requireNonNull(storage, "storage"); this.memberGate = Objects.requireNonNull(memberGate);
			this.raw = Objects.requireNonNull(raw);
			this.scratch = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.Scratch();
		}
		@Override public Access guardedBy(BooleanSupplier gate) { var access = new EnumeratedAccess(storage, () -> memberGate.getAsBoolean() && gate.getAsBoolean(), raw); access.residentScope = residentScope; return access; }
		@Override public Access scopedResidents(ResidentScope scope) { var access = new EnumeratedAccess(storage, memberGate, raw); access.residentScope = Objects.requireNonNull(scope); return access; }
		@Override public boolean valid() {
			try { return memberGate.getAsBoolean(); }
			catch (RuntimeException | LinkageError unavailable) {
				if (!(unavailable instanceof InventorySourceUnavailableException))
					Diagnostics.warn("inventory-storage-valid", storage.getClass().getName(),
						() -> "storageClass=" + storage.getClass().getName() + " gateClass=" + memberGate.getClass().getName(), unavailable);
				return false;
			}
		}

		@Override
		public int slots() {
			return 0;
		}

		@Override
		public boolean stableCursor() {
			return false;
		}

		@Override
		public OptionalLong version() {
			requireMember(memberGate); var version = storageVersion(storage); requireMember(memberGate); return version;
		}

		@Override
		public Entry read(int slot) {
			throw new UnsupportedOperationException("source has no stable slot cursor");
		}

		@Override
		public ResidentVisit observeResident(int limit, ResidentVisitor visitor) {
			if (limit < 0) throw new IllegalArgumentException("negative observation limit");
			Objects.requireNonNull(visitor, "visitor");
			// A zero allowance performs no iterator/getter work and proves nothing.
			if (limit == 0) return new ResidentVisit(0, false);
			requireMember(memberGate);
			Iterator<StorageView<ItemVariant>> iterator = storage.iterator();
			requireMember(memberGate);
			int visited = 0;
			while (visited < limit) {
				requireMember(memberGate); boolean next = iterator.hasNext(); requireMember(memberGate);
				if (!next) return new ResidentVisit(visited, true);
				requireMember(memberGate); StorageView<ItemVariant> view = iterator.next(); requireMember(memberGate);
				ResidentEntry entry = residentEntry(view, memberGate, raw, residentScope, visited, false, scratch);
				if (residentScope != null && !residentScope.valid()) throw new InventorySourceUnavailableException("inventory source revoked during resident read");
				visited++;
				boolean more = visitor.visit(entry);
				requireMember(memberGate);
				if (!more) return new ResidentVisit(visited, false);
			}
			requireMember(memberGate); boolean complete = !iterator.hasNext(); requireMember(memberGate);
			return new ResidentVisit(visited, complete);
		}

		@Override
		public Budgeted observe(int limit) {
			if (limit < 0) throw new IllegalArgumentException("negative observation limit");
			java.util.List<Entry> entries = new java.util.ArrayList<>();
			requireMember(memberGate);
			Iterator<StorageView<ItemVariant>> iterator = storage.iterator();
			while (entries.size() < limit) {
				requireMember(memberGate); boolean next = iterator.hasNext(); requireMember(memberGate);
				if (!next) return new Budgeted(entries, true);
				entries.add(entry(iterator.next(), memberGate));
			}
			requireMember(memberGate); boolean complete = !iterator.hasNext(); requireMember(memberGate);
			return new Budgeted(entries, complete);
		}

		@Override
		public boolean visit(int limit, Consumer<Entry> consumer) {
			Objects.requireNonNull(consumer, "consumer");
			if (limit < 0) throw new IllegalArgumentException("negative visit limit");
			requireMember(memberGate);
			Iterator<StorageView<ItemVariant>> iterator = storage.iterator();
			int visited = 0;
			while (true) {
				requireMember(memberGate); boolean next = iterator.hasNext(); requireMember(memberGate);
				if (!next) return true;
				if (visited >= limit) return false;
				Entry entry = entry(iterator.next(), memberGate);
				visited++;
				if (!entry.isEmpty()) consumer.accept(entry);
			}
		}
	}
}
