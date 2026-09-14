package nx.pingwheel.neoforge.integration.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import nx.pingwheel.common.client.outline.BlockOutlineSpec;
import nx.pingwheel.common.client.outline.BlockPresentation;
import nx.pingwheel.common.client.outline.BlockPresentationContext;
import nx.pingwheel.common.client.outline.BlockPresentationCoverageRelation;
import nx.pingwheel.common.client.outline.BlockPresentationRelation;
import nx.pingwheel.common.client.outline.BlockPresentationResolution;
import nx.pingwheel.common.client.outline.BlockPresentationResolver;
import nx.pingwheel.common.client.outline.BlockPresentationResolverRegistry;
import nx.pingwheel.common.client.outline.BlockRenderSubject;
import nx.pingwheel.common.client.outline.EntityBlockGeometryRunner;
import nx.pingwheel.common.client.outline.VanillaDoorBlockPresentationResolver;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;

class CreateDoorPresentationResolverTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final AtomicLong NEXT_MARKER_ID = new AtomicLong(1L);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void validLowerAndUpperSourcesRetainBothRealStatesAndLowerBerCoverage() {
		BlockPos lowerPos = new BlockPos(4, 64, 7);
		BlockPos upperPos = lowerPos.above();
		BlockState lower = doorState(DoubleBlockHalf.LOWER);
		BlockState upper = doorState(DoubleBlockHalf.UPPER);
		CreateDoorPresentationResolver resolver = standInResolver();

		BlockPresentationResolution lowerResolution = resolve(
			resolver, lowerPos, lower, upperPos, upper, "entity_block");
		BlockPresentationResolution upperResolution = resolve(
			resolver, upperPos, upper, lowerPos, lower, "entity_block");

