package nx.pingwheel.common.interaction.state;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertTrue;

class InteractionTimeSourceTest {

	@Test
	void systemClockIsMonotonicAndNonNegative() {
		InteractionTimeSource clock = InteractionTimeSource.system();

		long first = clock.nowMillis();
		long second = clock.nowMillis();

		assertTrue(first >= 0L);
		assertTrue(second >= first, "monotonic clock must never move backwards");
	}
}
