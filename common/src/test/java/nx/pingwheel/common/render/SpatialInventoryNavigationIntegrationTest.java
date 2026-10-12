package nx.pingwheel.common.render;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import nx.pingwheel.common.client.spatial.InventoryListModel;
import nx.pingwheel.common.client.spatial.NativeSelectorInput;
import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.config.SpatialSelectorSettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.render.SpatialInventoryView.Status;

import static org.junit.jupiter.api.Assertions.*;

/** Real session, native normalization, frame projection and recorded production paint; no GLFW or GPU. */
class SpatialInventoryNavigationIntegrationTest {

	private static final class Painter implements SpatialOverlayRenderer.FramePainter {
		private static final StringSplitter FONT = new StringSplitter((point, style) -> 6.0f);
		final List<String> selectedRows = new ArrayList<>();
		boolean pointerHidden;
		public int lineHeight() { return 9; }
		public int width(FormattedText text) { return (int) Math.ceil(FONT.stringWidth(text)); }
		public StringSplitter splitter() { return FONT; }
		public boolean paint(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
			SpatialOverlayRenderer.NodeLayout layout, SpatialOverlayRenderer.Style style) {
			if (state.present() && state.data() instanceof SpatialOverlayRenderer.RowPaint row && row.selected())
				selectedRows.add(row.row().key());
			if (state.present() && state.data() instanceof SpatialOverlayRenderer.ChromePaint chrome)
				pointerHidden = chrome.pointerHidden();
			return true;
		}
		public void connector(SpatialOverlayRenderer.GuideLine line, double alpha) {}
		void clear() { selectedRows.clear(); pointerHidden = false; }
	}

	private static final class Fixture {
		final SpatialSelectorSession.CapturedTarget target = new SpatialSelectorSession.CapturedTarget("ordinary", new ResolvedTarget(
			new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"),
			TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow()), Optional.of(BlockFace.NORTH), Optional.empty());
		final SpatialSelectorSession.ContentFence fence = new SpatialSelectorSession.ContentFence(31, 42, 53, "ordinary");
		final SpatialOverlayRenderer.Session paint = new SpatialOverlayRenderer.Session();
		final Painter painter = new Painter();
		final SpatialSelectorSettings.Snapshot settings;
		final SpatialOverlayRenderer.Style style;
		final SpatialSelectorSession.ListGeometry geometry;
		final SpatialSelectorSession<String> session;
		final NativeSelectorInput input;
		final List<SpatialSelectorSession.Property> properties;
		NativeSelectorInput.Frame nativeFrame;
		SpatialSelectorSession.ContentProjection<String> pending;
		long now, revision = 1;
		double rawX = 1000, rawY = 700;

		Fixture(int width, int height, String glide, boolean reduced, int propertyCount) {
			this(width, height, glide, reduced, propertyCount, SpatialSelectorSettings.DEFAULT_DEADZONE, false);
		}

		Fixture(int width, int height, String glide, boolean reduced, int propertyCount, int deadzone, boolean hover) {
			var preferences = new SpatialSelectorSettings();
			preferences.setTargetGlide(new BigDecimal(glide));
			preferences.setReduceMotion(reduced);
			preferences.setDeadzone(deadzone);
			preferences.setHoverEnabled(hover);
			settings = preferences.snapshot();
			style = new SpatialOverlayRenderer.Style(100, 100, 1, 1, settings.rootDistance(),
				settings.submenuRadiusScale().doubleValue(), settings.showTrail(), settings.reduceMotion());
			nativeFrame = nativeFrame(width, height);
			var empty = new SpatialInventoryView("geometry", false, List.of(), -1, 0, 1, true, Status.UNKNOWN, 0, 0);
			var layout = SpatialOverlayRenderer.inventoryLayout(empty, 0, 0, painter.lineHeight(), style);
			int visible = Math.max(1, (int) ((height - layout.headerHeight() - layout.footerHeight()) / layout.rowHeight()));
			geometry = SpatialSelectorSession.ListGeometry.fromLayout(visible, layout, settings.deadzone());
			properties = IntStream.range(0, propertyCount).mapToObj(index -> new SpatialSelectorSession.Property("p" + index, "p" + index,
				PresentationPropertyRef.root("pingforit:basic", "pingforit:p" + index), new PresentationValue.NumberValue(index),
				List.of(PingTypeCatalog.builtIn().findById("attention").orElseThrow()),
				PingTypeCatalog.builtIn().findById("attention").orElseThrow())).toList();
			pending = projection(IntStream.range(0, 30).mapToObj(index -> row("k" + index, 100L - index)).toList());
			session = new SpatialSelectorSession<>(target, Map.of(), settings, fence, (ignored, bound) -> {
				var update = pending; pending = null; return update;
			}, geometry);
			session.open(0);
			frame(0);
			input = new NativeSelectorInput(session);
			input.open(); input.prime(rawX, rawY, nativeFrame);
		}

