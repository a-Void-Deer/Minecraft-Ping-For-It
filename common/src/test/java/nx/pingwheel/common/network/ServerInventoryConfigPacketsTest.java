package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import nx.pingwheel.common.config.ServerConfigUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ServerInventoryConfigPacketsTest {
	@Test
	void routesVersionTheWholeAdministrationTransaction() {
		assertEquals("pingforit-c2s:server-config-request-v2", ServerConfigRequestC2SPacket.PACKET_ID.toString());
		assertEquals("pingforit-c2s:server-config-update-v2", ServerConfigUpdateC2SPacket.PACKET_ID.toString());
		assertEquals("pingforit-s2c:server-config-snapshot-v2", ServerConfigSnapshotS2CPacket.PACKET_ID.toString());
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void everyLeafAndExplicitUnlimitedFiniteValueRoundTrip(Field selected) {
		var inventory = InventoryConfigValues.defaults();
		Value supplied = selected.isMultiplier() ? new Value(true, selected.grid().minimum())
			: new Value(selected.supportsUnlimited(), BigDecimal.valueOf(selected.maximum()));
		inventory = inventory.with(selected, supplied);
		var snapshot = new ServerConfigSnapshotS2CPacket(77, true, ChannelMode.TEAM_ONLY, false, 0, 9, 23, inventory);
		var update = new ServerConfigUpdateC2SPacket(new ServerConfigUpdate(selected.mask(), ChannelMode.AUTO, true, 1, 2, 3, inventory));
		assertEquals(snapshot, roundTrip(snapshot, ServerConfigSnapshotS2CPacket::readSafe));
		assertEquals(update, roundTrip(update, ServerConfigUpdateC2SPacket::readSafe));
		assertEquals(inventory, snapshot.snapshot().inventory());
		assertEquals(inventory, update.update().inventory());
	}

	@ParameterizedTest
	@EnumSource(value = Field.class, names = {"PREVIEW_CLIENT_BYTE_MULTIPLIER", "PREVIEW_GLOBAL_BYTE_MULTIPLIER",
		"TRACKING_STREAM_BYTE_MULTIPLIER", "TRACKING_SNAPSHOT_BYTE_MULTIPLIER", "TRACKING_GLOBAL_BYTE_MULTIPLIER"})
	void allLegalGridValuesRemainExactOnTheWire(Field field) {
		for (BigDecimal value = field.grid().minimum(); ; value = field.grid().next(value)) {
			var inventory = InventoryConfigValues.defaults().with(field, new Value(false, value));
			var packet = new ServerConfigUpdateC2SPacket(field.mask(), ChannelMode.AUTO, true, 0, 0, 7, inventory);
			assertEquals(value, roundTrip(packet, ServerConfigUpdateC2SPacket::readSafe).inventory().value(field).value());
			if (value.compareTo(field.grid().maximum()) == 0) break;
		}
	}

	@Test
	void everyTruncatedBoundaryOldPrefixAndTrailingDataRejectsWithoutThrowing() {
		checkBoundaries(new ServerConfigRequestC2SPacket(500L), ServerConfigRequestC2SPacket::readSafe);
		checkBoundaries(new ServerConfigSnapshotS2CPacket(500L, true, ChannelMode.AUTO, true, 1000, 5, 23),
			ServerConfigSnapshotS2CPacket::readSafe);
		checkBoundaries(new ServerConfigUpdateC2SPacket(ServerConfigUpdate.ALL_FIELDS, ChannelMode.AUTO, true, 1000, 5, 23),
			ServerConfigUpdateC2SPacket::readSafe);
		var oldSnapshot = buffer();
		oldSnapshot.writeVarLong(1); oldSnapshot.writeBoolean(true); oldSnapshot.writeVarInt(0);
		oldSnapshot.writeBoolean(true); oldSnapshot.writeVarInt(1000); oldSnapshot.writeVarInt(5); oldSnapshot.writeVarInt(23);
		assertTrue(ServerConfigSnapshotS2CPacket.readSafe(oldSnapshot).isCorrupt());
		oldSnapshot.release();
		var oldUpdate = updatePrefix(ServerConfigUpdate.RATE_LIMIT);
		assertTrue(ServerConfigUpdateC2SPacket.readSafe(oldUpdate).isCorrupt());
		oldUpdate.release();
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void invalidEncodedLeafOrMalformedModeRejectsWholePacket(Field bad) {
		var buf = updatePrefix(ServerConfigUpdate.RATE_LIMIT | bad.mask());
		for (Field field : Field.values()) {
			if (field.supportsUnlimited()) buf.writeBoolean(false);
			buf.writeVarInt(field == bad ? -1 : encodedDefault(field));
		}
		assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt());
		assertEquals(0, buf.readableBytes());
		buf.release();
		if (bad.supportsUnlimited()) {
			buf = updatePrefix(bad.mask());
			for (Field field : Field.values()) {
				if (field.supportsUnlimited()) buf.writeByte(field == bad ? 2 : 0);
				buf.writeVarInt(encodedDefault(field));
			}
			assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt());
			buf.release();
		}
	}

	@Test
	void offGridQuantaUnknownMaskAndMissingInventoryFailClosed() {
		var buf = updatePrefix(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER.mask());
		for (Field field : Field.values()) {
			if (field.supportsUnlimited()) buf.writeBoolean(false);
			// 17 client quanta = 4.25, beyond the quarter-step region.
			buf.writeVarInt(field == Field.PREVIEW_CLIENT_BYTE_MULTIPLIER ? 17 : encodedDefault(field));
		}
		assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt());
		buf.release();
		var unknown = new ServerConfigUpdateC2SPacket(1 << 24, ChannelMode.AUTO, true, 0, 0, 7);
		assertTrue(roundTrip(unknown, ServerConfigUpdateC2SPacket::readSafe).isCorrupt());
		assertTrue(new ServerConfigSnapshotS2CPacket(1, true, ChannelMode.AUTO, true, 0, 0, 7, null).isCorrupt());
		assertTrue(new ServerConfigUpdateC2SPacket(1, ChannelMode.AUTO, true, 0, 0, 7, null).isCorrupt());
		assertFalse(new ServerConfigSnapshotS2CPacket().snapshot().isSafe());
	}

	@Test
	void knownFieldsRejectNonpositiveCapsAndOutOfRangeScalarsWithoutClamping() {
		for (Field field : Field.values()) {
			if (field == Field.TRACKING_HEARTBEAT_PERIODS) continue;
			var buf = updatePrefix(field.mask());
			for (Field encoded : Field.values()) {
				if (encoded.supportsUnlimited()) buf.writeBoolean(false);
				buf.writeVarInt(encoded == field ? 0 : encodedDefault(encoded));
			}
			assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt(), field.name());
			buf.release();
		}
		var buf = updatePrefix(Field.TRACKING_HEARTBEAT_PERIODS.mask());
		for (Field field : Field.values()) {
			if (field.supportsUnlimited()) buf.writeBoolean(false);
			buf.writeVarInt(field == Field.TRACKING_HEARTBEAT_PERIODS ? field.maximum() + 1 : encodedDefault(field));
		}
		assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt());
		buf.release();
	}

	private static int encodedDefault(Field field) {
		Value value = InventoryConfigValues.defaults().value(field);
		return field.isMultiplier() ? field.grid().quantaOf(value.value()) : value.value().intValueExact();
	}
	private static FriendlyByteBuf buffer() { return new FriendlyByteBuf(Unpooled.buffer()); }
	private static FriendlyByteBuf updatePrefix(int mask) {
		var buf = buffer();
		buf.writeVarInt(mask); buf.writeVarInt(0); buf.writeBoolean(true);
		buf.writeVarInt(1000); buf.writeVarInt(5); buf.writeVarInt(7);
		return buf;
	}
	private static <T extends IPacket> T roundTrip(T packet, Function<FriendlyByteBuf, T> reader) {
		var buf = buffer();
		try {
			packet.write(buf);
			T decoded = reader.apply(buf);
			assertEquals(0, buf.readableBytes());
			return decoded;
		} finally { buf.release(); }
	}
	private static <T extends IPacket> void checkBoundaries(T packet, Function<FriendlyByteBuf, T> reader) {
		var encoded = buffer();
		try {
			packet.write(encoded);
			for (int length = 0; length < encoded.readableBytes(); length++) {
				var truncated = new FriendlyByteBuf(encoded.copy(0, length));
				try {
					assertTrue(reader.apply(truncated).isCorrupt(), "truncation at " + length);
					assertEquals(0, truncated.readableBytes());
				} finally { truncated.release(); }
			}
			encoded.writeByte(1);
			assertTrue(reader.apply(encoded).isCorrupt());
			assertEquals(0, encoded.readableBytes());
		} finally { encoded.release(); }
	}
}
