package nx.pingwheel.common.render;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.PreciseCaptureRefresh;
import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialMenu;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.config.SpatialSelectorSettings;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.Candidate;
import nx.pingwheel.common.interaction.candidate.CaptureEquivalenceKey;
import nx.pingwheel.common.interaction.candidate.PreciseTargetType;
import nx.pingwheel.common.math.RaycastPolicy;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the HUD's production frame path over recording paint/metrics ports, not a helper clone or GPU. */
class SpatialOverlayRendererFollowerTest {
	private static final long DURATION = 160_000_000L;
	private static final StringSplitter FONT = new StringSplitter((point, style) -> 6.0f);
	private static final SpatialController.Viewport VIEW = new SpatialController.Viewport(400, 300, 0, 0);
	private static final SpatialOverlayRenderer.Style NORMAL = new SpatialOverlayRenderer.Style(100, 100, 1, 1, 88, 1, true, false);
	private static final SpatialOverlayRenderer.Style REDUCED = new SpatialOverlayRenderer.Style(100, 100, 1, 1, 88, 1, true, true);

	private record Draw(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
		SpatialOverlayRenderer.NodeLayout layout) {}
	private record Stroke(SpatialOverlayRenderer.GuideLine line, double alpha) {}
	private static final class Painter implements SpatialOverlayRenderer.FramePainter {
		final List<Draw> draws = new ArrayList<>();
		final List<Stroke> strokes = new ArrayList<>();
		Predicate<SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>> accepts = state -> true;
		public int lineHeight() { return 9; }
		public int width(FormattedText text) { return (int) Math.ceil(FONT.stringWidth(text)); }
		public StringSplitter splitter() { return FONT; }
		public boolean paint(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
			SpatialOverlayRenderer.NodeLayout layout, SpatialOverlayRenderer.Style style) {
			draws.add(new Draw(state, layout));
			return accepts.test(state);
		}
		public void connector(SpatialOverlayRenderer.GuideLine line, double alpha) { strokes.add(new Stroke(line, alpha)); }
		void clear() { draws.clear(); strokes.clear(); }
		SpatialOverlayRenderer.ChromePaint chrome() {
			return (SpatialOverlayRenderer.ChromePaint) draws.stream().filter(draw -> draw.state().data() instanceof SpatialOverlayRenderer.ChromePaint)
				.findFirst().orElseThrow().state().data();
		}
		Draw follower() {
			return draws.stream().filter(draw -> draw.state().present() && draw.state().data() instanceof SpatialOverlayRenderer.NodePaint node
				&& node.follower() != null).findFirst().orElseThrow();
		}
	}

	private static SpatialController.Point point(double x, double y) { return new SpatialController.Point(x, y); }
	private static SpatialController.ChoiceView choice(String id, String action, boolean branch, boolean back, boolean disabled, boolean reserved) {
		return new SpatialController.ChoiceView(id, id, action, back, disabled, reserved, branch, true, 0, 180, null);
	}
	private static SpatialController.ChoiceView leaf(String id) { return choice(id, "action:" + id, false, false, false, false); }
	private static SpatialController.MenuView menu(String id, SpatialController.Point origin, String focus, List<SpatialController.ChoiceView> choices) {
		return new SpatialController.MenuView(id, origin, point(0, 0), focus, choices);
	}
	private static SpatialController.Snapshot snapshot(SpatialController.ChoiceView choice, SpatialController.Point pointer) {
		return snapshot(List.of(menu("root", point(0, 0), choice.id(), List.of(choice))), pointer);
	}
	private static SpatialController.Snapshot snapshot(List<SpatialController.MenuView> menus, SpatialController.Point pointer) {
		return new SpatialController.Snapshot(true, pointer, List.of(point(0, 0), pointer), menus.getLast().focusId(), menus, 0, 12);
	}
	private static void frame(SpatialOverlayRenderer.Session session, SpatialController.Snapshot radial, Painter painter,
		SpatialOverlayRenderer.Style style, long time, SpatialController.Viewport viewport) {
		painter.clear();
		session.drawFrame(radial, null, choice -> Component.literal(choice.label()), choice -> choice.id().startsWith("precise:")
			? Component.literal("\uD83D\uDE00".repeat(33)) : null, style, time, viewport, 1000, 1000, painter);
	}

