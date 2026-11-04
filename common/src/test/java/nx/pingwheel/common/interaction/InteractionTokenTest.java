package nx.pingwheel.common.interaction;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertNotEquals;
import static nx.pingwheel.common.testing.Assertions.assertNotSame;
import static nx.pingwheel.common.testing.Assertions.assertThrows;
import static nx.pingwheel.common.testing.Assertions.assertTrue;

class InteractionTokenTest {

	@Test
	void tokensAreDistinctByObjectIdentity() {
		ActiveInteraction interaction = new ActiveInteraction();

		InteractionToken first = interaction.begin();
		InteractionToken second = interaction.begin();

		assertNotSame(first, second);
		// identity-based equality: two distinct tokens are never equal
		assertNotEquals(first, second);
	}

	@Test
	void sequencesIncreaseMonotonicallyThroughActiveInteraction() {
		ActiveInteraction interaction = new ActiveInteraction();

		long previous = -1L;

		for (int i = 0; i < 100; i++) {
			long sequence = interaction.begin().sequence();

			assertTrue(sequence > previous, "sequence must be monotonically increasing");

			previous = sequence;
		}
	}

	@Test
	void sequenceStartsNonNegative() {
		ActiveInteraction interaction = new ActiveInteraction();

		assertTrue(interaction.begin().sequence() >= 0L);
	}

	@Test
	void rejectsNegativeSequence() {
		assertThrows(IllegalArgumentException.class, () -> new InteractionToken(-1L));
	}
}
