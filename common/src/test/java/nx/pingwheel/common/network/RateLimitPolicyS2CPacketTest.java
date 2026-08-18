package nx.pingwheel.common.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class RateLimitPolicyS2CPacketTest {

	@Test
	void roundTripPreservesBothFields() {
		RateLimitPolicyS2CPacket packet = new RateLimitPolicyS2CPacket(5, 1000);
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());

		packet.write(buf);

		assertEquals(packet, new RateLimitPolicyS2CPacket(buf));
		assertFalse(packet.isCorrupt());
	}

	@Test
	void safeDecodePreservesFieldIdentity() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeInt(0);
		buf.writeInt(250);

		RateLimitPolicyS2CPacket packet = RateLimitPolicyS2CPacket.readSafe(buf);

		assertEquals(0, packet.rateLimit());
		assertEquals(250, packet.msToRegenerate());
		assertFalse(packet.isCorrupt());
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void negativeValuesAreCorrupt() {
		assertTrue(new RateLimitPolicyS2CPacket(-1, 1000).isCorrupt());
		assertTrue(new RateLimitPolicyS2CPacket(5, -1).isCorrupt());
	}

	@Test
	void noArgPacketIsCorrupt() {
		RateLimitPolicyS2CPacket packet = new RateLimitPolicyS2CPacket();

		assertTrue(packet.isCorrupt());
		assertTrue(packet.rateLimit() < 0);
		assertTrue(packet.msToRegenerate() < 0);
	}

	@Test
	void truncatedPayloadUsesCorruptFallback() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeInt(5);

		RateLimitPolicyS2CPacket packet = RateLimitPolicyS2CPacket.readSafe(buf);

		assertTrue(packet.isCorrupt());
		assertEquals(0, buf.readableBytes());
	}
}
