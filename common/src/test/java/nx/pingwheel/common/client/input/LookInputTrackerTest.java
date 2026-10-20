package nx.pingwheel.common.client.input;

import nx.pingwheel.common.interaction.cancel.WorldVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LookInputTrackerTest {

	@Test void signedNetInputCancelsAndResetAdvancesTheGenerationFence() {
		LookInputTracker tracker = new LookInputTracker();
		long generation = tracker.snapshot().generation();

		tracker.acceptDegrees(30.0, -20.0);
		tracker.acceptDegrees(-30.0, 20.0);
		LookInputTracker.Sample roundTrip = tracker.snapshot();

		assertEquals(0.0, roundTrip.yawDegrees(), 1.0e-12);
		assertEquals(0.0, roundTrip.pitchDegrees(), 1.0e-12);
		assertEquals(generation, roundTrip.generation());

		tracker.acceptDegrees(12.0, 8.0);
		tracker.reset();
		LookInputTracker.Sample reset = tracker.snapshot();

		assertEquals(0.0, reset.yawDegrees(), 0.0);
		assertEquals(0.0, reset.pitchDegrees(), 0.0);
		assertEquals(generation + 1, reset.generation());

		tracker.acceptDegrees(1.0, 2.0);
		LookInputTracker.Sample afterAccept = tracker.snapshot();

		assertEquals(1.0, afterAccept.yawDegrees(), 0.0);
		assertEquals(2.0, afterAccept.pitchDegrees(), 0.0);
		assertEquals(reset.generation(), afterAccept.generation(),
			"ordinary input keeps the current baseline generation");
	}

	@Test void nonFiniteAndOverflowingInputRejectTheWholeBaseline() {
		LookInputTracker tracker = new LookInputTracker();
		tracker.acceptDegrees(10.0, 20.0);
		long generation = tracker.snapshot().generation();

		tracker.acceptDegrees(Double.NaN, 1.0);
		LookInputTracker.Sample afterNaN = tracker.snapshot();

		assertEquals(0.0, afterNaN.yawDegrees(), 0.0);
		assertEquals(0.0, afterNaN.pitchDegrees(), 0.0);
		assertEquals(generation + 1, afterNaN.generation());

		tracker.acceptDegrees(1.0, Double.POSITIVE_INFINITY);
		assertEquals(generation + 2, tracker.snapshot().generation());

		tracker.acceptDegrees(Double.MAX_VALUE, 0.0);
		tracker.acceptDegrees(Double.MAX_VALUE, 0.0);
		LookInputTracker.Sample afterOverflow = tracker.snapshot();

		assertEquals(0.0, afterOverflow.yawDegrees(), 0.0);
		assertEquals(0.0, afterOverflow.pitchDegrees(), 0.0);
		assertEquals(generation + 3, afterOverflow.generation());
	}

	@Test void pixelConversionMatchesVanillaNonSmoothedSensitivityAndScoping() {
		double ordinary = LookInputTracker.degreesFromPixels(10.0, 0.5, false);
		double scoping = LookInputTracker.degreesFromPixels(10.0, 0.5, true);

		assertEquals(1.5, ordinary, 1.0e-6);
		assertEquals(0.1875, scoping, 1.0e-6);
		assertEquals(ordinary / 8.0, scoping, 1.0e-6);
		assertEquals(0.096, LookInputTracker.degreesFromPixels(10.0, 0.0, false), 1.0e-6);
		assertEquals(0.0, LookInputTracker.degreesFromPixels(Double.NaN, 0.5, false), 0.0);
		assertEquals(0.0, LookInputTracker.degreesFromPixels(10.0, Double.NaN, false), 0.0);
	}

	@Test void yawPitchAndDiagonalAnglesAreStrictThreeDimensionalAngles() {
		WorldVector forward = new WorldVector(0.0, 0.0, 1.0);

		assertEquals(90.0, angle(forward, 0.0, 0.0, 90.0, 0.0), 1.0e-9);
		assertEquals(45.0, angle(forward, 0.0, 0.0, 0.0, 45.0), 1.0e-9);
		assertEquals(90.0, angle(forward, 0.0, 0.0, -90.0, 0.0), 1.0e-9);

		double diagonal = Math.toDegrees(Math.acos(
			Math.cos(Math.toRadians(40.0)) * Math.cos(Math.toRadians(30.0))));
		assertEquals(diagonal, angle(forward, 0.0, 0.0, 40.0, 30.0), 1.0e-9);
	}

	@Test void highPitchYawAndWrapUseTheVirtualCameraEndpoint() {
		WorldVector highPitch = viewDirection(0.0, 80.0);
		double expectedHighPitch = Math.toDegrees(Math.acos(
			Math.sin(Math.toRadians(80.0)) * Math.sin(Math.toRadians(80.0))));

		assertEquals(expectedHighPitch, angle(highPitch, 0.0, 0.0, 90.0, 0.0), 1.0e-9);
		assertTrue(expectedHighPitch < 20.0, "yaw at a high pitch is not a 90 degree turn");

		assertEquals(10.0, angle(forward(), 0.0, 0.0, 370.0, 0.0), 1.0e-9);
		assertEquals(10.0, angle(forward(), 10.0, 0.0, 20.0, 0.0), 1.0e-9);
	}

	@Test void pitchAccumulatesWithoutPerSampleClampingAndClampsOnlyAtTheEndpoint() {
		LookInputTracker tracker = new LookInputTracker();
		LookInputTracker.Sample start = tracker.snapshot();

		tracker.acceptDegrees(0.0, 100.0);
		tracker.acceptDegrees(0.0, -100.0);
		LookInputTracker.Sample current = tracker.snapshot();

		assertEquals(0.0, LookInputTracker.angleBetween(forward(), start, current), 1.0e-9,
			"a round trip beyond the camera pitch limit must cancel");

		LookInputTracker.Sample tiltedStart = new LookInputTracker.Sample(0.0, 0.0, 5L);
		LookInputTracker.Sample tiltedCurrent = new LookInputTracker.Sample(0.0, 30.0, 5L);
		assertEquals(10.0, LookInputTracker.angleBetween(
			viewDirection(0.0, 80.0), tiltedStart, tiltedCurrent), 1.0e-9,
			"the endpoint clamps to 90 degrees after the signed net sum");
	}

	@Test void generationMismatchAndDegenerateRayUseTheFailClosedBoundary() {
		LookInputTracker.Sample start = new LookInputTracker.Sample(0.0, 0.0, 3L);
		LookInputTracker.Sample current = new LookInputTracker.Sample(90.0, 0.0, 4L);

		assertEquals(0.0, LookInputTracker.angleBetween(forward(), start, current), 0.0);

		assertEquals(90.0, LookInputTracker.angleBetween(
			new WorldVector(0.0, 0.0, 5.0), new LookInputTracker.Sample(0.0, 0.0, 1L),
			new LookInputTracker.Sample(90.0, 0.0, 1L)), 1.0e-9,
			"the first ray is normalized before the strict dot/cross calculation");

		assertThrows(IllegalArgumentException.class, () -> LookInputTracker.angleBetween(
			new WorldVector(0.0, 0.0, 0.0), start, start));
		assertThrows(NullPointerException.class, () -> LookInputTracker.angleBetween(
			null, start, start));
	}

	private static double angle(WorldVector firstRay, double startYaw, double startPitch,
		double currentYaw, double currentPitch) {
		return LookInputTracker.angleBetween(firstRay,
			new LookInputTracker.Sample(startYaw, startPitch, 1L),
			new LookInputTracker.Sample(currentYaw, currentPitch, 1L));
	}

	private static WorldVector forward() {
		return new WorldVector(0.0, 0.0, 1.0);
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
}
