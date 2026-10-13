package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.SpatialController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production radial planning and frame seam for the ancestor breadcrumb rule:
 * each ancestor of the active path keeps only the entry choice that opened the
 * next child plus Back navigation, hiding every other option frame and sector
 * (including disabled and reserved siblings) through the existing finite exit.
 * Returning restores the parent choices under normal and reduced motion. The
 * controller's logical stack and selection geometry are never mutated.
 */
class SpatialOverlayRendererAncestorBreadcrumbTest {
	private static final long DURATION = 160_000_000L;
	private static final double CENTER_X = 200.0;
	private static final double CENTER_Y = 150.0;
	private static final double ROOT_DISTANCE = 88.0;
	private static final StringSplitter FONT = new StringSplitter((point, style) -> 6.0f);
	private static final SpatialController.Viewport VIEW = new SpatialController.Viewport(400, 300, 0, 0);
	private static final SpatialOverlayRenderer.Style STYLE =
		new SpatialOverlayRenderer.Style(100, 100, 1, 1, ROOT_DISTANCE, 1, true, false);
	private static final SpatialOverlayRenderer.Style REDUCED =
		new SpatialOverlayRenderer.Style(100, 100, 1, 1, ROOT_DISTANCE, 1, true, true);

	private record Draw(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
		SpatialOverlayRenderer.NodeLayout layout) {}

	private static final class Painter implements SpatialOverlayRenderer.FramePainter {
		final List<Draw> draws = new ArrayList<>();
		public int lineHeight() { return 9; }
		public int width(FormattedText text) { return (int) Math.ceil(FONT.stringWidth(text)); }
		public StringSplitter splitter() { return FONT; }
		public boolean paint(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
			SpatialOverlayRenderer.NodeLayout layout, SpatialOverlayRenderer.Style style) {
			draws.add(new Draw(state, layout));
			return true;
		}
		public void connector(SpatialOverlayRenderer.GuideLine line, double alpha) {}
		void clear() { draws.clear(); }
		Draw present(String id) {
			return draws.stream().filter(draw -> draw.state().present() && id.equals(nodeId(draw)))
				.findFirst().orElseThrow();
		}
		Draw exiting(String id) {
			return draws.stream().filter(draw -> !draw.state().present() && id.equals(nodeId(draw)))
				.findFirst().orElseThrow();
		}
		boolean hasPresent(String id) {
			return draws.stream().anyMatch(draw -> draw.state().present() && id.equals(nodeId(draw)));
		}
		boolean hasAny(String id) {
			return draws.stream().anyMatch(draw -> id.equals(nodeId(draw)));
		}
		Draw presentSector(String id) {
			return draws.stream().filter(draw -> draw.state().present() && id.equals(sectorId(draw)))
				.findFirst().orElseThrow();
		}
		boolean hasAnySector(String id) {
			return draws.stream().anyMatch(draw -> id.equals(sectorId(draw)));
		}
		private static String nodeId(Draw draw) {
			return draw.state().data() instanceof SpatialOverlayRenderer.NodePaint node ? node.choice().id() : "";
		}
		private static String sectorId(Draw draw) {
			return draw.state().data() instanceof SpatialOverlayRenderer.SectorPaint sector ? sector.choice().id() : "";
		}
	}

	private static SpatialController.Point p(double x, double y) { return new SpatialController.Point(x, y); }

	private static SpatialController.ChoiceView choice(String id, String action, boolean back, boolean disabled,
		boolean reserved, boolean branch, double start, double span) {
		return new SpatialController.ChoiceView(id, id, action, back, disabled, reserved, branch, false, start, span, null);
	}
	private static SpatialController.ChoiceView leaf(String id) { return choice(id, "action:" + id, false, false, false, false, 0, 180); }
	private static SpatialController.ChoiceView branch(String id) { return choice(id, null, false, false, false, true, 0, 180); }
	private static SpatialController.ChoiceView back(String id) { return choice(id, null, true, false, false, false, 0, 180); }

