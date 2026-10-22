package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigPacketsTest {
	@Test
	void requestRoundTripsPositiveCorrelationIdAndSafeDecodeDrainsPayload() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		new ServerConfigRequestC2SPacket(17L).write(buf);

		assertEquals(new ServerConfigRequestC2SPacket(17L), ServerConfigRequestC2SPacket.readSafe(buf));
		assertEquals(0, buf.readableBytes());

		buf.writeVarLong(7L);
		buf.writeByte(1);
		assertTrue(ServerConfigRequestC2SPacket.readSafe(buf).isCorrupt());
		assertEquals(0, buf.readableBytes());

		FriendlyByteBuf truncated = new FriendlyByteBuf(Unpooled.buffer());
		truncated.writeByte(0x80);
		assertTrue(ServerConfigRequestC2SPacket.readSafe(truncated).isCorrupt());
		assertEquals(0, truncated.readableBytes());

		assertTrue(new ServerConfigRequestC2SPacket(-1L).isCorrupt());
	}

	@Test
	void snapshotRoundTripsAndRejectsInvalidEnumOrNegativeValues() {
		var expected = new ServerConfigSnapshotS2CPacket(17L, true, ChannelMode.TEAM_ONLY, true, 1000, 5);
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		expected.write(buf);

		var actual = ServerConfigSnapshotS2CPacket.readSafe(buf);
		assertEquals(expected, actual);
		assertFalse(actual.isCorrupt());

		assertFalse(new ServerConfigSnapshotS2CPacket(1L, true, ChannelMode.AUTO, true, 0, 5).isCorrupt());
		assertTrue(new ServerConfigSnapshotS2CPacket(-1L, true, ChannelMode.AUTO, true, 0, 5).isCorrupt());
		assertTrue(new ServerConfigSnapshotS2CPacket(1L, true, ChannelMode.AUTO, true, -1, 5).isCorrupt());
		FriendlyByteBuf corruptEnum = new FriendlyByteBuf(Unpooled.buffer());
		corruptEnum.writeVarLong(17L);
		corruptEnum.writeBoolean(true);
		corruptEnum.writeVarInt(99);
		corruptEnum.writeBoolean(true);
		corruptEnum.writeVarInt(1);
		corruptEnum.writeVarInt(1);
		assertTrue(ServerConfigSnapshotS2CPacket.readSafe(corruptEnum).isCorrupt());
		assertEquals(0, corruptEnum.readableBytes());
	}

	@Test
	void updateRequiresKnownNonzeroMaskAndNonnegativeFields() {
		var valid = new ServerConfigUpdateC2SPacket(
			ServerConfigUpdateC2SPacket.DEFAULT_CHANNEL_MODE | ServerConfigUpdateC2SPacket.RATE_LIMIT,
			ChannelMode.GLOBAL,
			false,
			0,
			12);
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		valid.write(buf);
		assertEquals(valid, ServerConfigUpdateC2SPacket.readSafe(buf));
		assertFalse(valid.isCorrupt());

		assertTrue(new ServerConfigUpdateC2SPacket(0, ChannelMode.AUTO, true, 0, 0).isCorrupt());
		assertTrue(new ServerConfigUpdateC2SPacket(1 << 27, ChannelMode.AUTO, true, 0, 0).isCorrupt());
		assertTrue(new ServerConfigUpdateC2SPacket(1, ChannelMode.AUTO, true, -1, 0).isCorrupt());
		assertTrue(new ServerConfigUpdateC2SPacket(1, null, true, 0, 0).isCorrupt());
	}

	@Test
	void truncatedUpdateUsesCorruptFallbackAndDrainsBuffer() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeVarInt(1);
		assertTrue(ServerConfigUpdateC2SPacket.readSafe(buf).isCorrupt());
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void modelConversionsAndRoundTripsPreserveExplicitFalse() {
		var inventory = InventoryConfigValues.defaults();
		var snapshot = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23, inventory, false);
		var snapshotPacket = new ServerConfigSnapshotS2CPacket(17L, snapshot);
		assertEquals(snapshot, snapshotPacket.snapshot());
		var update = new ServerConfigUpdate(ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED,
			ChannelMode.AUTO, true, 1000, 5, 23, inventory, false);
		var updatePacket = new ServerConfigUpdateC2SPacket(update);
		assertEquals(update, updatePacket.update());
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			snapshotPacket.write(buf);
			var decodedSnapshot = ServerConfigSnapshotS2CPacket.readSafe(buf);
			assertEquals(snapshotPacket, decodedSnapshot);
			assertEquals(snapshot, decodedSnapshot.snapshot());
			assertEquals(0, buf.readableBytes());
			updatePacket.write(buf);
			var decodedUpdate = ServerConfigUpdateC2SPacket.readSafe(buf);
			assertEquals(updatePacket, decodedUpdate);
			assertEquals(update, decodedUpdate.update());
			assertEquals(0, buf.readableBytes());
		} finally { buf.release(); }
	}

	@Test
	void packetCompatibilityConstructorsDefaultNoticesOn() {
		var inventory = InventoryConfigValues.defaults();
		for (var snapshot : List.of(
			new ServerConfigSnapshotS2CPacket(1L, true, ChannelMode.AUTO, true, 1, 2),
			new ServerConfigSnapshotS2CPacket(1L, true, ChannelMode.AUTO, true, 1, 2, 7),
			new ServerConfigSnapshotS2CPacket(1L, true, ChannelMode.AUTO, true, 1, 2, 7, inventory))) {
			assertTrue(snapshot.budgetDelayNotificationsEnabled());
			assertTrue(snapshot.snapshot().budgetDelayNotificationsEnabled());
		}
		for (var update : List.of(
			new ServerConfigUpdateC2SPacket(1, ChannelMode.AUTO, true, 1, 2),
			new ServerConfigUpdateC2SPacket(1, ChannelMode.AUTO, true, 1, 2, 7),
			new ServerConfigUpdateC2SPacket(1, ChannelMode.AUTO, true, 1, 2, 7, inventory))) {
			assertTrue(update.budgetDelayNotificationsEnabled());
			assertTrue(update.update().budgetDelayNotificationsEnabled());
		}
	}
}
