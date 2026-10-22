package nx.pingwheel.common.presentation;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationCodecAnnotationTest {
	private static final String BASIC = PresentationBasic.ID;

	private static Map<String, PresentationValue> stateFields() {
		Map<String, PresentationValue> state = new LinkedHashMap<>();
		state.put("#counts", new PresentationValue.Text("3"));
		state.put("nested", new PresentationValue.RecordValue(Map.of("minecraft:cobblestone",
			new PresentationValue.Text("1"))));
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		fields.put(PresentationBasic.BLOCK_STATE, new PresentationValue.RecordValue(state));
		fields.put(PresentationBasic.NAME, new PresentationValue.Text("Chest"));
		return fields;
	}

	@Test
	void rootAndNestedAnnotationsRoundTripAndEncodeDeterministically() {
		var name = PresentationPropertyRef.root(BASIC, PresentationBasic.NAME);
		var counts = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("#counts"));
		var nested = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE,
			List.of("nested", "minecraft:cobblestone"));
		var annotations = Map.of(name, "attention", counts, "danger", nested, "request");
		var original = new PresentationSection(BASIC, 1, stateFields(), false, annotations);

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			PresentationCodec.write(buf, original);
			byte[] first = new byte[buf.readableBytes()];
			buf.getBytes(buf.readerIndex(), first);

			var decoded = PresentationCodec.read(buf, id -> true);
			assertEquals(original, decoded);
			assertEquals(annotations, decoded.annotations());
			assertEquals(0, buf.readableBytes());

			buf.clear();
			Map<String, PresentationValue> reversed = new LinkedHashMap<>();
			reversed.put(PresentationBasic.NAME, stateFields().get(PresentationBasic.NAME));
			reversed.put(PresentationBasic.BLOCK_STATE, stateFields().get(PresentationBasic.BLOCK_STATE));
			PresentationCodec.write(buf, new PresentationSection(BASIC, 1, reversed, false, annotations));
			byte[] second = new byte[buf.readableBytes()];
			buf.getBytes(buf.readerIndex(), second);

			assertArrayEquals(first, second);
		} finally {
			buf.release();
		}
	}

	@Test
	void absentAnnotationsStayNullableAndDoNotConsumePayload() {
		var plain = new PresentationSection(BASIC, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text("Chest")), false);

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			PresentationCodec.write(buf, plain);
			var decoded = PresentationCodec.read(buf, id -> true);
			assertEquals(plain, decoded);
			assertTrue(decoded.annotations().isEmpty());
		} finally {
			buf.release();
		}
	}

	@Test
	void deniedTopLevelFieldSkipsItsEntireFrameIncludingAnnotations() {
		var counts = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("#counts"));
		var annotated = new PresentationSection(BASIC, 1, stateFields(), false, Map.of(counts, "danger"));

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			PresentationCodec.write(buf, annotated);
			var withoutState = PresentationCodec.read(buf, id -> !id.equals(PresentationBasic.BLOCK_STATE));
			assertEquals(Map.of(PresentationBasic.NAME, new PresentationValue.Text("Chest")), withoutState.fields());
			assertTrue(withoutState.annotations().isEmpty());

			buf.clear();
			PresentationCodec.write(buf, annotated);
			var stateOnly = PresentationCodec.read(buf, id -> id.equals(PresentationBasic.BLOCK_STATE));
			assertEquals(Set.of(PresentationBasic.BLOCK_STATE), stateOnly.fields().keySet());
			assertEquals(Map.of(counts, "danger"), stateOnly.annotations());
		} finally {
			buf.release();
		}
	}

	@Test
	void sequenceEntriesCarryNoAddressableAnnotation() {
		var record = new PresentationValue.RecordValue(Map.of("list",
			new PresentationValue.Sequence(List.of(new PresentationValue.Text("v")))));
		var top = PresentationPropertyRef.root(BASIC, "pingforit:record");
		var section = new PresentationSection(BASIC, 1, Map.of("pingforit:record", record), false,
			Map.of(top, "go_to"));

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			PresentationCodec.write(buf, section);
			var decoded = PresentationCodec.read(buf, id -> true);
			assertEquals(Map.of(top, "go_to"), decoded.annotations());
			assertEquals(record, decoded.fields().get("pingforit:record"));
		} finally {
			buf.release();
		}
	}

	@Test
	void unknownAnnotationTypeAndSectionTrailingBytesAreRejectedOnDecode() {
		var counts = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("#counts"));
		var annotated = new PresentationSection(BASIC, 1, stateFields(), false, Map.of(counts, "danger"));

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			PresentationCodec.write(buf, annotated);
			byte[] bytes = new byte[buf.readableBytes()];
			buf.getBytes(buf.readerIndex(), bytes);

			byte[] target = "danger".getBytes(StandardCharsets.UTF_8);
			int at = indexOf(bytes, target);
			assertTrue(at >= 0);
			byte[] patched = bytes.clone();
			System.arraycopy("dangor".getBytes(StandardCharsets.UTF_8), 0, patched, at, target.length);
			FriendlyByteBuf patchedBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(patched));
			try {
				assertThrows(IllegalArgumentException.class, () -> PresentationCodec.read(patchedBuf, id -> true));
			} finally {
				patchedBuf.release();
			}
		} finally {
			buf.release();
		}

		FriendlyByteBuf trailing = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer());
			try {
				frame.writeUtf(BASIC, 193);
				frame.writeVarInt(1);
				frame.writeBoolean(false);
				frame.writeVarInt(0);
				frame.writeByte(7);
				trailing.writeVarInt(frame.readableBytes());
				trailing.writeBytes(frame);
			} finally {
				frame.release();
			}
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.read(trailing, id -> true));
		} finally {
			trailing.release();
		}
	}

	@Test
	void propertyIntentFramesStayTypedBoundedAndTrailingSafe() {
		var ref = PresentationPropertyRef.root(BASIC, "minecraft:block.state");
		var observations = List.of(
			PresentationPropertyIntent.observed(ref, new PresentationValue.NumberValue(7.5)),
			PresentationPropertyIntent.of(ref, new PresentationValue.Flag(true), "danger"),
			PresentationPropertyIntent.observed(ref, new PresentationValue.RecordValue(
				Map.of("speed", new PresentationValue.NumberValue(1.5)))));

		for (PresentationPropertyIntent intent : observations) {
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
			try {
				PresentationCodec.writePropertyIntent(buf, intent);
				assertEquals(intent, PresentationCodec.readPropertyIntent(buf));
				assertEquals(0, buf.readableBytes());
			} finally {
				buf.release();
			}
		}

		FriendlyByteBuf declaredOversize = new FriendlyByteBuf(Unpooled.buffer());
		try {
			declaredOversize.writeVarInt(PresentationCodec.MAX_FIELD_BYTES + 1);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readPropertyIntent(declaredOversize));
		} finally {
			declaredOversize.release();
		}

		FriendlyByteBuf inner = new FriendlyByteBuf(Unpooled.buffer());
		FriendlyByteBuf trailing = new FriendlyByteBuf(Unpooled.buffer());
		try {
			inner.writeUtf(BASIC, 193);
			inner.writeUtf("minecraft:target.name", 193);
			inner.writeVarInt(0);
			inner.writeByte(1);
			inner.writeUtf("Chest", PresentationCodec.MAX_TEXT_BYTES);
			inner.writeBoolean(false);
			inner.writeByte(7);
			trailing.writeVarInt(inner.readableBytes());
			trailing.writeBytes(inner);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readPropertyIntent(trailing));
		} finally {
			inner.release();
			trailing.release();
		}

		Map<String, PresentationValue> huge = new LinkedHashMap<>();
		for (int i = 0; i < 5; i++) {
			huge.put("k" + i, new PresentationValue.Text("x".repeat(PresentationLimits.MAX_TEXT_BYTES)));
		}
		var oversized = PresentationPropertyIntent.observed(ref, new PresentationValue.RecordValue(huge));
		FriendlyByteBuf target = new FriendlyByteBuf(Unpooled.buffer());
		try {
			assertThrows(RuntimeException.class, () -> PresentationCodec.writePropertyIntent(target, oversized));
		} finally {
			target.release();
		}
	}

	@Test
	void blankAndWhitespaceRecordKeysRoundTripWithoutAnnotations() {
		Map<String, PresentationValue> inner = new LinkedHashMap<>();
		inner.put("", new PresentationValue.Text("empty"));
		inner.put("   ", new PresentationValue.Text("blank"));
		inner.put("valid", new PresentationValue.NumberValue(2));
		Map<String, PresentationValue> outer = new LinkedHashMap<>();
		outer.put(" ", new PresentationValue.RecordValue(inner));
		outer.put("minecraft:cobblestone", new PresentationValue.RecordValue(Map.of("x", new PresentationValue.Text("1"))));
		Map<String, PresentationValue> fields = Map.of(PresentationBasic.BLOCK_STATE,
			new PresentationValue.RecordValue(outer));

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			var plain = new PresentationSection(BASIC, 1, fields, false);
			PresentationCodec.write(buf, plain);
			var decoded = PresentationCodec.read(buf, id -> true);
			assertEquals(plain, decoded);
			assertTrue(decoded.annotations().isEmpty());
		} finally {
			buf.release();
		}

		var addressable = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE,
			List.of("minecraft:cobblestone", "x"));
		FriendlyByteBuf annotatedBuf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			var annotated = new PresentationSection(BASIC, 1, fields, false, Map.of(addressable, "danger"));
			PresentationCodec.write(annotatedBuf, annotated);
			assertEquals(Map.of(addressable, "danger"), PresentationCodec.read(annotatedBuf, id -> true).annotations());
		} finally {
			annotatedBuf.release();
		}

		// A path through a blank or whitespace key is never a legal property ref.
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("")));
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of(" ", "valid")));
	}

	@Test
	void nonBooleanAnnotationOnABlankKeyPathIsRejectedOnDecode() {
		FriendlyByteBuf field = new FriendlyByteBuf(Unpooled.buffer());
		FriendlyByteBuf section = new FriendlyByteBuf(Unpooled.buffer());
		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
		try {
			field.writeByte(5);
			field.writeVarInt(1);
			field.writeUtf("", PresentationPropertyRef.MAX_KEY_BYTES);
			field.writeByte(1);
			field.writeUtf("x", PresentationCodec.MAX_TEXT_BYTES);
			field.writeBoolean(true); // an annotation flag on an unaddressable path
			field.writeUtf("danger", PresentationCodec.MAX_PING_TYPE_LENGTH);

			section.writeUtf(BASIC, 193);
			section.writeVarInt(1);
			section.writeBoolean(false);
			section.writeVarInt(1);
			section.writeUtf("pingforit:record", 193);
			section.writeVarInt(field.readableBytes());
			section.writeBytes(field);

			out.writeVarInt(section.readableBytes());
			out.writeBytes(section);

			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.read(out, id -> true));
		} finally {
			field.release();
			section.release();
			out.release();
		}
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			boolean match = true;
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) { match = false; break; }
			}
			if (match) return i;
		}
		return -1;
	}
}
