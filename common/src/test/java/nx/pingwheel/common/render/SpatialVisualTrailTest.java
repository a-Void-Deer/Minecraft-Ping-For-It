package nx.pingwheel.common.render;

import java.util.List;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialMenu;
import static org.junit.jupiter.api.Assertions.*;

class SpatialVisualTrailTest {
	private static final long DURATION = 160_000_000L;
	private static SpatialController.Point p(double x, double y) { return new SpatialController.Point(x, y); }
	private static SpatialController controller(int cap) {
		var controller = new SpatialController(SpatialMenu.of("root",
			SpatialMenu.Choice.disabled("disabled", "disabled").withSector(0, 360)),
			new SpatialController.Tuning(12, 20, 1000, false, 500, cap));
		controller.start(0); return controller;
	}
	private static void frame(SpatialVisualTrail<String> trail, SpatialController controller, String owner,
		SpatialController.Point painted, long time, boolean reduced, int cap) {
		trail.prepare(controller.snapshot(), cap, time, reduced);
		trail.commit(controller.snapshot(), owner, painted, time, reduced);
	}

	@Test void nativeBatchesAreConsumedOnceAndFollowerSamplesNeverReplayOnReturn() {
		var c = controller(17); var trail = new SpatialVisualTrail<String>(DURATION);
		frame(trail, c, null, null, 0, false, 17);
		c.movePhysical(1, 0, 1); c.movePhysical(2, 0, 2); c.movePhysical(3, 0, 3);
		frame(trail, c, null, null, 1, false, 17);
		assertEquals(List.of(p(0, 0), p(1, 0), p(3, 0), p(6, 0)), trail.points());
		frame(trail, c, null, null, 2, false, 17);
		assertEquals(4, trail.points().size());
		frame(trail, c, "A", p(30, 20), 3, true, 17);
		c.movePhysical(100, 0, 4); c.movePhysical(100, 0, 5);
		frame(trail, c, "A", p(35, 25), 4, false, 17);
		frame(trail, c, null, null, 5, true, 17);
		assertEquals(List.of(p(0, 0), p(1, 0), p(3, 0), p(6, 0), p(30, 20), p(35, 25), p(206, 0)), trail.points());
		frame(trail, c, null, null, 6, false, 17);
		assertEquals(7, trail.points().size(), "raw follower-era points cannot be consumed later");
		c.movePhysical(1, 0, 7); frame(trail, c, null, null, 7, false, 17);
		assertEquals(p(207, 0), trail.points().getLast());
	}

	@Test void finiteMovingTargetHandoffAndInterruptedOwnersStartAtLastDisplayedEndpoint() {
		var c = controller(32); var trail = new SpatialVisualTrail<String>(DURATION);
		frame(trail, c, null, null, 0, false, 32);
		frame(trail, c, "A", p(80, 0), 10, false, 32);
		frame(trail, c, "A", p(100, 40), 10 + DURATION / 2, false, 32);
		assertEquals(p(50, 20), trail.points().getLast(), "same owner moving target does not restart the interval");
		frame(trail, c, "B", p(-100, 100), 10 + DURATION / 2, false, 32);
		assertEquals(p(50, 20), trail.points().getLast(), "retarget begins at displayed endpoint, not A target");
		frame(trail, c, "B", p(-110, 80), 10 + DURATION, false, 32);
		assertEquals(p(-30, 50), trail.points().getLast());
		frame(trail, c, null, null, 10 + DURATION, false, 32);
		frame(trail, c, null, null, 10 + DURATION * 3 / 2, false, 32);
		assertEquals(p(-15, 25), trail.points().getLast());
		frame(trail, c, null, null, 10 + DURATION * 2, false, 32);
		assertEquals(p(0, 0), trail.points().getLast());
		frame(trail, c, "A", p(50, 20), 10 + DURATION * 2, false, 32);
		frame(trail, c, "A", p(90, 60), 10 + DURATION * 3, false, 32);
		assertEquals(p(90, 60), trail.points().getLast());
		frame(trail, c, "A", p(93, 64), 11 + DURATION * 3, false, 32);
		assertEquals(p(93, 64), trail.points().getLast(), "settled ownership has no second lag filter");
	}

	@Test void projectionCorrectionsAndExplicitCoincidentRebasesFenceOldPointsButPurePanDoesNot() {
		var c = controller(16); var trail = new SpatialVisualTrail<String>(DURATION);
		c.constrainPointer(new SpatialController.Viewport(100, 100, 0, 0), 0);
		c.movePhysical(40, 0, 1); frame(trail, c, null, null, 0, false, 16);
		var original = trail.points(); long sequence = c.snapshot().trailSequence();
		c.constrainPointer(new SpatialController.Viewport(100, 100, -1, 0), 2);
		frame(trail, c, null, null, 1, false, 16);
		assertEquals(original, trail.points()); assertEquals(sequence, c.snapshot().trailSequence());
		c.constrainPointer(new SpatialController.Viewport(100, 100, 20, 0), 3);
		c.movePhysical(-2, 0, 4); c.movePhysical(-3, 0, 5);
		frame(trail, c, null, null, 2, false, 16);
		assertEquals(List.of(p(27, 0), p(24, 0)), trail.points(), "post-correction callback batch kept, no correction point or stale stroke");
		long epoch = c.snapshot().trailEpoch();
		c.rebase(24, 0, 6); frame(trail, c, null, null, 3, false, 16);
		assertTrue(c.snapshot().trailEpoch() > epoch);
		assertEquals(List.of(p(24, 0)), trail.points(), "coincident rebase still starts fresh by identity");
	}

