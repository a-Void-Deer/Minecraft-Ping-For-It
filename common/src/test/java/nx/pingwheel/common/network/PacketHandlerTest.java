package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;

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
		var packet = new MarkerCreatedS2CPacket(new MarkerSnapshot(
			new MarkerId(9L),
			UUID.randomUUID(),
			new Target.LocationTarget("minecraft:overworld", 1.0, 2.0, 3.0),
			"entity",
			"attention",
			new MarkerAnchor(0.5, 64.0, -8.25),
			10L,
			1000L
		));

		var buf = buffer();
		packet.write(buf);
		assertTrue(buf.readableBytes() > 0);

		assertFalse(MarkerCreatedS2CPacket.readSafe(buf).isCorrupt());
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void corruptDecodeLeavesBufferFullyConsumed() {
		var buf = buffer();
		buf.writeLong(5L); // truncated marker-create: request id only

		assertTrue(MarkerCreateC2SPacket.readSafe(buf).isCorrupt());
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
