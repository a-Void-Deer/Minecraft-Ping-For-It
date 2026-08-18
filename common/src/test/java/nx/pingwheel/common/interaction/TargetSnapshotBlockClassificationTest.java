package nx.pingwheel.common.interaction;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the block-snapshot classification overload of
 * {@link TargetSnapshotFactory}: the 6-argument form carries the transient
 * {@code EntityBlock} classification in the match context, while the 5-argument
 * form keeps an unknown classification (fail-soft generic block).
 */
class TargetSnapshotBlockClassificationTest {

	private static final String OVERWORLD = "minecraft:overworld";

	@Test
	void blockSnapshotCarriesExplicitBlockEntityClassification() {
		TargetSnapshot chest = TargetSnapshotFactory.block(OVERWORLD, 1, 2, 3, "minecraft:chest", true);

		assertEquals(Optional.of(true), chest.matchContext().blockHasBlockEntity());
		assertEquals("minecraft:chest", ((Target.BlockTarget) chest.target()).blockRegistryId());
	}

	@Test
	void plainBlockSnapshotCarriesNegativeClassification() {
		TargetSnapshot stone = TargetSnapshotFactory.block(OVERWORLD, 1, 2, 3, "minecraft:stone", false);

		assertEquals(Optional.of(false), stone.matchContext().blockHasBlockEntity());
	}

	@Test
	void classificationlessBlockSnapshotFailsSoft() {
		TargetSnapshot stone = TargetSnapshotFactory.block(OVERWORLD, 1, 2, 3, "minecraft:stone");

		assertEquals(Optional.empty(), stone.matchContext().blockHasBlockEntity());
	}
}
