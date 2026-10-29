package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.config.ServerConfigUpdateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HexFormat;
import java.util.Arrays;
import java.math.BigDecimal;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigVarNumbersTest {
	// Independent v5 fixture, not codec-produced: pending memory carries mode=1
	// and finite=65; tracking server slots=128 is a two-byte varint. In-range
	// non-step counts remain literal integers. Size leaves have no mode byte.
	private static final byte @Unmodifiable [] UPDATE = hex(
		"20 00 01 01 01 07 "
			+ "00 10 01 41 07 00 11 00 43 00 61 00 03 00 04 00 08 "
			+ "03 00 09 00 51 00 80 01 00 04 00 04 00 08 00 01 01 04 04 01 00 01");
	private static final byte @Unmodifiable [] SNAPSHOT = concat(hex("01 01"), UPDATE, 1, UPDATE.length - 1);

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
	@ValueSource(ints = {0, 1, 3, 4, 5, 7, 9, 10, 12, 14, 16, 18, 20, 22, 23, 25, 27, 29, 32, 34, 36, 37, 38, 39, 40, 41})
	void everyUpdateVariableIntRejectsOverflowWithoutServiceMutation(int offset) {
		assertRejectedUpdate(replace(UPDATE, offset, hex("81 80 80 80 10")));
	}

	@ParameterizedTest
	@ValueSource(ints = {2, 4, 5, 6, 8, 10, 11, 13, 15, 17, 19, 21, 23, 24, 26, 28, 30, 33, 35, 37, 38, 39, 40, 41, 42})
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
		byte[] cap = replace(UPDATE, 7, hex("80 08"));
		byte[] ms = replace(cap, 3, hex("ff ff ff ff 07"));
		var buf = buffer(ms);
		try {
			var packet = ServerConfigUpdateC2SPacket.readSafe(buf);
			assertFalse(packet.isCorrupt());
			assertEquals(Integer.MAX_VALUE, packet.msToRegenerate());
			assertEquals(1024, packet.inventory().toSettings().getPhysicalSlotsPerTick().getValue());
			assertTrue(ServerConfigUpdateService.apply(true, current(), packet.update()).applied());
		} finally { buf.release(); }
	}

	@Test
	void independentFixturesPinMemoryModeAndRetainedFiniteBytesAndUnchangedRequest() {
		var inventory = fixtureInventory();
		assertEncoding(UPDATE, new ServerConfigUpdateC2SPacket(32, ChannelMode.AUTO, true, 1, 1, 7, inventory, true));
		assertEncoding(SNAPSHOT, new ServerConfigSnapshotS2CPacket(1L, true, ChannelMode.AUTO, true, 1, 1, 7, inventory, true));
		assertEncoding(hex("01"), new ServerConfigRequestC2SPacket(1L));
		byte[] falseUpdate = replace(UPDATE, UPDATE.length - 1, hex("00"));
		byte[] falseSnapshot = replace(SNAPSHOT, SNAPSHOT.length - 1, hex("00"));
		assertEncoding(falseUpdate, new ServerConfigUpdateC2SPacket(32, ChannelMode.AUTO, true, 1, 1, 7, inventory, false));
		assertEncoding(falseSnapshot, new ServerConfigSnapshotS2CPacket(1L, true, ChannelMode.AUTO, true, 1, 1, 7, inventory, false));
		for (boolean enabled : new boolean[] {false, true}) {
			var buf = buffer(enabled ? UPDATE.clone() : falseUpdate.clone());
			try {
				var packet = ServerConfigUpdateC2SPacket.readSafe(buf);
				assertFalse(packet.isCorrupt());
				assertEquals(enabled, packet.update().budgetDelayNotificationsEnabled());
				assertEquals(inventory, packet.inventory());
				assertEquals(0, buf.readableBytes());
			} finally { buf.release(); }
			buf = buffer(enabled ? SNAPSHOT.clone() : falseSnapshot.clone());
			try {
				var packet = ServerConfigSnapshotS2CPacket.readSafe(buf);
				assertFalse(packet.isCorrupt());
				assertEquals(enabled, packet.snapshot().budgetDelayNotificationsEnabled());
				assertEquals(inventory, packet.inventory());
				assertEquals(0, buf.readableBytes());
			} finally { buf.release(); }
		}
	}

	@Test
	void independentRawShapesRejectEveryTruncationMissingFlagMalformedFlagAndTrailingBytes() {
		assertStrictShape(UPDATE, ServerConfigUpdateC2SPacket::readSafe);
		assertStrictShape(SNAPSHOT, ServerConfigSnapshotS2CPacket::readSafe);
		for (String invalid : new String[] {"02", "ff"}) {
			assertRejectedUpdate(replace(UPDATE, UPDATE.length - 1, hex(invalid)));
		}
	}

	@Test
	void independentMemoryModeRejectsMalformedModesAndSupersededScalarShape() {
		for (String invalid : new String[] {"02", "ff"}) {
			assertRejectedUpdate(replace(UPDATE, 8, hex(invalid)));
			assertCorruptAndDrained(replace(SNAPSHOT, 9, hex(invalid)), ServerConfigSnapshotS2CPacket::readSafe);
		}
		assertRejectedUpdate(replace(UPDATE, 8, new byte[0]));
		assertCorruptAndDrained(replace(SNAPSHOT, 9, new byte[0]), ServerConfigSnapshotS2CPacket::readSafe);
		// Even with unlimited selected, the retained finite memory value must be legal.
		assertRejectedUpdate(replace(UPDATE, 9, hex("0f")));
		assertRejectedUpdate(replace(UPDATE, 9, hex("81 08")));
		assertRejectedUpdate(replace(UPDATE, 7, hex("81 08")));
	}

	@Test
	void rawNoticeMaskBit26AppliesFalseWhileBit29RemainsUnknown() {
		byte[] raw = replace(replace(UPDATE, UPDATE.length - 1, hex("00")), 0, hex("80 80 80 20"));
		var buf = buffer(raw);
		try {
			var packet = ServerConfigUpdateC2SPacket.readSafe(buf);
			assertFalse(packet.isCorrupt());
			assertEquals(ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED, packet.changedFields());
			var result = ServerConfigUpdateService.apply(true, current(), packet.update());
			assertTrue(result.applied());
			assertFalse(result.snapshot().budgetDelayNotificationsEnabled());
			assertEquals(current().inventory(), result.snapshot().inventory());
			assertEquals(current().rateLimit(), result.snapshot().rateLimit());
			assertEquals(0, buf.readableBytes());
		} finally { buf.release(); }
		assertRejectedUpdate(replace(UPDATE, 0, hex("80 80 80 80 02")));
	}

	@Test
	void rawV5BooleanOrderBitsAndAllIndependentValuesPreserveUnselectedAuthority() {
		for (boolean faces : new boolean[] {false, true}) {
			for (boolean players : new boolean[] {false, true}) {
				byte[] raw = UPDATE.clone();
				raw[raw.length - 3] = (byte) (faces ? 1 : 0);
				raw[raw.length - 2] = (byte) (players ? 1 : 0);
				var inventory = fixtureInventory().with(Field.TRACKING_DISTINGUISH_FACES, Value.bool(faces))
					.with(Field.TRACKING_DISTINGUISH_PLAYERS, Value.bool(players));
				assertEncoding(raw, new ServerConfigUpdateC2SPacket(32, ChannelMode.AUTO, true, 1, 1, 7, inventory, true));
				assertEncoding(concat(hex("01 01"), raw, 1, raw.length - 1),
					new ServerConfigSnapshotS2CPacket(1, true, ChannelMode.AUTO, true, 1, 1, 7, inventory, true));
				for (int bit : new int[] {27, 28}) {
					byte[] selected = replace(raw, 0, hex(bit == 27 ? "80 80 80 40" : "80 80 80 80 01"));
					var buf = buffer(selected);
					try {
						var packet = ServerConfigUpdateC2SPacket.readSafe(buf);
						assertFalse(packet.isCorrupt());
						assertEquals(1 << bit, packet.changedFields());
						var result = ServerConfigUpdateService.apply(true, current(), packet.update());
						assertTrue(result.applied());
						assertEquals(bit == 27 && faces, result.snapshot().inventory().value(Field.TRACKING_DISTINGUISH_FACES).booleanValue());
						assertEquals(bit == 28 && players, result.snapshot().inventory().value(Field.TRACKING_DISTINGUISH_PLAYERS).booleanValue());
						assertEquals(current().inventory().value(Field.PENDING_MEMORY_MIB), result.snapshot().inventory().value(Field.PENDING_MEMORY_MIB));
						var denied = ServerConfigUpdateService.apply(false, current(), packet.update());
						assertFalse(denied.applied());
						assertEquals(current().inventory(), denied.snapshot().inventory());
					} finally { buf.release(); }
				}
			}
		}
	}

	@Test
	void bothNewBooleanBytesRejectNonBooleanOverlongMissingAndSupersededV4Shapes() {
		for (int fromEnd : new int[] {2, 3}) {
			for (String invalid : new String[] {"02", "ff", "81 00"}) {
				assertRejectedUpdate(replace(UPDATE, UPDATE.length - fromEnd, hex(invalid)));
				assertCorruptAndDrained(replace(SNAPSHOT, SNAPSHOT.length - fromEnd, hex(invalid)), ServerConfigSnapshotS2CPacket::readSafe);
			}
			assertRejectedUpdate(replace(UPDATE, UPDATE.length - fromEnd, new byte[0]));
			assertCorruptAndDrained(replace(SNAPSHOT, SNAPSHOT.length - fromEnd, new byte[0]), ServerConfigSnapshotS2CPacket::readSafe);
		}
		byte[] oldUpdate = replace(replace(UPDATE, UPDATE.length - 2, new byte[0]), UPDATE.length - 3, new byte[0]);
		assertRejectedUpdate(oldUpdate);
		assertCorruptAndDrained(concat(hex("01 01"), oldUpdate, 1, oldUpdate.length - 1), ServerConfigSnapshotS2CPacket::readSafe);
	}

	private static <T extends IPacket> void assertStrictShape(byte[] raw, Function<FriendlyByteBuf, T> reader) {
		for (int length = 0; length < raw.length; length++) {
			assertCorruptAndDrained(Arrays.copyOf(raw, length), reader);
		}
		for (String invalid : new String[] {"02", "ff"}) {
			assertCorruptAndDrained(replace(raw, raw.length - 1, hex(invalid)), reader);
		}
		for (byte trailing : new byte[] {0, 1, 2, (byte) 255}) {
			byte[] extended = Arrays.copyOf(raw, raw.length + 1);
			extended[raw.length] = trailing;
			assertCorruptAndDrained(extended, reader);
		}
	}
	private static <T extends IPacket> void assertCorruptAndDrained(byte[] raw, Function<FriendlyByteBuf, T> reader) {
		var buf = buffer(raw);
		try {
			assertTrue(reader.apply(buf).isCorrupt());
			assertEquals(0, buf.readableBytes());
		} finally { buf.release(); }
	}
	private static void assertEncoding(byte[] expected, IPacket packet) {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buf);
			byte[] actual = new byte[buf.readableBytes()];
			buf.readBytes(actual);
			assertArrayEquals(expected, actual);
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
	private static InventoryConfigValues fixtureInventory() {
		var values = InventoryConfigValues.defaults();
		for (Field field : Field.values()) {
			if (field.isBoolean()) {
				values = values.with(field, Value.bool(field == Field.TRACKING_DISTINGUISH_FACES));
				continue;
			}
			int integer = switch (field) {
				case PHYSICAL_SLOTS_PER_TICK -> 16;
				case PENDING_MEMORY_MIB -> 65;
				case PREVIEW_PERIOD_TICKS -> 7;
				case PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD -> 17;
				case PREVIEW_MAX_SLOTS_PER_CLIENT -> 67;
				case PREVIEW_MAX_SLOTS_SERVER -> 97;
				case PREVIEW_MAX_TARGETS_PER_CLIENT, TRACKING_PERIOD_TICKS -> 3;
				case TRACKING_MAX_VARIANTS_PER_TARGET -> 9;
				case TRACKING_MAX_SLOTS_PER_TARGET -> 81;
				case TRACKING_MAX_SLOTS_SERVER -> 128;
				case TRACKING_RESYNC_MIN_PERIODS -> 0;
				default -> 1;
			};
			values = values.with(field, new Value(field == Field.PENDING_MEMORY_MIB, BigDecimal.valueOf(integer)));
		}
		return values;
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
