package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigVarNumbersTest {
	// Independent fixed-shape wire fixture: physical-only mask, safe ordinary fields,
	// positive scalar/cap values and exact 1x multiplier quanta. No codec writes it.
	private static final byte[] UPDATE = hex(
		"20 00 01 01 01 07 "
			+ "00 01 01 01 00 01 00 01 00 01 00 01 00 04 00 08 "
			+ "01 00 01 00 01 00 01 00 04 00 04 00 08 01 01 01");
	private static final byte[] SNAPSHOT = concat(hex("01 01"), UPDATE, 1, UPDATE.length - 1);

	@ParameterizedTest
	@ValueSource(strings = {"00", "01", "7f", "80 01", "ff 7f", "80 80 01", "ff ff ff ff 07"})
	void canonicalNonnegativeIntBoundariesKeepTheirExactValues(String bytes) {
		int expected = switch (bytes) {
			case "00" -> 0;
			case "01" -> 1;
			case "7f" -> 127;
			case "80 01" -> 128;
			case "ff 7f" -> 16383;
			case "80 80 01" -> 16384;
			default -> Integer.MAX_VALUE;
		};
		var buf = buffer(hex(bytes));
		try {
			assertEquals(expected, ServerConfigVarNumbers.readInt(buf));
			assertEquals(0, buf.readableBytes());
		} finally { buf.release(); }
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "80", "80 80 80 80", "81 80 80 80 10", "88 80 80 80 10",
		"80 80 80 80 08", "ff ff ff ff 0f", "ff ff ff ff ff 01", "81 80 80 80 80 00",
		"80 00", "81 00", "ff 80 00", "81 80 80 80 00"})
	void truncationOverflowSignedAndNoncanonicalIntsAreRejectedBeforeNarrowing(String bytes) {
		var buf = buffer(hex(bytes));
		try {
			assertThrows(RuntimeException.class, () -> ServerConfigVarNumbers.readInt(buf), bytes);
		} finally { buf.release(); }
	}

	@ParameterizedTest
	@ValueSource(strings = {"01", "7f", "80 01", "ff ff ff ff ff ff ff ff 7f"})
	void positiveCorrelationBoundariesIncludeLongMaximum(String bytes) {
		long expected = switch (bytes) {
			case "01" -> 1;
			case "7f" -> 127;
			case "80 01" -> 128;
			default -> Long.MAX_VALUE;
		};
		var buf = buffer(hex(bytes));
		try {
			var request = ServerConfigRequestC2SPacket.readSafe(buf);
			assertFalse(request.isCorrupt());
			assertEquals(expected, request.requestId());
			assertEquals(0, buf.readableBytes());
		} finally { buf.release(); }
		buf = buffer(concat(hex(bytes), SNAPSHOT, 1, SNAPSHOT.length - 1));
		try {
			var snapshot = ServerConfigSnapshotS2CPacket.readSafe(buf);
			assertFalse(snapshot.isCorrupt());
			assertEquals(expected, snapshot.requestId());
		} finally { buf.release(); }
	}

	@ParameterizedTest
	@ValueSource(strings = {"00", "80", "81 00", "80 80 80 80 80 80 80 80",
		"81 80 80 80 80 80 80 80 80 02", "ff ff ff ff ff ff ff ff ff 01",
		"80 80 80 80 80 80 80 80 80 01", "81 80 80 80 80 80 80 80 80 80 00"})
	void invalidCorrelationWireCannotAliasAPositiveRequest(String bytes) {
		var request = buffer(hex(bytes));
		try {
			assertTrue(ServerConfigRequestC2SPacket.readSafe(request).isCorrupt());
			assertEquals(0, request.readableBytes());
		} finally { request.release(); }
		var snapshot = buffer(concat(hex(bytes), SNAPSHOT, 1, SNAPSHOT.length - 1));
		try {
			assertTrue(ServerConfigSnapshotS2CPacket.readSafe(snapshot).isCorrupt());
			assertEquals(0, snapshot.readableBytes());
		} finally { snapshot.release(); }
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 3, 4, 5, 7, 8, 9, 11, 13, 15, 17, 19, 21, 22, 24, 26, 28, 30, 32, 34, 35, 36, 37})
	void everyUpdateVariableIntRejectsOverflowWithoutServiceMutation(int offset) {
		assertRejectedUpdate(replace(UPDATE, offset, hex("81 80 80 80 10")));
	}

	@ParameterizedTest
	@ValueSource(ints = {2, 4, 5, 6, 8, 9, 10, 12, 14, 16, 18, 20, 22, 23, 25, 27, 29, 31, 33, 35, 36, 37, 38})
	void everySnapshotVariableIntRejectsOverflowRatherThanPublishingAView(int offset) {
		var buf = buffer(replace(SNAPSHOT, offset, hex("81 80 80 80 10")));
		try {
			var snapshot = ServerConfigSnapshotS2CPacket.readSafe(buf);
			assertTrue(snapshot.isCorrupt());
			assertFalse(snapshot.snapshot().isSafe());
			assertEquals(0, buf.readableBytes());
		} finally { buf.release(); }
	}

	@Test
	void reportedCapAndMaskCounterexamplesAreCorruptAndCannotApply() {
		assertRejectedUpdate(replace(UPDATE, 7, hex("81 80 80 80 10")));
		assertRejectedUpdate(replace(UPDATE, 0, hex("88 80 80 80 10")));
	}

	@ParameterizedTest
	@MethodSource("invalidNumberEncodings")
	void malformedCapAndMaskGrammarBothRejectWithoutMutation(String raw) {
		assertRejectedUpdate(replace(UPDATE, 7, hex(raw)));
		assertRejectedUpdate(replace(UPDATE, 0, hex(raw)));
	}

	@Test
	void legalMaximumRetainedCapAndOriginalIntStillDecodeExactly() {
		byte[] cap = replace(UPDATE, 7, hex("ff ff ff ff 07"));
		byte[] ms = replace(cap, 3, hex("ff ff ff ff 07"));
		var buf = buffer(ms);
		try {
			var packet = ServerConfigUpdateC2SPacket.readSafe(buf);
			assertFalse(packet.isCorrupt());
			assertEquals(Integer.MAX_VALUE, packet.msToRegenerate());
			assertEquals(Integer.MAX_VALUE, packet.inventory().toSettings().getPhysicalSlotsPerTick().getValue());
			assertTrue(ServerConfigUpdateService.apply(true, current(), packet.update()).applied());
		} finally { buf.release(); }
	}

	private static Stream<String> invalidNumberEncodings() {
		return Stream.of("81 80 80 80 10", "80 80 80 80 08", "ff ff ff ff 0f",
			"81 80 80 80 80 00", "81 00", "80 00", "ff 80 00");
	}
	private static void assertRejectedUpdate(byte[] raw) {
		var buf = buffer(raw);
		try {
			var packet = ServerConfigUpdateC2SPacket.readSafe(buf);
			assertTrue(packet.isCorrupt());
			assertEquals(0, buf.readableBytes());
			ServerConfigSnapshot current = current();
			var result = ServerConfigUpdateService.apply(true, current, packet.update());
			assertFalse(result.applied());
			assertEquals(current, result.snapshot());
		} finally { buf.release(); }
	}
	private static ServerConfigSnapshot current() {
		return new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23);
	}
	private static FriendlyByteBuf buffer(byte[] raw) {
		return new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
	}
	private static byte[] hex(String raw) {
		return HexFormat.of().parseHex(raw.replace(" ", ""));
	}
	private static byte[] replace(byte[] source, int offset, byte[] bytes) {
		var result = new byte[source.length - 1 + bytes.length];
		System.arraycopy(source, 0, result, 0, offset);
		System.arraycopy(bytes, 0, result, offset, bytes.length);
		System.arraycopy(source, offset + 1, result, offset + bytes.length, source.length - offset - 1);
		return result;
	}
	private static byte[] concat(byte[] prefix, byte[] source, int offset, int length) {
		var result = new byte[prefix.length + length];
		System.arraycopy(prefix, 0, result, 0, prefix.length);
		System.arraycopy(source, offset, result, prefix.length, length);
		return result;
	}
}
