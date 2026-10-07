package nx.pingwheel.common.screen;

import nx.pingwheel.common.screen.SettingsCategoryCatalog.Setting;
import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
			Setting.PASS_THROUGH_TRANSPARENT_BLOCKS,
			Setting.MARK_BLACKLISTED_TARGETS,
			Setting.MARK_FLUIDS), SettingsCategoryCatalog.settings(Category.TARGET_SELECTION));
		assertEquals(List.of(
			Setting.SPATIAL_ROOT_DISTANCE,
			Setting.WHEEL_OPACITY,
			Setting.WHEEL_TARGET_OPACITY,
			Setting.WHEEL_TARGET_FONT_SIZE,
			Setting.WHEEL_OPTION_FONT_SIZE,
			Setting.SPATIAL_SHOW_TRAIL,
			Setting.SPATIAL_REDUCE_MOTION), SettingsCategoryCatalog.settings(Category.WHEEL_APPEARANCE));
		assertEquals(List.of(
			Setting.WHEEL_HOLD_MILLIS,
			Setting.LONG_PRESS_COMPATIBILITY_MODE,
			Setting.LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			Setting.CANCEL_HALF_CONE_ANGLE_DEGREES,
			Setting.SPATIAL_DEADZONE,
			Setting.SPATIAL_STROKE,
			Setting.SPATIAL_DWELL_MILLIS,
			Setting.SPATIAL_TARGET_GLIDE,
			Setting.SPATIAL_HOVER_ENABLED,
			Setting.SPATIAL_HOVER_MILLIS), SettingsCategoryCatalog.settings(Category.INPUT_INTERACTION));
		assertEquals(List.of(
			Setting.CHANNEL,
			Setting.PING_VOLUME,
			Setting.CONFIGURATION_NOTICE_SIZE), SettingsCategoryCatalog.settings(Category.CHANNEL_NOTICES));
		assertEquals(List.of(
			Setting.ENTITY_BLOCK_RENDER_MODE,
			Setting.OPEN_CLIENT_CONFIG), SettingsCategoryCatalog.settings(Category.GEOMETRY_CONFIG));
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
		assertEquals(java.util.Arrays.asList(nx.pingwheel.common.config.InventoryConfigValues.Field.values()),
			performance.stream().map(Setting::inventoryField).toList());
		for (Category category : Category.values()) {
			if (category != Category.PERFORMANCE) {
				assertEquals(0, SettingsCategoryCatalog.settings(category).stream()
					.filter(setting -> setting.inventoryField() != null).count());
			}
		}
		assertThrows(UnsupportedOperationException.class, () -> performance.clear());
	}
}
