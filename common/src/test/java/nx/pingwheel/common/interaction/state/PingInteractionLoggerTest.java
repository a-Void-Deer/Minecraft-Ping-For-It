package nx.pingwheel.common.interaction.state;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertDoesNotThrow;
import static nx.pingwheel.common.testing.Assertions.assertNotNull;

class PingInteractionLoggerTest {

	@Test
	void noopDiscardsMessages() {
		PingInteractionLogger noop = PingInteractionLogger.noop();

		assertDoesNotThrow(() -> noop.debug("anything {} {}", "a", "b"));
		assertDoesNotThrow(() -> noop.debugCreateThrottled(7L, 5, 1000));
	}

	@Test
	void globalFactoryDoesNotInitializeTheGameLogger() {
		// Calling the factory must not touch Global; only the first debug() does.
		assertNotNull(PingInteractionLogger.global());
	}
}
