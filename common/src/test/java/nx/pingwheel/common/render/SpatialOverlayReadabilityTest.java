package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialMenu;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual frame planner and paint ordering, with independent font and rectangle measurements. */
class SpatialOverlayReadabilityTest {
	private static final long D = 160_000_000L;
	private static final StringSplitter FONT = new StringSplitter((code, style) -> 6f);
	private static final SpatialController.Viewport VIEW = new SpatialController.Viewport(400, 300, 0, 0);
	private static final SpatialOverlayRenderer.Style SNAP = new SpatialOverlayRenderer.Style(100, 100, 1.5, 1, 55, 1, true, true);
	private static final SpatialOverlayRenderer.Style EASE = new SpatialOverlayRenderer.Style(100, 100, 1.5, 1, 55, 1, true, false);
	private record Draw(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
		SpatialOverlayRenderer.NodeLayout layout) {}
	private static final class Painter implements SpatialOverlayRenderer.FramePainter {
		final List<Draw> draws = new ArrayList<>();
		public int lineHeight() { return 9; }
		public int width(FormattedText text) { return (int) Math.ceil(FONT.stringWidth(text)); }
		public StringSplitter splitter() { return FONT; }
		public boolean paint(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
			SpatialOverlayRenderer.NodeLayout layout, SpatialOverlayRenderer.Style style) {
			draws.add(new Draw(state, layout)); return true;
		}
		public void connector(SpatialOverlayRenderer.GuideLine stroke, double alpha) {}
		Draw node(String id) { return draws.stream().filter(d -> d.state().present() && d.layout() != null
			&& d.state().key().entry().equals(id)).findFirst().orElseThrow(); }
	}

	private static SpatialController controller() {
		var c = new SpatialController(SpatialMenu.of("root",
			SpatialMenu.Choice.leaf("danger", "DANGER", "danger").withSector(0, 55),
			SpatialMenu.Choice.leaf("content", "Content", "content").withSector(45, 35),
			SpatialMenu.Choice.reserved("east", "Reserved").withSector(90, 55),
			SpatialMenu.Choice.reserved("southeast", "Reserved").withSector(135, 35),
			SpatialMenu.Choice.leaf("cancel", "Unping", "cancel").withSector(180, 55),
			SpatialMenu.Choice.leaf("precise", "Precise", "precise").withSector(225, 35),
			SpatialMenu.Choice.leaf("intent", "Intent", "intent").withSector(270, 55),
			SpatialMenu.Choice.leaf("settings", "Selection", "settings").withSector(315, 35)),
			new SpatialController.Tuning(12, 20, 1000, false, 500));
		c.start(0); return c;
	}
	private static void frame(SpatialOverlayRenderer.Session renderer, SpatialController c, Painter p,
		SpatialOverlayRenderer.Style style, long time) {
		p.draws.clear();
		renderer.drawFrame(c.snapshot(), null, v -> Component.literal(v.label()), null, style, time, VIEW, 400, 300, p);
	}
	private static double bottom(Draw d) { return d.state().y() + d.layout().bottom() * (float) d.state().scale(); }
	private static double top(Draw d) { return d.state().y() + d.layout().top() * (float) d.state().scale(); }

	@Test void shallowEnglishOverlapSeparatesWithoutChangingTextGeometryOrSelection() {
		var c = controller(); c.movePhysical(80, 80, 1);
		var before = c.snapshot(); var renderer = new SpatialOverlayRenderer.Session(); var p = new Painter();
		frame(renderer, c, p, SNAP, 0);
		assertEquals(before, c.snapshot(), "painting cannot pay input or move logical geometry");
		var reserved = p.node("southeast"); var cancel = p.node("cancel");
		assertTrue(bottom(reserved) <= top(cancel), "previous shallow title-frame overlap is separated");
		assertEquals(150 + Math.sqrt(0.5) * 60, reserved.state().y(), 1e-9, "focused frame is not floated");
		assertEquals(200, cancel.state().x(), 1e-9);
		assertEquals(6 * 6 * 1.5 + 8, cancel.layout().width(), "full single-line label width remains unchanged");
		assertEquals(1.5, cancel.layout().titleScale()); assertNull(cancel.layout().detail());
		double settled = cancel.state().y();
		for (int i = 1; i < 50; i++) {
			frame(renderer, c, p, SNAP, i);
			assertEquals(settled, p.node("cancel").state().y());
		}
		assertEquals("southeast", c.snapshot().focusId());
	}

	@Test void focusPaintsLastAsACompleteNodeAndOldFocusCannotKeepPriority() {
		var c = controller(); var renderer = new SpatialOverlayRenderer.Session(); var p = new Painter();
		c.movePhysical(0, -90, 1); frame(renderer, c, p, SNAP, 0);
		assertEquals("danger", p.draws.stream().filter(d -> d.layout() != null).toList().getLast().state().key().entry());
		assertEquals(200, p.node("danger").state().x()); assertEquals(60, p.node("danger").state().y());
		c.movePhysical(90, 90, 2); frame(renderer, c, p, EASE, 1);
		assertEquals("east", p.draws.stream().filter(d -> d.layout() != null).toList().getLast().state().key().entry());
		assertTrue(p.draws.getLast().state().data() instanceof SpatialOverlayRenderer.ChromePaint,
			"pointer feedback still resolves after successful node paints");
	}