		assertCreateDoorPresentation(lowerResolution, lowerPos, lower, upperPos, upper);
		assertCreateDoorPresentation(upperResolution, lowerPos, lower, upperPos, upper);
	}

	@Test
	void damagedOrMismatchedPairsFallBackDirectlyWithoutCoverage() {
		BlockPos lowerPos = new BlockPos(8, 64, 9);
		BlockPos upperPos = lowerPos.above();
		BlockState lower = doorState(DoubleBlockHalf.LOWER);
		BlockState upper = doorState(DoubleBlockHalf.UPPER);
		CreateDoorPresentationResolver resolver = standInResolver();

		assertDirectWithoutCoverage(resolve(
			resolver, lowerPos, lower, upperPos, Blocks.AIR.defaultBlockState(), "entity_block"),
			lowerPos, lower);
		assertDirectWithoutCoverage(resolve(
			resolver,
			lowerPos,
			lower,
			upperPos,
			upper.setValue(DoorBlock.OPEN, !upper.getValue(DoorBlock.OPEN)),
			"entity_block"), lowerPos, lower);
		assertDirectWithoutCoverage(resolve(
			resolver, upperPos, upper, lowerPos, Blocks.AIR.defaultBlockState(), "entity_block"),
			upperPos, upper);
	}

	@Test
	void ordinaryOrUnsupportedSourcesRemainUnhandledUnderProductionPredicates() {
		BlockPos lowerPos = new BlockPos(12, 64, 13);
		BlockPos upperPos = lowerPos.above();
		BlockState lower = doorState(DoubleBlockHalf.LOWER);
		BlockState upper = doorState(DoubleBlockHalf.UPPER);

		BlockPresentationResolution ordinaryDoor = resolve(
			new CreateDoorPresentationResolver(), lowerPos, lower, upperPos, upper, "entity_block");
		BlockPresentationResolution wrongTargetType = resolve(
			standInResolver(), lowerPos, lower, upperPos, upper, "block");
		BlockPresentationResolution ordinaryBlock = resolve(
			standInResolver(), lowerPos, Blocks.STONE.defaultBlockState(), null, null, "entity_block");

		assertUnhandled(ordinaryDoor);
		assertUnhandled(wrongTargetType);
		assertUnhandled(ordinaryBlock);
	}

	@Test
	void productionIdentifierMatcherAcceptsOnlyTheFiveVerifiedCreateDoors() {
		for (String identifier : List.of(
			"create:andesite_door",
			"create:copper_door",
			"create:brass_door",
			"create:train_door",
			"create:framed_glass_door")) {
			assertTrue(CreateDoorPresentationResolver.matchesSupportedCreateDoorId(identifier));
		}

		for (String identifier : List.of(
			"create:oak_door",
			"create:large_water_wheel",
			"minecraft:oak_door",
			"create:framed_glass_door_extra",
			"create:framed_glass_door/other")) {
			assertFalse(CreateDoorPresentationResolver.matchesSupportedCreateDoorId(identifier));
		}
	}

	@Test
	void specializedResolverPrecedesGenericDoorResolverThroughRegistryApi() {
		BlockPresentationResolverRegistry registry = new BlockPresentationResolverRegistry();
		BlockPresentationResolver specialized = standInResolver();

		BlockPresentationResolverRegistry.Registration registration = registry.registerBefore(
			VanillaDoorBlockPresentationResolver.ID, specialized);

		assertTrue(registration.accepted());
		List<BlockPresentationResolver> resolvers = registry.snapshot();
		assertEquals(CreateDoorPresentationResolver.RESOLVER_ID, resolvers.get(0).id());
		assertEquals(VanillaDoorBlockPresentationResolver.ID, resolvers.get(1).id());

		BlockPos lowerPos = new BlockPos(16, 64, 17);
		BlockPos upperPos = lowerPos.above();
		BlockState lower = doorState(DoubleBlockHalf.LOWER);
		BlockState upper = doorState(DoubleBlockHalf.UPPER);
		BlockPresentation presentation = registry.resolve(new BlockPresentationContext(
			world(lowerPos, lower, upperPos, upper), sourceSpec(lowerPos, lower, "entity_block")));

		assertEquals(1, presentation.coverageRelations().size());
		assertEquals("lower", presentation.coverageRelations().get(0).ownerSubjectId());
	}

	private static CreateDoorPresentationResolver standInResolver() {
		return new CreateDoorPresentationResolver(
			state -> state.getBlock() instanceof DoorBlock,
			CreateDoorPresentationResolverTest::isOakDoor);
	}

	private static boolean isOakDoor(String blockId) {
		return registryId(Blocks.OAK_DOOR).equals(blockId);
	}

	private static BlockState doorState(DoubleBlockHalf half) {
		return Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, half);
	}

	private static BlockPresentationResolution resolve(
		CreateDoorPresentationResolver resolver,
		BlockPos sourcePos,
		BlockState sourceState,
		BlockPos otherPos,
		BlockState otherState,
		String targetTypeId
	) {
		return resolver.resolve(new BlockPresentationContext(
			world(sourcePos, sourceState, otherPos, otherState),
			sourceSpec(sourcePos, sourceState, targetTypeId)));
	}

	private static void assertCreateDoorPresentation(
		BlockPresentationResolution resolution,
		BlockPos lowerPos,
		BlockState lower,
		BlockPos upperPos,
		BlockState upper
	) {
		assertTrue(resolution.handled());
		assertEquals(2, resolution.subjects().size());
		assertSubject(resolution.subjects().get(0), "lower", lowerPos, lower);
		assertSubject(resolution.subjects().get(1), "upper", upperPos, upper);
		assertEquals(List.of(new BlockPresentationCoverageRelation(
			"lower",
			EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID,
			"upper")), resolution.coverageRelations());
	}

	private static void assertSubject(
		BlockRenderSubject subject,
		String expectedSubjectId,
		BlockPos expectedPos,
		BlockState expectedState
	) {
		assertEquals(expectedSubjectId, subject.subjectId());
		assertEquals(expectedPos, subject.blockPos());
		assertSame(expectedState, subject.blockState());
		assertEquals(registryId(Blocks.OAK_DOOR), subject.expectedBlockRegistryId());
		assertEquals("entity_block", subject.renderTargetTypeId());
		assertEquals(BlockPresentationRelation.COMPOSITE, subject.relation());
	}

	private static void assertDirectWithoutCoverage(
		BlockPresentationResolution resolution,
		BlockPos sourcePos,
		BlockState sourceState
	) {
		assertTrue(resolution.handled());
		assertEquals(1, resolution.subjects().size());
		BlockRenderSubject direct = resolution.subjects().get(0);
		assertEquals("direct", direct.subjectId());
		assertEquals(sourcePos, direct.blockPos());
		assertSame(sourceState, direct.blockState());
		assertEquals(registryId(Blocks.OAK_DOOR), direct.expectedBlockRegistryId());
		assertEquals("entity_block", direct.renderTargetTypeId());
		assertEquals(BlockPresentationRelation.DIRECT, direct.relation());
		assertTrue(resolution.coverageRelations().isEmpty());
	}

	private static void assertUnhandled(BlockPresentationResolution resolution) {
		assertFalse(resolution.handled());
		assertTrue(resolution.subjects().isEmpty());
		assertTrue(resolution.coverageRelations().isEmpty());
	}

	private static BlockOutlineSpec sourceSpec(
		BlockPos pos,
		BlockState state,
		String targetTypeId
	) {
		return new BlockOutlineSpec(
			new MarkerId(NEXT_MARKER_ID.getAndIncrement()),
			new TargetKey.BlockKey(DIMENSION, pos.getX(), pos.getY(), pos.getZ(), registryId(state)),
			targetTypeId,
			"attention",
			0xFF123456);
	}

	private static String registryId(BlockState state) {
		return registryId(state.getBlock());
	}

	private static String registryId(net.minecraft.world.level.block.Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	private static BlockGetter world(
		BlockPos firstPos,
		BlockState firstState,
		BlockPos secondPos,
		BlockState secondState
	) {
		Map<BlockPos, BlockState> states = new HashMap<>();
		states.put(firstPos, firstState);
		if (secondPos != null && secondState != null) {
			states.put(secondPos, secondState);
		}
		Map<BlockPos, BlockState> stateCopy = Map.copyOf(states);
		return (BlockGetter) Proxy.newProxyInstance(
			BlockGetter.class.getClassLoader(),
			new Class<?>[] {BlockGetter.class},
			(proxy, method, arguments) -> {
				switch (method.getName()) {
					case "getBlockState":
						return stateCopy.getOrDefault(
							(BlockPos) arguments[0], Blocks.AIR.defaultBlockState());
					case "getBlockEntity":
						return null;
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == arguments[0];
					case "toString":
						return "test-world";
					default:
						return defaultValue(method.getReturnType());
				}
			});
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive()) {
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
		return null;
	}
}
