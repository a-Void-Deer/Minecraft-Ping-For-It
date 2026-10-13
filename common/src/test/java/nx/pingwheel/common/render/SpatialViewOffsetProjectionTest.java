package nx.pingwheel.common.render;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.InventoryListModel;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialMenu;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.config.SpatialSelectorSettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.render.SpatialInventoryView.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Projection invariants of the selector's single rigid view translation, driven
 * through the real headless controller and selector session: every active menu
 * origin lands on the GUI center across push, nested push, return and root; one
 * sampled translation keeps pointer/trail/menu vectors; an inactive frame
 * freezes the exit translation; the inventory list centers on its active origin
 * and an item submenu keeps the selected row's logical anchor. Pure headless
 * geometry/offset seams: no GuiGraphics, GPU or game client is involved.
 */
class SpatialViewOffsetProjectionTest {

	private static final long DURATION = 1000L;
	private static final double GUI_WIDTH = 400.0;
	private static final double GUI_HEIGHT = 300.0;
	private static final int FONT_LINE_HEIGHT = 9;
	private static final SpatialSelectorSession.ListGeometry GEOMETRY =
		new SpatialSelectorSession.ListGeometry(4, 180, 17, 23, 19, 85);

	@Test
	void intermediatePanPublishesOneProjectionForConfinementPaintAndRelease() {
		var controller = controller();
		var paint = new SpatialOverlayRenderer.Session();
		controller.constrainPointer(paint.prepareViewport(controller.snapshot(), 400, 300, SpatialOverlayRenderer.Style.NATIVE, 0), 0);
		controller.enterExternal(child("child"), -60, 0, 1);
		controller.constrainPointer(paint.prepareViewport(controller.snapshot(), 400, 300, SpatialOverlayRenderer.Style.NATIVE, 0), 1);
		controller.movePhysical(Double.MAX_VALUE, 0, 2);
		assertEquals(new SpatialController.Point(199, 0), controller.snapshot().pointer());
		var before = controller.snapshot();
		var quarter = paint.prepareViewport(before, 400, 300, SpatialOverlayRenderer.Style.NATIVE, 40_000_000L);
		assertEquals(9.375, quarter.viewX(), 1e-9, "40ms is one quarter of the actual 160ms pan");
		assertEquals(before, controller.snapshot(), "sampling render state cannot mutate interaction");
		controller.constrainPointer(quarter, 40);
		assertEquals(new SpatialController.Point(189.625, 0), controller.snapshot().pointer());
		assertEquals(399, quarter.centerX() + quarter.viewX() + controller.snapshot().pointer().x(), 1e-9);
		var half = paint.prepareViewport(controller.snapshot(), 400, 300, SpatialOverlayRenderer.Style.NATIVE, 80_000_000L);
		assertEquals(30, half.viewX(), 1e-9);
		controller.constrainPointer(half, 80);
		var painted = controller.snapshot();
		assertEquals(new SpatialController.Point(169, 0), painted.pointer());
		assertEquals("child:back", painted.focusId());
		paint.drawFrame(null, painted, null, null, null, SpatialOverlayRenderer.Style.NATIVE, 160_000_000L, half);
		assertEquals(painted, controller.snapshot(), "paint is not an interaction tick");
		assertEquals(half, paint.lastViewport(400, 300), "paint/release read the published pan, not its future endpoint");
		var resized = paint.lastViewport(200, 100);
		assertEquals(30, resized.viewX());
		controller.constrainPointer(resized, 81);
		assertEquals(new SpatialController.Point(69, 0), controller.snapshot().pointer());
		assertEquals(199, 100 + 30 + controller.snapshot().pointer().x());
		var result = assertInstanceOf(SpatialController.Release.NoAction.class, controller.release(82));
		assertEquals("child:back", result.choiceId());
		assertEquals(SpatialController.Release.NoAction.Reason.NAVIGATION, result.reason());
	}

