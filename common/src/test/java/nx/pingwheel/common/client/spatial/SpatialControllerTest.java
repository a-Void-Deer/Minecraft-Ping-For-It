package nx.pingwheel.common.client.spatial;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic tests for {@link SpatialController}. Every case drives the
 * controller with explicit timestamps and physical deltas, so the assertions
 * describe interaction behavior (frozen params, half-open sectors, dwell,
 * release ownership, hover delegation, fresh-stroke gating) rather than
 * prototype literals. No game client or renderer is involved.
 */
class SpatialControllerTest {

	private static final double DELTA = 1.0e-9;

	@ParameterizedTest
	@CsvSource({"-1000,0,0,40", "1000,0,100,40", "0,-1000,50.5,0", "0,1000,50.5,79",
		"-1000,-1000,0,0", "1000,-1000,100,0", "-1000,1000,0,79", "1000,1000,100,79"})
	void everyEdgeAndCornerUsesInclusivePixelCenters(double dx, double dy, double screenX, double screenY) {
		var controller = new SpatialController(halfRoot(), tuning(180, false, 500));
		controller.start(0);
		controller.constrainPointer(new SpatialController.Viewport(101, 80, 7, -3), 0);
		controller.rebase(-7, 3, 0); // exact displayed GUI center
		controller.movePhysical(dx, dy, 10);
		var pointer = controller.snapshot().pointer();
		assertEquals(screenX, 57.5 + pointer.x(), DELTA);
		assertEquals(screenY, 37 + pointer.y(), DELTA);
	}

	@Test
	void hugeFiniteInputSaturatesWithoutOverflowAndFirstInwardSampleHasNoDebt() {
		var controller = new SpatialController(halfRoot(), tuning(180, false, 500));
		controller.start(0);
		controller.constrainPointer(new SpatialController.Viewport(100, 80, 0, 0), 0);
		controller.movePhysical(Double.MAX_VALUE, Double.MAX_VALUE, 10);
		assertEquals(new SpatialController.Point(49, 39), controller.snapshot().pointer());
		var saturated = controller.snapshot();
		controller.movePhysical(Double.MAX_VALUE, Double.MAX_VALUE, 20);
		assertEquals(saturated, controller.snapshot(), "outward input creates no duplicate trail or timer progression");
		controller.movePhysical(-1, -2, 30);
		assertEquals(new SpatialController.Point(48, 37), controller.snapshot().pointer());
		controller.movePhysical(-Double.MAX_VALUE, -Double.MAX_VALUE, 40);
		assertEquals(new SpatialController.Point(-50, -40), controller.snapshot().pointer());
		controller.movePhysical(1, 2, 50);
		assertEquals(new SpatialController.Point(-49, -38), controller.snapshot().pointer());
	}

	@Test
	void nonFiniteDeltasLeaveRadialAndExternalStateUntouched() {
		var controller = new SpatialController(halfRoot(), tuning(180, false, 500));
		controller.start(0);
		controller.constrainPointer(new SpatialController.Viewport(100, 80, 0, 0), 0);
		for (double bad : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
			var before = controller.snapshot();
			controller.movePhysical(bad, 10, 10);
			controller.movePhysical(10, bad, 10);
			assertEquals(before, controller.snapshot());
		}
		controller.useExternalSurface();
		for (double bad : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
			var before = controller.snapshot();
			assertEquals(new SpatialController.Point(0, 0), controller.moveExternalPointer(bad, 10, 10));
			assertEquals(new SpatialController.Point(0, 0), controller.moveExternalPointer(10, bad, 10));
			assertEquals(before, controller.snapshot());
		}
	}

	@Test
	void saturatedInputDoesNotResetDwellAndClippedTravelCannotPayBackFreshStroke() {
		var controller = new SpatialController(root(), new SpatialController.Tuning(10, 40, 100, false, 500));
		controller.start(0);
		controller.constrainPointer(new SpatialController.Viewport(100, 100, 0, 0), 0);
		controller.movePhysical(1000, 0, 10);
		controller.movePhysical(1000, 0, 109);
		controller.tick(110);
		assertEquals(2, controller.snapshot().menus().size(), "saturation must not reset the last real movement");
		controller.back(120);
		controller.constrainPointer(new SpatialController.Viewport(60, 100, 0, 0), 130);
		controller.movePhysical(1000, 0, 140);
		controller.tick(1000);
		assertEquals(1, controller.snapshot().menus().size(), "outward overshoot is not a fresh stroke");
	}

	@Test
	void resizeCorrectionDoesNotEnterOrRetraceOrKeepAStaleTurnCorner() {
		var controller = new SpatialController(root(), tuning(200, false, 500));
		controller.start(0);
		controller.constrainPointer(new SpatialController.Viewport(1000, 600, 0, 0), 0);
		controller.movePhysical(200, 0, 10);
		controller.movePhysical(100, 0, 50); // qualifies a turn corner at x=200
		var trail = controller.snapshot().trail();
		controller.constrainPointer(new SpatialController.Viewport(620, 600, 200, 0), 60);
		assertEquals(new SpatialController.Point(109, 0), controller.snapshot().pointer());
		assertEquals(trail, controller.snapshot().trail(), "projection correction paints no synthetic stroke");
		controller.movePhysical(0, -5, 70);
		assertEquals(1, controller.snapshot().menus().size(), "the previous turn corner cannot admit this short segment");
		controller.tick(1000);
		assertEquals(1, controller.snapshot().menus().size(), "correction supplied no dwell travel");
	}

	@Test
	void externalRebaseStartEndAndBackSnapshotsStayWithinPublishedViewport() {
		var controller = new SpatialController(root(), tuning(200, false, 500));
		controller.constrainPointer(new SpatialController.Viewport(100, 80, 100, -100), 0);
		controller.start(0);
		assertEquals(new SpatialController.Point(-51, 60), controller.snapshot().pointer());
		assertEquals(new SpatialController.Point(0, 0), controller.snapshot().menus().getFirst().origin());
		controller.enterExternal(SpatialMenu.of("external", SpatialMenu.Choice.leaf("x", "x", "x")), 500, -500, 10);
		assertEquals(new SpatialController.Point(-51, 60), controller.snapshot().pointer());
		assertEquals(List.of(new SpatialController.Point(-51, 60)), controller.snapshot().trail());
		controller.rebase(-500, 500, 20);
		assertEquals(new SpatialController.Point(-150, 139), controller.snapshot().pointer());
		controller.back(30);
		controller.constrainPointer(new SpatialController.Viewport(100, 80, 0, 0), 40);
		assertEquals(new SpatialController.Point(-50, 39), controller.snapshot().pointer());
		controller.tick(1000);
		assertEquals(1, controller.snapshot().menus().size());
		controller.cancel();
		assertEquals(new SpatialController.Point(0, 0), controller.snapshot().pointer());
	}

	@Test
	void panCorrectionRestartsPendingBackHoverDwell() {
		var controller = enteredContent(true, 400, 200, 210);
		controller.movePhysical(-50, 0, 220);
		controller.tick(520);
		assertEquals(0.75, controller.snapshot().hoverProgress(), DELTA);
		controller.constrainPointer(new SpatialController.Viewport(300, 300, 10, 0), 600);
		assertEquals(139, controller.snapshot().pointer().x(), DELTA);
		assertEquals(0, controller.snapshot().pointer().y(), DELTA);
		assertEquals(0, controller.snapshot().hoverProgress());
		controller.tick(620);
		assertEquals(2, controller.snapshot().menus().size(), "correction cannot complete the stale hover");
		controller.tick(1020);
		assertEquals(1, controller.snapshot().menus().size());
	}

	@ParameterizedTest
	@CsvSource({"false", "true"})
	void resizeThenPanCannotRearmHeldBackInNonRootParent(boolean saturatedPhysicalInput) {
		var controller = returnedToNonRootContentWithHeldBack();
		var trail = controller.snapshot().trail();
		controller.constrainPointer(new SpatialController.Viewport(320, 240, 100, 600), 640);
		assertEquals(new SpatialController.Point(-100, -481), controller.snapshot().pointer());
		assertEquals("content:first", controller.snapshot().focusId(), "resize moves focus out of Back");
		if (saturatedPhysicalInput) controller.movePhysical(0, 1000, 645);
		controller.tick(650);
		controller.constrainPointer(new SpatialController.Viewport(320, 240, 0, 0), 660);
		assertEquals(new SpatialController.Point(-100, -120), controller.snapshot().pointer());
		assertEquals("content:back", controller.snapshot().focusId(), "pan moves focus back into Back");
		assertEquals(trail, controller.snapshot().trail(), "corrections and saturated input add no travel");
		controller.tick(670);
		controller.tick(1070);
		assertEquals(2, controller.snapshot().menus().size(), "projection-only leave/re-entry must not pop the non-root parent");
		assertEquals("content", controller.snapshot().menus().getLast().menuId());
		assertEquals(0, controller.snapshot().hoverProgress());
	}

	@Test
	void effectivePhysicalTurnEntryOutsideBackRearmsHoverAfterCorrection() {
		var controller = returnedToNonRootContentWithHeldBack();
		controller.constrainPointer(new SpatialController.Viewport(320, 240, 100, 600), 640);
		controller.tick(650);
		controller.constrainPointer(new SpatialController.Viewport(1200, 1400, 100, 600), 690);
		controller.movePhysical(-50, 0, 700);
		assertEquals(3, controller.snapshot().menus().size(), "qualified physical turn enters the focused property branch");
		assertEquals("property:leaf", controller.snapshot().focusId(), "the effective turn leaves Back focus");
		controller.movePhysical(70, 80, 710);
		assertEquals("property:back", controller.snapshot().focusId());
		controller.tick(1109);
		assertEquals(3, controller.snapshot().menus().size());
		controller.tick(1110);
		assertEquals(2, controller.snapshot().menus().size(), "physical turn entry must preserve normal Back rearming");
	}

