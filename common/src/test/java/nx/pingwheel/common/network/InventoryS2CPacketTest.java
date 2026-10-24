package nx.pingwheel.common.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryDetachedItem;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentItem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryS2CPacketTest {
	private static final long BIG = (1L << 53) + 1;
	/** Smallest legal frame-scale budget for a 128 KiB display: display + metadata reserve on the frame grid. */
	private static final int MAX_FRAME_FOR_MAX_DISPLAY = 136 * 1024;

	private static RegistryAccess registries;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

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
		return offerFrame(preview, tracking, resync, heartbeat, InventoryLimits.DISPLAY_BASE_BYTES,
			InventoryLimits.FRAME_BASE_BYTES);
	}

	private static FriendlyByteBuf offerFrame(int preview, int tracking, int resync, int heartbeat, int displayBytes,
		int frameBytes) {
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
		frame.writeVarInt(displayBytes);
		frame.writeVarInt(frameBytes);
		return frame;
	}

	private static FriendlyByteBuf offerFrameWithRawResync(byte[] resync) {
		FriendlyByteBuf frame = buffer();
		MarkerPacketCodec.writeEnum(frame, InventoryS2CPacket.Kind.OFFER);
		frame.writeVarInt(InventoryS2CPacket.VERSION);
		frame.writeLong(1L);
		frame.writeLong(0L);
		frame.writeLong(100L); frame.writeLong(1L);
		frame.writeVarInt(20);
		frame.writeVarInt(40);
		frame.writeBytes(resync);
		frame.writeVarInt(0);
		frame.writeVarInt(InventoryLimits.DISPLAY_BASE_BYTES);
		frame.writeVarInt(InventoryLimits.FRAME_BASE_BYTES);
		return frame;
	}

	@Test
	void routeVersionKindsAndStatusAreInventoryV1() {
		assertEquals(1, InventoryS2CPacket.VERSION);
		assertEquals(InventoryC2SPacket.VERSION, InventoryS2CPacket.VERSION,
			"both inventory directions declare one protocol version");
		assertEquals("pingforit-s2c:inventory-v1", InventoryS2CPacket.PACKET_ID.toString());
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
	void previousInventoryV4FramesAreRejectedOnTheV1Route() {
		FriendlyByteBuf frame = buffer();
		try {
			MarkerPacketCodec.writeEnum(frame, InventoryS2CPacket.Kind.OFFER);
			frame.writeVarInt(4);
			frame.writeLong(1L);
			frame.writeLong(0L);
			frame.writeLong(100L);
			frame.writeLong(1L);
			frame.writeVarInt(20);
			frame.writeVarInt(40);
			frame.writeVarInt(5);
			frame.writeVarInt(0);
			frame.writeVarInt(InventoryLimits.DISPLAY_BASE_BYTES);
			frame.writeVarInt(InventoryLimits.FRAME_BASE_BYTES);
			assertTrue(InventoryS2CPacket.readSafe(frame).isCorrupt());
		} finally {
			frame.release();
		}
	}

	@Test
	void offerRoundTripsAndValidatesPeriods() {
		InventoryS2CPacket.Offer offer = new InventoryS2CPacket.Offer(20, 40, 5, 0);
		InventoryS2CPacket decoded = roundTrip(InventoryS2CPacket.offer(BIG, offer));

		assertEquals(InventoryS2CPacket.Kind.OFFER, decoded.kind());
		assertEquals(BIG, decoded.epoch());
		assertEquals(offer, decoded.offer());
		assertEquals(InventoryLimits.DISPLAY_BASE_BYTES, decoded.offer().effectiveDisplayBytes(),
			"the legacy Java constructor keeps the 1x display budget");
		assertEquals(InventoryLimits.FRAME_BASE_BYTES, decoded.offer().effectiveFrameBytes(),
			"the legacy Java constructor keeps the 1x frame budget");
		assertNull(decoded.markerId());
		assertEquals(List.of(), decoded.entries());

		InventoryS2CPacket.Offer configured = new InventoryS2CPacket.Offer(20, 40, 5, 0,
			InventoryLimits.MAX_DISPLAY_BYTES, InventoryLimits.MAX_FRAME_BYTES);
		assertEquals(configured, roundTrip(InventoryS2CPacket.offer(BIG, configured)).offer());

		InventoryS2CPacket.Offer highest = new InventoryS2CPacket.Offer(InventoryLimits.MAX_PERIOD_TICKS,
			InventoryLimits.MAX_PERIOD_TICKS, Integer.MAX_VALUE,
			InventoryLimits.MAX_HEARTBEAT_PERIODS);
		assertEquals(highest, roundTrip(InventoryS2CPacket.offer(BIG, highest)).offer());
		assertEquals(72001, roundTrip(InventoryS2CPacket.offer(BIG,
			new InventoryS2CPacket.Offer(20, 40, 72001, 0))).offer().resyncMinPeriods());

		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(0, 40, 5, 0));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(
			InventoryLimits.MAX_PERIOD_TICKS + 1, 40, 5, 0));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20, 0, 5, 0));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20,
			InventoryLimits.MAX_PERIOD_TICKS + 1, 5, 0));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20, 40, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20, 40, 5, -1));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20, 40, 5,
			InventoryLimits.MAX_HEARTBEAT_PERIODS + 1));
		assertTrue(InventoryS2CPacket.offer(BIG, null).isCorrupt());
	}

	@Test
	void offerRejectsOffGridBudgetsAndAPairWithoutTheMetadataReserve() {
		// 9216 bytes (9 KiB) is not on the display scale; 4096 bytes (0.25 KiB) is below the frame scale.
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20, 40, 5, 0, 9216,
			InventoryLimits.FRAME_BASE_BYTES));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20, 40, 5, 0,
			InventoryLimits.DISPLAY_BASE_BYTES, 4096));
		// A legal pair still fails when display + fixed reserve exceeds the frame.
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Offer(20, 40, 5, 0,
			InventoryLimits.FRAME_BASE_BYTES, InventoryLimits.FRAME_BASE_BYTES));
		assertDoesNotThrow(() -> new InventoryS2CPacket.Offer(20, 40, 5, 0, 4096, 8192));
	}

	@Test
	void offerDecodeRejectsOffGridBudgetsWithoutDefaults() {
		FriendlyByteBuf buf = offerFrame(20, 40, 5, 0, 9216, InventoryLimits.FRAME_BASE_BYTES);
		try {
			InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(buf);
			assertTrue(decoded.isCorrupt());
			assertNull(decoded.offer());
		} finally {
			buf.release();
		}

		buf = offerFrame(20, 40, 5, 0, InventoryLimits.FRAME_BASE_BYTES, InventoryLimits.FRAME_BASE_BYTES);
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}
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
			InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt());
			assertEquals(InventoryLimits.MAX_PERIOD_TICKS + 1, decoded.offer().resyncMinPeriods());
		} finally {
			buf.release();
		}

		buf = offerFrame(20, 40, 0, 0);
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
		} finally {
			buf.release();
		}

		for (byte[] raw : List.of(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10},
			new byte[] {(byte) 0x85, 0})) {
			buf = offerFrameWithRawResync(raw);
			try {
				assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt());
			} finally {
				buf.release();
			}
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
			"d".repeat(InventoryS2CPacket.MAX_DISPLAY_BYTES), BIG, BIG + 1, true, InventoryS2CPacket.Status.READY);
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
			"minecraft:stone", "Stone", "d".repeat(InventoryS2CPacket.MAX_DISPLAY_BYTES + 1), 0, 0, false, null));
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
			assertThrows(IllegalArgumentException.class,
				() -> packet.write(buf, InventoryLimits.DISPLAY_BASE_BYTES, InventoryLimits.FRAME_BASE_BYTES));
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}
	}

	@Test
	void absoluteDisplayEnvelopeRoundTripsAtTheAbsoluteBound() {
		InventoryS2CPacket.Entry envelope = new InventoryS2CPacket.Entry("k", "minecraft:stone", "Stone",
			"d".repeat(InventoryS2CPacket.MAX_DISPLAY_BYTES), BIG, BIG + 1, false, null);
		InventoryS2CPacket.Entry worstCase = new InventoryS2CPacket.Entry("k".repeat(256), "i".repeat(256),
			"l".repeat(1024), "d".repeat(InventoryS2CPacket.MAX_DISPLAY_BYTES), BIG, BIG + 2, true,
			InventoryS2CPacket.Status.COMPONENT_TOO_LONG, BIG + 3, true, "p".repeat(256));

		assertEquals(InventoryLimits.MAX_FRAME_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES);
		assertEquals(InventoryLimits.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_DISPLAY_BYTES);
		assertTrue(InventoryS2CPacket.MAX_ENTRY_BYTES >= InventoryS2CPacket.MAX_DISPLAY_BYTES + 1024,
			"the entry envelope never understates metadata plus display");

		for (InventoryS2CPacket.Entry entry : List.of(envelope, worstCase)) {
			InventoryS2CPacket packet = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, BIG, BIG,
				new MarkerId(BIG), 0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, List.of(entry));
			FriendlyByteBuf buf = buffer();
			try {
				packet.write(buf);
				assertTrue(buf.readableBytes() <= InventoryS2CPacket.MAX_FRAME_BYTES);
				InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(buf);
				assertFalse(decoded.isCorrupt());
				assertEquals(List.of(entry), decoded.entries());
			} finally {
				buf.release();
			}
		}
	}

	@Test
	void defaultIconTerminalAndUnavailableDisplayRemainDistinctWithExactLongMetadata() {
		String terminal = "{\"id\":\"minecraft:stone\"}";
		var exact = new InventoryS2CPacket.Entry("exact-selection", "minecraft:stone", "block.minecraft.stone",
			terminal, BIG, BIG + 1, false, null);
		var unavailable = new InventoryS2CPacket.Entry("unavailable-selection", "minecraft:stone", "block.minecraft.stone",
			null, BIG, BIG + 1, false, null);
		var packet = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, BIG, BIG, new MarkerId(BIG),
			1, 1, 1, 0, 1, true, InventoryS2CPacket.Status.READY, BIG + 2, List.of(exact, unavailable));
		var decoded = roundTrip(packet);
		assertEquals(List.of(exact, unavailable), decoded.entries());
		assertEquals(BIG, decoded.entries().getFirst().count());
		assertTrue(nx.pingwheel.common.presentation.inventory.minecraft.InventoryDisplayWriter.isDefaultIcon(
			decoded.entries().getFirst().itemId(), decoded.entries().getFirst().displayJson()));
		assertNull(decoded.entries().get(1).displayJson());
		assertFalse(decoded.entries().getFirst().fallback());
	}

	@Test
	void maximumDisplayRealItemPayloadRoundTripsAtTheOfferEffectiveBoundary() {
		DisplayFixture fixture = maximumDisplayFixture(Items.STONE, InventoryS2CPacket.MAX_DISPLAY_BYTES);
		assertEquals(InventoryS2CPacket.MAX_DISPLAY_BYTES, utf8(fixture.json()), "independent UTF-8 measurement");
		assertEquals(InventoryS2CPacket.MAX_DISPLAY_BYTES, io.netty.buffer.ByteBufUtil.utf8Bytes(fixture.json()),
			"the wire UTF codec agrees on the exact byte count");

		ItemStack parsed = parseSingleItem(fixture.json());
		assertEquals(1, parsed.getCount(), "the display payload is a count-one item");
		assertEquals(Items.STONE, parsed.getItem());
		CompoundTag payload = parsed.get(DataComponents.CUSTOM_DATA).copyTag();
		assertEquals(42, payload.getInt("marker"), "persistent custom data survives the real item codec");
		assertEquals(fixture.data(), payload.getString("data"), "the multibyte custom text is exact");

		InventoryS2CPacket.Offer effective = roundTrip(InventoryS2CPacket.offer(BIG,
			new InventoryS2CPacket.Offer(20, 40, 5, 0, InventoryS2CPacket.MAX_DISPLAY_BYTES, MAX_FRAME_FOR_MAX_DISPLAY))).offer();
		assertEquals(InventoryS2CPacket.MAX_DISPLAY_BYTES, effective.effectiveDisplayBytes());
		assertEquals(MAX_FRAME_FOR_MAX_DISPLAY, effective.effectiveFrameBytes());

		InventoryS2CPacket.Entry entry = new InventoryS2CPacket.Entry("variant", "minecraft:stone", "Stone",
			fixture.json(), BIG, BIG + 1, false, null);
		InventoryS2CPacket packet = InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, BIG, BIG + 2,
			new MarkerId(BIG + 3), BIG + 4, BIG + 5, BIG + 6, 0, 1, true, InventoryS2CPacket.Status.READY, BIG + 7,
			List.of(entry));
		int measured = packet.encodedSize(effective.effectiveDisplayBytes(), effective.effectiveFrameBytes());
		assertTrue(measured > InventoryS2CPacket.MAX_DISPLAY_BYTES && measured <= MAX_FRAME_FOR_MAX_DISPLAY,
			"the maximum display plus its metadata fits the configured 136 KiB frame");

		FriendlyByteBuf buf = buffer();
		try {
			packet.write(buf, effective.effectiveDisplayBytes(), effective.effectiveFrameBytes());
			assertEquals(measured, buf.readableBytes(), "exact measurement equals the actual wire bytes");
			InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt());
			assertEquals(packet, decoded);
			assertEquals(fixture.json(), decoded.entries().get(0).displayJson(), "the display data is preserved byte for byte");
			assertEquals(BIG, decoded.entries().get(0).count(), "the exact long count is preserved");
			ItemStack decodedStack = parseSingleItem(decoded.entries().get(0).displayJson());
			assertEquals(1, decodedStack.getCount(), "the decoded payload is still a count-one item");
			assertEquals(payload, decodedStack.get(DataComponents.CUSTOM_DATA).copyTag(),
				"the decoded components equal the observed persistent custom data");
		} finally {
			buf.release();
		}
	}

	@Test
	void maximumDisplayRealItemPayloadBoundaryRejectsOneByteOverAndAFrameTooSmall() {
		DisplayFixture over = maximumDisplayFixture(Items.STONE, InventoryS2CPacket.MAX_DISPLAY_BYTES + 1);
		assertEquals(InventoryS2CPacket.MAX_DISPLAY_BYTES + 1, utf8(over.json()));
		assertEquals(1, parseSingleItem(over.json()).getCount(),
			"the over-bound fixture is still a valid count-one item payload");
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Entry("variant", "minecraft:stone",
			"Stone", over.json(), 1L, 1L, false, null));

		FriendlyByteBuf overBuf = streamFrame(0, 1, entriesPayload(payload -> {
			payload.writeVarInt(1);
			payload.writeUtf("variant", 256);
			payload.writeUtf("minecraft:stone", 256);
			payload.writeUtf("Stone", 1024);
			payload.writeBoolean(true);
			payload.writeUtf(over.json(), InventoryS2CPacket.MAX_DISPLAY_BYTES + 1);
			payload.writeLong(1L);
			payload.writeLong(1L);
			payload.writeBoolean(false);
			payload.writeBoolean(false);
			payload.writeLong(0L);
			payload.writeBoolean(false);
			payload.writeBoolean(false);
		}));
		try {
			assertTrue(InventoryS2CPacket.readSafe(overBuf).isCorrupt(),
				"one byte over the absolute display bound is rejected on decode");
		} finally {
			overBuf.release();
		}

		DisplayFixture stone = maximumDisplayFixture(Items.STONE, InventoryS2CPacket.MAX_DISPLAY_BYTES);
		DisplayFixture dirt = maximumDisplayFixture(Items.DIRT, InventoryS2CPacket.MAX_DISPLAY_BYTES);
		InventoryS2CPacket.Entry stoneEntry = new InventoryS2CPacket.Entry("stone", "minecraft:stone", "Stone",
			stone.json(), 1L, 1L, false, null);
		InventoryS2CPacket.Entry dirtEntry = new InventoryS2CPacket.Entry("dirt", "minecraft:dirt", "Dirt",
			dirt.json(), 1L, 1L, false, null);
		InventoryS2CPacket single = InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, BIG, BIG + 1,
			new MarkerId(BIG + 2), BIG + 3, BIG + 4, BIG + 5, 0, 1, true, InventoryS2CPacket.Status.READY, BIG + 6,
			List.of(stoneEntry));
		InventoryS2CPacket tooLarge = InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, BIG, BIG + 1,
			new MarkerId(BIG + 2), BIG + 3, BIG + 4, BIG + 5, 0, 1, true, InventoryS2CPacket.Status.READY, BIG + 6,
			List.of(stoneEntry, dirtEntry));
		assertTrue(single.encodedSize(InventoryS2CPacket.MAX_DISPLAY_BYTES, MAX_FRAME_FOR_MAX_DISPLAY)
			<= MAX_FRAME_FOR_MAX_DISPLAY, "one maximum display entry fits the configured frame");
		assertThrows(IllegalArgumentException.class,
			() -> tooLarge.encodedSize(InventoryS2CPacket.MAX_DISPLAY_BYTES, MAX_FRAME_FOR_MAX_DISPLAY),
			"two maximum displays exceed the configured frame");

		FriendlyByteBuf frameBuf = buffer();
		try {
			assertThrows(IllegalArgumentException.class,
				() -> tooLarge.write(frameBuf, InventoryS2CPacket.MAX_DISPLAY_BYTES, MAX_FRAME_FOR_MAX_DISPLAY));
			assertEquals(0, frameBuf.readableBytes(), "an over-budget frame writes nothing to the destination");
		} finally {
			frameBuf.release();
		}
	}

	@Test
	void configuredFrameBudgetIsExactAndRejectsOneByteMore() {
		int displayBytes = InventoryLimits.MAX_DISPLAY_BYTES;
		int frameBytes = InventoryLimits.MAX_FRAME_BYTES;
		List<InventoryS2CPacket.Entry> probe = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			probe.add(new InventoryS2CPacket.Entry("k" + i, "minecraft:stone", "Stone",
				"d".repeat(displayBytes), 1L, 1L, false, null));
		}
		probe.add(new InventoryS2CPacket.Entry("k3", "minecraft:stone", "Stone", "d".repeat(130000), 1L, 1L, false, null));

		int probeSize = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1L, 1L, new MarkerId(1L), 0, 0, 0, 0,
			1, true, InventoryS2CPacket.Status.READY, 0, probe).encodedSize(displayBytes, frameBytes);
		int exactDisplay = 130000 + (frameBytes - probeSize);
		assertTrue(exactDisplay >= 130000 && exactDisplay <= displayBytes, "the probe leaves adjustable room");

		List<InventoryS2CPacket.Entry> exactEntries = new ArrayList<>(probe.subList(0, 3));
		exactEntries.add(new InventoryS2CPacket.Entry("k3", "minecraft:stone", "Stone", "d".repeat(exactDisplay),
			1L, 1L, false, null));
		InventoryS2CPacket exact = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1L, 1L, new MarkerId(1L),
			0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, exactEntries);
		assertEquals(frameBytes, exact.encodedSize(displayBytes, frameBytes));

		FriendlyByteBuf buf = buffer();
		try {
			exact.write(buf, displayBytes, frameBytes);
			assertEquals(frameBytes, buf.readableBytes(), "the exact configured frame is written at its budget");
			InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt());
			assertEquals(exactEntries, decoded.entries());
		} finally {
			buf.release();
		}

		List<InventoryS2CPacket.Entry> overEntries = new ArrayList<>(probe.subList(0, 3));
		overEntries.add(new InventoryS2CPacket.Entry("k3", "minecraft:stone", "Stone",
			"d".repeat(exactDisplay + 1), 1L, 1L, false, null));
		InventoryS2CPacket over = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1L, 1L, new MarkerId(1L),
			0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, overEntries);
		FriendlyByteBuf overBuf = buffer();
		try {
			assertThrows(IllegalArgumentException.class, () -> over.encodedSize(displayBytes, frameBytes));
			assertThrows(IllegalArgumentException.class, () -> over.write(overBuf, displayBytes, frameBytes));
			assertEquals(0, overBuf.readableBytes(), "an over-budget frame writes nothing to the destination");
		} finally {
			overBuf.release();
		}
	}

	@Test
	void effectiveWriteRejectsADisplayBeyondTheConfiguredCapAndAnIllegalPair() {
		InventoryS2CPacket packet = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1L, 1L, new MarkerId(1L),
			0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0,
			List.of(new InventoryS2CPacket.Entry("k", "minecraft:stone", "Stone", "d".repeat(4097), 1L, 1L, false, null)));

		FriendlyByteBuf buf = buffer();
		try {
			assertThrows(IllegalArgumentException.class,
				() -> packet.write(buf, InventoryLimits.DISPLAY_BASE_BYTES, InventoryLimits.FRAME_BASE_BYTES));
			assertThrows(IllegalArgumentException.class,
				() -> packet.write(buf, 9216, InventoryLimits.FRAME_BASE_BYTES));
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}

		FriendlyByteBuf legacyBuf = buffer();
		try {
			packet.write(legacyBuf);
			assertTrue(legacyBuf.readableBytes() > 0, "the legacy absolute-safe write admits the larger display");
		} finally {
			legacyBuf.release();
		}
	}

	@Test
	void entireFrameBudgetIncludesHeaderAndDecodeBound() {
		int frameBytes = InventoryLimits.FRAME_BASE_BYTES;
		List<InventoryS2CPacket.Entry> nearBound = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			nearBound.add(new InventoryS2CPacket.Entry("k" + i, "minecraft:stone", "l".repeat(1024),
				"d".repeat(3585), 1L, 1L, false, null));
		}
		InventoryS2CPacket accepted = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1L, 1L,
			new MarkerId(1L), 0, 0, 0, 0, 1, true, InventoryS2CPacket.Status.READY, 0, nearBound);
		FriendlyByteBuf buf = buffer();
		try {
			accepted.write(buf, InventoryLimits.DISPLAY_BASE_BYTES, frameBytes);
			assertTrue(buf.readableBytes() <= frameBytes);
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
			assertThrows(IllegalArgumentException.class,
				() -> rejected.write(over, InventoryLimits.DISPLAY_BASE_BYTES, frameBytes));
			assertEquals(0, over.readableBytes());
		} finally {
			over.release();
		}

		buf = streamFrame(0, 1, new byte[InventoryS2CPacket.MAX_FRAME_BYTES]);
		try {
			assertTrue(InventoryS2CPacket.readSafe(buf).isCorrupt(), "the absolute decode guard precedes allocation");
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

	@Test void exactMeasurementMatchesIndependentVanillaUtfCodecForEveryKindAndMaximalMultibyteEntries() {
		var max = new InventoryS2CPacket.Entry("é".repeat(128), "中".repeat(85), "😀".repeat(256),
			"é中😀".repeat(14563) + "éé", Long.MAX_VALUE, Long.MAX_VALUE, true,
			InventoryS2CPacket.Status.COMPONENT_TOO_LONG, Long.MAX_VALUE, true, "é".repeat(128));
		assertEquals(InventoryS2CPacket.MAX_DISPLAY_BYTES - 1, io.netty.buffer.ByteBufUtil.utf8Bytes(max.displayJson()));
		var entries = List.of(max, entry("plain", "minecraft:stone", 0, 0),
			new InventoryS2CPacket.Entry("surrogates", "minecraft:stone", "\ud800\ud800\udc00", "\ud800é\udc00", 1, 1, false, null));
		var packets = List.of(
			InventoryS2CPacket.offer(1, new InventoryS2CPacket.Offer(72000, 72000, Integer.MAX_VALUE, 0)),
			InventoryS2CPacket.policy(1, 100, 1, java.util.Set.of("location", "entity_block", "dropped_item", "block", "entity")),
			InventoryS2CPacket.selected(1, 2, 3, new MarkerId(4), null),
			InventoryS2CPacket.selected(1, 2, 3, null, nx.pingwheel.common.marker.MarkerRejectReason.TARGET_GONE),
			InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 1, 2, null, 3, 4, 5, 127, 128, true, InventoryS2CPacket.Status.READY, 6, entries),
			InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, 1, 2, new MarkerId(4), 3, 4, 5, 128, 129, true, InventoryS2CPacket.Status.UNCERTAIN, 6, entries),
			InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1, 2, new MarkerId(4), 3, 4, 5, 0, 1, false, InventoryS2CPacket.Status.UPDATING, 6, List.of()),
			InventoryS2CPacket.status(1, 2, new MarkerId(4), 3, 4, 5, InventoryS2CPacket.Status.INVALID, 6),
			InventoryS2CPacket.heartbeat(1, 2, new MarkerId(4), 3, 4, 5, 6));
		for (var packet : packets) {
			var vanilla = buffer(); var actual = buffer();
			try {
				writeVanillaFrame(vanilla, packet);
				assertEquals(vanilla.readableBytes(), packet.encodedSize(InventoryS2CPacket.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES), packet.kind().name());
				packet.write(actual);
				assertEquals(vanilla, actual, "the allocation-bounded writer preserves the actual wire bytes");
			} finally { vanilla.release(); actual.release(); }
		}
	}

	@Test void measurementAllocatesNoDisplaySizedBuffersAndWorkspaceScalesWithActualPacket() {
		var small = InventoryS2CPacket.offer(1, new InventoryS2CPacket.Offer(5, 3, 5, 0));
		var large = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1, 0, new MarkerId(1), 1, 1, 1, 0, 1,
			true, InventoryS2CPacket.Status.READY, 0, List.of(new InventoryS2CPacket.Entry("k", "minecraft:stone", "Stone",
			"中".repeat(43690) + "é", 1, 1, false, null)));
		assertTrue(small.encodingWorkspace(4096, 32768) < 2048, "tiny controls never reserve an absolute frame mirror");
		assertTrue(large.encodingWorkspace(InventoryS2CPacket.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES)
			> 2L * large.encodedSize(InventoryS2CPacket.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES));
		var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		assertTrue(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
		int checksum = 0;
		for (int i = 0; i < 200; i++) checksum += large.encodedSize(InventoryS2CPacket.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES);
		long thread = Thread.currentThread().threadId(), before = bean.getThreadAllocatedBytes(thread);
		for (int i = 0; i < 64; i++) checksum += large.encodedSize(InventoryS2CPacket.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES);
		long allocated = bean.getThreadAllocatedBytes(thread) - before;
		assertTrue(checksum > 0); assertEquals(0, allocated, "warmed exact measurement allocates no objects or byte buffers");
	}

	@Test void failedWritePreservesExistingDestinationBytesAndIndices() {
		var packet = InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1, 0, new MarkerId(1), 1, 1, 1, 0, 1,
			true, InventoryS2CPacket.Status.READY, 0, List.of(new InventoryS2CPacket.Entry("k", "minecraft:stone", "Stone",
			"d".repeat(4097), 1, 1, false, null)));
		var destination = buffer();
		try {
			destination.writeInt(0x12345678); destination.readerIndex(1);
			assertThrows(IllegalArgumentException.class, () -> packet.write(destination, 4096, 32768));
			assertEquals(1, destination.readerIndex()); assertEquals(4, destination.writerIndex());
			assertEquals(0x12345678, destination.getInt(0));
		} finally { destination.release(); }
	}

	@Test
	void sparseDisplaySavesActualWireBytesAgainstTheFullEffectivePatchBaseline() {
		int displayBytes = InventoryLimits.DISPLAY_BASE_BYTES;
		int frameBytes = InventoryLimits.FRAME_BASE_BYTES;

		assertSparseDisplaySavesWire("stone", new ItemStack(Items.STONE), displayBytes, frameBytes);
		assertSparseDisplaySavesWire("pickaxe", new ItemStack(Items.IRON_PICKAXE), displayBytes, frameBytes);
		ItemStack damagedPickaxe = new ItemStack(Items.IRON_PICKAXE);
		damagedPickaxe.set(DataComponents.DAMAGE, 5);
		assertSparseDisplaySavesWire("damaged pickaxe", damagedPickaxe, displayBytes, frameBytes);
	}

	private static void assertSparseDisplaySavesWire(String fixture, ItemStack stack, int displayBytes, int frameBytes) {
		String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		DataComponentPatch fullPatch = fullEffectivePatch(stack);
		assertNativeFullItemOracle(fixture, stack, fullPatch);

		InventoryDetachedItem sparse = freezeStack(stack, displayBytes);
		InventoryDetachedItem legacy = freezePatch(stack.getItem(), fullPatch, displayBytes);
		assertEquals(itemId, sparse.display().itemId(), fixture + ": the sparse payload keeps the registry item id");
		assertEquals(itemId, legacy.display().itemId(), fixture + ": the legacy payload keeps the registry item id");
		assertFalse(sparse.display().aggregateRequired(), fixture + ": the fixture has exact identity");
		assertFalse(legacy.display().aggregateRequired(), fixture + ": the baseline keeps exact identity");
		assertNotNull(sparse.display().displayJson(), fixture + ": sparse display is available");
		assertNotNull(legacy.display().displayJson(), fixture + ": legacy display is available");
		assertEquals(sparse.display().label(), legacy.display().label(), fixture + ": the label is unchanged");

		InventoryItemCodec.Catalog catalog = new InventoryItemCodec.Catalog(registries, displayBytes);
		var sparseKey = catalog.intern(sparse).key();
		var legacyKey = catalog.intern(legacy).key();
		assertFalse(sparseKey.componentsKey().isEmpty(), fixture + ": fixture identity is exact");
		assertNotEquals(sparseKey, legacyKey, fixture + ": a synthetic full-effective patch is a different raw patch identity");

		InventoryS2CPacket.Entry sparseEntry = wireEntry(sparseKey.componentsKey(), sparse.display(),
			sparse.display().displayJson());
		// Compare transport projections for the same authoritative selection;
		// the full-effective payload is only a synthetic display-size baseline.
		InventoryS2CPacket.Entry legacyEntry = wireEntry(sparseKey.componentsKey(), legacy.display(),
			legacy.display().displayJson());
		assertSameWireIdentity(sparseEntry, legacyEntry);

		WireResult sparseWire = writeAndRead(sparseEntry, displayBytes, frameBytes);
		WireResult legacyWire = writeAndRead(legacyEntry, displayBytes, frameBytes);
		assertSameWireIdentity(sparseWire.packet().entries().getFirst(), legacyWire.packet().entries().getFirst());
		assertEquals(sparseEntry, sparseWire.packet().entries().getFirst());
		assertEquals(legacyEntry, legacyWire.packet().entries().getFirst());
		assertTrue(sparseWire.bytes() < legacyWire.bytes(),
			() -> fixture + ": sparse display must use fewer actual wire bytes (sparse=" + sparseWire.bytes()
				+ ", legacy=" + legacyWire.bytes() + ")");
	}

	private static InventoryDetachedItem freezeStack(ItemStack stack, int displayBytes) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), registries, displayBytes,
			InventoryItemBudget.root(displayBytes));
	}

	private static InventoryDetachedItem freezePatch(Item item, DataComponentPatch patch, int displayBytes) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Patch(item, patch), registries, displayBytes,
			InventoryItemBudget.root(displayBytes));
	}

	private static DataComponentPatch fullEffectivePatch(ItemStack stack) {
		DataComponentPatch.Builder patch = DataComponentPatch.builder();
		for (TypedDataComponent<?> component : stack.getComponents()) {
			if (!component.type().isTransient()) addComponent(patch, component);
		}
		return patch.build();
	}

	private static <T> void addComponent(DataComponentPatch.Builder patch, TypedDataComponent<T> component) {
		patch.set(component.type(), component.value());
	}

	private static void assertNativeFullItemOracle(String fixture, ItemStack original, DataComponentPatch fullPatch) {
		ItemStack full = new ItemStack(original.getItemHolder(), 1, fullPatch);
		assertEquals(original.getComponents(), full.getComponents(),
			fixture + ": the explicit full patch is the effective component map");
		var context = registries.createSerializationContext(JsonOps.INSTANCE);
		JsonElement json = ItemStack.SINGLE_ITEM_CODEC.encodeStart(context, full).result().orElseThrow();
		ItemStack decoded = ItemStack.SINGLE_ITEM_CODEC.parse(context, json).result().orElseThrow();
		assertEquals(1, decoded.getCount(), fixture + ": the native full-map payload is count one");
		assertEquals(original.getItem(), decoded.getItem(), fixture + ": the native full-map payload keeps its item id");
		assertEquals(original.getComponents(), decoded.getComponents(),
			fixture + ": the native full-map JSON round-trips every effective component");
	}

	private static InventoryS2CPacket.Entry wireEntry(String key, InventoryItemCodec.Display display, String displayJson) {
		return new InventoryS2CPacket.Entry(key, display.itemId(), display.label(), displayJson,
			BIG, BIG + 1, false, InventoryS2CPacket.Status.READY, BIG + 2, false, null);
	}

	private static void assertSameWireIdentity(InventoryS2CPacket.Entry expected, InventoryS2CPacket.Entry actual) {
		assertEquals(expected.key(), actual.key());
		assertEquals(expected.itemId(), actual.itemId());
		assertEquals(expected.label(), actual.label());
		assertEquals(expected.count(), actual.count());
		assertEquals(expected.itemRevision(), actual.itemRevision());
		assertEquals(expected.fallback(), actual.fallback());
		assertEquals(expected.quality(), actual.quality());
		assertEquals(expected.groupRevision(), actual.groupRevision());
		assertEquals(expected.replaceGroup(), actual.replaceGroup());
		assertEquals(expected.itemPingType(), actual.itemPingType());
	}

	private static WireResult writeAndRead(InventoryS2CPacket.Entry entry, int displayBytes, int frameBytes) {
		InventoryS2CPacket packet = InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, BIG, BIG + 3,
			new MarkerId(BIG + 4), BIG + 5, BIG + 6, BIG + 7, 0, 1, true,
			InventoryS2CPacket.Status.READY, BIG + 8, List.of(entry));
		FriendlyByteBuf buf = buffer();
		try {
			int encodedSize = packet.encodedSize(displayBytes, frameBytes);
			packet.write(buf, displayBytes, frameBytes);
			assertEquals(encodedSize, buf.writerIndex(), "the production writer emits exactly its measured frame size");
			int bytes = buf.writerIndex();
			InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt());
			assertEquals(0, buf.readableBytes(), "the reader consumes the complete frame");
			assertEquals(packet, decoded);
			assertEquals(List.of(entry), decoded.entries());
			return new WireResult(bytes, decoded);
		} finally {
			buf.release();
		}
	}

	private record WireResult(int bytes, InventoryS2CPacket packet) {}

	/**
	 * Real registry-backed single-item JSON sized exactly in UTF-8 bytes. The
	 * persistent custom data carries multibyte text, and the item codec itself
	 * produced the string; callers re-parse it against the fixture contract.
	 *
	 * <p>This is a display/wire fixture only. Its canonical persistent bytes
	 * exceed the item codec's internal 64 KiB identity guard, so it makes no
	 * claim about an admissible server capture identity or the retained
	 * catalogue bound; those remain owned by the inventory item codec.
	 */
	private static DisplayFixture maximumDisplayFixture(Item item, int targetBytes) {
		ItemStack probe = new ItemStack(item);
		CompoundTag payload = new CompoundTag();
		payload.putInt("marker", 42);
		payload.putString("data", "");
		probe.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));
		int missing = targetBytes - utf8(encodeSingleItem(probe));
		assertTrue(missing > 0, "the target size must exceed the bare item JSON");
		String data = "a".repeat(missing % 9) + "é中😀".repeat(missing / 9);
		payload.putString("data", data);
		probe.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));
		String json = encodeSingleItem(probe);
		assertEquals(targetBytes, utf8(json), "the fixture is sized exactly in UTF-8 bytes");
		return new DisplayFixture(json, data);
	}

	private record DisplayFixture(String json, String data) {}

	private static String encodeSingleItem(ItemStack stack) {
		return ItemStack.SINGLE_ITEM_CODEC
			.encodeStart(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE), stack)
			.result().map(JsonElement::toString).orElseThrow();
	}

	private static ItemStack parseSingleItem(String json) {
		return ItemStack.SINGLE_ITEM_CODEC
			.parse(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json))
			.result().orElseThrow();
	}

	private static int utf8(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	/** Independent reference uses the vanilla UTF writer and a separate framed payload. */
	private static void writeVanillaFrame(FriendlyByteBuf buf, InventoryS2CPacket packet) {
		MarkerPacketCodec.writeEnum(buf, packet.kind()); buf.writeVarInt(packet.protocol());
		buf.writeLong(packet.epoch()); buf.writeLong(packet.requestId()); buf.writeLong(packet.presentationEpoch()); buf.writeLong(packet.view());
		switch (packet.kind()) {
			case OFFER -> {
				var offer = packet.offer(); buf.writeVarInt(offer.previewPeriodTicks()); buf.writeVarInt(offer.trackingPeriodTicks());
				buf.writeVarInt(offer.resyncMinPeriods()); buf.writeVarInt(offer.heartbeatPeriods());
				buf.writeVarInt(offer.effectiveDisplayBytes()); buf.writeVarInt(offer.effectiveFrameBytes());
			}
			case POLICY -> {
				buf.writeVarInt(packet.allowedTypes().size());
				for (String type : packet.allowedTypes().stream().sorted().toList()) buf.writeUtf(type, 256);
			}
			case SELECTED, REJECT -> {
				buf.writeLong(packet.commitId()); MarkerPacketCodec.writeOptionalMarkerId(buf, Optional.ofNullable(packet.markerId()));
				if (packet.kind() == InventoryS2CPacket.Kind.REJECT) MarkerPacketCodec.writeEnum(buf, packet.rejection());
			}
			case STATUS, HEARTBEAT, PREVIEW, SNAPSHOT, STREAM -> {
				MarkerPacketCodec.writeOptionalMarkerId(buf, Optional.ofNullable(packet.markerId()));
				buf.writeLong(packet.baselineId()); buf.writeLong(packet.statusRevision()); buf.writeLong(packet.watermark());
				boolean data = packet.kind() == InventoryS2CPacket.Kind.PREVIEW || packet.kind() == InventoryS2CPacket.Kind.SNAPSHOT || packet.kind() == InventoryS2CPacket.Kind.STREAM;
				if (data) { buf.writeVarInt(packet.partIndex()); buf.writeVarInt(packet.partCount()); buf.writeBoolean(packet.completeScan()); }
				if (packet.kind() != InventoryS2CPacket.Kind.HEARTBEAT) MarkerPacketCodec.writeEnum(buf, packet.status());
				buf.writeLong(packet.checksum());
				if (data) {
					var payload = buffer();
					try {
						payload.writeVarInt(packet.entries().size());
						for (var entry : packet.entries()) {
							payload.writeUtf(entry.key(), 256); payload.writeUtf(entry.itemId(), 256); payload.writeUtf(entry.label(), 1024);
							payload.writeBoolean(entry.displayJson() != null); if (entry.displayJson() != null) payload.writeUtf(entry.displayJson(), InventoryS2CPacket.MAX_DISPLAY_BYTES);
							payload.writeLong(entry.count()); payload.writeLong(entry.itemRevision()); payload.writeBoolean(entry.fallback());
							payload.writeBoolean(entry.quality() != null); if (entry.quality() != null) MarkerPacketCodec.writeEnum(payload, entry.quality());
							payload.writeLong(entry.groupRevision()); payload.writeBoolean(entry.replaceGroup()); payload.writeBoolean(entry.itemPingType() != null);
							if (entry.itemPingType() != null) payload.writeUtf(entry.itemPingType(), 256);
						}
						buf.writeVarInt(payload.readableBytes()); buf.writeBytes(payload);
					} finally { payload.release(); }
				}
			}
		}
	}
}