		private NativeSelectorInput.Frame nativeFrame(int width, int height) {
			return new NativeSelectorInput.Frame(7, width * 2, height * 2, width, height, true, false, true);
		}
		SpatialSelectorSession.InventoryRow<String> row(String key, long count) {
			return new SpatialSelectorSession.InventoryRow<>(key, key, count, "minecraft:stone", Status.READY, "opaque:" + key);
		}
		SpatialSelectorSession.ContentProjection<String> projection(List<SpatialSelectorSession.InventoryRow<String>> rows) {
			var attention = PingTypeCatalog.builtIn().findById("attention").orElseThrow();
			return new SpatialSelectorSession.ContentProjection<>(fence, revision++, false, Status.READY, properties,
				new SpatialSelectorSession.InventoryPreview<>("inventory", Status.READY, rows, List.of(attention), attention));
		}
		SpatialSelectorSession.Snapshot snapshot() {
			session.constrainPointer(paint.lastViewport(nativeFrame.guiWidth(), nativeFrame.guiHeight()), now);
			return session.snapshot();
		}
		SpatialController.MenuView menu() { return snapshot().radial().menus().getLast(); }
		void frame(long time) {
			now = time;
			long nanos = time * 1_000_000L;
			session.constrainPointer(paint.prepareViewport(session.snapshot().radial(), nativeFrame.guiWidth(), nativeFrame.guiHeight(), style, nanos), now);
			session.tick(now);
			session.constrainPointer(paint.retargetViewport(session.snapshot().radial(), style, nanos), now);
			inputSynchronize();
			painter.clear();
			var state = session.snapshot();
			paint.drawFrame(state.radial(), state.inventoryView(), choice -> Component.literal(choice.label()), null, style, nanos,
				state.viewport(), nativeFrame.guiWidth(), nativeFrame.guiHeight(), painter);
		}
		void inputSynchronize() { if (input != null) input.synchronize(nativeFrame); }
		void move(double dx, double dy, long time) {
			now = time;
			session.constrainPointer(paint.lastViewport(nativeFrame.guiWidth(), nativeFrame.guiHeight()), now);
			rawX += dx * nativeFrame.windowWidth() / nativeFrame.guiWidth();
			rawY += dy * nativeFrame.windowHeight() / nativeFrame.guiHeight();
			assertTrue(input.onMove(7, rawX, rawY, nativeFrame, now));
		}
		void moveTo(double x, double y, long time) {
			var pointer = snapshot().radial().pointer();
			move(x - pointer.x(), y - pointer.y(), time);
		}
		SpatialController.MenuView retainedParent() {
			var menus = snapshot().radial().menus();
			return menus.get(menus.size() - 2);
		}
		SpatialController.Point inventoryDeadzoneTarget(SpatialController.MenuView parent) {
			var inventory = parent.choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow();
			double radians = Math.toRadians(inventory.startDegrees() + inventory.spanDegrees() / 2.0);
			return new SpatialController.Point(parent.origin().x() + Math.sin(radians) * settings.deadzone(),
				parent.origin().y() - Math.cos(radians) * settings.deadzone());
		}
		double inventorySlotBearing(SpatialController.MenuView parent) {
			var inventory = parent.choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow();
			return inventory.startDegrees() + inventory.spanDegrees() / 2.0;
		}
		double backSign() {
			return snapshot().inventory().direction().back() == InventoryListModel.Side.LEFT ? -1.0 : 1.0;
		}
		void leaveListBySideBack() {
			if (settings.hoverEnabled()) {
				move(backSign() * 50, 0, now + 1);
				assertTrue(snapshot().inventoryView().backAffordance().focused(), "Back hover precedes the return");
				frame(now + settings.hoverMillis() + 1);
			} else {
				move(backSign() * 90, 0, now + 1);
			}
		}
		void settle() { frame(now + 1); frame(now + 160); }
		void enter(String id, double distance) {
			var menu = menu();
			var choice = menu.choices().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
			double radians = Math.toRadians(choice.startDegrees() + choice.spanDegrees() / 2);
			enterAt(id, Math.sin(radians) * distance, -Math.cos(radians) * distance);
		}
		void enterAt(String id, double dx, double dy) {
			int depth = snapshot().radial().menus().size();
			var menu = menu();
			var pointer = snapshot().radial().pointer();
			move(menu.origin().x() + dx - pointer.x(), menu.origin().y() + dy - pointer.y(), now + 1);
			assertEquals(id, snapshot().radial().focusId(), "physical trajectory reaches the intended choice");
			frame(now + settings.dwellMillis());
			assertEquals(depth + 1, snapshot().radial().menus().size());
		}
		void enterAtBack(String id, double dx) {
			int depth = snapshot().radial().menus().size();
			var menu = menu();
			var pointer = snapshot().radial().pointer();
			move(menu.origin().x() + dx - pointer.x(), menu.origin().y() - pointer.y(), now + 1);
			assertEquals(id, snapshot().radial().focusId());
			frame(now + settings.dwellMillis());
			assertEquals(depth - 1, snapshot().radial().menus().size());
		}
		void openList(boolean edge) {
			enter("content", edge ? nativeFrame.guiWidth() : settings.stroke() * 2);
			settle();
			String list = menu().choices().stream().filter(value -> value.id().endsWith(":inventory")).findFirst().orElseThrow().id();
			enter(list, edge ? nativeFrame.guiWidth() : settings.stroke() * 2);
			settle();
			assertTrue(snapshot().inventory().open());
			assertTrue(menu().choices().isEmpty(), "inventory external surface is active");
			assertTrue(painter.pointerHidden, "the real frame path hides the list pointer");
			assertEquals(List.of("k0"), painter.selectedRows);
		}
		void scroll(double rows) {
			assertTrue(input.onScroll(7, 0, -rows, nativeFrame, now + 1));
			now++;
		}
		void resize(int width, int height) {
			nativeFrame = nativeFrame(width, height);
			input.synchronize(nativeFrame);
			input.prime(rawX, rawY, nativeFrame);
			frame(now + 1);
		}
		void assertSelected(int expected) {
			var state = snapshot();
			assertEquals(expected, state.inventory().selectedIndex());
			assertEquals("k" + expected, state.inventory().selectedKey());
			assertEquals(expected, state.inventoryView().selectedIndex());
			assertEquals(state.inventory().windowFirst(), state.inventoryView().windowFirst());
			frame(now + 1); // a newly visible row begins its appearance at zero alpha
			frame(now + 16);
			assertEquals(List.of("k" + expected), painter.selectedRows, "painted selected key agrees with the model");
		}
	}

