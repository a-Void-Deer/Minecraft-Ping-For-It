package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.ServerConfigBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncDurationPolicyS2CPacketTest {
	@Test
	void roundTripPreservesDuration() {
		SyncDurationPolicyS2CPacket packet = new SyncDurationPolicyS2CPacket(23);
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());

		packet.write(buf);

		assertEquals(packet, new SyncDurationPolicyS2CPacket(buf));
		assertFalse(packet.isCorrupt());
	}

	@Test
	void negativeAndOutOfBoundsPoliciesAreCorrupt() {
		assertTrue(new SyncDurationPolicyS2CPacket(-1).isCorrupt());
		assertTrue(new SyncDurationPolicyS2CPacket(0).isCorrupt());
		assertTrue(new SyncDurationPolicyS2CPacket(ServerConfigBounds.MAX_PING_DURATION + 1).isCorrupt());
		assertFalse(new SyncDurationPolicyS2CPacket(ServerConfigBounds.MIN_PING_DURATION).isCorrupt());
		assertFalse(new SyncDurationPolicyS2CPacket(ServerConfigBounds.MAX_PING_DURATION).isCorrupt());
	}

	@Test
	void truncatedPayloadUsesTheCorruptFallbackAndConsumesTheBuffer() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		SyncDurationPolicyS2CPacket packet = SyncDurationPolicyS2CPacket.readSafe(buf);

		assertTrue(packet.isCorrupt());
		assertEquals(0, buf.readableBytes());
	}
}
