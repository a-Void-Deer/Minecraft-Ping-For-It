package nx.pingwheel.common.client.outline;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * Loader-neutral acceptance rules for a Flywheel instance environment.
 *
 * <p>The optional adapter supplies the environment's identity and public
 * render origin without making this class depend on Flywheel. Main-world
 * instances are accepted only from the singleton global environment. An
 * externally transformed attempt may use a non-global environment only when
 * its integer render origin is plausibly the target plot position.</p>
 */
public final class FlywheelEnvironmentPolicy {
	public static final long MAX_RENDER_ORIGIN_MANHATTAN_DISTANCE = 4_096L;

	private FlywheelEnvironmentPolicy() {}

	/**
	 * Returns whether an instance environment may contribute to the attempt.
	 *
	 * <p>The main-world gate deliberately does not inspect the origin: the
	 * existing manager/global render-origin path validates that value separately.
	 * The transformed gate requires all position data because it must protect the
	 * external transform from a foreign embedding or an API unit mismatch.</p>
	 */
	public static boolean accepts(
		boolean hasExternalTransform,
		boolean globalEnvironment,
		BlockPos targetBlockPos,
		Vec3i environmentOrigin
	) {
		if (!hasExternalTransform) {
			return globalEnvironment;
		}

		if (globalEnvironment || targetBlockPos == null || environmentOrigin == null) {
			return false;
		}

		long distance = Math.abs((long) environmentOrigin.getX() - targetBlockPos.getX())
			+ Math.abs((long) environmentOrigin.getY() - targetBlockPos.getY())
			+ Math.abs((long) environmentOrigin.getZ() - targetBlockPos.getZ());
		return distance <= MAX_RENDER_ORIGIN_MANHATTAN_DISTANCE;
	}
}