	@Test
	void effectivePhysicalLeaveAndReentryRearmsBackAfterProjectionCorrection() {
		var controller = returnedToNonRootContentWithHeldBack();
		controller.constrainPointer(new SpatialController.Viewport(320, 240, 100, 600), 640);
		controller.tick(650);
		controller.constrainPointer(new SpatialController.Viewport(320, 240, 0, 0), 660);
		controller.movePhysical(1, 0, 670);
		assertEquals("content:back", controller.snapshot().focusId());
		controller.tick(1070);
		assertEquals(2, controller.snapshot().menus().size(), "effective motion still inside Back is not a leave");
		controller.movePhysical(90, 240, 1080);
		assertEquals("content:second", controller.snapshot().focusId());
		controller.movePhysical(-91, -239, 1090);
		assertEquals("content:back", controller.snapshot().focusId());
		controller.tick(1489);
		assertEquals(2, controller.snapshot().menus().size(), "genuine re-entry requires the full hover dwell");
		controller.tick(1490);
		assertEquals(1, controller.snapshot().menus().size(), "genuine physical leave/re-entry returns exactly one level");
	}

	private static SpatialController returnedToNonRootContentWithHeldBack() {
		var property = SpatialMenu.of("property", SpatialMenu.Choice.leaf("property:leaf", "leaf", "leaf"));
		var content = SpatialMenu.of("content",
			SpatialMenu.Choice.branch("content:first", "first", property),
			SpatialMenu.Choice.branch("content:second", "second", SpatialMenu.of("second",
				SpatialMenu.Choice.leaf("second:leaf", "leaf", "leaf"))));
		var root = SpatialMenu.of("root", SpatialMenu.Choice.branch("content", "content", content).withSector(90, 55));
		var controller = new SpatialController(root, new SpatialController.Tuning(10, 40, 100, true, 400));
		controller.start(0);
		controller.constrainPointer(new SpatialController.Viewport(1200, 1400, 0, 0), 0);
		controller.movePhysical(100, 0, 10);
		controller.tick(110);
		assertEquals(new SpatialController.Point(100, 0), activeOrigin(controller));
		controller.movePhysical(-200, -600, 120);
		assertEquals("content:first", controller.snapshot().focusId());
		controller.tick(220);
		assertEquals(3, controller.snapshot().menus().size());
		assertEquals(new SpatialController.Point(-100, -600), activeOrigin(controller));
		controller.constrainPointer(new SpatialController.Viewport(1200, 1400, 100, 600), 220);
		controller.movePhysical(100, 600, 230);
		assertEquals("property:back", controller.snapshot().focusId());
		controller.tick(630);
		assertEquals(2, controller.snapshot().menus().size(), "first hover returns from property to content");
		assertEquals(new SpatialController.Point(0, 0), activeOrigin(controller));
		assertNull(controller.snapshot().focusId(), "user return lands at the returned origin");
		// A non-input rebase can expose parent Back again, but cannot release the shared held block.
		controller.rebase(-100, 0, 631);
		assertEquals("content:back", controller.snapshot().focusId());
		return controller;
	}

	private static SpatialController.Tuning tuning(long dwellMillis, boolean hoverEnabled, long hoverMillis) {
		return new SpatialController.Tuning(36.0, 110.0, dwellMillis, hoverEnabled, hoverMillis);
	}

	private static SpatialMenu root() {
		SpatialMenu rpm = SpatialMenu.of(
			"content:rpm",
			SpatialMenu.Choice.leaf("content:rpm:attention", "attention", "ping:attention"),
			SpatialMenu.Choice.leaf("content:rpm:danger", "danger", "ping:danger"));
		SpatialMenu content = SpatialMenu.of(
			"content",
			SpatialMenu.Choice.branch("content:rpm", "rpm", rpm),
			SpatialMenu.Choice.leaf("content:items", "items", "ping:attention"));

		return SpatialMenu.of(
			"root",
			SpatialMenu.Choice.leaf("danger", "danger", "ping:danger").withSector(0.0, 55.0),
			SpatialMenu.Choice.reserved("reserved-ne", "reserved").withSector(45.0, 35.0),
			SpatialMenu.Choice.branch("content", "content", content).withSector(90.0, 55.0),
			SpatialMenu.Choice.reserved("reserved-se", "reserved").withSector(135.0, 35.0),
			SpatialMenu.Choice.leaf("cancel-marker", "cancel", "action:clear").withSector(180.0, 55.0),
			SpatialMenu.Choice.leaf("precise", "precise", "ui:precise").withSector(225.0, 35.0),
			SpatialMenu.Choice.leaf("intent", "intent", "ping:attention").withSector(270.0, 55.0),
			SpatialMenu.Choice.leaf("settings", "settings", "ui:settings").withSector(315.0, 35.0));
	}

	private static SpatialMenu halfRoot() {
		return SpatialMenu.of(
			"half",
			SpatialMenu.Choice.leaf("up", "up", "a:up").withSector(0.0, 180.0),
			SpatialMenu.Choice.leaf("down", "down", "a:down").withSector(180.0, 180.0));
	}

	private static void moveToBearing(
		SpatialController controller,
		SpatialController.Point origin,
		double bearingDegrees,
		double distance,
		long nowMillis
	) {
		SpatialController.Point current = controller.snapshot().pointer();
		double radians = Math.toRadians(bearingDegrees);
		double targetX = origin.x() + Math.sin(radians) * distance;
		double targetY = origin.y() - Math.cos(radians) * distance;
		controller.movePhysical(targetX - current.x(), targetY - current.y(), nowMillis);
	}

	private static SpatialController.Point activeOrigin(SpatialController controller) {
		var menus = controller.snapshot().menus();
		return menus.get(menus.size() - 1).origin();
	}

	/** Starts a session, focuses content at bearing 90 and dwells into it. */
	private static SpatialController enteredContent(boolean hoverEnabled, long hoverMillis, long dwellMillis, long enteredAt) {
		SpatialController controller = new SpatialController(root(), tuning(dwellMillis, hoverEnabled, hoverMillis));
		controller.start(0L);
		moveToBearing(controller, controller.snapshot().pointer(), 90.0, 200.0, 10L);
		controller.tick(enteredAt);
		assertEquals(2, controller.snapshot().menus().size(), "helper precondition: content entered");
		return controller;
	}

	@Test
	void centerInsideDeadzoneAbandonsAndReleaseIsSingleUse() {
		SpatialController controller = new SpatialController(root(), tuning(180L, false, 500L));
		controller.start(0L);
		assertTrue(controller.isActive());

		assertInstanceOf(SpatialController.Release.Abandoned.class, controller.release(1L));
		assertFalse(controller.isActive());
		assertInstanceOf(SpatialController.Release.Idle.class, controller.release(2L));
	}

	@Test
	void releaseCommitsFocusedLeafActionOnce() {
		SpatialController controller = new SpatialController(root(), tuning(180L, false, 500L));
		controller.start(0L);
		moveToBearing(controller, controller.snapshot().pointer(), 0.0, 120.0, 10L);

		SpatialController.Release.Committed committed = assertInstanceOf(
			SpatialController.Release.Committed.class,
			controller.release(20L));
		assertEquals("danger", committed.choiceId());
		assertEquals("ping:danger", committed.action());
		assertFalse(controller.isActive());
		assertInstanceOf(SpatialController.Release.Idle.class, controller.release(30L));
	}

	@Test
	void sectorStartIsInclusiveAndPreviousSectorEndsExclusive() {
		SpatialController below = new SpatialController(halfRoot(), tuning(180L, false, 500L));
		below.start(0L);
		moveToBearing(below, below.snapshot().pointer(), 89.9, 120.0, 10L);
		assertEquals("up", below.snapshot().focusId());

		SpatialController exact = new SpatialController(halfRoot(), tuning(180L, false, 500L));
		exact.start(0L);
		moveToBearing(exact, exact.snapshot().pointer(), 90.0, 120.0, 10L);
		assertEquals("down", exact.snapshot().focusId(), "exact sector start belongs to the next half-open sector");

		SpatialController wrap = new SpatialController(halfRoot(), tuning(180L, false, 500L));
		wrap.start(0L);
		moveToBearing(wrap, wrap.snapshot().pointer(), 270.0, 120.0, 10L);
		assertEquals("up", wrap.snapshot().focusId(), "exact start after the 0/360 wrap belongs to the wrapped sector");
	}

	@Test
	void reservedSectorFocusesButNeverCommits() {
		SpatialController controller = new SpatialController(root(), tuning(180L, false, 500L));
		controller.start(0L);
		moveToBearing(controller, controller.snapshot().pointer(), 45.0, 120.0, 10L);
		assertEquals("reserved-ne", controller.snapshot().focusId());

		SpatialController.Release.NoAction noAction = assertInstanceOf(
			SpatialController.Release.NoAction.class,
			controller.release(20L));
		assertEquals(SpatialController.Release.NoAction.Reason.RESERVED, noAction.reason());
		assertEquals("reserved-ne", noAction.choiceId());
	}

	@Test
	void branchWithoutActionAndNavigationNeverCommit() {
		SpatialController controller = new SpatialController(root(), tuning(180L, false, 500L));
		controller.start(0L);
		moveToBearing(controller, controller.snapshot().pointer(), 90.0, 120.0, 10L);

		SpatialController.Release.NoAction branch = assertInstanceOf(
			SpatialController.Release.NoAction.class,
			controller.release(20L));
		assertEquals(SpatialController.Release.NoAction.Reason.BRANCH_WITHOUT_ACTION, branch.reason());
		assertEquals("content", branch.choiceId());
	}