	@Test
	void frameNavigationRetargetsFromItsSingleSampleAndReducedMotionStillSnaps() {
		for (boolean reduced : List.of(false, true)) {
			var controller = controller();
			var paint = new SpatialOverlayRenderer.Session();
			var style = new SpatialOverlayRenderer.Style(100, 1, 1, true, reduced);
			paint.prepareViewport(controller.snapshot(), 400, 300, style, 0);
			controller.enterExternal(child("child"), -60, 0, 1);
			var entered = paint.retargetViewport(controller.snapshot(), style, 0);
			assertEquals(reduced ? 60 : 0, entered.viewX());
			var sampled = paint.prepareViewport(controller.snapshot(), 400, 300, style, 80_000_000L);
			assertEquals(reduced ? 60 : 30, sampled.viewX());
			controller.back(80);
			var returned = paint.retargetViewport(controller.snapshot(), style, 80_000_000L);
			assertEquals(new SpatialController.Point(-60, 0), SpatialOverlayRenderer.activeOrigin(controller.snapshot()),
				"user return aligns the root with the unchanged trigger pointer");
			assertEquals(reduced ? 60 : 30, returned.viewX(), "normal return re-aims from this frame's displayed offset");
			assertEquals(returned, paint.lastViewport(400, 300));
		}
	}

	@Test
	void pointerChromeUsesSnapshotCenterNotResizeInterpolatedCenter() {
		var snapshot = new SpatialController.Snapshot(true, new SpatialController.Point(69, 0), List.of(), null, List.of(), 0);
		var chrome = new SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>(
			new SpatialOverlayRenderer.VisualKey("chrome", "radial", ""), 200, 150, 1, 1, true,
			new SpatialOverlayRenderer.ChromePaint(snapshot));
		var viewport = new SpatialController.Viewport(200, 100, 30, 0);
		var paint = SpatialOverlayRenderer.alignChrome(chrome, viewport);
		assertEquals(130, paint.x());
		assertEquals(50, paint.y());
		assertEquals(199, paint.x() + snapshot.pointer().x());
		assertEquals(50, paint.y() + snapshot.pointer().y());
		assertSame(chrome.data(), paint.data());
		var outgoing = new SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>(
			chrome.key(), 200, 150, 1, 1, false, chrome.data());
		assertSame(outgoing, SpatialOverlayRenderer.alignChrome(outgoing, viewport), "exit geometry is detached paint only");
	}

	@Test
	void fractionalPanRoundsTheDisplayedPointerCenterInsideAllFourEdges() {
		var viewport = new SpatialController.Viewport(101, 81, 9.375, -7.625);
		var controller = controller();
		controller.constrainPointer(viewport, 0);
		for (double[] edge : List.of(new double[] {-1000, 0, 0, 32.875}, new double[] {1000, 0, 100, 32.875},
			new double[] {0, -1000, 59.875, 0}, new double[] {0, 1000, 59.875, 80})) {
			controller.rebase(0, 0, 0);
			controller.movePhysical(edge[0], edge[1], 1);
			var snapshot = controller.snapshot();
			var chrome = new SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>(
				new SpatialOverlayRenderer.VisualKey("chrome", "radial", ""), 200, 150, 1, 1, true,
				new SpatialOverlayRenderer.ChromePaint(snapshot));
			var paint = SpatialOverlayRenderer.alignChrome(chrome, viewport);
			assertEquals(edge[2], paint.x() + snapshot.pointer().x(), 1e-9);
			assertEquals(edge[3], paint.y() + snapshot.pointer().y(), 1e-9);
			assertTrue(Math.round(paint.x() + snapshot.pointer().x()) >= 0 && Math.round(paint.x() + snapshot.pointer().x()) <= 100);
			assertTrue(Math.round(paint.y() + snapshot.pointer().y()) >= 0 && Math.round(paint.y() + snapshot.pointer().y()) <= 80);
		}
	}

