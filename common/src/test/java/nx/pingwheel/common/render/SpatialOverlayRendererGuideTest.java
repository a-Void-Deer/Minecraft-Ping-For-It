package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialMenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless oracles for the radial centre guides: one divider per true shared
 * sector boundary, the closed deadzone circle, their transition-chrome alpha,
 * paint order and controller-driven placement under the rigid view translation.
 * Every endpoint is checked against geometry computed independently in the
 * test. No GuiGraphics, GPU submission or manual rendering evidence is claimed.
 */
class SpatialOverlayRendererGuideTest {

	private static final double EPSILON = 1.0e-9;
	private static final double CENTER_X = 200.0;
	private static final double CENTER_Y = 150.0;
	private static final double ROOT_DISTANCE = 88.0;

	@Test
	void unequalRootSectorsProduceOneBoundaryPerTrueSharedEdge() {
		List<SpatialController.ChoiceView> root = fixedRootChoices();
		double inner = 12.0;
		double outer = 100.0;
		List<SpatialOverlayRenderer.GuideLine> boundaries =
			SpatialOverlayRenderer.menuBoundaries(root, inner, outer);

		assertEquals(8, boundaries.size(), "one line per adjacent pair, including the wrap pair");
		for (int i = 0; i < root.size(); i++) {
			assertIndependentEndpoints(boundaries.get(i), root.get(i).startDegrees(), inner, outer);
		}
		for (int a = 0; a < boundaries.size(); a++) {
			for (int b = a + 1; b < boundaries.size(); b++) {
				assertFalse(overlaps(boundaries.get(a), boundaries.get(b)),
					"each shared bearing is emitted exactly once");
			}
		}
	}

	@Test
	void equalSectorMenusIncludingBackDrawBothWrapBoundariesOnce() {
		List<SpatialController.ChoiceView> menu = List.of(
			choice("a", 0.0, 90.0),
			choice("back", 90.0, 90.0, true),
			choice("c", 180.0, 90.0),
			choice("d", 270.0, 90.0));
		List<SpatialOverlayRenderer.GuideLine> boundaries =
			SpatialOverlayRenderer.menuBoundaries(menu, 18.0, 67.0);

		assertEquals(4, boundaries.size());
		double[] bearings = {0.0, 90.0, 180.0, 270.0};
		for (int i = 0; i < bearings.length; i++) {
			assertIndependentEndpoints(boundaries.get(i), bearings[i], 18.0, 67.0);
		}
	}

	@Test
	void wrappedSharedBoundariesCompareNormalizedBearings() {
		// Left ends exactly at 60 (past 0), right ends exactly at 300 (past 0).
		List<SpatialController.ChoiceView> menu = List.of(
			choice("left", 300.0, 120.0),
			choice("right", 60.0, 240.0));
		List<SpatialOverlayRenderer.GuideLine> boundaries =
			SpatialOverlayRenderer.menuBoundaries(menu, 18.0, 67.0);

		assertEquals(2, boundaries.size());
		assertIndependentEndpoints(boundaries.get(0), 300.0, 18.0, 67.0);
		assertIndependentEndpoints(boundaries.get(1), 60.0, 18.0, 67.0);
	}

	@Test
	void singleFullDiscAndGappedNeighboursNeverGainPhantomDividers() {
		assertTrue(SpatialOverlayRenderer.menuBoundaries(
			List.of(choice("only", 0.0, 360.0)), 18.0, 100.0).isEmpty(),
			"a single full disc has no adjacent pair");
		assertTrue(SpatialOverlayRenderer.menuBoundaries(
			List.of(choice("a", 0.0, 90.0), choice("b", 120.0, 60.0)), 18.0, 100.0).isEmpty(),
			"a gap between neighbours is not a shared boundary");
	}

