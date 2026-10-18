package nx.pingwheel.common.client.spatial;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import nx.pingwheel.common.config.SpatialSelectorSettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.Candidate;
import nx.pingwheel.common.interaction.candidate.CaptureEquivalenceKey;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.render.SpatialInventoryView.Status;

import static org.junit.jupiter.api.Assertions.*;

class SpatialSelectorSessionTest {
	private static final SpatialSelectorSession.ListGeometry GEOMETRY =
		new SpatialSelectorSession.ListGeometry(4, 180, 17, 23, 19, 85);

	@Test
	void unreachableAxisCorrectionPreservesSubrowRemainderWithoutAnyProjectionSelection() {
		var session = openedList(false, new Content());
		session.moveGui(0, 91, 280); // 23px row, 0.5 user factor: one native row takes 92px
		var before = session.snapshot();
		var viewport = new SpatialController.Viewport(100, 100, -510, 0);
		session.constrainPointer(viewport, 290);
		assertPointer(session, 460, 0);
		assertEquals(before.inventory(), session.snapshot().inventory());
		assertEquals(before.radial().trail(), session.snapshot().radial().trail());
		assertEquals(current(session).menuId(), before.radial().menus().getLast().menuId());
		session.constrainPointer(viewport, 291);
		session.moveGui(0, 1, 300);
		assertEquals("b", session.snapshot().inventory().selectedKey(), "the almost-row fraction survives axis correction");
		assertTrue(current(session).menuId().endsWith(":inventory"));
	}

	@Test
	void unreachableAxisCorrectionPreservesSignedWheelUnlockAccumulation() {
		var session = openedList(false, new Content());
		session.scrollRows(1, 280);
		session.moveGui(0, 10, 281); session.moveGui(0, -4, 282); // signed unlock = +6
		var before = session.snapshot();
		var viewport = new SpatialController.Viewport(100, 100, -510, 0);
		session.constrainPointer(viewport, 290);
		assertEquals(before.inventory(), session.snapshot().inventory());
		assertEquals(before.radial().trail(), session.snapshot().radial().trail());
		session.moveGui(0, 7, 300); // still locked at +13
		session.moveGui(0, 1, 310); // +14 unlocks and seeds only this sample's glide
		session.constrainPointer(viewport, 311);
		session.moveGui(0, 90, 320);
		assertEquals("b", session.snapshot().inventory().selectedKey());
		session.moveGui(0, 1, 330);
		assertEquals("c", session.snapshot().inventory().selectedKey(), "projection never resets signed unlock travel");
		assertTrue(current(session).menuId().endsWith(":inventory"));
	}

	@Test
	void hugeAuthorizedVerticalTravelGlidesAtSaturationWithoutViewportSideTravel() {
		var session = openedList(false, new Content());
		// This fixture opens the list at (360,0). Its displayed axis is x=20.
		session.constrainPointer(new SpatialController.Viewport(40, 1, -360, -0.5), 280);
		assertPointer(session, 360, 0);
		session.moveGui(0, Double.MAX_VALUE, 290);
		assertPointer(session, 360, 0, "fixed list input never moves the logical pointer");
		assertEquals("c", session.snapshot().inventory().selectedKey(),
			"authorized finite vertical travel reaches the end with zero pointer travel");
		assertEquals(1, session.snapshot().radial().trail().size());
		var saturated = session.snapshot();
		session.moveGui(0, Double.MAX_VALUE, 310);
		assertEquals(saturated, session.snapshot(), "attempted rows at the endpoint change no pointer, trail or selection");
		session.moveGui(0, -1, 320);
		assertEquals("c", session.snapshot().inventory().selectedKey());
		session.moveGui(0, -Double.MAX_VALUE, 340);
		assertEquals("a", session.snapshot().inventory().selectedKey(), "huge reverse overshoot reaches the top");
		session.moveGui(0, 92, 350);
		assertTrue(session.snapshot().inventory().selectedIndex() >= 1,
			"a fresh 92px stroke glides again without inheriting the reverse overshoot");
		session.moveGui(0, -Double.MAX_VALUE, 360);
		assertEquals("a", session.snapshot().inventory().selectedKey());
		session.moveGui(0, -92, 370);
		assertEquals("a", session.snapshot().inventory().selectedKey(), "an attempted row at the top endpoint is not banked");
		assertTrue(current(session).menuId().endsWith(":inventory"), "vertical saturation never navigates");
	}

	@Test
	void nativeRowHeightUsesAuthorizedVerticalTravelBeyondBothVisibleBounds() {
		var session = openedList(false, new Content());
		// The frozen fixture uses 23px rows and explicit 0.5x: one row takes 92px.
		session.constrainPointer(new SpatialController.Viewport(40, 2, -360, -0.5), 280);
		session.moveGui(0, 91, 290);
		assertPointer(session, 360, 0);
		assertEquals("a", session.snapshot().inventory().selectedKey());
		var atBottom = session.snapshot().radial();
		session.moveGui(0, 1, 300);
		assertEquals("b", session.snapshot().inventory().selectedKey());
		assertEquals(atBottom.pointer(), session.snapshot().radial().pointer());
		assertEquals(atBottom.trail(), session.snapshot().radial().trail());
		session.moveGui(0, 92, 310);
		assertEquals("c", session.snapshot().inventory().selectedKey());
		assertEquals(atBottom.trail(), session.snapshot().radial().trail());
		session.moveGui(0, -92, 320);
		assertPointer(session, 360, 0);
		assertEquals("b", session.snapshot().inventory().selectedKey());
		var atTop = session.snapshot().radial();
		session.moveGui(0, -92, 330);
		assertEquals("a", session.snapshot().inventory().selectedKey());
		assertEquals(atTop.pointer(), session.snapshot().radial().pointer());
		assertEquals(atTop.trail(), session.snapshot().radial().trail());
		var intent = assertInstanceOf(SelectorIntent.SelectInventory.class, session.releaseIntent(340));
		assertEquals("opaque:a", intent.reference(), "edge gliding retains the opaque selection reference");
	}

	@Test
	void rawVerticalTravelCanUnlockWheelAtTheEdgeButProjectionCorrectionCannot() {
		var session = openedList(false, new Content());
		session.scrollRows(1, 280);
		var trail = session.snapshot().radial().trail();
		session.constrainPointer(new SpatialController.Viewport(40, 1, -360, -100), 290);
		assertPointer(session, 360, 99.5);
		assertEquals(trail, session.snapshot().radial().trail());
		assertEquals("b", session.snapshot().inventory().selectedKey());
		session.moveGui(0, 10, 300);
		assertEquals("b", session.snapshot().inventory().selectedKey());
		session.moveGui(0, 4, 310);
		session.moveGui(0, 87, 320);
		assertEquals("b", session.snapshot().inventory().selectedKey(), "correction did not contribute to unlock or glide");
		session.moveGui(0, 1, 330);
		assertEquals("c", session.snapshot().inventory().selectedKey());
		assertEquals(trail, session.snapshot().radial().trail());
		session.moveGui(0, -92, 340);
		assertEquals("b", session.snapshot().inventory().selectedKey());
	}

	@Test
	void reachableOffAxisPointerSuppressesRawOnlyVerticalTravelWithoutNavigation() {
		for (boolean hover : List.of(false, true)) {
			for (double sign : new double[] {-1, 1}) {
				var session = openedList(hover, new Content());
				String listMenu = current(session).menuId();
				var trail = session.snapshot().radial().trail();
				session.constrainPointer(new SpatialController.Viewport(401, 1, -360, -0.5), 280);
				session.moveGui(sign * 50, 0, 281);
				trail = session.snapshot().radial().trail();
				session.moveGui(0, 92, 281); // same timestamp isolates raw selection travel from elapsed dwell
				assertEquals(listMenu, current(session).menuId());
				assertEquals(trail, session.snapshot().radial().trail());
				assertEquals("a", session.snapshot().inventory().selectedKey(), "side-hint and Back-hover suppression remain intact");
				assertEquals(0, session.snapshot().radial().hoverProgress(), "raw travel never advances Back hover");
				session.tick(290);
				assertEquals(hover && sign < 0 ? 9.0 / 250.0 : 0.0,
					session.snapshot().radial().hoverProgress(), 1e-12,
					"genuine Back focus still accrues elapsed stationary dwell");
			}
		}
	}

	@Test
	void frozenSettingsDeadzoneReachesTheRendererReadModel() {
		var session = session(target("ordinary", "block", true), Map.of(), false, new Content());
		session.open(0);
		var snapshot = session.snapshot();

		assertEquals(30.0, snapshot.radial().deadzone(), 1e-12,
			"the renderer reads the frozen nondefault radius the interaction uses");
		assertEquals(snapshot.settings().deadzone(), snapshot.radial().deadzone(), 1e-12);
	}

	@Test
	void panAndResizeAlignListReleaseFocusWithoutGlideTrailOrAutomaticSideNavigation() {
		for (boolean hover : List.of(false, true)) {
			var session = openedList(hover, new Content());
			session.scrollRows(1, 280);
			var trail = session.snapshot().radial().trail();
			String listMenu = current(session).menuId();
			// C=(100,50), V=(-200,0): x=360 must become x=299 (screen x=199).
			session.constrainPointer(new SpatialController.Viewport(200, 100, -200, 0), 290);
			assertPointer(session, 299, 0);
			assertEquals("b", session.snapshot().inventory().selectedKey());
			assertEquals(trail, session.snapshot().radial().trail());
			assertEquals(listMenu, current(session).menuId());
			assertFalse(session.snapshot().inventoryView().backAffordance().focused(), "unreachable axis follows confinement without inventing Back focus");
			assertEquals(0, session.snapshot().radial().hoverProgress());
			var selected = assertInstanceOf(SelectorIntent.SelectInventory.class, session.releaseIntent(291));
			assertEquals("opaque:b", selected.reference(), "axis correction preserves the selected item without synthetic travel");
		}
	}

	@Test
	void viewportCorrectionNeverFabricatesSideProgressButAFreshRawStrokeStillEntersAnItem() {
		var session = openedList(false, new Content());
		String listMenu = current(session).menuId();
		var trail = session.snapshot().radial().trail();
		// Pan confines the pointer and unreachable gesture axis without navigation.
		session.constrainPointer(new SpatialController.Viewport(200, 100, -541, 0), 280);
		assertPointer(session, 441, 0);
		assertEquals(listMenu, current(session).menuId());
		assertEquals(trail, session.snapshot().radial().trail());
		session.moveGui(-10, 0, 290);
		assertPointer(session, 441, 0, "fixed list input never moves the logical pointer");
		assertEquals(listMenu, current(session).menuId(), "confinement and a small inward sample stay below the hint gate");
		assertEquals(trail, session.snapshot().radial().trail());
		session.moveGui(1, 0, 300);
		assertEquals(listMenu, current(session).menuId(), "the projection's former displacement is never inherited as progress");
		session.moveGui(85, 0, 310); // -10 + 1 + 85 = 76: the exit gate needs the full raw stroke
		assertTrue(current(session).menuId().contains(":item:"), "a full fresh raw stroke still enters an item");
		assertPointer(session, 535, -24);
		assertEquals(1, session.snapshot().radial().trail().size(), "external row rebase seeds exactly one point");
	}

