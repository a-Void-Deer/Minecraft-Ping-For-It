package nx.pingwheel.common.interaction.candidate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.EntityLocalGeometryMetadata;
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
import nx.pingwheel.common.math.LocalGeometryKind;
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
	void incompleteScannedClassStaysUnavailableEvenWhenTheOrdinaryMatchesIt() {
		TargetSnapshot ordinary = block(1, false, BlockFace.WEST);
		FrozenCandidateSet set = finish(ordinary, List.of(entity(10, "minecraft:item", 1), block(3, true, BlockFace.UP)),
			EnumSet.noneOf(PreciseTargetType.class));
		for (PreciseTargetType type : List.of(PreciseTargetType.DROPPED_ITEM, PreciseTargetType.ENTITY,
			PreciseTargetType.ENTITY_BLOCK, PreciseTargetType.BLOCK)) {
			assertEquals(PreciseSlot.Availability.INCOMPLETE, set.slot(type).availability(), type.name());
			assertTrue(set.slot(type).candidateId().isEmpty(), type.name());
		}
		assertEquals(PreciseSlot.Availability.AVAILABLE, set.slot(PreciseTargetType.LOCATION).availability());
		assertEquals(ordinary.target(), set.ordinary().resolvedTarget().target());
		assertEquals(2, set.candidates().size(), "an incomplete scan retains only the ordinary and the derived location");
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
	void nearestSameCategoryCandidateWinsAndOnlyOneSupplementIsInstalledPerConcreteType() {
		TargetSnapshot ordinary = entity(10, "minecraft:cow", 1);
		List<TargetSnapshot> alternatives = new ArrayList<>();
		alternatives.add(entity(2, "minecraft:item", 2));
		TargetSnapshot nearerCow = entity(1, "minecraft:cow", 3);
		alternatives.add(nearerCow);
		for (int i = 3; i < 30; i++) alternatives.add(block(i, i % 2 == 0, BlockFace.WEST));
		FrozenCandidateSet set = finish(ordinary, alternatives, all());
		assertEquals(nearerCow.target(), selected(set, PreciseTargetType.ENTITY).resolvedTarget().target(),
			"a nearer same-category supplement wins over the farther ordinary");
		assertEquals(entity(2, "minecraft:item", 2).target(),
			selected(set, PreciseTargetType.DROPPED_ITEM).resolvedTarget().target());
		assertEquals(ordinary.target(), set.ordinary().resolvedTarget().target());
		assertEquals(6, set.candidates().size());
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
	void nearerSupplementBeatsFartherOrdinaryInTheSameCategory() {
		TargetSnapshot ordinary = entity(5, "minecraft:item", 1);
		TargetSnapshot nearerItem = entity(2, "minecraft:item", 2);
		FrozenCandidateSet set = finish(ordinary, List.of(nearerItem), all());
		Candidate specific = selected(set, PreciseTargetType.DROPPED_ITEM);
		assertEquals(nearerItem.target(), specific.resolvedTarget().target());
		assertEquals(2, specific.distance());
		Candidate generic = selected(set, PreciseTargetType.ENTITY);
		assertEquals(ordinary.target(), generic.resolvedTarget().target(),
			"the specific class consumed the nearer identity, so the generic class keeps the ordinary");
		assertEquals(5, generic.distance());
	}

	@Test
	void nearerOrdinaryBeatsFartherSupplementInTheSameCategory() {
		TargetSnapshot ordinary = entity(2, "minecraft:item", 1);
		TargetSnapshot fartherItem = entity(5, "minecraft:item", 2);
		FrozenCandidateSet set = finish(ordinary, List.of(fartherItem), all());
		Candidate specific = selected(set, PreciseTargetType.DROPPED_ITEM);
		assertEquals(ordinary.target(), specific.resolvedTarget().target());
		assertEquals(2, specific.distance());
		Candidate generic = selected(set, PreciseTargetType.ENTITY);
		assertEquals(fartherItem.target(), generic.resolvedTarget().target());
		assertEquals(5, generic.distance());
	}

	@Test
	void equalDistanceKeepsTheOrdinaryBeforeAnEquivalentSupplement() {
		TargetSnapshot ordinary = block(2, false, BlockFace.WEST);
		TargetSnapshot tied = blockAt(0, 0, 2, false, BlockFace.NORTH);
		FrozenCandidateSet set = finish(ordinary, List.of(tied), all());
		Candidate selected = selected(set, PreciseTargetType.BLOCK);
		assertEquals(ordinary.target(), selected.resolvedTarget().target());
		assertEquals(2, selected.distance());
		assertEquals(2, set.candidates().size(), "an exact tie installs neither the supplement nor any other candidate");
	}

	@Test
	void eachCertifiedClassScansIndependentlyAndTheOrdinaryCannotBypassAnIncompleteClass() {
		TargetSnapshot ordinary = block(5, true, BlockFace.WEST);
		TargetSnapshot item = entity(2, "minecraft:item", 1);
		FrozenCandidateSet set = finish(ordinary, List.of(item),
			EnumSet.of(PreciseTargetType.DROPPED_ITEM, PreciseTargetType.BLOCK));
		assertEquals(item.target(), selected(set, PreciseTargetType.DROPPED_ITEM).resolvedTarget().target());
		assertEquals(PreciseSlot.Availability.INCOMPLETE, set.slot(PreciseTargetType.ENTITY).availability());
		assertEquals(PreciseSlot.Availability.INCOMPLETE, set.slot(PreciseTargetType.ENTITY_BLOCK).availability(),
			"an incomplete more specific class disables its leaf even though the ordinary chest would match it");
		assertTrue(set.slot(PreciseTargetType.ENTITY_BLOCK).candidateId().isEmpty());
		assertEquals(ordinary.target(), selected(set, PreciseTargetType.BLOCK).resolvedTarget().target());
		assertEquals(PreciseSlot.Availability.AVAILABLE, set.slot(PreciseTargetType.LOCATION).availability());
	}

	@Test
	void specificClassConsumesItsIdentityBeforeTheGenericClassInstallsTheNextNearest() {
		TargetSnapshot ordinary = blockAt(5, 0, 0, true, BlockFace.WEST);
		TargetSnapshot nearerChest = blockAt(2, 0, 0, true, BlockFace.EAST);
		TargetSnapshot stone = blockAt(3, 0, 0, false, BlockFace.NORTH);
		FrozenCandidateSet set = finish(ordinary, List.of(stone, nearerChest), all());
		Candidate specific = selected(set, PreciseTargetType.ENTITY_BLOCK);
		assertEquals(nearerChest.target(), specific.resolvedTarget().target());
		assertEquals(2, specific.distance());
		Candidate generic = selected(set, PreciseTargetType.BLOCK);
		assertEquals(stone.target(), generic.resolvedTarget().target());
		assertEquals(3, generic.distance(),
			"the generic class skips the consumed identity and installs the next nearest, not the farther ordinary");
	}

	@Test
	void sameIdentityNearerContactInstallsThatContactsOwnFaceInsteadOfTheOrdinaryFace() {
		TargetSnapshot base = TargetSnapshotFactory.block(DIMENSION, 5, 0, 0, "minecraft:chest", true);
		CaptureEquivalenceKey key = CaptureEquivalenceKey.nativeTarget(base.target());
		TargetSnapshot ordinary = TargetSnapshotFactory.block(DIMENSION, 5, 0, 0, "minecraft:chest", true, BlockFace.WEST)
			.withCandidateHit(new CandidateHit(new WorldVector(5.5, 0, 0), key));
		TargetSnapshot nearerContact = TargetSnapshotFactory.block(DIMENSION, 5, 0, 0, "minecraft:chest", true, BlockFace.NORTH)
			.withCandidateHit(new CandidateHit(new WorldVector(5.25, 0, 0), key));
		FrozenCandidateSet set = acquisition(List.of(evidence(nearerContact)), all())
			.finish(ordinary, resolve(ordinary), RESOLVER);
		assertEquals(new WorldVector(5.25, 0, 0), set.ordinary().worldHit());
		assertEquals(5.25, set.ordinary().distance());
		assertEquals(Optional.of(BlockFace.NORTH), set.ordinary().blockHitFace(),
			"the installed face must come from the selected nearer contact, not the farther ordinary hit");
		assertEquals(resolve(ordinary).target(), set.ordinary().resolvedTarget().target());
	}

	@Test
	void sameIdentityNearerContactInstallsThatContactsOwnLocalGeometryMetadataConsistentWithItsPoint() {
		TargetSnapshot ordinary = entityWithMetadata(5, new UUID(0, 1), "test:ordinary");
		TargetSnapshot nearerContact = entityWithMetadata(2, new UUID(0, 1), "test:near");
		FrozenCandidateSet set = acquisition(List.of(evidence(nearerContact)), all())
			.finish(ordinary, resolve(ordinary), RESOLVER);
		assertEquals(new WorldVector(2, 0, 0), set.ordinary().worldHit());
		assertEquals(2, set.ordinary().distance());
		EntityLocalGeometryMetadata installed = set.ordinary().entityLocalGeometryMetadata().orElseThrow();
		assertEquals("test:near", installed.sourceId(),
			"the installed metadata must be the selected contact's own, not the farther ordinary detail");
		assertEquals(new WorldVector(2, 0, 0), installed.worldWorldVector());
		assertEquals(resolve(ordinary).target(), set.ordinary().resolvedTarget().target());
		assertEquals(2, selected(set, PreciseTargetType.ENTITY).distance());
		assertEquals(2, set.candidates().size(), "the same identity is never installed twice");
	}

	@Test
	void sameIdentityEvidenceAtEqualDistanceKeepsTheOrdinaryContactAndProvenance() {
		TargetSnapshot ordinary = entityWithMetadata(5, new UUID(0, 1), "test:ordinary");
		TargetSnapshot equal = entityWithMetadata(5, new UUID(0, 1), "test:equal");
		FrozenCandidateSet set = acquisition(List.of(evidence(equal)), all())
			.finish(ordinary, resolve(ordinary), RESOLVER);
		assertEquals(new WorldVector(5, 0, 0), set.ordinary().worldHit());
		assertEquals("test:ordinary", set.ordinary().entityLocalGeometryMetadata().orElseThrow().sourceId());
		assertEquals(2, set.candidates().size());
	}

	@Test
	void fartherSameIdentityEvidenceIsNotInstalledAndCannotDuplicateTheOrdinary() {
		TargetSnapshot ordinary = entityWithMetadata(5, new UUID(0, 1), "test:ordinary");
		TargetSnapshot farther = entityWithMetadata(9, new UUID(0, 1), "test:farther");
		FrozenCandidateSet set = acquisition(List.of(evidence(farther)), all())
			.finish(ordinary, resolve(ordinary), RESOLVER);
		assertEquals(new WorldVector(5, 0, 0), set.ordinary().worldHit());
		assertEquals(5, set.ordinary().distance());
		assertEquals("test:ordinary", set.ordinary().entityLocalGeometryMetadata().orElseThrow().sourceId());
		assertEquals(ordinary.target(), selected(set, PreciseTargetType.ENTITY).resolvedTarget().target());
		assertEquals(2, set.candidates().size());
	}

	@Test
	void missingAndIncompleteStayDistinctForTheSameEmptyEvidence() {
		TargetSnapshot ordinary = block(2, false, BlockFace.NORTH);
		FrozenCandidateSet complete = finish(ordinary, List.of(), all());
		assertEquals(PreciseSlot.Availability.MISSING, complete.slot(PreciseTargetType.DROPPED_ITEM).availability());
		FrozenCandidateSet truncated = finish(ordinary, List.of(), EnumSet.noneOf(PreciseTargetType.class));
		assertEquals(PreciseSlot.Availability.INCOMPLETE, truncated.slot(PreciseTargetType.DROPPED_ITEM).availability());
		assertTrue(truncated.slot(PreciseTargetType.DROPPED_ITEM).candidateId().isEmpty());
	}

	@Test
	void boundedCollectorMatchesAnIndependentExhaustiveNearestAllocationAcrossMixedIdentityOrders() {
		java.util.Random random = new java.util.Random(4182);
		for (int attempt = 0; attempt < 80; attempt++) {
			List<TargetSnapshot> pool = new ArrayList<>();
			for (int i = 1; i < 25; i++) {
				int distance = random.nextInt(20) + 1;
				pool.add(i % 2 == 0 ? entity(distance, i % 4 == 0 ? "minecraft:item" : "minecraft:cow", i)
					: block(distance, i % 3 == 0, BlockFace.WEST));
			}
			// The bounded collector may re-contact an identity nearer with its own provenance.
			// The independent expectation below is derived from this original pool only, never
			// from the collector reduction, so a dropped winner cannot hide behind it.
			if (random.nextBoolean()) {
				TargetSnapshot original = pool.get(random.nextInt(pool.size()));
				pool.add(recontact(original, Math.max(0.5, evidence(original).distance() - 1)));
			}
			java.util.Collections.shuffle(pool, random);
			TargetSnapshot ordinary = pool.get(random.nextInt(pool.size()));
			EnumSet<PreciseTargetType> certified = EnumSet.noneOf(PreciseTargetType.class);
			for (PreciseTargetType type : List.of(PreciseTargetType.DROPPED_ITEM, PreciseTargetType.ENTITY,
				PreciseTargetType.ENTITY_BLOCK, PreciseTargetType.BLOCK)) {
				if (random.nextBoolean()) certified.add(type);
			}
			List<CandidateEvidence> bounded = orderedEvidence(pool);
			FrozenCandidateSet actual = acquisition(bounded, certified).finish(ordinary, resolve(ordinary), RESOLVER);
			java.util.Map<CaptureEquivalenceKey, Contact> contacts = canonicalContacts(pool);
			CaptureEquivalenceKey ordinaryKey = ordinary.candidateHit().orElseThrow().equivalenceKey();
			Contact ordinaryContact = contacts.get(ordinaryKey);
			assertNotNull(ordinaryContact, "attempt=" + attempt);
			// A non-winning ordinary identity need not survive the nearest-two reduction.
			// Independently sort the original contacts to determine whether its nearer
			// contact can reach finalization; do not derive this expectation from bounded.
			if (!retainedByNearestTwo(ordinaryContact, contacts.values())
				|| ordinaryContact.distance() >= evidence(ordinary).distance()) {
				CandidateEvidence original = evidence(ordinary);
				ordinaryContact = new Contact(ordinaryKey, resolve(ordinary), original.distance(),
					original.hit().worldHit(), ordinary.entityLocalGeometryMetadata(), ordinary.blockHitFace(), -1);
			}
			java.util.Set<CaptureEquivalenceKey> consumed = new java.util.HashSet<>();
			for (PreciseTargetType type : List.of(PreciseTargetType.DROPPED_ITEM, PreciseTargetType.ENTITY,
				PreciseTargetType.ENTITY_BLOCK, PreciseTargetType.BLOCK)) {
				if (!certified.contains(type)) {
					assertTrue(actual.slot(type).candidateId().isEmpty(), "attempt=" + attempt + " type=" + type);
					assertEquals(PreciseSlot.Availability.INCOMPLETE, actual.slot(type).availability(),
						"attempt=" + attempt + " type=" + type);
					continue;
				}
				Contact expected = type.matches(ordinaryContact.resolved()) && !consumed.contains(ordinaryKey)
					? ordinaryContact : null;
				Contact nearestSupplement = null;
				for (Contact contact : contacts.values()) {
					if (contact.key().equals(ordinaryKey) || !type.matches(contact.resolved())
						|| consumed.contains(contact.key())) continue;
					if (nearestSupplement == null || contact.distance() < nearestSupplement.distance()
						|| (contact.distance() == nearestSupplement.distance()
							&& contact.encounter() < nearestSupplement.encounter())) {
						nearestSupplement = contact;
					}
				}
				if (nearestSupplement != null && (expected == null || nearestSupplement.distance() < expected.distance())) {
					expected = nearestSupplement;
				}
				if (expected == null) {
					assertTrue(actual.slot(type).candidateId().isEmpty(), "attempt=" + attempt + " type=" + type);
					continue;
				}
				Candidate installed = selected(actual, type);
				assertEquals(expected.key(), installed.equivalenceKey(), "attempt=" + attempt + " type=" + type);
				assertEquals(expected.distance(), installed.distance(), 1.0E-12, "attempt=" + attempt + " type=" + type);
				assertEquals(expected.worldHit(), installed.worldHit(), "attempt=" + attempt + " type=" + type);
				assertEquals(expected.metadata(), installed.entityLocalGeometryMetadata(),
					"attempt=" + attempt + " type=" + type);
				assertEquals(expected.face(), installed.blockHitFace(), "attempt=" + attempt + " type=" + type);
				consumed.add(expected.key());
			}
			assertEquals(ordinaryContact.distance(), actual.ordinary().distance(), 1.0E-12, "attempt=" + attempt);
			assertEquals(ordinaryContact.worldHit(), actual.ordinary().worldHit(), "attempt=" + attempt);
			assertEquals(ordinaryContact.metadata(), actual.ordinary().entityLocalGeometryMetadata(), "attempt=" + attempt);
			assertEquals(ordinaryContact.face(), actual.ordinary().blockHitFace(), "attempt=" + attempt);
			assertEquals(ordinaryContact.resolved().target(), actual.ordinary().resolvedTarget().target(),
				"attempt=" + attempt);
		}
	}

	private static EnumSet<PreciseTargetType> all() { return EnumSet.allOf(PreciseTargetType.class); }
	private static FrozenCandidateAcquisition acquisition(List<CandidateEvidence> values, java.util.Set<PreciseTargetType> certified) {
		return new FrozenCandidateAcquisition(new ActiveInteraction().begin(), RAY, 100, new WorldVector(20, 0, 0), values, certified);
	}
	private static List<CandidateEvidence> orderedEvidence(List<TargetSnapshot> values) {
		CandidateCollector collector = new CandidateCollector();
		values.forEach(snapshot -> collector.add(evidence(snapshot)));
		return collector.evidence();
	}
	private static FrozenCandidateSet finish(TargetSnapshot ordinary, List<TargetSnapshot> values, java.util.Set<PreciseTargetType> certified) {
		return acquisition(orderedEvidence(values), certified).finish(ordinary, resolve(ordinary), RESOLVER);
	}
	private static Candidate selected(FrozenCandidateSet set, PreciseTargetType type) {
		return set.candidate(set.slot(type).candidateId().orElseThrow()).orElseThrow();
	}
	private static ResolvedTarget resolve(TargetSnapshot snapshot) { return RESOLVER.resolve(snapshot.target(), snapshot.matchContext()); }
	private static CandidateEvidence evidence(TargetSnapshot snapshot) {
		return new CandidateEvidence(snapshot, FrozenCandidateAcquisition.distance(RAY.origin(), snapshot.candidateHit().orElseThrow().worldHit()));
	}
	private record Contact(CaptureEquivalenceKey key, ResolvedTarget resolved, double distance,
		WorldVector worldHit, Optional<EntityLocalGeometryMetadata> metadata, Optional<BlockFace> face, int encounter) {}
	private static java.util.Map<CaptureEquivalenceKey, Contact> canonicalContacts(List<TargetSnapshot> pool) {
		java.util.Map<CaptureEquivalenceKey, Contact> contacts = new java.util.LinkedHashMap<>();
		for (int index = 0; index < pool.size(); index++) {
			TargetSnapshot snapshot = pool.get(index);
			CandidateEvidence value = evidence(snapshot);
			Contact existing = contacts.get(value.hit().equivalenceKey());
			if (existing == null || value.distance() < existing.distance()) {
				contacts.put(value.hit().equivalenceKey(), new Contact(value.hit().equivalenceKey(),
					resolve(snapshot), value.distance(), value.hit().worldHit(),
					snapshot.entityLocalGeometryMetadata(), snapshot.blockHitFace(), index));
			}
		}
		return contacts;
	}
	private static boolean retainedByNearestTwo(Contact ordinary, java.util.Collection<Contact> contacts) {
		for (PreciseTargetType type : List.of(PreciseTargetType.DROPPED_ITEM, PreciseTargetType.ENTITY,
			PreciseTargetType.ENTITY_BLOCK, PreciseTargetType.BLOCK)) {
			boolean retained = contacts.stream().filter(contact -> type.matches(contact.resolved()))
				.sorted(java.util.Comparator.comparingDouble(Contact::distance).thenComparingInt(Contact::encounter))
				.limit(2).anyMatch(contact -> contact.key().equals(ordinary.key()));
			if (retained) return true;
		}
		return false;
	}
	private static TargetSnapshot recontact(TargetSnapshot original, double nearer) {
		CandidateHit hit = new CandidateHit(new WorldVector(nearer, 0, 0),
			original.candidateHit().orElseThrow().equivalenceKey());
		if (original.target() instanceof Target.BlockTarget block) {
			return TargetSnapshotFactory.block(block.dimensionId(), block.x(), block.y(), block.z(),
				block.blockRegistryId(), original.matchContext().blockHasBlockEntity().orElse(false), BlockFace.NORTH)
				.withCandidateHit(hit);
		}
		Target.EntityTarget entity = (Target.EntityTarget) original.target();
		EntityLocalGeometryMetadata metadata = new EntityLocalGeometryMetadata("test:recontact", LocalGeometryKind.BLOCK,
			0, 0, 0, "minecraft:stone", Optional.empty(), new WorldVector(0.5, 0, 0.5), new WorldVector(nearer, 0, 0));
		return new TargetSnapshot(entity, original.matchContext(), Optional.empty(), Optional.of(metadata),
			Optional.empty(), Optional.of(hit));
	}
	private static TargetSnapshot entityWithMetadata(int distance, UUID id, String sourceId) {
		TargetSnapshot base = TargetSnapshotFactory.entity(DIMENSION, id, "minecraft:cow");
		EntityLocalGeometryMetadata metadata = new EntityLocalGeometryMetadata(sourceId, LocalGeometryKind.BLOCK,
			0, 0, 0, "minecraft:stone", Optional.empty(), new WorldVector(0.5, 0, 0.5), new WorldVector(distance, 0, 0));
		return new TargetSnapshot(base.target(), base.matchContext(), Optional.empty(), Optional.of(metadata),
			Optional.empty(), Optional.of(new CandidateHit(new WorldVector(distance, 0, 0),
				CaptureEquivalenceKey.nativeTarget(base.target()))));
	}
	private static TargetSnapshot block(int x, boolean blockEntity, BlockFace face) {
		return blockAt(x, 0, 0, blockEntity, face);
	}
	private static TargetSnapshot blockAt(int x, int y, int z, boolean blockEntity, BlockFace face) {
		TargetSnapshot snapshot = TargetSnapshotFactory.block(DIMENSION, x, y, z, blockEntity ? "minecraft:chest" : "minecraft:stone", blockEntity, face);
		return snapshot.withCandidateHit(new CandidateHit(new WorldVector(x, y, z), CaptureEquivalenceKey.nativeTarget(snapshot.target())));
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
