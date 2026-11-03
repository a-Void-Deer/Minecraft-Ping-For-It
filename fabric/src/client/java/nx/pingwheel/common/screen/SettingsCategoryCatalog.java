package nx.pingwheel.common.screen;

import nx.pingwheel.common.screen.SettingsNavigationModel.Category;

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
		WHEEL_INNER_RADIUS("wheel_inner_radius"),
		WHEEL_OUTER_RADIUS("wheel_outer_radius"),
		WHEEL_OPACITY("wheel_opacity"),
		WHEEL_TARGET_FONT_SIZE("wheel_target_font_size"),
		WHEEL_OPTION_FONT_SIZE("wheel_font_size"),
		WHEEL_HOLD_MILLIS("wheel_hold_millis"),
		WHEEL_TIMEOUT_MILLIS("wheel_timeout_millis"),
		LONG_PRESS_COMPATIBILITY_MODE("long_press_compatibility_mode"),
		LONG_PRESS_COMPATIBILITY_SLICE_MILLIS("long_press_compatibility_slice_millis"),
		CANCEL_HALF_CONE_ANGLE_DEGREES("cancel_half_cone_angle_degrees"),
		CHANNEL("channel"),
		PING_VOLUME("ping_volume"),
		CONFIGURATION_NOTICE_SIZE("configuration_notice_size"),
		ENTITY_BLOCK_RENDER_MODE("entity_block_render_mode"),
		OPEN_CLIENT_CONFIG("open_client_config"),
		DEFAULT_CHANNEL_MODE("default_channel_mode"),
		PLAYER_TRACKING_ENABLED("player_tracking_enabled"),
		MS_TO_REGENERATE("ms_to_regenerate"),
		RATE_LIMIT("rate_limit"),
		SYNC_DURATION("sync_duration");

		private final String id;

		Setting(String id) {
			this.id = id;
		}

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
			Setting.WHEEL_INNER_RADIUS,
			Setting.WHEEL_OUTER_RADIUS,
			Setting.WHEEL_OPACITY,
			Setting.WHEEL_TARGET_FONT_SIZE,
			Setting.WHEEL_OPTION_FONT_SIZE));
		settings.put(Category.INPUT_INTERACTION, List.of(
			Setting.WHEEL_HOLD_MILLIS,
			Setting.WHEEL_TIMEOUT_MILLIS,
			Setting.LONG_PRESS_COMPATIBILITY_MODE,
			Setting.LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			Setting.CANCEL_HALF_CONE_ANGLE_DEGREES));
		settings.put(Category.CHANNEL_NOTICES, List.of(
			Setting.CHANNEL,
			Setting.PING_VOLUME,
			Setting.CONFIGURATION_NOTICE_SIZE));
		settings.put(Category.GEOMETRY_CONFIG, List.of(
			Setting.ENTITY_BLOCK_RENDER_MODE,
			Setting.OPEN_CLIENT_CONFIG));
		settings.put(Category.CHANNEL_PLAYERS, List.of(
			Setting.DEFAULT_CHANNEL_MODE,
			Setting.PLAYER_TRACKING_ENABLED));
		settings.put(Category.SEND_RATE, List.of(
			Setting.MS_TO_REGENERATE,
			Setting.RATE_LIMIT));
		settings.put(Category.MARKER_DURATION, List.of(Setting.SYNC_DURATION));
		return Map.copyOf(settings);
	}
}
