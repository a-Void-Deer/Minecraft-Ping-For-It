package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.junit.jupiter.api.Test;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialMenu;
import static org.junit.jupiter.api.Assertions.*;

/** Actual production paint/commit order with independent recorded node centres; no GPU is claimed. */
class SpatialOverlayRendererTrailTest {
	private static final long D = 160_000_000L;
	private static final SpatialController.Viewport VIEW = new SpatialController.Viewport(400, 300, 0, 0);
	private static final StringSplitter FONT = new StringSplitter((point, style) -> 6f);
	private static SpatialController.Point p(double x, double y) { return new SpatialController.Point(x, y); }
	private static SpatialOverlayRenderer.Style style(boolean reduced, int cap, int opacity) {
		return new SpatialOverlayRenderer.Style(25, 70, 1, 1, 88, 1, true, reduced, cap, opacity);
	}
	private static final class Painter implements SpatialOverlayRenderer.FramePainter {
		final List<SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>> paints = new ArrayList<>();
		Predicate<SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>> accepts = s -> true;
		int connectors;
		public int lineHeight() { return 9; }
		public int width(FormattedText text) { return (int) Math.ceil(FONT.stringWidth(text)); }
		public StringSplitter splitter() { return FONT; }
		public boolean paint(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
			SpatialOverlayRenderer.NodeLayout layout, SpatialOverlayRenderer.Style style) { paints.add(state); return accepts.test(state); }
		public void connector(SpatialOverlayRenderer.GuideLine stroke, double alpha) { connectors++; }
		void clear() { paints.clear(); connectors = 0; }
		SpatialOverlayRenderer.TrailPaint trail() {
			return (SpatialOverlayRenderer.TrailPaint) paints.stream().filter(s -> s.data() instanceof SpatialOverlayRenderer.TrailPaint).findFirst().orElseThrow().data();
		}
		SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> follower(boolean present) {
			return paints.stream().filter(s -> s.present() == present && s.key().kind().equals("follow")).findFirst().orElseThrow();
		}
		SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> follower(String entry, boolean present) {
			return paints.stream().filter(s -> s.present() == present && s.key().kind().equals("follow") && s.key().entry().equals(entry))
				.findFirst().orElseThrow();
		}
		SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> trailState() {
			return paints.stream().filter(s -> s.data() instanceof SpatialOverlayRenderer.TrailPaint).findFirst().orElseThrow();
		}
		boolean hidden() { return paints.stream().filter(s -> s.data() instanceof SpatialOverlayRenderer.ChromePaint)
			.map(s -> ((SpatialOverlayRenderer.ChromePaint) s.data()).pointerHidden()).findFirst().orElse(false); }
	}
	private static SpatialController controller(int cap) {
		var c = new SpatialController(SpatialMenu.of("root",
			SpatialMenu.Choice.leaf("a", "a", "a").withSector(0, 90),
			SpatialMenu.Choice.leaf("b", "b", "b").withSector(90, 90),
			SpatialMenu.Choice.disabled("disabled", "disabled").withSector(180, 90),
			SpatialMenu.Choice.back("back").withSector(270, 90)), new SpatialController.Tuning(12, 20, 10000, false, 500, cap));
		c.start(0); return c;
	}
	private static void frame(SpatialOverlayRenderer.Session s, SpatialController c, Painter painter, SpatialOverlayRenderer.Style style,
		long time, SpatialController.Viewport view, SpatialInventoryView inventory) {
		painter.clear(); s.drawFrame(c == null ? null : c.snapshot(), inventory, choice -> Component.literal(choice.label()),
			choice -> Component.literal("Precise name"), style, time, view, 400, 300, painter);
	}
	private static SpatialController.Point actual(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
		SpatialController.Viewport view) { return p(state.x() - view.centerX() - view.viewX(), state.y() - view.centerY() - view.viewY()); }

