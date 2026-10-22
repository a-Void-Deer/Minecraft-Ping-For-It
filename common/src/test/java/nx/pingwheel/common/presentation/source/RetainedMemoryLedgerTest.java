package nx.pingwheel.common.presentation.source;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetainedMemoryLedgerTest {

	@Test
	void actualCostIsHeldUntilCloseAndCloseIsIdempotent() {
		var ledger = new RetainedMemoryLedger(100L);
		var ticket = ledger.tryReserve(30L).orElseThrow();

		assertEquals(30L, ledger.reserved());
		assertEquals(70L, ledger.remaining());

		ticket.commit(24L);

		assertEquals(0L, ledger.reserved());
		assertEquals(24L, ledger.retained(), "the actual cost stays charged after settle");
		assertEquals(76L, ledger.remaining());

		ticket.close();
		ticket.close();

		assertEquals(0L, ledger.retained());
		assertEquals(100L, ledger.remaining());
		assertThrows(IllegalStateException.class, () -> ticket.commit(1L));
	}

	@Test
	void closingAnUncommittedReservationReleasesItsWholeUpperBound() {
		var ledger = new RetainedMemoryLedger(100L);
		var ticket = ledger.tryReserve(40L).orElseThrow();
		assertEquals(60L, ledger.remaining());

		ticket.close();
		ticket.close();

		assertEquals(0L, ledger.reserved());
		assertEquals(0L, ledger.retained());
		assertEquals(100L, ledger.remaining());
	}

	@Test
	void capReductionDoesNotForgetLiveReservations() {
		var ledger = new RetainedMemoryLedger(100L);
		var shared = ledger.tryReserve(60L).orElseThrow();
		shared.commit(60L);
		var cursor = ledger.tryReserve(20L).orElseThrow();
		cursor.commit(20L);

		ledger.setCap(50L);

		assertEquals(50L, ledger.cap());
		assertEquals(80L, ledger.retained(), "live reservations survive the reduction");
		assertEquals(-30L, ledger.remaining());
		assertTrue(ledger.tryReserve(1L).isEmpty(), "an over-cap ledger admits nothing new");
		assertEquals(80L, ledger.retained());

		cursor.close();

		assertEquals(60L, ledger.retained());
		assertEquals(-10L, ledger.remaining());
		assertTrue(ledger.tryReserve(1L).isEmpty(), "admission stays deferred while over cap");

		shared.close();

		assertEquals(0L, ledger.retained());
		assertEquals(50L, ledger.remaining());
		assertTrue(ledger.tryReserve(50L).isPresent());
		assertEquals(0L, ledger.remaining());
	}

	@Test
	void sharedObjectIsChargedOnceAndEachRecipientCursorSeparately() {
		var ledger = new RetainedMemoryLedger(100L);
		var shared = ledger.tryReserve(40L).orElseThrow();
		shared.commit(40L);

		var cursorA = ledger.tryReserve(6L).orElseThrow();
		var cursorB = ledger.tryReserve(6L).orElseThrow();
		cursorA.commit(5L);
		cursorB.commit(5L);

		assertEquals(50L, ledger.retained(), "one shared object plus two independent recipient cursors");
		assertEquals(50L, ledger.remaining());

		shared.close();
		cursorA.close();
		cursorB.close();

		assertEquals(0L, ledger.retained());
		assertEquals(100L, ledger.remaining());
	}

	@Test
	void deniedAdmissionNeverRunsTheReadEncodeOrAllocationCallback() {
		var ledger = new RetainedMemoryLedger(64L);
		var held = ledger.tryReserve(64L).orElseThrow();
		var reads = new AtomicInteger();
		var encodes = new AtomicInteger();

		assertFalse(admitThenWork(ledger, 8L, reads, encodes));
		assertEquals(0, reads.get(), "a denied reservation must not read");
		assertEquals(0, encodes.get(), "a denied reservation must not encode or allocate");
		assertEquals(64L, ledger.reserved(), "a denied attempt must not move the ledger");
		assertEquals(0L, ledger.retained());

		held.close();

		assertTrue(admitThenWork(ledger, 8L, reads, encodes));
		assertEquals(1, reads.get());
		assertEquals(1, encodes.get());
		assertEquals(0L, ledger.retained(), "the demonstration releases its settled ticket");
	}

	/** Documented admission order: reserve retained memory before any read, encode, or allocation. */
	private static boolean admitThenWork(RetainedMemoryLedger ledger, long upperBound,
		AtomicInteger reads, AtomicInteger encodes) {
		var ticket = ledger.tryReserve(upperBound);
		if (ticket.isEmpty()) return false;
		try {
			reads.incrementAndGet();
			encodes.incrementAndGet();
			ticket.get().commit(upperBound);
			return true;
		} finally {
			ticket.get().close();
		}
	}

	@Test
	void rejectedRequestsLeaveTheLedgerUntouched() {
		var ledger = new RetainedMemoryLedger(100L);
		var settled = ledger.tryReserve(40L).orElseThrow();
		settled.commit(30L);
		var provisional = ledger.tryReserve(10L).orElseThrow();

		assertThrows(IllegalArgumentException.class, () -> ledger.tryReserve(-1L));
		assertThrows(IllegalArgumentException.class, () -> ledger.setCap(-1L));
		assertThrows(IllegalArgumentException.class, () -> provisional.commit(-1L));
		assertThrows(IllegalArgumentException.class, () -> provisional.commit(11L));
		assertThrows(IllegalStateException.class, () -> settled.commit(1L));

		assertEquals(30L, ledger.retained());
		assertEquals(10L, ledger.reserved(), "a rejected commit must leave the reservation standing");
		assertEquals(60L, ledger.remaining());

		provisional.close();

		assertEquals(30L, ledger.retained());
		assertEquals(70L, ledger.remaining());
	}
}
