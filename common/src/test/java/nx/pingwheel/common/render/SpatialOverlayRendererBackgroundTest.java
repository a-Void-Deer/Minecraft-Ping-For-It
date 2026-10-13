package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.render.SpatialInventoryView.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production radial/inventory planning and frame seam over recording paint for
 * the independent background coverage channel: the fractional 30%/60% bases,
 * the eligible-focus gains, the structural ancestor/disabled dimming, exactly
 * one final rounding (153 * 1.5 -&gt; 230), the inventory surface gains, the
 * retained-row/Forward rules and the foreground/acknowledgement independence.
 */
class SpatialOverlayRendererBackgroundTest {
	private static final long DURATION = 160_000_000L;
	private static final StringSplitter FONT = new StringSplitter((point, style) -> 6.0f);
	private static final SpatialController.Viewport VIEW = new SpatialController.Viewport(400, 300, 0, 0);
	private static final SpatialOverlayRenderer.Style NORMAL =
		new SpatialOverlayRenderer.Style(100, 100, 1, 1, 88, 1, true, false);
	private static final SpatialOverlayRenderer.Style REDUCED =
		new SpatialOverlayRenderer.Style(100, 100, 1, 1, 88, 1, true, true);

	private record Draw(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
		SpatialOverlayRenderer.NodeLayout layout) {}
	private record Fill(String row, int left, int top, int right, int bottom, int color) {}