	@Test void historicalTrailIsBelowEveryTextAndCommitsActualChangingFollowerOnlyAfterPaint() {
		var c = controller(32); var s = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		frame(s, c, painter, style(true, 32, 37), 0, VIEW, null);
		assertTrue(painter.paints.stream().noneMatch(state -> state.data() instanceof SpatialOverlayRenderer.TrailPaint));
		c.movePhysical(0, -70, 1);
		frame(s, c, painter, style(false, 32, 37), 1, VIEW, null);
		assertEquals(List.of(p(0, 0)), painter.trail().points());
		frame(s, c, painter, style(false, 32, 37), 1 + D / 2, VIEW, null);
		var prior = s.trailPoints(); var moving = painter.follower(true);
		assertNotEquals(c.snapshot().pointer(), actual(moving, VIEW), "actual animated follower differs from raw target");
		c.movePhysical(8, -30, 2);
		frame(s, c, painter, style(false, 32, 37), 1 + D, VIEW, null);
		assertEquals(prior, painter.trail().points(), "immutable historical payload predates current node paint");
		assertEquals(actual(painter.follower(true), VIEW), s.trailPoints().getLast(), "finite handoff reaches current actual pose");
		c.movePhysical(1, -1, 3);
		frame(s, c, painter, style(false, 32, 37), 2 + D, VIEW, null);
		assertEquals(actual(painter.follower(true), VIEW), s.trailPoints().getLast(), "no lag filter after handoff");
		int trailIndex = java.util.stream.IntStream.range(0, painter.paints.size()).filter(i -> painter.paints.get(i).data() instanceof SpatialOverlayRenderer.TrailPaint).findFirst().orElseThrow();
		for (int i = 0; i < painter.paints.size(); i++) {
			var data = painter.paints.get(i).data();
			if (data instanceof SpatialOverlayRenderer.NodePaint) assertTrue(i > trailIndex, "title and external name above trail");
			if (data instanceof SpatialOverlayRenderer.SectorPaint) assertTrue(i < trailIndex);
		}
		assertTrue(painter.hidden()); assertTrue(s.paintedChoiceIds().contains("a")); assertEquals(1, painter.connectors);
	}

	@Test void switchingFailedPaintDisabledAndBackDoNotCertifyPredictedOrDetachedOwner() {
		var c = controller(16); var s = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		frame(s, c, painter, style(true, 16, 50), 0, VIEW, null);
		c.movePhysical(0, -90, 1); frame(s, c, painter, style(true, 16, 50), 1, VIEW, null);
		var before = s.trailPoints();
		c.movePhysical(90, 90, 2); frame(s, c, painter, style(false, 16, 50), 2, VIEW, null);
		assertFalse(painter.hidden()); assertFalse(s.paintedChoiceIds().contains("b")); assertEquals(before, s.trailPoints());
		frame(s, c, painter, style(false, 16, 50), 2 + D / 2, VIEW, null);
		var endpoint = s.trailPoints().getLast(); var pose = actual(painter.follower(true), VIEW);
		assertEquals(p((before.getLast().x() + pose.x()) / 2, (before.getLast().y() + pose.y()) / 2), endpoint);
		painter.accepts = state -> !(state.data() instanceof SpatialOverlayRenderer.NodePaint);
		var history = s.trailPoints(); c.movePhysical(20, 0, 3);
		frame(s, c, painter, style(false, 16, 50), 2 + D, VIEW, null);
		assertEquals(history, s.trailPoints()); assertFalse(painter.hidden()); assertTrue(s.paintedChoiceIds().isEmpty());
		painter.accepts = state -> true;
		c.movePhysical(-110, 60, 4); frame(s, c, painter, style(true, 16, 50), 3 + D, VIEW, null);
		assertEquals("disabled", c.snapshot().focusId()); assertFalse(painter.hidden());
		assertEquals(p(0, 60), s.trailPoints().getLast());
		c.movePhysical(-60, -60, 5); frame(s, c, painter, style(true, 16, 50), 4 + D, VIEW, null);
		assertEquals("back", c.snapshot().focusId()); assertFalse(painter.hidden()); assertEquals(p(-60, 0), s.trailPoints().getLast());
		assertFalse(s.trailPoints().contains(p(110, 0)), "raw pointer movement under failed follower is not replayed");
	}

