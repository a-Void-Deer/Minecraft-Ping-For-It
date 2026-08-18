package nx.pingwheel.common.interaction.cancel;

import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;

import nx.pingwheel.common.config.ClientConfigBounds;

/**
 * Deterministically selects the marker to cancel from a
 * {@link CancellationContext}.
 *
 * <p>Selection applies the phase-5 cancellation rules in order:
 * <ol>
 *   <li>only candidates owned by the local player are considered;</li>
 *   <li>only candidates in exactly the local player's current dimension are
 *       considered;</li>
 *   <li>only candidates inside the inclusive half-angle cone around the look
 *       direction are considered. Membership is decided by comparing the
 *       normalized dot product against {@code cos(halfAngle)} — no {@code acos}
 *       is used, and a candidate exactly on the cone boundary is included. The
 *       threshold is {@link Math#nextDown(double) nextDown(cos(halfAngle))} so a
 *       cosine that lands one ULP below the boundary through rounding is still
 *       in-cone, without materially broadening the cone;</li>
 *   <li>a candidate exactly at the eye position (zero distance) has no defined
 *       direction and is treated as eligible (in-cone);</li>
 *   <li>among the remaining candidates, the one with the smallest squared world
 *       distance to the eye position wins.</li>
 * </ol>
 *
 * <p>Exact equal-distance ties choose the candidate with the larger
 * {@link nx.pingwheel.common.domain.MarkerId}. This is a spec-silent
 * convention: the spec only defines "nearest wins", but because marker ids are
 * server-assigned, monotonic, and unique, a larger id is a deterministic and
 * stable tie-break that never depends on input ordering.
 */
public final class CancelCandidatePicker {

	/**
	 * The default cancellation cone half-angle, in degrees.
	 */
	public static final double DEFAULT_HALF_CONE_ANGLE_DEGREES = 5.0;

	private final IntSupplier configuredHalfConeAngleSupplier;
	private final double halfConeAngleDegrees;
	private final double cosHalfConeAngle;
	private final double inclusiveCosThreshold;

	/**
	 * Creates a picker with the default 5-degree half-angle cone.
	 */
	public CancelCandidatePicker() {
		this(DEFAULT_HALF_CONE_ANGLE_DEGREES);
	}

	/**
	 * Creates a picker with a custom half-angle cone, in degrees.
	 *
	 * <p>The angle must be finite and in the range {@code (0, 180]}.
	 */
	public CancelCandidatePicker(double halfConeAngleDegrees) {
		validateAngle(halfConeAngleDegrees);

		this.configuredHalfConeAngleSupplier = null;
		this.halfConeAngleDegrees = halfConeAngleDegrees;
		this.cosHalfConeAngle = Math.cos(Math.toRadians(halfConeAngleDegrees));
		// Inclusive boundary: nextDown shifts the nominal threshold one ULP down so a
		// dot product that rounds just below cos(halfAngle) still counts as on-cone.
		// The widening is at most one ULP, so the cone is not materially broadened.
		this.inclusiveCosThreshold = Math.nextDown(this.cosHalfConeAngle);
	}

	/**
	 * Creates a picker that reads the configured integer angle once for each
	 * cancellation selection. The supplier is retained rather than rebuilding a
	 * picker on every client tick.
	 */
	public CancelCandidatePicker(IntSupplier halfConeAngleDegreesSupplier) {
		this.configuredHalfConeAngleSupplier = Objects.requireNonNull(
			halfConeAngleDegreesSupplier,
			"halfConeAngleDegreesSupplier");
		this.halfConeAngleDegrees = Double.NaN;
		this.cosHalfConeAngle = Double.NaN;
		this.inclusiveCosThreshold = Double.NaN;
	}

	/**
	 * The half-angle cone, in degrees, this picker uses.
	 */
	public double halfConeAngleDegrees() {
		if (configuredHalfConeAngleSupplier == null) {
			return halfConeAngleDegrees;
		}

		return configuredHalfConeAngleDegrees();
	}

	/**
	 * Picks the marker to cancel, or {@link Optional#empty()} if no candidate is
	 * eligible.
	 */
	public Optional<CancelMarkerCandidate> pick(CancellationContext context) {
		Objects.requireNonNull(context, "context");

		double inclusiveCosThreshold = this.inclusiveCosThreshold;

		if (configuredHalfConeAngleSupplier != null) {
			inclusiveCosThreshold = configuredInclusiveCosThreshold();
		}

		double invLookLength = 1.0 / Math.sqrt(context.lookDirection().lengthSquared());

		CancelMarkerCandidate best = null;
		double bestDistanceSquared = Double.POSITIVE_INFINITY;

		for (CancelMarkerCandidate candidate : context.candidates()) {
			if (!candidate.ownerId().equals(context.localOwnerId())) {
				continue;
			}

			if (!candidate.dimensionId().equals(context.currentDimensionId())) {
				continue;
			}

			WorldVector offset = candidate.position().subtract(context.eyePosition());
			double distanceSquared = offset.lengthSquared();

			if (distanceSquared > 0.0) {
				double cosine = offset.dot(context.lookDirection())
					* invLookLength
					/ Math.sqrt(distanceSquared);

				if (cosine < inclusiveCosThreshold) {
					continue;
				}
			}

			if (best == null
				|| distanceSquared < bestDistanceSquared
				|| (distanceSquared == bestDistanceSquared
					&& candidate.markerId().compareTo(best.markerId()) > 0)) {
				best = candidate;
				bestDistanceSquared = distanceSquared;
			}
		}

		return Optional.ofNullable(best);
	}

	private static void validateAngle(double halfConeAngleDegrees) {
		if (!Double.isFinite(halfConeAngleDegrees)) {
			throw new IllegalArgumentException(
				"halfConeAngleDegrees must be finite, got " + halfConeAngleDegrees);
		}

		if (halfConeAngleDegrees <= 0.0 || halfConeAngleDegrees > 180.0) {
			throw new IllegalArgumentException(
				"halfConeAngleDegrees must be in (0, 180], got " + halfConeAngleDegrees);
		}
	}

	private double configuredHalfConeAngleDegrees() {
		int value = configuredHalfConeAngleSupplier.getAsInt();

		if (value < ClientConfigBounds.MIN_CANCEL_HALF_CONE_ANGLE_DEGREES
			|| value > ClientConfigBounds.MAX_CANCEL_HALF_CONE_ANGLE_DEGREES) {
			throw new IllegalArgumentException(
				"cancelHalfConeAngleDegrees must be in ["
					+ ClientConfigBounds.MIN_CANCEL_HALF_CONE_ANGLE_DEGREES
					+ ", "
					+ ClientConfigBounds.MAX_CANCEL_HALF_CONE_ANGLE_DEGREES
					+ "], got "
					+ value);
		}

		double angle = value;
		validateAngle(angle);
		return angle;
	}

	private double configuredInclusiveCosThreshold() {
		return Math.nextDown(Math.cos(Math.toRadians(configuredHalfConeAngleDegrees())));
	}
}
