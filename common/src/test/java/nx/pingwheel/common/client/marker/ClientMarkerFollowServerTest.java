package nx.pingwheel.common.client.marker;

import java.util.UUID;

import nx.pingwheel.common.client.duration.ClientMarkerDisplayDuration;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class ClientMarkerFollowServerTest {
	@Test
	void equalNumericDurationsInDifferentDomainsDoNotExpireTogether() {
		var serverChoice = ClientMarkerDisplayDuration.fromConfig(0);
		var localChoice = ClientMarkerDisplayDuration.fromConfig(1);
		assertTrue(serverChoice.followsServer());
		assertFalse(localChoice.followsServer());
		assertFalse(ClientMarkerDisplayDuration.customTicks(0L).followsServer());
		var clock = new ObservedServerClock();
		Object world = new Object();
		var snapshot = snapshot(20L, new MarkerAnchor(0.0, 64.0, 0.0));
		var following = ClientMarker.followingServer(snapshot, 10L, clock.receiptOrigin());
		var custom = ClientMarker.from(snapshot, 10L, 2L, localChoice.localTicks());
		clock.observeSample(world, 100L);

		assertTrue(custom.isVisuallyActiveAt(29L));
		assertFalse(custom.isVisuallyActiveAt(30L));
		assertTrue(custom.isFallbackDueAt(32L));
		assertTrue(following.isVisuallyActiveAt(Long.MAX_VALUE));
		assertFalse(following.isFallbackDueAt(Long.MAX_VALUE));

		clock.observeSample(world, 119L);
		assertTrue(following.isVisuallyActiveAt(Long.MAX_VALUE));
		clock.observeSample(world, 120L);
		assertFalse(following.isVisuallyActiveAt(10L));
		assertTrue(custom.isVisuallyActiveAt(10L), "server progress cannot consume a local duration");
	}

	@Test
	void sameIdRefreshUpdatesPayloadAndOnlyRestartsTheSynchronizationFallback() {
		var clock = new ObservedServerClock();
		Object world = new Object();
		var initial = snapshot(4L, new MarkerAnchor(0.0, 64.0, 0.0));
		var marker = ClientMarker.followingServer(initial, 0L, clock.receiptOrigin());
		clock.observeSample(world, 10L);
		clock.observeSample(world, 13L);
		assertTrue(marker.isVisuallyActiveAt(40L));

		var refreshedSnapshot = snapshot(4L, new MarkerAnchor(2.0, 64.0, 0.0));
		var refreshed = marker.refreshed(refreshedSnapshot, 40L, 2L, clock);
		assertEquals(refreshedSnapshot.anchor(), refreshed.anchor());
		clock.observeSample(world, 14L);
		assertFalse(refreshed.isVisuallyActiveAt(40L));
		assertFalse(refreshed.observeFallbackGrace(40L, 2L).isFallbackDueAt(Long.MAX_VALUE));
		clock.observeSample(world, 17L);
		assertFalse(refreshed.observeFallbackGrace(44L, 2L).isFallbackDueAt(Long.MAX_VALUE));

		clock.observeSample(world, 18L);
		var inGrace = refreshed.observeFallbackGrace(45L, 2L);
		assertFalse(inGrace.isFallbackDueAt(46L));
		assertTrue(inGrace.observeFallbackGrace(46L, 2L).isFallbackDueAt(47L),
			"another observation does not restart grace");
		var replayed = inGrace.refreshed(refreshedSnapshot, 46L, 2L, clock);
		assertFalse(replayed.isVisuallyActiveAt(46L));
		assertFalse(replayed.isFallbackDueAt(47L));
	}

	@Test
	void observedDeadlineAndLocalGraceBothHandleTheLongBoundary() {
		var clock = new ObservedServerClock();
		Object world = new Object();
		var marker = ClientMarker.followingServer(snapshot(Long.MAX_VALUE, new MarkerAnchor(0.0, 64.0, 0.0)),
			Long.MAX_VALUE - 5L, clock.receiptOrigin());
		clock.observeSample(world, 0L);
		clock.observeSample(world, Long.MAX_VALUE - 1L);
		assertTrue(marker.isVisuallyActiveAt(Long.MAX_VALUE));
		assertFalse(marker.observeFallbackGrace(Long.MAX_VALUE - 3L, 4L).isFallbackDueAt(Long.MAX_VALUE));

		clock.observeSample(world, Long.MAX_VALUE);
		var inGrace = marker.observeFallbackGrace(Long.MAX_VALUE - 2L, 4L);
		assertFalse(inGrace.isVisuallyActiveAt(Long.MAX_VALUE - 2L));
		assertFalse(inGrace.isFallbackDueAt(Long.MAX_VALUE - 1L));
		assertTrue(inGrace.isFallbackDueAt(Long.MAX_VALUE));
	}

	private static MarkerSnapshot snapshot(long duration, MarkerAnchor anchor) {
		var target = new Target.LocationTarget("minecraft:overworld", 0.0, 64.0, 0.0);
		return new MarkerSnapshot(new MarkerId(1L), new UUID(0L, 2L), target, "location", "attention", anchor, 0L, duration);
	}
}
