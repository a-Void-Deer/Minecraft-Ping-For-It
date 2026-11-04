package nx.pingwheel.common.marker;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertEquals;

class MarkerRemovalReasonTest {

	@Test
	void exposesExactlyFourReasons() {
		assertEquals(4, MarkerRemovalReason.values().length);
	}

	@Test
	void reasonNamesMatchRemovalCauses() {
		assertEquals("CANCELLED", MarkerRemovalReason.CANCELLED.name());
		assertEquals("EXPIRED", MarkerRemovalReason.EXPIRED.name());
		assertEquals("TARGET_INVALID", MarkerRemovalReason.TARGET_INVALID.name());
		assertEquals("OWNER_DISCONNECTED", MarkerRemovalReason.OWNER_DISCONNECTED.name());
	}
}