	@Test void exitSamplesOnlyExactDepartingFollowerWithoutAcknowledgementAndNewHoldOrResetDropsHistory() {
		var c = controller(16); var s = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		c.movePhysical(0, -100, 1); frame(s, c, painter, style(true, 16, 50), 0, VIEW, null);
		var prior = s.trailPoints(); c.release(2);
		frame(s, c, painter, style(false, 16, 50), 1, VIEW, null);
		assertEquals(prior, painter.trail().points());
		frame(s, c, painter, style(false, 16, 50), 1 + D / 2, VIEW, null);
		assertEquals(actual(painter.follower(false), VIEW), s.trailPoints().getLast());
		assertNotEquals(p(0, 0), s.trailPoints().getLast()); assertTrue(s.paintedChoiceIds().isEmpty());
		assertFalse(painter.hidden()); assertEquals(0, painter.connectors);
		frame(s, c, painter, style(false, 16, 50), 1 + D, VIEW, null);
		assertTrue(s.trailPoints().isEmpty()); assertFalse(s.isAnimating());
		c.start(3); frame(s, c, painter, style(true, 16, 50), 2 + D, VIEW, null);
		assertEquals(List.of(p(0, 0)), s.trailPoints());
		c.movePhysical(0, -50, 4); frame(s, c, painter, style(true, 16, 50), 3 + D, VIEW, null);
		c.release(5); frame(s, c, painter, style(false, 16, 50), 4 + D, VIEW, null);
		c.start(6); frame(s, c, painter, style(true, 16, 50), 5 + D, VIEW, null);
		assertEquals(List.of(p(0, 0)), s.trailPoints(), "quick reopen discards exit history");
		s.reset(); assertTrue(s.trailPoints().isEmpty()); assertEquals(0, s.retainedSize());
	}

	@Test void activeInventoryListHidesEveryTrailPaintWhileItemRadialKeepsTheSharedHistoryBelowText() {
		var c = controller(16); c.enterExternal(SpatialMenu.of("list"), 0, 0, 1); c.useExternalSurface();
		var s = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		var list = new SpatialInventoryView("list", true, List.of(new SpatialInventoryView.Row("row", "Item", 1L, null,
			SpatialInventoryView.Status.READY)), 0, 0, 3, true, SpatialInventoryView.Status.READY, 0, 0);
		// A prior tail already paints without the list; the active list surface
		// still hides all of it and inventory rows never own history.
		frame(s, c, painter, style(true, 16, 50), 0, VIEW, null);
		c.moveExternalPointer(4, 7, 2);
		frame(s, c, painter, style(true, 16, 50), 1, VIEW, null);
		assertTrue(painter.paints.stream().anyMatch(state -> state.data() instanceof SpatialOverlayRenderer.TrailPaint),
			"precondition: a prior tail paints while no list surface is active");
		c.moveExternalPointerWithoutTrail(3, 8, 3);
		frame(s, c, painter, style(true, 16, 50), 2, VIEW, list);
		assertEquals(List.of(p(0, 0), p(4, 7)), s.trailPoints()); assertFalse(painter.hidden());
		assertEquals(p(7, 15), c.snapshot().pointer(), "the shared crosshair follows list input without recording history");
		assertEquals(p(7, 15), ((SpatialOverlayRenderer.ChromePaint) painter.paints.stream()
			.filter(state -> state.data() instanceof SpatialOverlayRenderer.ChromePaint).findFirst().orElseThrow().data()).snapshot().pointer());
		assertTrue(painter.paints.stream().noneMatch(state -> state.data() instanceof SpatialOverlayRenderer.TrailPaint),
			"the active list hides its own trail and any prior tail");
		assertTrue(painter.paints.stream().anyMatch(state -> state.data() instanceof SpatialOverlayRenderer.PanelPaint),
			"the list chrome still paints");
		c.alignExternalPointer(7, -40, 4);
		frame(s, c, painter, style(true, 16, 50), 3, VIEW, list);
		assertTrue(s.trailPoints().isEmpty(), "list alignment fences the prior tail without recording its new seed");
		assertFalse(painter.hidden());
		c.enterExternal(SpatialMenu.of("item", SpatialMenu.Choice.leaf("item", "item", "item")), 30, 20, 5);
		painter.clear();
		frame(s, c, painter, style(true, 16, 50), 4, VIEW, list);
		assertEquals(List.of(p(30, 20)), s.trailPoints(), "list to item rebase is not a joining stroke");
		assertFalse(painter.hidden(), "the item radial keeps the ordinary crosshair rule");
		frame(s, c, painter, style(true, 16, 50), 5, VIEW, list);
		assertEquals(List.of(p(30, 20)), painter.trail().points(), "the next frame paints the new epoch's committed seed");
		int trail = java.util.stream.IntStream.range(0, painter.paints.size()).filter(i -> painter.paints.get(i).data() instanceof SpatialOverlayRenderer.TrailPaint).findFirst().orElseThrow();
		for (int i = 0; i < painter.paints.size(); i++) {
			var data = painter.paints.get(i).data();
			if (data instanceof SpatialOverlayRenderer.NodePaint) assertTrue(i > trail, "title and external name above trail");
			if (data instanceof SpatialOverlayRenderer.SectorPaint) assertTrue(i < trail);
			if (data instanceof SpatialOverlayRenderer.PanelPaint || data instanceof SpatialOverlayRenderer.HeaderPaint
				|| data instanceof SpatialOverlayRenderer.RowPaint || data instanceof SpatialOverlayRenderer.FooterPaint) assertTrue(i > trail);
		}
	}

