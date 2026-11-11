package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.presentation.inventory.InventoryChecksums;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Current unreleased v2 grammar: there is no earlier-body fallback. */
@NotNullByDefault
class InventoryFailurePacketsTest {
	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	private static InventoryS2CPacket roundTrip(InventoryS2CPacket packet) {
		FriendlyByteBuf frame = buffer();
		try {
			int size = packet.encodedSize(InventoryS2CPacket.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES);
			assertEquals(1024L + 5L * size,
				packet.encodingWorkspace(InventoryS2CPacket.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES));
			packet.write(frame);
			assertEquals(size, frame.readableBytes());
			InventoryS2CPacket decoded = InventoryS2CPacket.readSafe(frame);
			assertFalse(decoded.isCorrupt());
			assertEquals(packet, decoded);
			assertFalse(frame.isReadable());
			return decoded;
		} finally {
			frame.release();
		}
	}

	private static FriendlyByteBuf header(String kind, long request, boolean lane) {
		FriendlyByteBuf frame = buffer();
		frame.writeUtf(kind);
		frame.writeVarInt(2);
		frame.writeLong(7);
		frame.writeLong(request);
		frame.writeLong(100);
		frame.writeLong(3);
		if (lane) frame.writeByte(1);
		return frame;
	}

	private static FriendlyByteBuf state(long request, long control, long progress, String phase, String condition,
		int presence, int reason) {
		FriendlyByteBuf frame = header("PREVIEW_STATE", request, true);
		frame.writeLong(control);
		frame.writeLong(progress);
		frame.writeUtf(phase);
		frame.writeUtf(condition);
		frame.writeByte(presence);
		if (presence == 1) frame.writeVarInt(reason);
		return frame;
	}

	private static void assertRejected(FriendlyByteBuf frame) {
		try {
			assertTrue(InventoryS2CPacket.readSafe(frame).isCorrupt());
			assertFalse(frame.isReadable(), "safe rejection drains the entire frame");
		} finally {
			frame.release();
		}
	}

	@Test
	void openRejectedHasNoLaneOrGrantAndRoundTripsEveryStableReason() {
		for (InventoryFailureReason reason : InventoryFailureReason.values()) {
			InventoryS2CPacket packet = roundTrip(InventoryS2CPacket.openRejected(7, 100, 3, 5, reason));
			assertEquals(InventoryResourceKind.ITEM, packet.resourceKind());
			assertEquals(0, packet.laneBits());
			assertFalse(packet.completeScan());
			assertNull(packet.markerId());
			assertNull(packet.status());
			assertTrue(packet.entries().isEmpty());
			FriendlyByteBuf frame = buffer();
			try {
				packet.write(frame);
				assertEquals("OPEN_REJECTED", frame.readUtf());
				assertEquals(2, frame.readVarInt());
				assertEquals(7, frame.readLong());
				assertEquals(5, frame.readLong());
				assertEquals(100, frame.readLong());
				assertEquals(3, frame.readLong());
				assertEquals(reason.wireId(), frame.readVarInt());
				assertFalse(frame.isReadable(), "only the required reason follows the common header");
			} finally {
				frame.release();
			}
		}
		assertThrows(NullPointerException.class, () -> InventoryS2CPacket.openRejected(7, 100, 3, 5, null));
		assertThrows(IllegalArgumentException.class, () -> InventoryS2CPacket.openRejected(7, 100, 3, 5,
			InventoryFailureReason.REQUEST_CAPACITY).withResourceKind(InventoryResourceKind.FLUID));
	}

	@Test
	void previewStatePreservesIndependentControlAndProgressRevisionsWithoutData() {
		for (InventoryResourceKind lane : InventoryResourceKind.values()) {
			for (InventoryS2CPacket.PreviewPhase phase : InventoryS2CPacket.PreviewPhase.values()) {
				for (InventoryS2CPacket.PreviewCondition condition : InventoryS2CPacket.PreviewCondition.values()) {
					@Nullable InventoryFailureReason reason = condition == InventoryS2CPacket.PreviewCondition.PROGRESS
						? null : InventoryFailureReason.MEMORY_LIMIT;
					InventoryS2CPacket packet = roundTrip(InventoryS2CPacket.previewState(lane, 7, 100, 3, 5,
						Long.MAX_VALUE, 0, phase, condition, reason));
					assertEquals(Long.MAX_VALUE, packet.controlRevision());
					assertEquals(0, packet.progressRevision());
					assertEquals(0, packet.laneBits());
					assertFalse(packet.completeScan());
					assertTrue(packet.entries().isEmpty());
					assertEquals(packet, packet.stamp(101, 4).stamp(100, 3));
				}
			}
		}
		roundTrip(InventoryS2CPacket.previewState(InventoryResourceKind.ITEM, 7, 100, 3, 5, 1, 2,
			InventoryS2CPacket.PreviewPhase.DELIVERY, InventoryS2CPacket.PreviewCondition.PROGRESS, InventoryFailureReason.WIRE_LIMIT));
	}

