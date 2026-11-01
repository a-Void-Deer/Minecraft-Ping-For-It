package nx.pingwheel.common.presentation.inventory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import nx.pingwheel.common.presentation.source.CaptureResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryFluidCodecTest {
	/** Independent binary observation oracle: version, four-byte UTF lengths, amount, strict presence/mode. */
	private static byte[] literal(int presence, byte[] label, int aggregate) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(bytes)) {
			out.writeByte(1);
			byte[] id = "minecraft:water".getBytes(StandardCharsets.UTF_8); out.writeInt(id.length); out.write(id);
			out.writeInt(1); out.writeByte('v'); out.writeLong(9007199254740993L);
			out.writeInt(label.length); out.write(label); out.writeByte(presence); out.writeByte(aggregate);
		}
		return bytes.toByteArray();
	}
	private static CaptureResult.OpaqueValue tagged(byte[] bytes) { return CaptureResult.OpaqueValue.forCodec(InventoryFluidCodec.CODEC, bytes); }
	@Test void independentLiteralRoundTripsBeyondDoublePrecisionAndDistinguishesEmptyFromUnavailable() throws IOException {
		byte[] bytes = literal(0, "water".getBytes(StandardCharsets.UTF_8), 0);
		var value = InventoryFluidCodec.decode(tagged(bytes)); assertNotNull(value);
		assertEquals(InventoryResourceKind.FLUID, value.key().kind()); assertEquals(9007199254740993L, value.amount());
		assertNull(value.display().displayJson()); assertFalse(value.aggregate());
		assertArrayEquals(bytes, InventoryFluidCodec.encode(value).bytes());
		assertNull(InventoryFluidCodec.decode(InventoryFluidCodec.encode(null)));
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidCodec.decode(new CaptureResult.OpaqueValue(bytes)));
	}
	@Test void allTruncationsTrailingBytesMalformedUtfAndNonBooleanFlagsReject() throws IOException {
		byte[] valid = literal(0, new byte[] {'w'}, 0);
		for (int i = 1; i < valid.length; i++) {
			byte[] truncated = Arrays.copyOf(valid, i);
			assertThrows(IllegalArgumentException.class, () -> InventoryFluidCodec.decode(tagged(truncated)), "prefix " + i);
		}
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidCodec.decode(tagged(Arrays.copyOf(valid, valid.length + 1))));
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidCodec.decode(tagged(literal(2, new byte[] {'w'}, 0))));
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidCodec.decode(tagged(literal(0, new byte[] {'w'}, 2))));
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidCodec.decode(tagged(literal(0, new byte[] {(byte) 0xc0, (byte) 0xaf}, 0))));
		byte[] unknown = valid.clone(); unknown[0] = 2;
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidCodec.decode(tagged(unknown)));
		byte[] allocationAttack = valid.clone(); Arrays.fill(allocationAttack, 1, 5, (byte) 0x7f);
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidCodec.decode(tagged(allocationAttack)));
	}
	@Test void boundedDisplayIsIndependentOfExactnessAndStrictAtTheByteBoundary() {
		var key = new InventoryScanner.Key(InventoryResourceKind.FLUID, "minecraft:water", "variant");
		String text = "é".repeat(InventoryFluidCodec.MAX_DISPLAY_BYTES / 2);
		var display = new InventoryFluidValue.Display(key.resourceId(), "water", text);
		var value = new InventoryFluidValue(key, Long.MAX_VALUE, display, false);
		assertEquals(value, InventoryFluidCodec.decode(InventoryFluidCodec.encode(value)));
		assertThrows(IllegalArgumentException.class, () -> new InventoryFluidValue.Display(key.resourceId(), "water", text + "a"));
		assertThrows(IllegalArgumentException.class, () -> new InventoryFluidValue.Display(key.resourceId(), "water", "\ud800"));
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidCodec.Identity.exact(new byte[InventoryFluidCodec.MAX_CANONICAL_BYTES + 1]));
	}
}
