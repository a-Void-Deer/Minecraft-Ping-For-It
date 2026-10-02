package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.BlockFace;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryC2SPacketTest {
	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	private static InventoryC2SPacket roundTrip(InventoryC2SPacket packet) {
		FriendlyByteBuf buf = buffer();
		try {
			packet.write(buf);
			InventoryC2SPacket decoded = InventoryC2SPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt());
			assertEquals(packet, decoded);
			assertEquals(0, buf.readableBytes());
			return decoded;
		} finally {
			buf.release();
		}
	}

	@Test
	void routeAndKindsAreVersionedInventoryV2() {
		assertEquals(2, InventoryC2SPacket.VERSION);
		assertEquals("pingforit-c2s:inventory-v2", InventoryC2SPacket.PACKET_ID.toString());
		assertEquals(List.of(InventoryC2SPacket.Kind.HELLO, InventoryC2SPacket.Kind.OPEN,
			InventoryC2SPacket.Kind.CLOSE, InventoryC2SPacket.Kind.RESYNC,
			InventoryC2SPacket.Kind.SELECT), Arrays.asList(InventoryC2SPacket.Kind.values()));
	}

	@Test
	void helloHasEpochZeroAndRoundTrips() {
		InventoryC2SPacket decoded = roundTrip(InventoryC2SPacket.hello());

		assertEquals(InventoryC2SPacket.Kind.HELLO, decoded.kind());
		assertEquals(0L, decoded.epoch());
		assertEquals(0L, decoded.requestId());
		assertNull(decoded.target());
		assertNull(decoded.markerId());
		assertNull(decoded.entryKey());
		assertNull(decoded.itemId());
		assertNull(decoded.pingType());
	}

	@Test
	void openBindsARequestToATargetAndRoundTrips() {
		long epoch = (1L << 53) + 1;
		long request = (1L << 53) + 3;
		Target.BlockTarget target = new Target.BlockTarget("minecraft:overworld", 12, 64, -8, "minecraft:chest");

		InventoryC2SPacket decoded = roundTrip(InventoryC2SPacket.open(epoch, 100, 3, request, target, BlockFace.WEST));
		assertEquals(BlockFace.WEST, decoded.face());

		assertEquals(InventoryC2SPacket.Kind.OPEN, decoded.kind());
		assertEquals(epoch, decoded.epoch());
		assertEquals(request, decoded.requestId());
		assertEquals(target, decoded.target());
		assertNull(decoded.markerId());
	}

	@Test
	void closeResyncAndSelectRoundTripWithIndependentIds() {
		long epoch = (1L << 53) + 5;
		long request = (1L << 53) + 7;

		InventoryC2SPacket close = roundTrip(InventoryC2SPacket.close(epoch, request));
		assertEquals(InventoryC2SPacket.Kind.CLOSE, close.kind());
		assertEquals(epoch, close.epoch());
		assertEquals(request, close.requestId());

		MarkerId marker = new MarkerId((1L << 53) + 9);
		InventoryC2SPacket resync = roundTrip(InventoryC2SPacket.resync(epoch, request, marker));
		assertEquals(InventoryC2SPacket.Kind.RESYNC, resync.kind());
		assertEquals(request, resync.requestId());
		assertEquals(marker, resync.markerId());

		InventoryC2SPacket resyncAll = roundTrip(InventoryC2SPacket.resync(epoch, request));
		assertNull(resyncAll.markerId());

		InventoryC2SPacket select = roundTrip(InventoryC2SPacket.select(epoch, 100, 3, 5, request, 9, 7, "opaque-token", "attention"));
		assertEquals(InventoryC2SPacket.Kind.SELECT, select.kind());
		assertEquals(request, select.requestId());
		assertEquals("opaque-token", select.entryKey());
		assertNull(select.itemId());
		assertEquals("attention", select.pingType());
		assertNull(select.target());
		assertNull(select.markerId());
	}

	@Test
	void maximumBoundedTextRoundTripsAndLargerTextIsRejected() {
		String max = "a".repeat(256);
		InventoryC2SPacket decoded = roundTrip(InventoryC2SPacket.select(1, 100, 3, 5, 2, 9, 7, max, max));
		assertEquals(max, decoded.entryKey());
		assertNull(decoded.itemId());
		assertEquals(max, decoded.pingType());

		assertThrows(IllegalArgumentException.class,
			() -> InventoryC2SPacket.select(1L, 2L, "a".repeat(257), "minecraft:stone", "attention"));
		assertThrows(IllegalArgumentException.class,
			() -> InventoryC2SPacket.select(1L, 2L, "key", "a".repeat(257), "attention"));
		assertThrows(IllegalArgumentException.class,
			() -> InventoryC2SPacket.select(1L, 2L, "key", "minecraft:stone", "a".repeat(257)));
		assertThrows(IllegalArgumentException.class,
			() -> InventoryC2SPacket.select(1L, 2L, "\u00e9".repeat(200), "minecraft:stone", "attention"));
	}

	@Test
	void badVersionUnknownEnumAndTrailingBytesBecomeCorrupt() {
		FriendlyByteBuf buf = buffer();
		try {
			MarkerPacketCodec.writeEnum(buf, InventoryC2SPacket.Kind.HELLO);
			buf.writeVarInt(2);
			buf.writeLong(0L);
			assertTrue(InventoryC2SPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = buffer();
		try {
			buf.writeUtf("NOT_A_KIND", 256);
			buf.writeVarInt(InventoryC2SPacket.VERSION);
			buf.writeLong(0L);
			assertTrue(InventoryC2SPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = buffer();
		try {
			InventoryC2SPacket.close(1L, 2L).write(buf);
			buf.writeByte(0);
			assertTrue(InventoryC2SPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		assertTrue(new InventoryC2SPacket(InventoryC2SPacket.Kind.HELLO, 1, 0L, 0L,
			null, null, null, null, null).isCorrupt());
	}

	@Test
	void decodeRejectsNegativeRequestAndOversizedText() {
		FriendlyByteBuf buf = buffer();
		try {
			MarkerPacketCodec.writeEnum(buf, InventoryC2SPacket.Kind.CLOSE);
			buf.writeVarInt(InventoryC2SPacket.VERSION);
			buf.writeLong(1L);
			buf.writeLong(-1L);
			assertTrue(InventoryC2SPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = buffer();
		try {
			MarkerPacketCodec.writeEnum(buf, InventoryC2SPacket.Kind.SELECT);
			buf.writeVarInt(InventoryC2SPacket.VERSION);
			buf.writeLong(1L);
			buf.writeLong(2L);
			buf.writeUtf("a".repeat(257), 512);
			buf.writeUtf("minecraft:stone", 512);
			buf.writeUtf("attention", 512);
			assertTrue(InventoryC2SPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}
	}

	@Test
	void missingOrUnexpectedFieldsAreCorrupt() {
		Target target = new Target.LocationTarget("minecraft:overworld", 0.0, 0.0, 0.0);

		assertTrue(InventoryC2SPacket.open(1L, 2L, null).isCorrupt());
		assertTrue(InventoryC2SPacket.close(1L, -2L).isCorrupt());
		assertTrue(InventoryC2SPacket.select(1L, 2L, null, "minecraft:stone", "attention").isCorrupt());
		assertTrue(InventoryC2SPacket.select(1L, 2L, "key", null, "attention").isCorrupt());
		assertTrue(InventoryC2SPacket.select(1L, 2L, "key", "minecraft:stone", null).isCorrupt());
		assertTrue(InventoryC2SPacket.select(1L, 2L, " ", "minecraft:stone", "attention").isCorrupt());

		assertTrue(new InventoryC2SPacket(InventoryC2SPacket.Kind.OPEN, InventoryC2SPacket.VERSION,
			0L, 2L, target, null, null, null, null).isCorrupt());
		assertTrue(new InventoryC2SPacket(InventoryC2SPacket.Kind.HELLO, InventoryC2SPacket.VERSION,
			1L, 0L, null, null, null, null, null).isCorrupt());
		assertTrue(new InventoryC2SPacket(InventoryC2SPacket.Kind.HELLO, InventoryC2SPacket.VERSION,
			0L, 0L, null, null, "key", null, null).isCorrupt());
		assertTrue(new InventoryC2SPacket(InventoryC2SPacket.Kind.OPEN, InventoryC2SPacket.VERSION,
			1L, 2L, target, new MarkerId(9L), null, null, null).isCorrupt());
		assertTrue(new InventoryC2SPacket(InventoryC2SPacket.Kind.RESYNC, InventoryC2SPacket.VERSION,
			1L, 2L, target, null, null, null, null).isCorrupt());
		assertTrue(new InventoryC2SPacket(InventoryC2SPacket.Kind.CLOSE, InventoryC2SPacket.VERSION,
			1L, 2L, null, null, "key", null, null).isCorrupt());
		assertTrue(new InventoryC2SPacket().isCorrupt());
	}
}
