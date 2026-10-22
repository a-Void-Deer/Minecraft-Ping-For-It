package nx.pingwheel.common.presentation.inventory;

import java.util.List;
import nx.pingwheel.common.network.InventoryS2CPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryChecksumsTest {

	private static InventoryChecksums.Sample sample(String key, String itemId, long count, String quality,
			boolean fallback) {
		return new InventoryChecksums.Sample(key, itemId, count, quality, fallback);
	}

	@Test
	void canonicalOrderIsIndependentOfInsertionOrder() {
		long forward = InventoryChecksums.builder()
			.add("a", "minecraft:stone", 1L, null, false)
			.add("b", "minecraft:dirt", 2L, "COMPONENT_TOO_LONG", true)
			.checksum();
		long backward = InventoryChecksums.builder()
			.add("b", "minecraft:dirt", 2L, "COMPONENT_TOO_LONG", true)
			.add("a", "minecraft:stone", 1L, null, false)
			.checksum();

		assertEquals(forward, backward);
	}

	@Test
	void everyComponentChangesTheDigest() {
		long base = InventoryChecksums.checksum(List.of(sample("a", "minecraft:stone", 1L, null, false)));

		assertNotEquals(base,
			InventoryChecksums.checksum(List.of(sample("b", "minecraft:stone", 1L, null, false))));
		assertNotEquals(base,
			InventoryChecksums.checksum(List.of(sample("a", "minecraft:dirt", 1L, null, false))));
		assertNotEquals(base,
			InventoryChecksums.checksum(List.of(sample("a", "minecraft:stone", 2L, null, false))));
		assertNotEquals(base,
			InventoryChecksums.checksum(List.of(sample("a", "minecraft:stone", 1L, "INVALID", false))));
		assertNotEquals(base,
			InventoryChecksums.checksum(List.of(sample("a", "minecraft:stone", 1L, null, true))));
	}

	@Test
	void textComponentsAreLengthDelimited() {
		long splitLeft = InventoryChecksums.checksum(List.of(sample("ab", "c", 0L, null, false)));
		long splitRight = InventoryChecksums.checksum(List.of(sample("a", "bc", 0L, null, false)));

		assertNotEquals(splitLeft, splitRight);
	}

	@Test
	void duplicateKeysAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> InventoryChecksums.checksum(List.of(
			sample("a", "minecraft:stone", 1L, null, false),
			sample("a", "minecraft:dirt", 2L, null, false))));
	}

	@Test
	void wireEntriesMatchTheBuilderProjection() {
		InventoryS2CPacket.Entry withQuality = new InventoryS2CPacket.Entry("a", "minecraft:stone", "Stone", null,
			3L, 1L, true, InventoryS2CPacket.Status.COMPONENT_TOO_LONG);
		InventoryS2CPacket.Entry withoutQuality = new InventoryS2CPacket.Entry("b", "minecraft:dirt", "Dirt", null,
			5L, 1L, false, null);

		long fromEntries = InventoryChecksums.checksumEntries(List.of(withQuality, withoutQuality));
		long fromBuilder = InventoryChecksums.builder()
			.add("a", "minecraft:stone", 3L, "COMPONENT_TOO_LONG", true)
			.add("b", "minecraft:dirt", 5L, null, false)
			.checksum();

		assertEquals(fromBuilder, fromEntries);
	}
}