	@Test
	void outsideSectorsWithoutCrossingDeadzoneReportsOutside() {
		SpatialMenu gapped = SpatialMenu.of(
			"gapped",
			SpatialMenu.Choice.leaf("only", "only", "a:only").withSector(0.0, 90.0));
		SpatialController controller = new SpatialController(gapped, tuning(180L, false, 500L));
		controller.start(0L);
		moveToBearing(controller, controller.snapshot().pointer(), 180.0, 120.0, 10L);
		assertNull(controller.snapshot().focusId());

		SpatialController.Release.NoAction noAction = assertInstanceOf(
			SpatialController.Release.NoAction.class,
			controller.release(20L));
		assertEquals(SpatialController.Release.NoAction.Reason.OUTSIDE, noAction.reason());
	}

	@Test
	void disabledEntryFocusesButNeverCommitsAndNeverEnters() {
		SpatialMenu gate = SpatialMenu.of(
			"gate",
			new SpatialMenu.Choice("gate:off", "off", "ping:danger", null, false, true, false, null, null, null),
			SpatialMenu.Choice.leaf("gate:on", "on", "ping:attention"));
		SpatialMenu disabledRoot = SpatialMenu.of(
			"disabled-root",
			SpatialMenu.Choice.branch("gate", "gate", gate).withSector(0.0, 360.0));

		SpatialController controller = new SpatialController(disabledRoot, tuning(200L, false, 500L));
		controller.start(0L);
		moveToBearing(controller, controller.snapshot().pointer(), 0.0, 200.0, 10L);
		controller.tick(210L);
		assertEquals(2, controller.snapshot().menus().size());

		SpatialController.Point gateOrigin = activeOrigin(controller);
		moveToBearing(controller, gateOrigin, 300.0, 120.0, 220L);
		assertEquals("gate:off", controller.snapshot().focusId());

		controller.tick(1000L);
		assertEquals(2, controller.snapshot().menus().size(), "disabled branch must not be entered by dwell");

		SpatialController.Release.NoAction noAction = assertInstanceOf(
			SpatialController.Release.NoAction.class,
			controller.release(1010L));
		assertEquals(SpatialController.Release.NoAction.Reason.DISABLED, noAction.reason());
	}

	@Test
	void dwellEntersBranchAndBackDwellPopsOneLevelWhenHoverDisabled() {
		SpatialController controller = enteredContent(false, 500L, 200L, 210L);
		assertNull(controller.snapshot().focusId(), "child menu starts inside its deadzone");

		SpatialController.Point contentOrigin = activeOrigin(controller);
		moveToBearing(controller, contentOrigin, 282.0, 130.0, 220L);
		assertEquals("content:back", controller.snapshot().focusId());

		controller.tick(419L);
		assertEquals(2, controller.snapshot().menus().size(), "back dwell must wait for the frozen threshold");
		controller.tick(420L);
		assertEquals(1, controller.snapshot().menus().size(), "back dwell pops exactly one level");
		assertEquals(controller.snapshot().pointer(), activeOrigin(controller), "the root aligns with the Back trigger");
		assertNull(controller.snapshot().focusId(), "a user return leaves the pointer in the returned deadzone");
	}

	@Test
	void freshStrokeAfterPopPreventsStationaryReentryUntilNewStroke() {
		SpatialController controller = enteredContent(false, 500L, 200L, 210L);
		moveToBearing(controller, activeOrigin(controller), 282.0, 130.0, 220L);
		controller.tick(420L);
		assertEquals(1, controller.snapshot().menus().size());

		controller.tick(700L);
		assertEquals(1, controller.snapshot().menus().size(), "stationary pointer must not re-enter after a pop");

		controller.movePhysical(60.0, 0.0, 710L);
		controller.tick(910L);
		assertEquals(1, controller.snapshot().menus().size(), "a partial new stroke is not enough to re-enter");

		controller.movePhysical(60.0, 0.0, 920L);
		controller.tick(1120L);
		assertEquals(2, controller.snapshot().menus().size(), "a full fresh stroke plus dwell re-enters");
	}

	@Test
	void hoverReturnPopsOneLevelAndDelegatesProgress() {
		SpatialController controller = enteredContent(true, 400L, 200L, 210L);
		SpatialController.Point contentOrigin = activeOrigin(controller);
		moveToBearing(controller, contentOrigin, 270.0, 50.0, 220L);
		assertEquals("content:back", controller.snapshot().focusId());
		assertEquals(0.0, controller.snapshot().hoverProgress(), DELTA);

		controller.tick(320L);
		assertEquals(0.25, controller.snapshot().hoverProgress(), DELTA);
		assertEquals(2, controller.snapshot().menus().size());

		controller.tick(620L);
		assertEquals(1, controller.snapshot().menus().size(), "completed hover pops exactly one level");
		assertEquals(0.0, controller.snapshot().hoverProgress(), DELTA);

		controller.tick(2000L);
		controller.tick(3000L);
		assertEquals(1, controller.snapshot().menus().size(), "held focus after return must not cascade");
	}

	@Test
	void hoverEnabledSuppressesBackDwell() {
		SpatialController controller = enteredContent(true, 5000L, 200L, 210L);
		moveToBearing(controller, activeOrigin(controller), 270.0, 50.0, 220L);
		assertEquals("content:back", controller.snapshot().focusId());

		controller.tick(1000L);
		assertEquals(2, controller.snapshot().menus().size(), "hover mode ignores the ordinary back dwell");
	}

	@Test
	void physicalDeltasAccumulateAndRebaseStartsFreshTrail() {
		SpatialController controller = new SpatialController(root(), tuning(180L, false, 500L));
		controller.start(0L);

		controller.movePhysical(30.0, 0.0, 1L);
		controller.movePhysical(0.0, -40.0, 2L);
		assertEquals(new SpatialController.Point(30.0, -40.0), controller.snapshot().pointer());

		controller.rebase(500.0, 500.0, 3L);
		assertEquals(new SpatialController.Point(500.0, 500.0), controller.snapshot().pointer());
		assertEquals(1, controller.snapshot().trail().size());

		controller.movePhysical(10.0, 0.0, 4L);
		assertEquals(new SpatialController.Point(510.0, 500.0), controller.snapshot().pointer());
		assertEquals(2, controller.snapshot().trail().size());
	}

	@Test void sampleIdentityAndDiscontinuitiesDistinguishEffectiveInputFromProjectionAndNavigation() {
		var controller = new SpatialController(root(), new SpatialController.Tuning(12, 20, 10000, false, 500, 17));
		controller.start(0); controller.constrainPointer(new SpatialController.Viewport(100, 100, 0, 0), 0);
		var initial = controller.snapshot();
		controller.movePhysical(1000, 0, 1); var moved = controller.snapshot();
		assertEquals(initial.trailSequence() + 1, moved.trailSequence());
		controller.movePhysical(1000, 0, 2); assertEquals(moved, controller.snapshot(), "saturated callbacks are not samples");
		controller.constrainPointer(new SpatialController.Viewport(100, 100, 10, 0), 3); var corrected = controller.snapshot();
		assertEquals(moved.trailSequence(), corrected.trailSequence());
		assertEquals(moved.trailEpoch(), corrected.trailEpoch()); assertTrue(corrected.trailAlignment() > moved.trailAlignment());
		assertEquals(moved.trailSequence(), corrected.trailAlignmentSequence(), "precise raw-sample fence at correction");
		controller.rebase(corrected.pointer().x(), corrected.pointer().y(), 4);
		assertTrue(controller.snapshot().trailEpoch() > corrected.trailEpoch(), "coincident coordinates cannot hide rebase");
		for (int i = 0; i < 100; i++) controller.movePhysical(i % 2 == 0 ? -1 : 1, 0, 5);
		assertEquals(17, controller.snapshot().trail().size());
		long epoch = controller.snapshot().trailEpoch();
		controller.enterExternal(SpatialMenu.of("external", SpatialMenu.Choice.leaf("x", "x", "x")), 10, 20, 6);
		assertTrue(controller.snapshot().trailEpoch() > epoch); assertEquals(1, controller.snapshot().trail().size());
		epoch = controller.snapshot().trailEpoch(); controller.back(7);
		assertTrue(controller.snapshot().trailEpoch() > epoch); assertEquals(1, controller.snapshot().trail().size());
	}

	@Test
	void cancelClearsSessionIdempotentlyAndReleaseStaysIdle() {
		SpatialController controller = new SpatialController(root(), tuning(180L, false, 500L));
		controller.start(0L);
		controller.movePhysical(0.0, -120.0, 10L);

		assertTrue(controller.cancel());
		assertFalse(controller.isActive());
		assertFalse(controller.cancel());
		assertInstanceOf(SpatialController.Release.Idle.class, controller.release(20L));
		assertEquals(0, controller.snapshot().menus().size());
	}

	@Test
	void enterExternalPushesMenuAtGivenOriginAndRebasesPointer() {
		SpatialController controller = new SpatialController(root(), tuning(180L, false, 500L));
		controller.start(0L);
		controller.movePhysical(200.0, 0.0, 10L);

		SpatialMenu external = SpatialMenu.of(
			"external",
			SpatialMenu.Choice.leaf("external:up", "up", "a:up"));
		controller.enterExternal(external, 400.0, 300.0, 20L);

		assertEquals(2, controller.snapshot().menus().size());
		assertEquals(new SpatialController.Point(400.0, 300.0), controller.snapshot().pointer());
		assertEquals(1, controller.snapshot().trail().size());
		assertNull(controller.snapshot().focusId(), "external entry starts inside its own deadzone");

		var externalView = controller.snapshot().menus().get(1);
		assertEquals("external", externalView.menuId());
		assertEquals(2, externalView.choices().size(), "non-root menu gains one automatic Back entry");
		assertTrue(externalView.choices().stream().anyMatch(SpatialController.ChoiceView::back));
		assertNull(externalView.entryChoiceId(), "an externally pushed menu carries no radial entry");

		SpatialController.ChoiceView leaf = externalView.choices().get(0);
		moveToBearing(controller, externalView.origin(), leaf.startDegrees() + leaf.spanDegrees() / 2.0, 120.0, 30L);
		assertEquals("external:up", controller.snapshot().focusId());
	}

