package nx.pingwheel.common.presentation.minecraft;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftBlockReadSourcesTest {
	@Test
	void physicalReadCoordinatesDoNotLeakIntoOriginalDetachedExternalTarget() {
		Target.ExternalBlockTarget target = Target.ExternalBlockTarget.candidate(
			"minecraft:overworld", "sable", "minecraft:chest", "locator", true);
		var source = new BlockReadSource(target, "sable", "sublevel",
			new Target.BlockTarget(target.dimensionId(), 100, 64, -200, "minecraft:chest"), new MarkerAnchor(5, 6, 7));
		var detached = MinecraftBlockReadSources.detached(source);
		assertSame(target, detached.externalBlock());
		assertEquals(0, detached.x());
		assertEquals(0, detached.y());
		assertEquals(0, detached.z());
		assertEquals("", detached.locator());
		assertEquals("minecraft:chest", detached.registryId());
	}

	@Test
	void candidateAndCommittedReadBindingsRequireLocatorAlongsideUnchangedIdentityQuartet() {
		for (String stable : java.util.List.of("", "stable")) {
			var first = new Target.ExternalBlockTarget("minecraft:overworld", "sable", stable, "minecraft:chest", "first", true);
			var otherLocator = new Target.ExternalBlockTarget("minecraft:overworld", "sable", stable, "minecraft:chest", "second", true);
			var changedClassification = new Target.ExternalBlockTarget("minecraft:overworld", "sable", stable, "minecraft:chest", "first", false);
			assertEquals(first, otherLocator);
			assertFalse(MinecraftBlockReadSources.sameReadBinding(first, otherLocator));
			assertTrue(MinecraftBlockReadSources.sameReadBinding(first, changedClassification));
			var block = new Target.BlockTarget(first.dimensionId(), 100, 64, -200, first.expectedBlockRegistryId());
			var descriptor = new BlockReadSource(first, "sable", "first-sublevel", block, new MarkerAnchor(1, 2, 3));
			assertFalse(MinecraftBlockReadSources.sameReadBinding(descriptor,
				new BlockReadSource(first, "sable", "other-sublevel", block, new MarkerAnchor(1, 2, 3))));
			assertFalse(MinecraftBlockReadSources.sameReadBinding(descriptor,
				new BlockReadSource(first, "sable", "first-sublevel",
					new Target.BlockTarget(first.dimensionId(), 101, 64, -200, first.expectedBlockRegistryId()), new MarkerAnchor(1, 2, 3))));
		}
	}
}
