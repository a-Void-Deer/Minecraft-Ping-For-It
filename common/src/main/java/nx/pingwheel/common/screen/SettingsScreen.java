package nx.pingwheel.common.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.PlayerInfoMode;
import nx.pingwheel.common.config.TeamColorMode;
import nx.pingwheel.common.integration.TeamContext;
import nx.pingwheel.common.integration.TeamContextHandler;
import nx.pingwheel.common.resource.LanguageUtils;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.config.ClientConfig.*;
import static nx.pingwheel.common.config.ClientConfigBounds.*;

public class SettingsScreen extends OptionsSubScreen {

	private static final int WHITE = 0xFFFFFF;
	private static final int GRAY = 0xA0A0A0;

	private final ClientConfig config;

	private Screen parent;
	private EditBox channelTextField;

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
		if (this.channelTextField.isFocused() && this.getFocused() != this.channelTextField) {
			this.setFocused(this.channelTextField);
		}
	}

	@Override
	protected void init() {
		this.addTitle();
		this.addContents();
		this.addFooter();
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void addContents() {
		this.list = this.layout.addToContents(new OptionsList(this.minecraft, this.width, this));
		this.addOptions();
	}

	@Override
	protected void addOptions() {
		this.list.addSmall(getPingVolumeOption(), getPingDurationOption());

		this.list.addSmall(getPingDistanceOption(), getCorrectionPeriodOption());

		this.list.addSmall(getItemIconsVisibleOption(), getDirectionIndicatorVisibleOption());

		this.list.addSmall(getPlayerInfoModeOption(), getTeamColorModeOption());

		this.list.addSmall(getPingSizeOption(), null);

		this.list.addSmall(getWheelInnerRadiusOption(), getWheelOuterRadiusOption());

		this.list.addSmall(getWheelOpacityOption(), getWheelFontSizeOption());

		this.list.addSmall(getWheelHoldMillisOption(), getWheelTimeoutMillisOption());

		this.list.addSmall(getCancelHalfConeAngleDegreesOption(), null);

		this.channelTextField = new EditBox(this.font, -1, -1, 200, 20, Component.empty());
		this.channelTextField.setMaxLength(MAX_CHANNEL_LENGTH);
		this.channelTextField.setValue(config.getChannel());
		this.channelTextField.setResponder(config::setChannel);
		this.addWidget(this.channelTextField);
	}

	@Override
	public void onClose() {
		ClientConfig.HANDLER.save();

		if (parent != null && this.minecraft != null) {
			this.minecraft.setScreen(parent);
		} else {
			super.onClose();
		}
	}

	@Override
	public void repositionElements() {
		super.repositionElements();
		this.list.updateSize(this.width, this.layout);
	}

	@Override
	public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		this.list.render(ctx, mouseX, mouseY, delta);

		final var yOffset = 50 + 25 * this.list.children().size();
		this.channelTextField.setPosition(width / 2 - 100, yOffset);
		ctx.drawString(this.font, LanguageUtils.settings("channel").get(), this.width / 2 - 100, this.channelTextField.getY() - 12, GRAY);
		this.channelTextField.render(ctx, mouseX, mouseY, delta);

		if (this.channelTextField.getValue().isEmpty()) {
			ctx.drawString(this.font, getChannelPlaceholder(), this.width / 2 - 100 + 4, this.channelTextField.getY() + 6, WHITE);
		}

		if (this.channelTextField.isHoveredOrFocused() && !this.channelTextField.isFocused()) {
			ctx.renderTooltip(this.font, Tooltip.create(LanguageUtils.settings("channel.tooltip").get()).toCharSequence(Game), mouseX, mouseY);
		}
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

	private OptionInstance<Integer> getPingDurationOption() {
		final var text = LanguageUtils.settings("ping_duration");

		return OptionUtils.ofInt(
			text.getKey(),
			1, MAX_PING_DURATION, 1,
			(value) -> {
				if (value >= MAX_PING_DURATION) {
					return text.get(LanguageUtils.VALUE_INFINITE);
				}

				return text.get(LanguageUtils.UNIT_SECONDS.get(value));
			},
			config::getPingDuration,
			config::setPingDuration
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

	private OptionInstance<Float> getCorrectionPeriodOption() {
		final var text = LanguageUtils.settings("correction_period");

		return OptionUtils.ofFloat(
			text.getKey(),
			0.1f, MAX_CORRECTION_PERIOD, 0.1f,
			(value) -> {
				if (value >= MAX_CORRECTION_PERIOD) {
					return text.get(LanguageUtils.VALUE_INFINITE);
				}

				return text.get(LanguageUtils.UNIT_SECONDS.get("%.1f".formatted(value)));
			},
			config::getCorrectionPeriod,
			config::setCorrectionPeriod
		);
	}

	private OptionInstance<Boolean> getItemIconsVisibleOption() {
		return OptionUtils.ofBool(
			LanguageUtils.settings("item_icon_visible").getKey(),
			config::isItemIconVisible,
			config::setItemIconVisible
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

	private OptionInstance<Integer> getWheelFontSizeOption() {
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
}
