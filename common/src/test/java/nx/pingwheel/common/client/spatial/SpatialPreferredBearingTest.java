package nx.pingwheel.common.client.spatial;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A bearing preference permutes choices, never the parent-relative equal-sector geometry. */
class SpatialPreferredBearingTest {

	private static SpatialController controller(SpatialMenu child, double entryBearing) {
		var controller = new SpatialController(SpatialMenu.of("root",
			SpatialMenu.Choice.branch("open", "open", child).withSector(45, 35)),
			new SpatialController.Tuning(10, 40, 100, false, 500));
		controller.start(0);
		double radians = Math.toRadians(entryBearing);
		controller.movePhysical(Math.sin(radians) * 200, -Math.cos(radians) * 200, 10);
		controller.tick(110);
		assertEquals(2, controller.snapshot().menus().size());
		return controller;
	}

	private static List<SpatialMenu.Choice> entries(int siblings) {
		List<SpatialMenu.Choice> choices = new ArrayList<>();
		for (int index = 0; index < siblings; index++)
			choices.add(SpatialMenu.Choice.leaf("sibling:" + index, "ordinary", "action:" + index));
		choices.add(SpatialMenu.Choice.leaf("opaque-choice", "not an inventory label", "preferred-action"));
		return choices;
	}

	private static double normalize(double degrees) { return (degrees % 360 + 360) % 360; }

	@Test
	void varyingEntryAnglesAndCountsKeepBackAndEverySlotWhileRightSelectsThePreferredChoice() {
		for (int siblings = 0; siblings <= 12; siblings++) {
			for (double entryBearing : new double[] {27.5, 35, 45, 55, 62.4999}) {
				var menu = new SpatialMenu("child", entries(siblings));
				var original = controller(menu, entryBearing).snapshot().menus().getLast();
				var controller = controller(menu.withPreferredBearing("opaque-choice", 90), entryBearing);
				var resolved = controller.snapshot().menus().getLast();
				assertEquals(original.choices().stream().map(SpatialController.ChoiceView::startDegrees).toList(),
					resolved.choices().stream().map(SpatialController.ChoiceView::startDegrees).toList(), "no slot rotates");
				assertEquals(menu.choices().stream().filter(choice -> !choice.id().equals("opaque-choice")).map(SpatialMenu.Choice::id).toList(),
					resolved.choices().stream().filter(choice -> !choice.back() && !choice.id().equals("opaque-choice"))
						.map(SpatialController.ChoiceView::id).toList(), "all other choices retain relative order");
				var back = resolved.choices().getLast();
				assertTrue(back.back());
				assertEquals(entryBearing + 180, normalize(back.startDegrees() + back.spanDegrees() / 2), 1e-9);
				var starts = resolved.choices().stream().map(SpatialController.ChoiceView::startDegrees).sorted().toList();
				for (int index = 0; index < starts.size(); index++) {
					double next = index + 1 == starts.size() ? starts.getFirst() + 360 : starts.get(index + 1);
					assertEquals(360.0 / starts.size(), next - starts.get(index), 1e-9, "full-circle tiling without overlap");
					assertEquals(360.0 / starts.size(), resolved.choices().get(index).spanDegrees(), 1e-9);
				}
				controller.rebase(resolved.origin().x() + 120, resolved.origin().y(), 120);
				assertEquals("opaque-choice", controller.snapshot().focusId(), "exactly right uses the selected equal slot");
				assertEquals(new SpatialController.Release.Committed("opaque-choice", "preferred-action"), controller.release(121));
			}
		}
	}

	@Test
	void fourSlotTieChoosesTheStartInclusiveRightSectorWithoutForcingItsCentreRight() {
		var controller = controller(new SpatialMenu("child", entries(2)).withPreferredBearing("opaque-choice", 90), 45);
		var menu = controller.snapshot().menus().getLast();
		var preferred = menu.choices().stream().filter(choice -> choice.id().equals("opaque-choice")).findFirst().orElseThrow();
		assertEquals(90, preferred.startDegrees(), 1e-9);
		assertEquals(135, normalize(preferred.startDegrees() + preferred.spanDegrees() / 2), 1e-9,
			"the equal-slot centre stays diagonal; it is not a forced anchor");
		controller.rebase(menu.origin().x() + 120, menu.origin().y() - 0.001, 120);
		assertEquals("sibling:1", controller.snapshot().focusId(), "just before the boundary belongs to the previous slot");
		controller.rebase(menu.origin().x() + 120, menu.origin().y(), 121);
		assertEquals("opaque-choice", controller.snapshot().focusId(), "the exact boundary belongs to its starting sector");
	}