	private static SpatialMenu controllerRoot() {
		SpatialMenu types = SpatialMenu.of("content:rpm",
			SpatialMenu.Choice.leaf("content:rpm:attention", "attention", "ping:attention"),
			SpatialMenu.Choice.leaf("content:rpm:danger", "danger", "ping:danger"));
		SpatialMenu content = SpatialMenu.of("content",
			SpatialMenu.Choice.branch("content:rpm", "rpm", types),
			SpatialMenu.Choice.leaf("content:items", "items", "ping:attention"));

		return SpatialMenu.of("root",
			SpatialMenu.Choice.leaf("danger", "danger", "ping:danger").withSector(0.0, 55.0),
			SpatialMenu.Choice.reserved("reserved-ne", "reserved").withSector(45.0, 35.0),
			SpatialMenu.Choice.branch("content", "content", content).withSector(90.0, 55.0),
			SpatialMenu.Choice.reserved("reserved-se", "reserved").withSector(135.0, 35.0),
			SpatialMenu.Choice.leaf("cancel-marker", "cancel", "action:clear").withSector(180.0, 55.0),
			SpatialMenu.Choice.leaf("precise", "precise", "ui:precise").withSector(225.0, 35.0),
			SpatialMenu.Choice.leaf("intent", "intent", "ping:attention").withSector(270.0, 55.0),
			SpatialMenu.Choice.leaf("settings", "settings", "ui:settings").withSector(315.0, 35.0));
	}

	private static SpatialController controller() {
		var controller = new SpatialController(controllerRoot(),
			new SpatialController.Tuning(36.0, 110.0, 250L, false, 0L));
		controller.start(0L);
		return controller;
	}

	private static SpatialMenu child(String id) {
		return SpatialMenu.of(id, SpatialMenu.Choice.leaf(id + ":one", "one", "a:one"));
	}

	private static void assertCenteredAt(SpatialViewOffset offset, SpatialController.Point origin, long now) {
		SpatialOverlayRenderer.sampleViewOffset(offset, origin, now, false);
		SpatialOverlayRenderer.sampleViewOffset(offset, origin, now + DURATION, false);
		assertEquals(0.0, origin.x() + offset.x(), 1.0e-9);
		assertEquals(0.0, origin.y() + offset.y(), 1.0e-9);
	}

	@Test
	void everyActiveMenuOriginIsCenteredAcrossPushNestedReturnAndRoot() {
		var controller = controller();
		var offset = new SpatialViewOffset(DURATION);

		var root = SpatialOverlayRenderer.activeOrigin(controller.snapshot());
		assertNotNull(root);
		assertEquals(0.0, root.x());
		assertEquals(0.0, root.y());
		assertCenteredAt(offset, root, 0L);

		controller.enterExternal(child("child"), 30.0, -40.0, DURATION);
		var child = SpatialOverlayRenderer.activeOrigin(controller.snapshot());
		assertEquals(new SpatialController.Point(30.0, -40.0), child);
		assertCenteredAt(offset, child, DURATION);

		controller.enterExternal(child("grandchild"), 90.0, 25.0, 2 * DURATION);
		var grandchild = SpatialOverlayRenderer.activeOrigin(controller.snapshot());
		assertEquals(new SpatialController.Point(90.0, 25.0), grandchild);
		assertCenteredAt(offset, grandchild, 2 * DURATION);

		assertTrue(controller.back(3 * DURATION));
		var returnedChild = SpatialOverlayRenderer.activeOrigin(controller.snapshot());
		assertEquals(new SpatialController.Point(90.0, 25.0), returnedChild, "user return aligns the child with the pointer");
		assertEquals(new SpatialController.Point(60.0, 65.0), controller.snapshot().menus().getFirst().origin(),
			"the retained root receives the same rigid translation");
		assertCenteredAt(offset, returnedChild, 3 * DURATION);

		assertTrue(controller.back(4 * DURATION));
		var returnedRoot = SpatialOverlayRenderer.activeOrigin(controller.snapshot());
		assertEquals(new SpatialController.Point(90.0, 25.0), returnedRoot, "the next user return aligns the root at that pointer");
		assertCenteredAt(offset, returnedRoot, 4 * DURATION);
		assertEquals(-90.0, offset.x());
		assertEquals(-25.0, offset.y());
	}

