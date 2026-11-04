package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNullByDefault;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.presentation.PresentationDiagnosticCapture;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerRemovalReason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
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
			() -> PacketHandler.readSafe(buf, NoFallbackPacket.class, Diagnostics.server()));

		assertInstanceOf(NoSuchMethodException.class, error.getCause());
		assertEquals(0, buf.readableBytes());
	}

	/**
	 * A packet shape without a no-arg fallback constructor, used to verify
	 * that readSafe reports the fallback failure explicitly.
	 */
	@NotNullByDefault
	public static final class NoFallbackPacket {
		public NoFallbackPacket(FriendlyByteBuf buf) {
			buf.readLong(); // fails on the truncated buffer
		}
	}

	@Test void decodeFailureChannelsSeparatePacketClassesAndSidesWithoutNormalDisclosure() {
		try (var capture = new PresentationDiagnosticCapture(org.apache.logging.log4j.Level.DEBUG)) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			for (int attempt = 0; attempt < 20; attempt++) assertCorruptC2S();
			assertCorruptS2C();
			assertCorruptC2SWithClientConsumer();
			assertEquals(3, capture.events().size(),
				"same packet class repeats share one channel; another family and the other side start independently");
			assertTrue(capture.events().stream().allMatch(event -> event.getLevel() == org.apache.logging.log4j.Level.DEBUG));

			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
			int before = capture.events().size();
			assertCorruptS2C();
			assertEquals(before, capture.events().size(), "malformed decode never becomes a NORMAL report");
		}
	}

	private static void assertCorruptC2S() {
		var buf = buffer();
		try {
			buf.writeEnum(PresentationC2SPacket.Kind.CREATE);
			buf.writeVarInt(PresentationC2SPacket.VERSION);
			buf.writeLong(41L);
			assertTrue(PresentationC2SPacket.readSafe(buf).isCorrupt());
			assertEquals(0, buf.readableBytes());
		} finally { buf.release(); }
	}

	private static void assertCorruptC2SWithClientConsumer() {
		var buf = buffer();
		try {
			buf.writeEnum(PresentationC2SPacket.Kind.CREATE);
			buf.writeVarInt(PresentationC2SPacket.VERSION);
			buf.writeLong(41L);
			assertTrue(PacketHandler.readSafe(buf, PresentationC2SPacket.class, Diagnostics.client()).isCorrupt());
			assertEquals(0, buf.readableBytes());
		} finally { buf.release(); }
	}

	private static void assertCorruptS2C() {
		var buf = buffer();
		try {
			buf.writeByte(0);
			assertTrue(PresentationS2CPacket.readSafe(buf).isCorrupt());
			assertEquals(0, buf.readableBytes());
		} finally { buf.release(); }
	}

	@Test void explicitConsumerIsSelectedBeforeDecodeAndMalformedFloodCannotBlockDelivery() {
		try (var capture = new PresentationDiagnosticCapture(org.apache.logging.log4j.Level.DEBUG)) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			for (int attempt = 0; attempt < 20; attempt++) {
				var malformed = buffer();
				try { assertTrue(PresentationC2SPacket.readSafe(malformed).isCorrupt()); }
				finally { malformed.release(); }
			}
			var clientMalformed = buffer();
			try { assertTrue(PresentationS2CPacket.readSafe(clientMalformed).isCorrupt()); }
			finally { clientMalformed.release(); }
			assertEquals(1, capture.messages().stream().filter(text -> text.contains("site=packet-decode ")).count());
			assertTrue(capture.messages().stream().allMatch(text -> text.contains("side=SERVER")));
			var valid = buffer();
			try {
				PresentationS2CPacket.removed(41L, 2L, new MarkerId(9L), MarkerRemovalReason.CANCELLED).write(valid);
				assertFalse(PresentationS2CPacket.readSafe(valid).isCorrupt());
				assertEquals(0, valid.readableBytes());
			} finally { valid.release(); }
		}
	}
}
