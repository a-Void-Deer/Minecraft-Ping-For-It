package nx.pingwheel.common.interaction.candidate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.*;

class NativeBlockCandidateRegressionTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test
	void longObliqueSegmentHitsActualChestNorthSurfaceRatherThanVanillaProbeInterior() {
		MemoryWorld world = new MemoryWorld();
		world.blocks.put(new BlockPos(1, 0, 1), Blocks.STONE.defaultBlockState());
		world.blocks.put(new BlockPos(2, 0, 2), Blocks.CHEST.defaultBlockState());
		Vec3 start = new Vec3(.5, .5, .85);
		Vec3 end = start.add(new Vec3(.8, 0, .6).scale(2048));
		List<BlockHitResult> hits = new ArrayList<>();
		assertTrue(scan(world, start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, hits));
		BlockHitResult chest = hits.stream().filter(hit -> hit.getBlockPos().equals(new BlockPos(2, 0, 2))).findFirst().orElseThrow();
		// Chest box has minZ=2+1/16. Its NORTH entry is later than minX=2+1/16.
		double expectedZ = 2 + 1.0 / 16;
		double expectedX = .5 + .8 * ((expectedZ - .85) / .6);
		assertEquals(expectedX, chest.getLocation().x, 1.0E-12);
		assertEquals(.5, chest.getLocation().y, 1.0E-12);
		assertEquals(expectedZ, chest.getLocation().z, 1.0E-12);
		assertEquals(Direction.NORTH, chest.getDirection());
		assertFalse(chest.isInside());
	}

	@Test
	void fluidsUseTheSameExactFiniteSurfaceKernelOnLongSegments() {
		MemoryWorld world = new MemoryWorld();
		world.fluids.put(new BlockPos(2, 0, 2), Fluids.WATER.defaultFluidState());
		Vec3 start = new Vec3(.5, .5, .85);
		Vec3 end = start.add(new Vec3(.8, 0, .6).scale(2048));
		List<BlockHitResult> hits = new ArrayList<>();
		assertTrue(scan(world, start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, hits));
		BlockHitResult water = hits.getFirst();
		assertEquals(2, water.getLocation().z, 1.0E-12);
		assertEquals(.5 + .8 * ((2 - .85) / .6), water.getLocation().x, 1.0E-12);
		assertEquals(Direction.NORTH, water.getDirection());
		assertFalse(water.isInside());
	}

	@Test
	void selectableCellsDoNotExpandToAdjacentModelOrMultipartOwners() {
		for (boolean chestPresent : List.of(false, true)) {
			MemoryWorld world = new MemoryWorld();
			world.blocks.put(new BlockPos(1, 1, 0), Blocks.STONE.defaultBlockState());
			world.blocks.put(new BlockPos(3, 0, 0), Blocks.LECTERN.defaultBlockState());
			if (chestPresent) world.blocks.put(new BlockPos(5, 1, 0), Blocks.CHEST.defaultBlockState());
			Vec3 start = new Vec3(.5, 1.05, .85), end = new Vec3(8, 1.05, .85);
			List<BlockHitResult> hits = new ArrayList<>();
			assertTrue(scan(world, start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, hits));
			assertTrue(hits.stream().noneMatch(hit -> hit.getBlockPos().equals(new BlockPos(3, 0, 0))),
				"discovery follows native cells, not an adjacent owner/model search");
			CandidateCollector collector = new CandidateCollector();
			for (BlockHitResult hit : hits) {
				TargetSnapshot snapshot = snapshot(world, hit);
				collector.add(new CandidateEvidence(snapshot, start.distanceTo(hit.getLocation())));
			}
			TargetSnapshot ordinary = snapshot(world, hits.stream().filter(hit -> hit.getBlockPos().equals(new BlockPos(1, 1, 0))).findFirst().orElseThrow());
			var resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());
			var acquisition = new FrozenCandidateAcquisition(new ActiveInteraction().begin(), new CapturedRay(vector(start), new WorldVector(1, 0, 0)),
				end.x - start.x, ordinary.candidateHit().orElseThrow().worldHit(), collector.evidence(), EnumSet.allOf(PreciseTargetType.class));
			var set = acquisition.finish(ordinary, resolver.resolve(ordinary.target(), ordinary.matchContext()), resolver);
			if (chestPresent) {
				var selected = set.candidate(set.slot(PreciseTargetType.ENTITY_BLOCK).candidateId().orElseThrow()).orElseThrow();
				assertEquals(5.0625, selected.worldHit().x());
			} else {
				assertTrue(set.slot(PreciseTargetType.ENTITY_BLOCK).candidateId().isEmpty());
			}
		}
	}

	@Test
	void guardedMovingPistonVisualShapeReadsLoadedBeAndFindsActualMovedStoneSurface() throws Exception {
		MemoryWorld world = pistonWorld();
		LoadedCandidateBlockView view = new LoadedCandidateBlockView(world, ignored -> true);
		List<BlockHitResult> hits = new ArrayList<>();
		assertTrue(scan(view, new Vec3(.5, .5, .5), new Vec3(8, .5, .5), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, hits));
		assertTrue(view.complete());
		assertTrue(world.beReads.get() > 0);
		BlockHitResult piston = hits.stream().filter(hit -> hit.getBlockPos().equals(new BlockPos(3, 0, 0))).findFirst().orElseThrow();
		assertEquals(2.5, piston.getLocation().x, 1.0E-12);
		assertEquals(Direction.WEST, piston.getDirection());
		assertFalse(piston.isInside());
	}

	@Test
	void missingOrUnloadedShapeBeCannotSilentlyCertifyFartherChest() throws Exception {
		MemoryWorld world = pistonWorld();
		world.entities.clear();
		LoadedCandidateBlockView missing = new LoadedCandidateBlockView(world, ignored -> true);
		List<BlockHitResult> hits = new ArrayList<>();
		scan(missing, new Vec3(.5, .5, .5), new Vec3(8, .5, .5), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, hits);
		assertFalse(missing.complete(), "empty moving geometry cannot certify farther chest when required BE is absent");
		world.beReads.set(0);
		LoadedCandidateBlockView unloaded = new LoadedCandidateBlockView(world, pos -> !pos.equals(new BlockPos(3, 0, 0)));
		assertNull(unloaded.getBlockEntity(new BlockPos(3, 0, 0)));
		assertEquals(0, world.beReads.get());
		assertFalse(unloaded.complete());
	}

	@Test
	void shapeNeighborAndBeReadsConsumeTheSameProductionReadBudgetBeforeAccess() throws Exception {
		MemoryWorld world = pistonWorld();
		var budget = new CandidateWorkBudget(new CandidateWorkLimits(1, 0, 0));
		var view = new LoadedCandidateBlockView(world, ignored -> true, budget);
		assertSame(world.blocks.get(new BlockPos(3, 0, 0)), view.getBlockState(new BlockPos(3, 0, 0)));
		assertThrows(ExactNativeShapeClip.Incomplete.class, () -> view.getBlockEntity(new BlockPos(3, 0, 0)));
		assertEquals(0, world.beReads.get(), "exhaustion is checked before native BE access");
		assertFalse(view.complete());
	}

	@Test
	void exactBoxBudgetFailsClosedRatherThanCertifyPrefix() {
		var budget = new CandidateWorkBudget(new CandidateWorkLimits(10, 0, 0, 0));
		assertThrows(ExactNativeShapeClip.Incomplete.class, () -> ExactNativeShapeClip.clip(Shapes.block(),
			new Vec3(-1, .5, .5), new Vec3(2, .5, .5), BlockPos.ZERO, budget));
	}

	@Test
	void productionScanReportsIncompleteWhenNativeShapeBoxWorkIsExhausted() {
		MemoryWorld world = new MemoryWorld();
		world.blocks.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
		world.blocks.put(new BlockPos(3, 0, 0), Blocks.CHEST.defaultBlockState());
		List<BlockHitResult> hits = new ArrayList<>();
		assertFalse(NativeBlockCandidateScan.scan(world, ignored -> true,
			new ClipContext(new Vec3(.5, .5, .5), new Vec3(8, .5, .5), ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, CollisionContext.empty()),
			new CandidateWorkBudget(new CandidateWorkLimits(1000, 0, 0, 0)), hits::add));
		assertTrue(hits.isEmpty());
	}

	@Test
	void trueOriginContainmentAndOutwardFaceContactsDoNotUseALengthScaledProbe() {
		BlockHitResult contained = ExactNativeShapeClip.clip(Shapes.block(), new Vec3(.5, .5, .5), new Vec3(2048, .5, .5),
			BlockPos.ZERO, new CandidateWorkBudget(CandidateWorkLimits.defaults()));
		assertEquals(new Vec3(.5, .5, .5), contained.getLocation());
		assertTrue(contained.isInside());
		assertInstanceOf(UnobservedFaceBlockHitResult.class, contained);
		assertEquals(Direction.WEST, contained.getDirection());
		assertNull(ExactNativeShapeClip.clip(Shapes.block(), new Vec3(0, .5, .5), new Vec3(-2048, .5, .5),
			BlockPos.ZERO, new CandidateWorkBudget(CandidateWorkLimits.defaults())));
		BlockHitResult inward = ExactNativeShapeClip.clip(Shapes.block(), new Vec3(0, .5, .5), new Vec3(2048, .5, .5),
			BlockPos.ZERO, new CandidateWorkBudget(CandidateWorkLimits.defaults()));
		assertEquals(Direction.WEST, inward.getDirection());
		assertFalse(inward.isInside());
	}

	private static MemoryWorld pistonWorld() throws Exception {
		MemoryWorld world = new MemoryWorld();
		BlockPos pos = new BlockPos(3, 0, 0);
		BlockState moving = Blocks.MOVING_PISTON.defaultBlockState().setValue(MovingPistonBlock.FACING, Direction.EAST);
		world.blocks.put(pos, moving);
		world.blocks.put(new BlockPos(6, 0, 0), Blocks.CHEST.defaultBlockState());
		PistonMovingBlockEntity entity = new PistonMovingBlockEntity(pos, moving, Blocks.STONE.defaultBlockState(), Direction.EAST, true, false);
		for (String fieldName : List.of("progress", "progressO")) {
			var field = PistonMovingBlockEntity.class.getDeclaredField(fieldName);
			field.setAccessible(true); field.setFloat(entity, .5f);
		}
		world.entities.put(pos, entity);
		return world;
	}
	private static boolean scan(BlockGetter world, Vec3 start, Vec3 end, ClipContext.Block block, ClipContext.Fluid fluid, List<BlockHitResult> hits) {
		return NativeBlockCandidateScan.scan(world, ignored -> true, new ClipContext(start, end, block, fluid, CollisionContext.empty()),
			new CandidateWorkBudget(CandidateWorkLimits.defaults()), hits::add);
	}
	private static TargetSnapshot snapshot(MemoryWorld world, BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos(); BlockState state = world.getBlockState(pos);
		TargetSnapshot snapshot = TargetSnapshotFactory.block("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ(),
			BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), state.hasBlockEntity(), BlockFace.valueOf(hit.getDirection().name()));
		return snapshot.withCandidateHit(new CandidateHit(vector(hit.getLocation()), CaptureEquivalenceKey.nativeTarget(snapshot.target())));
	}
	private static WorldVector vector(Vec3 value) { return new WorldVector(value.x, value.y, value.z); }
	private static final class MemoryWorld implements BlockGetter {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Map<BlockPos, FluidState> fluids = new HashMap<>();
		final Map<BlockPos, BlockEntity> entities = new HashMap<>();
		final AtomicInteger beReads = new AtomicInteger();
		@Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
		@Override public FluidState getFluidState(BlockPos pos) { return fluids.getOrDefault(pos, Fluids.EMPTY.defaultFluidState()); }
		@Override public BlockEntity getBlockEntity(BlockPos pos) { beReads.incrementAndGet(); return entities.get(pos); }
		@Override public int getHeight() { return 384; }
		@Override public int getMinBuildHeight() { return -64; }
	}
}
