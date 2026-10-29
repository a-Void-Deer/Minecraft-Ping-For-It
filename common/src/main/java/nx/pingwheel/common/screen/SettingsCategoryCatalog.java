package nx.pingwheel.common.screen;

import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Stable, non-localized catalogue of the controls owned by each settings
 * category.  The screen uses this catalogue when it builds leaf pages, while
 * focused tests can verify the product mapping without inspecting source text
 * or instantiating Minecraft widgets.
 */
@NotNullByDefault
public final class SettingsCategoryCatalog {
	@NotNullByDefault
	public enum PresentationSection {
		SERVER_EDITOR
	}

	@NotNullByDefault
	public enum Setting {
		PING_DISTANCE("ping_distance"),
		MARKER_DISPLAY_DURATION("marker_display_duration"),
		PING_SIZE("ping_size"),
		ITEM_ICON_VISIBLE("item_icon_visible"),
		DIRECTION_INDICATOR_VISIBLE("direction_indicator_visible"),
		PLAYER_INFO_MODE("player_info_mode"),
		TEAM_COLOR_MODE("team_color_mode"),
		MARK_TRANSPARENT_BLOCKS("mark_transparent_blocks"),
		MARK_BLACKLISTED_TARGETS("mark_blacklisted_targets"),
		MARK_FLUIDS("mark_fluids"),
		SPATIAL_ROOT_DISTANCE("spatial_selector.root_distance"),
		SPATIAL_SUBMENU_RADIUS_SCALE("spatial_selector.submenu_radius_scale"),
		SPATIAL_INVENTORY_MAX_WIDTH_FRACTION("spatial_selector.inventory_max_width_fraction"),
		SPATIAL_INVENTORY_MAX_HEIGHT_FRACTION("spatial_selector.inventory_max_height_fraction"),
		WHEEL_OPACITY("wheel_opacity"),
		WHEEL_TARGET_OPACITY("wheel_target_opacity"),
		WHEEL_TARGET_FONT_SIZE("wheel_target_font_size"),
		WHEEL_OPTION_FONT_SIZE("wheel_font_size"),
		SPATIAL_SHOW_TRAIL("spatial_selector.show_trail"),
		SPATIAL_TRAIL_LENGTH("spatial_selector.trail_length"),
		SPATIAL_TRAIL_OPACITY("spatial_selector.trail_opacity"),
		SPATIAL_REDUCE_MOTION("spatial_selector.reduce_motion"),
		WHEEL_HOLD_MILLIS("wheel_hold_millis"),
		SPATIAL_GESTURE_SKIP_HOLD_ENABLED("spatial_selector.gesture_skip_hold_enabled"),
		SPATIAL_GESTURE_SKIP_HOLD_MILLIS("spatial_selector.gesture_skip_hold_millis"),
		LONG_PRESS_COMPATIBILITY_MODE("long_press_compatibility_mode"),
		LONG_PRESS_COMPATIBILITY_SLICE_MILLIS("long_press_compatibility_slice_millis"),
		CANCEL_HALF_CONE_ANGLE_DEGREES("cancel_half_cone_angle_degrees"),
		DOUBLE_CLICK_DANGER_WINDOW("double_click_danger_window_millis"),
		DOUBLE_CLICK_DANGER_ANGLE("double_click_danger_angle_degrees"),
		SPATIAL_DEADZONE("spatial_selector.deadzone"),
		SPATIAL_STROKE("spatial_selector.stroke"),
		SPATIAL_DWELL_MILLIS("spatial_selector.dwell_millis"),
		SPATIAL_HOVER_ENABLED("spatial_selector.hover_enabled"),
		SPATIAL_HOVER_MILLIS("spatial_selector.hover_millis"),
		SPATIAL_CONTENT_INTENT_ENABLED("spatial_selector.content_intent_enabled"),
		SPATIAL_INVENTORY_SELECTION_STAY_OPEN_ENABLED("spatial_selector.inventory_selection_stay_open_enabled"),
		PRECISE_CAPTURE_PERIOD_TICKS("spatial_selector.precise_capture_period_ticks"),
		CHANNEL("channel"),
		PING_VOLUME("ping_volume"),
		CONFIGURATION_NOTICE_SIZE("configuration_notice_size"),
		ENTITY_BLOCK_RENDER_MODE("entity_block_render_mode"),
		OPEN_CLIENT_CONFIG("open_client_config"),
		DEFAULT_CHANNEL_MODE("default_channel_mode"),
		PLAYER_TRACKING_ENABLED("player_tracking_enabled"),
		MS_TO_REGENERATE("ms_to_regenerate"),
		RATE_LIMIT("rate_limit"),
		SYNC_DURATION("sync_duration"),
		PRESENTATION_SERVER_POLICY("presentation_server_policy"),
		BUDGET_DELAY_NOTIFICATIONS_ENABLED("budget_delay_notifications_enabled"),
		INVENTORY_PHYSICAL_SLOTS_PER_TICK(Field.PHYSICAL_SLOTS_PER_TICK),
		INVENTORY_PENDING_MEMORY_MIB(Field.PENDING_MEMORY_MIB),
		INVENTORY_DISPLAY_BYTE_MULTIPLIER(Field.DISPLAY_BYTE_MULTIPLIER),
		INVENTORY_FRAME_BYTE_MULTIPLIER(Field.FRAME_BYTE_MULTIPLIER),
		INVENTORY_PREVIEW_PERIOD_TICKS(Field.PREVIEW_PERIOD_TICKS),
		INVENTORY_PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD(Field.PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD),
		INVENTORY_PREVIEW_MAX_SLOTS_PER_CLIENT(Field.PREVIEW_MAX_SLOTS_PER_CLIENT),
		INVENTORY_PREVIEW_MAX_SLOTS_SERVER(Field.PREVIEW_MAX_SLOTS_SERVER),
		INVENTORY_PREVIEW_MAX_TARGETS_PER_CLIENT(Field.PREVIEW_MAX_TARGETS_PER_CLIENT),
		INVENTORY_PREVIEW_CLIENT_BYTE_MULTIPLIER(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER),
		INVENTORY_PREVIEW_GLOBAL_BYTE_MULTIPLIER(Field.PREVIEW_GLOBAL_BYTE_MULTIPLIER),
		INVENTORY_TRACKING_PERIOD_TICKS(Field.TRACKING_PERIOD_TICKS),
		INVENTORY_TRACKING_MAX_VARIANTS_PER_TARGET(Field.TRACKING_MAX_VARIANTS_PER_TARGET),
		INVENTORY_TRACKING_MAX_SLOTS_PER_TARGET(Field.TRACKING_MAX_SLOTS_PER_TARGET),
		INVENTORY_TRACKING_MAX_SLOTS_SERVER(Field.TRACKING_MAX_SLOTS_SERVER),
		INVENTORY_TRACKING_STREAM_BYTE_MULTIPLIER(Field.TRACKING_STREAM_BYTE_MULTIPLIER),
		INVENTORY_TRACKING_SNAPSHOT_BYTE_MULTIPLIER(Field.TRACKING_SNAPSHOT_BYTE_MULTIPLIER),
		INVENTORY_TRACKING_GLOBAL_BYTE_MULTIPLIER(Field.TRACKING_GLOBAL_BYTE_MULTIPLIER),
		INVENTORY_TRACKING_RESYNC_MIN_PERIODS(Field.TRACKING_RESYNC_MIN_PERIODS),
		INVENTORY_TRACKING_HEARTBEAT_PERIODS(Field.TRACKING_HEARTBEAT_PERIODS),
		INVENTORY_TRACKING_GRACE_PERIODS(Field.TRACKING_GRACE_PERIODS),
		INVENTORY_TRACKING_DISTINGUISH_FACES(Field.TRACKING_DISTINGUISH_FACES),
		INVENTORY_TRACKING_DISTINGUISH_PLAYERS(Field.TRACKING_DISTINGUISH_PLAYERS);