	private static SpatialController.MenuView menu(String id, String focus, List<SpatialController.ChoiceView> choices,
		String entryChoiceId) {
		return new SpatialController.MenuView(id, p(0, 0), p(0, 0), focus, choices, entryChoiceId);
	}
	private static SpatialController.Snapshot snapshot(List<SpatialController.MenuView> menus, SpatialController.Point pointer) {
		return new SpatialController.Snapshot(true, pointer, List.of(p(0, 0), pointer), menus.getLast().focusId(), menus, 0, 12);
	}
	private static void frame(SpatialOverlayRenderer.Session session, SpatialController.Snapshot radial, Painter painter,
		SpatialOverlayRenderer.Style style, long time) {
		painter.clear();
		session.drawFrame(radial, null, choice -> Component.literal(choice.label()), null, style, time, VIEW, 400, 300, painter);
	}

	private static SpatialController.Snapshot nestedSnapshot() {
		return snapshot(List.of(
			menu("root", "content", List.of(
				choice("content", null, false, false, false, true, 90, 55),
				choice("danger", "action:danger", false, false, false, false, 0, 55),
				choice("reserved-ne", null, false, false, true, false, 45, 35)), null),
			menu("content", "child", List.of(
				choice("child", null, false, false, false, true, 0, 120),
				choice("other", "action:other", false, false, false, false, 120, 120),
				choice("disabled", "action:disabled", false, true, false, false, 180, 60),
				choice("content:back", null, true, false, false, false, 240, 120)), "content"),
			menu("child", "child:leaf", List.of(
				choice("child:leaf", "action:leaf", false, false, false, false, 0, 180),
				choice("child:other", "action:other", false, false, false, false, 180, 180)), "child")),
			p(0, 0));
	}

	private static SpatialController.Snapshot contentSnapshot() {
		return snapshot(List.of(
			menu("root", null, List.of(branch("content")), null),
			menu("content", null, List.of(branch("child"), leaf("other"), back("content:back")), "content")),
			p(0, 0));
	}

	private static SpatialController.Snapshot childSnapshot() {
		return snapshot(List.of(
			menu("root", null, List.of(branch("content")), null),
			menu("content", null, List.of(branch("child"), leaf("other"), back("content:back")), "content"),
			menu("child", null, List.of(leaf("child:leaf")), "child")),
			p(0, 0));
	}

	/** The realistic ancestor stack with its parent menu active again after the child return. */
	private static SpatialController.Snapshot parentSnapshot() {
		var menus = nestedSnapshot().menus();
		return snapshot(List.of(menus.get(0), menus.get(1)), p(0, 0));
	}

	private static List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>>
		targets(SpatialController.Snapshot snapshot) {
		var targets = new ArrayList<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>>();
		SpatialOverlayRenderer.addRadial(targets, snapshot, choice -> Component.literal(choice.label()), null,
			CENTER_X, CENTER_Y, SpatialOverlayRenderer.orbitFor(240.0), ROOT_DISTANCE, 1.0);
		return targets;
	}

	private static List<String> nodeIds(
		List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>> targets,
		String owner) {
		return targets.stream().filter(target -> target.data() instanceof SpatialOverlayRenderer.NodePaint
			&& target.key().owner().equals(owner))
			.map(target -> ((SpatialOverlayRenderer.NodePaint) target.data()).choice().id()).toList();
	}

	private static List<String> sectorIds(
		List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>> targets,
		String owner) {
		return targets.stream().filter(target -> target.data() instanceof SpatialOverlayRenderer.SectorPaint
			&& target.key().owner().equals(owner))
			.map(target -> ((SpatialOverlayRenderer.SectorPaint) target.data()).choice().id()).toList();
	}

	private static SpatialOverlayRenderer.GuidePaint guide(
		List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>> targets,
		String owner) {
		return targets.stream().filter(target -> target.data() instanceof SpatialOverlayRenderer.GuidePaint
			&& target.key().owner().equals(owner))
			.map(target -> (SpatialOverlayRenderer.GuidePaint) target.data()).findFirst().orElseThrow();
	}

