package nx.pingwheel.common.presentation.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One cost-admission ticket shared by the accounting ledgers. Admission is a
 * grant only: no read, encoding, allocation, or other side effect may run
 * before a ticket is granted. Closing is idempotent and releases the charge
 * still outstanding for the ticket: a provisional ticket refunds its whole
 * reservation, while a settled ticket releases whatever its ledger still
 * holds. The ledgers differ there: a committed period ticket has already
 * charged its measured subset permanently, so its close adds no further
 * refund, whereas a settled retained-memory ticket keeps its actual retained
 * bytes charged until close releases them together with the object they pay
 * for.
 *
 * <p>{@link #tryReserveAll(List)} composes several ledger attempts into one
 * all-or-nothing admission. Attempts are tried in order; when any ledger
 * defers, every ticket already granted is released and the remaining attempts
 * are never invoked. Any exceptional exit from acquisition or result
 * construction also releases every granted ticket in reverse order and
 * rethrows the original failure unchanged; a cleanup failure is suppressed
 * under that original failure rather than replacing it. The ledgers
 * themselves stay separate: no unit is converted, weighted, or mixed.
 */
public interface LedgerTicket extends AutoCloseable {

	/**
	 * Releases the charge still outstanding for this ticket. A never-committed
	 * ticket refunds its whole reservation; a settled retained-memory ticket
	 * releases its actual retained bytes; a settled period ticket was already
	 * charged and releases nothing further. Repeated close is a no-op, and an
	 * implementation must not throw.
	 */
	@Override
	void close();

	/** One ledger's admission attempt; empty means that ledger deferred. */
	@FunctionalInterface
	interface Attempt {
		Optional<? extends LedgerTicket> reserve();
	}

	/**
	 * Tries every attempt in order and returns the granted tickets, or releases
	 * every provisional ticket and returns empty when one ledger defers. Later
	 * attempts are not invoked after a denial. An exception or error from an
	 * attempt, a null attempt or result, or result construction releases every
	 * already granted ticket in reverse order and is rethrown unchanged, with
	 * any cleanup failure suppressed under it. Callers close the returned
	 * tickets, for example through {@code granted.forEach(LedgerTicket::close)}.
	 */
	static Optional<List<LedgerTicket>> tryReserveAll(List<Attempt> attempts) {
		Objects.requireNonNull(attempts, "attempts");
		if (attempts.isEmpty()) throw new IllegalArgumentException("at least one admission attempt is required");
		List<LedgerTicket> granted = new ArrayList<>(attempts.size());
		try {
			for (Attempt attempt : attempts) {
				Optional<? extends LedgerTicket> next = Objects.requireNonNull(attempt, "attempt").reserve();
				if (Objects.requireNonNull(next, "reservation result").isEmpty()) {
					releaseAll(granted);
					return Optional.empty();
				}
				granted.add(Objects.requireNonNull(next.get(), "reservation"));
			}
			return Optional.of(List.copyOf(granted));
		} catch (RuntimeException | Error failure) {
			releaseAfterFailure(granted, failure);
			throw failure;
		}
	}

	/** Releases every granted ticket in reverse admission order, rethrowing the first failure unchanged. */
	private static void releaseAll(List<LedgerTicket> granted) {
		Throwable failure = null;
		for (int index = granted.size() - 1; index >= 0; index--) {
			try {
				granted.get(index).close();
			} catch (RuntimeException | Error problem) {
				if (failure == null) failure = problem;
				else failure.addSuppressed(problem);
			}
		}
		if (failure instanceof RuntimeException runtime) throw runtime;
		if (failure instanceof Error error) throw error;
	}

	/**
	 * Releases every granted ticket in reverse admission order while the
	 * original failure stays primary; each cleanup failure is suppressed under
	 * that primary failure instead of replacing it.
	 */
	private static void releaseAfterFailure(List<LedgerTicket> granted, Throwable primary) {
		for (int index = granted.size() - 1; index >= 0; index--) {
			try {
				granted.get(index).close();
			} catch (Throwable cleanup) {
				if (cleanup != primary) primary.addSuppressed(cleanup);
			}
		}
	}
}
