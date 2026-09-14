package nx.pingwheel.common.math;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLocalShapePolicyTest {

	@BeforeAll
	static void bootstrapMinecraftBeforeAnyBlockStateIsCreated() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void appliesTheTransparentAndFluidFlagsToNativeGlassAndWaterShapes() {
		BlockPos glassPos = new BlockPos(2, 0, 0);
		BlockPos waterPos = new BlockPos(4, 0, 0);
		BlockPos stonePos = new BlockPos(6, 0, 0);
		BlockState glass = Blocks.GLASS.defaultBlockState();
		BlockGetter view = localBlockGetter(Map.of(
			glassPos, glass,
			waterPos, Blocks.WATER.defaultBlockState(),
			stonePos, Blocks.STONE.defaultBlockState()));
		Vec3 start = new Vec3(0.0, 0.5, 0.5);
		Vec3 end = new Vec3(8.0, 0.5, 0.5);

		VoxelShape nativeOutline = glass.getShape(view, glassPos, CollisionContext.empty());
		VoxelShape nativeVisual = glass.getVisualShape(view, glassPos, CollisionContext.empty());
		assertFalse(nativeOutline.isEmpty(), "glass must expose its native outline shape");
		assertTrue(nativeVisual.isEmpty(), "glass must expose its native visual pass-through shape");

		// Deliberately supply the children far-to-near: the scanner, not child-map
		// order, must select the closest eligible native surface.
		List<BlockPos> suppliedPositions = List.of(stonePos, waterPos, glassPos);
		for (boolean passThroughTransparentBlocks : new boolean[] {false, true}) {
			for (boolean markFluids : new boolean[] {false, true}) {
				RaycastPolicy policy = RaycastPolicy.from(
					passThroughTransparentBlocks, false, markFluids);
				LocalGeometryHit hit = trace(
					start, end, policy, view, suppliedPositions, ignored -> false).orElseThrow();

				if (!passThroughTransparentBlocks) {
					assertHit(hit, glassPos, LocalGeometryKind.BLOCK, "minecraft:glass", Optional.empty(),
						0.25, new Vec3(2.0, 0.5, 0.5));
				} else if (markFluids) {
					assertHit(hit, waterPos, LocalGeometryKind.FLUID, "minecraft:water",
						Optional.of("minecraft:water"), 0.5, new Vec3(4.0, 0.5, 0.5));
				} else {
					assertHit(hit, stonePos, LocalGeometryKind.BLOCK, "minecraft:stone", Optional.empty(),
						0.75, new Vec3(6.0, 0.5, 0.5));
				}
			}
		}
	}

	@Test
	void waterloggedSlabHitsItsNativeFluidAboveTheBlockShapeOnlyWhenFluidsAreEnabled() {
		BlockPos slabPos = new BlockPos(2, 0, 0);
		BlockState slab = waterloggedBottomSlab();
		BlockGetter view = localBlockGetter(Map.of(slabPos, slab));
		Vec3 start = new Vec3(0.0, 0.75, 0.5);
		Vec3 end = new Vec3(4.0, 0.75, 0.5);

		assertFalse(slab.getFluidState().isEmpty());
		assertTrue(trace(start, end, RaycastPolicy.from(false, false, false), view,
			List.of(slabPos), ignored -> false).isEmpty());

		LocalGeometryHit fluidHit = trace(start, end, RaycastPolicy.from(false, false, true), view,
			List.of(slabPos), ignored -> false).orElseThrow();
		assertHit(fluidHit, slabPos, LocalGeometryKind.FLUID, "minecraft:oak_slab",
			Optional.of("minecraft:water"), 0.5, new Vec3(2.0, 0.75, 0.5));
		assertEquals(slab, fluidHit.state(), "a fluid hit retains its waterlogged host block state");
	}

	@Test
	void followsTheCanonicalNativeFluidShapeCacheWhileStillSupplyingTheSameFluidAboveNeighbor() {
		BlockPos flowingPos = new BlockPos(2, 0, 0);
		BlockState flowingWater = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
		FluidState fluid = flowingWater.getFluidState();
		BlockGetter isolatedView = localBlockGetter(Map.of(flowingPos, flowingWater));
		VoxelShape isolatedShape = fluid.getShape(isolatedView, flowingPos);
		double isolatedTop = isolatedShape.bounds().maxY;

		assertFalse(isolatedShape.isEmpty());
		assertTrue(isolatedTop > 0.0 && isolatedTop < 1.0,
			"level-three flowing water without water above must retain its partial native height");
		double insideY = isolatedTop / 2.0;
		double aboveIsolatedWaterY = isolatedTop + (1.0 - isolatedTop) / 2.0;

		LocalGeometryHit insideHit = trace(
			new Vec3(0.0, insideY, 0.5), new Vec3(4.0, insideY, 0.5),
			RaycastPolicy.from(false, false, true), isolatedView, List.of(flowingPos), ignored -> false)
			.orElseThrow();
		assertHit(insideHit, flowingPos, LocalGeometryKind.FLUID, "minecraft:water",
			Optional.of("minecraft:flowing_water"), 0.5, new Vec3(2.0, insideY, 0.5));
		assertTrue(trace(
			new Vec3(0.0, aboveIsolatedWaterY, 0.5), new Vec3(4.0, aboveIsolatedWaterY, 0.5),
			RaycastPolicy.from(false, false, true), isolatedView, List.of(flowingPos), ignored -> false).isEmpty());

		BlockGetter viewWithWaterAbove = localBlockGetter(Map.of(
			flowingPos, flowingWater,
			flowingPos.above(), Blocks.WATER.defaultBlockState()));
		FluidState fluidAbove = viewWithWaterAbove.getFluidState(flowingPos.above());
		assertFalse(fluidAbove.isEmpty(),
			"the local view must expose the captured same-fluid neighbor above");
		assertEquals(1.0F, fluid.getHeight(viewWithWaterAbove, flowingPos),
			"native fluid height must observe the supplied same-fluid neighbor above");

		// FlowingFluid#getShape caches by the canonical FluidState identity. Since
		// the isolated lookup above populated that native cache first, this lookup
		// is allowed to retain the earlier partial shape even though getHeight now
		// reports one. The raycaster must consume that actual native shape without
		// clearing Minecraft's cache or synthesizing a corrected fluid cube.
		VoxelShape cachedNativeShape = fluid.getShape(viewWithWaterAbove, flowingPos);
		double cachedNativeTop = cachedNativeShape.bounds().maxY;
		double insideCachedNativeShapeY = cachedNativeTop / 2.0;

		LocalGeometryHit cachedShapeHit = trace(
			new Vec3(0.0, insideCachedNativeShapeY, 0.5),
			new Vec3(4.0, insideCachedNativeShapeY, 0.5),
			RaycastPolicy.from(false, false, true), viewWithWaterAbove, List.of(flowingPos), ignored -> false)
			.orElseThrow();
		assertHit(cachedShapeHit, flowingPos, LocalGeometryKind.FLUID, "minecraft:water",
			Optional.of("minecraft:flowing_water"), 0.5,
			new Vec3(2.0, insideCachedNativeShapeY, 0.5));

		if (cachedNativeTop < 1.0) {
			double aboveCachedNativeShapeY = cachedNativeTop + (1.0 - cachedNativeTop) / 2.0;
			assertTrue(trace(
				new Vec3(0.0, aboveCachedNativeShapeY, 0.5),
				new Vec3(4.0, aboveCachedNativeShapeY, 0.5),
				RaycastPolicy.from(false, false, true), viewWithWaterAbove,
				List.of(flowingPos), ignored -> false).isEmpty(),
				"raycast must match the actual cached native fluid shape rather than getHeight alone");
		}
	}

	@Test
	void givesTheBlockPriorityForAnExactWaterloggedBlockFluidTie() {
		BlockPos slabPos = new BlockPos(2, 0, 0);
		BlockState slab = waterloggedBottomSlab();
		BlockGetter view = localBlockGetter(Map.of(slabPos, slab));
		Vec3 start = new Vec3(0.0, 0.25, 0.5);
		Vec3 end = new Vec3(4.0, 0.25, 0.5);
		VoxelShape blockShape = slab.getShape(view, slabPos, CollisionContext.empty());
		VoxelShape fluidShape = slab.getFluidState().getShape(view, slabPos);

		assertTrue(blockShape.bounds().minY <= start.y && start.y < blockShape.bounds().maxY);
		assertTrue(fluidShape.bounds().minY <= start.y && start.y < fluidShape.bounds().maxY);

		LocalGeometryHit blockOnly = trace(start, end, RaycastPolicy.from(false, false, false), view,
			List.of(slabPos), ignored -> false).orElseThrow();
		LocalGeometryHit tiedHit = trace(start, end, RaycastPolicy.from(false, false, true), view,
			List.of(slabPos), ignored -> false).orElseThrow();

		assertHit(tiedHit, slabPos, LocalGeometryKind.BLOCK, "minecraft:oak_slab", Optional.empty(),
			0.5, new Vec3(2.0, 0.25, 0.5));
		assertEquals(blockOnly.t(), tiedHit.t(), 0.0);
		assertEquals(blockOnly.localPoint(), tiedHit.localPoint());
	}

	@Test
	void hiddenLocalPositionSkipsBothItsBlockAndFluidBeforeLaterChildrenAreConsidered() {
		BlockPos hiddenSlabPos = new BlockPos(2, 0, 0);
		BlockPos stonePos = new BlockPos(4, 0, 0);
		BlockState hiddenSlab = waterloggedBottomSlab();
		BlockGetter view = localBlockGetter(Map.of(
			hiddenSlabPos, hiddenSlab,
			stonePos, Blocks.STONE.defaultBlockState()));
		Vec3 start = new Vec3(0.0, 0.25, 0.5);
		Vec3 end = new Vec3(6.0, 0.25, 0.5);
		RaycastPolicy fluidPolicy = RaycastPolicy.from(false, false, true);

		LocalGeometryHit visibleHit = trace(start, end, fluidPolicy, view,
			List.of(stonePos, hiddenSlabPos), ignored -> false).orElseThrow();
		assertHit(visibleHit, hiddenSlabPos, LocalGeometryKind.BLOCK, "minecraft:oak_slab", Optional.empty(),
			1.0 / 3.0, new Vec3(2.0, 0.25, 0.5));

		LocalGeometryHit hiddenHit = trace(start, end, fluidPolicy, view,
			List.of(stonePos, hiddenSlabPos), hiddenSlabPos::equals).orElseThrow();
		assertHit(hiddenHit, stonePos, LocalGeometryKind.BLOCK, "minecraft:stone", Optional.empty(),
			2.0 / 3.0, new Vec3(4.0, 0.25, 0.5));
	}

	@Test
	void scansA1024BlockSegmentAndChoosesTheNearThinChildDespiteFarFirstMapOrder() {
		BlockPos nearThinPos = new BlockPos(250, 0, 0);
		BlockPos farStonePos = new BlockPos(900, 0, 0);
		BlockState nearThinState = Blocks.IRON_BARS.defaultBlockState();
		Map<BlockPos, BlockState> children = new LinkedHashMap<>();
		children.put(farStonePos, Blocks.STONE.defaultBlockState());
		children.put(nearThinPos, nearThinState);
		BlockGetter view = localBlockGetter(children);
		List<BlockPos> suppliedPositions = new ArrayList<>(children.keySet());
		VoxelShape nativeThinShape = nearThinState.getShape(view, nearThinPos, CollisionContext.empty());
		Vec3 start = new Vec3(0.0, 0.5, 0.5);
		Vec3 end = new Vec3(1024.0, 0.5, 0.5);

		assertEquals(farStonePos, suppliedPositions.get(0));
		assertFalse(nativeThinShape.isEmpty());
		assertTrue(nativeThinShape.bounds().maxX - nativeThinShape.bounds().minX < 1.0,
			"the near child must remain a native thin shape rather than a unit-block probe");

		LocalGeometryHit hit = trace(start, end, RaycastPolicy.from(false, false, false), view,
			suppliedPositions, ignored -> false).orElseThrow();
		double entryX = nearThinPos.getX() + nativeThinShape.bounds().minX;
		assertHit(hit, nearThinPos, LocalGeometryKind.BLOCK, "minecraft:iron_bars", Optional.empty(),
			entryX / 1024.0, new Vec3(entryX, 0.5, 0.5));
	}

	@Test
	void usesTheFrozenPressTimePolicyRatherThanASeparateLaterToggleSnapshot() {
		BlockPos glassPos = new BlockPos(2, 0, 0);
		BlockPos waterPos = new BlockPos(3, 0, 0);
		BlockPos stonePos = new BlockPos(4, 0, 0);
		BlockGetter view = localBlockGetter(Map.of(
			glassPos, Blocks.GLASS.defaultBlockState(),
			waterPos, Blocks.WATER.defaultBlockState(),
			stonePos, Blocks.STONE.defaultBlockState()));
		Vec3 start = new Vec3(0.0, 0.5, 0.5);
		Vec3 end = new Vec3(5.0, 0.5, 0.5);
		RaycastPolicy policyAtPress = RaycastPolicy.from(true, false, false);
		RaycastPolicy laterToggleSnapshot = RaycastPolicy.from(false, false, true);

		LocalGeometryHit pressTimeHit = trace(start, end, policyAtPress, view,
			List.of(stonePos, waterPos, glassPos), ignored -> false).orElseThrow();
		assertHit(pressTimeHit, stonePos, LocalGeometryKind.BLOCK, "minecraft:stone", Optional.empty(),
			0.8, new Vec3(4.0, 0.5, 0.5));

		LocalGeometryHit laterPolicyHit = trace(start, end, laterToggleSnapshot, view,
			List.of(stonePos, waterPos, glassPos), ignored -> false).orElseThrow();
		assertHit(laterPolicyHit, glassPos, LocalGeometryKind.BLOCK, "minecraft:glass", Optional.empty(),
			0.4, new Vec3(2.0, 0.5, 0.5));
	}

	@Test
	void propagatesALaterBlockGetterFailureInsteadOfReturningAnEarlierPartialHit() {
		BlockPos earlierStonePos = new BlockPos(2, 0, 0);
		BlockPos failingPos = new BlockPos(4, 0, 0);
		BlockGetter view = localBlockGetter(
			Map.of(earlierStonePos, Blocks.STONE.defaultBlockState()), Set.of(failingPos));

		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> trace(
			new Vec3(0.0, 0.5, 0.5), new Vec3(6.0, 0.5, 0.5),
			RaycastPolicy.from(false, false, false), view, List.of(earlierStonePos, failingPos), ignored -> false));
		assertEquals("test block getter failure at " + failingPos, failure.getMessage());
	}

	private static BlockState waterloggedBottomSlab() {
		return Blocks.OAK_SLAB.defaultBlockState()
			.setValue(SlabBlock.TYPE, SlabType.BOTTOM)
			.setValue(BlockStateProperties.WATERLOGGED, true);
	}

	private static Optional<LocalGeometryHit> trace(
		Vec3 start,
		Vec3 end,
		RaycastPolicy policy,
		BlockGetter view,
		Iterable<BlockPos> suppliedPositions,
		Predicate<BlockPos> hiddenPredicate
	) {
		return NativeLocalShapeRaycaster.trace(
			start, end, policy, view, suppliedPositions, hiddenPredicate, CollisionContext.empty());
	}

	private static void assertHit(
		LocalGeometryHit hit,
		BlockPos expectedPos,
		LocalGeometryKind expectedKind,
		String expectedBlockId,
		Optional<String> expectedFluidId,
		double expectedT,
		Vec3 expectedPoint
	) {
		assertEquals(expectedPos, hit.localPos());
		assertEquals(expectedKind, hit.kind());
		assertEquals(expectedBlockId, hit.blockRegistryId());
		assertEquals(expectedFluidId, hit.fluidRegistryId());
		assertEquals(expectedT, hit.t(), 0.0);
		assertEquals(expectedPoint, hit.localPoint());
	}

	private static BlockGetter localBlockGetter(Map<BlockPos, BlockState> states) {
		return localBlockGetter(states, Set.of());
	}

	private static BlockGetter localBlockGetter(
		Map<BlockPos, BlockState> states,
		Set<BlockPos> failingBlockStatePositions
	) {
		Map<BlockPos, BlockState> copiedStates = new HashMap<>();
		states.forEach((position, state) -> copiedStates.put(copy(position), Objects.requireNonNull(state, "state")));
		Set<BlockPos> copiedFailures = failingBlockStatePositions.stream()
			.map(NativeLocalShapePolicyTest::copy)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());

		return (BlockGetter) Proxy.newProxyInstance(
			BlockGetter.class.getClassLoader(),
			new Class<?>[] {BlockGetter.class},
			(proxy, method, arguments) -> switch (method.getName()) {
				case "getBlockState" -> {
					BlockPos position = (BlockPos) arguments[0];
					if (copiedFailures.contains(position)) {
						throw new IllegalStateException("test block getter failure at " + position);
					}
					yield stateAt(copiedStates, position);
				}
				case "getFluidState" -> stateAt(copiedStates, (BlockPos) arguments[0]).getFluidState();
				case "getBlockEntity" -> null;
				case "getHeight" -> 256;
				case "getMinY", "getMinBuildHeight" -> 0;
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				case "toString" -> "native-local-shape-policy-test-view";
				default -> defaultValue(method.getReturnType());
			});
	}

	private static BlockState stateAt(Map<BlockPos, BlockState> states, BlockPos position) {
		return states.getOrDefault(position, Blocks.AIR.defaultBlockState());
	}

	private static BlockPos copy(BlockPos position) {
		return new BlockPos(position.getX(), position.getY(), position.getZ());
	}

	private static Object defaultValue(Class<?> type) {
		if (type == void.class || !type.isPrimitive()) {
			return null;
		}
		if (type == boolean.class) {
			return false;
		}
		if (type == byte.class) {
			return (byte) 0;
		}
		if (type == short.class) {
			return (short) 0;
		}
		if (type == int.class) {
			return 0;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == float.class) {
			return 0.0F;
		}
		if (type == double.class) {
			return 0.0D;
		}
		if (type == char.class) {
			return '\0';
		}
		throw new IllegalArgumentException("unsupported primitive return type: " + type);
	}
}