	@Test void onlyEnabledActiveActionAndBranchCapabilitiesFollowWithoutWaitingForDwell() {
		var choices = List.of(leaf("leaf"), choice("property", "property:health", true, false, false, false),
			choice("pure-group", null, true, false, false, false), choice("back", "action", true, true, false, false),
			choice("disabled", "action", true, false, true, false), choice("reserved", "action", true, false, false, true),
			choice("inert", null, false, false, false, false));
		for (int index = 0; index < choices.size(); index++) {
			var session = new SpatialOverlayRenderer.Session(); var painter = new Painter();
			frame(session, snapshot(choices.get(index), point(90, -70)), painter, REDUCED, 0, VIEW);
			assertEquals(index < 3, painter.chrome().pointerHidden(), choices.get(index).id());
			assertEquals(index < 3 ? 1 : 0, painter.strokes.size());
			assertEquals(List.of(point(0, 0), point(90, -70)), painter.chrome().snapshot().trail(), "hiding never clears trail");
		}
		var controller = new SpatialController(SpatialMenu.of("real", SpatialMenu.Choice.branch("group", "group",
			SpatialMenu.of("child", SpatialMenu.Choice.leaf("child-leaf", "child", "action"))).withSector(90, 180)),
			new SpatialController.Tuning(12, 20, 1000, false, 500));
		controller.start(0); controller.movePhysical(80, 0, 1);
		assertEquals(1, controller.snapshot().menus().size(), "dwell has not entered child");
		var session = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		frame(session, controller.snapshot(), painter, REDUCED, 0, VIEW);
		assertTrue(painter.chrome().pointerHidden(), "capability qualifies before dwell completion");
	}

	@Test void deadzoneAncestorsInventoryAndInactiveExitCannotHideThePointer() {
		var painter = new Painter(); var session = new SpatialOverlayRenderer.Session();
		frame(session, snapshot(leaf("a"), point(0, 0)), painter, REDUCED, 0, VIEW);
		assertFalse(painter.chrome().pointerHidden()); assertTrue(painter.strokes.isEmpty());
		var ancestors = List.of(menu("root", point(0, 0), "a", List.of(leaf("a"))),
			menu("list-menu", point(90, 0), null, List.of()));
		var radial = snapshot(ancestors, point(90, 40));
		var inventory = new SpatialInventoryView("list", true, List.of(new SpatialInventoryView.Row("row", "Inventory Item", 1L,
			null, SpatialInventoryView.Status.READY)), 0, 0, 3, true, SpatialInventoryView.Status.READY, 90, 0);
		painter.clear();
		session.drawFrame(radial, inventory, c -> Component.literal(c.label()), null, REDUCED, 1, VIEW, 400, 300, painter);
		assertTrue(painter.draws.stream().anyMatch(draw -> draw.state().data() instanceof SpatialOverlayRenderer.RowPaint));
		assertFalse(painter.chrome().pointerHidden()); assertTrue(painter.strokes.isEmpty()); assertTrue(session.paintedChoiceIds().isEmpty());
		frame(session, snapshot(leaf("a"), point(90, 40)), painter, REDUCED, 2, VIEW);
		assertTrue(painter.chrome().pointerHidden());
		frame(session, null, painter, NORMAL, 3, VIEW);
		assertFalse(painter.chrome().pointerHidden()); assertTrue(painter.strokes.isEmpty()); assertTrue(session.paintedChoiceIds().isEmpty());
	}

