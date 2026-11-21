package nx.pingwheel.common.client.marker;

import java.lang.ref.WeakReference;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Connection-local, client-thread-only progress from received vanilla game-time samples. */
@NotNullByDefault
public final class ObservedServerClock {

	/** Implemented by the play listener; the clock does not keep a reference back to it. */
	@NotNullByDefault
	public interface Source {
		ObservedServerClock pingforit$observedServerClock();
	}

	private @Nullable WeakReference<Object> sampledWorld;
	private long lastGameTime;
	private @Nullable Origin pendingOrigin;
	private Object session = new Object();

	// Keep progress modulo 2^63 and distinguish eras so a huge old observation
	// cannot saturate the clock itself and prevent later receipts from aging.
	private Object era = new Object();
	private @Nullable Object previousEra;
	private long ticksInEra;

	/** Applies one sample for the current client world, using world identity rather than its dimension key. */
	public void observeSample(Object world, long gameTime) {
		if (sampledWorld == null || !sampledWorld.refersTo(world)) {
			sampledWorld = new WeakReference<>(world);
		} else if (gameTime >= lastGameTime) {
			long delta = gameTime - lastGameTime;
			// The subtraction can overflow on signed input; every supported duration is at most MAX_VALUE.
			advance(delta < 0L ? Long.MAX_VALUE : delta);
		}
		lastGameTime = gameTime;

		if (pendingOrigin != null) {
			pendingOrigin.settle(era, ticksInEra);
			pendingOrigin = null;
		}
	}

	/**
	 * Returns a shared origin for receipts since the last time packet. It settles at the
	 * next sample so those receipts do not consume the interval containing pre-receipt time.
	 */
	public Origin receiptOrigin() {
		if (pendingOrigin == null) {
			pendingOrigin = new Origin(this, session);
		}
		return pendingOrigin;
	}

	/** A world detach must not make the first sample after reattachment consume a gap. */
	public void breakWorldBaseline() {
		sampledWorld = null;
	}

	/** Discards all observation history and pending origins at connection end. */
	public void reset() {
		breakWorldBaseline();
		pendingOrigin = null;
		session = new Object();
		era = new Object();
		previousEra = null;
		ticksInEra = 0L;
	}

	private void advance(long delta) {
		long sum = ticksInEra + delta;
		if (sum < 0L) {
			previousEra = era;
			era = new Object();
		}
		ticksInEra = sum & Long.MAX_VALUE;
	}

	/** A receipt-time anchor; reads are allocation-free and elapsed ticks saturate independently of the clock. */
	@NotNullByDefault
	public static final class Origin {
		private final ObservedServerClock clock;
		private final Object session;
		private @Nullable Object era;
		private long ticksInEra;

		private Origin(ObservedServerClock clock, Object session) {
			this.clock = clock;
			this.session = session;
		}

		private void settle(Object era, long ticksInEra) {
			this.era = era;
			this.ticksInEra = ticksInEra;
		}

		public long elapsedTicks() {
			if (era == null || session != clock.session) return 0L;
			if (era == clock.era) return clock.ticksInEra - ticksInEra;
			if (era == clock.previousEra && clock.ticksInEra < ticksInEra) {
				return (clock.ticksInEra - ticksInEra) & Long.MAX_VALUE;
			}
			return Long.MAX_VALUE;
		}
	}
}