	@ParameterizedTest
	@CsvSource({"1, false, 0", "0.25, false, 0", "1, true, 0", "0.25, true, 0",
		"1, false, 3", "0.25, false, 3", "1, true, 3", "0.25, true, 3"})
	void nativeVerticalTravelSelectsAndPaintsBothDirectionsFromTheMiddleAtGuiEdges(String glide, boolean reduced, int properties) {
		var f = new Fixture(320, 240, glide, reduced, properties);
		f.openList(true);
		f.move(0, -1000, f.now + 1);
		f.assertSelected(0); // the first row cannot go up
		f.scroll(15); f.assertSelected(15);
		f.pending = f.projection(List.of(f.row("k0", 0), f.row("new", 1000)));
		f.frame(f.now + 16);
		f.assertSelected(15);
		double threshold = f.geometry.rowHeight() / (0.5 * Double.parseDouble(glide));
		for (int step = 1; step <= 3; step++) {
			f.move(0, threshold, f.now + 1);
			f.assertSelected(15 + step);
		}
		assertTrue(f.snapshot().inventory().windowFirst() > 0, "the row window follows selection");
		for (int step = 1; step <= 3; step++) {
			f.move(0, -threshold, f.now + 1);
			f.assertSelected(18 - step);
		}
		var intent = assertInstanceOf(SelectorIntent.SelectInventory.class, f.session.releaseIntent(f.now + 1));
		assertEquals("opaque:k15", intent.reference());
	}

	@Test
	void hiddenPointerOutsideHintCorridorSuppressesEvenLargeVerticalTravelUntilPhysicalRecentering() {
		var f = new Fixture(320, 240, "1", false, 0);
		f.openList(false); f.scroll(15); f.assertSelected(15);
		f.move(20, 0, f.now + 1); f.frame(f.now + 16);
		assertTrue(f.painter.pointerHidden);
		f.move(0, 1000, f.now + 1); f.assertSelected(15);
		f.move(0, -1000, f.now + 1); f.assertSelected(15);
		f.move(-20, 0, f.now + 1);
		f.move(0, 1000, f.now + 1);
		assertTrue(f.snapshot().inventory().selectedIndex() > 15, "reentering the corridor restores glide without changing sensitivity");
	}

