package nx.pingwheel.common.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.config.PlayerInfoMode;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.config.TeamColorMode;
import nx.pingwheel.common.integration.TeamContext;
import nx.pingwheel.common.integration.TeamContextHandler;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Operation;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;
import nx.pingwheel.common.presentation.client.ServerPresentationPolicyState;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.screen.SettingsCategoryCatalog.Setting;
import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import nx.pingwheel.common.screen.SettingsNavigationModel.Page;
import nx.pingwheel.common.screen.SettingsNavigationModel.Scope;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.config.ClientConfig.*;
import static nx.pingwheel.common.config.ClientConfigBounds.*;
import static nx.pingwheel.common.Global.warnException;

public class SettingsScreen extends OptionsSubScreen {
	private static final long NO_REQUEST = -1L;
	private static WeakReference<SettingsScreen> currentSettingsScreen = new WeakReference<>(null);

	private final ClientConfig config;
	private final ServerSettingsModel serverSettings = new ServerSettingsModel(false);
	private final SettingsNavigationModel navigation = new SettingsNavigationModel();
	private final Map<String, AbstractWidget> keyedWidgets = new HashMap<>();
	private final Map<Scope, ScopeTab> scopeTabs = new EnumMap<>(Scope.class);
	private final TabManager tabManager;
	private final DeferredActionCoordinator inputActions = new DeferredActionCoordinator();

	private Screen parent;
	private SettingsOptionsList settingsList;
	private TabNavigationBar scopeTabBar;
	private Button primaryButton;
	private Button resetAllButton;
	private EditBox channelTextField;
	private EditBox serverMsToRegenerateField;
	private EditBox serverRateLimitField;
	private EditBox serverSyncDurationField;
	private SettingsTitleWidget pageTitle;
	private StringWidget serverStatusWidget;
	private AbstractWidget pendingHalfWidth;
	private boolean serverRequestDeferred;
	private boolean tabBarRegistered;
	private boolean syncingScopeTab;
	private boolean resetConfirmationHandled;
	private boolean suppressSaveOnClose;
	private MutableComponent serverValidationMessage;

	private final ServerPresentationPolicyState.ChangeListener presentationPolicyListener =
		this::onPresentationPolicyChanged;
	private final PresentationSelectorDraftModel presentationDrafts = new PresentationSelectorDraftModel();
	private final Map<PresentationSelectorDraftModel.Slot, StringWidget> presentationSlotFeedbackWidgets =
		new EnumMap<>(PresentationSelectorDraftModel.Slot.class);
	private final Map<PresentationSelectorDraftModel.Slot, PresentationSelectorEditBox> presentationSelectorFields =
		new EnumMap<>(PresentationSelectorDraftModel.Slot.class);
	private ServerPresentationPolicyState presentationPolicyState;
	private Operation presentationPendingOperation;
	private MutableComponent presentationGeneralFeedback;
	private StringWidget presentationGeneralFeedbackWidget;

	private static void registerCurrent(SettingsScreen screen) {
		currentSettingsScreen = new WeakReference<>(screen);
	}

	private static void clearCurrent(SettingsScreen screen) {
		if (currentSettingsScreen.get() == screen) {
			currentSettingsScreen.clear();
		}
	}

