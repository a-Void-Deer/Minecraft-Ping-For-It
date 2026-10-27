package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import nx.pingwheel.common.config.InventorySizeScale;
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
		assertEquals("pingforit-c2s:server-config-request-v4", ServerConfigRequestC2SPacket.PACKET_ID.toString());
		assertEquals("pingforit-c2s:server-config-update-v4", ServerConfigUpdateC2SPacket.PACKET_ID.toString());
		assertEquals("pingforit-s2c:server-config-snapshot-v4", ServerConfigSnapshotS2CPacket.PACKET_ID.toString());
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void everyLeafAndExplicitUnlimitedFiniteValueRoundTrip(Field selected) {
		var inventory = InventoryConfigValues.defaults();
		Value supplied;
		if (selected.isSizeScale()) {
			supplied = new Value(false, selected.sizeScale().maximumMultiplier());
		} else if (selected.isMultiplier()) {
			supplied = new Value(true, selected.grid().minimum());
		} else {
			supplied = new Value(selected.supportsUnlimited(), BigDecimal.valueOf(selected.maximum()));
		}
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

	@ParameterizedTest
	@EnumSource(value = Field.class, names = {"DISPLAY_BYTE_MULTIPLIER", "FRAME_BYTE_MULTIPLIER"})
	void allLegalSizeScaleValuesRemainExactOnTheWire(Field field) {
		for (BigDecimal value : field.sizeScale().legalValues()) {
			var inventory = InventoryConfigValues.defaults().with(field, new Value(false, value));
			var packet = new ServerConfigUpdateC2SPacket(field.mask(), ChannelMode.AUTO, true, 0, 0, 7, inventory);
			var decoded = roundTrip(packet, ServerConfigUpdateC2SPacket::readSafe);
			assertEquals(0, value.compareTo(decoded.inventory().value(field).value()), value.toPlainString());
			assertFalse(decoded.inventory().value(field).unlimited());
		}
	}

	@Test
	void sizeScaleMaximumQuantaRoundTripAsExactQuarterMultipliers() {
		assertEquals(128, InventorySizeScale.DISPLAY.quantaOf(InventorySizeScale.DISPLAY.maximumMultiplier()));
		assertEquals(64, InventorySizeScale.FRAME.quantaOf(InventorySizeScale.FRAME.maximumMultiplier()));
		var inventory = InventoryConfigValues.defaults()
			.with(Field.DISPLAY_BYTE_MULTIPLIER, new Value(false, InventorySizeScale.DISPLAY.maximumMultiplier()))
			.with(Field.FRAME_BYTE_MULTIPLIER, new Value(false, InventorySizeScale.FRAME.maximumMultiplier()));
		var snapshot = new ServerConfigSnapshotS2CPacket(9L, true, ChannelMode.AUTO, true, 1000, 5, 23, inventory);
		var decoded = roundTrip(snapshot, ServerConfigSnapshotS2CPacket::readSafe);
		assertEquals(inventory, decoded.inventory());
		assertEquals(131072, InventorySizeScale.DISPLAY.bytes(
			decoded.inventory().value(Field.DISPLAY_BYTE_MULTIPLIER).value()));
		assertEquals(524288, InventorySizeScale.FRAME.bytes(
			decoded.inventory().value(Field.FRAME_BYTE_MULTIPLIER).value()));
	}

	@Test
	void sizeScaleLeavesRejectZeroOffGridOverflowAndUnlimitedFlagBytes() {
		for (int illegal : new int[] {0, 9, 34, 129, 1 << 20}) {
			var buf = updatePrefix(Field.DISPLAY_BYTE_MULTIPLIER.mask());
			for (Field field : Field.values()) {
				if (field.supportsUnlimited()) buf.writeBoolean(false);
				buf.writeVarInt(field == Field.DISPLAY_BYTE_MULTIPLIER ? illegal : encodedDefault(field));
			}
			buf.writeBoolean(true);
			assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt(), "display quanta " + illegal);
			assertEquals(0, buf.readableBytes());
			buf.release();
		}
		for (int illegal : new int[] {0, 65, 1 << 20}) {
			var buf = updatePrefix(Field.FRAME_BYTE_MULTIPLIER.mask());
			for (Field field : Field.values()) {
				if (field.supportsUnlimited()) buf.writeBoolean(false);
				buf.writeVarInt(field == Field.FRAME_BYTE_MULTIPLIER ? illegal : encodedDefault(field));
			}
			buf.writeBoolean(true);
			assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt(), "frame quanta " + illegal);
			assertEquals(0, buf.readableBytes());
			buf.release();
		}

		// A size-scale leaf carries no unlimited boolean; an extra v2-style flag byte
		// shifts the fixed shape and the payload is rejected as a whole.
		var buf = updatePrefix(Field.DISPLAY_BYTE_MULTIPLIER.mask());
		for (Field field : Field.values()) {
			if (field.supportsUnlimited()) buf.writeBoolean(false);
			if (field == Field.DISPLAY_BYTE_MULTIPLIER) {
				buf.writeBoolean(true);
				buf.writeVarInt(4);
			} else {
				buf.writeVarInt(encodedDefault(field));
			}
		}
		buf.writeBoolean(true);
		assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt());
		assertEquals(0, buf.readableBytes());
		buf.release();
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
		buf.writeBoolean(true);
		assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt());
		assertEquals(0, buf.readableBytes());
		buf.release();
		if (bad.supportsUnlimited()) {
			buf = updatePrefix(bad.mask());
			for (Field field : Field.values()) {
				if (field.supportsUnlimited()) buf.writeByte(field == bad ? 2 : 0);
				buf.writeVarInt(encodedDefault(field));
			}
			buf.writeBoolean(true);
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
		buf.writeBoolean(true);
		assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt());
		buf.release();
		var unknown = new ServerConfigUpdateC2SPacket(1 << 27, ChannelMode.AUTO, true, 0, 0, 7);
		assertTrue(roundTrip(unknown, ServerConfigUpdateC2SPacket::readSafe).isCorrupt());
		assertTrue(new ServerConfigSnapshotS2CPacket(1, true, ChannelMode.AUTO, true, 0, 0, 7, null).isCorrupt());
		assertTrue(new ServerConfigUpdateC2SPacket(1, ChannelMode.AUTO, true, 0, 0, 7, null).isCorrupt());
		assertFalse(new ServerConfigSnapshotS2CPacket().snapshot().isSafe());
	}

	@Test
	void knownFieldsRejectNonpositiveCapsAndOutOfRangeScalarsWithoutClamping() {
		for (Field field : Field.values()) {
			if (field == Field.TRACKING_HEARTBEAT_PERIODS || field == Field.TRACKING_RESYNC_MIN_PERIODS) continue;
			var buf = updatePrefix(field.mask());
			for (Field encoded : Field.values()) {
				if (encoded.supportsUnlimited()) buf.writeBoolean(false);
				buf.writeVarInt(encoded == field ? 0 : encodedDefault(encoded));
			}
			buf.writeBoolean(true);
			assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt(), field.name());
			buf.release();
		}
		var buf = updatePrefix(Field.TRACKING_HEARTBEAT_PERIODS.mask());
		for (Field field : Field.values()) {
			if (field.supportsUnlimited()) buf.writeBoolean(false);
			buf.writeVarInt(field == Field.TRACKING_HEARTBEAT_PERIODS ? field.maximum() + 1 : encodedDefault(field));
		}
		buf.writeBoolean(true);
		assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt());
		buf.release();
	}

	@ParameterizedTest
	@EnumSource(value = Field.class, names = {"PHYSICAL_SLOTS_PER_TICK", "PENDING_MEMORY_MIB", "PREVIEW_PERIOD_TICKS",
		"PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD", "PREVIEW_MAX_SLOTS_PER_CLIENT", "PREVIEW_MAX_SLOTS_SERVER",
		"PREVIEW_MAX_TARGETS_PER_CLIENT", "TRACKING_PERIOD_TICKS", "TRACKING_MAX_VARIANTS_PER_TARGET",
		"TRACKING_MAX_SLOTS_PER_TARGET", "TRACKING_MAX_SLOTS_SERVER", "TRACKING_RESYNC_MIN_PERIODS",
		"TRACKING_HEARTBEAT_PERIODS", "TRACKING_GRACE_PERIODS"})
	void integerWireBoundsAreStrictEvenForAnUnlimitedLeaf(Field selected) {
		for (boolean unlimited : new boolean[] {false, true}) {
			if (unlimited && !selected.supportsUnlimited()) continue;
			for (int illegal : new int[] {selected.minimum() - 1, selected.maximum() + 1}) {
				var buf = updatePrefix(selected.mask() | ServerConfigUpdate.RATE_LIMIT);
				try {
					for (Field field : Field.values()) {
						if (field.supportsUnlimited()) buf.writeBoolean(field == selected && unlimited);
						buf.writeVarInt(field == selected ? illegal : encodedDefault(field));
					}
					buf.writeBoolean(true);
					var packet = ServerConfigUpdateC2SPacket.readSafe(buf);
					assertTrue(packet.isCorrupt(), selected + "=" + illegal + " unlimited=" + unlimited);
					assertFalse(packet.update().isValid());
					assertEquals(0, buf.readableBytes());
				} finally { buf.release(); }
			}
		}
	}

	private static int encodedDefault(Field field) {
		Value value = InventoryConfigValues.defaults().value(field);
		if (field.isSizeScale()) return field.sizeScale().quantaOf(value.value());
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