	@Test
	void menuViewsExposeTheRadialEntryChoiceOfEachPushedLevel() {
		SpatialController controller = enteredContent(false, 500L, 200L, 210L);
		var content = controller.snapshot().menus();
		assertNull(content.get(0).entryChoiceId(), "the root has no parent entry");
		assertEquals("content", content.get(1).entryChoiceId(), "content records the root choice that opened it");

		SpatialController.Point contentOrigin = activeOrigin(controller);
		moveToBearing(controller, contentOrigin, 30.0, 150.0, 220L);
		assertEquals("content:rpm", controller.snapshot().focusId());
		controller.tick(430L);

		var menus = controller.snapshot().menus();
		assertEquals(3, menus.size());
		assertEquals("content:rpm", menus.get(2).entryChoiceId(), "each level records its own opening choice");
		assertEquals("content", menus.get(1).entryChoiceId(), "an ancestor keeps its original entry edge");
	}

	@Test
	void explicitBackIsCenteredOnParentBearingAndSiblingsTile() {
		SpatialMenu gate = SpatialMenu.of(
			"gate",
			SpatialMenu.Choice.leaf("gate:x", "x", "a:x"),
			SpatialMenu.Choice.back("gate:back"),
			SpatialMenu.Choice.leaf("gate:y", "y", "a:y"));
		SpatialMenu explicitRoot = SpatialMenu.of(
			"explicit-root",
			SpatialMenu.Choice.branch("gate", "gate", gate).withSector(0.0, 360.0));

		SpatialController controller = new SpatialController(explicitRoot, tuning(200L, false, 500L));
		controller.start(0L);
		moveToBearing(controller, controller.snapshot().pointer(), 0.0, 200.0, 10L);
		controller.tick(210L);
		assertEquals(2, controller.snapshot().menus().size(), "gate must be entered before checking sectors");

		var gateView = controller.snapshot().menus().get(1);
		assertEquals(3, gateView.choices().size());

		SpatialController.ChoiceView back = gateView.choices().stream()
			.filter(SpatialController.ChoiceView::back)
			.findFirst()
			.orElseThrow();
		double backCentre = (back.startDegrees() + back.spanDegrees() / 2.0) % 360.0;
		assertEquals(180.0, backCentre, 1.0e-9, "an explicit Back must still face the parent origin");

		gateView.choices().forEach(choice -> assertEquals(120.0, choice.spanDegrees(), 1.0e-9));
		List<Double> starts = gateView.choices().stream()
			.map(SpatialController.ChoiceView::startDegrees)
			.sorted()
			.toList();
		assertEquals(0.0, starts.get(0), 1.0e-9);
		assertEquals(120.0, starts.get(1), 1.0e-9);
		assertEquals(240.0, starts.get(2), 1.0e-9, "equal sibling spans must tile without overlap");

		moveToBearing(controller, gateView.origin(), 192.0, 150.0, 220L);
		assertEquals("gate:back", controller.snapshot().focusId(), "parent-direction sector selects the explicit Back");

		controller.tick(420L);
		assertEquals(1, controller.snapshot().menus().size(), "the explicit Back dwell still pops one level");
	}

	@Test
	void automaticBackEntryHasALocalizedLabelAndReleaseStaysNavigation() {
		SpatialController controller = enteredContent(false, 500L, 200L, 210L);
		SpatialController.Point contentOrigin = activeOrigin(controller);
		moveToBearing(controller, contentOrigin, 282.0, 130.0, 220L);
		assertEquals("content:back", controller.snapshot().focusId());

		SpatialController.ChoiceView back = controller.snapshot().menus().get(1).choices().stream()
			.filter(SpatialController.ChoiceView::back)
			.findFirst()
			.orElseThrow();
		assertEquals("pingforit.spatial.back", back.label(), "the automatic Back entry carries a localized label key");
		assertNull(back.action(), "the automatic Back entry commits nothing");
		assertFalse(back.branch(), "the automatic Back entry opens nothing");

		SpatialController.Release.NoAction noAction = assertInstanceOf(
			SpatialController.Release.NoAction.class,
			controller.release(230L));
		assertEquals(SpatialController.Release.NoAction.Reason.NAVIGATION, noAction.reason());
		assertEquals("content:back", noAction.choiceId());
		assertFalse(controller.isActive(), "a Back release ends the session without committing");
	}

	@Test
	void qualifiedTurnEntersBranchAtCornerWithoutDwell() {
		SpatialController controller = new SpatialController(root(), tuning(200L, false, 500L));
		controller.start(0L);
		moveToBearing(controller, controller.snapshot().pointer(), 90.0, 200.0, 10L);
		assertEquals("content", controller.snapshot().focusId());

		controller.movePhysical(100.0, 0.0, 50L);
		controller.movePhysical(0.0, -100.0, 60L);

		assertEquals(2, controller.snapshot().menus().size(), "a qualified turn enters without waiting for dwell");
		SpatialController.Point childOrigin = activeOrigin(controller);
		assertEquals(200.0, childOrigin.x(), DELTA);
		assertEquals(0.0, childOrigin.y(), DELTA, "the child opens at the corner");
	}

	@Test
	void disabledBranchCannotEnterByTurn() {
		SpatialMenu deep = SpatialMenu.of("gate:off:sub", SpatialMenu.Choice.leaf("gate:off:x", "x", "a:x"));
		SpatialMenu gate = SpatialMenu.of(
			"gate",
			new SpatialMenu.Choice("gate:off", "off", "ping:danger", deep, false, true, false, null, null, null),
			SpatialMenu.Choice.leaf("gate:on", "on", "ping:attention"));
		SpatialMenu turnRoot = SpatialMenu.of(
			"turn-root",
			SpatialMenu.Choice.branch("gate", "gate", gate).withSector(0.0, 360.0));

		SpatialController controller = new SpatialController(turnRoot, tuning(200L, false, 500L));
		controller.start(0L);
		moveToBearing(controller, controller.snapshot().pointer(), 0.0, 200.0, 10L);
		controller.tick(210L);
		assertEquals(2, controller.snapshot().menus().size());

		moveToBearing(controller, activeOrigin(controller), 300.0, 120.0, 220L);
		assertEquals("gate:off", controller.snapshot().focusId());

		moveToBearing(controller, activeOrigin(controller), 300.0, 240.0, 260L);
		moveToBearing(controller, activeOrigin(controller), 60.0, 240.0, 270L);

		assertEquals(2, controller.snapshot().menus().size(), "a disabled branch must not be entered by turn");
	}

	@Test
	void hoverModeBackQualifiedTurnDoesNotPop() {
		SpatialController controller = enteredContent(true, 5000L, 200L, 210L);
		SpatialController.Point contentOrigin = activeOrigin(controller);

		moveToBearing(controller, contentOrigin, 270.0, 130.0, 220L);
		assertEquals("content:back", controller.snapshot().focusId());

		moveToBearing(controller, contentOrigin, 270.0, 260.0, 260L);
		moveToBearing(controller, contentOrigin, 180.0, 260.0, 270L);

		assertEquals(2, controller.snapshot().menus().size(), "hover mode must not pop a Back by turn");
	}

	@Test
	void reverseStrokeRetracePopsOneLevelWhenHoverOff() {
		SpatialController controller = enteredContent(false, 500L, 200L, 210L);

		controller.movePhysical(-50.0, 0.0, 220L);
		assertEquals(2, controller.snapshot().menus().size(), "half the corridor is not enough");

		controller.movePhysical(-20.0, 0.0, 230L);
		assertEquals(1, controller.snapshot().menus().size(), "the reverse stroke pops exactly one level");
	}

	@Test
	void rowAnchoredExternalMenuDoesNotRetrace() {
		SpatialController controller = new SpatialController(root(), tuning(200L, false, 500L));
		controller.start(0L);
		controller.movePhysical(200.0, 0.0, 10L);

		SpatialMenu external = SpatialMenu.of(
			"external",
			SpatialMenu.Choice.leaf("external:up", "up", "a:up"));
		controller.enterExternal(external, 400.0, 300.0, 20L);

		controller.movePhysical(-120.0, -90.0, 30L);

		assertEquals(2, controller.snapshot().menus().size(), "row-anchored menus must not use the reverse-stroke shortcut");
	}

	@Test
	void incrementalSubStepReverseSamplesPopExactlyOnceAtTheNetDistance() {
		SpatialController controller = enteredContent(false, 500L, 200L, 210L);

		for (int sample = 1; sample <= 67; sample++) {
			controller.movePhysical(-1.0, 0.0, 220L + sample);
		}
		assertEquals(2, controller.snapshot().menus().size(), "one pixel below the net return distance is not enough");

		controller.movePhysical(-1.0, 0.0, 288L);
		assertEquals(1, controller.snapshot().menus().size(),
			"the incremental sample reaching the net distance pops exactly one level");

		for (int sample = 0; sample < 20; sample++) {
			controller.movePhysical(-1.0, 0.0, 289L + sample);
		}
		assertEquals(1, controller.snapshot().menus().size(), "further reverse samples cannot pop another level");
	}

	@Test
	void exactTwoPixelReverseSamplePopsOnceTheNetDistanceIsReached() {
		SpatialController controller = enteredContent(false, 500L, 200L, 210L);

		controller.movePhysical(-66.0, 0.0, 220L);
		assertEquals(2, controller.snapshot().menus().size(), "66 px is below the net return distance");

		controller.movePhysical(-2.0, 0.0, 230L);
		assertEquals(1, controller.snapshot().menus().size(),
			"an exact two-pixel sample is positive progress once the net distance is reached");
	}