	@Test void everyActualIntermediatePoseTracksCurrentRayAndConnectorEndsOnActualScaledTitle() {
		var painter = new Painter(); var session = new SpatialOverlayRenderer.Session();
		var view = new SpatialController.Viewport(401, 301, -37.25, 16.625);
		var origin = point(70.125, -30.5);
		for (int frame = 0; frame <= 160; frame++) {
			double angle = frame * 0.047;
			double radius = 100 + frame * 0.4;
			var pointer = point(origin.x() + Math.cos(angle) * radius, origin.y() + Math.sin(angle) * radius);
			var snapshot = snapshot(List.of(menu("root", point(0, 0), null, List.of(leaf("ancestor"))),
				menu("precise", origin, "precise:block", List.of(leaf("precise:block"), leaf("sibling")))), pointer);
			frame(session, snapshot, painter, NORMAL, frame * 8_000_000L, view);
			if (frame == 0) { assertTrue(session.paintedChoiceIds().isEmpty()); continue; }
			var draw = painter.follower(); var state = draw.state();
			double ox = view.centerX() + view.viewX() + origin.x();
			double oy = view.centerY() + view.viewY() + origin.y();
			double px = view.centerX() + view.viewX() + pointer.x(); double py = view.centerY() + view.viewY() + pointer.y();
			assertEquals(0, (state.x() - ox) * (py - oy) - (state.y() - oy) * (px - ox), 1e-8,
				"angle is never Cartesian-eased");
			assertTrue((state.x() - ox) * (px - ox) + (state.y() - oy) * (py - oy) > 0);
			assertTrue(painter.chrome().pointerHidden());
			if (frame >= 20) {
				assertEquals(1, state.alpha(), "moving radius/direction never restarts the appearance fade");
				assertEquals(SpatialOverlayRenderer.NODE_SELECTED_SCALE, state.scale());
			}
			var line = painter.strokes.getFirst().line();
			assertEquals(12, Math.hypot(line.x1() - ox, line.y1() - oy), 1e-9);
			assertEquals(0, (line.x2() - line.x1()) * (py - oy) - (line.y2() - line.y1()) * (px - ox), 1e-8);
			double scale = (float) state.scale();
			double lx = (line.x2() - state.x()) / scale; double ly = (line.y2() - state.y()) / scale;
			var layout = draw.layout();
			assertTrue(lx >= layout.left() - 1e-8 && lx <= layout.right() + 1e-8);
			assertTrue(ly >= layout.top() - 1e-8 && ly <= layout.bottom() + 1e-8);
			assertTrue(Math.min(Math.min(Math.abs(lx - layout.left()), Math.abs(lx - layout.right())),
				Math.min(Math.abs(ly - layout.top()), Math.abs(ly - layout.bottom()))) < 1e-8, "near actual title boundary, not target pose or preview");
			assertTrue((line.x2() - state.x()) * (px - ox) + (line.y2() - state.y()) * (py - oy) < 0, "near not far edge");
			assertEquals(state.alpha(), painter.strokes.getFirst().alpha());
			assertEquals(32, layout.detail().text().getString().codePointCount(0, layout.detail().text().getString().length()));
			assertTrue(layout.detail().top() > layout.bottom(), "outside preview shares node pose and scale");
			var guide = painter.draws.stream().filter(value -> value.state().key().owner().equals("precise")
				&& value.state().data() instanceof SpatialOverlayRenderer.GuidePaint).findFirst().orElseThrow().state();
			assertEquals(ox, guide.x()); assertEquals(oy, guide.y()); assertEquals(1, guide.scale());
			assertTrue(session.retainedSize() < 20, "continuous motion keeps one stable follow identity, no frame history");
		}
	}