	private static void assertPointer(SpatialSelectorSession<?> session, double x, double y) {
		var pointer = session.snapshot().radial().pointer();
		assertEquals(x, pointer.x(), 1e-9);
		assertEquals(y, pointer.y(), 1e-9);
	}
	private static void assertPointer(SpatialSelectorSession<?> session, double x, double y, String message) {
		var pointer = session.snapshot().radial().pointer();
		assertEquals(x, pointer.x(), 1e-9, message);
		assertEquals(y, pointer.y(), 1e-9, message);
	}

	private static final class Content implements SpatialSelectorSession.ContentPort<String> {
		SpatialSelectorSession.ContentProjection<String> next;
		int reads;
		public SpatialSelectorSession.ContentProjection<String> read(SpatialSelectorSession.CapturedTarget target,
			SpatialSelectorSession.ContentFence fence) { reads++; var value = next; next = null; return value; }
	}
	private static PingType ping(String id) { return PingTypeCatalog.builtIn().findById(id).orElseThrow(); }
	private static SpatialSelectorSession.CapturedTarget target(String id, String type, boolean face) {
		var classification = TargetTypeCatalog.builtIn().findById(type).orElseThrow();
		Target value = switch (classification.kind()) {
			case ENTITY -> new Target.EntityTarget("minecraft:overworld", UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			case BLOCK -> new Target.BlockTarget("minecraft:overworld", id.hashCode(), 4, 5, "minecraft:chest");
			case LOCATION -> new Target.LocationTarget("minecraft:overworld", 1, 2, 3);
		};
		return new SpatialSelectorSession.CapturedTarget(id, new ResolvedTarget(value, classification),
			face ? Optional.of(BlockFace.NORTH) : Optional.empty(), Optional.of(new WorldVector(1, 2, 3)));
	}
	private static Target.ExternalBlockTarget externalBlock(String locator) {
		return Target.ExternalBlockTarget.candidate("minecraft:overworld", "sable", "minecraft:chest", locator, true);
	}
	private static SpatialSelectorSession.CapturedTarget externalTarget(String id, String locator, Optional<BlockFace> face) {
		var classification = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
		return new SpatialSelectorSession.CapturedTarget(id, new ResolvedTarget(externalBlock(locator), classification),
			face, Optional.of(new WorldVector(1, 2, 3)));
	}
	private static Candidate externalCandidate(int x, Optional<BlockFace> face) {
		var classification = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
		return new Candidate(0, new ResolvedTarget(externalBlock("opaque-locator"), classification), new WorldVector(x, 2, 3), 1,
			Optional.empty(), face, new CaptureEquivalenceKey.ExternalKey("minecraft:overworld", "sable", "opaque-locator", "minecraft:chest"));
	}
	private static SpatialSelectorSettings.Snapshot settings(boolean hover) {
		return settings(hover, 30);
	}
	/** Explicit content-intent ON for the established property/item intent fixtures. */
	private static SpatialSelectorSettings.Snapshot settings(boolean hover, int deadzone) {
		return new SpatialSelectorSettings.Snapshot(deadzone, 90, 120, 140, new BigDecimal("0.5"), hover, 250, true, false,
			SpatialSelectorSettings.DEFAULT_PRECISE_CAPTURE_PERIOD_TICKS, SpatialSelectorSettings.DEFAULT_SUBMENU_RADIUS_SCALE,
			SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH, SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY, true);
	}
	/** Explicit content-intent OFF for the default direct-Attention fixtures. */
	private static SpatialSelectorSettings.Snapshot settingsOff(boolean hover, int deadzone) {
		return new SpatialSelectorSettings.Snapshot(deadzone, 90, 120, 140, new BigDecimal("0.5"), hover, 250, true, false,
			SpatialSelectorSettings.DEFAULT_PRECISE_CAPTURE_PERIOD_TICKS, SpatialSelectorSettings.DEFAULT_SUBMENU_RADIUS_SCALE,
			SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH, SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY, false);
	}
	private static SpatialSelectorSession.ContentFence fence(SpatialSelectorSession.CapturedTarget target) {
		return new SpatialSelectorSession.ContentFence(31, 42, 53, target.candidateId());
	}
	private static SpatialSelectorSession<String> session(SpatialSelectorSession.CapturedTarget target,
		Map<String, SpatialSelectorSession.CapturedTarget> precise, boolean hover, Content port) {
		return new SpatialSelectorSession<>(target, precise, settings(hover), fence(target), port, GEOMETRY);
	}
	private static SpatialSelectorSession<String> session(SpatialSelectorSession.CapturedTarget target,
		Map<String, SpatialSelectorSession.CapturedTarget> precise, boolean hover, Content port, int deadzone) {
		return new SpatialSelectorSession<>(target, precise, settings(hover, deadzone), fence(target), port, GEOMETRY);
	}
	/** Explicit content-intent OFF with an enabled Back-hover variant when requested. */
	private static SpatialSelectorSession<String> sessionOff(SpatialSelectorSession.CapturedTarget target, boolean hover, Content port) {
		return new SpatialSelectorSession<>(target, Map.of(), settingsOff(hover, 30), fence(target), port, GEOMETRY);
	}
	private static SpatialController.MenuView current(SpatialSelectorSession<?> session) {
		return session.snapshot().radial().menus().getLast();
	}
	private static void moveTo(SpatialSelectorSession<?> session, SpatialController.Point origin, double bearing, double distance, long now) {
		var pointer = session.snapshot().radial().pointer();
		double radians = Math.toRadians(bearing);
		session.moveGui(origin.x() + Math.sin(radians) * distance - pointer.x(),
			origin.y() - Math.cos(radians) * distance - pointer.y(), now);
	}
	private static void focus(SpatialSelectorSession<?> session, String choiceId, long now) {
		var menu = current(session);
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
		return new SpatialSelectorSession.InventoryRow<>(key, "label " + key, count, "minecraft:stone", Status.READY, "opaque:" + key);
	}
	private static SpatialSelectorSession.ContentProjection<String> inventory(SpatialSelectorSession.CapturedTarget target,
		long revision, boolean reset, List<SpatialSelectorSession.InventoryRow<String>> rows) {
		return new SpatialSelectorSession.ContentProjection<>(fence(target), revision, reset, Status.UPDATING, List.of(),
			new SpatialSelectorSession.InventoryPreview<>("inventory", Status.UPDATING, rows,
				List.of(ping("attention"), ping("request")), ping("attention")));
	}
	private static SpatialSelectorSession<String> openedList(boolean hover, Content port) {
		var target = target("ordinary", "entity_block", true);
		port.next = inventory(target, 1, false, List.of(row("a", 50L), row("b", 30L), row("c", 10L)));
		var session = session(target, Map.of(), hover, port);
		session.open(0);
		enter(session, "content", 10);
		String id = current(session).choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow().id();
		// Keep these list-input fixtures at their explicit (360, 0) axis;
		// entering Content diagonally must not change their viewport preconditions.
		var pointer = session.snapshot().radial().pointer();
		session.moveGui(360 - pointer.x(), -pointer.y(), 150);
		assertEquals(id, session.snapshot().radial().focusId());
		session.tick(150 + session.snapshot().settings().dwellMillis());
		assertTrue(session.snapshot().inventory().open());
		return session;
	}
	private static SpatialSelectorSession<String> openedList(boolean hover, InventoryListModel.Side backSide) {
		if (backSide == InventoryListModel.Side.LEFT) return openedList(hover, new Content());
		var target = target("ordinary", "entity_block", true);
		Content port = new Content();
		port.next = inventory(target, 1, false, List.of(row("a", 50L), row("b", 30L), row("c", 10L)));
		var session = session(target, Map.of(), hover, port);
		session.open(0); enter(session, "content", 10);
		String id = current(session).choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow().id();
		// With only inventory and Back, the right-preferring inventory half-circle
		// still includes 350 degrees: a real reachable left-side entry.
		moveTo(session, current(session).origin(), 350, session.snapshot().settings().stroke() * 2.0, 150);
		assertEquals(id, session.snapshot().radial().focusId());
		session.tick(150 + session.snapshot().settings().dwellMillis());
		assertEquals(backSide, session.snapshot().inventory().direction().back());
		return session;
	}

	@Test
	void rootMapFreezesAllowedIntentAndCenterIsNotCancellation() {
		var target = target("ordinary", "entity_block", true);
		var session = session(target, Map.of(), false, new Content());
		session.open(0);
		var choices = current(session).choices();
		assertEquals(List.of("danger", "content", "reserved-ne", "reserved-se", "cancel-marker", "precise", "intent", "settings"),
			choices.stream().map(SpatialController.ChoiceView::id).toList());
		assertTrue(choices.getFirst().disabled(), "entity-block disallows whole-marker danger");
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(1));
		assertFalse(session.open(2), "a facade cannot reopen its old request fence");
		var cancel = session(target, Map.of(), false, new Content());
		cancel.open(0); focus(cancel, "cancel-marker", 1);
		assertInstanceOf(SelectorIntent.CancelOwnMarker.class, cancel.releaseIntent(2));
		var intent = session(target, Map.of(), false, new Content());
		intent.open(0); enter(intent, "intent", 10);
		assertEquals(target.resolvedTarget().targetType().pingTypes().stream().map(type -> "intent:" + type.id()).toList(),
			current(intent).choices().stream().filter(choice -> !choice.back()).map(SpatialController.ChoiceView::id).toList());
	}

	private static SpatialSelectorSession.ContentProjection<String> layoutProjection(
		SpatialSelectorSession.CapturedTarget target, int topLevelCount, boolean withInventory) {
		var properties = new java.util.ArrayList<SpatialSelectorSession.Property>();
		var groups = new java.util.ArrayList<SpatialSelectorSession.ContentGroup>();
		for (int index = 0; index < topLevelCount; index++) {
			List<String> path = index % 2 == 0 ? List.of() : List.of("group" + index);
			if (!path.isEmpty()) groups.add(new SpatialSelectorSession.ContentGroup(path, "group" + index));
			properties.add(new SpatialSelectorSession.Property("value" + index, "value" + index,
				PresentationPropertyRef.root("pingforit:basic", "pingforit:value" + index), new PresentationValue.NumberValue(index),
				List.of(ping("attention"), ping("danger")), ping("attention"), path));
		}
		return new SpatialSelectorSession.ContentProjection<>(fence(target), 1, false, Status.READY, properties,
			withInventory ? inventory(target, 1, false, List.of(row("a", 5L))).inventory() : null, groups);
	}