	@Test
	void newControlsStrictlyRejectInvalidReasonsScopesAndMissingFields() {
		for (int id : new int[] {0, Integer.MAX_VALUE, -1}) {
			FriendlyByteBuf rejected = header("OPEN_REJECTED", 5, false);
			rejected.writeVarInt(id);
			assertRejected(rejected);
			assertRejected(state(5, 1, 0, "ADMISSION", "DEFERRED", 1, id));
		}
		for (String condition : List.of("DEFERRED", "UNSUPPORTED", "FAILED")) {
			assertRejected(state(5, 1, 0, "SCAN", condition, 0, 0));
		}
		assertRejected(state(0, 1, 0, "SCAN", "PROGRESS", 0, 0));
		assertRejected(state(5, 0, 0, "SCAN", "PROGRESS", 0, 0));
		assertRejected(state(5, 1, -1, "SCAN", "PROGRESS", 0, 0));
		assertRejected(state(5, 1, 0, "UNKNOWN", "PROGRESS", 0, 0));
		assertRejected(state(5, 1, 0, "SCAN", "COMPLETE", 0, 0));
		assertRejected(state(5, 1, 0, "SCAN", "PROGRESS", 2, 0));
		FriendlyByteBuf wrongLane = state(5, 1, 0, "SCAN", "PROGRESS", 0, 0);
		wrongLane.setByte(1 + "PREVIEW_STATE".length() + 1 + 4 * Long.BYTES, 2);
		assertRejected(wrongLane);
		FriendlyByteBuf noReason = header("OPEN_REJECTED", 5, false);
		assertRejected(noReason);
		FriendlyByteBuf noncanonicalReason = header("OPEN_REJECTED", 5, false);
		noncanonicalReason.writeByte(0x92); noncanonicalReason.writeByte(0);
		assertRejected(noncanonicalReason);
		FriendlyByteBuf zeroRequest = header("OPEN_REJECTED", 0, false);
		zeroRequest.writeVarInt(18);
		assertRejected(zeroRequest);
		for (InventoryS2CPacket packet : List.of(
			InventoryS2CPacket.openRejected(7, 100, 3, 5, InventoryFailureReason.REQUEST_CAPACITY),
			InventoryS2CPacket.previewState(InventoryResourceKind.ITEM, 7, 100, 3, 5, 1, 0,
				InventoryS2CPacket.PreviewPhase.ADMISSION, InventoryS2CPacket.PreviewCondition.PROGRESS, null))) {
			FriendlyByteBuf trailing = buffer();
			packet.write(trailing);
			trailing.writeByte(0);
			assertRejected(trailing);
			FriendlyByteBuf truncated = buffer();
			packet.write(truncated);
			truncated.writerIndex(truncated.writerIndex() - 1);
			assertRejected(truncated);
			InventoryS2CPacket wrongScope = new InventoryS2CPacket(packet.kind(), packet.protocol(), packet.epoch(),
				packet.requestId(), new MarkerId(8), 0, 0, 0, 0, 1, false, null, 0, List.of(), null,
				packet.presentationEpoch(), packet.view(), Set.of(), 0, null, packet.resourceKind(), 0,
				packet.failureReason(), packet.controlRevision(), packet.progressRevision(), packet.phase(), packet.condition());
			assertTrue(wrongScope.isCorrupt());
			assertThrows(IllegalArgumentException.class, () -> wrongScope.encodedSize(4096, 32768));
		}
	}

	private static FriendlyByteBuf dataPrefix(int failurePresence, int failureId) {
		FriendlyByteBuf frame = header("PREVIEW", 5, true);
		frame.writeBoolean(false);
		frame.writeLong(1); frame.writeLong(2); frame.writeLong(3);
		frame.writeVarInt(0); frame.writeVarInt(1); frame.writeBoolean(false);
		frame.writeUtf("INCOMPLETE");
		frame.writeByte(failurePresence);
		if (failurePresence == 1) frame.writeVarInt(failureId);
		frame.writeLong(99);
		return frame;
	}