	@Test
	void ancestorLevelsPlanOnlyTheEntryBreadcrumbBackFramesAndSectors() {
		var snapshot = nestedSnapshot();
		var targets = targets(snapshot);

		assertEquals(List.of("content"), nodeIds(targets, "root"));
		assertEquals(List.of("content"), sectorIds(targets, "root"));
		assertEquals(List.of("child", "content:back"), nodeIds(targets, "content"));
		assertEquals(List.of("child", "content:back"), sectorIds(targets, "content"));
		assertFalse(nodeIds(targets, "content").contains("disabled"),
			"the disabled sibling frame is hidden from the settled ancestor plan");
		assertFalse(sectorIds(targets, "content").contains("disabled"),
			"the disabled sibling sector is hidden from the settled ancestor plan");
		assertEquals(List.of("child:leaf", "child:other"), nodeIds(targets, "child"));
		assertEquals(List.of("child:leaf", "child:other"), sectorIds(targets, "child"));

		var rootGuide = guide(targets, "root");
		assertTrue(rootGuide.boundaries().isEmpty(), "a lone breadcrumb contributes no hidden divider");
		assertEquals(SpatialOverlayRenderer.GUIDE_CIRCLE_SAMPLES, rootGuide.circle().size(),
			"the defined centre guide stays");
		var contentGuide = guide(targets, "content");
		assertEquals(1, contentGuide.boundaries().size(), "only the visible shared edge is drawn");
		assertEquals(0.0, contentGuide.boundaries().getFirst().x1(), 1.0e-9);

		// Presentation filtering never mutates the logical navigation stack.
		assertEquals(3, snapshot.menus().get(0).choices().size());
		assertEquals(4, snapshot.menus().get(1).choices().size());
		assertEquals(2, snapshot.menus().get(2).choices().size());
	}

	@Test
	void enteringAndReturningFadesHiddenSiblingsAndRestoresThemUnderBothMotionModes() {
		var session = new SpatialOverlayRenderer.Session();
		var painter = new Painter();
		var contentActive = contentSnapshot();
		var childActive = childSnapshot();

		frame(session, contentActive, painter, STYLE, 0);
		frame(session, contentActive, painter, STYLE, DURATION);
		assertEquals(SpatialOverlayRenderer.nodeAlpha(true, false, false), painter.present("other").state().alpha(), 1.0e-9);

		frame(session, childActive, painter, STYLE, DURATION + 1);
		assertTrue(painter.exiting("other").state().alpha() > 0.0, "the hidden sibling fades through the ordinary exit");
		assertTrue(painter.hasPresent("child"), "the content breadcrumb stays");
		assertTrue(painter.hasPresent("content:back"), "the ancestor Back stays");
		assertTrue(painter.hasPresent("content"), "the root breadcrumb stays");

		frame(session, childActive, painter, STYLE, DURATION * 2 + 1);
		assertFalse(painter.hasAny("other"), "the exit tail is bounded");
		assertTrue(session.paintedChoiceIds().contains("child:leaf"));
		assertFalse(session.paintedChoiceIds().contains("other"), "a hidden ancestor never acknowledges");
		assertFalse(session.paintedChoiceIds().contains("child"), "a breadcrumb is never an active acknowledgement");

		frame(session, contentActive, painter, STYLE, DURATION * 2 + 2);
		assertFalse(painter.hasPresent("other"), "the restored sibling starts from its transparent appearance");
		frame(session, contentActive, painter, STYLE, DURATION * 2 + 2 + DURATION / 2);
		double restored = painter.present("other").state().alpha();
		assertTrue(restored > 0.0 && restored < SpatialOverlayRenderer.nodeAlpha(true, false, false),
			"the return fades the parent choices back in");
		frame(session, contentActive, painter, STYLE, DURATION * 3 + 2);
		assertEquals(SpatialOverlayRenderer.nodeAlpha(true, false, false), painter.present("other").state().alpha(), 1.0e-9);

		var reduced = new SpatialOverlayRenderer.Session();
		var reducedPainter = new Painter();
		frame(reduced, contentActive, reducedPainter, REDUCED, 0);
		frame(reduced, childActive, reducedPainter, REDUCED, 1);
		assertFalse(reducedPainter.hasAny("other"), "reduced motion drops the exit tail immediately");
		frame(reduced, contentActive, reducedPainter, REDUCED, 2);
		assertEquals(SpatialOverlayRenderer.nodeAlpha(true, false, false), reducedPainter.present("other").state().alpha(), 1.0e-9);
	}