	@ParameterizedTest
	@CsvSource({"1, false, -1", "0.25, false, -1", "1, true, -1", "0.25, true, -1",
		"1, false, 1", "0.25, false, 1", "1, true, 1", "0.25, true, 1"})
	void enteringListAtEitherReachableRightGuiCornerPreservesGlideThroughoutCenteringPan(String glide, boolean reduced, int ySign) {
		var f = new Fixture(320, 240, glide, reduced, 0);
		f.enter("content", 320); f.settle();
		String list = f.menu().choices().stream().filter(value -> value.id().endsWith(":inventory")).findFirst().orElseThrow().id();
		f.enterAt(list, 320, ySign * 119);
		var state = f.snapshot();
		assertTrue(state.inventory().open());
		assertEquals(state.inventory().axisX(), state.radial().pointer().x(), 1e-9, "entry rebase agrees with its frozen list axis");
		f.scroll(15);
		double threshold = f.geometry.rowHeight() / (0.5 * Double.parseDouble(glide));
		for (int step = 1; step <= 4; step++) {
			f.move(0, threshold, f.now + 1);
			f.assertSelected(15 + step);
		}
		f.settle();
		f.move(0, -threshold * 4, f.now + 1); f.assertSelected(15);
	}

	@ParameterizedTest
	@CsvSource({"1, false", "0.25, false", "1, true", "0.25, true"})
	void ordinaryItemBackReturnPreservesKeyWindowAndVerticalGlideBeforeAnyResize(String glide, boolean reduced) {
		var f = new Fixture(320, 240, glide, reduced, 0);
		f.openList(false); f.scroll(15); f.assertSelected(15);
		int window = f.snapshot().inventory().windowFirst();
		f.move(80, 0, f.now + 1); f.settle();
		var item = f.menu();
		var back = item.choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
		f.enterAtBack(back.id(), -100);
		assertEquals(window, f.snapshot().inventory().windowFirst());
		f.settle(); f.assertSelected(15);
		double threshold = f.geometry.rowHeight() / (0.5 * Double.parseDouble(glide));
		f.move(0, threshold * 3, f.now + 1); f.assertSelected(18);
		f.move(0, -threshold * 3, f.now + 1); f.assertSelected(15);
	}

	@ParameterizedTest
	@CsvSource({"1, false", "0.25, false", "1, true", "0.25, true"})
	void centeredListResizeAndScaleHandoffPreserveRelativeRowsWithoutProjectionTravel(String glide, boolean reduced) {
		var f = new Fixture(1000, 700, glide, reduced, 0);
		f.openList(false); f.scroll(15); f.assertSelected(15);
		var trail = f.snapshot().radial().trail();
		f.resize(320, 240); f.settle();
		assertEquals(trail, f.snapshot().radial().trail(), "resize and native scale priming are not gesture travel");
		f.assertSelected(15);
		double threshold = f.geometry.rowHeight() / (0.5 * Double.parseDouble(glide));
		f.move(0, threshold * 3, f.now + 1); f.assertSelected(18);
		f.move(0, -threshold * 6, f.now + 1); f.assertSelected(12);
	}