	@Test void rigidViewportPanDoesNotAppendSamplesAndOpacityAndVisibilityReachOnlyTrail() {
		var c = controller(16); var s = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		c.movePhysical(0, 80, 1); frame(s, c, painter, style(true, 16, 25), 0, VIEW, null);
		var prior = s.trailPoints(); var pan = new SpatialController.Viewport(401, 301, -7.375, 16.625);
		frame(s, c, painter, style(true, 16, 25), 1, pan, null);
		assertEquals(prior, s.trailPoints());
		var paint = painter.paints.stream().filter(state -> state.data() instanceof SpatialOverlayRenderer.TrailPaint).findFirst().orElseThrow();
		assertEquals(pan.centerX() + pan.viewX(), paint.x()); assertEquals(pan.centerY() + pan.viewY(), paint.y());
		assertEquals(0.25, SpatialOverlayRenderer.layerAlpha(paint.data(), 1, style(true, 16, 25)));
		var unrelatedOpacity = new SpatialOverlayRenderer.Style(0, 0, 1, 1, 88, 1, true, true, 16, 25);
		assertEquals(0.25, SpatialOverlayRenderer.layerAlpha(paint.data(), 1, unrelatedOpacity), "wheel/target opacity never dim trail");
		assertEquals(0.1, SpatialOverlayRenderer.layerAlpha(paint.data(), 0.4, unrelatedOpacity), 1e-12, "trail opacity multiplied exactly once");
		assertEquals(0.4, SpatialOverlayRenderer.layerAlpha(new SpatialOverlayRenderer.ChromePaint(c.snapshot()), 0.4, unrelatedOpacity));
		assertEquals(0.4, SpatialOverlayRenderer.layerAlpha(new SpatialOverlayRenderer.GuidePaint(List.of(), List.of()), 0.4, unrelatedOpacity));
		var zero = new SpatialOverlayRenderer.Style(0, 0, 1, 1, 88, 1, true, true, 16, 0);
		frame(s, c, painter, zero, 2, pan, null);
		assertTrue(painter.paints.stream().noneMatch(state -> state.data() instanceof SpatialOverlayRenderer.TrailPaint));
		assertTrue(painter.paints.stream().anyMatch(state -> state.data() instanceof SpatialOverlayRenderer.ChromePaint));
		assertEquals(prior, s.trailPoints(), "zero opacity does not mutate history");
		frame(s, c, painter, new SpatialOverlayRenderer.Style(25, 70, 1, 1, 88, 1, false, true, 16, 90), 3, pan, null);
		assertTrue(painter.paints.stream().noneMatch(state -> state.data() instanceof SpatialOverlayRenderer.TrailPaint));
	}

	@Test void actualOffscreenFollowerCannotSampleEvenWhenSubmissionSucceedsAndConfinedTargetIsVisible() {
		var child = SpatialMenu.of("intent", SpatialMenu.Choice.leaf("a", "a", "a"),
			SpatialMenu.Choice.leaf("b", "b", "b"), SpatialMenu.Choice.leaf("c", "c", "c"));
		var c = new SpatialController(SpatialMenu.of("root", SpatialMenu.Choice.branch("intent", "intent", child).withSector(90, 35)),
			new SpatialController.Tuning(12, 20, 1000, false, 500, 32));
		c.start(0); c.enterExternal(child, 80, 0, 1);
		var view = new SpatialController.Viewport(320, 240, -80, 0); c.constrainPointer(view, 1);
		c.movePhysical(115, 119, 2);
		var s = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		frame(s, c, painter, style(false, 32, 50), 0, view, null);
		frame(s, c, painter, style(false, 32, 50), D, view, null);
		frame(s, c, painter, style(false, 32, 50), D * 2, view, null);
		var prior = s.trailPoints();
		double dx = Math.sin(Math.toRadians(175)) * 80, dy = -Math.cos(Math.toRadians(175)) * 80;
		c.movePhysical(dx - 115, dy - 119, 3);
		frame(s, c, painter, style(false, 32, 50), D * 2 + 16_000_000L, view, null);
		assertTrue(painter.follower(true).y() > 260, "actual intermediate pose is wholly beyond the viewport");
		assertEquals(prior, s.trailPoints(), "successful offscreen draw adds no follower sample or raw pointer substitute");
		assertFalse(painter.hidden()); assertEquals(0, painter.connectors); assertFalse(s.paintedChoiceIds().contains("c"));
		frame(s, c, painter, style(false, 32, 50), D * 2 + 80_000_000L, view, null);
		assertTrue(painter.hidden()); assertEquals(actual(painter.follower(true), view), s.trailPoints().getLast());
	}

