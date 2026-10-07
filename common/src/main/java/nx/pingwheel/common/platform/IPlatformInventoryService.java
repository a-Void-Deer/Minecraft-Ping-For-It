package nx.pingwheel.common.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;

/**
 * Loader bridge for item sources that expose a native, read-only inventory
 * capability.
 *
 * <p>{@link #find} returns a server-thread-confined {@link Access} for the
 * physical source at one position. The vanilla container contract keeps its own
 * provider and is not replaced by this bridge; this bridge covers sources that
 * are reachable only through a loader capability. Implementations read raw
 * Minecraft stacks and never mutate the source, open a transaction, force-load
 * a chunk, or trigger loot generation.
 *
	 * <p>A specialized safe provider may establish a canonical alias, but the
	 * bridge owns no permission or lock decision and no consumer or packet state.
	 * The caller admits bounded provider work before resolution and composes the
	 * physical access with {@code SourceKey} identity.
 */
public interface IPlatformInventoryService {

	IPlatformInventoryService INSTANCE = ServiceLoader.load(IPlatformInventoryService.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No IPlatformInventoryService implementation found!"));

	/**
	 * Resolves the loader item capability at {@code pos} for the non-null frozen
	 * side. Returns empty when the position is not loaded, when no block entity
	 * or capability is present, when loot generation is pending, or when the
	 * provider fails. Must be called on the server thread; the caller owns
	 * target validation and every permission decision.
	 */
	Optional<Access> find(ServerLevel level, BlockPos pos, Direction side);

	/**
	 * One detached observation of a source entry. {@code exemplar} is a
	 * single-item stack carrying item and component identity; {@code amount} is
	 * the provider-confirmed long count and stays authoritative even when it
	 * exceeds the integer stack limit. An amount of zero is the canonical empty
	 * observation and never carries an exemplar.
	 */
	record Entry(ItemStack exemplar, long amount) {

		public Entry {
			Objects.requireNonNull(exemplar, "exemplar");
			if (amount < 0) throw new IllegalArgumentException("negative item amount");
			if (amount == 0) {
				exemplar = ItemStack.EMPTY;
			} else if (exemplar.isEmpty()) {
				throw new IllegalArgumentException("non-empty amount requires an item exemplar");
			}
		}

		public static Entry empty() {
			return new Entry(ItemStack.EMPTY, 0L);
		}

		public boolean isEmpty() {
			return amount == 0L;
		}
	}

	/**
	 * One detached, bounded enumeration result. {@code complete} is true only
	 * when the whole source was traversed within the call's limit.
	 */
	record Budgeted(List<Entry> entries, boolean complete) {

		public Budgeted {
			Objects.requireNonNull(entries, "entries");
			entries = List.copyOf(entries);
		}
	}

	/**
	 * Read-only access to one physical source. The access, the provider objects
	 * behind it, and every returned stack stay confined to the server thread.
	 */
	interface Access {
		/** Canonical controller alias, only when established without force-loading. */
		default Optional<String> alias() { return Optional.empty(); }
		/**
		 * Optional detached description of an explicitly supported multipart NBT
		 * snapshot. This DTO contains positions and slot mappings only; it never
		 * carries live block entities or widens this access's frozen side.
		 */
		default Optional<InventorySnapshotLayout> snapshotLayout() { return Optional.empty(); }
		/** Reacquire live provider segments on validation/read when the provider requires it. */
		default boolean valid() { return true; }
		/** Counts every visited view, including blanks; implementations override cursorless enumeration. */
		default Budgeted observe(int limit) {
			if (limit < 0) throw new IllegalArgumentException("negative observation limit");
			List<Entry> entries = new ArrayList<>();
			if (stableCursor()) {
				int count = slots();
				for (int i = 0; i < Math.min(count, limit); i++) entries.add(read(i));
				return new Budgeted(entries, count <= limit);
			}
			throw new UnsupportedOperationException("cursorless observation must account blank views");
		}

		/**
		 * Number of stable slots, or {@code 0} when the source has no stable
		 * slot view. Never negative.
		 */
		int slots();

		/**
		 * True when {@link #read(int)} addresses stable slots across calls.
		 * False when the source can only be enumerated by {@link #visit} and
		 * each pass is a fresh, possibly shifted observation.
		 */
		boolean stableCursor();

		/**
		 * Provider change counter when the provider offers one; empty when the
		 * provider exposes none. Values are only comparable for this same
		 * access instance.
		 */
		OptionalLong version();

		/**
		 * Detached read of one stable slot. Only valid when
		 * {@link #stableCursor()} is true; an empty slot reports
		 * {@link Entry#empty()}.
		 */
		Entry read(int slot);

		/**
		 * One bounded pass that visits at most {@code limit} non-empty entries
		 * and returns true only when the source was fully traversed inside that
		 * limit. A later call starts a new pass and is not promised the same
		 * views when {@link #stableCursor()} is false.
		 */
		boolean visit(int limit, Consumer<Entry> consumer);

		/**
		 * Detached convenience form of one {@link #visit} pass; the returned
		 * list holds at most {@code limit} entries.
		 */
		default Budgeted readBudgeted(int limit) {
			List<Entry> entries = new ArrayList<>();
			boolean complete = visit(limit, entries::add);
			return new Budgeted(entries, complete);
		}
	}
}
