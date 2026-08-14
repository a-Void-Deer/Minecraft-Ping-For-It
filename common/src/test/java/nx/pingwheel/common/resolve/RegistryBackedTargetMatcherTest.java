package nx.pingwheel.common.resolve;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetKind;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.registry.OptionalRegistryRef;
import nx.pingwheel.common.registry.RegistryLookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryBackedTargetMatcherTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String BLOCK_REGISTRY = "minecraft:block";
	private static final String ENTITY_REGISTRY = "minecraft:entity_type";

	private static RegistryLookup lookup(String... present) {
		Set<String> set = new HashSet<>(List.of(present));

		return (registryId, entryId) -> set.contains(registryId + ":" + entryId);
	}

	private static RegistryBackedTargetMatcher blockMatcher(RegistryLookup lookup, String... entries) {
		List<OptionalRegistryRef> refs = Arrays.stream(entries)
			.map(entryId -> new OptionalRegistryRef(BLOCK_REGISTRY, entryId))
			.toList();

		return new RegistryBackedTargetMatcher(TargetKind.BLOCK, refs, lookup);
	}

	private static RegistryBackedTargetMatcher entityMatcher(RegistryLookup lookup, String... entries) {
		List<OptionalRegistryRef> refs = Arrays.stream(entries)
			.map(entryId -> new OptionalRegistryRef(ENTITY_REGISTRY, entryId))
			.toList();

		return new RegistryBackedTargetMatcher(TargetKind.ENTITY, refs, lookup);
	}

	@Test
	void blockMatcherMatchesPresentBlock() {
		RegistryBackedTargetMatcher matcher = blockMatcher(
			lookup(BLOCK_REGISTRY + ":minecraft:stone"), "minecraft:stone");

		Target.BlockTarget stone = new Target.BlockTarget(OVERWORLD, 0, 0, 0, "minecraft:stone");

		assertTrue(matcher.isActive());
		assertTrue(matcher.matches(stone, TargetMatchContext.none()));
	}

	@Test
	void blockMatcherIgnoresMissingEntry() {
		RegistryBackedTargetMatcher matcher = blockMatcher(
			lookup(BLOCK_REGISTRY + ":minecraft:stone"),
			"minecraft:stone", "minecraft:modded_block");

		Target.BlockTarget stone = new Target.BlockTarget(OVERWORLD, 0, 0, 0, "minecraft:stone");
		Target.BlockTarget other = new Target.BlockTarget(OVERWORLD, 0, 0, 0, "minecraft:modded_block");

		// present entry matches; the missing entry is simply ignored
		assertTrue(matcher.matches(stone, TargetMatchContext.none()));
		assertFalse(matcher.matches(other, TargetMatchContext.none()));
	}

	@Test
	void blockMatcherDoesNotMatchDifferentBlockType() {
		RegistryBackedTargetMatcher matcher = blockMatcher(
			lookup(BLOCK_REGISTRY + ":minecraft:stone"), "minecraft:stone");

		Target.BlockTarget dirt = new Target.BlockTarget(OVERWORLD, 0, 0, 0, "minecraft:dirt");

		assertFalse(matcher.matches(dirt, TargetMatchContext.none()));
	}

	@Test
	void blockMatcherDoesNotMatchEntityTarget() {
		RegistryBackedTargetMatcher matcher = blockMatcher(
			lookup(BLOCK_REGISTRY + ":minecraft:stone"), "minecraft:stone");

		Target.EntityTarget entity = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());

		assertFalse(matcher.matches(entity, TargetMatchContext.none()));
	}

	@Test
	void entityMatcherMatchesPresentEntityType() {
		RegistryBackedTargetMatcher matcher = entityMatcher(
			lookup(ENTITY_REGISTRY + ":minecraft:item"), "minecraft:item");

		Target.EntityTarget item = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());

		assertTrue(matcher.isActive());
		assertTrue(matcher.matches(item, TargetMatchContext.entityType("minecraft:item")));
	}

	@Test
	void entityMatcherNeedsEntityTypeContext() {
		RegistryBackedTargetMatcher matcher = entityMatcher(
			lookup(ENTITY_REGISTRY + ":minecraft:item"), "minecraft:item");

		Target.EntityTarget item = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());

		assertFalse(matcher.matches(item, TargetMatchContext.none()));
	}

	@Test
	void allRefsAbsentIsInactiveAndDoesNotThrow() {
		RegistryBackedTargetMatcher matcher = blockMatcher(
			lookup(), "minecraft:modded_block_a", "minecraft:modded_block_b");

		Target.BlockTarget block = new Target.BlockTarget(OVERWORLD, 0, 0, 0, "minecraft:modded_block_a");

		assertFalse(matcher.isActive());
		assertFalse(matcher.matches(block, TargetMatchContext.none()));
	}

	@Test
	void noConcreteContentNeverMatches() {
		RegistryBackedTargetMatcher matcher = entityMatcher(
			lookup(), "minecraft:modded_entity");

		Target.EntityTarget entity = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());

		assertFalse(matcher.isActive());
		assertFalse(matcher.matches(entity, TargetMatchContext.entityType("minecraft:modded_entity")));
	}

	@Test
	void rejectsLocationKind() {
		List<OptionalRegistryRef> refs = List.of(new OptionalRegistryRef(BLOCK_REGISTRY, "minecraft:stone"));

		assertThrows(IllegalArgumentException.class,
			() -> new RegistryBackedTargetMatcher(TargetKind.LOCATION, refs, lookup()));
	}

	@Test
	void rejectsMismatchedRegistryForBlockKind() {
		List<OptionalRegistryRef> refs = List.of(new OptionalRegistryRef(ENTITY_REGISTRY, "minecraft:stone"));

		assertThrows(IllegalArgumentException.class,
			() -> new RegistryBackedTargetMatcher(TargetKind.BLOCK, refs, lookup()));
	}

	@Test
	void rejectsMismatchedRegistryForEntityKind() {
		List<OptionalRegistryRef> refs = List.of(new OptionalRegistryRef(BLOCK_REGISTRY, "minecraft:zombie"));

		assertThrows(IllegalArgumentException.class,
			() -> new RegistryBackedTargetMatcher(TargetKind.ENTITY, refs, lookup()));
	}

	@Test
	void evaluateReportsMatchForPresentMatchingEntry() {
		RegistryBackedTargetMatcher matcher = blockMatcher(
			lookup(BLOCK_REGISTRY + ":minecraft:stone"), "minecraft:stone");

		Target.BlockTarget stone = new Target.BlockTarget(OVERWORLD, 0, 0, 0, "minecraft:stone");

		assertEquals(TargetMatchResult.MATCH, matcher.evaluate(stone, TargetMatchContext.none()));
	}

	@Test
	void evaluateReportsNoMatchForPresentNonMatchingEntry() {
		RegistryBackedTargetMatcher matcher = blockMatcher(
			lookup(BLOCK_REGISTRY + ":minecraft:stone"), "minecraft:stone");

		Target.BlockTarget dirt = new Target.BlockTarget(OVERWORLD, 0, 0, 0, "minecraft:dirt");

		assertEquals(TargetMatchResult.NO_MATCH, matcher.evaluate(dirt, TargetMatchContext.none()));
	}

	@Test
	void evaluateReportsInactiveWhenNoReferencePresent() {
		RegistryBackedTargetMatcher matcher = blockMatcher(
			lookup(), "minecraft:modded_block_a", "minecraft:modded_block_b");

		Target.BlockTarget block = new Target.BlockTarget(OVERWORLD, 0, 0, 0, "minecraft:modded_block_a");

		assertEquals(TargetMatchResult.INACTIVE, matcher.evaluate(block, TargetMatchContext.none()));
	}

	@Test
	void rejectsEmptyReferenceList() {
		assertThrows(IllegalArgumentException.class,
			() -> new RegistryBackedTargetMatcher(TargetKind.BLOCK, List.of(), lookup()));
	}

	@Test
	void rejectsNullReferences() {
		assertThrows(NullPointerException.class,
			() -> new RegistryBackedTargetMatcher(TargetKind.BLOCK, null, lookup()));
	}

	@Test
	void rejectsNullLookup() {
		List<OptionalRegistryRef> refs = List.of(new OptionalRegistryRef(BLOCK_REGISTRY, "minecraft:stone"));

		assertThrows(NullPointerException.class,
			() -> new RegistryBackedTargetMatcher(TargetKind.BLOCK, refs, null));
	}
}
