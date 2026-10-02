package nx.pingwheel.common.interaction.candidate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetResolver;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.integration.sable.client.SableCaptureEquivalence;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.*;

class FrozenCandidateAcquisitionTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final CapturedRay RAY = new CapturedRay(new WorldVector(0, 0, 0), new WorldVector(1, 0, 0));
	private static final TargetResolver RESOLVER = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());

	@Test
	void allocationUsesPrioritySkipsConsumedAndKeepsActualClassAndEachFace() {
		TargetSnapshot ordinary = block(1, true, BlockFace.WEST);
		TargetSnapshot nearerItem = entity(2, "minecraft:item", 1);
		TargetSnapshot secondItem = entity(3, "minecraft:item", 2);
		TargetSnapshot fartherCow = entity(4, "minecraft:cow", 3);
		TargetSnapshot chestBehind = block(5, true, BlockFace.EAST);
		TargetSnapshot fartherBlock = block(7, false, BlockFace.NORTH);
		FrozenCandidateSet set = finish(ordinary, List.of(fartherCow, fartherBlock, secondItem, chestBehind, nearerItem), all());

		assertEquals(nearerItem.target(), selected(set, PreciseTargetType.DROPPED_ITEM).resolvedTarget().target());
		Candidate broadEntity = selected(set, PreciseTargetType.ENTITY);
		assertEquals(secondItem.target(), broadEntity.resolvedTarget().target());
		assertEquals("dropped_item", broadEntity.resolvedTarget().targetType().id());
		assertEquals(ordinary.target(), selected(set, PreciseTargetType.ENTITY_BLOCK).resolvedTarget().target());
		Candidate broadBlock = selected(set, PreciseTargetType.BLOCK);
		assertEquals(chestBehind.target(), broadBlock.resolvedTarget().target());
		assertEquals("entity_block", broadBlock.resolvedTarget().targetType().id());
		assertEquals(Optional.of(BlockFace.WEST), set.ordinary().blockHitFace());
		assertEquals(Optional.of(BlockFace.EAST), broadBlock.blockHitFace());
		assertEquals(new Target.LocationTarget(DIMENSION, 1, 0, 0), selected(set, PreciseTargetType.LOCATION).resolvedTarget().target());
		assertEquals(5, set.candidates().size());
	}

	@Test
	void boundedReductionRetainsSecondDistinctIdentityEvenAfterManyDuplicates() {
		TargetSnapshot first = entity(2, "minecraft:item", 1);
		TargetSnapshot second = entity(3, "minecraft:item", 2);
		CandidateCollector collector = new CandidateCollector();
		for (int i = 0; i < 100; i++) collector.add(evidence(first));
		collector.add(evidence(second));
		for (int i = 4; i < 100; i++) collector.add(evidence(entity(i, "minecraft:cow", i)));
		assertEquals(2, collector.evidence().size());
		FrozenCandidateSet set = finish(block(1, false, BlockFace.WEST),
			collector.evidence().stream().map(CandidateEvidence::snapshot).toList(), all());
		assertEquals(second.target(), selected(set, PreciseTargetType.ENTITY).resolvedTarget().target());
	}

	@Test
	void equalDistanceGenericTiesKeepEncounterOrderNotSpecializedBucketOrder() {
		TargetSnapshot cow = entity(3, "minecraft:cow", 1);
		TargetSnapshot tiedItem = entity(3, "minecraft:item", 2);
		TargetSnapshot nearerItem = entity(1, "minecraft:item", 3);
		FrozenCandidateSet set = finish(block(2, false, BlockFace.NORTH), List.of(cow, tiedItem, nearerItem), all());
		assertEquals(nearerItem.target(), selected(set, PreciseTargetType.DROPPED_ITEM).resolvedTarget().target());
		assertEquals(cow.target(), selected(set, PreciseTargetType.ENTITY).resolvedTarget().target());
	}

	@Test
	void truncatedUnorderedPrefixCannotPublishItsProvisionalNearestButOrdinaryRemains() {
		TargetSnapshot ordinary = block(1, false, BlockFace.WEST);
		FrozenCandidateSet set = finish(ordinary, List.of(entity(10, "minecraft:item", 1), block(3, true, BlockFace.UP)),
			EnumSet.noneOf(PreciseTargetType.class));
		assertEquals(PreciseSlot.Availability.INCOMPLETE, set.slot(PreciseTargetType.DROPPED_ITEM).availability());
		assertEquals(PreciseSlot.Availability.INCOMPLETE, set.slot(PreciseTargetType.ENTITY_BLOCK).availability());
		assertEquals(ordinary.target(), selected(set, PreciseTargetType.BLOCK).resolvedTarget().target());
		assertEquals(2, set.candidates().size());
	}

	@Test
	void completeNoHitIsMissingNotIncompleteAndNoNullCandidateIsInvented() {
		FrozenCandidateSet set = finish(block(2, false, BlockFace.NORTH), List.of(), all());
		assertEquals(PreciseSlot.Availability.MISSING, set.slot(PreciseTargetType.DROPPED_ITEM).availability());
		assertEquals(PreciseSlot.Availability.MISSING, set.slot(PreciseTargetType.ENTITY).availability());
		assertEquals(PreciseSlot.Availability.MISSING, set.slot(PreciseTargetType.ENTITY_BLOCK).availability());
		assertTrue(set.slot(PreciseTargetType.ENTITY).candidateId().isEmpty());
	}

	@Test
	void ordinaryIsPreferredForItsSlotAndOnlyOneSupplementIsInstalledPerConcreteType() {
		TargetSnapshot ordinary = entity(10, "minecraft:cow", 1);
		List<TargetSnapshot> alternatives = new ArrayList<>();
		alternatives.add(entity(2, "minecraft:item", 2));
		alternatives.add(entity(1, "minecraft:cow", 3));
		for (int i = 3; i < 30; i++) alternatives.add(block(i, i % 2 == 0, BlockFace.WEST));
		FrozenCandidateSet set = finish(ordinary, alternatives, all());
		assertEquals(ordinary.target(), selected(set, PreciseTargetType.ENTITY).resolvedTarget().target());
		assertEquals(5, set.candidates().size());
		assertTrue(set.candidates().size() <= FrozenCandidateSet.MAX_CANDIDATES);
		assertEquals(5, set.preciseSlots().stream().map(slot -> slot.candidateId().orElseThrow()).distinct().count());
	}

	@Test
	void locationUsesExactSurfaceNotBlockCenterSupplementOrEntityAnchor() {
		TargetSnapshot ordinary = TargetSnapshotFactory.block(DIMENSION, 12, 5, 9, "minecraft:stone", false, BlockFace.UP);
		WorldVector actual = new WorldVector(12.25, 6, 9.5);
		ordinary = ordinary.withCandidateHit(new CandidateHit(actual, CaptureEquivalenceKey.nativeTarget(ordinary.target())));
		FrozenCandidateSet set = finish(ordinary, List.of(entity(2, "minecraft:item", 1)), all());
		Candidate location = selected(set, PreciseTargetType.LOCATION);
		assertEquals(actual, location.worldHit());
		assertEquals(new Target.LocationTarget(DIMENSION, 12.25, 6, 9.5), location.resolvedTarget().target());
		assertTrue(location.blockHitFace().isEmpty());
	}

	@Test
	void missAndLateDhPointFinalizeWithoutReadingOrRecollectingSupplements() {
		var acquisition = acquisition(List.of(evidence(entity(2, "minecraft:item", 1))), all());
		TargetSnapshot nativeMiss = TargetSnapshotFactory.location(DIMENSION, 20, 0, 0);
		assertEquals(nativeMiss.target(), acquisition.finish(nativeMiss, resolve(nativeMiss), RESOLVER).ordinary().resolvedTarget().target());
		TargetSnapshot dhHit = TargetSnapshotFactory.block(DIMENSION, 1000, 0, 0, "minecraft:stone", false, BlockFace.WEST);
		dhHit = dhHit.withCandidateHit(new CandidateHit(new WorldVector(1000, 0, 0), CaptureEquivalenceKey.nativeTarget(dhHit.target())));
		FrozenCandidateSet set = acquisition.finish(dhHit, resolve(dhHit), RESOLVER);
		assertEquals(1000, selected(set, PreciseTargetType.LOCATION).distance());
		assertEquals(entity(2, "minecraft:item", 1).target(), selected(set, PreciseTargetType.DROPPED_ITEM).resolvedTarget().target());
	}

	@Test
	void providerEquivalenceDoesNotCollapseDistinctEmptyStableIdsOrParseGenericLocators() {
		UUID sublevel = UUID.randomUUID();
		TargetSnapshot first = external(sublevel, 1, "opaque-a");
		TargetSnapshot second = external(sublevel, 2, "opaque-b");
		assertEquals(first.target(), second.target(), "domain equality intentionally ignores uncommitted locator");
		assertNotEquals(first.candidateHit().orElseThrow().equivalenceKey(), second.candidateHit().orElseThrow().equivalenceKey());
		FrozenCandidateSet set = finish(first, List.of(second), all());
		assertEquals("opaque-b", ((Target.ExternalBlockTarget) selected(set, PreciseTargetType.BLOCK).resolvedTarget().target()).providerLocator());
		assertEquals("", ((Target.ExternalBlockTarget) set.ordinary().resolvedTarget().target()).stableTargetId());
		assertTrue(set.ordinary().blockHitFace().isEmpty());
		assertThrows(IllegalArgumentException.class, () -> CaptureEquivalenceKey.nativeTarget(first.target()));
		CaptureEquivalenceKey firstKey = first.candidateHit().orElseThrow().equivalenceKey();
		assertEquals(firstKey, SableCaptureEquivalence.fromResolved(DIMENSION, sublevel, 1, 0, 0, "minecraft:chest"));
		assertNotEquals(firstKey, SableCaptureEquivalence.fromResolved(DIMENSION, UUID.randomUUID(), 1, 0, 0, "minecraft:chest"));
		assertNotEquals(firstKey, SableCaptureEquivalence.fromResolved(DIMENSION, sublevel, 1, 0, 0, "minecraft:stone"));
		assertNotEquals(firstKey, SableCaptureEquivalence.fromResolved("minecraft:the_nether", sublevel, 1, 0, 0, "minecraft:chest"));
	}

	@Test
	void taggedEntityLocatorsKeepRuntimeXpSeparateFromUuidAndDimensions() {
		var uuid = new Target.EntityTarget(DIMENSION, EntityLocator.uuid(new UUID(0, 7)));
		var xp = new Target.EntityTarget(DIMENSION, EntityLocator.runtimeId(7));
		assertNotEquals(CaptureEquivalenceKey.nativeTarget(uuid), CaptureEquivalenceKey.nativeTarget(xp));
		assertNotEquals(CaptureEquivalenceKey.nativeTarget(xp),
			CaptureEquivalenceKey.nativeTarget(new Target.EntityTarget("minecraft:the_nether", xp.locator())));
	}

	@Test
	void immutableAcquisitionSetAndBoundsRejectInconsistentDistances() {
		List<CandidateEvidence> values = new ArrayList<>(List.of(evidence(block(3, false, BlockFace.WEST))));
		EnumSet<PreciseTargetType> certified = all();
		var acquisition = acquisition(values, certified);
		values.clear();
		certified.clear();
		assertEquals(1, acquisition.evidence().size());
		assertTrue(acquisition.certifiedTypes().contains(PreciseTargetType.BLOCK));
		assertThrows(UnsupportedOperationException.class, () -> acquisition.evidence().clear());
		assertThrows(IllegalArgumentException.class, () -> acquisition(List.of(evidence(block(101, false, BlockFace.UP))), all()));
		assertThrows(IllegalArgumentException.class, () -> acquisition(List.of(new CandidateEvidence(block(3, false, BlockFace.UP), 2)), all()));
		FrozenCandidateSet set = finish(block(2, false, BlockFace.UP), List.of(), all());
		assertThrows(UnsupportedOperationException.class, () -> set.candidates().clear());
		assertThrows(UnsupportedOperationException.class, () -> set.preciseSlots().clear());
	}

	@Test
	void injectedWorkBudgetsBoundEachWorkClassIndependently() {
		var budget = new CandidateWorkBudget(new CandidateWorkLimits(1, 2, 0));
		assertTrue(budget.visitBlock());
		assertFalse(budget.visitBlock());
		assertTrue(budget.visitEntity());
		assertTrue(budget.visitEntity());
		assertFalse(budget.visitEntity());
		assertFalse(budget.callProvider());
	}

	@Test
	void boundedCollectorMatchesAnIndependentExhaustiveAllocationAcrossMixedIdentityOrders() {
		java.util.Random random = new java.util.Random(4182);
		for (int attempt = 0; attempt < 80; attempt++) {
			List<TargetSnapshot> pool = new ArrayList<>();
			for (int i = 1; i < 25; i++) {
				int distance = random.nextInt(20) + 1;
				pool.add(i % 2 == 0 ? entity(distance, i % 4 == 0 ? "minecraft:item" : "minecraft:cow", i)
					: block(distance, i % 3 == 0, BlockFace.WEST));
			}
			java.util.Collections.shuffle(pool, random);
			TargetSnapshot ordinary = pool.get(random.nextInt(pool.size()));
			FrozenCandidateSet actual = finish(ordinary, pool, all());
			java.util.Set<CaptureEquivalenceKey> consumed = new java.util.HashSet<>();
			for (PreciseTargetType type : List.of(PreciseTargetType.DROPPED_ITEM, PreciseTargetType.ENTITY,
				PreciseTargetType.ENTITY_BLOCK, PreciseTargetType.BLOCK)) {
				TargetSnapshot expected = null;
				if (type.matches(resolve(ordinary)) && !consumed.contains(ordinary.candidateHit().orElseThrow().equivalenceKey())) {
					expected = ordinary;
				} else {
					for (TargetSnapshot snapshot : pool) {
						if (!type.matches(resolve(snapshot)) || consumed.contains(snapshot.candidateHit().orElseThrow().equivalenceKey())) continue;
						if (expected == null || evidence(snapshot).distance() < evidence(expected).distance()) expected = snapshot;
					}
				}
				if (expected == null) {
					assertTrue(actual.slot(type).candidateId().isEmpty());
				} else {
					CaptureEquivalenceKey expectedKey = expected.candidateHit().orElseThrow().equivalenceKey();
					assertEquals(expectedKey, selected(actual, type).equivalenceKey(), "attempt=" + attempt + " type=" + type);
					consumed.add(expectedKey);
				}
			}
		}
	}

	private static EnumSet<PreciseTargetType> all() { return EnumSet.allOf(PreciseTargetType.class); }
	private static FrozenCandidateAcquisition acquisition(List<CandidateEvidence> values, java.util.Set<PreciseTargetType> certified) {
		return new FrozenCandidateAcquisition(new ActiveInteraction().begin(), RAY, 100, new WorldVector(20, 0, 0), values, certified);
	}
	private static FrozenCandidateSet finish(TargetSnapshot ordinary, List<TargetSnapshot> values, java.util.Set<PreciseTargetType> certified) {
		CandidateCollector collector = new CandidateCollector();
		values.forEach(snapshot -> collector.add(evidence(snapshot)));
		return acquisition(collector.evidence(), certified).finish(ordinary, resolve(ordinary), RESOLVER);
	}
	private static Candidate selected(FrozenCandidateSet set, PreciseTargetType type) {
		return set.candidate(set.slot(type).candidateId().orElseThrow()).orElseThrow();
	}
	private static ResolvedTarget resolve(TargetSnapshot snapshot) { return RESOLVER.resolve(snapshot.target(), snapshot.matchContext()); }
	private static CandidateEvidence evidence(TargetSnapshot snapshot) {
		return new CandidateEvidence(snapshot, FrozenCandidateAcquisition.distance(RAY.origin(), snapshot.candidateHit().orElseThrow().worldHit()));
	}
	private static TargetSnapshot block(int x, boolean blockEntity, BlockFace face) {
		TargetSnapshot snapshot = TargetSnapshotFactory.block(DIMENSION, x, 0, 0, blockEntity ? "minecraft:chest" : "minecraft:stone", blockEntity, face);
		return snapshot.withCandidateHit(new CandidateHit(new WorldVector(x, 0, 0), CaptureEquivalenceKey.nativeTarget(snapshot.target())));
	}
	private static TargetSnapshot entity(int distance, String type, int id) {
		TargetSnapshot snapshot = TargetSnapshotFactory.entity(DIMENSION, new UUID(0, id), type);
		return snapshot.withCandidateHit(new CandidateHit(new WorldVector(distance, 0, 0), CaptureEquivalenceKey.nativeTarget(snapshot.target())));
	}
	private static TargetSnapshot external(UUID sublevel, int x, String locator) {
		return TargetSnapshotFactory.externalBlockCandidate(DIMENSION, "sable", "minecraft:chest", locator, true)
			.withCandidateHit(new CandidateHit(new WorldVector(x, 0, 0),
				SableCaptureEquivalence.fromResolved(DIMENSION, sublevel, x, 0, 0, "minecraft:chest")));
	}
}
