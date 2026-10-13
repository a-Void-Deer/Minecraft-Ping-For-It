package nx.pingwheel.common.client.spatial;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.config.SpatialSelectorSettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.render.SpatialInventoryView.Status;

import static org.junit.jupiter.api.Assertions.*;

class NativeSelectorInputTest {
	private static final class Sink implements NativeSelectorInput.Sink {
		final List<SpatialController.Point> moves = new ArrayList<>();
		final List<Double> rows = new ArrayList<>();
		public void moveGui(double x, double y, long now) { moves.add(new SpatialController.Point(x, y)); }
		public void scrollRows(double delta, long now) { rows.add(delta); }
	}
	private static NativeSelectorInput.Frame frame(int width, int guiWidth, boolean focused, boolean grabbed) {
		return new NativeSelectorInput.Frame(7, width, 800, guiWidth, 400, focused, grabbed);
	}

	@Test void nativeOwnershipGatesMovementAndEveryScrollWithoutChangingVanillaCapture() {
		Sink sink = new Sink(); NativeSelectorInput input = new NativeSelectorInput(sink); input.open();
		var owned = frame(1200, 600, true, false);
		input.prime(600, 400, owned); input.onMove(7, 620, 410, owned, 1);
		var handoff = new NativeSelectorInput.Frame(7, 1200, 800, 600, 400, true, false, false);
		assertFalse(input.onMove(7, -4000, 3000, handoff, 2));
		assertFalse(input.onScroll(7, 4, 0, handoff, 3)); assertFalse(input.onScroll(7, 0, 1, handoff, 4));
		input.prime(-4000, 3000, owned); input.onMove(7, -3980, 3010, owned, 5);
		assertEquals(List.of(new SpatialController.Point(10, 5), new SpatialController.Point(10, 5)), sink.moves);
		assertTrue(sink.rows.isEmpty());
	}

	@Test
	void onlyAcceptedSuccessiveWindowPositionsBecomeGuiTravel() {
		Sink sink = new Sink();
		NativeSelectorInput input = new NativeSelectorInput(sink);
		var frame = frame(1200, 600, true, false);
		assertFalse(input.onMove(7, 400, 300, frame, 0));
		input.open();
		assertFalse(input.onMove(8, 900, 900, frame, 1));
		assertTrue(input.onMove(7, 400, 300, frame, 2));
		assertTrue(sink.moves.isEmpty(), "opening/cursor warp primes without travel");
		input.onMove(8, 10000, 10000, frame, 3);
		input.onMove(7, 420, 310, frame, 4);
		assertEquals(List.of(new SpatialController.Point(10, 5)), sink.moves);
	}

	@Test
	void resizeScaleCaptureFocusAndReopenReprimeWithoutPhantomTravel() {
		Sink sink = new Sink();
		NativeSelectorInput input = new NativeSelectorInput(sink);
		input.open();
		input.onMove(7, 100, 100, frame(1000, 500, true, false), 0);
		input.onMove(7, 900, 900, frame(1000, 250, true, false), 1);
		input.onMove(7, 700, 700, frame(1400, 250, true, false), 2);
		input.synchronize(frame(1400, 250, true, true));
		assertFalse(input.onMove(7, 10000, 10000, frame(1400, 250, true, true), 3));
		input.onMove(7, 500, 500, frame(1400, 250, true, false), 4);
		input.synchronize(frame(1400, 250, false, false));
		input.onMove(7, 800, 800, frame(1400, 250, true, false), 5);
		input.release(); input.open();
		input.onMove(7, 900, 900, frame(1400, 250, true, false), 6);
		input.rePrime();
		input.onMove(7, 100, 100, frame(1400, 250, true, false), 7);
		assertTrue(sink.moves.isEmpty());
		input.onMove(7, 114, 108, frame(1400, 250, true, false), 8);
		assertEquals(new SpatialController.Point(2.5, 4), sink.moves.getFirst());
	}