	@Test
	void zeroReverseTangentialAndOutOfCorridorMovementCannotPopAtTheThreshold() {
		SpatialController controller = enteredContent(false, 500L, 200L, 210L);

		controller.movePhysical(-80.0, 20.0, 220L);
		assertEquals(2, controller.snapshot().menus().size(), "sideways beyond the corridor is not a return");
		controller.movePhysical(0.0, -5.0, 230L);
		assertEquals(2, controller.snapshot().menus().size(), "tangential movement adds no parent-directed progress");
		controller.movePhysical(10.0, 0.0, 240L);
		assertEquals(2, controller.snapshot().menus().size(), "outward movement cannot pop");
		controller.movePhysical(0.0, 0.0, 250L);
		assertEquals(2, controller.snapshot().menus().size(), "a zero delta is not a sample");

		controller.movePhysical(-10.0, 0.0, 260L);
		assertEquals(1, controller.snapshot().menus().size(),
			"a strictly parent-directed sample inside the corridor pops at the net distance");
	}

	@Test
	void hoverModeReverseStrokeDoesNotUseTheRetraceShortcut() {
		SpatialController controller = enteredContent(true, 5000L, 200L, 210L);

		controller.movePhysical(-120.0, 0.0, 220L);

		assertEquals(2, controller.snapshot().menus().size(), "hover mode owns Back instead of retracing");
		assertEquals("content:back", controller.snapshot().focusId());
	}

	@Test
	void returnPlacementFillsTheInclusiveDeadzoneCircumferenceAndAbandonsOnRelease() {
		var controller = new SpatialController(root(), tuning(180L, false, 500L));
		controller.start(0L);
		controller.constrainPointer(new SpatialController.Viewport(400, 400, 0, 0), 0L);
		double epoch = controller.snapshot().trailEpoch();
		double radians = Math.toRadians(33.0);
		controller.placeReturn(new SpatialController.Point(Math.sin(radians) * 36.0, -Math.cos(radians) * 36.0), 10L);
		var placed = controller.snapshot();
		assertEquals(Math.sin(radians) * 36.0, placed.pointer().x(), DELTA);
		assertEquals(-Math.cos(radians) * 36.0, placed.pointer().y(), DELTA);
		assertEquals(36.0, Math.hypot(placed.pointer().x(), placed.pointer().y()), 1e-9,
			"the frozen circumference radius is preserved");
		assertNull(placed.focusId(), "the inclusive deadzone admits no immediate activation");
		assertEquals(1, placed.trail().size());
		assertTrue(placed.trailEpoch() > epoch, "an explicit return starts a fresh trail identity");
		// A hair outside is corrected inward by the minimal representable step, not a product inset.
		controller.placeReturn(new SpatialController.Point(36.0 + 1.0e-13, 0.0), 20L);
		double corrected = Math.hypot(controller.snapshot().pointer().x(), controller.snapshot().pointer().y());
		assertTrue(corrected <= 36.0, "rounding must land inside the inclusive deadzone");
		assertEquals(36.0, corrected, 1e-9, "the correction is a rounding step, not a visible inset");
		assertNull(controller.snapshot().focusId());
		assertInstanceOf(SpatialController.Release.Abandoned.class, controller.release(30L));
	}

	@ParameterizedTest
	@CsvSource({
		"496, -16, 88.1523897340054",
		"-496, 16, 268.1523897340054",
		"496, 16, 88.1523897340054",
		"1000.5, 200.25, 20"
	})
	void returnPlacementAtNonZeroOriginReconstructsInsideTheInclusiveDeadzone(double originX, double originY,
		double bearing) {
		var controller = new SpatialController(SpatialMenu.of("root",
			SpatialMenu.Choice.leaf("up", "up", "a:up").withSector(0.0, 360.0)),
			new SpatialController.Tuning(18, 110, 180, false, 500));
		controller.start(0L);
		controller.constrainPointer(new SpatialController.Viewport(2400, 1600, 0, 0), 0L);
		controller.enterExternal(SpatialMenu.of("child", SpatialMenu.Choice.leaf("child:leaf", "leaf", "a:leaf")),
			originX, originY, 10L);

		double radians = Math.toRadians(bearing);
		controller.placeReturn(new SpatialController.Point(originX + Math.sin(radians) * 18.0,
			originY - Math.cos(radians) * 18.0), 20L);

		var returned = controller.snapshot();
		var origin = returned.menus().getLast().origin();
		assertEquals(originX, origin.x(), DELTA);
		assertEquals(originY, origin.y(), DELTA);
		double distance = Math.hypot(returned.pointer().x() - origin.x(), returned.pointer().y() - origin.y());
		assertTrue(distance <= 18.0,
			"the reconstructed absolute point must satisfy the same inclusive deadzone as focus and release");
		assertNull(returned.focusId(), "the inclusive deadzone admits no immediate activation");
		assertInstanceOf(SpatialController.Release.Abandoned.class, controller.release(30L));
	}

	@Test
	void pendingReturnResolutionCannotPayTheFreshStrokeRequiredByItsPop() {
		var controller = enteredContent(false, 500L, 200L, 210L);
		SpatialController.Point contentOrigin = activeOrigin(controller);
		moveToBearing(controller, contentOrigin, 30.0, 130.0, 220L);
		assertEquals("content:rpm", controller.snapshot().focusId());
		controller.tick(420L);
		assertEquals(3, controller.snapshot().menus().size());
		SpatialController.Point rpmOrigin = activeOrigin(controller);
		moveToBearing(controller, rpmOrigin, 222.0, 130.0, 430L);
		assertEquals("content:rpm:back", controller.snapshot().focusId());
		assertTrue(controller.backProgrammatically(630L));
		assertEquals(2, controller.snapshot().menus().size(), "a programmatic pop preserves the placement being tested");
		// The published projection cannot show the Content deadzone circumference yet.
		controller.constrainPointer(new SpatialController.Viewport(100, 100, 400, 0), 640L);
		var confined = controller.snapshot().pointer();
		var trail = controller.snapshot().trail();
		controller.placeReturn(new SpatialController.Point(236.0, 0.0), 650L);
		assertEquals(confined, controller.snapshot().pointer(), "an unreachable return stays pending without travel");
		assertEquals(trail, controller.snapshot().trail());
		assertEquals(0.0, controller.snapshot().hoverProgress());
		controller.constrainPointer(new SpatialController.Viewport(1000, 1000, 0, 0), 660L);
		var returned = controller.snapshot();
		assertEquals(new SpatialController.Point(236.0, 0.0), returned.pointer());
		assertNull(returned.focusId());
		assertEquals(List.of(returned.pointer()), returned.trail(), "resolution rebases the seed and appends no stroke");
		controller.tick(1000L);
		assertEquals(2, controller.snapshot().menus().size(), "projection resolution pays no fresh stroke by itself");
		// A partial new stroke cannot pay the pop's fence even after dwell.
		moveToBearing(controller, contentOrigin, 30.0, 96.0, 1010L);
		controller.tick(1010L + 201L);
		assertEquals(2, controller.snapshot().menus().size(), "a partial stroke cannot re-enter after a return placement");
		// The remaining admitted stroke plus dwell re-enters exactly the focused branch.
		moveToBearing(controller, contentOrigin, 30.0, 146.0, 1220L);
		controller.tick(1220L + 201L);
		assertEquals(3, controller.snapshot().menus().size(), "a full fresh stroke plus dwell re-enters");
	}

	@Test
	void freshEffectivePhysicalMovementCancelsAPendingReturn() {
		var controller = enteredContent(false, 500L, 200L, 210L);
		controller.constrainPointer(new SpatialController.Viewport(100, 100, 200, 0), 220L);
		controller.placeReturn(new SpatialController.Point(236.0, 0.0), 230L);
		assertEquals(-151.0, controller.snapshot().pointer().x(), DELTA);
		controller.movePhysical(-10.0, 0.0, 240L);
		assertEquals(-161.0, controller.snapshot().pointer().x(), DELTA);
		controller.constrainPointer(new SpatialController.Viewport(1000, 1000, 0, 0), 250L);
		assertEquals(-161.0, controller.snapshot().pointer().x(), DELTA,
			"a fresh physical stroke keeps its own position instead of chasing the abandoned target");
	}

	@Test
	void pendingReturnResolutionDoesNotRearmHeldBackOrAppendTrail() {
		var controller = returnedToNonRootContentWithHeldBack();
		controller.constrainPointer(new SpatialController.Viewport(100, 100, 200, 0), 640L);
		var trail = controller.snapshot().trail();
		controller.placeReturn(new SpatialController.Point(10.0, 0.0), 650L);
		assertEquals(trail, controller.snapshot().trail(), "a pending return adds no travel or stroke");
		controller.constrainPointer(new SpatialController.Viewport(1200, 1400, 0, 0), 660L);
		var returned = controller.snapshot();
		assertEquals(new SpatialController.Point(10.0, 0.0), returned.pointer());
		assertNull(returned.focusId());
		assertEquals(List.of(returned.pointer()), returned.trail(), "resolution rebases the trail seed");
		var resolved = controller.snapshot();
		controller.tick(1000L);
		controller.tick(1400L);
		assertEquals(2, controller.snapshot().menus().size(), "projection and stationary samples cannot rearm the held Back");
		assertEquals(resolved.trail(), controller.snapshot().trail(), "no synthetic stroke is appended after the seed");
		assertEquals(0.0, controller.snapshot().hoverProgress());
		// A genuine physical leave and re-entry still arms exactly one new return.
		controller.movePhysical(90.0, 0.0, 1410L);
		controller.movePhysical(-130.0, 0.0, 1420L);
		assertEquals("content:back", controller.snapshot().focusId());
		controller.tick(1821L);
		assertEquals(1, controller.snapshot().menus().size(), "a deliberate leave/re-entry re-arms the held Back");
	}