		private final String id;
		private final @Nullable Field inventoryField;

		Setting(String id) {
			this.id = id;
			this.inventoryField = null;
		}

		Setting(Field field) {
			this.id = "inventory." + field.name().toLowerCase(java.util.Locale.ROOT);
			this.inventoryField = field;
		}

		public @Nullable Field inventoryField() { return inventoryField; }

		public String id() {
			return id;
		}
	}

	/** One semantic row, never an implicitly packed half-row. */
	@NotNullByDefault
	public record Row(@Unmodifiable List<Setting> settings) {
		public Row {
			settings = List.copyOf(settings);
			if (settings.isEmpty() || settings.size() > 2
				|| settings.size() == 2 && settings.getFirst() == settings.getLast()) {
				throw new IllegalArgumentException("a row requires one or two distinct settings");
			}
		}

		public static Row single(Setting setting) {
			return new Row(List.of(setting));
		}

		public static Row pair(Setting left, Setting right) {
			return new Row(List.of(left, right));
		}
	}

	/** A localized, non-interactive heading followed by ordered semantic rows. */
	@NotNullByDefault
	public record Section(String headingKey, @Unmodifiable List<Row> rows) {
		public Section {
			if (headingKey.isBlank()) throw new IllegalArgumentException("blank section heading");
			rows = List.copyOf(rows);
			if (rows.isEmpty()) throw new IllegalArgumentException("empty settings section");
		}
	}

