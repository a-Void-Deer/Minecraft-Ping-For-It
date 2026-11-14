package nx.pingwheel.common.client.outline;

import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.TargetKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockOutlineSpecLookupTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID OWNER = new UUID(0L, 100L);

	@Test
	void specConstructorIsStrictAndForcesOpaque() {
		assertThrows(NullPointerException.class,
			() -> new BlockOutlineSpec(null, keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), "block", "attention", 0xFFC247));
		assertThrows(NullPointerException.class,
			() -> new BlockOutlineSpec(new MarkerId(1L), null, "block", "attention", 0xFFC247));
		assertThrows(NullPointerException.class,
			() -> new BlockOutlineSpec(
				new MarkerId(1L), keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), null, "attention", 0xFFC247));
		assertThrows(IllegalArgumentException.class,
			() -> new BlockOutlineSpec(
				new MarkerId(1L), keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), "  ", "attention", 0xFFC247));
		assertThrows(IllegalArgumentException.class,
			() -> new BlockOutlineSpec(
				new MarkerId(1L), keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), "block", "  ", 0xFFC247));

		BlockOutlineSpec spec = new BlockOutlineSpec(
			new MarkerId(1L), keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), "block", "attention", 0xC247);

		assertEquals("block", spec.targetTypeId());
		assertEquals(0xFF00C247, spec.argbColor());

		// A nonzero partial caller alpha is discarded: the spec stays fully
		// opaque and keeps only the 24-bit RGB payload.
		BlockOutlineSpec alphaSpec = new BlockOutlineSpec(
			new MarkerId(2L), keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), "entity_block", "attention", 0x8000C247);

		assertEquals("entity_block", alphaSpec.targetTypeId());
		assertEquals(0xFF00C247, alphaSpec.argbColor());
	}

	@Test
	void specForAcceptsOnlyBlockTargets() {
		ClientMarker block = marker(1L, blockTarget(1, 2, 3, "minecraft:stone"), "block", "attention");
		Optional<BlockOutlineSpec> blockSpec = BlockPresentationLookup.specFor(block);

		assertTrue(blockSpec.isPresent());
		assertEquals(new MarkerId(1L), blockSpec.orElseThrow().markerId());
		assertEquals(keyOf(OVERWORLD, 1, 2, 3, "minecraft:stone"), blockSpec.orElseThrow().blockKey());

		ClientMarker entity = marker(2L, new Target.EntityTarget(OVERWORLD, UUID.randomUUID()), "entity", "attention");
		assertTrue(BlockPresentationLookup.specFor(entity).isEmpty());

		ClientMarker location = marker(3L, new Target.LocationTarget(OVERWORLD, 1.0, 2.0, 3.0), "location", "attention");
		assertTrue(BlockPresentationLookup.specFor(location).isEmpty());
	}

	@Test
	void specForCarriesTargetTypeId() {
		ClientMarker block = marker(1L, blockTarget(1, 2, 3, "minecraft:stone"), "block", "attention");
		ClientMarker entityBlock = marker(2L, blockTarget(4, 5, 6, "minecraft:chest"), "entity_block", "destroy");

		BlockOutlineSpec blockSpec = BlockPresentationLookup.specFor(block).orElseThrow();
		assertEquals(new MarkerId(1L), blockSpec.markerId());
		assertEquals("block", blockSpec.targetTypeId());
		assertEquals("attention", blockSpec.pingTypeId());

		BlockOutlineSpec entityBlockSpec = BlockPresentationLookup.specFor(entityBlock).orElseThrow();
		assertEquals(new MarkerId(2L), entityBlockSpec.markerId());
		assertEquals("entity_block", entityBlockSpec.targetTypeId());
		assertEquals("destroy", entityBlockSpec.pingTypeId());
	}

	private static ClientMarker marker(long id, Target target, String targetTypeId, String pingTypeId) {
		return new ClientMarker(
			new MarkerId(id),
			OWNER,
			target,
			targetTypeId,
			pingTypeId,
			new MarkerAnchor(0, 0, 0),
			1L,
			100L,
			0L,
			100L);
	}

	private static Target blockTarget(int x, int y, int z, String registryId) {
		return new Target.BlockTarget(OVERWORLD, x, y, z, registryId);
	}

	private static TargetKey.BlockKey keyOf(String dimension, int x, int y, int z, String registryId) {
		return new TargetKey.BlockKey(dimension, x, y, z, registryId);
	}
}
