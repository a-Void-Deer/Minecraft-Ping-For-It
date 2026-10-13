package nx.pingwheel.common.presentation.inventory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import nx.pingwheel.common.presentation.source.CaptureResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryDomainCodecTest {

	@Test
	void absoluteDisplayEnvelopeRoundTripsWithinTheCodecBound() {
		String display = "d".repeat(InventoryDomainCodec.MAX_DISPLAY_BYTES);
		var item = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "digest"), Long.MAX_VALUE,
			"l".repeat(1024), display, false);

		var encoded = InventoryDomainCodec.encode(item);

		assertEquals(InventoryDomainCodec.CODEC, encoded.codec());
		assertTrue(encoded.size() <= InventoryDomainCodec.MAX_BYTES);
		assertEquals(InventoryDomainCodec.MAX_DISPLAY_BYTES, display.getBytes(StandardCharsets.UTF_8).length);
		assertEquals(item, InventoryDomainCodec.decode(encoded));
		assertEquals(133120, InventoryDomainCodec.MAX_BYTES, "the total envelope is the code-owned token bound");
		assertEquals(131072, InventoryDomainCodec.MAX_DISPLAY_BYTES, "the display field is bounded at the absolute budget");
	}

	@Test
	void displayBeyondTheAbsoluteBoundIsRejectedBeforeEncoding() {
		assertThrows(IllegalArgumentException.class, () -> new InventoryDomainCodec.Item(
			new InventoryScanner.Key("minecraft:stone", "digest"), 1, "stone",
			"d".repeat(InventoryDomainCodec.MAX_DISPLAY_BYTES + 1), false));
	}

	@Test
	void wideDisplayLengthIsCheckedAgainstBoundAndRemainingInputBeforeAllocation() throws IOException {
		byte[] overBound = raw(InventoryDomainCodec.MAX_DISPLAY_BYTES + 1, 0, false);
		byte[] negative = raw(-1, 0, false);
		byte[] truncated = raw(64, 16, false);
		byte[] atBound = raw(InventoryDomainCodec.MAX_DISPLAY_BYTES, InventoryDomainCodec.MAX_DISPLAY_BYTES, false);

		assertThrows(IllegalArgumentException.class, () -> InventoryDomainCodec.decode(value(overBound)));
		assertThrows(IllegalArgumentException.class, () -> InventoryDomainCodec.decode(value(negative)));
		assertThrows(IllegalArgumentException.class, () -> InventoryDomainCodec.decode(value(truncated)));
		assertEquals(InventoryDomainCodec.MAX_DISPLAY_BYTES,
			InventoryDomainCodec.decode(value(atBound)).displayJson().length());
	}

	@Test
	void emptyNullAndTrailingRulesAreUnchanged() throws IOException {
		var empty = InventoryDomainCodec.encode(null);
		assertEquals(InventoryDomainCodec.CODEC, empty.codec());
		assertEquals(0, empty.size());
		assertNull(InventoryDomainCodec.decode(empty), "empty bytes are an observed empty slot, not a value");

		byte[] trailing = raw(4, 4, true);
		assertThrows(IllegalArgumentException.class, () -> InventoryDomainCodec.decode(value(trailing)));
	}

	@Test
	void inventoryValuesStayIsolatedToTheAdoptedCodecId() {
		var item = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "digest"), 1, "stone",
			null, false);
		var encoded = InventoryDomainCodec.encode(item);

		assertEquals("pingforit:inventory.observation.v1", InventoryDomainCodec.ID,
			"the domain codec adopts the version-one observation descriptor");
		assertThrows(IllegalArgumentException.class, () -> new CaptureResult.OpaqueKeyedFragment(
			"pingforit:inventory.observation.v2", Map.of("k", encoded)));
		assertEquals(encoded, new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, Map.of("k", encoded))
			.entries().get("k"));
		assertEquals(item, InventoryDomainCodec.decode(encoded));
	}

	private static CaptureResult.OpaqueValue value(byte[] bytes) {
		return CaptureResult.OpaqueValue.forCodec(InventoryDomainCodec.CODEC, bytes);
	}

	/** Minimal raw observation frame with a chosen wide display length. */
	private static byte[] raw(int declaredDisplayLength, int actualDisplayBytes, boolean trailing) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeShort(1);
			out.writeByte('k');
			out.writeShort(1);
			out.writeByte('v');
			out.writeLong(1L);
			out.writeShort(1);
			out.writeByte('l');
			out.writeBoolean(true);
			out.writeInt(declaredDisplayLength);
			out.write(new byte[actualDisplayBytes]);
			out.writeBoolean(false);
			if (trailing) out.writeByte(0);
		}
		return bytes.toByteArray();
	}
}