	@Test void floatingUsesFiniteTransitionsAndResetDropsItsLayoutMemory() {
		var c = controller(); var renderer = new SpatialOverlayRenderer.Session(); var p = new Painter();
		frame(renderer, c, p, SNAP, 0); double old = p.node("cancel").state().y();
		c.movePhysical(80, 80, 1); frame(renderer, c, p, EASE, 1);
		assertEquals(old, p.node("cancel").state().y(), "retarget begins at the actual displayed pose");
		frame(renderer, c, p, EASE, 1 + D / 2); double midway = p.node("cancel").state().y();
		frame(renderer, c, p, EASE, 1 + D); double end = p.node("cancel").state().y();
		assertTrue(midway >= Math.min(old, end) && midway <= Math.max(old, end));
		frame(renderer, c, p, EASE, 1 + 3 * D); assertEquals(end, p.node("cancel").state().y());
		renderer.reset(); frame(renderer, c, p, SNAP, 0);
		var fresh = new Painter(); frame(new SpatialOverlayRenderer.Session(), c, fresh, SNAP, 0);
		assertEquals(fresh.node("cancel").state(), p.node("cancel").state());
	}

	@Test void connectorsExcludeOverlappingTitleFramesInBothDirections() {
		var boxes = List.of(new SpatialOverlayRenderer.TitleBounds(20, -5, 40, 5),
			new SpatialOverlayRenderer.TitleBounds(35, -5, 60, 5));
		assertEquals(List.of(new SpatialOverlayRenderer.GuideLine(0, 0, 20, 0), new SpatialOverlayRenderer.GuideLine(60, 0, 100, 0)),
			SpatialOverlayRenderer.uncoveredLine(new SpatialOverlayRenderer.GuideLine(0, 0, 100, 0), boxes));
		assertEquals(List.of(new SpatialOverlayRenderer.GuideLine(100, 0, 60, 0), new SpatialOverlayRenderer.GuideLine(20, 0, 0, 0)),
			SpatialOverlayRenderer.uncoveredLine(new SpatialOverlayRenderer.GuideLine(100, 0, 0, 0), boxes));
		assertTrue(SpatialOverlayRenderer.uncoveredLine(new SpatialOverlayRenderer.GuideLine(25, -2, 25, 2), boxes).isEmpty());
	}

	@Test void translatedLongTitlesStaySingleLineBoundedAndInsideTheirSectorsAcrossResize() throws Exception {
		String[] locales = {"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw"};
		var keys = java.util.Map.of("danger", "pingforit.ping_type.danger", "content", "pingforit.spatial.content",
			"east", "pingforit.spatial.reserved", "southeast", "pingforit.spatial.reserved", "cancel", "pingforit.spatial.cancel",
			"precise", "pingforit.spatial.precise", "intent", "pingforit.spatial.intent", "settings", "pingforit.spatial.settings");
		for (String locale : locales) {
			try (var stream = getClass().getResourceAsStream("/assets/pingforit/lang/" + locale + ".json")) {
				assertNotNull(stream);
				var translations = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
				var c = controller(); var before = c.snapshot(); var r = new SpatialOverlayRenderer.Session(); var p = new Painter();
				for (int size : new int[] {400, 200, 600}) {
					p.draws.clear();
					r.drawFrame(c.snapshot(), null, choice -> Component.literal(translations.get(keys.get(choice.id())).getAsString()),
						null, SNAP, size, new SpatialController.Viewport(size, size, 0, 0), size, size, p);
					for (var choice : before.menus().getFirst().choices()) {
						var draw = p.node(choice.id()); var text = translations.get(keys.get(choice.id())).getAsString();
						double angle = Math.toRadians(choice.startDegrees() + choice.spanDegrees() / 2);
						assertEquals(size / 2.0 + Math.sin(angle) * 55, draw.state().x(), 1e-9);
						assertTrue(Math.abs(draw.state().y() - (size / 2.0 - Math.cos(angle) * 55)) <= 24);
						double bearing = Math.toDegrees(Math.atan2(draw.state().x() - size / 2.0, size / 2.0 - draw.state().y()));
						double delta = ((bearing - choice.startDegrees()) % 360 + 360) % 360;
						assertTrue(delta < choice.spanDegrees(), locale + ": center must retain sector association");
						assertEquals(Math.max(14, p.width(Component.literal(text)) * 1.5 + 8), draw.layout().width());
						assertEquals(1.5, draw.layout().titleScale()); assertNull(draw.layout().detail());
					}
					assertEquals(before, c.snapshot());
				}
			}
		}
	}
}
