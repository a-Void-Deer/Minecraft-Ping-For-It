package nx.pingwheel.common.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.resource.LanguageUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the same factory and native drag/key callbacks used by the Performance page. */
@NotNullByDefault
class InventoryPerformanceOptionTest {
	@ParameterizedTest
	@EnumSource(value = SettingsCategoryCatalog.Setting.class, names = {
		"INVENTORY_TRACKING_DISTINGUISH_FACES", "INVENTORY_TRACKING_DISTINGUISH_PLAYERS"})
	void actualBooleanFactoryUsesFullWidthNativeButtonsAndFencesStaleReadOnlyCallbacks(SettingsCategoryCatalog.Setting setting) {
		Field field = java.util.Objects.requireNonNull(setting.inventoryField());
		var model = loaded(field, Value.bool(false), true);
		var button = SettingsScreen.createServerInventoryBooleanButton(setting, model);
		assertEquals(SettingsScreenLayout.LARGE_WIDGET_WIDTH, button.getWidth());
		assertNotNull(button.getTooltip());
		assertTrue(button.active);
		assertFalse(model.dirty());
		Component off = button.getMessage();
		button.onPress();
		assertTrue(model.inventoryBoolean(field));
		assertEquals(field.mask(), model.dirtyFields());
		assertNotEquals(off, button.getMessage());
		button.onPress();
		assertFalse(model.dirty());
		assertEquals(off, button.getMessage());
		model.setClientPermission(false);
		button.onPress();
		assertFalse(model.inventoryBoolean(field));
		assertFalse(model.dirty());
		assertFalse(SettingsScreen.createServerInventoryBooleanButton(setting, model).active);
		model.resetForDisconnect();
		button.onPress();
		assertTrue(model.updatePlan().isEmpty());
		assertFalse(model.dirty());
	}