	@Test
	void deadzoneCircleIsAClosedClockwisePolylineAtTheExactRadius() {
		double radius = 32.0;
		List<SpatialOverlayRenderer.GuideLine> circle = SpatialOverlayRenderer.circleSegments(radius);

		assertEquals(SpatialOverlayRenderer.GUIDE_CIRCLE_SAMPLES, circle.size());
		SpatialOverlayRenderer.GuideLine first = circle.getFirst();
		assertEquals(0.0, first.x1(), EPSILON, "the circle starts straight up");
		assertEquals(-radius, first.y1(), EPSILON);
		assertTrue(first.x2() > 0.0, "the polyline walks clockwise");
		double step = Math.PI * 2.0 / SpatialOverlayRenderer.GUIDE_CIRCLE_SAMPLES;
		assertEquals(Math.sin(step) * radius, first.x2(), EPSILON);
		assertEquals(-Math.cos(step) * radius, first.y2(), EPSILON);
		SpatialOverlayRenderer.GuideLine last = circle.getLast();
		assertEquals(first.x1(), last.x2(), EPSILON, "the polyline closes exactly on its first vertex");
		assertEquals(first.y1(), last.y2(), EPSILON);
		for (int i = 0; i + 1 < circle.size(); i++) {
			assertEquals(circle.get(i).x2(), circle.get(i + 1).x1(), EPSILON);
			assertEquals(circle.get(i).y2(), circle.get(i + 1).y1(), EPSILON);
		}
		for (SpatialOverlayRenderer.GuideLine segment : circle) {
			assertEquals(radius * radius, segment.x1() * segment.x1() + segment.y1() * segment.y1(), 1.0e-6);
			assertEquals(radius * radius, segment.x2() * segment.x2() + segment.y2() * segment.y2(), 1.0e-6);
		}
		assertTrue(SpatialOverlayRenderer.circleSegments(0.0).isEmpty());
		assertTrue(SpatialOverlayRenderer.circleSegments(Double.NaN).isEmpty());
	}

	@Test
	void nondefaultDeadzoneRadiiReachTheGuideGeometryThroughTheControllerReadModel() {
		for (double deadzone : new double[] {12.0, 22.0, 32.0}) {
			SpatialController controller = controller(deadzone);
			SpatialController.Snapshot snapshot = controller.snapshot();
			assertEquals(deadzone, snapshot.deadzone(), EPSILON,
				"the read model exposes the controller's own frozen tuning");

			var root = guide(targets(snapshot), "root");
			assertNotNull(root, "the active radial menu paints its centre guides");
			SpatialOverlayRenderer.GuidePaint paint = (SpatialOverlayRenderer.GuidePaint) root.data();
			double outer = SpatialOverlayRenderer.sectorRadius(
				SpatialOverlayRenderer.nodeRadius(0, SpatialOverlayRenderer.orbitFor(240.0), ROOT_DISTANCE, 1.0, false));
			assertEquals(8, paint.boundaries().size());
			for (int i = 0; i < 8; i++) {
				double start = snapshot.menus().getFirst().choices().get(i).startDegrees();
				assertIndependentEndpoints(paint.boundaries().get(i), start, deadzone, outer);
			}
			assertEquals(SpatialOverlayRenderer.GUIDE_CIRCLE_SAMPLES, paint.circle().size());
			for (SpatialOverlayRenderer.GuideLine segment : paint.circle()) {
				assertEquals(deadzone * deadzone,
					segment.x1() * segment.x1() + segment.y1() * segment.y1(), 1.0e-6);
			}
		}
	}

