package nx.pingwheel.common.config;

import nx.pingwheel.common.presentation.PresentationPropertyRef;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Shared client and server configuration default definitions, excluding keybindings.
 * Models own mutable state and structural factories; these definitions are immutable
 * and initialize no config handlers, runtime services or optional integrations.
 */
@NotNullByDefault
public final class ConfigDefaults {

	private ConfigDefaults() {}

	@NotNullByDefault
	public static final class Client {
		public static final int PING_VOLUME = 100;
		public static final int PING_DISTANCE = 2048;
		public static final int PING_SIZE = 100;
		public static final boolean ITEM_ICON_VISIBLE = true;
		public static final boolean DIRECTION_INDICATOR_VISIBLE = true;
		public static final boolean MARK_TRANSPARENT_BLOCKS = true;
		public static final boolean MARK_BLACKLISTED_TARGETS = false;
		public static final boolean MARK_FLUIDS = false;
		public static final PlayerInfoMode PLAYER_INFO_MODE = PlayerInfoMode.HOLD;
		public static final TeamColorMode TEAM_COLOR_MODE = TeamColorMode.FULL;
		/** Fresh configs use ALL; invalid or null persisted modes recover to COMPATIBLE. */
		public static final EntityBlockRenderMode ENTITY_BLOCK_RENDER_MODE = EntityBlockRenderMode.ALL;
		public static final EntityBlockRenderMode RECOVERY_ENTITY_BLOCK_RENDER_MODE = EntityBlockRenderMode.COMPATIBLE;

		public static final int CONFIGURATION_NOTICE_SIZE = 100;
		public static final int WHEEL_HOLD_MILLIS = 150;
		public static final boolean LONG_PRESS_COMPATIBILITY_MODE = false;
		public static final int LONG_PRESS_COMPATIBILITY_SLICE_MILLIS = 20;
		public static final int CANCEL_HALF_CONE_ANGLE_DEGREES = 5;
		public static final int DOUBLE_CLICK_DANGER_WINDOW_MS = 100;
		public static final double DOUBLE_CLICK_DANGER_ANGLE_DEGREES = 3.5d;
		public static final int WHEEL_OPACITY = 75;
		public static final int WHEEL_TARGET_OPACITY = 100;
		public static final int WHEEL_FONT_SIZE = 200;
		public static final int WHEEL_TARGET_FONT_SIZE = 100;
		/** Zero follows each marker's frozen server-side duration, not a zero lifetime. */
		public static final int MARKER_DISPLAY_DURATION = 0;

		public static final @Unmodifiable List<String> BLOCK_DISPLAY_WHITELIST = List.of("*:*");
		public static final @Unmodifiable List<String> BLOCK_SHAPE_BLACKLIST = List.of();
		public static final String CHANNEL = "";
		/** Consumers must copy this definition into their own mutable channel map. */
		public static final @Unmodifiable Map<String, String> SERVER_CHANNELS = Map.of();

		public static final int RAYCAST_DISTANCE = 1024;
		/** Direction-indicator insets in GUI-scaled pixels. */
		public static final int SAFE_ZONE_LEFT = 5;
		public static final int SAFE_ZONE_RIGHT = 5;
		public static final int SAFE_ZONE_TOP = 5;
		public static final int SAFE_ZONE_BOTTOM = 60;

		private Client() {}

		/** Distances are GUI pixels; trail length is a retained-point count. */
		@NotNullByDefault
		public static final class SpatialSelector {

			public static final int DEADZONE = 24;
			public static final int STROKE = 50;
			public static final int DWELL_MILLIS = 180;
			public static final int ROOT_DISTANCE = 55;
			public static final BigDecimal SUBMENU_RADIUS_SCALE = new BigDecimal("1.5");
			public static final BigDecimal INVENTORY_MAX_WIDTH_FRACTION = new BigDecimal("0.3");
			public static final BigDecimal INVENTORY_MAX_HEIGHT_FRACTION = new BigDecimal("0.7");
			public static final boolean HOVER_ENABLED = false;
			public static final int HOVER_MILLIS = 500;
			public static final boolean SHOW_TRAIL = true;
			public static final int TRAIL_LENGTH = 130;
			public static final int TRAIL_OPACITY = 50;
			public static final boolean REDUCE_MOTION = false;
			public static final int PRECISE_CAPTURE_PERIOD_TICKS = 1;
			public static final boolean CONTENT_INTENT_ENABLED = false;
			public static final boolean INVENTORY_SELECTION_STAY_OPEN_ENABLED = false;
			public static final boolean GESTURE_SKIP_HOLD_ENABLED = false;
			public static final int GESTURE_SKIP_HOLD_MILLIS = 75;

