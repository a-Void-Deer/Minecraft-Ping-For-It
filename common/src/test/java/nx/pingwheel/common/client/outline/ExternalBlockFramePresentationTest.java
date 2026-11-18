package nx.pingwheel.common.client.outline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exercises the production scoped resolver, model bookkeeping, and headless late edge submission. */
@NotNullByDefault
class ExternalBlockFramePresentationTest {

	private static final String DIMENSION = "minecraft:overworld";
	private static final BlockPos PLOT_POS = new BlockPos(21_000_001, 65, 20_000_003);
	private static final String BER = EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID;
	private static final String BAKED = EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void eachVanillaPairUsesLoadedSamePlotDataFromEitherEnd() {
		var chest = new DoubleChestFixture(Blocks.CHEST, Direction.NORTH);
		var trapped = new DoubleChestFixture(Blocks.TRAPPED_CHEST, Direction.NORTH);
		BlockState foot = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT)
			.setValue(BedBlock.FACING, Direction.EAST);
		for (Pair pair : List.of(door(), new Pair(PLOT_POS, foot, PLOT_POS.east(),
			foot.setValue(BedBlock.PART, BedPart.HEAD), "foot", "head", "entity_block"), chest(chest), chest(trapped))) {
			for (BlockPos root : List.of(pair.firstPos(), pair.secondPos())) {
				var plot = new Plot(pair.states());
				ExternalBlockOutlineSpec spec = spec(1, Objects.requireNonNull(plot.storage.get(root)), pair.targetType());
				var complete = resolve(spec, root, plot);
				assertEquals(List.of(pair.firstId(), pair.secondId()), ids(complete));
				assertEquals(List.of(pair.firstPos(), pair.secondPos()), complete.presentation().renderSubjects().stream()
					.map(BlockRenderSubject::blockPos).toList());
				assertTrue(complete.presentation().renderSubjects().stream()
					.allMatch(subject -> pair.targetType().equals(subject.renderTargetTypeId())));

				BlockPos neighbor = root.equals(pair.firstPos()) ? pair.secondPos() : pair.firstPos();
				plot.storage.put(neighbor, Blocks.STONE.defaultBlockState());
				assertDirect(spec, root, plot);
				plot.storage.putAll(pair.states());

				plot.reads.clear();
				plot.foreign.add(neighbor);
				assertDirect(spec, root, plot);
				assertFalse(plot.reads.contains(neighbor), "another plot's valid twin is not read");
				plot.foreign.clear();
				plot.unloaded.add(neighbor);
				assertDirect(spec, root, plot);
				assertFalse(plot.reads.contains(neighbor), "a positive grid entry alone does not identify a loaded member");
			}
		}
	}

	@Test
	void bothPassesShareOneBindingAndUsePerWinnerPerSubjectSuccessAndFrozenPlacement() {
		Pair pair = door();
		var plot = new Plot(pair.states());
		var first = spec(1, pair.first(), pair.targetType());
		var second = spec(2, pair.first(), pair.targetType());
		var missing = spec(3, Blocks.CHEST.defaultBlockState(), "entity_block");
		var pos = pair.firstPos();
		var pose = new Matrix4d().translation(29_999_999.125, -1_000_000.375, 0.25).rotateZ(Math.PI / 2);
		Vec3 camera = new Vec3(29_999_934, 20_000_000, 20_000_003);
		Map<TargetKey.ExternalBlockKey, Integer> calls = new HashMap<>();
		Function<ExternalBlockOutlineSpec, Optional<ExternalBlockFramePresentation>> provider = spec -> {
			calls.merge(spec.blockKey(), 1, Integer::sum);
			return ExternalBlockFramePresentation.resolve(spec, pos, plot.view, pose);
		};
		@Unmodifiable List<ExternalBlockOutlineSpec> specs = List.of(first, second, missing);
		BlockModelOutlineState frame = BlockModelOutlineState.INSTANCE;
		try {
			frame.beginFrame();
			frame.preparePresentations(List.of(), specs, unused -> fail("no ordinary winners"), provider);
			assertEquals(Map.of(first.blockKey(), 1, second.blockKey(), 1, missing.blockKey(), 1), calls);
			@Unmodifiable List<ExternalBlockFramePresentation> snapshots = frame.externalPresentations();
			assertEquals(List.of(first, second), snapshots.stream().map(ExternalBlockFramePresentation::spec).toList());
			frame.preparePresentations(List.of(), specs, unused -> fail("second prepare"), unused -> fail("second binding"));
			assertSame(snapshots, frame.externalPresentations());
			pose.identity(); // Must not mutate the pose already made available to both passes.

			var firstPresentation = snapshots.getFirst();
			var secondPresentation = snapshots.getLast();
			var firstModelOrigins = new ArrayList<Vec3>();
			firstPresentation.dispatchModels(frame, (subject, observer) -> {
				var origin = firstPresentation.transform().cameraRelativeEnvironmentVertex(new Vector3d(), subject.blockPos(), camera);
				firstModelOrigins.add(new Vec3(origin.x, origin.y, origin.z));
				return subject.blockPos().equals(pos);
			});
			secondPresentation.dispatchModels(frame, (subject, observer) -> true);
			assertEquals(List.of(new Vec3(0.125, 0.625, 0.25), new Vec3(-0.875, 0.625, 0.25)), firstModelOrigins);
			assertFalse(frame.allCoveredByModels(), "another winner's upper subject cannot cover this one");

			BlockState liveUpper = pair.second().setValue(DoorBlock.OPEN, true);
			plot.storage.put(pair.secondPos(), liveUpper);
			plot.reads.clear();
			var vertices = new Vertices();
			BlockOutlineRenderer.renderExternal(firstPresentation, frame.externalSuccessKeys(), CollisionContext.empty(), camera, vertices);
			BlockOutlineRenderer.renderExternal(secondPresentation, frame.externalSuccessKeys(), CollisionContext.empty(), camera, vertices);
			assertEquals(List.of(pair.secondPos()), plot.reads, "only the uncovered subject reaches late state/shape lookup");
			List<Vec3> expected = new ArrayList<>();
			appendRotatedNativeEdges(expected, plot, liveUpper, pair.secondPos(), new Vec3(-0.875, 0.625, 0.25));
			assertVertices(expected, vertices.positions);

			firstPresentation.dispatchModels(frame, (subject, observer) -> true);
			assertTrue(frame.allCoveredByModels());
			plot.reads.clear();
			vertices.positions.clear();
			BlockOutlineRenderer.renderExternal(firstPresentation, frame.externalSuccessKeys(), CollisionContext.empty(), camera, vertices);
			assertTrue(plot.reads.isEmpty());
			assertTrue(vertices.positions.isEmpty());

			frame.clearSuccesses(); // Also used when the native model pipeline is unavailable.
			assertSame(snapshots, frame.externalPresentations());
			assertFalse(frame.allCoveredByModels());
			BlockOutlineRenderer.renderExternal(firstPresentation, frame.externalSuccessKeys(), CollisionContext.empty(), camera, vertices);
			assertEquals(List.of(pair.firstPos(), pair.secondPos()), plot.reads);
			expected.clear();
			appendRotatedNativeEdges(expected, plot, pair.first(), pair.firstPos(), new Vec3(0.125, 0.625, 0.25));
			appendRotatedNativeEdges(expected, plot, liveUpper, pair.secondPos(), new Vec3(-0.875, 0.625, 0.25));
			assertVertices(expected, vertices.positions);

			frame.beginFrame();
			frame.preparePresentations(List.of(), specs, unused -> fail("no ordinary winners"), provider);
			assertEquals(Map.of(first.blockKey(), 2, second.blockKey(), 2, missing.blockKey(), 2), calls);
		} finally {
			frame.clear();
		}
	}

	@Test
	void sharedResolverPriorityAndDeclaredBerCoverageArePreserved() {
		Pair pair = door();
		var plot = new Plot(pair.states());
		var registry = new BlockPresentationResolverRegistry();
		var ordinaryOnlyCalls = new AtomicInteger();
		assertTrue(registry.registerBefore(VanillaDoorBlockPresentationResolver.ID,
			BlockPresentationResolver.of("test:ordinary_only", context -> {
				ordinaryOnlyCalls.incrementAndGet();
				return BlockPresentationResolution.UNHANDLED;
			})).accepted());
		assertTrue(registry.registerBefore(VanillaDoorBlockPresentationResolver.ID,
			new ScopedResolver("test:door_ber", context -> {
				var pairResolution = new VanillaDoorBlockPresentationResolver().resolve(context);
				return BlockPresentationResolution.handled(pairResolution.subjects(), List.of(
					new BlockPresentationCoverageRelation("lower", BER, "upper")));
			})).accepted());
		var spec = spec(1, pair.second(), "entity_block");
		var frame = BlockModelOutlineState.INSTANCE;
		try {
			frame.beginFrame();
			var presentation = ExternalBlockFramePresentation.resolve(spec, pair.secondPos(), plot.view, new Matrix4d(), registry).orElseThrow();
			assertEquals(0, ordinaryOnlyCalls.get(), "ordinary-only resolver must not run for an external read view");
			for (String source : List.of(BAKED, BER)) {
				frame.clearSuccesses();
				List<String> attempted = new ArrayList<>();
				presentation.dispatchModels(frame, (subject, observer) -> {
					attempted.add(subject.subjectId());
					if (!subject.subjectId().equals("lower")) return false;
					assertNotNull(observer);
					observer.accept(source, EntityBlockGeometryOutcome.RENDERED);
					return true;
				});
				boolean covered = BER.equals(source);
				assertEquals(covered ? List.of("lower") : List.of("lower", "upper"), attempted);
				assertEquals(covered, presentation.allCoveredBy(frame.externalSuccessKeys()));
			}
		} finally {
			frame.clear();
		}
	}

	@Test
	void unlikeRegistryProxyUsesOwnerIdentityAndAnUnavailableOwnerIsHandledEmpty() {
		BlockPos root = PLOT_POS;
		BlockPos link = root.east();
		BlockPos owner = link.east();
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState chest = Blocks.CHEST.defaultBlockState();
		var plot = new Plot(Map.of(root, stone, link, stone, owner, chest));
		var registry = new BlockPresentationResolverRegistry();
		registry.register(new ScopedResolver("test:proxy", context -> {
			BlockState ownerState = context.world().getBlockState(owner);
			if (!context.world().getBlockState(link).is(Blocks.STONE) || !ownerState.is(Blocks.CHEST)) {
				return BlockPresentationResolution.handled(List.of());
			}
			return BlockPresentationResolution.handled(new BlockRenderSubject("owner", owner, ownerState, "minecraft:chest",
				"entity_block", BlockPresentationRelation.PROXY_TO_OWNER));
		}));
		var spec = spec(1, stone, "block");
		var matrix = new Matrix4d();
		var valid = ExternalBlockFramePresentation.resolve(spec, root, plot.view, matrix, registry).orElseThrow();
		var subject = valid.presentation().renderSubjects().getFirst();
		assertEquals(owner, subject.blockPos());
		assertEquals("entity_block", subject.renderTargetTypeId());
		assertEquals("minecraft:chest", subject.expectedBlockRegistryId());
		assertSame(chest, valid.liveState(subject));
		assertEquals(spec.blockKey(), valid.successKey(subject).sourceKey());
		plot.unloaded.add(owner);
		assertTrue(ExternalBlockFramePresentation.resolve(spec, root, plot.view, matrix, registry).orElseThrow().presentation().isEmpty());
		plot.unloaded.clear();
		plot.foreign.add(link);
		assertTrue(ExternalBlockFramePresentation.resolve(spec, root, plot.view, matrix, registry).orElseThrow().presentation().isEmpty());
		plot.storage.put(root, chest);
		assertTrue(ExternalBlockFramePresentation.resolve(spec, root, plot.view, matrix, registry).isEmpty(),
			"the unlike owner can be valid, but a replacement at the locator root is never a valid source");
	}

	private static void assertDirect(ExternalBlockOutlineSpec spec, BlockPos root, Plot plot) {
		var resolved = resolve(spec, root, plot);
		assertEquals(List.of("direct"), ids(resolved));
		assertEquals(root, resolved.presentation().renderSubjects().getFirst().blockPos());
	}

	private static Pair door() {
		BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
			.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
		return new Pair(PLOT_POS, lower, PLOT_POS.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), "lower", "upper", "block");
	}

	private static Pair chest(DoubleChestFixture fixture) {
		return new Pair(fixture.left, Objects.requireNonNull(fixture.states.get(fixture.left)), fixture.right,
			Objects.requireNonNull(fixture.states.get(fixture.right)), "left", "right", "entity_block");
	}

	private static ExternalBlockOutlineSpec spec(long id, BlockState root, String type) {
		var target = Target.ExternalBlockTarget.committed(DIMENSION, "sable", "plot-target-" + id,
			BlockPresentationContext.blockRegistryId(root), "opaque-test-locator", root.hasBlockEntity());
		return new ExternalBlockOutlineSpec(new MarkerId(id), (TargetKey.ExternalBlockKey) TargetKey.from(target),
			target, type, "attention", 0xFF123456);
	}

	private static ExternalBlockFramePresentation resolve(ExternalBlockOutlineSpec spec, BlockPos root, Plot plot) {
		return ExternalBlockFramePresentation.resolve(spec, root, plot.view, new Matrix4d(), new BlockPresentationResolverRegistry()).orElseThrow();
	}

	private static @Unmodifiable List<String> ids(ExternalBlockFramePresentation presentation) {
		return presentation.presentation().renderSubjects().stream().map(BlockRenderSubject::subjectId).toList();
	}

	private static void appendRotatedNativeEdges(List<Vec3> out, Plot plot, BlockState state, BlockPos pos, Vec3 origin) {
		state.getShape(plot.view, pos, CollisionContext.empty()).forAllEdges((ax, ay, az, bx, by, bz) -> {
			out.add(origin.add(-ay, ax, az));
			out.add(origin.add(-by, bx, bz));
		});
	}

	private static void assertVertices(List<Vec3> expected, List<Vec3> actual) {
		assertFalse(expected.isEmpty());
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++) {
			assertEquals(0.0, expected.get(i).distanceTo(actual.get(i)), 1.0e-5);
		}
	}

	@NotNullByDefault
	private record Pair(BlockPos firstPos, BlockState first, BlockPos secondPos, BlockState second,
		String firstId, String secondId, String targetType) {
		@Unmodifiable Map<BlockPos, BlockState> states() { return Map.of(firstPos, first, secondPos, second); }
	}

	@NotNullByDefault
	private record ScopedResolver(String id, Function<BlockPresentationContext, BlockPresentationResolution> function)
		implements BlockPresentationResolver {
		@Override public boolean supportsExternalScope() { return true; }
		@Override public BlockPresentationResolution resolve(BlockPresentationContext context) { return function.apply(context); }
	}

	@NotNullByDefault
	private static final class Plot implements BlockGetter {
		final Map<BlockPos, BlockState> storage;
		final Set<BlockPos> foreign = new HashSet<>();
		final Set<BlockPos> unloaded = new HashSet<>();
		final List<BlockPos> reads = new ArrayList<>();
		final ExternalBlockReadView view = new ExternalBlockReadView(this, this::readState,
			pos -> fail("these subjects do not need a resolver-time BE"));

		Plot(Map<BlockPos, BlockState> blocks) { storage = new HashMap<>(blocks); }

		private @Nullable BlockState readState(BlockPos pos) {
			if (foreign.contains(pos) || unloaded.contains(pos)) return null;
			reads.add(pos);
			return storage.get(pos);
		}

		@Override public BlockState getBlockState(BlockPos pos) { return fail("bare parent state read"); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return fail("bare parent block entity read"); }
		@Override public FluidState getFluidState(BlockPos pos) { return fail("bare parent fluid state read"); }
		@Override public int getHeight() { return 384; }
		@Override public int getMinBuildHeight() { return -64; }
	}

	@NotNullByDefault
	private static final class Vertices implements VertexConsumer {
		final List<Vec3> positions = new ArrayList<>();
		@Override public VertexConsumer addVertex(float x, float y, float z) { positions.add(new Vec3(x, y, z)); return this; }
		@Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
		@Override public VertexConsumer setUv(float u, float v) { return this; }
		@Override public VertexConsumer setUv1(int u, int v) { return this; }
		@Override public VertexConsumer setUv2(int u, int v) { return this; }
		@Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
	}
}
