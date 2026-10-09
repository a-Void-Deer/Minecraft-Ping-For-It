package nx.pingwheel.fabric.platform;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.entity.BlockEntity;

import nx.pingwheel.common.platform.IPlatformInventoryService;

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
			return Optional.empty();
		}
	}

	private static OptionalLong storageVersion(Storage<ItemVariant> storage) {
		try {
			return OptionalLong.of(storage.getVersion());
		} catch (RuntimeException | LinkageError failure) {
			return OptionalLong.empty();
		}
	}

	private static void requireMember(BooleanSupplier memberGate) {
		if (!memberGate.getAsBoolean()) throw new IllegalStateException("inventory storage outside the read scope");
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

	static final class SlottedAccess implements Access {

		private final SlottedStorage<ItemVariant> storage;
		private final BooleanSupplier memberGate;

		SlottedAccess(SlottedStorage<ItemVariant> storage) {
			this(storage, () -> true);
		}
		SlottedAccess(SlottedStorage<ItemVariant> storage, BooleanSupplier memberGate) {
			this.storage = Objects.requireNonNull(storage, "storage"); this.memberGate = Objects.requireNonNull(memberGate);
		}
		@Override public Access guardedBy(BooleanSupplier gate) { return new SlottedAccess(storage, () -> memberGate.getAsBoolean() && gate.getAsBoolean()); }
		@Override public boolean valid() {
			try { requireMember(memberGate); return true; }
			catch (RuntimeException | LinkageError unavailable) { return false; }
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

		EnumeratedAccess(Storage<ItemVariant> storage) {
			this(storage, () -> true);
		}
		EnumeratedAccess(Storage<ItemVariant> storage, BooleanSupplier memberGate) {
			this.storage = Objects.requireNonNull(storage, "storage"); this.memberGate = Objects.requireNonNull(memberGate);
		}
		@Override public Access guardedBy(BooleanSupplier gate) { return new EnumeratedAccess(storage, () -> memberGate.getAsBoolean() && gate.getAsBoolean()); }
		@Override public boolean valid() {
			try { requireMember(memberGate); return true; }
			catch (RuntimeException | LinkageError unavailable) { return false; }
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
