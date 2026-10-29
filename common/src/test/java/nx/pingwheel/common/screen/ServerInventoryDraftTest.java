package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import nx.pingwheel.common.config.InventorySizeScale;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ServerInventoryDraftTest {
	private static final ServerConfigSnapshot EDITABLE = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23);
	private static final Field DISPLAY = Field.DISPLAY_BYTE_MULTIPLIER;
	private static final Field FRAME = Field.FRAME_BYTE_MULTIPLIER;

	@ParameterizedTest
	@EnumSource(value = Field.class, names = {"TRACKING_DISTINGUISH_FACES", "TRACKING_DISTINGUISH_PLAYERS"}, mode = EnumSource.Mode.EXCLUDE)
	void eachLeafTracksDirtyRevertsAndBuildsAnIndependentPlan(Field field) {
		BigDecimal initial = field.isSizeScale() ? field.sizeScale().valueAt(0)
			: InventoryPerformanceScale.forField(field).finiteValueAt(0);
		var snapshot = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23,
			EDITABLE.inventory().with(field, new Value(false, initial)));
		var model = loaded(true, snapshot);
		String original = model.inventoryText(field);
		model.stepInventoryValue(field, true);
		assertEquals(field.mask(), model.dirtyFields());
		var plan = model.updatePlan().orElseThrow();
		assertEquals(field.mask(), plan.changedFields());
		assertNotEquals(snapshot.inventory().value(field), plan.inventory().value(field));
		assertEquals(snapshot.inventory(), model.authoritative().inventory());
		model.setInventoryText(field, original);
		assertEquals(0, model.dirtyFields());
		assertEquals(0, model.invalidFieldMask());
		assertTrue(model.updatePlan().isEmpty());
	}

	@ParameterizedTest
	@EnumSource(value = Field.class, names = {"TRACKING_DISTINGUISH_FACES", "TRACKING_DISTINGUISH_PLAYERS"}, mode = EnumSource.Mode.EXCLUDE)
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
		model.toggleInventoryBoolean(field);
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
		var initial = new Value(false, InventoryPerformanceScale.forField(field).finiteValueAt(0));
		var snapshot = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23,
			EDITABLE.inventory().with(field, initial));
		var model = loaded(true, snapshot);
		String original = model.inventoryText(field);
		model.toggleInventoryUnlimited(field);
		assertEquals(original, model.inventoryText(field));
		assertEquals(new Value(true, initial.value()), model.updatePlan().orElseThrow().inventory().value(field));
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
		model.setInventoryText(Field.PHYSICAL_SLOTS_PER_TICK, "16");
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
		model.setInventoryText(Field.PENDING_MEMORY_MIB, "64");
		model.stepInventoryValue(Field.PENDING_MEMORY_MIB, false);
		assertEquals("60", model.inventoryText(Field.PENDING_MEMORY_MIB));
		model.stepInventoryValue(Field.PENDING_MEMORY_MIB, true);
		model.stepInventoryValue(Field.PENDING_MEMORY_MIB, true);
		assertEquals("72", model.inventoryText(Field.PENDING_MEMORY_MIB));
		model.setInventoryText(Field.PENDING_MEMORY_MIB, "1024");
		model.stepInventoryValue(Field.PENDING_MEMORY_MIB, true);
		assertEquals("1024", model.inventoryText(Field.PENDING_MEMORY_MIB));
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

	@Test
	void sizeScaleSlidersUseOrderedPositionsAndExactPhysicalEndpoints() {
		var model = loaded(true, EDITABLE);
		assertEquals(3, model.sizeScalePosition(DISPLAY));
		assertEquals(3, model.sizeScalePosition(FRAME));

		model.setSizeScalePosition(FRAME, 63);
		model.setSizeScalePosition(DISPLAY, 0);
		assertEquals("0.25", model.inventoryText(DISPLAY));
		assertEquals(1024L, InventorySizeScale.DISPLAY.bytes(sizeDraft(model, DISPLAY)));
		model.setSizeScalePosition(DISPLAY, 7);
		assertEquals("2", model.inventoryText(DISPLAY));
		model.setSizeScalePosition(DISPLAY, 8);
		assertEquals("2.5", model.inventoryText(DISPLAY));
		model.setSizeScalePosition(DISPLAY, 19);
		assertEquals("8", model.inventoryText(DISPLAY));
		model.setSizeScalePosition(DISPLAY, 20);
		assertEquals("9", model.inventoryText(DISPLAY));
		model.setSizeScalePosition(DISPLAY, 43);
		assertEquals("32", model.inventoryText(DISPLAY));
		assertEquals(128L * 1024L, InventorySizeScale.DISPLAY.bytes(sizeDraft(model, DISPLAY)));
		assertEquals("16", model.inventoryText(FRAME));
		assertEquals(512L * 1024L, InventorySizeScale.FRAME.bytes(sizeDraft(model, FRAME)));
	}

	@Test
	void frameBudgetCapsTheDisplaySliderAndLowersItAtomically() {
		var model = loaded(true, EDITABLE);
		assertEquals(30L * 1024L, InventorySizeScale.DISPLAY.bytes(
			InventorySizeScale.DISPLAY.valueAt(model.maximumDisplayPosition())));
		model.setSizeScalePosition(DISPLAY, 43);
		assertEquals("7.5", model.inventoryText(DISPLAY));
		assertNull(model.sizeAdjustment());
		assertEquals(DISPLAY.mask(), model.dirtyFields());

		model.setSizeScalePosition(FRAME, 0);
		assertEquals("1.5", model.inventoryText(DISPLAY));
		assertEquals(6L * 1024L, InventorySizeScale.DISPLAY.bytes(
			InventorySizeScale.DISPLAY.valueAt(model.maximumDisplayPosition())));
		assertEquals("0.25", model.inventoryText(FRAME));
		assertEquals(DISPLAY.mask() | FRAME.mask(), model.dirtyFields());
		var adjustment = model.sizeAdjustment();
		assertNotNull(adjustment);
		assertEquals(new BigDecimal("1.5"), adjustment.displayMultiplier());
		assertEquals(new BigDecimal("0.25"), adjustment.frameMultiplier());

		var plan = model.updatePlan().orElseThrow();
		assertEquals(DISPLAY.mask() | FRAME.mask(), plan.changedFields());
		assertTrue(plan.inventory().isSizePairCompatible());
		assertTrue(plan.appliedTo(EDITABLE).isPresent());
	}

	@Test
	void raisingTheFrameNeverRaisesTheDisplayAndRejectsForbiddenSelections() {
		var model = loaded(true, EDITABLE);
		model.setSizeScalePosition(FRAME, InventorySizeScale.FRAME.indexOf(new BigDecimal("4")));
		// The 2 KiB reserve leaves 126 KiB, but the display grid above 32 KiB
		// advances by 4 KiB, so the greatest compatible legal value is 124 KiB.
		assertEquals(124L * 1024L, InventorySizeScale.DISPLAY.bytes(
			InventorySizeScale.DISPLAY.valueAt(model.maximumDisplayPosition())));
		model.setSizeScalePosition(DISPLAY, 43);
		assertEquals("31", model.inventoryText(DISPLAY));
		assertNull(model.sizeAdjustment());

		model.setSizeScalePosition(FRAME, InventorySizeScale.FRAME.indexOf(new BigDecimal("4.25")));
		assertEquals("31", model.inventoryText(DISPLAY));
		assertNull(model.sizeAdjustment());
		model.setSizeScalePosition(DISPLAY, 43);
		assertEquals("32", model.inventoryText(DISPLAY));
		assertEquals(128L * 1024L, InventorySizeScale.DISPLAY.bytes(sizeDraft(model, DISPLAY)));
	}

	@Test
	void sizeAdjustmentNoticeClearsOnRevertAndSnapshotRefresh() {
		var model = loaded(true, EDITABLE);
		model.setSizeScalePosition(DISPLAY, 43);
		model.setSizeScalePosition(FRAME, 0);
		assertNotNull(model.sizeAdjustment());
		assertEquals("1.5", model.inventoryText(DISPLAY));

		model.setSizeScalePosition(FRAME, model.sizeScalePosition(FRAME) + 1);
		assertNull(model.sizeAdjustment());
		assertEquals(DISPLAY.mask() | FRAME.mask(), model.dirtyFields());

		model.setSizeScalePosition(FRAME, 3);
		model.setSizeScalePosition(DISPLAY, 43);
		model.setSizeScalePosition(FRAME, 0);
		assertNotNull(model.sizeAdjustment());
		model.setSizeScalePosition(FRAME, 0);
		assertNull(model.sizeAdjustment());

		model.setSizeScalePosition(FRAME, 3);
		model.setSizeScalePosition(DISPLAY, 43);
		model.setSizeScalePosition(FRAME, 0);
		assertNotNull(model.sizeAdjustment());
		model.setInventoryText(DISPLAY, "1");
		assertNull(model.sizeAdjustment());
		assertEquals(FRAME.mask(), model.dirtyFields());

		model.setSizeScalePosition(FRAME, 3);
		model.setSizeScalePosition(DISPLAY, 43);
		model.setSizeScalePosition(FRAME, 0);
		assertNotNull(model.sizeAdjustment());
		model.setClientPermission(false);
		assertNull(model.sizeAdjustment());
		model.setClientPermission(true);
		assertNull(model.sizeAdjustment());
		assertFalse(model.dirty());
		assertEquals("1", model.inventoryText(FRAME));
		assertEquals("1", model.inventoryText(DISPLAY));
	}

	@Test
	void sizeScaleLeavesHaveNoUnlimitedModeAndIgnoreReadOnlyEdits() {
		var model = loaded(true, EDITABLE);
		model.toggleInventoryUnlimited(DISPLAY);
		model.toggleInventoryUnlimited(FRAME);
		assertFalse(model.inventoryUnlimited(DISPLAY));
		assertFalse(model.inventoryUnlimited(FRAME));
		assertFalse(model.dirty());

		var readOnly = loaded(false, EDITABLE.withCanEdit(false));
		int displayPosition = readOnly.sizeScalePosition(DISPLAY);
		readOnly.setSizeScalePosition(DISPLAY, 43);
		readOnly.setSizeScalePosition(FRAME, 0);
		readOnly.setInventoryText(DISPLAY, "32");
		readOnly.setInventoryText(FRAME, "0.25");
		readOnly.toggleInventoryUnlimited(DISPLAY);
		assertEquals(displayPosition, readOnly.sizeScalePosition(DISPLAY));
		assertEquals("1", readOnly.inventoryText(DISPLAY));
		assertEquals("1", readOnly.inventoryText(FRAME));
		assertNull(readOnly.sizeAdjustment());
		assertFalse(readOnly.dirty());
		assertTrue(readOnly.updatePlan().isEmpty());

		var unloaded = new ServerSettingsModel(true);
		assertEquals(3, unloaded.sizeScalePosition(DISPLAY));
		assertEquals(3, unloaded.sizeScalePosition(FRAME));
		assertEquals(30L * 1024L, InventorySizeScale.DISPLAY.bytes(
			InventorySizeScale.DISPLAY.valueAt(unloaded.maximumDisplayPosition())));
		unloaded.setSizeScalePosition(FRAME, 0);
		unloaded.setInventoryText(FRAME, "0.25");
		assertEquals("", unloaded.inventoryText(FRAME));
		assertNull(unloaded.sizeAdjustment());
		assertFalse(unloaded.dirty());
	}

	@Test
	void sizeScaleStepsCrossThePiecewiseBoundariesAndStopAtTheFrameCompatibleMaximum() {
		var model = loaded(true, EDITABLE);
		model.setInventoryText(DISPLAY, "2");
		model.stepInventoryValue(DISPLAY, true);
		assertEquals("2.5", model.inventoryText(DISPLAY));
		model.stepInventoryValue(DISPLAY, false);
		assertEquals("2", model.inventoryText(DISPLAY));
		model.setInventoryText(DISPLAY, "7.5");
		model.stepInventoryValue(DISPLAY, true);
		assertEquals("7.5", model.inventoryText(DISPLAY));
		model.setInventoryText(FRAME, "0.25");
		model.stepInventoryValue(DISPLAY, true);
		assertEquals("1.5", model.inventoryText(DISPLAY));
		model.setInventoryText(FRAME, "16");
		model.stepInventoryValue(FRAME, false);
		assertEquals("15.75", model.inventoryText(FRAME));
	}

	@Test
	void validFrameEditsReconcileThePairForSliderStepAndText() {
		var stepped = loaded(true, EDITABLE);
		stepped.setInventoryText(DISPLAY, "7.5");
		assertEquals("7.5", stepped.inventoryText(DISPLAY));
		stepped.stepInventoryValue(FRAME, false);
		assertEquals("0.75", stepped.inventoryText(FRAME));
		assertEquals("5.5", stepped.inventoryText(DISPLAY));
		assertEquals(DISPLAY.mask() | FRAME.mask(), stepped.dirtyFields());
		assertEquals(0, stepped.invalidFieldMask());
		var adjustment = stepped.sizeAdjustment();
		assertNotNull(adjustment);
		assertEquals(new BigDecimal("5.5"), adjustment.displayMultiplier());
		assertEquals(new BigDecimal("0.75"), adjustment.frameMultiplier());
		var steppedPlan = stepped.updatePlan().orElseThrow();
		assertTrue(steppedPlan.inventory().isSizePairCompatible());
		assertTrue(steppedPlan.appliedTo(EDITABLE).isPresent());

		var text = loaded(true, EDITABLE);
		text.setInventoryText(DISPLAY, "7.5");
		text.setInventoryText(FRAME, "0.25");
		assertEquals("0.25", text.inventoryText(FRAME));
		assertEquals("1.5", text.inventoryText(DISPLAY));
		assertEquals(DISPLAY.mask() | FRAME.mask(), text.dirtyFields());
		assertNotNull(text.sizeAdjustment());
		assertTrue(text.updatePlan().orElseThrow().inventory().isSizePairCompatible());

		text.setInventoryText(FRAME, "0.5");
		assertNull(text.sizeAdjustment());
		assertEquals(DISPLAY.mask() | FRAME.mask(), text.dirtyFields());
	}

	@Test
	void validDisplayTextEditsClampToTheFrameCompatibleMaximum() {
		var model = loaded(true, EDITABLE);
		model.setInventoryText(DISPLAY, "32");
		assertEquals("7.5", model.inventoryText(DISPLAY));
		assertEquals(DISPLAY.mask(), model.dirtyFields());
		assertNull(model.sizeAdjustment());
		assertEquals(0, model.invalidFieldMask());
		assertTrue(model.updatePlan().orElseThrow().inventory().isSizePairCompatible());

		model.setInventoryText(FRAME, "0.25");
		model.setInventoryText(DISPLAY, "32");
		assertEquals("1.5", model.inventoryText(DISPLAY));
		assertTrue(model.updatePlan().orElseThrow().inventory().isSizePairCompatible());
	}

	@Test
	void noSupportedSizeEntrypointEmitsAnInvalidPairPlan() {
		for (Field field : java.util.List.of(DISPLAY, FRAME)) {
			for (int entry = 0; entry < 3; entry++) {
				var model = loaded(true, EDITABLE);
				model.setInventoryText(DISPLAY, "7.5");
				switch (entry) {
					case 0 -> model.setSizeScalePosition(field, 0);
					case 1 -> model.stepInventoryValue(field, false);
					default -> model.setInventoryText(field, field == FRAME ? "0.25" : "32");
				}
				assertEquals(0, model.invalidFieldMask(), field + " entry " + entry);
				var plan = model.updatePlan().orElseThrow();
				assertTrue(plan.inventory().isSizePairCompatible(), field + " entry " + entry);
				assertTrue(plan.appliedTo(EDITABLE).isPresent(), field + " entry " + entry);
				if (field == FRAME) {
					assertEquals(DISPLAY.mask() | FRAME.mask(), model.dirtyFields(), field + " entry " + entry);
				} else {
					assertEquals(DISPLAY.mask(), model.dirtyFields(), field + " entry " + entry);
				}
			}
		}
	}

	@Test
	void malformedSizeTextStaysVisibleAndBlocksThePlan() {
		var model = loaded(true, EDITABLE);
		model.setInventoryText(DISPLAY, "not-a-number");
		assertEquals("not-a-number", model.inventoryText(DISPLAY));
		assertEquals(DISPLAY.mask(), model.invalidFieldMask());
		assertTrue(model.updatePlan().isEmpty());

		model.setInventoryText(FRAME, "0.25");
		assertEquals("not-a-number", model.inventoryText(DISPLAY));
		assertEquals("0.25", model.inventoryText(FRAME));
		assertEquals(DISPLAY.mask(), model.invalidFieldMask());
		assertNull(model.sizeAdjustment());
		assertTrue(model.updatePlan().isEmpty());

		model.setInventoryText(DISPLAY, "1");
		assertEquals(FRAME.mask(), model.dirtyFields());
		assertTrue(model.updatePlan().orElseThrow().inventory().isSizePairCompatible());
	}

	@ParameterizedTest
	@EnumSource(value = Field.class, names = {"TRACKING_DISTINGUISH_FACES", "TRACKING_DISTINGUISH_PLAYERS"})
	void booleanEditsStayIndependentRevertCleanAndRejectNumericAndUnlimitedEntrypoints(Field field) {
		var model = loaded(true, EDITABLE);
		model.toggleInventoryBoolean(field);
		assertTrue(model.inventoryBoolean(field));
		assertEquals(field.mask(), model.dirtyFields());
		assertEquals(0, model.invalidFieldMask());
		var plan = model.updatePlan().orElseThrow();
		assertEquals(field.mask(), plan.changedFields());
		assertTrue(plan.inventory().value(field).booleanValue());
		for (Field other : Field.values()) {
			if (other != field) assertEquals(EDITABLE.inventory().value(other), plan.inventory().value(other));
		}
		model.setInventoryText(field, "0");
		model.stepInventoryValue(field, false);
		model.selectInventoryPosition(field, 0);
		model.toggleInventoryUnlimited(field);
		assertTrue(model.inventoryBoolean(field));
		assertFalse(model.inventoryUnlimited(field));
		assertThrows(IllegalArgumentException.class, () -> InventoryPerformanceScale.forField(field));
		model.toggleInventoryBoolean(field);
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());
	}

	@ParameterizedTest
	@EnumSource(value = Field.class, names = {"TRACKING_DISTINGUISH_FACES", "TRACKING_DISTINGUISH_PLAYERS"})
	void booleanDraftLifecycleUsesOnlyLatestCorrelatedSnapshotAndRetainsReadOnlyTrue(Field field) {
		var enabled = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23,
			EDITABLE.inventory().with(field, Value.bool(true)));
		var model = loaded(true, enabled);
		model.toggleInventoryBoolean(field);
		assertFalse(model.inventoryBoolean(field));
		model.setClientPermission(false);
		assertTrue(model.canView());
		assertTrue(model.inventoryBoolean(field));
		model.toggleInventoryBoolean(field);
		assertTrue(model.inventoryBoolean(field));
		assertFalse(model.dirty());
		model.setClientPermission(true);
		model.toggleInventoryBoolean(field);
		assertEquals(field.mask(), model.dirtyFields());
		model.resetForDisconnect();
		assertFalse(model.canView());
		assertFalse(model.inventoryBoolean(field));
		model.setClientPermission(true);
		long stale = model.beginSessionIfNeeded();
		model.collapseAndDiscard();
		long fresh = model.beginSessionIfNeeded();
		assertFalse(model.applySnapshot(stale, enabled));
		assertTrue(model.applySnapshot(fresh, enabled.withCanEdit(false)));
		model.toggleInventoryBoolean(field);
		assertTrue(model.inventoryBoolean(field));
		assertTrue(model.updatePlan().isEmpty());
		model.setClientPermission(false);
		model.setClientPermission(true);
		long retry = model.beginSessionIfNeeded();
		assertFalse(model.applySnapshot(fresh, enabled));
		assertTrue(model.applySnapshot(retry, EDITABLE));
		assertFalse(model.inventoryBoolean(field));
		assertFalse(model.dirty());
	}

	private static BigDecimal sizeDraft(ServerSettingsModel model, Field field) {
		return field.parse(model.inventoryText(field), model.inventoryUnlimited(field)).orElseThrow().value();
	}

	private static ServerSettingsModel loaded(boolean permission, ServerConfigSnapshot snapshot) {
		var model = new ServerSettingsModel(permission);
		assertTrue(model.applySnapshot(model.beginSessionIfNeeded(), snapshot));
		return model;
	}
}
