package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MinecraftTargetGroupingResolver;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.topology.DoubleChestFixture;
import nx.pingwheel.common.topology.VanillaDoubleChestTopology;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class VanillaChestPresentationTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	static Stream<Arguments> pairs() {
		return Stream.of(Blocks.CHEST, Blocks.TRAPPED_CHEST).flatMap(block -> Stream.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
			.flatMap(facing -> Stream.of(false, true).map(right -> Arguments.of(block, facing, right))));
	}
	private static BlockOutlineSpec source(DoubleChestFixture f, BlockPos pos) {
		return new BlockOutlineSpec(new MarkerId(1), (TargetKey.BlockKey) TargetKey.from(f.target(pos)), "entity_block", "danger", 0xFFFF0000);
	}
	@ParameterizedTest @MethodSource("pairs")
	void nativeLoadedAdaptersGroupBothKindsAndPresentLeftThenRightFromEitherHit(Block block, Direction facing, boolean hitRight) {
		var f = new DoubleChestFixture(block, facing); BlockPos hit = hitRight ? f.right : f.left;
		var resolver = new MinecraftTargetGroupingResolver(f.server);
		TargetKey key = resolver.resolve(DoubleChestFixture.OWNER, f.target(hit));
		assertInstanceOf(TargetKey.DoubleChestKey.class, key);
		assertEquals(key, resolver.resolve(DoubleChestFixture.OWNER, f.target(hitRight ? f.left : f.right)));
		var spec = source(f, hit);
		var presentation = new BlockPresentationResolverRegistry().resolve(f.client, spec);
		assertSame(spec, presentation.sourceSpec()); assertEquals(hit, new BlockPos(spec.blockKey().x(), spec.blockKey().y(), spec.blockKey().z()));
		assertEquals(List.of(f.left, f.right), presentation.renderSubjects().stream().map(BlockRenderSubject::blockPos).toList());
		assertEquals(List.of("left", "right"), presentation.renderSubjects().stream().map(BlockRenderSubject::subjectId).toList());
		assertTrue(presentation.renderSubjects().stream().allMatch(s -> s.relation() == BlockPresentationRelation.COMPOSITE
			&& s.renderTargetTypeId().equals("entity_block") && s.expectedBlockRegistryId().equals(spec.blockKey().blockRegistryId())));
		assertTrue(presentation.coverageRelations().isEmpty(), "one vanilla BER cannot cover its sibling");
		assertEquals(0, f.blockEntities); assertEquals(0, f.ordinaryClientReads);
		assertTrue(f.reads.stream().allMatch(p -> p.equals(f.left) || p.equals(f.right)));
	}
	@Test void malformedMissingSingleAndWrongKindFailSoftWithoutInventoryOrLoading() {
		for (Block block : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST)) {
			for (String invalid : List.of("single", "same-half", "facing", "nonreciprocal", "wrong-kind", "missing", "unloaded")) {
				var f = new DoubleChestFixture(block, Direction.NORTH); var spec = source(f, f.left); var target = f.target(f.left);
				var other = f.states.get(f.right);
				switch (invalid) {
					case "single" -> f.states.put(f.left, f.states.get(f.left).setValue(ChestBlock.TYPE, ChestType.SINGLE));
					case "same-half" -> f.states.put(f.right, other.setValue(ChestBlock.TYPE, ChestType.LEFT));
					case "facing" -> f.states.put(f.right, other.setValue(ChestBlock.FACING, Direction.EAST));
					case "nonreciprocal" -> f.states.put(f.right, other.setValue(ChestBlock.FACING, Direction.SOUTH));
					case "wrong-kind" -> f.states.put(f.right, (block == Blocks.CHEST ? Blocks.TRAPPED_CHEST : Blocks.CHEST).defaultBlockState());
					case "missing" -> f.states.remove(f.right);
					case "unloaded" -> f.unavailable.add(f.right);
					default -> throw new AssertionError(invalid);
				}
				assertEquals(TargetKey.from(target), new MinecraftTargetGroupingResolver(f.server).resolve(DoubleChestFixture.OWNER, target), invalid);
				var presentation = new BlockPresentationResolverRegistry().resolve(f.client, spec);
				assertEquals(1, presentation.renderSubjects().size(), invalid);
				assertEquals(f.left, presentation.renderSubjects().getFirst().blockPos());
				assertEquals(BlockPresentationRelation.DIRECT, presentation.renderSubjects().getFirst().relation());
				assertEquals(0, f.blockEntities); assertEquals(0, f.ordinaryClientReads);
				if (invalid.equals("unloaded") || invalid.equals("single")) assertFalse(f.reads.contains(f.right), invalid);
			}
		}
	}
	@Test void unavailableSourceAndReplacementNeverPresentAnotherRegistryOrReadMissingChunk() {
		var f = new DoubleChestFixture(Blocks.CHEST, Direction.NORTH); var spec = source(f, f.left); var target = f.target(f.left);
		f.unavailable.add(f.left);
		assertTrue(new BlockPresentationResolverRegistry().resolve(f.client, spec).isEmpty());
		assertEquals(TargetKey.from(target), new MinecraftTargetGroupingResolver(f.server).resolve(DoubleChestFixture.OWNER, target));
		assertTrue(f.reads.isEmpty());
		f.unavailable.clear(); f.states.put(f.left, Blocks.TRAPPED_CHEST.defaultBlockState());
		assertTrue(new BlockPresentationResolverRegistry().resolve(f.client, spec).isEmpty());
		assertEquals(TargetKey.from(target), new MinecraftTargetGroupingResolver(f.server).resolve(DoubleChestFixture.OWNER, target));
		assertFalse(f.reads.contains(f.right));
		assertTrue(VanillaDoubleChestTopology.resolve(f.left, "mod:chest", pos -> { fail("unsupported registry must not read"); return null; }).isEmpty());
	}
	@Test void oneHalfEmissionLeavesOtherHalfForFallbackAndBothPathsUseIdenticalOrderedSnapshot() {
		var f = new DoubleChestFixture(Blocks.TRAPPED_CHEST, Direction.WEST);
		var presentation = new BlockPresentationResolverRegistry().resolve(f.client, source(f, f.right));
		var visited = new ArrayList<String>(); var state = BlockModelOutlineState.INSTANCE; state.beginFrame();
		try {
			state.setPresentations(List.of(presentation));
			BlockPresentationSubjectDispatcher.dispatch(presentation, subject -> true,
				subject -> state.addSuccess(subject.successKey(presentation.sourceSpec())), (subject, observer) -> {
					visited.add(subject.subjectId()); return subject.subjectId().equals("left");
				});
			assertEquals(List.of("left", "right"), visited); assertFalse(state.allPresentationsCovered());
			assertEquals(List.of("right"), state.presentations().getFirst().renderSubjects().stream()
				.filter(subject -> !state.successKeys().contains(subject.successKey(presentation.sourceSpec()))).map(BlockRenderSubject::subjectId).toList());
			state.clearSuccesses();
			assertEquals(List.of("left", "right"), state.presentations().getFirst().renderSubjects().stream().map(BlockRenderSubject::subjectId).toList());
		} finally { state.clear(); }
	}
	@Test void syntheticMetadataPortAndNativeFramePreparationUseSameChestResolver() {
		var f = new DoubleChestFixture(Blocks.CHEST, Direction.NORTH); var spec = source(f, f.right);
		var getter = (net.minecraft.world.level.BlockGetter) java.lang.reflect.Proxy.newProxyInstance(
			getClass().getClassLoader(), new Class<?>[]{net.minecraft.world.level.BlockGetter.class}, (proxy, method, args) -> {
				if (method.getName().equals("getBlockState")) return f.states.getOrDefault(args[0], Blocks.AIR.defaultBlockState());
				throw new AssertionError("metadata-only resolver called " + method.getName());
			});
		assertEquals(new BlockPresentationResolverRegistry().resolve(f.client, spec), new BlockPresentationResolverRegistry().resolve(getter, spec));
		var clientStore = new nx.pingwheel.common.client.marker.ClientMarkerStore(0);
		var key = new MinecraftTargetGroupingResolver(f.server).resolve(DoubleChestFixture.OWNER, f.target(f.right));
		clientStore.onCreated(new nx.pingwheel.common.marker.MarkerSnapshot(new MarkerId(1), DoubleChestFixture.OWNER, f.target(f.right), key,
			"entity_block", "attention", new nx.pingwheel.common.marker.MarkerAnchor(16.5, 64.5, 15.5), 1, 30), 0);
		clientStore.onWinnerChanged(key, java.util.Optional.of(new MarkerId(1)));
		var outline = BlockOutlineState.INSTANCE; var models = BlockModelOutlineState.INSTANCE;
		try {
			outline.prepare(clientStore, "minecraft:overworld"); models.beginFrame(); models.preparePresentations(f.client, outline);
			assertEquals(List.of(f.left, f.right), models.presentations().getFirst().renderSubjects().stream().map(BlockRenderSubject::blockPos).toList());
			@Unmodifiable List<BlockPresentation> frozen = models.presentations(); f.unavailable.add(f.right);
			models.preparePresentations(f.client, outline); assertSame(frozen, models.presentations(), "normal/fallback passes cannot resolve different topology in one frame");
			models.beginFrame(); f.reads.clear(); models.preparePresentations(f.client, outline);
			assertTrue(models.presentations().getFirst().isEmpty()); assertTrue(f.reads.isEmpty());
		} finally { outline.clear(); models.clear(); }
	}
}