	@ParameterizedTest
	@CsvSource({"1, false, 0", "0.25, false, 0", "1, true, 0", "0.25, true, 0",
		"1, false, 3", "0.25, false, 3", "1, true, 3", "0.25, true, 3"})
	void itemReturnThenResizeCannotLeaveTheVerticalGestureAxisPermanentlyOutsideTheViewport(String glide, boolean reduced, int properties) {
		var f = new Fixture(1000, 700, glide, reduced, properties);
		f.openList(false); f.scroll(15); f.assertSelected(15);
		double forward = f.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		f.move(forward * 80, 0, f.now + 1);
		assertTrue(f.menu().menuId().contains(":item:"));
		f.settle();
		var item = f.menu();
		var back = item.choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
		var pointer = f.snapshot().radial().pointer();
		f.move(item.origin().x() - forward * 400 - pointer.x(), item.origin().y() - pointer.y(), f.now + 1);
		assertEquals(back.id(), f.snapshot().radial().focusId());
		f.frame(f.now + f.settings.dwellMillis());
		assertTrue(f.menu().menuId().endsWith(":inventory"), "Back returns exactly one level");
		f.settle(); f.assertSelected(15);
		var beforeResize = f.snapshot();
		f.resize(320, 240); f.settle();
		assertEquals(beforeResize.inventory(), f.snapshot().inventory(), "axis-only correction preserves row selection, window and list layout");
		assertEquals(beforeResize.radial().trail(), f.snapshot().radial().trail(), "correction adds no gesture trail");
		assertEquals(0, f.snapshot().radial().hoverProgress(), "projection does not advance Back");
		assertTrue(f.menu().menuId().endsWith(":inventory"));
		double threshold = f.geometry.rowHeight() / (0.5 * Double.parseDouble(glide));
		f.move(0, threshold * 3, f.now + 1);
		f.frame(f.now + 1); f.frame(f.now + 16);
		int downIndex = f.snapshot().inventory().selectedIndex();
		List<String> downPaint = List.copyOf(f.painter.selectedRows);
		String downMenu = f.menu().menuId();
		f.move(0, -threshold * 6, f.now + 1);
		f.frame(f.now + 1); f.frame(f.now + 16);
		int upIndex = f.snapshot().inventory().selectedIndex();
		List<String> upPaint = List.copyOf(f.painter.selectedRows);
		String upMenu = f.menu().menuId();
		assertAll(
			() -> assertTrue(downMenu.endsWith(":inventory"), "vertical-only movement must not enter an item because resize made the axis unreachable"),
			() -> assertTrue(upMenu.endsWith(":inventory"), "vertical-only reversal remains list input"),
			() -> assertEquals(18, downIndex, "three admitted native row thresholds must advance down"),
			() -> assertEquals(List.of("k18"), downPaint, "paint must follow the down selection"),
			() -> assertEquals(12, upIndex, "reverse travel must select above the middle without overshoot debt"),
			() -> assertEquals(List.of("k12"), upPaint));
	}

	@ParameterizedTest
	@CsvSource({
		"false, false, 0, 18, 320, 240",
		"true, false, 0, 18, 320, 240",
		"false, true, 3, 18, 320, 240",
		"true, true, 3, 18, 320, 240",
		"false, false, 0, 30, 200, 160",
		"true, false, 3, 30, 200, 160"})
	void listExitReturnsToTheFrozenInventorySlotDeadzoneForBothBackStylesAndMotionModes(
		boolean hover, boolean reduced, int properties, int deadzone, int width, int height) {
		var f = new Fixture(width, height, "1", reduced, properties, deadzone, hover);
		f.openList(true);
		var parent = f.retainedParent();
		var expected = f.inventoryDeadzoneTarget(parent);
		f.leaveListBySideBack();
		f.settle();
		var returned = f.snapshot().radial();
		assertEquals(parent.menuId(), f.menu().menuId(), "exactly one level returns to Content");
		assertFalse(f.snapshot().inventory().open());
		assertNull(returned.focusId(), "the inclusive deadzone admits no immediate activation");
		assertEquals(expected.x(), returned.pointer().x(), 1e-6);
		assertEquals(expected.y(), returned.pointer().y(), 1e-6);
		assertEquals(f.settings.deadzone(), Math.hypot(returned.pointer().x() - parent.origin().x(),
			returned.pointer().y() - parent.origin().y()), 1e-6, "the returned radius is the frozen circumference");
		assertEquals(List.of(returned.pointer()), returned.trail(), "the explicit return rebases the trail seed");
		f.frame(f.now + f.settings.dwellMillis() * 3);
		assertEquals(parent.menuId(), f.menu().menuId(), "stationary frames cannot reopen the list or navigate");
		assertFalse(f.snapshot().inventory().open());
		assertEquals(0.0, f.snapshot().radial().hoverProgress(), "stationary frames do not rearm Back");
		assertInstanceOf(SelectorIntent.None.class, f.session.releaseIntent(f.now + 1),
			"the deadzone release abandons instead of repeating the previous direction");
		assertFalse(f.session.snapshot().active());
	}

