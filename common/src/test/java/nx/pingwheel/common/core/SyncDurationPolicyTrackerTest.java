package nx.pingwheel.common.core;

import java.util.UUID;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertFalse;
import static nx.pingwheel.common.testing.Assertions.assertTrue;

class SyncDurationPolicyTrackerTest {

	@Test
	void initialDeliveryIsOncePerConnectionAndBroadcastOnlyForEffectiveChanges() {
		SyncDurationPolicyTracker tracker = new SyncDurationPolicyTracker();
		UUID player = UUID.randomUUID();

		assertTrue(tracker.claimInitialSync(player));
		assertFalse(tracker.claimInitialSync(player));

		assertTrue(tracker.needsBroadcast(7));
		tracker.recordBroadcast(7);
		assertFalse(tracker.needsBroadcast(7));
		assertTrue(tracker.needsBroadcast(8));

		tracker.forget(player);
		assertTrue(tracker.claimInitialSync(player));
	}
}
