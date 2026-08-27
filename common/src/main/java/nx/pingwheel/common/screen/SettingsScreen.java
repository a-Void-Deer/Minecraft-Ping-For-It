package nx.pingwheel.common.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.Util;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.config.PlayerInfoMode;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.TeamColorMode;
import nx.pingwheel.common.integration.TeamContext;
import nx.pingwheel.common.integration.TeamContextHandler;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.resource.LanguageUtils;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.config.ClientConfig.*;
import static nx.pingwheel.common.config.ClientConfigBounds.*;
import static nx.pingwheel.common.Global.warnException;

public class SettingsScreen extends OptionsSubScreen {
	private static WeakReference<SettingsScreen> currentSettingsScreen = new WeakReference<>(null);

	private final ClientConfig config;
	private final ServerSettingsModel serverSettings = new ServerSettingsModel(false);

	private Screen parent;
	private EditBox channelTextField;
	private Button resetAllButton;
	private boolean resetConfirmationHandled;
	private boolean serverCollapseConfirmationHandled;
	private boolean suppressSaveOnClose;
	private MutableComponent serverValidationMessage;
	private StringWidget serverValidationWidget;

	private static void registerCurrent(SettingsScreen screen) {
		currentSettingsScreen = new WeakReference<>(screen);
	}

	private static void clearCurrent(SettingsScreen screen) {
		if (currentSettingsScreen.get() == screen) {
			currentSettingsScreen.clear();
		}
	}

	/** Notifies the live settings session even when a ConfirmScreen is on top. */
	public static void notifyServerDisconnected() {
		final SettingsScreen screen = currentSettingsScreen.get();
		if (screen != null) {
			screen.onServerDisconnected();
		}
	}

	/** Routes a delayed response to the latest live settings session. */
	public static void notifyServerConfigSnapshot(long requestId, ServerConfigSnapshot snapshot) {
		final SettingsScreen screen = currentSettingsScreen.get();
		if (screen != null) {
			screen.onServerConfigSnapshot(requestId, snapshot);
		}
	}

	public SettingsScreen() {
		super(null, null, LanguageUtils.settings("title").get());
		this.config = ClientConfig.HANDLER.getConfig();
	}

	public SettingsScreen(Screen parent) {
		this();
		this.parent = parent;
	}

	@Override
	public void tick() {
		this.updatePermissionState();

		if (this.channelTextField != null
			&& this.channelTextField.isFocused()
			&& this.getFocused() != this.channelTextField) {
			this.setFocused(this.channelTextField);
		}
	}

