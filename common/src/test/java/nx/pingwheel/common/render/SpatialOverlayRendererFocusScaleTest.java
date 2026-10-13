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
 * Production frame seam over a recording painter: an eligible focused action or
 * branch selects the confirmed 1.2/0.85 selection scaling through the existing
 * finite transition, while Back, disabled, reserved and deadzone focus keep the
 * established 1.12/0.95 resting scales. Inventory list rows and the logical
 * controller choices never change with this presentation mode.
 */
class SpatialOverlayRendererFocusScaleTest {
	private static final long DURATION = 160_000_000L;
	private static final StringSplitter FONT = new StringSplitter((point, style) -> 6.0f);
	private static final SpatialController.Viewport VIEW = new SpatialController.Viewport(400, 300, 0, 0);
	private static final SpatialOverlayRenderer.Style STYLE =
		new SpatialOverlayRenderer.Style(100, 100, 1, 1, 88, 1, true, false);
	private static final SpatialOverlayRenderer.Style REDUCED =
		new SpatialOverlayRenderer.Style(100, 100, 1, 1, 88, 1, true, true);

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
		Draw follower(String id) {
			return draws.stream().filter(draw -> draw.state().present() && id.equals(nodeId(draw))
				&& node(draw).follower() != null).findFirst().orElseThrow();
		}
		Draw row(String key) {
			return draws.stream().filter(draw -> draw.state().present()
				&& draw.state().data() instanceof SpatialOverlayRenderer.RowPaint row && row.row().key().equals(key))
				.findFirst().orElseThrow();
		}
		boolean hasPresent(String id) {
			return draws.stream().anyMatch(draw -> draw.state().present() && id.equals(nodeId(draw)));
		}
		boolean hasFollower() {
			return draws.stream().anyMatch(draw -> draw.state().present()
				&& draw.state().data() instanceof SpatialOverlayRenderer.NodePaint node && node.follower() != null);
		}
		private static String nodeId(Draw draw) {
			return draw.state().data() instanceof SpatialOverlayRenderer.NodePaint node ? node.choice().id() : "";
		}
		private static SpatialOverlayRenderer.NodePaint node(Draw draw) {
			return (SpatialOverlayRenderer.NodePaint) draw.state().data();
		}
	}

	private static SpatialController.Point p(double x, double y) { return new SpatialController.Point(x, y); }

	private static SpatialController.ChoiceView choice(String id, String action, boolean back, boolean disabled,
		boolean reserved, boolean branch) {
		return new SpatialController.ChoiceView(id, id, action, back, disabled, reserved, branch, false, 0, 180, null);
	}
	private static SpatialController.ChoiceView leaf(String id) { return choice(id, "action:" + id, false, false, false, false); }
	private static SpatialController.ChoiceView branch(String id) { return choice(id, null, false, false, false, true); }
	private static SpatialController.ChoiceView back(String id) { return choice(id, null, true, false, false, false); }
	private static SpatialController.ChoiceView disabled(String id) { return choice(id, "action:" + id, false, true, false, false); }
	private static SpatialController.ChoiceView reserved(String id) { return choice(id, "action:" + id, false, false, true, false); }

	private static SpatialController.MenuView menu(String id, SpatialController.Point origin, SpatialController.Point parentOrigin,
		String focus, List<SpatialController.ChoiceView> choices, String entryChoiceId) {
		return new SpatialController.MenuView(id, origin, parentOrigin, focus, choices, entryChoiceId);
	}
	private static SpatialController.Snapshot snapshot(List<SpatialController.MenuView> menus, SpatialController.Point pointer) {
		return new SpatialController.Snapshot(true, pointer, List.of(p(0, 0), pointer), menus.getLast().focusId(), menus, 0, 12);
	}
	private static void frame(SpatialOverlayRenderer.Session session, SpatialController.Snapshot radial, Painter painter,
		SpatialOverlayRenderer.Style style, long time, SpatialController.Viewport viewport) {
		painter.clear();
		session.drawFrame(radial, null, choice -> Component.literal(choice.label()), null, style, time, viewport, 400, 300, painter);
	}

	@Test
	void eligibleActionFocusEasesThroughTheExistingIntervalThenSettlesAtTheConfirmedScales() {
		var choices = List.of(leaf("leaf"), leaf("other"), back("back"), disabled("disabled"), reserved("reserved"));
		var resting = snapshot(List.of(menu("root", p(0, 0), p(0, 0), null, choices, null)), p(0, 0));
		var focused = snapshot(List.of(menu("root", p(0, 0), p(0, 0), "leaf", choices, null)), p(90, 0));
		var session = new SpatialOverlayRenderer.Session();
		var painter = new Painter();

		frame(session, resting, painter, STYLE, 0, VIEW);
		frame(session, resting, painter, STYLE, DURATION, VIEW);
		assertEquals(SpatialOverlayRenderer.NODE_SCALE, painter.present("leaf").state().scale(), 1.0e-9,
			"deadzone focus keeps the resting scale");

		frame(session, focused, painter, STYLE, DURATION + 1, VIEW);
		assertEquals(SpatialOverlayRenderer.NODE_SCALE, painter.present("other").state().scale(), 1.0e-9,
			"the retarget starts from the displayed resting pose");
		assertFalse(painter.hasFollower(), "the new follower is still fully transparent on its first frame");

		frame(session, focused, painter, STYLE, DURATION + 1 + DURATION / 2, VIEW);
		double sibling = painter.present("other").state().scale();
		assertTrue(sibling > SpatialOverlayRenderer.NODE_FOCUSED_SIBLING_SCALE && sibling < SpatialOverlayRenderer.NODE_SCALE,
			"the sibling shrink eases through the existing finite interval");
		double follower = painter.follower("leaf").state().scale();
		assertTrue(follower > SpatialOverlayRenderer.NODE_SELECTED_SCALE && follower < SpatialOverlayRenderer.NODE_FOCUSED_SCALE,
			"the focused node grows through the existing finite interval");
		assertTrue(painter.follower("leaf").state().alpha() > 0.0 && painter.follower("leaf").state().alpha() < 1.0,
			"the focused node reuses the existing appearance fade");

		frame(session, focused, painter, STYLE, DURATION * 2 + 1, VIEW);
		assertEquals(SpatialOverlayRenderer.NODE_FOCUSED_SCALE, painter.follower("leaf").state().scale(), 1.0e-9);
		assertEquals(SpatialOverlayRenderer.NODE_FOCUSED_SIBLING_SCALE, painter.present("other").state().scale(), 1.0e-9);
		assertEquals(SpatialOverlayRenderer.NODE_FOCUSED_SIBLING_SCALE, painter.present("back").state().scale(), 1.0e-9);
		assertEquals(SpatialOverlayRenderer.NODE_FOCUSED_SIBLING_SCALE, painter.present("disabled").state().scale(), 1.0e-9);
		assertEquals(SpatialOverlayRenderer.NODE_FOCUSED_SIBLING_SCALE, painter.present("reserved").state().scale(), 1.0e-9);
		assertEquals(1.0, painter.follower("leaf").state().alpha(), 1.0e-9);
	}

	@Test
	void actionlessBranchFocusAlsoSelectsTheFocusedPresentationAndReducedMotionSnapsIt() {
		var choices = List.of(leaf("leaf"), branch("branch"));
		var session = new SpatialOverlayRenderer.Session();
		var painter = new Painter();

		frame(session, snapshot(List.of(menu("root", p(0, 0), p(0, 0), "branch", choices, null)), p(90, 0)),
			painter, REDUCED, 0, VIEW);
		assertEquals(SpatialOverlayRenderer.NODE_FOCUSED_SCALE, painter.follower("branch").state().scale(), 1.0e-9);
		assertEquals(SpatialOverlayRenderer.NODE_FOCUSED_SIBLING_SCALE, painter.present("leaf").state().scale(), 1.0e-9);
	}

	@Test
	void backDisabledReservedAndDeadzoneFocusKeepTheEstablishedRestingScales() {
		var choices = List.of(leaf("leaf"), back("back"), disabled("disabled"), reserved("reserved"));
		for (String focus : List.of("back", "disabled", "reserved")) {
			var session = new SpatialOverlayRenderer.Session();
			var painter = new Painter();
			frame(session, snapshot(List.of(menu("root", p(0, 0), p(0, 0), focus, choices, null)), p(90, 0)),
				painter, REDUCED, 0, VIEW);
			assertEquals(SpatialOverlayRenderer.NODE_SELECTED_SCALE, painter.present(focus).state().scale(), 1.0e-9, focus);
			assertEquals(SpatialOverlayRenderer.NODE_SCALE, painter.present("leaf").state().scale(), 1.0e-9, focus);
			assertFalse(painter.hasFollower(), "only an eligible action or branch follows: " + focus);
		}

		var session = new SpatialOverlayRenderer.Session();
		var painter = new Painter();
		frame(session, snapshot(List.of(menu("root", p(0, 0), p(0, 0), null, choices, null)), p(0, 0)),
			painter, REDUCED, 0, VIEW);
		assertEquals(SpatialOverlayRenderer.NODE_SCALE, painter.present("leaf").state().scale(), 1.0e-9);
		assertEquals(SpatialOverlayRenderer.NODE_SCALE, painter.present("back").state().scale(), 1.0e-9);
	}

	@Test
	void itemSubmenuFocusScalesRadialNodesWhileInventoryRowsKeepTheirOwnScale() {
		var content = branch("content");
		var inventory = branch("content:inventory");
		var rootMenu = menu("root", p(0, 0), p(0, 0), null, List.of(content), null);
		var contentMenu = menu("content", p(0, 200), p(0, 0), null, List.of(inventory, back("content:back")), "content");
		var surfaceMenu = menu("content:inventory", p(0, 200), p(0, 200), null, List.of(), "content:inventory");
		var itemMenu = menu("item", p(90, 200), p(0, 200), "item:attention", List.of(leaf("item:attention")), null);
		var radial = new SpatialController.Snapshot(true, p(180, 200), List.of(p(90, 200), p(180, 200)),
			"item:attention", List.of(rootMenu, contentMenu, surfaceMenu, itemMenu), 0, 12);
		var list = new SpatialInventoryView("list", true,
			List.of(new SpatialInventoryView.Row("row", "Item", 1L, null, SpatialInventoryView.Status.READY)),
			0, 0, 3, true, SpatialInventoryView.Status.READY, 90, 0);
		var session = new SpatialOverlayRenderer.Session();
		var painter = new Painter();

		painter.clear();
		session.drawFrame(radial, list, choice -> Component.literal(choice.label()), null, REDUCED, 0, VIEW, 400, 300, painter);

		assertEquals(SpatialOverlayRenderer.NODE_FOCUSED_SCALE, painter.follower("item:attention").state().scale(), 1.0e-9);
		assertEquals(1.0, painter.row("row").state().scale(), 1.0e-9, "inventory list rows never participate");
		assertTrue(painter.hasPresent("content"), "the root breadcrumb stays");
		assertTrue(painter.hasPresent("content:inventory"), "the content breadcrumb stays");
		assertTrue(painter.hasPresent("content:back"), "the ancestor Back navigation stays");
	}
}