	@Test
	void rebaseClearsPendingTurnCandidate() {
		SpatialMenu deep = SpatialMenu.of("deep", SpatialMenu.Choice.leaf("deep:leaf", "leaf", "a:leaf"));
		SpatialMenu inner = SpatialMenu.of("inner", SpatialMenu.Choice.branch("inner:deep", "deep", deep));
		SpatialMenu gate = SpatialMenu.of(
			"gate",
			SpatialMenu.Choice.branch("gate:inner", "inner", inner),
			SpatialMenu.Choice.leaf("gate:other", "other", "a:other"));
		SpatialMenu turnRoot = SpatialMenu.of(
			"turn-root",
			SpatialMenu.Choice.branch("gate", "gate", gate).withSector(0.0, 360.0));

		SpatialController controller = new SpatialController(turnRoot, tuning(200L, false, 500L));
		controller.start(0L);
		moveToBearing(controller, controller.snapshot().pointer(), 0.0, 200.0, 10L);
		controller.tick(210L);

		SpatialController.Point gateOrigin = activeOrigin(controller);
		moveToBearing(controller, gateOrigin, 300.0, 120.0, 220L);
		assertEquals("gate:inner", controller.snapshot().focusId());

		moveToBearing(controller, gateOrigin, 300.0, 240.0, 260L);

		controller.rebase(-259.8076211353316, -50.0, 270L);
		assertEquals("gate:inner", controller.snapshot().focusId());

		controller.movePhysical(5.0, 0.0, 280L);
		assertEquals(2, controller.snapshot().menus().size(), "rebase must not leave a phantom turn corner");
	}

	@ParameterizedTest
	@CsvSource({"explicit,175,0", "retrace,130,0", "dwell,80,40", "turn,80,60", "hover,150,0"})
	void everyRadialUserReturnAlignsRootAtTheAdmittedTriggerAndImmediateReleaseAbandons(
		String path, double triggerX, double triggerY) {
		var controller = enteredContent(path.equals("hover"), 400, 200, 210);
		long epoch = controller.snapshot().trailEpoch();
		switch (path) {
			case "explicit" -> {
				controller.movePhysical(-25, 0, 220);
				assertTrue(controller.back(230));
			}
			case "retrace" -> controller.movePhysical(-70, 0, 220);
			case "dwell" -> {
				controller.movePhysical(-120, 40, 220); // Back, outside the narrow retrace corridor
				assertEquals("content:back", controller.snapshot().focusId());
				controller.tick(420);
			}
			case "turn" -> {
				controller.movePhysical(-120, 40, 220);
				assertEquals("content:back", controller.snapshot().focusId());
				controller.movePhysical(0, 20, 260); // a qualified turn, still outside the retrace corridor
			}
			case "hover" -> {
				controller.movePhysical(-50, 0, 220);
				controller.tick(620);
			}
			default -> throw new AssertionError("unknown return path");
		}
		var returned = controller.snapshot();
		var trigger = returned.pointer();
		assertEquals(1, returned.menus().size(), "each trigger removes only one child");
		assertEquals(triggerX, trigger.x(), DELTA, "return never moves the admitted trigger pointer");
		assertEquals(triggerY, trigger.y(), DELTA);
		assertEquals(trigger, returned.menus().getFirst().origin());
		assertEquals(trigger, returned.menus().getFirst().parentOrigin(), "the root anchor translates too");
		assertEquals(new SpatialController.UserReturnPlacement(1, trigger), controller.userReturnPlacement());
		assertNull(returned.focusId());
		assertEquals(List.of(trigger), returned.trail(), "a return seeds an epoch without a joining segment");
		assertTrue(returned.trailEpoch() > epoch);
		controller.tick(10_000);
		assertEquals(1, controller.snapshot().menus().size(), "idle frames cannot reopen the departed child");
		assertInstanceOf(SpatialController.Release.Abandoned.class, controller.release(10_001));
	}

	@Test
	void retraceUsesTheConfinedPointerBeforeAligningTheRoot() {
		var controller = enteredContent(false, 500, 200, 210);
		controller.constrainPointer(new SpatialController.Viewport(200, 200, -200, 0), 220);
		controller.movePhysical(-Double.MAX_VALUE, 0, 230);
		var returned = controller.snapshot();
		assertEquals(100, returned.pointer().x(), DELTA, "only the admitted boundary point returns");
		assertEquals(0, returned.pointer().y(), DELTA);
		assertEquals(returned.pointer(), activeOrigin(controller));
		assertEquals(new SpatialController.UserReturnPlacement(1, returned.pointer()),
			controller.userReturnPlacement());
		assertEquals(List.of(returned.pointer()), returned.trail());
	}

	@Test
	void nestedUserReturnsRigidlyTranslateEveryAncestorAndIndependentRowAnchor() {
		var controller = new SpatialController(root(), tuning(200, false, 500));
		controller.start(0);
		controller.movePhysical(200, 0, 10);
		controller.tick(210);
		controller.movePhysical(80, -160, 220);
		controller.tick(420);
		assertEquals(3, controller.snapshot().menus().size());
		assertEquals("content:rpm", controller.snapshot().menus().getLast().entryChoiceId());
		controller.enterExternal(SpatialMenu.of("list"), new SpatialController.Point(430, -95),
			new SpatialController.Point(280, -120), 430);
		controller.useExternalSurface();
		controller.alignExternalBack(true);
		controller.enterExternal(SpatialMenu.of("item", SpatialMenu.Choice.leaf("item:leaf", "leaf", "a:leaf")),
			new SpatialController.Point(550, -85), new SpatialController.Point(430, -85), 440);
		var before = controller.snapshot();
		assertTrue(controller.back(450));
		var returned = controller.snapshot();
		assertRigidReturn(before, returned, new SpatialController.Point(120, 10));
		assertEquals(new SpatialController.Point(400, -110), returned.menus().getLast().parentOrigin(),
			"the retained row anchor is translated, not replaced with the previous radial origin");
		assertNull(returned.focusId(), "the returned list clears stale external Back focus");
		assertEquals(new SpatialController.UserReturnPlacement(1, new SpatialController.Point(120, 10)),
			controller.userReturnPlacement());
		assertTrue(controller.back(460));
		assertRigidReturn(returned, controller.snapshot(), new SpatialController.Point(150, 65));
		assertEquals(new SpatialController.UserReturnPlacement(2, new SpatialController.Point(270, 75)),
			controller.userReturnPlacement());
		assertTrue(controller.back(470));
		assertTrue(controller.back(480));
		assertEquals(new SpatialController.Point(550, -85), activeOrigin(controller));
		assertEquals(new SpatialController.UserReturnPlacement(4, new SpatialController.Point(550, -85)),
			controller.userReturnPlacement(), "successive vectors accumulate across nested and root returns");
		assertInstanceOf(SpatialController.Release.Abandoned.class, controller.release(490));
	}

	private static void assertRigidReturn(SpatialController.Snapshot before, SpatialController.Snapshot after,
		SpatialController.Point delta) {
		assertEquals(before.menus().size() - 1, after.menus().size());
		assertEquals(before.pointer(), after.pointer());
		assertEquals(after.pointer(), after.menus().getLast().origin());
		assertEquals(List.of(after.pointer()), after.trail());
		assertTrue(after.trailEpoch() > before.trailEpoch());
		for (int index = 0; index < after.menus().size(); index++) {
			var oldMenu = before.menus().get(index);
			var newMenu = after.menus().get(index);
			assertEquals(oldMenu.origin().x() + delta.x(), newMenu.origin().x(), DELTA);
			assertEquals(oldMenu.origin().y() + delta.y(), newMenu.origin().y(), DELTA);
			assertEquals(oldMenu.parentOrigin().x() + delta.x(), newMenu.parentOrigin().x(), DELTA);
			assertEquals(oldMenu.parentOrigin().y() + delta.y(), newMenu.parentOrigin().y(), DELTA);
			assertEquals(oldMenu.origin().x() - oldMenu.parentOrigin().x(),
				newMenu.origin().x() - newMenu.parentOrigin().x(), DELTA);
			assertEquals(oldMenu.origin().y() - oldMenu.parentOrigin().y(),
				newMenu.origin().y() - newMenu.parentOrigin().y(), DELTA);
			assertEquals(oldMenu.menuId(), newMenu.menuId());
			assertEquals(oldMenu.entryChoiceId(), newMenu.entryChoiceId());
			assertEquals(oldMenu.choices().size(), newMenu.choices().size());
			for (int choice = 0; choice < oldMenu.choices().size(); choice++) {
				var oldChoice = oldMenu.choices().get(choice);
				var newChoice = newMenu.choices().get(choice);
				assertEquals(oldChoice.id(), newChoice.id());
				assertEquals(oldChoice.label(), newChoice.label());
				assertEquals(oldChoice.action(), newChoice.action());
				assertEquals(oldChoice.back(), newChoice.back());
				assertEquals(oldChoice.branch(), newChoice.branch());
				assertEquals(oldChoice.disabled(), newChoice.disabled());
				assertEquals(oldChoice.reserved(), newChoice.reserved());
				assertEquals(oldChoice.outlineColor(), newChoice.outlineColor());
				assertEquals(oldChoice.startDegrees(), newChoice.startDegrees(), DELTA);
				assertEquals(oldChoice.spanDegrees(), newChoice.spanDegrees(), DELTA);
			}
		}
	}

