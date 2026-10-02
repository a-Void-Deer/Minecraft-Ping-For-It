package nx.pingwheel.fabric.platform;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
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
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(side, "side");
		if (!level.isLoaded(pos)) return Optional.empty();
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) {
			return Optional.empty();
		}
		try {
			Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, pos, side);
			if (storage == null) return Optional.empty();
			if (storage instanceof SlottedStorage<ItemVariant> slotted) {
				return Optional.of(new SlottedAccess(slotted));
			}
			return Optional.of(new EnumeratedAccess(storage));
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

	private static Entry entry(StorageView<ItemVariant> view) {
		if (view == null || view.isResourceBlank()) return Entry.empty();
		long amount = view.getAmount();
		if (amount < 0) throw new IllegalStateException("provider reported a negative item amount");
		if (amount == 0) return Entry.empty();
		return new Entry(view.getResource().toStack(1), amount);
	}

	private static final class SlottedAccess implements Access {

		private final SlottedStorage<ItemVariant> storage;

		SlottedAccess(SlottedStorage<ItemVariant> storage) {
			this.storage = Objects.requireNonNull(storage, "storage");
		}

		@Override
		public int slots() {
			int slots = storage.getSlotCount();
			if (slots < 0) throw new IllegalStateException("provider reported a negative slot count");
			return slots;
		}

		@Override
		public boolean stableCursor() {
			return true;
		}

		@Override
		public OptionalLong version() {
			return storageVersion(storage);
		}

		@Override
		public Entry read(int slot) {
			Objects.checkIndex(slot, slots());
			return entry(storage.getSlot(slot));
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
			return count <= limit;
		}
	}

	private static final class EnumeratedAccess implements Access {

		private final Storage<ItemVariant> storage;

		EnumeratedAccess(Storage<ItemVariant> storage) {
			this.storage = Objects.requireNonNull(storage, "storage");
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
			return storageVersion(storage);
		}

		@Override
		public Entry read(int slot) {
			throw new UnsupportedOperationException("source has no stable slot cursor");
		}

		@Override
		public Budgeted observe(int limit) {
			if (limit < 0) throw new IllegalArgumentException("negative observation limit");
			java.util.List<Entry> entries = new java.util.ArrayList<>();
			Iterator<StorageView<ItemVariant>> iterator = storage.iterator();
			while (entries.size() < limit && iterator.hasNext()) entries.add(entry(iterator.next()));
			return new Budgeted(entries, !iterator.hasNext());
		}

		@Override
		public boolean visit(int limit, Consumer<Entry> consumer) {
			Objects.requireNonNull(consumer, "consumer");
			if (limit < 0) throw new IllegalArgumentException("negative visit limit");
			Iterator<StorageView<ItemVariant>> iterator = storage.iterator();
			int visited = 0;
			while (true) {
				if (!iterator.hasNext()) return true;
				if (visited >= limit) return false;
				Entry entry = entry(iterator.next());
				visited++;
				if (!entry.isEmpty()) consumer.accept(entry);
			}
		}
	}
}
