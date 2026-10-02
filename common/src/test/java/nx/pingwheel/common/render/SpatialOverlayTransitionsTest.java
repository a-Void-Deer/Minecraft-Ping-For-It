package nx.pingwheel.common.render;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Time/geometry oracles use caller-set durations, endpoints and halfway symmetry. */
class SpatialOverlayTransitionsTest {

	private static SpatialOverlayTransitions<String, String> model(int capacity) {
		return new SpatialOverlayTransitions<>(1000L, capacity, 0.5);
	}

	private static SpatialOverlayTransitions.Target<String, String> target(String key) {
		return new SpatialOverlayTransitions.Target<>(key, 20.0, 40.0, 2.0, 0.8, 0.0, 10.0, key);
	}

	private static SpatialOverlayTransitions.State<String, String> only(
		List<SpatialOverlayTransitions.State<String, String>> states) {
		assertEquals(1, states.size());
		return states.getFirst();
	}

	@Test
	void appearanceStartsHiddenAtEntryAndReachesExactEndpointInFiniteTime() {
		var model = model(8);
		var initial = only(model.update(List.of(target("a")), 0L, false));
		assertEquals(0.0, initial.x());
		assertEquals(10.0, initial.y());
		assertEquals(1.0, initial.scale());
		assertEquals(0.0, initial.alpha());
		var half = only(model.update(List.of(target("a")), 500L, false));
		assertEquals(10.0, half.x(), 1.0e-12);
		assertEquals(25.0, half.y(), 1.0e-12);
		assertEquals(1.5, half.scale(), 1.0e-12);
		assertEquals(0.4, half.alpha(), 1.0e-12);
		var finalState = only(model.update(List.of(target("a")), 1000L, false));
		assertEquals(20.0, finalState.x());
		assertEquals(40.0, finalState.y());
		assertEquals(2.0, finalState.scale());
		assertEquals(0.8, finalState.alpha());
	}

	@Test
	void samplingMoreFramesDoesNotRestartAnUnchangedTransition() {
		var sparse = model(8);
		var dense = model(8);
		var targets = List.of(target("a"));
		sparse.update(targets, 0L, false);
		dense.update(targets, 0L, false);
		for (long time = 50L; time < 500L; time += 50L) {
			dense.update(targets, time, false);
		}
		assertEquals(only(sparse.update(targets, 500L, false)), only(dense.update(targets, 500L, false)));
	}

	@Test
	void easingStartsAndEndsMoreGentlyThanTheMiddleWithoutOvershoot() {
		var model = model(8);
		var targets = List.of(target("a"));
		model.update(targets, 0L, false);
		var early = only(model.update(targets, 100L, false));
		var middleBefore = only(model.update(targets, 450L, false));
		var middleAfter = only(model.update(targets, 550L, false));
		var late = only(model.update(targets, 900L, false));
		var end = only(model.update(targets, 1000L, false));
		double centralTravel = middleAfter.x() - middleBefore.x();
		assertTrue(early.x() > 0.0 && early.x() < centralTravel);
		assertTrue(end.x() - late.x() > 0.0 && end.x() - late.x() < centralTravel);
		assertTrue(early.alpha() > 0.0 && late.alpha() < 0.8);
		assertEquals(end, only(model.update(targets, 2000L, false)));
	}

	@Test
	void exitStartsContinuouslyThenFadesAndReleasesDataEvenWhenCalledEveryFrame() {
		var model = model(8);
		model.update(List.of(target("a")), 0L, true);
		var startExit = only(model.update(List.of(), 100L, false));
		assertFalse(startExit.present());
		assertEquals(20.0, startExit.x());
		assertEquals(0.8, startExit.alpha());
		for (long time = 200L; time < 600L; time += 100L) {
			model.update(List.of(), time, false);
		}
		var halfway = only(model.update(List.of(), 600L, false));
		assertEquals(0.4, halfway.alpha(), 1.0e-12);
		assertEquals(10.0, halfway.x(), 1.0e-12);
		assertTrue(model.update(List.of(), 1100L, false).isEmpty());
		assertTrue(model.isEmpty());
	}

