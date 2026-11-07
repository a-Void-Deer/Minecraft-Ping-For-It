package nx.pingwheel.common.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.presentation.MarkerContentGrant;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationGrant;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * D0019's grant frame is fail-closed at its own boundary: canonical IDs and
 * ordering, bounded counts, known field kinds and nested child references.
 * The 256 child ceiling asserted here is only the strict wire bound; it is not
 * a create-time union oracle and does not require combining an old advertised
 * deny set with the fresh server child set. Trailing bytes and a missing grant
 * field belong to the enclosing CREATED packet, which remains owned by the full
 * presentation wire test.
 */
@NotNullByDefault
class FrozenGrantMalformedFrameTest {
	private static final String ADAPTER = "test:adapter";
	private static final String FIELD = "test:field";

	@Test
	void canonicalGrantRoundTripsAndEveryTruncationFailsClosed() {
		var grant = new MarkerContentGrant(new PresentationGrant(Map.of(ADAPTER,
			new PresentationGrant.AdapterGrant(1, Map.of(FIELD, PresentationField.Kind.TEXT))), Set.of()), null);
		var valid = bytes(grant);
		assertEquals(grant, read(valid));

		for (int length = 0; length < valid.length; length++) {
			byte[] truncated = new byte[length];
			System.arraycopy(valid, 0, truncated, 0, length);
			assertThrows(RuntimeException.class, () -> read(truncated),
				"every truncated grant prefix must be rejected, length=" + length);
		}
	}

	@Test
	void duplicateOrNonCanonicalAdapterAndFieldOrderIsRejectedRatherThanNormalized() {
		var duplicate = buffer();
		try {
			duplicate.writeVarInt(2);
			writeAdapter(duplicate, ADAPTER, 1, 0);
			writeAdapter(duplicate, ADAPTER, 1, 0);
			duplicate.writeVarInt(0);
			duplicate.writeBoolean(false);
			assertThrows(RuntimeException.class, () -> read(duplicate));
		} finally {
			duplicate.release();
		}

		var reversedFields = buffer();
		try {
			reversedFields.writeVarInt(1);
			reversedFields.writeUtf(ADAPTER);
			reversedFields.writeVarInt(1);
			reversedFields.writeVarInt(2);
			reversedFields.writeUtf("test:z");
			reversedFields.writeVarInt(0);
			reversedFields.writeUtf("test:a");
			reversedFields.writeVarInt(0);
			reversedFields.writeVarInt(0);
			reversedFields.writeBoolean(false);
			assertThrows(RuntimeException.class, () -> read(reversedFields));
		} finally {
			reversedFields.release();
		}
	}

	@Test
	void unknownFieldKindAndRootChildReferenceAreRejected() {
		var unknownKind = buffer();
		try {
			unknownKind.writeVarInt(1);
			unknownKind.writeUtf(ADAPTER);
			unknownKind.writeVarInt(1);
			unknownKind.writeVarInt(1);
			unknownKind.writeUtf(FIELD);
			unknownKind.writeVarInt(99);
			unknownKind.writeVarInt(0);
			unknownKind.writeBoolean(false);
			assertThrows(RuntimeException.class, () -> read(unknownKind));
		} finally {
			unknownKind.release();
		}

		var rootChild = buffer();
		try {
			rootChild.writeVarInt(0);
			rootChild.writeVarInt(1);
			rootChild.writeUtf(ADAPTER);
			rootChild.writeUtf(FIELD);
			rootChild.writeVarInt(0);
			rootChild.writeBoolean(false);
			assertThrows(RuntimeException.class, () -> read(rootChild));
		} finally {
			rootChild.release();
		}
	}

	@Test
	void oversizedAdapterFieldAndChildCountsRejectBeforeReadingTheirBodies() {
		var adapters = buffer();
		try {
			adapters.writeVarInt(PresentationGrantCodec.MAX_ADAPTERS + 1);
			assertThrows(RuntimeException.class, () -> read(adapters));
		} finally {
			adapters.release();
		}

		var fields = buffer();
		try {
			fields.writeVarInt(1);
			fields.writeUtf(ADAPTER);
			fields.writeVarInt(1);
			fields.writeVarInt(PresentationGrantCodec.MAX_FIELDS_PER_ADAPTER + 1);
			assertThrows(RuntimeException.class, () -> read(fields));
		} finally {
			fields.release();
		}

		var children = buffer();
		try {
			children.writeVarInt(0);
			children.writeVarInt(PresentationGrantCodec.MAX_CHILD_REFS + 1);
			assertThrows(RuntimeException.class, () -> read(children));
		} finally {
			children.release();
		}
	}

	@Test
	void malformedUtfAndOversizedWriteAreRejectedBeforeCanonicalOutput() {
		var malformed = buffer();
		try {
			malformed.writeVarInt(1);
			malformed.writeVarInt(2);
			malformed.writeByte(0xC3);
			malformed.writeByte(0x28);
			assertThrows(RuntimeException.class, () -> read(malformed));
		} finally {
			malformed.release();
		}

		Map<String, PresentationGrant.AdapterGrant> adapters = new LinkedHashMap<>();
		for (int index = 0; index <= PresentationGrantCodec.MAX_ADAPTERS; index++)
			adapters.put("test:a" + index, new PresentationGrant.AdapterGrant(1, Map.of()));
		var oversized = new MarkerContentGrant(new PresentationGrant(adapters, Set.of()), null);
		var output = buffer();
		try {
			assertThrows(IllegalArgumentException.class, () -> PresentationGrantCodec.write(output, oversized));
			assertEquals(0, output.writerIndex(), "capacity must be rejected before canonical bytes are emitted");
		} finally {
			output.release();
		}
	}

	private static byte[] bytes(MarkerContentGrant grant) {
		var buffer = buffer();
		try {
			PresentationGrantCodec.write(buffer, grant);
			byte[] bytes = new byte[buffer.readableBytes()];
			buffer.readBytes(bytes);
			return bytes;
		} finally {
			buffer.release();
		}
	}

	private static MarkerContentGrant read(byte[] bytes) {
		var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
		try {
			return PresentationGrantCodec.read(buffer);
		} finally {
			buffer.release();
		}
	}

	private static MarkerContentGrant read(FriendlyByteBuf buffer) {
		return PresentationGrantCodec.read(buffer);
	}

	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	private static void writeAdapter(FriendlyByteBuf buffer, String adapter, int schema, int fields) {
		buffer.writeUtf(adapter);
		buffer.writeVarInt(schema);
		buffer.writeVarInt(fields);
	}

	@Test
	void validGrantLeavesNoUnreadGrantBytesForTheEnclosingPacket() {
		var grant = new MarkerContentGrant(new PresentationGrant(Map.of(ADAPTER,
			new PresentationGrant.AdapterGrant(1, Map.of(FIELD, PresentationField.Kind.TEXT))), Set.of()), null);
		var buffer = buffer();
		try {
			PresentationGrantCodec.write(buffer, grant);
			assertEquals(grant, PresentationGrantCodec.read(buffer));
			assertEquals(0, buffer.readableBytes(), "the grant frame must consume its complete body");
		} finally {
			buffer.release();
		}
	}
}
