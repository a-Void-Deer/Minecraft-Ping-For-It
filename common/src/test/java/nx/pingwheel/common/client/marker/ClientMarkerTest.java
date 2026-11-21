package nx.pingwheel.common.client.marker;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMarkerTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID OWNER = new UUID(0L, 100L);
	private static final UUID ENTITY = new UUID(0L, 200L);

	private static MarkerSnapshot snapshot(MarkerId id, Target target, long arrival, long expires) {
		return new MarkerSnapshot(id, OWNER, target, "entity", "attention", new MarkerAnchor(0, 0, 0), arrival, expires);
	}

	@Test
	void fromMapsAllSnapshotFieldsAndLocalTick() {
		MarkerId id = new MarkerId(42L);
		Target target = new Target.EntityTarget(OVERWORLD, ENTITY);
		MarkerAnchor anchor = new MarkerAnchor(1.5, 64.0, -3.25);
		MarkerSnapshot source = new MarkerSnapshot(
			id, OWNER, target, "entity", "attention", anchor, 100L, 300L);

		ClientMarker marker = ClientMarker.withLocalDuration(source, 250L, 10L, 200L);

		assertEquals(id, marker.id());
		assertEquals(OWNER, marker.owner());
		assertEquals(target, marker.target());
		assertEquals("entity", marker.targetTypeId());
		assertEquals("attention", marker.pingTypeId());
		assertEquals(anchor, marker.anchor());
		assertEquals(100L, marker.arrivalTick());
		assertEquals(300L, marker.expiresAtTick());
		assertEquals(250L, marker.receivedAtLocalTick());
		assertEquals(450L, marker.displayExpiresAtLocalTick());
		assertEquals(ClientMarkerState.SYNCHRONIZED, marker.state());
	}

	@Test
	void fromAddsGraceToServerLifetime() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 10L, 110L);

		ClientMarker marker = ClientMarker.withLocalDuration(source, 50L, 10L, 100L);

		// 50 + (110 - 10) + 10 = 160
		assertEquals(160L, marker.fallbackExpiresAtLocalTick());
		assertEquals(150L, marker.displayExpiresAtLocalTick());
	}

	@Test
	void fromCanUseAnIndependentDisplayDuration() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 10L, 110L);

		ClientMarker marker = ClientMarker.withLocalDuration(source, 50L, 10L, 5L);

		assertEquals(160L, marker.fallbackExpiresAtLocalTick());
		assertEquals(55L, marker.displayExpiresAtLocalTick());
		assertTrue(marker.isVisuallyActiveAt(54L));
		assertFalse(marker.isVisuallyActiveAt(55L));
	}

	@Test
	void staleStatePreservesBothDeadlinesAndPayload() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 10L, 110L);
		ClientMarker marker = ClientMarker.withLocalDuration(source, 50L, 10L, 200L);

		ClientMarker stale = marker.asStale();

		assertEquals(ClientMarkerState.STALE, stale.state());
		assertTrue(stale.isStale());
		assertEquals(marker.fallbackExpiresAtLocalTick(), stale.fallbackExpiresAtLocalTick());
		assertEquals(marker.displayExpiresAtLocalTick(), stale.displayExpiresAtLocalTick());
		assertEquals(marker.target(), stale.target());
	}

	@Test
	void fromMinimumFallbackDurationIsOneTickPlusGrace() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 10L, 11L);

		ClientMarker marker = ClientMarker.withLocalDuration(source, 50L, 0L, 1L);

		// 50 + max(1, 11 - 10) + 0 = 51
		assertEquals(51L, marker.fallbackExpiresAtLocalTick());
	}

	@Test
	void fromSaturatesWhenServerLifetimeOverflows() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 0L, Long.MAX_VALUE);

		ClientMarker marker = ClientMarker.withLocalDuration(source, 5L, 10L, Long.MAX_VALUE);

		assertEquals(Long.MAX_VALUE, marker.fallbackExpiresAtLocalTick());
		assertEquals(Long.MAX_VALUE, marker.displayExpiresAtLocalTick());
	}

	@Test
	void fromSaturatesWhenGraceOverflows() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 0L, 5L);

		ClientMarker marker = ClientMarker.withLocalDuration(source, 100L, Long.MAX_VALUE, 5L);

		assertEquals(Long.MAX_VALUE, marker.fallbackExpiresAtLocalTick());
	}

	@Test
	void fromSaturatesWhenLocalTickAdditionOverflows() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 0L, 1000L);

		ClientMarker marker = ClientMarker.withLocalDuration(source, Long.MAX_VALUE - 5L, 0L, 1000L);

		assertEquals(Long.MAX_VALUE, marker.fallbackExpiresAtLocalTick());
	}

	@Test
	void fromRejectsNullSnapshot() {
		assertThrows(NullPointerException.class, () -> ClientMarker.withLocalDuration(null, 0L, 0L, 1L));
	}

	@Test
	void fromRejectsNegativeLocalTick() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 1L, 10L);

		assertThrows(IllegalArgumentException.class, () -> ClientMarker.withLocalDuration(source, -1L, 0L, 1L));
	}

	@Test
	void fromRejectsNegativeGraceTicks() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 1L, 10L);

		assertThrows(IllegalArgumentException.class, () -> ClientMarker.withLocalDuration(source, 0L, -1L, 1L));
	}

	@Test
	void zeroLocalDurationExpiresAtReceiptAndNegativeDurationIsRejected() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 1L, 10L);

		ClientMarker marker = ClientMarker.withLocalDuration(source, 5L, 0L, 0L);
		assertFalse(marker.followsServer());
		assertFalse(marker.isVisuallyActiveAt(5L));
		assertThrows(IllegalArgumentException.class, () -> ClientMarker.withLocalDuration(source, 5L, 0L, -1L));
	}

	@Test
	void preservesSnapshotEntityKey() {
		Target target = new Target.EntityTarget(OVERWORLD, ENTITY);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 1L, 10L);

		ClientMarker marker = ClientMarker.withLocalDuration(source, 0L, 0L, 1L);

		assertEquals(new TargetKey.EntityKey(OVERWORLD, ENTITY), marker.targetKey());
	}

	@Test
	void preservesSnapshotBlockKey() {
		Target target = new Target.BlockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 1L, 10L);

		ClientMarker marker = ClientMarker.withLocalDuration(source, 0L, 0L, 1L);

		assertEquals(new TargetKey.BlockKey(OVERWORLD, 1, 2, 3, "minecraft:stone"), marker.targetKey());
	}

	@Test
	void preservesSnapshotLocationKey() {
		Target target = new Target.LocationTarget(OVERWORLD, 1.5, 2.5, 3.5);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 1L, 10L);

		ClientMarker marker = ClientMarker.withLocalDuration(source, 0L, 0L, 1L);

		assertEquals(new TargetKey.LocationKey(OVERWORLD, 1.5, 2.5, 3.5), marker.targetKey());
	}

	@Test
	void rejectsNullComponents() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		TargetKey key = TargetKey.from(target);
		MarkerAnchor anchor = new MarkerAnchor(0, 0, 0);
		MarkerId id = new MarkerId(1L);
		var state = ClientMarkerState.SYNCHRONIZED;

		assertThrows(NullPointerException.class,
			() -> new ClientMarker(null, OWNER, target, key, "location", "go_to", anchor, 1L, 100L, 0L, 100L, 100L, state, null));
		assertThrows(NullPointerException.class,
			() -> new ClientMarker(id, null, target, key, "location", "go_to", anchor, 1L, 100L, 0L, 100L, 100L, state, null));
		assertThrows(NullPointerException.class,
			() -> new ClientMarker(id, OWNER, null, key, "location", "go_to", anchor, 1L, 100L, 0L, 100L, 100L, state, null));
		assertThrows(NullPointerException.class,
			() -> new ClientMarker(id, OWNER, target, key, null, "go_to", anchor, 1L, 100L, 0L, 100L, 100L, state, null));
		assertThrows(NullPointerException.class,
			() -> new ClientMarker(id, OWNER, target, key, "location", null, anchor, 1L, 100L, 0L, 100L, 100L, state, null));
		assertThrows(NullPointerException.class,
			() -> new ClientMarker(id, OWNER, target, key, "location", "go_to", null, 1L, 100L, 0L, 100L, 100L, state, null));
	}

	@Test
	void rejectsBlankTypeIds() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);

		assertThrows(IllegalArgumentException.class,
			() -> new ClientMarker(new MarkerId(1L), OWNER, target, TargetKey.from(target), " ", "go_to",
				new MarkerAnchor(0, 0, 0), 1L, 100L, 0L, 100L, 100L, ClientMarkerState.SYNCHRONIZED, null));
		assertThrows(IllegalArgumentException.class,
			() -> new ClientMarker(new MarkerId(1L), OWNER, target, TargetKey.from(target), "location", "",
				new MarkerAnchor(0, 0, 0), 1L, 100L, 0L, 100L, 100L, ClientMarkerState.SYNCHRONIZED, null));
	}

	@Test
	void rejectsNegativeArrivalTick() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);

		assertThrows(IllegalArgumentException.class,
			() -> new ClientMarker(new MarkerId(1L), OWNER, target, TargetKey.from(target), "location", "go_to",
				new MarkerAnchor(0, 0, 0), -1L, 100L, 0L, 100L, 100L, ClientMarkerState.SYNCHRONIZED, null));
	}

	@Test
	void rejectsExpiresNotAfterArrival() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);

		assertThrows(IllegalArgumentException.class,
			() -> new ClientMarker(new MarkerId(1L), OWNER, target, TargetKey.from(target), "location", "go_to",
				new MarkerAnchor(0, 0, 0), 10L, 10L, 0L, 100L, 100L, ClientMarkerState.SYNCHRONIZED, null));
		assertThrows(IllegalArgumentException.class,
			() -> new ClientMarker(new MarkerId(1L), OWNER, target, TargetKey.from(target), "location", "go_to",
				new MarkerAnchor(0, 0, 0), 10L, 5L, 0L, 100L, 100L, ClientMarkerState.SYNCHRONIZED, null));
	}

	@Test
	void rejectsNegativeReceivedAtLocalTick() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);

		assertThrows(IllegalArgumentException.class,
			() -> new ClientMarker(new MarkerId(1L), OWNER, target, TargetKey.from(target), "location", "go_to",
				new MarkerAnchor(0, 0, 0), 1L, 100L, -1L, 100L, 100L, ClientMarkerState.SYNCHRONIZED, null));
	}

	@Test
	void rejectsFallbackExpiryBeforeReceivedTick() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);

		assertThrows(IllegalArgumentException.class,
			() -> new ClientMarker(new MarkerId(1L), OWNER, target, TargetKey.from(target), "location", "go_to",
				new MarkerAnchor(0, 0, 0), 1L, 100L, 50L, 49L, 100L, ClientMarkerState.SYNCHRONIZED, null));
	}

	@Test
	void equalValuesAreEqualRecords() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		MarkerSnapshot source = snapshot(new MarkerId(1L), target, 1L, 10L);

		assertEquals(ClientMarker.withLocalDuration(source, 5L, 3L, 9L),
			ClientMarker.withLocalDuration(source, 5L, 3L, 9L));
	}
}
