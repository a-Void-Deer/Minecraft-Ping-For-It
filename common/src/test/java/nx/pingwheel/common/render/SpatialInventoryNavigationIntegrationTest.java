package nx.pingwheel.common.render;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.joml.Vector4f;

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

	private record RowFrame(String key, Integer outlineColor, boolean present, double alpha) {}
	private record Draw(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
		SpatialOverlayRenderer.NodeLayout layout) {}
	private record Bounds(double left, double top, double right, double bottom) {
		boolean disjoint(Bounds other) {
			return right <= other.left || other.right <= left || bottom <= other.top || other.bottom <= top;
		}
	}

	private static final class Painter implements SpatialOverlayRenderer.FramePainter {
		private static final StringSplitter FONT = new StringSplitter((point, style) -> 6.0f);
		final List<String> selectedRows = new ArrayList<>();
		final List<RowFrame> rows = new ArrayList<>();
		final List<String> paintedNodes = new ArrayList<>();
		final List<SpatialOverlayRenderer.NodePaint> nodes = new ArrayList<>();
		final List<SpatialOverlayRenderer.SectorPaint> sectors = new ArrayList<>();
		final List<Draw> draws = new ArrayList<>();
		boolean localizedInventoryLabels;
		boolean pointerHidden;
		public int lineHeight() { return 9; }
		public int width(FormattedText text) {
			if (localizedInventoryLabels && text instanceof Component component
				&& component.getContents() instanceof TranslatableContents translation
				&& translation.getKey().equals("pingforit.spatial.inventory.forward")) text = Component.literal("Forward");
			return (int) Math.ceil(FONT.stringWidth(text));
		}
		Component label(SpatialController.ChoiceView choice) {
			return Component.literal(localizedInventoryLabels && choice.back() ? "Back" : choice.label());
		}
		public StringSplitter splitter() { return FONT; }
		public boolean paint(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
			SpatialOverlayRenderer.NodeLayout layout, SpatialOverlayRenderer.Style style) {
			draws.add(new Draw(state, layout));
			if (state.data() instanceof SpatialOverlayRenderer.RowPaint row) {
				rows.add(new RowFrame(row.row().key(), row.outlineColor(), state.present(), state.alpha()));
				if (state.present() && row.selected()) selectedRows.add(row.row().key());
			}
			if (state.present() && state.data() instanceof SpatialOverlayRenderer.NodePaint node) {
				paintedNodes.add(node.choice().id());
				nodes.add(node);
			}
			if (state.present() && state.data() instanceof SpatialOverlayRenderer.SectorPaint sector) sectors.add(sector);
			if (state.present() && state.data() instanceof SpatialOverlayRenderer.ChromePaint chrome)
				pointerHidden = chrome.pointerHidden();
			return true;
		}
		public void connector(SpatialOverlayRenderer.GuideLine line, double alpha) {}
		RowFrame row(String key) { return rows.stream().filter(row -> row.key().equals(key)).findFirst().orElseThrow(); }
		void clear() { selectedRows.clear(); rows.clear(); paintedNodes.clear(); nodes.clear(); sectors.clear(); draws.clear(); pointerHidden = false; }
	}

	private static final class Fixture {
		final SpatialSelectorSession.CapturedTarget target = new SpatialSelectorSession.CapturedTarget("ordinary", new ResolvedTarget(
			new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"),
			TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow()), Optional.of(BlockFace.NORTH), Optional.empty());
		final SpatialSelectorSession.ContentFence fence = new SpatialSelectorSession.ContentFence(31, 42, 53, "ordinary");
		final SpatialOverlayRenderer.Session paint = new SpatialOverlayRenderer.Session();
		final Painter painter = new Painter();
		final SpatialSelectorSettings preferences = new SpatialSelectorSettings();
		final SpatialSelectorSettings.Snapshot settings;
		final SpatialOverlayRenderer.Style style;
		SpatialSelectorSession.ListGeometry geometry;
		final SpatialSelectorSession<String> session;
		final NativeSelectorInput input;
		final List<SpatialSelectorSession.Property> properties;
		NativeSelectorInput.Frame nativeFrame;
		SpatialSelectorSession.ContentProjection<String> pending;
		long now, revision = 1;
		int displayProjections;
		double rawX = 1000, rawY = 700;

		Fixture(int width, int height, boolean reduced, int propertyCount) {
			this(width, height, reduced, propertyCount, SpatialSelectorSettings.DEFAULT_DEADZONE, false);
		}

		Fixture(int width, int height, boolean reduced, int propertyCount, int deadzone, boolean hover) {
			this(width, height, reduced, propertyCount, deadzone, hover, true);
		}

		/** {@code contentIntent} selects the frozen property/item intent behavior explicitly. */
		Fixture(int width, int height, boolean reduced, int propertyCount, int deadzone, boolean hover,
			boolean contentIntent) {
			this(width, height, reduced, propertyCount, deadzone, hover, contentIntent,
				SpatialSelectorSettings.DEFAULT_INVENTORY_MAX_WIDTH_FRACTION.doubleValue(),
				SpatialSelectorSettings.DEFAULT_INVENTORY_MAX_HEIGHT_FRACTION.doubleValue());
		}

		Fixture(int width, int height, boolean reduced, int propertyCount, int deadzone, boolean hover,
			boolean contentIntent, double maxWidthFraction, double maxHeightFraction) {
			preferences.setReduceMotion(reduced);
			preferences.setDeadzone(deadzone);
			preferences.setHoverEnabled(hover);
			preferences.setContentIntentEnabled(contentIntent);
			preferences.setInventoryMaxWidthFraction(BigDecimal.valueOf(maxWidthFraction));
			preferences.setInventoryMaxHeightFraction(BigDecimal.valueOf(maxHeightFraction));
			settings = preferences.snapshot();
			style = new SpatialOverlayRenderer.Style(100, 100, 1, 1, settings.rootDistance(),
				settings.submenuRadiusScale().doubleValue(), settings.showTrail(), settings.reduceMotion(),
				settings.trailLength(), settings.trailOpacity(), settings.inventoryMaxWidthFraction().doubleValue(),
				settings.inventoryMaxHeightFraction().doubleValue());
			nativeFrame = nativeFrame(width, height);
			geometry = measureGeometry(null);
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
			prepareGeometry();
			long nanos = time * 1_000_000L;
			session.constrainPointer(paint.prepareViewport(session.snapshot().radial(), nativeFrame.guiWidth(), nativeFrame.guiHeight(), style, nanos), now);
			session.tick(now);
			session.constrainPointer(paint.retargetViewport(session.snapshot().radial(), style, nanos), now);
			inputSynchronize();
			painter.clear();
			var state = session.snapshot();
			paint.drawFrame(state.radial(), state.inventoryView(), painter::label, null, style, nanos,
				state.viewport(), nativeFrame.guiWidth(), nativeFrame.guiHeight(), painter);
		}
		void inputSynchronize() { if (input != null) input.synchronize(nativeFrame); }
		void move(double dx, double dy, long time) {
			now = time;
			prepareGeometry();
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
		double optionStep() { return (nativeFrame.guiHeight() - 1.0) / (snapshot().inventory().entries().size() - 1.0); }
		/** Exercise only real native Y input after callers have explicitly paid any wheel lock. */
		void moveToOption(int index) {
			var state = snapshot();
			double screenY = state.radial().pointer().y() + state.viewport().centerY() + state.viewport().viewY();
			move(0, index * optionStep() - screenY, now + 1);
		}
		/** Reach the actual logical panel edge, then pay the requested exterior distance. */
		void side(double sign, double exterior) {
			var state = snapshot();
			double boundary = state.inventory().axisX() + sign * geometry.width() / 2;
			double distance = Math.max(0, sign * (boundary - state.radial().pointer().x()));
			move(sign * (distance + exterior), 0, now + 1);
		}
		void leaveListBySideBack() {
			if (settings.hoverEnabled()) {
				side(backSign(), settings.deadzone() + 1);
				assertTrue(snapshot().inventoryView().backAffordance().focused(), "Back hover precedes the return");
				frame(now + settings.hoverMillis() + 1);
			} else {
				side(backSign(), settings.stroke() + 1);
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
		void openList(boolean edge) { openList(edge, false); }
		void openList(boolean edge, boolean leftEntry) {
			enter("content", edge ? nativeFrame.guiWidth() : settings.stroke() * 2);
			settle();
			String list = menu().choices().stream().filter(value -> value.id().endsWith(":inventory")).findFirst().orElseThrow().id();
			if (leftEntry) {
				// With no properties the inventory half-circle includes 350 degrees.
				// It is a reachable real-menu left entry even though right is preferred.
				double distance = edge ? nativeFrame.guiWidth() : settings.stroke() * 2;
				enterAt(list, Math.sin(Math.toRadians(350)) * distance, -Math.cos(Math.toRadians(350)) * distance);
			} else enter(list, edge ? nativeFrame.guiWidth() : settings.stroke() * 2);
			settle();
			assertTrue(snapshot().inventory().open());
			assertTrue(menu().choices().isEmpty(), "inventory external surface is active");
			assertFalse(painter.pointerHidden, "the real frame path paints the moving list crosshair");
			assertEquals(List.of("k0"), painter.selectedRows);
		}
		void scroll(double rows) {
			prepareGeometry();
			assertTrue(input.onScroll(7, 0, -rows, nativeFrame, now + 1));
			now++;
		}
		SpatialSelectorSession.ListGeometry measureGeometry(SpatialInventoryView view) {
			return SpatialSelectorSession.ListGeometry.fromMetrics(SpatialInventoryLayout.measure(
				view, nativeFrame.guiWidth(), nativeFrame.guiHeight(), painter.lineHeight(), style.inventoryTextScale(),
				style.inventoryMaxWidthFraction(), style.inventoryMaxHeightFraction(), painter::width),
				SpatialOverlayRenderer.itemMenuClearance(nativeFrame.guiWidth(), nativeFrame.guiHeight(), painter.lineHeight(),
					painter.width(Component.literal("Back")), style));
		}
		void prepareGeometry() {
			session.prepareInventoryGeometry(view -> geometry = measureGeometry(view),
				row -> { displayProjections++; return new SpatialInventoryView.RowDisplay(Component.literal(row.label())); }, now);
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

	private static void publishLongNames(Fixture fixture) {
		fixture.pending = fixture.projection(IntStream.range(0, 30).mapToObj(index ->
			new SpatialSelectorSession.InventoryRow<String>("k" + index, "Long detached item name ".repeat(10), 100L - index,
				"minecraft:stone", Status.READY, "opaque:k" + index)).toList());
		fixture.frame(fixture.now + 1);
	}

	private static void assertSharedInventoryPanel(Fixture fixture) {
		var view = fixture.snapshot().inventoryView();
		var layout = SpatialOverlayRenderer.inventoryLayout(view, fixture.nativeFrame.guiWidth(), fixture.nativeFrame.guiHeight(),
			fixture.painter.lineHeight(), fixture.style, fixture.painter::width);
		var panel = fixture.painter.draws.stream().filter(draw -> draw.state().present()
			&& draw.state().data() instanceof SpatialOverlayRenderer.PanelPaint).findFirst().orElseThrow();
		var paint = (SpatialOverlayRenderer.PanelPaint) panel.state().data();
		assertEquals(fixture.geometry.width(), layout.width(), "native side boundaries and renderer layout share the held width");
		assertEquals(layout.width(), paint.width(), "production panel paint uses the same measured edge");
		assertEquals(layout.height(), paint.height(), "panel chrome and complete row capacity share the held height");
		assertEquals(fixture.geometry.visibleRows(), view.visibleRows(), "the input model receives the measured row capacity");
		var back = fixture.painter.draws.stream().filter(draw -> draw.state().present()
			&& draw.state().data() instanceof SpatialOverlayRenderer.InventoryBackPaint).findFirst().orElseThrow();
		var backPaint = (SpatialOverlayRenderer.InventoryBackPaint) back.state().data();
		double sign = fixture.backSign();
		assertTrue(sign * (back.state().x() - panel.state().x()) - backPaint.width() * back.state().scale() / 2
			>= paint.width() * panel.state().scale() / 2, "the complete Back frame lies outside the capped content panel");
	}

	@ParameterizedTest
	@CsvSource({"0.20, 0.30, false", "0.80, 0.30, true", "0.20, 0.95, true", "0.80, 0.95, false"})
	void customInventoryMaximaDrivePaintCapacitySideBoundariesAndRowAnchoredItems(
		double maxWidth, double maxHeight, boolean leftEntry) {
		var f = new Fixture(1000, 700, true, 0, 18, false, true, maxWidth, maxHeight);
		f.painter.localizedInventoryLabels = true;
		publishLongNames(f);
		f.openList(false, leftEntry);
		assertEquals(Math.floor(1000 * maxWidth), f.geometry.width(), "long content reaches the independently configured width cap");
		assertEquals((int) Math.floor((700 * maxHeight - 41) / 22), f.geometry.visibleRows());
		assertSharedInventoryPanel(f);

		double sign = f.backSign(), axis = f.snapshot().inventory().axisX();
		f.moveTo(axis + sign * (f.geometry.width() / 2 - 1), f.snapshot().radial().pointer().y(), f.now + 1);
		assertFalse(f.snapshot().inventoryView().backAffordance().focused(), "motion inside the chosen panel width is not side intent");
		f.move(sign * (0.5 + f.settings.deadzone()), 0, f.now + 1);
		assertFalse(f.snapshot().inventoryView().backAffordance().focused(), "travel just below the deadzone is measured from the actual content edge");
		f.move(sign, 0, f.now + 1);
		assertTrue(f.snapshot().inventoryView().backAffordance().focused());
		f.moveTo(axis, f.snapshot().radial().pointer().y(), f.now + 1);
		assertFalse(f.snapshot().inventoryView().backAffordance().focused());

		f.scroll(15); f.settle();
		var before = f.snapshot();
		var layout = SpatialOverlayRenderer.inventoryLayout(before.inventoryView(), 1000, 700, f.painter.lineHeight(), f.style, f.painter::width);
		f.side(-sign, f.settings.stroke() - 1);
		assertTrue(f.menu().menuId().endsWith(":inventory"), "the same edge-based stroke is required for Forward");
		f.move(-sign * 2, 0, f.now + 1); f.settle();
		assertTrue(f.menu().menuId().contains(":item:"));
		assertEquals("k15", f.snapshot().inventoryView().retainedRowKey());
		assertEquals(layout.rowCenterY(15) - 350, f.menu().origin().y(), 1e-9, "item Y is the measured selected-row anchor");
		assertEquals(f.geometry.width() / 2 + f.geometry.childClearance(),
			-sign * (f.menu().origin().x() - before.inventory().axisX()), 1e-9);
		assertSharedInventoryPanel(f);
		assertItemClearOfEveryPaintedPanel(f, f.menu().menuId(), "custom held dimensions");
	}

	@Test
	void livePreferenceEditsDoNotChangeHeldStyleAndResizeUsesItsOriginalFractions() {
		var f = new Fixture(1000, 700, true, 0, 18, false, true, 0.65, 0.85);
		publishLongNames(f); f.openList(false); f.scroll(15); f.settle();
		f.preferences.setInventoryMaxWidthFraction(new BigDecimal("0.20"));
		f.preferences.setInventoryMaxHeightFraction(new BigDecimal("0.30"));
		f.frame(f.now + 1);
		assertEquals(650, f.geometry.width());
		assertEquals(25, f.geometry.visibleRows());
		assertSharedInventoryPanel(f);
		var before = f.snapshot();
		f.resize(500, 350); f.settle();
		assertEquals(325, f.geometry.width(), "the new GUI viewport is measured with the held 0.65 proportion");
		assertEquals(11, f.geometry.visibleRows(), "the held 0.85 height is retained rather than live config or an old pixel height");
		assertEquals(before.inventory().entries(), f.snapshot().inventory().entries());
		assertEquals(before.inventory().selectedKey(), f.snapshot().inventory().selectedKey());
		assertEquals(before.inventory().direction(), f.snapshot().inventory().direction());
		assertSharedInventoryPanel(f);
		assertTrue(f.menu().menuId().endsWith(":inventory"), "resize is not navigation");
		assertEquals(0, f.snapshot().radial().hoverProgress());
	}

	@Test
	void aClosingListRemeasuresItsRealDetachedPanelAndRowsWithTheHeldProportionsAfterResize() {
		var f = new Fixture(1000, 700, false, 0, 18, false, true, 0.65, 0.85);
		publishLongNames(f); f.openList(false); f.scroll(15); f.settle();
		assertInstanceOf(SelectorIntent.SelectInventory.class, f.session.releaseIntent(f.now + 1));
		f.frame(f.now + 1); // begin the ordinary bounded exit before changing GUI dimensions
		f.resize(320, 240);
		var detached = f.painter.draws.stream().filter(draw -> !draw.state().present()
			&& draw.state().data() instanceof SpatialOverlayRenderer.PanelPaint).findFirst().orElseThrow();
		var panel = (SpatialOverlayRenderer.PanelPaint) detached.state().data();
		assertEquals(208, panel.width(), "the exit tail uses the held 0.65, not the default width or old pixel width");
		assertEquals(195, panel.height(), "the held 0.85 fits seven complete rows and natural chrome at the new GUI height");
		assertEquals(7, f.painter.rows.size(), "detached rows outside the newly measured capacity are not painted");
		assertTrue(f.painter.rows.stream().noneMatch(RowFrame::present));
		assertTrue(detached.state().alpha() > 0 && detached.state().alpha() < 1, "the existing finite exit still paints");
	}

	/** Observe rounded paint rectangles through the same float pose used by native submission. */
	private static Bounds transformedBounds(Draw draw, int left, int top, int right, int bottom) {
		var pose = new PoseStack();
		pose.translate(draw.state().x(), draw.state().y(), 0);
		pose.scale((float) draw.state().scale(), (float) draw.state().scale(), 1);
		var near = new Vector4f(left, top, 0, 1).mul(pose.last().pose());
		var far = new Vector4f(right, bottom, 0, 1).mul(pose.last().pose());
		return new Bounds(near.x(), near.y(), far.x(), far.y());
	}

	private static void assertItemClearOfEveryPaintedPanel(Fixture fixture, String itemMenu, String phase) {
		var panels = fixture.painter.draws.stream().filter(draw -> draw.state().data() instanceof SpatialOverlayRenderer.PanelPaint)
			.map(draw -> {
				var panel = (SpatialOverlayRenderer.PanelPaint) draw.state().data();
				return transformedBounds(draw, (int) Math.round(-panel.width() / 2), (int) Math.round(-panel.height() / 2),
					(int) Math.round(panel.width() / 2), (int) Math.round(panel.height() / 2));
			}).toList();
		assertFalse(panels.isEmpty(), phase + ": the panel must actually paint, not be hidden to avoid overlap");
		var back = fixture.painter.draws.stream().filter(draw -> draw.state().present() && draw.state().key().owner().equals(itemMenu)
			&& draw.state().data() instanceof SpatialOverlayRenderer.NodePaint node && node.choice().back()).findFirst().orElseThrow();
		var frame = back.layout();
		Bounds backBounds = transformedBounds(back, frame.left(), frame.top(), frame.right(), frame.bottom());
		for (Bounds panel : panels) assertTrue(panel.disjoint(backBounds),
			phase + ": the complete child Back frame clears every present or detached panel");
		var sectors = fixture.painter.draws.stream().filter(draw -> draw.state().present() && draw.state().key().owner().equals(itemMenu)
			&& draw.state().data() instanceof SpatialOverlayRenderer.SectorPaint).toList();
		assertFalse(sectors.isEmpty(), phase + ": actual child sectors are recorded");
		for (Draw draw : sectors) {
			// A conservative bound on the emitted sector, including rounded edge
			// pixels, reads paint output rather than mirroring clearance tuning.
			int extent = (int) Math.ceil(((SpatialOverlayRenderer.SectorPaint) draw.state().data()).radius()) + 1;
			Bounds sectorBounds = transformedBounds(draw, -extent, -extent, extent, extent);
			for (Bounds panel : panels) assertTrue(panel.disjoint(sectorBounds),
				phase + ": every child sector clears every present or detached panel");
		}
	}

	@Test
	void nativeUpperRightThenExactlyRightUsesTheSameEqualInventorySectorForHitFollowerAndPaint() {
		var f = new Fixture(1000, 700, true, 2, 18, false);
		f.move(140, -140, 10);
		assertEquals("content", f.snapshot().radial().focusId());
		f.move(120, 0, 50); // qualified upper-right -> right turn, without a Content dwell
		var content = f.menu();
		assertTrue(content.menuId().endsWith(":content"), "the physical turn enters Content at its actual corner");
		assertEquals(new SpatialController.Point(140, -140), content.origin());
		var inventory = content.choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow();
		assertEquals(90, inventory.startDegrees(), 1e-9, "the half-open boundary selects the clockwise equal slot");
		assertEquals(90, inventory.spanDegrees(), 1e-9);
		assertEquals(135, inventory.startDegrees() + inventory.spanDegrees() / 2, 1e-9,
			"the preference never rotates a slot centre to the desired bearing");
		var back = content.choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
		assertEquals(225, back.startDegrees() + back.spanDegrees() / 2, 1e-9, "Back still faces the actual root origin");

		assertEquals(inventory.id(), f.snapshot().radial().focusId(), "the actual native second stroke is exactly right");
		f.frame(f.now + 1);
		var focused = f.menu().choices().stream().filter(choice -> choice.id().equals(inventory.id())).findFirst().orElseThrow();
		var sector = f.painter.sectors.stream().filter(paint -> paint.choice().id().equals(inventory.id())).findFirst().orElseThrow();
		var node = f.painter.nodes.stream().filter(paint -> paint.choice().id().equals(inventory.id())).findFirst().orElseThrow();
		assertEquals(focused, sector.choice(), "sector paint receives the exact controller-resolved hit geometry");
		assertEquals(focused, node.choice(), "node and follower share that resolved choice");
		assertNotNull(node.follower(), "inventory follows before branch dwell completes");
		assertTrue(f.painter.pointerHidden);

		// A turn opens the child at its corner but does not pay that child's
		// own minimum travel; continue the same right stroke before dwelling.
		f.move(20, 0, f.now + 20);
		assertEquals(inventory.id(), f.snapshot().radial().focusId());
		f.frame(f.now + f.settings.dwellMillis() + 1);
		assertTrue(f.snapshot().inventory().open(), "the same focused entry opens the list");
		assertEquals(InventoryListModel.Side.RIGHT, f.snapshot().inventory().direction().forward());
		assertEquals(inventory.id(), f.menu().entryChoiceId());
		var breadcrumb = f.painter.nodes.stream().filter(paint -> paint.choice().id().equals(inventory.id())).findFirst().orElseThrow();
		assertNull(breadcrumb.follower(), "the entered inventory choice becomes the ordinary ancestor breadcrumb");
		assertEquals(inventory.startDegrees(), breadcrumb.choice().startDegrees());
		assertEquals(inventory.spanDegrees(), breadcrumb.choice().spanDegrees());
	}

	@ParameterizedTest
	@CsvSource({"false, false", "true, false", "false, true", "true, true"})
	void nativeVerticalTravelSelectsAndPaintsAbsoluteOptionsAtGuiEdges(boolean reduced, boolean leftEntry) {
		var f = new Fixture(320, 240, reduced, 0);
		f.openList(true, leftEntry);
		assertEquals(leftEntry ? InventoryListModel.Side.LEFT : InventoryListModel.Side.RIGHT, f.snapshot().inventory().direction().forward());
		f.move(0, -1000, f.now + 1);
		f.assertSelected(0); // the first row cannot go up
		f.scroll(15); f.assertSelected(15);
		f.pending = f.projection(List.of(f.row("k0", 0), f.row("new", 1000)));
		f.frame(f.now + 16);
		f.assertSelected(15);
		f.move(0, 14, f.now + 1); // unlock without moving Y
		for (int step = 1; step <= 3; step++) {
			f.moveToOption(15 + step);
			f.assertSelected(15 + step);
		}
		assertTrue(f.snapshot().inventory().windowFirst() > 0, "the row window follows selection");
		for (int step = 1; step <= 3; step++) {
			f.moveToOption(18 - step);
			f.assertSelected(18 - step);
		}
		var intent = assertInstanceOf(SelectorIntent.SelectInventory.class, f.session.releaseIntent(f.now + 1));
		assertEquals("opaque:k15", intent.reference());
	}

	@Test
	void nativeSelectionChangeMovesTheAnnotationBorderToTheCurrentKeyWithinTheWindow() {
		var f = new Fixture(320, 240, false, 0);
		f.openList(false);
		var before = f.snapshot().inventory();
		String oldKey = before.selectedKey();
		Integer color = f.painter.row(oldKey).outlineColor();
		assertNotNull(color, "the initial direct release has an annotation border");

		f.moveToOption(1);
		var after = f.snapshot().inventory();
		String selectedKey = after.selectedKey();
		assertNotEquals(oldKey, selectedKey);
		assertEquals(before.windowFirst(), after.windowFirst(), "both rows stay in the same window");
		f.frame(f.now + 1);
		assertTrue(f.painter.row(oldKey).present(), "the old row still paints");
		assertNull(f.painter.row(oldKey).outlineColor());
		assertEquals(color, f.painter.row(selectedKey).outlineColor());
		assertEquals(List.of(selectedKey), f.painter.rows.stream().filter(row -> row.outlineColor() != null)
			.map(RowFrame::key).toList(), "only the model's selected key advertises direct release");

		f.side(f.backSign(), f.settings.deadzone() + 1);
		assertTrue(f.snapshot().inventoryView().backAffordance().focused());
		f.frame(f.now + 1);
		assertTrue(f.painter.rows.stream().allMatch(row -> row.outlineColor() == null), "Back focus removes direct-send borders");
	}

	@Test
	void nativeWindowJumpKeepsTheOldSelectedRowFadingWithoutItsAnnotationBorder() {
		var f = new Fixture(320, 240, false, 0);
		f.openList(false);
		String oldKey = f.snapshot().inventory().selectedKey();
		Integer color = f.painter.row(oldKey).outlineColor();
		assertNotNull(color);
		f.scroll(15);
		String selectedKey = f.snapshot().inventory().selectedKey();
		assertNotEquals(oldKey, selectedKey);
		assertTrue(f.snapshot().inventory().windowFirst() > 0);
		f.frame(f.now + 1);
		f.frame(f.now + 16);
		var detached = f.painter.row(oldKey);
		assertFalse(detached.present(), "the former selection remains as an exit node");
		assertTrue(detached.alpha() > 0 && detached.alpha() < 1, "the ordinary fade remains in progress");
		assertNull(detached.outlineColor(), "a row outside the visible window cannot advertise direct release");
		assertEquals(color, f.painter.row(selectedKey).outlineColor());
		assertEquals(List.of(selectedKey), f.painter.rows.stream().filter(row -> row.outlineColor() != null)
			.map(RowFrame::key).toList());

		assertInstanceOf(SelectorIntent.SelectInventory.class, f.session.releaseIntent(f.now + 1));
		f.frame(f.now + 1);
		f.frame(f.now + 16);
		var closing = f.painter.row(selectedKey);
		assertFalse(closing.present());
		assertTrue(closing.alpha() > 0 && closing.alpha() < 1);
		assertEquals(color, closing.outlineColor(), "ordinary close retains the departing border through its exit fade");
		var oldClosing = f.painter.row(oldKey);
		assertFalse(oldClosing.present(), "closing does not remove the former selection's detached node");
		assertTrue(oldClosing.alpha() > 0 && oldClosing.alpha() < detached.alpha(), "its original exit fade continues");
		assertNull(oldClosing.outlineColor(), "closing cannot restore a canceled annotation border from cached paint");
	}

	@Test
	void nativeReselectionRestoresTheAnnotationBorderOnTheRetargetedRow() {
		var f = new Fixture(320, 240, false, 0);
		f.openList(false);
		String key = f.snapshot().inventory().selectedKey();
		Integer color = f.painter.row(key).outlineColor();
		assertNotNull(color);
		f.scroll(15);
		f.frame(f.now + 1); f.frame(f.now + 16);
		assertFalse(f.painter.row(key).present());
		assertNull(f.painter.row(key).outlineColor());
		f.scroll(-15);
		assertEquals(key, f.snapshot().inventory().selectedKey());
		f.frame(f.now + 1); f.frame(f.now + 16);
		assertTrue(f.painter.row(key).present());
		assertEquals(color, f.painter.row(key).outlineColor(), "retargeting replaces the canceled paint with current authorization");
		assertEquals(List.of(key), f.painter.rows.stream().filter(row -> row.outlineColor() != null).map(RowFrame::key).toList());
	}

	@Test
	void visiblePointerOutsidePanelDeadzoneSuppressesYUntilPhysicalReentry() {
		var f = new Fixture(320, 240, false, 0);
		f.openList(false); f.scroll(15); f.assertSelected(15);
		f.side(-f.backSign(), f.settings.deadzone() + 1); f.frame(f.now + 16);
		assertFalse(f.painter.pointerHidden);
		f.move(0, 1000, f.now + 1); f.assertSelected(15);
		f.move(0, -1000, f.now + 1); f.assertSelected(15);
		double axis = f.snapshot().inventory().axisX();
		f.move(axis - f.snapshot().radial().pointer().x(), 0, f.now + 1);
		f.move(0, 1000, f.now + 1);
		f.assertSelected(29);
	}

	@ParameterizedTest
	@CsvSource({"false, -1", "true, -1", "false, 1", "true, 1"})
	void enteringListAtEitherReachableRightGuiCornerAlignsOptionsThroughoutCenteringPan(boolean reduced, int ySign) {
		var f = new Fixture(320, 240, reduced, 0);
		f.enter("content", 320); f.settle();
		String list = f.menu().choices().stream().filter(value -> value.id().endsWith(":inventory")).findFirst().orElseThrow().id();
		f.enterAt(list, 320, ySign * 119);
		var state = f.snapshot();
		assertTrue(state.inventory().open());
		assertEquals(state.inventory().axisX(), state.radial().pointer().x(), 1e-9, "entry rebase agrees with its frozen list axis");
		f.scroll(15);
		f.move(0, 14, f.now + 1);
		for (int step = 1; step <= 4; step++) {
			f.moveToOption(15 + step);
			f.assertSelected(15 + step);
		}
		f.settle();
		f.moveToOption(15); f.assertSelected(15);
	}

	@ParameterizedTest
	@CsvSource({"false", "true"})
	void ordinaryItemBackReturnPreservesKeyWindowAndAbsoluteSelectionBeforeAnyResize(boolean reduced) {
		var f = new Fixture(320, 240, reduced, 0);
		f.openList(false); f.scroll(15); f.assertSelected(15);
		int window = f.snapshot().inventory().windowFirst();
		f.side(-f.backSign(), f.settings.stroke() + 1); f.settle();
		var item = f.menu();
		var back = item.choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
		f.enterAtBack(back.id(), -100);
		assertEquals(window, f.snapshot().inventory().windowFirst());
		f.settle(); f.assertSelected(15);
		f.moveToOption(18); f.assertSelected(18);
		f.moveToOption(15); f.assertSelected(15);
	}

	@ParameterizedTest
	@CsvSource({"false", "true"})
	void centeredListResizeAndScaleHandoffPreserveKeyWithoutProjectionTravel(boolean reduced) {
		var f = new Fixture(1000, 700, reduced, 0);
		f.openList(false); f.scroll(15); f.assertSelected(15);
		f.resize(320, 240); f.settle();
		assertEquals(List.of(f.snapshot().radial().pointer()), f.snapshot().radial().trail(), "resize alignment begins a fresh epoch");
		f.assertSelected(15);
		f.move(0, 14, f.now + 1);
		f.moveToOption(18); f.assertSelected(18);
		f.moveToOption(12); f.assertSelected(12);
	}

	@ParameterizedTest
	@CsvSource({"false, false", "true, false", "false, true", "true, true"})
	void itemReturnThenResizePreservesAbsoluteSelectionAcrossBothDirections(boolean reduced, boolean leftEntry) {
		var f = new Fixture(1000, 700, reduced, 0);
		f.openList(false, leftEntry); f.scroll(15); f.assertSelected(15);
		assertEquals(leftEntry ? InventoryListModel.Side.LEFT : InventoryListModel.Side.RIGHT, f.snapshot().inventory().direction().forward());
		double forward = f.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		f.side(forward, f.settings.stroke() + 1);
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
		assertEquals(beforeResize.inventory().entries(), f.snapshot().inventory().entries());
		assertEquals(beforeResize.inventory().selectedKey(), f.snapshot().inventory().selectedKey());
		assertEquals(beforeResize.inventory().direction(), f.snapshot().inventory().direction());
		var resized = f.snapshot().inventoryView();
		assertTrue(resized.selectedIndex() >= resized.windowFirst()
			&& resized.selectedIndex() < resized.windowFirst() + resized.visibleRows(), "rewindowing keeps the key visible");
		assertEquals(List.of(f.snapshot().radial().pointer()), f.snapshot().radial().trail(), "correction starts a fresh seed without a joining stroke");
		assertEquals(0, f.snapshot().radial().hoverProgress(), "projection does not advance Back");
		assertTrue(f.menu().menuId().endsWith(":inventory"));
		f.moveToOption(18);
		f.frame(f.now + 1); f.frame(f.now + 16);
		int downIndex = f.snapshot().inventory().selectedIndex();
		List<String> downPaint = List.copyOf(f.painter.selectedRows);
		String downMenu = f.menu().menuId();
		f.moveToOption(12);
		f.frame(f.now + 1); f.frame(f.now + 16);
		int upIndex = f.snapshot().inventory().selectedIndex();
		List<String> upPaint = List.copyOf(f.painter.selectedRows);
		String upMenu = f.menu().menuId();
		assertAll(
			() -> assertTrue(downMenu.endsWith(":inventory"), "vertical-only movement must not enter an item because resize made the axis unreachable"),
			() -> assertTrue(upMenu.endsWith(":inventory"), "vertical-only reversal remains list input"),
			() -> assertEquals(18, downIndex, "absolute screen Y selects the full-list option"),
			() -> assertEquals(List.of("k18"), downPaint, "paint must follow the down selection"),
			() -> assertEquals(12, upIndex, "reverse travel must select above the middle without overshoot debt"),
			() -> assertEquals(List.of("k12"), upPaint));
	}

	@ParameterizedTest
	@CsvSource({
		"false, false, 0, 18, 320, 240",
		"true, false, 0, 18, 320, 240",
		"false, true, 7, 18, 320, 240",
		"true, true, 7, 18, 320, 240",
		"false, false, 0, 30, 200, 160",
		"true, false, 7, 30, 200, 160"})
	void listUserReturnKeepsThePointerAtTheTriggerAndMovesTheParentCentreThere(
		boolean hover, boolean reduced, int properties, int deadzone, int width, int height) {
		var f = new Fixture(width, height, reduced, properties, deadzone, hover);
		f.openList(true);
		f.leaveListBySideBack();
		var trigger = f.snapshot().radial().pointer();
		f.settle();
		var returned = f.snapshot().radial();
		assertTrue(f.menu().menuId().endsWith(":content"), "exactly one level returns to Content");
		assertFalse(f.snapshot().inventory().open());
		assertEquals(trigger, returned.pointer(), "the user return leaves the pointer at the trigger");
		assertEquals(trigger, f.menu().origin(), "the returned parent is rigidly translated to the pointer");
		assertNull(returned.focusId(), "the shared centre is inside the frozen deadzone");
		assertEquals(List.of(returned.pointer()), returned.trail(), "the return seeds exactly one point");
		f.frame(f.now + f.settings.dwellMillis() * 3);
		assertTrue(f.menu().menuId().endsWith(":content"), "stationary frames cannot reopen the list or navigate");
		assertFalse(f.snapshot().inventory().open());
		assertEquals(0.0, f.snapshot().radial().hoverProgress(), "stationary frames do not rearm Back");
		assertInstanceOf(SelectorIntent.None.class, f.session.releaseIntent(f.now + 1),
			"the deadzone release abandons instead of repeating the previous direction");
		assertFalse(f.session.snapshot().active());
	}

	@ParameterizedTest
	@CsvSource({"false, false", "true, false", "false, true", "true, true"})
	void listUserReturnNeverResolvesThroughTheOldCircumferencePlacement(boolean hover, boolean reduced) {
		var f = new Fixture(1000, 700, reduced, 0, SpatialSelectorSettings.DEFAULT_DEADZONE, hover);
		f.openList(true);
		f.resize(320, 240);
		f.settle();
		f.leaveListBySideBack();
		var trigger = f.snapshot().radial().pointer();
		assertTrue(f.menu().menuId().endsWith(":content"));
		assertFalse(f.snapshot().inventory().open());
		var returned = f.snapshot().radial();
		assertEquals(trigger, returned.pointer(), "the user return leaves the pointer at the trigger");
		assertEquals(trigger, f.menu().origin(), "the parent is already at the pointer, not a pending circumference");
		assertNull(returned.focusId());
		assertEquals(List.of(returned.pointer()), returned.trail(), "no deferred placement stroke is appended");
		f.settle();
		var settled = f.snapshot().radial();
		assertEquals(trigger, settled.pointer(), "no deferred placement target is chased");
		assertEquals(0.0, f.snapshot().radial().hoverProgress());
		assertInstanceOf(SelectorIntent.None.class, f.session.releaseIntent(f.now + 1));
		assertFalse(f.session.snapshot().active());
	}

	@Test
	void freshPhysicalMovementAfterAListUserReturnContinuesFromTheReturnedCentre() {
		var f = new Fixture(1000, 700, false, 0, SpatialSelectorSettings.DEFAULT_DEADZONE, false);
		f.openList(true);
		f.resize(320, 240);
		f.settle();
		f.leaveListBySideBack();
		var trigger = f.snapshot().radial().pointer();
		assertTrue(f.menu().menuId().endsWith(":content"));
		assertEquals(trigger, f.snapshot().radial().pointer());
		f.move(10, 0, f.now + 1);
		var expected = f.snapshot().viewport().confine(new SpatialController.Point(trigger.x() + 10.0, trigger.y()));
		assertEquals(expected, f.snapshot().radial().pointer(),
			"fresh physical movement continues from the returned centre");
	}

	@ParameterizedTest
	@CsvSource({"false, 0", "true, 7"})
	void returnedCentreReopensTheListOnlyAfterAFullDeliberateStrokeAndDwell(boolean hover, int properties) {
		var f = new Fixture(320, 240, false, properties, 18, hover);
		f.openList(true);
		f.leaveListBySideBack();
		f.settle();
		var parent = f.menu();
		assertNull(f.snapshot().radial().focusId(), "precondition: the return sits inside the deadzone");
		double bearing = f.inventorySlotBearing(parent);
		double radians = Math.toRadians(bearing);
		double stroke = f.settings.stroke();
		f.moveTo(parent.origin().x() + Math.sin(radians) * (stroke - 1.0),
			parent.origin().y() - Math.cos(radians) * (stroke - 1.0), f.now + 1);
		f.frame(f.now + f.settings.dwellMillis() + 1);
		assertTrue(f.menu().menuId().endsWith(":content"), "a partial new stroke cannot pay the pop's fresh-stroke fence");
		assertFalse(f.snapshot().inventory().open());
		f.moveTo(parent.origin().x() + Math.sin(radians) * (stroke + 1.0),
			parent.origin().y() - Math.cos(radians) * (stroke + 1.0), f.now + 1);
		f.frame(f.now + f.settings.dwellMillis() + 1);
		assertTrue(f.menu().menuId().endsWith(":inventory"));
		assertTrue(f.snapshot().inventory().open(), "a full deliberate stroke plus dwell reopens the list");
	}

	@Test
	void offContentIntentKeepsOrdinaryRowsAndNoForwardOrRetainedKey() {
		var f = new Fixture(320, 240, false, 0, SpatialSelectorSettings.DEFAULT_DEADZONE, false, false);
		f.openList(false);
		var view = f.snapshot().inventoryView();
		assertNull(view.retainedRowKey());
		assertFalse(view.forwardAvailable(), "OFF has no item Forward entry or hint");
		assertTrue(view.selectedRowCommittable(), "direct release is still eligible under OFF");
		double forward = f.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		f.side(forward, f.settings.stroke() + 1);
		assertTrue(f.menu().menuId().endsWith(":inventory"), "the frozen OFF toggle keeps Forward inert");
		var intent = assertInstanceOf(SelectorIntent.SelectInventory.class, f.session.releaseIntent(f.now + 1));
		assertEquals("opaque:k0", intent.reference());
		assertEquals("attention", intent.itemType().id());
	}

	@Test
	void itemClearanceUpdatesRepositionTheActiveItemMenuWithoutPointerOrTrailTravel() {
		var f = new Fixture(320, 240, false, 3, SpatialSelectorSettings.DEFAULT_DEADZONE, false);
		f.openList(false);
		double forward = f.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		f.side(forward, f.settings.stroke() + 1);
		var item = f.menu();
		assertTrue(item.menuId().contains(":item:"));
		double axis = f.snapshot().inventory().axisX();
		var before = f.snapshot().radial();

		f.session.updateItemClearance(140, f.now + 1);

		var moved = f.menu();
		assertTrue(moved.menuId().contains(":item:"));
		assertEquals(axis + forward * (f.geometry.width() / 2 + 140), moved.origin().x(), 1e-9);
		assertEquals(item.origin().y(), moved.origin().y(), 1e-9, "the frozen row anchor is kept");
		var after = f.snapshot().radial();
		assertEquals(before.pointer(), after.pointer(), "a pure metric update moves no pointer");
		assertEquals(before.trail(), after.trail(), "a pure metric update adds no trail");
		assertEquals(0.0, after.hoverProgress());
	}

	@Test
	void boundedDetachedDisplayProjectionIsReusedAcrossScrollAndRemeasureButInvalidatedForChangedText() {
		var f = new Fixture(1600, 1000, true, 0, 18, false, false, 0.30, 0.60);
		f.openList(false);
		int projected = f.displayProjections;
		assertEquals(f.snapshot().inventoryView().rows().size(), projected, "one projection per bounded discovered row");
		double beforeWidth = f.geometry.width();
		f.scroll(15); f.prepareGeometry(); f.frame(f.now + 1);
		assertEquals(projected, f.displayProjections, "scrolling and repeated font measurement never call item display providers");
		var before = f.snapshot();
		var changed = new SpatialSelectorSession.InventoryRow<String>("k0",
			"A changed detached display name that has enough characters to fill the available GUI width", 100L,
			"minecraft:stone", Status.READY, "opaque:k0");
		f.pending = f.projection(List.of(changed)); f.frame(f.now + 1);
		assertEquals(projected + 1, f.displayProjections, "only the changed display identity is detached again");
		assertTrue(f.geometry.width() > beforeWidth);
		assertTrue(f.geometry.width() <= 480);
		assertEquals(before.inventory().selectedKey(), f.snapshot().inventory().selectedKey());
		assertEquals(before.radial().pointer(), f.snapshot().radial().pointer());
		assertEquals(before.radial().trail(), f.snapshot().radial().trail());
		assertTrue(f.menu().menuId().endsWith(":inventory"), "content remeasurement is never navigation input");
	}

	@Test
	void guiScaleHandoffAtAnUnchangedNativeWindowPreparesCapacityWithoutSyntheticInput() {
		var f = new Fixture(1000, 700, true, 0, 18, false, true, 0.30, 0.60);
		f.openList(false); f.scroll(15); f.settle();
		var before = f.snapshot();
		var oldNative = f.nativeFrame;
		f.nativeFrame = new NativeSelectorInput.Frame(7, oldNative.windowWidth(), oldNative.windowHeight(),
			500, 350, true, false, true);
		f.input.synchronize(f.nativeFrame); f.input.prime(f.rawX, f.rawY, f.nativeFrame);
		f.frame(f.now + 1);
		var after = f.snapshot();
		assertEquals(before.inventory().entries(), after.inventory().entries());
		assertEquals(before.inventory().selectedKey(), after.inventory().selectedKey());
		assertEquals(List.of(after.radial().pointer()), after.radial().trail(), "scale alignment resets the trail epoch");
		assertTrue(f.menu().menuId().endsWith(":inventory"));
		assertTrue(f.geometry.width() <= 150, "the content width respects the scaled viewport budget");
		assertTrue(after.inventoryView().visibleRows() < before.inventoryView().visibleRows());
		f.move(0, 14, f.now + 1);
		f.moveToOption(16);
		assertEquals("k16", f.snapshot().inventory().selectedKey(), "the first real scaled sample moves exactly one row");
	}

	@Test
	void activeListGeometryRefreshPreservesTheRunningBackTimerAndFractionalWheelInput() {
		var f = new Fixture(1000, 700, true, 0, 18, true);
		f.openList(false);
		f.scroll(0.75);
		f.resize(640, 360);
		f.scroll(0.25);
		assertEquals("k1", f.snapshot().inventory().selectedKey(), "resize does not drop the ordinary-wheel remainder");
		f.side(f.backSign(), f.settings.deadzone() + 1);
		long started = f.now;
		f.frame(started + f.settings.hoverMillis() / 2);
		var before = f.snapshot();
		assertEquals(0.5, before.radial().hoverProgress(), 0.01);
		var next = SpatialSelectorSession.ListGeometry.fromMetrics(SpatialInventoryLayout.measure(640, 360, 12, 1),
			f.geometry.childClearance());
		f.session.updateListGeometry(next, f.now);
		var after = f.session.snapshot();
		assertEquals(before.radial(), after.radial(), "font/capacity changes add neither input nor Back timer progress");
		assertEquals(before.inventory().selectedKey(), after.inventory().selectedKey());
		assertEquals(before.inventory().entries(), after.inventory().entries());
		assertTrue(after.inventoryView().backAffordance().focused());
		assertTrue(after.inventoryView().visibleRows() < before.inventoryView().visibleRows());
		f.session.tick(started + f.settings.hoverMillis());
		assertTrue(f.session.snapshot().radial().menus().getLast().menuId().endsWith(":content"),
			"relayout does not restart or fire the established running list Back timer");
	}

	@ParameterizedTest
	@CsvSource({"false", "true"})
	void quantityShrinkAndDiscoveryKeepEveryPaintedPanelClearOfTheActiveItemInBothDirections(boolean leftEntry) {
		var f = new Fixture(1600, 1000, false, 0);
		f.painter.localizedInventoryLabels = true;
		var initial = f.projection(List.of(f.row("k0", Long.MAX_VALUE)));
		f.pending = new SpatialSelectorSession.ContentProjection<>(initial.fence(), initial.revision(), true,
			initial.status(), initial.properties(), initial.inventory());
		f.frame(f.now + 1);
		f.openList(false, leftEntry);
		f.side(leftEntry ? -1 : 1, f.settings.stroke() + 1);
		f.settle();
		var before = f.snapshot();
		String item = f.menu().menuId();
		assertTrue(item.contains(":item:"));
		assertEquals("k0", before.inventoryView().retainedRowKey());
		double wide = f.geometry.width();
		assertItemClearOfEveryPaintedPanel(f, item, "old settled");

		// A legal sparse update shrinks the quantity and discovers a second row
		// in the same tick, changing both natural width and panel row count.
		f.pending = f.projection(List.of(f.row("k0", 1), f.row("k1", 1)));
		long changed = f.now + 1;
		for (long elapsed : new long[] {0, 80, 160}) {
			f.frame(changed + elapsed);
			var after = f.snapshot();
			assertEquals(item, f.menu().menuId());
			assertEquals(before.inventory().selectedKey(), after.inventory().selectedKey());
			assertEquals(before.inventoryView().retainedRowKey(), after.inventoryView().retainedRowKey());
			assertEquals(before.inventoryView().selectedRowCenterY(), after.inventoryView().selectedRowCenterY());
			assertEquals(before.radial().pointer(), after.radial().pointer(), "item content relayout moves no pointer");
			assertEquals(before.radial().trail(), after.radial().trail(), "content relayout is not physical input");
			assertEquals(2, after.inventory().entries().size());
			assertTrue(f.geometry.width() < wide, "the shared content width genuinely shrinks instead of retaining old clearance width");
			assertItemClearOfEveryPaintedPanel(f, item, "content shrink at " + elapsed);
		}
		var finalPanel = f.painter.draws.stream().filter(draw -> draw.state().present()
			&& draw.state().data() instanceof SpatialOverlayRenderer.PanelPaint).findFirst().orElseThrow();
		assertEquals(f.geometry.width(), ((SpatialOverlayRenderer.PanelPaint) finalPanel.state().data()).width(),
			"the settled painted surface uses the new shared width, not a permanently cached long quantity");
	}

	@ParameterizedTest
	@CsvSource({"false, false", "false, true", "true, false", "true, true"})
	void resizingAnActiveItemRetainsItsFrozenRowAndUsesTheNewPanelEdgeInBothDirections(boolean leftEntry, boolean reduced) {
		var f = new Fixture(1000, 700, reduced, 0, 18, false, true, 0.65, 0.85);
		f.painter.localizedInventoryLabels = true;
		publishLongNames(f);
		f.openList(false, leftEntry); f.scroll(15); f.settle();
		double forward = leftEntry ? -1 : 1;
		f.side(forward, f.settings.stroke() + 1); f.settle();
		var before = f.snapshot();
		String item = f.menu().menuId();
		double anchor = before.inventoryView().selectedRowCenterY();
		f.resize(320, 240);
		var after = f.snapshot();
		assertEquals(item, f.menu().menuId(), "resize never navigates or changes the entered item");
		assertEquals(before.inventory().selectedKey(), after.inventory().selectedKey());
		assertEquals(before.inventory().entries(), after.inventory().entries());
		assertEquals("k15", after.inventoryView().retainedRowKey());
		assertEquals(anchor, after.inventoryView().selectedRowCenterY());
		assertEquals(before.radial().trail(), after.radial().trail());
		var layout = SpatialOverlayRenderer.inventoryLayout(after.inventoryView(), 320, 240, f.painter.lineHeight(), f.style, f.painter::width);
		assertEquals(208, layout.width(), "resized paint and item placement use the custom held width");
		assertEquals(7, after.inventoryView().visibleRows(), "resized list capacity uses the custom held height");
		assertEquals(anchor, layout.rowCenterY(after.inventory().selectedIndex()) - 120, 1e-9);
		assertEquals(layout.width() / 2 + f.geometry.childClearance(),
			forward * (f.menu().origin().x() - after.inventory().axisX()), 1e-9,
			"the child and painter consume the same newly measured content edge");
		assertEquals(List.of("k15"), f.painter.selectedRows, "only the frozen entered row paints");
		f.settle();
		assertEquals(item, f.menu().menuId());
		assertSharedInventoryPanel(f);
		assertItemClearOfEveryPaintedPanel(f, item, "resized custom dimensions");
	}

	@Test
	void contentAndRootAncestorsKeepTheirBreadcrumbsWhileTheItemSubmenuKeepsTheListPanel() {
		var f = new Fixture(320, 240, true, 3, 18, false);
		f.openList(false);
		f.frame(f.now + 1);

		var menus = f.snapshot().radial().menus();
		assertEquals(3, menus.size());
		assertNull(menus.get(0).entryChoiceId(), "the root has no parent entry");
		assertEquals("content", menus.get(1).entryChoiceId());
		String inventoryId = menus.get(2).menuId();
		assertEquals(inventoryId, menus.get(2).entryChoiceId(), "the list surface records the content entry that opened it");
		String contentBack = menus.get(1).choices().stream().filter(SpatialController.ChoiceView::back)
			.findFirst().orElseThrow().id();

		assertTrue(f.painter.paintedNodes.contains("content"), "the root keeps only its content breadcrumb");
		assertTrue(f.painter.paintedNodes.contains(inventoryId), "the content keeps its inventory breadcrumb");
		assertTrue(f.painter.paintedNodes.contains(contentBack), "the content keeps its Back navigation");
		assertTrue(f.painter.paintedNodes.stream().noneMatch(id -> id.contains(":property:")),
			"hidden content options are not planned");
		assertTrue(f.painter.paintedNodes.stream().noneMatch(id -> List.of("danger", "precise", "intent", "settings",
			"cancel-marker", "reserved-ne", "reserved-se").contains(id)), "hidden root options are not planned");
		assertEquals(3, menus.get(1).choices().stream().filter(choice -> choice.id().contains(":property:")).count(),
			"the logical content choices are never removed");

		double forward = f.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		f.side(forward, f.settings.stroke() + 1);
		f.settle();
		assertTrue(f.menu().menuId().contains(":item:"), "a forward stroke opens the item submenu");
		f.frame(f.now + 1);
		assertEquals(List.of("k0"), f.painter.selectedRows, "the inventory list stays painted under the item submenu");
		assertTrue(f.painter.rows.stream().allMatch(row -> row.outlineColor() == null),
			"the retained item row does not advertise a direct list release");
		assertTrue(f.painter.paintedNodes.contains(inventoryId), "the content ancestor keeps its inventory breadcrumb");
		assertTrue(f.painter.paintedNodes.stream().noneMatch(id -> id.contains(":property:")));
		assertEquals(3, f.snapshot().radial().menus().get(1).choices().stream()
			.filter(choice -> choice.id().contains(":property:")).count(), "navigation remains complete");
	}
}
