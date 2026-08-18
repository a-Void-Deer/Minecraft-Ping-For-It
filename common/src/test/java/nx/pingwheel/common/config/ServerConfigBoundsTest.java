package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boundary tests for the pure clamp helpers used by
 * {@link ServerConfig#validate()}. The helpers live in
 * {@link ServerConfigBounds} specifically so these tests never have to
 * initialize {@link ServerConfig}'s static platform handler (which requires a
 * loaded game platform service).
 */
class ServerConfigBoundsTest {

	@Test
	void clampPingDurationEnforcesLowerBound() {
		assertEquals(1, ServerConfigBounds.clampPingDuration(Integer.MIN_VALUE));
		assertEquals(1, ServerConfigBounds.clampPingDuration(-1000));
		assertEquals(1, ServerConfigBounds.clampPingDuration(0));
		assertEquals(1, ServerConfigBounds.clampPingDuration(1));
	}

	@Test
	void clampPingDurationEnforcesUpperBound() {
		assertEquals(60, ServerConfigBounds.clampPingDuration(60));
		assertEquals(60, ServerConfigBounds.clampPingDuration(61));
		assertEquals(60, ServerConfigBounds.clampPingDuration(1000));
		assertEquals(60, ServerConfigBounds.clampPingDuration(Integer.MAX_VALUE));
	}

	@Test
	void clampPingDurationPreservesInRangeValues() {
		assertEquals(2, ServerConfigBounds.clampPingDuration(2));
		assertEquals(7, ServerConfigBounds.clampPingDuration(7));
		assertEquals(59, ServerConfigBounds.clampPingDuration(59));
	}

	@Test
	void clampPingDistanceEnforcesLowerBound() {
		assertEquals(1, ServerConfigBounds.clampPingDistance(Integer.MIN_VALUE));
		assertEquals(1, ServerConfigBounds.clampPingDistance(-1000));
		assertEquals(1, ServerConfigBounds.clampPingDistance(0));
		assertEquals(1, ServerConfigBounds.clampPingDistance(1));
	}

	@Test
	void clampPingDistanceEnforcesUpperBound() {
		assertEquals(2048, ServerConfigBounds.clampPingDistance(2048));
		assertEquals(2048, ServerConfigBounds.clampPingDistance(2049));
		assertEquals(2048, ServerConfigBounds.clampPingDistance(10000));
		assertEquals(2048, ServerConfigBounds.clampPingDistance(Integer.MAX_VALUE));
	}

	@Test
	void clampPingDistancePreservesInRangeValues() {
		assertEquals(2, ServerConfigBounds.clampPingDistance(2));
		assertEquals(1000, ServerConfigBounds.clampPingDistance(1000));
		assertEquals(2047, ServerConfigBounds.clampPingDistance(2047));
	}

	@Test
	void boundaryConstantsAreConsistent() {
		assertEquals(1, ServerConfigBounds.MIN_PING_DURATION);
		assertEquals(60, ServerConfigBounds.MAX_PING_DURATION);
		assertEquals(1, ServerConfigBounds.MIN_PING_DISTANCE);
		assertEquals(2048, ServerConfigBounds.MAX_PING_DISTANCE);
	}
}