	@Test
	void ancestorGuidesDimWithTheirMenuAndChildGuidesSitOnTheChildOrigin() {
		SpatialMenu child = SpatialMenu.of("child",
			SpatialMenu.Choice.leaf("a", "a", "a"),
			SpatialMenu.Choice.leaf("b", "b", "b"),
			SpatialMenu.Choice.leaf("c", "c", "c"));
		SpatialMenu rootMenu = SpatialMenu.of("root",
			SpatialMenu.Choice.branch("content", "Content", child).withSector(90.0, 55.0));
		SpatialController controller = new SpatialController(rootMenu,
			new SpatialController.Tuning(22.0, 50.0, 180L, false, 500L));
		controller.start(0L);
		controller.movePhysical(120.0, 0.0, 10L);
		assertEquals("content", controller.snapshot().focusId(), "precondition: the breadcrumb entry is focused");
		controller.tick(200L);
		SpatialController.Snapshot snapshot = controller.snapshot();
		assertEquals(2, snapshot.menus().size());
		assertEquals(new SpatialController.Point(120.0, 0.0), snapshot.menus().getLast().origin());
		assertEquals("content", snapshot.menus().getLast().entryChoiceId(),
			"the child records the radial choice that opened it");

		var targets = targets(snapshot);
		var root = guide(targets, "root");
		var childGuide = guide(targets, "child");
		assertNotNull(root);
		assertNotNull(childGuide);
		assertEquals(SpatialOverlayRenderer.ANCESTOR_ALPHA, root.alpha(), EPSILON);
		assertEquals(SpatialOverlayRenderer.nodeAlpha(true, false, false), childGuide.alpha(), EPSILON);
		assertEquals(CENTER_X, root.x(), EPSILON);
		assertEquals(CENTER_Y, root.y(), EPSILON);
		assertEquals(CENTER_X + 120.0, childGuide.x(), EPSILON);
		assertEquals(CENTER_Y, childGuide.y(), EPSILON);

		// Three leaves plus the automatically appended Back: four equal sectors
		// whose every shared edge, including Back's, is drawn exactly once.
		SpatialOverlayRenderer.GuidePaint childPaint = (SpatialOverlayRenderer.GuidePaint) childGuide.data();
		assertEquals(4, childPaint.boundaries().size());
		for (int i = 0; i < 4; i++) {
			double start = snapshot.menus().getLast().choices().get(i).startDegrees();
			assertIndependentEndpoints(childPaint.boundaries().get(i), start, 22.0, 44.0);
		}
	}

	@Test
	void guideCircleFollowsTheMenuOriginAndNotTheGuiCenterDuringPan() {
		SpatialController controller = controller(22.0);
		SpatialController.Snapshot rootSnapshot = controller.snapshot();
		SpatialMenu child = SpatialMenu.of("child",
			SpatialMenu.Choice.leaf("a", "a", "a"),
			SpatialMenu.Choice.leaf("b", "b", "b"));
		controller.enterExternal(child, 120.0, 0.0, 1L);
		SpatialController.Snapshot childSnapshot = controller.snapshot();
		var childGuide = guide(targets(childSnapshot), "child");
		assertNotNull(childGuide);
		assertEquals(CENTER_X + 120.0, childGuide.x(), EPSILON);

		SpatialOverlayRenderer.Session paint = new SpatialOverlayRenderer.Session();
		SpatialOverlayRenderer.Style style = SpatialOverlayRenderer.Style.NATIVE;
		paint.prepareViewport(rootSnapshot, 400, 300, style, 0L);
		paint.prepareViewport(childSnapshot, 400, 300, style, 1L);
		SpatialController.Viewport mid = paint.prepareViewport(childSnapshot, 400, 300, style, 80_000_001L);
		assertTrue(mid.viewX() < 0.0 && mid.viewX() > -120.0, "the pan is mid-interval, not snapped");
		assertEquals(CENTER_X + 120.0 + mid.viewX(), childGuide.x() + mid.viewX(), EPSILON);
		assertNotEquals(CENTER_X, childGuide.x() + mid.viewX(), 1.0,
			"the painted circle is not stuck on the view-screen center during pan");

		SpatialController.Viewport settled = paint.prepareViewport(childSnapshot, 400, 300, style, 160_000_001L);
		assertEquals(CENTER_X, childGuide.x() + settled.viewX(), EPSILON,
			"the settled translation projects the menu origin onto the GUI center");
		assertEquals(CENTER_Y, childGuide.y() + settled.viewY(), EPSILON);
	}

