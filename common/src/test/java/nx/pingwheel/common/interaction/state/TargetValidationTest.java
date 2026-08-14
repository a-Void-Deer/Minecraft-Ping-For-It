package nx.pingwheel.common.interaction.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetValidationTest {

	@Test
	void validIsSharedSingletonWithoutReason() {
		assertSame(TargetValidation.valid(), TargetValidation.valid());
		assertTrue(TargetValidation.valid().isValid());
		assertTrue(TargetValidation.valid().goneReason().isEmpty());
	}

	@Test
	void goneCarriesExactlyOneReason() {
		TargetValidation gone = TargetValidation.gone(TargetGoneReason.ENTITY_GONE_OR_DEAD);

		assertFalse(gone.isValid());
		assertEquals(TargetGoneReason.ENTITY_GONE_OR_DEAD, gone.goneReason().orElseThrow());
	}

	@Test
	void goneRejectsNullReason() {
		assertThrows(NullPointerException.class, () -> TargetValidation.gone(null));
	}

	@Test
	void equalsAndHashCodeFollowTheVerdict() {
		assertEquals(TargetValidation.valid(), TargetValidation.valid());
		assertEquals(TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED),
			TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED));
		assertEquals(TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED).hashCode(),
			TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED).hashCode());

		assertNotEquals(TargetValidation.valid(), TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED));
		assertNotEquals(TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED),
			TargetValidation.gone(TargetGoneReason.DIMENSION_CHANGED));
	}
}
