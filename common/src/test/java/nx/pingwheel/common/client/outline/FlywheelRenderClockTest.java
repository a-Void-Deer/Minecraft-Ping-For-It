package nx.pingwheel.common.client.outline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlywheelRenderClockTest {
	@Test
	void maskUsesTheExactFlywheelClockAndLeavesBuiltInClockUntouched() {
		float builtInPartialTick = 0.25F;
		float flywheelPartialTick = 0.75F;

		assertEquals(flywheelPartialTick,
			FlywheelRenderClock.maskPartialTick(builtInPartialTick, flywheelPartialTick));
		assertEquals(0.25F, builtInPartialTick);
	}
}