	@Test
	void incrementalRetraceFromANonRootChildCannotCascadeWithoutANewReverseStroke() {
		var leaf = SpatialMenu.of("leaf", SpatialMenu.Choice.leaf("leaf:action", "leaf", "a:leaf"));
		var parent = SpatialMenu.of("parent", SpatialMenu.Choice.branch("parent:child", "child", leaf));
		var root = SpatialMenu.of("root", SpatialMenu.Choice.branch("root:parent", "parent", parent).withSector(90, 360));
		var controller = new SpatialController(root, new SpatialController.Tuning(10, 40, 100, false, 400));
		controller.start(0);
		controller.movePhysical(200, 0, 10);
		controller.tick(110);
		controller.movePhysical(200, 0, 120);
		controller.tick(220);
		assertEquals(3, controller.snapshot().menus().size());
		for (int sample = 1; sample <= 67; sample++) controller.movePhysical(-1, 0, 230 + sample);
		assertEquals(3, controller.snapshot().menus().size());
		controller.movePhysical(-1, 0, 298);
		assertEquals(2, controller.snapshot().menus().size());
		assertEquals(new SpatialController.Point(332, 0), activeOrigin(controller));
		assertEquals(new SpatialController.Point(132, 0), controller.snapshot().menus().getFirst().origin());
		controller.tick(10_000);
		assertEquals(2, controller.snapshot().menus().size(), "a stationary non-root return cannot cascade");
		for (int sample = 1; sample <= 67; sample++) controller.movePhysical(-1, 0, 10_000 + sample);
		assertEquals(2, controller.snapshot().menus().size(), "the next level requires its own full retrace distance");
		controller.movePhysical(-1, 0, 10_068);
		assertEquals(1, controller.snapshot().menus().size());
		assertEquals(new SpatialController.Point(264, 0), activeOrigin(controller));
		assertEquals(new SpatialController.UserReturnPlacement(2, new SpatialController.Point(264, 0)),
			controller.userReturnPlacement());
	}

	@Test
	void programmaticReturnKeepsPlacementAndOldFocusWithoutAUserNotification() {
		var controller = enteredContent(false, 500, 200, 210);
		controller.movePhysical(-50, 0, 220);
		var before = controller.snapshot();
		assertTrue(controller.backProgrammatically(230));
		var returned = controller.snapshot();
		assertEquals(before.pointer(), returned.pointer());
		assertEquals(before.menus().getFirst().origin(), activeOrigin(controller));
		assertEquals(before.menus().getFirst().parentOrigin(), returned.menus().getFirst().parentOrigin());
		assertEquals("content", returned.focusId(), "the untranslated root still focuses the old pointer direction");
		assertEquals(new SpatialController.UserReturnPlacement(0, new SpatialController.Point(0, 0)),
			controller.userReturnPlacement());
		controller.tick(10_000);
		assertEquals(1, controller.snapshot().menus().size(), "the old programmatic fresh-stroke fence remains");
		var release = assertInstanceOf(SpatialController.Release.NoAction.class, controller.release(10_001));
		assertEquals(SpatialController.Release.NoAction.Reason.BRANCH_WITHOUT_ACTION, release.reason());
	}

	@Test
	void rootPruningPreservesAlreadyTranslatedPlacementAndDoesNotReportAnotherUserReturn() {
		var controller = enteredContent(false, 500, 200, 210);
		controller.movePhysical(-70, 0, 220);
		controller.movePhysical(200, 0, 230);
		controller.tick(430);
		assertEquals(2, controller.snapshot().menus().size());
		var before = controller.snapshot();
		var notification = controller.userReturnPlacement();
		controller.replaceRoot(SpatialMenu.of("root",
			SpatialMenu.Choice.leaf("danger", "danger", "ping:danger").withSector(0, 55)), 440);
		assertEquals(1, controller.snapshot().menus().size());
		assertEquals(before.pointer(), controller.snapshot().pointer());
		assertEquals(before.menus().getFirst().origin(), activeOrigin(controller));
		assertEquals(notification, controller.userReturnPlacement());
	}