	@Override
	protected void init() {
		registerCurrent(this);
		this.addTitle();
		this.updatePermissionState();
		this.addContents();
		this.addFooter();
		this.addResetAllButton();
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void addContents() {
		this.list = this.layout.addToContents(new SettingsOptionsList(this.minecraft, this.width, this));
		this.addOptions();
	}

	@Override
	protected void addOptions() {
		this.list.addSmall(this.createCategoryHeader(
			LanguageUtils.settings("client_settings").get(),
			false,
			() -> {}),
			null);

		this.addChannelRow();
		this.addBlockShapeBlacklistButton();
		this.list.addSmall(getEntityBlockRenderModeOption(), null);

		this.list.addSmall(getPingVolumeOption(), getPingDistanceOption());

		this.list.addSmall(getPassThroughTransparentBlocksOption(), getMarkBlacklistedTargetsOption());
		this.list.addSmall(getMarkFluidsOption(), null);

		this.list.addSmall(getItemIconsVisibleOption(), getDirectionIndicatorVisibleOption());

		this.list.addSmall(getPlayerInfoModeOption(), getTeamColorModeOption());

		this.list.addSmall(getPingSizeOption(), getWheelInnerRadiusOption());
		this.list.addSmall(getConfigurationNoticeSizeOption(), null);

		this.list.addSmall(getWheelOuterRadiusOption(), getWheelOpacityOption());

		this.list.addSmall(getWheelTargetFontSizeOption(), getWheelOptionFontSizeOption());

		this.list.addSmall(getWheelHoldMillisOption(), getWheelTimeoutMillisOption());

		this.list.addSmall(getLongPressCompatibilityModeOption(), getLongPressCompatibilitySliceMillisOption());

		this.list.addSmall(getCancelHalfConeAngleDegreesOption(), null);

		this.addServerOptions();
	}

	private void addResetAllButton() {
		this.resetAllButton = Button.builder(
			LanguageUtils.settings("reset_all").get(),
			button -> this.openResetConfirmation())
			.bounds(0, 0, SettingsScreenLayout.RESET_BUTTON_WIDTH, SettingsScreenLayout.RESET_BUTTON_HEIGHT)
			.build();
		this.addRenderableWidget(this.resetAllButton);
	}

	@Override
	public void onClose() {
		if (!this.suppressSaveOnClose && !this.persistSettings()) {
			return;
		}

		this.leaveSettingsScreen();
	}

	private boolean persistSettings() {
		// Client options are local and must be persisted even when an invalid
		// server draft keeps this screen open.
		return ClientConfig.HANDLER.saveSafely() && this.commitServerSettings();
	}

	private void leaveSettingsScreen() {
		if (parent != null && this.minecraft != null) {
			clearCurrent(this);
			this.minecraft.setScreen(parent);
		} else {
			clearCurrent(this);
			super.onClose();
		}
	}

	@Override
	public void repositionElements() {
		this.layout.setFooterHeight(SettingsScreenLayout.footerHeightFor(this.height, this.layout.getHeaderHeight()));
		super.repositionElements();
		this.list.updateSize(this.width, this.layout);

		final var screenLayout = this.getScreenLayout();
		this.resetAllButton.setPosition(screenLayout.resetX(), screenLayout.resetY());
	}

	@Override
	public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		this.list.render(ctx, mouseX, mouseY, delta);

	}

	private SettingsScreenLayout getScreenLayout() {
		return SettingsScreenLayout.calculate(
			this.width,
			this.height,
			this.layout.getHeaderHeight(),
			this.layout.getFooterHeight()
		);
	}

	private MutableComponent getChannelPlaceholder() {
		if (Game.player == null) {
			return Component.empty();
		}

		final var teamContext = TeamContextHandler.getSelfContext();
		MutableComponent placeholder;

		if (teamContext == TeamContext.NONE) {
			placeholder = LanguageUtils.of("value", "global").get();
		} else {
			placeholder = LanguageUtils.settings("channel").path("placeholder")
				.get(LanguageUtils.of("value", teamContext.toString()).get());
		}

		return placeholder
			.withStyle(ChatFormatting.ITALIC)
			.withStyle(ChatFormatting.DARK_GRAY);
	}

	private OptionInstance<Integer> getPingVolumeOption() {
		final var text = LanguageUtils.settings("ping_volume");

		return OptionUtils.ofInt(
			text.getKey(),
			0, 100, 1,
			(value) -> {
				if (value == 0) {
					return text.get(CommonComponents.OPTION_OFF);
				}

				return text.get(LanguageUtils.UNIT_PERCENT.get(value));
			},
			config::getPingVolume,
			config::setPingVolume
		);
	}

	private OptionInstance<Integer> getPingDistanceOption() {
		final var text = LanguageUtils.settings("ping_distance");

		return OptionUtils.ofInt(
			text.getKey(),
			0, MAX_PING_DISTANCE, 16,
			(value) -> {
				if (value == 0) {
					return text.get(LanguageUtils.VALUE_HIDDEN);
				} else if (value >= MAX_PING_DISTANCE) {
					return text.get(LanguageUtils.VALUE_INFINITE);
				}

				return text.get(LanguageUtils.UNIT_METERS.get(value));
			},
			config::getPingDistance,
			config::setPingDistance
		);
	}

	private OptionInstance<Boolean> getItemIconsVisibleOption() {
		return OptionUtils.ofBool(
			LanguageUtils.settings("item_icon_visible").getKey(),
			config::isItemIconVisible,
			config::setItemIconVisible
		);
	}

	private OptionInstance<Boolean> getPassThroughTransparentBlocksOption() {
		return OptionUtils.ofBool(
			LanguageUtils.settings("pass_through_transparent_blocks").getKey(),
			config::isPassThroughTransparentBlocks,
			config::setPassThroughTransparentBlocks
		);
	}

