package nx.pingwheel.common.math;

import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLocalShapeRaycasterTest {

	private static final String BLOCK_ID = "minecraft:stone";
	private static BlockState state;

	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		state = Blocks.STONE.defaultBlockState();
	}

	@Test
	void intersectsAProtrudingNativeShapeWithoutUnitBlockBroadRejection() {
		Optional<LocalGeometryHit> hit = trace(
			Shapes.box(-1.0, 0.25, 0.25, -0.5, 0.75, 0.75),
			new Vec3(-1.5, 0.5, 0.5), new Vec3(1.5, 0.5, 0.5), BlockPos.ZERO);

		assertTrue(hit.isPresent());
		assertEquals(1.0 / 6.0, hit.orElseThrow().t(), 0.0);
		assertEquals(new Vec3(-1.0, 0.5, 0.5), hit.orElseThrow().localPoint());
	}

	@Test
	void scansEveryNativeBoxAndChoosesTheClosestRatherThanFirstDeclaredBox() {
		VoxelShape fartherFirst = Shapes.or(
			Shapes.box(0.75, 0.25, 0.25, 0.9, 0.75, 0.75),
			Shapes.box(0.25, 0.25, 0.25, 0.4, 0.75, 0.75));

		Optional<LocalGeometryHit> hit = trace(
			fartherFirst, new Vec3(0.0, 0.5, 0.5), new Vec3(1.0, 0.5, 0.5), BlockPos.ZERO);

		assertTrue(hit.isPresent());
		assertEquals(0.25, hit.orElseThrow().t(), 0.0);
	}

	@Test
	void retainsExactInteriorStartAtZeroButRejectsAnOutwardFaceContact() {
		VoxelShape box = Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

		Optional<LocalGeometryHit> inside = trace(
			box, new Vec3(0.5, 0.5, 0.5), new Vec3(2.0, 0.5, 0.5), BlockPos.ZERO);
		Optional<LocalGeometryHit> outward = trace(
			box, new Vec3(0.0, 0.5, 0.5), new Vec3(-1.0, 0.5, 0.5), BlockPos.ZERO);
		Optional<LocalGeometryHit> inward = trace(
			box, new Vec3(0.0, 0.5, 0.5), new Vec3(1.0, 0.5, 0.5), BlockPos.ZERO);

		assertEquals(0.0, inside.orElseThrow().t(), 0.0);
		assertFalse(outward.isPresent());
		assertEquals(0.0, inward.orElseThrow().t(), 0.0);
	}

	@Test
	void rejectsEndpointOnlyAndDegenerateSegmentsWithoutInflatingNativeSurfaces() {
		VoxelShape box = Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

		Optional<LocalGeometryHit> endpoint = trace(
			box, new Vec3(-1.0, 0.5, 0.5), new Vec3(0.0, 0.5, 0.5), BlockPos.ZERO);
		Optional<LocalGeometryHit> degenerate = trace(
			box, new Vec3(0.5, 0.5, 0.5), new Vec3(0.5, 0.5, 0.5), BlockPos.ZERO);

		assertFalse(endpoint.isPresent());
		assertFalse(degenerate.isPresent());
	}

	@Test
	void intersectsThinAndParallelNativeGeometryExactly() {
		// Native shape coordinates are quantized by Minecraft's discrete shape
		// representation, so use one supported 1/16th-wide thin box rather than
		// an unrepresentable decimal-width box.
		VoxelShape thin = Shapes.box(0.5, 0.0, 0.0, 0.5625, 1.0, 1.0);

		Optional<LocalGeometryHit> hit = trace(
			thin, new Vec3(0.0, 0.5, 0.5), new Vec3(1.0, 0.5, 0.5), BlockPos.ZERO);
		Optional<LocalGeometryHit> parallelMiss = trace(
			thin, new Vec3(0.0, 1.5, 0.5), new Vec3(1.0, 1.5, 0.5), BlockPos.ZERO);

		assertEquals(0.5, hit.orElseThrow().t(), 0.0);
		assertFalse(parallelMiss.isPresent());
	}

	private static Optional<LocalGeometryHit> trace(VoxelShape shape, Vec3 start, Vec3 end, BlockPos localPos) {
		return NativeLocalShapeRaycaster.traceNativeShape(
			shape, start, end, localPos, LocalGeometryKind.BLOCK, state, BLOCK_ID, Optional.empty());
	}
}
