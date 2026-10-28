package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerRemovalReason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketHandlerTest {

	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	@Test
	void validDecodeLeavesBufferFullyConsumed() {
		var packet = PresentationS2CPacket.removed(41L, 2L, new MarkerId(9L), MarkerRemovalReason.CANCELLED);

		var buf = buffer();
		packet.write(buf);
		assertTrue(buf.readableBytes() > 0);

		assertFalse(PresentationS2CPacket.readSafe(buf).isCorrupt());
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void corruptDecodeLeavesBufferFullyConsumed() {
		var buf = buffer();
		buf.writeEnum(PresentationC2SPacket.Kind.CREATE);
		buf.writeVarInt(PresentationC2SPacket.VERSION);
		buf.writeLong(41L); // truncated presentation create: target and request id missing

		assertTrue(PresentationC2SPacket.readSafe(buf).isCorrupt());
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void fallbackConstructorFailureThrowsIllegalStateExceptionAndStillDrainsBuffer() {
		var buf = buffer();
		buf.writeInt(1); // 4 bytes: too short for the long read below

		IllegalStateException error = assertThrows(IllegalStateException.class,
			() -> PacketHandler.readSafe(buf, NoFallbackPacket.class));

		assertInstanceOf(NoSuchMethodException.class, error.getCause());
		assertEquals(0, buf.readableBytes());
	}

	/**
	 * A packet shape without a no-arg fallback constructor, used to verify
	 * that readSafe reports the fallback failure explicitly.
	 */
	public static final class NoFallbackPacket {
		public NoFallbackPacket(FriendlyByteBuf buf) {
			buf.readLong(); // fails on the truncated buffer
		}
	}
}
