package nx.pingwheel.common.presentation.source;

import nx.pingwheel.common.presentation.PresentationLimits;
import nx.pingwheel.common.presentation.PresentationValue;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpaqueKeyedPayloadTest {

	private static final String CODEC = "test:inventory.items.v1";

	@Test
	void exactIntegerBeyondDoublePrecisionSurvivesTheDomainByteCodec() {
		long exact = 9007199254740993L;
		String displayJson = "{\"id\":\"minecraft:stone\",\"lore\":\"" + "d".repeat(2048) + "\"}";
		byte[] encoded = encodeEntry(exact, displayJson);

		var payload = new CaptureResult.OpaqueKeyedFragment(CODEC,
			Map.of("test:item/0", new CaptureResult.OpaqueValue(encoded)));
		byte[] carried = payload.entries().get("test:item/0").bytes();

		assertEquals(exact, decodeCount(carried), "the exact long is carried losslessly");
		assertEquals(displayJson, decodeDisplay(carried), "the domain display value is carried independently");
		assertNotEquals(exact, (long) (double) exact, "the legacy double form cannot represent this value");
		assertTrue(displayJson.getBytes(StandardCharsets.UTF_8).length > PresentationLimits.MAX_TEXT_BYTES,
			"the domain display value exceeds the generic presentation text bound");
		assertThrows(IllegalArgumentException.class,
			() -> PresentationLimits.validate(new PresentationValue.Text(displayJson)),
			"the generic presentation text bound cannot carry the domain display value");
	}

	@Test
	void inputAndOutputMutationsCannotAffectThePayload() {
		byte[] source = {1, 2, 3};
		var value = new CaptureResult.OpaqueValue(source);
		source[0] = 9;

		byte[] read = value.bytes();
		read[1] = 9;

		assertArrayEquals(new byte[] {1, 2, 3}, value.bytes(), "construction and access must both copy");
		assertNotSame(read, value.bytes());

		Map<String, CaptureResult.OpaqueValue> entries = new LinkedHashMap<>();
		entries.put("test:item/0", value);
		var payload = new CaptureResult.OpaqueKeyedFragment(CODEC, entries);
		entries.put("test:item/1", new CaptureResult.OpaqueValue(new byte[] {4}));

		assertEquals(1, payload.entries().size(), "construction must copy the caller map");
		assertThrows(UnsupportedOperationException.class,
			() -> payload.entries().put("test:item/2", new CaptureResult.OpaqueValue(new byte[] {5})));
	}

	@Test
	void opaqueValuesCompareByTheirBytes() {
		var first = new CaptureResult.OpaqueValue(new byte[] {1, 2, 3});
		var equal = new CaptureResult.OpaqueValue(new byte[] {1, 2, 3});
		var different = new CaptureResult.OpaqueValue(new byte[] {3, 2, 1});

		assertEquals(first, equal);
		assertEquals(first.hashCode(), equal.hashCode());
		assertNotEquals(first, different);
	}

	@Test
	void opaqueValueBoundAdmitsTheExactLimitAndRejectsOneMore() {
		var atBound = new CaptureResult.OpaqueValue(new byte[CaptureResult.OpaqueValue.MAX_VALUE_BYTES]);

		assertEquals(CaptureResult.OpaqueValue.MAX_VALUE_BYTES, atBound.bytes().length);
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.OpaqueValue(new byte[CaptureResult.OpaqueValue.MAX_VALUE_BYTES + 1]));
	}

	@Test
	void opaqueBoundsRejectInvalidValuesKeysAndCodecs() {
		var value = new CaptureResult.OpaqueValue(new byte[] {1});

		assertThrows(NullPointerException.class, () -> new CaptureResult.OpaqueValue(null));
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.OpaqueKeyedFragment(" ", Map.of("test:item/0", value)));
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.OpaqueKeyedFragment("c".repeat(SourceKey.MAX_TOKEN_BYTES + 1),
				Map.of("test:item/0", value)));
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.OpaqueKeyedFragment(CODEC, Map.of(" ", value)));
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.OpaqueKeyedFragment(CODEC,
				Map.of("k".repeat(SourceKey.MAX_TOKEN_BYTES + 1), value)));
		assertThrows(NullPointerException.class, () -> new CaptureResult.OpaqueKeyedFragment(CODEC, null));

		Map<String, CaptureResult.OpaqueValue> withNullValue = new LinkedHashMap<>();
		withNullValue.put("test:item/0", null);
		assertThrows(NullPointerException.class,
			() -> new CaptureResult.OpaqueKeyedFragment(CODEC, withNullValue));
	}

	@Test
	void opaquePageBoundIsIndependentOfTheRecordValueEntryBound() {
		Map<String, CaptureResult.OpaqueValue> overRecordBound = new LinkedHashMap<>();
		for (int index = 0; index <= PresentationLimits.MAX_ENTRIES; index++) {
			overRecordBound.put("test:item/" + index, new CaptureResult.OpaqueValue(new byte[] {(byte) index}));
		}

		var payload = new CaptureResult.OpaqueKeyedFragment(CODEC, overRecordBound);
		assertEquals(PresentationLimits.MAX_ENTRIES + 1, payload.entries().size(),
			"an opaque page is not capped by the record-value entry bound");

		Map<String, CaptureResult.OpaqueValue> overPageBound = new LinkedHashMap<>();
		for (int index = 0; index <= CaptureResult.OpaqueKeyedFragment.MAX_ENTRIES; index++) {
			overPageBound.put("test:item/" + index, new CaptureResult.OpaqueValue(new byte[] {(byte) index}));
		}
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.OpaqueKeyedFragment(CODEC, overPageBound));
	}

	@Test
	void readableCaptureResultCarriesTheOpaquePayloadAndKeepsTheAvailabilityInvariant() {
		var payload = new CaptureResult.OpaqueKeyedFragment(CODEC,
			Map.of("test:item/0", new CaptureResult.OpaqueValue(new byte[] {1})));
		var coverage = new CaptureResult.Coverage("demand-1", 1, 1, OptionalLong.of(1));

		var readable = new CaptureResult(Optional.of(payload), coverage, CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
		assertEquals(payload, readable.payload().orElseThrow());

		for (var availability : new CaptureResult.Availability[] {
			CaptureResult.Availability.UNAVAILABLE, CaptureResult.Availability.INVALID }) {
			assertThrows(IllegalArgumentException.class,
				() -> new CaptureResult(Optional.of(payload), coverage, availability,
					CaptureResult.Completeness.INCOMPLETE, CaptureResult.Consistency.UNKNOWN, Optional.empty(),
					Optional.empty()));
		}
	}

	/** Minimal domain codec example: exact count plus a bounded display JSON string. */
	private static byte[] encodeEntry(long count, String displayJson) {
		byte[] display = displayJson.getBytes(StandardCharsets.UTF_8);
		return ByteBuffer.allocate(Long.BYTES + Integer.BYTES + display.length)
			.putLong(count)
			.putInt(display.length)
			.put(display)
			.array();
	}

	private static long decodeCount(byte[] value) {
		return ByteBuffer.wrap(value).getLong();
	}

	private static String decodeDisplay(byte[] value) {
		ByteBuffer buffer = ByteBuffer.wrap(value);
		buffer.getLong();
		byte[] display = new byte[buffer.getInt()];
		buffer.get(display);
		return new String(display, StandardCharsets.UTF_8);
	}
}