	@Test
	void dataAndEntryReasonIdsAndPresenceFlagsAreStrictAndOldBodiesAreNotCompleted() {
		for (int presence : new int[] {1, 2}) {
			FriendlyByteBuf unknown = dataPrefix(presence, Integer.MAX_VALUE);
			unknown.writeVarInt(1); unknown.writeVarInt(0);
			assertRejected(unknown);
		}
		for (int qualityPresence : new int[] {1, 2}) {
			FriendlyByteBuf payload = buffer();
			FriendlyByteBuf frame = dataPrefix(0, 0);
			try {
				payload.writeVarInt(1);
				payload.writeUtf("key"); payload.writeUtf("minecraft:stone"); payload.writeUtf("Stone");
				payload.writeBoolean(false); payload.writeLong(1); payload.writeLong(1); payload.writeBoolean(false);
				payload.writeBoolean(true); payload.writeUtf("COMPONENT_TOO_LONG");
				payload.writeLong(0); payload.writeBoolean(false); payload.writeBoolean(false);
				payload.writeByte(qualityPresence);
				if (qualityPresence == 1) payload.writeVarInt(Integer.MAX_VALUE);
				frame.writeVarInt(payload.readableBytes()); frame.writeBytes(payload);
			} finally {
				payload.release();
			}
			assertRejected(frame);
		}
		FriendlyByteBuf oldReject = header("REJECT", 5, true);
		oldReject.writeLong(8); oldReject.writeBoolean(false); oldReject.writeUtf("INVALID_REQUEST");
		assertRejected(oldReject);
		FriendlyByteBuf unknownReject = header("REJECT", 5, true);
		unknownReject.writeLong(8); unknownReject.writeBoolean(false); unknownReject.writeUtf("INVALID_REQUEST");
		unknownReject.writeBoolean(true); unknownReject.writeVarInt(Integer.MAX_VALUE);
		assertRejected(unknownReject);
		FriendlyByteBuf oldStatus = header("STATUS", 5, true);
		oldStatus.writeBoolean(true); oldStatus.writeLong(8);
		oldStatus.writeLong(1); oldStatus.writeLong(2); oldStatus.writeLong(3);
		oldStatus.writeUtf("INVALID"); oldStatus.writeLong(99);
		assertRejected(oldStatus);
		FriendlyByteBuf oldEntryPayload = buffer();
		FriendlyByteBuf oldEntryFrame = dataPrefix(0, 0);
		try {
			oldEntryPayload.writeVarInt(1);
			oldEntryPayload.writeUtf("key"); oldEntryPayload.writeUtf("minecraft:stone"); oldEntryPayload.writeUtf("Stone");
			oldEntryPayload.writeBoolean(false); oldEntryPayload.writeLong(1); oldEntryPayload.writeLong(1);
			oldEntryPayload.writeBoolean(false); oldEntryPayload.writeBoolean(false); oldEntryPayload.writeLong(0);
			oldEntryPayload.writeBoolean(false); oldEntryPayload.writeBoolean(false);
			oldEntryFrame.writeVarInt(oldEntryPayload.readableBytes()); oldEntryFrame.writeBytes(oldEntryPayload);
		} finally {
			oldEntryPayload.release();
		}
		assertRejected(oldEntryFrame);
		FriendlyByteBuf trailingEntries = dataPrefix(0, 0);
		trailingEntries.writeVarInt(2); trailingEntries.writeVarInt(0); trailingEntries.writeByte(0);
		assertRejected(trailingEntries);
	}

