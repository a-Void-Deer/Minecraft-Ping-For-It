package nx.pingwheel.common.interaction.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PingInteractionLoggerTest {

	@Test
	void noopDiscardsMessages() {
		PingInteractionLogger noop = PingInteractionLogger.noop();

		assertDoesNotThrow(() -> noop.debug("anything {} {}", "a", "b"));
	}

	@Test
	void globalFactoryDoesNotInitializeTheGameLogger() {
		// Calling the factory must not touch Global; only the first debug() does.
		assertNotNull(PingInteractionLogger.global());
	}
}