	@ParameterizedTest
	@EnumSource(value = Field.class, names = {"DISPLAY_BYTE_MULTIPLIER", "FRAME_BYTE_MULTIPLIER",
		"TRACKING_DISTINGUISH_FACES", "TRACKING_DISTINGUISH_PLAYERS"}, mode = EnumSource.Mode.EXCLUDE)
	void everyReplacementSliderWritesOnlyItsSelectedLeafAndRevertsExactly(Field field) {
		var model = loaded(field, new Value(false, InventoryPerformanceScale.forField(field).finiteValueAt(0)), true);
		var edits = new AtomicInteger();
		var slider = slider(field, model, edits);
		assertEquals(0, edits.get());
		slider.keyPressed(32, 0, 0);
		slider.keyPressed(262, 0, 0);
		assertEquals(1, edits.get());
		assertEquals(field.mask(), model.dirtyFields());
		var plan = model.updatePlan().orElseThrow();
		assertEquals(field.mask(), plan.changedFields());
		for (Field other : Field.values()) {
			if (other != field) assertEquals(model.authoritative().inventory().value(other), plan.inventory().value(other));
		}
		slider.keyPressed(263, 0, 0);
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());
	}

	@Test
	void constructionRebuildAndRepeatedSelectionNeverWriteOrSnapTheAuthoritativeExtraStop() {
		Field field = Field.PENDING_MEMORY_MIB;
		var model = loaded(field, Value.limit(false, 66), true);
		var edits = new AtomicInteger();
		var slider = slider(field, model, edits);
		assertEquals(0, edits.get());
		assertFalse(model.dirty());
		assertEquals("66", model.inventoryText(field));
		assertEquals(new BigDecimal("66"), model.inventoryScale(field).finiteValueAt(slider.currentPosition()));
		assertTrue(slider.keyPressed(32, 0, 0));
		assertTrue(slider.keyPressed(262, 0, 0));
		assertEquals("72", model.inventoryText(field));
		assertEquals(field.mask(), model.dirtyFields());
		assertEquals(field.mask(), model.updatePlan().orElseThrow().changedFields());

		var rebuilt = slider(field, model, edits);
		assertEquals(1, edits.get());
		assertTrue(rebuilt.keyPressed(32, 0, 0));
		assertTrue(rebuilt.keyPressed(263, 0, 0));
		assertEquals("66", model.inventoryText(field));
		assertEquals(2, edits.get());
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());
		model.selectInventoryPosition(field, rebuilt.currentPosition());
		assertFalse(model.dirty());
	}

	@Test
	void nativeDragReachesBothEndpointsAndUnlimitedPreservesTheFiniteValue() {
		Field field = Field.PENDING_MEMORY_MIB;
		var model = loaded(field, Value.limit(false, 66), true);
		var edits = new AtomicInteger();
		var slider = slider(field, model, edits);
		slider.onClick(slider.getX() + slider.getWidth(), 0);
		assertTrue(model.inventoryUnlimited(field));
		assertEquals("66", model.inventoryText(field));
		assertEquals(new Value(true, new BigDecimal("66")), model.updatePlan().orElseThrow().inventory().value(field));
		slider.onDrag(slider.getX() + slider.getWidth(), 0, 0, 0);
		assertEquals(1, edits.get());

		assertTrue(slider.keyPressed(32, 0, 0));
		assertTrue(slider.keyPressed(263, 0, 0));
		assertFalse(model.inventoryUnlimited(field));
		assertEquals("1024", model.inventoryText(field));
		slider.onDrag(slider.getX(), 0, -slider.getWidth(), 0);
		assertEquals("16", model.inventoryText(field));
		assertFalse(model.inventoryUnlimited(field));
		assertEquals(field.mask(), model.dirtyFields());
	}

	@Test
	void readOnlyConstructionAndAllInputPathsAreInertIncludingAfterPermissionRevocation() {
		Field field = Field.PENDING_MEMORY_MIB;
		var model = loaded(field, Value.limit(true, 66), false);
		var edits = new AtomicInteger();
		var slider = slider(field, model, edits);
		assertFalse(slider.active);
		slider.onClick(0, 0);
		slider.onDrag(0, 0, 0, 0);
		assertFalse(slider.keyPressed(32, 0, 0));
		assertFalse(slider.keyPressed(263, 0, 0));
		model.selectInventoryPosition(field, 0);
		assertEquals("66", model.inventoryText(field));
		assertTrue(model.inventoryUnlimited(field));
		assertEquals(0, edits.get());
		assertFalse(model.dirty());

		var editable = loaded(field, Value.limit(false, 66), true);
		var staleWidget = slider(field, editable, edits);
		staleWidget.keyPressed(32, 0, 0);
		editable.setClientPermission(false);
		staleWidget.onClick(0, 0);
		staleWidget.onDrag(0, 0, 0, 0);
		assertFalse(staleWidget.keyPressed(262, 0, 0));
		assertEquals(0, edits.get());
		assertFalse(editable.dirty());
	}

	@Test
	void everyArrowMovesExactlyOneStopAtRealWidthAndFocusLossEndsAdjustment() {
		Field field = Field.PREVIEW_CLIENT_BYTE_MULTIPLIER;
		var model = loaded(field, new Value(false, new BigDecimal("4")), true);
		var edits = new AtomicInteger();
		var slider = slider(field, model, edits);
		assertFalse(slider.keyPressed(262, 0, 0));
		assertTrue(slider.keyPressed(32, 0, 0));
		slider.keyPressed(262, 0, 0);
		assertEquals("4.5", model.inventoryText(field));
		slider.keyPressed(263, 0, 0);
		assertEquals("4", model.inventoryText(field));
		assertFalse(model.dirty());
		slider.keyPressed(263, 0, 0);
		assertEquals("3.75", model.inventoryText(field));
		assertEquals(3, edits.get());
		slider.setFocused(false);
		assertFalse(slider.keyPressed(262, 0, 0));
		assertEquals(3, edits.get());
	}

	@Test
	void unlimitedSnapshotRetainsItsExtraFiniteStopThroughNavigationAndCanBeRevertedClean() {
		Field field = Field.PENDING_MEMORY_MIB;
		var model = loaded(field, Value.limit(true, 66), true);
		var scale = model.inventoryScale(field);
		model.selectInventoryPosition(field, scale.size() - 2);
		assertEquals("1024", model.inventoryText(field));
		assertFalse(model.inventoryUnlimited(field));
		assertTrue(model.dirty());

		var navigation = new SettingsNavigationModel();
		navigation.selectScope(SettingsNavigationModel.Scope.SERVER);
		navigation.openCategory(SettingsNavigationModel.Category.PERFORMANCE);
		navigation.back();
		navigation.selectScope(SettingsNavigationModel.Scope.CLIENT);
		var rebuiltScale = model.inventoryScale(field);
		int extra = rebuiltScale.positionOf(Value.limit(false, 66));
		model.selectInventoryPosition(field, extra);
		model.selectInventoryPosition(field, rebuiltScale.size() - 1);
		assertEquals("66", model.inventoryText(field));
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());

		model.resetForDisconnect();
		assertFalse(model.inventoryScale(field).finiteValues().contains(new BigDecimal("66")));
	}

	@Test
	void onlyAnAcceptedNewSnapshotReplacesTheSessionExtraStops() {
		Field field = Field.PENDING_MEMORY_MIB;
		var model = loaded(field, Value.limit(false, 66), true);
		assertTrue(model.inventoryScale(field).finiteValues().contains(new BigDecimal("66")));
		model.setInventoryText(field, "67");
		assertTrue(model.inventoryScale(field).finiteValues().contains(new BigDecimal("67")));
		model.setClientPermission(false);
		model.setClientPermission(true);
		assertTrue(model.inventoryScale(field).finiteValues().contains(new BigDecimal("66")));
		assertFalse(model.dirty());

		model.collapseAndDiscard();
		long request = model.beginExpansion();
		var inventory = InventoryConfigValues.defaults().with(field, Value.limit(false, 70));
		var snapshot = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23, inventory);
		assertFalse(model.applySnapshot(request + 1, snapshot));
		assertTrue(model.inventoryScale(field).finiteValues().contains(new BigDecimal("66")));
		assertTrue(model.applySnapshot(request, snapshot));
		assertFalse(model.inventoryScale(field).finiteValues().contains(new BigDecimal("66")));
		assertFalse(model.inventoryScale(field).finiteValues().contains(new BigDecimal("67")));
		assertTrue(model.inventoryScale(field).finiteValues().contains(new BigDecimal("70")));
		assertFalse(model.dirty());
	}

	@Test
	void factorySubmitsTheExactCapturedStopEvenIfTheLegacyTextSeamAddsAnotherExtraStop() {
		Field field = Field.PENDING_MEMORY_MIB;
		var model = loaded(field, Value.limit(false, 66), true);
		var slider = slider(field, model, new AtomicInteger());
		model.setInventoryText(field, "67");
		model.inventoryScale(field);
		slider.keyPressed(32, 0, 0);
		slider.keyPressed(262, 0, 0);
		assertEquals("72", model.inventoryText(field));
		assertEquals(field.mask(), model.dirtyFields());
	}

	@Test
	void labelsComposeLocalizedNameAndValueAndGiveZeroSentinelsDistinctNames() {
		Field field = Field.TRACKING_RESYNC_MIN_PERIODS;
		var model = loaded(field, Value.scalar(0), true);
		var slider = slider(field, model, new AtomicInteger());
		assertNotNull(slider.getTooltip());
		var contents = assertInstanceOf(TranslatableContents.class, slider.getMessage().getContents());
		assertEquals("settings.pingforit.inventory.value", contents.getKey());
		assertEquals(2, contents.getArgs().length);
		var name = assertInstanceOf(Component.class, contents.getArgs()[0]);
		assertEquals(label(field).getKey(), assertInstanceOf(TranslatableContents.class, name.getContents()).getKey());
		var value = assertInstanceOf(Component.class, contents.getArgs()[1]);
		assertEquals("settings.pingforit.inventory.resync_no_cooldown",
			assertInstanceOf(TranslatableContents.class, value.getContents()).getKey());
		var heartbeat = InventoryPerformanceScale.forField(Field.TRACKING_HEARTBEAT_PERIODS);
		assertEquals("settings.pingforit.inventory.heartbeat_disabled", assertInstanceOf(TranslatableContents.class,
			InventoryPerformanceOption.valueLabel(Field.TRACKING_HEARTBEAT_PERIODS, heartbeat, 0).getContents()).getKey());
		assertEquals("0.25", InventoryPerformanceOption.valueLabel(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER,
			InventoryPerformanceScale.forField(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER), 0).getString());
	}

	private static InventoryPerformanceOption.PerformanceSlider slider(Field field, ServerSettingsModel model, AtomicInteger edits) {
		return InventoryPerformanceOption.slider(label(field), field, model, edits::incrementAndGet);
	}

	private static LanguageUtils label(Field field) {
		return LanguageUtils.settings(SettingsCategoryCatalog.settings(SettingsNavigationModel.Category.PERFORMANCE).stream()
			.filter(setting -> setting.inventoryField() == field).findFirst().orElseThrow().id());
	}

	private static ServerSettingsModel loaded(Field field, Value value, boolean editable) {
		var inventory = InventoryConfigValues.defaults().with(field, value);
		var snapshot = new ServerConfigSnapshot(editable, ChannelMode.AUTO, true, 1000, 5, 23, inventory);
		var model = new ServerSettingsModel(editable);
		assertTrue(model.applySnapshot(model.beginSessionIfNeeded(), snapshot));
		return model;
	}
}
