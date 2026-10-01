package nx.pingwheel.common.presentation.inventory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One server-thread-confined, bounded observation. A new sweep uses a new scanner. */
public final class InventoryScanner implements AutoCloseable {

	public record Key(String itemId, String componentsKey) {
		public Key {
			Objects.requireNonNull(itemId, "itemId");
			Objects.requireNonNull(componentsKey, "componentsKey");
			if (itemId.isBlank() || itemId.length() > 256 || componentsKey.length() > 256) {
				throw new IllegalArgumentException("invalid item identity");
			}
		}
	}

	public record Stack(Key key, long count) {
		public Stack {
			Objects.requireNonNull(key, "key");
			if (count < 0) throw new IllegalArgumentException("negative item count");
		}
	}

	/** Reads must be side-effect free; returned stacks are detached values, not game stacks. */
	public interface Source {
		int slots();
		boolean stableCursor();
		boolean stableSnapshot();

		/**
		 * Reads one detached observation. {@code null} means the slot was
		 * actually observed as empty; it is not a cache miss or an unknown
		 * state. A source that cannot read the slot must throw instead of
		 * returning {@code null}, so an unavailable read is never mistaken
		 * for an observed empty slot.
		 */
		Stack read(int slot);
	}

	public enum State { SCANNING, COMPLETE, INCOMPLETE, UNAVAILABLE, CANCELLED }

	public record Result(Map<Key, Long> counts, Map<String, Long> itemTotals,
		State state, boolean uncertain, int scanned, int expected) {
		public Result {
			counts = Map.copyOf(counts);
			itemTotals = Map.copyOf(itemTotals);
			Objects.requireNonNull(state, "state");
		}
		public boolean complete() { return state == State.COMPLETE; }
	}

	private final Source source;
	private final Set<Key> selected;
	private final Set<String> aggregateIds;
	private final boolean previewAll;
	private final int maximumEntries;
	private final Map<Key, Long> counts = new LinkedHashMap<>();
	private final Map<String, Long> totals = new LinkedHashMap<>();
	private State state = State.SCANNING;
	private int cursor;
	private int expected = -1;
	private boolean initialized;
	private boolean stableCursor;
	private boolean uncertain = true;

	public InventoryScanner(Source source, Set<Key> selected, Set<String> aggregateIds,
		boolean previewAll, int maximumEntries) {
		this.source = Objects.requireNonNull(source, "source");
		this.selected = Set.copyOf(selected);
		this.aggregateIds = Set.copyOf(aggregateIds);
		this.previewAll = previewAll;
		if (maximumEntries < 1 || this.selected.size() > maximumEntries
			|| this.aggregateIds.size() > maximumEntries) {
			throw new IllegalArgumentException("invalid entry bound");
		}
		this.maximumEntries = maximumEntries;
	}

	/** The caller reserves this physical read allowance before invoking a step. */
	public Result step(int slotBudget) {
		if (slotBudget < 0) throw new IllegalArgumentException("negative slot budget");
		if (state != State.SCANNING || slotBudget == 0) return snapshot();
		try {
			if (!initialized) {
				expected = source.slots();
				if (expected < 0) throw new IllegalStateException("negative source size");
				stableCursor = source.stableCursor();
				uncertain = !source.stableSnapshot();
				initialized = true;
			} else if (source.slots() != expected) {
				state = State.INCOMPLETE;
				return snapshot();
			}
			int remaining = slotBudget;
			while (cursor < expected && remaining-- > 0) {
				Stack stack = source.read(cursor++);
				if (stack == null || stack.count() == 0) continue;
				if (previewAll || selected.contains(stack.key())) {
					if (!counts.containsKey(stack.key()) && counts.size() >= maximumEntries) {
						state = State.INCOMPLETE;
						return snapshot();
					}
					counts.merge(stack.key(), stack.count(), Math::addExact);
				}
				if (aggregateIds.contains(stack.key().itemId())) {
					totals.merge(stack.key().itemId(), stack.count(), Math::addExact);
				}
			}
			if (cursor == expected) {
				long missing = selected.stream().filter(key -> !counts.containsKey(key)).count();
				if (counts.size() + missing > maximumEntries) {
					state = State.INCOMPLETE;
					return snapshot();
				}
				selected.forEach(key -> counts.putIfAbsent(key, 0L));
				aggregateIds.forEach(id -> totals.putIfAbsent(id, 0L));
				state = State.COMPLETE;
			} else if (!stableCursor) {
				state = State.INCOMPLETE;
			}
		} catch (ArithmeticException failure) {
			state = State.INCOMPLETE;
		} catch (RuntimeException | LinkageError failure) {
			state = State.UNAVAILABLE;
			counts.clear();
			totals.clear();
		}
		return snapshot();
	}

	public Result snapshot() {
		return new Result(counts, totals, state, uncertain, cursor, expected);
	}

	@Override
	public void close() {
		counts.clear();
		totals.clear();
		state = State.CANCELLED;
	}
}
