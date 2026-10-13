package nx.pingwheel.common.config;

import java.util.Optional;

/**
 * A validated, dirty-field-only server settings update.  Keeping this value
 * independent of networking makes permission and merge behavior testable
 * without constructing a Minecraft server.
 */
public record ServerConfigUpdate(
	int changedFields,
	ChannelMode defaultChannelMode,
	boolean playerTrackingEnabled,
	int msToRegenerate,
	int rateLimit,
	int syncDuration,
	InventoryConfigValues inventory
) {
	public static final int DEFAULT_CHANNEL_MODE = 1;
	public static final int PLAYER_TRACKING_ENABLED = 1 << 1;
	public static final int MS_TO_REGENERATE = 1 << 2;
	public static final int RATE_LIMIT = 1 << 3;
	public static final int SYNC_DURATION = 1 << 4;
	public static final int INVENTORY_PHYSICAL_SLOTS_PER_TICK = InventoryConfigValues.Field.PHYSICAL_SLOTS_PER_TICK.mask();
	public static final int INVENTORY_PENDING_MEMORY_MIB = InventoryConfigValues.Field.PENDING_MEMORY_MIB.mask();
	public static final int INVENTORY_PREVIEW_PERIOD_TICKS = InventoryConfigValues.Field.PREVIEW_PERIOD_TICKS.mask();
	public static final int INVENTORY_PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD = InventoryConfigValues.Field.PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD.mask();
	public static final int INVENTORY_PREVIEW_MAX_SLOTS_PER_CLIENT = InventoryConfigValues.Field.PREVIEW_MAX_SLOTS_PER_CLIENT.mask();
	public static final int INVENTORY_PREVIEW_MAX_SLOTS_SERVER = InventoryConfigValues.Field.PREVIEW_MAX_SLOTS_SERVER.mask();
	public static final int INVENTORY_PREVIEW_MAX_TARGETS_PER_CLIENT = InventoryConfigValues.Field.PREVIEW_MAX_TARGETS_PER_CLIENT.mask();
	public static final int INVENTORY_PREVIEW_CLIENT_BYTE_MULTIPLIER = InventoryConfigValues.Field.PREVIEW_CLIENT_BYTE_MULTIPLIER.mask();
	public static final int INVENTORY_PREVIEW_GLOBAL_BYTE_MULTIPLIER = InventoryConfigValues.Field.PREVIEW_GLOBAL_BYTE_MULTIPLIER.mask();
	public static final int INVENTORY_TRACKING_PERIOD_TICKS = InventoryConfigValues.Field.TRACKING_PERIOD_TICKS.mask();
	public static final int INVENTORY_TRACKING_MAX_VARIANTS_PER_TARGET = InventoryConfigValues.Field.TRACKING_MAX_VARIANTS_PER_TARGET.mask();
	public static final int INVENTORY_TRACKING_MAX_SLOTS_PER_TARGET = InventoryConfigValues.Field.TRACKING_MAX_SLOTS_PER_TARGET.mask();
	public static final int INVENTORY_TRACKING_MAX_SLOTS_SERVER = InventoryConfigValues.Field.TRACKING_MAX_SLOTS_SERVER.mask();
	public static final int INVENTORY_TRACKING_STREAM_BYTE_MULTIPLIER = InventoryConfigValues.Field.TRACKING_STREAM_BYTE_MULTIPLIER.mask();
	public static final int INVENTORY_TRACKING_SNAPSHOT_BYTE_MULTIPLIER = InventoryConfigValues.Field.TRACKING_SNAPSHOT_BYTE_MULTIPLIER.mask();
	public static final int INVENTORY_TRACKING_GLOBAL_BYTE_MULTIPLIER = InventoryConfigValues.Field.TRACKING_GLOBAL_BYTE_MULTIPLIER.mask();
	public static final int INVENTORY_TRACKING_RESYNC_MIN_PERIODS = InventoryConfigValues.Field.TRACKING_RESYNC_MIN_PERIODS.mask();
	public static final int INVENTORY_TRACKING_HEARTBEAT_PERIODS = InventoryConfigValues.Field.TRACKING_HEARTBEAT_PERIODS.mask();
	public static final int INVENTORY_TRACKING_GRACE_PERIODS = InventoryConfigValues.Field.TRACKING_GRACE_PERIODS.mask();
	public static final int INVENTORY_DISPLAY_BYTE_MULTIPLIER = InventoryConfigValues.Field.DISPLAY_BYTE_MULTIPLIER.mask();
	public static final int INVENTORY_FRAME_BYTE_MULTIPLIER = InventoryConfigValues.Field.FRAME_BYTE_MULTIPLIER.mask();
	public static final int ALL_INVENTORY_FIELDS = InventoryConfigValues.allFieldMask();
	public static final int ALL_FIELDS = DEFAULT_CHANNEL_MODE
		| PLAYER_TRACKING_ENABLED
		| MS_TO_REGENERATE
		| RATE_LIMIT
		| SYNC_DURATION
		| ALL_INVENTORY_FIELDS;

	public ServerConfigUpdate(
		int changedFields,
		ChannelMode defaultChannelMode,
		boolean playerTrackingEnabled,
		int msToRegenerate,
		int rateLimit) {
		this(changedFields, defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit,
			ServerConfigBounds.DEFAULT_SYNC_DURATION);
	}

	/** Source-compatible constructor for callers selecting the original five fields. */
	public ServerConfigUpdate(
		int changedFields, ChannelMode defaultChannelMode, boolean playerTrackingEnabled,
		int msToRegenerate, int rateLimit, int syncDuration) {
		this(changedFields, defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit,
			syncDuration, InventoryConfigValues.defaults());
	}

	public boolean isValid() {
		return changedFields != 0
			&& (changedFields & ~ALL_FIELDS) == 0
			&& defaultChannelMode != null
			&& msToRegenerate >= 0
			&& rateLimit >= 0
			&& syncDuration >= 0
			&& inventory != null && inventory.isSafe();
	}

	public static Optional<ServerConfigUpdate> validated(
		int changedFields,
		ChannelMode defaultChannelMode,
		boolean playerTrackingEnabled,
		int msToRegenerate,
		int rateLimit) {
		return validated(changedFields, defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit,
			ServerConfigBounds.DEFAULT_SYNC_DURATION);
	}

	public static Optional<ServerConfigUpdate> validated(
		int changedFields,
		ChannelMode defaultChannelMode,
		boolean playerTrackingEnabled,
		int msToRegenerate,
		int rateLimit,
		int syncDuration) {
		return validated(changedFields, defaultChannelMode, playerTrackingEnabled, msToRegenerate,
			rateLimit, syncDuration, InventoryConfigValues.defaults());
	}

	public static Optional<ServerConfigUpdate> validated(
		int changedFields, ChannelMode defaultChannelMode, boolean playerTrackingEnabled,
		int msToRegenerate, int rateLimit, int syncDuration, InventoryConfigValues inventory) {
		var update = new ServerConfigUpdate(
			changedFields,
			defaultChannelMode,
			playerTrackingEnabled,
			msToRegenerate,
			rateLimit,
			syncDuration,
			inventory);
		return update.isValid() ? Optional.of(update) : Optional.empty();
	}

	/**
	 * Applies only the fields selected by the bitmask.  Untouched fields come
	 * from the authoritative snapshot, so concurrent edits to another field are
	 * preserved.  A rejected update is returned as the unchanged current
	 * snapshot; callers that must distinguish rejection use {@link #appliedTo}.
	 */
	public ServerConfigSnapshot applyTo(ServerConfigSnapshot current) {
		return appliedTo(current).orElse(current);
	}

	/**
	 * Applies only the fields selected by the bitmask and then validates the
	 * merged display/frame size pair against the current authoritative values.
	 * An invalid merged pair is rejected atomically: no unselected leaf is
	 * adjusted and no assignment is produced.
	 */
	public Optional<ServerConfigSnapshot> appliedTo(ServerConfigSnapshot current) {
		if (!isValid() || current == null || !current.isSafe()) {
			return Optional.empty();
		}

		var mergedInventory = current.inventory().merge(changedFields, inventory);
		if (!mergedInventory.isSizePairCompatible()) {
			return Optional.empty();
		}

		return Optional.of(new ServerConfigSnapshot(
			current.canEdit(),
			(changedFields & DEFAULT_CHANNEL_MODE) != 0 ? defaultChannelMode : current.defaultChannelMode(),
			(changedFields & PLAYER_TRACKING_ENABLED) != 0 ? playerTrackingEnabled : current.playerTrackingEnabled(),
			(changedFields & MS_TO_REGENERATE) != 0 ? msToRegenerate : current.msToRegenerate(),
			(changedFields & RATE_LIMIT) != 0 ? rateLimit : current.rateLimit(),
			(changedFields & SYNC_DURATION) != 0
				? ServerConfigBounds.clampSyncDuration(syncDuration)
				: current.syncDuration(),
			mergedInventory));
	}
}
