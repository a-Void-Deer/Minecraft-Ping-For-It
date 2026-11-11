package nx.pingwheel.common.presentation.inventory;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class InventoryDecisionTest {

	@Test
	void acceptCarriesOnlyAValue() {
		InventoryDecision<String> decision = InventoryDecision.accept("entry");
		assertTrue(decision.accepted());
		assertFalse(decision.deferred());
		assertFalse(decision.unsupported());
		assertFalse(decision.rejected());
		assertEquals(InventoryDecision.Kind.ACCEPT, decision.kind());
		assertEquals(InventoryOutcome.ACCEPTED, decision.outcome());
		assertEquals("entry", decision.value());
		assertNull(decision.reason());
		assertNull(decision.impact());
	}

	@Test
	void nonAcceptKindsAreMutuallyExclusive() {
		for (InventoryDecision.Kind kind : new InventoryDecision.Kind[] {
			InventoryDecision.Kind.DEFER, InventoryDecision.Kind.UNSUPPORTED, InventoryDecision.Kind.REJECT
		}) {
			InventoryDecision<String> decision = switch (kind) {
				case DEFER -> InventoryDecision.defer(InventoryFailureReason.REQUEST_CAPACITY, InventoryImpact.DEGRADED);
				case UNSUPPORTED -> InventoryDecision.unsupported(InventoryFailureReason.UNSUPPORTED_CONTEXT,
					InventoryImpact.PING_UNAVAILABLE);
				case REJECT -> InventoryDecision.reject(InventoryFailureReason.INVALID_REQUEST, InventoryImpact.DETAIL_ONLY);
				case ACCEPT -> throw new AssertionError("Accept is covered separately");
			};
			assertEquals(kind, decision.kind());
			assertFalse(decision.accepted());
			assertEquals(kind == InventoryDecision.Kind.DEFER, decision.deferred());
			assertEquals(kind == InventoryDecision.Kind.UNSUPPORTED, decision.unsupported());
			assertEquals(kind == InventoryDecision.Kind.REJECT, decision.rejected());
			assertNull(decision.value());
			assertEquals(kind == InventoryDecision.Kind.DEFER ? InventoryOutcome.DEFERRED
				: kind == InventoryDecision.Kind.UNSUPPORTED ? InventoryOutcome.UNSUPPORTED
				: InventoryOutcome.REJECTED, decision.outcome());
		}
	}

	@Test
	void impactIsSuppliedByTheDecisionNotDerivedFromTheReason() {
		InventoryFailureReason shared = InventoryFailureReason.REQUEST_CAPACITY;
		InventoryDecision<String> deferred = InventoryDecision.defer(shared, InventoryImpact.DETAIL_ONLY);
		InventoryDecision<String> rejected = InventoryDecision.reject(shared, InventoryImpact.PING_UNAVAILABLE);
		assertEquals(InventoryImpact.DETAIL_ONLY, deferred.impact());
		assertEquals(InventoryImpact.PING_UNAVAILABLE, rejected.impact());
		assertEquals(shared, deferred.reason());
		assertEquals(shared, rejected.reason());
	}

	@Test
	void invalidFactoriesRejectIncompleteDecisions() {
		assertThrows(NullPointerException.class, () -> InventoryDecision.accept(null));
		assertThrows(NullPointerException.class, () -> InventoryDecision.defer(null, InventoryImpact.DEGRADED));
		assertThrows(NullPointerException.class, () -> InventoryDecision.defer(InventoryFailureReason.REQUEST_CAPACITY, null));
		assertThrows(NullPointerException.class, () -> InventoryDecision.unsupported(null, InventoryImpact.PING_UNAVAILABLE));
		assertThrows(NullPointerException.class, () -> InventoryDecision.reject(InventoryFailureReason.INVALID_REQUEST, null));
	}
}