	@Test void handoffCrossfadesNoninteractiveRestingPoseThenReturnsHomeAndReopenResetIsBounded() {
		var painter = new Painter(); var session = new SpatialOverlayRenderer.Session();
		var choices = List.of(leaf("a"), leaf("b"));
		var resting = snapshot(List.of(menu("root", point(0, 0), null, choices)), point(0, 0));
		frame(session, resting, painter, REDUCED, 0, VIEW);
		var oldHome = painter.draws.stream().filter(draw -> draw.state().key().entry().equals("a") && draw.layout() != null).findFirst().orElseThrow().state();
		var a = snapshot(List.of(menu("root", point(0, 0), "a", choices)), point(80, -90));
		frame(session, a, painter, NORMAL, 1, VIEW);
		assertFalse(painter.chrome().pointerHidden(), "transparent new follower cannot hide pointer even with visible outgoing duplicate");
		assertFalse(session.paintedChoiceIds().contains("a"));
		var outgoing = painter.draws.stream().filter(draw -> draw.state().key().entry().equals("a") && draw.layout() != null).findFirst().orElseThrow().state();
		assertFalse(outgoing.present()); assertEquals(oldHome.x(), outgoing.x()); assertEquals(oldHome.y(), outgoing.y());
		frame(session, a, painter, NORMAL, DURATION + 1, VIEW);
		assertTrue(painter.chrome().pointerHidden()); assertTrue(session.paintedChoiceIds().contains("a"));
		var b = snapshot(List.of(menu("root", point(0, 0), "b", choices)), point(-90, 80));
		frame(session, b, painter, NORMAL, DURATION + 2, VIEW);
		assertFalse(painter.chrome().pointerHidden()); assertFalse(session.paintedChoiceIds().contains("b"));
		frame(session, b, painter, NORMAL, DURATION * 2 + 2, VIEW);
		assertTrue(painter.chrome().pointerHidden());
		frame(session, resting, painter, NORMAL, DURATION * 2 + 3, VIEW);
		assertFalse(painter.chrome().pointerHidden()); assertTrue(painter.strokes.isEmpty());
		frame(session, resting, painter, NORMAL, DURATION * 3 + 3, VIEW);
		assertTrue(painter.draws.stream().noneMatch(draw -> !draw.state().present()));
		frame(session, a, painter, REDUCED, DURATION * 3 + 4, VIEW);
		assertEquals(280, painter.follower().state().x()); assertEquals(60, painter.follower().state().y());
		frame(session, null, painter, NORMAL, DURATION * 3 + 5, VIEW);
		frame(session, a, painter, NORMAL, DURATION * 3 + 6, VIEW);
		assertTrue(session.retainedSize() < 12, "quick reopen reuses bounded keys");
		session.reset(); assertEquals(0, session.retainedSize()); assertTrue(session.paintedChoiceIds().isEmpty());
		frame(session, a, painter, NORMAL, 0, VIEW); assertTrue(painter.draws.isEmpty(), "new clock and no old pose after abort");
		frame(session, null, painter, REDUCED, 1, VIEW); assertFalse(session.isAnimating());
	}

	@Test void movingRadiusMakesProgressForHundredsOfRetargetsAndSettlesExactlyInOneInterval() {
		var session = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		frame(session, snapshot(leaf("a"), point(90, 0)), painter, REDUCED, 0, VIEW);
		for (int frame = 1; frame <= 400; frame++) {
			frame(session, snapshot(leaf("a"), point(100 + frame * 0.25, 0)), painter, NORMAL, frame * 1_000_000L, VIEW);
			assertTrue(session.retainedSize() <= 4);
		}
		double radius = painter.follower().state().x() - VIEW.centerX();
		assertTrue(radius > 170 && radius < 200, "finite transitions retargeting each millisecond cannot freeze near initial radius");
		frame(session, snapshot(leaf("a"), point(200, 0)), painter, NORMAL, 400_000_000L + DURATION, VIEW);
		assertEquals(400, painter.follower().state().x());
		frame(session, null, painter, NORMAL, 600_000_000L, VIEW);
		frame(session, null, painter, NORMAL, 600_000_000L + DURATION, VIEW);
		assertEquals(0, session.retainedSize());
	}

	@Test void failedQuantizedOrTransparentCurrentPaintKeepsPointerAndCannotAcknowledge() {
		var session = new SpatialOverlayRenderer.Session(); var painter = new Painter(); var radial = snapshot(leaf("a"), point(90, -70));
		frame(session, radial, painter, NORMAL, 0, VIEW);
		frame(session, radial, painter, NORMAL, 1, VIEW);
		assertFalse(painter.chrome().pointerHidden()); assertTrue(session.paintedChoiceIds().isEmpty()); assertTrue(painter.strokes.isEmpty());
		painter.accepts = state -> !(state.data() instanceof SpatialOverlayRenderer.NodePaint);
		frame(session, radial, painter, REDUCED, DURATION, VIEW);
		assertFalse(painter.chrome().pointerHidden()); assertTrue(session.paintedChoiceIds().isEmpty());
		painter.accepts = state -> true;
		frame(session, radial, painter, new SpatialOverlayRenderer.Style(0, 0, 1, 1, 88, 1, true, true), DURATION + 1, VIEW);
		assertFalse(painter.chrome().pointerHidden()); assertTrue(session.paintedChoiceIds().isEmpty());
		assertTrue(painter.chrome().snapshot().trail().size() > 1);
		frame(session, radial, painter, new SpatialOverlayRenderer.Style(0, 20, 1, 1, 88, 1, false, true), DURATION + 2, VIEW);
		assertTrue(painter.chrome().pointerHidden()); assertEquals(1, painter.strokes.getFirst().alpha(), "connector ignores both opacity preferences");
	}

