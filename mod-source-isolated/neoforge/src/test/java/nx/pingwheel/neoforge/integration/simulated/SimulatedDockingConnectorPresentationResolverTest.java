package nx.pingwheel.neoforge.integration.simulated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import nx.pingwheel.common.client.outline.BlockPresentationContext;
import nx.pingwheel.common.client.outline.BlockPresentationRelation;
import nx.pingwheel.common.client.outline.BlockPresentationResolution;
import nx.pingwheel.common.client.outline.BlockRenderSubject;
import nx.pingwheel.common.client.outline.BlockOutlineSpec;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;

class SimulatedDockingConnectorPresentationResolverTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final AtomicLong NEXT_MARKER_ID = new AtomicLong(1L);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void validPairedConnectorRedirectsToExactlyOneOwnerSubject() {
		BlockPos pairedPos = new BlockPos(4, 64, 7);
		Direction towardOwner = Direction.EAST;
		BlockPos ownerPos = pairedPos.relative(towardOwner);
		BlockState pairedState = pairedState(towardOwner);
		BlockState ownerState = ownerState(towardOwner.getOpposite(), true);
		BlockEntity ownerBlockEntity = blockEntity(ownerTypeStandIn(), ownerPos, ownerState);

		BlockPresentationResolution resolution = resolve(
			newResolver(), pairedPos, pairedState, ownerPos, ownerState, ownerBlockEntity);

