package nx.pingwheel.common.math;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import nx.pingwheel.common.interaction.candidate.CandidateWorkBudget;
import nx.pingwheel.common.interaction.candidate.CandidateWorkLimits;
import nx.pingwheel.common.interaction.candidate.NativeBlockCandidateScan;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class PingClipContextTest {

	private static final @Unmodifiable List<String> CYCLIC_GLASS_IDS = List.of(
		"cyclic:glass_connected",
		"cyclic:glass_dark",
		"cyclic:glass_dark_connected");
	private static final @Unmodifiable List<String> NEAR_MISS_IDS = List.of(
		"cyclic:glass",
		"cyclic:glass_connected_slab",
		"cyclic:glass_dark_connected_glass",
		"other:glass_connected");
	private static final ResourceLocation CYCLIC_GLASS_CONNECTED = id("cyclic:glass_connected");
	private static final BlockPos STONE_POS = new BlockPos(2, 0, 0);
	private static final BlockPos WATER_POS = new BlockPos(4, 0, 0);
	private static final BlockState STONE = Blocks.STONE.defaultBlockState();
	private static final BlockState WATER_BLOCK = Blocks.WATER.defaultBlockState();
	private static final BlockGetter VIEW = new BlockGetter() {
		@Override
		public BlockState getBlockState(BlockPos pos) {
			if (pos.equals(STONE_POS)) {
				return STONE;
			}
			if (pos.equals(WATER_POS)) {
				return WATER_BLOCK;
			}
			return Blocks.AIR.defaultBlockState();
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return getBlockState(pos).getFluidState();
		}

		@Override
		public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public int getHeight() {
			return 384;
		}

		@Override
		public int getMinBuildHeight() {
			return -64;
		}
	};

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void exactCyclicConnectedGlassIdsPassThroughOnlyWithVisualSelection() {
		for (String value : CYCLIC_GLASS_IDS) {
			ResourceLocation exactId = id(value);
			PingClipContext visual = context(RaycastPolicy.from(false, false, false), state -> exactId);
			assertTrue(visual.getBlockShape(STONE, VIEW, STONE_POS).isEmpty(),
				value + " must pass through under VISUAL");

			PingClipContext outline = context(RaycastPolicy.from(true, false, false), state -> exactId);
			assertFalse(outline.getBlockShape(STONE, VIEW, STONE_POS).isEmpty(),
				value + " must remain selectable under OUTLINE");
		}
	}

	@Test
	void nearMissAndOtherNamespaceIdsKeepTheirNativeVisualShape() {
		for (String value : NEAR_MISS_IDS) {
			ResourceLocation nearMiss = id(value);
			PingClipContext visual = context(RaycastPolicy.from(false, false, false), state -> nearMiss);
			assertFalse(visual.getBlockShape(STONE, VIEW, STONE_POS).isEmpty(),
				value + " must not inherit the compatibility pass-through");
		}
	}

	@Test
	void nonMatchingBlocksKeepNativeShapesAndFluidsAreUnaffected() {
		PingClipContext nonMatching = context(RaycastPolicy.from(false, false, false),
			state -> id("minecraft:stone"));
		assertFalse(nonMatching.getBlockShape(STONE, VIEW, STONE_POS).isEmpty());

		PingClipContext visualPassThrough = context(RaycastPolicy.from(false, false, true),
			state -> CYCLIC_GLASS_CONNECTED);
		assertTrue(visualPassThrough.getBlockShape(WATER_BLOCK, VIEW, WATER_POS).isEmpty());
		VoxelShape fluidShape = visualPassThrough.getFluidShape(Fluids.WATER.defaultFluidState(), VIEW, WATER_POS);
		assertFalse(fluidShape.isEmpty(), "fluid selection must remain independent of block pass-through");
	}

	@Test
	void sharedNativeCandidateScanUsesVisualPassThroughAndStillSelectsOutlineAndFluids() {
		MemoryWorld world = new MemoryWorld();
		world.blocks.put(STONE_POS, STONE);
		world.fluids.put(WATER_POS, Fluids.WATER.defaultFluidState());

		List<BlockHitResult> visualHits = new ArrayList<>();
		assertTrue(scan(world, RaycastPolicy.from(false, false, false),
			state -> state.is(Blocks.STONE) ? CYCLIC_GLASS_CONNECTED : id("minecraft:stone"), visualHits));
		assertTrue(visualHits.isEmpty(), "a matching block must not produce a VISUAL candidate");

		List<BlockHitResult> outlineHits = new ArrayList<>();
		assertTrue(scan(world, RaycastPolicy.from(true, false, false),
			state -> state.is(Blocks.STONE) ? CYCLIC_GLASS_CONNECTED : id("minecraft:stone"), outlineHits));
		assertEquals(List.of(STONE_POS), outlineHits.stream().map(BlockHitResult::getBlockPos).toList());

		List<BlockHitResult> fluidHits = new ArrayList<>();
		assertTrue(scan(world, RaycastPolicy.from(false, false, true), state -> CYCLIC_GLASS_CONNECTED, fluidHits));
		assertEquals(List.of(WATER_POS), fluidHits.stream().map(BlockHitResult::getBlockPos).toList());
	}

	private static PingClipContext context(RaycastPolicy policy, PingClipContext.BlockIdResolver resolver) {
		return PingClipContext.create(new Vec3(0.5, 0.5, 0.5), new Vec3(8.5, 0.5, 0.5),
			policy, CollisionContext.empty(), resolver);
	}

	private static boolean scan(
		MemoryWorld world,
		RaycastPolicy policy,
		PingClipContext.BlockIdResolver resolver,
		List<BlockHitResult> hits
	) {
		return NativeBlockCandidateScan.scan(
			world,
			ignored -> true,
			context(policy, resolver),
			new CandidateWorkBudget(new CandidateWorkLimits(1000, 0, 0)),
			hits::add);
	}

	private static ResourceLocation id(String value) {
		return ResourceLocation.parse(value);
	}

	private static final class MemoryWorld implements BlockGetter {
		private final Map<BlockPos, BlockState> blocks = new HashMap<>();
		private final Map<BlockPos, FluidState> fluids = new HashMap<>();

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return fluids.getOrDefault(pos, Fluids.EMPTY.defaultFluidState());
		}

		@Override
		public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public int getHeight() {
			return 384;
		}

		@Override
		public int getMinBuildHeight() {
			return -64;
		}
	}
}
