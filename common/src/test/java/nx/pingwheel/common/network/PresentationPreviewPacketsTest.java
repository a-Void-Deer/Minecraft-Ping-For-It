package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Set;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.preview.PresentationPreviewAccess;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class PresentationPreviewPacketsTest {
	private static final String ADAPTER = "test:source", ALLOWED = "test:value", DENIED = "test:secret";
	private static PresentationPreviewAccess access(PresentationField.Kind kind) {
		return new PresentationPreviewAccess(9, 2, "block", Map.of(ADAPTER, new PresentationPreviewAccess.Adapter(1,
			Map.of(ALLOWED, new PresentationField(ALLOWED, kind, true, 0, "value")))));
	}
	private static PresentationPreviewC2SPacket request() {
		return PresentationPreviewC2SPacket.read(9, 2, 4, new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:stone"), "block", ADAPTER, Set.of(ALLOWED));
	}
	@Test void framedRoundTripsAndTrailingBytesReject() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			request().write(buf); assertEquals(request(), PresentationPreviewC2SPacket.readSafe(buf));
			request().write(buf); buf.writeByte(1); assertTrue(PresentationPreviewC2SPacket.readSafe(buf).isCorrupt());
			var response = PresentationPreviewS2CPacket.result(request(), new PresentationSection(ADAPTER, 1, Map.of(ALLOWED, new PresentationValue.NumberValue(0)), false));
			response.write(buf); var decoded = PresentationPreviewS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt()); assertEquals(new PresentationValue.NumberValue(0), decoded.decodeSection(access(PresentationField.Kind.NUMBER)).fields().get(ALLOWED));
			assertNull(decoded.decodeSection(access(PresentationField.Kind.TEXT)));
			response.write(buf); buf.writeByte(1); assertTrue(PresentationPreviewS2CPacket.readSafe(buf).isCorrupt());
		} finally { buf.release(); }
	}
	@Test void deniedMalformedValueRejectsTheWholeResultWithoutTypedDecodingAndAnnotationRejected() {
		FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer());
		FriendlyByteBuf section = new FriendlyByteBuf(Unpooled.buffer());
		try {
			frame.writeUtf(ADAPTER, 193); frame.writeVarInt(1); frame.writeBoolean(false); frame.writeVarInt(1);
			frame.writeUtf(DENIED, 193); frame.writeVarInt(1); frame.writeByte(127); // invalid type, deliberately denied
			section.writeVarInt(frame.readableBytes()); section.writeBytes(frame);
			byte[] bytes = new byte[section.readableBytes()]; section.readBytes(bytes);
			var packet = new PresentationPreviewS2CPacket(1, 9, 2, 4, ADAPTER, 1, PresentationPreviewS2CPacket.Status.RESULT, bytes);
			// The denied frame is skipped before typed decoding, then rejects the whole result.
			assertNull(packet.decodeSection(access(PresentationField.Kind.NUMBER)));
			var annotated = new PresentationSection(ADAPTER, 1, Map.of(ALLOWED, new PresentationValue.NumberValue(1)), false,
				Map.of(PresentationPropertyRef.root(ADAPTER, ALLOWED), "attention"));
			PresentationCodec.write(section, annotated); bytes = new byte[section.readableBytes()]; section.readBytes(bytes);
			assertNull(new PresentationPreviewS2CPacket(1, 9, 2, 4, ADAPTER, 1, PresentationPreviewS2CPacket.Status.RESULT, bytes).decodeSection(access(PresentationField.Kind.NUMBER)));
			assertThrows(IllegalArgumentException.class, () -> PresentationPreviewS2CPacket.result(request(), annotated));
		} finally { frame.release(); section.release(); }
	}
	@Test void duplicateRequestRootsOversizeFrameAndInvalidSectionSchemaReject() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			buf.writeEnum(PresentationPreviewC2SPacket.Kind.READ); buf.writeVarInt(1); buf.writeLong(9); buf.writeLong(2); buf.writeLong(4);
			MarkerPacketCodec.writeTarget(buf, request().target()); buf.writeUtf("block", 193); buf.writeUtf(ADAPTER, 193);
			buf.writeVarInt(2); buf.writeUtf(ALLOWED, 193); buf.writeUtf(ALLOWED, 193);
			assertTrue(PresentationPreviewC2SPacket.readSafe(buf).isCorrupt());
			buf.writeZero(nx.pingwheel.common.presentation.preview.PresentationPreviewLimits.MAX_REQUEST_BYTES + 1);
			assertTrue(PresentationPreviewC2SPacket.readSafe(buf).isCorrupt());
			var valid = PresentationPreviewS2CPacket.result(request(), new PresentationSection(ADAPTER, 1, Map.of(ALLOWED, new PresentationValue.NumberValue(3)), false));
			assertNull(new PresentationPreviewS2CPacket(1, 9, 2, 4, ADAPTER, 2, PresentationPreviewS2CPacket.Status.RESULT, valid.sectionBytes()).decodeSection(access(PresentationField.Kind.NUMBER)));
		} finally { buf.release(); }
	}
	private static byte[] encode(IPacket packet) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try { packet.write(buf); byte[] result = new byte[buf.readableBytes()]; buf.readBytes(result); return result; }
		finally { buf.release(); }
	}
	private static byte[] replaceInt(byte[] valid, int offset, byte[] replacement) {
		FriendlyByteBuf original = new FriendlyByteBuf(Unpooled.wrappedBuffer(valid));
		try {
			original.readerIndex(offset); original.readVarInt(); int end = original.readerIndex();
			byte[] changed = new byte[valid.length - (end - offset) + replacement.length];
			System.arraycopy(valid, 0, changed, 0, offset);
			System.arraycopy(replacement, 0, changed, offset, replacement.length);
			System.arraycopy(valid, end, changed, offset + replacement.length, valid.length - end);
			return changed;
		} finally { original.release(); }
	}
	private static byte[] overflowingAlias(int value) {
		return new byte[] {(byte) ((value & 0x7f) | 0x80), (byte) (((value >>> 7) & 0x7f) | 0x80),
			(byte) (((value >>> 14) & 0x7f) | 0x80), (byte) (((value >>> 21) & 0x7f) | 0x80), (byte) ((value >>> 28) | 0x10)};
	}
	private record WrapperInt(String name, byte[] frame, int offset, boolean response) {
		@Override public String toString() { return name; }
	}
	private static java.util.stream.Stream<WrapperInt> wrapperInts() {
		List<WrapperInt> result = new java.util.ArrayList<>();
		byte[] request = encode(request()); FriendlyByteBuf cursor = new FriendlyByteBuf(Unpooled.wrappedBuffer(request));
		try {
			result.add(new WrapperInt("request kind", request, cursor.readerIndex(), false)); cursor.readVarInt();
			result.add(new WrapperInt("request protocol", request, cursor.readerIndex(), false)); cursor.readVarInt(); cursor.skipBytes(24);
			result.add(new WrapperInt("target kind UTF length", request, cursor.readerIndex(), false)); cursor.readUtf(256);
			result.add(new WrapperInt("target dimension UTF length", request, cursor.readerIndex(), false)); cursor.readUtf(256);
			result.add(new WrapperInt("block variant", request, cursor.readerIndex(), false)); cursor.readVarInt(); cursor.skipBytes(12);
			result.add(new WrapperInt("block ID UTF length", request, cursor.readerIndex(), false)); cursor.readUtf(256);
			result.add(new WrapperInt("target type UTF length", request, cursor.readerIndex(), false)); cursor.readUtf(193);
			result.add(new WrapperInt("request adapter UTF length", request, cursor.readerIndex(), false)); cursor.readUtf(193);
			result.add(new WrapperInt("root count", request, cursor.readerIndex(), false)); cursor.readVarInt();
			result.add(new WrapperInt("root ID UTF length", request, cursor.readerIndex(), false));
		} finally { cursor.release(); }
		byte[] response = encode(PresentationPreviewS2CPacket.result(request(),
			new PresentationSection(ADAPTER, 1, Map.of(ALLOWED, new PresentationValue.NumberValue(0)), false)));
		cursor = new FriendlyByteBuf(Unpooled.wrappedBuffer(response));
		try {
			result.add(new WrapperInt("response protocol", response, cursor.readerIndex(), true)); cursor.readVarInt(); cursor.skipBytes(24);
			result.add(new WrapperInt("response adapter UTF length", response, cursor.readerIndex(), true)); cursor.readUtf(193);
			result.add(new WrapperInt("response schema", response, cursor.readerIndex(), true)); cursor.readVarInt();
			result.add(new WrapperInt("response status", response, cursor.readerIndex(), true)); cursor.readVarInt();
			result.add(new WrapperInt("response payload length", response, cursor.readerIndex(), true));
		} finally { cursor.release(); }
		byte[] entity = encode(PresentationPreviewC2SPacket.read(9, 2, 4,
			new Target.EntityTarget("minecraft:overworld", EntityLocator.runtimeId(Integer.MAX_VALUE)), "entity", ADAPTER, Set.of(ALLOWED)));
		cursor = new FriendlyByteBuf(Unpooled.wrappedBuffer(entity));
		try {
			cursor.readVarInt(); cursor.readVarInt(); cursor.skipBytes(24); cursor.readUtf(256); cursor.readUtf(256);
			result.add(new WrapperInt("entity locator tag", entity, cursor.readerIndex(), false)); cursor.readVarInt();
			result.add(new WrapperInt("entity runtime ID", entity, cursor.readerIndex(), false));
		} finally { cursor.release(); }
		return result.stream();
	}
	@ParameterizedTest(name = "{0}") @MethodSource("wrapperInts")
	void everyWrapperIntegerRejectsOverflowNonCanonicalNegativeOverlongAndTruncated(WrapperInt field) {
		FriendlyByteBuf cursor = new FriendlyByteBuf(Unpooled.wrappedBuffer(field.frame())); int value;
		try { cursor.readerIndex(field.offset()); value = cursor.readVarInt(); }
		finally { cursor.release(); }
		for (byte[] invalid : new byte[][] {overflowingAlias(value),
			new byte[] {(byte) 0x80, 0}, // overlong encoding of zero
			new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f}, // negative width
			new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0}}) {
			assertRejected(field, replaceInt(field.frame(), field.offset(), invalid));
		}
		byte[] truncated = java.util.Arrays.copyOf(field.frame(), field.offset() + 1); truncated[field.offset()] = (byte) 0x80;
		assertRejected(field, truncated);
	}
	private static void assertRejected(WrapperInt field, byte[] bytes) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
		try {
			if (field.response()) {
				var packet = PresentationPreviewS2CPacket.readSafe(buf);
				assertTrue(packet.isCorrupt(), field.name()); assertNull(packet.decodeSection(access(PresentationField.Kind.NUMBER)));
			} else assertTrue(PresentationPreviewC2SPacket.readSafe(buf).isCorrupt(), field.name());
			assertFalse(buf.isReadable(), "safe wrapper consumes the rejected frame");
		} finally { buf.release(); }
	}
	@Test void overflowingProtocolAndRootCountRejectedInsteadOfAliasingOne() {
		byte[] valid = encode(request());
		FriendlyByteBuf cursor = new FriendlyByteBuf(Unpooled.wrappedBuffer(valid));
		try {
			cursor.readEnum(PresentationPreviewC2SPacket.Kind.class); int protocolOffset = cursor.readerIndex();
			cursor.readVarInt(); cursor.skipBytes(24); MarkerPacketCodec.readTarget(cursor); cursor.readUtf(193); cursor.readUtf(193);
			int countOffset = cursor.readerIndex();
			for (int offset : new int[] {protocolOffset, countOffset}) {
				FriendlyByteBuf changed = new FriendlyByteBuf(Unpooled.wrappedBuffer(replaceInt(valid, offset, overflowingAlias(1))));
				try { assertTrue(PresentationPreviewC2SPacket.readSafe(changed).isCorrupt(), "raw 81 80 80 80 10 must not decode as 1"); }
				finally { changed.release(); }
			}
		} finally { cursor.release(); }
	}
	@Test void overflowingResponseProtocolSchemaAndPayloadLengthRejectedBeforeTypedDecode() {
		var response = PresentationPreviewS2CPacket.result(request(), new PresentationSection(ADAPTER, 1, Map.of(ALLOWED, new PresentationValue.NumberValue(0)), false));
		byte[] valid = encode(response); FriendlyByteBuf cursor = new FriendlyByteBuf(Unpooled.wrappedBuffer(valid));
		try {
			cursor.readVarInt(); cursor.skipBytes(24); cursor.readUtf(193); int schemaOffset = cursor.readerIndex();
			cursor.readVarInt(); cursor.readEnum(PresentationPreviewS2CPacket.Status.class); int lengthOffset = cursor.readerIndex();
			int length = cursor.readVarInt();
			for (int offset : new int[] {0, schemaOffset, lengthOffset}) {
				FriendlyByteBuf changed = new FriendlyByteBuf(Unpooled.wrappedBuffer(replaceInt(valid, offset, overflowingAlias(offset == lengthOffset ? length : 1))));
				try {
					var decoded = PresentationPreviewS2CPacket.readSafe(changed);
					assertTrue(decoded.isCorrupt(), "overflow is rejected at the wrapper, not aliased into valid field decoding");
					assertNull(decoded.decodeSection(access(PresentationField.Kind.NUMBER)));
				} finally { changed.release(); }
			}
		} finally { cursor.release(); }
	}
	@Test void canonicalTargetVariantsCancelAndAllControlStatusesStillRoundTrip() {
		for (Target target : java.util.List.of(request().target(), new Target.EntityTarget("minecraft:overworld", java.util.UUID.randomUUID()),
			new Target.EntityTarget("minecraft:overworld", EntityLocator.runtimeId(Integer.MAX_VALUE)),
			new Target.ExternalBlockTarget("minecraft:overworld", "test:provider", "stable", "test:machine", "opaque-locator", true),
			new Target.LocationTarget("minecraft:overworld", 1.5, 2.5, 3.5))) {
			var request = PresentationPreviewC2SPacket.read(-1, Long.MAX_VALUE, Long.MAX_VALUE, target, "block", ADAPTER, Set.of(ALLOWED));
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(encode(request)));
			try { assertEquals(request, PresentationPreviewC2SPacket.readSafe(buf)); assertFalse(buf.isReadable()); }
			finally { buf.release(); }
		}
		var cancel = PresentationPreviewC2SPacket.cancel(-1, Long.MAX_VALUE, Long.MAX_VALUE);
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(encode(cancel)));
		try { assertEquals(cancel, PresentationPreviewC2SPacket.readSafe(buf)); }
		finally { buf.release(); }
		for (var status : java.util.List.of(PresentationPreviewS2CPacket.Status.DEFERRED, PresentationPreviewS2CPacket.Status.UNAVAILABLE, PresentationPreviewS2CPacket.Status.REJECTED)) {
			var response = PresentationPreviewS2CPacket.control(request(), 255, status);
			buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(encode(response)));
			try { var decoded = PresentationPreviewS2CPacket.readSafe(buf); assertFalse(decoded.isCorrupt()); assertEquals(status, decoded.status()); assertEquals(255, decoded.schema()); }
			finally { buf.release(); }
		}
	}
}
