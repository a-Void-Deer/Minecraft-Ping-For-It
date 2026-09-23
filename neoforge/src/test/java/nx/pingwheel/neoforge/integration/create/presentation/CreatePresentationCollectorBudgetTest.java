package nx.pingwheel.neoforge.integration.create.presentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CreatePresentationCollectorBudgetTest {
	@Test
	void invalidOrOverflowingShapesCannotStartLoadedBlockChecks() {
		var limits = new CreateSamplingLimits(128, 128, 16, 16, 256, 2048);
		assertTrue(limits.permitsShape(3, 3, 3, 256));
		assertFalse(limits.permitsShape(0, 1, 1, 256));
		assertFalse(limits.permitsShape(-1, 1, 1, 256));
		assertFalse(limits.permitsShape(1, 1, 129, 256));
		assertFalse(limits.permitsShape(Integer.MAX_VALUE,
			Integer.MAX_VALUE, Integer.MAX_VALUE, 256));
		assertFalse(limits.permitsShape(6, 6, 6, 256));
		assertFalse(limits.permitsShape(3, 3, 3, 16));
	}

	@Test
	void suppliedLimitsHaveAbsoluteUpperBounds() {
		assertThrows(IllegalArgumentException.class,
			() -> new CreateSamplingLimits(0, 1, 1, 1, 1, 1));
		assertThrows(IllegalArgumentException.class,
			() -> new CreateSamplingLimits(128, 513, 1, 1, 256, 2048));
		assertThrows(IllegalArgumentException.class,
			() -> new CreateSamplingLimits(128, 128, 1, 1, 256, 8193));
	}

	@Test
	void versionGateRejectsAbsentOrUntestedApi() {
		assertTrue(CreatePresentationAvailability.testedVersion("6.0.10"));
		assertTrue(CreatePresentationAvailability.testedVersion("6.0.10-281"));
		assertFalse(CreatePresentationAvailability.testedVersion(null));
		assertFalse(CreatePresentationAvailability.testedVersion("6.0.10-282"));
	}
}
