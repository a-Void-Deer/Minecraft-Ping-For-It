package nx.pingwheel.common.presentation.source;

import java.util.Optional;

/**
 * Persistent retained-memory admission for the shared source mechanism.
 *
 * <p>Unlike the period-scoped {@link CostLedger}, a reservation here holds the
 * actual retained cost for the whole lifetime of the cached object and releases
 * it only on close. The ledger is one finite server-wide cap; it is not a
 * period, work, or wire ledger, and its bytes are never mixed with those units.
 * A shared object occupies one reservation while each recipient's cursor or
 * index takes its own separate reservation.
 *
 * <p>The cap may be lowered while reservations are outstanding. Lowering it
 * never forgets or rewrites an outstanding charge: outstanding reservations
 * stay accounted, {@link #remaining()} may become negative, and further
 * admission defers until enough reservations close. Admission is checked before
 * the caller performs any read, encoding, or allocation.
 *
 * <p>Owner-thread confined; no synchronization is provided.
 */
public final class RetainedMemoryLedger {

	private long cap;
	private long reserved;
	private long retained;

	public RetainedMemoryLedger(long capBytes) {
		requireCap(capBytes);
		this.cap = capBytes;
	}

	public long cap() {
		return cap;
	}

	/** Actual committed retained bytes; charged until each ticket closes. */
	public long retained() {
		return retained;
	}

	/** Upper-bound bytes still held by uncommitted tickets. */
	public long reserved() {
		return reserved;
	}

	/**
	 * Configured cap minus committed retained bytes and outstanding
	 * reservations. It may be negative after the cap is lowered below live
	 * reservations.
	 */
	public long remaining() {
		return cap - retained - reserved;
	}

	/** Lowers or raises the finite cap without changing any outstanding charge. */
	public void setCap(long capBytes) {
		requireCap(capBytes);
		this.cap = capBytes;
	}

	/**
	 * Reserves a bounded upper bound or defers without moving any counter. A
	 * non-negative request is admitted only while it fits the remaining cap, so
	 * a cap lowered below live reservations admits nothing new.
	 */
	public Optional<Ticket> tryReserve(long upperBoundBytes) {
		if (upperBoundBytes < 0L) throw new IllegalArgumentException("reservation amount must be non-negative");
		if (upperBoundBytes > remaining()) return Optional.empty();
		reserved += upperBoundBytes;
		return Optional.of(new RetainedTicket(upperBoundBytes));
	}

	public interface Ticket extends LedgerTicket {
		/**
		 * Settles the measured actual retained cost, which stays charged until
		 * close. The actual amount must be a non-negative subset of the
		 * reservation; a rejected commit leaves the reservation standing.
		 */
		void commit(long actualBytes);
	}

	private final class RetainedTicket implements Ticket {
		private final long granted;
		private long charged;
		private boolean settled;
		private boolean released;

		RetainedTicket(long granted) {
			this.granted = granted;
		}

		@Override
		public void commit(long actualBytes) {
			if (settled) throw new IllegalStateException("retained reservation is already settled");
			if (actualBytes < 0L) throw new IllegalArgumentException("actual retained bytes must be non-negative");
			if (actualBytes > granted) throw new IllegalArgumentException("actual retained bytes exceed the reservation");
			settled = true;
			reserved -= granted;
			retained += actualBytes;
			charged = actualBytes;
		}

		@Override
		public void close() {
			if (released) return;
			released = true;
			if (settled) {
				retained -= charged;
			} else {
				settled = true;
				reserved -= granted;
			}
		}
	}

	private static void requireCap(long capBytes) {
		if (capBytes < 0L) throw new IllegalArgumentException("retained memory cap must be non-negative");
	}
}
