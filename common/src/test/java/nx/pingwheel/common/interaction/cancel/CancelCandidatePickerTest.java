package nx.pingwheel.common.interaction.cancel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.interaction.CapturedRay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancelCandidatePickerTest {

	private static final UUID LOCAL_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";

	private static final WorldVector EYE = new WorldVector(0, 0, 0);
	private static final WorldVector LOOK = new WorldVector(0, 0, 1);

	@Test
	void defaultHalfConeAngleIsFiveDegrees() {
		assertEquals(5.0, new CancelCandidatePicker().halfConeAngleDegrees());
	}

	@Test
	void picksOwnMarkerInCurrentDimension() {
		CancelMarkerCandidate own = marker(1L, new WorldVector(0, 0, 5));
		CancelMarkerCandidate otherOwner = new CancelMarkerCandidate(
			new MarkerId(2L), OTHER_OWNER, OVERWORLD, new WorldVector(0, 0, 3));
		CancelMarkerCandidate otherDimension = new CancelMarkerCandidate(
			new MarkerId(3L), LOCAL_OWNER, NETHER, new WorldVector(0, 0, 3));
		CancelMarkerCandidate outOfCone = markerAtAngle(4L, 45.0);

		Optional<CancelMarkerCandidate> picked = new CancelCandidatePicker()
			.pick(context(List.of(otherOwner, otherDimension, outOfCone, own)));

		assertEquals(own, picked.orElseThrow());
	}

	@Test
	void otherOwnerIsNeverCancelled() {
		CancelMarkerCandidate otherOwner = new CancelMarkerCandidate(
			new MarkerId(1L), OTHER_OWNER, OVERWORLD, new WorldVector(0, 0, 5));

		assertTrue(new CancelCandidatePicker().pick(context(List.of(otherOwner))).isEmpty());
	}

	@Test
	void otherDimensionIsNeverCancelled() {
		CancelMarkerCandidate otherDimension = new CancelMarkerCandidate(
			new MarkerId(1L), LOCAL_OWNER, NETHER, new WorldVector(0, 0, 5));

		assertTrue(new CancelCandidatePicker().pick(context(List.of(otherDimension))).isEmpty());
	}

	@Test
	void coneBoundaryIsInclusive() {
		CancelCandidatePicker picker = new CancelCandidatePicker();

		assertEquals(new MarkerId(1L), pick(picker, markerAtAngle(1L, 4.9)).orElseThrow().markerId());
		assertEquals(new MarkerId(2L), pick(picker, markerAtAngle(2L, 5.0)).orElseThrow().markerId());
		assertTrue(pick(picker, markerAtAngle(3L, 5.1)).isEmpty());
	}

	@Test
	void picksNearestCandidate() {
		CancelMarkerCandidate near = marker(1L, new WorldVector(0, 0, 5));
		CancelMarkerCandidate mid = marker(2L, new WorldVector(0, 0, 10));
		CancelMarkerCandidate far = marker(3L, new WorldVector(0, 0, 20));

		assertEquals(near, new CancelCandidatePicker().pick(context(List.of(far, mid, near))).orElseThrow());
	}

	@Test
	void zeroDistanceCandidateIsEligibleAndNearest() {
		CancelMarkerCandidate atEye = marker(1L, EYE);
		CancelMarkerCandidate ahead = marker(2L, new WorldVector(0, 0, 5));

		assertEquals(atEye, new CancelCandidatePicker().pick(context(List.of(ahead, atEye))).orElseThrow());
	}

	@Test
	void tallEntityTopCenterIsPickedWhileFeetAtSameXZAreOutsideCone() {
		// A 4-block tall entity whose feet sit at eye level, straight ahead.
		// The camera aims exactly at the displayed top-center (0, 6, 10): that
		// candidate must be cancelable, while the same marker resolved at the
		// feet point (0, 2, 10) — same X/Z — falls outside the 5-degree cone.
		WorldVector feet = new WorldVector(0.0, 2.0, 10.0);
		WorldVector topCenter = new WorldVector(0.0, 6.0, 10.0);
		WorldVector eye = new WorldVector(0.0, 2.0, 0.0);
		WorldVector lookAtTopCenter = new WorldVector(0.0, 4.0, 10.0);

		CancelMarkerCandidate atTopCenter = marker(1L, topCenter);
		CancelMarkerCandidate atFeet = marker(2L, feet);

		assertEquals(new MarkerId(1L), new CancelCandidatePicker()
			.pick(new CancellationContext(LOCAL_OWNER, OVERWORLD, eye, lookAtTopCenter, List.of(atTopCenter)))
			.orElseThrow()
			.markerId());
		assertTrue(new CancelCandidatePicker()
			.pick(new CancellationContext(LOCAL_OWNER, OVERWORLD, eye, lookAtTopCenter, List.of(atFeet)))
			.isEmpty());
	}

	@Test
	void equalDistanceTieChoosesLargerMarkerId() {
		double rad = Math.toRadians(3.0);
		double distance = 10.0;
		double x = distance * Math.sin(rad);
		double z = distance * Math.cos(rad);

		CancelMarkerCandidate smaller = marker(1L, new WorldVector(x, 0, z));
		CancelMarkerCandidate larger = marker(2L, new WorldVector(-x, 0, z));

		CancelCandidatePicker picker = new CancelCandidatePicker();

		assertEquals(larger, picker.pick(context(List.of(smaller, larger))).orElseThrow());
		assertEquals(larger, picker.pick(context(List.of(larger, smaller))).orElseThrow());
	}

	@Test
	void emptyCandidatesYieldEmptyResult() {
		assertTrue(new CancelCandidatePicker().pick(context(List.of())).isEmpty());
	}

	@Test
	void frozenPressRayWinsEvenWhenALaterCameraWouldPointAtAnotherMarker() {
		CapturedRay pressRay = new CapturedRay(
			new WorldVector(0.0, 0.0, 0.0),
			new WorldVector(0.0, 0.0, 1.0));
		CancelMarkerCandidate pressRayCandidate = marker(1L, new WorldVector(0.0, 0.0, 5.0));
		CancelMarkerCandidate laterCameraCandidate = marker(2L, new WorldVector(5.0, 0.0, 0.0));
		CancellationContext frozenContext = new CancellationContext(
			LOCAL_OWNER,
			OVERWORLD,
			pressRay.origin(),
			pressRay.direction(),
			List.of(laterCameraCandidate, pressRayCandidate));

		// The later camera direction is deliberately not supplied to the picker.
		// Only the immutable press-time context can make the forward candidate
		// eligible.
		assertEquals(pressRayCandidate,
			new CancelCandidatePicker().pick(frozenContext).orElseThrow());
	}

	@Test
	void frozenPresentationPositionIsSelectedForCancellation() {
		WorldVector anchor = new WorldVector(0.0, 0.0, 30.0);
		WorldVector frozenPresentation = new WorldVector(0.0, 0.0, 5.0);
		CancelMarkerCandidate candidate = marker(1L,
			MarkerCandidatePosition.resolve(anchor, Optional.of(frozenPresentation)));

		assertEquals(candidate, new CancelCandidatePicker().pick(context(List.of(candidate))).orElseThrow());
	}

	@Test
	void noEligibleCandidateYieldsEmptyResult() {
		CancelMarkerCandidate otherOwner = new CancelMarkerCandidate(
			new MarkerId(1L), OTHER_OWNER, OVERWORLD, new WorldVector(0, 0, 5));
		CancelMarkerCandidate otherDimension = new CancelMarkerCandidate(
			new MarkerId(2L), LOCAL_OWNER, NETHER, new WorldVector(0, 0, 5));
		CancelMarkerCandidate outOfCone = markerAtAngle(3L, 45.0);

		assertTrue(new CancelCandidatePicker()
			.pick(context(List.of(otherOwner, otherDimension, outOfCone))).isEmpty());
	}

	@Test
	void customAngleWidensOrNarrowsCone() {
		CancelMarkerCandidate marker = markerAtAngle(1L, 10.0);

		assertTrue(new CancelCandidatePicker().pick(context(List.of(marker))).isEmpty());
		assertEquals(new MarkerId(1L),
			new CancelCandidatePicker(15.0).pick(context(List.of(marker))).orElseThrow().markerId());
	}

	@Test
	void configuredAngleIsReadOncePerSelectionAndCanChangeForTheNextSelection() {
		AtomicInteger angle = new AtomicInteger(5);
		AtomicInteger reads = new AtomicInteger();
		CancelCandidatePicker picker = new CancelCandidatePicker(() -> {
			reads.incrementAndGet();
			return angle.get();
		});
		CancelMarkerCandidate marker = markerAtAngle(1L, 10.0);

		assertTrue(picker.pick(context(List.of(marker))).isEmpty());
		assertEquals(1, reads.get());

		angle.set(15);
		assertEquals(new MarkerId(1L), picker.pick(context(List.of(marker))).orElseThrow().markerId());
		assertEquals(2, reads.get());
	}

	@Test
	void acceptsFullHemisphereBoundaryAngle() {
		CancelMarkerCandidate behind = marker(1L, new WorldVector(0, 0, -10));

		assertEquals(new MarkerId(1L),
			new CancelCandidatePicker(180.0).pick(context(List.of(behind))).orElseThrow().markerId());
	}

	@Test
	void rejectsNonFiniteAngle() {
		assertThrows(IllegalArgumentException.class, () -> new CancelCandidatePicker(Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> new CancelCandidatePicker(Double.POSITIVE_INFINITY));
		assertThrows(IllegalArgumentException.class, () -> new CancelCandidatePicker(Double.NEGATIVE_INFINITY));
	}

	@Test
	void rejectsOutOfRangeAngle() {
		assertThrows(IllegalArgumentException.class, () -> new CancelCandidatePicker(0.0));
		assertThrows(IllegalArgumentException.class, () -> new CancelCandidatePicker(-5.0));
		assertThrows(IllegalArgumentException.class, () -> new CancelCandidatePicker(180.1));
	}

	@Test
	void rejectsNullContext() {
		assertThrows(NullPointerException.class, () -> new CancelCandidatePicker().pick(null));
	}

	private static Optional<CancelMarkerCandidate> pick(CancelCandidatePicker picker, CancelMarkerCandidate candidate) {
		return picker.pick(context(List.of(candidate)));
	}

	private static CancellationContext context(List<CancelMarkerCandidate> candidates) {
		return new CancellationContext(LOCAL_OWNER, OVERWORLD, EYE, LOOK, candidates);
	}

	private static CancelMarkerCandidate marker(long id, WorldVector position) {
		return new CancelMarkerCandidate(new MarkerId(id), LOCAL_OWNER, OVERWORLD, position);
	}

	private static CancelMarkerCandidate markerAtAngle(long id, double degrees) {
		double rad = Math.toRadians(degrees);
		double distance = 10.0;

		return marker(id, new WorldVector(distance * Math.sin(rad), 0.0, distance * Math.cos(rad)));
	}
}
