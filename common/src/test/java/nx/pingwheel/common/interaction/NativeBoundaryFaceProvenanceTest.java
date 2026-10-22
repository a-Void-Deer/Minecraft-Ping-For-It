package nx.pingwheel.common.interaction;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetResolver;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.Candidate;
import nx.pingwheel.common.interaction.candidate.CandidateEvidence;
import nx.pingwheel.common.interaction.candidate.CandidateWorkBudget;
import nx.pingwheel.common.interaction.candidate.CandidateWorkLimits;
import nx.pingwheel.common.interaction.candidate.FrozenCandidateAcquisition;
import nx.pingwheel.common.interaction.candidate.FrozenCandidateSet;
import nx.pingwheel.common.interaction.candidate.NativeBlockCandidateScan;
import nx.pingwheel.common.interaction.candidate.PreciseTargetType;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end boundary-face provenance through the production candidate chain:
 * native scan kernel -> level-free snapshot factory -> frozen allocation. A ray
 * that only touches a native shape boundary keeps its point/target without
 * installing a clamped slab face, a real inward crossing keeps its actual front
 * face, and true origin containment stays unobserved.
 */
class NativeBoundaryFaceProvenanceTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final String CHEST_ID = "minecraft:chest";
	private static final BlockPos CHEST_POS = BlockPos.ZERO;
	private static final TargetResolver RESOLVER = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	static Stream<Arguments> tangentialBoundaryRays() {
		return Stream.of(
			Arguments.of("bottom boundary along +X", new Vec3(.5, 0, .5), new Vec3(2048, 0, .5)),
			Arguments.of("bottom boundary along -X", new Vec3(.5, 0, .5), new Vec3(-2048, 0, .5)),
			Arguments.of("bottom boundary along +Z", new Vec3(.5, 0, .5), new Vec3(.5, 0, 2048)),
			Arguments.of("bottom boundary along -Z", new Vec3(.5, 0, .5), new Vec3(.5, 0, -2048)),
			Arguments.of("top boundary along +X", new Vec3(.5, .875, .5), new Vec3(2048, .875, .5)),
			Arguments.of("west boundary along +Y", new Vec3(1.0 / 16, .5, .5), new Vec3(1.0 / 16, 2048, .5)),
			Arguments.of("east boundary along +Y", new Vec3(15.0 / 16, .5, .5), new Vec3(15.0 / 16, 2048, .5)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("tangentialBoundaryRays")
	void tangentialBoundaryContactKeepsThePointButNotAClampedSlabFace(String description, Vec3 start, Vec3 end) {
		List<BlockHitResult> chestHits = chestHits(start, end);
		assertFalse(chestHits.isEmpty(), description + ": the native DDA must still retain the boundary contact");
		for (BlockHitResult hit : chestHits) {
			assertEquals(start, hit.getLocation(), description + ": boundary contact keeps its point");
			TargetSnapshot snapshot = snapshot(hit);
			assertTrue(snapshot.blockHitFace().isEmpty(), description + ": no actual surface face was observed");
			Candidate installed = install(snapshot, start, end, 0);
			assertTrue(installed.blockHitFace().isEmpty(), description + ": allocation must not install a clamped slab face");
			assertEquals(new WorldVector(start.x, start.y, start.z), installed.worldHit(), description);
			assertEquals(new Target.BlockTarget(DIMENSION, 0, 0, 0, CHEST_ID), installed.resolvedTarget().target(), description);
		}
	}

	static Stream<Arguments> boundaryEntryRays() {
		return Stream.of(
			Arguments.of("bottom entry", new Vec3(.5, 0, .5), new Vec3(.5, 2048, .5), BlockFace.DOWN),
			Arguments.of("top entry", new Vec3(.5, .875, .5), new Vec3(.5, -2048, .5), BlockFace.UP),
			Arguments.of("west entry", new Vec3(1.0 / 16, .5, .5), new Vec3(2048, .5, .5), BlockFace.WEST),
			Arguments.of("east entry", new Vec3(15.0 / 16, .5, .5), new Vec3(-2048, .5, .5), BlockFace.EAST));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("boundaryEntryRays")
	void exactBoundaryEntryInwardKeepsItsActualFrontFace(String description, Vec3 start, Vec3 end, BlockFace expectedFace) {
		List<BlockHitResult> chestHits = chestHits(start, end);
		assertFalse(chestHits.isEmpty(), description);
		for (BlockHitResult hit : chestHits) {
			assertEquals(start, hit.getLocation(), description);
			assertFalse(hit.isInside(), description + ": a boundary entry is not origin containment");
			TargetSnapshot snapshot = snapshot(hit);
			assertEquals(Optional.of(expectedFace), snapshot.blockHitFace(), description);
			Candidate installed = install(snapshot, start, end, 0);
			assertEquals(Optional.of(expectedFace), installed.blockHitFace(), description);
			assertEquals(new WorldVector(start.x, start.y, start.z), installed.worldHit(), description);
		}
	}

	static Stream<Arguments> outwardBoundaryRays() {
		return Stream.of(
			Arguments.of("bottom outward", new Vec3(.5, 0, .5), new Vec3(.5, -2048, .5)),
			Arguments.of("top outward", new Vec3(.5, .875, .5), new Vec3(.5, 2048, .5)),
			Arguments.of("west outward", new Vec3(1.0 / 16, .5, .5), new Vec3(-2048, .5, .5)),
			Arguments.of("east outward", new Vec3(15.0 / 16, .5, .5), new Vec3(2048, .5, .5)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("outwardBoundaryRays")
	void exactBoundaryOutwardContactIsStillNotAHit(String description, Vec3 start, Vec3 end) {
		assertTrue(chestHits(start, end).isEmpty(), description + ": an outward boundary contact is not retained");
	}

	@Test
	void realApproachSurfaceStillInstallsItsObservedFaceThroughAllocation() {
		Vec3 start = new Vec3(.5, .5, -2);
		Vec3 end = new Vec3(.5, .5, 2048);
		BlockHitResult hit = chestHits(start, end).getFirst();
		assertEquals(.5, hit.getLocation().x, 1.0E-12);
		assertEquals(.5, hit.getLocation().y, 1.0E-12);
		assertEquals(1.0 / 16, hit.getLocation().z, 1.0E-12);
		TargetSnapshot snapshot = snapshot(hit);
		assertEquals(Optional.of(BlockFace.NORTH), snapshot.blockHitFace());
		Candidate installed = install(snapshot, start, end, start.distanceTo(hit.getLocation()));
		assertEquals(Optional.of(BlockFace.NORTH), installed.blockHitFace());
		assertEquals(1.0 / 16, installed.worldHit().z(), 1.0E-12);
	}

	@Test
	void trueOriginContainmentStillStaysUnobservedThroughAllocation() {
		Vec3 start = new Vec3(.5, .5, .5);
		Vec3 end = new Vec3(2048, .5, .5);
		BlockHitResult hit = chestHits(start, end).getFirst();
		assertEquals(start, hit.getLocation());
		assertTrue(hit.isInside());
		TargetSnapshot snapshot = snapshot(hit);
		assertTrue(snapshot.blockHitFace().isEmpty());
		Candidate installed = install(snapshot, start, end, 0);
		assertTrue(installed.blockHitFace().isEmpty());
		assertEquals(new WorldVector(.5, .5, .5), installed.worldHit());
	}

	private static List<BlockHitResult> chestHits(Vec3 start, Vec3 end) {
		List<BlockHitResult> hits = new ArrayList<>();
		boolean complete = NativeBlockCandidateScan.scan(new ChestWorld(), ignored -> true,
			new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()),
			new CandidateWorkBudget(CandidateWorkLimits.defaults()), hits::add);
		assertTrue(complete, "production native scan must complete");
		return hits.stream().filter(hit -> hit.getBlockPos().equals(CHEST_POS)).toList();
	}

	private static TargetSnapshot snapshot(BlockHitResult hit) {
		return MinecraftTargetSnapshotFactory.fromCandidateHitResult(DIMENSION, hit, Optional.of(CHEST_ID), true);
	}

	private static Candidate install(TargetSnapshot boundarySnapshot, Vec3 start, Vec3 end, double distance) {
		TargetSnapshot ordinary = TargetSnapshotFactory.location(DIMENSION, end.x, end.y, end.z);
		CapturedRay ray = new CapturedRay(new WorldVector(start.x, start.y, start.z),
			new WorldVector(end.x - start.x, end.y - start.y, end.z - start.z));
		FrozenCandidateAcquisition acquisition = new FrozenCandidateAcquisition(new ActiveInteraction().begin(), ray,
			start.distanceTo(end), new WorldVector(end.x, end.y, end.z),
			List.of(new CandidateEvidence(boundarySnapshot, distance)),
			EnumSet.of(PreciseTargetType.ENTITY_BLOCK, PreciseTargetType.BLOCK));
		FrozenCandidateSet set = acquisition.finish(ordinary, RESOLVER.resolve(ordinary.target(), ordinary.matchContext()), RESOLVER);
		return set.candidate(set.slot(PreciseTargetType.ENTITY_BLOCK).candidateId().orElseThrow()).orElseThrow();
	}

	private static final class ChestWorld implements BlockGetter {
		@Override public BlockState getBlockState(BlockPos pos) {
			return pos.equals(CHEST_POS) ? Blocks.CHEST.defaultBlockState() : Blocks.AIR.defaultBlockState();
		}
		@Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
		@Override public BlockEntity getBlockEntity(BlockPos pos) { fail("native scan must never request block entities"); return null; }
		@Override public int getHeight() { return 384; }
		@Override public int getMinBuildHeight() { return -64; }
	}
}
