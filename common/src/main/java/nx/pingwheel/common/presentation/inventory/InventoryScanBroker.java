package nx.pingwheel.common.presentation.inventory;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

import nx.pingwheel.common.config.InventoryLimits;

/**
 * Server-thread-confined bounded physical observation cache for one
 * {@link InventoryScanner.Source}.
 *
 * <p>One broker describes one observation round of one physical source: each
 * source slot is read at most once, the detached
 * {@link InventoryScanner.Stack} values are kept in a fixed array bounded by
 * the constructor's {@code maximumSlots}, and there is no source-instance
 * generation identity. A replaced or invalidated source uses a new broker or
 * {@link #invalidate()}.
 *
 * <p>The broker never manages consumers and never charges logical progress. It
 * only admits physical reads through {@link #physicalStep(int)}. Any number of
 * consumers share the captured prefix through {@link #capturedSource()}; each
 * consumer still runs its own {@link InventoryScanner} and spends its own step
 * budget for every slot it advances over, so a cache hit is never free
 * progress.
 *
 * <p>The wrapper reports the full expected size from
 * {@link InventoryScanner.Source#slots()} and a stable cursor because the
 * captured prefix is immutable, and it returns the snapshot quality frozen at
 * physical capture rather than consulting the live source again. It rejects a
 * read outside the captured prefix with {@link IllegalStateException}; a caller
 * therefore clamps a consumer step to the available prefix and must consult
 * {@link #complete()} before treating a consumer result as complete. A terminal
 * {@link InventoryScanner.State#INCOMPLETE} or
 * {@link InventoryScanner.State#UNAVAILABLE} is a physical truth that never
 * becomes a complete scan.
 *
 * <p>A consumer result produced from the wrapper is detached evidence about
 * the captured prefix only. Before publishing, the server revalidates it
 * against the current broker state and the domain fence, because a result that
 * was complete when captured is not proof that the source is still valid or
 * that a later observation round has not begun. The broker adds no
 * source-instance generation counter.
 */
public final class InventoryScanBroker implements AutoCloseable {

	private final InventoryScanner.Source source;
	private final int maximumSlots;
	private final InventoryScanner.Stack[] cache;
	private final BitSet captured;
	private InventoryScanner.State state = InventoryScanner.State.SCANNING;
	private int total = -1;
	private int scanned;
	private long physicalReads;
	private boolean uncertain = true;
	private boolean initialized;

	/**
	 * Creates the cache for one observation round. {@code maximumSlots} is
	 * validated against
	 * {@link InventoryLimits#INTERNAL_COUNT_LIMIT_GUARD} before the fixed
	 * array is allocated, so even a caller-supplied finite limit can never
	 * become an unbounded cache.
	 */
	public InventoryScanBroker(InventoryScanner.Source source, int maximumSlots) {
		this.source = Objects.requireNonNull(source, "source");
		if (maximumSlots < 0 || maximumSlots > InventoryLimits.INTERNAL_COUNT_LIMIT_GUARD) {
			throw new IllegalArgumentException("maximumSlots must be between 0 and "
				+ InventoryLimits.INTERNAL_COUNT_LIMIT_GUARD);
		}
		this.maximumSlots = maximumSlots;
		this.cache = new InventoryScanner.Stack[maximumSlots];
		this.captured = new BitSet(maximumSlots);
	}

	/**
	 * Advances the captured prefix by at most {@code maximumReads} source
	 * slots. A zero budget performs no work at all, not even a source-size
	 * probe. A source larger than {@link #maximumSlots()} captures only the
	 * bounded prefix and ends {@link InventoryScanner.State#INCOMPLETE}; a
	 * source without a stable cursor cannot resume a sweep that its first
	 * positive budget does not finish.
	 */
	public void physicalStep(int maximumReads) {
		if (maximumReads < 0) {
			throw new IllegalArgumentException("maximumReads must be non-negative");
		}
		if (state != InventoryScanner.State.SCANNING || maximumReads == 0) {
			return;
		}
		try {
			if (!initialized) {
				int slots = source.slots();
				if (slots < 0) {
					throw new IllegalStateException("negative source size");
				}
				total = slots;
				uncertain = !source.stableSnapshot();
				initialized = true;
			} else if (source.slots() != total) {
				state = InventoryScanner.State.INCOMPLETE;
				return;
			}
			int limit = Math.min(total, maximumSlots);
			int remaining = maximumReads;
			while (scanned < limit && remaining > 0) {
				cache[scanned] = source.read(scanned);
				captured.set(scanned);
				scanned++;
				remaining--;
				physicalReads++;
			}
			if (scanned >= limit) {
				state = total <= maximumSlots
					? InventoryScanner.State.COMPLETE
					: InventoryScanner.State.INCOMPLETE;
			} else if (!source.stableCursor()) {
				state = InventoryScanner.State.INCOMPLETE;
			}
		} catch (RuntimeException | LinkageError failure) {
			state = InventoryScanner.State.UNAVAILABLE;
			clearCache();
		}
	}

	/**
	 * A consumer-side view of the captured prefix. The full expected size is
	 * reported so a consumer scanner sizes its sweep correctly; reads are only
	 * valid below {@link #scanned()}. The caller clamps each consumer step to
	 * {@code scanned() - cursor} before invoking it.
	 */
	public InventoryScanner.Source capturedSource() {
		if (!initialized) {
			throw new IllegalStateException("the shared scan has not been initialized by a physical step");
		}
		return new CapturedPrefixSource();
	}

	/** Number of physically captured slots, forming a stable prefix. */
	public int scanned() {
		return scanned;
	}

	/** Full expected source size, or {@code -1} before the first physical step. */
	public int total() {
		return total;
	}

	/** True only when every expected slot was captured. */
	public boolean complete() {
		return state == InventoryScanner.State.COMPLETE;
	}

	/** The snapshot quality frozen when the prefix was first captured; the cache adds no atomicity. */
	public boolean uncertain() {
		return uncertain;
	}

	public InventoryScanner.State state() {
		return state;
	}

	public int maximumSlots() {
		return maximumSlots;
	}

	/** Slots actually read from the underlying source; never exceeds the prefix. */
	public long physicalReads() {
		return physicalReads;
	}

	/** Drops the captured observations and stops physical work for this source. */
	public void invalidate() {
		if (state == InventoryScanner.State.CANCELLED) {
			return;
		}
		state = InventoryScanner.State.UNAVAILABLE;
		clearCache();
	}

	/** Releases the bounded cache; further reads and physical steps are rejected. */
	@Override
	public void close() {
		state = InventoryScanner.State.CANCELLED;
		clearCache();
	}

	private void clearCache() {
		Arrays.fill(cache, null);
		captured.clear();
	}

	private final class CapturedPrefixSource implements InventoryScanner.Source {

		@Override
		public int slots() {
			return total;
		}

		@Override
		public boolean stableCursor() {
			return true;
		}

		@Override
		public boolean stableSnapshot() {
			return !uncertain;
		}

		@Override
		public InventoryScanner.Stack read(int index) {
			if (state == InventoryScanner.State.UNAVAILABLE || state == InventoryScanner.State.CANCELLED) {
				throw new IllegalStateException("the shared source is no longer readable");
			}
			if (index < 0 || index >= total) {
				throw new IllegalStateException("slot is outside the source size");
			}
			if (index >= scanned || !captured.get(index)) {
				throw new IllegalStateException("slot is beyond the captured prefix");
			}
			return cache[index];
		}
	}
}
