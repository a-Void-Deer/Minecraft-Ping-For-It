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
		assertEquals(ServerConfigBounds.MIN_PING_DURATION, ServerConfigBounds.clampPingDuration(Integer.MIN_VALUE));
		assertEquals(ServerConfigBounds.MIN_PING_DURATION,
			ServerConfigBounds.clampPingDuration(ServerConfigBounds.MIN_PING_DURATION - 1));
		assertEquals(ServerConfigBounds.MIN_PING_DURATION,
			ServerConfigBounds.clampPingDuration(ServerConfigBounds.MIN_PING_DURATION));
	}

	@Test
	void clampSyncDurationUsesTheSharedDurationBounds() {
		assertEquals(ServerConfigBounds.MIN_PING_DURATION, ServerConfigBounds.clampSyncDuration(Integer.MIN_VALUE));
		assertEquals(23, ServerConfigBounds.clampSyncDuration(23));
		assertEquals(ServerConfigBounds.MAX_PING_DURATION, ServerConfigBounds.clampSyncDuration(Integer.MAX_VALUE));
	}

	@Test
	void clampPingDurationEnforcesUpperBound() {
		assertEquals(ServerConfigBounds.MAX_PING_DURATION,
			ServerConfigBounds.clampPingDuration(ServerConfigBounds.MAX_PING_DURATION));
		assertEquals(ServerConfigBounds.MAX_PING_DURATION,
			ServerConfigBounds.clampPingDuration(ServerConfigBounds.MAX_PING_DURATION + 1));
		assertEquals(ServerConfigBounds.MAX_PING_DURATION, ServerConfigBounds.clampPingDuration(Integer.MAX_VALUE));
	}

	@Test
	void clampPingDurationPreservesInRangeValues() {
		assertEquals(2, ServerConfigBounds.clampPingDuration(2));
		assertEquals(7, ServerConfigBounds.clampPingDuration(7));
		int nearMaximum = ServerConfigBounds.MAX_PING_DURATION - 1;
		assertEquals(nearMaximum, ServerConfigBounds.clampPingDuration(nearMaximum));
	}

	@Test
	void clampPingDistanceEnforcesLowerBound() {
		assertEquals(ServerConfigBounds.MIN_PING_DISTANCE, ServerConfigBounds.clampPingDistance(Integer.MIN_VALUE));
		assertEquals(ServerConfigBounds.MIN_PING_DISTANCE,
			ServerConfigBounds.clampPingDistance(ServerConfigBounds.MIN_PING_DISTANCE - 1));
		assertEquals(ServerConfigBounds.MIN_PING_DISTANCE,
			ServerConfigBounds.clampPingDistance(ServerConfigBounds.MIN_PING_DISTANCE));
	}

	@Test
	void clampPingDistanceEnforcesUpperBound() {
		assertEquals(ServerConfigBounds.MAX_PING_DISTANCE,
			ServerConfigBounds.clampPingDistance(ServerConfigBounds.MAX_PING_DISTANCE));
		assertEquals(ServerConfigBounds.MAX_PING_DISTANCE,
			ServerConfigBounds.clampPingDistance(ServerConfigBounds.MAX_PING_DISTANCE + 1));
		assertEquals(ServerConfigBounds.MAX_PING_DISTANCE, ServerConfigBounds.clampPingDistance(Integer.MAX_VALUE));
	}

	@Test
	void clampPingDistancePreservesInRangeValues() {
		assertEquals(2, ServerConfigBounds.clampPingDistance(2));
		assertEquals(1000, ServerConfigBounds.clampPingDistance(1000));
		int nearMaximum = ServerConfigBounds.MAX_PING_DISTANCE - 1;
		assertEquals(nearMaximum, ServerConfigBounds.clampPingDistance(nearMaximum));
	}
}
