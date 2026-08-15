package nx.pingwheel.common.marker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetType;
import nx.pingwheel.common.domain.TargetTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerCreateOutcomeTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID OWNER = new UUID(0L, 100L);
	private static final UUID RECIPIENT = new UUID(0L, 10L);

	private static MarkerCreation newCreation() {
		ServerMarkerStore store = new ServerMarkerStore(new MarkerIdSource());

		TargetType entityType = TargetTypeCatalog.builtIn().findById("entity").orElseThrow();
		PingType attention = PingTypeCatalog.builtIn().findById("attention").orElseThrow();

		return store.create(
			OWNER,
			new Target.EntityTarget(OVERWORLD, new UUID(1L, 1L)),
			entityType,
			attention,
			new MarkerAnchor(0, 0, 0),
			10L,
			110L,
			List.of(RECIPIENT));
	}

	@Test
	void acceptedCarriesCreationAndNoReason() {
		MarkerCreation creation = newCreation();
		MarkerCreateOutcome outcome = MarkerCreateOutcome.accepted(creation);

		assertTrue(outcome.isAccepted());
		assertEquals(Optional.of(creation), outcome.creation());
		assertEquals(Optional.empty(), outcome.rejectReason());
	}

	@Test
	void rejectedCarriesReasonAndNoCreation() {
		MarkerCreateOutcome outcome = MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_PING_TYPE);

		assertFalse(outcome.isAccepted());
		assertEquals(Optional.empty(), outcome.creation());
		assertEquals(Optional.of(MarkerRejectReason.INVALID_PING_TYPE), outcome.rejectReason());
	}

	@Test
	void factoriesRejectNulls() {
		assertThrows(NullPointerException.class, () -> MarkerCreateOutcome.accepted(null));
		assertThrows(NullPointerException.class, () -> MarkerCreateOutcome.rejected(null));
	}

	@Test
	void acceptedOutcomesCompareByCreation() {
		assertEquals(MarkerCreateOutcome.accepted(newCreation()), MarkerCreateOutcome.accepted(newCreation()));
		assertEquals(
			MarkerCreateOutcome.accepted(newCreation()).hashCode(),
			MarkerCreateOutcome.accepted(newCreation()).hashCode());
		assertNotEquals(
			MarkerCreateOutcome.accepted(newCreation()),
			MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_REQUEST));
	}

	@Test
	void rejectedOutcomesCompareByReason() {
		assertEquals(
			MarkerCreateOutcome.rejected(MarkerRejectReason.NOT_FOUND),
			MarkerCreateOutcome.rejected(MarkerRejectReason.NOT_FOUND));
		assertNotEquals(
			MarkerCreateOutcome.rejected(MarkerRejectReason.NOT_FOUND),
			MarkerCreateOutcome.rejected(MarkerRejectReason.NOT_OWNER));
	}
}
