package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Set;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Focused wire contract for the session-wide OPENED lane entitlement. */
@NotNullByDefault
class InventoryOpenedPacketTest {
	private static final int DISPLAY_BYTES = InventoryLimits.DISPLAY_BASE_BYTES;
	private static final int FRAME_BYTES = InventoryLimits.FRAME_BASE_BYTES;

	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	private static FriendlyByteBuf rawOpened(long requestId, int laneBits) {
		FriendlyByteBuf frame = buffer();
		MarkerPacketCodec.writeEnum(frame, InventoryS2CPacket.Kind.OPENED);
		frame.writeVarInt(InventoryS2CPacket.VERSION);
		frame.writeLong(7L);
		frame.writeLong(requestId);
		frame.writeLong(100L);
		frame.writeLong(1L);
		frame.writeByte(laneBits);
		return frame;
	}

	@Test
	void openedKindIsAppendedAfterHeartbeatWithoutChangingExistingOrdinals() {
		assertEquals(InventoryS2CPacket.VERSION, 2);
		assertEquals(9, InventoryS2CPacket.Kind.OPENED.ordinal());
		assertEquals(InventoryS2CPacket.Kind.HEARTBEAT, InventoryS2CPacket.Kind.values()[8]);
		assertEquals(InventoryS2CPacket.Kind.OPENED, InventoryS2CPacket.Kind.values()[9]);
	}

	@Test
	void openedIsSessionWideAndEncodesOnlyOneTrailingLaneByte() {
		InventoryS2CPacket packet = InventoryS2CPacket.opened(7L, 100L, 3L, 5L, 2);
		assertEquals(InventoryResourceKind.ITEM, packet.resourceKind());
		assertEquals(2, packet.laneBits());
		assertEquals(41, packet.encodedSize(DISPLAY_BYTES, FRAME_BYTES),
			"kind text + protocol + four longs + the single lane byte, with no resource-kind byte");

		FriendlyByteBuf buf = buffer();
		try {
			packet.write(buf, DISPLAY_BYTES, FRAME_BYTES);
			assertEquals(packet.encodedSize(DISPLAY_BYTES, FRAME_BYTES), buf.readableBytes());
			assertEquals("OPENED", buf.readUtf());
			assertEquals(InventoryS2CPacket.VERSION, buf.readVarInt());
			assertEquals(7L, buf.readLong());
			assertEquals(5L, buf.readLong());
			assertEquals(100L, buf.readLong());
			assertEquals(3L, buf.readLong());
			assertEquals(2, buf.readUnsignedByte());
			assertFalse(buf.isReadable(), "OPENED is session-wide and carries no resource-kind byte");
			buf.readerIndex(0);
			InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt());
			assertEquals(packet, decoded);
			assertFalse(buf.isReadable());
		} finally {
			buf.release();
		}

