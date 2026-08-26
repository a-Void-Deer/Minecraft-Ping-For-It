package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.ChannelMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigPolicyPacketTest {
	@Test
	void settingsSnapshotRoundTripCarriesSyncDuration() {
		ServerConfigSnapshotS2CPacket packet = new ServerConfigSnapshotS2CPacket(
			4L, true, ChannelMode.AUTO, true, 1000, 5, 23);
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());

		packet.write(buf);

		ServerConfigSnapshotS2CPacket decoded = new ServerConfigSnapshotS2CPacket(buf);
		assertEquals(packet, decoded);
		assertEquals(23, decoded.snapshot().syncDuration());
		assertFalse(decoded.isCorrupt());
	}

	@Test
	void settingsUpdateRoundTripCarriesSyncDurationAndRejectsCorruptFallback() {
		ServerConfigUpdateC2SPacket packet = new ServerConfigUpdateC2SPacket(
			ServerConfigUpdateC2SPacket.SYNC_DURATION,
			ChannelMode.AUTO,
			true,
			1000,
			5,
			23);
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());

		packet.write(buf);

		ServerConfigUpdateC2SPacket decoded = new ServerConfigUpdateC2SPacket(buf);
		assertEquals(packet, decoded);
		assertEquals(23, decoded.update().syncDuration());
		assertFalse(decoded.isCorrupt());

		assertTrue(new ServerConfigUpdateC2SPacket().isCorrupt());
	}
}
