package nx.pingwheel.common.screen;

import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import nx.pingwheel.common.config.InventoryConfigValues.Field;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Stable, non-localized catalogue of the controls owned by each settings
 * category.  The screen uses this catalogue when it builds leaf pages, while
 * focused tests can verify the product mapping without inspecting source text
 * or instantiating Minecraft widgets.
 */
public final class SettingsCategoryCatalog {
	public enum PresentationSection {
		SERVER_EDITOR
	}

	public enum Setting {
		PING_DISTANCE("ping_distance"),
		MARKER_DISPLAY_DURATION("marker_display_duration"),
		PING_SIZE("ping_size"),
		ITEM_ICON_VISIBLE("item_icon_visible"),
		DIRECTION_INDICATOR_VISIBLE("direction_indicator_visible"),
		PLAYER_INFO_MODE("player_info_mode"),
		TEAM_COLOR_MODE("team_color_mode"),
		PASS_THROUGH_TRANSPARENT_BLOCKS("pass_through_transparent_blocks"),
		MARK_BLACKLISTED_TARGETS("mark_blacklisted_targets"),
		MARK_FLUIDS("mark_fluids"),
		SPATIAL_ROOT_DISTANCE("spatial_selector.root_distance"),
		SPATIAL_SUBMENU_RADIUS_SCALE("spatial_selector.submenu_radius_scale"),
		WHEEL_OPACITY("wheel_opacity"),
		WHEEL_TARGET_OPACITY("wheel_target_opacity"),
		WHEEL_TARGET_FONT_SIZE("wheel_target_font_size"),
		WHEEL_OPTION_FONT_SIZE("wheel_font_size"),
		SPATIAL_SHOW_TRAIL("spatial_selector.show_trail"),
		SPATIAL_REDUCE_MOTION("spatial_selector.reduce_motion"),
		WHEEL_HOLD_MILLIS("wheel_hold_millis"),
		LONG_PRESS_COMPATIBILITY_MODE("long_press_compatibility_mode"),
		LONG_PRESS_COMPATIBILITY_SLICE_MILLIS("long_press_compatibility_slice_millis"),
		CANCEL_HALF_CONE_ANGLE_DEGREES("cancel_half_cone_angle_degrees"),
		SPATIAL_DEADZONE("spatial_selector.deadzone"),
		SPATIAL_STROKE("spatial_selector.stroke"),
		SPATIAL_DWELL_MILLIS("spatial_selector.dwell_millis"),
		SPATIAL_TARGET_GLIDE("spatial_selector.target_glide"),
		SPATIAL_HOVER_ENABLED("spatial_selector.hover_enabled"),
		SPATIAL_HOVER_MILLIS("spatial_selector.hover_millis"),
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
		INVENTORY_PHYSICAL_SLOTS_PER_TICK(Field.PHYSICAL_SLOTS_PER_TICK),
		INVENTORY_PENDING_MEMORY_MIB(Field.PENDING_MEMORY_MIB),
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
		INVENTORY_TRACKING_GRACE_PERIODS(Field.TRACKING_GRACE_PERIODS);

		private final String id;
		private final Field inventoryField;

		Setting(String id) {
			this.id = id;
			this.inventoryField = null;
		}

		Setting(Field field) {
			this.id = "inventory." + field.name().toLowerCase(java.util.Locale.ROOT);
			this.inventoryField = field;
		}

		public Field inventoryField() { return inventoryField; }

		public String id() {
			return id;
		}
	}

	private static final Map<Category, List<Setting>> SETTINGS = createSettings();

	private SettingsCategoryCatalog() {
	}

	/** Returns the immutable control order for one leaf category. */
	public static List<Setting> settings(Category category) {
		return SETTINGS.get(category);
	}

	/**
	 * Returns the presentation panels rendered for the category. The editable
	 * server policy remains owned by its server setting; the client page appends
	 * exactly one shared read-only reference after both local editors.
	 */
	public static List<PresentationSection> presentationSections(Category category) {
		if (category != Category.SERVER_PRESENTATION) throw new IllegalArgumentException("not a presentation category: " + category);
		return List.of(PresentationSection.SERVER_EDITOR);
	}

	private static Map<Category, List<Setting>> createSettings() {
		var settings = new EnumMap<Category, List<Setting>>(Category.class);
		settings.put(Category.MARKER_DISPLAY, List.of(
			Setting.PING_DISTANCE,
			Setting.MARKER_DISPLAY_DURATION,
			Setting.PING_SIZE,
			Setting.ITEM_ICON_VISIBLE,
			Setting.DIRECTION_INDICATOR_VISIBLE,
			Setting.PLAYER_INFO_MODE,
			Setting.TEAM_COLOR_MODE));
		settings.put(Category.TARGET_SELECTION, List.of(
			Setting.PASS_THROUGH_TRANSPARENT_BLOCKS,
			Setting.MARK_BLACKLISTED_TARGETS,
			Setting.MARK_FLUIDS));
		settings.put(Category.WHEEL_APPEARANCE, List.of(
			Setting.SPATIAL_ROOT_DISTANCE,
			Setting.SPATIAL_SUBMENU_RADIUS_SCALE,
			Setting.WHEEL_OPACITY,
			Setting.WHEEL_TARGET_OPACITY,
			Setting.WHEEL_TARGET_FONT_SIZE,
			Setting.WHEEL_OPTION_FONT_SIZE,
			Setting.SPATIAL_SHOW_TRAIL,
			Setting.SPATIAL_REDUCE_MOTION));
		settings.put(Category.INPUT_INTERACTION, List.of(
			Setting.WHEEL_HOLD_MILLIS,
			Setting.LONG_PRESS_COMPATIBILITY_MODE,
			Setting.LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			Setting.CANCEL_HALF_CONE_ANGLE_DEGREES,
			Setting.SPATIAL_DEADZONE,
			Setting.SPATIAL_STROKE,
			Setting.SPATIAL_DWELL_MILLIS,
			Setting.SPATIAL_TARGET_GLIDE,
			Setting.SPATIAL_HOVER_ENABLED,
			Setting.SPATIAL_HOVER_MILLIS));
		settings.put(Category.CHANNEL_NOTICES, List.of(
			Setting.CHANNEL,
			Setting.PING_VOLUME,
			Setting.CONFIGURATION_NOTICE_SIZE));
		settings.put(Category.GEOMETRY_CONFIG, List.of(
			Setting.ENTITY_BLOCK_RENDER_MODE,
			Setting.OPEN_CLIENT_CONFIG));
		settings.put(Category.CLIENT_PERFORMANCE, List.of(Setting.PRECISE_CAPTURE_PERIOD_TICKS));
		settings.put(Category.CHANNEL_PLAYERS, List.of(
			Setting.DEFAULT_CHANNEL_MODE,
			Setting.PLAYER_TRACKING_ENABLED));
		settings.put(Category.SEND_RATE, List.of(
			Setting.MS_TO_REGENERATE,
			Setting.RATE_LIMIT));
		settings.put(Category.MARKER_DURATION, List.of(Setting.SYNC_DURATION));
		settings.put(Category.PERFORMANCE, Arrays.stream(Setting.values())
			.filter(setting -> setting.inventoryField() != null).toList());
		settings.put(Category.SERVER_PRESENTATION, List.of(Setting.PRESENTATION_SERVER_POLICY));
		return Map.copyOf(settings);
	}
}
