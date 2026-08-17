package nx.pingwheel.common.interaction.state;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.PingCaptureCoordinator;
import nx.pingwheel.common.interaction.PingCaptureLogger;
import nx.pingwheel.common.interaction.cancel.CancelCandidatePicker;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PingInteractionConfigValidationTest {

	@Test
	void configuredSupplierRejectsValuesOutsideClientBounds() {
		ActiveInteraction interaction = new ActiveInteraction();
		PingCaptureCoordinator coordinator = new PingCaptureCoordinator(
			DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()),
			interaction,
			PingCaptureLogger.noop());
		AtomicLong holdMillis = new AtomicLong(99L);

		PingInteractionStateMachine machine = new PingInteractionStateMachine(
			coordinator,
			interaction,
			() -> 0L,
			resolved -> TargetValidation.valid(),
			new CancelCandidatePicker(),
			PingInteractionLogger.noop(),
			holdMillis::get,
			() -> 1000L);

		assertThrows(IllegalArgumentException.class, machine::press);
	}
}