	@Test
	void centreGuidesFollowTheTransitionFadeAndIgnoreBothOpacityPreferences() {
		var guide = new SpatialOverlayRenderer.GuidePaint(
			List.of(new SpatialOverlayRenderer.GuideLine(0.0, -18.0, 0.0, -67.0)),
			SpatialOverlayRenderer.circleSegments(18.0));
		var zeroOpacity = new SpatialOverlayRenderer.Style(0, 0, 1.0, 1.0, true, false);
		var splitOpacity = new SpatialOverlayRenderer.Style(20, 80, 1.0, 1.0, true, false);
		var choice = choice("back", 0.0, 90.0, true);
		var sector = new SpatialOverlayRenderer.SectorPaint(choice, 67.0);
		var node = new SpatialOverlayRenderer.NodePaint(choice, Component.literal("Back"), true, 0.0);

		assertEquals(0.5, SpatialOverlayRenderer.layerAlpha(guide, 0.5, zeroOpacity), EPSILON,
			"neither opacity preference reaches the guides");
		assertEquals(0.2, SpatialOverlayRenderer.layerAlpha(sector, 1.0, splitOpacity), EPSILON);
		assertEquals(0.8, SpatialOverlayRenderer.layerAlpha(node, 1.0, splitOpacity), EPSILON);
		assertEquals(1.0, SpatialOverlayRenderer.layerAlpha(guide, 1.0, splitOpacity), EPSILON);
		assertTrue(SpatialOverlayRenderer.renders(guide, 0.4, zeroOpacity));
		assertFalse(SpatialOverlayRenderer.renders(guide, 0.0, splitOpacity),
			"the guides still fade with their own transition");

		assertTrue(SpatialOverlayRenderer.paintRank(guide) > SpatialOverlayRenderer.paintRank(sector));
		assertTrue(SpatialOverlayRenderer.paintRank(guide) < SpatialOverlayRenderer.paintRank(
			new SpatialOverlayRenderer.PanelPaint(10.0, 10.0, 3.0)));
	}

	@Test
	void guideShapesDoNotDependOnFocusSelectionOrRelease() {
		SpatialController controller = controller(22.0);
		var unfocused = guide(targets(controller.snapshot()), "root");
		controller.rebase(0.0, -40.0, 2L);
		SpatialController.Snapshot focusedSnapshot = controller.snapshot();
		assertEquals("danger", focusedSnapshot.focusId(), "precondition: a sector is focused");
		var focused = guide(targets(focusedSnapshot), "root");

		assertNotNull(unfocused);
		assertNotNull(focused);
		assertEquals(unfocused.data(), focused.data(), "focus, selection and release never reshape the guides");
	}

	@Test
	void guidePaintFadesWithTheSharedTransitionModelAndSnapsUnderReducedMotion() {
		var guide = new SpatialOverlayRenderer.GuidePaint(List.of(),
			SpatialOverlayRenderer.circleSegments(18.0));
		var key = new SpatialOverlayRenderer.VisualKey("guide", "root", "");
		var target = new SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>(
			key, CENTER_X, CENTER_Y, 1.0, 0.8, CENTER_X, CENTER_Y, guide);

		var transitions = new SpatialOverlayTransitions<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>(
			160_000_000L, 8, 0.92);
		var appearing = transitions.update(List.of(target), 0L, false);
		assertEquals(0.0, appearing.getFirst().alpha(), EPSILON, "appearance starts fully faded");
		var settled = transitions.update(List.of(target), 160_000_000L, false);
		assertEquals(0.8, settled.getFirst().alpha(), EPSILON);
		transitions.update(List.of(), 240_000_000L, false);
		var fading = transitions.update(List.of(), 320_000_000L, false);
		assertFalse(fading.getFirst().present());
		assertEquals(0.4, fading.getFirst().alpha(), EPSILON, "the exit tail fades the guides out");
		transitions.update(List.of(), 400_000_000L, false);
		assertTrue(transitions.isEmpty(), "the bounded exit interval then releases the guides");

		var reduced = new SpatialOverlayTransitions<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>(
			160_000_000L, 8, 0.92);
		var shown = reduced.update(List.of(target), 0L, true);
		assertEquals(0.8, shown.getFirst().alpha(), EPSILON, "reduced motion snaps appearance");
		reduced.update(List.of(), 1L, true);
		assertTrue(reduced.isEmpty(), "reduced motion drops the exit tail immediately");
	}