		assertTrue(resolution.handled());
		assertEquals(1, resolution.subjects().size());
		BlockRenderSubject subject = resolution.subjects().get(0);
		assertEquals(ownerPos, subject.blockPos());
		assertSame(ownerState, subject.blockState());
		assertEquals(registryId(ownerStandIn()), subject.expectedBlockRegistryId());
		assertEquals("entity_block", subject.renderTargetTypeId());
		assertEquals(BlockPresentationRelation.PROXY_TO_OWNER, subject.relation());
		assertEquals(towardOwner, pairedState.getValue(BlockStateProperties.FACING));
		assertEquals(towardOwner.getOpposite(), ownerState.getValue(BlockStateProperties.FACING));
	}

	@Test
	void poweredFalseIsAClaimedEmptyPresentation() {
		BlockPos pairedPos = new BlockPos(8, 64, 9);
		Direction towardOwner = Direction.NORTH;
		BlockPos ownerPos = pairedPos.relative(towardOwner);

		assertHandledEmpty(resolve(
			newResolver(), pairedPos, pairedState(towardOwner), ownerPos,
			ownerState(towardOwner.getOpposite(), false), null));
	}

	@Test
	void ownerFacingMismatchIsAClaimedEmptyPresentation() {
		BlockPos pairedPos = new BlockPos(10, 64, 11);
		Direction towardOwner = Direction.SOUTH;
		BlockPos ownerPos = pairedPos.relative(towardOwner);

		assertHandledEmpty(resolve(
			newResolver(), pairedPos, pairedState(towardOwner), ownerPos,
			ownerState(towardOwner, true), null));
	}

	@Test
	void missingPairedFacingIsAClaimedEmptyPresentation() {
		BlockPos pairedPos = new BlockPos(12, 64, 13);

		assertHandledEmpty(resolve(
			new SimulatedDockingConnectorPresentationResolver(
				Blocks.STONE, ownerStandIn(), ownerTypeStandIn()),
			pairedPos, Blocks.STONE.defaultBlockState(), null, null, null));
	}

	@Test
	void missingOwnerFacingIsAClaimedEmptyPresentation() {
		BlockPos pairedPos = new BlockPos(14, 64, 15);
		Direction towardOwner = Direction.WEST;
		BlockPos ownerPos = pairedPos.relative(towardOwner);

		assertHandledEmpty(resolve(
			new SimulatedDockingConnectorPresentationResolver(
				pairedStandIn(), Blocks.STONE, ownerTypeStandIn()),
			pairedPos, pairedState(towardOwner), ownerPos, Blocks.STONE.defaultBlockState(), null));
	}

	@Test
	void missingOwnerPoweredPropertyIsAClaimedEmptyPresentation() {
		BlockPos pairedPos = new BlockPos(16, 64, 17);
		Direction towardOwner = Direction.UP;
		BlockPos ownerPos = pairedPos.relative(towardOwner);
		BlockState ownerState = Blocks.DISPENSER.defaultBlockState()
			.setValue(BlockStateProperties.FACING, towardOwner.getOpposite());

		assertHandledEmpty(resolve(
			new SimulatedDockingConnectorPresentationResolver(
				pairedStandIn(), Blocks.DISPENSER, ownerTypeStandIn()),
			pairedPos, pairedState(towardOwner), ownerPos, ownerState, null));
	}

	@Test
	void wrongOwnerBlockOrIdIsAClaimedEmptyPresentation() {
		BlockPos pairedPos = new BlockPos(18, 64, 19);
		Direction towardOwner = Direction.DOWN;
		BlockPos ownerPos = pairedPos.relative(towardOwner);
		BlockState wrongOwnerState = Blocks.DROPPER.defaultBlockState()
			.setValue(BlockStateProperties.FACING, towardOwner.getOpposite());

		assertHandledEmpty(resolve(
			newResolver(), pairedPos, pairedState(towardOwner), ownerPos, wrongOwnerState, null));
	}

	@Test
	void missingOwnerBlockEntityIsAClaimedEmptyPresentation() {
		BlockPos pairedPos = new BlockPos(20, 64, 21);
		Direction towardOwner = Direction.EAST;
		BlockPos ownerPos = pairedPos.relative(towardOwner);

		assertHandledEmpty(resolve(
			newResolver(), pairedPos, pairedState(towardOwner), ownerPos,
			ownerState(towardOwner.getOpposite(), true), null));
	}

	@Test
	void wrongOwnerBlockEntityTypeIsAClaimedEmptyPresentation() {
		BlockPos pairedPos = new BlockPos(22, 64, 23);
		Direction towardOwner = Direction.NORTH;
		BlockPos ownerPos = pairedPos.relative(towardOwner);
		BlockState ownerState = ownerState(towardOwner.getOpposite(), true);

		assertHandledEmpty(resolve(
			newResolver(), pairedPos, pairedState(towardOwner), ownerPos, ownerState,
			blockEntity(BlockEntityType.BEACON, ownerPos, ownerState)));
	}

	@Test
	void directOwnerSourceIsUnhandled() {
		BlockPos ownerPos = new BlockPos(24, 64, 25);
		BlockState ownerState = ownerState(Direction.NORTH, true);

		BlockPresentationResolution resolution = resolve(
			newResolver(), ownerPos, ownerState, null, null, null);

		assertFalse(resolution.handled());
		assertTrue(resolution.subjects().isEmpty());
	}

	private static SimulatedDockingConnectorPresentationResolver newResolver() {
		return new SimulatedDockingConnectorPresentationResolver(
			pairedStandIn(), ownerStandIn(), ownerTypeStandIn());
	}

	private static BlockState pairedState(Direction towardOwner) {
		return pairedStandIn().defaultBlockState()
			.setValue(BlockStateProperties.FACING, towardOwner);
	}

	private static BlockState ownerState(Direction facing, boolean powered) {
		return ownerStandIn().defaultBlockState()
			.setValue(BlockStateProperties.FACING, facing)
			.setValue(BlockStateProperties.POWERED, powered);
	}

	private static Block pairedStandIn() {
		return Blocks.DISPENSER;
	}

	private static Block ownerStandIn() {
		return Blocks.OBSERVER;
	}

	private static BlockEntityType<?> ownerTypeStandIn() {
		return BlockEntityType.SIGN;
	}

	private static BlockEntity blockEntity(
		BlockEntityType<?> type,
		BlockPos pos,
		BlockState state
	) {
		return new BlockEntity(type, pos, state) {};
	}

	private static BlockPresentationResolution resolve(
		SimulatedDockingConnectorPresentationResolver resolver,
		BlockPos pairedPos,
		BlockState pairedState,
		BlockPos ownerPos,
		BlockState ownerState,
		BlockEntity ownerBlockEntity
	) {
		Map<BlockPos, BlockState> states = new HashMap<>();
		states.put(pairedPos, pairedState);
		if (ownerPos != null && ownerState != null) {
			states.put(ownerPos, ownerState);
		}
		Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
		if (ownerPos != null && ownerBlockEntity != null) {
			blockEntities.put(ownerPos, ownerBlockEntity);
		}
		return resolver.resolve(new BlockPresentationContext(
			world(states, blockEntities), sourceSpec(pairedPos, pairedState)));
	}

	private static void assertHandledEmpty(BlockPresentationResolution resolution) {
		assertTrue(resolution.handled());
		assertTrue(resolution.subjects().isEmpty());
	}

	private static BlockOutlineSpec sourceSpec(BlockPos pos, BlockState state) {
		return new BlockOutlineSpec(
			new MarkerId(NEXT_MARKER_ID.getAndIncrement()),
			new TargetKey.BlockKey(DIMENSION, pos.getX(), pos.getY(), pos.getZ(), registryId(state)),
			"block",
			"attention",
			0xFF123456);
	}

	private static String registryId(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	private static String registryId(BlockState state) {
		return registryId(state.getBlock());
	}

	private static BlockGetter world(
		Map<BlockPos, BlockState> states,
		Map<BlockPos, BlockEntity> blockEntities
	) {
		Map<BlockPos, BlockState> stateCopy = new HashMap<>(states);
		Map<BlockPos, BlockEntity> blockEntityCopy = new HashMap<>(blockEntities);
		return (BlockGetter) Proxy.newProxyInstance(
			BlockGetter.class.getClassLoader(),
			new Class<?>[] {BlockGetter.class},
			(proxy, method, arguments) -> {
				switch (method.getName()) {
					case "getBlockState":
						return stateCopy.getOrDefault(
							(BlockPos) arguments[0], Blocks.AIR.defaultBlockState());
					case "getBlockEntity":
						return blockEntityCopy.get(arguments[0]);
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
