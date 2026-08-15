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

class ServerMarkerTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID RECIPIENT = UUID.randomUUID();
	private static final UUID UUID_SMALL = new UUID(0L, 1L);
	private static final UUID UUID_LARGE = new UUID(0L, 2L);

	private static final PingTypeCatalog PING_TYPES = PingTypeCatalog.builtIn();
	private static final TargetTypeCatalog TARGET_TYPES = TargetTypeCatalog.builtIn();

	private static PingType ping(String id) {
		return PING_TYPES.findById(id).orElseThrow();
	}

	private static TargetType type(String id) {
		return TARGET_TYPES.findById(id).orElseThrow();
	}

	private static ServerMarker marker(
		MarkerId id, Target target, TargetType type, PingType pingType, long arrival, long expires, List<UUID> recipients) {
		return new ServerMarker(id, OWNER, target, type, pingType, new MarkerAnchor(0, 0, 0), arrival, expires, recipients);
	}

	private static ServerMarker entityMarker(long id, long arrival, List<UUID> recipients) {
		Target target = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());
		return marker(new MarkerId(id), target, type("entity"), ping("attention"), arrival, arrival + 100L, recipients);
	}

	@Test
	void targetKeyDerivesForEntityTarget() {
		UUID uuid = UUID.randomUUID();
		ServerMarker marker = marker(
			new MarkerId(1L), new Target.EntityTarget(OVERWORLD, uuid), type("entity"), ping("attention"),
			10L, 110L, List.of(RECIPIENT));

		assertEquals(new TargetKey.EntityKey(OVERWORLD, uuid), marker.targetKey());
	}

	@Test
	void targetKeyDerivesForBlockTargetIncludingBlockType() {
		ServerMarker marker = marker(
			new MarkerId(1L), new Target.BlockTarget(OVERWORLD, 5, 6, 7, "minecraft:chest"), type("block"), ping("attention"),
			10L, 110L, List.of(RECIPIENT));

		assertEquals(new TargetKey.BlockKey(OVERWORLD, 5, 6, 7, "minecraft:chest"), marker.targetKey());
	}

	@Test
	void targetKeyDerivesForLocationTarget() {
		ServerMarker marker = marker(
			new MarkerId(1L), new Target.LocationTarget(OVERWORLD, 0.5, 64.0, -8.25), type("location"), ping("go_to"),
			10L, 110L, List.of(RECIPIENT));

		assertEquals(new TargetKey.LocationKey(OVERWORLD, 0.5, 64.0, -8.25), marker.targetKey());
	}

	@Test
	void droppedItemAndEntityTargetTypesBothAcceptEntityTargets() {
		Target.EntityTarget entity = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());

		// dropped_item is an ENTITY target type, so its kind matches the target
		// kind naturally; no special case is required.
		ServerMarker droppedItem = marker(new MarkerId(1L), entity, type("dropped_item"), ping("loot"),
			10L, 110L, List.of(RECIPIENT));
		ServerMarker genericEntity = marker(new MarkerId(2L), entity, type("entity"), ping("attention"),
			10L, 110L, List.of(RECIPIENT));

		assertEquals(new TargetKey.EntityKey(OVERWORLD, entity.entityId()), droppedItem.targetKey());
		assertEquals(droppedItem.targetKey(), genericEntity.targetKey());
	}

	@Test
	void rejectsPingTypeNotInTargetType() {
		// "entity" target type offers attention/danger/go_to, not loot.
		assertThrows(IllegalArgumentException.class,
			() -> marker(new MarkerId(1L), new Target.EntityTarget(OVERWORLD, UUID.randomUUID()),
				type("entity"), ping("loot"), 10L, 110L, List.of(RECIPIENT)));
	}

	@Test
	void rejectsTargetKindMismatch() {
		assertThrows(IllegalArgumentException.class,
			() -> marker(new MarkerId(1L), new Target.BlockTarget(OVERWORLD, 0, 0, 0, "minecraft:stone"),
				type("entity"), ping("attention"), 10L, 110L, List.of(RECIPIENT)));
		assertThrows(IllegalArgumentException.class,
			() -> marker(new MarkerId(1L), new Target.EntityTarget(OVERWORLD, UUID.randomUUID()),
				type("block"), ping("attention"), 10L, 110L, List.of(RECIPIENT)));
	}

	@Test
	void rejectsNegativeArrivalTick() {
		assertThrows(IllegalArgumentException.class,
			() -> entityMarker(1L, -1L, List.of(RECIPIENT)));
	}

	@Test
	void rejectsExpiresNotAfterArrival() {
		Target target = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());

		assertThrows(IllegalArgumentException.class,
			() -> marker(new MarkerId(1L), target, type("entity"), ping("attention"), 10L, 10L, List.of(RECIPIENT)));
		assertThrows(IllegalArgumentException.class,
			() -> marker(new MarkerId(1L), target, type("entity"), ping("attention"), 10L, 5L, List.of(RECIPIENT)));
	}

	@Test
	void rejectsEmptyRecipients() {
		assertThrows(IllegalArgumentException.class,
			() -> entityMarker(1L, 10L, List.of()));
	}

	@Test
	void rejectsNullRecipients() {
		Target target = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());

		assertThrows(NullPointerException.class,
			() -> marker(new MarkerId(1L), target, type("entity"), ping("attention"), 10L, 110L, null));
	}

	@Test
	void recipientsAreSortedAndDeduplicated() {
		ServerMarker marker = entityMarker(1L, 10L, List.of(UUID_LARGE, UUID_SMALL, UUID_LARGE, UUID_SMALL));

		assertEquals(List.of(UUID_SMALL, UUID_LARGE), marker.recipients());
	}

	@Test
	void recipientsAreImmutable() {
		ServerMarker marker = entityMarker(1L, 10L, List.of(RECIPIENT));

		assertThrows(UnsupportedOperationException.class,
			() -> marker.recipients().add(UUID.randomUUID()));
	}

	@Test
	void rejectsNullComponents() {
		Target target = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());
		TargetType entityType = type("entity");
		PingType attention = ping("attention");
		MarkerAnchor anchor = new MarkerAnchor(0, 0, 0);

		assertThrows(NullPointerException.class,
			() -> new ServerMarker(null, OWNER, target, entityType, attention, anchor, 10L, 110L, List.of(RECIPIENT)));
		assertThrows(NullPointerException.class,
			() -> new ServerMarker(new MarkerId(1L), null, target, entityType, attention, anchor, 10L, 110L, List.of(RECIPIENT)));
		assertThrows(NullPointerException.class,
			() -> new ServerMarker(new MarkerId(1L), OWNER, null, entityType, attention, anchor, 10L, 110L, List.of(RECIPIENT)));
		assertThrows(NullPointerException.class,
			() -> new ServerMarker(new MarkerId(1L), OWNER, target, null, attention, anchor, 10L, 110L, List.of(RECIPIENT)));
		assertThrows(NullPointerException.class,
			() -> new ServerMarker(new MarkerId(1L), OWNER, target, entityType, null, anchor, 10L, 110L, List.of(RECIPIENT)));
		assertThrows(NullPointerException.class,
			() -> new ServerMarker(new MarkerId(1L), OWNER, target, entityType, attention, null, 10L, 110L, List.of(RECIPIENT)));
	}

	@Test
	void exposesMarkerFields() {
		MarkerId id = new MarkerId(42L);
		Target target = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());
		MarkerAnchor anchor = new MarkerAnchor(1.0, 2.0, 3.0);
		ServerMarker marker = new ServerMarker(id, OWNER, target, type("entity"), ping("attention"), anchor,
			100L, 300L, List.of(RECIPIENT));

		assertEquals(id, marker.id());
		assertEquals(OWNER, marker.owner());
		assertEquals(target, marker.target());
		assertEquals(type("entity"), marker.targetType());
		assertEquals(ping("attention"), marker.pingType());
		assertEquals(anchor, marker.anchor());
		assertEquals(100L, marker.arrivalTick());
		assertEquals(300L, marker.expiresAtTick());
		assertEquals(List.of(RECIPIENT), marker.recipients());
	}
}
