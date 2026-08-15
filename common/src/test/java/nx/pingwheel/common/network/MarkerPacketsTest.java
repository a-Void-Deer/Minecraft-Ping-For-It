package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerRequestKind;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerPacketsTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID ENTITY_ID = UUID.randomUUID();
	private static final UUID OWNER = UUID.randomUUID();

	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	private static Target locationTarget() {
		return new Target.LocationTarget(OVERWORLD, 1.5, 64.0, -2.5);
	}

	private static MarkerSnapshot snapshot(long arrival, long expires) {
		return new MarkerSnapshot(
			new MarkerId(7L),
			OWNER,
			new Target.EntityTarget(OVERWORLD, ENTITY_ID),
			"entity",
			"attention",
			new nx.pingwheel.common.marker.MarkerAnchor(1.5, 64.0, -2.5),
			arrival,
			expires
		);
	}

	@Test
	void createPacketRoundTripsDirectly() {
		var packet = new MarkerCreateC2SPacket(42L, locationTarget(), "go_to");

		var buf = buffer();
		packet.write(buf);

		assertEquals(packet, new MarkerCreateC2SPacket(buf));
	}

	@Test
	void createPacketRoundTripsThroughReadSafe() {
		var packet = new MarkerCreateC2SPacket(42L, locationTarget(), "go_to");

		var buf = buffer();
		packet.write(buf);

		assertEquals(packet, MarkerCreateC2SPacket.readSafe(buf));
	}

	@Test
	void createPacketRejectsNegativeRequestId() {
		var negative = new MarkerCreateC2SPacket(-5L, locationTarget(), "go_to");
		assertTrue(negative.isCorrupt());

		var buf = buffer();
		buf.writeLong(-5L);
		MarkerPacketCodec.writeTarget(buf, locationTarget());
		MarkerPacketCodec.writeIdString(buf, "go_to");

		assertTrue(MarkerCreateC2SPacket.readSafe(buf).isCorrupt());
	}

	@Test
	void createPacketRejectsBlankPingTypeId() {
		assertTrue(new MarkerCreateC2SPacket(1L, locationTarget(), " ").isCorrupt());
		assertTrue(new MarkerCreateC2SPacket(1L, locationTarget(), "").isCorrupt());
	}

	@Test
	void removePacketRoundTripsDirectly() {
		var packet = new MarkerRemoveC2SPacket(new MarkerId(99L));

		var buf = buffer();
		packet.write(buf);

		assertEquals(packet, new MarkerRemoveC2SPacket(buf));
	}

	@Test
	void removePacketRoundTripsThroughReadSafe() {
		var packet = new MarkerRemoveC2SPacket(new MarkerId(99L));

		var buf = buffer();
		packet.write(buf);

		assertEquals(packet, MarkerRemoveC2SPacket.readSafe(buf));
	}

	@Test
	void removePacketRejectsNegativeMarkerIdThroughReadSafe() {
		var buf = buffer();
		buf.writeLong(-1L);

		assertTrue(MarkerRemoveC2SPacket.readSafe(buf).isCorrupt());
	}

	@Test
	void createdPacketRoundTripsDirectly() {
		var packet = new MarkerCreatedS2CPacket(snapshot(10L, 500L));

		var buf = buffer();
		packet.write(buf);

		assertEquals(packet, new MarkerCreatedS2CPacket(buf));
	}

	@Test
	void createdPacketRoundTripsThroughReadSafe() {
		var packet = new MarkerCreatedS2CPacket(snapshot(10L, 500L));

		var buf = buffer();
		packet.write(buf);

		assertEquals(packet, MarkerCreatedS2CPacket.readSafe(buf));
	}

	@Test
	void removedPacketRoundTripsForEveryReason() {
		for (MarkerRemovalReason reason : MarkerRemovalReason.values()) {
			var packet = new MarkerRemovedS2CPacket(new MarkerId(3L), reason);

			var buf = buffer();
			packet.write(buf);

			assertEquals(packet, new MarkerRemovedS2CPacket(buf));
			assertEquals(packet, MarkerRemovedS2CPacket.readSafe(bufferRoundTrip(packet)));
		}
	}

	@Test
	void removedPacketRejectsUnknownReasonThroughReadSafe() {
		var buf = buffer();
		buf.writeLong(3L);
		buf.writeUtf("NOPE", MarkerPacketCodec.MAX_ID_LENGTH);

		assertTrue(MarkerRemovedS2CPacket.readSafe(buf).isCorrupt());
	}

	@Test
	void rejectedPacketRoundTripsForEveryKindAndReason() {
		for (MarkerRequestKind kind : MarkerRequestKind.values()) {
			for (MarkerRejectReason reason : MarkerRejectReason.values()) {
				var packet = new MarkerRejectedS2CPacket(11L, kind, reason);

				var buf = buffer();
				packet.write(buf);

				assertEquals(packet, new MarkerRejectedS2CPacket(buf));
				assertEquals(packet, MarkerRejectedS2CPacket.readSafe(bufferRoundTrip(packet)));
			}
		}
	}

	@Test
	void rejectedPacketRejectsUnknownReasonThroughReadSafe() {
		var buf = buffer();
		buf.writeLong(11L);
		MarkerPacketCodec.writeEnum(buf, MarkerRequestKind.CREATE);
		buf.writeUtf("NOPE", MarkerPacketCodec.MAX_ID_LENGTH);

		assertTrue(MarkerRejectedS2CPacket.readSafe(buf).isCorrupt());
	}

	@Test
	void rejectedPacketRejectsNegativeRequestId() {
		assertTrue(new MarkerRejectedS2CPacket(-1L, MarkerRequestKind.CREATE, MarkerRejectReason.NOT_FOUND).isCorrupt());
	}

	@Test
	void winnerChangedPacketRoundTripsPresentWinner() {
		var packet = new MarkerWinnerChangedS2CPacket(
			new TargetKey.BlockKey(OVERWORLD, 4, 5, 6, "minecraft:chest"), Optional.of(new MarkerId(21L)));

		var buf = buffer();
		packet.write(buf);

		assertEquals(packet, new MarkerWinnerChangedS2CPacket(buf));
		assertEquals(packet, MarkerWinnerChangedS2CPacket.readSafe(bufferRoundTrip(packet)));
	}

	@Test
	void winnerChangedPacketRoundTripsEmptyWinner() {
		var packet = new MarkerWinnerChangedS2CPacket(
			new TargetKey.EntityKey(OVERWORLD, ENTITY_ID), Optional.empty());

		var buf = buffer();
		packet.write(buf);

		assertEquals(packet, new MarkerWinnerChangedS2CPacket(buf));
		assertEquals(packet, MarkerWinnerChangedS2CPacket.readSafe(bufferRoundTrip(packet)));
	}

	@Test
	void winnerChangedPacketRejectsNegativeWinnerIdThroughReadSafe() {
		var buf = buffer();
		MarkerPacketCodec.writeTargetKey(buf, new TargetKey.EntityKey(OVERWORLD, ENTITY_ID));
		buf.writeBoolean(true);
		buf.writeLong(-1L);

		assertTrue(MarkerWinnerChangedS2CPacket.readSafe(buf).isCorrupt());
	}

	@Test
	void createdPacketRejectsNegativeArrivalTickThroughReadSafe() {
		var buf = buffer();
		MarkerPacketCodec.writeMarkerId(buf, new MarkerId(1L));
		buf.writeUUID(OWNER);
		MarkerPacketCodec.writeTarget(buf, new Target.EntityTarget(OVERWORLD, ENTITY_ID));
		MarkerPacketCodec.writeIdString(buf, "entity");
		MarkerPacketCodec.writeIdString(buf, "attention");
		MarkerPacketCodec.writeMarkerAnchor(buf, new nx.pingwheel.common.marker.MarkerAnchor(0, 0, 0));
		buf.writeLong(-1L); // arrival tick
		buf.writeLong(100L); // expiry tick

		assertTrue(MarkerCreatedS2CPacket.readSafe(buf).isCorrupt());
	}

	@Test
	void truncatedBuffersBecomeCorruptThroughReadSafe() {
		// create packet with request id only, missing target + ping type
		var truncatedCreate = buffer();
		truncatedCreate.writeLong(5L);
		assertTrue(MarkerCreateC2SPacket.readSafe(truncatedCreate).isCorrupt());

		// created packet with nothing written
		assertTrue(MarkerCreatedS2CPacket.readSafe(buffer()).isCorrupt());

		// winner-changed packet with only the target key, missing winner id
		var truncatedWinner = buffer();
		MarkerPacketCodec.writeTargetKey(truncatedWinner, new TargetKey.LocationKey(OVERWORLD, 0, 0, 0));
		assertTrue(MarkerWinnerChangedS2CPacket.readSafe(truncatedWinner).isCorrupt());
	}

	@Test
	void noArgConstructorsDoNotThrowAndProduceCorruptPackets() {
		assertDoesNotThrow(() -> assertTrue(new MarkerCreateC2SPacket().isCorrupt()));
		assertDoesNotThrow(() -> assertTrue(new MarkerRemoveC2SPacket().isCorrupt()));
		assertDoesNotThrow(() -> assertTrue(new MarkerCreatedS2CPacket().isCorrupt()));
		assertDoesNotThrow(() -> assertTrue(new MarkerRemovedS2CPacket().isCorrupt()));
		assertDoesNotThrow(() -> assertTrue(new MarkerRejectedS2CPacket().isCorrupt()));
		assertDoesNotThrow(() -> assertTrue(new MarkerWinnerChangedS2CPacket().isCorrupt()));
	}

	@Test
	void packetIdsAreUniqueAndUseExpectedNamespaces() {
		List<String> ids = List.of(
			MarkerCreateC2SPacket.PACKET_ID.toString(),
			MarkerRemoveC2SPacket.PACKET_ID.toString(),
			MarkerCreatedS2CPacket.PACKET_ID.toString(),
			MarkerRemovedS2CPacket.PACKET_ID.toString(),
			MarkerRejectedS2CPacket.PACKET_ID.toString(),
			MarkerWinnerChangedS2CPacket.PACKET_ID.toString()
		);

		assertEquals(Set.of(
			"ping-wheel-c2s:marker-create",
			"ping-wheel-c2s:marker-remove",
			"ping-wheel-s2c:marker-created",
			"ping-wheel-s2c:marker-removed",
			"ping-wheel-s2c:marker-rejected",
			"ping-wheel-s2c:marker-winner-changed"
		), Set.copyOf(ids));
	}

	@Test
	void clientPacketsDoNotCarryServerAuthoritativeFields() {
		Set<String> forbidden = Set.of(
			"owner", "ownerId", "author", "targetType", "targetTypeId",
			"outlineColor", "textColor", "color", "displayName", "channel", "recipients"
		);

		for (Class<?> packetClass : List.of(MarkerCreateC2SPacket.class, MarkerRemoveC2SPacket.class)) {
			for (RecordComponent component : packetClass.getRecordComponents()) {
				assertFalse(forbidden.contains(component.getName()),
					() -> packetClass.getSimpleName() + " must not carry client-supplied field '" + component.getName() + "'");
			}
		}
	}

	private static FriendlyByteBuf bufferRoundTrip(IPacket packet) {
		var buf = buffer();
		packet.write(buf);
		return buf;
	}
}
