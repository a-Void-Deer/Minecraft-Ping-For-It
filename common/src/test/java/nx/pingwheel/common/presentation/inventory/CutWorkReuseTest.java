package nx.pingwheel.common.presentation.inventory;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import nx.pingwheel.common.presentation.source.CaptureResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CutWorkReuseTest {

	@Test
	void oneEncodedFragmentCanBackIndependentRecipientResultsWithoutExposingMutableBytes() {
		var item = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "variant"),
			3L, "stone", null, false);
		InventoryEncodedFragment encoded = InventoryEncodedFragment.encode(
			InventoryResourceKind.ITEM, Map.of("selected", item));

		CaptureResult.OpaqueKeyedFragment first = encoded.fragment();
		CaptureResult.OpaqueKeyedFragment second = encoded.fragment();
		assertSame(first, second);
		assertSame(first.entries().get("selected"), second.entries().get("selected"));
		assertEquals(InventoryDomainCodec.ID, encoded.codecId());
		assertEquals(InventoryResourceKind.ITEM, encoded.resourceKind());
		assertArrayEquals(InventoryResourceCodec.encode(InventoryResourceKind.ITEM, item).bytes(),
			first.entries().get("selected").bytes(), "the helper preserves the domain codec bytes");

		byte[] original = first.entries().get("selected").bytes();
		byte[] copy = first.entries().get("selected").bytes();
		assertArrayEquals(original, copy);
		copy[0] ^= 1;
		assertTrue(Arrays.equals(original, first.entries().get("selected").bytes()),
			"a caller cannot mutate the carried detached bytes");
		assertThrows(UnsupportedOperationException.class, () -> encoded.entries().clear());
	}

	@Test
	void reusableFragmentKeepsOneDetachedPayloadForBothRecipientProjections() {
		var item = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", ""),
			7L, "stone", null, true);
		InventoryEncodedFragment encoded = InventoryEncodedFragment.encode(
			InventoryResourceKind.ITEM, Map.of("aggregate", item));
		CaptureResult first = result(encoded, 1L);
		CaptureResult second = result(encoded, 1L);

		assertSame(first.payload().orElseThrow(), second.payload().orElseThrow());
		assertEquals(((CaptureResult.OpaqueKeyedFragment) first.payload().orElseThrow()).entries().keySet(),
			((CaptureResult.OpaqueKeyedFragment) second.payload().orElseThrow()).entries().keySet());
		assertTrue(((CaptureResult.OpaqueKeyedFragment) first.payload().orElseThrow()).entries().containsKey("aggregate"));
	}

	@Test
	void encodedFragmentRejectsACrossLaneValue() {
		var fluid = new InventoryFluidValue(new InventoryScanner.Key(InventoryResourceKind.FLUID,
			"minecraft:water", "fluid"), 1L, new InventoryFluidValue.Display("minecraft:water", "water", null),
			false);
		assertThrows(IllegalArgumentException.class, () -> InventoryEncodedFragment.encode(
			InventoryResourceKind.ITEM, Map.of("selected", fluid)));
	}

	private static CaptureResult result(InventoryEncodedFragment encoded, long revision) {
		return new CaptureResult(Optional.of(encoded.fragment()),
			new CaptureResult.Coverage("inventory", revision, 1, OptionalLong.of(1)),
			CaptureResult.Availability.READABLE, CaptureResult.Completeness.COMPLETE,
			CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
	}
}
