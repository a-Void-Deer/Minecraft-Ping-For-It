package nx.pingwheel.common.interaction.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
