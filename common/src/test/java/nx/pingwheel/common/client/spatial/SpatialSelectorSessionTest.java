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
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.render.SpatialInventoryView.Status;

import static org.junit.jupiter.api.Assertions.*;

class SpatialSelectorSessionTest {
	private static final SpatialSelectorSession.ListGeometry GEOMETRY =
		new SpatialSelectorSession.ListGeometry(4, 180, 17, 23, 19, 85);

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
	private static SpatialSelectorSettings.Snapshot settings(boolean hover) {
		return new SpatialSelectorSettings.Snapshot(30, 90, 120, 140, new BigDecimal("0.5"), hover, 250, true, false);
	}
	private static SpatialSelectorSession.ContentFence fence(SpatialSelectorSession.CapturedTarget target) {
		return new SpatialSelectorSession.ContentFence(31, 42, 53, target.candidateId());
	}
	private static SpatialSelectorSession<String> session(SpatialSelectorSession.CapturedTarget target,
		Map<String, SpatialSelectorSession.CapturedTarget> precise, boolean hover, Content port) {
		return new SpatialSelectorSession<>(target, precise, settings(hover), fence(target), port, GEOMETRY);
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
		enter(session, id, 150);
		assertTrue(session.snapshot().inventory().open());
		return session;
	}
	private static SpatialSelectorSession<String> openedList(boolean hover, InventoryListModel.Side backSide) {
		if (backSide == InventoryListModel.Side.LEFT) return openedList(hover, new Content());
		var target = target("ordinary", "entity_block", true);
		Content port = new Content();
		var properties = java.util.stream.IntStream.range(0, 3).mapToObj(index ->
			new SpatialSelectorSession.Property("value" + index, "value" + index,
				PresentationPropertyRef.root("pingforit:basic", "pingforit:value" + index), new PresentationValue.NumberValue(index),
				List.of(ping("attention")), ping("attention"))).toList();
		port.next = new SpatialSelectorSession.ContentProjection<>(fence(target), 1, false, Status.UPDATING, properties,
			inventory(target, 1, false, List.of(row("a", 50L), row("b", 30L), row("c", 10L))).inventory());
		var session = session(target, Map.of(), hover, port);
		session.open(0); enter(session, "content", 10);
		String id = current(session).choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow().id();
		enter(session, id, 150);
		assertEquals(backSide, session.snapshot().inventory().direction().back());
		return session;
	}

	@Test
	void rootMapFreezesAllowedIntentAndCenterIsNotCancellation() {
		var target = target("ordinary", "entity_block", true);
		var session = session(target, Map.of(), false, new Content());
		session.open(0);
		var choices = current(session).choices();
		assertEquals(List.of("danger", "reserved-ne", "content", "reserved-se", "cancel-marker", "precise", "intent", "settings"),
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
	void inventoryEnteredLeftOfParentUsesRightBackAndLeftForward() {
		var target = target("ordinary", "entity_block", true);
		Content port = new Content();
		var properties = java.util.stream.IntStream.range(0, 3).mapToObj(index ->
			new SpatialSelectorSession.Property("value" + index, "value" + index,
				PresentationPropertyRef.root("pingforit:basic", "pingforit:value" + index), new PresentationValue.NumberValue(index),
				List.of(ping("attention")), ping("attention"))).toList();
		port.next = new SpatialSelectorSession.ContentProjection<>(fence(target), 1, false, Status.UPDATING, properties,
			new SpatialSelectorSession.InventoryPreview<>("inventory", Status.UPDATING, List.of(row("a", 5L)),
				List.of(ping("attention")), ping("attention")));
		var session = session(target, Map.of(), false, port);
		session.open(0); enter(session, "content", 10);
		String inventory = current(session).choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow().id();
		enter(session, inventory, 150);
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
}