	private OptionInstance<Boolean> getMarkBlacklistedTargetsOption() {
		return OptionUtils.ofBool(
			LanguageUtils.settings("mark_blacklisted_targets").getKey(),
			config::isMarkBlacklistedTargets,
			config::setMarkBlacklistedTargets
		);
	}

	private OptionInstance<Boolean> getMarkFluidsOption() {
		return OptionUtils.ofBool(
			LanguageUtils.settings("mark_fluids").getKey(),
			config::isMarkFluids,
			config::setMarkFluids
		);
	}

	private OptionInstance<Boolean> getDirectionIndicatorVisibleOption() {
		return OptionUtils.ofBool(
			LanguageUtils.settings("direction_indicator_visible").getKey(),
			config::isDirectionIndicatorVisible,
			config::setDirectionIndicatorVisible
		);
	}

	private OptionInstance<PlayerInfoMode> getPlayerInfoModeOption() {
		return OptionUtils.ofEnum(
			LanguageUtils.settings("player_info_mode").getKey(),
			PlayerInfoMode.class,
			(mode) -> LanguageUtils.of("value", mode.toString()).get(),
			(mode) -> {
				if (mode != PlayerInfoMode.HOLD) return Component.empty();

				final var kayPlayerListTitle = Component.translatable(Game.options.keyPlayerList.getName());
				final var kayPlayerListName = Game.options.keyPlayerList.getTranslatedKeyMessage();

				return LanguageUtils.settings("player_info_mode")
					.path("hold", "tooltip")
					.get(kayPlayerListTitle, kayPlayerListName);
			},
			config::getPlayerInfoMode,
			config::setPlayerInfoMode
		);
	}

	private OptionInstance<TeamColorMode> getTeamColorModeOption() {
		return OptionUtils.ofEnum(
			LanguageUtils.settings("team_color_mode").getKey(),
			TeamColorMode.class,
			(mode) -> LanguageUtils.of("value", mode.toString()).get(),
			(mode) -> Component.empty(),
			config::getTeamColorMode,
			config::setTeamColorMode
		);
	}

	private OptionInstance<Integer> getConfigurationNoticeSizeOption() {
		final var text = LanguageUtils.settings("configuration_notice_size");

		return OptionUtils.ofInt(
			text.getKey(),
			MIN_CONFIGURATION_NOTICE_SIZE,
			MAX_CONFIGURATION_NOTICE_SIZE,
			CONFIGURATION_NOTICE_SIZE_STEP,
			(value) -> text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			config::getConfigurationNoticeSize,
			config::setConfigurationNoticeSize
		);
	}

	private OptionInstance<Integer> getPingSizeOption() {
		final var text = LanguageUtils.settings("ping_size");

		return OptionUtils.ofInt(
			text.getKey(),
			40, 300, 10,
			(value) -> text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			config::getPingSize,
			config::setPingSize
		);
	}

	private OptionInstance<Integer> getWheelHoldMillisOption() {
		final var text = LanguageUtils.settings("wheel_hold_millis");

		return OptionUtils.ofInt(
			text.getKey(),
			MIN_WHEEL_HOLD_MILLIS,
			MAX_WHEEL_HOLD_MILLIS,
			WHEEL_HOLD_MILLIS_STEP,
			(value) -> text.get(LanguageUtils.UNIT_MILLISECONDS.get(value)),
			config::getWheelHoldMillis,
			config::setWheelHoldMillis
		);
	}

	private OptionInstance<Boolean> getLongPressCompatibilityModeOption() {
		final var text = LanguageUtils.settings("long_press_compatibility_mode");

		return OptionUtils.ofBool(
			text.getKey(),
			config::isLongPressCompatibilityMode,
			config::setLongPressCompatibilityMode,
			() -> text.path("tooltip").get());
	}

	private OptionInstance<Integer> getLongPressCompatibilitySliceMillisOption() {
		final var text = LanguageUtils.settings("long_press_compatibility_slice_millis");
		final int maximum = effectiveLongPressCompatibilitySliceMaxMillis(config.getWheelHoldMillis());

		return OptionUtils.ofInt(
			text.getKey(),
			MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			maximum,
			LONG_PRESS_COMPATIBILITY_SLICE_MILLIS_STEP,
			(value) -> text.get(LanguageUtils.UNIT_MILLISECONDS.get(value)),
			() -> text.path("tooltip").get(),
			config::getLongPressCompatibilitySliceMillis,
			config::setLongPressCompatibilitySliceMillis
		);
	}

