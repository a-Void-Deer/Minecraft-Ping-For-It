package nx.pingwheel.common.marker;

import nx.pingwheel.common.testing.Test;

import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.name.TargetNameJson;

import static nx.pingwheel.common.testing.Assertions.assertEquals;
import static nx.pingwheel.common.testing.Assertions.assertFalse;
import static nx.pingwheel.common.testing.Assertions.assertNotEquals;
import static nx.pingwheel.common.testing.Assertions.assertThrows;
import static nx.pingwheel.common.testing.Assertions.assertTrue;

class AuthoritativeTargetValidationTest {

	private static final Target TARGET =
		new Target.EntityTarget("minecraft:overworld", new UUID(0L, 1L));

	private static ValidatedMarkerTarget validated() {
		return new ValidatedMarkerTarget(
			TARGET,
			TargetMatchContext.none(),
			new MarkerAnchor(0, 0, 0),
			new TargetNameJson("{\"translate\":\"pingforit.target.unknown\"}"));
	}

	@Test
	void acceptedCarriesValidatedTargetAndNoReason() {
		ValidatedMarkerTarget validated = validated();
		AuthoritativeTargetValidation verdict = AuthoritativeTargetValidation.accepted(validated);

		assertTrue(verdict.isAccepted());
		assertEquals(Optional.of(validated), verdict.validatedTarget());
		assertEquals(Optional.empty(), verdict.rejectReason());
	}

	@Test
	void rejectedCarriesReasonAndNoTarget() {
		AuthoritativeTargetValidation verdict =
			AuthoritativeTargetValidation.rejected(MarkerRejectReason.TARGET_GONE);

		assertFalse(verdict.isAccepted());
		assertEquals(Optional.empty(), verdict.validatedTarget());
		assertEquals(Optional.of(MarkerRejectReason.TARGET_GONE), verdict.rejectReason());
	}

	@Test
	void factoriesRejectNulls() {
		assertThrows(NullPointerException.class, () -> AuthoritativeTargetValidation.accepted(null));
		assertThrows(NullPointerException.class, () -> AuthoritativeTargetValidation.rejected(null));
	}

	@Test
	void acceptedVerdictsCompareByValidatedTarget() {
		assertEquals(
			AuthoritativeTargetValidation.accepted(validated()),
			AuthoritativeTargetValidation.accepted(validated()));
		assertEquals(
			AuthoritativeTargetValidation.accepted(validated()).hashCode(),
			AuthoritativeTargetValidation.accepted(validated()).hashCode());
		assertNotEquals(
			AuthoritativeTargetValidation.accepted(validated()),
			AuthoritativeTargetValidation.rejected(MarkerRejectReason.TARGET_GONE));
	}

	@Test
	void rejectedVerdictsCompareByReason() {
		assertEquals(
			AuthoritativeTargetValidation.rejected(MarkerRejectReason.OUT_OF_RANGE),
			AuthoritativeTargetValidation.rejected(MarkerRejectReason.OUT_OF_RANGE));
		assertNotEquals(
			AuthoritativeTargetValidation.rejected(MarkerRejectReason.OUT_OF_RANGE),
			AuthoritativeTargetValidation.rejected(MarkerRejectReason.TARGET_GONE));
	}
}