	@Test
	void selectionDataStatusAndEntryQualityCarryReasonsWithoutChangingTheChecksumDomain() {
		InventoryS2CPacket rejected = roundTrip(InventoryS2CPacket.selected(InventoryResourceKind.FLUID, 7, 5, 8, null,
			MarkerRejectReason.INVALID_PING_TYPE, InventoryFailureReason.INVALID_PING_TYPE));
		assertEquals(MarkerRejectReason.INVALID_PING_TYPE, rejected.rejection());
		assertEquals(InventoryFailureReason.INVALID_PING_TYPE, rejected.failureReason());
		assertNull(roundTrip(InventoryS2CPacket.selected(7, 5, 8, null, MarkerRejectReason.INVALID_REQUEST)).failureReason());
		InventoryS2CPacket.Entry entry = new InventoryS2CPacket.Entry("key", "minecraft:stone", "Stone", null,
			Long.MAX_VALUE, 9, false, InventoryS2CPacket.Status.COMPONENT_TOO_LONG, 0, false, null,
			InventoryFailureReason.DISPLAY_LIMIT);
		InventoryS2CPacket.Entry plain = new InventoryS2CPacket.Entry("key", "minecraft:stone", "Stone", null,
			Long.MAX_VALUE, 9, false, InventoryS2CPacket.Status.COMPONENT_TOO_LONG);
		assertEquals(InventoryChecksums.checksumEntries(InventoryResourceKind.ITEM, List.of(plain)),
			InventoryChecksums.checksumEntries(InventoryResourceKind.ITEM, List.of(entry)));
		for (InventoryS2CPacket.Kind kind : List.of(InventoryS2CPacket.Kind.PREVIEW, InventoryS2CPacket.Kind.SNAPSHOT,
			InventoryS2CPacket.Kind.STREAM)) {
			InventoryS2CPacket data = roundTrip(InventoryS2CPacket.data(InventoryResourceKind.ITEM, kind, 7, 5,
				kind == InventoryS2CPacket.Kind.PREVIEW ? null : new MarkerId(8), 1, 2, 3, 0, 1, false,
				InventoryS2CPacket.Status.INCOMPLETE, 99, List.of(entry), InventoryFailureReason.VARIANT_LIMIT));
			assertEquals(InventoryFailureReason.VARIANT_LIMIT, data.failureReason());
			assertEquals(InventoryFailureReason.DISPLAY_LIMIT, data.entries().getFirst().qualityReason());
			assertEquals(99, data.checksum());
		}
		roundTrip(InventoryS2CPacket.status(InventoryResourceKind.FLUID, 7, 5, new MarkerId(8), 1, 2, 3,
			InventoryS2CPacket.Status.INVALID, 99, InventoryFailureReason.SOURCE_CHANGED));
		assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket.Entry("key", "minecraft:stone", "Stone",
			null, 1, 1, false, null, 0, false, null, InventoryFailureReason.DISPLAY_LIMIT));
		assertTrue(InventoryS2CPacket.heartbeat(7, 5, new MarkerId(8), 1, 2, 3, 4)
			.withFailureReason(InventoryFailureReason.SOURCE_CHANGED).isCorrupt());
	}

	@Test
	void frameAccepts128EntriesAndRejectsThe129thIndependentlyOfDeliveryParts() {
		List<InventoryS2CPacket.Entry> entries = new ArrayList<>();
		for (int index = 0; index < 128; index++) entries.add(new InventoryS2CPacket.Entry("k" + index,
			"minecraft:stone", "Stone", null, index, index, false, null));
		InventoryS2CPacket packet = InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, 5, null,
			1, 2, 3, 0, 1, true, InventoryS2CPacket.Status.READY, 99, entries);
		assertEquals(128, roundTrip(packet).entries().size());
		entries.add(new InventoryS2CPacket.Entry("k128", "minecraft:stone", "Stone", null, 128, 128, false, null));
		assertThrows(IllegalArgumentException.class, () -> InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW,
			7, 5, null, 1, 2, 3, 0, 1, true, InventoryS2CPacket.Status.READY, 99, entries));
		FriendlyByteBuf payload = buffer();
		FriendlyByteBuf frame = dataPrefix(0, 0);
		try {
			payload.writeVarInt(129);
			for (InventoryS2CPacket.Entry entry : entries) {
				payload.writeUtf(entry.key()); payload.writeUtf(entry.itemId()); payload.writeUtf(entry.label());
				payload.writeBoolean(false); payload.writeLong(entry.count()); payload.writeLong(entry.itemRevision());
				payload.writeBoolean(false); payload.writeBoolean(false); payload.writeLong(0);
				payload.writeBoolean(false); payload.writeBoolean(false); payload.writeBoolean(false);
			}
			frame.writeVarInt(payload.readableBytes()); frame.writeBytes(payload);
		} finally {
			payload.release();
		}
		assertRejected(frame);
	}

	@Test
	void deliveryAccepts1024PartsWithOneEntryPerFrameAndRejects1025Parts() {
		InventoryS2CPacket.Entry entry = new InventoryS2CPacket.Entry("key", "minecraft:stone", "Stone", null,
			1, 1, false, null);
		InventoryS2CPacket packet = InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, 5, null,
			1, 2, 3, 1023, 1024, true, InventoryS2CPacket.Status.READY, 99, List.of(entry));
		InventoryS2CPacket decoded = roundTrip(packet);
		assertEquals(1, decoded.entries().size());
		assertEquals(1023, decoded.partIndex());
		assertEquals(1024, decoded.partCount());
		InventoryS2CPacket tooManyParts = InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, 5, null,
			1, 2, 3, 0, 1025, false, InventoryS2CPacket.Status.UPDATING, 0, List.of());
		assertTrue(tooManyParts.isCorrupt());
		assertThrows(IllegalArgumentException.class, () -> tooManyParts.encodedSize(4096, 32768));
		FriendlyByteBuf tooManyRawParts = header("PREVIEW", 5, true);
		tooManyRawParts.writeBoolean(false);
		tooManyRawParts.writeLong(1); tooManyRawParts.writeLong(2); tooManyRawParts.writeLong(3);
		tooManyRawParts.writeVarInt(0); tooManyRawParts.writeVarInt(1025);
		assertRejected(tooManyRawParts);
		assertEquals(512 * 1024, InventoryS2CPacket.MAX_FRAME_BYTES);
		assertEquals(128 * 1024, InventoryS2CPacket.MAX_DISPLAY_BYTES);
		assertEquals(130 * 1024, InventoryS2CPacket.MAX_ENTRY_BYTES);
		assertEquals(4096, InventoryC2SPacket.MAX_FRAME_BYTES);
	}
}