	private OptionInstance<Integer> getWheelInnerRadiusOption() {
		final var text = LanguageUtils.settings("wheel_inner_radius");

		return OptionUtils.ofInt(
			text.getKey(),
			MIN_WHEEL_INNER_RADIUS,
			MAX_WHEEL_INNER_RADIUS,
			WHEEL_INNER_RADIUS_STEP,
			(value) -> text.get(LanguageUtils.UNIT_PIXELS.get(value)),
			config::getWheelInnerRadius,
			config::setWheelInnerRadius
		);
	}

	private OptionInstance<Integer> getWheelOuterRadiusOption() {
		final var text = LanguageUtils.settings("wheel_outer_radius");

		return OptionUtils.ofInt(
			text.getKey(),
			MIN_WHEEL_OUTER_RADIUS,
			MAX_WHEEL_OUTER_RADIUS,
			WHEEL_OUTER_RADIUS_STEP,
			(value) -> text.get(LanguageUtils.UNIT_PIXELS.get(value)),
			config::getWheelOuterRadius,
			config::setWheelOuterRadius
		);
	}

	private OptionInstance<Integer> getWheelOpacityOption() {
		final var text = LanguageUtils.settings("wheel_opacity");

		return OptionUtils.ofInt(
			text.getKey(),
			MIN_WHEEL_OPACITY,
			MAX_WHEEL_OPACITY,
			WHEEL_OPACITY_STEP,
			(value) -> value == 0
				? text.get(CommonComponents.OPTION_OFF)
				: text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			config::getWheelOpacity,
			config::setWheelOpacity
		);
	}

	private OptionInstance<Integer> getWheelOptionFontSizeOption() {
		final var text = LanguageUtils.settings("wheel_font_size");

		return OptionUtils.ofInt(
			text.getKey(),
			MIN_WHEEL_FONT_SIZE,
			MAX_WHEEL_FONT_SIZE,
			WHEEL_FONT_SIZE_STEP,
			(value) -> text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			config::getWheelFontSize,
			config::setWheelFontSize
		);
	}

	private OptionInstance<Integer> getWheelTargetFontSizeOption() {
		final var text = LanguageUtils.settings("wheel_target_font_size");

		return OptionUtils.ofInt(
			text.getKey(),
			MIN_WHEEL_TARGET_FONT_SIZE,
			MAX_WHEEL_TARGET_FONT_SIZE,
			WHEEL_TARGET_FONT_SIZE_STEP,
			(value) -> text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			config::getWheelTargetFontSize,
			config::setWheelTargetFontSize
		);
	}

	private OptionInstance<Integer> getWheelTimeoutMillisOption() {
		final var text = LanguageUtils.settings("wheel_timeout_millis");

		return OptionUtils.ofInt(
			text.getKey(),
			MIN_WHEEL_TIMEOUT_MILLIS,
			MAX_WHEEL_TIMEOUT_MILLIS,
			WHEEL_TIMEOUT_MILLIS_STEP,
			(value) -> text.get(LanguageUtils.UNIT_MILLISECONDS.get(value)),
			config::getWheelTimeoutMillis,
			config::setWheelTimeoutMillis
		);
	}

	private OptionInstance<Integer> getCancelHalfConeAngleDegreesOption() {
		final var text = LanguageUtils.settings("cancel_half_cone_angle_degrees");

		return OptionUtils.ofInt(
			text.getKey(),
			MIN_CANCEL_HALF_CONE_ANGLE_DEGREES,
			MAX_CANCEL_HALF_CONE_ANGLE_DEGREES,
			CANCEL_HALF_CONE_ANGLE_DEGREES_STEP,
			(value) -> text.get(LanguageUtils.UNIT_DEGREES.get(value)),
			config::getCancelHalfConeAngleDegrees,
			config::setCancelHalfConeAngleDegrees
		);
	}