	private static SpatialController.ChoiceView choice(String id, double start, double span) {
		return choice(id, start, span, false);
	}

	private static SpatialController.ChoiceView choice(String id, double start, double span, boolean back) {
		return new SpatialController.ChoiceView(id, id, null, back, false, false, false, false,
			start, span, null);
	}

	private static List<SpatialController.ChoiceView> fixedRootChoices() {
		return List.of(
			choice("danger", 332.5, 55.0),
			choice("reserved-ne", 27.5, 35.0),
			choice("content", 62.5, 55.0),
			choice("reserved-se", 117.5, 35.0),
			choice("cancel", 152.5, 55.0),
			choice("precise", 207.5, 35.0),
			choice("intent", 242.5, 55.0),
			choice("settings", 297.5, 35.0));
	}

	private static void assertIndependentEndpoints(SpatialOverlayRenderer.GuideLine line, double bearing,
		double inner, double outer) {
		double radians = Math.toRadians(bearing);
		assertEquals(Math.sin(radians) * inner, line.x1(), EPSILON);
		assertEquals(-Math.cos(radians) * inner, line.y1(), EPSILON);
		assertEquals(Math.sin(radians) * outer, line.x2(), EPSILON);
		assertEquals(-Math.cos(radians) * outer, line.y2(), EPSILON);
	}

	private static boolean overlaps(SpatialOverlayRenderer.GuideLine first, SpatialOverlayRenderer.GuideLine second) {
		return Math.abs(first.x2() - second.x2()) < 1.0e-6 && Math.abs(first.y2() - second.y2()) < 1.0e-6;
	}

	private static SpatialController controller(double deadzone) {
		SpatialMenu root = SpatialMenu.of("root",
			SpatialMenu.Choice.leaf("danger", "Danger", "ping:danger").withSector(0.0, 55.0),
			SpatialMenu.Choice.reserved("reserved-ne", "Reserved").withSector(45.0, 35.0),
			SpatialMenu.Choice.leaf("content", "Content", "content").withSector(90.0, 55.0),
			SpatialMenu.Choice.reserved("reserved-se", "Reserved").withSector(135.0, 35.0),
			SpatialMenu.Choice.leaf("cancel", "Cancel", "cancel").withSector(180.0, 55.0),
			SpatialMenu.Choice.leaf("precise", "Precise", "precise").withSector(225.0, 35.0),
			SpatialMenu.Choice.leaf("intent", "Intent", "intent").withSector(270.0, 55.0),
			SpatialMenu.Choice.leaf("settings", "Settings", "settings").withSector(315.0, 35.0));
		SpatialController controller = new SpatialController(root,
			new SpatialController.Tuning(deadzone, 50.0, 180L, false, 500L));
		controller.start(0L);
		return controller;
	}

	private static List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>>
		targets(SpatialController.Snapshot snapshot) {
		var targets = new ArrayList<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>>();
		SpatialOverlayRenderer.addRadial(targets, snapshot, choice -> Component.literal(choice.id()), null,
			CENTER_X, CENTER_Y, SpatialOverlayRenderer.orbitFor(240.0), ROOT_DISTANCE, 1.0);
		return targets;
	}

	private static SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>
		guide(List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>> targets,
			String menuId) {
		return targets.stream()
			.filter(target -> target.data() instanceof SpatialOverlayRenderer.GuidePaint
				&& target.key().owner().equals(menuId))
			.findFirst().orElse(null);
	}
}
