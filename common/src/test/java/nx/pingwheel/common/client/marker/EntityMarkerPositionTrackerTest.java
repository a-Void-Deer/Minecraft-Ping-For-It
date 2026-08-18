package nx.pingwheel.common.client.marker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityMarkerPositionTrackerTest {

	private static EntityMarkerPositionTracker.Position position(double x, double y, double z) {
		return new EntityMarkerPositionTracker.Position(x, y, z);
	}

	@Test
	void usesAuthoritativeAnchorUntilEntityFirstResolves() {
		EntityMarkerPositionTracker tracker = new EntityMarkerPositionTracker();
		EntityMarkerPositionTracker.Position anchor = position(1.0, 2.0, 3.0);

		assertEquals(anchor, tracker.resolve(anchor, null));
	}

	@Test
	void missingEntityStaysAtLastLivePosition() {
		EntityMarkerPositionTracker tracker = new EntityMarkerPositionTracker();
		EntityMarkerPositionTracker.Position anchor = position(1.0, 2.0, 3.0);
		EntityMarkerPositionTracker.Position live = position(10.0, 20.0, 30.0);

		tracker.resolve(anchor, live);

		assertEquals(live, tracker.resolve(anchor, null));
	}

	@Test
	void resolvingEntityAgainResumesFollowing() {
		EntityMarkerPositionTracker tracker = new EntityMarkerPositionTracker();
		EntityMarkerPositionTracker.Position anchor = position(1.0, 2.0, 3.0);
		EntityMarkerPositionTracker.Position firstLive = position(10.0, 20.0, 30.0);
		EntityMarkerPositionTracker.Position secondLive = position(40.0, 50.0, 60.0);

		tracker.resolve(anchor, firstLive);
		tracker.resolve(anchor, null);

		assertEquals(secondLive, tracker.resolve(anchor, secondLive));
		assertEquals(secondLive, tracker.resolve(anchor, null));
	}

	@Test
	void resetRestoresAuthoritativeAnchorFallback() {
		EntityMarkerPositionTracker tracker = new EntityMarkerPositionTracker();
		EntityMarkerPositionTracker.Position anchor = position(1.0, 2.0, 3.0);

		tracker.resolve(anchor, position(10.0, 20.0, 30.0));
		tracker.reset();

		assertEquals(anchor, tracker.resolve(anchor, null));
	}
}