	private static final @Unmodifiable Map<Category, @Unmodifiable List<Section>> SECTIONS = createSections();

	private SettingsCategoryCatalog() {
	}

	/** Returns the immutable control order for one leaf category. */
	public static @Unmodifiable List<Setting> settings(Category category) {
		return sections(category).stream().flatMap(section -> section.rows().stream())
			.flatMap(row -> row.settings().stream()).toList();
	}

	public static @Unmodifiable List<Section> sections(Category category) {
		return java.util.Objects.requireNonNull(SECTIONS.get(category));
	}

	/** Scope-qualified because both scopes deliberately retain the performance id. */
	public static String categoryTooltipKey(Category category) {
		return "settings.pingforit.category."
			+ category.scope().name().toLowerCase(java.util.Locale.ROOT)
			+ "." + category.id() + ".tooltip";
	}

	/** Finds the first invalid inventory control, skipping non-inventory Performance settings. */
	public static @Nullable Setting firstInvalidInventorySetting(int invalidMask) {
		return settings(Category.PERFORMANCE).stream()
			.filter(setting -> {
				@Nullable Field field = setting.inventoryField();
				return field != null && (invalidMask & field.mask()) != 0;
			})
			.findFirst().orElse(null);
	}

	/** Compatibility view of the independent server editor declared by the catalogue. */
	public static @Unmodifiable List<PresentationSection> presentationSections(Category category) {
		if (category != Category.SERVER_PRESENTATION) throw new IllegalArgumentException("not a presentation category: " + category);
		return settings(category).stream().filter(setting -> setting == Setting.PRESENTATION_SERVER_POLICY)
			.map(setting -> PresentationSection.SERVER_EDITOR).toList();
	}

	private static Section section(String suffix, Row... rows) {
		return new Section("settings.pingforit.group." + suffix, List.of(rows));
	}

