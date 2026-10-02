package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Set;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryStrictDecodeTest {
	private static FriendlyByteBuf frame(IPacket packet) {
		var buf = new FriendlyByteBuf(Unpooled.buffer()); packet.write(buf); return buf;
	}
	/** Preserve every lower bit and the complete valid suffix; only overflowing fifth-byte bits differ. */
	private static FriendlyByteBuf overflow(FriendlyByteBuf original, int offset) {
		var result = new FriendlyByteBuf(Unpooled.buffer());
		result.writeBytes(original, 0, offset);
		int end = offset;
		while ((original.getUnsignedByte(end++) & 0x80) != 0) {}
		int reader = original.readerIndex(); original.readerIndex(offset); int value = original.readVarInt(); original.readerIndex(reader);
		for (int i = 0; i < 4; i++) result.writeByte(((value >>> (7 * i)) & 0x7f) | 0x80);
		result.writeByte(((value >>> 28) & 0xf) | 0x10);
		result.writeBytes(original, end, original.writerIndex() - end);
		return result;
	}
	private static void rejectsResponse(FriendlyByteBuf original, int offset) {
		var malformed = overflow(original, offset);
		try { assertTrue(InventoryS2CPacket.readSafe(malformed).isCorrupt(), "overflow at " + offset); }
		finally { malformed.release(); }
	}
	private static void rejectsRequest(FriendlyByteBuf original, int offset) {
		var malformed = overflow(original, offset);
		try { assertTrue(InventoryC2SPacket.readSafe(malformed).isCorrupt(), "overflow at " + offset); }
		finally { malformed.release(); }
	}
	@Test void requestProtocolEnumNestedTargetTagAndTextLengthsRejectRawOverflow() {
		var original = frame(InventoryC2SPacket.open(1, 100, 1, 1,
			new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"), BlockFace.NORTH));
		try {
			rejectsRequest(original, 0);
			original.readUtf(256); rejectsRequest(original, original.readerIndex()); original.readVarInt();
			original.skipBytes(32); rejectsRequest(original, original.readerIndex()); original.readUtf(256);
			rejectsRequest(original, original.readerIndex()); original.readUtf(256);
			rejectsRequest(original, original.readerIndex()); original.readVarInt(); original.skipBytes(12);
			rejectsRequest(original, original.readerIndex()); original.readUtf(256);
			rejectsRequest(original, original.readerIndex());
		} finally { original.release(); }
	}
	@Test void responseOfferAndPolicyIntegersRejectRawOverflow() {
		var original = frame(InventoryS2CPacket.offer(1, new InventoryS2CPacket.Offer(3, 3, 5, 0)).stamp(100, 1));
		try {
			rejectsResponse(original, 0); original.readUtf(256); rejectsResponse(original, original.readerIndex());
			original.readVarInt(); original.skipBytes(32);
			for (int i = 0; i < 4; i++) { rejectsResponse(original, original.readerIndex()); original.readVarInt(); }
		} finally { original.release(); }
		original = frame(InventoryS2CPacket.policy(1, 100, 1, Set.of("entity_block")));
		try {
			original.readUtf(256); original.readVarInt(); original.skipBytes(32);
			rejectsResponse(original, original.readerIndex()); original.readVarInt(); rejectsResponse(original, original.readerIndex());
		} finally { original.release(); }
	}
	@Test void responsePartBoundsPayloadAndEntryCountRejectRawOverflow() {
		var original = frame(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1, 0, new MarkerId(1), 1, 1, 1,
			0, 1, true, InventoryS2CPacket.Status.READY, 0, List.of()).stamp(100, 1));
		try {
			original.readUtf(256); original.readVarInt(); original.skipBytes(32 + 1 + 8 + 24);
			rejectsResponse(original, original.readerIndex()); original.readVarInt();
			rejectsResponse(original, original.readerIndex()); original.readVarInt(); original.skipBytes(1); original.readUtf(256); original.skipBytes(8);
			rejectsResponse(original, original.readerIndex());
			int lengthOffset = original.readerIndex(); original.readVarInt();
			var payloadOverflow = overflow(original, original.readerIndex());
			try {
				payloadOverflow.setByte(lengthOffset, 5);
				assertTrue(InventoryS2CPacket.readSafe(payloadOverflow).isCorrupt());
			} finally { payloadOverflow.release(); }
		} finally { original.release(); }
	}
	@Test void strictNonnegativeNumbersRejectHighBitsAndOverlongRepresentations() {
		for (byte[] raw : List.of(new byte[] {(byte) 0x81, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10},
			new byte[] {(byte) 0x81, 0})) {
			var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
			try { assertThrows(IllegalArgumentException.class, () -> StrictPacketCodec.readVarInt(buf)); } finally { buf.release(); }
		}
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			buf.writeByte(0x81); for (int i = 0; i < 8; i++) buf.writeByte(0x80); buf.writeByte(2);
			assertThrows(IllegalArgumentException.class, () -> StrictPacketCodec.readVarLong(buf));
		} finally { buf.release(); }
	}
}
