package nx.pingwheel.common.presentation.inventory;

import java.util.List;
import nx.pingwheel.common.network.InventoryS2CPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryChecksumsTest {

	/**
	 * Independent external oracle, not produced by the production helper.
	 *
	 * <p>FNV-1a 64 with offset basis {@code 0xcbf29ce484222325} and prime
	 * {@code 0x100000001b3}. The canonical stream is an eight-byte domain length
	 * plus domain UTF-8 bytes, then samples sorted by unsigned UTF-8 key. Each
	 * sample is eight-byte key length plus key bytes, eight-byte item-id length
	 * plus bytes, eight-byte count, one fallback byte, one quality-present byte,
	 * then optional eight-byte quality length plus bytes. The domain is
	 * `pingwheel.inventory.checksum.v1` for ITEM and
	 * `pingforit.inventory.fluid.checksum.v1` for FLUID.
	 */
	static final long ITEM_GOLDEN_CHECKSUM = -6634718076368910857L; // 0xa3ecbf0fd12b05f7
	static final long FLUID_GOLDEN_CHECKSUM = -5856313389483100940L; // 0xaeba3200167ef4f4

	private static InventoryChecksums.Sample sample(String key, String itemId, long count, String quality,
			boolean fallback) {
		return new InventoryChecksums.Sample(key, itemId, count, quality, fallback);
	}

	private static java.util.List<InventoryChecksums.Sample> goldenSamples() {
		return List.of(
			sample("a", "minecraft:stone", 1L, "UNCERTAIN", false),
			sample("é", "minecraft:dirt", 2L, "COMPONENT_TOO_LONG", true));
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

	@Test
	void independentGoldenVectorsCoverOrderingAndLaneDomain() {
		List<InventoryChecksums.Sample> forward = goldenSamples();
		List<InventoryChecksums.Sample> reverse = List.of(forward.get(1), forward.get(0));

		assertEquals(ITEM_GOLDEN_CHECKSUM, InventoryChecksums.checksum(forward));
		assertEquals(ITEM_GOLDEN_CHECKSUM, InventoryChecksums.checksum(reverse),
			"canonical key order is independent of input order");
		assertEquals(FLUID_GOLDEN_CHECKSUM, InventoryChecksums.checksum(InventoryResourceKind.FLUID, forward));
		assertNotEquals(ITEM_GOLDEN_CHECKSUM, FLUID_GOLDEN_CHECKSUM,
			"ITEM and FLUID domains must not alias");

		InventoryS2CPacket.Entry first = new InventoryS2CPacket.Entry("a", "minecraft:stone", "stone", null,
			1L, 1L, false, InventoryS2CPacket.Status.UNCERTAIN);
		InventoryS2CPacket.Entry second = new InventoryS2CPacket.Entry("é", "minecraft:dirt", "dirt", null,
			2L, 1L, true, InventoryS2CPacket.Status.COMPONENT_TOO_LONG);
		assertEquals(ITEM_GOLDEN_CHECKSUM, InventoryChecksums.checksumEntries(List.of(second, first)));
		assertEquals(ITEM_GOLDEN_CHECKSUM,
			InventoryChecksums.checksumEntries(InventoryResourceKind.ITEM, List.of(first, second)));
		assertEquals(FLUID_GOLDEN_CHECKSUM,
			InventoryChecksums.checksumEntries(InventoryResourceKind.FLUID, List.of(first, second)));
	}

	@Test
	void sampleVisitorRunsOnceForTheWholeCut() {
		InventoryS2CPacket.Entry first = new InventoryS2CPacket.Entry("a", "minecraft:stone", "Stone", null,
			3L, 1L, false, null);
		InventoryS2CPacket.Entry second = new InventoryS2CPacket.Entry("b", "minecraft:dirt", "Dirt", null,
			5L, 1L, false, null);
		int[] calls = {0};
		int[] samples = {0};

		long observed = InventoryChecksums.checksumEntries(InventoryResourceKind.ITEM, List.of(first, second),
			count -> { calls[0]++; samples[0] += count; });

		assertEquals(InventoryChecksums.checksumEntries(List.of(first, second)), observed);
		assertEquals(1, calls[0], "one full-cut traversal, not one per sample");
		assertEquals(2, samples[0]);
	}
}