	@Test
	void ownedGuiScaledNativePositionsContinueListGlideAtEdgesWithoutHandoffTravel() {
		var target = new SpatialSelectorSession.CapturedTarget("ordinary", new ResolvedTarget(
			new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"),
			TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow()), Optional.of(BlockFace.NORTH), Optional.empty());
		var fence = new SpatialSelectorSession.ContentFence(31, 42, 53, "ordinary");
		var attention = PingTypeCatalog.builtIn().findById("attention").orElseThrow();
		var preview = new SpatialSelectorSession.InventoryPreview<String>("inventory", Status.READY,
			List.of(new SpatialSelectorSession.InventoryRow<>("a", "a", 3L, "minecraft:stone", Status.READY, "opaque:a"),
				new SpatialSelectorSession.InventoryRow<>("b", "b", 2L, "minecraft:stone", Status.READY, "opaque:b"),
				new SpatialSelectorSession.InventoryRow<>("c", "c", 1L, "minecraft:stone", Status.READY, "opaque:c")),
			List.of(attention), attention);
		var preferences = new SpatialSelectorSettings();
		preferences.setTargetGlide(new BigDecimal("1")); // the explicit calibration factor this native boundary checks
		var session = new SpatialSelectorSession<>(target, Map.of(), preferences.snapshot(), fence,
			(ignored, requestedFence) -> new SpatialSelectorSession.ContentProjection<>(fence, 1, false, Status.READY, List.of(), preview),
			new SpatialSelectorSession.ListGeometry(3, 180, 17, 20, 19, 85));
		session.open(0); session.moveGui(100, 0, 1); session.tick(181);
		session.moveGui(100, 0, 182); session.tick(362);
		assertTrue(session.snapshot().inventory().open());
		double axis = session.snapshot().radial().pointer().x();
		session.constrainPointer(new SpatialController.Viewport(1, 1, -axis - 0.5, -0.5), 363);
		var pointer = session.snapshot().radial().pointer(); var trail = session.snapshot().radial().trail();
		var owned = frame(1200, 600, true, false);
		var input = new NativeSelectorInput(session); input.open();
		input.onMove(7, 100, 100, owned, 364); // prime, not input
		input.onMove(8, 100, 10000, owned, 365); // foreign window, not a new baseline
		input.onMove(7, 100, 178, owned, 366); // 78 window px = 39 GUI px
		assertEquals("a", session.snapshot().inventory().selectedKey());
		input.onMove(7, 100, 180, owned, 367);
		assertEquals("b", session.snapshot().inventory().selectedKey());
		var rescaled = new NativeSelectorInput.Frame(7, 1200, 800, 300, 200, true, false);
		input.onMove(7, -4000, -4000, rescaled, 368);
		assertEquals("b", session.snapshot().inventory().selectedKey(), "scale handoff cannot synthesize reverse glide");
		input.onMove(7, -4000, -3840, rescaled, 369); // 160 window px = 40 GUI px
		assertEquals("c", session.snapshot().inventory().selectedKey());
		var unowned = new NativeSelectorInput.Frame(7, 1200, 800, 300, 200, true, false, false);
		assertFalse(input.onMove(7, 5000, 5000, unowned, 370));
		input.onMove(7, 2000, 2000, rescaled, 371);
		assertEquals("c", session.snapshot().inventory().selectedKey());
		input.onMove(7, 2000, 1840, rescaled, 372);
		assertEquals("b", session.snapshot().inventory().selectedKey(), "native reversal is relative even at a fixed drawn pointer");
		input.synchronize(new NativeSelectorInput.Frame(7, 1200, 800, 300, 200, false, false));
		input.onMove(7, -1000, -1000, rescaled, 373);
		assertEquals("b", session.snapshot().inventory().selectedKey(), "focus handoff primes without travel");
		assertEquals(pointer, session.snapshot().radial().pointer());
		assertEquals(trail, session.snapshot().radial().trail());
		var intent = assertInstanceOf(SelectorIntent.SelectInventory.class, session.releaseIntent(374));
		assertEquals("opaque:b", intent.reference());
	}