	@Test void pointerOwnedBatchesRemainCompleteDuringFiniteReturnHandoff() {
		var c = controller(32); var trail = new SpatialVisualTrail<String>(DURATION);
		frame(trail, c, "A", p(100, 0), 0, true, 32);
		frame(trail, c, null, null, 1, false, 32);
		c.movePhysical(10, 0, 1); c.movePhysical(10, 0, 2); c.movePhysical(10, 0, 3);
		frame(trail, c, null, null, 1 + DURATION / 2, false, 32);
		assertEquals(List.of(p(100, 0), p(55, 0), p(60, 0), p(65, 0)), trail.points(),
			"all three actual pointer callbacks contribute, at this frame's common handoff progress");
		frame(trail, c, null, null, 1 + DURATION, false, 32);
		assertEquals(p(30, 0), trail.points().getLast());
	}

	@Test void unfinishedHandoffSurvivesOrdinaryExitAndResumesAfterMissingDepartingPaint() {
		var c = controller(32); var trail = new SpatialVisualTrail<String>(DURATION);
		frame(trail, c, null, null, 0, false, 32);
		frame(trail, c, "A", p(100, 0), 10, false, 32);
		frame(trail, c, "A", p(100, 0), 10 + DURATION / 2, false, 32);
		var halfway = trail.points();
		assertEquals(p(50, 0), halfway.getLast());
		c.release(10 + DURATION / 2);
		frame(trail, c, null, p(100, 0), 10 + DURATION / 2, false, 32);
		assertEquals(halfway, trail.points(), "same-clock release keeps the displayed midpoint");
		frame(trail, c, null, null, 10 + DURATION * 3 / 4, false, 32);
		assertEquals(halfway, trail.points(), "missing departing paint retains history without a false pose");
		frame(trail, c, null, p(100, 40), 10 + DURATION * 3 / 4, false, 32);
		assertEquals(p(84.375, 33.75), trail.points().getLast(), "later paint resumes the original finite interval");
		frame(trail, c, null, p(120, 60), 10 + DURATION, false, 32);
		assertEquals(p(120, 60), trail.points().getLast(), "completed interval follows the departing pose");
		frame(trail, c, null, null, 10 + DURATION * 2, true, 32);
		assertTrue(trail.points().isEmpty(), "reduced motion exit still clears instead of easing");
	}

	@Test void unfinishedFollowerSwitchHandoffSurvivesOrdinaryExitTowardTheNewDepartingOwner() {
		var c = controller(32); var trail = new SpatialVisualTrail<String>(DURATION);
		frame(trail, c, null, null, 0, false, 32);
		frame(trail, c, "A", p(100, 0), 10, false, 32);
		frame(trail, c, "A", p(100, 0), 10 + DURATION, false, 32);
		assertEquals(p(100, 0), trail.points().getLast());
		frame(trail, c, "B", p(200, 0), 10 + DURATION + 1, false, 32);
		frame(trail, c, "B", p(200, 0), 10 + DURATION + 1 + DURATION / 2, false, 32);
		var halfway = trail.points();
		assertEquals(p(150, 0), halfway.getLast());
		c.release(10 + DURATION + 1 + DURATION / 2);
		frame(trail, c, null, p(200, 0), 10 + DURATION + 1 + DURATION / 2, false, 32);
		assertEquals(halfway, trail.points(), "same-clock release keeps the B handoff midpoint");
		frame(trail, c, null, p(200, 40), 10 + DURATION + 1 + DURATION * 3 / 4, false, 32);
		assertEquals(p(184.375, 33.75), trail.points().getLast(), "eases toward B's moving departing pose");
		frame(trail, c, null, p(200, 40), 10 + DURATION + 1 + DURATION, false, 32);
		assertEquals(p(200, 40), trail.points().getLast(), "completed interval follows the departing pose");
	}

	@Test void reducedMotionExitHardResetReopenRewoundClockAndRapidRetargetRemainBounded() {
		var c = controller(16); var trail = new SpatialVisualTrail<String>(DURATION);
		frame(trail, c, null, null, 100, false, 16);
		frame(trail, c, "A", p(100, 0), 100, false, 16);
		frame(trail, c, "A", p(100, 0), 100 + DURATION / 2, false, 16);
		assertEquals(p(50, 0), trail.points().getLast());
		frame(trail, c, "A", p(100, 0), 0, false, 16);
		assertEquals(p(50, 0), trail.points().getLast(), "rewound clock cannot undo displayed progress");
		frame(trail, c, "B", p(-40, -50), 100 + DURATION / 2, true, 16);
		assertEquals(p(-40, -50), trail.points().getLast());
		assertEquals(3, trail.points().size(), "reduced motion adds actual endpoint only");
		for (int i = 1; i < 1000; i++) {
			frame(trail, c, "owner" + i, p(i, i), 100 + DURATION / 2 + i, true, 16);
			assertTrue(trail.points().size() <= 16);
		}
		trail.prepare(null, 16, DURATION * 2, false);
		trail.commit(null, null, p(30, 40), DURATION * 2, false);
		assertEquals(p(30, 40), trail.points().getLast());
		trail.commit(null, null, null, DURATION * 3, false);
		assertEquals(p(30, 40), trail.points().getLast(), "missing departing paint freezes history");
		frame(trail, c, null, null, DURATION * 4, true, 16);
		assertEquals(List.of(p(0, 0)), trail.points(), "reopen discards old history");
		trail.clear(); assertTrue(trail.points().isEmpty()); assertNull(trail.owner());
		frame(trail, c, null, null, 0, true, 16); assertEquals(List.of(p(0, 0)), trail.points());
	}
}
