package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.screen.SettingsCategoryCatalog.Setting;
import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettingsCategoryCatalogTest {
	@Test
	void everyExistingControlAppearsExactlyOnceInTheApprovedCategoryOrder() {
		assertEquals(List.of(
			Setting.PING_DISTANCE,
			Setting.MARKER_DISPLAY_DURATION,
			Setting.PING_SIZE,
			Setting.ITEM_ICON_VISIBLE,
			Setting.DIRECTION_INDICATOR_VISIBLE,
			Setting.PLAYER_INFO_MODE,
			Setting.TEAM_COLOR_MODE), SettingsCategoryCatalog.settings(Category.MARKER_DISPLAY));
		assertEquals(List.of(
			Setting.MARK_TRANSPARENT_BLOCKS,
			Setting.MARK_BLACKLISTED_TARGETS,
			Setting.MARK_FLUIDS), SettingsCategoryCatalog.settings(Category.TARGET_SELECTION));
		assertEquals(List.of(
			Setting.SPATIAL_ROOT_DISTANCE,
			Setting.SPATIAL_SUBMENU_RADIUS_SCALE,
			Setting.SPATIAL_INVENTORY_MAX_WIDTH_FRACTION,
			Setting.SPATIAL_INVENTORY_MAX_HEIGHT_FRACTION,
			Setting.WHEEL_OPACITY,
			Setting.WHEEL_TARGET_OPACITY,
			Setting.WHEEL_TARGET_FONT_SIZE,
			Setting.WHEEL_OPTION_FONT_SIZE,
			Setting.SPATIAL_SHOW_TRAIL,
			Setting.SPATIAL_TRAIL_LENGTH,
			Setting.SPATIAL_TRAIL_OPACITY,
			Setting.SPATIAL_REDUCE_MOTION), SettingsCategoryCatalog.settings(Category.WHEEL_APPEARANCE));
		assertEquals(List.of(
			Setting.WHEEL_HOLD_MILLIS,
			Setting.SPATIAL_GESTURE_SKIP_HOLD_ENABLED,
			Setting.SPATIAL_GESTURE_SKIP_HOLD_MILLIS,
			Setting.LONG_PRESS_COMPATIBILITY_MODE,
			Setting.LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			Setting.CANCEL_HALF_CONE_ANGLE_DEGREES,
			Setting.DOUBLE_CLICK_DANGER_WINDOW,
			Setting.DOUBLE_CLICK_DANGER_ANGLE,
			Setting.SPATIAL_DEADZONE,
			Setting.SPATIAL_STROKE,
			Setting.SPATIAL_DWELL_MILLIS,
			Setting.SPATIAL_HOVER_ENABLED,
			Setting.SPATIAL_HOVER_MILLIS,
			Setting.SPATIAL_CONTENT_INTENT_ENABLED,
			Setting.SPATIAL_INVENTORY_SELECTION_STAY_OPEN_ENABLED), SettingsCategoryCatalog.settings(Category.INPUT_INTERACTION));
		assertEquals(List.of(
			Setting.CHANNEL,
			Setting.PING_VOLUME,
			Setting.CONFIGURATION_NOTICE_SIZE), SettingsCategoryCatalog.settings(Category.CHANNEL_NOTICES));
		assertEquals(List.of(
			Setting.ENTITY_BLOCK_RENDER_MODE,
			Setting.OPEN_CLIENT_CONFIG), SettingsCategoryCatalog.settings(Category.GEOMETRY_CONFIG));
		assertEquals(List.of(Setting.PRECISE_CAPTURE_PERIOD_TICKS),
			SettingsCategoryCatalog.settings(Category.CLIENT_PERFORMANCE));
		assertEquals(List.of(
			Setting.DEFAULT_CHANNEL_MODE,
			Setting.PLAYER_TRACKING_ENABLED), SettingsCategoryCatalog.settings(Category.CHANNEL_PLAYERS));
		assertEquals(List.of(
			Setting.MS_TO_REGENERATE,
			Setting.RATE_LIMIT), SettingsCategoryCatalog.settings(Category.SEND_RATE));
		assertEquals(List.of(Setting.SYNC_DURATION), SettingsCategoryCatalog.settings(Category.MARKER_DURATION));
		assertEquals(List.of(Setting.PRESENTATION_SERVER_POLICY),
			SettingsCategoryCatalog.settings(Category.SERVER_PRESENTATION));
		assertEquals(List.of(SettingsCategoryCatalog.PresentationSection.SERVER_EDITOR),
			SettingsCategoryCatalog.presentationSections(Category.SERVER_PRESENTATION));

		var all = EnumSet.noneOf(Setting.class);
		for (Category category : Category.values()) {
			for (Setting setting : SettingsCategoryCatalog.settings(category)) {
				if (!all.add(setting)) {
					throw new AssertionError("setting appears in more than one category: " + setting);
				}
			}
		}
		assertEquals(EnumSet.allOf(Setting.class), all);
	}

	@Test
	void categorySettingListsAreImmutable() {
		assertThrows(UnsupportedOperationException.class,
			() -> SettingsCategoryCatalog.settings(Category.SEND_RATE).add(Setting.SYNC_DURATION));
	}

	@Test
	void performanceExposesEveryInventoryLeafExactlyOnceInServerScopeOnly() {
		var performance = SettingsCategoryCatalog.settings(Category.PERFORMANCE);
		// The shared inventory budgets subgroup is declared before the preview
		// and tracking subgroups, and the display/frame size sliders sit with
		// the other shared budgets rather than at the end of the catalogue.
		assertEquals(List.of(
			Setting.BUDGET_DELAY_NOTIFICATIONS_ENABLED,
			Setting.INVENTORY_PHYSICAL_SLOTS_PER_TICK,
			Setting.INVENTORY_PENDING_MEMORY_MIB,
			Setting.INVENTORY_DISPLAY_BYTE_MULTIPLIER,
			Setting.INVENTORY_FRAME_BYTE_MULTIPLIER,
			Setting.INVENTORY_PREVIEW_PERIOD_TICKS,
			Setting.INVENTORY_PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD,
			Setting.INVENTORY_PREVIEW_MAX_SLOTS_PER_CLIENT,
			Setting.INVENTORY_PREVIEW_MAX_SLOTS_SERVER,
			Setting.INVENTORY_PREVIEW_MAX_TARGETS_PER_CLIENT,
			Setting.INVENTORY_PREVIEW_CLIENT_BYTE_MULTIPLIER,
			Setting.INVENTORY_PREVIEW_GLOBAL_BYTE_MULTIPLIER,
			Setting.INVENTORY_TRACKING_PERIOD_TICKS,
			Setting.INVENTORY_TRACKING_MAX_VARIANTS_PER_TARGET,
			Setting.INVENTORY_TRACKING_MAX_SLOTS_PER_TARGET,
			Setting.INVENTORY_TRACKING_MAX_SLOTS_SERVER,
			Setting.INVENTORY_TRACKING_STREAM_BYTE_MULTIPLIER,
			Setting.INVENTORY_TRACKING_SNAPSHOT_BYTE_MULTIPLIER,
			Setting.INVENTORY_TRACKING_GLOBAL_BYTE_MULTIPLIER,
			Setting.INVENTORY_TRACKING_RESYNC_MIN_PERIODS,
			Setting.INVENTORY_TRACKING_HEARTBEAT_PERIODS,
			Setting.INVENTORY_TRACKING_GRACE_PERIODS),
			performance);
		var covered = EnumSet.noneOf(Field.class);
		for (Setting setting : performance) {
			if (setting.inventoryField() != null) covered.add(setting.inventoryField());
		}
		assertEquals(EnumSet.allOf(Field.class), covered);
		for (Category category : Category.values()) {
			if (category != Category.PERFORMANCE) {
				assertEquals(0, SettingsCategoryCatalog.settings(category).stream()
					.filter(setting -> setting.inventoryField() != null).count());
			}
		}
		assertThrows(UnsupportedOperationException.class, () -> performance.clear());
	}

	@Test
	void invalidInventoryRoutingSkipsMonitoringAndKeepsExistingInventoryOrder() {
		assertNull(Setting.BUDGET_DELAY_NOTIFICATIONS_ENABLED.inventoryField());
		assertNull(SettingsCategoryCatalog.firstInvalidInventorySetting(ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED));
		assertNull(SettingsCategoryCatalog.firstInvalidInventorySetting(0));
		assertEquals(Setting.INVENTORY_DISPLAY_BYTE_MULTIPLIER,
			SettingsCategoryCatalog.firstInvalidInventorySetting(ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED
				| Field.DISPLAY_BYTE_MULTIPLIER.mask() | Field.PREVIEW_PERIOD_TICKS.mask()));
		for (Field field : Field.values()) {
			assertEquals(field, SettingsCategoryCatalog.firstInvalidInventorySetting(field.mask()).inventoryField());
		}
	}
}