	private static final class Painter implements SpatialOverlayRenderer.FramePainter {
		final List<Draw> draws = new ArrayList<>();
		final List<Fill> fills = new ArrayList<>();
		public int lineHeight() { return 9; }
		public int width(FormattedText text) { return (int) Math.ceil(FONT.stringWidth(text)); }
		public StringSplitter splitter() { return FONT; }
		public boolean paint(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
			SpatialOverlayRenderer.NodeLayout layout, SpatialOverlayRenderer.Style style) {
			draws.add(new Draw(state, layout));
			if (state.data() instanceof SpatialOverlayRenderer.RowPaint row) {
				// This invokes the same decoration routine used by paintRow, not a
				// test-side interpretation of the transition coverage channel.
				SpatialOverlayRenderer.paintRowFills(row, style,
					SpatialOverlayRenderer.targetAlpha(state.alpha(), style), state.backgroundAlpha(),
					(left, top, right, bottom, color) -> fills.add(new Fill(row.row().key(), left, top, right, bottom, color)));
			}
			return true;
		}
		public void connector(SpatialOverlayRenderer.GuideLine line, double alpha) {}
		void clear() { draws.clear(); fills.clear(); }
		List<Fill> overlays(String key) {
			return fills.stream().filter(fill -> fill.row().equals(key) && fill.right() - fill.left() > 1).toList();
		}
		boolean hasStripe(String key) {
			return fills.stream().anyMatch(fill -> fill.row().equals(key) && fill.right() - fill.left() == 1);
		}
		Draw node(String id) {
			return draws.stream().filter(draw -> draw.state().data() instanceof SpatialOverlayRenderer.NodePaint node
				&& node.choice().id().equals(id)).findFirst().orElseThrow();
		}
		boolean hasNode(String id) {
			return draws.stream().anyMatch(draw -> draw.state().data() instanceof SpatialOverlayRenderer.NodePaint node
				&& node.choice().id().equals(id));
		}
		boolean hasPresentData(Class<?> type) {
			return draws.stream().anyMatch(draw -> draw.state().present() && type.isInstance(draw.state().data()));
		}
		Draw presentData(Class<?> type) {
			return draws.stream().filter(draw -> draw.state().present() && type.isInstance(draw.state().data()))
				.findFirst().orElseThrow();
		}
		Draw presentRow(String key) {
			return draws.stream().filter(draw -> draw.state().present()
				&& draw.state().data() instanceof SpatialOverlayRenderer.RowPaint row && row.row().key().equals(key))
				.findFirst().orElseThrow();
		}
		boolean hasRow(String key) {
			return draws.stream().anyMatch(draw -> draw.state().data() instanceof SpatialOverlayRenderer.RowPaint row
				&& row.row().key().equals(key));
		}
		SpatialOverlayRenderer.FooterPaint footer() {
			return (SpatialOverlayRenderer.FooterPaint) presentData(SpatialOverlayRenderer.FooterPaint.class).state().data();
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

	private static SpatialController.MenuView menu(String id, SpatialController.Point origin, String focus,
		List<SpatialController.ChoiceView> choices) {
		return new SpatialController.MenuView(id, origin, p(0, 0), focus, choices);
	}

	private static SpatialController.Snapshot snapshot(List<SpatialController.MenuView> menus, SpatialController.Point pointer) {
		return new SpatialController.Snapshot(true, pointer, List.of(p(0, 0), pointer), menus.getLast().focusId(), menus, 0, 12);
	}

	private static List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>>
		planned(SpatialController.Snapshot snapshot) {
		var targets = new ArrayList<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>>();
		SpatialOverlayRenderer.addRadial(targets, snapshot, choice -> Component.literal(choice.label()), null,
			VIEW.centerX(), VIEW.centerY(), SpatialOverlayRenderer.orbitFor(240.0), 88.0, 1.0);
		return targets;
	}

	private static double nodeCoverage(
		List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>> targets,
		String id) {
		return targets.stream().filter(target -> target.data() instanceof SpatialOverlayRenderer.NodePaint node
			&& node.choice().id().equals(id)).findFirst().orElseThrow().backgroundAlpha();
	}

	private static double sectorCoverage(
		List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>> targets,
		String id) {
		return targets.stream().filter(target -> target.data() instanceof SpatialOverlayRenderer.SectorPaint sector
			&& sector.choice().id().equals(id)).findFirst().orElseThrow().backgroundAlpha();
	}

	private static void frame(SpatialOverlayRenderer.Session session, SpatialController.Snapshot radial,
		Painter painter, SpatialOverlayRenderer.Style style, long time, SpatialInventoryView inventory) {
		painter.clear();
		session.drawFrame(radial, inventory, choice -> Component.literal(choice.label()), null, style, time, VIEW, 400, 300, painter);
	}

	private static List<SpatialInventoryView.Row> rows(int count) {
		var rows = new ArrayList<SpatialInventoryView.Row>(count);
		for (int i = 0; i < count; i++) {
			rows.add(new SpatialInventoryView.Row("r" + i, "Row" + i, (long) i, null, Status.READY));
		}
		return rows;
	}

	private static SpatialController.Snapshot listSnapshot(SpatialController.Point pointer) {
		return snapshot(List.of(
			menu("root", p(0, 0), null, List.of(leaf("a"))),
			menu("list", p(90, 0), null, List.of())), pointer);
	}

	private static SpatialController.Snapshot itemSnapshot() {
		return snapshot(List.of(
			menu("root", p(0, 0), null, List.of(leaf("a"))),
			menu("list", p(90, 0), null, List.of()),
			menu("item", p(90, 90), "item:leaf", List.of(leaf("item:leaf")))), p(90, 90));
	}

	@Test
	void restingCoverageUsesTheFractionalThirtyAndSixtyPercentBases() {
		var choices = List.of(leaf("leaf"), back("back"), disabled("disabled"), reserved("reserved"));
		var targets = planned(snapshot(List.of(menu("root", p(0, 0), null, choices)), p(0, 0)));

		assertEquals(0.30, sectorCoverage(targets, "leaf"), 1.0e-12);
		assertEquals(0.60, nodeCoverage(targets, "leaf"), 1.0e-12);
		assertEquals(0.30, sectorCoverage(targets, "back"), 1.0e-12, "Back keeps the resting sector coverage");
		assertEquals(0.60, nodeCoverage(targets, "back"), 1.0e-12);
		assertEquals(0.15, sectorCoverage(targets, "disabled"), 1.0e-12);
		assertEquals(0.30, nodeCoverage(targets, "disabled"), 1.0e-12);
		assertEquals(0.15, sectorCoverage(targets, "reserved"), 1.0e-12);
		assertEquals(0.30, nodeCoverage(targets, "reserved"), 1.0e-12);
	}

	@Test
	void eligibleFocusAppliesTheConfirmedSectorAndBoxGainsToTheEntryAndItsSiblings() {
		var choices = List.of(leaf("leaf"), leaf("other"), back("back"), disabled("disabled"));
		var targets = planned(snapshot(List.of(menu("root", p(0, 0), "leaf", choices)), p(90, 0)));

		assertEquals(0.42, sectorCoverage(targets, "leaf"), 1.0e-12);
		assertEquals(0.90, nodeCoverage(targets, "leaf"), 1.0e-12);
		assertEquals(0.12, sectorCoverage(targets, "other"), 1.0e-12);
		assertEquals(0.24, nodeCoverage(targets, "other"), 1.0e-12);
		assertEquals(0.12, sectorCoverage(targets, "back"), 1.0e-12, "Back is a supported sibling, never the selected gain");
		assertEquals(0.24, nodeCoverage(targets, "back"), 1.0e-12);
		assertEquals(0.06, sectorCoverage(targets, "disabled"), 1.0e-12, "the disabled factor multiplies the sibling gain");
		assertEquals(0.12, nodeCoverage(targets, "disabled"), 1.0e-12);

		var branchTargets = planned(snapshot(List.of(menu("root", p(0, 0), "branch",
			List.of(branch("branch"), leaf("other")))), p(90, 0)));
		assertEquals(0.90, nodeCoverage(branchTargets, "branch"), 1.0e-12, "an actionless branch focus is eligible");
		assertEquals(0.24, nodeCoverage(branchTargets, "other"), 1.0e-12);
	}

	@Test
	void backDisabledReservedAndDeadzoneFocusKeepEveryRestingGain() {
		var choices = List.of(leaf("leaf"), back("back"), disabled("disabled"), reserved("reserved"));
		for (String focus : List.of("back", "disabled", "reserved")) {
			var targets = planned(snapshot(List.of(menu("root", p(0, 0), focus, choices)), p(90, 0)));
			assertEquals(0.30, sectorCoverage(targets, "leaf"), 1.0e-12, focus);
			assertEquals(0.60, nodeCoverage(targets, "leaf"), 1.0e-12, focus);
			assertEquals(focus.equals("back") ? 0.30 : 0.15, sectorCoverage(targets, focus), 1.0e-12, focus);
			assertEquals(focus.equals("back") ? 0.60 : 0.30, nodeCoverage(targets, focus), 1.0e-12, focus);
		}
		var deadzone = planned(snapshot(List.of(menu("root", p(0, 0), null, choices)), p(0, 0)));
		assertEquals(0.30, sectorCoverage(deadzone, "leaf"), 1.0e-12);
		assertEquals(0.60, nodeCoverage(deadzone, "leaf"), 1.0e-12);
	}

	@Test
	void ancestorLevelsApplyStructuralDimmingWithoutTheForegroundUnselectedFactor() {
		var root = menu("root", p(0, 0), null, List.of(branch("content"), leaf("danger")));
		var content = new SpatialController.MenuView("content", p(30, -20), p(0, 0), null,
			List.of(branch("child"), back("content:back")), "content");
		var child = new SpatialController.MenuView("child", p(70, 30), p(30, -20), null,
			List.of(leaf("child:leaf")), "child");
		var targets = planned(snapshot(List.of(root, content, child), p(0, 0)));

		assertEquals(0.21, nodeCoverage(targets, "content"), 1.0e-12, "60% * the ancestor 0.35 factor");
		assertTrue(targets.stream().noneMatch(target -> target.data() instanceof SpatialOverlayRenderer.SectorPaint sector
			&& sector.choice().id().equals("content")), "the ancestor breadcrumb contributes no sector");
		assertEquals(0.21, nodeCoverage(targets, "content:back"), 1.0e-12, "the ancestor Back keeps its frame");
		assertEquals(0.105, sectorCoverage(targets, "content:back"), 1.0e-12, "and its sector");
		assertFalse(nodeCoverage(targets, "content") == 0.6 * 0.35 * 0.8,
			"the foreground unselected 0.8 factor is not applied a second time");
		assertTrue(targets.stream().noneMatch(target -> target.data() instanceof SpatialOverlayRenderer.NodePaint node
			&& node.choice().id().equals("danger")), "hidden ancestor siblings stay absent from planning");
	}

	@Test
	void finalByteRoundsExactlyOnceAndKeepsTheSixtyPercentFocusedEndpoint() {
		double focusedBox = SpatialOverlayRenderer.backgroundCoverage(60.0, 1.5, 1.0);
		assertEquals(0.9, focusedBox);
		assertEquals((int) Math.round(255.0 * 60 / 100.0 * 1.5), SpatialOverlayRenderer.backgroundChannel(focusedBox, 100.0));
		assertEquals(230, SpatialOverlayRenderer.backgroundChannel(focusedBox, 100.0),
			"153 * 1.5 must reach the correct endpoint byte, not one below the half");
		assertEquals(153, SpatialOverlayRenderer.backgroundChannel(
			SpatialOverlayRenderer.backgroundCoverage(60.0, 1.0, 1.0), 100.0));
		assertEquals(77, SpatialOverlayRenderer.backgroundChannel(
			SpatialOverlayRenderer.backgroundCoverage(30.0, 1.0, 1.0), 100.0), "the fractional 30% base is preserved");
		assertEquals(107, SpatialOverlayRenderer.backgroundChannel(
			SpatialOverlayRenderer.backgroundCoverage(30.0, 1.4, 1.0), 100.0));
		assertEquals(80, SpatialOverlayRenderer.backgroundChannel(
			SpatialOverlayRenderer.backgroundCoverage(30.0, 1.4, 1.0), 75.0));
		assertEquals(38, SpatialOverlayRenderer.backgroundChannel(
			SpatialOverlayRenderer.backgroundCoverage(30.0, 1.0, 0.5), 100.0));
		assertEquals(54, SpatialOverlayRenderer.backgroundChannel(
			SpatialOverlayRenderer.backgroundCoverage(60.0, 1.0, 0.35), 100.0));
		assertEquals(27, SpatialOverlayRenderer.backgroundChannel(
			SpatialOverlayRenderer.backgroundCoverage(60.0, 1.0, 0.175), 100.0));
		assertEquals(0, SpatialOverlayRenderer.backgroundChannel(0.9, 0.0));
		int packed = SpatialOverlayRenderer.withBackgroundAlpha(0xCC1A1A1A, focusedBox, 100.0);
		assertEquals(230, packed >>> 24);
		assertEquals(0x1A1A1A, packed & 0x00FFFFFF, "only the alpha byte is recomputed");
	}

	@Test
	void eachOpacityKnobReachesOnlyItsOwnBackgroundLayer() {
		var session = new SpatialOverlayRenderer.Session();
		var painter = new Painter();
		var radial = snapshot(List.of(menu("root", p(0, 0), null, List.of(leaf("leaf")))), p(0, 0));

		frame(session, radial, painter, new SpatialOverlayRenderer.Style(100, 0, 1, 1, 88, 1, true, true), 0, null);
		assertTrue(painter.hasPresentData(SpatialOverlayRenderer.SectorPaint.class),
			"wheel opacity keeps only the sector underlay");
		assertFalse(painter.hasPresentData(SpatialOverlayRenderer.NodePaint.class),
			"zero target opacity drops the text-bearing box layer and its foreground");
		assertTrue(painter.hasPresentData(SpatialOverlayRenderer.ChromePaint.class));

		frame(session, radial, painter, new SpatialOverlayRenderer.Style(0, 100, 1, 1, 88, 1, true, true), 1, null);
		assertFalse(painter.hasPresentData(SpatialOverlayRenderer.SectorPaint.class),
			"zero wheel opacity drops the sector underlay");
		assertTrue(painter.hasPresentData(SpatialOverlayRenderer.NodePaint.class));

		frame(session, radial, painter, new SpatialOverlayRenderer.Style(0, 0, 1, 1, 88, 1, true, true), 2, null);
		assertFalse(painter.hasPresentData(SpatialOverlayRenderer.NodePaint.class));
		assertFalse(painter.hasPresentData(SpatialOverlayRenderer.SectorPaint.class));
		assertTrue(session.paintedChoiceIds().isEmpty());
	}

	@Test
	void backgroundOnlyPaintIsAdmittedButNeverAcknowledges() {
		var node = new SpatialOverlayRenderer.NodePaint(leaf("leaf"), Component.literal("leaf"), false, 0.0);
		assertEquals(0.0, SpatialOverlayRenderer.layerAlpha(node, 0.0, NORMAL), 1.0e-12);
		assertTrue(SpatialOverlayRenderer.backgroundLayerAlpha(node, 0.6, NORMAL) > 0.0);
		assertTrue(SpatialOverlayRenderer.renders(node, 0.0, 0.6, NORMAL),
			"a bright background alone admits its paint");
		assertFalse(SpatialOverlayRenderer.renders(node, 0.0, 0.0, NORMAL));
		assertFalse(SpatialOverlayRenderer.acknowledgesNodePaint(true, true, 0.0, NORMAL),
			"an invisible foreground can never acknowledge, however bright the background");
		assertEquals(1.0, SpatialOverlayRenderer.nodeAlpha(true, true, false));
		assertEquals(0.8, SpatialOverlayRenderer.nodeAlpha(true, false, false));
		assertEquals(0.35, SpatialOverlayRenderer.nodeAlpha(false, false, false));
		assertEquals(0.4, SpatialOverlayRenderer.nodeAlpha(true, false, true));
	}

	@Test
	void frameBackgroundsFadeWithTheirOwnChannelAndDetachedExitKeepsThem() {
		var session = new SpatialOverlayRenderer.Session();
		var painter = new Painter();
		var radial = snapshot(List.of(menu("root", p(0, 0), null, List.of(leaf("leaf")))), p(0, 0));

		frame(session, radial, painter, NORMAL, 0, null);
		assertFalse(painter.hasNode("leaf"), "a fully transparent appearance paints nothing");
		frame(session, radial, painter, NORMAL, DURATION / 2, null);
		assertEquals(0.4, painter.node("leaf").state().alpha(), 1.0e-12);
		assertEquals(0.3, painter.node("leaf").state().backgroundAlpha(), 1.0e-12);
		frame(session, radial, painter, NORMAL, DURATION, null);
		assertEquals(0.6, painter.node("leaf").state().backgroundAlpha(), 1.0e-12);

		frame(session, null, painter, NORMAL, DURATION + 1, null);
		var detached = painter.node("leaf");
		assertFalse(detached.state().present());
		assertEquals(0.6, detached.state().backgroundAlpha(), 1.0e-12,
			"the detached exit starts at the displayed coverage");
		frame(session, null, painter, NORMAL, DURATION + 1 + DURATION / 2, null);
		assertEquals(0.3, painter.node("leaf").state().backgroundAlpha(), 1.0e-12,
			"the detached coverage fades toward zero on its own interval");
	}

	@Test
	void activeListSurfaceCoverageTheSelectedRowGainsAndRetainedRowFiltering() {
		var session = new SpatialOverlayRenderer.Session();
		var painter = new Painter();
		var view = new SpatialInventoryView("list", true, rows(6), 2, 0, 3, true, Status.READY, 0, 0)
			.withSelectedRowCenterY(0.0).withNavigation(null, false, true);

		frame(session, listSnapshot(p(90, 0)), painter, REDUCED, 0, view);
		assertEquals(0.84, painter.presentData(SpatialOverlayRenderer.PanelPaint.class).state().backgroundAlpha(), 1.0e-12);
		assertEquals(0.84, painter.presentData(SpatialOverlayRenderer.HeaderPaint.class).state().backgroundAlpha(), 1.0e-12);
		assertEquals(0.84, painter.presentRow("r2").state().backgroundAlpha(), 1.0e-12,
			"a committable selected row emphasizes although Forward is unavailable");
		assertEquals(0.0, painter.presentRow("r0").state().backgroundAlpha(), 1.0e-12,
			"unselected rows keep their established unfilled paint");
		assertTrue(painter.hasRow("r0") && painter.hasRow("r1") && painter.hasRow("r2"));
		assertFalse(painter.hasRow("r3"), "only the shown window is planned");
		assertFalse(painter.footer().forwardAvailable(), "explicit availability reaches the footer unchanged");

		frame(session, listSnapshot(p(90, 0)), painter, REDUCED, 1, view.withNavigation(null, true, false));
		assertEquals(0.42, painter.presentRow("r2").state().backgroundAlpha(), 1.0e-12,
			"a non-committable selected row uses the resting gain");
		frame(session, listSnapshot(p(90, 0)), painter, REDUCED, 2,
			view.withBackAffordance(true, 0.5).withNavigation(null, true, true));
		assertEquals(0.42, painter.presentRow("r2").state().backgroundAlpha(), 1.0e-12,
			"Back focus suppresses the selected-row emphasis");

		frame(session, itemSnapshot(), painter, REDUCED, 3, view.withNavigation("r2", false, true));
		assertTrue(painter.hasRow("r2"));
		assertFalse(painter.hasRow("r0"), "only the entered item row may paint while its submenu is active");
		assertEquals(0.42, painter.presentData(SpatialOverlayRenderer.PanelPaint.class).state().backgroundAlpha(), 1.0e-12,
			"the list drops to the resting coverage under the item submenu");
		assertEquals(0.42, painter.presentRow("r2").state().backgroundAlpha(), 1.0e-12);
		assertFalse(painter.footer().forwardAvailable(), "the nonexistent Forward hint is hidden");

		var empty = new SpatialInventoryView("list", true, List.of(), -1, 0, 3, true, Status.UNAVAILABLE, 0, 0)
			.withNavigation(null, false, false);
		frame(session, listSnapshot(p(90, 0)), painter, REDUCED, 4, empty);
		assertEquals(0.84, painter.presentData(SpatialOverlayRenderer.PanelPaint.class).state().backgroundAlpha(), 1.0e-12,
			"an empty active list still emphasizes its panel");
		assertEquals(0.84, painter.presentData(SpatialOverlayRenderer.HeaderPaint.class).state().backgroundAlpha(), 1.0e-12);
		assertFalse(painter.hasRow("r2"), "the previous window leaves after the reduced-motion snap");
		assertFalse(painter.footer().forwardAvailable());
	}

	@Test
	void itemSubmenuSuppressesDetachedSiblingRowTailsAndTheReturnRestoresTheWindow() {
		var session = new SpatialOverlayRenderer.Session();
		var painter = new Painter();
		var view = new SpatialInventoryView("list", true, rows(3), 0, 0, 3, true, Status.READY, 0, 0)
			.withSelectedRowCenterY(0.0).withNavigation(null, true, true);

		frame(session, listSnapshot(p(90, 0)), painter, NORMAL, 0, view);
		frame(session, listSnapshot(p(90, 0)), painter, NORMAL, DURATION, view);
		frame(session, itemSnapshot(), painter, NORMAL, DURATION + 10, view.withNavigation("r0", false, true));
		assertTrue(painter.hasRow("r0"));
		assertFalse(painter.hasRow("r1"), "a detached sibling row never leaks through its exit tail");
		assertFalse(painter.hasRow("r2"));

		frame(session, listSnapshot(p(90, 0)), painter, NORMAL, DURATION * 2 + 10, view);
		frame(session, listSnapshot(p(90, 0)), painter, NORMAL, DURATION * 3 + 10, view);
		assertTrue(painter.hasRow("r1") && painter.hasRow("r2"),
			"the return restores the full window through the existing transitions");
	}

	private static SpatialInventoryView selectedView(int selected) {
		return new SpatialInventoryView("list", true, rows(3), selected, 0, 3, true, Status.READY, 0, 0)
			.withNavigation(null, true, true);
	}

	@Test
	void selectionChangeDrawsTheOldOverlayFadeAndNewOverlayGrowthWithoutFillingANeverSelectedRow() {
		var session = new SpatialOverlayRenderer.Session();
		var painter = new Painter();
		var radial = listSnapshot(p(90, 0));
		frame(session, radial, painter, NORMAL, 0, selectedView(0));
		frame(session, radial, painter, NORMAL, DURATION, selectedView(0));
		assertEquals(214, painter.overlays("r0").getFirst().color() >>> 24);
		assertTrue(painter.overlays("r1").isEmpty());
		assertTrue(painter.overlays("r2").isEmpty());

		long changed = DURATION + 1;
		frame(session, radial, painter, NORMAL, changed, selectedView(1));
		assertFalse(((SpatialOverlayRenderer.RowPaint) painter.presentRow("r0").state().data()).selected());
		assertEquals(214, painter.overlays("r0").getFirst().color() >>> 24,
			"an unselected payload still draws its previously displayed overlay on the first retarget frame");
		assertTrue(painter.overlays("r1").isEmpty(), "the new overlay starts at zero, not an opaque fill");
		assertFalse(painter.hasStripe("r0"), "the stripe remains foreground-selection conditional");
		assertTrue(painter.hasStripe("r1"));

		frame(session, radial, painter, NORMAL, changed + DURATION / 2, selectedView(1));
		assertEquals(107, painter.overlays("r0").getFirst().color() >>> 24);
		assertEquals(107, painter.overlays("r1").getFirst().color() >>> 24);
		assertEquals(0x303030, painter.overlays("r0").getFirst().color() & 0xFFFFFF, "the RGB stays unchanged");
		assertTrue(painter.overlays("r2").isEmpty(), "zero-channel rows never gain a background");

		frame(session, radial, painter, NORMAL, changed + DURATION, selectedView(1));
		assertTrue(painter.overlays("r0").isEmpty(), "the old draw ends at quantized zero");
		assertEquals(214, painter.overlays("r1").getFirst().color() >>> 24);
		assertTrue(painter.overlays("r2").isEmpty());
	}

	@Test
	void rowOverlayDrawHonorsReducedMotionOpacityZeroAndQuantizedZero() {
		var session = new SpatialOverlayRenderer.Session();
		var painter = new Painter();
		var radial = listSnapshot(p(90, 0));
		frame(session, radial, painter, REDUCED, 0, selectedView(0));
		frame(session, radial, painter, REDUCED, 1, selectedView(1));
		assertTrue(painter.overlays("r0").isEmpty());
		assertEquals(214, painter.overlays("r1").getFirst().color() >>> 24);
		var invisible = new SpatialOverlayRenderer.Style(100, 0, 1, 1, 88, 1, true, false);
		frame(session, radial, painter, invisible, 2, selectedView(0));
		assertTrue(painter.fills.isEmpty(), "target opacity zero admits no row decoration draw");

		var unselected = new SpatialOverlayRenderer.RowPaint(rows(1).getFirst(), 200, 22, false, false);
		SpatialOverlayRenderer.paintRowFills(unselected, NORMAL, 1.0, 0.0001,
			(left, top, right, bottom, color) -> painter.fills.add(new Fill("probe", left, top, right, bottom, color)));
		assertTrue(painter.fills.isEmpty(), "a nonzero double that quantizes to zero draws no overlay");
		SpatialOverlayRenderer.paintRowFills(unselected, invisible, 1.0, 0.84,
			(left, top, right, bottom, color) -> painter.fills.add(new Fill("probe", left, top, right, bottom, color)));
		assertTrue(painter.fills.isEmpty(), "the draw seam itself also guards opacity-zero backgrounds");
	}
}
