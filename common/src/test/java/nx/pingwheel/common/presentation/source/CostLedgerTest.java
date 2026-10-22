package nx.pingwheel.common.presentation.source;

import nx.pingwheel.common.presentation.source.CostLedger.Counter;
import nx.pingwheel.common.presentation.source.CostLedger.Unit;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostLedgerTest {

	private static final Counter WORK_A = new Counter("target-a", Unit.WORK);
	private static final Counter WORK_B = new Counter("target-b", Unit.WORK);
	private static final Counter SLOTS_A = new Counter("target-a", Unit.SLOT);
	private static final Counter WIRE_A = new Counter("client-a", Unit.WIRE_BYTES);

	@Test
	void failedMultiCounterReservationDeductsNothing() {
		var ledger = new CostLedger(Map.of(WORK_A, 10L, WORK_B, 2L));
		assertTrue(ledger.tryReserve(Map.of(WORK_A, 5L)).isPresent());

		var deferred = ledger.tryReserve(Map.of(WORK_A, 4L, WORK_B, 3L));

		assertTrue(deferred.isEmpty());
		assertEquals(0L, ledger.used(WORK_A));
		assertEquals(5L, ledger.remaining(WORK_A), "the fundable counter must not be deducted by a failed pair");
		assertEquals(2L, ledger.remaining(WORK_B));
	}

	@Test
	void exactLimitIsAdmittedAndOneMoreDefers() {
		var ledger = new CostLedger(Map.of(SLOTS_A, 4L));
		var ticket = ledger.tryReserve(Map.of(SLOTS_A, 4L)).orElseThrow();
		assertEquals(0L, ledger.remaining(SLOTS_A));

		assertTrue(ledger.tryReserve(Map.of(SLOTS_A, 1L)).isEmpty());
		assertEquals(0L, ledger.remaining(SLOTS_A));

		ticket.close();
		assertEquals(4L, ledger.remaining(SLOTS_A));
	}

	@Test
	void overCommitIsRejectedBeforeTheChargeChanges() {
		var ledger = new CostLedger(Map.of(WORK_A, 10L));
		var ticket = ledger.tryReserve(Map.of(WORK_A, 6L)).orElseThrow();

		assertThrows(IllegalArgumentException.class, () -> ticket.commit(Map.of(WORK_A, 7L)));
		assertThrows(IllegalArgumentException.class, () -> ticket.commit(Map.of(WIRE_A, 1L)));
		assertThrows(IllegalArgumentException.class, () -> ticket.commit(Map.of(WORK_A, -1L)));

		assertEquals(0L, ledger.used(WORK_A));
		assertEquals(4L, ledger.remaining(WORK_A), "a rejected commit must leave the reservation standing");

		ticket.close();
		assertEquals(10L, ledger.remaining(WORK_A));
	}

	@Test
	void committedReservationChargesMeasuredUseAndIsTerminal() {
		var ledger = new CostLedger(Map.of(WORK_A, 10L, WIRE_A, 500L));
		var ticket = ledger.tryReserve(Map.of(WORK_A, 6L, WIRE_A, 300L)).orElseThrow();

		ticket.commit(Map.of(WORK_A, 4L, WIRE_A, 250L));

		assertEquals(4L, ledger.used(WORK_A));
		assertEquals(6L, ledger.remaining(WORK_A));
		assertEquals(250L, ledger.used(WIRE_A));
		assertEquals(250L, ledger.remaining(WIRE_A));
		assertThrows(IllegalStateException.class, () -> ticket.commit(Map.of(WORK_A, 1L)));

		ticket.close();
		ticket.close();
		assertEquals(4L, ledger.used(WORK_A));
		assertEquals(6L, ledger.remaining(WORK_A));
	}

	@Test
	void measuredUseRefundsOmittedCountersAcrossDistinctUnits() {
		var ledger = new CostLedger(Map.of(WORK_A, 100L, SLOTS_A, 6L, WIRE_A, 1000L));
		var ticket = ledger.tryReserve(Map.of(WORK_A, 10L, SLOTS_A, 4L, WIRE_A, 300L)).orElseThrow();

		ticket.commit(Map.of(WORK_A, 3L, WIRE_A, 200L));

		assertEquals(3L, ledger.used(WORK_A));
		assertEquals(97L, ledger.remaining(WORK_A));
		assertEquals(0L, ledger.used(SLOTS_A));
		assertEquals(6L, ledger.remaining(SLOTS_A), "an omitted slot reservation is refunded, not mixed with work");
		assertEquals(200L, ledger.used(WIRE_A));
		assertEquals(800L, ledger.remaining(WIRE_A));
	}

	@Test
	void closingUncommittedReservationRefundsAllAndIsIdempotent() {
		var ledger = new CostLedger(Map.of(WORK_A, 10L));
		var ticket = ledger.tryReserve(Map.of(WORK_A, 7L)).orElseThrow();
		assertEquals(3L, ledger.remaining(WORK_A));

		ticket.close();

		assertEquals(0L, ledger.used(WORK_A));
		assertEquals(10L, ledger.remaining(WORK_A));
		ticket.close();
		assertEquals(10L, ledger.remaining(WORK_A));
		assertThrows(IllegalStateException.class, () -> ticket.commit(Map.of(WORK_A, 1L)));
	}

	@Test
	void outstandingReservationsHoldTheirOwnCharges() {
		var ledger = new CostLedger(Map.of(SLOTS_A, 10L));
		var first = ledger.tryReserve(Map.of(SLOTS_A, 4L)).orElseThrow();
		var second = ledger.tryReserve(Map.of(SLOTS_A, 5L)).orElseThrow();
		assertEquals(1L, ledger.remaining(SLOTS_A));

		first.commit(Map.of(SLOTS_A, 3L));

		assertEquals(3L, ledger.used(SLOTS_A));
		assertEquals(2L, ledger.remaining(SLOTS_A), "the second reservation still holds its five slots");

		second.close();
		assertEquals(3L, ledger.used(SLOTS_A));
		assertEquals(7L, ledger.remaining(SLOTS_A));
	}

	@Test
	void invalidRequestsFailBeforeAnyCounterMoves() {
		var ledger = new CostLedger(Map.of(WORK_A, 10L));
		assertTrue(ledger.tryReserve(Map.of(WORK_A, 5L)).isPresent());

		assertThrows(IllegalArgumentException.class, () -> ledger.tryReserve(Map.of(WORK_B, 1L)));
		assertThrows(IllegalArgumentException.class, () -> ledger.tryReserve(Map.of(WORK_A, -1L)));
		assertThrows(IllegalArgumentException.class, () -> ledger.tryReserve(Map.of()));
		assertThrows(IllegalArgumentException.class, () -> ledger.used(WORK_B));
		assertThrows(IllegalArgumentException.class, () -> ledger.remaining(WORK_B));
		assertThrows(IllegalArgumentException.class, () -> ledger.limit(WORK_B));
		assertTrue(ledger.tryReserve(Map.of(WORK_A, 6L)).isEmpty());

		assertEquals(0L, ledger.used(WORK_A));
		assertEquals(5L, ledger.remaining(WORK_A));
	}

	@Test
	void ledgerConstructionRejectsNegativeLimitsAndInvalidCounters() {
		assertThrows(IllegalArgumentException.class, () -> new CostLedger(Map.of(WORK_A, -1L)));
		assertThrows(IllegalArgumentException.class, () -> new Counter(" ", Unit.WORK));
		assertThrows(NullPointerException.class, () -> new Counter(null, Unit.WORK));
		assertThrows(NullPointerException.class, () -> new Counter("target-a", null));
	}

	@Test
	void longMaxValueLimitDoesNotOverflowRemaining() {
		var ledger = new CostLedger(Map.of(WIRE_A, Long.MAX_VALUE));
		var whole = ledger.tryReserve(Map.of(WIRE_A, Long.MAX_VALUE)).orElseThrow();

		assertEquals(0L, ledger.remaining(WIRE_A));
		assertTrue(ledger.tryReserve(Map.of(WIRE_A, 1L)).isEmpty(), "one over the exact maximum must defer, not wrap");

		whole.commit(Map.of(WIRE_A, Long.MAX_VALUE - 1));

		assertEquals(Long.MAX_VALUE - 1, ledger.used(WIRE_A));
		assertEquals(1L, ledger.remaining(WIRE_A));
		assertTrue(ledger.tryReserve(Map.of(WIRE_A, 1L)).isPresent());
		assertEquals(0L, ledger.remaining(WIRE_A));
	}

	@Test
	void callerMapsAreCopiedAndCannotMoveAccounting() {
		var limits = new LinkedHashMap<Counter, Long>();
		limits.put(WORK_A, 10L);
		var ledger = new CostLedger(limits);
		limits.put(WORK_A, 999L);
		assertEquals(10L, ledger.limit(WORK_A), "construction copies the limits map");

		var request = new LinkedHashMap<Counter, Long>();
		request.put(WORK_A, 6L);
		var ticket = ledger.tryReserve(request).orElseThrow();
		request.put(WORK_A, 999L);

		assertThrows(IllegalArgumentException.class, () -> ticket.commit(Map.of(WORK_A, 7L)),
			"the reservation holds its own copy of the granted amounts");
		ticket.commit(Map.of());

		assertEquals(0L, ledger.used(WORK_A));
		assertEquals(10L, ledger.remaining(WORK_A));
	}
}