	@Test
	void anInactiveFrameKeepsTheLastDisplayedTranslationForTheExitTail() {
		var controller = controller();
		var offset = new SpatialViewOffset(DURATION);
		var root = SpatialOverlayRenderer.activeOrigin(controller.snapshot());
		SpatialOverlayRenderer.sampleViewOffset(offset, root, 0L, false);

		controller.enterExternal(child("child"), 60.0, 0.0, 0L);
		var child = SpatialOverlayRenderer.activeOrigin(controller.snapshot());
		SpatialOverlayRenderer.sampleViewOffset(offset, child, 0L, false);
		SpatialOverlayRenderer.sampleViewOffset(offset, child, DURATION / 2, false);
		double displayed = offset.x();
		assertTrue(displayed < 0.0 && displayed > -60.0);

		SpatialOverlayRenderer.sampleViewOffset(offset, null, 10 * DURATION, false);
		assertEquals(displayed, offset.x(), 1.0e-12, "an exit keeps the last displayed translation");
		SpatialOverlayRenderer.sampleViewOffset(offset, null, 20 * DURATION, false);
		assertEquals(displayed, offset.x(), 1.0e-12);
		assertEquals(0.0, offset.y(), 1.0e-12);
	}

	@Test
	void quickReopenAfterAnInactiveFrameReaimsFromTheLastDisplayedTranslation() {
		var controller = controller();
		var offset = new SpatialViewOffset(160L);
		var root = SpatialOverlayRenderer.activeOrigin(controller.snapshot());
		SpatialOverlayRenderer.sampleViewOffset(offset, root, 0L, false);

		controller.enterExternal(child("child"), 60.0, 0.0, 0L);
		var child = SpatialOverlayRenderer.activeOrigin(controller.snapshot());
		SpatialOverlayRenderer.sampleViewOffset(offset, child, 0L, false);
		SpatialOverlayRenderer.sampleViewOffset(offset, child, 80L, false);
		assertEquals(-30.0, offset.x(), 1.0e-12);

		controller.cancel();
		SpatialOverlayRenderer.sampleViewOffset(offset, SpatialOverlayRenderer.activeOrigin(controller.snapshot()), 100L, false);
		assertEquals(-30.0, offset.x(), 1.0e-12, "an inactive frame does not advance the visible offset");
		controller.start(120L);
		root = SpatialOverlayRenderer.activeOrigin(controller.snapshot());
		SpatialOverlayRenderer.sampleViewOffset(offset, root, 120L, false);
		assertEquals(-30.0, offset.x(), 1.0e-12, "reopening preserves the last displayed offset in that frame");

		SpatialOverlayRenderer.sampleViewOffset(offset, root, 160L, false);
		assertEquals(-25.3125, offset.x(), 1.0e-12, "the new root aim advances only from the reopen time");
	}

	@Test
	void oneSampledTranslationKeepsPointerTrailAndMenuVectors() {
		var controller = controller();
		controller.enterExternal(child("child"), 30.0, -40.0, 7L);
		controller.movePhysical(10.0, 5.0, 8L);
		controller.movePhysical(-4.0, 2.0, 9L);
		var snapshot = controller.snapshot();
		var offset = new SpatialViewOffset(DURATION);
		var origin = SpatialOverlayRenderer.activeOrigin(snapshot);
		SpatialOverlayRenderer.sampleViewOffset(offset, origin, 0L, false);
		SpatialOverlayRenderer.sampleViewOffset(offset, origin, DURATION / 2, false);
		assertTrue(offset.x() != 0.0 || offset.y() != 0.0, "a non-trivial translation is actually applied");

		var pointer = snapshot.pointer();
		var trail = snapshot.trail();
		assertTrue(trail.size() >= 2);
		assertEquals(pointer.x() - trail.getLast().x(),
			projectedX(pointer, offset) - projectedX(trail.getLast(), offset), 1.0e-12);
		assertEquals(pointer.y() - trail.getFirst().y(),
			projectedY(pointer, offset) - projectedY(trail.getFirst(), offset), 1.0e-12);

		var menu = snapshot.menus().getLast();
		assertEquals(menu.origin().x() - menu.parentOrigin().x(),
			projectedX(menu.origin(), offset) - projectedX(menu.parentOrigin(), offset), 1.0e-12);
		assertEquals(menu.origin().y() - menu.parentOrigin().y(),
			projectedY(menu.origin(), offset) - projectedY(menu.parentOrigin(), offset), 1.0e-12);
	}

