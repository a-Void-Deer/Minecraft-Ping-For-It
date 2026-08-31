package nx.pingwheel.common.client.outline;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static nx.pingwheel.common.client.outline.BlockModelOutlineRoute.BLOCK_DISPLAY;
import static nx.pingwheel.common.client.outline.BlockModelOutlineRoute.ENTITY_BLOCK;
import static nx.pingwheel.common.client.outline.BlockModelOutlineRoute.VOXEL;

/**
 * Headless tests for the pure per-frame block outline route policy.
 */
class BlockModelOutlineRouteTest {

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	// --- routing ---

	@Test
	void entityBlockUsesTheEffectiveNativeGlowDecision() {
		assertEquals(VOXEL, BlockModelOutlineRoute.route("entity_block", false));
		assertEquals(ENTITY_BLOCK, BlockModelOutlineRoute.route("entity_block", true));
	}

	@Test
	void plainBlockRoutesToBlockDisplayOnlyWhenWhitelisted() {
		assertEquals(BLOCK_DISPLAY, BlockModelOutlineRoute.route("block", true));
		assertEquals(VOXEL, BlockModelOutlineRoute.route("block", false));
	}

	@Test
	void externalOrdinaryBlockKeepsTheSharedPolicyAndModelGate() {
		assertEquals(
			BLOCK_DISPLAY,
			BlockModelOutlineRoute.routeExternal("block", true, true));
		assertEquals(
			VOXEL,
			BlockModelOutlineRoute.routeExternal("block", false, true));
		assertEquals(
			VOXEL,
			BlockModelOutlineRoute.routeExternal("block", true, false));
		assertEquals(
			VOXEL,
			BlockModelOutlineRoute.routeExternal("block", false, false));
	}

	@Test
	void externalEntityBlockUsesTheRunnerEvenWithoutAStaticModel() {
		assertEquals(
			ENTITY_BLOCK,
			BlockModelOutlineRoute.routeExternal("entity_block", true, true));
		assertEquals(
			ENTITY_BLOCK,
			BlockModelOutlineRoute.routeExternal("entity_block", true, false));
		assertEquals(
			VOXEL,
			BlockModelOutlineRoute.routeExternal("entity_block", false, true));
		assertEquals(
			VOXEL,
			BlockModelOutlineRoute.routeExternal("entity_block", false, false));
	}

	@Test
	void externalUnknownTargetTypeNeverEntersTheModelRoute() {
		assertEquals(
			VOXEL,
			BlockModelOutlineRoute.routeExternal("unknown_type", true, true));
	}

	@Test
	void nonBlockTargetTypesAlwaysRouteToVoxel() {
		for (String targetTypeId : new String[] {"entity", "location", "dropped_item", "", "unknown_type"}) {
			assertEquals(VOXEL, BlockModelOutlineRoute.route(targetTypeId, true), "should be voxel: '" + targetTypeId + "'");
			assertEquals(VOXEL, BlockModelOutlineRoute.route(targetTypeId, false), "should be voxel: '" + targetTypeId + "'");
		}
	}

	// --- block rendering participation ---

	@Test
	void onlyBlockAndEntityBlockParticipateInBlockRendering() {
		assertTrue(BlockModelOutlineRoute.acceptsForBlockRendering("block"));
		assertTrue(BlockModelOutlineRoute.acceptsForBlockRendering("entity_block"));

		for (String targetTypeId : new String[] {"entity", "location", "dropped_item", "", "unknown_type"}) {
			assertFalse(BlockModelOutlineRoute.acceptsForBlockRendering(targetTypeId), "should reject: '" + targetTypeId + "'");
		}
	}

	@Test
	void nullTargetTypeIdIsRejected() {
		assertThrows(NullPointerException.class, () -> BlockModelOutlineRoute.route(null, true));
		assertThrows(NullPointerException.class, () -> BlockModelOutlineRoute.acceptsForBlockRendering(null));
	}

	@Test
	void sourceBlockPresentationCanRouteItsSubjectAsEntityBlockAtSubjectPosition() {
		BlockPos sourcePos = new BlockPos(1, 2, 3);
		BlockPos subjectPos = new BlockPos(7, 8, 9);
		BlockOutlineSpec source = new BlockOutlineSpec(
			new MarkerId(1L),
			new TargetKey.BlockKey(
				"minecraft:overworld", sourcePos.getX(), sourcePos.getY(), sourcePos.getZ(),
				"minecraft:stone"),
			"block",
			"attention",
			0xFF123456);
		BlockRenderSubject subject = new BlockRenderSubject(
			"entity-subject",
			subjectPos,
			Blocks.STONE.defaultBlockState(),
			"minecraft:stone",
			"entity_block",
			BlockPresentationRelation.PROXY_TO_OWNER);
		BlockPresentation presentation = new BlockPresentation(source, List.of(subject));

		assertEquals(ENTITY_BLOCK, BlockModelOutlineRoute.route(subject.renderTargetTypeId(), true));
		assertEquals(subjectPos, presentation.renderSubjects().get(0).renderPos());
		assertEquals("block", presentation.sourceSpec().targetTypeId());
	}

	@Test
	void resolvedBedSubjectTypesDriveEntityAndOrdinaryRoutes() {
		BlockPos footPos = new BlockPos(15, 64, 16);
		BlockPos headPos = footPos.relative(Direction.EAST);
		BlockState foot = bedState(BedPart.FOOT, Direction.EAST);
		BlockState head = bedState(BedPart.HEAD, Direction.EAST);
		BlockGetter world = world(Map.of(footPos, foot, headPos, head));

		BlockPresentation entityBlockPresentation = new BlockPresentationResolverRegistry()
			.resolve(world, sourceSpec(footPos, foot, "entity_block"));
		assertEquals(2, entityBlockPresentation.renderSubjects().size());
		for (BlockRenderSubject subject : entityBlockPresentation.renderSubjects()) {
			assertEquals(ENTITY_BLOCK,
				BlockModelOutlineRoute.route(subject.renderTargetTypeId(), true));
			assertEquals(VOXEL,
				BlockModelOutlineRoute.route(subject.renderTargetTypeId(), false));
		}

		BlockPresentation ordinaryPresentation = new BlockPresentationResolverRegistry()
			.resolve(world, sourceSpec(footPos, foot, "block"));
		assertEquals(2, ordinaryPresentation.renderSubjects().size());
		for (BlockRenderSubject subject : ordinaryPresentation.renderSubjects()) {
			assertEquals(BLOCK_DISPLAY,
				BlockModelOutlineRoute.route(subject.renderTargetTypeId(), true));
		}
	}

	private static BlockState bedState(BedPart part, Direction facing) {
		return Blocks.RED_BED.defaultBlockState()
			.setValue(BedBlock.PART, part)
			.setValue(BedBlock.FACING, facing)
			.setValue(BedBlock.OCCUPIED, false);
	}

	private static BlockOutlineSpec sourceSpec(BlockPos pos, BlockState state, String targetTypeId) {
		return new BlockOutlineSpec(
			new MarkerId(2L),
			new TargetKey.BlockKey(
				"minecraft:overworld", pos.getX(), pos.getY(), pos.getZ(), registryId(state)),
			targetTypeId,
			"attention",
			0xFF123456);
	}

	private static String registryId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
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