	@Test
	void fractionalNativeDeltasAccumulateIntoExactlyOneParentReturn() {
		var target = new SpatialSelectorSession.CapturedTarget("ordinary", new ResolvedTarget(
			new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"),
			TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow()), Optional.of(BlockFace.NORTH), Optional.empty());
		var fence = new SpatialSelectorSession.ContentFence(31, 42, 53, "ordinary");
		var attention = PingTypeCatalog.builtIn().findById("attention").orElseThrow();
		var preview = new SpatialSelectorSession.InventoryPreview<String>("inventory", Status.READY,
			List.of(new SpatialSelectorSession.InventoryRow<>("a", "a", 3L, "minecraft:stone", Status.READY, "opaque:a")),
			List.of(attention), attention);
		var preferences = new SpatialSelectorSettings();
		preferences.setDeadzone(18); // explicit geometry and timing, not an accidental default
		preferences.setStroke(50);
		preferences.setDwellMillis(180);
		preferences.setHoverEnabled(false);
		var session = new SpatialSelectorSession<String>(target, Map.of(), preferences.snapshot(), fence,
			(ignored, requestedFence) -> new SpatialSelectorSession.ContentProjection<>(fence, 1, false, Status.READY,
				List.of(), preview),
			new SpatialSelectorSession.ListGeometry(3, 180, 17, 20, 19, 85));
		session.open(0); session.moveGui(200, 0, 1); session.tick(181);
		assertEquals(2, session.snapshot().radial().menus().size(), "helper precondition: content entered");

		var owned = new NativeSelectorInput.Frame(7, 1200, 800, 600, 400, true, false);
		var input = new NativeSelectorInput(session);
		input.open();
		input.prime(600, 400, owned); // priming the cursor warp contributes no travel
		for (int windowPixel = 1; windowPixel <= 136; windowPixel++) {
			input.onMove(7, 600 - windowPixel, 400, owned, 182L + windowPixel); // one window pixel is half a GUI pixel
		}

		var returned = session.snapshot().radial();
		assertEquals(1, returned.menus().size(), "fractional native samples accumulate into one return");
		assertEquals(132.0, returned.pointer().x(), 1e-9, "the pointer stops exactly at the net return distance");
		for (int windowPixel = 1; windowPixel <= 40; windowPixel++) {
			input.onMove(7, 600 - 136 - windowPixel, 400, owned, 318L + windowPixel);
		}
		assertEquals(1, session.snapshot().radial().menus().size(), "further reverse samples cannot pop another level");
	}

	@Test
	void endlessAlternatingGuiCallbacksCannotDriftTheFixedListPointerOrSelection() {
		var target = new SpatialSelectorSession.CapturedTarget("ordinary", new ResolvedTarget(
			new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"),
			TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow()), Optional.of(BlockFace.NORTH), Optional.empty());
		var fence = new SpatialSelectorSession.ContentFence(31, 42, 53, "ordinary");
		var attention = PingTypeCatalog.builtIn().findById("attention").orElseThrow();
		var preview = new SpatialSelectorSession.InventoryPreview<String>("inventory", Status.READY,
			List.of(new SpatialSelectorSession.InventoryRow<>("a", "a", 3L, "minecraft:stone", Status.READY, "opaque:a"),
				new SpatialSelectorSession.InventoryRow<>("b", "b", 2L, "minecraft:stone", Status.READY, "opaque:b")),
			List.of(attention), attention);
		var preferences = new SpatialSelectorSettings();
		preferences.setTargetGlide(new BigDecimal("1"));
		var session = new SpatialSelectorSession<>(target, Map.of(), preferences.snapshot(), fence,
			(ignored, requestedFence) -> new SpatialSelectorSession.ContentProjection<>(fence, 1, false, Status.READY, List.of(), preview),
			new SpatialSelectorSession.ListGeometry(3, 180, 17, 20, 19, 85));
		session.open(0); session.moveGui(100, 0, 1); session.tick(181);
		session.moveGui(100, 0, 182); session.tick(362);
		assertTrue(session.snapshot().inventory().open());
		var before = session.snapshot().radial();

		var owned = frame(1200, 600, true, false);
		var input = new NativeSelectorInput(session);
		input.open(); input.prime(600, 400, owned);
		for (int sample = 0; sample < 2000; sample++) {
			input.onMove(7, 600 + (sample % 2 == 0 ? 2 : 0), 400, owned, 400L + sample);
		}

		assertEquals(before.pointer(), session.snapshot().radial().pointer(), "endless callbacks never move the fixed pointer");
		assertEquals(before.trail(), session.snapshot().radial().trail(), "list input appends no trail");
		assertEquals("a", session.snapshot().inventory().selectedKey());
		assertEquals(0.0, session.snapshot().radial().hoverProgress());
	}

	@Test
	void scrollNormalizationOccursOnceOnlyWhenOwnedAndDoesNotMovePointer() {
		Sink sink = new Sink();
		NativeSelectorInput input = new NativeSelectorInput(sink);
		var frame = frame(1200, 600, true, false);
		assertFalse(input.onScroll(7, 0, 1, frame, 0));
		input.open();
		input.onMove(7, 20, 20, frame, 0);
		assertFalse(input.onScroll(8, 0, 3, frame, 1));
		assertTrue(input.onScroll(7, 4, 0.5, frame, 2));
		assertTrue(input.onScroll(7, 4, -2, frame, 3));
		input.onMove(7, 20, 20, frame, 4);
		assertEquals(List.of(-0.5, 2.0), sink.rows);
		assertTrue(sink.moves.isEmpty());
		input.release();
		assertFalse(input.onScroll(7, 0, 1, frame, 5));
	}
}