	private OptionInstance<EntityBlockRenderMode> getEntityBlockRenderModeOption() {
		final var text = LanguageUtils.settings("entity_block_render_mode");

		return OptionUtils.ofEnum(
			text.getKey(),
			EntityBlockRenderMode.class,
			(mode) -> LanguageUtils.of("value", mode.toString()).get(),
			(mode) -> text.path(mode.toString(), "tooltip").get(),
			config::getEntityBlockRenderMode,
			config::setEntityBlockRenderMode
		);
	}

	private void addChannelRow() {
		final var label = new StringWidget(
			0,
			0,
			SettingsScreenLayout.SMALL_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			LanguageUtils.settings("channel").get(),
			this.font);

		this.channelTextField = new EditBox(
			this.font,
			-1,
			-1,
			SettingsScreenLayout.SMALL_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			Component.empty());
		this.channelTextField.setMaxLength(MAX_CHANNEL_LENGTH);
		this.channelTextField.setValue(config.getChannel());
		this.channelTextField.setHint(this.getChannelPlaceholder());
		this.channelTextField.setTooltip(Tooltip.create(LanguageUtils.settings("channel.tooltip").get()));
		this.channelTextField.setResponder(config::setChannel);
		this.list.addSmall(label, this.channelTextField);
	}

	private void addBlockShapeBlacklistButton() {
		Button button = Button.builder(
			LanguageUtils.settings("open_block_shape_blacklist_config").get(),
			ignored -> this.openBlockShapeBlacklistConfig())
			.bounds(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		button.setTooltip(Tooltip.create(
			LanguageUtils.settings("open_block_shape_blacklist_config").path("tooltip").get()));
		this.list.addSmall(button, null);
	}

	private void openBlockShapeBlacklistConfig() {
		if (this.minecraft == null || !this.persistSettings()) {
			return;
		}

		// Prevent any later lifecycle callback on this old screen from saving its
		// stale OptionInstances over edits made by the external editor.
		this.suppressSaveOnClose = true;
		this.leaveSettingsScreen();

		try {
			Util.getPlatform().openFile(ClientConfig.HANDLER.getConfigPath().toFile());
		} catch (Exception | LinkageError failure) {
			warnException("opening client config file failed", failure);
		}
	}

	private void addServerOptions() {
		this.serverValidationWidget = null;
		final boolean serverPermission = this.serverSettings.clientPermission()
			&& !this.serverSettings.accessDenied()
			&& (this.serverSettings.authoritative() == null || this.serverSettings.authoritative().canEdit());
		this.list.addSmall(
			this.createCategoryHeader(this.serverHeaderText(), serverPermission, this::onServerHeaderClicked),
			null);

		if (!this.serverSettings.expanded() || !this.serverSettings.canEdit()) {
			return;
		}

		final var mode = this.createServerValueButton(
			"default_channel_mode",
			() -> LanguageUtils.of("value", this.serverSettings.defaultChannelMode().toString()).get(),
			this.serverSettings::cycleDefaultChannelMode);
		final var tracking = this.createServerValueButton(
			"player_tracking_enabled",
			() -> LanguageUtils.of("value", this.serverSettings.playerTrackingEnabled() ? "enabled" : "disabled").get(),
			this.serverSettings::togglePlayerTracking);
		this.list.addSmall(mode, tracking);

		final var serverMsToRegenerateField = this.createServerIntegerField(
			this.serverSettings.msToRegenerateText(),
			this.serverSettings::setMsToRegenerateText,
			"ms_to_regenerate.tooltip");
		final var serverRateLimitField = this.createServerIntegerField(
			this.serverSettings.rateLimitText(),
			this.serverSettings::setRateLimitText,
			"rate_limit.tooltip");
		this.list.addSmall(
			this.createServerLabel("ms_to_regenerate", "ms_to_regenerate.tooltip"),
			serverMsToRegenerateField);
		this.list.addSmall(
			this.createServerLabel("rate_limit", "rate_limit.tooltip"),
			serverRateLimitField);

		if (this.serverValidationMessage != null) {
			this.serverValidationWidget = this.createServerValidationLabel();
			this.list.addSmall(this.serverValidationWidget, null);
		}
	}

	private Button createCategoryHeader(Component text, boolean active, Runnable action) {
		final var button = Button.builder(text, ignored -> action.run())
			.bounds(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		button.active = active;
		return button;
	}

	private MutableComponent serverHeaderText() {
		final var text = LanguageUtils.settings("server_settings").get();
		if (!this.serverSettings.clientPermission()
			|| this.serverSettings.accessDenied()
			|| (this.serverSettings.authoritative() != null && !this.serverSettings.authoritative().canEdit())) {
			return text.append(" ").append(LanguageUtils.settings("server_settings.locked").get());
		}
		if (this.serverSettings.loading()) {
			return text.append(" ").append(LanguageUtils.settings("server_settings.loading").get());
		}
		return text;
	}

	private void onServerHeaderClicked() {
		if (!this.serverSettings.clientPermission() || this.serverSettings.accessDenied()) {
			return;
		}

		if (this.serverSettings.expanded()) {
			if (this.serverSettings.loading()) {
				this.collapseServerSettings();
				return;
			}

			if (this.serverSettings.dirty()) {
				this.openServerCollapseConfirmation();
				return;
			}

			this.collapseServerSettings();
			return;
		}

		final long requestId = this.serverSettings.beginExpansion();
		if (requestId > 0L) {
			this.serverValidationMessage = null;
			this.rebuildSettingsList();
			IPlatformNetworkService.INSTANCE.sendToServer(new ServerConfigRequestC2SPacket(requestId));
		}
	}

	private void collapseServerSettings() {
		this.serverSettings.collapseAndDiscard();
		this.serverValidationMessage = null;
		this.rebuildSettingsList();
	}

	private Button createServerValueButton(String key, Supplier<Component> value, Runnable action) {
		final var label = LanguageUtils.settings(key).get();
		final var button = Button.builder(
			this.serverRowText(label, value.get()),
			clicked -> {
				action.run();
				clicked.setMessage(this.serverRowText(label, value.get()));
			})
			.bounds(0, 0, SettingsScreenLayout.SMALL_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		button.setTooltip(Tooltip.create(LanguageUtils.settings(key).path("tooltip").get()));
		return button;
	}

	private StringWidget createServerLabel(String key, String tooltipKey) {
		final var label = new StringWidget(
			0,
			0,
			SettingsScreenLayout.SMALL_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			LanguageUtils.settings(key).get(),
			this.font);
		label.setTooltip(Tooltip.create(LanguageUtils.settings(tooltipKey).get()));
		return label;
	}

	private StringWidget createServerValidationLabel() {
		final var label = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			this.serverValidationMessage.copy().withStyle(ChatFormatting.RED),
			this.font);
		return label;
	}

	private EditBox createServerIntegerField(String value, Consumer<String> responder, String tooltipKey) {
		final var field = new EditBox(
			this.font,
			-1,
			-1,
			SettingsScreenLayout.SMALL_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			Component.empty());
		field.setMaxLength(Integer.toString(Integer.MAX_VALUE).length());
		field.setFilter(text -> text.isEmpty()
			|| text.chars().allMatch(character -> character >= '0' && character <= '9'));
		field.setValue(value);
		field.setTooltip(Tooltip.create(LanguageUtils.settings(tooltipKey).get()));
		field.setResponder(text -> {
			if (this.serverValidationMessage != null) {
				this.serverValidationMessage = null;
				if (this.serverValidationWidget != null) {
					this.serverValidationWidget.setMessage(Component.empty());
				}
			}
			responder.accept(text);
		});
		return field;
	}

	private MutableComponent serverRowText(Component label, Component value) {
		return Component.empty().append(label).append(": ").append(value);
	}

	private void updatePermissionState() {
		final boolean permission = this.hasLiveServerConnection()
			&& this.minecraft.player.hasPermissions(3);
		if (permission == this.serverSettings.clientPermission()) {
			return;
		}

		this.serverSettings.setClientPermission(permission);
		this.serverValidationMessage = null;
		this.rebuildSettingsList();
	}

	public void onServerConfigSnapshot(long requestId, ServerConfigSnapshot snapshot) {
		if (!this.serverSettings.applySnapshot(requestId, snapshot)) {
			return;
		}
		this.serverValidationMessage = null;
		this.rebuildSettingsList();
	}

	public void onServerDisconnected() {
		this.serverSettings.resetForDisconnect();
		this.serverValidationMessage = null;
		this.rebuildSettingsList();
	}

	private void rebuildSettingsList() {
		if (this.list == null) {
			return;
		}

		((SettingsOptionsList) this.list).resetEntries();
		this.addOptions();
		this.list.updateSize(this.width, this.layout);
	}

	private boolean commitServerSettings() {
		if (!this.hasLiveServerConnection()) {
			this.serverSettings.resetForDisconnect();
			this.serverValidationMessage = null;
			return true;
		}

		if (!this.serverSettings.dirty()) {
			return true;
		}

		if (this.serverSettings.hasInvalidDraft()) {
			this.serverValidationMessage = LanguageUtils.settings("server_settings.validation").get();
			this.rebuildSettingsList();
			return false;
		}

		final var update = this.serverSettings.updatePlan();
		if (update.isEmpty()) {
			return true;
		}
		if (!this.hasLiveServerConnection()) {
			this.serverSettings.resetForDisconnect();
			this.serverValidationMessage = null;
			return true;
		}

		final var values = update.orElseThrow();
		this.serverSettings.markClean();
		IPlatformNetworkService.INSTANCE.sendToServer(new ServerConfigUpdateC2SPacket(
			values.changedFields(),
			values.defaultChannelMode(),
			values.playerTrackingEnabled(),
			values.msToRegenerate(),
			values.rateLimit()));
		return true;
	}

	private boolean hasLiveServerConnection() {
		return this.minecraft != null
			&& this.minecraft.level != null
			&& this.minecraft.player != null
			&& this.minecraft.getConnection() != null;
	}

	private void openServerCollapseConfirmation() {
		if (this.minecraft == null) {
			return;
		}

		this.serverCollapseConfirmationHandled = false;
		this.minecraft.setScreen(new ConfirmScreen(
			this::handleServerCollapseConfirmation,
			LanguageUtils.settings("server_settings").path("confirm", "title").get(),
			LanguageUtils.settings("server_settings").path("confirm", "message").get(),
			LanguageUtils.settings("server_settings").path("confirm", "discard").get(),
			LanguageUtils.settings("server_settings").path("confirm", "cancel").get()));
	}

	private void handleServerCollapseConfirmation(boolean confirmed) {
		if (this.serverCollapseConfirmationHandled) {
			return;
		}

		this.serverCollapseConfirmationHandled = true;
		if (this.minecraft == null) {
			this.serverSettings.resetForDisconnect();
			this.serverValidationMessage = null;
			return;
		}
		if (!this.hasLiveServerConnection()) {
			this.serverSettings.resetForDisconnect();
			this.serverValidationMessage = null;
		} else if (confirmed) {
			this.collapseServerSettings();
		}
		this.minecraft.setScreen(this);
	}

	private void openResetConfirmation() {
		if (this.minecraft == null) {
			return;
		}

		this.resetConfirmationHandled = false;
		this.minecraft.setScreen(new ConfirmScreen(
			confirmed -> this.handleResetConfirmation(confirmed),
			LanguageUtils.settings("reset_all").path("title").get(),
			LanguageUtils.settings("reset_all").path("message").get()));
	}

	private void handleResetConfirmation(boolean confirmed) {
		if (this.resetConfirmationHandled || this.minecraft == null) {
			return;
		}

		this.resetConfirmationHandled = true;

		if (confirmed) {
			// A replacement settings screen is intentional: the old OptionInstances
			// retain their pre-reset values and must never save them back over the
			// freshly constructed defaults.
			this.suppressSaveOnClose = true;
			clearCurrent(this);
			ClientConfig.HANDLER.resetToDefaults();
			this.minecraft.setScreen(new SettingsScreen(this.parent));
		} else {
			this.minecraft.setScreen(this);
		}
	}

	private static final class SettingsOptionsList extends OptionsList {
		private SettingsOptionsList(net.minecraft.client.Minecraft minecraft, int width, SettingsScreen screen) {
			super(minecraft, width, screen);
		}

		private void resetEntries() {
			this.clearEntries();
		}
	}
}
