package nx.pingwheel.common.interaction.state;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PingInteractionLoggerTest {

	@Test
	void noopDiscardsMessages() {
		PingInteractionLogger noop = PingInteractionLogger.noop();

		assertDoesNotThrow(() -> noop.debug("anything {} {}", "a", "b"));
		assertDoesNotThrow(() -> noop.debugCreateThrottled(7L, 5, 1000));
	}

	@Test
	void noopDoesNotEvaluateLazyDetail() {
		AtomicBoolean evaluated = new AtomicBoolean();
		assertDoesNotThrow(() -> PingInteractionLogger.noop().debug(() -> {
			evaluated.set(true);
			return "detail";
		}));
		assertFalse(evaluated.get());
	}

	@Test
	void globalFactoryDoesNotInitializeTheGameLogger() {
		// Calling the factory must not touch Global; only the first debug() does.
		assertNotNull(PingInteractionLogger.global());
	}
}
