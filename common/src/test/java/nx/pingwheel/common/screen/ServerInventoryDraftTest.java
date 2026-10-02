package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class ServerInventoryDraftTest {
	private static final ServerConfigSnapshot EDITABLE = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23);

	@ParameterizedTest
	@EnumSource(Field.class)
	void eachLeafTracksDirtyRevertsAndBuildsAnIndependentPlan(Field field) {
		var model = loaded(true, EDITABLE);
		String original = model.inventoryText(field);
		model.stepInventoryValue(field, true);
		assertEquals(field.mask(), model.dirtyFields());
		var plan = model.updatePlan().orElseThrow();
		assertEquals(field.mask(), plan.changedFields());
		assertNotEquals(EDITABLE.inventory().value(field), plan.inventory().value(field));
		assertEquals(EDITABLE.inventory(), model.authoritative().inventory());
		model.setInventoryText(field, original);
		assertEquals(0, model.dirtyFields());
		assertEquals(0, model.invalidFieldMask());
		assertTrue(model.updatePlan().isEmpty());
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void malformedLeafDoesNotEraseOtherInvalidDraftsOrPermitPartialUpdates(Field field) {
		var model = loaded(true, EDITABLE);
		String original = model.inventoryText(field);
		model.setRateLimitText("");
		model.setInventoryText(field, "not-a-number");
		assertEquals(ServerConfigUpdate.RATE_LIMIT | field.mask(), model.invalidFieldMask());
		model.toggleInventoryUnlimited(field);
		assertEquals("not-a-number", model.inventoryText(field));
		assertEquals(ServerConfigUpdate.RATE_LIMIT | field.mask(), model.invalidFieldMask());
		assertTrue(model.updatePlan().isEmpty());
		model.setInventoryText(field, original);
		assertEquals(ServerConfigUpdate.RATE_LIMIT, model.invalidFieldMask());
		model.setRateLimitText("5");
		if (field.supportsUnlimited()) {
			assertEquals(field.mask(), model.updatePlan().orElseThrow().changedFields());
			model.toggleInventoryUnlimited(field);
		}
		assertFalse(model.dirty());
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void readOnlyValuesRemainVisibleButEveryEditOperationIsInert(Field field) {
		var model = loaded(false, EDITABLE.withCanEdit(false));
		String original = model.inventoryText(field);
		model.setInventoryText(field, "99");
		model.toggleInventoryUnlimited(field);
		model.stepInventoryValue(field, true);
		assertTrue(model.canView());
		assertFalse(model.canEdit());
		assertEquals(original, model.inventoryText(field));
		assertFalse(model.inventoryUnlimited(field));
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void unlimitedLeavesRetainTheirFiniteTextAndCompareModeIndependently(Field field) {
		if (!field.supportsUnlimited()) return;
		var model = loaded(true, EDITABLE);
		String original = model.inventoryText(field);
		model.toggleInventoryUnlimited(field);
		assertEquals(original, model.inventoryText(field));
		assertEquals(new Value(true, EDITABLE.inventory().value(field).value()), model.updatePlan().orElseThrow().inventory().value(field));
		model.stepInventoryValue(field, true);
		String changed = model.inventoryText(field);
		model.toggleInventoryUnlimited(field);
		assertEquals(changed, model.inventoryText(field));
		assertEquals(field.mask(), model.dirtyFields());
		model.setInventoryText(field, original);
		assertFalse(model.dirty());
	}

	@Test
	void decimalParsingUsesNumericEqualityAndRejectsOffGridOverflowAndNonFiniteInput() {
		var model = loaded(true, EDITABLE);
		Field multiplier = Field.TRACKING_STREAM_BYTE_MULTIPLIER;
		model.setInventoryText(multiplier, "1.000");
		assertFalse(model.dirty());
		for (String invalid : new String[] {"", ".", "NaN", "Infinity", "1e100", "-1", "0", "4.25", "99999999999999999999999999999999999"}) {
			model.setInventoryText(multiplier, invalid);
			assertEquals(multiplier.mask(), model.invalidFieldMask(), invalid);
			assertEquals(invalid, model.inventoryText(multiplier));
		}
		model.setInventoryText(multiplier, "0.5");
		model.setInventoryText(Field.PHYSICAL_SLOTS_PER_TICK, "2147483648");
		assertEquals(Field.PHYSICAL_SLOTS_PER_TICK.mask(), model.invalidFieldMask());
		model.setInventoryText(Field.PHYSICAL_SLOTS_PER_TICK, "0");
		assertTrue(model.hasInvalidDraft());
		model.setInventoryText(Field.PHYSICAL_SLOTS_PER_TICK, "1");
		model.setInventoryText(Field.TRACKING_HEARTBEAT_PERIODS, "0");
		assertEquals(0, model.invalidFieldMask());
		assertEquals(Value.scalar(0), model.updatePlan().orElseThrow().inventory().value(Field.TRACKING_HEARTBEAT_PERIODS));
	}

	@Test
	void exactStepsCrossPiecewiseBoundariesAndSaturateWithoutChangingOtherDrafts() {
		var model = loaded(true, EDITABLE);
		model.setInventoryText(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER, "4");
		model.stepInventoryValue(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER, false);
		assertEquals("3.75", model.inventoryText(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER));
		model.stepInventoryValue(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER, true);
		assertEquals("4", model.inventoryText(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER));
		model.stepInventoryValue(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER, true);
		assertEquals("4.5", model.inventoryText(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER));
		model.setInventoryText(Field.TRACKING_GLOBAL_BYTE_MULTIPLIER, "16");
		model.stepInventoryValue(Field.TRACKING_GLOBAL_BYTE_MULTIPLIER, false);
		assertEquals("15.5", model.inventoryText(Field.TRACKING_GLOBAL_BYTE_MULTIPLIER));
		model.setInventoryText(Field.PENDING_MEMORY_MIB, "32");
		model.stepInventoryValue(Field.PENDING_MEMORY_MIB, false);
		assertEquals("31", model.inventoryText(Field.PENDING_MEMORY_MIB));
		model.stepInventoryValue(Field.PENDING_MEMORY_MIB, true);
		model.stepInventoryValue(Field.PENDING_MEMORY_MIB, true);
		assertEquals("34", model.inventoryText(Field.PENDING_MEMORY_MIB));
		model.setInventoryText(Field.PENDING_MEMORY_MIB, "512");
		model.stepInventoryValue(Field.PENDING_MEMORY_MIB, true);
		assertEquals("512", model.inventoryText(Field.PENDING_MEMORY_MIB));
		assertEquals("4.5", model.inventoryText(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER));
	}

	@Test
	void cleanNavigationPermissionAndDisconnectKeepTheEstablishedSessionLifecycle() {
		var model = loaded(true, EDITABLE);
		var navigation = new SettingsNavigationModel();
		Field field = Field.TRACKING_GRACE_PERIODS;
		model.setInventoryText(field, "");
		navigation.selectScope(SettingsNavigationModel.Scope.SERVER);
		navigation.openCategory(SettingsNavigationModel.Category.PERFORMANCE);
		navigation.back();
		navigation.selectScope(SettingsNavigationModel.Scope.CLIENT);
		assertEquals("", model.inventoryText(field));
		assertEquals(-1L, model.beginSessionIfNeeded());
		model.markClean();
		assertEquals("", model.inventoryText(field));
		assertEquals(0, model.invalidFieldMask());
		assertTrue(model.updatePlan().isEmpty());
		model.setInventoryText(field, "0");
		model.setClientPermission(false);
		assertTrue(model.canView());
		assertEquals("4", model.inventoryText(field));
		assertEquals(0, model.invalidFieldMask());
		model.setClientPermission(true);
		assertTrue(model.canEdit());
		model.setInventoryText(field, "8");
		model.resetForDisconnect();
		assertEquals("", model.inventoryText(field));
		assertFalse(model.inventoryUnlimited(field));
		assertFalse(model.canView());
		assertFalse(model.dirty());
	}

	@Test
	void unsafeAndStaleInventoryResponsesCannotInstallAViewOrOverwriteDrafts() {
		var model = new ServerSettingsModel(true);
		long request = model.beginSessionIfNeeded();
		var unsafe = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 0, 0, 7, new InventoryConfigValues(null));
		assertFalse(model.applySnapshot(request, unsafe));
		assertTrue(model.loading());
		assertFalse(model.canView());
		assertTrue(model.applySnapshot(request, EDITABLE));
		model.setInventoryText(Field.PREVIEW_PERIOD_TICKS, "9");
		assertFalse(model.applySnapshot(request, EDITABLE));
		assertEquals("9", model.inventoryText(Field.PREVIEW_PERIOD_TICKS));
		model.resetForDisconnect();
		assertFalse(model.applySnapshot(request, EDITABLE));
	}

	@Test
	void deniedInventoryViewAndPromotionRequireTheExistingPermissionRetryTransition() {
		var model = loaded(true, EDITABLE.withCanEdit(false));
		assertTrue(model.accessDenied());
		assertEquals("4", model.inventoryText(Field.TRACKING_GRACE_PERIODS));
		model.setInventoryText(Field.TRACKING_GRACE_PERIODS, "8");
		assertEquals("4", model.inventoryText(Field.TRACKING_GRACE_PERIODS));
		assertEquals(-1L, model.beginSessionIfNeeded());
		model.setClientPermission(false);
		model.setClientPermission(true);
		assertFalse(model.canView());
		long fresh = model.beginSessionIfNeeded();
		assertTrue(fresh > 0L);
		assertTrue(model.applySnapshot(fresh, EDITABLE));
		assertTrue(model.canEdit());
		assertFalse(model.dirty());
		model = loaded(false, EDITABLE.withCanEdit(false));
		model.setClientPermission(true);
		assertFalse(model.canView());
		assertTrue(model.beginSessionIfNeeded() > 0L);
	}

	private static ServerSettingsModel loaded(boolean permission, ServerConfigSnapshot snapshot) {
		var model = new ServerSettingsModel(permission);
		assertTrue(model.applySnapshot(model.beginSessionIfNeeded(), snapshot));
		return model;
	}
}