	@Test
	void zeroDistanceReturnStillNotifiesAndOnlyStartResetsTheCumulativePlacement() {
		var controller = new SpatialController(root(), tuning(200, false, 500));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("external", SpatialMenu.Choice.leaf("x", "x", "a:x")), 100, 20, 10);
		controller.rebase(0, 0, 20);
		long epoch = controller.snapshot().trailEpoch();
		assertTrue(controller.back(30));
		var notification = new SpatialController.UserReturnPlacement(1, new SpatialController.Point(0, 0));
		assertEquals(notification, controller.userReturnPlacement());
		assertTrue(controller.snapshot().trailEpoch() > epoch, "a zero vector is still a navigation discontinuity");
		assertFalse(controller.back(40));
		assertFalse(controller.backProgrammatically(40));
		controller.enterExternal(SpatialMenu.of("external"), 200, 10, 50);
		controller.repositionExternal(new SpatialController.Point(220, 10), new SpatialController.Point(10, 10), 60);
		assertTrue(controller.backProgrammatically(70));
		controller.placeReturn(new SpatialController.Point(10, 0), 80);
		assertEquals(notification, controller.userReturnPlacement(), "non-user operations never produce a notification");
		controller.cancel();
		assertEquals(notification, controller.userReturnPlacement(), "an ended hold's notification remains observable");
		controller.start(90);
		assertEquals(new SpatialController.UserReturnPlacement(0, new SpatialController.Point(0, 0)),
			controller.userReturnPlacement());
		assertEquals(new SpatialController.Point(0, 0), activeOrigin(controller));
	}

	@Test
	void userReturnDiscardsPendingProgrammaticPlacementBeforeAProjectionCanResolveIt() {
		var controller = new SpatialController(root(), tuning(200, false, 500));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("external"), 200, 0, 10);
		controller.constrainPointer(new SpatialController.Viewport(100, 100, 200, 0), 20);
		controller.placeReturn(new SpatialController.Point(236, 0), 30);
		var pointer = controller.snapshot().pointer();
		assertTrue(controller.back(40));
		assertEquals(pointer, activeOrigin(controller));
		var returned = controller.snapshot();
		controller.constrainPointer(new SpatialController.Viewport(1000, 1000, 0, 0), 50);
		assertEquals(returned, controller.snapshot(), "a stale pending target cannot move the new user-return placement");
	}

	@Test
	void userReturnImmediateReleaseAbandonsEvenWhenTheOldRootDirectionWasAnAction() {
		var controller = new SpatialController(halfRoot(), tuning(200, false, 400));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("child"), 120, 0, 10);
		assertTrue(controller.back(20));
		assertEquals(new SpatialController.Point(120, 0), controller.snapshot().pointer());
		assertEquals(controller.snapshot().pointer(), activeOrigin(controller));
		assertInstanceOf(SpatialController.Release.Abandoned.class, controller.release(20),
			"the old root's right-side action cannot leak through an immediate user-return release");
	}

	@Test
	void fractionalNestedReturnCoincidesExactlyEvenWithAZeroDeadzone() {
		var controller = new SpatialController(root(), new SpatialController.Tuning(0, 40, 100, false, 400));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("parent"), 1000.5, -200.25, 10);
		controller.enterExternal(SpatialMenu.of("child"), 0.1, 0.2, 20);
		var pointer = controller.snapshot().pointer();
		assertTrue(controller.back(30));
		assertEquals(pointer, controller.snapshot().pointer());
		assertEquals(pointer, activeOrigin(controller), "rounding must not leave the returned pointer outside a zero deadzone");
		assertNull(controller.snapshot().focusId());
		assertInstanceOf(SpatialController.Release.Abandoned.class, controller.release(40));
	}

	@ParameterizedTest
	@CsvSource({"false", "true"})
	void externalSurfaceUserBackAlignsTheRootWithoutMovingItsFixedPointer(boolean hoverReturn) {
		var controller = new SpatialController(root(), tuning(200, true, 400));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("list"), 120, 30, 10);
		controller.useExternalSurface();
		var pointer = controller.snapshot().pointer();
		if (hoverReturn) {
			assertFalse(controller.updateExternalBack(true, 20));
			assertTrue(controller.updateExternalBack(true, 420));
		} else assertTrue(controller.back(20));
		assertEquals(1, controller.snapshot().menus().size());
		assertEquals(pointer, controller.snapshot().pointer());
		assertEquals(pointer, activeOrigin(controller));
		assertEquals(new SpatialController.UserReturnPlacement(1, pointer), controller.userReturnPlacement());
		assertEquals(List.of(pointer), controller.snapshot().trail());
		assertInstanceOf(SpatialController.Release.Abandoned.class, controller.release(430));
	}

	@Test
	void programmaticItemPopKeepsTheReturnedSurfacePlacementAndExternalBackFocus() {
		var controller = new SpatialController(root(), tuning(200, true, 400));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("list"), 100, 0, 10);
		controller.useExternalSurface();
		controller.alignExternalBack(true);
		controller.enterExternal(SpatialMenu.of("item", SpatialMenu.Choice.leaf("item:leaf", "leaf", "a:leaf")),
			200, 0, 20);
		assertTrue(controller.backProgrammatically(30));
		assertEquals(new SpatialController.Point(200, 0), controller.snapshot().pointer());
		assertEquals(new SpatialController.Point(100, 0), activeOrigin(controller));
		assertEquals("list:back", controller.snapshot().focusId(), "the old programmatic surface focus is not cleared");
		assertEquals(new SpatialController.UserReturnPlacement(0, new SpatialController.Point(0, 0)),
			controller.userReturnPlacement());
	}

	@Test
	void externalRelayoutRestartsPendingHoverWithoutPointerTravelTrailOrImmediatePop() {
		var controller = new SpatialController(root(), tuning(200, true, 400));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("external", SpatialMenu.Choice.leaf("x", "x", "a:x")), 200, 0, 10);
		controller.movePhysical(-50, 0, 20);
		controller.tick(320);
		assertEquals(0.75, controller.snapshot().hoverProgress(), DELTA);
		var before = controller.snapshot();
		var notification = controller.userReturnPlacement();
		controller.repositionExternal(new SpatialController.Point(300, 0), new SpatialController.Point(100, 0), 600);
		var relaid = controller.snapshot();
		assertEquals(2, relaid.menus().size(), "relayout after an old deadline cannot itself pop");
		assertEquals(before.pointer(), relaid.pointer());
		assertEquals(before.trail(), relaid.trail());
		assertEquals(before.trailSequence(), relaid.trailSequence());
		assertEquals(before.trailEpoch(), relaid.trailEpoch());
		assertEquals(before.trailAlignment(), relaid.trailAlignment());
		assertEquals(before.trailAlignmentSequence(), relaid.trailAlignmentSequence());
		assertEquals(new SpatialController.Point(300, 0), activeOrigin(controller));
		assertEquals(new SpatialController.Point(100, 0), relaid.menus().getLast().parentOrigin());
		assertEquals("external:back", relaid.focusId());
		assertEquals(0, relaid.hoverProgress());
		assertEquals(notification, controller.userReturnPlacement());
		controller.tick(620);
		controller.tick(1019);
		assertEquals(2, controller.snapshot().menus().size(), "the new alignment requires a full fresh hover");
		controller.tick(1020);
		assertEquals(1, controller.snapshot().menus().size());
		assertEquals(before.pointer(), activeOrigin(controller));
	}

	@Test
	void externalRelayoutRestartsPendingBackDwellAtTheNewGeometryWhenHoverDisabled() {
		var controller = new SpatialController(root(), tuning(200, false, 400));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("external"), 200, 0, 10);
		moveToBearing(controller, new SpatialController.Point(200, 0), 270.0, 130.0, 20);
		assertEquals("external:back", controller.snapshot().focusId(), "helper precondition: Back is focused");
		var before = controller.snapshot();
		var notification = controller.userReturnPlacement();

		controller.repositionExternal(new SpatialController.Point(260, 0), new SpatialController.Point(60, 0), 210);
		var relaid = controller.snapshot();
		assertEquals("external:back", relaid.focusId(), "the same Back choice stays focused across the relayout");
		assertEquals(2, relaid.menus().size(), "relayout after an old deadline cannot itself pop");
		assertEquals(new SpatialController.Point(260, 0), activeOrigin(controller));
		assertEquals(new SpatialController.Point(60, 0), relaid.menus().getLast().parentOrigin());
		assertEquals(before.pointer(), relaid.pointer(), "relayout moves no pointer input");
		assertEquals(before.trail(), relaid.trail(), "relayout appends no trail sample");
		assertEquals(before.trailSequence(), relaid.trailSequence());
		assertEquals(before.trailEpoch(), relaid.trailEpoch());
		assertEquals(before.trailAlignment(), relaid.trailAlignment());
		assertEquals(notification, controller.userReturnPlacement(), "relayout is not a navigation");

		controller.tick(220);
		assertEquals(2, controller.snapshot().menus().size(), "a same-focus relayout restarts the pending Back dwell");
		controller.tick(409);
		assertEquals(2, controller.snapshot().menus().size(), "the fresh dwell still waits for its full threshold");
		controller.tick(410);
		assertEquals(1, controller.snapshot().menus().size(),
			"preserved physical travel qualifies once the fresh dwell elapses");
		assertEquals(before.pointer(), activeOrigin(controller), "the user return aligns the root at the admitted trigger");
		assertEquals(new SpatialController.UserReturnPlacement(1, before.pointer()),
			controller.userReturnPlacement(), "only the actual return advances the notification");
	}

	@Test
	void noOpExternalRelayoutDoesNotRestartAnEligibleBackDwell() {
		var controller = new SpatialController(root(), tuning(200, false, 400));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("external"), 200, 0, 10);
		moveToBearing(controller, new SpatialController.Point(200, 0), 270.0, 130.0, 20);
		assertEquals("external:back", controller.snapshot().focusId());

		controller.repositionExternal(new SpatialController.Point(200, 0), new SpatialController.Point(0, 0), 210);
		assertEquals("external:back", controller.snapshot().focusId());
		controller.tick(220);
		assertEquals(1, controller.snapshot().menus().size(),
			"an unchanged placement must not postpone the pending dwell past its deadline");
	}

	@Test
	void externalRelayoutReevaluatesFocusButCannotSupplyBranchTravel() {
		var child = SpatialMenu.of("child", SpatialMenu.Choice.leaf("leaf", "leaf", "a:leaf"));
		var controller = new SpatialController(root(), tuning(200, false, 400));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("external", SpatialMenu.Choice.branch("branch", "branch", child)), 200, 0, 10);
		var before = controller.snapshot();
		controller.repositionExternal(new SpatialController.Point(80, 0), new SpatialController.Point(-120, 0), 20);
		assertEquals("branch", controller.snapshot().focusId(), "focus follows geometry without moving the pointer");
		assertEquals(before.pointer(), controller.snapshot().pointer());
		assertEquals(before.trail(), controller.snapshot().trail());
		controller.tick(10_000);
		assertEquals(2, controller.snapshot().menus().size(), "relayout beyond the stroke radius pays no dwell travel");
	}

	@Test
	void externalRelayoutClearsAStaleTurnCornerEvenWhenChoiceFocusIsUnchanged() {
		var child = SpatialMenu.of("child", SpatialMenu.Choice.leaf("leaf", "leaf", "a:leaf"));
		var controller = new SpatialController(root(), tuning(200, false, 400));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("external", SpatialMenu.Choice.branch("branch", "branch", child)), 200, 0, 10);
		controller.movePhysical(120, 0, 20);
		controller.movePhysical(10, 0, 60); // pending corner at (320,0)
		controller.repositionExternal(new SpatialController.Point(330, -120), new SpatialController.Point(330, -320), 70);
		assertEquals("branch", controller.snapshot().focusId());
		controller.movePhysical(5, 0, 80);
		assertEquals(2, controller.snapshot().menus().size(), "the short new segment cannot use the stale corner");
	}

	@Test
	void externalRelayoutCannotPayAReturnsFreshStrokeButPreservesRealPartialTravel() {
		var child = SpatialMenu.of("child", SpatialMenu.Choice.leaf("leaf", "leaf", "a:leaf"));
		var controller = new SpatialController(root(), tuning(200, false, 400));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("external", SpatialMenu.Choice.branch("branch", "branch", child)), 200, 0, 10);
		controller.enterExternal(SpatialMenu.of("item"), 300, 0, 20);
		assertTrue(controller.back(30));
		controller.movePhysical(60, 0, 40);
		var before = controller.snapshot();
		controller.repositionExternal(new SpatialController.Point(230, 0), new SpatialController.Point(0, 0), 50);
		assertEquals(before.pointer(), controller.snapshot().pointer());
		assertEquals(before.trail(), controller.snapshot().trail());
		controller.tick(1000);
		assertEquals(2, controller.snapshot().menus().size(), "geometry alone cannot top up a partial fresh stroke");
		controller.movePhysical(50, 0, 1010);
		controller.tick(1210);
		assertEquals(3, controller.snapshot().menus().size(), "the preserved real partial stroke plus new physical travel admits entry");
	}

	@Test
	void externalRelayoutIsInertForInactiveRootAndOrdinaryRadialMenus() {
		var controller = new SpatialController(root(), tuning(200, false, 400));
		var before = controller.snapshot();
		controller.repositionExternal(new SpatialController.Point(50, 60), new SpatialController.Point(10, 20), 0);
		assertEquals(before, controller.snapshot());
		controller.start(0);
		before = controller.snapshot();
		controller.repositionExternal(new SpatialController.Point(50, 60), new SpatialController.Point(10, 20), 10);
		assertEquals(before, controller.snapshot());
		controller.movePhysical(200, 0, 20);
		controller.tick(220);
		before = controller.snapshot();
		controller.repositionExternal(new SpatialController.Point(50, 60), new SpatialController.Point(10, 20), 230);
		assertEquals(before, controller.snapshot());
	}

	@Test
	void itemHoverReturnAndExternalRelayoutPreserveTheSharedHeldBackBlock() {
		var controller = new SpatialController(root(), new SpatialController.Tuning(10, 40, 100, true, 400));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("list"), 100, 0, 10);
		controller.useExternalSurface();
		controller.alignExternalBack(true);
		controller.enterExternal(SpatialMenu.of("item", SpatialMenu.Choice.leaf("item:leaf", "leaf", "a:leaf")),
			new SpatialController.Point(200, 0), new SpatialController.Point(100, 0), 20);
		controller.movePhysical(-50, 0, 30);
		controller.tick(430);
		assertEquals(2, controller.snapshot().menus().size());
		assertEquals(new SpatialController.Point(150, 0), activeOrigin(controller));
		assertNull(controller.snapshot().focusId(), "item return clears the list's old Back focus");
		assertFalse(controller.updateExternalBack(true, 440));
		controller.tick(840);
		var returned = controller.snapshot();
		var notification = controller.userReturnPlacement();
		controller.repositionExternal(new SpatialController.Point(160, 20), new SpatialController.Point(50, 0), 850);
		assertEquals(returned.pointer(), controller.snapshot().pointer());
		assertEquals(returned.trail(), controller.snapshot().trail());
		controller.tick(1250);
		assertFalse(controller.updateExternalBack(false, 1260, false), "non-input focus loss is not a physical leave");
		controller.repositionExternal(new SpatialController.Point(140, -20), new SpatialController.Point(50, 0), 1270);
		assertFalse(controller.updateExternalBack(true, 1280, false));
		controller.tick(1680);
		assertEquals(2, controller.snapshot().menus().size(), "held Back cannot cascade through relayout or projection focus");
		assertEquals(0, controller.snapshot().hoverProgress());
		assertEquals(notification, controller.userReturnPlacement());
		controller.updateExternalBack(false, 1690, true);
		controller.updateExternalBack(true, 1700, true);
		controller.tick(2099);
		assertEquals(2, controller.snapshot().menus().size());
		controller.tick(2100);
		assertEquals(1, controller.snapshot().menus().size(), "a genuine physical leave/re-entry still returns once");
		assertEquals(new SpatialController.Point(150, 0), activeOrigin(controller));
		assertEquals(new SpatialController.UserReturnPlacement(2, new SpatialController.Point(150, 0)),
			controller.userReturnPlacement());
	}
}
