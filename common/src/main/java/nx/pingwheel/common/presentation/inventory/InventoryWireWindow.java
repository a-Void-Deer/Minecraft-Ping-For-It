package nx.pingwheel.common.presentation.inventory;

import java.util.Arrays;

/**
 * Fixed-size rolling-excess wire accounting for one recipient's inventory
 * sends. Each period has a base allowance {@code B}, and a period's excess is
 * {@code max(0, spent - B)}. Only the previous {@code n - 1} excesses are
 * retained, and a fresh period may spend
 * {@code (n + 1) * B - sum(retained excesses)}. That keeps every {@code n}
 * consecutive excesses at or below {@code n * B}, so the single-period peak is
 * {@code (n + 1) * B} and any {@code n} consecutive periods total at most
 * {@code 2 * n * B}. The unused part of a period's base is never carried into a
 * later period.
 *
 * <p>The history is a fixed ring and advancement never allocates: a gap of at
 * least {@code n} periods clears the ring in {@code O(n)}, and a smaller gap
 * shifts only as many slots as the gap. A base/window pair whose peak budget
 * would overflow {@code long} is rejected. Owner-thread confined; no
 * synchronization is provided.
 */
public final class InventoryWireWindow {

	/** Smallest supported rolling window; the window is one period wide. */
	public static final int MIN_GRACE_PERIODS = 1;

	/** Largest supported rolling window. */
	public static final int MAX_GRACE_PERIODS = 32;

	private long baseBytes;
	private long peakBytes;
	private long[] excesses;
	private long period;
	private long spent;
	private long excessTotal;
	private int cursor;

	/**
	 * Creates a window ready to spend in period zero. {@code baseBytes} is the
	 * per-period base allowance and {@code gracePeriods} is the number of
	 * periods in the rolling window.
	 */
	public InventoryWireWindow(long baseBytes, int gracePeriods) {
		if (baseBytes <= 0L) {
			throw new IllegalArgumentException("inventory wire base must be positive");
		}
		if (gracePeriods < MIN_GRACE_PERIODS || gracePeriods > MAX_GRACE_PERIODS) {
			throw new IllegalArgumentException("inventory wire grace periods out of range");
		}
		long peak;
		try {
			peak = Math.multiplyExact(baseBytes, gracePeriods + 1L);
		} catch (ArithmeticException overflow) {
			throw new IllegalArgumentException("inventory wire window budget overflows", overflow);
		}
		this.baseBytes = baseBytes;
		this.peakBytes = peak;
		this.excesses = new long[gracePeriods - 1];
	}

	/** Bytes spent in the current period. */
	public long spent() {
		return spent;
	}

	/** Bytes still spendable in the current period under the rolling bound. */
	public long remaining() {
		long left = peakBytes - excessTotal - spent;
		return left > 0L ? left : 0L;
	}

	/**
	 * Charges {@code bytes} atomically. A request beyond {@link #remaining()}
	 * is rejected without changing any accounting state. Negative amounts are
	 * invalid input.
	 */
	public boolean trySpend(long bytes) {
		if (bytes < 0L) {
			throw new IllegalArgumentException("inventory wire spend must be non-negative");
		}
		if (bytes > remaining()) {
			return false;
		}
		spent += bytes;
		return true;
	}

	/** Live policy changes retain debt and actual current-period spend; no free new window. */
	public void reconfigure(long base, int periods) {
		if (base <= 0 || periods < MIN_GRACE_PERIODS || periods > MAX_GRACE_PERIODS) throw new IllegalArgumentException("wire policy");
		long peak = Math.multiplyExact(base, periods + 1L);
		long[] next = new long[periods - 1];
		int kept = Math.min(next.length, excesses.length);
		long retained = 0;
		for (int i = 0; i < kept; i++) {
			int source = (cursor + excesses.length - kept + i) % excesses.length;
			next[next.length - kept + i] = excesses[source]; retained = Math.addExact(retained, excesses[source]);
		}
		// Debt aged out only by shrinking policy is carried into the present period.
		spent = Math.addExact(spent, excessTotal - retained);
		excesses = next; cursor = 0; excessTotal = retained; baseBytes = base; peakBytes = peak;
	}

	/**
	 * Completes the current period and opens {@code period}, which must be
	 * strictly after it. Intermediate periods are idle: their excess is zero,
	 * so a gap of at least the window width discards all retained excess.
	 */
	public void advance(long period) {
		if (period <= this.period) {
			throw new IllegalArgumentException("inventory wire period must advance");
		}
		long excess = spent > baseBytes ? spent - baseBytes : 0L;
		long gap = period - this.period;
		this.period = period;
		this.spent = 0L;
		if (excesses.length == 0) {
			return;
		}
		if (gap >= excesses.length + 1L) {
			Arrays.fill(excesses, 0L);
			excessTotal = 0L;
			cursor = 0;
			return;
		}
		excessTotal += insert(excess);
		for (long idle = 1L; idle < gap; idle++) {
			excessTotal += insert(0L);
		}
	}

	private long insert(long excess) {
		long dropped = excesses[cursor];
		excesses[cursor] = excess;
		cursor = (cursor + 1) % excesses.length;
		return excess - dropped;
	}
}