	@Test void omittedActiveFollowerCannotBorrowItsVisibleDetachedDuplicateOrGrowTrailBeyondCap() {
		var choices = java.util.stream.IntStream.range(0, 270).mapToObj(i -> new SpatialController.ChoiceView("choice" + i,
			"choice" + i, "action", false, false, false, false, false, 0, 360, null)).toList();
		var menu = new SpatialController.MenuView("root", p(0, 0), p(0, 0), "choice0", choices);
		var snapshot = new SpatialController.Snapshot(true, p(90, 0), List.of(), "choice0", List.of(menu), 0, 12);
		var s = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		s.drawFrame(snapshot, null, choice -> Component.literal(choice.label()), null, style(true, 16, 50), 0, VIEW, 400, 300, painter);
		var before = s.trailPoints();
		menu = new SpatialController.MenuView("root", p(0, 0), p(0, 0), "choice269", choices);
		snapshot = new SpatialController.Snapshot(true, p(95, 0), List.of(), "choice269", List.of(menu), 0, 12);
		painter.clear(); s.drawFrame(snapshot, null, choice -> Component.literal(choice.label()), null, style(false, 16, 50),
			1, VIEW, 400, 300, painter);
		assertFalse(painter.hidden()); assertEquals(before, s.trailPoints()); assertTrue(s.trailPoints().size() <= 16);
		assertFalse(s.paintedChoiceIds().contains("choice269")); assertEquals(0, painter.connectors);
		assertEquals(512, s.retainedSize());
	}

	@Test void defaultAndCustomCapsBoundActualFollowerHistoryWithoutChangingPaintAdmission() {
		for (int cap : List.of(16, 130, 512)) {
			var c = controller(cap); var s = new SpatialOverlayRenderer.Session(); var painter = new Painter();
			c.movePhysical(0, -60, 1);
			for (int i = 0; i < 700; i++) {
				c.movePhysical(i % 2 == 0 ? 1 : -1, 0, 2);
				frame(s, c, painter, style(true, cap, 50), i, VIEW, null);
			}
			assertEquals(cap, s.trailPoints().size()); assertEquals(cap, c.snapshot().trail().size());
			assertTrue(painter.hidden()); assertTrue(s.paintedChoiceIds().contains("a"));
		}
	}

	@Test void sameClockReleaseKeepsUnfinishedPointerHandoffMidpointInsteadOfJumpingToDepartingPose() {
		var c = controller(32); var s = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		frame(s, c, painter, style(false, 32, 50), 0, VIEW, null);
		c.movePhysical(0, -100, 1);
		frame(s, c, painter, style(false, 32, 50), 1, VIEW, null);
		frame(s, c, painter, style(false, 32, 50), 1 + D / 2, VIEW, null);
		var painted = actual(painter.follower("a", true), VIEW);
		var expected = p(painted.x() / 2, painted.y() / 2);
		assertEquals(expected, s.trailPoints().getLast(), "pointer-to-follower handoff is halfway at the half interval");
		var committed = s.trailPoints();
		c.release(1 + D / 2);
		frame(s, c, painter, style(false, 32, 50), 1 + D / 2, VIEW, null);
		assertEquals(painted, actual(painter.follower("a", false), VIEW), "departing pose freezes at the displayed pose");
		assertEquals(committed, painter.trail().points(), "the release frame paints the previously committed history");
		assertEquals(expected, s.trailPoints().getLast(), "same-clock release does not jump to the departing pose");
	}

