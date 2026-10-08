package nx.pingwheel.common.client.spatial;

import java.util.List;

import org.junit.jupiter.api.Test;

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
		assertEquals("content", controller.snapshot().focusId(), "root focus matches the pointer direction after pop");
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

		SpatialController.ChoiceView leaf = externalView.choices().get(0);
		moveToBearing(controller, externalView.origin(), leaf.startDegrees() + leaf.spanDegrees() / 2.0, 120.0, 30L);
		assertEquals("external:up", controller.snapshot().focusId());
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
}
