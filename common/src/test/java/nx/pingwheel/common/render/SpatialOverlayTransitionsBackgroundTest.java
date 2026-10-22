package nx.pingwheel.common.render;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent background-coverage channel of the shared transition model: the
 * historical constructors delegate the foreground alpha, the channel has its own
 * zero appearance and exit endpoints, eases from the displayed coverage on a
 * retarget, snaps under reduced motion, never rewinds, and reaches every emitted
 * state. Oracle coverages are caller-set constants, not production planning.
 */
class SpatialOverlayTransitionsBackgroundTest {

	private static SpatialOverlayTransitions<String, String> model(int capacity) {
		return new SpatialOverlayTransitions<>(1000L, capacity, 0.5);
	}

	private static SpatialOverlayTransitions.Target<String, String> target(String key) {
		return new SpatialOverlayTransitions.Target<>(key, 20.0, 40.0, 2.0, 0.5, 0.0, 10.0, key, null, 0.8);
	}

	private static SpatialOverlayTransitions.State<String, String> only(
		List<SpatialOverlayTransitions.State<String, String>> states) {
		assertEquals(1, states.size());
		return states.getFirst();
	}

	@Test
	void historicalConstructorsDelegateTheBackgroundChannelFromTheForegroundAlpha() {
		var legacy = new SpatialOverlayTransitions.Target<>("a", 20.0, 40.0, 2.0, 0.8, 0.0, 10.0, "a");
		assertEquals(0.8, legacy.backgroundAlpha());
		assertEquals(legacy.alpha(), legacy.backgroundAlpha());
		var legacyRay = new SpatialOverlayTransitions.Target<>("a", 20.0, 40.0, 2.0, 0.25, 0.0, 10.0, "a",
			new SpatialOverlayTransitions.Ray(0.0, 0.0, 1.0, 0.0));
		assertEquals(0.25, legacyRay.backgroundAlpha());
		var state = new SpatialOverlayTransitions.State<>("a", 1.0, 2.0, 1.0, 0.6, true, "a");
		assertEquals(0.6, state.backgroundAlpha());
	}

	@Test
	void coverageBoundsAreValidatedAsANormalizedChannel() {
		assertThrows(IllegalArgumentException.class, () -> new SpatialOverlayTransitions.Target<>(
			"a", 1.0, 1.0, 1.0, 0.5, 0.0, 0.0, "a", null, 1.4));
		assertThrows(IllegalArgumentException.class, () -> new SpatialOverlayTransitions.Target<>(
			"a", 1.0, 1.0, 1.0, 0.5, 0.0, 0.0, "a", null, -0.1));
	}

	@Test
	void appearanceStartsAtZeroCoverageAndFollowsItsOwnFiniteInterval() {
		var model = model(8);
		var shown = only(model.update(List.of(target("a")), 0L, false));
		assertEquals(0.0, shown.alpha());
		assertEquals(0.0, shown.backgroundAlpha(), "an appearance starts at zero background coverage");
		var half = only(model.update(List.of(target("a")), 500L, false));
		assertEquals(0.25, half.alpha(), 1.0e-12);
		assertEquals(0.4, half.backgroundAlpha(), 1.0e-12, "the channel eases on the shared finite interval");
		assertEquals(0.8, only(model.update(List.of(target("a")), 1000L, false)).backgroundAlpha());
	}

	@Test
	void retargetingEasesFromTheDisplayedCoverageAndASeparateAlphaNeverRestartsIt() {
		var model = model(8);
		model.update(List.of(target("a")), 0L, false);
		var settled = only(model.update(List.of(target("a")), 1000L, false));
		assertEquals(0.8, settled.backgroundAlpha());
		// The foreground alpha changes by itself; the coverage keeps its own target.
		var foregroundOnly = new SpatialOverlayTransitions.Target<>("a", 20.0, 40.0, 2.0, 1.0, 0.0, 10.0, "a", null, 0.8);
		var start = only(model.update(List.of(foregroundOnly), 1000L, false));
		assertEquals(0.8, start.backgroundAlpha(), "an unchanged coverage target never restarts its interval");
		var raised = new SpatialOverlayTransitions.Target<>("a", 20.0, 40.0, 2.0, 1.0, 0.0, 10.0, "a", null, 0.9);
		assertEquals(0.8, only(model.update(List.of(raised), 1000L, false)).backgroundAlpha(), 1.0e-12);
		assertEquals(0.85, only(model.update(List.of(raised), 1500L, false)).backgroundAlpha(), 1.0e-12,
			"the raised coverage eases from the displayed 0.8");
		assertEquals(0.9, only(model.update(List.of(raised), 2000L, false)).backgroundAlpha(), 1.0e-12);
	}

	@Test
	void exitFadesCoverageToZeroAndKeepsTheDetachedDataUntilItsDeadline() {
		var model = model(8);
		model.update(List.of(target("a")), 0L, true);
		var exiting = only(model.update(List.of(), 100L, false));
		assertFalse(exiting.present());
		assertEquals(0.8, exiting.backgroundAlpha(), "the exit starts at the displayed coverage");
		assertEquals("a", exiting.data());
		assertEquals(0.4, only(model.update(List.of(), 600L, false)).backgroundAlpha(), 1.0e-12);
		assertTrue(model.update(List.of(), 1100L, false).isEmpty());
	}

	@Test
	void reappearanceResumesFromTheDisplayedDetachedCoverage() {
		var model = model(8);
		model.update(List.of(target("a")), 0L, true);
		model.update(List.of(), 100L, false);
		var before = only(model.update(List.of(), 600L, false)).backgroundAlpha();
		var reappeared = only(model.update(List.of(target("a")), 600L, false));
		assertTrue(reappeared.present());
		assertEquals(before, reappeared.backgroundAlpha(), 1.0e-12);
		assertEquals(0.6, only(model.update(List.of(target("a")), 1100L, false)).backgroundAlpha(), 1.0e-12);
		assertEquals(0.8, only(model.update(List.of(target("a")), 1600L, false)).backgroundAlpha(), 1.0e-12);
	}

	@Test
	void reducedMotionSnapsTheChannelAndDropsTheExitTail() {
		var model = model(8);
		var shown = only(model.update(List.of(target("a")), 0L, true));
		assertEquals(0.8, shown.backgroundAlpha(), "reduced motion snaps the channel with the rest of the pose");
		assertTrue(model.update(List.of(), 1L, true).isEmpty());
	}

	@Test
	void rewindCannotRewindTheDisplayedCoverageAndALargeGapCompletesIt() {
		var model = model(8);
		var targets = List.of(target("a"));
		model.update(targets, 0L, false);
		var half = model.update(targets, 500L, false);
		assertEquals(half, model.update(targets, 100L, false), "a rewound clock cannot rewind the channel");
		assertEquals(0.8, only(model.update(targets, 1_000_000L, false)).backgroundAlpha());
	}
}
