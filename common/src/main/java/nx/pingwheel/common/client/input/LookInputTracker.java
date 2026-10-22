package nx.pingwheel.common.client.input;

import java.util.Objects;

import nx.pingwheel.common.interaction.cancel.WorldVector;

/**
 * Pure accumulator for active mouse-look input during ordinary gameplay.
 *
 * <p>The tracker never reads the camera or a later ray. It stores the signed
 * net yaw/pitch deltas converted from raw mouse movement at the callback that
 * delivered them, so a press can later compare its first captured ray with a
 * virtual endpoint built from the net input. A round trip cancels exactly;
 * samples are not clamped while accumulating.
 *
 * <p>This class is client-thread confined in production, but has no Minecraft
 * dependency so its arithmetic can be tested headlessly.
 */
public final class LookInputTracker {

	private static final double SENSITIVITY_SLOPE = 0.6F;
	private static final double SENSITIVITY_BASE = 0.2F;
	private static final double ORDINARY_TURN_MULTIPLIER = 8.0;
	private static final float TURN_DEGREES = 0.15F;

	/** Immutable look-input baseline/current value; {@code generation} fences resets. */
	public record Sample(double yawDegrees, double pitchDegrees, long generation) {
		public Sample {
			if (!Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees)) {
				throw new IllegalArgumentException("look input samples must be finite");
			}
		}
	}

	private double yawDegrees;
	private double pitchDegrees;
	private long generation;

	/**
	 * Adds one callback's signed look delta. A non-finite input or an
	 * accumulation overflow is rejected and resets the whole baseline, so a
	 * broken event can never leave a partially valid value behind.
	 */
	public void acceptDegrees(double yawDegrees, double pitchDegrees) {
		if (!Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees)) {
			reset();
			return;
		}

		double nextYaw = this.yawDegrees + yawDegrees;
		double nextPitch = this.pitchDegrees + pitchDegrees;

		if (!Double.isFinite(nextYaw) || !Double.isFinite(nextPitch)) {
			reset();
			return;
		}

		this.yawDegrees = nextYaw;
		this.pitchDegrees = nextPitch;
	}

	/** Returns an immutable view of the current net input and its reset generation. */
	public Sample snapshot() {
		return new Sample(yawDegrees, pitchDegrees, generation);
	}

	/**
	 * Starts a new baseline. The generation changes so a start sample captured
	 * before a warp, capture transition, focus change or selector handoff can
	 * never be combined with a current sample from the new lifecycle.
	 */
	public void reset() {
		yawDegrees = 0.0;
		pitchDegrees = 0.0;
		generation++;
	}

	/**
	 * Converts one raw mouse-axis delta exactly as vanilla's non-smoothed
	 * {@code MouseHandler.turnPlayer}/{@code Entity.turn} path does for the
	 * current sensitivity. Scoping removes only the ordinary {@code 8x}
	 * multiplier; smoothing and direct camera rotations are deliberately not
	 * modelled.
	 */
	public static double degreesFromPixels(double deltaPixels, double sensitivity, boolean scoping) {
		if (!Double.isFinite(deltaPixels) || !Double.isFinite(sensitivity)) {
			return 0.0;
		}

		double scaled = sensitivity * SENSITIVITY_SLOPE + SENSITIVITY_BASE;
		double cubic = scaled * scaled * scaled;
		double turnScale = scoping ? cubic : cubic * ORDINARY_TURN_MULTIPLIER;

		return (float) (deltaPixels * turnScale) * TURN_DEGREES;
	}

	/**
	 * Returns the strict 3D angle between the first captured ray direction and
	 * the virtual endpoint built by applying {@code start -> current} net yaw
	 * and pitch to that direction.
	 *
	 * <p>The endpoint pitch is clamped once to vanilla's {@code [-90, 90]}
	 * camera range; the accumulator itself is never clamped. A generation
	 * difference means the samples belong to different baselines and yields
	 * no angle rather than a value across a lifecycle boundary.
	 */
	public static double angleBetween(WorldVector firstRayDirection, Sample start, Sample current) {
		Objects.requireNonNull(firstRayDirection, "firstRayDirection");
		Objects.requireNonNull(start, "start");
		Objects.requireNonNull(current, "current");

		double length = Math.sqrt(firstRayDirection.lengthSquared());

		if (!(length > 0.0) || !Double.isFinite(length)) {
			throw new IllegalArgumentException("firstRayDirection must be a finite non-zero vector");
		}

		if (start.generation() != current.generation()) {
			return 0.0;
		}

		double netYaw = current.yawDegrees() - start.yawDegrees();
		double netPitch = current.pitchDegrees() - start.pitchDegrees();

		if (!Double.isFinite(netYaw) || !Double.isFinite(netPitch)) {
			return 0.0;
		}

		if (netYaw == 0.0 && netPitch == 0.0) {
			return 0.0;
		}

		double baseYaw = Math.toDegrees(Math.atan2(
			-firstRayDirection.x(), firstRayDirection.z()));
		double basePitch = Math.toDegrees(Math.asin(clamp(
			-firstRayDirection.y() / length, -1.0, 1.0)));

		WorldVector source = new WorldVector(
			firstRayDirection.x() / length,
			firstRayDirection.y() / length,
			firstRayDirection.z() / length);
		WorldVector endpoint = viewDirection(
			baseYaw + netYaw, clamp(basePitch + netPitch, -90.0, 90.0));

		return angleDegrees(source, endpoint);
	}

	private static WorldVector viewDirection(double yawDegrees, double pitchDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		double pitch = Math.toRadians(pitchDegrees);
		double cosPitch = Math.cos(pitch);

		return new WorldVector(
			-Math.sin(yaw) * cosPitch,
			-Math.sin(pitch),
			Math.cos(yaw) * cosPitch);
	}

	private static double angleDegrees(WorldVector from, WorldVector to) {
		double crossX = from.y() * to.z() - from.z() * to.y();
		double crossY = from.z() * to.x() - from.x() * to.z();
		double crossZ = from.x() * to.y() - from.y() * to.x();
		double crossLength = Math.sqrt(
			crossX * crossX + crossY * crossY + crossZ * crossZ);

		return Math.toDegrees(Math.atan2(crossLength, from.dot(to)));
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
