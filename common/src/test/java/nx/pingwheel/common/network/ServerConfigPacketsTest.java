package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.ChannelMode;
import org.junit.jupiter.api.Test;

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
		assertFalse(ServerConfigRequestC2SPacket.readSafe(buf).isCorrupt());
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
		assertTrue(new ServerConfigUpdateC2SPacket(1 << 8, ChannelMode.AUTO, true, 0, 0).isCorrupt());
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
}