	@Test void capacityOmittedFollowerAndOutgoingDuplicatesCannotHideOrAcknowledge() {
		var session = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		var choices = java.util.stream.IntStream.range(0, 270).mapToObj(index -> leaf("choice" + index)).toList();
		frame(session, snapshot(List.of(menu("root", point(0, 0), "choice0", choices)), point(90, 0)), painter, REDUCED, 0, VIEW);
		assertTrue(painter.chrome().pointerHidden());
		frame(session, snapshot(List.of(menu("root", point(0, 0), "choice269", choices)), point(90, 0)), painter, NORMAL, 1, VIEW);
		assertFalse(painter.chrome().pointerHidden()); assertFalse(session.paintedChoiceIds().contains("choice269"));
		assertFalse(session.paintedChoiceIds().contains("choice0"), "unpainted returning home and outgoing follow cannot authorize");
		assertTrue(painter.strokes.isEmpty()); assertEquals(512, session.retainedSize());
	}

	@Test void overlapOmitsCoveredConnectorAndFractionalViewportConfinementReachesEveryEdge() {
		var session = new SpatialOverlayRenderer.Session(); var painter = new Painter();
		frame(session, snapshot(leaf("wide-title"), point(13, 0)), painter, REDUCED, 0, VIEW);
		assertTrue(painter.chrome().pointerHidden()); assertTrue(painter.strokes.isEmpty(), "title overlaps circle, no reversed or covered stroke");
		var view = new SpatialController.Viewport(401, 301, -13.375, 27.625);
		for (var raw : List.of(point(-10000, 0), point(10000, 0), point(0, -10000), point(0, 10000))) {
			var pointer = view.confine(raw);
			frame(session, snapshot(leaf("a"), pointer), painter, REDUCED, 1, view);
			var state = painter.follower().state();
			assertEquals(view.centerX() + view.viewX() + pointer.x(), state.x(), 1e-9);
			assertEquals(view.centerY() + view.viewY() + pointer.y(), state.y(), 1e-9);
			assertTrue(state.x() >= 0 && state.x() <= 400 && state.y() >= 0 && state.y() <= 300);
			assertTrue(state.x() == 0 || state.x() == 400 || state.y() == 0 || state.y() == 300);
			var chromeState = painter.draws.stream().filter(draw -> draw.state().data() instanceof SpatialOverlayRenderer.ChromePaint).findFirst().orElseThrow().state();
			assertEquals(Math.round(state.x()), Math.round(chromeState.x() + pointer.x()));
			assertEquals(Math.round(state.y()), Math.round(chromeState.y() + pointer.y()));
		}
	}

