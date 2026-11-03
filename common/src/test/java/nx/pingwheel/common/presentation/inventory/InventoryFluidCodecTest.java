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
	@Test void capacityCountsCompleteKeysIncludingAggregatesRatherThanOnlyCanonicalEncodings() {
		for (boolean aggregate : new boolean[] {false, true}) {
			var catalog = new InventoryFluidCodec.Catalog();
			var identity = aggregate ? InventoryFluidCodec.Identity.refused() : InventoryFluidCodec.Identity.exact(new byte[] {0});
			for (int index = 0; index < 256; index++) {
				String id = "test:fluid_" + index;
				catalog.intern(id, 1, identity, new InventoryFluidValue.Display(id, "fluid", null));
			}
			long retained = catalog.modeledRetainedBytes();
			assertThrows(InventoryFluidCodec.CapacityException.class, () -> catalog.intern("test:overflow", 1, identity,
				new InventoryFluidValue.Display("test:overflow", "overflow", null)));
			assertEquals(retained, catalog.modeledRetainedBytes(), "refusal must precede retained catalogue mutation");
			var repeat = catalog.intern("test:fluid_0", Long.MAX_VALUE, identity, new InventoryFluidValue.Display("test:fluid_0", "other display", null));
			assertEquals(Long.MAX_VALUE, repeat.amount()); assertEquals(retained, catalog.modeledRetainedBytes());
		}
	}
	@Test void completeKeyRefusalCannotMutateCanonicalCollisionEvidenceAndRepeatedKeysRemainFree() throws Exception {
		var catalog = new InventoryFluidCodec.Catalog();
		var bare = new InventoryFluidValue.Display("test:fluid", "fluid", null);
		var identity = InventoryFluidCodec.Identity.exact(new byte[] {0});
		var first = catalog.intern("test:fluid", 1, identity, bare);
		for (int index = 1; index < 256; index++) {
			String id = "test:other_" + index;
			catalog.intern(id, index, InventoryFluidCodec.Identity.refused(), new InventoryFluidValue.Display(id, "fluid", null));
		}
		long before = catalog.retainedBytes();
		assertThrows(InventoryFluidCodec.CapacityException.class, () -> catalog.intern("test:overflow", 1,
			InventoryFluidCodec.Identity.exact(new byte[] {1}), new InventoryFluidValue.Display("test:overflow", "fluid", null)));
		assertEquals(before, catalog.retainedBytes(), "a new digest cannot slip into collision history after complete-key refusal");
		assertEquals(first.key(), catalog.intern("test:fluid", 99, identity, bare).key());
		// Inject conflicting retained collision evidence under the independently computed digest.
		// Collision comparison must remain mandatory even at full definition capacity.
		var field = InventoryFluidCodec.Catalog.class.getDeclaredField("identities"); field.setAccessible(true);
		@SuppressWarnings("unchecked") var evidence = (java.util.Map<String, byte[]>) field.get(catalog);
		evidence.put(first.key().componentsKey(), new byte[] {9});
		var collision = assertThrows(IllegalStateException.class, () -> catalog.intern("test:fluid", 99, identity, bare));
		assertFalse(collision instanceof InventoryFluidCodec.CapacityException);
	}
	@Test void exactAndAggregateOfOneFluidAreSeparateDefinitionsAndCanonicalPayloadRetainsItsOwnGuard() {
		var catalog = new InventoryFluidCodec.Catalog();
		var display = new InventoryFluidValue.Display("test:fluid", "fluid", null);
		catalog.intern("test:fluid", 1, InventoryFluidCodec.Identity.exact(new byte[] {0}), display);
		catalog.intern("test:fluid", 1, InventoryFluidCodec.Identity.refused(), display);
		for (int index = 0; index < 254; index++) {
			String id = "test:other_" + index;
			catalog.intern(id, 1, InventoryFluidCodec.Identity.refused(), new InventoryFluidValue.Display(id, "fluid", null));
		}
		assertThrows(InventoryFluidCodec.CapacityException.class, () -> catalog.intern("test:overflow", 1,
			InventoryFluidCodec.Identity.refused(), new InventoryFluidValue.Display("test:overflow", "fluid", null)));
		var payload = new InventoryFluidCodec.Catalog();
		for (int index = 0; index < 3; index++) {
			byte[] bytes = new byte[65536]; bytes[0] = (byte) index;
			payload.intern("test:fluid", 1, InventoryFluidCodec.Identity.exact(bytes), display);
		}
		long retained = payload.modeledRetainedBytes(); byte[] fourth = new byte[65536]; fourth[0] = 3;
		assertThrows(InventoryFluidCodec.CapacityException.class, () -> payload.intern("test:fluid", 1, InventoryFluidCodec.Identity.exact(fourth), display));
		assertEquals(retained, payload.modeledRetainedBytes());
	}
}
