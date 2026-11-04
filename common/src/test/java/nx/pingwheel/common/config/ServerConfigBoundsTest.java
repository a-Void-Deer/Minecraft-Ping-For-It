package nx.pingwheel.common.config;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertEquals;

/**
 * Boundary tests for the pure clamp helpers used by
 * {@link ServerConfig#validate()}. The helpers live in
 * {@link ServerConfigBounds} specifically so these tests never have to
 * initialize {@link ServerConfig}'s static platform handler (which requires a
 * loaded game platform service).
 *
 * <p>Ported from upstream with <strong>one documented deviation</strong>: upstream's own
 * {@code ServerConfigBounds} declares {@code MAX_PING_DURATION = 3600} while its test still
 * asserts the old one-minute (60s) ceiling. The sources are byte-identical to ours, so this is
 * upstream drift; five literals were corrected (sync ceiling 60 -&gt; 3600, ping ceilings
 * 60 -&gt; 61 / 1000 / 3600, and the constant itself 60 -&gt; 3600). The method name still says
 * "OneToSixtySecond" because that is the upstream case identity.
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
	void clampSyncDurationUsesTheSameOneToSixtySecondBounds() {
		assertEquals(1, ServerConfigBounds.clampSyncDuration(Integer.MIN_VALUE));
		assertEquals(1, ServerConfigBounds.clampSyncDuration(0));
		assertEquals(7, ServerConfigBounds.clampSyncDuration(7));
		assertEquals(3600, ServerConfigBounds.clampSyncDuration(Integer.MAX_VALUE));
	}

	@Test
	void clampPingDurationEnforcesUpperBound() {
		assertEquals(60, ServerConfigBounds.clampPingDuration(60));
		assertEquals(61, ServerConfigBounds.clampPingDuration(61));
		assertEquals(1000, ServerConfigBounds.clampPingDuration(1000));
		assertEquals(3600, ServerConfigBounds.clampPingDuration(Integer.MAX_VALUE));
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
		assertEquals(3600, ServerConfigBounds.MAX_PING_DURATION);
		assertEquals(1, ServerConfigBounds.MIN_PING_DISTANCE);
		assertEquals(2048, ServerConfigBounds.MAX_PING_DISTANCE);
	}
}
