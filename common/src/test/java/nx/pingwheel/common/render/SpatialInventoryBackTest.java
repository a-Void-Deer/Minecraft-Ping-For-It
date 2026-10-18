package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.SpatialController;

import static org.junit.jupiter.api.Assertions.*;

/** Recording production paint payloads: no graphics submission or native window is claimed. */
class SpatialInventoryBackTest {
	private static final SpatialOverlayRenderer.Style STYLE = new SpatialOverlayRenderer.Style(100, 100, 1, 1, false, true);
	private static final long DURATION = 160_000_000L;
	private static final class Painter implements SpatialOverlayRenderer.FramePainter {
		final List<SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>> states = new ArrayList<>();
		public int lineHeight() { return 9; }
		public int width(FormattedText text) { return text.getString().length() * 6; }
		public StringSplitter splitter() { return new StringSplitter((point, style) -> 6f); }
		public boolean paint(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
			SpatialOverlayRenderer.NodeLayout layout, SpatialOverlayRenderer.Style style) { states.add(state); return true; }
		public void connector(SpatialOverlayRenderer.GuideLine line, double alpha) {}
		SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state(Class<?> type) {
			return states.stream().filter(value -> value.present() && type.isInstance(value.data())).findFirst().orElseThrow();
		}
	}

	private static SpatialController.Snapshot radial() {
		var origin = new SpatialController.Point(0, 0);
		return new SpatialController.Snapshot(true, origin, List.of(origin), null,
			List.of(new SpatialController.MenuView("surface", origin, origin, null, List.of())), 0, 12);
	}
	private static SpatialInventoryView view(boolean backLeft) {
		var rows = IntStream.range(0, 30).mapToObj(index -> new SpatialInventoryView.Row("k" + index,
			"item", 1L, null, SpatialInventoryView.Status.READY)).toList();
		return new SpatialInventoryView("list", true, rows, 15, 7, 17, backLeft, SpatialInventoryView.Status.READY, 0, 0);
	}
	private static void frame(SpatialOverlayRenderer.Session session, Painter painter, SpatialInventoryView view,
		SpatialOverlayRenderer.Style style, int width, int height, long now) {
		painter.states.clear();
		session.drawFrame(view == null ? null : radial(), view, choice -> Component.literal(choice.label()), null,
			style, now, null, width, height, painter);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void backMirrorsTheActualSideAndTracesItsMeasuredRectangularFrame(boolean backLeft) {
		var painter = new Painter();
		var session = new SpatialOverlayRenderer.Session();
		frame(session, painter, view(backLeft).withBackAffordance(true, 1), STYLE, 640, 360, 0);
		var panel = painter.state(SpatialOverlayRenderer.PanelPaint.class);
		var panelPaint = (SpatialOverlayRenderer.PanelPaint) panel.data();
		var back = painter.state(SpatialOverlayRenderer.InventoryBackPaint.class);
		var paint = (SpatialOverlayRenderer.InventoryBackPaint) back.data();
		var sector = painter.state(SpatialOverlayRenderer.InventoryBackSectorPaint.class);
		var underlay = (SpatialOverlayRenderer.InventoryBackSectorPaint) sector.data();
		double sign = backLeft ? -1 : 1;
		assertEquals(panel.y(), back.y()); assertEquals(panel.y(), sector.y());
		assertTrue(sign * (back.x() - panel.x()) - paint.width() / 2 > panelPaint.width() / 2,
			"the complete Back frame is outside the actual parent-facing panel edge");
		assertTrue(sign * (sector.x() - panel.x()) >= panelPaint.width() / 2);
		assertEquals(backLeft ? 180 : 0, underlay.startDegrees());
		assertEquals(180, underlay.spanDegrees());
		assertTrue(sign * (back.x() - sector.x()) > 0, "the title is inside the outward half-disc");
		assertEquals(1, back.scale(), "Back focus has no follower/selected scaling");
		assertTrue(paint.back().focused());
		assertEquals(1, paint.back().progress());
		int left = (int) Math.round(-paint.width() / 2), right = (int) Math.round(paint.width() / 2);
		int top = (int) Math.round(-paint.height() / 2), bottom = (int) Math.round(paint.height() / 2);
		var progress = SpatialOverlayRenderer.backProgressSegments(left, top, right, bottom, paint.back().progress());
		assertEquals(4, progress.size());
		assertEquals(left, progress.getFirst().x1()); assertEquals(top, progress.getFirst().y1());
		assertEquals(left, progress.getLast().x2()); assertEquals(top, progress.getLast().y2());
		assertTrue(progress.stream().allMatch(line -> line.x1() >= left && line.x1() < right
			&& line.x2() >= left && line.x2() < right && line.y1() >= top && line.y1() < bottom
			&& line.y2() >= top && line.y2() < bottom));
		var footer = (SpatialOverlayRenderer.FooterPaint) painter.state(SpatialOverlayRenderer.FooterPaint.class).data();
		assertEquals(backLeft, footer.backLeft(), "Forward remains on the opposite side");
		assertTrue(session.paintedChoiceIds().isEmpty(), "navigation paint cannot acknowledge a fabricated choice");
	}

	@Test
	void backEnhancementUsesOnlyTheUnderlayCoverageAndIndependentOpacityLayer() {
		var painter = new Painter();
		var session = new SpatialOverlayRenderer.Session();
		frame(session, painter, view(true), STYLE, 640, 360, 0);
		var idleBack = painter.state(SpatialOverlayRenderer.InventoryBackPaint.class);
		var idleSector = painter.state(SpatialOverlayRenderer.InventoryBackSectorPaint.class);
		frame(session, painter, view(true).withBackAffordance(true, .5), STYLE, 640, 360, 1);
		var focusBack = painter.state(SpatialOverlayRenderer.InventoryBackPaint.class);
		var focusSector = painter.state(SpatialOverlayRenderer.InventoryBackSectorPaint.class);
		assertEquals(idleBack.alpha(), focusBack.alpha());
		assertEquals(idleBack.backgroundAlpha(), focusBack.backgroundAlpha());
		assertEquals(idleBack.scale(), focusBack.scale());
		var idleGeometry = (SpatialOverlayRenderer.InventoryBackSectorPaint) idleSector.data();
		var focusGeometry = (SpatialOverlayRenderer.InventoryBackSectorPaint) focusSector.data();
		assertEquals(idleGeometry.radius(), focusGeometry.radius());
		assertEquals(idleGeometry.startDegrees(), focusGeometry.startDegrees());
		assertEquals(idleSector.x(), focusSector.x()); assertEquals(idleSector.y(), focusSector.y());
		assertTrue(focusSector.backgroundAlpha() > idleSector.backgroundAlpha());
		assertTrue(SpatialOverlayRenderer.paintRank(focusSector.data()) < SpatialOverlayRenderer.paintRank(focusBack.data()));
		var wheelOff = new SpatialOverlayRenderer.Style(0, 100, 1, 1, false, true);
		assertEquals(0, SpatialOverlayRenderer.backgroundLayerAlpha(focusSector.data(), focusSector.backgroundAlpha(), wheelOff));
		assertEquals(focusBack.backgroundAlpha(), SpatialOverlayRenderer.backgroundLayerAlpha(
			focusBack.data(), focusBack.backgroundAlpha(), wheelOff));
		assertEquals(1, SpatialOverlayRenderer.layerAlpha(focusBack.data(), focusBack.alpha(), wheelOff));
	}

	@Test
	void resizedDetachedContentUsesTheCurrentContentBudgetWithoutScalingOrAnOldCapacityEscape() {
		var painter = new Painter();
		var session = new SpatialOverlayRenderer.Session();
		var animated = new SpatialOverlayRenderer.Style(100, 100, 1, 1, false, false);
		frame(session, painter, view(true), animated, 1000, 700, 0);
		frame(session, painter, view(true), animated, 1000, 700, DURATION);
		frame(session, painter, null, animated, 1000, 700, DURATION + 1);
		for (long elapsed : new long[] {2, DURATION / 2}) {
			frame(session, painter, null, animated, 320, 240, DURATION + elapsed);
			assertFalse(painter.states.isEmpty(), "the detached exit is still painted");
			var panel = painter.states.stream().filter(state -> state.data() instanceof SpatialOverlayRenderer.PanelPaint)
				.findFirst().orElseThrow();
			var paint = (SpatialOverlayRenderer.PanelPaint) panel.data();
			assertFalse(panel.present());
			assertEquals(64, paint.width());
			assertEquals(129, paint.height(), "four whole rows fit the resized content budget");
			assertEquals(22, painter.states.stream().filter(state -> state.data() instanceof SpatialOverlayRenderer.RowPaint)
				.map(state -> ((SpatialOverlayRenderer.RowPaint) state.data()).height()).findFirst().orElseThrow());
			assertTrue(painter.states.stream().filter(state -> state.data() instanceof SpatialOverlayRenderer.RowPaint).count() <= 4);
			assertTrue(panel.scale() <= 1, "only the ordinary exit scale remains, not a new whole-pattern shrink");
		}
	}
}
