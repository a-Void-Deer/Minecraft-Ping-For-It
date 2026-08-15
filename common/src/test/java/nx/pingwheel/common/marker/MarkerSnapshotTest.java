package nx.pingwheel.common.marker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetType;
import nx.pingwheel.common.domain.TargetTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarkerSnapshotTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID RECIPIENT = UUID.randomUUID();

	private static final PingTypeCatalog PING_TYPES = PingTypeCatalog.builtIn();
	private static final TargetTypeCatalog TARGET_TYPES = TargetTypeCatalog.builtIn();

	private static PingType ping(String id) {
		return PING_TYPES.findById(id).orElseThrow();
	}

	private static TargetType type(String id) {
		return TARGET_TYPES.findById(id).orElseThrow();
	}

	private static ServerMarker marker(
		MarkerId id, Target target, TargetType type, PingType pingType, MarkerAnchor anchor, long arrival, long expires) {
		return new ServerMarker(id, OWNER, target, type, pingType, anchor, arrival, expires, List.of(RECIPIENT));
	}

	private static MarkerSnapshot snapshot(
		MarkerId id, Target target, String targetTypeId, String pingTypeId, long arrival, long expires) {
		return new MarkerSnapshot(id, OWNER, target, targetTypeId, pingTypeId, new MarkerAnchor(0, 0, 0), arrival, expires);
	}

	@Test
	void fromServerMarkerMapsAllFields() {
		MarkerId id = new MarkerId(42L);
		Target target = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());
		MarkerAnchor anchor = new MarkerAnchor(1.5, 64.0, -3.25);
		ServerMarker marker = marker(id, target, type("entity"), ping("attention"), anchor, 100L, 300L);

		MarkerSnapshot snapshot = MarkerSnapshot.from(marker);

		assertEquals(id, snapshot.id());
		assertEquals(OWNER, snapshot.owner());
		assertEquals(target, snapshot.target());
		assertEquals("entity", snapshot.targetTypeId());
		assertEquals("attention", snapshot.pingTypeId());
		assertEquals(anchor, snapshot.anchor());
		assertEquals(100L, snapshot.arrivalTick());
		assertEquals(300L, snapshot.expiresAtTick());
	}

	@Test
	void fromServerMarkerRejectsNull() {
		assertThrows(NullPointerException.class, () -> MarkerSnapshot.from(null));
	}

	@Test
	void rejectsNullComponents() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);
		MarkerAnchor anchor = new MarkerAnchor(0, 0, 0);

		assertThrows(NullPointerException.class,
			() -> new MarkerSnapshot(null, OWNER, target, "location", "go_to", anchor, 1L, 100L));
		assertThrows(NullPointerException.class,
			() -> new MarkerSnapshot(new MarkerId(1L), null, target, "location", "go_to", anchor, 1L, 100L));
		assertThrows(NullPointerException.class,
			() -> new MarkerSnapshot(new MarkerId(1L), OWNER, null, "location", "go_to", anchor, 1L, 100L));
		assertThrows(NullPointerException.class,
			() -> new MarkerSnapshot(new MarkerId(1L), OWNER, target, null, "go_to", anchor, 1L, 100L));
		assertThrows(NullPointerException.class,
			() -> new MarkerSnapshot(new MarkerId(1L), OWNER, target, "location", null, anchor, 1L, 100L));
		assertThrows(NullPointerException.class,
			() -> new MarkerSnapshot(new MarkerId(1L), OWNER, target, "location", "go_to", null, 1L, 100L));
	}

	@Test
	void rejectsBlankTypeIds() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);

		assertThrows(IllegalArgumentException.class,
			() -> snapshot(new MarkerId(1L), target, " ", "go_to", 1L, 100L));
		assertThrows(IllegalArgumentException.class,
			() -> snapshot(new MarkerId(1L), target, "", "go_to", 1L, 100L));
		assertThrows(IllegalArgumentException.class,
			() -> snapshot(new MarkerId(1L), target, "location", " ", 1L, 100L));
		assertThrows(IllegalArgumentException.class,
			() -> snapshot(new MarkerId(1L), target, "location", "", 1L, 100L));
	}

	@Test
	void rejectsNegativeArrivalTick() {
		assertThrows(IllegalArgumentException.class,
			() -> snapshot(new MarkerId(1L), new Target.LocationTarget(OVERWORLD, 0, 0, 0),
				"location", "go_to", -1L, 100L));
	}

	@Test
	void rejectsExpiresNotAfterArrival() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);

		assertThrows(IllegalArgumentException.class,
			() -> snapshot(new MarkerId(1L), target, "location", "go_to", 10L, 10L));
		assertThrows(IllegalArgumentException.class,
			() -> snapshot(new MarkerId(1L), target, "location", "go_to", 10L, 5L));
	}
}
