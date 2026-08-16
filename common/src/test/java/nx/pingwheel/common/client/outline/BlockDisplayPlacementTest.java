package nx.pingwheel.common.client.outline;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Headless tests for the pure camera-relative placement math of the virtual
 * {@code BlockDisplay} model-glow route: the exact application of nonzero
 * x/z and negative y model offsets, the camera subtraction per axis,
 * determinism, and the guarantee that the model offset is applied exactly
 * once (never doubled) on top of the integer block MIN corner.
 */
class BlockDisplayPlacementTest {

	private static final double EPSILON = 1.0E-9;

	@Test
	void appliesNonzeroXAndZOffsetsExactly() {
		BlockPos pos = new BlockPos(3, 5, -7);
		Vec3 cameraPosition = new Vec3(10.5, 5.0, 2.25);
		Vec3 modelOffset = new Vec3(0.4, 0.0, 0.3);

		Vec3 result = BlockDisplayPlacement.cameraRelative(pos, cameraPosition, modelOffset);

		// 3 - 10.5 + 0.4 = -7.1; 5 - 5.0 + 0.0 = 0.0; -7 - 2.25 + 0.3 = -8.95.
		assertEquals(-7.1, result.x, EPSILON);
		assertEquals(0.0, result.y, EPSILON);
		assertEquals(-8.95, result.z, EPSILON);
	}

	@Test
	void appliesNegativeYOffsetExactly() {
		BlockPos pos = new BlockPos(1, 5, 2);
		Vec3 cameraPosition = Vec3.ZERO;
		Vec3 modelOffset = new Vec3(0.0, -0.125, 0.0);

		Vec3 result = BlockDisplayPlacement.cameraRelative(pos, cameraPosition, modelOffset);

		assertEquals(1.0, result.x, EPSILON);
		assertEquals(4.875, result.y, EPSILON);
		assertEquals(2.0, result.z, EPSILON);
	}

	@Test
	void subtractsCameraPositionFromEachAxis() {
		BlockPos pos = new BlockPos(-4, 64, 12);
		Vec3 cameraPosition = new Vec3(100.25, -3.5, 0.125);
		Vec3 modelOffset = Vec3.ZERO;

		Vec3 result = BlockDisplayPlacement.cameraRelative(pos, cameraPosition, modelOffset);

		// -4 - 100.25 = -104.25; 64 - (-3.5) = 67.5; 12 - 0.125 = 11.875.
		assertEquals(-104.25, result.x, EPSILON);
		assertEquals(67.5, result.y, EPSILON);
		assertEquals(11.875, result.z, EPSILON);
	}

	@Test
	void zeroOffsetPreservesIntegerMinCornerPlacement() {
		BlockPos pos = new BlockPos(8, -10, 0);
		Vec3 cameraPosition = new Vec3(2.0, 0.0, 6.0);

		Vec3 result = BlockDisplayPlacement.cameraRelative(pos, cameraPosition, Vec3.ZERO);

		// The pre-offset behavior (block MIN corner minus camera) is unchanged.
		assertEquals(6.0, result.x, EPSILON);
		assertEquals(-10.0, result.y, EPSILON);
		assertEquals(-6.0, result.z, EPSILON);
	}

	@Test
	void isDeterministicAcrossCalls() {
		BlockPos pos = new BlockPos(3, 5, -7);
		Vec3 cameraPosition = new Vec3(10.5, 5.0, 2.25);
		Vec3 modelOffset = new Vec3(0.4, -0.125, 0.3);

		Vec3 first = BlockDisplayPlacement.cameraRelative(pos, cameraPosition, modelOffset);
		Vec3 second = BlockDisplayPlacement.cameraRelative(pos, cameraPosition, modelOffset);

		assertEquals(first.x, second.x, 0.0);
		assertEquals(first.y, second.y, 0.0);
		assertEquals(first.z, second.z, 0.0);
	}

	@Test
	void appliesOffsetExactlyOnceNotDoubled() {
		BlockPos pos = new BlockPos(3, 5, -7);
		Vec3 cameraPosition = new Vec3(10.5, 5.0, 2.25);
		Vec3 modelOffset = new Vec3(0.4, -0.125, 0.3);

		Vec3 single = BlockDisplayPlacement.cameraRelative(pos, cameraPosition, modelOffset);
		Vec3 expectedOnce = new Vec3(
			pos.getX() - cameraPosition.x + modelOffset.x,
			pos.getY() - cameraPosition.y + modelOffset.y,
			pos.getZ() - cameraPosition.z + modelOffset.z);
		Vec3 doubled = new Vec3(
			pos.getX() - cameraPosition.x + modelOffset.x * 2.0,
			pos.getY() - cameraPosition.y + modelOffset.y * 2.0,
			pos.getZ() - cameraPosition.z + modelOffset.z * 2.0);

		assertEquals(expectedOnce.x, single.x, 0.0);
		assertEquals(expectedOnce.y, single.y, 0.0);
		assertEquals(expectedOnce.z, single.z, 0.0);
		assertNotEquals(doubled.x, single.x);
		assertNotEquals(doubled.y, single.y);
		assertNotEquals(doubled.z, single.z);
	}

	@Test
	void rejectsNullArguments() {
		assertThrows(NullPointerException.class, () -> BlockDisplayPlacement.cameraRelative(null, Vec3.ZERO, Vec3.ZERO));
		assertThrows(NullPointerException.class, () -> BlockDisplayPlacement.cameraRelative(BlockPos.ZERO, null, Vec3.ZERO));
		assertThrows(NullPointerException.class, () -> BlockDisplayPlacement.cameraRelative(BlockPos.ZERO, Vec3.ZERO, null));
	}
}