	@ParameterizedTest
	@CsvSource({"false, false", "true, false", "false, true", "true, true"})
	void distantListExitKeepsTheReturnPendingUntilTheReprojectedParentCanShowIt(boolean hover, boolean reduced) {
		var f = new Fixture(1000, 700, "1", reduced, 0, SpatialSelectorSettings.DEFAULT_DEADZONE, hover);
		f.openList(true);
		f.resize(320, 240);
		f.settle();
		var parent = f.retainedParent();
		var expected = f.inventoryDeadzoneTarget(parent);
		f.leaveListBySideBack();
		assertEquals(parent.menuId(), f.menu().menuId());
		var pending = f.snapshot().radial();
		assertFalse(f.snapshot().inventory().open());
		if (hover && reduced) {
			assertEquals(expected.x(), pending.pointer().x(), 1e-6,
				"reduced motion snaps the reprojection and resolves the return at once");
		} else {
			assertTrue(Math.abs(pending.pointer().x() - expected.x()) > 1.0,
				"the old list-centred projection cannot show the parent circumference yet");
		}
		f.settle();
		var returned = f.snapshot().radial();
		assertEquals(expected.x(), returned.pointer().x(), 1e-6);
		assertEquals(expected.y(), returned.pointer().y(), 1e-6);
		assertNull(returned.focusId(), "the resolved return lands inside the inclusive deadzone");
		assertEquals(List.of(returned.pointer()), returned.trail(), "projection resolution rebases the seed");
		f.frame(f.now + f.settings.dwellMillis() * 3);
		assertEquals(parent.menuId(), f.menu().menuId(), "projection and stationary frames cannot rearm or navigate");
		assertEquals(0.0, f.snapshot().radial().hoverProgress());
		assertInstanceOf(SelectorIntent.None.class, f.session.releaseIntent(f.now + 1));
		assertFalse(f.session.snapshot().active());
	}

	@Test
	void freshPhysicalMovementCancelsAPendingListReturnInsteadOfChasingIt() {
		var f = new Fixture(1000, 700, "1", false, 0, SpatialSelectorSettings.DEFAULT_DEADZONE, false);
		f.openList(true);
		f.resize(320, 240);
		f.settle();
		var parent = f.retainedParent();
		var expected = f.inventoryDeadzoneTarget(parent);
		f.leaveListBySideBack();
		var pending = f.snapshot().radial();
		assertTrue(Math.abs(pending.pointer().x() - expected.x()) > 1.0, "precondition: the return is still pending");
		f.move(10, 0, f.now + 1); // a fresh effective physical stroke cancels the pending return
		assertEquals(pending.pointer().x() + 10.0, f.snapshot().radial().pointer().x(), 1e-9);
		f.settle();
		var view = f.snapshot().viewport();
		var confined = f.snapshot().radial().pointer();
		assertEquals(view.width() - 1.0 - view.centerX() - view.viewX(), confined.x(), 1e-9,
			"the physically moved pointer is confined by the reprojection instead of warping to the old target");
		assertTrue(confined.x() > expected.x() + 1.0, "the abandoned target is not chased");
	}

	@ParameterizedTest
	@CsvSource({"false, 0", "true, 3"})
	void returnedPointerReopensTheListOnlyAfterAFullDeliberateStrokeAndDwell(boolean hover, int properties) {
		var f = new Fixture(320, 240, "1", false, properties, SpatialSelectorSettings.DEFAULT_DEADZONE, hover);
		f.openList(true);
		var parent = f.retainedParent();
		var expected = f.inventoryDeadzoneTarget(parent);
		double bearing = f.inventorySlotBearing(parent);
		f.leaveListBySideBack();
		f.settle();
		assertNull(f.snapshot().radial().focusId(), "precondition: the return sits inside the deadzone");
		assertEquals(expected.x(), f.snapshot().radial().pointer().x(), 1e-6);
		double radians = Math.toRadians(bearing);
		double deadzone = f.settings.deadzone();
		double stroke = f.settings.stroke();
		f.moveTo(parent.origin().x() + Math.sin(radians) * (deadzone + stroke - 1.0),
			parent.origin().y() - Math.cos(radians) * (deadzone + stroke - 1.0), f.now + 1);
		f.frame(f.now + f.settings.dwellMillis() + 1);
		assertEquals(parent.menuId(), f.menu().menuId(), "a partial new stroke cannot pay the pop's fresh-stroke fence");
		assertFalse(f.snapshot().inventory().open());
		f.moveTo(parent.origin().x() + Math.sin(radians) * (deadzone + stroke),
			parent.origin().y() - Math.cos(radians) * (deadzone + stroke), f.now + 1);
		f.frame(f.now + f.settings.dwellMillis() + 1);
		assertTrue(f.menu().menuId().endsWith(":inventory"));
		assertTrue(f.snapshot().inventory().open(), "a full deliberate stroke plus dwell reopens the list");
	}
}
