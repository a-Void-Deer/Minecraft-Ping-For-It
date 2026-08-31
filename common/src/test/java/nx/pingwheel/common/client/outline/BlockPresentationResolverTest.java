package nx.pingwheel.common.client.outline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;

class BlockPresentationResolverTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final AtomicLong NEXT_MARKER_ID = new AtomicLong(1L);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void directFallbackUsesLiveStateAndSourceTargetType() {
		BlockPos pos = new BlockPos(1, 64, 2);
		BlockState state = Blocks.STONE.defaultBlockState();
		BlockOutlineSpec source = sourceSpec(pos, state, "entity_block");

		BlockPresentation presentation = new BlockPresentationResolverRegistry(false)
			.resolve(world(Map.of(pos, state)), source);

		assertSame(source, presentation.sourceSpec());
		assertEquals(1, presentation.renderSubjects().size());
		BlockRenderSubject subject = presentation.renderSubjects().get(0);
		assertEquals("direct", subject.subjectId());
		assertEquals(pos, subject.blockPos());
		assertSame(state, subject.blockState());
		assertEquals(registryId(state), subject.expectedBlockRegistryId());
		assertEquals("entity_block", subject.renderTargetTypeId());
		assertEquals(BlockPresentationRelation.DIRECT, subject.relation());
	}

	@Test
	void replacedSourceProducesNoSubjects() {
		BlockPos pos = new BlockPos(3, 65, 4);
		BlockState expected = Blocks.STONE.defaultBlockState();
		BlockOutlineSpec source = sourceSpec(pos, expected, "block");

		BlockPresentation presentation = new BlockPresentationResolverRegistry()
			.resolve(world(Map.of(pos, Blocks.DIRT.defaultBlockState())), source);

		assertTrue(presentation.renderSubjects().isEmpty());
	}

	@ParameterizedTest(name = "door {0}, open={1}, powered={2}, hinge={3}, source={4}")
	@MethodSource("doorCases")
	void resolvesDoorLowerThenUpperForAllStates(
		Direction facing,
		boolean open,
		boolean powered,
		DoorHingeSide hinge,
		DoubleBlockHalf sourceHalf
	) {
		BlockPos lowerPos = new BlockPos(10, 70, 11);
		BlockPos upperPos = lowerPos.above();
		BlockState lower = doorState(DoubleBlockHalf.LOWER, facing, hinge, open, powered);
		BlockState upper = doorState(DoubleBlockHalf.UPPER, facing, hinge, open, powered);
		BlockPos sourcePos = sourceHalf == DoubleBlockHalf.LOWER ? lowerPos : upperPos;
		BlockOutlineSpec source = sourceSpec(sourcePos, sourceHalf == DoubleBlockHalf.LOWER ? lower : upper, "block");

		BlockPresentation presentation = new BlockPresentationResolverRegistry()
			.resolve(world(Map.of(lowerPos, lower, upperPos, upper)), source);

		assertEquals(2, presentation.renderSubjects().size());
		BlockRenderSubject lowerSubject = presentation.renderSubjects().get(0);
		BlockRenderSubject upperSubject = presentation.renderSubjects().get(1);
		assertEquals("lower", lowerSubject.subjectId());
		assertEquals("upper", upperSubject.subjectId());
		assertEquals(lowerPos, lowerSubject.blockPos());
		assertEquals(upperPos, upperSubject.blockPos());
		assertSame(lower, lowerSubject.blockState());
		assertSame(upper, upperSubject.blockState());
		assertEquals("block", lowerSubject.renderTargetTypeId());
		assertEquals("block", upperSubject.renderTargetTypeId());
		assertEquals(BlockPresentationRelation.COMPOSITE, lowerSubject.relation());
		assertEquals(BlockPresentationRelation.COMPOSITE, upperSubject.relation());
		assertNotEquals(lowerSubject.blockState(), upperSubject.blockState());
	}

	@ParameterizedTest(name = "door invalid neighbor {0}")
	@MethodSource("invalidDoorNeighborCases")
	void invalidDoorNeighborFallsBackToDirect(DoorNeighborMismatch mismatch) {
		BlockPos lowerPos = new BlockPos(11, 70, 12);
		BlockPos upperPos = lowerPos.above();
		BlockState lower = doorState(
			DoubleBlockHalf.LOWER, Direction.NORTH, DoorHingeSide.LEFT, false, false);
		BlockState invalidUpper = invalidDoorUpper(mismatch);
		BlockOutlineSpec source = sourceSpec(lowerPos, lower, "block");

		BlockPresentation presentation = new BlockPresentationResolverRegistry()
			.resolve(world(Map.of(lowerPos, lower, upperPos, invalidUpper)), source);

		assertEquals(1, presentation.renderSubjects().size());
		BlockRenderSubject subject = presentation.renderSubjects().get(0);
		assertEquals("direct", subject.subjectId());
		assertEquals(lowerPos, subject.blockPos());
		assertSame(lower, subject.blockState());
		assertEquals(BlockPresentationRelation.DIRECT, subject.relation());
	}

	@Test
	void damagedDoorFallsBackToDirectSourcePresentation() {
		BlockPos lowerPos = new BlockPos(12, 70, 13);
		BlockPos upperPos = lowerPos.above();
		BlockState lower = doorState(
			DoubleBlockHalf.LOWER, Direction.NORTH, DoorHingeSide.LEFT, false, false);
		BlockState mismatchedUpper = doorState(
			DoubleBlockHalf.UPPER, Direction.SOUTH, DoorHingeSide.LEFT, false, false);
		BlockOutlineSpec source = sourceSpec(lowerPos, lower, "block");

		BlockPresentation presentation = new BlockPresentationResolverRegistry()
			.resolve(world(Map.of(lowerPos, lower, upperPos, mismatchedUpper)), source);

		assertEquals(1, presentation.renderSubjects().size());
		BlockRenderSubject subject = presentation.renderSubjects().get(0);
		assertEquals("direct", subject.subjectId());
		assertEquals(lowerPos, subject.blockPos());
		assertSame(lower, subject.blockState());
		assertEquals(BlockPresentationRelation.DIRECT, subject.relation());
	}

	@ParameterizedTest(name = "bed {0}, source={1}")
	@MethodSource("bedFacings")
	void resolvesBedFootThenHeadForAllFacings(Direction facing, BedPart sourcePart) {
		BlockPos footPos = new BlockPos(20, 70, 21);
		BlockPos headPos = footPos.relative(facing);
		BlockState foot = bedState(BedPart.FOOT, facing, false);
		BlockState head = bedState(BedPart.HEAD, facing, true);
		BlockPos sourcePos = sourcePart == BedPart.FOOT ? footPos : headPos;
		BlockState sourceState = sourcePart == BedPart.FOOT ? foot : head;
		BlockOutlineSpec source = sourceSpec(sourcePos, sourceState, "block");

		BlockPresentation presentation = new BlockPresentationResolverRegistry()
			.resolve(world(Map.of(footPos, foot, headPos, head)), source);

		assertEquals(2, presentation.renderSubjects().size());
		BlockRenderSubject footSubject = presentation.renderSubjects().get(0);
		BlockRenderSubject headSubject = presentation.renderSubjects().get(1);
		assertEquals("foot", footSubject.subjectId());
		assertEquals("head", headSubject.subjectId());
		assertEquals(footPos, footSubject.blockPos());
		assertEquals(headPos, headSubject.blockPos());
		assertSame(foot, footSubject.blockState());
		assertSame(head, headSubject.blockState());
		assertEquals(BlockPresentationRelation.COMPOSITE, footSubject.relation());
		assertEquals(BlockPresentationRelation.COMPOSITE, headSubject.relation());
		assertFalse(footSubject.blockState().getValue(BedBlock.OCCUPIED));
		assertTrue(headSubject.blockState().getValue(BedBlock.OCCUPIED));
	}

	@ParameterizedTest(name = "bed invalid neighbor {0}")
	@MethodSource("invalidBedNeighborCases")
	void invalidBedNeighborFallsBackToDirect(BedNeighborMismatch mismatch) {
		BlockPos footPos = new BlockPos(23, 70, 24);
		BlockPos headPos = footPos.relative(Direction.EAST);
		BlockState foot = bedState(BedPart.FOOT, Direction.EAST, false);
		BlockState invalidHead = invalidBedHead(mismatch);
		BlockOutlineSpec source = sourceSpec(footPos, foot, "block");

		BlockPresentation presentation = new BlockPresentationResolverRegistry()
			.resolve(world(Map.of(footPos, foot, headPos, invalidHead)), source);

		assertEquals(1, presentation.renderSubjects().size());
		BlockRenderSubject subject = presentation.renderSubjects().get(0);
		assertEquals("direct", subject.subjectId());
		assertEquals(footPos, subject.blockPos());
		assertSame(foot, subject.blockState());
		assertEquals(BlockPresentationRelation.DIRECT, subject.relation());
	}

	@Test
	void bedHeadSourceStillReturnsFootThenHead() {
		BlockPos footPos = new BlockPos(22, 70, 23);
		BlockPos headPos = footPos.relative(Direction.WEST);
		BlockState foot = bedState(BedPart.FOOT, Direction.WEST, false);
		BlockState head = bedState(BedPart.HEAD, Direction.WEST, false);
		BlockOutlineSpec source = sourceSpec(headPos, head, "block");

		BlockPresentation presentation = new BlockPresentationResolverRegistry()
			.resolve(world(Map.of(footPos, foot, headPos, head)), source);

		assertEquals(List.of(footPos, headPos), presentation.renderSubjects().stream()
			.map(BlockRenderSubject::blockPos).toList());
	}

	@Test
	void damagedBedFallsBackToDirectSourcePresentation() {
		BlockPos footPos = new BlockPos(24, 70, 25);
		BlockPos headPos = footPos.relative(Direction.EAST);
		BlockState foot = bedState(BedPart.FOOT, Direction.EAST, false);
		BlockState damagedHead = Blocks.AIR.defaultBlockState();
		BlockOutlineSpec source = sourceSpec(footPos, foot, "block");

		BlockPresentation presentation = new BlockPresentationResolverRegistry()
			.resolve(world(Map.of(footPos, foot, headPos, damagedHead)), source);

		assertEquals(1, presentation.renderSubjects().size());
		BlockRenderSubject subject = presentation.renderSubjects().get(0);
		assertEquals("direct", subject.subjectId());
		assertEquals(footPos, subject.blockPos());
		assertSame(foot, subject.blockState());
		assertEquals(BlockPresentationRelation.DIRECT, subject.relation());
	}

	@Test
	void firstClaimWinsInRegistrationOrder() {
		BlockPresentationResolverRegistry registry = new BlockPresentationResolverRegistry(false);
		BlockPos pos = new BlockPos(30, 70, 31);
		BlockState state = Blocks.STONE.defaultBlockState();
		BlockPresentationResolver first = resolver("test:first", context ->
			BlockPresentationResolution.handled(subject("first", pos, state)));
		BlockPresentationResolver second = resolver("test:second", context ->
			BlockPresentationResolution.handled(subject("second", pos, state)));

		BlockPresentationResolverRegistry.Registration firstHandle = registry.register(first);
		BlockPresentationResolverRegistry.Registration secondHandle = registry.register(second);
		BlockPresentation presentation = registry.resolve(world(Map.of(pos, state)), sourceSpec(pos, state, "block"));

		assertTrue(firstHandle.accepted());
		assertTrue(secondHandle.accepted());
		assertEquals(List.of(first, second), registry.snapshot());
		assertEquals("first", presentation.renderSubjects().get(0).subjectId());
	}

	@Test
	void unhandledResolverForwardsToLaterHandledResolver() {
		BlockPresentationResolverRegistry registry = new BlockPresentationResolverRegistry(false);
		BlockPos pos = new BlockPos(31, 70, 32);
		BlockState state = Blocks.STONE.defaultBlockState();
		int[] forwarded = {0};
		registry.register(resolver("test:unhandled", context -> BlockPresentationResolution.UNHANDLED));
		registry.register(resolver("test:later", context -> {
			forwarded[0]++;
			return BlockPresentationResolution.handled(subject("later", pos, state));
		}));

		BlockPresentation presentation = registry.resolve(world(Map.of(pos, state)), sourceSpec(pos, state, "block"));

		assertEquals(1, forwarded[0]);
		assertEquals("later", presentation.renderSubjects().get(0).subjectId());
	}

	@Test
	void recoverableExceptionForwardsToLaterHandledResolver() {
		BlockPresentationResolverRegistry registry = new BlockPresentationResolverRegistry(false);
		BlockPos pos = new BlockPos(33, 70, 34);
		BlockState state = Blocks.STONE.defaultBlockState();
		registry.register(resolver("test:exception", context -> {
			throw new IllegalStateException("recoverable");
		}));
		registry.register(resolver("test:later-exception", context ->
			BlockPresentationResolution.handled(subject("after-exception", pos, state))));

		BlockPresentation presentation = registry.resolve(world(Map.of(pos, state)), sourceSpec(pos, state, "block"));

		assertEquals("after-exception", presentation.renderSubjects().get(0).subjectId());
	}

	@Test
	void linkageErrorForwardsToLaterHandledResolver() {
		BlockPresentationResolverRegistry registry = new BlockPresentationResolverRegistry(false);
		BlockPos pos = new BlockPos(35, 70, 36);
		BlockState state = Blocks.STONE.defaultBlockState();
		registry.register(resolver("test:linkage", context -> {
			throw new LinkageError("optional linkage failure");
		}));
		registry.register(resolver("test:later-linkage", context ->
			BlockPresentationResolution.handled(subject("after-linkage", pos, state))));

		BlockPresentation presentation = registry.resolve(world(Map.of(pos, state)), sourceSpec(pos, state, "block"));

		assertEquals("after-linkage", presentation.renderSubjects().get(0).subjectId());
	}

	@Test
	void handledEmptyDoesNotFallBackToDirect() {
		BlockPresentationResolverRegistry registry = new BlockPresentationResolverRegistry(false);
		BlockPos pos = new BlockPos(32, 70, 33);
		BlockState state = Blocks.STONE.defaultBlockState();
		registry.register(resolver("test:empty", context ->
			BlockPresentationResolution.handled(List.of())));

		BlockPresentation presentation = registry.resolve(world(Map.of(pos, state)), sourceSpec(pos, state, "block"));

		assertTrue(presentation.renderSubjects().isEmpty());
	}

	@Test
	void unregisterRemovesOnlyThatResolverAndIsIdempotent() {
		BlockPresentationResolverRegistry registry = new BlockPresentationResolverRegistry(false);
		BlockPos pos = new BlockPos(34, 70, 35);
		BlockState state = Blocks.STONE.defaultBlockState();
		BlockPresentationResolver custom = resolver("test:temporary", context ->
			BlockPresentationResolution.handled(subject("temporary", pos, state)));
		BlockPresentationResolverRegistry.Registration handle = registry.register(custom);

		handle.close();
		handle.close();
		BlockPresentation presentation = registry.resolve(world(Map.of(pos, state)), sourceSpec(pos, state, "block"));

		assertTrue(registry.snapshot().isEmpty());
		assertEquals("direct", presentation.renderSubjects().get(0).subjectId());
		assertTrue(handle.accepted());
	}

	@Test
	void reRegistrationAfterIdempotentCloseIsAppended() {
		BlockPresentationResolverRegistry registry = new BlockPresentationResolverRegistry(false);
		BlockPos pos = new BlockPos(37, 70, 38);
		BlockState state = Blocks.STONE.defaultBlockState();
		BlockPresentationResolver first = resolver("test:reorder", context ->
			BlockPresentationResolution.handled(subject("first", pos, state)));
		BlockPresentationResolver second = resolver("test:stable", context ->
			BlockPresentationResolution.handled(subject("second", pos, state)));
		BlockPresentationResolver replacement = resolver("test:reorder", context ->
			BlockPresentationResolution.handled(subject("replacement", pos, state)));
		BlockPresentationResolverRegistry.Registration firstHandle = registry.register(first);
		registry.register(second);

		firstHandle.close();
		firstHandle.close();
		BlockPresentationResolverRegistry.Registration replacementHandle = registry.register(replacement);

		assertTrue(replacementHandle.accepted());
		assertEquals(List.of(second, replacement), registry.snapshot());
	}

	@Test
	void throwingResolverFailsSoftToNextResolution() {
		BlockPresentationResolverRegistry registry = new BlockPresentationResolverRegistry(false);
		BlockPos pos = new BlockPos(36, 70, 37);
		BlockState state = Blocks.STONE.defaultBlockState();
		registry.register(resolver("test:throws", context -> {
			throw new IllegalStateException("optional resolver failure");
		}));

		BlockPresentation presentation = registry.resolve(world(Map.of(pos, state)), sourceSpec(pos, state, "block"));

		assertEquals("direct", presentation.renderSubjects().get(0).subjectId());
	}

	@Test
	void replacedSourceIsRejectedBeforeResolverCanClaim() {
		BlockPresentationResolverRegistry registry = new BlockPresentationResolverRegistry(false);
		BlockPos pos = new BlockPos(38, 70, 39);
		BlockState expected = Blocks.STONE.defaultBlockState();
		int[] calls = {0};
		registry.register(resolver("test:claim", context -> {
			calls[0]++;
			return BlockPresentationResolution.handled(subject("claimed", pos, context.sourceState()));
		}));

		BlockPresentation presentation = registry.resolve(
			world(Map.of(pos, Blocks.DIRT.defaultBlockState())), sourceSpec(pos, expected, "block"));

		assertEquals(0, calls[0]);
		assertTrue(presentation.renderSubjects().isEmpty());
	}

	@Test
	void compositeSubjectsHaveDistinctStableSuccessKeys() {
		BlockPos lowerPos = new BlockPos(40, 70, 41);
		BlockPos upperPos = lowerPos.above();
		BlockState lower = doorState(
			DoubleBlockHalf.LOWER, Direction.NORTH, DoorHingeSide.LEFT, false, false);
		BlockState upper = doorState(
			DoubleBlockHalf.UPPER, Direction.NORTH, DoorHingeSide.LEFT, false, false);
		BlockOutlineSpec source = sourceSpec(lowerPos, lower, "block");
		BlockPresentation presentation = new BlockPresentationResolverRegistry()
			.resolve(world(Map.of(lowerPos, lower, upperPos, upper)), source);

		BlockPresentationSuccessKey lowerKey = presentation.renderSubjects().get(0).successKey(source);
		BlockPresentationSuccessKey upperKey = presentation.renderSubjects().get(1).successKey(source);

		assertEquals(source.blockKey(), lowerKey.sourceKey());
		assertEquals(source.blockKey(), upperKey.sourceKey());
		assertNotEquals(lowerKey, upperKey);
		assertNotEquals(lowerKey.subjectId(), upperKey.subjectId());
	}

	private static Stream<Arguments> doorCases() {
		return Stream.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
			.flatMap(facing -> Stream.of(false, true)
				.flatMap(open -> Stream.of(false, true)
					.flatMap(powered -> Stream.of(DoorHingeSide.LEFT, DoorHingeSide.RIGHT)
						.flatMap(hinge -> Stream.of(DoubleBlockHalf.LOWER, DoubleBlockHalf.UPPER)
							.map(sourceHalf -> Arguments.of(facing, open, powered, hinge, sourceHalf))))));
	}

	private static Stream<Arguments> invalidDoorNeighborCases() {
		return Stream.of(DoorNeighborMismatch.values()).map(Arguments::of);
	}

	private static Stream<Arguments> bedFacings() {
		return Stream.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
			.flatMap(facing -> Stream.of(BedPart.FOOT, BedPart.HEAD)
				.map(sourcePart -> Arguments.of(facing, sourcePart)));
	}

	private static Stream<Arguments> invalidBedNeighborCases() {
		return Stream.of(BedNeighborMismatch.values()).map(Arguments::of);
	}

	private enum DoorNeighborMismatch {
		HALF,
		FACING,
		HINGE,
		OPEN,
		POWERED,
		DIFFERENT_BLOCK
	}

	private enum BedNeighborMismatch {
		PART,
		FACING,
		DIFFERENT_BLOCK
	}

	private static BlockState invalidDoorUpper(DoorNeighborMismatch mismatch) {
		return switch (mismatch) {
			case HALF -> doorState(
				DoubleBlockHalf.LOWER, Direction.NORTH, DoorHingeSide.LEFT, false, false);
			case FACING -> doorState(
				DoubleBlockHalf.UPPER, Direction.SOUTH, DoorHingeSide.LEFT, false, false);
			case HINGE -> doorState(
				DoubleBlockHalf.UPPER, Direction.NORTH, DoorHingeSide.RIGHT, false, false);
			case OPEN -> doorState(
				DoubleBlockHalf.UPPER, Direction.NORTH, DoorHingeSide.LEFT, true, false);
			case POWERED -> doorState(
				DoubleBlockHalf.UPPER, Direction.NORTH, DoorHingeSide.LEFT, false, true);
			case DIFFERENT_BLOCK -> withDoorProperties(
				Blocks.BIRCH_DOOR.defaultBlockState(),
				DoubleBlockHalf.UPPER, Direction.NORTH, DoorHingeSide.LEFT, false, false);
		};
	}

	private static BlockState invalidBedHead(BedNeighborMismatch mismatch) {
		return switch (mismatch) {
			case PART -> bedState(BedPart.FOOT, Direction.EAST, false);
			case FACING -> bedState(BedPart.HEAD, Direction.WEST, false);
			case DIFFERENT_BLOCK -> withBedProperties(
				Blocks.WHITE_BED.defaultBlockState(), BedPart.HEAD, Direction.EAST, false);
		};
	}

	private static BlockState doorState(
		DoubleBlockHalf half,
		Direction facing,
		DoorHingeSide hinge,
		boolean open,
		boolean powered
	) {
		return withDoorProperties(
			Blocks.OAK_DOOR.defaultBlockState(), half, facing, hinge, open, powered);
	}

	private static BlockState withDoorProperties(
		BlockState state,
		DoubleBlockHalf half,
		Direction facing,
		DoorHingeSide hinge,
		boolean open,
		boolean powered
	) {
		return state
			.setValue(DoorBlock.HALF, half)
			.setValue(DoorBlock.FACING, facing)
			.setValue(DoorBlock.HINGE, hinge)
			.setValue(DoorBlock.OPEN, open)
			.setValue(DoorBlock.POWERED, powered);
	}

	private static BlockState bedState(BedPart part, Direction facing, boolean occupied) {
		return withBedProperties(Blocks.RED_BED.defaultBlockState(), part, facing, occupied);
	}

	private static BlockState withBedProperties(
		BlockState state,
		BedPart part,
		Direction facing,
		boolean occupied
	) {
		return state
			.setValue(BedBlock.PART, part)
			.setValue(BedBlock.FACING, facing)
			.setValue(BedBlock.OCCUPIED, occupied);
	}

	private static BlockOutlineSpec sourceSpec(BlockPos pos, BlockState state, String targetTypeId) {
		return new BlockOutlineSpec(
			new MarkerId(NEXT_MARKER_ID.getAndIncrement()),
			new TargetKey.BlockKey(DIMENSION, pos.getX(), pos.getY(), pos.getZ(), registryId(state)),
			targetTypeId,
			"attention",
			0xFF123456);
	}

	private static String registryId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static BlockRenderSubject subject(String id, BlockPos pos, BlockState state) {
		return new BlockRenderSubject(
			id, pos, state, registryId(state), "block", BlockPresentationRelation.DIRECT);
	}

	private static BlockPresentationResolver resolver(
		String id,
		Function<BlockPresentationContext, BlockPresentationResolution> resolve
	) {
		return BlockPresentationResolver.of(id, resolve);
	}

	private static BlockGetter world(Map<BlockPos, BlockState> states) {
		Map<BlockPos, BlockState> copy = new HashMap<>(states);
		return (BlockGetter) Proxy.newProxyInstance(
			BlockGetter.class.getClassLoader(),
			new Class<?>[] {BlockGetter.class},
			(proxy, method, arguments) -> {
				switch (method.getName()) {
					case "getBlockState":
						return copy.getOrDefault((BlockPos) arguments[0], Blocks.AIR.defaultBlockState());
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
