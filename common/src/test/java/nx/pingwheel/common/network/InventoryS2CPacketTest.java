package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.domain.MarkerId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryS2CPacketTest {
	private static final long BIG = (1L << 53) + 1;

	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	private static InventoryS2CPacket roundTrip(InventoryS2CPacket packet) {
		FriendlyByteBuf buf = buffer();
		try {
			packet.write(buf);
			InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt());
			assertEquals(packet, decoded);
			assertEquals(0, buf.readableBytes());
			return decoded;
		} finally {
			buf.release();
		}
	}

	private static InventoryS2CPacket.Entry entry(String key, String itemId, long count, long revision) {
		return new InventoryS2CPacket.Entry(key, itemId, "Stone", null, count, revision, false, null);
	}

	private static byte[] entriesPayload(Consumer<FriendlyByteBuf> writer) {
		FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
		try {
			writer.accept(payload);
			byte[] bytes = new byte[payload.readableBytes()];
			payload.readBytes(bytes);
			return bytes;
		} finally {
			payload.release();
		}
	}

	private static void writeRawEntry(FriendlyByteBuf buf, String key, long count, long revision) {
		buf.writeUtf(key, 256);
		buf.writeUtf("minecraft:stone", 256);
		buf.writeUtf("Stone", 1024);
		buf.writeBoolean(false);
		buf.writeLong(count);
		buf.writeLong(revision);
		buf.writeBoolean(false);
		buf.writeBoolean(false);
		buf.writeLong(0L); buf.writeBoolean(false); buf.writeBoolean(false);
	}

	private static void writeStreamPrefix(FriendlyByteBuf frame, int partIndex, int partCount) {
		MarkerPacketCodec.writeEnum(frame, InventoryS2CPacket.Kind.STREAM);
		frame.writeVarInt(InventoryS2CPacket.VERSION);
		frame.writeLong(1L);
		frame.writeLong(1L);
		frame.writeLong(100L); frame.writeLong(1L);
		MarkerPacketCodec.writeOptionalMarkerId(frame, Optional.of(new MarkerId(1L)));
		frame.writeLong(1L);
		frame.writeLong(0L);
		frame.writeLong(0L);
		frame.writeVarInt(partIndex);
		frame.writeVarInt(partCount);
		frame.writeBoolean(false);
		MarkerPacketCodec.writeEnum(frame, InventoryS2CPacket.Status.READY);
		frame.writeLong(0L);
	}

	private static FriendlyByteBuf streamFrame(int partIndex, int partCount, byte[] payload) {
		FriendlyByteBuf frame = buffer();
		writeStreamPrefix(frame, partIndex, partCount);
		frame.writeVarInt(payload.length);
		frame.writeBytes(payload);
		return frame;
	}

	private static FriendlyByteBuf streamFrameWithDeclaredPayloadLength(int declaredLength) {
		FriendlyByteBuf frame = buffer();
		writeStreamPrefix(frame, 0, 1);
		frame.writeVarInt(declaredLength);
		return frame;
	}

	private static FriendlyByteBuf statusFrameWithUnknownStatus() {
		FriendlyByteBuf frame = buffer();
		MarkerPacketCodec.writeEnum(frame, InventoryS2CPacket.Kind.STATUS);
		frame.writeVarInt(InventoryS2CPacket.VERSION);
		frame.writeLong(1L);
		frame.writeLong(1L);
		frame.writeLong(100L); frame.writeLong(1L);
		MarkerPacketCodec.writeOptionalMarkerId(frame, Optional.of(new MarkerId(1L)));
		frame.writeLong(0L);
		frame.writeLong(0L);
		frame.writeLong(0L);
		frame.writeUtf("NOT_A_STATUS", 256);
		frame.writeLong(0L);
		return frame;
	}

	private static FriendlyByteBuf offerFrame(int preview, int tracking, int resync, int heartbeat) {
		FriendlyByteBuf frame = buffer();
		MarkerPacketCodec.writeEnum(frame, InventoryS2CPacket.Kind.OFFER);
		frame.writeVarInt(InventoryS2CPacket.VERSION);
		frame.writeLong(1L);
		frame.writeLong(0L);
		frame.writeLong(100L); frame.writeLong(1L);
		frame.writeVarInt(preview);
		frame.writeVarInt(tracking);
		frame.writeVarInt(resync);
		frame.writeVarInt(heartbeat);
		return frame;
	}

	@Test
	void routeVersionKindsAndStatusAreInventoryV2() {
		assertEquals(2, InventoryS2CPacket.VERSION);
		assertEquals("pingforit-s2c:inventory-v2", InventoryS2CPacket.PACKET_ID.toString());
		assertEquals(List.of(InventoryS2CPacket.Kind.OFFER, InventoryS2CPacket.Kind.POLICY, InventoryS2CPacket.Kind.SELECTED, InventoryS2CPacket.Kind.REJECT, InventoryS2CPacket.Kind.PREVIEW,
			InventoryS2CPacket.Kind.SNAPSHOT, InventoryS2CPacket.Kind.STREAM,
			InventoryS2CPacket.Kind.STATUS, InventoryS2CPacket.Kind.HEARTBEAT),
			Arrays.asList(InventoryS2CPacket.Kind.values()));
		assertEquals(List.of(InventoryS2CPacket.Status.UPDATING, InventoryS2CPacket.Status.READY,
			InventoryS2CPacket.Status.UNCERTAIN, InventoryS2CPacket.Status.INCOMPLETE,
			InventoryS2CPacket.Status.UNAVAILABLE, InventoryS2CPacket.Status.INVALID,
			InventoryS2CPacket.Status.EXPIRED, InventoryS2CPacket.Status.COMPONENT_TOO_LONG),
			Arrays.asList(InventoryS2CPacket.Status.values()));
	}

	@Test
	void offerRoundTripsAndValidatesPeriods() {
		InventoryS2CPacket.Offer offer = new InventoryS2CPacket.Offer(20, 40, 5, 0);
		InventoryS2CPacket decoded = roundTrip(InventoryS2CPacket.offer(BIG, offer));

		assertEquals(InventoryS2CPacket.Kind.OFFER, decoded.kind());
		assertEquals(BIG, decoded.epoch());
		assertEquals(offer, decoded.offer());
		assertNull(decoded.markerId());
		assertEquals(List.of(), decoded.entries());

		InventoryS2CPacket.Offer highest = new InventoryS2CPacket.Offer(InventoryLimits.MAX_PERIOD_TICKS,
			InventoryLimits.MAX_PERIOD_TICKS, InventoryLimits.MAX_PERIOD_TICKS,
			InventoryLimits.MAX_HEARTBEAT_PERIODS);
		assertEquals(highest, roundTrip(InventoryS2CPacket.offer(BIG, highest)).offer());

		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(0, 40, 5, 0));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(
			InventoryLimits.MAX_PERIOD_TICKS + 1, 40, 5, 0));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20, 0, 5, 0));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20,
			InventoryLimits.MAX_PERIOD_TICKS + 1, 5, 0));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20, 40, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20, 40,
			InventoryLimits.MAX_PERIOD_TICKS + 1, 0));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20, 40, 5, -1));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20, 40, 5,
			InventoryLimits.MAX_HEARTBEAT_PERIODS + 1));
		assertTrue(InventoryS2CPacket.offer(BIG, null).isCorrupt());
	}

	@Test
	void offerDecodeRejectsOutOfRangePeriodsWithoutDefaults() {
		FriendlyByteBuf buf = offerFrame(Integer.MAX_VALUE, 40, 5, 0);
		try {
			InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(buf);
			assertTrue(decoded.isCorrupt());
			assertNull(decoded.kind());
			assertNull(decoded.offer());
		} finally {
			buf.release();
		}

		buf = offerFrame(InventoryLimits.MAX_PERIOD_TICKS + 1, 40, 5, 0);
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = offerFrame(20, InventoryLimits.MAX_PERIOD_TICKS + 1, 5, 0);
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = offerFrame(20, 40, InventoryLimits.MAX_PERIOD_TICKS + 1, 0);
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = offerFrame(20, 40, 5, InventoryLimits.MAX_HEARTBEAT_PERIODS + 1);
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = offerFrame(InventoryLimits.MAX_PERIOD_TICKS, InventoryLimits.MAX_PERIOD_TICKS,
			InventoryLimits.MAX_PERIOD_TICKS, InventoryLimits.MAX_HEARTBEAT_PERIODS);
		try {
			InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt());
			assertEquals(InventoryLimits.MAX_PERIOD_TICKS, decoded.offer().previewPeriodTicks());
			assertEquals(InventoryLimits.MAX_HEARTBEAT_PERIODS, decoded.offer().heartbeatPeriods());
		} finally {
			buf.release();
		}
	}

	@Test
	void previewSnapshotAndStreamRoundTripWithExactLongs() {
		InventoryS2CPacket.Entry variant = new InventoryS2CPacket.Entry("sha-1", "minecraft:stone", "Stone",
			"{\"id\":\"minecraft:stone\"}", BIG + 2, BIG + 4, false, InventoryS2CPacket.Status.COMPONENT_TOO_LONG);
		InventoryS2CPacket.Entry fallback = new InventoryS2CPacket.Entry("sha-2", "minecraft:dirt", "Dirt",
			null, 0L, BIG + 6, true, null);
		List<InventoryS2CPacket.Entry> entries = List.of(variant, fallback);

		InventoryS2CPacket preview = roundTrip(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, BIG, BIG + 8,
			null, BIG + 10, BIG + 12, BIG + 14, 1, 3, false, InventoryS2CPacket.Status.UPDATING, BIG + 16, entries));
		assertEquals(InventoryS2CPacket.Kind.PREVIEW, preview.kind());
		assertNull(preview.markerId());
		assertEquals(BIG + 10, preview.baselineId());
		assertEquals(BIG + 12, preview.statusRevision());
		assertEquals(BIG + 14, preview.watermark());
		assertEquals(1, preview.partIndex());
		assertEquals(3, preview.partCount());
		assertFalse(preview.completeScan());
		assertEquals(InventoryS2CPacket.Status.UPDATING, preview.status());
		assertEquals(BIG + 16, preview.checksum());
		assertEquals(entries, preview.entries());

		InventoryS2CPacket snapshot = roundTrip(InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, BIG, BIG + 18,
			new MarkerId(BIG + 20), BIG + 22, BIG + 24, BIG + 26, 0, 1, true,
			InventoryS2CPacket.Status.READY, BIG + 28, entries));
		assertEquals(InventoryS2CPacket.Kind.SNAPSHOT, snapshot.kind());
		assertEquals(new MarkerId(BIG + 20), snapshot.markerId());
		assertTrue(snapshot.completeScan());
		assertEquals(BIG + 4, snapshot.entries().get(0).itemRevision());
		assertEquals(BIG + 2, snapshot.entries().get(0).count());

		InventoryS2CPacket stream = roundTrip(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, BIG, BIG + 30,
			new MarkerId(BIG + 32), BIG + 34, BIG + 36, BIG + 38, 2, 4, false,
			InventoryS2CPacket.Status.UNCERTAIN, BIG + 40, entries));
		assertEquals(InventoryS2CPacket.Kind.STREAM, stream.kind());
		assertEquals(new MarkerId(BIG + 32), stream.markerId());
		assertEquals(entries, stream.entries());
	}

	@Test
	void statusAndHeartbeatRoundTrip() {
		InventoryS2CPacket status = roundTrip(InventoryS2CPacket.status(BIG, BIG + 2, new MarkerId(BIG + 4),
			BIG + 6, BIG + 8, BIG + 10, InventoryS2CPacket.Status.INVALID, BIG + 12));
		assertEquals(InventoryS2CPacket.Kind.STATUS, status.kind());
		assertEquals(InventoryS2CPacket.Status.INVALID, status.status());
		assertEquals(BIG + 8, status.statusRevision());
		assertEquals(List.of(), status.entries());

		InventoryS2CPacket heartbeat = roundTrip(InventoryS2CPacket.heartbeat(BIG, BIG + 14, new MarkerId(BIG + 16),
			BIG + 18, BIG + 20, BIG + 22, BIG + 24));
		assertEquals(InventoryS2CPacket.Kind.HEARTBEAT, heartbeat.kind());
		assertNull(heartbeat.status());
		assertEquals(BIG + 22, heartbeat.watermark());
		assertEquals(BIG + 24, heartbeat.checksum());
		assertEquals(List.of(), heartbeat.entries());
	}

	@Test
	void entryTextCapsAndCountsAreEnforced() {
		String maxKey = "k".repeat(256);
		InventoryS2CPacket.Entry max = new InventoryS2CPacket.Entry(maxKey, maxKey, "l".repeat(1024),
			"d".repeat(4096), BIG, BIG + 1, true, InventoryS2CPacket.Status.READY);
		InventoryS2CPacket decoded = roundTrip(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, BIG, BIG,
			new MarkerId(BIG), 0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, List.of(max)));
		assertEquals(max, decoded.entries().get(0));

		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Entry("k".repeat(257),
			"minecraft:stone", "Stone", null, 0, 0, false, null));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Entry("k",
			"i".repeat(257), "Stone", null, 0, 0, false, null));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Entry("k",
			"minecraft:stone", "l".repeat(1025), null, 0, 0, false, null));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Entry("k",
			"minecraft:stone", "Stone", "d".repeat(4097), 0, 0, false, null));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Entry(" ",
			"minecraft:stone", "Stone", null, 0, 0, false, null));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Entry("k",
			"minecraft:stone", "Stone", null, -1, 0, false, null));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Entry("k",
			"minecraft:stone", "Stone", null, 0, -1, false, null));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Entry("\u00e9".repeat(200),
			"minecraft:stone", "Stone", null, 0, 0, false, null));
	}

	@Test
	void entryCountAndEncodedFrameBudgetAreEnforced() {
		List<InventoryS2CPacket.Entry> tooMany = new ArrayList<>();
		for (int i = 0; i < InventoryS2CPacket.MAX_ENTRIES + 1; i++) {
			tooMany.add(entry("key-" + i, "minecraft:stone", 1L, 1L));
		}
		assertThrows(IllegalArgumentException.class, () -> InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM,
			1L, 1L, new MarkerId(1L), 0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, tooMany));

		List<InventoryS2CPacket.Entry> oversized = new ArrayList<>();
		for (int i = 0; i < InventoryS2CPacket.MAX_ENTRIES; i++) {
			oversized.add(new InventoryS2CPacket.Entry("key-" + i, "minecraft:stone", "Stone", "d".repeat(4096),
				1L, 1L, false, null));
		}
		InventoryS2CPacket packet = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1L, 1L,
			new MarkerId(1L), 0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, oversized);
		FriendlyByteBuf buf = buffer();
		try {
			assertThrows(IllegalArgumentException.class, () -> packet.write(buf));
		} finally {
			buf.release();
		}
	}

	@Test
	void entireFrameBudgetIncludesHeaderAndDecodeBound() {
		List<InventoryS2CPacket.Entry> nearBound = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			nearBound.add(new InventoryS2CPacket.Entry("k" + i, "minecraft:stone", "l".repeat(1024),
				"d".repeat(3585), 1L, 1L, false, null));
		}
		InventoryS2CPacket accepted = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1L, 1L,
			new MarkerId(1L), 0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, nearBound);
		FriendlyByteBuf buf = buffer();
		try {
			accepted.write(buf);
			assertTrue(buf.readableBytes() <= InventoryS2CPacket.MAX_FRAME_BYTES);
			InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt());
			assertEquals(nearBound, decoded.entries());
		} finally {
			buf.release();
		}

		List<InventoryS2CPacket.Entry> overBound = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			overBound.add(new InventoryS2CPacket.Entry("k" + i, "minecraft:stone", "l".repeat(1024),
				"d".repeat(3610), 1L, 1L, false, null));
		}
		InventoryS2CPacket rejected = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1L, 1L,
			new MarkerId(1L), 0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, overBound);
		FriendlyByteBuf over = buffer();
		try {
			assertThrows(IllegalArgumentException.class, () -> rejected.write(over));
			assertEquals(0, over.readableBytes());
		} finally {
			over.release();
		}

		buf = streamFrame(0, 1, new byte[InventoryS2CPacket.MAX_FRAME_BYTES]);
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}
	}

	@Test
	void corruptFramesBecomeCorrupt() {
		FriendlyByteBuf buf = buffer();
		try {
			InventoryS2CPacket.heartbeat(1L, 2L, new MarkerId(3L), 4L, 5L, 6L, 7L).write(buf);
			buf.writeByte(0);
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = buffer();
		try {
			MarkerPacketCodec.writeEnum(buf, InventoryS2CPacket.Kind.OFFER);
			buf.writeVarInt(2);
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = streamFrame(3, 3, entriesPayload(payload -> writeRawEntry(payload, "key", 1L, 1L)));
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = streamFrameWithDeclaredPayloadLength(InventoryS2CPacket.MAX_FRAME_BYTES + 1);
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = streamFrame(0, 1, entriesPayload(payload -> payload.writeVarInt(InventoryS2CPacket.MAX_ENTRIES + 1)));
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = streamFrame(0, 1, entriesPayload(payload -> {
			payload.writeVarInt(1);
			writeRawEntry(payload, "key", -1L, 0L);
		}));
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = streamFrame(0, 1, entriesPayload(payload -> {
			payload.writeVarInt(1);
			payload.writeUtf("key", 256);
			payload.writeUtf("minecraft:stone", 256);
			payload.writeUtf("Stone", 1024);
			payload.writeBoolean(false);
			payload.writeLong(0L);
			payload.writeLong(0L);
			payload.writeBoolean(false);
			payload.writeBoolean(true);
			payload.writeUtf("NOT_A_STATUS", 256);
		}));
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = streamFrame(0, 1, entriesPayload(payload -> payload.writeBoolean(true)));
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = statusFrameWithUnknownStatus();
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		buf = buffer();
		try {
			MarkerPacketCodec.writeEnum(buf, InventoryS2CPacket.Kind.STREAM);
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}
	}

	@Test
	void duplicateKeysAreRejectedOnDecodeAndFlaggedCorrupt() {
		List<InventoryS2CPacket.Entry> duplicates = List.of(
			entry("same-key", "minecraft:stone", 1L, 1L),
			entry("same-key", "minecraft:dirt", 2L, 2L));
		InventoryS2CPacket packet = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1L, 1L,
			new MarkerId(1L), 0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, duplicates);
		assertTrue(packet.isCorrupt());

		FriendlyByteBuf buf = buffer();
		try {
			packet.write(buf);
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}
	}

	@Test
	void missingFieldsAndNoArgAreCorrupt() {
		assertTrue(new InventoryS2CPacket().isCorrupt());
		assertTrue(new InventoryS2CPacket(InventoryS2CPacket.Kind.SNAPSHOT, InventoryS2CPacket.VERSION,
			1L, 1L, null, 0, 0, 0, 0, 1, false, InventoryS2CPacket.Status.READY, 0, List.of(), null).isCorrupt());
		assertTrue(new InventoryS2CPacket(InventoryS2CPacket.Kind.PREVIEW, InventoryS2CPacket.VERSION,
			1L, 1L, null, 0, 0, 0, 1, 1, false, InventoryS2CPacket.Status.READY, 0, List.of(), null).isCorrupt());
		assertTrue(new InventoryS2CPacket(InventoryS2CPacket.Kind.PREVIEW, InventoryS2CPacket.VERSION,
			1L, 1L, null, 0, 0, 0, 0, 1, false, null, 0, List.of(), null).isCorrupt());
		assertTrue(new InventoryS2CPacket(InventoryS2CPacket.Kind.OFFER, InventoryS2CPacket.VERSION,
			1L, 0L, null, 0, 0, 0, 0, 1, false, null, 0, List.of(), null).isCorrupt());
		assertTrue(new InventoryS2CPacket(InventoryS2CPacket.Kind.STATUS, InventoryS2CPacket.VERSION,
			1L, 1L, new MarkerId(1L), 0, 0, 0, 0, 1, false, null, 0, List.of(), null).isCorrupt());
		assertTrue(InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 0L, -1L, new MarkerId(1L),
			0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, List.of()).isCorrupt());

		assertThrows(IllegalArgumentException.class, () -> InventoryS2CPacket.data(InventoryS2CPacket.Kind.OFFER,
			1L, 1L, null, 0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, List.of()));
		assertThrows(IllegalArgumentException.class, () -> InventoryS2CPacket.data(InventoryS2CPacket.Kind.STATUS,
			1L, 1L, null, 0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, List.of()));
	}
}
