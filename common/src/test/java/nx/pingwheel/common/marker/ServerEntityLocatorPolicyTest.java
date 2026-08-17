package nx.pingwheel.common.marker;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.EntityLocator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerEntityLocatorPolicyTest {

	private static final UUID ENTITY = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");

	@Test
	void runtimeIdExperienceOrbIsAcceptedAndNormalizedToActualRuntimeId() {
		EntityLocator requested = EntityLocator.runtimeId(17);

		assertEquals(
			ServerEntityLocatorPolicy.Outcome.ACCEPTED,
			ServerEntityLocatorPolicy.classify(requested, true, true, false, true));
		assertEquals(
			EntityLocator.runtimeId(29),
			ServerEntityLocatorPolicy.normalize(true, ENTITY, 29));
	}

	@Test
	void runtimeIdNonOrbIsDisallowedBeforeAliveOrNamePolicy() {
		EntityLocator requested = EntityLocator.runtimeId(17);

		assertEquals(
			ServerEntityLocatorPolicy.Outcome.DISALLOWED_TYPE,
			ServerEntityLocatorPolicy.classify(requested, true, true, false, false));
	}

	@Test
	void missingAndGoneRuntimeEntitiesAreRejected() {
		EntityLocator requested = EntityLocator.runtimeId(17);

		assertEquals(
			ServerEntityLocatorPolicy.Outcome.MISSING,
			ServerEntityLocatorPolicy.classify(requested, false, false, false, true));
		assertEquals(
			ServerEntityLocatorPolicy.Outcome.GONE,
			ServerEntityLocatorPolicy.classify(requested, true, false, false, true));
		assertEquals(
			ServerEntityLocatorPolicy.Outcome.GONE,
			ServerEntityLocatorPolicy.classify(requested, true, true, true, true));
	}

	@Test
	void uuidLookupPolicyRemainsUnchangedAndNormalizesOrdinaryEntities() {
		EntityLocator requested = EntityLocator.uuid(ENTITY);

		assertEquals(
			ServerEntityLocatorPolicy.Outcome.ACCEPTED,
			ServerEntityLocatorPolicy.classify(requested, true, true, false, false));
		assertEquals(
			EntityLocator.uuid(ENTITY),
			ServerEntityLocatorPolicy.normalize(false, ENTITY, 29));
		assertEquals(
			ServerEntityLocatorPolicy.Outcome.MISSING,
			ServerEntityLocatorPolicy.classify(requested, false, true, false, false));
	}
}