	@Test
	void realUpperRightThenRightGestureFindsInventoryAcrossPropertyGroupCountsAndDisabledState() {
		for (int count = 0; count <= 10; count++) {
			for (double entryBearing : new double[] {27.5, 35, 45, 55, 62.4999}) {
				for (boolean disabled : List.of(false, true)) {
					var target = target("ordinary", "entity_block", !disabled);
					Content port = new Content(); port.next = layoutProjection(target, count, true);
					var session = session(target, Map.of(), false, port);
					session.open(0);
					moveTo(session, current(session).origin(), entryBearing, 180, 10);
					assertEquals("content", current(session).focusId(), "the actual root admits upper-right Content input");
					session.tick(130);
					var menu = current(session);
					assertTrue(menu.menuId().endsWith(":content"));
					var inventory = menu.choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow();
					assertEquals(disabled, inventory.disabled());
					var labels = java.util.stream.IntStream.range(0, count).mapToObj(index ->
						(index % 2 == 0 ? "value" : "group") + index).toList();
					assertEquals(labels, menu.choices().stream().filter(choice -> !choice.back() && !choice.id().equals(inventory.id()))
						.map(SpatialController.ChoiceView::label).toList(), "properties and navigation groups keep their relative order");
					var back = menu.choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
					assertEquals(entryBearing + 180, (back.startDegrees() + back.spanDegrees() / 2) % 360, 1e-9);
					session.moveGui(180, 0, 150); // the requested second stroke is exactly right, not the slot centre
					assertEquals(inventory.id(), session.snapshot().radial().focusId());
					session.tick(270);
					assertEquals(!disabled, session.snapshot().inventory().open());
					assertEquals(disabled ? 2 : 3, session.snapshot().radial().menus().size());
					if (disabled) assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(271));
					else {
						assertEquals(InventoryListModel.Side.RIGHT, session.snapshot().inventory().direction().forward());
						assertEquals(inventory.id(), current(session).entryChoiceId(), "breadcrumb follows the resolved inventory choice");
					}
				}
			}
		}
	}

	@Test
	void missingInventoryLeavesTheDeclaredContentGeometryAndNestedTypeMenusUnchanged() {
		for (int count = 1; count <= 6; count++) {
			var target = target("ordinary", "entity_block", true);
			Content port = new Content(); port.next = layoutProjection(target, count, false);
			var session = session(target, Map.of(), false, port);
			session.open(0); enter(session, "content", 10);
			var menu = current(session);
			assertEquals(count + 1, menu.choices().size());
			for (int index = 0; index < count; index++) {
				var choice = menu.choices().get(index);
				assertEquals((index % 2 == 0 ? "value" : "group") + index, choice.label());
				assertEquals(360.0 / (count + 1), choice.spanDegrees(), 1e-9);
				double expectedCentre = (225 + (index + 1) * 360.0 / (count + 1)) % 360;
				assertEquals(expectedCentre, (choice.startDegrees() + choice.spanDegrees() / 2) % 360, 1e-9);
			}
		}
		var target = target("ordinary", "entity_block", true);
		Content port = new Content(); port.next = layoutProjection(target, 3, true);
		var session = session(target, Map.of(), false, port);
		session.open(0); enter(session, "content", 10);
		var group = current(session).choices().stream().filter(choice -> choice.label().equals("group1")).findFirst().orElseThrow();
		enter(session, group.id(), 150);
		var member = current(session).choices().getFirst();
		assertEquals(List.of("value1", "pingforit.spatial.back"), current(session).choices().stream().map(SpatialController.ChoiceView::label).toList());
		enter(session, member.id(), 300);
		assertEquals(List.of(member.id() + ":attention", member.id() + ":danger", current(session).menuId() + ":back"),
			current(session).choices().stream().map(SpatialController.ChoiceView::id).toList(), "nested Ping Types retain their own order");
	}

	@Test
	void pingTypeChoicesCarryTheirCatalogOutlineColorWhileUntypedChoicesStayNull() {
		var ordinary = target("ordinary", "entity", false);
		var preciseEntity = target("precise-entity", "entity", false);
		var rootSession = session(ordinary, Map.of("entity", preciseEntity), false, new Content());
		rootSession.open(0);
		var root = current(rootSession);
		assertEquals(ping("danger").outlineColor(),
			root.choices().stream().filter(choice -> choice.id().equals("danger")).findFirst().orElseThrow().outlineColor());
		assertNotNull(root.choices().stream().filter(choice -> choice.id().equals("cancel-marker")).findFirst().orElseThrow().outlineColor());
		assertNull(root.choices().stream().filter(choice -> choice.id().equals("reserved-ne")).findFirst().orElseThrow().outlineColor());

		enter(rootSession, "intent", 10);
		for (PingType type : ordinary.resolvedTarget().targetType().pingTypes()) {
			var choice = current(rootSession).choices().stream()
				.filter(value -> value.id().equals("intent:" + type.id())).findFirst().orElseThrow();
			assertEquals(type.outlineColor(), choice.outlineColor());
		}

		var preciseSession = session(ordinary, Map.of("entity", preciseEntity), false, new Content());
		preciseSession.open(0);
		enter(preciseSession, "precise", 10);
		assertEquals(preciseEntity.resolvedTarget().targetType().defaultPingType().outlineColor(),
			current(preciseSession).choices().stream()
				.filter(value -> value.id().equals("precise:entity")).findFirst().orElseThrow().outlineColor());
		assertNull(current(preciseSession).choices().stream()
			.filter(value -> value.id().equals("precise:block")).findFirst().orElseThrow().outlineColor(),
			"an unavailable precise slot carries no Ping Type");

		Content content = new Content();
		content.next = new SpatialSelectorSession.ContentProjection<>(fence(ordinary), 1, false, Status.READY,
			List.of(new SpatialSelectorSession.Property("health", "health",
				PresentationPropertyRef.root("pingforit:basic", "pingforit:health"), new PresentationValue.NumberValue(7),
				List.of(ping("attention"), ping("danger")), ping("attention"))), null);
		var contentSession = session(ordinary, Map.of(), false, content);
		contentSession.open(0);
		enter(contentSession, "content", 10);
		String property = current(contentSession).choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow().id();
		assertEquals(ping("attention").outlineColor(),
			current(contentSession).choices().stream().filter(choice -> choice.id().equals(property))
				.findFirst().orElseThrow().outlineColor(),
			"a property branch direct release carries its default Ping Type");
		enter(contentSession, property, 150);
		assertEquals(ping("danger").outlineColor(), current(contentSession).choices().stream()
			.filter(value -> value.id().equals(property + ":danger")).findFirst().orElseThrow().outlineColor());

		var list = openedList(false, new Content());
		double forward = list.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		list.moveGui(forward * 200, 0, 290);
		var itemMenu = current(list);
		for (PingType type : List.of(ping("attention"), ping("request"))) {
			assertEquals(type.outlineColor(), itemMenu.choices().stream()
				.filter(value -> value.id().equals(itemMenu.menuId() + ":" + type.id()))
				.findFirst().orElseThrow().outlineColor());
		}
	}

	@Test
	void suppliedPreciseAllocationIsNotRetargetedOrReusedAndReleaseIsSingleUse() {
		var ordinary = target("ordinary", "entity", false);
		var item = target("item-A", "dropped_item", false);
		var entity = target("entity-B", "entity", false);
		var session = session(ordinary, Map.of("dropped_item", item, "entity", entity), false, new Content());
		session.open(0); enter(session, "precise", 10);
		assertEquals(List.of("precise:dropped_item", "precise:entity", "precise:entity_block", "precise:block", "precise:location"),
			current(session).choices().stream().filter(choice -> !choice.back()).map(SpatialController.ChoiceView::id).toList());
		assertEquals(1, current(session).choices().stream().filter(SpatialController.ChoiceView::back).count());
		assertTrue(current(session).choices().stream().filter(choice -> choice.id().equals("precise:block")).findFirst().orElseThrow().disabled());
		focus(session, "precise:entity", 150);
		var proposal = assertInstanceOf(SelectorIntent.CreateTarget.class, session.releaseIntent(151));
		assertSame(entity, proposal.candidate());
		assertNotEquals(item.resolvedTarget().target(), proposal.candidate().resolvedTarget().target());
		assertEquals(entity.resolvedTarget().targetType().defaultPingType(), proposal.pingType());
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(152));
	}

	@Test
	void broadPreciseSlotsKeepCanonicalTypeWhileForeignCanonicalTypeIsRejected() {
		var ordinary = target("ordinary", "entity_block", true);
		var behind = target("behind", "entity_block", true);
		var item = target("item", "dropped_item", false);
		var session = session(ordinary, Map.of("entity_block", ordinary, "block", behind, "entity", item), false, new Content());
		session.open(0); enter(session, "precise", 10);
		assertFalse(current(session).choices().stream().filter(choice -> choice.id().equals("precise:block")).findFirst().orElseThrow().disabled());
		assertFalse(current(session).choices().stream().filter(choice -> choice.id().equals("precise:entity")).findFirst().orElseThrow().disabled());
		focus(session, "precise:block", 150);
		var proposal = assertInstanceOf(SelectorIntent.CreateTarget.class, session.releaseIntent(151));
		assertSame(behind, proposal.candidate());
		assertEquals("entity_block", proposal.candidate().resolvedTarget().targetType().id(), "a broad slot never relabels its canonical candidate");
		assertEquals(ping("attention"), proposal.pingType());
		assertThrows(IllegalArgumentException.class, () -> session(ordinary, Map.of("entity", behind), false, new Content()));
		assertThrows(IllegalArgumentException.class,
			() -> session(ordinary, Map.of("dropped_item", target("plain", "entity", false)), false, new Content()));
	}

	@ParameterizedTest
	@CsvSource({"entity, false", "location, false", "block, true", "entity_block, true"})
	void hitFaceStaysRestrictedToBlockTargets(String type, boolean accepted) {
		if (accepted) assertEquals(Optional.of(BlockFace.NORTH), target("ordinary", type, true).face());
		else assertThrows(IllegalArgumentException.class, () -> target("ordinary", type, true));
	}

	@ParameterizedTest
	@CsvSource({"NORTH", "NONE"})
	void externalBlockOrdinaryTargetOpensSelectorAndKeepsOnlyItsOwnProviderLocalFace(String observedFace) {
		Optional<BlockFace> face = "NONE".equals(observedFace) ? Optional.empty() : Optional.of(BlockFace.valueOf(observedFace));
		var ordinary = externalTarget("ordinary", "opaque-locator", face);
		var session = session(ordinary, Map.of(), false, new Content());
		session.open(0);
		enter(session, "intent", 10);
		focus(session, "intent:" + ordinary.resolvedTarget().targetType().defaultPingType().id(), 150);
		var intent = assertInstanceOf(SelectorIntent.CreateTarget.class, session.releaseIntent(151));
		assertSame(ordinary, intent.candidate());
		assertEquals(ordinary.resolvedTarget().target(), intent.candidate().resolvedTarget().target());
		assertEquals(face, intent.candidate().face());
		assertEquals(Optional.of(new WorldVector(1, 2, 3)), intent.candidate().hit());
	}

	@Test
	void unavailableDangerAndReservedAndBranchReleaseDoNotCreate() {
		for (String id : List.of("danger", "reserved-ne", "content", "precise")) {
			var session = session(target("ordinary", "entity_block", true), Map.of(), false, new Content());
			session.open(0); focus(session, id, 1);
			assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(2));
		}
	}

	@Test
	void nextCaptureToggleDoesNotMutateFrozenTarget() {
		var target = target("ordinary", "entity", false);
		var session = session(target, Map.of(), false, new Content());
		session.open(0); enter(session, "settings", 10); focus(session, "settings:FLUIDS", 150);
		var intent = assertInstanceOf(SelectorIntent.ToggleNextCapture.class, session.releaseIntent(151));
		assertEquals(SelectorIntent.CaptureToggle.FLUIDS, intent.toggle());
		assertEquals("ordinary", target.candidateId());
	}

	@Test
	void actualContentValueAndIndependentAnnotationTravelWithoutFixtureData() {
		var target = target("ordinary", "entity", false);
		Content content = new Content();
		var ref = PresentationPropertyRef.root("pingforit:basic", "pingforit:health");
		var observed = new PresentationValue.NumberValue(7.5);
		content.next = new SpatialSelectorSession.ContentProjection<>(fence(target), 1, false, Status.READY,
			List.of(new SpatialSelectorSession.Property("health", "health", ref, observed,
				List.of(ping("attention"), ping("danger")), ping("attention"))), null);
		var session = session(target, Map.of(), false, content);
		session.open(0); enter(session, "content", 10);
		String property = current(session).choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow().id();
		enter(session, property, 150);
		focus(session, property + ":danger", 290);
		var intent = assertInstanceOf(SelectorIntent.CreateProperty.class, session.releaseIntent(291));
		assertEquals(observed, intent.property().observedValue());
		assertEquals(ref, intent.property().ref());
		assertEquals("danger", intent.property().pingTypeId());
		assertEquals(target.resolvedTarget().targetType().defaultPingType(), intent.mainType());
	}

	@Test
	void inventorySubmenuAnchorsAtLogicalSelectedRowAndBackPreservesSelection() {
		var session = openedList(false, new Content());
		session.scrollRows(1, 290);
		var list = session.snapshot().inventory();
		assertEquals("b", list.selectedKey());
		double expectedY = GEOMETRY.layout(session.snapshot().inventoryView()).rowCenterY(list.selectedIndex());
		double forward = list.direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		session.moveGui(forward * 200, 35, 300);
		var submenu = current(session);
		assertEquals(expectedY, submenu.origin().y());
		assertEquals(expectedY, submenu.parentOrigin().y(), "Back faces the row, not panel centre");
		assertEquals(1, session.snapshot().radial().trail().size(), "entry does not paint a warp stroke");
		var back = submenu.choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
		focus(session, back.id(), 310); session.tick(430);
		assertEquals("b", session.snapshot().inventory().selectedKey());
		assertTrue(current(session).menuId().endsWith(":inventory"));
		assertEquals(1, session.snapshot().radial().trail().size(), "return resets the logical stroke");
		session.tick(1000);
		assertTrue(current(session).menuId().endsWith(":inventory"), "stationary return cannot reenter");
		session.moveGui(forward * 200, 0, 1010);
		focus(session, current(session).menuId() + ":request", 1020);
		var intent = assertInstanceOf(SelectorIntent.SelectInventory.class, session.releaseIntent(1021));
		assertEquals("opaque:b", intent.reference());
		assertEquals("attention", intent.mainType().id());
		assertEquals("request", intent.itemType().id(), "item annotation is not main-marker type");
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(1022));
	}

	@Test
	void directListReleaseChoosesOneOpaqueReferenceAndNoCountAuthority() {
		var session = openedList(false, new Content());
		session.scrollRows(1, 290);
		var intent = assertInstanceOf(SelectorIntent.SelectInventory.class, session.releaseIntent(300));
		assertEquals("opaque:b", intent.reference());
		assertEquals("attention", intent.itemType().id());
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(301));
	}

	@ParameterizedTest
	@CsvSource({"false, LEFT", "true, LEFT", "false, RIGHT", "true, RIGHT"})
	void backFocusedListReleaseNeverSelectsAnItemRegardlessOfHoverOrParentSide(boolean hover, InventoryListModel.Side backSide) {
		var session = openedList(hover, backSide);
		assertEquals(backSide, session.snapshot().inventory().direction().back());
		String listMenu = current(session).menuId();
		double backSign = backSide == InventoryListModel.Side.LEFT ? -1 : 1;
		session.moveGui(backSign * 50, 0, 290);
		assertEquals(listMenu, current(session).menuId(), "Back focus has not crossed the navigation exit gate");
		assertEquals("a", session.snapshot().inventory().selectedKey());
		assertTrue(session.snapshot().inventoryView().backAffordance().focused(), "paint focus matches release eligibility with either hover setting");
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(300), "Back is navigation even with hover disabled");
		assertFalse(session.snapshot().active());
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(301));
	}

	@ParameterizedTest
	@CsvSource({"false, LEFT, 0", "true, LEFT, 0", "false, RIGHT, 0", "true, RIGHT, 0",
		"false, LEFT, 1", "true, LEFT, 1", "false, RIGHT, 1", "true, RIGHT, 1"})
	void centeredListReleaseSelectsCurrentRowExactlyOnceRegardlessOfHoverOrParentSide(boolean hover,
		InventoryListModel.Side backSide, int scrollRows) {
		var session = openedList(hover, backSide);
		session.scrollRows(scrollRows, 290);
		String expectedKey = scrollRows == 0 ? "a" : "b";
		assertEquals(expectedKey, session.snapshot().inventory().selectedKey());
		var intent = assertInstanceOf(SelectorIntent.SelectInventory.class, session.releaseIntent(300));
		assertEquals("opaque:" + expectedKey, intent.reference());
		assertEquals("attention", intent.mainType().id());
		assertEquals("attention", intent.itemType().id());
		assertFalse(session.snapshot().active());
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(301));
	}

	@Test
	void partialOrderingFreezesOnScrollCountOnlyNeverReordersAndZeroStays() {
		var target = target("ordinary", "entity_block", true);
		var session = openedList(false, new Content());
		session.updateContent(inventory(target, 2, false, List.of(row("d", 100L))), 280);
		assertEquals(List.of("d", "a", "b", "c"), session.snapshot().inventory().entries().stream().map(InventoryListModel.Entry::key).toList());
		session.updateContent(inventory(target, 3, false, List.of(row("c", 999L), row("a", 0L))), 290);
		assertEquals(List.of("d", "a", "b", "c"), session.snapshot().inventory().entries().stream().map(InventoryListModel.Entry::key).toList());
		session.scrollRows(1, 300);
		session.updateContent(inventory(target, 4, false, List.of(row("e", 2000L), row("f", 3000L))), 310);
		assertEquals(List.of("d", "a", "b", "c", "f", "e"), session.snapshot().inventory().entries().stream().map(InventoryListModel.Entry::key).toList());
		assertEquals(0L, session.snapshot().inventoryView().rows().get(1).count());
	}

	@Test
	void listBackHoverOwnsRowsAndPopsOnlyOneLevelWithoutCascade() {
		var session = openedList(true, new Content());
		var list = session.snapshot().inventory();
		double back = list.direction().back() == InventoryListModel.Side.LEFT ? -1 : 1;
		session.moveGui(back * 50, 0, 290);
		session.scrollRows(2, 300); session.moveGui(0, 150, 310);
		assertEquals(list.selectedKey(), session.snapshot().inventory().selectedKey());
		session.tick(415);
		assertEquals(0.5, session.snapshot().radial().hoverProgress(), 1e-9);
		assertTrue(session.snapshot().inventoryView().backAffordance().focused());
		assertEquals(session.snapshot().radial().hoverProgress(), session.snapshot().inventoryView().backAffordance().progress());
		session.tick(540);
		assertFalse(current(session).menuId().endsWith(":inventory"));
		int depth = session.snapshot().radial().menus().size();
		session.tick(5000);
		assertEquals(depth, session.snapshot().radial().menus().size());
	}

	@Test
	void listBackHoverLeaveAllowsScrollingAgainWithoutHiddenRemainder() {
		var session = openedList(true, new Content());
		double back = session.snapshot().inventory().direction().back() == InventoryListModel.Side.LEFT ? -1 : 1;
		session.moveGui(back * 50, 0, 290);
		session.scrollRows(2, 300);
		session.moveGui(-back * 50, 0, 310);
		assertFalse(session.snapshot().inventoryView().backAffordance().focused());
		assertEquals(0.0, session.snapshot().inventoryView().backAffordance().progress());
		session.scrollRows(1, 320);
		assertEquals("b", session.snapshot().inventory().selectedKey());
	}

	@Test
	void unavailableResetAndLifecycleFencesDiscardOldReferencesAndLateData() {
		Content port = new Content();
		var session = openedList(false, port);
		var target = target("ordinary", "entity_block", true);
		var wrong = new SpatialSelectorSession.ContentProjection<String>(
			new SpatialSelectorSession.ContentFence(31, 42, 999, "ordinary"), 10, false, Status.READY, List.of(), null);
		assertFalse(session.updateContent(wrong, 290));
		assertFalse(session.updateContent(inventory(target, 1, false, List.of(row("evil", 999L))), 290));
		assertTrue(session.updateContent(new SpatialSelectorSession.ContentProjection<>(fence(target), 2, true,
			Status.UNAVAILABLE, List.of(), null), 300));
		assertFalse(session.snapshot().inventory().open());
		assertTrue(session.snapshot().inventoryView().rows().isEmpty());
		session.abort();
		int reads = port.reads;
		port.next = inventory(target, 3, false, List.of(row("late", 5L)));
		session.tick(400);
		assertEquals(reads, port.reads, "abort does not poll/restore content");
		assertFalse(session.updateContent(port.next, 401));
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(402));
	}

	@Test
	void ordinaryBlockWithoutFrozenFaceCannotEnterInventoryEvenIfPortOffersRows() {
		var target = target("ordinary", "entity_block", false);
		Content port = new Content(); port.next = inventory(target, 1, false, List.of(row("a", 5L)));
		var session = session(target, Map.of(), false, port);
		session.open(0); enter(session, "content", 10);
		var choice = current(session).choices().stream().filter(value -> !value.back()).findFirst().orElseThrow();
		assertTrue(choice.disabled());
		focus(session, choice.id(), 150); session.tick(500);
		assertFalse(session.snapshot().inventory().open());
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(501));
	}

	@Test
	void missingCountStaysUnknownWhileExplicitZeroRemainsSelectable() {
		var target = target("ordinary", "entity_block", true);
		Content port = new Content();
		var session = openedList(false, port);
		session.updateContent(inventory(target, 2, true, List.of(row("unknown", null), row("zero", 0L))), 290);
		var view = session.snapshot().inventoryView();
		assertEquals(List.of("zero", "unknown"), view.rows().stream().map(value -> value.key()).toList());
		assertEquals(0L, view.rows().getFirst().count());
		assertNull(view.rows().getLast().count());
		var intent = assertInstanceOf(SelectorIntent.SelectInventory.class, session.releaseIntent(300));
		assertEquals("opaque:zero", intent.reference());
	}

	@Test
	void newBatchWhileItemOpenUsesSameLogicalRowAnchorAndRevokedItemTypeCannotRelease() {
		var target = target("ordinary", "entity_block", true);
		var session = openedList(false, new Content());
		double forward = session.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		session.moveGui(forward * 200, 0, 290);
		double anchor = current(session).origin().y();
		session.updateContent(inventory(target, 2, false, List.of(row("new", 1000L))), 300);
		assertEquals(anchor, GEOMETRY.layout(session.snapshot().inventoryView()).rowCenterY(session.snapshot().inventory().selectedIndex()), 1e-9);
		String menu = current(session).menuId();
		focus(session, menu + ":request", 310);
		var narrowed = new SpatialSelectorSession.InventoryPreview<String>("inventory", Status.UPDATING, List.of(),
			List.of(ping("attention")), ping("attention"));
		session.updateContent(new SpatialSelectorSession.ContentProjection<>(fence(target), 3, false, Status.UPDATING, List.of(), narrowed), 320);
		assertFalse(current(session).choices().stream().anyMatch(choice -> choice.id().equals(menu + ":request")));
		SelectorIntent<String> result = session.releaseIntent(321);
		if (result instanceof SelectorIntent.SelectInventory<String> item) assertEquals("attention", item.itemType().id());
		else assertInstanceOf(SelectorIntent.None.class, result);
	}

	@Test
	void hoverReturnFromItemToListPreservesSelectionWithoutCascading() {
		var session = openedList(true, new Content());
		session.scrollRows(1, 280);
		double forward = session.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		session.moveGui(forward * 200, 0, 290);
		var back = current(session).choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
		focus(session, back.id(), 300);
		session.tick(425);
		assertEquals(0.5, session.snapshot().radial().hoverProgress(), 1e-9);
		session.tick(550);
		assertTrue(current(session).menuId().endsWith(":inventory"));
		assertEquals("b", session.snapshot().inventory().selectedKey());
		session.tick(2000); session.moveGui(0, 0, 2001); session.tick(3000);
		assertTrue(current(session).menuId().endsWith(":inventory"));
		session.moveGui(forward * 200, 0, 3010);
		assertFalse(current(session).menuId().endsWith(":inventory"));
		var reenteredBack = current(session).choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
		focus(session, reenteredBack.id(), 3020); session.tick(3270);
		assertTrue(current(session).menuId().endsWith(":inventory"), "deliberate forward stroke and Back reentry rearm one return");
	}

	@Test
	void viewportCorrectionCannotRearmHeldItemBackOnListButPhysicalLeaveCan() {
		var session = openedList(true, new Content());
		session.scrollRows(1, 280);
		session.moveGui(200, 0, 290);
		var back = current(session).choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
		focus(session, back.id(), 300);
		session.tick(550);
		String listMenu = current(session).menuId();
		assertTrue(listMenu.endsWith(":inventory"));
		assertFalse(session.snapshot().inventoryView().backAffordance().focused(), "item return rebases list to its center");
		var trail = session.snapshot().radial().trail();
		session.tick(560);
		// Alignment out of Back plus raw-only vertical input must not release the
		// item-return latch. The eventual actual leave/re-entry below still must.
		session.constrainPointer(new SpatialController.Viewport(1, 1, -session.snapshot().radial().pointer().x() - 0.5,
			-session.snapshot().radial().pointer().y() - 0.5), 561);
		session.moveGui(0, 92, 562);
		session.constrainPointer(new SpatialController.Viewport(320, 240, 0, 0), 570);
		assertFalse(session.snapshot().inventoryView().backAffordance().focused(), "axis-only correction keeps the entry corridor reachable");
		assertEquals(trail, session.snapshot().radial().trail());
		session.tick(580);
		session.tick(830);
		assertEquals(listMenu, current(session).menuId(), "synthetic list focus must not cascade to content");
		assertEquals(0, session.snapshot().radial().hoverProgress());
		session.constrainPointer(new SpatialController.Viewport(1200, 800, -360, 0), 840);
		double axis = session.snapshot().radial().pointer().x();
		session.moveGui(-50, 0, 841);
		session.tick(1091);
		assertEquals(listMenu, current(session).menuId(), "projection-only leave must not release the held item-Back latch");
		session.moveGui(50, 0, 1100);
		assertFalse(session.snapshot().inventoryView().backAffordance().focused(), "effective physical leave releases the list block");
		session.moveGui(-50, 0, 1110);
		assertEquals(axis, session.snapshot().radial().pointer().x());
		assertTrue(session.snapshot().inventoryView().backAffordance().focused());
		session.tick(1359);
		assertEquals(listMenu, current(session).menuId());
		session.tick(1360);
		assertTrue(current(session).menuId().endsWith(":content"), "physical re-entry and full dwell pop exactly one level");
	}

	@Test
	void inventoryBordersFollowLiveDirectAnnotationAndLoseColorWhenAttentionIsNotAuthorized() {
		var target = target("ordinary", "entity_block", true);
		var request = ping("request");
		for (boolean enabled : List.of(false, true)) {
			var port = new Content();
			port.next = new SpatialSelectorSession.ContentProjection<>(fence(target), 1, false, Status.UPDATING, List.of(),
				new SpatialSelectorSession.InventoryPreview<>("inventory", Status.UNKNOWN,
					List.of(new SpatialSelectorSession.InventoryRow<>("a", "Unknown count", null, "minecraft:stone", Status.INCOMPLETE, "opaque:a")),
					List.of(request), request));
			var session = enabled ? session(target, Map.of(), false, port) : sessionOff(target, false, port);
			session.open(0); enter(session, "content", 10);
			String id = current(session).choices().stream().filter(c -> c.id().endsWith(":inventory")).findFirst().orElseThrow().id();
			enter(session, id, 150);
			var row = session.snapshot().inventoryView().rows().getFirst();
			assertTrue(row.quality().grey());
			if (enabled) {
				assertEquals(request.outlineColor(), row.outlineColor());
				var intent = assertInstanceOf(SelectorIntent.SelectInventory.class, session.releaseIntent(300));
				assertEquals("request", intent.itemType().id());
				assertEquals("attention", intent.mainType().id(), "row color is independent of whole-marker default");
			} else {
				assertNull(row.outlineColor(), "OFF cannot invent Attention permission");
				assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(300));
			}
		}
	}

	@Test
	void inventoryBorderUpdatesWithAnnotationAuthorityWithoutChangingSelection() {
		var session = openedList(false, new Content());
		var target = target("ordinary", "entity_block", true);
		var prior = session.snapshot().inventory();
		var request = ping("request");
		session.updateContent(new SpatialSelectorSession.ContentProjection<>(fence(target), 2, false, Status.UPDATING, List.of(),
			new SpatialSelectorSession.InventoryPreview<String>("inventory", Status.UNKNOWN, List.of(), List.of(request), request)), 280);
		assertEquals(prior.selectedKey(), session.snapshot().inventory().selectedKey());
		assertTrue(session.snapshot().inventoryView().rows().stream().allMatch(row -> row.outlineColor().equals(request.outlineColor())));
		session.updateContent(new SpatialSelectorSession.ContentProjection<>(fence(target), 3, false, Status.UPDATING, List.of(),
			new SpatialSelectorSession.InventoryPreview<String>("inventory", Status.UNKNOWN, List.of(), List.of(), null)), 281);
		assertTrue(session.snapshot().inventoryView().rows().stream().allMatch(row -> row.outlineColor() == null));
		assertFalse(session.snapshot().inventoryView().selectedRowCommittable());
	}

	@Test
	void localActionsHaveDistinctNonGreyRoleColorsAndNavigationKeepsNoActionColor() {
		var session = session(target("ordinary", "block", true), Map.of(), false, new Content());
		session.open(0);
		var root = current(session);
		var colors = new java.util.HashSet<Integer>();
		colors.add(root.choices().stream().filter(c -> c.id().equals("cancel-marker")).findFirst().orElseThrow().outlineColor());
		assertNull(root.choices().stream().filter(c -> c.id().equals("settings")).findFirst().orElseThrow().outlineColor());
		enter(session, "settings", 10);
		for (var choice : current(session).choices()) {
			if (choice.back()) { assertNull(choice.outlineColor()); continue; }
			assertNotNull(choice.outlineColor());
			assertTrue(colors.add(choice.outlineColor()), "each local action has its own role color");
			assertNotEquals((choice.outlineColor() >> 16) & 255, choice.outlineColor() & 255, "not grey");
			assertTrue(choice.action().startsWith("settings:"), "no synthetic Ping Type action");
		}
		assertEquals(4, colors.size());
	}

	@Test
	void inventoryEnteredLeftOfParentUsesRightBackAndLeftForward() {
		var session = openedList(false, InventoryListModel.Side.RIGHT);
		assertEquals(InventoryListModel.Side.RIGHT, session.snapshot().inventory().direction().back());
		assertEquals(InventoryListModel.Side.LEFT, session.snapshot().inventory().direction().forward());
		double axis = session.snapshot().inventory().axisX();
		session.moveGui(-200, 0, 290);
		assertTrue(current(session).origin().x() < axis, "item submenu opens on frozen forward side");
		var back = current(session).choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
		focus(session, back.id(), 300); session.tick(420);
		assertTrue(current(session).menuId().endsWith(":inventory"));
		session.moveGui(200, 0, 430);
		assertFalse(current(session).menuId().endsWith(":inventory"), "right stroke returns to content");
	}

	@Test
	void inputAdapterDrivesFacadeWithoutCreatingOpeningOrScaleWarpTrail() {
		var session = session(target("ordinary", "entity", false), Map.of(), false, new Content());
		session.open(0);
		var input = new NativeSelectorInput(session);
		var frame = new NativeSelectorInput.Frame(7, 1200, 800, 600, 400, true, false);
		input.open(); input.onMove(7, 800, 500, frame, 1);
		assertEquals(1, session.snapshot().radial().trail().size());
		input.onMove(7, 800, 400, frame, 2);
		assertEquals(new SpatialController.Point(0, -50), session.snapshot().radial().pointer());
		var rescaled = new NativeSelectorInput.Frame(7, 1200, 800, 300, 200, true, false);
		input.onMove(7, 200, 200, rescaled, 3);
		assertEquals(new SpatialController.Point(0, -50), session.snapshot().radial().pointer());
		assertEquals(2, session.snapshot().radial().trail().size());
		var intent = assertInstanceOf(SelectorIntent.CreateTarget.class, session.releaseIntent(4));
		assertEquals("danger", intent.pingType().id());
	}

	@Test
	void opaquePropertyKeysCannotAliasAnotherPropertyAnnotationAction() {
		var target = target("ordinary", "entity", false);
		Content port = new Content();
		var health = PresentationPropertyRef.root("pingforit:basic", "pingforit:health");
		var other = PresentationPropertyRef.root("pingforit:basic", "pingforit:other");
		port.next = new SpatialSelectorSession.ContentProjection<>(fence(target), 1, false, Status.READY, List.of(
			new SpatialSelectorSession.Property("health", "first", health, new PresentationValue.NumberValue(7),
				List.of(ping("attention"), ping("danger")), ping("attention")),
			new SpatialSelectorSession.Property("health:danger", "second", other, new PresentationValue.NumberValue(9),
				List.of(ping("attention")), ping("attention"))), null);
		var session = session(target, Map.of(), false, port);
		session.open(0); enter(session, "content", 10);
		String first = current(session).choices().stream().filter(choice -> "first".equals(choice.label())).findFirst().orElseThrow().id();
		enter(session, first, 150); focus(session, first + ":danger", 290);
		var intent = assertInstanceOf(SelectorIntent.CreateProperty.class, session.releaseIntent(291));
		assertEquals(health, intent.property().ref());
		assertEquals("danger", intent.property().pingTypeId());
	}

	private static SpatialSelectorSession.Property blockStateProperty(String id, String key, String state) {
		return new SpatialSelectorSession.Property(id, id,
			new PresentationPropertyRef("pingforit:basic", "minecraft:block.state", List.of(key)),
			new PresentationValue.Text(state), List.of(ping("attention"), ping("danger")), ping("attention"), List.of("block-state"));
	}

	private static SpatialSelectorSession.ContentProjection<String> blockStateProjection(
		SpatialSelectorSession.CapturedTarget target, List<SpatialSelectorSession.Property> properties) {
		return new SpatialSelectorSession.ContentProjection<>(fence(target), 1, false, Status.READY, properties, null,
			List.of(new SpatialSelectorSession.ContentGroup(List.of("block-state"), "state-label")));
	}

	@Test
	void contentGroupIsAPureNavigationParentWhileUnrelatedRowsKeepTheirActions() {
		var target = target("ordinary", "entity_block", true);
		Content port = new Content();
		port.next = blockStateProjection(target, List.of(
			blockStateProperty("snowy", "snowy", "false"),
			new SpatialSelectorSession.Property("other", "other",
				PresentationPropertyRef.root("pingforit:basic", "pingforit:other"), new PresentationValue.NumberValue(1),
				List.of(ping("attention")), ping("attention")),
			blockStateProperty("lit", "lit", "true")));
		var session = session(target, Map.of(), false, port);
		session.open(0); enter(session, "content", 10);
		var top = current(session).choices();
		assertEquals(List.of("state-label", "other", "pingforit.spatial.back"),
			top.stream().map(SpatialController.ChoiceView::label).toList());
		assertTrue(top.getFirst().branch());
		assertNull(top.getFirst().action(), "the group parent must not ping the whole record");
		assertNotNull(top.get(1).action(), "an unrelated top-level row keeps its established action");
		focus(session, top.getFirst().id(), 150);
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(151),
			"releasing the focused group parent itself commits nothing");
	}

	@Test
	void groupedMemberChildrenKeepTheirOwnRefOldValueAndTypedDefaultRelease() {
		var target = target("ordinary", "entity_block", true);
		Content port = new Content();
		port.next = blockStateProjection(target, List.of(
			blockStateProperty("snowy", "snowy", "false"), blockStateProperty("lit", "lit", "true")));
		var session = session(target, Map.of(), false, port);
		session.open(0); enter(session, "content", 10);
		String parent = current(session).choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow().id();
		enter(session, parent, 150);
		var children = current(session).choices().stream().filter(choice -> !choice.back()).toList();
		assertEquals(List.of("snowy", "lit"), children.stream().map(SpatialController.ChoiceView::label).toList());
		assertEquals(ping("attention").outlineColor(), children.getFirst().outlineColor(),
			"a grouped member row keeps its default Ping Type outline color");
		var snowy = children.getFirst();
		focus(session, snowy.id(), 300);
		var intent = assertInstanceOf(SelectorIntent.CreateProperty.class, session.releaseIntent(301));
		assertEquals(new PresentationPropertyRef("pingforit:basic", "minecraft:block.state", List.of("snowy")), intent.property().ref());
		assertEquals(new PresentationValue.Text("false"), intent.property().observedValue());
		assertEquals("attention", intent.property().pingTypeId());
		assertEquals(target.resolvedTarget().targetType().defaultPingType(), intent.mainType());
	}

	@Test
	void groupBackNavigationReturnsOneLevelAndKeepsTheSiblingChoices() {
		var target = target("ordinary", "entity_block", true);
		Content port = new Content();
		port.next = blockStateProjection(target, List.of(
			blockStateProperty("snowy", "snowy", "false"), blockStateProperty("lit", "lit", "true")));
		var session = session(target, Map.of(), false, port);
		session.open(0); enter(session, "content", 10);
		String parent = current(session).choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow().id();
		enter(session, parent, 150);
		String back = current(session).choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow().id();
		focus(session, back, 200); session.tick(400);
		assertTrue(current(session).menuId().endsWith(":content"), "Back returns one level to the content menu");
		assertEquals(parent, current(session).choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow().id(),
			"the group parent keeps its identity after the return");
	}

	@Test
	void declaredGroupWithoutAnyMemberChoiceIsOmittedInsteadOfDeadSelectableRoot() {
		var target = target("ordinary", "entity_block", true);
		Content port = new Content();
		port.next = new SpatialSelectorSession.ContentProjection<>(fence(target), 1, false, Status.READY,
			List.of(new SpatialSelectorSession.Property("other", "other",
				PresentationPropertyRef.root("pingforit:basic", "pingforit:other"), new PresentationValue.NumberValue(1),
				List.of(ping("attention")), ping("attention"))),
			null, List.of(new SpatialSelectorSession.ContentGroup(List.of("block-state"), "state-label")));
		var session = session(target, Map.of(), false, port);
		session.open(0); enter(session, "content", 10);
		assertEquals(List.of("other", "pingforit.spatial.back"),
			current(session).choices().stream().map(SpatialController.ChoiceView::label).toList(),
			"an empty declared group leaves no focusable parent row");
	}

	@Test
	void nestedGroupPathRendersSubgroupsUnderTheirDeclaredParent() {
		var target = target("ordinary", "entity_block", true);
		List<String> create = List.of("create"), stress = List.of("create", "stress");
		Content port = new Content();
		port.next = new SpatialSelectorSession.ContentProjection<>(fence(target), 1, false, Status.READY, List.of(
			new SpatialSelectorSession.Property("speed", "speed",
				PresentationPropertyRef.root("create:presentation", "create:kinetic.speed"), new PresentationValue.NumberValue(64),
				List.of(ping("attention")), ping("attention"), create),
			new SpatialSelectorSession.Property("stress", "stress",
				PresentationPropertyRef.root("create:presentation", "create:kinetic.stress"), new PresentationValue.NumberValue(4),
				List.of(ping("attention")), ping("attention"), stress)),
			null, List.of(new SpatialSelectorSession.ContentGroup(create, "create-label"),
				new SpatialSelectorSession.ContentGroup(stress, "stress-label")));
		var session = session(target, Map.of(), false, port);
		session.open(0); enter(session, "content", 10);
		String createParent = current(session).choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow().id();
		enter(session, createParent, 150);
		var children = current(session).choices().stream().filter(choice -> !choice.back()).toList();
		assertEquals(List.of("speed", "stress-label"), children.stream().map(SpatialController.ChoiceView::label).toList());
		enter(session, children.getLast().id(), 300);
		var stressChild = current(session).choices().stream().filter(choice -> !choice.back()).findFirst().orElseThrow();
		assertEquals("stress", stressChild.label());
		focus(session, stressChild.id(), 450);
		var intent = assertInstanceOf(SelectorIntent.CreateProperty.class, session.releaseIntent(451));
		assertEquals("create:kinetic.stress", intent.property().ref().fieldId());
		assertEquals(new PresentationValue.NumberValue(4), intent.property().observedValue());
	}

	@Test
	void propertyGroupPathWithoutItsDeclaredChainIsRejected() {
		var target = target("ordinary", "entity_block", true);
		assertThrows(IllegalArgumentException.class, () -> new SpatialSelectorSession.ContentProjection<>(fence(target), 1,
			false, Status.READY, List.of(blockStateProperty("snowy", "snowy", "false")), null, List.of()));
	}

	private static SpatialSelectorSession<String> liveSession(nx.pingwheel.common.interaction.InteractionToken token, Object level) {
		var ordinary = target("ordinary", "entity", false);
		var session = new SpatialSelectorSession<String>(ordinary, Map.of(), settings(false),
			new SpatialSelectorSession.ContentFence(token.sequence(), 42, 53, "ordinary"), new Content(), GEOMETRY);
		session.beginLivePrecise(token, level); session.open(0); enter(session, "precise", 10);
		return session;
	}
	private static PreciseCaptureRefresh.Published publication(nx.pingwheel.common.interaction.InteractionToken token,
		Object level, long revision, int x, boolean available) {
		return publication(token, level, revision, x, available, PreciseCaptureRefreshTest.candidate(false, x));
	}
	private static PreciseCaptureRefresh.Published publication(nx.pingwheel.common.interaction.InteractionToken token,
		Object level, long revision, int x, boolean available, Candidate blockCandidate) {
		var inputs = new PreciseCaptureRefresh.Inputs(token, level,
			new nx.pingwheel.common.interaction.CapturedRay(new WorldVector(x, 2, 3), new WorldVector(1, 0, 0)),
			nx.pingwheel.common.math.RaycastPolicy.from(false, false, false), 10, 100);
		var slots = new java.util.EnumMap<nx.pingwheel.common.interaction.candidate.PreciseTargetType, PreciseCaptureRefresh.Slot>(
			nx.pingwheel.common.interaction.candidate.PreciseTargetType.class);
		for (var type : nx.pingwheel.common.interaction.candidate.PreciseTargetType.values()) {
			var outcome = type == nx.pingwheel.common.interaction.candidate.PreciseTargetType.BLOCK && available
				? PreciseCaptureRefresh.Outcome.available(blockCandidate) : PreciseCaptureRefresh.Outcome.missing();
			slots.put(type, new PreciseCaptureRefresh.Slot(revision, inputs, outcome));
		}
		return new PreciseCaptureRefresh.Published(revision, revision, inputs, slots);
	}

	@Test void liveReplacementPreservesSlotFocusOriginTypedColorAndPaintedPayloadRatherThanInstalledTarget() {
		var token = new nx.pingwheel.common.interaction.ActiveInteraction().begin(); Object level = new Object();
		var session = liveSession(token, level);
		assertTrue(session.isPreciseBranchActive());
		assertTrue(session.updatePrecise(publication(token, level, 1, 10, true), 140));
		focus(session, "precise:block", 150);
		var before = current(session); var frame = session.snapshot();
		var painted = session.markPresented(frame, java.util.Set.of("precise:block")).orElseThrow();
		var targetA = painted.choices().get("precise:block");
		assertEquals(ping("attention").outlineColor(), before.choices().stream().filter(choice -> choice.id().equals("precise:block"))
			.findFirst().orElseThrow().outlineColor());
		assertTrue(session.updatePrecise(publication(token, level, 2, 20, true), 160));
		assertEquals(before.origin(), current(session).origin()); assertEquals("precise:block", current(session).focusId());
		assertNotEquals(targetA.candidateId(), session.snapshot().preciseFrame().choices().get("precise:block").candidateId());
		var intent = assertInstanceOf(SelectorIntent.CreateTarget.class, session.releaseIntent(170));
		assertSame(targetA, intent.candidate()); assertEquals(1, intent.presentationRevision());
		assertEquals(nx.pingwheel.common.interaction.state.SelectorReleaseProposal.Admission.PRECISE_PRESENTED, intent.admission());
		assertEquals(new WorldVector(10, 2, 3), painted.contexts().get(targetA.candidateId()).ray().origin());
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(171));
	}

	@Test void liveInstalledWithoutPaintCannotReleaseAndDisabledPaintClearsOlderSelectableVersion() {
		for (boolean firstPaint : List.of(false, true)) {
			var token = new nx.pingwheel.common.interaction.ActiveInteraction().begin(); Object level = new Object();
			var session = liveSession(token, level);
			session.updatePrecise(publication(token, level, 1, 10, true), 140); focus(session, "precise:block", 150);
			if (firstPaint) {
				session.markPresented(session.snapshot(), java.util.Set.of("precise:block"));
				session.updatePrecise(publication(token, level, 2, 20, false), 160);
				assertTrue(session.markPresented(session.snapshot(), java.util.Set.of("precise:block")).orElseThrow().choices().isEmpty());
			}
			assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(170));
		}
	}

	@ParameterizedTest
	@CsvSource({"EAST", "NONE"})
	void livePreciseExternalBlockCandidateKeepsItsProviderLocalFace(String observedFace) {
		Optional<BlockFace> face = "NONE".equals(observedFace) ? Optional.empty() : Optional.of(BlockFace.valueOf(observedFace));
		var token = new nx.pingwheel.common.interaction.ActiveInteraction().begin(); Object level = new Object();
		var session = liveSession(token, level);
		var candidate = externalCandidate(10, face);
		assertTrue(session.updatePrecise(publication(token, level, 1, 10, true, candidate), 140));
		var installed = session.snapshot().preciseFrame().choices().get("precise:block");
		assertNotNull(installed);
		assertEquals(candidate.resolvedTarget(), installed.resolvedTarget());
		assertEquals(face, installed.face());
		focus(session, "precise:block", 150);
		var painted = session.markPresented(session.snapshot(), java.util.Set.of("precise:block")).orElseThrow();
		var intent = assertInstanceOf(SelectorIntent.CreateTarget.class, session.releaseIntent(160));
		assertSame(painted.choices().get("precise:block"), intent.candidate());
		assertEquals(face, intent.candidate().face());
		assertEquals(new WorldVector(10, 2, 3), painted.contexts().get(intent.candidate().candidateId()).ray().origin(),
			"the painted context keeps the live ray");
	}

	@Test void unpaintedIncompleteReplacementKeepsLastPaintedSelectablePayloadButEmptyAckCannotAuthorize() {
		var token = new nx.pingwheel.common.interaction.ActiveInteraction().begin(); Object level = new Object();
		var session = liveSession(token, level);
		session.updatePrecise(publication(token, level, 1, 10, true), 140); focus(session, "precise:block", 150);
		assertTrue(session.markPresented(session.snapshot(), java.util.Set.of()).isEmpty());
		var painted = session.markPresented(session.snapshot(), java.util.Set.of("precise:block")).orElseThrow();
		session.updatePrecise(publication(token, level, 2, 20, false), 160);
		var intent = assertInstanceOf(SelectorIntent.CreateTarget.class, session.releaseIntent(170));
		assertSame(painted.choices().get("precise:block"), intent.candidate());
	}

	@Test void livePublicationAndPaintFencesRejectDuplicateWrongTokenWrongLevelAndEndedSession() {
		var token = new nx.pingwheel.common.interaction.ActiveInteraction().begin(); Object level = new Object();
		var session = liveSession(token, level);
		assertTrue(session.updatePrecise(publication(token, level, 2, 20, true), 140));
		assertFalse(session.updatePrecise(publication(token, level, 2, 99, true), 141));
		assertFalse(session.updatePrecise(publication(token, level, 1, 99, true), 142));
		assertFalse(session.updatePrecise(publication(new nx.pingwheel.common.interaction.ActiveInteraction().begin(), level, 3, 99, true), 143));
		assertFalse(session.updatePrecise(publication(token, new Object(), 3, 99, true), 144));
		var old = session.snapshot(); session.updatePrecise(publication(token, level, 3, 30, true), 145); session.snapshot();
		assertTrue(session.markPresented(old, java.util.Set.of("precise:block")).isEmpty());
		session.abort(); assertFalse(session.updatePrecise(publication(token, level, 4, 40, true), 146));
		assertTrue(session.markPresented(old, java.util.Set.of("precise:block")).isEmpty());
	}

	@Test void preciseDeadzoneAfterPaintRemainsSilentNoAction() {
		var token = new nx.pingwheel.common.interaction.ActiveInteraction().begin(); Object level = new Object();
		var session = liveSession(token, level);
		session.updatePrecise(publication(token, level, 1, 10, true), 140);
		session.markPresented(session.snapshot(), java.util.Set.of("precise:block"));
		assertNull(current(session).focusId());
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(150));
	}

	/** The retained Content frame directly under an active inventory surface. */
	private static SpatialController.MenuView retainedParent(SpatialSelectorSession<?> session) {
		var menus = session.snapshot().radial().menus();
		return menus.get(menus.size() - 2);
	}

	/** The frozen InventoryDisplay slot-centre bearing read from the retained parent frame. */
	private static double inventorySlotBearing(SpatialController.MenuView parent) {
		var inventory = parent.choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow();
		return inventory.startDegrees() + inventory.spanDegrees() / 2.0;
	}

	@ParameterizedTest
	@CsvSource({"false, LEFT", "true, LEFT", "false, RIGHT", "true, RIGHT"})
	void listSideBackUserReturnKeepsThePointerAtTheTriggerAndMovesTheParentCentreThere(boolean hover,
		InventoryListModel.Side backSide) {
		var session = openedList(hover, backSide);
		var trigger = session.snapshot().radial().pointer();
		double backSign = backSide == InventoryListModel.Side.LEFT ? -1.0 : 1.0;
		if (hover) {
			session.moveGui(backSign * 50, 0, 290);
			assertTrue(session.snapshot().inventoryView().backAffordance().focused(), "Back hover precedes the return");
			session.tick(290 + session.snapshot().settings().hoverMillis() + 1);
		} else {
			session.moveGui(backSign * 100, 0, 290);
		}
		var returned = session.snapshot().radial();
		var parent = current(session);
		assertTrue(parent.menuId().endsWith(":content"), "exactly one level returns to Content");
		assertFalse(session.snapshot().inventory().open());
		assertEquals(trigger, returned.pointer(), "the user return leaves the pointer at the trigger");
		assertEquals(trigger, parent.origin(), "the returned parent is rigidly translated to the pointer");
		assertNull(returned.focusId(), "the shared centre is inside the frozen deadzone");
		assertEquals(List.of(returned.pointer()), returned.trail(), "the return seeds exactly one trail point");
		session.tick(5000); session.tick(6000);
		assertEquals(parent.menuId(), current(session).menuId(), "stationary frames cannot reopen the list or navigate");
		assertFalse(session.snapshot().inventory().open());
		assertEquals(0.0, session.snapshot().radial().hoverProgress());
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(6001),
			"a deadzone release abandons instead of repeating the previous direction");
		assertFalse(session.snapshot().active());
	}

	@Test
	void returnedListCentreRequiresAFullFreshStrokeBeforeTheInventoryReopens() {
		var session = openedList(false, InventoryListModel.Side.LEFT);
		session.moveGui(-100, 0, 290);
		assertTrue(current(session).menuId().endsWith(":content"));
		var parent = current(session);
		double bearing = inventorySlotBearing(parent);
		double stroke = session.snapshot().settings().stroke();
		long dwell = session.snapshot().settings().dwellMillis();
		moveTo(session, parent.origin(), bearing, stroke - 1.0, 300);
		session.tick(300 + dwell + 1);
		assertEquals(parent.menuId(), current(session).menuId(), "a partial new stroke cannot pay the pop's fresh-stroke fence");
		assertFalse(session.snapshot().inventory().open());
		moveTo(session, parent.origin(), bearing, stroke, 500);
		session.tick(500 + dwell + 1);
		assertTrue(current(session).menuId().endsWith(":inventory"));
		assertTrue(session.snapshot().inventory().open(), "a full deliberate stroke plus dwell reopens the list");
	}

	@Test
	void listUserReturnNeverUsesTheOldCircumferenceEvenUnderDistantProjection() {
		var session = openedList(false, new Content());
		session.constrainPointer(new SpatialController.Viewport(100, 100, -300, 0), 280);
		var trigger = session.snapshot().radial().pointer();
		session.moveGui(-100, 0, 290);
		assertTrue(current(session).menuId().endsWith(":content"));
		assertFalse(session.snapshot().inventory().open());
		var returned = session.snapshot().radial();
		var parent = current(session);
		assertEquals(trigger, returned.pointer(), "the user return leaves the pointer at the trigger");
		assertEquals(trigger, parent.origin(), "the retained parent is rigidly translated to the pointer");
		assertNull(returned.focusId(), "the shared centre is inside the frozen deadzone");
		assertEquals(List.of(returned.pointer()), returned.trail(), "the pop seeds exactly one point");
		// No pending circumference placement exists: a later projection change
		// re-confines the pointer normally instead of chasing the old slot.
		session.constrainPointer(new SpatialController.Viewport(100, 100, -180, 0), 300);
		assertEquals(229.0, session.snapshot().radial().pointer().x(), 1e-9,
			"the pointer never warps to the old inventory-slot circumference");
	}

	@Test
	void programmaticItemRevocationDoesNotTranslateRetainedOrigins() {
		var target = target("ordinary", "entity_block", true);
		var session = openedList(false, new Content());
		var contentBefore = retainedParent(session);
		double forward = session.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		session.moveGui(forward * 200, 0, 290);
		assertTrue(current(session).menuId().contains(":item:"));

		assertTrue(session.updateContent(inventory(target, 2, true, List.of(row("other", 5L))), 300));

		var contentAfter = retainedParent(session);
		assertEquals(contentBefore.menuId(), contentAfter.menuId());
		assertEquals(contentBefore.origin(), contentAfter.origin(), "a programmatic pop applies no rigid translation");
		assertTrue(current(session).menuId().endsWith(":inventory"));
		assertTrue(session.snapshot().inventory().open());
		assertEquals("other", session.snapshot().inventory().selectedKey());
		assertEquals(current(session).origin().x(), session.snapshot().inventory().axisX(), 1e-9,
			"a reopened list takes the unchanged menu origin instead of a user-return translation");
	}

	@Test
	void freshPhysicalMovementAfterAUserReturnContinuesFromTheReturnedCentre() {
		var session = openedList(false, new Content());
		session.constrainPointer(new SpatialController.Viewport(100, 100, -180, 0), 280);
		var trigger = session.snapshot().radial().pointer();
		session.moveGui(-100, 0, 290);
		assertTrue(current(session).menuId().endsWith(":content"));
		assertEquals(trigger, session.snapshot().radial().pointer());
		session.moveGui(-10, 0, 291);
		assertEquals(trigger.x() - 10.0, session.snapshot().radial().pointer().x(), 1e-9,
			"fresh physical movement continues from the returned centre");
		assertNull(session.snapshot().radial().focusId(),
			"the small movement stays inside the deadzone around the returned parent");
	}

	@Test
	void contentIntentOffTurnsAPropertyIntoAnAttentionLeafWithTheWholeMarkerDefault() {
		var target = target("ordinary", "dropped_item", false);
		Content port = new Content();
		var ref = PresentationPropertyRef.root("pingforit:basic", "pingforit:health");
		port.next = new SpatialSelectorSession.ContentProjection<>(fence(target), 1, false, Status.READY,
			List.of(new SpatialSelectorSession.Property("health", "health", ref, new PresentationValue.NumberValue(7.5),
				List.of(ping("attention"), ping("danger")), ping("attention"))), null);
		var session = sessionOff(target, false, port);
		session.open(0); enter(session, "content", 10);
		var choice = current(session).choices().stream().filter(value -> !value.back()).findFirst().orElseThrow();
		assertFalse(choice.branch(), "OFF keeps no property intent branch");
		assertNotNull(choice.action());
		assertEquals(ping("attention").outlineColor(), choice.outlineColor(), "the Attention border is kept");
		focus(session, choice.id(), 150);
		var intent = assertInstanceOf(SelectorIntent.CreateProperty.class, session.releaseIntent(151));
		assertEquals(ref, intent.property().ref());
		assertEquals(new PresentationValue.NumberValue(7.5), intent.property().observedValue());
		assertEquals("attention", intent.property().pingTypeId());
		assertEquals("loot", intent.mainType().id(), "the whole-marker Target-Type default stays unchanged");
	}

	@Test
	void contentIntentOffDisablesPropertiesWithoutAuthorizedAttentionInsteadOfFallingBack() {
		var target = target("ordinary", "entity", false);
		Content port = new Content();
		port.next = new SpatialSelectorSession.ContentProjection<>(fence(target), 1, false, Status.READY, List.of(
			new SpatialSelectorSession.Property("danger-only", "danger-only",
				PresentationPropertyRef.root("pingforit:basic", "pingforit:danger"), new PresentationValue.NumberValue(1),
				List.of(ping("danger")), ping("danger")),
			new SpatialSelectorSession.Property("empty", "empty",
				PresentationPropertyRef.root("pingforit:basic", "pingforit:empty"), new PresentationValue.NumberValue(2),
				List.of(), null)), null);
		var session = sessionOff(target, false, port);
		session.open(0); enter(session, "content", 10);
		var choices = current(session).choices().stream().filter(value -> !value.back()).toList();
		assertEquals(2, choices.size());
		for (var choice : choices) {
			assertTrue(choice.disabled(), choice.id());
			assertNull(choice.action(), "no fallback annotation is invented");
		}
		focus(session, choices.getFirst().id(), 150);
		assertInstanceOf(SelectorIntent.None.class, session.releaseIntent(151));
	}

	@Test
	void contentIntentOffDirectListReleaseUsesAttentionAndKeepsTheWholeMarkerDefault() {
		var target = target("ordinary", "dropped_item", false);
		Content port = new Content();
		port.next = inventory(target, 1, false, List.of(row("a", 50L)));
		var session = sessionOff(target, false, port);
		session.open(0); enter(session, "content", 10);
		String id = current(session).choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow().id();
		enter(session, id, 150);
		assertTrue(session.snapshot().inventory().open());
		var view = session.snapshot().inventoryView();
		assertNull(view.retainedRowKey());
		assertFalse(view.forwardAvailable(), "OFF has no item Forward entry or hint");
		assertTrue(view.selectedRowCommittable(), "direct release is still eligible under OFF");
		assertEquals(ping("attention").outlineColor(), view.rows().getFirst().outlineColor(),
			"row border follows the item annotation, not the whole-marker Loot type");
		var intent = assertInstanceOf(SelectorIntent.SelectInventory.class, session.releaseIntent(300));
		assertEquals("opaque:a", intent.reference());
		assertEquals("attention", intent.itemType().id());
		assertEquals("loot", intent.mainType().id(), "the whole-marker Loot default is preserved");
	}

	@Test
	void contentIntentOffForwardStrokeNeverOpensAnItemSubmenu() {
		var target = target("ordinary", "entity_block", true);
		Content port = new Content();
		port.next = inventory(target, 1, false, List.of(row("a", 50L)));
		var session = sessionOff(target, false, port);
		session.open(0); enter(session, "content", 10);
		String id = current(session).choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow().id();
		enter(session, id, 150);
		double forward = session.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		session.moveGui(forward * 200, 0, 290);
		assertTrue(current(session).menuId().endsWith(":inventory"), "the frozen OFF toggle keeps Forward inert");
		assertFalse(session.snapshot().inventoryView().forwardAvailable());
		assertTrue(session.snapshot().inventory().open());
	}

	@Test
	void contentIntentOnExposesForwardAndTheFrozenRetainedRowKey() {
		var session = openedList(false, new Content());
		var listView = session.snapshot().inventoryView();
		assertNull(listView.retainedRowKey());
		assertTrue(listView.forwardAvailable());
		assertTrue(listView.selectedRowCommittable());
		double forward = session.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		session.moveGui(forward * 200, 0, 290);
		var itemView = session.snapshot().inventoryView();
		assertEquals("a", itemView.retainedRowKey(), "the frozen item key is exposed while its submenu is active");
		assertEquals(3, itemView.rows().size(), "the full window stays in the paint model");
		assertFalse(itemView.forwardAvailable());
		assertFalse(itemView.selectedRowCommittable(), "direct list release is not active behind the item submenu");
	}

	@Test
	void backFocusRemovesOnlyCommittabilityNotTheForwardAffordance() {
		var session = openedList(false, new Content());
		double back = session.snapshot().inventory().direction().back() == InventoryListModel.Side.LEFT ? -1 : 1;
		session.moveGui(back * 50, 0, 290);
		var view = session.snapshot().inventoryView();
		assertTrue(view.backAffordance().focused());
		assertTrue(view.forwardAvailable(), "Back focus alone does not remove the Forward hint");
		assertFalse(view.selectedRowCommittable(), "Back focus makes direct release ineligible");
	}

	@Test
	void emptySelectionIsNeverCommittableInEitherMode() {
		var target = target("ordinary", "entity_block", true);
		Content port = new Content();
		port.next = inventory(target, 1, false, List.of());
		var session = sessionOff(target, false, port);
		session.open(0); enter(session, "content", 10);
		String id = current(session).choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow().id();
		enter(session, id, 150);
		var view = session.snapshot().inventoryView();
		assertTrue(session.snapshot().inventory().open());
		assertNull(view.retainedRowKey());
		assertFalse(view.forwardAvailable());
		assertFalse(view.selectedRowCommittable(), "an empty list is never committable");
	}

	@Test
	void capacityRelayoutCannotRearmTheSharedHeldBackBlockAfterAnItemReturn() {
		var session = openedList(true, new Content());
		session.moveGui(200, 0, 290);
		var back = current(session).choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
		focus(session, back.id(), 300); session.tick(550);
		String listMenu = current(session).menuId();
		assertTrue(listMenu.endsWith(":inventory"));
		var before = session.snapshot();
		session.updateListGeometry(new SpatialSelectorSession.ListGeometry(1, 64, 26, 22, 15, 70), 560);
		assertEquals(before.radial(), session.snapshot().radial(), "capacity-only relayout is not physical leave input");
		double backSign = session.snapshot().inventory().direction().back() == InventoryListModel.Side.LEFT ? -1 : 1;
		session.moveGui(backSign * 30, 0, 570); session.tick(1000);
		assertEquals(listMenu, current(session).menuId(), "entering Back while the shared block is held cannot cascade");
		session.moveGui(-backSign * 30, 0, 1010);
		session.moveGui(backSign * 30, 0, 1020); session.tick(1270);
		assertTrue(current(session).menuId().endsWith(":content"), "a deliberate physical leave/reentry still permits one return");
	}

	@Test
	void itemClearanceUpdateRepositionsTheActiveItemAndIsInertBeforeEntry() {
		var session = openedList(false, new Content());
		session.updateItemClearance(120, 290); // before entry: caches only
		double forward = session.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		session.moveGui(forward * 200, 0, 291);
		var item = current(session);
		assertTrue(item.menuId().contains(":item:"));
		double axis = session.snapshot().inventory().axisX();
		assertEquals(axis + forward * (GEOMETRY.width() / 2 + 120), item.origin().x(), 1e-9,
			"entry uses the prepared clearance metric");
		var pointer = session.snapshot().radial().pointer();
		var trail = session.snapshot().radial().trail();

		session.updateItemClearance(160, 300);
		var moved = current(session);
		assertTrue(moved.menuId().contains(":item:"));
		assertEquals(axis + forward * (GEOMETRY.width() / 2 + 160), moved.origin().x(), 1e-9);
		assertEquals(item.origin().y(), moved.origin().y(), 1e-9, "the frozen row anchor is kept");
		assertEquals(pointer, session.snapshot().radial().pointer(), "a pure metric update moves no pointer");
		assertEquals(trail, session.snapshot().radial().trail(), "a pure metric update adds no trail");
		assertEquals(0.0, session.snapshot().radial().hoverProgress(), "a pure metric update pays no hover travel");

		var stable = session.snapshot().radial();
		session.updateItemClearance(160, 301);
		assertEquals(stable, session.snapshot().radial(), "an unchanged metric is inert");
		assertThrows(IllegalArgumentException.class, () -> session.updateItemClearance(0.0, 302));
		assertThrows(IllegalArgumentException.class, () -> session.updateItemClearance(Double.NaN, 303));
		assertThrows(IllegalArgumentException.class, () -> session.updateItemClearance(Double.POSITIVE_INFINITY, 304));
	}

	@Test
	void itemSubmenuBackReturnStaysOnTheListWithoutAContentReturnPlacement() {
		var session = openedList(false, new Content());
		double forward = session.snapshot().inventory().direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		session.moveGui(forward * 200, 0, 290);
		assertTrue(current(session).menuId().contains(":item:"));
		var back = current(session).choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow();
		focus(session, back.id(), 300);
		var beforeReturn = session.snapshot().radial().pointer();
		session.tick(300 + session.snapshot().settings().dwellMillis() + 1);
		assertTrue(current(session).menuId().endsWith(":inventory"));
		assertEquals(beforeReturn, session.snapshot().radial().pointer(),
			"the item Back return keeps the list pointer instead of the Content deadzone");
		assertTrue(session.snapshot().inventory().open());
	}
}
