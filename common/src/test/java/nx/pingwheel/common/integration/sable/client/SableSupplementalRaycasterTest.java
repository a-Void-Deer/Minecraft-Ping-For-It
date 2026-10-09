package nx.pingwheel.common.integration.sable.client;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.interaction.*;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.*;
import nx.pingwheel.common.math.RaycastPolicy;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.*;

/** Actual provider transform/scan core with real Companion poses/native shapes and recording plot ports. */
class SableSupplementalRaycasterTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final Vec3 START = new Vec3(0, .5, .5), END = new Vec3(20, .5, .5);
	private static final RaycastPolicy POLICY = RaycastPolicy.from(false, false, false);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test
	void translatedPlotFindsExternalChestBehindWorldBlockerWithoutWorldCoordinatesAsLocalIdentity() {
		var plot = plot(new Pose3d(new Vector3d(8, 0, 0), new Quaterniond(), new Vector3d(100, 0, 100), new Vector3d(1)), UUID.randomUUID());
		CandidateCollector collector = new CandidateCollector();
		assertTrue(scan(List.of(plot), START, END, POLICY, limits(), collector));
		CandidateEvidence chest = collector.evidence().getFirst();
		assertEquals(8.0625, chest.hit().worldHit().x(), 1.0E-10);
		assertEquals(.5, chest.hit().worldHit().z(), 1.0E-10);
		assertEquals(8.0625, chest.distance(), 1.0E-10);
		Target.ExternalBlockTarget target = (Target.ExternalBlockTarget) chest.snapshot().target();
		assertTrue(target.isCandidate());
		var locator = nx.pingwheel.common.integration.sable.server.SableExternalBlockLocator.parse(target.providerLocator()).orElseThrow();
		assertEquals(new BlockPos(100, 0, 100), locator.blockPos());
		assertEquals(plot.id(), locator.subLevelId());
		assertEquals(java.util.Optional.of(BlockFace.WEST), chest.snapshot().blockHitFace());
		var resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());
		TargetSnapshot ordinary = TargetSnapshotFactory.block(DIMENSION, 2, 0, 0, "minecraft:stone", false, BlockFace.WEST);
		ordinary = ordinary.withCandidateHit(new CandidateHit(new WorldVector(2, .5, .5), CaptureEquivalenceKey.nativeTarget(ordinary.target())));
		var ray = new CapturedRay(new WorldVector(0, .5, .5), new WorldVector(1, 0, 0));
		var set = new FrozenCandidateAcquisition(new ActiveInteraction().begin(), ray, 20, new WorldVector(2, .5, .5),
			collector.evidence(), EnumSet.allOf(PreciseTargetType.class)).finish(ordinary,
			resolver.resolve(ordinary.target(), ordinary.matchContext()), resolver);
		assertEquals(target, set.candidate(set.slot(PreciseTargetType.ENTITY_BLOCK).candidateId().orElseThrow()).orElseThrow().resolvedTarget().target());
		assertEquals(ordinary.target(), set.ordinary().resolvedTarget().target());
	}

	@Test
	void rotatedFrozenLogicalPoseFindsHitAfterNativeMissAndKeepsWorldDistance() {
		Pose3d pose = new Pose3d(new Vector3d(8, 0, 1), new Quaterniond().rotateY(Math.PI / 2),
			new Vector3d(100, 0, 100), new Vector3d(1));
		var plot = plot(pose, UUID.randomUUID());
		CandidateCollector collector = new CandidateCollector();
		assertTrue(scan(List.of(plot), START, END, POLICY, limits(), collector));
		CandidateEvidence hit = collector.evidence().getFirst();
		assertEquals(8.0625, hit.hit().worldHit().x(), 1.0E-10);
		assertEquals(.5, hit.hit().worldHit().z(), 1.0E-10);
		assertEquals(java.util.Optional.of(BlockFace.NORTH), hit.snapshot().blockHitFace(),
			"a world WEST approach hits provider-local NORTH after this rotation");
		pose.position().set(40, 30, 20);
		assertEquals(8.0625, hit.hit().worldHit().x(), 1.0E-10, "retained data is detached from mutable pose");
		TargetSnapshot miss = TargetSnapshotFactory.location(DIMENSION, 20, .5, .5);
		var resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());
		var set = new FrozenCandidateAcquisition(new ActiveInteraction().begin(),
			new CapturedRay(new WorldVector(0, .5, .5), new WorldVector(1, 0, 0)), 20,
			new WorldVector(20, .5, .5), collector.evidence(), EnumSet.allOf(PreciseTargetType.class))
			.finish(miss, resolver.resolve(miss.target(), miss.matchContext()), resolver);
		var selected = set.candidate(set.slot(PreciseTargetType.ENTITY_BLOCK).candidateId().orElseThrow()).orElseThrow();
		assertTrue(selected.resolvedTarget().target() instanceof Target.ExternalBlockTarget);
		assertEquals(java.util.Optional.of(BlockFace.NORTH), selected.blockHitFace());
		assertEquals(miss.target(), set.ordinary().resolvedTarget().target());
	}

	@Test
	void worldRangeAndDistinctPlotTokensRemainCorrectAndConsumedCandidateIsSkipped() {
		var first = plot(new Pose3d(new Vector3d(4, 0, 0), new Quaterniond(), new Vector3d(100, 0, 100), new Vector3d(1)), UUID.randomUUID());
		var second = plot(new Pose3d(new Vector3d(7, 0, 0), new Quaterniond(), new Vector3d(100, 0, 100), new Vector3d(1)), UUID.randomUUID());
		CandidateCollector collector = new CandidateCollector();
		assertTrue(scan(List.of(first, second), START, END, POLICY, limits(), collector));
		assertEquals(2, collector.evidence().size());
		assertEquals(collector.evidence().get(0).snapshot().target(), collector.evidence().get(1).snapshot().target());
		assertNotEquals(collector.evidence().get(0).hit().equivalenceKey(), collector.evidence().get(1).hit().equivalenceKey());
		TargetSnapshot miss = TargetSnapshotFactory.location(DIMENSION, 20, .5, .5);
		var resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());
		var set = new FrozenCandidateAcquisition(new ActiveInteraction().begin(),
			new CapturedRay(new WorldVector(0, .5, .5), new WorldVector(1, 0, 0)), 20,
			new WorldVector(20, .5, .5), collector.evidence(), EnumSet.allOf(PreciseTargetType.class))
			.finish(miss, resolver.resolve(miss.target(), miss.matchContext()), resolver);
		assertEquals(collector.evidence().get(0).hit().equivalenceKey(),
			set.candidate(set.slot(PreciseTargetType.ENTITY_BLOCK).candidateId().orElseThrow()).orElseThrow().equivalenceKey());
		assertEquals(collector.evidence().get(1).hit().equivalenceKey(),
			set.candidate(set.slot(PreciseTargetType.BLOCK).candidateId().orElseThrow()).orElseThrow().equivalenceKey());
		CandidateCollector outOfRange = new CandidateCollector();
		assertTrue(scan(List.of(second), START, new Vec3(6, .5, .5), POLICY, limits(), outOfRange));
		assertTrue(outOfRange.evidence().isEmpty());
	}

	@Test
	void rawNonintersectingEntryVisitsAreChargedBeforeAnyBoundsWorkAndNoCopyOccurs() {
		AtomicInteger reads = new AtomicInteger();
		var far = plot(new Pose3d(new Vector3d(50, 0, 0), new Quaterniond(), new Vector3d(100, 0, 100), new Vector3d(1)), UUID.randomUUID());
		SableSupplementalRaycaster.Source raw = new SableSupplementalRaycaster.Source() {
			@Override public int size() { return 4096; }
			@Override public SableSupplementalRaycaster.SubLevel at(int index, CandidateWorkBudget budget) { reads.incrementAndGet(); return far; }
		};
		CandidateCollector collector = new CandidateCollector();
		assertFalse(SableSupplementalRaycaster.scan(raw, DIMENSION, START, END, POLICY, CollisionContext.empty(), Vec3.ZERO,
			new CandidateWorkBudget(new CandidateWorkLimits(1000, 0, 4)), collector));
		assertEquals(4, reads.get());
		assertTrue(collector.evidence().isEmpty());
	}

	@Test
	void loadedPlotAndShapeBudgetBoundariesFailClosedWithoutWorldReadsWhenUnavailable() {
		var pose = new Pose3d(new Vector3d(8, 0, 0), new Quaterniond(), new Vector3d(100, 0, 100), new Vector3d(1));
		var original = plot(pose, UUID.randomUUID());
		MemoryWorld world = (MemoryWorld) original.world();
		var unloaded = new SableSupplementalRaycaster.SubLevel(original.id(), pose, original.localBounds(), world, original.contains(), ignored -> false);
		CandidateCollector collector = new CandidateCollector();
		assertFalse(scan(List.of(unloaded), START, END, POLICY, limits(), collector));
		assertEquals(0, world.reads.get());
		assertTrue(collector.evidence().isEmpty());
		assertFalse(scan(List.of(original), START, END, POLICY, new CandidateWorkLimits(0, 0, 10), collector));
		assertFalse(scan(List.of(original), START, END, POLICY, new CandidateWorkLimits(1000, 0, 10, 0), collector));
		assertTrue(collector.evidence().isEmpty());
	}

	@Test
	void absentSableCompletesEmptyProviderContributionWithoutLinkingOptionalApi() {
		boolean previous = ModContext.HasSable;
		try {
			ModContext.HasSable = false;
			assertTrue(SableClientProvider.resolvePreviewReadSource(null, Target.ExternalBlockTarget.candidate(
				DIMENSION, "sable", "minecraft:chest", "opaque", true)).isEmpty());
			assertTrue(SableClientProvider.candidateBlocks().collectSupplemental(null, START, END, POLICY,
				CollisionContext.empty(), Vec3.ZERO, new CandidateWorkBudget(new CandidateWorkLimits(0, 0, 0)), new CandidateCollector()));
		} finally { ModContext.HasSable = previous; }
	}

	@Test
	void localNativePolicyStillControlsSelectableDecorationsAndFluids() {
		var pose = new Pose3d(new Vector3d(8, 0, 0), new Quaterniond(), new Vector3d(100, 0, 100), new Vector3d(1));
		var plot = plot(pose, UUID.randomUUID());
		MemoryWorld world = (MemoryWorld) plot.world();
		world.blocks.put(new BlockPos(100, 0, 100), Blocks.TORCH.defaultBlockState());
		CandidateCollector outline = new CandidateCollector(), visual = new CandidateCollector();
		Vec3 start = new Vec3(0, .25, .5), end = new Vec3(20, .25, .5);
		assertTrue(scan(List.of(plot), start, end, POLICY, limits(), outline));
		assertTrue(scan(List.of(plot), start, end, RaycastPolicy.from(true, false, false), limits(), visual));
		assertFalse(outline.evidence().isEmpty());
		assertTrue(visual.evidence().isEmpty());
		world.blocks.put(new BlockPos(100, 0, 100), Blocks.WATER.defaultBlockState());
		CandidateCollector none = new CandidateCollector(), any = new CandidateCollector();
		assertTrue(scan(List.of(plot), start, end, POLICY, limits(), none));
		assertTrue(scan(List.of(plot), start, end, RaycastPolicy.from(false, false, true), limits(), any));
		assertTrue(none.evidence().isEmpty());
		assertFalse(any.evidence().isEmpty());
	}

	@Test
	void poseIsCopiedBeforeLiveWorldSamplingAndInvalidPoseFailsClosed() {
		Pose3d pose = new Pose3d(new Vector3d(8, 0, 0), new Quaterniond(), new Vector3d(100, 0, 100), new Vector3d(1));
		var plot = plot(pose, UUID.randomUUID());
		MemoryWorld world = (MemoryWorld) plot.world();
		world.beforeRead = () -> pose.position().set(40, 0, 0);
		CandidateCollector collector = new CandidateCollector();
		assertTrue(scan(List.of(plot), START, END, POLICY, limits(), collector));
		assertEquals(8.0625, collector.evidence().getFirst().hit().worldHit().x(), 1.0E-10);
		pose.scale().x = 0;
		assertFalse(scan(List.of(plot), START, END, POLICY, limits(), new CandidateCollector()));
	}

	@Test
	void nonuniformScaleUsesWorldSegmentRangeNotLocalLengthAndRemovedEntriesAreEmpty() {
		Pose3d pose = new Pose3d(new Vector3d(8, 0, 0), new Quaterniond(), new Vector3d(100, 0, 100), new Vector3d(2, 1, 1));
		CandidateCollector collector = new CandidateCollector();
		assertTrue(scan(List.of(plot(pose, UUID.randomUUID())), START, END, POLICY, limits(), collector));
		assertEquals(8.125, collector.evidence().getFirst().distance(), 1.0E-10);
		CandidateCollector shorter = new CandidateCollector();
		assertTrue(scan(List.of(plot(pose, UUID.randomUUID())), START, new Vec3(8.1, .5, .5), POLICY, limits(), shorter));
		assertTrue(shorter.evidence().isEmpty());
		assertTrue(SableSupplementalRaycaster.scan(new SableSupplementalRaycaster.Source() {
			@Override public int size() { return 1; }
			@Override public SableSupplementalRaycaster.SubLevel at(int index, CandidateWorkBudget budget) { return null; }
		}, DIMENSION, START, END, POLICY, CollisionContext.empty(), Vec3.ZERO,
			new CandidateWorkBudget(new CandidateWorkLimits(0, 0, 1)), new CandidateCollector()));
	}

	@Test
	void originContainmentKeepsExternalTargetWithoutInventingFace() {
		var plot = plot(new Pose3d(new Vector3d(8, 0, 0), new Quaterniond(), new Vector3d(100, 0, 100), new Vector3d(1)), UUID.randomUUID());
		CandidateCollector collector = new CandidateCollector();
		assertTrue(scan(List.of(plot), new Vec3(8.5, .5, .5), END, POLICY, limits(), collector));
		assertFalse(collector.evidence().isEmpty());
		assertTrue(collector.evidence().getFirst().snapshot().target() instanceof Target.ExternalBlockTarget);
		assertTrue(collector.evidence().getFirst().snapshot().blockHitFace().isEmpty());
	}

	@Test
	void nearerSameExternalContactInstallsLocalFaceWithoutChangingOrdinaryFrozenFace() {
		UUID id = UUID.randomUUID();
		String locator = new nx.pingwheel.common.integration.sable.server.SableExternalBlockLocator(id, 100, 0, 100).encode();
		var key = SableCaptureEquivalence.fromResolved(DIMENSION, id, 100, 0, 100, "minecraft:chest");
		TargetSnapshot ordinary = TargetSnapshotFactory.externalBlockCandidate(DIMENSION, "sable", "minecraft:chest",
			locator, true, BlockFace.WEST).withCandidateHit(new CandidateHit(new WorldVector(9, .5, .5), key));
		TargetSnapshot nearer = TargetSnapshotFactory.externalBlockCandidate(DIMENSION, "sable", "minecraft:chest",
			locator, true, BlockFace.NORTH).withCandidateHit(new CandidateHit(new WorldVector(8, .5, .5), key));
		var resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());
		var active = new ActiveInteraction();
		var coordinator = new PingCaptureCoordinator(resolver, active, PingCaptureLogger.noop());
		var token = coordinator.begin();
		var ray = new CapturedRay(new WorldVector(0, .5, .5), new WorldVector(1, 0, 0));
		var acquisition = new FrozenCandidateAcquisition(token, ray, 20, ordinary.candidateHit().orElseThrow().worldHit(),
			List.of(new CandidateEvidence(nearer, 8)), EnumSet.allOf(PreciseTargetType.class));
		var captured = coordinator.complete(token, ordinary, ray, java.util.Optional.of(acquisition)).orElseThrow();
		assertEquals(java.util.Optional.of(BlockFace.WEST), captured.blockHitFace(), "ordinary Content stays frozen");
		var precise = captured.selectorCandidates().orElseThrow().ordinary();
		assertEquals(java.util.Optional.of(BlockFace.NORTH), precise.blockHitFace(), "Precise takes the nearest contact's local face");
		assertEquals(new WorldVector(8, .5, .5), precise.worldHit());
		assertEquals(ordinary.target(), precise.resolvedTarget().target());
	}

	@Test
	void actualLocalFacesAreCopiedWhileSyntheticMissAndUnobservedHitsStayFaceless() {
		BlockPos pos = new BlockPos(100, 0, 100);
		Vec3 point = new Vec3(100, .5, 100.5);
		for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
			var nativeHit = new net.minecraft.world.phys.BlockHitResult(point, direction, pos, false);
			assertEquals(java.util.Optional.of(BlockFace.valueOf(direction.name())),
				SableClientCompanionAccess.observedLocalFace(nativeHit));
			assertTrue(SableClientCompanionAccess.observedLocalFace(
				net.minecraft.world.phys.BlockHitResult.miss(point, direction, pos)).isEmpty());
			assertTrue(SableClientCompanionAccess.observedLocalFace(
				new UnobservedFaceBlockHitResult(point, direction, pos, false)).isEmpty());
			assertTrue(SableClientCompanionAccess.observedLocalFace(
				new net.minecraft.world.phys.BlockHitResult(point, direction, pos, true)).isEmpty());
		}
	}

	@Test
	void nearerDifferentExternalLocatorCannotReplaceOrdinaryFaceDespiteEqualCandidateTargets() {
		UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
		var firstKey = SableCaptureEquivalence.fromResolved(DIMENSION, firstId, 100, 0, 100, "minecraft:chest");
		var secondKey = SableCaptureEquivalence.fromResolved(DIMENSION, secondId, 100, 0, 100, "minecraft:chest");
		TargetSnapshot ordinary = TargetSnapshotFactory.externalBlockCandidate(DIMENSION, "sable", "minecraft:chest",
			new nx.pingwheel.common.integration.sable.server.SableExternalBlockLocator(firstId, 100, 0, 100).encode(),
			true, BlockFace.WEST).withCandidateHit(new CandidateHit(new WorldVector(9, .5, .5), firstKey));
		TargetSnapshot other = TargetSnapshotFactory.externalBlockCandidate(DIMENSION, "sable", "minecraft:chest",
			new nx.pingwheel.common.integration.sable.server.SableExternalBlockLocator(secondId, 100, 0, 100).encode(),
			true, BlockFace.NORTH).withCandidateHit(new CandidateHit(new WorldVector(8, .5, .5), secondKey));
		assertEquals(ordinary.target(), other.target());
		var resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());
		var ray = new CapturedRay(new WorldVector(0, .5, .5), new WorldVector(1, 0, 0));
		var acquisition = new FrozenCandidateAcquisition(new ActiveInteraction().begin(), ray, 20,
			ordinary.candidateHit().orElseThrow().worldHit(), List.of(new CandidateEvidence(other, 8)),
			EnumSet.allOf(PreciseTargetType.class));
		var set = acquisition.finish(ordinary, resolver.resolve(ordinary.target(), ordinary.matchContext()), resolver);
		assertEquals(java.util.Optional.of(BlockFace.WEST), set.ordinary().blockHitFace());
		assertEquals(new WorldVector(9, .5, .5), set.ordinary().worldHit());
		var nearest = set.candidate(set.slot(PreciseTargetType.ENTITY_BLOCK).candidateId().orElseThrow()).orElseThrow();
		assertEquals(java.util.Optional.of(BlockFace.NORTH), nearest.blockHitFace());
		assertEquals(secondKey, nearest.equivalenceKey());
	}

	private static boolean scan(List<SableSupplementalRaycaster.SubLevel> plots, Vec3 start, Vec3 end,
		RaycastPolicy policy, CandidateWorkLimits limits, CandidateCollector collector) {
		return SableSupplementalRaycaster.scan(new SableSupplementalRaycaster.Source() {
			@Override public int size() { return plots.size(); }
			@Override public SableSupplementalRaycaster.SubLevel at(int index, CandidateWorkBudget budget) { return plots.get(index); }
		}, DIMENSION, start, end, policy, CollisionContext.empty(), Vec3.ZERO, new CandidateWorkBudget(limits), collector);
	}
	private static CandidateWorkLimits limits() { return new CandidateWorkLimits(1000, 0, 100); }
	private static SableSupplementalRaycaster.SubLevel plot(Pose3d pose, UUID id) {
		MemoryWorld world = new MemoryWorld(); world.blocks.put(new BlockPos(100, 0, 100), Blocks.CHEST.defaultBlockState());
		return new SableSupplementalRaycaster.SubLevel(id, pose, new AABB(100, 0, 100, 101, 1, 101), world,
			pos -> pos.getX() >= 100 && pos.getX() < 101 && pos.getZ() >= 100 && pos.getZ() < 101, ignored -> true);
	}
	private static final class MemoryWorld implements BlockGetter {
		final Map<BlockPos, BlockState> blocks = new HashMap<>(); final AtomicInteger reads = new AtomicInteger();
		Runnable beforeRead = () -> {};
		@Override public BlockState getBlockState(BlockPos pos) { reads.incrementAndGet(); beforeRead.run(); return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
		@Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
		@Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
		@Override public int getHeight() { return 384; }
		@Override public int getMinBuildHeight() { return -64; }
	}
}