			private SpatialSelector() {}
		}
	}

	@NotNullByDefault
	public static final class Server {

		public static final ChannelMode DEFAULT_CHANNEL_MODE = ChannelMode.AUTO;
		public static final boolean PLAYER_TRACKING_ENABLED = true;
		public static final boolean BUDGET_DELAY_NOTIFICATIONS_ENABLED = true;
		public static final int MS_TO_REGENERATE = 1000;
		public static final int RATE_LIMIT = 5;
		/** Server marker lifetime in seconds. */
		public static final int SYNC_DURATION = 7;
		public static final int PING_DISTANCE = 2048;

		private Server() {}
	}

	/** Finite inventory values; each multiplier has an independent configuration owner. */
	@NotNullByDefault
	public static final class Inventory {

		public static final int PHYSICAL_SLOTS_PER_TICK = 256;
		public static final int PENDING_MEMORY_MIB = 64;
		public static final BigDecimal DISPLAY_BYTE_MULTIPLIER = BigDecimal.ONE;
		public static final BigDecimal FRAME_BYTE_MULTIPLIER = BigDecimal.ONE;

		private Inventory() {}

		@NotNullByDefault
		public static final class Preview {

			public static final int PERIOD_TICKS = 5;
			public static final int MAX_VARIANTS_PER_CLIENT_PERIOD = 32;
			public static final int MAX_SLOTS_PER_CLIENT = 256;
			public static final int MAX_SLOTS_SERVER = 1024;
			public static final int MAX_TARGETS_PER_CLIENT = 2;
			public static final BigDecimal CLIENT_BYTE_MULTIPLIER = BigDecimal.ONE;
			public static final BigDecimal GLOBAL_BYTE_MULTIPLIER = BigDecimal.ONE;

			private Preview() {}
		}

		@NotNullByDefault
		public static final class Tracking {

			public static final int PERIOD_TICKS = 3;
			public static final boolean DISTINGUISH_FACES = false;
			public static final boolean DISTINGUISH_PLAYERS = false;
			public static final int MAX_VARIANTS_PER_TARGET = 8;
			public static final int MAX_SLOTS_PER_TARGET = 512;
			public static final int MAX_SLOTS_SERVER = 1024;
			public static final BigDecimal STREAM_BYTE_MULTIPLIER = BigDecimal.ONE;
			public static final BigDecimal SNAPSHOT_BYTE_MULTIPLIER = BigDecimal.ONE;
			public static final BigDecimal GLOBAL_BYTE_MULTIPLIER = BigDecimal.ONE;
			/** Cadences and smoothing window are measured in tracking accounting periods. */
			public static final int RESYNC_MIN_PERIODS = 5;
			public static final int HEARTBEAT_PERIODS = 16;
			public static final int GRACE_PERIODS = 4;

			private Tracking() {}
		}
	}

	@NotNullByDefault
	public static final class Presentation {

		public static final int MIN_UPDATE_INTERVAL_TICKS = 10;
		public static final int SCAN_BUDGET = 256;
		public static final @Unmodifiable Map<String, Integer> PERMISSION_LEVELS = Map.of();
		public static final @Unmodifiable Map<String, Integer> UPDATE_INTERVALS = Map.of();

		private Presentation() {}

		@NotNullByDefault
		public static final class RuleSet {

			public static final @Unmodifiable List<String> WHITE = List.of();
			public static final @Unmodifiable List<String> BLACK = List.of();
			public static final boolean WHITELIST_ONLY = false;
			public static final @Unmodifiable List<PresentationPropertyRef> CHILD_BLACK = List.of(
				new PresentationPropertyRef("create:presentation", "create:kinetic.speed", List.of("effective_rpm")),
				new PresentationPropertyRef("create:presentation", "create:kinetic.speed", List.of("theoretical_rpm")));

			private RuleSet() {}
		}
	}
}