	@Test void sameClockReleaseKeepsUnfinishedFollowerSwitchHandoffTowardTheExactDepartingOwner() {
		var c = controller(32); var s = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		frame(s, c, painter, style(false, 32, 50), 0, VIEW, null);
		c.movePhysical(0, -100, 1);
		frame(s, c, painter, style(false, 32, 50), 1, VIEW, null);
		frame(s, c, painter, style(false, 32, 50), 1 + D, VIEW, null);
		var settledA = actual(painter.follower("a", true), VIEW);
		assertEquals(settledA, s.trailPoints().getLast(), "A settles before the switch");
		long switched = 1 + D + 1;
		c.movePhysical(100, 100, switched);
		frame(s, c, painter, style(false, 32, 50), switched, VIEW, null);
		assertEquals("b", c.snapshot().focusId());
		frame(s, c, painter, style(false, 32, 50), switched + D / 2, VIEW, null);
		var paintedB = actual(painter.follower("b", true), VIEW);
		var expected = p((settledA.x() + paintedB.x()) / 2, (settledA.y() + paintedB.y()) / 2);
		assertEquals(expected, s.trailPoints().getLast(), "switch eases from A's displayed endpoint toward B");
		var committed = s.trailPoints();
		c.release(switched + D / 2);
		frame(s, c, painter, style(false, 32, 50), switched + D / 2, VIEW, null);
		assertEquals(paintedB, actual(painter.follower("b", false), VIEW), "B's departing pose freezes at the displayed pose");
		assertEquals(committed, s.trailPoints(), "same-clock release does not jump to B's departing pose");
		assertNotEquals(actual(painter.follower("a", false), VIEW), s.trailPoints().getLast(),
			"the outgoing A duplicate never owns the departing sample");
	}

	@Test void releasedUnfinishedHandoffKeepsDetachedTrailOriginAcrossGuiResize() {
		var c = controller(32); var s = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		frame(s, c, painter, style(false, 32, 50), 0, VIEW, null);
		c.movePhysical(0, -100, 1);
		frame(s, c, painter, style(false, 32, 50), 1, VIEW, null);
		frame(s, c, painter, style(false, 32, 50), 1 + D / 2, VIEW, null);
		var painted = actual(painter.follower("a", true), VIEW);
		var committed = s.trailPoints();
		assertEquals(p(painted.x() / 2, painted.y() / 2), committed.getLast());
		c.release(1 + D / 2);
		var resized = new SpatialController.Viewport(600, 300, 0, 0);
		frame(s, c, painter, style(false, 32, 50), 1 + D / 2, resized, null);
		assertEquals(p(200, 150), p(painter.trailState().x(), painter.trailState().y()),
			"detached trail keeps its exit origin instead of the new GUI center");
		assertEquals(committed, painter.trail().points(), "the release frame paints the previous committed history");
		assertEquals(committed, s.trailPoints(), "same-clock release keeps the midpoint under resize");
		frame(s, c, painter, style(false, 32, 50), 1 + D, resized, null);
		var departing = p(painter.follower("a", false).x(), painter.follower("a", false).y());
		assertEquals(200, painter.trailState().x()); assertEquals(150, painter.trailState().y());
		var endpoint = s.trailPoints().getLast();
		assertEquals(departing.x(), 200 + endpoint.x(), 1e-9,
			"reprojected trail endpoint tracks the recorded painted pose, not the new GUI center");
		assertEquals(departing.y(), 150 + endpoint.y(), 1e-9);
	}

	@Test void duplicateRoundedSquareCoverageUsesMaximumAlphaEvenForPartialOverlap() {
		var points = List.of(p(0, 0), p(0.1, 0.1), p(1, 0));
		var pixels = SpatialOverlayRenderer.trailPixels(points, 20, 30, 0.5);
		assertEquals(12, pixels.size(), "union of two overlapping 3x3 squares, no duplicate coverage");
		assertEquals(pixels.size(), pixels.stream().map(pixel -> pixel.x() + ":" + pixel.y()).distinct().count());
		assertEquals(61, pixels.stream().filter(pixel -> pixel.x() == 20 && pixel.y() == 30).findFirst().orElseThrow().color() >>> 24);
		assertTrue(pixels.stream().allMatch(pixel -> (pixel.color() >>> 24) <= 61));
		assertTrue(SpatialOverlayRenderer.trailPixels(points, 20, 30, 0).isEmpty());
	}
}
