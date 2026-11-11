package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryWireLimitException.Bound;
import nx.pingwheel.common.presentation.PresentationDiagnosticCapture;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class InventoryWireLimitDiagnosticsTest {
	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	private static byte[] payload(Consumer<FriendlyByteBuf> writer) {
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

	private static FriendlyByteBuf streamFrame(int partIndex, int partCount, Consumer<FriendlyByteBuf> writer) {
		byte[] entries = payload(writer);
		FriendlyByteBuf frame = buffer();
		MarkerPacketCodec.writeEnum(frame, InventoryS2CPacket.Kind.STREAM);
		frame.writeVarInt(InventoryS2CPacket.VERSION);
		frame.writeLong(1L);
		frame.writeLong(1L);
		frame.writeLong(100L);
		frame.writeLong(1L);
		frame.writeByte(0); // ITEM lane
		frame.writeBoolean(true);
		frame.writeLong(1L);
		frame.writeLong(1L);
		frame.writeLong(0L);
		frame.writeLong(0L);
		frame.writeVarInt(partIndex);
		frame.writeVarInt(partCount);
		frame.writeBoolean(true);
		MarkerPacketCodec.writeEnum(frame, InventoryS2CPacket.Status.READY);
		frame.writeBoolean(false);
		frame.writeLong(0L);
		frame.writeVarInt(entries.length);
		frame.writeBytes(entries);
		return frame;
	}

	private static InventoryWireLimitException rejected(FriendlyByteBuf frame, Class<? extends IPacket> packetType) {
		try {
			return assertThrows(InventoryWireLimitException.class, () -> {
				if (packetType == InventoryC2SPacket.class) new InventoryC2SPacket(frame);
				else new InventoryS2CPacket(frame);
			});
		} finally {
			frame.release();
		}
	}

	private static void corruptC2SFrame() {
		FriendlyByteBuf frame = buffer();
		try {
			InventoryC2SPacket.hello().write(frame);
			frame.writeBytes("SECRET-PAYLOAD".getBytes(StandardCharsets.UTF_8));
			frame.writeZero(InventoryC2SPacket.MAX_FRAME_BYTES + 1 - frame.readableBytes());
			assertTrue(InventoryC2SPacket.readSafe(frame).isCorrupt());
			assertEquals(0, frame.readableBytes());
		} finally {
			frame.release();
		}
	}

	private static void corruptPartCount() {
		FriendlyByteBuf frame = streamFrame(0, InventoryS2CPacket.MAX_PARTS + 1, entries -> entries.writeVarInt(0));
		try {
			assertTrue(InventoryS2CPacket.readSafe(frame).isCorrupt());
		} finally {
			frame.release();
		}
	}

	private static void corruptEntryCount() {
		FriendlyByteBuf frame = streamFrame(0, 1, entries -> entries.writeVarInt(InventoryS2CPacket.MAX_ENTRIES + 1));
		try {
			assertTrue(InventoryS2CPacket.readSafe(frame).isCorrupt());
		} finally {
			frame.release();
		}
	}

	private static void corruptTruncatedKey() {
		FriendlyByteBuf frame = streamFrame(0, 1, entries -> {
			entries.writeVarInt(1);
			entries.writeVarInt(10);
			entries.writeByte(0);
		});
		try {
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new InventoryS2CPacket(frame));
			assertFalse(failure instanceof InventoryWireLimitException, "a truncated in-bound field is malformed, not a limit");
		} finally {
			frame.release();
		}
	}

	@Test
	void wholeFrameCapPlusOneIsTypedWithoutPayload() {
		FriendlyByteBuf request = buffer();
		InventoryC2SPacket.hello().write(request);
		request.writeZero(InventoryC2SPacket.MAX_FRAME_BYTES + 1 - request.readableBytes());
		InventoryWireLimitException requestFailure = rejected(request, InventoryC2SPacket.class);
		assertEquals(Bound.C2S_FRAME, requestFailure.bound());
		assertEquals(InventoryFailureReason.FRAME_LIMIT, requestFailure.reason());
		assertEquals(InventoryC2SPacket.MAX_FRAME_BYTES + 1, requestFailure.requested());
		assertEquals(InventoryC2SPacket.MAX_FRAME_BYTES, requestFailure.limit());

		FriendlyByteBuf response = buffer();
		InventoryS2CPacket.heartbeat(1L, 2L, new MarkerId(3L), 4L, 5L, 6L, 7L).write(response);
		response.writeZero(InventoryS2CPacket.MAX_FRAME_BYTES + 1 - response.readableBytes());
		InventoryWireLimitException responseFailure = rejected(response, InventoryS2CPacket.class);
		assertEquals(Bound.S2C_FRAME, responseFailure.bound());
		assertEquals(InventoryS2CPacket.MAX_FRAME_BYTES + 1, responseFailure.requested());
		assertEquals(InventoryS2CPacket.MAX_FRAME_BYTES, responseFailure.limit());
	}

	@Test
	void partAndEntryCountCapPlusOneAreTyped() {
		InventoryWireLimitException parts = rejected(
			streamFrame(0, InventoryS2CPacket.MAX_PARTS + 1, entries -> entries.writeVarInt(0)), InventoryS2CPacket.class);
		assertEquals(Bound.PART_COUNT, parts.bound());
		assertEquals(InventoryS2CPacket.MAX_PARTS + 1, parts.requested());
		assertEquals(InventoryS2CPacket.MAX_PARTS, parts.limit());

		InventoryWireLimitException entries = rejected(
			streamFrame(0, 1, body -> body.writeVarInt(InventoryS2CPacket.MAX_ENTRIES + 1)), InventoryS2CPacket.class);
		assertEquals(Bound.ENTRY_COUNT, entries.bound());
		assertEquals(InventoryS2CPacket.MAX_ENTRIES + 1, entries.requested());
		assertEquals(InventoryS2CPacket.MAX_ENTRIES, entries.limit());
	}

	@Test
	void entryUtfByteCapsAreTypedAndTruncationStaysMalformed() {
		InventoryWireLimitException key = rejected(streamFrame(0, 1, body -> {
			body.writeVarInt(1);
			body.writeVarInt(InventoryS2CPacket.MAX_KEY_BYTES + 1);
		}), InventoryS2CPacket.class);
		assertEquals(Bound.KEY_BYTES, key.bound());
		assertEquals(InventoryS2CPacket.MAX_KEY_BYTES + 1, key.requested());
		assertEquals(InventoryS2CPacket.MAX_KEY_BYTES, key.limit());

		InventoryWireLimitException id = rejected(streamFrame(0, 1, body -> {
			body.writeVarInt(1);
			body.writeUtf("key", InventoryS2CPacket.MAX_KEY_BYTES);
			body.writeVarInt(InventoryS2CPacket.MAX_ITEM_ID_BYTES + 1);
		}), InventoryS2CPacket.class);
		assertEquals(Bound.ID_BYTES, id.bound());
		assertEquals(InventoryS2CPacket.MAX_ITEM_ID_BYTES + 1, id.requested());
		assertEquals(InventoryS2CPacket.MAX_ITEM_ID_BYTES, id.limit());

		InventoryWireLimitException label = rejected(streamFrame(0, 1, body -> {
			body.writeVarInt(1);
			body.writeUtf("key", InventoryS2CPacket.MAX_KEY_BYTES);
			body.writeUtf("minecraft:stone", InventoryS2CPacket.MAX_ITEM_ID_BYTES);
			body.writeVarInt(InventoryS2CPacket.MAX_LABEL_BYTES + 1);
		}), InventoryS2CPacket.class);
		assertEquals(Bound.LABEL_BYTES, label.bound());
		assertEquals(InventoryS2CPacket.MAX_LABEL_BYTES + 1, label.requested());
		assertEquals(InventoryS2CPacket.MAX_LABEL_BYTES, label.limit());

		InventoryWireLimitException display = rejected(streamFrame(0, 1, body -> {
			body.writeVarInt(1);
			body.writeUtf("key", InventoryS2CPacket.MAX_KEY_BYTES);
			body.writeUtf("minecraft:stone", InventoryS2CPacket.MAX_ITEM_ID_BYTES);
			body.writeUtf("Stone", InventoryS2CPacket.MAX_LABEL_BYTES);
			body.writeBoolean(true);
			body.writeVarInt(InventoryS2CPacket.MAX_DISPLAY_BYTES + 1);
		}), InventoryS2CPacket.class);
		assertEquals(Bound.DISPLAY_BYTES, display.bound());
		assertEquals(InventoryS2CPacket.MAX_DISPLAY_BYTES + 1, display.requested());
		assertEquals(InventoryS2CPacket.MAX_DISPLAY_BYTES, display.limit());

		InventoryWireLimitException annotation = rejected(streamFrame(0, 1, body -> {
			body.writeVarInt(1);
			body.writeUtf("key", InventoryS2CPacket.MAX_KEY_BYTES);
			body.writeUtf("minecraft:stone", InventoryS2CPacket.MAX_ITEM_ID_BYTES);
			body.writeUtf("Stone", InventoryS2CPacket.MAX_LABEL_BYTES);
			body.writeBoolean(false);
			body.writeLong(0L);
			body.writeLong(0L);
			body.writeBoolean(false);
			body.writeBoolean(false);
			body.writeLong(0L);
			body.writeBoolean(false);
			body.writeBoolean(true);
			body.writeVarInt(257);
		}), InventoryS2CPacket.class);
		assertEquals(Bound.ANNOTATION_BYTES, annotation.bound());

		corruptTruncatedKey();
	}

	@Test
	void packetHandlerReportsEachDebugLimitOccurrencePerBoundAndDoesNotSuppressAnotherBound() {
		try (var capture = new PresentationDiagnosticCapture(Level.DEBUG)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			corruptC2SFrame();
			corruptC2SFrame();
			corruptPartCount();
			corruptEntryCount();

			List<String> messages = capture.messages();
			assertEquals(4, messages.size(), "two same-bound events and two different bounds are all admitted");
			assertEquals(2, messages.stream().filter(message -> message.contains("bound=c2s-frame")).count());
			assertEquals(1, messages.stream().filter(message -> message.contains("bound=part-count")).count());
			assertEquals(1, messages.stream().filter(message -> message.contains("bound=entry-count")).count());
			assertTrue(messages.stream().allMatch(message -> message.contains("site=packet-decode handling=wire-limit")));
			assertTrue(messages.stream().allMatch(message -> message.contains("bypass=debug-every-occurrence")));
			assertTrue(messages.stream().allMatch(message -> message.contains("requested=") && message.contains("limit=")));
			assertTrue(messages.stream().noneMatch(message -> message.contains("handling=corrupt-fallback")));
			assertTrue(messages.stream().noneMatch(message -> message.contains("SECRET-PAYLOAD")),
				"the limit summary never carries frame text");
		}
	}

	@Test
	void ordinaryMalformedFramesStillUseTheCorruptFallbackGate() {
		try (var capture = new PresentationDiagnosticCapture(Level.DEBUG)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			FriendlyByteBuf truncated = buffer();
			try {
				InventoryC2SPacket.hello().write(truncated);
				truncated.writerIndex(truncated.writerIndex() - 1);
				assertTrue(InventoryC2SPacket.readSafe(truncated).isCorrupt());
			} finally {
				truncated.release();
			}
			assertEquals(1, capture.messages().size());
			assertTrue(capture.messages().getFirst().contains("handling=corrupt-fallback"));
			assertFalse(capture.messages().getFirst().contains("handling=wire-limit"));
		}
	}

	@Test
	void normalProfileDoesNotAdmitWireLimitReports() {
		try (var capture = new PresentationDiagnosticCapture(Level.DEBUG)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.NORMAL);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
			corruptC2SFrame();
			corruptEntryCount();
			assertTrue(capture.messages().isEmpty(), "limit sites require the DEBUG profile");
		}
	}
}