	@Test
	void explicitBackKeepsItsIndexEvenWhenTheChoiceMovesAcrossItAndNearestAvailableSlotWins() {
		var choices = List.of(SpatialMenu.Choice.leaf("a", "a", "a"), SpatialMenu.Choice.back("back"),
			SpatialMenu.Choice.leaf("b", "b", "b"), SpatialMenu.Choice.disabled("preferred", "disabled"));
		var menu = new SpatialMenu("child", choices).withPreferredBearing("preferred", 230);
		var controller = controller(menu, 45);
		var resolved = controller.snapshot().menus().getLast();
		assertEquals(List.of("a", "back", "preferred", "b"), resolved.choices().stream().map(SpatialController.ChoiceView::id).toList(),
			"230 is inside Back, so its nearest non-Back centre at 315 wins");
		assertEquals(225, normalize(resolved.choices().get(1).startDegrees() + 45), 1e-9);
		assertTrue(resolved.choices().get(2).disabled(), "disabled choices still receive the layout preference");
		var acrossBack = controller(new SpatialMenu("child", choices).withPreferredBearing("preferred", 100), 45)
			.snapshot().menus().getLast();
		assertEquals(List.of("preferred", "back", "a", "b"), acrossBack.choices().stream().map(SpatialController.ChoiceView::id).toList());
		var forwardsAcrossBack = controller(new SpatialMenu("child", choices).withPreferredBearing("a", 315), 45)
			.snapshot().menus().getLast();
		assertEquals(List.of("b", "back", "a", "preferred"),
			forwardsAcrossBack.choices().stream().map(SpatialController.ChoiceView::id).toList(),
			"moving forward across Back also preserves every other non-Back choice's relative order");
	}

	@Test
	void absentOrBackPreferenceIsInertAndRootCallerGeometryRemainsAuthoritative() {
		var choices = entries(2);
		choices.add(1, SpatialMenu.Choice.back("back"));
		var menu = new SpatialMenu("child", choices);
		var original = controller(menu, 45).snapshot();
		assertEquals(original, controller(menu.withPreferredBearing("absent", 90), 45).snapshot());
		assertEquals(original, controller(menu.withPreferredBearing("back", 90), 45).snapshot());
		var root = SpatialMenu.of("root", SpatialMenu.Choice.leaf("leaf", "leaf", "action").withSector(0, 55));
		var controller = new SpatialController(root.withPreferredBearing("leaf", 90), new SpatialController.Tuning(10, 40, 100, false, 500));
		controller.start(0);
		assertEquals(332.5, controller.snapshot().menus().getFirst().choices().getFirst().startDegrees());
		controller.movePhysical(100, 0, 10);
		assertNull(controller.snapshot().focusId());
		assertThrows(IllegalArgumentException.class, () -> menu.withPreferredBearing("leaf", Double.NaN));
	}

	@Test
	void preferenceDoesNotChangeTheActualParentDirectedReverseStroke() {
		var controller = controller(new SpatialMenu("child", entries(2)).withPreferredBearing("opaque-choice", 90), 45);
		var origin = controller.snapshot().menus().getLast().origin();
		controller.movePhysical(-Math.sqrt(0.5) * 67, Math.sqrt(0.5) * 67, 120);
		assertEquals(2, controller.snapshot().menus().size());
		controller.movePhysical(-Math.sqrt(0.5) * 2, Math.sqrt(0.5) * 2, 121);
		assertEquals(1, controller.snapshot().menus().size(), "the unchanged reverse corridor returns one level");
		assertNotEquals(origin, controller.snapshot().pointer());
		assertEquals(controller.snapshot().pointer(), controller.snapshot().menus().getFirst().origin());
	}
}