	@Test
	void inventoryListCentersAndItemSubmenuKeepsTheSelectedRowAnchor() {
		var session = openedList();
		var offset = new SpatialViewOffset(DURATION);

		var listOrigin = SpatialOverlayRenderer.activeOrigin(session.snapshot().radial());
		assertNotNull(listOrigin);
		var listView = session.snapshot().inventoryView();
		var listLayout = SpatialOverlayRenderer.inventoryLayout(listView, GUI_WIDTH, GUI_HEIGHT,
			FONT_LINE_HEIGHT, SpatialOverlayRenderer.Style.NATIVE);
		SpatialOverlayRenderer.sampleViewOffset(offset, listOrigin, 0L, false);
		SpatialOverlayRenderer.sampleViewOffset(offset, listOrigin, DURATION, false);
		assertEquals(GUI_WIDTH / 2.0, listLayout.centerX() + offset.x(), 1.0e-9,
			"the open list centers on its active origin");
		assertEquals(GUI_HEIGHT / 2.0, listLayout.centerY() + offset.y(), 1.0e-9);

		double forward = session.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT
			? 1.0 : -1.0;
		session.moveGui(forward * 200.0, 0.0, 2 * DURATION);
		var itemOrigin = SpatialOverlayRenderer.activeOrigin(session.snapshot().radial());
		assertNotNull(itemOrigin);
		var itemView = session.snapshot().inventoryView();
		assertEquals(itemOrigin.y(), itemView.selectedRowCenterY(), 1.0e-9,
			"the item menu stays anchored to the logical selected row");
		var itemLayout = SpatialOverlayRenderer.inventoryLayout(itemView, GUI_WIDTH, GUI_HEIGHT,
			FONT_LINE_HEIGHT, SpatialOverlayRenderer.Style.NATIVE);
		int selected = session.snapshot().inventory().selectedIndex();
		SpatialOverlayRenderer.sampleViewOffset(offset, itemOrigin, 2 * DURATION, false);
		SpatialOverlayRenderer.sampleViewOffset(offset, itemOrigin, 3 * DURATION, false);
		assertEquals(0.0, itemOrigin.x() + offset.x(), 1.0e-9, "the item menu origin is centered");
		assertEquals(0.0, itemOrigin.y() + offset.y(), 1.0e-9);
		assertEquals(GUI_HEIGHT / 2.0, itemLayout.rowCenterY(selected) + offset.y(), 1.0e-9,
			"the selected row stays at the item menu anchor under the same translation");
		assertEquals(GUI_WIDTH / 2.0 - forward * (GEOMETRY.width() / 2.0 + GEOMETRY.childClearance()),
			itemLayout.centerX() + offset.x(), 1.0e-9,
			"the list keeps its logical displacement from the centered item menu");
	}

	private static double projectedX(SpatialController.Point point, SpatialViewOffset offset) {
		return point.x() + offset.x();
	}

	private static double projectedY(SpatialController.Point point, SpatialViewOffset offset) {
		return point.y() + offset.y();
	}

	private static final class Content implements SpatialSelectorSession.ContentPort<String> {
		SpatialSelectorSession.ContentProjection<String> next;
		public SpatialSelectorSession.ContentProjection<String> read(SpatialSelectorSession.CapturedTarget target,
			SpatialSelectorSession.ContentFence fence) { var value = next; next = null; return value; }
	}

