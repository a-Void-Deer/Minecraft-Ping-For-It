package nx.pingwheel.common.config;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import nx.pingwheel.common.client.outline.BlockDisplayPolicy;
import nx.pingwheel.common.client.outline.BlockDisplayWhitelist;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.Global.LOGGER;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public class ClientConfig implements IConfig {
	int pingVolume = ConfigDefaults.Client.PING_VOLUME;
	int pingDistance = ConfigDefaults.Client.PING_DISTANCE;
	boolean itemIconVisible = ConfigDefaults.Client.ITEM_ICON_VISIBLE;
	boolean directionIndicatorVisible = ConfigDefaults.Client.DIRECTION_INDICATOR_VISIBLE;
	boolean markTransparentBlocks = ConfigDefaults.Client.MARK_TRANSPARENT_BLOCKS;
	boolean markBlacklistedTargets = ConfigDefaults.Client.MARK_BLACKLISTED_TARGETS;
	boolean markFluids = ConfigDefaults.Client.MARK_FLUIDS;
	@Nullable PlayerInfoMode playerInfoMode = ConfigDefaults.Client.PLAYER_INFO_MODE;
	@Nullable TeamColorMode teamColorMode = ConfigDefaults.Client.TEAM_COLOR_MODE;
	@Nullable EntityBlockRenderMode entityBlockRenderMode = ConfigDefaults.Client.ENTITY_BLOCK_RENDER_MODE;
	/** Client-local spatial gesture and appearance preferences; additive with defaults. */
	@Nullable SpatialSelectorSettings spatialSelector = new SpatialSelectorSettings();
	int pingSize = ConfigDefaults.Client.PING_SIZE;
	int configurationNoticeSize = ConfigDefaults.Client.CONFIGURATION_NOTICE_SIZE;
	int wheelHoldMillis = ConfigDefaults.Client.WHEEL_HOLD_MILLIS;
	boolean longPressCompatibilityMode = ConfigDefaults.Client.LONG_PRESS_COMPATIBILITY_MODE;
	int longPressCompatibilitySliceMillis = ConfigDefaults.Client.LONG_PRESS_COMPATIBILITY_SLICE_MILLIS;
	int cancelHalfConeAngleDegrees = ConfigDefaults.Client.CANCEL_HALF_CONE_ANGLE_DEGREES;
	/** Adjacent-press window for deriving the double-click danger angle. */
	int doubleClickDangerWindowMs = ConfigDefaults.Client.DOUBLE_CLICK_DANGER_WINDOW_MS;
	/** Zero disables double-click danger; compatibility mode clears it to zero. */
	double doubleClickDangerAngleDegrees = ConfigDefaults.Client.DOUBLE_CLICK_DANGER_ANGLE_DEGREES;
	int wheelOpacity = ConfigDefaults.Client.WHEEL_OPACITY;
	int wheelTargetOpacity = ConfigDefaults.Client.WHEEL_TARGET_OPACITY;
	/** Kept as wheelFontSize in JSON: this is the radial option-label value. */
	int wheelFontSize = ConfigDefaults.Client.WHEEL_FONT_SIZE;
	int wheelTargetFontSize = ConfigDefaults.Client.WHEEL_TARGET_FONT_SIZE;
	/** Zero means that each marker uses its frozen server-side duration. */
	int markerDisplayDuration = ConfigDefaults.Client.MARKER_DISPLAY_DURATION;
	@Setter(AccessLevel.NONE)
	@Nullable List<@Nullable String> blockDisplayWhitelist = ConfigDefaults.Client.BLOCK_DISPLAY_WHITELIST;
	@Setter(AccessLevel.NONE)
	@Nullable List<@Nullable String> blockShapeBlacklist = ConfigDefaults.Client.BLOCK_SHAPE_BLACKLIST;

	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private transient BlockDisplayPolicy blockDisplayPolicy = BlockDisplayPolicy.defaults();

	@ToString.Exclude
	@Nullable String channel = ConfigDefaults.Client.CHANNEL;
	@ToString.Exclude
	@Nullable Map<String, @Nullable String> serverChannels = new HashMap<>(ConfigDefaults.Client.SERVER_CHANNELS);

	// hidden from the settings screen
	int raycastDistance = ConfigDefaults.Client.RAYCAST_DISTANCE;
	int safeZoneLeft = ConfigDefaults.Client.SAFE_ZONE_LEFT;
	int safeZoneRight = ConfigDefaults.Client.SAFE_ZONE_RIGHT;
	int safeZoneTop = ConfigDefaults.Client.SAFE_ZONE_TOP;
	int safeZoneBottom = ConfigDefaults.Client.SAFE_ZONE_BOTTOM;

	public static final int TPS = 20;
	public static final int MAX_PING_DISTANCE = 2048;
	public static final int MAX_CHANNEL_LENGTH = 128;

	public @Nullable String getChannel() {
		final var ip = GameContext.getCurrentServerIp();

		if (ip.isPresent()) return serverChannels.getOrDefault(ip.get(), ConfigDefaults.Client.CHANNEL);

		return channel;
	}

	public void setChannel(@Nullable String newChannel) {
		final var ip = GameContext.getCurrentServerIp();

		if (ip.isPresent()) {
			serverChannels.put(ip.get(), newChannel);
		} else {
			channel = newChannel;
		}
	}

	/** Temporary nonpersisted bridge for the old wheel renderer during native-input integration. */
	@Deprecated
	public int getWheelInnerRadius() {
		return ClientConfigBounds.DEFAULT_WHEEL_INNER_RADIUS;
	}

	/** Temporary nonpersisted bridge; deliberately unrelated to spatial-selector settings. */
	@Deprecated
	public int getWheelOuterRadius() {
		return ClientConfigBounds.DEFAULT_WHEEL_OUTER_RADIUS;
	}

	public void setWheelHoldMillis(int wheelHoldMillis) {
		this.wheelHoldMillis = ClientConfigBounds.clampWheelHoldMillis(wheelHoldMillis);
		this.longPressCompatibilitySliceMillis = ClientConfigBounds.clampLongPressCompatibilitySliceMillis(
			this.longPressCompatibilitySliceMillis,
			this.wheelHoldMillis);
	}

	public void setLongPressCompatibilitySliceMillis(int longPressCompatibilitySliceMillis) {
		this.longPressCompatibilitySliceMillis = ClientConfigBounds.clampLongPressCompatibilitySliceMillis(
			longPressCompatibilitySliceMillis,
			this.wheelHoldMillis);
	}

	/**
	 * Returns the runtime-safe slice even if a caller mutated a field through a
	 * deserializer or an older reflective config path without invoking a setter.
	 */
	public int getEffectiveLongPressCompatibilitySliceMillis() {
		return ClientConfigBounds.clampLongPressCompatibilitySliceMillis(
			longPressCompatibilitySliceMillis,
			wheelHoldMillis);
	}

	/**
	 * Returns the local marker display setting with its safe fallback even when
	 * a caller supplied a value without invoking the setter or validation.
	 */
	public int getEffectiveMarkerDisplayDuration() {
		return ClientConfigBounds.clampMarkerDisplayDuration(markerDisplayDuration);
	}

	/** Returns the runtime-safe persisted value, including its follow-server sentinel. */
	public int getMarkerDisplayDuration() {
		return getEffectiveMarkerDisplayDuration();
	}

	public boolean isFollowServerMarkerDisplayDuration() {
		return getEffectiveMarkerDisplayDuration()
			== ClientConfigBounds.FOLLOW_SERVER_MARKER_DISPLAY_DURATION;
	}

	public void setMarkerDisplayDuration(int markerDisplayDuration) {
		this.markerDisplayDuration = ClientConfigBounds.clampMarkerDisplayDuration(markerDisplayDuration);
	}

	/**
	 * Returns the local entity-block geometry mode with the safe fallback even
	 * if an older deserializer or reflective path supplied {@code null}.
	 */
	public EntityBlockRenderMode getEntityBlockRenderMode() {
		return EntityBlockRenderMode.effective(entityBlockRenderMode);
	}

	/**
	 * Updates the live local setting immediately. This setter intentionally has
	 * no network or reconnect side effect.
	 */
	public void setEntityBlockRenderMode(@Nullable EntityBlockRenderMode mode) {
		this.entityBlockRenderMode = EntityBlockRenderMode.effective(mode);
	}

	public void setConfigurationNoticeSize(int configurationNoticeSize) {
		this.configurationNoticeSize = ClientConfigBounds.clampConfigurationNoticeSize(configurationNoticeSize);
	}

	public void setLongPressCompatibilityMode(boolean enabled) {
		longPressCompatibilityMode = enabled;
		if (enabled) {
			doubleClickDangerAngleDegrees = 0.0;
			if (spatialSelector != null) spatialSelector.setGestureSkipHoldEnabled(false);
		}
	}

	public void setDoubleClickDangerWindowMs(int doubleClickDangerWindowMs) {
		this.doubleClickDangerWindowMs = ClientConfigBounds.clampDoubleClickDangerWindowMs(doubleClickDangerWindowMs);
	}

	/**
	 * Keeps zero as the disabled value. While compatibility mode is active a
	 * non-zero request is rejected instead of being retained for later.
	 */
	public void setDoubleClickDangerAngleDegrees(double doubleClickDangerAngleDegrees) {
		double clamped = ClientConfigBounds.clampDoubleClickDangerAngleDegrees(doubleClickDangerAngleDegrees);
		this.doubleClickDangerAngleDegrees = longPressCompatibilityMode ? 0.0 : clamped;
	}

	public boolean isGestureSkipHoldEnabled() {
		return !longPressCompatibilityMode && spatialSelector != null && spatialSelector.isGestureSkipHoldEnabled();
	}

	public void setGestureSkipHoldEnabled(boolean enabled) {
		if (spatialSelector == null) spatialSelector = new SpatialSelectorSettings();
		spatialSelector.setGestureSkipHoldEnabled(enabled && !longPressCompatibilityMode);
	}

	public void setWheelTargetOpacity(int wheelTargetOpacity) {
		this.wheelTargetOpacity = ClientConfigBounds.clampWheelTargetOpacity(wheelTargetOpacity);
	}

	public void setBlockDisplayWhitelist(@Nullable List<@Nullable String> entries) {
		@Unmodifiable List<String> copy = validatedEntries(entries, "blockDisplayWhitelist");
		BlockDisplayPolicy nextPolicy = BlockDisplayPolicy.compile(copy, blockShapeBlacklist);
		blockDisplayWhitelist = copy;
		blockDisplayPolicy = nextPolicy;
	}

	public void setBlockShapeBlacklist(@Nullable List<@Nullable String> entries) {
		@Unmodifiable List<String> copy = validatedEntries(entries, "blockShapeBlacklist");
		BlockDisplayPolicy nextPolicy = BlockDisplayPolicy.compile(blockDisplayWhitelist, copy);
		blockShapeBlacklist = copy;
		blockDisplayPolicy = nextPolicy;
	}

	public BlockDisplayPolicy getBlockDisplayPolicy() {
		return blockDisplayPolicy;
	}

	private static @Unmodifiable List<String> validatedEntries(@Nullable List<@Nullable String> entries, String fieldName) {
		BlockDisplayWhitelist.validateEntries(entries, fieldName);
		return List.copyOf(entries);
	}

	@Override
	public void validate() {
		validate((key, suppliedValue, effectiveValue) -> LOGGER.warn(
			formatClampWarning(key, suppliedValue, effectiveValue)));
	}

	static String formatClampWarning(String key, int suppliedValue, int effectiveValue) {
		return "Client config value clamped: key=%s, supplied=%d, effective=%d"
			.formatted(key, suppliedValue, effectiveValue);
	}

	void validate(ClampWarningSink warningSink) {
		// Gson maps unknown enum names to null. Recover them locally so malformed
		// client data never selects a new geometry route accidentally.
		entityBlockRenderMode = EntityBlockRenderMode.effective(entityBlockRenderMode);

		if (spatialSelector == null) spatialSelector = new SpatialSelectorSettings();
		spatialSelector.validate();
		if (longPressCompatibilityMode) spatialSelector.setGestureSkipHoldEnabled(false);

		final int suppliedWheelHoldMillis = wheelHoldMillis;
		final int suppliedLongPressCompatibilitySliceMillis = longPressCompatibilitySliceMillis;
		final int suppliedCancelHalfConeAngleDegrees = cancelHalfConeAngleDegrees;
		final int suppliedDoubleClickDangerWindowMs = doubleClickDangerWindowMs;
		final int suppliedWheelOpacity = wheelOpacity;
		final int suppliedWheelTargetOpacity = wheelTargetOpacity;
		final int suppliedWheelFontSize = wheelFontSize;
		final int suppliedWheelTargetFontSize = wheelTargetFontSize;
		final int suppliedConfigurationNoticeSize = configurationNoticeSize;
		final int suppliedMarkerDisplayDuration = markerDisplayDuration;

		wheelHoldMillis = ClientConfigBounds.clampWheelHoldMillis(wheelHoldMillis);
		warnIfChanged(
			warningSink,
			"wheelHoldMillis",
			suppliedWheelHoldMillis,
			wheelHoldMillis);

		longPressCompatibilitySliceMillis = ClientConfigBounds.clampLongPressCompatibilitySliceMillis(
			longPressCompatibilitySliceMillis,
			wheelHoldMillis);
		warnIfChanged(
			warningSink,
			"longPressCompatibilitySliceMillis",
			suppliedLongPressCompatibilitySliceMillis,
			longPressCompatibilitySliceMillis);

		cancelHalfConeAngleDegrees = ClientConfigBounds.clampCancelHalfConeAngleDegrees(cancelHalfConeAngleDegrees);
		warnIfChanged(
			warningSink,
			"cancelHalfConeAngleDegrees",
			suppliedCancelHalfConeAngleDegrees,
			cancelHalfConeAngleDegrees);

		doubleClickDangerWindowMs = ClientConfigBounds.clampDoubleClickDangerWindowMs(doubleClickDangerWindowMs);
		warnIfChanged(
			warningSink,
			"doubleClickDangerWindowMs",
			suppliedDoubleClickDangerWindowMs,
			doubleClickDangerWindowMs);

		doubleClickDangerAngleDegrees =
			ClientConfigBounds.clampDoubleClickDangerAngleDegrees(doubleClickDangerAngleDegrees);
		if (longPressCompatibilityMode) doubleClickDangerAngleDegrees = 0.0;

		wheelOpacity = ClientConfigBounds.clampWheelOpacity(wheelOpacity);
		warnIfChanged(warningSink, "wheelOpacity", suppliedWheelOpacity, wheelOpacity);

		wheelTargetOpacity = ClientConfigBounds.clampWheelTargetOpacity(wheelTargetOpacity);
		warnIfChanged(warningSink, "wheelTargetOpacity", suppliedWheelTargetOpacity, wheelTargetOpacity);

		wheelFontSize = ClientConfigBounds.clampWheelFontSize(wheelFontSize);
		warnIfChanged(warningSink, "wheelFontSize", suppliedWheelFontSize, wheelFontSize);

		wheelTargetFontSize = ClientConfigBounds.clampWheelTargetFontSize(wheelTargetFontSize);
		warnIfChanged(warningSink, "wheelTargetFontSize", suppliedWheelTargetFontSize, wheelTargetFontSize);

		configurationNoticeSize = ClientConfigBounds.clampConfigurationNoticeSize(configurationNoticeSize);
		warnIfChanged(
			warningSink,
			"configurationNoticeSize",
			suppliedConfigurationNoticeSize,
			configurationNoticeSize);

		markerDisplayDuration = ClientConfigBounds.clampMarkerDisplayDuration(markerDisplayDuration);
		warnIfChanged(
			warningSink,
			"markerDisplayDuration",
			suppliedMarkerDisplayDuration,
			markerDisplayDuration);

		if (channel.length() > MAX_CHANNEL_LENGTH) {
			channel = channel.substring(0, MAX_CHANNEL_LENGTH);
		}

		for (Map.Entry<String, String> entry : serverChannels.entrySet()) {
			final var channel = entry.getValue();

			if (channel.length() > MAX_CHANNEL_LENGTH) {
				entry.setValue(channel.substring(0, MAX_CHANNEL_LENGTH));
			}
		}

		@Unmodifiable List<String> validatedWhitelist = validatedEntries(blockDisplayWhitelist, "blockDisplayWhitelist");
		@Unmodifiable List<String> validatedBlacklist = validatedEntries(blockShapeBlacklist, "blockShapeBlacklist");
		blockDisplayWhitelist = validatedWhitelist;
		blockShapeBlacklist = validatedBlacklist;
		blockDisplayPolicy = BlockDisplayPolicy.compile(validatedWhitelist, validatedBlacklist);
	}

	private static void warnIfChanged(
		ClampWarningSink warningSink,
		String key,
		int suppliedValue,
		int effectiveValue) {
		if (suppliedValue != effectiveValue) {
			warningSink.warn(key, suppliedValue, effectiveValue);
		}
	}

	@Override
	public void onUpdate() {
		if (longPressCompatibilityMode) doubleClickDangerAngleDegrees = 0.0;
		if (longPressCompatibilityMode && spatialSelector != null) spatialSelector.setGestureSkipHoldEnabled(false);
		blockDisplayPolicy = BlockDisplayPolicy.compile(blockDisplayWhitelist, blockShapeBlacklist);
		LOGGER.debug(
			"Client wheel settings updated: wheelHoldMillis=%d, longPressCompatibilityMode=%s, longPressCompatibilitySliceMillis=%d, cancelHalfConeAngleDegrees=%d, doubleClickDangerWindowMs=%d, doubleClickDangerAngleDegrees=%s, wheelOpacity=%d, wheelTargetOpacity=%d, wheelFontSize=%d, wheelTargetFontSize=%d, configurationNoticeSize=%d, spatialSelector=%s"
				.formatted(
					wheelHoldMillis,
					longPressCompatibilityMode,
					getEffectiveLongPressCompatibilitySliceMillis(),
					cancelHalfConeAngleDegrees,
					doubleClickDangerWindowMs,
					doubleClickDangerAngleDegrees,
					wheelOpacity,
					wheelTargetOpacity,
					wheelFontSize,
					wheelTargetFontSize,
					configurationNoticeSize,
					spatialSelector));

		if (Game != null) {
			IPlatformNetworkService.INSTANCE.sendToServer(new UpdateChannelC2SPacket(getChannel()));
		}
	}

	@Override
	public boolean recoverInvalidOnLoad() {
		return true;
	}

	public static final ConfigHandler<ClientConfig> HANDLER = ConfigHandler.of(ClientConfig.class, ".json");
}

@FunctionalInterface
interface ClampWarningSink {
	void warn(String key, int suppliedValue, int effectiveValue);
}