	private static @Unmodifiable Map<Category, @Unmodifiable List<Section>> createSections() {
		var sections = new EnumMap<Category, @Unmodifiable List<Section>>(Category.class);
		sections.put(Category.MARKER_DISPLAY, List.of(
			section("marker_range_lifetime", Row.single(Setting.PING_DISTANCE), Row.single(Setting.MARKER_DISPLAY_DURATION)),
			section("marker_display", Row.single(Setting.PING_SIZE), Row.pair(Setting.ITEM_ICON_VISIBLE, Setting.DIRECTION_INDICATOR_VISIBLE)),
			section("player_labels", Row.pair(Setting.PLAYER_INFO_MODE, Setting.TEAM_COLOR_MODE))));
		sections.put(Category.TARGET_SELECTION, List.of(section("target_selection",
			Row.single(Setting.MARK_TRANSPARENT_BLOCKS), Row.single(Setting.MARK_BLACKLISTED_TARGETS), Row.single(Setting.MARK_FLUIDS))));
		sections.put(Category.WHEEL_APPEARANCE, List.of(
			section("wheel_layout", Row.pair(Setting.SPATIAL_ROOT_DISTANCE, Setting.SPATIAL_SUBMENU_RADIUS_SCALE),
				Row.pair(Setting.SPATIAL_INVENTORY_MAX_WIDTH_FRACTION, Setting.SPATIAL_INVENTORY_MAX_HEIGHT_FRACTION)),
			section("wheel_readability", Row.pair(Setting.WHEEL_OPACITY, Setting.WHEEL_TARGET_OPACITY),
				Row.pair(Setting.WHEEL_OPTION_FONT_SIZE, Setting.WHEEL_TARGET_FONT_SIZE)),
			section("gesture_trail", Row.single(Setting.SPATIAL_SHOW_TRAIL), Row.pair(Setting.SPATIAL_TRAIL_LENGTH, Setting.SPATIAL_TRAIL_OPACITY)),
			section("selector_motion", Row.single(Setting.SPATIAL_REDUCE_MOTION))));
		sections.put(Category.INPUT_INTERACTION, List.of(
			section("input_timing", Row.single(Setting.WHEEL_HOLD_MILLIS),
				Row.pair(Setting.SPATIAL_GESTURE_SKIP_HOLD_ENABLED, Setting.SPATIAL_GESTURE_SKIP_HOLD_MILLIS)),
			section("input_compatibility", Row.single(Setting.LONG_PRESS_COMPATIBILITY_MODE), Row.single(Setting.LONG_PRESS_COMPATIBILITY_SLICE_MILLIS)),
			section("double_click_danger", Row.single(Setting.DOUBLE_CLICK_DANGER_WINDOW), Row.single(Setting.DOUBLE_CLICK_DANGER_ANGLE)),
			section("marker_cancellation", Row.single(Setting.CANCEL_HALF_CONE_ANGLE_DEGREES)),
			section("spatial_selector", Row.pair(Setting.SPATIAL_DEADZONE, Setting.SPATIAL_STROKE), Row.single(Setting.SPATIAL_DWELL_MILLIS),
				Row.pair(Setting.SPATIAL_HOVER_ENABLED, Setting.SPATIAL_HOVER_MILLIS)),
			section("content_selection", Row.single(Setting.SPATIAL_CONTENT_INTENT_ENABLED), Row.single(Setting.SPATIAL_INVENTORY_SELECTION_STAY_OPEN_ENABLED))));
		sections.put(Category.CHANNEL_NOTICES, List.of(section("channel", Row.single(Setting.CHANNEL)),
			section("sound_notices", Row.single(Setting.PING_VOLUME), Row.single(Setting.CONFIGURATION_NOTICE_SIZE))));
		sections.put(Category.GEOMETRY_CONFIG, List.of(section("geometry", Row.single(Setting.ENTITY_BLOCK_RENDER_MODE)),
			section("client_config_file", Row.single(Setting.OPEN_CLIENT_CONFIG))));
		sections.put(Category.CLIENT_PERFORMANCE, List.of(section("wheel_options", Row.single(Setting.PRECISE_CAPTURE_PERIOD_TICKS))));
		sections.put(Category.CHANNEL_PLAYERS, List.of(section("channel_players", Row.single(Setting.DEFAULT_CHANNEL_MODE), Row.single(Setting.PLAYER_TRACKING_ENABLED))));
		sections.put(Category.SEND_RATE, List.of(section("send_rate", Row.single(Setting.MS_TO_REGENERATE), Row.single(Setting.RATE_LIMIT))));
		sections.put(Category.MARKER_DURATION, List.of(section("marker_duration", Row.single(Setting.SYNC_DURATION))));
		sections.put(Category.PERFORMANCE, List.of(
			section("monitoring", Row.single(Setting.BUDGET_DELAY_NOTIFICATIONS_ENABLED)),
			section("inventory_shared", Row.single(Setting.INVENTORY_PHYSICAL_SLOTS_PER_TICK), Row.single(Setting.INVENTORY_PENDING_MEMORY_MIB),
				Row.single(Setting.INVENTORY_DISPLAY_BYTE_MULTIPLIER), Row.single(Setting.INVENTORY_FRAME_BYTE_MULTIPLIER)),
			section("inventory_preview", Row.single(Setting.INVENTORY_PREVIEW_PERIOD_TICKS), Row.single(Setting.INVENTORY_PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD),
				Row.single(Setting.INVENTORY_PREVIEW_MAX_SLOTS_PER_CLIENT), Row.single(Setting.INVENTORY_PREVIEW_MAX_SLOTS_SERVER),
				Row.single(Setting.INVENTORY_PREVIEW_MAX_TARGETS_PER_CLIENT), Row.single(Setting.INVENTORY_PREVIEW_CLIENT_BYTE_MULTIPLIER), Row.single(Setting.INVENTORY_PREVIEW_GLOBAL_BYTE_MULTIPLIER)),
			section("inventory_tracking", Row.single(Setting.INVENTORY_TRACKING_PERIOD_TICKS),
				Row.single(Setting.INVENTORY_TRACKING_DISTINGUISH_FACES), Row.single(Setting.INVENTORY_TRACKING_DISTINGUISH_PLAYERS),
				Row.single(Setting.INVENTORY_TRACKING_MAX_VARIANTS_PER_TARGET),
				Row.single(Setting.INVENTORY_TRACKING_MAX_SLOTS_PER_TARGET), Row.single(Setting.INVENTORY_TRACKING_MAX_SLOTS_SERVER),
				Row.single(Setting.INVENTORY_TRACKING_STREAM_BYTE_MULTIPLIER), Row.single(Setting.INVENTORY_TRACKING_SNAPSHOT_BYTE_MULTIPLIER), Row.single(Setting.INVENTORY_TRACKING_GLOBAL_BYTE_MULTIPLIER),
				Row.single(Setting.INVENTORY_TRACKING_RESYNC_MIN_PERIODS), Row.single(Setting.INVENTORY_TRACKING_HEARTBEAT_PERIODS), Row.single(Setting.INVENTORY_TRACKING_GRACE_PERIODS))));
		sections.put(Category.SERVER_PRESENTATION, List.of(new Section("settings.pingforit.presentation.server.title",
			List.of(Row.single(Setting.PRESENTATION_SERVER_POLICY)))));
		validate(sections);
		return Map.copyOf(sections);
	}

	private static void validate(Map<Category, @Unmodifiable List<Section>> sections) {
		if (!sections.keySet().equals(EnumSet.allOf(Category.class))) throw new IllegalStateException("incomplete settings categories");
		var placed = EnumSet.noneOf(Setting.class);
		for (var categorySections : sections.values()) {
			if (categorySections.isEmpty()) throw new IllegalStateException("empty settings category");
			for (Section section : categorySections) {
				for (Row row : section.rows()) {
					for (Setting setting : row.settings()) {
						if (!placed.add(setting)) throw new IllegalStateException("duplicate setting: " + setting);
					}
				}
			}
		}
		if (!placed.equals(EnumSet.allOf(Setting.class))) throw new IllegalStateException("incomplete settings placement");
	}
}
