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
import nx.pingwheel.common.name.TargetNameJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerCreateOutcomeTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID OWNER = new UUID(0L, 100L);
	private static final UUID RECIPIENT = new UUID(0L, 10L);
	private static final TargetNameJson NAME = new TargetNameJson("{\"translate\":\"minecraft.zombie\"}");

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
	void acceptedCarriesCreationNameAndNoReason() {
		MarkerCreation creation = newCreation();
		MarkerCreateOutcome outcome = MarkerCreateOutcome.accepted(creation, NAME);

		assertTrue(outcome.isAccepted());
		assertEquals(Optional.of(creation), outcome.creation());
		assertEquals(Optional.of(NAME), outcome.targetName());
		assertEquals(Optional.empty(), outcome.rejectReason());
	}

	@Test
	void rejectedCarriesReasonAndNoCreationAndNoName() {
		MarkerCreateOutcome outcome = MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_PING_TYPE);

		assertFalse(outcome.isAccepted());
		assertEquals(Optional.empty(), outcome.creation());
		assertEquals(Optional.empty(), outcome.targetName());
		assertEquals(Optional.of(MarkerRejectReason.INVALID_PING_TYPE), outcome.rejectReason());
	}

	@Test
	void toStringReportsNamePresenceWithoutNameContent() {
		String secret = "never-leak-this-name-token";
		TargetNameJson secretName = new TargetNameJson("{\"text\":\"" + secret + "\"}");
		MarkerCreateOutcome outcome = MarkerCreateOutcome.accepted(newCreation(), secretName);

		String text = outcome.toString();

		assertTrue(outcome.isAccepted());
		assertTrue(text.contains("namePresent: true"), "toString should indicate name presence");
		assertFalse(text.contains(secret), "toString must not contain the name JSON content");
		assertFalse(text.contains("never-leak-this-name-token"));
	}

	@Test
	void factoriesRejectNulls() {
		assertThrows(NullPointerException.class, () -> MarkerCreateOutcome.accepted(null, NAME));
		assertThrows(NullPointerException.class, () -> MarkerCreateOutcome.accepted(newCreation(), null));
		assertThrows(NullPointerException.class, () -> MarkerCreateOutcome.rejected(null));
	}

	@Test
	void acceptedOutcomesCompareByCreationAndName() {
		assertEquals(
			MarkerCreateOutcome.accepted(newCreation(), NAME),
			MarkerCreateOutcome.accepted(newCreation(), NAME));
		assertEquals(
			MarkerCreateOutcome.accepted(newCreation(), NAME).hashCode(),
			MarkerCreateOutcome.accepted(newCreation(), NAME).hashCode());
		assertNotEquals(
			MarkerCreateOutcome.accepted(newCreation(), NAME),
			MarkerCreateOutcome.accepted(newCreation(), new TargetNameJson("{\"translate\":\"minecraft.skeleton\"}")));
		assertNotEquals(
			MarkerCreateOutcome.accepted(newCreation(), NAME),
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
