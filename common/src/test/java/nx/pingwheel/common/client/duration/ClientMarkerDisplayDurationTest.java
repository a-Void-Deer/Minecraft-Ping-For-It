package nx.pingwheel.common.client.duration;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.client.marker.ObservedServerClock;
import nx.pingwheel.common.config.ClientConfigBounds;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMarkerDisplayDurationTest {

	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Test
	void configuredZeroFollowsServerButExplicitZeroTicksIsLocal() {
		assertTrue(ClientMarkerDisplayDuration.fromConfig(0).followsServer());
		assertFalse(ClientMarkerDisplayDuration.customTicks(0L).followsServer());
		assertThrows(IllegalArgumentException.class, () -> ClientMarkerDisplayDuration.customTicks(-1L));
	}

	@Test
	void customDurationUsesSecondsAndClampsInvalidValuesSafely() {
		var custom = ClientMarkerDisplayDuration.fromConfig(1);
		assertFalse(custom.followsServer());
		assertEquals(20L, custom.localTicks());
		assertTrue(ClientMarkerDisplayDuration.fromConfig(-1).followsServer());
		int maxSeconds = ClientConfigBounds.MAX_MARKER_DISPLAY_DURATION;
		var clamped = ClientMarkerDisplayDuration.fromConfig(maxSeconds + 1);
		assertFalse(clamped.followsServer());
		assertEquals(maxSeconds * 20L, clamped.localTicks());
	}

	@Test
	void selectedDurationIsFrozenAtReceiptAndCannotResurrectAnExpiredVisual() {
		MarkerSnapshot shorter = snapshot(0L, 140L, 3L);
		int[] selectedSeconds = {1};
		ClientMarkerStore store = new ClientMarkerStore(
			40L, new ObservedServerClock(),
			ignored -> ClientMarkerDisplayDuration.fromConfig(selectedSeconds[0]));

		store.onCreated(shorter, 10L);
		selectedSeconds[0] = 60;

		assertEquals(30L, store.marker(shorter.id()).orElseThrow().displayExpiresAtLocalTick());
		assertTrue(store.renderMarkers().contains(store.marker(shorter.id()).orElseThrow()));
		store.expireFallback(30L);
		assertTrue(store.renderMarkers().isEmpty());
		assertFalse(store.marker(shorter.id()).orElseThrow().isVisuallyActiveAt(30L));

		// A same-id retransmission must preserve the expired per-marker deadline.
		store.onCreated(shorter, 31L);
		assertTrue(store.renderMarkers().isEmpty());
	}

	@Test
	void customLongerDurationRemainsVisibleAfterFrozenServerLifetimeExpires() {
		MarkerSnapshot snapshot = snapshot(20L, 40L, 4L);
		ClientMarkerStore store = new ClientMarkerStore(
			0L, new ObservedServerClock(), ignored -> ClientMarkerDisplayDuration.fromConfig(60));

		store.onCreated(snapshot, 5L);
		store.expireFallback(45L);

		ClientMarker marker = store.marker(snapshot.id()).orElseThrow();
		assertTrue(marker.isStale());
		assertEquals(1205L, marker.displayExpiresAtLocalTick());
		assertTrue(store.renderMarkers().contains(marker));
	}

	private static MarkerSnapshot snapshot(long arrivalTick, long expiresAtTick, long id) {
		Target target = new Target.LocationTarget("minecraft:overworld", id, 64.0, id);
		return new MarkerSnapshot(
			new MarkerId(id),
			OWNER,
			target,
			"location",
			"attention",
			new MarkerAnchor(id, 64.0, id),
			arrivalTick,
			expiresAtTick);
	}
}
