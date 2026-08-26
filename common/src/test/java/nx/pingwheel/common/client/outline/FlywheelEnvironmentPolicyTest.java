package nx.pingwheel.common.client.outline;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywheelEnvironmentPolicyTest {
	private static final BlockPos TARGET = new BlockPos(20_480_000, 64, -20_480_000);

	@Test
	void acceptsOnlyTheExpectedEnvironmentAndTransformCombinations() {
		assertTrue(FlywheelEnvironmentPolicy.accepts(false, true, TARGET, null));
		assertFalse(FlywheelEnvironmentPolicy.accepts(
			false, false, TARGET, new Vec3i(TARGET.getX(), TARGET.getY(), TARGET.getZ())));
		assertFalse(FlywheelEnvironmentPolicy.accepts(
			true, true, TARGET, new Vec3i(TARGET.getX(), TARGET.getY(), TARGET.getZ())));
		assertTrue(FlywheelEnvironmentPolicy.accepts(
			true, false, TARGET,
			new Vec3i(TARGET.getX() + 4_096, TARGET.getY(), TARGET.getZ())));
		assertFalse(FlywheelEnvironmentPolicy.accepts(
			true, false, TARGET,
			new Vec3i(TARGET.getX() + 4_097, TARGET.getY(), TARGET.getZ())));
	}

	@Test
	void transformedEnvironmentsRequireAllOriginDataAndUseManhattanDistance() {
		assertFalse(FlywheelEnvironmentPolicy.accepts(true, false, TARGET, null));
		assertFalse(FlywheelEnvironmentPolicy.accepts(
			true, false, null, new Vec3i(TARGET.getX(), TARGET.getY(), TARGET.getZ())));
		assertTrue(FlywheelEnvironmentPolicy.accepts(
			true, false, TARGET,
			new Vec3i(TARGET.getX() + 2_000, TARGET.getY() + 1_000, TARGET.getZ() + 1_096)));
		assertFalse(FlywheelEnvironmentPolicy.accepts(
			true, false, TARGET,
			new Vec3i(TARGET.getX() + 2_000, TARGET.getY() + 1_000, TARGET.getZ() + 1_097)));
	}

	@Test
	void transformedPlotCenterGuardCoversTheSquareCornerAndVerticalExtentAtTheExactLimit() {
		int halfPlotSide = 64 * 16;
		int verticalExtent = 2_048;

		assertTrue(FlywheelEnvironmentPolicy.accepts(
			true, false, TARGET,
			new Vec3i(
				TARGET.getX() + halfPlotSide,
				TARGET.getY() + verticalExtent,
				TARGET.getZ() + halfPlotSide)));
		assertTrue(FlywheelEnvironmentPolicy.accepts(
			true, false, TARGET,
			new Vec3i(
				TARGET.getX() - halfPlotSide,
				TARGET.getY() - verticalExtent,
				TARGET.getZ() - halfPlotSide)));

		assertFalse(FlywheelEnvironmentPolicy.accepts(
			true, false, TARGET,
			new Vec3i(
				TARGET.getX() + halfPlotSide,
				TARGET.getY() + verticalExtent + 1,
				TARGET.getZ() + halfPlotSide)));
		assertFalse(FlywheelEnvironmentPolicy.accepts(
			true, false, TARGET,
			new Vec3i(
				TARGET.getX() + halfPlotSide + 1,
				TARGET.getY() + verticalExtent,
				TARGET.getZ() + halfPlotSide)));
	}
}
