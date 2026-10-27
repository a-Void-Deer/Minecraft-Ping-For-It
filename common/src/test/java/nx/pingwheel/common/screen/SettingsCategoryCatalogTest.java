package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.screen.SettingsCategoryCatalog.Row;
import nx.pingwheel.common.screen.SettingsCategoryCatalog.Section;
import nx.pingwheel.common.screen.SettingsCategoryCatalog.Setting;
import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class SettingsCategoryCatalogTest {
	private static final String GROUP_PREFIX = "settings.pingforit.group.";
	private static final String MARKER_RANGE_LIFETIME_HEADING = GROUP_PREFIX + "marker_range_lifetime";
	private static final String MARKER_DISPLAY_HEADING = GROUP_PREFIX + "marker_display";
	private static final String PLAYER_LABELS_HEADING = GROUP_PREFIX + "player_labels";
	private static final String TARGET_SELECTION_HEADING = GROUP_PREFIX + "target_selection";
	private static final String WHEEL_LAYOUT_HEADING = GROUP_PREFIX + "wheel_layout";
	private static final String WHEEL_READABILITY_HEADING = GROUP_PREFIX + "wheel_readability";
	private static final String GESTURE_TRAIL_HEADING = GROUP_PREFIX + "gesture_trail";
	private static final String SELECTOR_MOTION_HEADING = GROUP_PREFIX + "selector_motion";
	private static final String INPUT_TIMING_HEADING = GROUP_PREFIX + "input_timing";
	private static final String INPUT_COMPATIBILITY_HEADING = GROUP_PREFIX + "input_compatibility";
	private static final String DOUBLE_CLICK_DANGER_HEADING = GROUP_PREFIX + "double_click_danger";
	private static final String MARKER_CANCELLATION_HEADING = GROUP_PREFIX + "marker_cancellation";
	private static final String SPATIAL_SELECTOR_HEADING = GROUP_PREFIX + "spatial_selector";
	private static final String CONTENT_SELECTION_HEADING = GROUP_PREFIX + "content_selection";
	private static final String CHANNEL_HEADING = GROUP_PREFIX + "channel";
	private static final String SOUND_NOTICES_HEADING = GROUP_PREFIX + "sound_notices";
	private static final String GEOMETRY_HEADING = GROUP_PREFIX + "geometry";
	private static final String CLIENT_CONFIG_FILE_HEADING = GROUP_PREFIX + "client_config_file";
	private static final String WHEEL_OPTIONS_HEADING = GROUP_PREFIX + "wheel_options";
	private static final String CHANNEL_PLAYERS_HEADING = GROUP_PREFIX + "channel_players";
	private static final String SEND_RATE_HEADING = GROUP_PREFIX + "send_rate";
	private static final String MARKER_DURATION_HEADING = GROUP_PREFIX + "marker_duration";
	private static final String MONITORING_HEADING = GROUP_PREFIX + "monitoring";
	private static final String INVENTORY_SHARED_HEADING = GROUP_PREFIX + "inventory_shared";
	private static final String INVENTORY_PREVIEW_HEADING = GROUP_PREFIX + "inventory_preview";
	private static final String INVENTORY_TRACKING_HEADING = GROUP_PREFIX + "inventory_tracking";
	private static final String PRESENTATION_HEADING = "settings.pingforit.presentation.server.title";

	/**
	 * Product-approved section order and row composition. The expected data is
	 * deliberately independent from the catalog implementation so a moved
	 * control or a plausible wrong pairing fails at the semantic boundary.
	 */
	private static final @Unmodifiable Map<Category, List<ExpectedSection>> EXPECTED_SECTIONS = expectedSections();

	private static final @Unmodifiable Set<Set<Setting>> EXPECTED_PAIRS = Set.of(
		Set.of(Setting.ITEM_ICON_VISIBLE, Setting.DIRECTION_INDICATOR_VISIBLE),
		Set.of(Setting.PLAYER_INFO_MODE, Setting.TEAM_COLOR_MODE),
		Set.of(Setting.SPATIAL_ROOT_DISTANCE, Setting.SPATIAL_SUBMENU_RADIUS_SCALE),
		Set.of(Setting.SPATIAL_INVENTORY_MAX_WIDTH_FRACTION, Setting.SPATIAL_INVENTORY_MAX_HEIGHT_FRACTION),
		Set.of(Setting.WHEEL_OPACITY, Setting.WHEEL_TARGET_OPACITY),
		Set.of(Setting.WHEEL_OPTION_FONT_SIZE, Setting.WHEEL_TARGET_FONT_SIZE),
		Set.of(Setting.SPATIAL_TRAIL_LENGTH, Setting.SPATIAL_TRAIL_OPACITY),
		Set.of(Setting.SPATIAL_GESTURE_SKIP_HOLD_ENABLED, Setting.SPATIAL_GESTURE_SKIP_HOLD_MILLIS),
		Set.of(Setting.SPATIAL_DEADZONE, Setting.SPATIAL_STROKE),
		Set.of(Setting.SPATIAL_HOVER_ENABLED, Setting.SPATIAL_HOVER_MILLIS));

	private static final @Unmodifiable List<Set<Setting>> FORBIDDEN_PAIRS = List.of(
		Set.of(Setting.SPATIAL_TRAIL_OPACITY, Setting.SPATIAL_REDUCE_MOTION),
		Set.of(Setting.SPATIAL_DWELL_MILLIS, Setting.SPATIAL_CONTENT_INTENT_ENABLED),
		Set.of(Setting.SPATIAL_HOVER_ENABLED, Setting.SPATIAL_CONTENT_INTENT_ENABLED),
		Set.of(Setting.SPATIAL_HOVER_MILLIS, Setting.SPATIAL_CONTENT_INTENT_ENABLED),
		Set.of(Setting.SPATIAL_DWELL_MILLIS, Setting.SPATIAL_HOVER_MILLIS));

	private static final @Unmodifiable List<String> PERFORMANCE_HEADINGS = List.of(
		MONITORING_HEADING,
		INVENTORY_SHARED_HEADING,
		INVENTORY_PREVIEW_HEADING,
		INVENTORY_TRACKING_HEADING);

	@Test
	void everySettingAppearsExactlyOnceInItsApprovedCategory() {
		assertEquals(71, Setting.values().length, "the approved catalog has 71 entries");
		assertEquals(EnumSet.allOf(Category.class), EnumSet.copyOf(EXPECTED_SECTIONS.keySet()),
			"the approved section map must cover every settings category");

		var all = EnumSet.noneOf(Setting.class);
		for (Category category : Category.values()) {
			var expected = EnumSet.noneOf(Setting.class);
			for (ExpectedSection section : EXPECTED_SECTIONS.get(category)) {
				for (ExpectedRow row : section.rows()) {
					expected.addAll(row.settings());
				}
			}

			var actual = SettingsCategoryCatalog.settings(category);
			assertEquals(expected, EnumSet.copyOf(actual), () -> category + " membership changed");
			for (Setting setting : actual) {
				assertTrue(all.add(setting), () -> setting + " appears in more than one category");
			}
		}

		assertEquals(EnumSet.allOf(Setting.class), all);
		assertEquals(69L, all.stream()
			.filter(setting -> setting != Setting.OPEN_CLIENT_CONFIG && setting != Setting.PRESENTATION_SERVER_POLICY)
			.count(), "only the file action and presentation editor are outside the 69 value controls");
	}

	@Test
	void sectionsMatchTheApprovedHeadingsAndSemanticRows() {
		for (Category category : Category.values()) {
			var expected = EXPECTED_SECTIONS.get(category);
			var actual = SettingsCategoryCatalog.sections(category);

			assertEquals(expected.size(), actual.size(), () -> category + " section count changed");
			for (int index = 0; index < expected.size(); index++) {
				final int sectionIndex = index;
				var expectedSection = expected.get(index);
				var actualSection = actual.get(index);

				assertEquals(expectedSection.headingKey(), actualSection.headingKey(),
					() -> category + " section " + sectionIndex + " heading changed");
				assertEquals(
					expectedSection.rows().stream().map(ExpectedRow::settings).toList(),
					actualSection.rows().stream().map(Row::settings).toList(),
					() -> category + " section " + sectionIndex + " rows changed");
			}
		}
	}

	@Test
	void settingsAreTheFlattenedSectionRowsInExactOrder() {
		for (Category category : Category.values()) {
			var expected = new ArrayList<Setting>();
			for (ExpectedSection section : EXPECTED_SECTIONS.get(category)) {
				for (ExpectedRow row : section.rows()) {
					expected.addAll(row.settings());
				}
			}

			assertEquals(List.copyOf(expected), SettingsCategoryCatalog.settings(category),
				() -> category + " flattened setting order changed");
			assertEquals(
				SettingsCategoryCatalog.sections(category).stream()
					.flatMap(section -> section.rows().stream())
					.flatMap(row -> row.settings().stream())
					.toList(),
				SettingsCategoryCatalog.settings(category),
				() -> category + " settings() must be derived from sections()");
		}
	}

	@Test
	void everySectionHasANonBlankHeadingAndAtLeastOneRow() {
		for (Category category : Category.values()) {
			var sections = SettingsCategoryCatalog.sections(category);
			assertFalse(sections.isEmpty(), () -> category + " must have at least one section");

			var headings = new HashSet<String>();
			for (Section section : sections) {
				assertFalse(section.headingKey().isBlank(), () -> category + " has a blank section heading");
				assertFalse(section.rows().isEmpty(),
					() -> category + " section " + section.headingKey() + " has no rows");
				assertTrue(headings.add(section.headingKey()),
					() -> category + " repeats the section heading " + section.headingKey());
			}
		}
	}

	@Test
	void pairedRowsGroupOnlyTheConfirmedRelatedControls() {
		var actualPairs = new HashSet<Set<Setting>>();
		for (Category category : Category.values()) {
			for (Section section : SettingsCategoryCatalog.sections(category)) {
				for (Row row : section.rows()) {
					if (row.settings().size() == 2) {
						assertTrue(actualPairs.add(Set.copyOf(row.settings())),
							() -> "the catalog repeats the pair " + row.settings());
					}
				}
			}
		}

		assertEquals(EXPECTED_PAIRS, actualPairs);
		for (Set<Setting> forbidden : FORBIDDEN_PAIRS) {
			assertFalse(actualPairs.contains(forbidden),
				() -> "unrelated settings must not share a row: " + forbidden);
		}
	}

	@Test
	void serverPerformanceKeepsEveryControlOnItsOwnRow() {
		var sections = SettingsCategoryCatalog.sections(Category.PERFORMANCE);
		assertEquals(PERFORMANCE_HEADINGS, sections.stream().map(Section::headingKey).toList());

		var rows = sections.stream().flatMap(section -> section.rows().stream()).toList();
		assertEquals(22, rows.size(), "Performance contains the monitoring toggle and 21 inventory leaves");
		assertTrue(rows.stream().allMatch(row -> row.settings().size() == 1),
			"server Performance controls must not be paired into shared rows");
		assertEquals(
			SettingsCategoryCatalog.settings(Category.PERFORMANCE),
			rows.stream().flatMap(row -> row.settings().stream()).toList());
	}

	@Test
	void catalogCollectionsAreImmutable() {
		var settings = SettingsCategoryCatalog.settings(Category.SEND_RATE);
		assertThrows(UnsupportedOperationException.class,
			() -> settings.add(Setting.SYNC_DURATION));

		var sections = SettingsCategoryCatalog.sections(Category.SEND_RATE);
		assertThrows(UnsupportedOperationException.class, sections::clear);

		var rows = sections.getFirst().rows();
		assertThrows(UnsupportedOperationException.class, rows::clear);

		var rowSettings = rows.getFirst().settings();
		assertThrows(UnsupportedOperationException.class, rowSettings::clear);
	}

	@Test
	void sectionAndRowConstructionDefensivelyCopiesMutableInputs() {
		var mutableRows = new ArrayList<Row>();
		mutableRows.add(Row.single(Setting.PING_DISTANCE));
		var section = new Section("heading", mutableRows);
		mutableRows.add(Row.single(Setting.MARKER_DISPLAY_DURATION));

		assertEquals(List.of(List.of(Setting.PING_DISTANCE)),
			section.rows().stream().map(Row::settings).toList(),
			"later edits to the caller's row list must not change the section");
		assertThrows(UnsupportedOperationException.class,
			() -> section.rows().add(Row.single(Setting.PING_SIZE)));

		var mutableSettings = new ArrayList<Setting>();
		mutableSettings.add(Setting.PING_DISTANCE);
		var row = new Row(mutableSettings);
		mutableSettings.add(Setting.MARKER_DISPLAY_DURATION);

		assertEquals(List.of(Setting.PING_DISTANCE), row.settings(),
			"later edits to the caller's setting list must not change the row");
		assertThrows(UnsupportedOperationException.class, row.settings()::clear);
	}

	@Test
	void invalidSectionAndRowDescriptorsAreRejected() {
		var single = Row.single(Setting.PING_DISTANCE);

		assertThrows(IllegalArgumentException.class, () -> new Section("", List.of(single)));
		assertThrows(IllegalArgumentException.class, () -> new Section("   ", List.of(single)));
		assertThrows(IllegalArgumentException.class, () -> new Section("heading", List.of()));

		assertThrows(IllegalArgumentException.class, () -> new Row(List.of()));
		assertThrows(IllegalArgumentException.class, () -> new Row(List.of(
			Setting.PING_DISTANCE,
			Setting.MARKER_DISPLAY_DURATION,
			Setting.PING_SIZE)));
		assertThrows(IllegalArgumentException.class,
			() -> Row.pair(Setting.PING_DISTANCE, Setting.PING_DISTANCE));
		assertThrows(IllegalArgumentException.class,
			() -> new Row(List.of(Setting.PING_DISTANCE, Setting.PING_DISTANCE)));
	}

	@Test
	void categoryTooltipKeysAreDistinctScopedTranslationKeys() {
		var keys = new ArrayList<String>();
		for (Category category : Category.values()) {
			String key = SettingsCategoryCatalog.categoryTooltipKey(category);

			assertNotNull(key, () -> category + " has no tooltip key");
			assertFalse(key.isBlank(), () -> category + " has a blank tooltip key");
			assertTrue(key.startsWith("settings.pingforit."),
				() -> category + " tooltip key is not a settings translation key: " + key);
			assertFalse(key.chars().anyMatch(Character::isWhitespace),
				() -> category + " tooltip key must not be translated text: " + key);
			keys.add(key);
		}

		assertEquals(Category.values().length, Set.copyOf(keys).size(),
			"every category needs its own tooltip key");

		String clientPerformanceKey = SettingsCategoryCatalog.categoryTooltipKey(Category.CLIENT_PERFORMANCE);
		String serverPerformanceKey = SettingsCategoryCatalog.categoryTooltipKey(Category.PERFORMANCE);
		assertNotEquals(clientPerformanceKey, serverPerformanceKey,
			"the two Performance categories must not share one tooltip key");
		assertTrue(clientPerformanceKey.contains("client"),
			() -> "the client Performance tooltip key must name its scope: " + clientPerformanceKey);
		assertTrue(serverPerformanceKey.contains("server"),
			() -> "the server Performance tooltip key must name its scope: " + serverPerformanceKey);
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

	private static @Unmodifiable Map<Category, List<ExpectedSection>> expectedSections() {
		var expected = new EnumMap<Category, List<ExpectedSection>>(Category.class);
		expected.put(Category.MARKER_DISPLAY, List.of(
			section(MARKER_RANGE_LIFETIME_HEADING,
				row(Setting.PING_DISTANCE),
				row(Setting.MARKER_DISPLAY_DURATION)),
			section(MARKER_DISPLAY_HEADING,
				row(Setting.PING_SIZE),
				row(Setting.ITEM_ICON_VISIBLE, Setting.DIRECTION_INDICATOR_VISIBLE)),
			section(PLAYER_LABELS_HEADING,
				row(Setting.PLAYER_INFO_MODE, Setting.TEAM_COLOR_MODE))));
		expected.put(Category.TARGET_SELECTION, List.of(
			section(TARGET_SELECTION_HEADING,
				row(Setting.MARK_TRANSPARENT_BLOCKS),
				row(Setting.MARK_BLACKLISTED_TARGETS),
				row(Setting.MARK_FLUIDS))));
		expected.put(Category.WHEEL_APPEARANCE, List.of(
			section(WHEEL_LAYOUT_HEADING,
				row(Setting.SPATIAL_ROOT_DISTANCE, Setting.SPATIAL_SUBMENU_RADIUS_SCALE),
				row(Setting.SPATIAL_INVENTORY_MAX_WIDTH_FRACTION, Setting.SPATIAL_INVENTORY_MAX_HEIGHT_FRACTION)),
			section(WHEEL_READABILITY_HEADING,
				row(Setting.WHEEL_OPACITY, Setting.WHEEL_TARGET_OPACITY),
				row(Setting.WHEEL_OPTION_FONT_SIZE, Setting.WHEEL_TARGET_FONT_SIZE)),
			section(GESTURE_TRAIL_HEADING,
				row(Setting.SPATIAL_SHOW_TRAIL),
				row(Setting.SPATIAL_TRAIL_LENGTH, Setting.SPATIAL_TRAIL_OPACITY)),
			section(SELECTOR_MOTION_HEADING,
				row(Setting.SPATIAL_REDUCE_MOTION))));
		expected.put(Category.INPUT_INTERACTION, List.of(
			section(INPUT_TIMING_HEADING,
				row(Setting.WHEEL_HOLD_MILLIS),
				row(Setting.SPATIAL_GESTURE_SKIP_HOLD_ENABLED, Setting.SPATIAL_GESTURE_SKIP_HOLD_MILLIS)),
			section(INPUT_COMPATIBILITY_HEADING,
				row(Setting.LONG_PRESS_COMPATIBILITY_MODE),
				row(Setting.LONG_PRESS_COMPATIBILITY_SLICE_MILLIS)),
			section(DOUBLE_CLICK_DANGER_HEADING,
				row(Setting.DOUBLE_CLICK_DANGER_WINDOW),
				row(Setting.DOUBLE_CLICK_DANGER_ANGLE)),
			section(MARKER_CANCELLATION_HEADING,
				row(Setting.CANCEL_HALF_CONE_ANGLE_DEGREES)),
			section(SPATIAL_SELECTOR_HEADING,
				row(Setting.SPATIAL_DEADZONE, Setting.SPATIAL_STROKE),
				row(Setting.SPATIAL_DWELL_MILLIS),
				row(Setting.SPATIAL_HOVER_ENABLED, Setting.SPATIAL_HOVER_MILLIS)),
			section(CONTENT_SELECTION_HEADING,
				row(Setting.SPATIAL_CONTENT_INTENT_ENABLED),
				row(Setting.SPATIAL_INVENTORY_SELECTION_STAY_OPEN_ENABLED))));
		expected.put(Category.CHANNEL_NOTICES, List.of(
			section(CHANNEL_HEADING,
				row(Setting.CHANNEL)),
			section(SOUND_NOTICES_HEADING,
				row(Setting.PING_VOLUME),
				row(Setting.CONFIGURATION_NOTICE_SIZE))));
		expected.put(Category.GEOMETRY_CONFIG, List.of(
			section(GEOMETRY_HEADING,
				row(Setting.ENTITY_BLOCK_RENDER_MODE)),
			section(CLIENT_CONFIG_FILE_HEADING,
				row(Setting.OPEN_CLIENT_CONFIG))));
		expected.put(Category.CLIENT_PERFORMANCE, List.of(
			section(WHEEL_OPTIONS_HEADING,
				row(Setting.PRECISE_CAPTURE_PERIOD_TICKS))));
		expected.put(Category.CHANNEL_PLAYERS, List.of(
			section(CHANNEL_PLAYERS_HEADING,
				row(Setting.DEFAULT_CHANNEL_MODE),
				row(Setting.PLAYER_TRACKING_ENABLED))));
		expected.put(Category.SEND_RATE, List.of(
			section(SEND_RATE_HEADING,
				row(Setting.MS_TO_REGENERATE),
				row(Setting.RATE_LIMIT))));
		expected.put(Category.MARKER_DURATION, List.of(
			section(MARKER_DURATION_HEADING,
				row(Setting.SYNC_DURATION))));
		expected.put(Category.PERFORMANCE, List.of(
			section(MONITORING_HEADING,
				row(Setting.BUDGET_DELAY_NOTIFICATIONS_ENABLED)),
			section(INVENTORY_SHARED_HEADING,
				row(Setting.INVENTORY_PHYSICAL_SLOTS_PER_TICK),
				row(Setting.INVENTORY_PENDING_MEMORY_MIB),
				row(Setting.INVENTORY_DISPLAY_BYTE_MULTIPLIER),
				row(Setting.INVENTORY_FRAME_BYTE_MULTIPLIER)),
			section(INVENTORY_PREVIEW_HEADING,
				row(Setting.INVENTORY_PREVIEW_PERIOD_TICKS),
				row(Setting.INVENTORY_PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD),
				row(Setting.INVENTORY_PREVIEW_MAX_SLOTS_PER_CLIENT),
				row(Setting.INVENTORY_PREVIEW_MAX_SLOTS_SERVER),
				row(Setting.INVENTORY_PREVIEW_MAX_TARGETS_PER_CLIENT),
				row(Setting.INVENTORY_PREVIEW_CLIENT_BYTE_MULTIPLIER),
				row(Setting.INVENTORY_PREVIEW_GLOBAL_BYTE_MULTIPLIER)),
			section(INVENTORY_TRACKING_HEADING,
				row(Setting.INVENTORY_TRACKING_PERIOD_TICKS),
				row(Setting.INVENTORY_TRACKING_MAX_VARIANTS_PER_TARGET),
				row(Setting.INVENTORY_TRACKING_MAX_SLOTS_PER_TARGET),
				row(Setting.INVENTORY_TRACKING_MAX_SLOTS_SERVER),
				row(Setting.INVENTORY_TRACKING_STREAM_BYTE_MULTIPLIER),
				row(Setting.INVENTORY_TRACKING_SNAPSHOT_BYTE_MULTIPLIER),
				row(Setting.INVENTORY_TRACKING_GLOBAL_BYTE_MULTIPLIER),
				row(Setting.INVENTORY_TRACKING_RESYNC_MIN_PERIODS),
				row(Setting.INVENTORY_TRACKING_HEARTBEAT_PERIODS),
				row(Setting.INVENTORY_TRACKING_GRACE_PERIODS))));
		expected.put(Category.SERVER_PRESENTATION, List.of(
			section(PRESENTATION_HEADING,
				row(Setting.PRESENTATION_SERVER_POLICY))));
		return Map.copyOf(expected);
	}

	private static ExpectedSection section(String headingKey, ExpectedRow... rows) {
		return new ExpectedSection(headingKey, List.of(rows));
	}

	private static ExpectedRow row(Setting... settings) {
		return new ExpectedRow(List.of(settings));
	}

	@NotNullByDefault
	private record ExpectedSection(String headingKey, @Unmodifiable List<ExpectedRow> rows) {
	}

	@NotNullByDefault
	private record ExpectedRow(@Unmodifiable List<Setting> settings) {
	}
}
