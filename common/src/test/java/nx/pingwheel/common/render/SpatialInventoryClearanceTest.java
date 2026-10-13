package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.joml.Vector4f;

import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialMenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sole renderer-owned item-menu clearance metric. The oracles are written from
 * the confirmed contract values (orbit clamp and sector margin literally, the
 * Back frame's rounded title bounds at the maximum existing Back scale), and
 * every case also checks the geometric consequence: the actual Back frame keeps
 * at least the conservative rounding margin clear of the panel edge in both
 * orientations.
 */
class SpatialInventoryClearanceTest {

	private static final SpatialOverlayRenderer.Style STYLE =
		new SpatialOverlayRenderer.Style(100, 100, 1, 1, 88, 1, true, false);

	/** Contract orbit clamp and sector focus margin, written independently of the renderer helpers. */
	private static double orbitOracle(int guiWidth, int guiHeight) {
		return Math.max(32.0, Math.min(66.0, Math.min(guiWidth, guiHeight) * 0.11));
	}

	private static double pushedBackRadiusOracle(int guiWidth, int guiHeight) {
		return orbitOracle(guiWidth, guiHeight) + 5.0;
	}

	@Test
	void pinnedViewportMatchesTheIndependentMaxOfBothContractBounds() {
		// 100x100 clamps the orbit to 32. A 24px Back label paints a 32x15 frame
		// whose larger rounded half extent is 16, scaled by the resting selected
		// 1.12 (Back is never an eligible 1.2 follower).
		double pushed = 32.0 + 5.0;
		double outer = 1.1 * (pushed + 12.0);
		double frameBound = pushed + 16.0 * 1.12 + 2.0;
		assertEquals(Math.max(outer, frameBound),
			SpatialOverlayRenderer.itemMenuClearance(100, 100, 9, 24.0, STYLE), 1.0e-9);
		assertTrue(frameBound > outer, "precondition: the Back frame dominates at this label width");
	}

	@Test
	void metricReturnsAtLeastTheOuterSectorCoverageBoundIncludingTheSelectedPush() {
		for (int minimum : List.of(100, 400, 1000)) {
			double pushed = pushedBackRadiusOracle(minimum, minimum);
			double outer = 1.1 * (pushed + 12.0);
			double clearance = SpatialOverlayRenderer.itemMenuClearance(minimum, minimum, 9, 24.0, STYLE);
			assertTrue(clearance >= outer - 1.0e-9, "minimum dimension " + minimum);
		}
	}

	@ParameterizedTest
	@CsvSource({"320,240,24.0", "320,240,240.0", "1000,700,24.0", "1000,700,240.0", "100,100,60.0", "600,200,180.0"})
	void theActualBackFrameStaysClearOfThePanelInBothOrientations(int guiWidth, int guiHeight, double labelWidth) {
		double clearance = SpatialOverlayRenderer.itemMenuClearance(guiWidth, guiHeight, 9, labelWidth, STYLE);
		double pushed = pushedBackRadiusOracle(guiWidth, guiHeight);
		var layout = SpatialOverlayRenderer.nodeLayout(labelWidth, 9, STYLE.optionTextScale());
		double halfWidth = Math.max(Math.abs(layout.left()), layout.right()) * 1.12;

		assertTrue(clearance >= 1.1 * (pushed + 12.0) - 1.0e-9, "outer sector coverage minimum");
		assertTrue(clearance >= pushed + halfWidth + 2.0 - 1.0e-9, "measured Back frame minimum");
		for (double sign : new double[] {-1.0, 1.0}) {
			double itemCenter = sign * (100.0 + clearance);
			double backCenter = itemCenter - sign * pushed;
			double panelEdgeGap = (sign > 0 ? backCenter : -backCenter) - 100.0 - halfWidth;
			assertTrue(panelEdgeGap >= 2.0 - 1.0e-9,
				"orientation " + sign + " keeps the rounding margin between the panel and the Back frame");
		}
	}

	@Test
	void longLocalizedLabelsGrowTheDistanceWhileAShortLabelStaysAtTheOuterBound() {
		double shortLabel = SpatialOverlayRenderer.itemMenuClearance(1000, 700, 9, 24.0, STYLE);
		double longLabel = SpatialOverlayRenderer.itemMenuClearance(1000, 700, 9, 240.0, STYLE);
		assertTrue(longLabel > shortLabel, "a long localized Back label widens the toward-panel frame");
		assertEquals(1.1 * (pushedBackRadiusOracle(1000, 700) + 12.0), shortLabel, 1.0e-9,
			"a short label leaves the outer sector coverage bound in force");
	}

	@Test
	void theFrameUsesTheOptionTextScaleAndNeverTheInventoryScale() {
		double smallOption = SpatialOverlayRenderer.itemMenuClearance(1000, 700, 9, 200.0,
			new SpatialOverlayRenderer.Style(100, 100, 0.5, 1.0, 88, 1, true, false));
		double largeOption = SpatialOverlayRenderer.itemMenuClearance(1000, 700, 9, 200.0,
			new SpatialOverlayRenderer.Style(100, 100, 2.0, 1.0, 88, 1, true, false));
		assertTrue(largeOption > smallOption, "the Back frame follows the option text scale");

		double smallInventory = SpatialOverlayRenderer.itemMenuClearance(1000, 700, 9, 200.0,
			new SpatialOverlayRenderer.Style(100, 100, 1.0, 1.0, 88, 1, true, false));
		double largeInventory = SpatialOverlayRenderer.itemMenuClearance(1000, 700, 9, 200.0,
			new SpatialOverlayRenderer.Style(100, 100, 1.0, 3.0, 88, 1, true, false));
		assertEquals(smallInventory, largeInventory, 1.0e-12, "the inventory text scale never reaches this metric");
	}

	@Test
	void aTallerFontLineGrowsTheMeasuredFrameExtent() {
		double shortLine = SpatialOverlayRenderer.itemMenuClearance(1000, 700, 9, 24.0, STYLE);
		double tallLine = SpatialOverlayRenderer.itemMenuClearance(1000, 700, 60, 24.0, STYLE);
		assertTrue(tallLine > shortLine, "the frame's larger half extent includes its rounded height");
	}

	@Test
	void intermediateResizeDimensionsProduceMonotonicFiniteDistances() {
		double previous = 0.0;
		for (int dimension = 100; dimension <= 1000; dimension += 50) {
			double clearance = SpatialOverlayRenderer.itemMenuClearance(dimension, dimension, 9, 24.0, STYLE);
			assertTrue(Double.isFinite(clearance) && clearance > 0.0, "finite at " + dimension);
			assertTrue(clearance >= previous - 1.0e-9, "orbit growth never shrinks the clearance at " + dimension);
			previous = clearance;
		}
	}

	private static final long DURATION = 160_000_000L;
	private static final StringSplitter FONT = new StringSplitter((point, style) -> 6.0f);
	private record Draw(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
		SpatialOverlayRenderer.NodeLayout layout) {}
	private record Bounds(double left, double top, double right, double bottom) {
		boolean disjoint(Bounds other) {
			return right <= other.left || other.right <= left || bottom <= other.top || other.bottom <= top;
		}
		boolean visible(int width, int height) {
			return right > 0 && bottom > 0 && left < width && top < height;
		}
	}

	private static final class Painter implements SpatialOverlayRenderer.FramePainter {
		final int lineHeight;
		final List<Draw> draws = new ArrayList<>();
		final List<SpatialOverlayRenderer.GuideLine> connectors = new ArrayList<>();
		Painter(int lineHeight) { this.lineHeight = lineHeight; }
		public int lineHeight() { return lineHeight; }
		public int width(FormattedText text) { return (int) Math.ceil(FONT.stringWidth(text)); }
		public StringSplitter splitter() { return FONT; }
		public boolean paint(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
			SpatialOverlayRenderer.NodeLayout layout, SpatialOverlayRenderer.Style style) {
			draws.add(new Draw(state, layout));
			return true;
		}
		public void connector(SpatialOverlayRenderer.GuideLine line, double alpha) { connectors.add(line); }
		Draw panel() {
			return draws.stream().filter(draw -> draw.state().present() && draw.state().data() instanceof SpatialOverlayRenderer.PanelPaint)
				.findFirst().orElseThrow();
		}
		Draw node(boolean back) {
			return draws.stream().filter(draw -> draw.state().present() && draw.state().key().owner().equals("item")
				&& draw.state().data() instanceof SpatialOverlayRenderer.NodePaint node && node.choice().back() == back)
				.findFirst().orElseThrow();
		}
		Draw itemGuide() {
			return draws.stream().filter(draw -> draw.state().present() && draw.state().key().owner().equals("item")
				&& draw.state().data() instanceof SpatialOverlayRenderer.GuidePaint).findFirst().orElseThrow();
		}
		SpatialOverlayRenderer.ChromePaint chrome() {
			return (SpatialOverlayRenderer.ChromePaint) draws.stream().filter(draw -> draw.state().present()
				&& draw.state().data() instanceof SpatialOverlayRenderer.ChromePaint).findFirst().orElseThrow().state().data();
		}
	}

	/** Observe the same rounded local rectangles and float pose transform submitted by the paint path. */
	private static Bounds transformed(Draw draw, int left, int top, int right, int bottom) {
		var pose = new PoseStack();
		pose.translate(draw.state().x(), draw.state().y(), 0);
		pose.scale((float) draw.state().scale(), (float) draw.state().scale(), 1);
		var near = new Vector4f(left, top, 0, 1).mul(pose.last().pose());
		var far = new Vector4f(right, bottom, 0, 1).mul(pose.last().pose());
		return new Bounds(near.x(), near.y(), far.x(), far.y());
	}

	private static Bounds panelBounds(Draw draw) {
		var panel = (SpatialOverlayRenderer.PanelPaint) draw.state().data();
		return transformed(draw, (int) Math.round(-panel.width() / 2), (int) Math.round(-panel.height() / 2),
			(int) Math.round(panel.width() / 2), (int) Math.round(panel.height() / 2));
	}

	private static Bounds titleBounds(Draw draw) {
		return transformed(draw, draw.layout().left(), draw.layout().top(), draw.layout().right(), draw.layout().bottom());
	}

	private static void assertPaintedClearance(Painter painter, String frame) {
		var panels = painter.draws.stream().filter(draw -> draw.state().data() instanceof SpatialOverlayRenderer.PanelPaint)
			.map(SpatialInventoryClearanceTest::panelBounds).toList();
		assertTrue(!panels.isEmpty(), "the actual inventory panel is recorded");
		for (Bounds panel : panels) {
			assertTrue(panel.disjoint(titleBounds(painter.node(true))),
				frame + ": the actual Back title rectangle must not intersect any painted panel, including a detached tail");
		}
		var guide = painter.itemGuide().state();
		int sectors = 0;
		for (Draw draw : painter.draws) {
			if (!draw.state().present() || !draw.state().key().owner().equals("item")
				|| !(draw.state().data() instanceof SpatialOverlayRenderer.SectorPaint sector)) continue;
			sectors++;
			// Enclose every possible painted scanline, including integer rounding
			// and its exclusive bottom/right. This oracle reads paint output, not
			// the clearance helper's orbit/max-bound formula.
			int extent = (int) Math.ceil(sector.radius()) + 1;
			for (Bounds panel : panels) {
				assertTrue(panel.disjoint(transformed(draw, -extent, -extent, extent, extent)),
					frame + ": even the full outer-sector bounding box must clear every painted panel");
			}
			assertEquals(guide.x(), draw.state().x(), 1.0e-9, frame + ": sector and logical guide use the same current origin");
			assertEquals(guide.y(), draw.state().y(), 1.0e-9);
		}
		assertEquals(2, sectors, "both the actual item action sector and Back sector are recorded");
	}

	private static SpatialController.Point p(double x, double y) { return new SpatialController.Point(x, y); }
	private static SpatialController controller(double itemX, double rowY, double axisX, double sign, boolean follower) {
		var controller = new SpatialController(SpatialMenu.of("root",
			SpatialMenu.Choice.leaf("root:leaf", "root", "root:action").withSector(0, 360)),
			new SpatialController.Tuning(12, 20, 1000, false, 500));
		controller.start(0);
		controller.enterExternal(SpatialMenu.of("list"), p(axisX, rowY), p(0, 0), 0);
		controller.useExternalSurface();
		controller.enterExternal(SpatialMenu.of("item", SpatialMenu.Choice.leaf("item:leaf", "Item", "item:action")),
			p(itemX, rowY), p(axisX, rowY), 0);
		controller.rebase(itemX + (follower ? sign : -sign) * 90, rowY, 0);
		return controller;
	}

	private static void frame(SpatialOverlayRenderer.Session session, SpatialController controller, SpatialInventoryView inventory,
		Painter painter, Component backLabel, SpatialOverlayRenderer.Style style, int width, int height, long now) {
		painter.draws.clear();
		painter.connectors.clear();
		var viewport = session.prepareViewport(controller.snapshot(), width, height, style, now);
		controller.constrainPointer(viewport, now / 1_000_000L);
		session.drawFrame(controller.snapshot(), inventory, choice -> choice.back() ? backLabel : Component.literal(choice.label()),
			null, style, now, viewport, width, height, painter);
	}

	@ParameterizedTest
	@CsvSource({"1,24,9,0.5", "-1,24,9,0.5", "1,240,9,1.0", "-1,240,9,1.0",
		"1,24,60,1.0", "-1,24,60,1.0"})
	void actualFirstMidAndSettledResizePaintKeepsSectorAndMeasuredBackFrameDisjoint(
		double sign, int rawBackWidth, int lineHeight, double optionScale) {
		var style = new SpatialOverlayRenderer.Style(100, 100, optionScale, 1, 88, 1.5, true, false);
		var painter = new Painter(lineHeight);
		var label = Component.literal("B".repeat(rawBackWidth / 6));
		assertEquals(rawBackWidth, painter.width(label), "explicit recording-font precondition");
		var session = new SpatialOverlayRenderer.Session();
		double axisX = 57.25, rowY = -23.625;
		var inventory = new SpatialInventoryView("list", true, List.of(
			new SpatialInventoryView.Row("before", "Before", 1L, null, SpatialInventoryView.Status.READY),
			new SpatialInventoryView.Row("retained", "Retained", 2L, null, SpatialInventoryView.Status.READY),
			new SpatialInventoryView.Row("after", "After", 3L, null, SpatialInventoryView.Status.READY)),
			1, 0, 3, sign > 0, SpatialInventoryView.Status.READY, axisX, 0)
			.withSelectedRowCenterY(rowY).withNavigation("retained", true, true);
		double oldClearance = SpatialOverlayRenderer.itemMenuClearance(320, 240, lineHeight, rawBackWidth, style);
		double newClearance = SpatialOverlayRenderer.itemMenuClearance(1000, 700, lineHeight, rawBackWidth, style);
		var controller = controller(axisX + sign * (100 + oldClearance), rowY, axisX, sign, false);
		frame(session, controller, inventory, painter, label, style, 320, 240, 0);
		frame(session, controller, inventory, painter, label, style, 320, 240, DURATION);
		assertPaintedClearance(painter, "old settled");
		assertTrue(((SpatialOverlayRenderer.NodePaint) painter.node(true).state().data()).selected(), "exercise the largest selected Back frame");

		long resized = DURATION + 1;
		controller.repositionExternal(p(axisX + sign * (100 + newClearance), rowY), p(axisX, rowY), resized / 1_000_000L);
		frame(session, controller, inventory, painter, label, style, 1000, 700, resized);
		assertPaintedClearance(painter, "first enlarged frame");
		double firstPanelX = painter.panel().state().x(), firstGuideX = painter.itemGuide().state().x();
		assertEquals(100 + newClearance, sign * (firstGuideX - firstPanelX), 1.0e-9,
			"the first frame cannot retain the old panel-to-menu clearance beside a new radius payload");
		frame(session, controller, inventory, painter, label, style, 1000, 700, resized + DURATION / 2);
		assertPaintedClearance(painter, "mid enlarged frame");
		assertEquals(painter.panel().state().x() - firstPanelX, painter.itemGuide().state().x() - firstGuideX, 1.0e-9,
			"the ongoing pan remains a shared rigid translation, not an element-specific collision shift");
		frame(session, controller, inventory, painter, label, style, 1000, 700, resized + DURATION);
		assertPaintedClearance(painter, "settled enlarged frame");
		assertEquals(500, painter.itemGuide().state().x(), 1.0e-9);
		assertEquals(350, painter.itemGuide().state().y(), 1.0e-9);

		long shrunk = resized + DURATION + 1;
		controller.repositionExternal(p(axisX + sign * (100 + oldClearance), rowY), p(axisX, rowY), shrunk / 1_000_000L);
		for (long elapsed : new long[] {0, DURATION / 2, DURATION}) {
			frame(session, controller, inventory, painter, label, style, 320, 240, shrunk + elapsed);
			assertPaintedClearance(painter, "shrunk frame at " + elapsed);
		}
	}

	@ParameterizedTest
	@CsvSource({"1", "-1"})
	void resizeKeepsFollowerPointerGuideConnectorAndActualTitleAcknowledgementOnOneGeometry(double sign) {
		var style = new SpatialOverlayRenderer.Style(100, 100, 0.5, 1, 88, 1.5, true, false);
		var label = Component.literal("Back");
		var painter = new Painter(9);
		var session = new SpatialOverlayRenderer.Session();
		double oldClearance = SpatialOverlayRenderer.itemMenuClearance(320, 240, 9, 24, style);
		double newClearance = SpatialOverlayRenderer.itemMenuClearance(1000, 700, 9, 24, style);
		var inventory = new SpatialInventoryView("list", true, List.of(
			new SpatialInventoryView.Row("retained", "Retained", 2L, null, SpatialInventoryView.Status.READY)),
			0, 0, 1, sign > 0, SpatialInventoryView.Status.READY, 0, 0)
			.withSelectedRowCenterY(0).withNavigation("retained", true, true);
		var controller = controller(sign * (100 + oldClearance), 0, 0, sign, true);
		frame(session, controller, inventory, painter, label, style, 320, 240, 0);
		frame(session, controller, inventory, painter, label, style, 320, 240, DURATION);
		long resized = DURATION + 1;
		controller.repositionExternal(p(sign * (100 + newClearance), 0), p(0, 0), resized / 1_000_000L);
		for (long elapsed : new long[] {0, DURATION / 2, DURATION}) {
			frame(session, controller, inventory, painter, label, style, 1000, 700, resized + elapsed);
			assertPaintedClearance(painter, "follower resize at " + elapsed);
			var follower = painter.node(false);
			var guide = painter.itemGuide().state();
			var pointer = controller.snapshot().pointer();
			var origin = controller.snapshot().menus().getLast().origin();
			assertTrue(((SpatialOverlayRenderer.NodePaint) follower.state().data()).follower() != null);
			assertEquals(0, (follower.state().x() - guide.x()) * (pointer.y() - origin.y())
				- (follower.state().y() - guide.y()) * (pointer.x() - origin.x()), 1.0e-8,
				"follower always lies on the current logical pointer ray, including the first retarget frame");
			Bounds title = titleBounds(follower);
			assertEquals(title.visible(1000, 700), session.paintedChoiceIds().contains("item:leaf"));
			assertEquals(title.visible(1000, 700), painter.chrome().pointerHidden());
			assertEquals(1, painter.connectors.size());
			var connector = painter.connectors.getFirst();
			assertEquals(guide.x() + sign * 12, connector.x1(), 1.0e-8);
			assertEquals(sign > 0 ? title.left() : title.right(), connector.x2(), 1.0e-4,
				"the connector ends at the actual painted title, never a settled target or shifted substitute");
		}
	}

	@ParameterizedTest
	@CsvSource({"1", "-1"})
	void aLongerBackLabelRelayoutAtTheSameViewportCommitsTheWholeGeometryBeforeTheNewFramePaints(double sign) {
		var style = new SpatialOverlayRenderer.Style(100, 100, 1, 1, 88, 1.5, true, false);
		var painter = new Painter(9);
		var session = new SpatialOverlayRenderer.Session();
		var shortLabel = Component.literal("Back");
		var longLabel = Component.literal("B".repeat(40));
		var inventory = new SpatialInventoryView("list", true, List.of(
			new SpatialInventoryView.Row("retained", "Retained", 2L, null, SpatialInventoryView.Status.READY)),
			0, 0, 1, sign > 0, SpatialInventoryView.Status.READY, 0, 0)
			.withSelectedRowCenterY(0).withNavigation("retained", true, true);
		double oldClearance = SpatialOverlayRenderer.itemMenuClearance(400, 300, 9, painter.width(shortLabel), style);
		double newClearance = SpatialOverlayRenderer.itemMenuClearance(400, 300, 9, painter.width(longLabel), style);
		assertTrue(newClearance > oldClearance, "the newly measured frame requires an actual metric-only relayout");
		var controller = controller(sign * (100 + oldClearance), 0, 0, sign, false);
		frame(session, controller, inventory, painter, shortLabel, style, 400, 300, 0);
		frame(session, controller, inventory, painter, shortLabel, style, 400, 300, DURATION);
		assertPaintedClearance(painter, "old measured label");
		long changed = DURATION + 1;
		controller.repositionExternal(p(sign * (100 + newClearance), 0), p(0, 0), changed / 1_000_000L);
		for (long elapsed : new long[] {0, DURATION / 2, DURATION}) {
			frame(session, controller, inventory, painter, longLabel, style, 400, 300, changed + elapsed);
			assertPaintedClearance(painter, "same-viewport label relayout at " + elapsed);
		}
	}

	@ParameterizedTest
	@CsvSource({"1", "-1"})
	void resizeRebasesADetachedPriorPanelWithTheSamePatternInsteadOfLeavingItsOldGuiCenter(double sign) {
		var style = new SpatialOverlayRenderer.Style(100, 100, 0.5, 1, 88, 1.5, true, false);
		var painter = new Painter(9);
		var session = new SpatialOverlayRenderer.Session();
		var label = Component.literal("Back");
		var retained = new SpatialInventoryView.Row("retained", "Retained", 2L, null, SpatialInventoryView.Status.READY);
		var oldInventory = new SpatialInventoryView("list", true, List.of(retained,
			new SpatialInventoryView.Row("other", "Other", 1L, null, SpatialInventoryView.Status.READY)),
			0, 0, 3, sign > 0, SpatialInventoryView.Status.READY, 0, 0)
			.withSelectedRowCenterY(0).withNavigation("retained", true, true);
		var newInventory = new SpatialInventoryView("list", true, List.of(retained),
			0, 0, 3, sign > 0, SpatialInventoryView.Status.READY, 0, 0)
			.withSelectedRowCenterY(0).withNavigation("retained", true, true);
		double oldClearance = SpatialOverlayRenderer.itemMenuClearance(320, 240, 9, 24, style);
		double newClearance = SpatialOverlayRenderer.itemMenuClearance(1000, 700, 9, 24, style);
		var controller = controller(sign * (100 + oldClearance), 0, 0, sign, false);
		frame(session, controller, oldInventory, painter, label, style, 320, 240, 0);
		frame(session, controller, oldInventory, painter, label, style, 320, 240, DURATION);
		// A stream/window topology change leaves a bounded panel exit before resize.
		frame(session, controller, newInventory, painter, label, style, 320, 240, DURATION + 1);
		long resized = DURATION + 2;
		controller.repositionExternal(p(sign * (100 + newClearance), 0), p(0, 0), resized / 1_000_000L);
		for (long elapsed : new long[] {0, DURATION / 2, DURATION}) {
			frame(session, controller, newInventory, painter, label, style, 1000, 700, resized + elapsed);
			assertPaintedClearance(painter, "detached-panel resize at " + elapsed);
		}
	}

	@Test
	void invalidInputsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> SpatialOverlayRenderer.itemMenuClearance(0, 240, 9, 24.0, STYLE));
		assertThrows(IllegalArgumentException.class, () -> SpatialOverlayRenderer.itemMenuClearance(320, 0, 9, 24.0, STYLE));
		assertThrows(IllegalArgumentException.class, () -> SpatialOverlayRenderer.itemMenuClearance(320, 240, 0, 24.0, STYLE));
		assertThrows(IllegalArgumentException.class, () -> SpatialOverlayRenderer.itemMenuClearance(320, 240, 9, -1.0, STYLE));
		assertThrows(IllegalArgumentException.class, () -> SpatialOverlayRenderer.itemMenuClearance(320, 240, 9, Double.NaN, STYLE));
	}
}
