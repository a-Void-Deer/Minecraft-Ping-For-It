package nx.pingwheel.common.presentation.source;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure-JVM cost ledger for the shared source mechanism. Admission reserves a
 * bounded upper bound before any world read or allocation; measured use is
 * committed afterwards, and the unused part of the reservation is refunded.
 *
 * <p>Every counter is configured explicitly and bounded independently. Units
 * are never mixed or weighted, and a counter whose limit was not configured is
 * rejected instead of being treated as unlimited. Create a new ledger per
 * accounting period; there is no reset while reservations are outstanding.
 *
 * <p>Owner-thread confined; no synchronization is provided.
 */
public final class CostLedger {

	public enum Unit {
		WORK,
		CAPTURE,
		SLOT,
		LOGICAL_PROGRESS,
		WIRE_BYTES,
		RETAINED_BYTES
	}

	/**
	 * Named typed counter. The scope is a server-owned identity (for example a
	 * target or a client) and distinguishes counters that share a unit.
	 */
	public record Counter(String scope, Unit unit) {
		public Counter {
			Objects.requireNonNull(scope, "scope");
			Objects.requireNonNull(unit, "unit");
			if (scope.isBlank()) throw new IllegalArgumentException("counter scope must not be blank");
		}
	}

	public interface Ticket extends LedgerTicket {
		/**
		 * Charges measured use, which must be a non-negative subset of this
		 * reservation and must not exceed any reserved amount. The ticket
		 * settles: it cannot be committed or refunded again.
		 */
		void commit(Map<Counter, Long> actual);

		/**
		 * Refunds the whole reservation when it was never committed; closing a
		 * settled ticket does nothing.
		 */
		@Override
		void close();
	}

	private final Map<Counter, Long> limits;
	private final Map<Counter, Long> reserved = new HashMap<>();
	private final Map<Counter, Long> used = new HashMap<>();

	public CostLedger(Map<Counter, Long> limits) {
		Objects.requireNonNull(limits, "limits");
		if (limits.isEmpty()) throw new IllegalArgumentException("at least one counter limit is required");
		Map<Counter, Long> copy = new LinkedHashMap<>();
		for (Map.Entry<Counter, Long> entry : limits.entrySet()) {
			Counter counter = Objects.requireNonNull(entry.getKey(), "counter");
			Long limit = Objects.requireNonNull(entry.getValue(), "limit");
			if (limit < 0L) throw new IllegalArgumentException("limit must be non-negative: " + counter);
			copy.put(counter, limit);
		}
		this.limits = Collections.unmodifiableMap(copy);
	}

	public long limit(Counter counter) {
		return require(counter);
	}

	public long used(Counter counter) {
		require(counter);
		return used.getOrDefault(counter, 0L);
	}

	/** Configured limit minus committed use and outstanding reservations. */
	public long remaining(Counter counter) {
		return require(counter) - used.getOrDefault(counter, 0L) - reserved.getOrDefault(counter, 0L);
	}

	/**
	 * Atomically reserves every requested counter or none. A non-negative
	 * request may still return empty when any limit is insufficient; no counter
	 * is deducted in that case.
	 */
	public Optional<Ticket> tryReserve(Map<Counter, Long> request) {
		Objects.requireNonNull(request, "request");
		if (request.isEmpty()) throw new IllegalArgumentException("reservation must request at least one counter");
		Map<Counter, Long> amounts = new LinkedHashMap<>();
		for (Map.Entry<Counter, Long> entry : request.entrySet()) {
			Counter counter = Objects.requireNonNull(entry.getKey(), "counter");
			require(counter);
			Long amount = Objects.requireNonNull(entry.getValue(), "amount");
			if (amount < 0L) throw new IllegalArgumentException("reservation amount must be non-negative: " + counter);
			amounts.put(counter, amount);
		}
		for (Map.Entry<Counter, Long> entry : amounts.entrySet()) {
			if (entry.getValue() > remaining(entry.getKey())) return Optional.empty();
		}
		for (Map.Entry<Counter, Long> entry : amounts.entrySet()) {
			reserved.merge(entry.getKey(), entry.getValue(), Long::sum);
		}
		return Optional.of(new Reservation(amounts));
	}

	private long require(Counter counter) {
		Objects.requireNonNull(counter, "counter");
		Long configured = limits.get(counter);
		if (configured == null) throw new IllegalArgumentException("counter is not configured: " + counter);
		return configured;
	}

	private void release(Counter counter, long amount) {
		long outstanding = reserved.getOrDefault(counter, 0L) - amount;
		if (outstanding == 0L) {
			reserved.remove(counter);
		} else {
			reserved.put(counter, outstanding);
		}
	}

	private final class Reservation implements Ticket {
		private final Map<Counter, Long> granted;
		private boolean settled;

		Reservation(Map<Counter, Long> granted) {
			this.granted = Collections.unmodifiableMap(new LinkedHashMap<>(granted));
		}

		@Override
		public void commit(Map<Counter, Long> actual) {
			Objects.requireNonNull(actual, "actual");
			if (settled) throw new IllegalStateException("reservation is already settled");
			Map<Counter, Long> measured = new LinkedHashMap<>();
			for (Map.Entry<Counter, Long> entry : actual.entrySet()) {
				Counter counter = Objects.requireNonNull(entry.getKey(), "counter");
				Long amount = Objects.requireNonNull(entry.getValue(), "amount");
				if (amount < 0L) throw new IllegalArgumentException("measured use must be non-negative: " + counter);
				Long grantedAmount = granted.get(counter);
				if (grantedAmount == null) throw new IllegalArgumentException("counter was not reserved: " + counter);
				if (amount > grantedAmount) throw new IllegalArgumentException("measured use exceeds the reservation: " + counter);
				measured.put(counter, amount);
			}
			settled = true;
			for (Map.Entry<Counter, Long> entry : granted.entrySet()) {
				used.merge(entry.getKey(), measured.getOrDefault(entry.getKey(), 0L), Long::sum);
				release(entry.getKey(), entry.getValue());
			}
		}

		@Override
		public void close() {
			if (settled) return;
			settled = true;
			for (Map.Entry<Counter, Long> entry : granted.entrySet()) {
				release(entry.getKey(), entry.getValue());
			}
		}
	}
}