	@Test void realPreciseReleaseUsesOnlySuccessfullyPaintedPublicationNotNewFollowerOrOutgoingDuplicate() {
		for (boolean firstPaint : List.of(false, true)) {
			var token = new ActiveInteraction().begin(); var level = new Object();
			var ordinary = captured("ordinary", 1);
			var settings = new SpatialSelectorSettings.Snapshot(12, 20, 120, 88, new BigDecimal("0.5"), false, 250, true, false);
			var selector = new SpatialSelectorSession<String>(ordinary, Map.of(), settings,
				new SpatialSelectorSession.ContentFence(token.sequence(), 42, 53, "ordinary"), (target, fence) -> null,
				new SpatialSelectorSession.ListGeometry(3, 180, 17, 23, 19, 85));
			selector.beginLivePrecise(token, level); selector.open(0);
			focus(selector, "precise", 1); selector.tick(121);
			assertTrue(selector.isPreciseBranchActive());
			selector.updatePrecise(publication(token, level, 1, 10), 122);
			var renderer = new SpatialOverlayRenderer.Session(); var painter = new Painter();
			if (!firstPaint) {
				// Visible resting paint is deliberately not presented to the selector.
				// On focus its outgoing duplicate must not acknowledge the transparent follower.
				frame(renderer, selector.snapshot().radial(), painter, REDUCED, 0, VIEW);
				assertTrue(renderer.paintedChoiceIds().contains("precise:block"));
			}
			focus(selector, "precise:block", 123);
			var initial = selector.snapshot();
			frame(renderer, initial.radial(), painter, firstPaint ? REDUCED : NORMAL, 1, VIEW);
			var painted = selector.markPresented(initial, renderer.paintedChoiceIds());
			assertEquals(firstPaint, painted.isPresent() && painted.orElseThrow().choices().containsKey("precise:block"));
			if (firstPaint) assertTrue(painter.chrome().pointerHidden());
			else {
				assertFalse(painter.chrome().pointerHidden());
				assertTrue(painter.draws.stream().anyMatch(draw -> !draw.state().present() && draw.state().alpha() > 0
					&& draw.state().data() instanceof SpatialOverlayRenderer.NodePaint node && node.choice().id().equals("precise:block")),
					"visible outgoing duplicate really painted but cannot authorize the new follower");
			}
			selector.updatePrecise(publication(token, level, 2, 20), 124);
			var newer = selector.snapshot();
			painter.accepts = state -> !(state.data() instanceof SpatialOverlayRenderer.NodePaint);
			frame(renderer, newer.radial(), painter, REDUCED, 1, VIEW);
			assertFalse(painter.chrome().pointerHidden()); assertTrue(renderer.paintedChoiceIds().isEmpty());
			assertTrue(selector.markPresented(newer, renderer.paintedChoiceIds()).isEmpty());
			var intent = selector.releaseIntent(125);
			if (firstPaint) {
				var create = assertInstanceOf(SelectorIntent.CreateTarget.class, intent);
				assertSame(painted.orElseThrow().choices().get("precise:block"), create.candidate());
				assertEquals(1, create.presentationRevision());
				assertEquals(10, ((Target.BlockTarget) create.candidate().resolvedTarget().target()).x());
			} else assertInstanceOf(SelectorIntent.None.class, intent);
		}
	}

	private static SpatialSelectorSession.CapturedTarget captured(String id, int x) {
		var target = new Target.BlockTarget("minecraft:overworld", x, 2, 3, "minecraft:stone");
		return new SpatialSelectorSession.CapturedTarget(id, new ResolvedTarget(target, TargetTypeCatalog.builtIn().findById("block").orElseThrow()),
			Optional.empty(), Optional.of(new WorldVector(x, 2, 3)));
	}
	private static PreciseCaptureRefresh.Published publication(InteractionToken token, Object level, long revision, int x) {
		var inputs = new PreciseCaptureRefresh.Inputs(token, level, CapturedRay.defaultRay(), RaycastPolicy.from(false, false, false), 10, 100);
		var value = captured("capture", x).resolvedTarget();
		var candidate = new Candidate(0, value, new WorldVector(x, 2, 3), 1, Optional.empty(), Optional.empty(), CaptureEquivalenceKey.nativeTarget(value.target()));
		var slots = new EnumMap<PreciseTargetType, PreciseCaptureRefresh.Slot>(PreciseTargetType.class);
		for (var type : PreciseTargetType.values()) slots.put(type, new PreciseCaptureRefresh.Slot(revision, inputs,
			type == PreciseTargetType.BLOCK ? PreciseCaptureRefresh.Outcome.available(candidate) : PreciseCaptureRefresh.Outcome.missing()));
		return new PreciseCaptureRefresh.Published(revision, revision, inputs, slots);
	}
	private static void focus(SpatialSelectorSession<?> selector, String id, long now) {
		var frame = selector.snapshot().radial(); var menu = frame.menus().getLast();
		var choice = menu.choices().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
		double angle = Math.toRadians(choice.startDegrees() + choice.spanDegrees() / 2);
		selector.moveGui(menu.origin().x() + Math.sin(angle) * 80 - frame.pointer().x(),
			menu.origin().y() - Math.cos(angle) * 80 - frame.pointer().y(), now);
	}
}