	private static PingType ping(String id) {
		return PingTypeCatalog.builtIn().findById(id).orElseThrow();
	}

	private static SpatialSelectorSession.CapturedTarget target(String id, String type, boolean face) {
		var classification = TargetTypeCatalog.builtIn().findById(type).orElseThrow();
		Target value = switch (classification.kind()) {
			case ENTITY -> new Target.EntityTarget("minecraft:overworld",
				UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			case BLOCK -> new Target.BlockTarget("minecraft:overworld", id.hashCode(), 4, 5, "minecraft:chest");
			case LOCATION -> new Target.LocationTarget("minecraft:overworld", 1, 2, 3);
		};
		return new SpatialSelectorSession.CapturedTarget(id, new ResolvedTarget(value, classification),
			face ? Optional.of(BlockFace.NORTH) : Optional.empty(), Optional.of(new WorldVector(1, 2, 3)));
	}

	private static SpatialSelectorSettings.Snapshot settings() {
		return new SpatialSelectorSettings.Snapshot(30, 90, 120, 140, new BigDecimal("0.5"), false, 250, true, false,
			1, new BigDecimal("1.5"), 130, 50, true);
	}

	private static SpatialSelectorSession.ContentFence fence(SpatialSelectorSession.CapturedTarget target) {
		return new SpatialSelectorSession.ContentFence(31, 42, 53, target.candidateId());
	}

	private static SpatialSelectorSession<String> session(SpatialSelectorSession.CapturedTarget target, Content port) {
		return new SpatialSelectorSession<>(target, Map.of(), settings(), fence(target), port, GEOMETRY);
	}

	private static void moveTo(SpatialSelectorSession<?> session, SpatialController.Point origin,
		double bearing, double distance, long now) {
		var pointer = session.snapshot().radial().pointer();
		double radians = Math.toRadians(bearing);
		session.moveGui(origin.x() + Math.sin(radians) * distance - pointer.x(),
			origin.y() - Math.cos(radians) * distance - pointer.y(), now);
	}

	private static void focus(SpatialSelectorSession<?> session, String choiceId, long now) {
		var menu = session.snapshot().radial().menus().getLast();
		var choice = menu.choices().stream().filter(value -> value.id().equals(choiceId)).findFirst().orElseThrow();
		moveTo(session, menu.origin(), choice.startDegrees() + choice.spanDegrees() / 2,
			session.snapshot().settings().stroke() * 2.0, now);
	}

	private static void enter(SpatialSelectorSession<?> session, String choiceId, long now) {
		int before = session.snapshot().radial().menus().size();
		focus(session, choiceId, now);
		session.tick(now + session.snapshot().settings().dwellMillis());
		assertEquals(before + 1, session.snapshot().radial().menus().size());
	}

	private static SpatialSelectorSession.InventoryRow<String> row(String key, Long count) {
		return new SpatialSelectorSession.InventoryRow<>(key, "label " + key, count, "minecraft:stone",
			Status.READY, "opaque:" + key);
	}

	private static SpatialSelectorSession.ContentProjection<String> inventory(
		SpatialSelectorSession.CapturedTarget target, long revision, List<SpatialSelectorSession.InventoryRow<String>> rows) {
		return new SpatialSelectorSession.ContentProjection<>(fence(target), revision, false, Status.UPDATING, List.of(),
			new SpatialSelectorSession.InventoryPreview<>("inventory", Status.UPDATING, rows,
				List.of(ping("attention"), ping("request")), ping("attention")));
	}

	private static SpatialSelectorSession<String> openedList() {
		var target = target("ordinary", "entity_block", true);
		Content port = new Content();
		port.next = inventory(target, 1, List.of(row("a", 50L), row("b", 30L), row("c", 10L)));
		var session = session(target, port);
		session.open(0);
		enter(session, "content", 10);
		String id = session.snapshot().radial().menus().getLast().choices().stream()
			.filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow().id();
		enter(session, id, 150);
		assertTrue(session.snapshot().inventory().open());
		return session;
	}
}
