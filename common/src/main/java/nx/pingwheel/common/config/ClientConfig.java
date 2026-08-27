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
	int pingVolume = 100;
	int pingDistance = 2048;
	boolean itemIconVisible = true;
	boolean directionIndicatorVisible = true;
	boolean passThroughTransparentBlocks = false;
	boolean markBlacklistedTargets = false;
	boolean markFluids = false;
	PlayerInfoMode playerInfoMode = PlayerInfoMode.HOLD;
	TeamColorMode teamColorMode = TeamColorMode.FULL;
	EntityBlockRenderMode entityBlockRenderMode = EntityBlockRenderMode.ALL;
	int pingSize = 100;
	int configurationNoticeSize = ClientConfigBounds.DEFAULT_CONFIGURATION_NOTICE_SIZE;
	int wheelHoldMillis = ClientConfigBounds.DEFAULT_WHEEL_HOLD_MILLIS;
	boolean longPressCompatibilityMode = false;
	int longPressCompatibilitySliceMillis = ClientConfigBounds.DEFAULT_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS;
	int wheelTimeoutMillis = ClientConfigBounds.DEFAULT_WHEEL_TIMEOUT_MILLIS;
	int cancelHalfConeAngleDegrees = ClientConfigBounds.DEFAULT_CANCEL_HALF_CONE_ANGLE_DEGREES;
	int wheelInnerRadius = ClientConfigBounds.DEFAULT_WHEEL_INNER_RADIUS;
	int wheelOuterRadius = ClientConfigBounds.DEFAULT_WHEEL_OUTER_RADIUS;
	int wheelOpacity = ClientConfigBounds.DEFAULT_WHEEL_OPACITY;
	/** Kept as wheelFontSize in JSON: this is the radial option-label value. */
	int wheelFontSize = ClientConfigBounds.DEFAULT_WHEEL_FONT_SIZE;
	int wheelTargetFontSize = ClientConfigBounds.DEFAULT_WHEEL_TARGET_FONT_SIZE;
	/** Zero means that each marker uses its frozen server-side duration. */
	int markerDisplayDuration = ClientConfigBounds.DEFAULT_MARKER_DISPLAY_DURATION;
	@Setter(AccessLevel.NONE)
	List<String> blockDisplayWhitelist = List.of("*:*");
	@Setter(AccessLevel.NONE)
	List<String> blockShapeBlacklist = List.of();

	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private transient BlockDisplayPolicy blockDisplayPolicy = BlockDisplayPolicy.defaults();

	@ToString.Exclude
	String channel = "";
	@ToString.Exclude
	Map<String, String> serverChannels = new HashMap<>();

	// hidden from the settings screen
	int raycastDistance = 1024;
	int safeZoneLeft = 5;
	int safeZoneRight = 5;
	int safeZoneTop = 5;
	int safeZoneBottom = 60;

	public static final int TPS = 20;
	public static final int MAX_PING_DISTANCE = 2048;
	public static final int MAX_CHANNEL_LENGTH = 128;

	public String getChannel() {
		final var ip = GameContext.getCurrentServerIp();

		if (ip.isPresent()) return serverChannels.getOrDefault(ip.get(), "");

		return channel;
	}

	public void setChannel(String newChannel) {
		final var ip = GameContext.getCurrentServerIp();

		if (ip.isPresent()) {
			serverChannels.put(ip.get(), newChannel);
		} else {
			channel = newChannel;
		}
	}

	public void setWheelInnerRadius(int wheelInnerRadius) {
		final var radii = ClientConfigBounds.clampWheelRadii(wheelInnerRadius, this.wheelOuterRadius);
		this.wheelInnerRadius = radii.innerRadius();
		this.wheelOuterRadius = radii.outerRadius();
	}

	public void setWheelOuterRadius(int wheelOuterRadius) {
		final var radii = ClientConfigBounds.clampWheelRadii(this.wheelInnerRadius, wheelOuterRadius);
		this.wheelInnerRadius = radii.innerRadius();
		this.wheelOuterRadius = radii.outerRadius();
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
	public void setEntityBlockRenderMode(EntityBlockRenderMode mode) {
		this.entityBlockRenderMode = EntityBlockRenderMode.effective(mode);
	}

	public void setConfigurationNoticeSize(int configurationNoticeSize) {
		this.configurationNoticeSize = ClientConfigBounds.clampConfigurationNoticeSize(configurationNoticeSize);
	}

	public void setBlockDisplayWhitelist(List<String> entries) {
		List<String> copy = validatedEntries(entries, "blockDisplayWhitelist");
		BlockDisplayPolicy nextPolicy = BlockDisplayPolicy.compile(copy, blockShapeBlacklist);
		blockDisplayWhitelist = copy;
		blockDisplayPolicy = nextPolicy;
	}

	public void setBlockShapeBlacklist(List<String> entries) {
		List<String> copy = validatedEntries(entries, "blockShapeBlacklist");
		BlockDisplayPolicy nextPolicy = BlockDisplayPolicy.compile(blockDisplayWhitelist, copy);
		blockShapeBlacklist = copy;
		blockDisplayPolicy = nextPolicy;
	}

	public BlockDisplayPolicy getBlockDisplayPolicy() {
		return blockDisplayPolicy;
	}

	private static List<String> validatedEntries(List<String> entries, String fieldName) {
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

		final int suppliedWheelHoldMillis = wheelHoldMillis;
		final int suppliedLongPressCompatibilitySliceMillis = longPressCompatibilitySliceMillis;
		final int suppliedWheelTimeoutMillis = wheelTimeoutMillis;
		final int suppliedCancelHalfConeAngleDegrees = cancelHalfConeAngleDegrees;
		final int suppliedWheelInnerRadius = wheelInnerRadius;
		final int suppliedWheelOuterRadius = wheelOuterRadius;
		final int suppliedWheelOpacity = wheelOpacity;
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

		wheelTimeoutMillis = ClientConfigBounds.clampWheelTimeoutMillis(wheelTimeoutMillis);
		warnIfChanged(
			warningSink,
			"wheelTimeoutMillis",
			suppliedWheelTimeoutMillis,
			wheelTimeoutMillis);

		cancelHalfConeAngleDegrees = ClientConfigBounds.clampCancelHalfConeAngleDegrees(cancelHalfConeAngleDegrees);
		warnIfChanged(
			warningSink,
			"cancelHalfConeAngleDegrees",
			suppliedCancelHalfConeAngleDegrees,
			cancelHalfConeAngleDegrees);

		final var radii = ClientConfigBounds.clampWheelRadii(wheelInnerRadius, wheelOuterRadius);
		wheelInnerRadius = radii.innerRadius();
		wheelOuterRadius = radii.outerRadius();
		warnIfChanged(warningSink, "wheelInnerRadius", suppliedWheelInnerRadius, wheelInnerRadius);
		warnIfChanged(warningSink, "wheelOuterRadius", suppliedWheelOuterRadius, wheelOuterRadius);

		wheelOpacity = ClientConfigBounds.clampWheelOpacity(wheelOpacity);
		warnIfChanged(warningSink, "wheelOpacity", suppliedWheelOpacity, wheelOpacity);

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

		List<String> validatedWhitelist = validatedEntries(blockDisplayWhitelist, "blockDisplayWhitelist");
		List<String> validatedBlacklist = validatedEntries(blockShapeBlacklist, "blockShapeBlacklist");
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
		blockDisplayPolicy = BlockDisplayPolicy.compile(blockDisplayWhitelist, blockShapeBlacklist);
		LOGGER.debug(
			"Client wheel settings updated: wheelHoldMillis=%d, longPressCompatibilityMode=%s, longPressCompatibilitySliceMillis=%d, wheelTimeoutMillis=%d, cancelHalfConeAngleDegrees=%d, wheelInnerRadius=%d, wheelOuterRadius=%d, wheelOpacity=%d, wheelFontSize=%d, wheelTargetFontSize=%d, configurationNoticeSize=%d"
				.formatted(
					wheelHoldMillis,
					longPressCompatibilityMode,
					getEffectiveLongPressCompatibilitySliceMillis(),
					wheelTimeoutMillis,
					cancelHalfConeAngleDegrees,
					wheelInnerRadius,
					wheelOuterRadius,
					wheelOpacity,
					wheelFontSize,
					wheelTargetFontSize,
					configurationNoticeSize));

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
