package nx.pingwheel.common.presentation.source;

import nx.pingwheel.common.presentation.source.CostLedger.Counter;
import nx.pingwheel.common.presentation.source.CostLedger.Unit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerTicketTest {

	private static final Counter SLOTS = new Counter("target-a", Unit.SLOT);
	private static final Counter WIRE = new Counter("client-a", Unit.WIRE_BYTES);

	@Test
	void combinedAdmissionReleasesEveryProvisionalTicketWhenOneLedgerDefers() {
		var memory = new RetainedMemoryLedger(100L);
		var slots = new CostLedger(Map.of(SLOTS, 4L));
		var wire = new CostLedger(Map.of(WIRE, 10L));

		var denied = LedgerTicket.tryReserveAll(List.of(
			() -> memory.tryReserve(40L),
			() -> slots.tryReserve(Map.of(SLOTS, 3L)),
			() -> wire.tryReserve(Map.of(WIRE, 11L))));

		assertTrue(denied.isEmpty());
		assertEquals(100L, memory.remaining(), "the granted memory ticket must be released");
		assertEquals(4L, slots.remaining(SLOTS), "the granted slot ticket must be released");
		assertEquals(10L, wire.remaining(WIRE));
		assertEquals(0L, memory.retained());
	}

	@Test
	void combinedAdmissionHoldsEveryLedgerUntilTheCallerCloses() {
		var memory = new RetainedMemoryLedger(100L);
		var slots = new CostLedger(Map.of(SLOTS, 4L));

		var granted = LedgerTicket.tryReserveAll(List.of(
			() -> memory.tryReserve(40L),
			() -> slots.tryReserve(Map.of(SLOTS, 3L)))).orElseThrow();

		assertEquals(60L, memory.remaining());
		assertEquals(1L, slots.remaining(SLOTS));
		assertEquals(2, granted.size());

		granted.forEach(LedgerTicket::close);
		granted.forEach(LedgerTicket::close);

		assertEquals(100L, memory.remaining());
		assertEquals(4L, slots.remaining(SLOTS));
	}

	@Test
	void combinedAdmissionReturnsTheGrantedLedgerTickets() {
		var memory = new RetainedMemoryLedger(100L);
		var granted = LedgerTicket.tryReserveAll(List.of(() -> memory.tryReserve(40L))).orElseThrow();

		var memoryTicket = (RetainedMemoryLedger.Ticket) granted.get(0);
		memoryTicket.commit(25L);

		assertEquals(25L, memory.retained(), "a returned ticket is the real ledger ticket");
		assertEquals(0L, memory.reserved());

		granted.get(0).close();

		assertEquals(0L, memory.retained());
	}

	@Test
	void combinedAdmissionDoesNotRunLaterAttemptsAfterADenial() {
		var memory = new RetainedMemoryLedger(0L);
		var slots = new CostLedger(Map.of(SLOTS, 4L));
		var laterAttempts = new AtomicInteger();

		var denied = LedgerTicket.tryReserveAll(List.of(
			() -> memory.tryReserve(1L),
			() -> {
				laterAttempts.incrementAndGet();
				return slots.tryReserve(Map.of(SLOTS, 1L));
			}));

		assertTrue(denied.isEmpty());
		assertEquals(0, laterAttempts.get(), "a denied attempt must short-circuit the remaining ledgers");
		assertEquals(4L, slots.remaining(SLOTS));
	}

	@Test
	void throwingLedgerAttemptReleasesTheMemoryTicketGrantedBeforeIt() {
		var memory = new RetainedMemoryLedger(100L);
		var period = new CostLedger(Map.of(SLOTS, 4L));
		var unconfigured = new Counter("target-b", Unit.WORK);

		assertThrows(IllegalArgumentException.class, () -> LedgerTicket.tryReserveAll(List.of(
			() -> memory.tryReserve(40L),
			() -> period.tryReserve(Map.of(unconfigured, 1L)))));

		assertEquals(100L, memory.remaining(), "the granted memory ticket must be released on exceptional exit");
		assertEquals(0L, memory.retained());
		assertEquals(4L, period.remaining(SLOTS), "the throwing ledger moved nothing");
	}

	@Test
	void throwingAttemptRethrowsTheSameFailureObjectAndSkipsLaterAttempts() {
		var memory = new RetainedMemoryLedger(100L);
		var original = new IllegalStateException("attempt failed");
		var laterAttempts = new AtomicInteger();

		var thrown = assertThrows(IllegalStateException.class, () -> LedgerTicket.tryReserveAll(List.of(
			() -> memory.tryReserve(40L),
			() -> {
				throw original;
			},
			() -> {
				laterAttempts.incrementAndGet();
				return Optional.empty();
			})));

		assertSame(original, thrown);
		assertEquals(0, laterAttempts.get(), "attempts after an exceptional exit are not invoked");
		assertEquals(100L, memory.remaining(), "the granted memory ticket must be released");
	}

	@Test
	void errorFailurePropagatesInsteadOfBecomingADeferral() {
		var memory = new RetainedMemoryLedger(100L);
		var fatal = new AssertionError("fatal");

		var thrown = assertThrows(AssertionError.class, () -> LedgerTicket.tryReserveAll(List.of(
			() -> memory.tryReserve(40L),
			() -> {
				throw fatal;
			})));

		assertSame(fatal, thrown);
		assertEquals(100L, memory.remaining(), "an error exit must still release the granted ticket");
	}

	@Test
	void nullAttemptAfterAGrantReleasesTheGrantedTicket() {
		var memory = new RetainedMemoryLedger(100L);
		var attempts = new ArrayList<LedgerTicket.Attempt>();
		attempts.add(() -> memory.tryReserve(40L));
		attempts.add(null);

		assertThrows(NullPointerException.class, () -> LedgerTicket.tryReserveAll(attempts));

		assertEquals(100L, memory.remaining(), "a null attempt must not leak an earlier grant");
		assertEquals(0L, memory.reserved());
	}

	@Test
	void nullAttemptResultAfterAGrantReleasesTheGrantedTicket() {
		var memory = new RetainedMemoryLedger(100L);
		var attempts = new ArrayList<LedgerTicket.Attempt>();
		attempts.add(() -> memory.tryReserve(40L));
		attempts.add(() -> null);

		assertThrows(NullPointerException.class, () -> LedgerTicket.tryReserveAll(attempts));

		assertEquals(100L, memory.remaining(), "a null result must not leak an earlier grant");
		assertEquals(0L, memory.reserved());
	}

	@Test
	void exceptionalExitReleasesGrantedTicketsInReverseOrder() {
		var order = new ArrayList<String>();
		var first = new FakeTicket("first", order, null);
		var second = new FakeTicket("second", order, null);
		var original = new IllegalStateException("attempt failed");

		var thrown = assertThrows(IllegalStateException.class, () -> LedgerTicket.tryReserveAll(List.of(
			() -> Optional.of(first),
			() -> Optional.of(second),
			() -> {
				throw original;
			})));

		assertSame(original, thrown);
		assertEquals(List.of("second", "first"), order, "granted tickets release in reverse admission order");
	}

	@Test
	void cleanupFailureIsSuppressedUnderTheOriginalFailureAndOtherTicketsStillClose() {
		var order = new ArrayList<String>();
		var cleanup = new IllegalStateException("cleanup failed");
		var healthy = new FakeTicket("healthy", order, null);
		var failing = new FakeTicket("failing", order, cleanup);
		var original = new IllegalStateException("attempt failed");

		var thrown = assertThrows(IllegalStateException.class, () -> LedgerTicket.tryReserveAll(List.of(
			() -> Optional.of(healthy),
			() -> Optional.of(failing),
			() -> {
				throw original;
			})));

		assertSame(original, thrown);
		assertEquals(List.of("failing", "healthy"), order, "cleanup continues past a failing close");
		assertArrayEquals(new Throwable[] {cleanup}, thrown.getSuppressed());
	}

	@Test
	void anEmptyAttemptListIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> LedgerTicket.tryReserveAll(List.of()));
	}

	/** Minimal ticket whose close records admission order and can fail, for cleanup-path coverage. */
	private static final class FakeTicket implements LedgerTicket {

		private final String name;
		private final List<String> order;
		private final RuntimeException closeFailure;

		FakeTicket(String name, List<String> order, RuntimeException closeFailure) {
			this.name = name;
			this.order = order;
			this.closeFailure = closeFailure;
		}

		@Override
		public void close() {
			order.add(name);
			if (closeFailure != null) throw closeFailure;
		}
	}
}