	/** Notifies the live settings session even when a confirmation screen is on top. */
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
		this.tabManager = new NavigationTabManager(this::addRenderableWidget, this::removeWidget);
	}

	public SettingsScreen(Screen parent) {
		this();
		this.parent = parent;
	}

	@Override
	protected void init() {
		registerCurrent(this);
		this.presentationPolicyState = CommonClient.INSTANCE.getServerPresentationPolicyState();
		this.presentationPolicyState.addListener(this.presentationPolicyListener);
		this.updatePermissionState(false);
		this.createScopeTabs();
		this.addRenderableWidget(this.scopeTabBar);
		this.tabBarRegistered = true;

		this.pageTitle = new SettingsTitleWidget(this.font);
		this.pageTitle.setMessage(this.currentPageTitle());
		this.addRenderableOnly(this.pageTitle);

		this.settingsList = new SettingsOptionsList(this.minecraft, this.width, this);
		this.list = this.settingsList;
		this.addRenderableWidget(this.settingsList);

		this.resetAllButton = Button.builder(
			LanguageUtils.settings("reset_all").get(),
			ignored -> this.openResetConfirmation())
			.bounds(0, 0, SettingsScreenLayout.RESET_BUTTON_WIDTH, SettingsScreenLayout.RESET_BUTTON_HEIGHT)
			.build();
		this.addRenderableWidget(this.resetAllButton);

		this.primaryButton = Button.builder(this.primaryButtonText(), ignored -> this.onPrimaryButton())
			.bounds(0, 0, SettingsScreenLayout.PRIMARY_BUTTON_WIDTH, SettingsScreenLayout.PRIMARY_BUTTON_HEIGHT)
			.build();
		this.addRenderableWidget(this.primaryButton);

		this.rebuildPage(false);
	}

	@Override
	protected void addOptions() {
		// Pages are built by rebuildPage so navigation can retain viewport state.
	}

	@Override
	public void tick() {
		this.updatePermissionState(true);
		if (this.serverRequestDeferred) {
			this.serverRequestDeferred = false;
			this.requestServerSettingsIfNeeded();
			if (navigation.scope() == Scope.SERVER) {
				this.rebuildPage(false);
			}
		}
		if (this.serverStatusWidget != null) {
			this.serverStatusWidget.setMessage(
				this.serverStatusText().withStyle(this.serverSettings.canEdit() ? ChatFormatting.GRAY : ChatFormatting.YELLOW));
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return this.inputActions.dispatch(() -> this.dispatchKeyPressed(keyCode, scanCode, modifiers));
	}

	private boolean dispatchKeyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 256 && navigation.isLeaf()) {
			this.navigateBack();
			return true;
		}
		if (navigation.isOverview() && this.scopeTabBar != null && this.scopeTabBar.keyPressed(keyCode)) {
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return this.inputActions.dispatch(() -> super.mouseClicked(mouseX, mouseY, button));
	}

	@Override
	public List<? extends GuiEventListener> children() {
		final List<? extends GuiEventListener> children = super.children();
		if (!this.tabBarRegistered || this.scopeTabBar == null) {
			return children;
		}

		final var visualOrder = new ArrayList<GuiEventListener>(children.size());
		visualOrder.add(this.scopeTabBar);
		for (GuiEventListener child : children) {
			if (child != this.scopeTabBar) {
				visualOrder.add(child);
			}
		}
		return visualOrder;
	}

	@Override
	public void onClose() {
		this.saveCurrentViewState();
		this.inputActions.runAfterDispatch(this::performClose);
	}

	private void performClose() {
		if (navigation.isLeaf()) {
			this.performNavigateBack();
			return;
		}

		if (this.suppressSaveOnClose) {
			this.leaveSettingsScreen();
			return;
		}
		if (!this.persistSettings()) {
			return;
		}
		this.leaveSettingsScreen();
	}

	@Override
	public void removed() {
		if (this.presentationPolicyState != null) {
			this.presentationPolicyState.removeListener(this.presentationPolicyListener);
		}
		// ClientConfig owns persistence.  Do not invoke OptionsSubScreen's vanilla
		// options save for this custom screen.
	}

	@Override
	public void repositionElements() {
		if (this.settingsList == null || this.pageTitle == null) {
			return;
		}

		if (this.scopeTabBar != null) {
			this.scopeTabBar.setWidth(this.width);
			this.scopeTabBar.arrangeElements();
		}

		final int titleY = navigation.isOverview()
			? (this.scopeTabBar == null ? 4 : this.scopeTabBar.getRectangle().bottom() + 3)
			: 6;
		final int titleHeight = this.pageTitle.layout(
			this.width,
			titleY,
			this.font.lineHeight,
			this.font::width);
		this.layout.setHeaderHeight(SettingsScreenLayout.headerHeightFor(
			navigation.isLeaf(),
			titleY,
			titleHeight));
		this.layout.setFooterHeight(SettingsScreenLayout.footerHeightFor(
			this.height,
			this.layout.getHeaderHeight(),
			navigation.isLeaf()));

		this.settingsList.updateSizeAndPosition(
			this.width,
			Math.max(0, this.height - this.layout.getHeaderHeight() - this.layout.getFooterHeight()),
			this.layout.getHeaderHeight());
		this.tabManager.setTabArea(new ScreenRectangle(
			0,
			this.layout.getHeaderHeight(),
			this.width,
			Math.max(0, this.height - this.layout.getHeaderHeight() - this.layout.getFooterHeight())));

		final var screenLayout = this.getScreenLayout();
		this.primaryButton.setPosition(screenLayout.doneX(), screenLayout.doneY());
		this.resetAllButton.setPosition(screenLayout.resetX(), screenLayout.resetY());
	}

	@Override
	public void resize(net.minecraft.client.Minecraft minecraft, int width, int height) {
		this.saveCurrentViewState();
		super.resize(minecraft, width, height);
		this.restoreCurrentViewState();
	}

	private void createScopeTabs() {
		this.scopeTabs.clear();
		this.scopeTabs.put(Scope.CLIENT, new ScopeTab(Scope.CLIENT));
		this.scopeTabs.put(Scope.SERVER, new ScopeTab(Scope.SERVER));
		this.scopeTabBar = TabNavigationBar.builder(this.tabManager, this.width)
			.addTabs(this.scopeTabs.get(Scope.CLIENT), this.scopeTabs.get(Scope.SERVER))
			.build();
		this.scopeTabBar.setWidth(this.width);
		this.scopeTabBar.arrangeElements();

		this.syncingScopeTab = true;
		this.tabManager.setCurrentTab(this.scopeTabs.get(navigation.scope()), false);
		this.syncingScopeTab = false;
	}

	private void selectScopeFromTab(Scope scope) {
		if (this.syncingScopeTab || navigation.isLeaf() || scope == navigation.scope()) {
			return;
		}

		this.saveCurrentViewState();
		this.inputActions.runAfterDispatch(() -> {
			if (navigation.isLeaf() || scope == navigation.scope()) {
				return;
			}
			this.flushOptionWidgets();
			navigation.selectScope(scope);
			this.serverRequestDeferred = scope == Scope.SERVER;
			this.rebuildPage(false);
		});
	}

	private void syncSelectedScopeTab() {
		if (this.scopeTabBar == null || this.tabManager.getCurrentTab() == this.scopeTabs.get(navigation.scope())) {
			return;
		}
		this.syncingScopeTab = true;
		this.tabManager.setCurrentTab(this.scopeTabs.get(navigation.scope()), false);
		this.syncingScopeTab = false;
	}

	private void openCategory(Category category) {
		if (category.scope() != navigation.scope() || !navigation.isOverview()) {
			return;
		}
		this.saveCurrentViewState(category.id());
		this.inputActions.runAfterDispatch(() -> {
			if (category.scope() != navigation.scope() || !navigation.isOverview()) {
				return;
			}
			this.flushOptionWidgets();
			if (navigation.openCategory(category)) {
				this.rebuildPage(false);
			}
		});
	}

	private void navigateBack() {
		this.saveCurrentViewState();
		this.inputActions.runAfterDispatch(this::performNavigateBack);
	}

	private void performNavigateBack() {
		this.flushOptionWidgets();
		if (navigation.back()) {
			this.rebuildPage(false);
		}
	}

	private void rebuildPage(boolean saveCurrentState) {
		if (this.settingsList == null) {
			return;
		}
		if (saveCurrentState) {
			this.saveCurrentViewState();
		}
		this.flushOptionWidgets();
		// Capture the live caret/selection before the widgets are cleared; the
		// pure draft model owns the values until the fields are recreated.
		this.savePresentationSelectorSelections();

		this.clearFocus();
		this.settingsList.setFocused((GuiEventListener) null);
		this.settingsList.resetEntries();
		this.keyedWidgets.clear();
		this.channelTextField = null;
		this.serverMsToRegenerateField = null;
		this.serverRateLimitField = null;
		this.serverSyncDurationField = null;
		this.serverStatusWidget = null;
		this.presentationGeneralFeedbackWidget = null;
		this.presentationSlotFeedbackWidgets.clear();
		this.presentationSelectorFields.clear();
		this.pendingHalfWidth = null;
		if (navigation.scope() == Scope.SERVER && !this.serverRequestDeferred) {
			this.requestServerSettingsIfNeeded();
		}

		if (navigation.isOverview()) {
			this.addOverview(navigation.scope());
		} else {
			this.addCategoryPage(navigation.current().category().orElseThrow());
		}
		this.flushPendingHalfWidthRow();

		this.updateChrome();
		this.repositionElements();
		this.restoreCurrentViewState();
	}

	private void updateChrome() {
		final boolean overview = navigation.isOverview();
		if (overview && !this.tabBarRegistered) {
			this.addRenderableWidget(this.scopeTabBar);
			this.tabBarRegistered = true;
		} else if (!overview && this.tabBarRegistered) {
			this.removeWidget(this.scopeTabBar);
			this.tabBarRegistered = false;
		}

		if (overview) {
			this.syncSelectedScopeTab();
		}
		this.pageTitle.setMessage(this.currentPageTitle());
		this.primaryButton.setMessage(this.primaryButtonText());
		this.resetAllButton.visible = overview && navigation.scope() == Scope.CLIENT;
		this.resetAllButton.active = this.resetAllButton.visible;
	}

	private void addOverview(Scope scope) {
		if (scope == Scope.SERVER) {
			this.addServerStatus();
		}

		final var categories = SettingsNavigationModel.categories(scope);
		for (int index = 0; index < categories.size(); index++) {
			final Category category = categories.get(index);
			final var button = Button.builder(
				LanguageUtils.settings("category").path(category.id(), "button").get(),
				ignored -> this.openCategory(category))
				.bounds(0, 0, SettingsScreenLayout.SMALL_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
				.build();
			if (index == categories.size() - 1 && categories.size() % 2 != 0) {
				this.addFullWidth(category.id(), button);
			} else {
				this.addHalfWidth(category.id(), button);
			}
		}
	}

	private void addCategoryPage(Category category) {
		if (category.scope() == Scope.SERVER) {
			this.addServerStatus();
			if (!serverSettings.canView()) {
				return;
			}
		}

		if (category == Category.PRESENTATION) {
			this.addPresentationPage();
			return;
		}

		if (category == Category.MARKER_DISPLAY) {
			this.addSubgroup("marker_display");
		} else if (category == Category.INPUT_INTERACTION) {
			this.addSubgroup("input_timing");
		} else if (category == Category.GEOMETRY_CONFIG) {
			this.addSubgroup("geometry");
		} else if (category == Category.CHANNEL_PLAYERS) {
			this.addSubgroup("channel_players");
		} else if (category == Category.SEND_RATE) {
			this.addSubgroup("send_rate");
		} else if (category == Category.MARKER_DURATION) {
			this.addSubgroup("marker_duration");
		}

		for (Setting setting : SettingsCategoryCatalog.settings(category)) {
			this.addSetting(category, setting);
		}
		this.flushPendingHalfWidthRow();
		if (category.scope() == Scope.SERVER && this.serverValidationMessage != null) {
			this.addFullWidth("server_validation", this.createServerValidationLabel());
		}
	}

	private void addSetting(Category category, Setting setting) {
		switch (setting) {
			case PING_DISTANCE -> this.addOption(setting, this.getPingDistanceOption(), false);
			case MARKER_DISPLAY_DURATION -> this.addOption(setting, this.getMarkerDisplayDurationOption(), true);
			case PING_SIZE -> this.addOption(setting, this.getPingSizeOption(), false);
			case ITEM_ICON_VISIBLE -> this.addOption(setting, this.getItemIconsVisibleOption(), false);
			case DIRECTION_INDICATOR_VISIBLE -> this.addOption(setting, this.getDirectionIndicatorVisibleOption(), false);
			case PLAYER_INFO_MODE -> this.addOption(setting, this.getPlayerInfoModeOption(), false);
			case TEAM_COLOR_MODE -> this.addOption(setting, this.getTeamColorModeOption(), false);
			case PASS_THROUGH_TRANSPARENT_BLOCKS -> this.addOption(setting, this.getPassThroughTransparentBlocksOption(), true);
			case MARK_BLACKLISTED_TARGETS -> this.addOption(setting, this.getMarkBlacklistedTargetsOption(), true);
			case MARK_FLUIDS -> this.addOption(setting, this.getMarkFluidsOption(), false);
			case WHEEL_INNER_RADIUS -> this.addOption(setting, this.getWheelInnerRadiusOption(), false);
			case WHEEL_OUTER_RADIUS -> this.addOption(setting, this.getWheelOuterRadiusOption(), false);
			case WHEEL_OPACITY -> this.addOption(setting, this.getWheelOpacityOption(), false);
			case WHEEL_TARGET_FONT_SIZE -> this.addOption(setting, this.getWheelTargetFontSizeOption(), false);
			case WHEEL_OPTION_FONT_SIZE -> this.addOption(setting, this.getWheelOptionFontSizeOption(), false);
			case WHEEL_HOLD_MILLIS -> this.addOption(setting, this.getWheelHoldMillisOption(), false);
			case WHEEL_TIMEOUT_MILLIS -> this.addOption(setting, this.getWheelTimeoutMillisOption(), false);
			case LONG_PRESS_COMPATIBILITY_MODE -> this.addOption(setting, this.getLongPressCompatibilityModeOption(), true);
			case LONG_PRESS_COMPATIBILITY_SLICE_MILLIS -> this.addOption(setting, this.getLongPressCompatibilitySliceMillisOption(), true);
			case CANCEL_HALF_CONE_ANGLE_DEGREES -> this.addOption(setting, this.getCancelHalfConeAngleDegreesOption(), true);
			case CHANNEL -> this.addChannelRow();
			case PING_VOLUME -> this.addOption(setting, this.getPingVolumeOption(), false);
			case CONFIGURATION_NOTICE_SIZE -> this.addOption(setting, this.getConfigurationNoticeSizeOption(), true);
			case ENTITY_BLOCK_RENDER_MODE -> this.addOption(setting, this.getEntityBlockRenderModeOption(), true);
			case OPEN_CLIENT_CONFIG -> {
				this.addSubgroup("client_config_file");
				this.addOpenClientConfigButton();
			}
			case DEFAULT_CHANNEL_MODE -> this.addServerChannelModeButton();
			case PLAYER_TRACKING_ENABLED -> this.addServerPlayerTrackingButton();
			case MS_TO_REGENERATE -> this.addServerIntegerRow(setting);
			case RATE_LIMIT -> this.addServerIntegerRow(setting);
			case SYNC_DURATION -> this.addServerIntegerRow(setting);
		}
	}

	private void addOption(Setting setting, OptionInstance<?> option, boolean fullWidth) {
		final int width = fullWidth ? SettingsScreenLayout.LARGE_WIDGET_WIDTH : SettingsScreenLayout.SMALL_WIDGET_WIDTH;
		final AbstractWidget widget = option.createButton(Game.options, 0, 0, width);
		if (fullWidth) {
			this.addFullWidth(setting.id(), widget);
		} else {
			this.addHalfWidth(setting.id(), widget);
		}
	}

	private void addHalfWidth(String key, AbstractWidget widget) {
		this.keyedWidgets.put(key, widget);
		if (this.pendingHalfWidth == null) {
			this.pendingHalfWidth = widget;
		} else {
			this.settingsList.addSmall(this.pendingHalfWidth, widget);
			this.pendingHalfWidth = null;
		}
	}

	private void addFullWidth(String key, AbstractWidget widget) {
		this.flushPendingHalfWidthRow();
		widget.setWidth(SettingsScreenLayout.LARGE_WIDGET_WIDTH);
		this.keyedWidgets.put(key, widget);
		this.settingsList.addSmall(widget, null);
	}

	private void flushPendingHalfWidthRow() {
		if (this.pendingHalfWidth != null) {
			this.settingsList.addSmall(this.pendingHalfWidth, null);
			this.pendingHalfWidth = null;
		}
	}

	private void addSubgroup(String key) {
		this.addHeading(LanguageUtils.settings("group").path(key).get());
	}

	private void addHeading(MutableComponent text) {
		this.flushPendingHalfWidthRow();
		final var heading = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			text.withStyle(ChatFormatting.BOLD),
			this.font).alignLeft();
		heading.active = false;
		this.settingsList.addSmall(heading, null);
	}

	private void addPresentationPage() {
		this.addHeading(LanguageUtils.settings("presentation").path("receive").get());
		this.addPresentationLocalPanel(config.getPresentationReceive(), "receive");
		this.addPresentationServerReadOnly("receive");

		this.addHeading(LanguageUtils.settings("presentation").path("display").get());
		this.addPresentationLocalPanel(config.getPresentationDisplay(), "display");
		this.addPresentationServerReadOnly("display");

		this.addHeading(LanguageUtils.settings("presentation").path("server", "title").get());
		this.addPresentationServerPanel();
	}

	private void addPresentationLocalPanel(PresentationSettings settings, String panelKey) {
		final var whitelistOnlyText = LanguageUtils.settings("presentation").path("whitelist_only");
		this.addPresentationBooleanRow(
			panelKey + "_whitelist_only",
			whitelistOnlyText,
			settings::isWhitelistOnly,
			settings::setWhitelistOnly);
		this.addPresentationLocalList(panelKey, settings, true);
		this.addPresentationLocalList(panelKey, settings, false);
	}

	private void addPresentationBooleanRow(
		String key,
		LanguageUtils text,
		Supplier<Boolean> getter,
		Consumer<Boolean> setter
	) {
		final var option = OptionUtils.ofBool(
			text.getKey(),
			getter,
			setter,
			() -> text.path("tooltip").get());
		final AbstractWidget widget = option.createButton(
			Game.options,
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH);
		this.addFullWidth(key, widget);
	}

	private void addPresentationLocalList(String panelKey, PresentationSettings settings, boolean toWhite) {
		final String listKey = panelKey + (toWhite ? "_white" : "_black");
		final PresentationSelectorDraftModel.Slot slot =
			PresentationSelectorDraftModel.Slot.local(panelKey, toWhite);
		this.addHeading(LanguageUtils.settings("presentation").path(toWhite ? "white" : "black").get());

		final List<String> selectors = PresentationSelectorListModel.copyOf(
			toWhite ? settings.getWhite() : settings.getBlack());
		if (selectors.isEmpty()) {
			this.addPresentationEmptyRow(listKey);
		}
		for (String selector : selectors) {
			this.addHalfWidth(listKey + "_label_" + selector, this.createPresentationSelectorLabel(selector));
			final var remove = Button.builder(
				LanguageUtils.settings("presentation").path("remove").get(),
				ignored -> this.removeLocalPresentationSelector(settings, slot, toWhite, selector))
				.bounds(0, 0, SettingsScreenLayout.SMALL_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
				.build();
			this.addHalfWidth(listKey + "_remove_" + selector, remove);
		}

		final PresentationSelectorEditBox field = this.createPresentationSelectorField(slot);
		final var add = Button.builder(
			LanguageUtils.settings("presentation").path("add").get(),
			ignored -> this.addLocalPresentationSelector(settings, slot, toWhite, field))
			.bounds(0, 0, SettingsScreenLayout.SMALL_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		this.restorePresentationSelectorSelection(slot, field);
		this.addHalfWidth(listKey + "_field", field);
		this.addHalfWidth(listKey + "_add", add);
		this.addPresentationSlotFeedbackRow(listKey, slot);
	}

	/**
	 * Builds one selector input restored from its stable draft slot. The initial
	 * value is applied before the responder is attached, so restoring a draft
	 * never records it as a fresh edit or clears the slot's feedback. The
	 * caret/selection is applied by the caller once the field's final
	 * editable state is known.
	 */
	private PresentationSelectorEditBox createPresentationSelectorField(PresentationSelectorDraftModel.Slot slot) {
		final var narration = LanguageUtils.settings("presentation").path("selector").get();
		final PresentationSelectorEditBox field = new PresentationSelectorEditBox(
			this.font,
			-1,
			-1,
			SettingsScreenLayout.SMALL_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			narration);
		field.setMaxLength(PresentationSelectorListModel.MAX_SELECTOR_LENGTH);
		field.setHint(narration);
		field.setValue(this.presentationDrafts.draft(slot));
		this.presentationSelectorFields.put(slot, field);
		field.setResponder(text -> {
			this.presentationDrafts.setDraft(slot, text);
			this.setPresentationSlotFeedback(slot, null);
		});
		return field;
	}

	private StringWidget createPresentationSelectorLabel(String selector) {
		final String display = this.font.plainSubstrByWidth(
			selector,
			SettingsScreenLayout.SMALL_WIDGET_WIDTH - 8);
		final boolean truncated = display.length() < selector.length();
		final var label = new StringWidget(
			0,
			0,
			SettingsScreenLayout.SMALL_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			Component.literal(truncated ? display + "..." : display),
			this.font).alignLeft();
		label.active = false;
		if (truncated) {
			label.setTooltip(Tooltip.create(Component.literal(selector)));
		}
		return label;
	}

	private void addPresentationEmptyRow(String key) {
		this.addFullWidth(key + "_empty", this.createPresentationReadOnlyRow(
			LanguageUtils.settings("presentation").path("empty").get()));
	}

	private StringWidget createPresentationReadOnlyRow(MutableComponent text) {
		final var row = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			text.copy().withStyle(ChatFormatting.GRAY),
			this.font).alignLeft();
		row.active = false;
		return row;
	}

	private void addPresentationServerReadOnly(String panelKey) {
		final String keyPrefix = panelKey + "_server_read_only";
		this.addHeading(LanguageUtils.settings("presentation").path("server", "title").get());
		this.addFullWidth(keyPrefix, this.createPresentationReadOnlyRow(
			LanguageUtils.settings("presentation").path("server", "read_only").get()));
		this.addPresentationServerStatusRows();
		if (!this.isPresentationPolicyKnown()) {
			this.addPresentationServerUnavailableRow(keyPrefix);
			return;
		}
		this.addPresentationServerSelectorLabels(keyPrefix, true);
		this.addPresentationServerSelectorLabels(keyPrefix, false);
		this.addFullWidth(keyPrefix + "_whitelist_only", this.createPresentationReadOnlyRow(
			this.presentationServerWhitelistOnlyText()));
	}

	private boolean isPresentationPolicyKnown() {
		final var state = this.presentationPolicyState;
		return state != null && state.isKnown();
	}

	/**
	 * The shared placeholder for a list whose authoritative state is not known
	 * yet. A known empty list is rendered as "(none)" by the caller; a missing
	 * route, a pending read, a timeout, and an error each keep their own wording
	 * instead of masquerading as an empty authoritative list.
	 */
	private void addPresentationServerUnavailableRow(String key) {
		this.addFullWidth(key + "_unavailable", this.createPresentationReadOnlyRow(
			this.presentationServerListStateText()));
	}

	private MutableComponent presentationServerListStateText() {
		final var state = this.presentationPolicyState;
		final var serverText = LanguageUtils.settings("presentation").path("server");
		if (state == null) {
			return serverText.path("unsupported").get();
		}
		return switch (state.viewStatus()) {
			case DISCONNECTED -> serverText.path("unsupported").get();
			case UNAVAILABLE -> serverText.path("unknown").get();
			case PENDING -> serverText.path(state.isKnown() ? "pending" : "loading").get();
			case TIMED_OUT -> serverText.path("timeout").get();
			case FAILED -> serverText.path("error").get();
			case READY -> serverText.path("ready").get();
		};
	}

	private void addPresentationServerSelectorLabels(String keyPrefix, boolean white) {
		final String listKey = keyPrefix + (white ? "_white" : "_black");
		this.addHeading(LanguageUtils.settings("presentation").path(white ? "white" : "black").get());
		final List<String> selectors = PresentationSelectorListModel.copyOf(
			white ? this.presentationPolicyState.white() : this.presentationPolicyState.black());
		if (selectors.isEmpty()) {
			this.addPresentationEmptyRow(listKey);
			return;
		}
		for (String selector : selectors) {
			this.addFullWidth(listKey + "_" + selector, this.createPresentationSelectorLabel(selector));
		}
	}

	private void addPresentationServerPanel() {
		this.addPresentationServerStatusRows();
		final var state = this.presentationPolicyState;
		if (state != null && state.isKnown() && !state.canEdit()) {
			this.addFullWidth("presentation_server_panel_read_only", this.createPresentationReadOnlyRow(
				LanguageUtils.settings("presentation").path("server", "read_only").get()));
		}

		this.addPresentationServerEditableList(true);
		this.addPresentationServerEditableList(false);

		final var whitelistOnlyButton = Button.builder(
			this.presentationServerWhitelistOnlyText(),
			ignored -> this.setServerWhitelistOnly(state != null && !state.whitelistOnly()))
			.bounds(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		whitelistOnlyButton.active = this.canMutateServerPresentation();
		this.addFullWidth("presentation_server_whitelist_only", whitelistOnlyButton);

		final var refresh = Button.builder(
			LanguageUtils.settings("presentation").path("refresh").get(),
			ignored -> this.refreshPresentationPolicy())
			.bounds(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		refresh.active = state != null
			&& state.isConnected()
			&& state.pendingRequestId() == ServerPresentationPolicyState.NO_PENDING_REQUEST;
		this.addFullWidth("presentation_server_refresh", refresh);
	}

	private void addPresentationServerEditableList(boolean white) {
		final String listKey = "server_" + (white ? "white" : "black");
		final PresentationSelectorDraftModel.Slot slot = PresentationSelectorDraftModel.Slot.server(white);
		this.addHeading(LanguageUtils.settings("presentation").path(white ? "white" : "black").get());

		final var state = this.presentationPolicyState;
		if (!this.isPresentationPolicyKnown()) {
			// An unknown route is not an empty authoritative list; a known list
			// stays visible through pending and error states, labelled above.
			this.addPresentationServerUnavailableRow(listKey);
		} else {
			final List<String> selectors = PresentationSelectorListModel.copyOf(
				white ? state.white() : state.black());
			if (selectors.isEmpty()) {
				this.addPresentationEmptyRow(listKey);
			}

			final boolean editable = this.canMutateServerPresentation();
			for (String selector : selectors) {
				this.addHalfWidth(listKey + "_label_" + selector, this.createPresentationSelectorLabel(selector));
				final var remove = Button.builder(
					LanguageUtils.settings("presentation").path("remove").get(),
					ignored -> this.removeServerPresentationSelector(white, selector))
					.bounds(0, 0, SettingsScreenLayout.SMALL_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
					.build();
				remove.active = editable;
				this.addHalfWidth(listKey + "_remove_" + selector, remove);
			}
		}

		final boolean editable = this.canMutateServerPresentation();
		final PresentationSelectorEditBox field = this.createPresentationSelectorField(slot);
		// A below-permission or pending viewer must never get an editable field,
		// matching the disabled Add/Remove buttons; the handler still re-checks
		// the policy state before sending a mutation.
		field.setEditable(editable);
		field.active = editable;
		this.restorePresentationSelectorSelection(slot, field);
		final var add = Button.builder(
			LanguageUtils.settings("presentation").path("add").get(),
			ignored -> this.addServerPresentationSelector(white, field))
			.bounds(0, 0, SettingsScreenLayout.SMALL_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		add.active = editable;
		this.addHalfWidth(listKey + "_field", field);
		this.addHalfWidth(listKey + "_add", add);
		this.addPresentationSlotFeedbackRow(listKey, slot);
	}

	private void addPresentationServerStatusRows() {
		this.flushPendingHalfWidthRow();
		final var status = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			this.presentationServerStatusText().copy().withStyle(ChatFormatting.GRAY),
			this.font).alignCenter();
		status.active = false;
		this.settingsList.addSmall(status, null);

		final MutableComponent permission = this.presentationServerPermissionText();
		if (permission != null) {
			final var permissionRow = new StringWidget(
				0,
				0,
				SettingsScreenLayout.LARGE_WIDGET_WIDTH,
				SettingsScreenLayout.ROW_HEIGHT,
				permission.copy().withStyle(ChatFormatting.YELLOW),
				this.font).alignCenter();
			permissionRow.active = false;
			this.settingsList.addSmall(permissionRow, null);
		}

		// Group-level feedback for the whitelist toggle and the refresh request;
		// per-list feedback lives directly next to each list's input.
		this.presentationGeneralFeedbackWidget = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			this.presentationGeneralFeedbackText(),
			this.font).alignCenter();
		this.presentationGeneralFeedbackWidget.active = false;
		this.settingsList.addSmall(this.presentationGeneralFeedbackWidget, null);
	}

	private void addPresentationSlotFeedbackRow(String key, PresentationSelectorDraftModel.Slot slot) {
		this.flushPendingHalfWidthRow();
		final var row = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			this.presentationSlotFeedbackText(slot),
			this.font).alignLeft();
		row.active = false;
		this.presentationSlotFeedbackWidgets.put(slot, row);
		this.settingsList.addSmall(row, null);
	}

	private MutableComponent presentationGeneralFeedbackText() {
		return this.presentationGeneralFeedback == null
			? Component.empty()
			: this.presentationGeneralFeedback.copy().withStyle(ChatFormatting.RED);
	}

	private void setPresentationGeneralFeedback(MutableComponent message) {
		this.presentationGeneralFeedback = message;
		if (this.presentationGeneralFeedbackWidget != null) {
			this.presentationGeneralFeedbackWidget.setMessage(this.presentationGeneralFeedbackText());
		}
	}

	private MutableComponent presentationSlotFeedbackText(PresentationSelectorDraftModel.Slot slot) {
		final var feedback = this.presentationDrafts.feedback(slot);
		if (feedback == null) {
			return Component.empty();
		}
		final var text = this.presentationFeedbackText(feedback);
		return text.copy().withStyle(feedback == PresentationSelectorDraftModel.Feedback.PENDING
			? ChatFormatting.GRAY
			: ChatFormatting.RED);
	}

	private void setPresentationSlotFeedback(
		PresentationSelectorDraftModel.Slot slot,
		PresentationSelectorDraftModel.Feedback feedback
	) {
		this.presentationDrafts.setFeedback(slot, feedback);
		final StringWidget widget = this.presentationSlotFeedbackWidgets.get(slot);
		if (widget != null) {
			widget.setMessage(this.presentationSlotFeedbackText(slot));
		}
	}

	/**
	 * Captures the live cursor and selection of every mounted selector field
	 * before a rebuild clears the widgets. The pure draft model owns the values,
	 * so they survive the reconstruction and are clamped when applied again.
	 */
	private void savePresentationSelectorSelections() {
		for (var entry : this.presentationSelectorFields.entrySet()) {
			final PresentationSelectorEditBox field = entry.getValue();
			if (field != null) {
				this.presentationDrafts.setCaret(
					entry.getKey(), field.trackedCursor(), field.trackedHighlight());
			}
		}
	}

	/**
	 * Restores a recorded caret/selection only while the recreated field is
	 * still active and editable; a disabled field keeps its text but never
	 * receives a caret. The model clamps both ends to the restored text.
	 */
	private void restorePresentationSelectorSelection(
		PresentationSelectorDraftModel.Slot slot,
		PresentationSelectorEditBox field
	) {
		if (!field.active) {
			return;
		}
		final var caret = this.presentationDrafts.caretFor(slot, field.getValue().length());
		if (caret != null) {
			field.restoreSelection(caret.cursor(), caret.highlight());
		}
	}

	/**
	 * The existing generic focus restore must never focus a selector field that
	 * a pending request, a server revocation, or a read-only view disabled;
	 * every other widget keeps the existing focus behavior.
	 */
	private boolean canRestoreFocus(AbstractWidget target) {
		for (PresentationSelectorEditBox field : this.presentationSelectorFields.values()) {
			if (field == target) {
				return field.active;
			}
		}
		return true;
	}

	private MutableComponent presentationFeedbackText(PresentationSelectorDraftModel.Feedback feedback) {
		return switch (feedback) {
			case EMPTY -> this.presentationFeedback("empty");
			case INVALID -> this.presentationFeedback("invalid");
			case DUPLICATE -> this.presentationFeedback("duplicate");
			case LIST_FULL -> this.presentationFeedback("list_full");
			case NOT_FOUND -> this.presentationFeedback("not_found");
			case DENIED -> this.presentationFeedback("denied");
			case ERROR -> this.presentationFeedback("error");
			case PENDING -> LanguageUtils.settings("presentation").path("server", "pending").get();
			case TIMEOUT -> this.presentationFeedback("timeout");
		};
	}

	private MutableComponent presentationServerStatusText() {
		final var state = this.presentationPolicyState;
		final var serverText = LanguageUtils.settings("presentation").path("server");
		if (state == null) {
			return serverText.path("unsupported").get();
		}
		return switch (state.viewStatus()) {
			case DISCONNECTED -> serverText.path("unsupported").get();
			case UNAVAILABLE -> serverText.path("unknown").get();
			case PENDING -> serverText.path(state.isKnown() ? "pending" : "loading").get();
			case TIMED_OUT -> serverText.path("timeout").get();
			case FAILED -> serverText.path("error").get();
			case READY -> serverText.path("ready").get();
		};
	}

	private MutableComponent presentationServerPermissionText() {
		final var state = this.presentationPolicyState;
		if (state == null || !state.isReady()) {
			return null;
		}
		return LanguageUtils.settings("presentation").path("server", state.canEdit() ? "can_edit" : "cannot_edit").get();
	}

	private MutableComponent presentationServerWhitelistOnlyText() {
		final var state = this.presentationPolicyState;
		final MutableComponent label = LanguageUtils.settings("presentation").path("whitelist_only").get();
		if (state == null || !state.isReady()) {
			// Never present a value the server has not confirmed yet.
			return label;
		}
		return Component.empty()
			.append(label)
			.append(": ")
			.append(LanguageUtils.of("value", state.whitelistOnly() ? "enabled" : "disabled").get());
	}

	private MutableComponent presentationFeedback(String key) {
		return LanguageUtils.settings("presentation").path("feedback", key).get();
	}

	/** Maps a correlated wire status to the compact feedback the UI shows. */
	private PresentationSelectorDraftModel.Feedback feedbackForStatus(Status status) {
		if (status == null) {
			return PresentationSelectorDraftModel.Feedback.ERROR;
		}
		return switch (status) {
			case OK -> null;
			case INVALID -> PresentationSelectorDraftModel.Feedback.INVALID;
			case DUPLICATE -> PresentationSelectorDraftModel.Feedback.DUPLICATE;
			case LIST_FULL -> PresentationSelectorDraftModel.Feedback.LIST_FULL;
			case NOT_FOUND -> PresentationSelectorDraftModel.Feedback.NOT_FOUND;
			case DENIED -> PresentationSelectorDraftModel.Feedback.DENIED;
			case FAILED -> PresentationSelectorDraftModel.Feedback.ERROR;
		};
	}

	/**
	 * The feedback for an edit control that cannot currently submit: denied when
	 * the server revoked edit, a timeout hint when an earlier mutation is
	 * ambiguous, and pending while another request is still in flight.
	 */
	private PresentationSelectorDraftModel.Feedback unavailableServerFeedback(ServerPresentationPolicyState state) {
		if (state == null || !state.canEdit()) {
			return PresentationSelectorDraftModel.Feedback.DENIED;
		}
		return state.lastRequestTimedOut()
			? PresentationSelectorDraftModel.Feedback.TIMEOUT
			: PresentationSelectorDraftModel.Feedback.PENDING;
	}

	private boolean canMutateServerPresentation() {
		final var state = this.presentationPolicyState;
		return state != null && state.canMutate();
	}

	private void addLocalPresentationSelector(
		PresentationSettings settings,
		PresentationSelectorDraftModel.Slot slot,
		boolean toWhite,
		EditBox field
	) {
		final var outcome = PresentationSelectorListModel.add(
			toWhite ? settings.getWhite() : settings.getBlack(),
			field.getValue());
		switch (outcome.result()) {
			case EMPTY -> this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.EMPTY);
			case INVALID -> this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.INVALID);
			case DUPLICATE -> this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.DUPLICATE);
			case LIST_FULL -> this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.LIST_FULL);
			case ADDED -> {
				if (toWhite) {
					settings.setWhite(outcome.selectors());
				} else {
					settings.setBlack(outcome.selectors());
				}
				this.presentationDrafts.clearDraft(slot);
				this.presentationDrafts.clearFeedback(slot);
				this.rebuildPage(true);
			}
		}
	}

	private void removeLocalPresentationSelector(
		PresentationSettings settings,
		PresentationSelectorDraftModel.Slot slot,
		boolean fromWhite,
		String selector
	) {
		final var outcome = PresentationSelectorListModel.remove(
			fromWhite ? settings.getWhite() : settings.getBlack(),
			selector);
		if (!outcome.removed()) {
			this.setPresentationSlotFeedback(slot,
				outcome.result() == PresentationSelectorListModel.RemoveResult.INVALID
					? PresentationSelectorDraftModel.Feedback.INVALID
					: PresentationSelectorDraftModel.Feedback.NOT_FOUND);
			return;
		}
		if (fromWhite) {
			settings.setWhite(outcome.selectors());
		} else {
			settings.setBlack(outcome.selectors());
		}
		this.presentationDrafts.clearFeedback(slot);
		this.rebuildPage(true);
	}

	private void addServerPresentationSelector(boolean toWhite, EditBox field) {
		final PresentationSelectorDraftModel.Slot slot = PresentationSelectorDraftModel.Slot.server(toWhite);
		final var state = this.presentationPolicyState;
		if (state == null || !state.isKnown()) {
			this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.ERROR);
			return;
		}

		final var outcome = PresentationSelectorListModel.add(
			toWhite ? state.white() : state.black(),
			field.getValue());
		switch (outcome.result()) {
			case EMPTY -> this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.EMPTY);
			case INVALID -> this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.INVALID);
			case DUPLICATE -> this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.DUPLICATE);
			case LIST_FULL -> this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.LIST_FULL);
			case ADDED -> {
				if (!this.canMutateServerPresentation()) {
					this.setPresentationSlotFeedback(slot, this.unavailableServerFeedback(state));
					return;
				}

				// Capture the exact submitted text before the request: a
				// synchronous state notification may rebuild the page, and the
				// draft must only be cleared by a matching accepted response.
				final String rawValue = field.getValue();
				final String selector = PresentationSelectorListModel.normalize(rawValue);
				final Operation operation = toWhite ? Operation.ADD_WHITE : Operation.ADD_BLACK;
				if (!this.presentationDrafts.beginSubmission(slot, rawValue)) {
					this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.PENDING);
					return;
				}
				this.presentationPendingOperation = operation;
				if (!CommonClient.INSTANCE.requestPresentationPolicyMutation(operation, selector, false)) {
					this.presentationPendingOperation = null;
					this.presentationDrafts.abandonSubmission(slot);
					this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.ERROR);
					return;
				}
				this.presentationDrafts.clearFeedback(slot);
				this.rebuildPage(true);
			}
		}
	}

	private void removeServerPresentationSelector(boolean fromWhite, String selector) {
		final PresentationSelectorDraftModel.Slot slot = PresentationSelectorDraftModel.Slot.server(fromWhite);
		if (!this.canMutateServerPresentation()) {
			this.setPresentationSlotFeedback(slot, this.unavailableServerFeedback(this.presentationPolicyState));
			return;
		}
		final Operation operation = fromWhite ? Operation.REMOVE_WHITE : Operation.REMOVE_BLACK;
		if (!CommonClient.INSTANCE.requestPresentationPolicyMutation(operation, selector, false)) {
			this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.ERROR);
			return;
		}
		this.presentationPendingOperation = operation;
		this.presentationDrafts.clearFeedback(slot);
		this.rebuildPage(true);
	}

	private void setServerWhitelistOnly(boolean value) {
		if (!this.canMutateServerPresentation()) {
			this.setPresentationGeneralFeedback(this.presentationFeedbackText(
				this.unavailableServerFeedback(this.presentationPolicyState)));
			return;
		}
		if (!CommonClient.INSTANCE.requestPresentationPolicyMutation(Operation.SET_WHITELIST_ONLY, "", value)) {
			this.setPresentationGeneralFeedback(this.presentationFeedbackText(
				PresentationSelectorDraftModel.Feedback.ERROR));
			return;
		}
		this.presentationPendingOperation = Operation.SET_WHITELIST_ONLY;
		this.setPresentationGeneralFeedback(null);
		this.rebuildPage(true);
	}

	private void refreshPresentationPolicy() {
		final var state = this.presentationPolicyState;
		if (state == null || !state.isConnected()) {
			this.setPresentationGeneralFeedback(LanguageUtils.settings("presentation").path("server", "unsupported").get());
			return;
		}
		if (state.pendingRequestId() != ServerPresentationPolicyState.NO_PENDING_REQUEST) {
			this.setPresentationGeneralFeedback(LanguageUtils.settings("presentation").path("server", "pending").get());
			return;
		}
		if (CommonClient.INSTANCE.requestPresentationPolicyRead() == ServerPresentationPolicyState.NO_PENDING_REQUEST) {
			this.setPresentationGeneralFeedback(this.presentationFeedbackText(
				PresentationSelectorDraftModel.Feedback.ERROR));
			return;
		}
		this.presentationPendingOperation = null;
		this.setPresentationGeneralFeedback(null);
		this.rebuildPage(true);
	}

	private void onPresentationPolicyChanged(ServerPresentationPolicyState state) {
		if (this.settingsList == null) {
			return;
		}

		if (this.presentationPendingOperation != null
			&& state.pendingRequestId() == ServerPresentationPolicyState.NO_PENDING_REQUEST) {
			this.completePendingPresentationOperation(state);
		}

		if (this.navigation.current() == Page.CLIENT_PRESENTATION) {
			this.rebuildPage(true);
		}
	}

	/**
	 * Resolves the one outstanding server command against the state transition
	 * that completed it. A matching accepted response clears only the submitted
	 * draft; a denial, failure, or timeout keeps the text for correction and
	 * records the reason next to its list (or in the group feedback for the
	 * whitelist toggle).
	 */
	private void completePendingPresentationOperation(ServerPresentationPolicyState state) {
		final Operation operation = this.presentationPendingOperation;
		this.presentationPendingOperation = null;

		if (!state.isConnected()) {
			this.presentationDrafts.resetSubmission();
			this.presentationDrafts.clearAllFeedback();
			this.presentationGeneralFeedback = null;
			return;
		}

		if (operation == Operation.SET_WHITELIST_ONLY) {
			if (state.lastStatus() == Status.OK && !state.lastRequestTimedOut()) {
				this.presentationGeneralFeedback = null;
			} else {
				this.presentationGeneralFeedback = this.presentationFeedbackText(
					state.lastRequestTimedOut()
						? PresentationSelectorDraftModel.Feedback.TIMEOUT
						: this.feedbackForStatus(state.lastStatus()));
			}
			return;
		}

		final PresentationSelectorDraftModel.Slot slot = PresentationSelectorDraftModel.Slot.server(
			operation == Operation.ADD_WHITE || operation == Operation.REMOVE_WHITE);
		if (state.lastStatus() == Status.OK && !state.lastRequestTimedOut()) {
			this.presentationDrafts.completeSubmission(slot, true);
			this.presentationDrafts.clearFeedback(slot);
		} else {
			this.presentationDrafts.abandonSubmission(slot);
			this.presentationDrafts.setFeedback(slot, state.lastRequestTimedOut()
				? PresentationSelectorDraftModel.Feedback.TIMEOUT
				: this.feedbackForStatus(state.lastStatus()));
		}
	}

	private void addChannelRow() {
		this.addSubgroup("channel");
		final var label = new StringWidget(
			0,
			0,
			SettingsScreenLayout.SMALL_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			LanguageUtils.settings("channel").get(),
			this.font).alignLeft();
		label.active = false;
		label.setTooltip(Tooltip.create(LanguageUtils.settings("channel.tooltip").get()));

		this.channelTextField = new EditBox(
			this.font,
			-1,
			-1,
			SettingsScreenLayout.SMALL_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			LanguageUtils.settings("channel").get());
		this.channelTextField.setMaxLength(MAX_CHANNEL_LENGTH);
		this.channelTextField.setValue(config.getChannel());
		this.channelTextField.setHint(this.getChannelPlaceholder());
		this.channelTextField.setTooltip(Tooltip.create(LanguageUtils.settings("channel.tooltip").get()));
		this.channelTextField.setResponder(config::setChannel);
		this.keyedWidgets.put(Setting.CHANNEL.id(), this.channelTextField);
		this.settingsList.addSmall(label, this.channelTextField);
	}

	private void addOpenClientConfigButton() {
		final Button button = Button.builder(
			LanguageUtils.settings("open_client_config").get(),
			ignored -> this.openClientConfig())
			.bounds(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		button.setTooltip(Tooltip.create(LanguageUtils.settings("open_client_config").path("tooltip").get()));
		this.addFullWidth(Setting.OPEN_CLIENT_CONFIG.id(), button);
	}

	private void addServerChannelModeButton() {
		final var button = this.createServerValueButton(
			"default_channel_mode",
			() -> LanguageUtils.of("value", this.serverSettings.defaultChannelMode().toString()).get(),
			this.serverSettings::cycleDefaultChannelMode);
		button.active = this.serverSettings.canEdit();
		this.addFullWidth(Setting.DEFAULT_CHANNEL_MODE.id(), button);
	}

	private void addServerPlayerTrackingButton() {
		final var button = this.createServerValueButton(
			"player_tracking_enabled",
			() -> LanguageUtils.of(
				"value",
				this.serverSettings.playerTrackingEnabled() ? "enabled" : "disabled").get(),
			this.serverSettings::togglePlayerTracking);
		button.active = this.serverSettings.canEdit();
		this.addFullWidth(Setting.PLAYER_TRACKING_ENABLED.id(), button);
	}

	private void addServerIntegerRow(Setting setting) {
		final String key = setting.id();
		final String tooltipKey = key + ".tooltip";
		final String value;
		final Consumer<String> responder;
		switch (setting) {
			case MS_TO_REGENERATE -> {
				value = this.serverSettings.msToRegenerateText();
				responder = this.serverSettings::setMsToRegenerateText;
			}
			case RATE_LIMIT -> {
				value = this.serverSettings.rateLimitText();
				responder = this.serverSettings::setRateLimitText;
			}
			case SYNC_DURATION -> {
				value = this.serverSettings.syncDurationText();
				responder = this.serverSettings::setSyncDurationText;
			}
			default -> throw new IllegalArgumentException("not a server numeric field: " + setting);
		}

		final var label = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			LanguageUtils.settings(key).get(),
			this.font).alignLeft();
		label.active = false;
		label.setTooltip(Tooltip.create(LanguageUtils.settings(tooltipKey).get()));
		this.settingsList.addSmall(label, null);

		final var field = this.createServerIntegerField(value, responder, tooltipKey, key);
		field.setEditable(this.serverSettings.canEdit());
		field.active = this.serverSettings.canEdit();
		this.addFullWidth(key, field);
		switch (setting) {
			case MS_TO_REGENERATE -> this.serverMsToRegenerateField = field;
			case RATE_LIMIT -> this.serverRateLimitField = field;
			case SYNC_DURATION -> this.serverSyncDurationField = field;
			default -> {
			}
		}
	}

	private void addServerStatus() {
		this.flushPendingHalfWidthRow();
		this.serverStatusWidget = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			this.serverStatusText().withStyle(this.serverSettings.canEdit() ? ChatFormatting.GRAY : ChatFormatting.YELLOW),
			this.font).alignCenter();
		this.serverStatusWidget.active = false;
		this.settingsList.addSmall(this.serverStatusWidget, null);
	}

	private MutableComponent serverStatusText() {
		if (!this.hasLiveServerConnection()) {
			return LanguageUtils.settings("server_status").path("unavailable").get();
		}
		if (this.serverSettings.loading()) {
			return LanguageUtils.settings("server_status").path("loading").get();
		}
		if (!this.serverSettings.canView()) {
			return LanguageUtils.settings("server_status").path("unavailable").get();
		}
		if (!this.serverSettings.canEdit()) {
			// A viewer below the edit permission sees the authoritative values
			// read-only; a locally privileged requester that the server denied
			// gets the permission wording instead.
			return this.serverSettings.accessDenied()
				? LanguageUtils.settings("server_status").path("permission").get()
				: LanguageUtils.settings("server_status").path("read_only").get();
		}
		return this.serverSettings.dirty()
			? LanguageUtils.settings("server_status").path("draft").get()
			: LanguageUtils.settings("server_status").path("ready").get();
	}

	private MutableComponent currentPageTitle() {
		final MutableComponent base = LanguageUtils.settings("title").get();
		if (navigation.isOverview()) {
			return SettingsTitleWidget.composeTitle(base, null, null);
		}
		return SettingsTitleWidget.composeTitle(
			base,
			scopeTitle(navigation.scope()),
			LanguageUtils.settings("category").path(navigation.current().category().orElseThrow().id()).get());
	}

	private static MutableComponent scopeTitle(Scope scope) {
		return LanguageUtils.settings(scope == Scope.CLIENT ? "client_settings" : "server_settings").get();
	}

	private Component primaryButtonText() {
		return navigation.isLeaf() ? CommonComponents.GUI_BACK : CommonComponents.GUI_DONE;
	}

	private void onPrimaryButton() {
		if (navigation.isLeaf()) {
			this.navigateBack();
		} else {
			this.onClose();
		}
	}

	private void saveCurrentViewState() {
		this.saveCurrentViewState(this.currentFocusKey());
	}

	private void saveCurrentViewState(String focusKey) {
		if (this.settingsList == null) {
			return;
		}
		navigation.saveViewStateBeforeNavigation(
			(int) Math.round(this.settingsList.getScrollAmount()),
			focusKey);
	}

	private String currentFocusKey() {
		GuiEventListener focused = this.getFocused();
		if (focused == this.settingsList) {
			focused = this.settingsList.focusedWidget();
		}
		for (var entry : this.keyedWidgets.entrySet()) {
			if (entry.getValue() == focused || entry.getValue().isFocused()) {
				return entry.getKey();
			}
		}
		return null;
	}

	private void restoreCurrentViewState() {
		this.settingsList.setClampedScrollAmount(navigation.scrollAmount());
		final AbstractWidget target = this.keyedWidgets.get(navigation.focusKey());
		if (target == null || !this.canRestoreFocus(target)) {
			return;
		}
		this.clearFocus();
		this.settingsList.focusWidget(target);
		this.setFocused(this.settingsList);
		// ContainerObjectSelectionList may scroll while it restores a child focus;
		// the saved viewport is authoritative for a page revisit.
		this.settingsList.setClampedScrollAmount(navigation.scrollAmount());
	}

	@Override
	protected void setInitialFocus() {
		if (navigation.focusKey() != null) {
			this.restoreCurrentViewState();
			return;
		}
		if (navigation.isOverview() && this.scopeTabBar != null) {
			this.setInitialFocus(this.scopeTabBar);
		} else if (this.settingsList != null) {
			this.setInitialFocus(this.settingsList);
		}
	}

	private void focusFirstInvalidServerField(int invalidMask) {
		final AbstractWidget target;
		if ((invalidMask & ServerConfigUpdate.MS_TO_REGENERATE) != 0) {
			target = this.serverMsToRegenerateField;
		} else if ((invalidMask & ServerConfigUpdate.RATE_LIMIT) != 0) {
			target = this.serverRateLimitField;
		} else {
			target = this.serverSyncDurationField;
		}
		if (target != null) {
			this.clearFocus();
			this.settingsList.focusWidget(target);
			this.setFocused(this.settingsList);
		}
	}

	private void routeToInvalidServerDraftAfterInput(int invalidMask) {
		this.inputActions.runAfterDispatch(() -> {
			navigation.routeToInvalidServerDraft(invalidMask);
			this.rebuildPage(false);
			this.focusFirstInvalidServerField(invalidMask);
		});
	}

	private void flushOptionWidgets() {
		if (this.settingsList != null) {
			this.settingsList.applyUnsavedChanges();
		}
	}

	private boolean persistSettings() {
		this.flushOptionWidgets();
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

	private void openClientConfig() {
		if (this.minecraft == null) {
			return;
		}
		this.saveCurrentViewState();
		this.inputActions.runAfterDispatch(this::performOpenClientConfig);
	}

	private void performOpenClientConfig() {
		if (this.minecraft == null || !this.persistSettings()) {
			return;
		}

		this.suppressSaveOnClose = true;
		this.leaveSettingsScreen();
		try {
			Util.getPlatform().openFile(ClientConfig.HANDLER.getConfigPath().toFile());
		} catch (Exception | LinkageError failure) {
			warnException("opening client config file failed", failure);
		}
	}

	private void requestServerSettingsIfNeeded() {
		if (!this.hasLiveServerConnection()
			|| this.serverSettings.accessDenied()
			|| this.serverSettings.loaded()
			|| this.serverSettings.loading()) {
			return;
		}

		final long requestId = this.serverSettings.beginSessionIfNeeded();
		if (requestId != NO_REQUEST) {
			this.serverValidationMessage = null;
			IPlatformNetworkService.INSTANCE.sendToServer(new ServerConfigRequestC2SPacket(requestId));
		}
	}

	private void updatePermissionState(boolean rebuild) {
		final boolean permission = this.hasLiveServerConnection()
			&& this.minecraft.player.hasPermissions(3);
		if (permission == this.serverSettings.clientPermission()) {
			return;
		}

		final boolean wasDenied = this.serverSettings.accessDenied();
		this.serverSettings.setClientPermission(permission);
		this.serverValidationMessage = null;
		// A retained safe snapshot keeps the current server leaf open as a
		// read-only page with disabled values; only a lost view forces the
		// server scope overview.
		this.saveCurrentViewState();
		final boolean forcedToOverview = this.navigation.onServerViewAccessLost(this.serverSettings.canView());
		if (permission && navigation.scope() == Scope.SERVER) {
			if (wasDenied) {
				this.serverRequestDeferred = true;
			} else {
				this.requestServerSettingsIfNeeded();
			}
		}
		if (rebuild && navigation.scope() == Scope.SERVER) {
			this.rebuildPage(!forcedToOverview);
		}
	}

	public void onServerConfigSnapshot(long requestId, ServerConfigSnapshot snapshot) {
		if (!this.serverSettings.applySnapshot(requestId, snapshot)) {
			return;
		}
		this.serverValidationMessage = null;
		// A read-only or denied snapshot keeps the leaf page open and renders
		// its retained values without editable widgets.
		if (navigation.scope() == Scope.SERVER) {
			this.rebuildPage(true);
		}
	}

	public void onServerDisconnected() {
		this.serverSettings.resetForDisconnect();
		this.serverValidationMessage = null;
		this.saveCurrentViewState();
		final boolean forcedToOverview = this.navigation.onServerViewAccessLost(false);
		if (navigation.scope() == Scope.SERVER) {
			this.rebuildPage(!forcedToOverview);
		}
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

		final int invalidMask = this.serverSettings.invalidFieldMask();
		if (invalidMask != 0) {
			this.serverValidationMessage = LanguageUtils.settings("server_settings.validation").get();
			this.routeToInvalidServerDraftAfterInput(invalidMask);
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
			values.rateLimit(),
			values.syncDuration()));
		return true;
	}

	private boolean hasLiveServerConnection() {
		return this.minecraft != null
			&& this.minecraft.level != null
			&& this.minecraft.player != null
			&& this.minecraft.getConnection() != null;
	}

	private void openResetConfirmation() {
		if (this.minecraft == null) {
			return;
		}
		this.saveCurrentViewState();
		this.inputActions.runAfterDispatch(this::performOpenResetConfirmation);
	}

	private void performOpenResetConfirmation() {
		if (this.minecraft == null) {
			return;
		}

		this.resetConfirmationHandled = false;
		final MutableComponent message = LanguageUtils.settings("reset_all").path("message").get();
		if (this.serverSettings.dirty()) {
			message.append(" ").append(
				LanguageUtils.settings("reset_all").path("server_draft_warning").get().withStyle(ChatFormatting.YELLOW));
		}
		this.minecraft.setScreen(new ConfirmScreen(
			this::handleResetConfirmation,
			LanguageUtils.settings("reset_all").path("title").get(),
			message));
	}

	private void handleResetConfirmation(boolean confirmed) {
		if (this.resetConfirmationHandled || this.minecraft == null) {
			return;
		}

		this.resetConfirmationHandled = true;
		if (confirmed) {
			this.suppressSaveOnClose = true;
			clearCurrent(this);
			ClientConfig.HANDLER.resetToDefaults();
			this.minecraft.setScreen(new SettingsScreen(this.parent));
		} else {
			this.minecraft.setScreen(this);
		}
	}

	private SettingsScreenLayout getScreenLayout() {
		return SettingsScreenLayout.calculate(
			this.width,
			this.height,
			this.layout.getHeaderHeight(),
			this.layout.getFooterHeight());
	}

	private MutableComponent getChannelPlaceholder() {
		if (Game.player == null) {
			return Component.empty();
		}
		final var teamContext = TeamContextHandler.getSelfContext();
		final MutableComponent placeholder = teamContext == TeamContext.NONE
			? LanguageUtils.of("value", "global").get()
			: LanguageUtils.settings("channel").path("placeholder")
				.get(LanguageUtils.of("value", teamContext.toString()).get());
		return placeholder.withStyle(ChatFormatting.ITALIC).withStyle(ChatFormatting.DARK_GRAY);
	}

	private OptionInstance<Integer> getPingVolumeOption() {
		final var text = LanguageUtils.settings("ping_volume");
		return OptionUtils.ofInt(text.getKey(), 0, 100, 1,
			value -> value == 0
				? text.get(CommonComponents.OPTION_OFF)
				: text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			config::getPingVolume,
			config::setPingVolume);
	}

	private OptionInstance<Integer> getPingDistanceOption() {
		final var text = LanguageUtils.settings("ping_distance");
		return OptionUtils.ofInt(text.getKey(), 0, MAX_PING_DISTANCE, 16,
			value -> {
				if (value == 0) return text.get(LanguageUtils.VALUE_HIDDEN);
				if (value >= MAX_PING_DISTANCE) return text.get(LanguageUtils.VALUE_INFINITE);
				return text.get(LanguageUtils.UNIT_METERS.get(value));
			},
			config::getPingDistance,
			config::setPingDistance);
	}

	private OptionInstance<Integer> getMarkerDisplayDurationOption() {
		final var text = LanguageUtils.settings("marker_display_duration");
		return OptionUtils.ofInt(text.getKey(),
			FOLLOW_SERVER_MARKER_DISPLAY_DURATION,
			MAX_MARKER_DISPLAY_DURATION,
			MARKER_DISPLAY_DURATION_STEP,
			value -> value == FOLLOW_SERVER_MARKER_DISPLAY_DURATION
				? text.get(LanguageUtils.of("value", "follow_server").get())
				: text.get(LanguageUtils.UNIT_SECONDS.get(value)),
			() -> text.path("tooltip").get(),
			config::getEffectiveMarkerDisplayDuration,
			config::setMarkerDisplayDuration);
	}

	private OptionInstance<Boolean> getItemIconsVisibleOption() {
		return OptionUtils.ofBool(LanguageUtils.settings("item_icon_visible").getKey(),
			config::isItemIconVisible, config::setItemIconVisible);
	}

	private OptionInstance<Boolean> getPassThroughTransparentBlocksOption() {
		return OptionUtils.ofBool(LanguageUtils.settings("pass_through_transparent_blocks").getKey(),
			config::isPassThroughTransparentBlocks, config::setPassThroughTransparentBlocks);
	}

	private OptionInstance<Boolean> getMarkBlacklistedTargetsOption() {
		return OptionUtils.ofBool(LanguageUtils.settings("mark_blacklisted_targets").getKey(),
			config::isMarkBlacklistedTargets, config::setMarkBlacklistedTargets);
	}

	private OptionInstance<Boolean> getMarkFluidsOption() {
		return OptionUtils.ofBool(LanguageUtils.settings("mark_fluids").getKey(),
			config::isMarkFluids, config::setMarkFluids);
	}

	private OptionInstance<Boolean> getDirectionIndicatorVisibleOption() {
		return OptionUtils.ofBool(LanguageUtils.settings("direction_indicator_visible").getKey(),
			config::isDirectionIndicatorVisible, config::setDirectionIndicatorVisible);
	}

	private OptionInstance<PlayerInfoMode> getPlayerInfoModeOption() {
		return OptionUtils.ofEnum(
			LanguageUtils.settings("player_info_mode").getKey(),
			PlayerInfoMode.class,
			mode -> LanguageUtils.of("value", mode.toString()).get(),
			mode -> {
				if (mode != PlayerInfoMode.HOLD) return Component.empty();
				final var keyPlayerListTitle = Component.translatable(Game.options.keyPlayerList.getName());
				final var keyPlayerListName = Game.options.keyPlayerList.getTranslatedKeyMessage();
				return LanguageUtils.settings("player_info_mode").path("hold", "tooltip")
					.get(keyPlayerListTitle, keyPlayerListName);
			},
			config::getPlayerInfoMode,
			config::setPlayerInfoMode);
	}

	private OptionInstance<TeamColorMode> getTeamColorModeOption() {
		return OptionUtils.ofEnum(
			LanguageUtils.settings("team_color_mode").getKey(),
			TeamColorMode.class,
			mode -> LanguageUtils.of("value", mode.toString()).get(),
			mode -> Component.empty(),
			config::getTeamColorMode,
			config::setTeamColorMode);
	}

	private OptionInstance<Integer> getConfigurationNoticeSizeOption() {
		final var text = LanguageUtils.settings("configuration_notice_size");
		return OptionUtils.ofInt(text.getKey(),
			MIN_CONFIGURATION_NOTICE_SIZE,
			MAX_CONFIGURATION_NOTICE_SIZE,
			CONFIGURATION_NOTICE_SIZE_STEP,
			value -> text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			config::getConfigurationNoticeSize,
			config::setConfigurationNoticeSize);
	}

	private OptionInstance<Integer> getPingSizeOption() {
		final var text = LanguageUtils.settings("ping_size");
		return OptionUtils.ofInt(text.getKey(), 40, 300, 10,
			value -> text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			config::getPingSize,
			config::setPingSize);
	}

	private OptionInstance<Integer> getWheelHoldMillisOption() {
		final var text = LanguageUtils.settings("wheel_hold_millis");
		return OptionUtils.ofInt(text.getKey(),
			MIN_WHEEL_HOLD_MILLIS,
			MAX_WHEEL_HOLD_MILLIS,
			WHEEL_HOLD_MILLIS_STEP,
			value -> text.get(LanguageUtils.UNIT_MILLISECONDS.get(value)),
			config::getWheelHoldMillis,
			config::setWheelHoldMillis);
	}

	private OptionInstance<Boolean> getLongPressCompatibilityModeOption() {
		final var text = LanguageUtils.settings("long_press_compatibility_mode");
		return OptionUtils.ofBool(text.getKey(),
			config::isLongPressCompatibilityMode,
			config::setLongPressCompatibilityMode,
			() -> text.path("tooltip").get());
	}

	private OptionInstance<Integer> getLongPressCompatibilitySliceMillisOption() {
		final var text = LanguageUtils.settings("long_press_compatibility_slice_millis");
		return OptionUtils.ofInt(text.getKey(),
			MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			effectiveLongPressCompatibilitySliceMaxMillis(config.getWheelHoldMillis()),
			LONG_PRESS_COMPATIBILITY_SLICE_MILLIS_STEP,
			value -> text.get(LanguageUtils.UNIT_MILLISECONDS.get(value)),
			() -> text.path("tooltip").get(),
			config::getLongPressCompatibilitySliceMillis,
			config::setLongPressCompatibilitySliceMillis);
	}

	private OptionInstance<Integer> getWheelInnerRadiusOption() {
		final var text = LanguageUtils.settings("wheel_inner_radius");
		return OptionUtils.ofInt(text.getKey(), MIN_WHEEL_INNER_RADIUS, MAX_WHEEL_INNER_RADIUS, WHEEL_INNER_RADIUS_STEP,
			value -> text.get(LanguageUtils.UNIT_PIXELS.get(value)),
			config::getWheelInnerRadius,
			config::setWheelInnerRadius);
	}

	private OptionInstance<Integer> getWheelOuterRadiusOption() {
		final var text = LanguageUtils.settings("wheel_outer_radius");
		return OptionUtils.ofInt(text.getKey(), MIN_WHEEL_OUTER_RADIUS, MAX_WHEEL_OUTER_RADIUS, WHEEL_OUTER_RADIUS_STEP,
			value -> text.get(LanguageUtils.UNIT_PIXELS.get(value)),
			config::getWheelOuterRadius,
			config::setWheelOuterRadius);
	}

	private OptionInstance<Integer> getWheelOpacityOption() {
		final var text = LanguageUtils.settings("wheel_opacity");
		return OptionUtils.ofInt(text.getKey(), MIN_WHEEL_OPACITY, MAX_WHEEL_OPACITY, WHEEL_OPACITY_STEP,
			value -> value == 0
				? text.get(CommonComponents.OPTION_OFF)
				: text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			config::getWheelOpacity,
			config::setWheelOpacity);
	}

	private OptionInstance<Integer> getWheelOptionFontSizeOption() {
		final var text = LanguageUtils.settings("wheel_font_size");
		return OptionUtils.ofInt(text.getKey(), MIN_WHEEL_FONT_SIZE, MAX_WHEEL_FONT_SIZE, WHEEL_FONT_SIZE_STEP,
			value -> text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			config::getWheelFontSize,
			config::setWheelFontSize);
	}

	private OptionInstance<Integer> getWheelTargetFontSizeOption() {
		final var text = LanguageUtils.settings("wheel_target_font_size");
		return OptionUtils.ofInt(text.getKey(),
			MIN_WHEEL_TARGET_FONT_SIZE,
			MAX_WHEEL_TARGET_FONT_SIZE,
			WHEEL_TARGET_FONT_SIZE_STEP,
			value -> text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			config::getWheelTargetFontSize,
			config::setWheelTargetFontSize);
	}

	private OptionInstance<Integer> getWheelTimeoutMillisOption() {
		final var text = LanguageUtils.settings("wheel_timeout_millis");
		return OptionUtils.ofInt(text.getKey(), MIN_WHEEL_TIMEOUT_MILLIS, MAX_WHEEL_TIMEOUT_MILLIS, WHEEL_TIMEOUT_MILLIS_STEP,
			value -> text.get(LanguageUtils.UNIT_MILLISECONDS.get(value)),
			config::getWheelTimeoutMillis,
			config::setWheelTimeoutMillis);
	}

	private OptionInstance<Integer> getCancelHalfConeAngleDegreesOption() {
		final var text = LanguageUtils.settings("cancel_half_cone_angle_degrees");
		return OptionUtils.ofInt(text.getKey(),
			MIN_CANCEL_HALF_CONE_ANGLE_DEGREES,
			MAX_CANCEL_HALF_CONE_ANGLE_DEGREES,
			CANCEL_HALF_CONE_ANGLE_DEGREES_STEP,
			value -> text.get(LanguageUtils.UNIT_DEGREES.get(value)),
			config::getCancelHalfConeAngleDegrees,
			config::setCancelHalfConeAngleDegrees);
	}

	private OptionInstance<EntityBlockRenderMode> getEntityBlockRenderModeOption() {
		final var text = LanguageUtils.settings("entity_block_render_mode");
		return OptionUtils.ofEnum(text.getKey(),
			EntityBlockRenderMode.class,
			mode -> LanguageUtils.of("value", mode.toString()).get(),
			mode -> text.path(mode.toString(), "tooltip").get(),
			config::getEntityBlockRenderMode,
			config::setEntityBlockRenderMode);
	}

	private Button createServerValueButton(String key, Supplier<Component> value, Runnable action) {
		final var label = LanguageUtils.settings(key).get();
		final var button = Button.builder(
			this.serverRowText(label, value.get()),
			clicked -> {
				action.run();
				clicked.setMessage(this.serverRowText(label, value.get()));
			})
			.bounds(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		button.setTooltip(Tooltip.create(LanguageUtils.settings(key).path("tooltip").get()));
		return button;
	}

	private StringWidget createServerValidationLabel() {
		final var label = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			this.serverValidationMessage.copy().withStyle(ChatFormatting.RED),
			this.font).alignCenter();
		label.active = false;
		return label;
	}

	private EditBox createServerIntegerField(
		String value,
		Consumer<String> responder,
		String tooltipKey,
		String narrationKey
	) {
		final var field = new EditBox(
			this.font,
			-1,
			-1,
			SettingsScreenLayout.SMALL_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			LanguageUtils.settings(narrationKey).get());
		field.setMaxLength(Integer.toString(Integer.MAX_VALUE).length());
		field.setFilter(text -> text.isEmpty()
			|| text.chars().allMatch(character -> character >= '0' && character <= '9'));
		field.setValue(value);
		field.setTooltip(Tooltip.create(LanguageUtils.settings(tooltipKey).get()));
		field.setResponder(text -> {
			this.serverValidationMessage = null;
			responder.accept(text);
		});
		return field;
	}

	private MutableComponent serverRowText(Component label, Component value) {
		return Component.empty().append(label).append(": ").append(value);
	}

	private final class NavigationTabManager extends TabManager {
		private NavigationTabManager(Consumer<AbstractWidget> addWidget, Consumer<AbstractWidget> removeWidget) {
			super(addWidget, removeWidget);
		}

		@Override
		public void setCurrentTab(Tab tab, boolean playClickSound) {
			final boolean changed = tab != this.getCurrentTab();
			super.setCurrentTab(tab, playClickSound);
			if (changed && tab instanceof ScopeTab scopeTab) {
				SettingsScreen.this.selectScopeFromTab(scopeTab.scope);
			}
		}
	}

	static final class ScopeTab implements Tab {
		private final Scope scope;

		ScopeTab(Scope scope) {
			this.scope = scope;
		}

		@Override
		public Component getTabTitle() {
			return scopeTitle(this.scope);
		}

		@Override
		public void visitChildren(Consumer<AbstractWidget> consumer) {
			// The root tabs select navigation state; their content remains in the
			// screen's single retained OptionsList.
		}

		@Override
		public void doLayout(ScreenRectangle area) {
			// The shared page list is laid out by the screen, not by either tab.
		}
	}

	private static final class SettingsOptionsList extends OptionsList {
		private SettingsOptionsList(net.minecraft.client.Minecraft minecraft, int width, SettingsScreen screen) {
			super(minecraft, width, screen);
		}

		private void resetEntries() {
			this.clearEntries();
		}

		private AbstractWidget focusedWidget() {
			final GuiEventListener focusedEntry = this.getFocused();
			if (focusedEntry instanceof OptionsList.Entry entry && entry.getFocused() instanceof AbstractWidget widget) {
				return widget;
			}
			return null;
		}

		private void focusWidget(AbstractWidget widget) {
			for (OptionsList.Entry entry : this.children()) {
				if (entry.children().contains(widget)) {
					entry.setFocused(widget);
					this.setFocused(entry);
					return;
				}
			}
		}
	}
}