	@Test
	void disabledAncestorSiblingSettlesHiddenAndReturnsWithItsDisabledStyling() {
		var session = new SpatialOverlayRenderer.Session();
		var painter = new Painter();
		var parentActive = parentSnapshot();
		var childActive = nestedSnapshot();
		double disabledAlpha = SpatialOverlayRenderer.nodeAlpha(true, false, true);

		frame(session, parentActive, painter, STYLE, 0);
		frame(session, parentActive, painter, STYLE, DURATION);
		assertEquals(disabledAlpha, painter.present("disabled").state().alpha(), 1.0e-9,
			"the active parent paints the disabled frame with its disabled styling");
		assertEquals(disabledAlpha, painter.presentSector("disabled").state().alpha(), 1.0e-9,
			"the active parent paints the disabled sector with its disabled styling");

		frame(session, childActive, painter, STYLE, DURATION + 1);
		assertTrue(painter.exiting("disabled").state().alpha() > 0.0,
			"the hidden disabled frame fades through the ordinary exit");
		frame(session, childActive, painter, STYLE, DURATION * 2 + 1);
		assertFalse(painter.hasAny("disabled"), "the disabled ancestor frame settles hidden");
		assertFalse(painter.hasAnySector("disabled"), "the disabled ancestor sector settles hidden");
		assertTrue(painter.hasPresent("child"), "the content breadcrumb stays");
		assertTrue(painter.hasPresent("content:back"), "the ancestor Back stays");

		frame(session, parentActive, painter, STYLE, DURATION * 2 + 2);
		assertFalse(painter.hasPresent("disabled"), "the restored disabled sibling starts transparent");
		frame(session, parentActive, painter, STYLE, DURATION * 2 + 2 + DURATION / 2);
		double restored = painter.present("disabled").state().alpha();
		assertTrue(restored > 0.0 && restored < disabledAlpha, "the return fades the disabled sibling back in");
		frame(session, parentActive, painter, STYLE, DURATION * 3 + 2);
		assertEquals(disabledAlpha, painter.present("disabled").state().alpha(), 1.0e-9,
			"the returned disabled frame keeps its disabled styling");
		assertEquals(disabledAlpha, painter.presentSector("disabled").state().alpha(), 1.0e-9,
			"the returned disabled sector keeps its disabled styling");

		var reduced = new SpatialOverlayRenderer.Session();
		var reducedPainter = new Painter();
		frame(reduced, parentActive, reducedPainter, REDUCED, 0);
		assertEquals(disabledAlpha, reducedPainter.present("disabled").state().alpha(), 1.0e-9);
		assertEquals(disabledAlpha, reducedPainter.presentSector("disabled").state().alpha(), 1.0e-9);
		frame(reduced, childActive, reducedPainter, REDUCED, 1);
		assertFalse(reducedPainter.hasAny("disabled"), "reduced motion drops the disabled exit tail immediately");
		assertFalse(reducedPainter.hasAnySector("disabled"), "reduced motion drops the disabled sector immediately");
		frame(reduced, parentActive, reducedPainter, REDUCED, 2);
		assertEquals(disabledAlpha, reducedPainter.present("disabled").state().alpha(), 1.0e-9);
		assertEquals(disabledAlpha, reducedPainter.presentSector("disabled").state().alpha(), 1.0e-9);
	}
}