		assertEquals(packet, packet.withResourceKind(InventoryResourceKind.ITEM));
		assertThrows(IllegalArgumentException.class, () -> packet.withResourceKind(InventoryResourceKind.FLUID));
		InventoryS2CPacket foreignLane = new InventoryS2CPacket(InventoryS2CPacket.Kind.OPENED,
			InventoryS2CPacket.VERSION, 7L, 5L, null, 0L, 0L, 0L, 0, 1, false, null, 0L, List.of(), null,
			100L, 3L, Set.of(), 0L, null, InventoryResourceKind.FLUID, 2);
		assertTrue(foreignLane.isCorrupt(), "OPENED cannot borrow a resource lane identity");
	}

	@Test
	void rawOpenedFramesRejectOutOfRangeLaneBitsAndAZeroRequestId() {
		for (int laneBits : List.of(0, 1, 2, 3)) {
			FriendlyByteBuf frame = rawOpened(5L, laneBits);
			try {
				InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(frame);
				assertFalse(decoded.isCorrupt(), "lane bits " + laneBits);
				assertEquals(laneBits, decoded.laneBits());
				assertFalse(frame.isReadable());
			} finally {
				frame.release();
			}
		}
		for (int laneBits : List.of(4, 255)) {
			FriendlyByteBuf frame = rawOpened(5L, laneBits);
			try {
				assertTrue(InventoryS2CPacket.readSafe(frame).isCorrupt(), "lane bits " + laneBits);
				assertFalse(frame.isReadable());
			} finally {
				frame.release();
			}
		}
		FriendlyByteBuf zeroRequest = rawOpened(0L, 3);
		try {
			assertTrue(InventoryS2CPacket.readSafe(zeroRequest).isCorrupt(), "OPENED requires a positive request id");
			assertFalse(zeroRequest.isReadable());
		} finally {
			zeroRequest.release();
		}
	}

	@Test
	void nonOpenedKindsCannotCarryLaneBitsAndOpenedAcceptsOnlyAValidMask() {
		for (InventoryS2CPacket.Kind kind : InventoryS2CPacket.Kind.values()) {
			if (kind == InventoryS2CPacket.Kind.OPENED) continue;
			assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket(kind, InventoryS2CPacket.VERSION,
				1L, 2L, null, 0L, 0L, 0L, 0, 1, false, null, 0L, List.of(), null,
				100L, 1L, Set.of(), 0L, null, InventoryResourceKind.ITEM, 1), kind.name());
		}
		for (int laneBits : List.of(-1, 0, 3, 4)) {
			if (laneBits >= 0 && laneBits <= 3) {
				assertFalse(new InventoryS2CPacket(InventoryS2CPacket.Kind.OPENED, InventoryS2CPacket.VERSION,
					1L, 2L, null, 0L, 0L, 0L, 0, 1, false, null, 0L, List.of(), null,
					100L, 1L, Set.of(), 0L, null, InventoryResourceKind.ITEM, laneBits).isCorrupt(), "lane bits " + laneBits);
			} else {
				assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket(InventoryS2CPacket.Kind.OPENED,
					InventoryS2CPacket.VERSION, 1L, 2L, null, 0L, 0L, 0L, 0, 1, false, null, 0L, List.of(), null,
					100L, 1L, Set.of(), 0L, null, InventoryResourceKind.ITEM, laneBits), "lane bits " + laneBits);
			}
		}
	}

	@Test
	void truncatedAndTrailingOpenedFramesAreRejectedAfterAFullRead() {
		FriendlyByteBuf truncated = buffer();
		try {
			InventoryS2CPacket.opened(7L, 100L, 1L, 5L, 3).write(truncated);
			truncated.writerIndex(truncated.writerIndex() - 1);
			assertTrue(InventoryS2CPacket.readSafe(truncated).isCorrupt());
			assertFalse(truncated.isReadable());
		} finally {
			truncated.release();
		}

		FriendlyByteBuf trailing = buffer();
		try {
			InventoryS2CPacket.opened(7L, 100L, 1L, 5L, 3).write(trailing);
			trailing.writeByte(0);
			assertTrue(InventoryS2CPacket.readSafe(trailing).isCorrupt());
			assertFalse(trailing.isReadable());
		} finally {
			trailing.release();
		}
	}

	@Test
	void encodedOpenedSizeIsExactForEveryValidLaneMask() {
		for (int laneBits : List.of(0, 1, 2, 3)) {
			InventoryS2CPacket packet = InventoryS2CPacket.opened(7L, 100L, 1L, 5L, laneBits);
			FriendlyByteBuf buf = buffer();
			try {
				int measured = packet.encodedSize(DISPLAY_BYTES, FRAME_BYTES);
				packet.write(buf, DISPLAY_BYTES, FRAME_BYTES);
				assertEquals(measured, buf.writerIndex());
				buf.readerIndex(0);
				assertEquals(packet, InventoryS2CPacket.readSafe(buf));
				assertFalse(buf.isReadable());
			} finally {
				buf.release();
			}
		}
	}
}