	@Test
	void reappearingKeyResumesFromItsDisplayedExitRatherThanRestartingAppearance() {
		var model = model(8);
		model.update(List.of(target("a")), 0L, true);
		model.update(List.of(), 100L, false);
		var before = only(model.update(List.of(), 600L, false));
		var reappeared = only(model.update(List.of(target("a")), 600L, false));
		assertTrue(reappeared.present());
		assertEquals(before.x(), reappeared.x());
		assertEquals(before.y(), reappeared.y());
		assertEquals(before.scale(), reappeared.scale());
		assertEquals(before.alpha(), reappeared.alpha());
		var midway = only(model.update(List.of(target("a")), 1100L, false));
		assertEquals(15.0, midway.x(), 1.0e-12);
		assertEquals(0.6, midway.alpha(), 1.0e-12);
		assertEquals(1, model.retainedSize());
	}

	@Test
	void keyReappearingAfterExitLifetimeStartsFreshInsteadOfRevivingRemovedContent() {
		var model = model(8);
		model.update(List.of(target("a")), 0L, true);
		model.update(List.of(), 100L, false);
		assertTrue(model.update(List.of(), 1100L, false).isEmpty());
		var returned = only(model.update(List.of(target("a")), 1200L, false));
		assertEquals(0.0, returned.alpha());
		assertEquals(0.0, returned.x());
		assertEquals(1, model.retainedSize());
	}

	@Test
	void modeSwitchKeepsOldDetachedContentOnlyUntilItsOwnExitDeadline() {
		var model = model(8);
		model.update(List.of(target("radial")), 0L, true);
		var switching = model.update(List.of(target("list")), 100L, false);
		assertEquals(List.of("radial", "list"), switching.stream().map(SpatialOverlayTransitions.State::key).toList());
		assertFalse(switching.get(0).present());
		assertTrue(switching.get(1).present());
		var finished = only(model.update(List.of(target("list")), 1100L, false));
		assertEquals("list", finished.key());
		assertEquals(0.8, finished.alpha());
	}

	@Test
	void retargetingIsContinuousAndReducedMotionMovesImmediatelyWithoutChangingOpacity() {
		var model = model(8);
		model.update(List.of(target("a")), 0L, true);
		var moved = new SpatialOverlayTransitions.Target<>("a", 100.0, 80.0, 1.0, 0.25, 90.0, 70.0, "new label");
		var start = only(model.update(List.of(moved), 100L, false));
		assertEquals(20.0, start.x());
		assertEquals(0.8, start.alpha());
		var half = only(model.update(List.of(moved), 600L, false));
		assertEquals(60.0, half.x(), 1.0e-12);
		assertEquals(0.525, half.alpha(), 1.0e-12);
		var reduced = only(model.update(List.of(moved), 700L, true));
		assertEquals(100.0, reduced.x());
		assertEquals(80.0, reduced.y());
		assertEquals(1.0, reduced.scale());
		assertEquals(0.25, reduced.alpha());
		assertEquals("new label", reduced.data());
		assertTrue(model.update(List.of(), 700L, true).isEmpty());
	}

	@Test
	void rewindCannotRewindTheDisplayedTransitionAndLargeGapCompletesIt() {
		var model = model(8);
		var targets = List.of(target("a"));
		model.update(targets, 0L, false);
		var half = model.update(targets, 500L, false);
		assertEquals(half, model.update(targets, 100L, false));
		assertEquals(0.8, only(model.update(targets, 1_000_000L, false)).alpha());
	}

	@Test
	void churnCannotGrowCachePastBoundAndCurrentTargetsReplaceOutgoingOnes() {
		var model = model(2);
		model.update(List.of(target("a"), target("b")), 0L, true);
		for (int i = 0; i < 20; i++) {
			var states = model.update(List.of(target("left" + i), target("right" + i)), i + 1L, false);
			assertEquals(2, model.retainedSize());
			assertTrue(states.stream().allMatch(SpatialOverlayTransitions.State::present));
		}
		var overloaded = model.update(List.of(target("x"), target("y"), target("z")), 30L, true);
		assertEquals(List.of("x", "y"), overloaded.stream().map(SpatialOverlayTransitions.State::key).toList());
		model.update(List.of(), 31L, false);
		assertTrue(model.update(List.of(), 1031L, false).isEmpty());
	}

	@Test
	void disposalAllowsAFreshClockAndNoStateFromPriorSession() {
		var model = model(2);
		model.update(List.of(target("old")), 100_000L, true);
		model.clear();
		assertTrue(model.isEmpty());
		var fresh = only(model.update(List.of(target("new")), 0L, false));
		assertEquals("new", fresh.key());
		assertEquals(0.0, fresh.alpha());
		assertEquals(0.4, only(model.update(List.of(target("new")), 500L, false)).alpha(), 1.0e-12);
	}
}
