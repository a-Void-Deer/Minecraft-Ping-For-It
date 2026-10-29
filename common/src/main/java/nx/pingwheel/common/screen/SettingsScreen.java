package nx.pingwheel.common.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.components.TabButton;
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
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.config.PlayerInfoMode;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventorySizeScale;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.config.SpatialSelectorSettings;
import nx.pingwheel.common.config.TeamColorMode;
import nx.pingwheel.common.integration.TeamContext;
import nx.pingwheel.common.integration.TeamContextHandler;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.presentation.PresentationPolicy;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Operation;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;
import nx.pingwheel.common.presentation.client.PresentationFieldCatalog;
import nx.pingwheel.common.presentation.client.ServerPresentationPolicyState;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.screen.SettingsCategoryCatalog.Setting;
import nx.pingwheel.common.screen.SettingsCategoryCatalog.Row;
import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import nx.pingwheel.common.screen.SettingsNavigationModel.Page;
import nx.pingwheel.common.screen.SettingsNavigationModel.Scope;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.config.ClientConfig.*;
import static nx.pingwheel.common.config.ClientConfigBounds.*;
import static nx.pingwheel.common.config.SpatialSelectorSettings.*;
import static nx.pingwheel.common.Global.warnException;

public class SettingsScreen extends OptionsSubScreen {
	private static final long NO_REQUEST = -1L;
	private static WeakReference<@Nullable SettingsScreen> currentSettingsScreen = new WeakReference<>(null);

	private final ClientConfig config;
	private final ServerSettingsModel serverSettings = new ServerSettingsModel(false);
	private final SettingsNavigationModel navigation = new SettingsNavigationModel();
	private final Map<String, AbstractWidget> keyedWidgets = new HashMap<>();
	private final Map<Scope, ScopeTab> scopeTabs = new EnumMap<>(Scope.class);
	private final TabManager tabManager;
	private final DeferredActionCoordinator inputActions = new DeferredActionCoordinator();

	private @Nullable Screen parent;
	private @Nullable SettingsOptionsList settingsList;
	private @Nullable TabNavigationBar scopeTabBar;
	private @Nullable Button primaryButton;
	private @Nullable Button resetAllButton;
	private @Nullable EditBox channelTextField;
	private @Nullable EditBox serverMsToRegenerateField;
	private @Nullable EditBox serverRateLimitField;
	private @Nullable EditBox serverSyncDurationField;
	private @Nullable SettingsTitleWidget pageTitle;
	private @Nullable StringWidget serverStatusWidget;
	private boolean serverRequestDeferred;
	private boolean tabBarRegistered;
	private boolean syncingScopeTab;
	private boolean resetConfirmationHandled;
	private boolean suppressSaveOnClose;
	private boolean sizeSliderEditPending;
	private final InventorySizeOption.AdjustmentState sizeSliderAdjustment = new InventorySizeOption.AdjustmentState();
	private @Nullable MutableComponent serverValidationMessage;

	private final ServerPresentationPolicyState.ChangeListener presentationPolicyListener =
		this::onPresentationPolicyChanged;
	private final PresentationSelectorDraftModel presentationDrafts = new PresentationSelectorDraftModel();
	private final Map<PresentationSelectorDraftModel.Slot, StringWidget> presentationSlotFeedbackWidgets =
		new HashMap<>();
	private final Map<PresentationSelectorDraftModel.Slot, PresentationSelectorEditBox> presentationSelectorFields =
		new HashMap<>();
	private String selectedPresentationTargetType = PresentationSettings.TARGET_TYPE_IDS.get(0);
	private @Nullable ServerPresentationPolicyState presentationPolicyState;
	private @Nullable Operation presentationPendingOperation;
	private @Nullable String presentationPendingTargetType;
	private @Nullable MutableComponent presentationGeneralFeedback;
	private final Map<String, MutableComponent> presentationWhitelistFeedback = new HashMap<>();
	private @Nullable StringWidget presentationGeneralFeedbackWidget;
	private final Set<String> presentationAdvancedExpanded = new HashSet<>();
	private final Map<String, MutableComponent> presentationQuickFeedback = new HashMap<>();
	private @Nullable PresentationQuickAction presentationPendingQuickAction;
	private long presentationCatalogStamp = -1L;

	/** One quick field-id rule toggle waiting for its correlated server response. */
	@NotNullByDefault
	private record PresentationQuickAction(String panelKey) {
	}

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
		final @Nullable SettingsScreen screen = currentSettingsScreen.get();
		if (screen != null) {
			screen.onServerDisconnected();
		}
	}

	/** Routes a delayed response to the latest live settings session. */
	public static void notifyServerConfigSnapshot(long requestId, ServerConfigSnapshot snapshot) {
		final @Nullable SettingsScreen screen = currentSettingsScreen.get();
		if (screen != null) {
			screen.onServerConfigSnapshot(requestId, snapshot);
		}
	}

	public SettingsScreen() {
		this(ClientConfig.HANDLER.getConfig(), null);
	}

	/** Builds the same option factories without loading a persisted client session. */
	SettingsScreen(ClientConfig config, @Nullable Screen parent) {
		super(null, null, LanguageUtils.settings("title").get());
		this.config = config;
		this.parent = parent;
		this.tabManager = new NavigationTabManager(this::addRenderableWidget, this::removeWidget);
	}

	public SettingsScreen(@Nullable Screen parent) {
		this();
		this.parent = parent;
	}

	@Override
	protected void init() {
		registerCurrent(this);
		this.presentationPolicyState = CommonClient.INSTANCE.getServerPresentationPolicyState();
		this.presentationPolicyState.addListener(this.presentationPolicyListener);
		this.presentationCatalogStamp = this.presentationCatalogStamp();
		this.updatePermissionState(false);
		this.createScopeTabs();
		this.addRenderableWidget(this.scopeTabBar);
		this.tabBarRegistered = true;

		this.pageTitle = new SettingsTitleWidget(this.font);
		this.pageTitle.setMessage(this.currentPageTitle());
		this.addRenderableOnly(this.pageTitle);

		this.settingsList = new SettingsOptionsList(this.minecraft, this.width, this,
			this.font::width, this::truncatedLabel);
		this.list = this.settingsList;
		this.addRenderableWidget(this.settingsList);

		this.resetAllButton = Button.builder(
			LanguageUtils.settings("reset_all").get(),
			ignored -> this.openResetConfirmation())
			.bounds(0, 0, SettingsScreenLayout.RESET_BUTTON_WIDTH, SettingsScreenLayout.RESET_BUTTON_HEIGHT)
			.build();
		this.resetAllButton.setTooltip(Tooltip.create(LanguageUtils.settings("reset_all.tooltip").get()));
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
		// An offer may arrive while a presentation page is open; rebuild only
		// when the accepted-catalog metadata actually changed.
		this.updatePresentationCatalogStamp();
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
		final boolean handled = this.inputActions.dispatch(() -> this.dispatchKeyPressed(keyCode, scanCode, modifiers));
		this.rebuildAfterSizeSliderEdit();
		return handled;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		final boolean handled = this.inputActions.dispatch(() -> super.mouseReleased(mouseX, mouseY, button));
		this.rebuildAfterSizeSliderEdit();
		return handled;
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
			this.clampScopeTabs();
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

		final var screenLayout = this.getScreenLayout();
		this.settingsList.updateSizeAndPosition(
			this.width,
			screenLayout.listBottom() - screenLayout.listTop(),
			screenLayout.listTop());
		if (!this.settingsList.hasUsableViewport() && this.getFocused() == this.settingsList) {
			this.setFocused((GuiEventListener) null);
			this.setDragging(false);
		}
		this.tabManager.setTabArea(new ScreenRectangle(
			0,
			screenLayout.listTop(),
			this.width,
			screenLayout.listBottom() - screenLayout.listTop()));

		this.primaryButton.setWidth(SettingsScreenLayout.buttonWidthFor(this.width, SettingsScreenLayout.PRIMARY_BUTTON_WIDTH));
		this.resetAllButton.setWidth(SettingsScreenLayout.buttonWidthFor(this.width, SettingsScreenLayout.RESET_BUTTON_WIDTH));
		this.primaryButton.visible = this.primaryButton.getWidth() > 0
			&& screenLayout.footerBottom() - screenLayout.footerTop() >= this.primaryButton.getHeight();
		this.resetAllButton.visible = this.resetAllButton.visible && this.resetAllButton.getWidth() > 0
			&& screenLayout.footerBottom() - screenLayout.footerTop() >= this.resetAllButton.getHeight();
		this.resetAllButton.active = this.resetAllButton.visible;
		this.primaryButton.setPosition(screenLayout.doneX(), screenLayout.doneY());
		this.resetAllButton.setPosition(screenLayout.resetX(), screenLayout.resetY());
	}

	@Override
	public void resize(net.minecraft.client.Minecraft minecraft, int width, int height) {
		this.saveCurrentViewState();
		super.resize(minecraft, width, height);
		if (this.getFocused() == null || this.getFocused() == this.settingsList) {
			this.restoreCurrentViewState();
		}
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
		this.clampScopeTabs();
		for (GuiEventListener child : this.scopeTabBar.children()) {
			if (child instanceof TabButton button && button.tab() instanceof ScopeTab tab) {
				button.setTooltip(Tooltip.create(LanguageUtils.settings("scope")
					.path(tab.scope == Scope.CLIENT ? "client" : "server", "tooltip").get()));
			}
		}

		this.syncingScopeTab = true;
		this.tabManager.setCurrentTab(this.scopeTabs.get(navigation.scope()), false);
		this.syncingScopeTab = false;
	}

	/** Vanilla's tab layout can calculate negative widths below its side margins. */
	private void clampScopeTabs() {
		if (this.scopeTabBar == null) return;
		int safeWidth = Math.max(0, this.width);
		int count = this.scopeTabBar.children().size();
		int index = 0;
		for (GuiEventListener child : this.scopeTabBar.children()) {
			if (child instanceof TabButton button) {
				if (safeWidth < 32) {
					int left = safeWidth * index / count;
					int right = safeWidth * (index + 1) / count;
					button.setWidth(right - left);
					button.setX(left);
				}
				button.visible = button.getWidth() > 0;
			}
			index++;
		}
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
		if (!this.sizeSliderAdjustment.isCarrying()) {
			this.sizeSliderAdjustment.clear();
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
		if (navigation.scope() == Scope.SERVER
			&& navigation.current() != Page.SERVER_PRESENTATION
			&& !this.serverRequestDeferred) {
			this.requestServerSettingsIfNeeded();
		}

		if (navigation.isOverview()) {
			this.addOverview(navigation.scope());
		} else {
			this.addCategoryPage(navigation.current().category().orElseThrow());
		}

		this.updateChrome();
		this.repositionElements();
		this.restoreCurrentViewState();
	}

	/**
	 * A frame size edit changes the display slider's maximum and can change the
	 * inline adjustment notice. The native slider must not be replaced
	 * mid-drag, so the rebuild happens after the gesture's final input dispatch
	 * instead of inside the value callback. Its keyboard adjustment mode is
	 * carried through the replacement, so held arrow keys keep adjusting.
	 */
	private void rebuildAfterSizeSliderEdit() {
		if (!this.sizeSliderEditPending) {
			return;
		}
		this.sizeSliderEditPending = false;
		this.sizeSliderAdjustment.carry(() -> this.rebuildPage(true));
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
		this.primaryButton.setTooltip(Tooltip.create(LanguageUtils.settings("navigation")
			.path(overview ? "done" : "back", "tooltip").get()));
		this.resetAllButton.visible = overview && navigation.scope() == Scope.CLIENT;
		this.resetAllButton.active = this.resetAllButton.visible;
	}

	private void addOverview(Scope scope) {
		if (scope == Scope.SERVER) {
			this.addServerStatus();
		}

		final @Unmodifiable List<Category> categories = SettingsNavigationModel.categories(scope);
		for (int index = 0; index < categories.size(); index += 2) {
			Category category = categories.get(index);
			Button button = this.createCategoryButton(category);
			if (index + 1 < categories.size()) {
				Category right = categories.get(index + 1);
				this.addPair(category.id(), button, right.id(), this.createCategoryButton(right));
			} else {
				this.addFullWidth(category.id(), button);
			}
		}
	}

	private Button createCategoryButton(Category category) {
		final var button = Button.builder(
				LanguageUtils.settings("category").path(category.id(), "button").get(),
				ignored -> this.openCategory(category))
				.bounds(0, 0, SettingsScreenLayout.SMALL_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
				.build();
		button.setTooltip(Tooltip.create(Component.translatable(SettingsCategoryCatalog.categoryTooltipKey(category))));
		return button;
	}

	private void addCategoryPage(Category category) {
		if (category.scope() == Scope.SERVER && category != Category.SERVER_PRESENTATION) {
			this.addServerStatus();
			if (!serverSettings.canView()) {
				return;
			}
		}

		for (var section : SettingsCategoryCatalog.sections(category)) {
			var heading = this.addHeading(Component.translatable(section.headingKey()));
			if (category == Category.SERVER_PRESENTATION) {
				heading.setTooltip(Tooltip.create(LanguageUtils.settings("presentation.server.tooltip").get()));
			}
			for (Row row : section.rows()) this.addSettingRow(row);
		}
		if (category.scope() == Scope.SERVER && category != Category.SERVER_PRESENTATION && this.serverValidationMessage != null) {
			this.addFullWidth("server_validation", this.createServerValidationLabel());
		}
	}

	private void addSettingRow(Row row) {
		if (row.settings().size() == 2) {
			Setting left = row.settings().getFirst();
			Setting right = row.settings().getLast();
			this.addPair(left.id(), this.createSettingWidget(left), right.id(), this.createSettingWidget(right));
			return;
		}
		Setting setting = row.settings().getFirst();
		switch (setting) {
			case CHANNEL -> this.addChannelRow();
			case MS_TO_REGENERATE, RATE_LIMIT, SYNC_DURATION -> this.addServerIntegerRow(setting);
			case PRESENTATION_SERVER_POLICY -> this.addPresentationServerPanel();
			default -> {
				this.addFullWidth(setting.id(), this.createSettingWidget(setting));
				if (setting == Setting.INVENTORY_FRAME_BYTE_MULTIPLIER) this.addSizeAdjustmentNotice();
			}
		}
	}

	private AbstractWidget createSettingWidget(Setting setting) {
		if (setting.inventoryField() != null) {
			return this.createServerInventoryWidget(setting);
		}
		if (setting == Setting.OPEN_CLIENT_CONFIG) return this.createOpenClientConfigButton();
		if (setting == Setting.DEFAULT_CHANNEL_MODE) return this.createServerChannelModeButton();
		if (setting == Setting.PLAYER_TRACKING_ENABLED) return this.createServerPlayerTrackingButton();
		if (setting == Setting.BUDGET_DELAY_NOTIFICATIONS_ENABLED) return this.createServerBudgetDelayNotificationsButton();
		final OptionInstance<?> option = switch (setting) {
			case PING_DISTANCE -> this.getPingDistanceOption();
			case MARKER_DISPLAY_DURATION -> this.getMarkerDisplayDurationOption();
			case PING_SIZE -> this.getPingSizeOption();
			case ITEM_ICON_VISIBLE -> this.getItemIconsVisibleOption();
			case DIRECTION_INDICATOR_VISIBLE -> this.getDirectionIndicatorVisibleOption();
			case PLAYER_INFO_MODE -> this.getPlayerInfoModeOption();
			case TEAM_COLOR_MODE -> this.getTeamColorModeOption();
			case MARK_TRANSPARENT_BLOCKS -> this.getMarkTransparentBlocksOption();
			case MARK_BLACKLISTED_TARGETS -> this.getMarkBlacklistedTargetsOption();
			case MARK_FLUIDS -> this.getMarkFluidsOption();
			case SPATIAL_ROOT_DISTANCE -> this.getSpatialRootDistanceOption();
			case SPATIAL_SUBMENU_RADIUS_SCALE -> this.getSpatialSubmenuRadiusScaleOption();
			case SPATIAL_INVENTORY_MAX_WIDTH_FRACTION -> this.getSpatialInventoryMaxWidthFractionOption();
			case SPATIAL_INVENTORY_MAX_HEIGHT_FRACTION -> this.getSpatialInventoryMaxHeightFractionOption();
			case WHEEL_OPACITY -> this.getWheelOpacityOption();
			case WHEEL_TARGET_OPACITY -> this.getWheelTargetOpacityOption();
			case WHEEL_TARGET_FONT_SIZE -> this.getWheelTargetFontSizeOption();
			case WHEEL_OPTION_FONT_SIZE -> this.getWheelOptionFontSizeOption();
			case SPATIAL_SHOW_TRAIL -> this.getSpatialShowTrailOption();
			case SPATIAL_TRAIL_LENGTH -> this.getSpatialTrailLengthOption();
			case SPATIAL_TRAIL_OPACITY -> this.getSpatialTrailOpacityOption();
			case SPATIAL_REDUCE_MOTION -> this.getSpatialReduceMotionOption();
			case WHEEL_HOLD_MILLIS -> this.getWheelHoldMillisOption();
			case SPATIAL_GESTURE_SKIP_HOLD_ENABLED -> this.getGestureSkipHoldEnabledOption();
			case SPATIAL_GESTURE_SKIP_HOLD_MILLIS -> this.getGestureSkipHoldMillisOption();
			case LONG_PRESS_COMPATIBILITY_MODE -> this.getLongPressCompatibilityModeOption();
			case LONG_PRESS_COMPATIBILITY_SLICE_MILLIS -> this.getLongPressCompatibilitySliceMillisOption();
			case CANCEL_HALF_CONE_ANGLE_DEGREES -> this.getCancelHalfConeAngleDegreesOption();
			case DOUBLE_CLICK_DANGER_WINDOW -> this.getDoubleClickDangerWindowMsOption();
			case DOUBLE_CLICK_DANGER_ANGLE -> this.getDoubleClickDangerAngleDegreesOption();
			case SPATIAL_DEADZONE -> this.getSpatialDeadzoneOption();
			case SPATIAL_STROKE -> this.getSpatialStrokeOption();
			case SPATIAL_DWELL_MILLIS -> this.getSpatialDwellOption();
			case SPATIAL_HOVER_ENABLED -> this.getSpatialHoverEnabledOption();
			case SPATIAL_HOVER_MILLIS -> this.getSpatialHoverMillisOption();
			case SPATIAL_CONTENT_INTENT_ENABLED -> this.getSpatialContentIntentEnabledOption();
			case SPATIAL_INVENTORY_SELECTION_STAY_OPEN_ENABLED -> this.getSpatialInventorySelectionStayOpenEnabledOption();
			case PRECISE_CAPTURE_PERIOD_TICKS -> this.getPreciseCapturePeriodTicksOption();
			case PING_VOLUME -> this.getPingVolumeOption();
			case CONFIGURATION_NOTICE_SIZE -> this.getConfigurationNoticeSizeOption();
			case ENTITY_BLOCK_RENDER_MODE -> this.getEntityBlockRenderModeOption();
			default -> throw new IllegalArgumentException("setting needs a dedicated row adapter: " + setting);
		};
		final AbstractWidget widget = option.createButton(Game.options, 0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH);
		if (setting == Setting.SPATIAL_GESTURE_SKIP_HOLD_ENABLED || setting == Setting.SPATIAL_GESTURE_SKIP_HOLD_MILLIS)
			widget.active = !config.isLongPressCompatibilityMode();
		if (setting == Setting.DOUBLE_CLICK_DANGER_ANGLE && config.isLongPressCompatibilityMode())
			widget.active = false;
		return widget;
	}

	private void addPair(String leftKey, AbstractWidget left, String rightKey, AbstractWidget right) {
		this.keyedWidgets.put(leftKey, left);
		this.keyedWidgets.put(rightKey, right);
		this.settingsList.addSmall(left, right);
	}

	private void addFullWidth(String key, AbstractWidget widget) {
		this.keyedWidgets.put(key, widget);
		this.settingsList.addSmall(widget, null);
	}

	private StringWidget addHeading(MutableComponent text) {
		final var heading = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			text.withStyle(ChatFormatting.BOLD),
			this.font).alignLeft();
		heading.active = false;
		if (this.font.width(text) > SettingsScreenLayout.contentFor(this.width).width()) {
			heading.setTooltip(Tooltip.create(text));
		}
		this.settingsList.addSmall(heading, null);
		return heading;
	}

	/**
	 * Renders only fields advertised by the accepted server offer.
	 */
	private void addPresentationFieldCatalog(String panelKey, @Nullable PresentationPolicy policy) {
		final PresentationFieldCatalog catalog = this.acceptedCatalog();

		if (catalog.isEmpty()) {
			// An accepted offer with no compatible field is a known empty list;
			// before any offer the same emptiness is unknown, not authoritative.
			final boolean unknown = !this.serverCatalogOffered();
			this.addFullWidth(panelKey + "_catalog_state", this.createPresentationReadOnlyRow(
				LanguageUtils.settings("presentation").path("fields", unknown ? "unknown" : "empty").get()));
			this.addPresentationQuickFeedbackRow(panelKey);
			return;
		}

		if (!this.isPresentationPolicyKnown()) {
			this.addFullWidth(panelKey + "_catalog_unknown", this.createPresentationReadOnlyRow(
				LanguageUtils.settings("presentation").path("fields", "unknown").get()));
		}

		for (PresentationFieldCatalog.Namespace namespace : catalog.namespaces()) {
			this.addPresentationNamespaceHeading(namespace);
			for (PresentationFieldCatalog.Entry entry : namespace.entries()) {
				this.addPresentationFieldRow(panelKey, policy, entry);
			}
		}
		this.addPresentationQuickFeedbackRow(panelKey);
	}

	private void addPresentationFieldRow(
		String panelKey,
		@Nullable PresentationPolicy policy,
		PresentationFieldCatalog.Entry entry
	) {
		final String fieldId = entry.field().id();
		final String key = panelKey + "_field_" + entry.translationSuffix();
		final PresentationFieldOutcome.Outcome outcome = PresentationFieldOutcome.evaluate(
			PresentationFieldOutcome.Role.SERVER_POLICY, policy, fieldId, entry.field().enabledByDefault());

		// The tooltip owns the complete name, outcome, and rule detail; the row
		// itself truncates like every other long settings label.
		final MutableComponent fullText = LanguageUtils.settings("format.name_value").get(
			this.friendlyFieldName(entry), this.outcomeText(outcome.effective()));
		final var label = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			fullText,
			this.font).alignLeft();
		label.active = false;
		label.setTooltip(Tooltip.create(this.fieldTooltip(entry, outcome)));
		this.addFullWidth(key + "_label", label);
		this.settingsList.truncateLabel(label, fullText);

		final boolean editable = PresentationFieldOutcome.canEditServerPolicy(this.presentationPolicyState);
		final String fieldTargetType = selectedPresentationTargetType;

		final Button allow = Button.builder(
			this.fieldRuleButtonText(true, outcome.allowOn()),
			ignored -> this.toggleServerFieldRule(
				fieldTargetType, panelKey, fieldId, true, outcome.allowOn()))
			.bounds(0, 0, SettingsScreenLayout.SMALL_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		allow.active = editable;
		allow.setTooltip(Tooltip.create(this.fieldRuleTooltip(true, outcome)));

		final Button block = Button.builder(
			this.fieldRuleButtonText(false, outcome.blockOn()),
			ignored -> this.toggleServerFieldRule(
				fieldTargetType, panelKey, fieldId, false, outcome.blockOn()))
			.bounds(0, 0, SettingsScreenLayout.SMALL_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		block.active = editable;
		block.setTooltip(Tooltip.create(this.fieldRuleTooltip(false, outcome)));
		this.addPair(key + "_allow", allow, key + "_block", block);
	}

	private void toggleServerFieldRule(String type, String panelKey, String fieldId, boolean white, boolean on) {
		final var state = this.presentationPolicyState;
		if (!PresentationFieldOutcome.canEditServerPolicy(state)) {
			this.setPresentationQuickFeedback(panelKey, this.unavailableServerFeedback(state));
			this.rebuildPage(true);
			return;
		}

		final Operation operation = white
			? PresentationFieldOutcome.toggleAllow(on)
			: PresentationFieldOutcome.toggleBlock(on);
		// Begin the pending correlation before the send: a synchronous state
		// notification may rebuild the page during the call.
		this.presentationPendingOperation = operation;
		this.presentationPendingTargetType = type;
		this.presentationPendingQuickAction = new PresentationQuickAction(panelKey);
		if (!CommonClient.INSTANCE.requestPresentationPolicyMutation(type, operation, fieldId, false)) {
			if (this.presentationPendingOperation != null) {
				this.presentationPendingOperation = null;
				this.presentationPendingQuickAction = null;
				this.setPresentationQuickFeedback(panelKey, PresentationSelectorDraftModel.Feedback.ERROR);
			}
			this.rebuildPage(true);
			return;
		}
		if (this.presentationPendingOperation != null) this.setPresentationQuickFeedback(panelKey, null);
		this.rebuildPage(true);
	}

	private MutableComponent fieldRuleButtonText(boolean white, boolean on) {
		return LanguageUtils.settings("presentation").path("field", white ? "allow" : "block").get(
			LanguageUtils.settings("presentation").path("field", on ? "on" : "off").get());
	}

	private MutableComponent friendlyFieldName(PresentationFieldCatalog.Entry entry) {
		final var localized = LanguageUtils.settings("presentation").path("field", entry.translationSuffix(), "name");
		return I18n.exists(localized.getKey()) ? localized.get() : Component.literal(entry.field().label());
	}

	private MutableComponent outcomeText(PresentationFieldOutcome.Effective effective) {
		final var text = LanguageUtils.settings("presentation").path("outcome");
		return switch (effective) {
			case ALLOWED_BY_RULE -> text.path("allowed_rule").get();
			case ALLOWED_BY_DEFAULT -> text.path("allowed_default").get();
			case ALLOW_RULE_PRIORITY -> text.path("allow_priority").get();
			case BLOCKED_BY_RULE -> text.path("blocked_rule").get();
			case BLOCKED_BY_WHITELIST_ONLY -> text.path("blocked_whitelist").get();
			case DISABLED_BY_DEFAULT -> text.path("disabled_default").get();
			case UNKNOWN -> text.path("waiting").get();
		};
	}

	private Component fieldTooltip(
		PresentationFieldCatalog.Entry entry,
		PresentationFieldOutcome.Outcome outcome
	) {
		final var text = LanguageUtils.settings("presentation");
		final var description = text.path("field", entry.translationSuffix(), "description");
		final MutableComponent tooltip = Component.empty()
			.append(I18n.exists(description.getKey()) ? description.get() : this.friendlyFieldName(entry))
			.append("\n")
			.append(text.path("field", "id").get(Component.literal(entry.field().id())))
			.append("\n")
			.append(text.path("field", "default").get(this.fieldDefaultText(entry)))
			.append("\n")
			.append(text.path("field", "now").get(this.outcomeText(outcome.effective())));
		if (!outcome.matchingWhite().isEmpty()) {
			tooltip.append("\n").append(text.path("field", "matching_white")
				.get(this.selectorListText(outcome.matchingWhite())));
		}
		if (!outcome.matchingBlack().isEmpty()) {
			tooltip.append("\n").append(text.path("field", "matching_black")
				.get(this.selectorListText(outcome.matchingBlack())));
		}
		return tooltip;
	}

	private MutableComponent selectorListText(@Unmodifiable List<String> selectors) {
		MutableComponent text = Component.empty();
		for (int index = 0; index < selectors.size(); index++) {
			if (index > 0) text.append(LanguageUtils.settings("presentation.selector_separator").get());
			text.append(Component.literal(selectors.get(index)));
		}
		return text;
	}

	/** Reserves the localized truncation wrapper before trimming the visible argument. */
	private Component truncatedLabel(Component text, int availableWidth) {
		int width = Math.max(0, availableWidth);
		if (this.font.width(text) <= width) return text;
		int suffixWidth = this.font.width(LanguageUtils.settings("format.truncated").get(Component.empty()));
		if (suffixWidth > width) return Component.empty();
		var visible = this.font.substrByWidth(text, width - suffixWidth);
		MutableComponent argument = Component.empty();
		visible.visit((style, part) -> {
			argument.append(Component.literal(part).withStyle(style));
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		return LanguageUtils.settings("format.truncated").get(argument);
	}

	private Component fieldDefaultText(PresentationFieldCatalog.Entry entry) {
		return LanguageUtils.settings("presentation").path("field", entry.field().enabledByDefault() ? "on" : "off").get();
	}

	private Component fieldRuleTooltip(
		boolean white,
		PresentationFieldOutcome.Outcome outcome
	) {
		return Component.empty()
			.append(LanguageUtils.settings("presentation").path("rule", white ? "allow" : "block", "tooltip").get())
			.append("\n")
			.append(LanguageUtils.settings("presentation").path("field", "now")
				.get(this.outcomeText(outcome.effective())));
	}

	private void addPresentationNamespaceHeading(PresentationFieldCatalog.Namespace namespace) {
		// Any bundled namespace name is used; an unknown namespace keeps its raw
		// id instead of an invented mod brand.
		final String key = namespace.translationKey();
		final MutableComponent heading = I18n.exists(key)
			? Component.translatable(key)
			: Component.literal(namespace.id());
		this.addHeading(heading);
	}

	private void addPresentationQuickFeedbackRow(String panelKey) {
		final @Nullable MutableComponent error = this.presentationQuickFeedback.get(panelKey);
		final MutableComponent message = error == null ? Component.empty()
			: error.copy().withStyle(ChatFormatting.RED);
		final var row = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			message,
			this.font).alignLeft();
		row.active = false;
		this.addFullWidth(panelKey + "_quick_feedback", row);
	}

	private void setPresentationQuickFeedback(String panelKey, PresentationSelectorDraftModel.@Nullable Feedback feedback) {
		if (feedback == null) {
			this.presentationQuickFeedback.remove(panelKey);
		} else {
			this.presentationQuickFeedback.put(panelKey, this.presentationFeedbackText(feedback));
		}
	}

	private void addPresentationAdvancedToggle(String panelKey, @Nullable PresentationPolicy policy) {
		final boolean expanded = this.presentationAdvancedExpanded.contains(panelKey);
		final MutableComponent actionLabel = LanguageUtils.settings("presentation")
			.path("advanced", expanded ? "hide" : "show").get().copy();
		final MutableComponent label = policy == null ? actionLabel
			: LanguageUtils.settings("presentation.advanced.with_counts").get(actionLabel,
				LanguageUtils.settings("presentation").path("advanced", "counts").get(
				Component.literal(Integer.toString(policy.white().size())),
				Component.literal(Integer.toString(policy.black().size()))));
		final var button = Button.builder(label, ignored -> {
			if (!this.presentationAdvancedExpanded.remove(panelKey)) {
				this.presentationAdvancedExpanded.add(panelKey);
			}
			this.rebuildPage(true);
		})
			.bounds(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		button.setTooltip(Tooltip.create(LanguageUtils.settings("presentation.advanced.tooltip").get()));
		this.addFullWidth(panelKey + "_advanced", button);
	}

	private PresentationFieldCatalog acceptedCatalog() {
		final @Nullable var runtime = CommonClient.INSTANCE.getPingRuntime();
		final @Nullable var presentation = runtime == null ? null : runtime.presentation();
		return presentation == null ? PresentationFieldCatalog.empty() : presentation.acceptedCatalog();
	}

	private boolean serverCatalogOffered() {
		final @Nullable var runtime = CommonClient.INSTANCE.getPingRuntime();
		final @Nullable var presentation = runtime == null ? null : runtime.presentation();
		return presentation != null && presentation.catalogOffered();
	}

	/**
	 * Detects an offer accepted while a presentation page is open. The stamp is
	 * read every tick but only a metadata change rebuilds the page, so an open
	 * page is not reconstructed per frame and no caret or viewport is lost.
	 */
	private void updatePresentationCatalogStamp() {
		final long stamp = this.presentationCatalogStamp();
		if (stamp == this.presentationCatalogStamp) {
			return;
		}
		this.presentationCatalogStamp = stamp;
		if (this.navigation.current() == Page.SERVER_PRESENTATION) {
			this.rebuildPage(true);
		}
	}

	private long presentationCatalogStamp() {
		final @Nullable var runtime = CommonClient.INSTANCE.getPingRuntime();
		final @Nullable var presentation = runtime == null ? null : runtime.presentation();
		return presentation == null ? 0L : 1L + presentation.catalogRevision();
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
		field.setTooltip(Tooltip.create(LanguageUtils.settings("presentation.selector.tooltip").get()));
		field.setValue(this.presentationDrafts.draft(slot));
		this.presentationSelectorFields.put(slot, field);
		field.setResponder(text -> {
			this.presentationDrafts.setDraft(slot, text);
			this.setPresentationSlotFeedback(slot, null);
		});
		return field;
	}

	private StringWidget createPresentationSelectorLabel(String selector) {
		final var label = new StringWidget(
			0,
			0,
			SettingsScreenLayout.SMALL_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			Component.literal(selector),
			this.font).alignLeft();
		label.active = false;
		label.setTooltip(Tooltip.create(Component.literal(selector)));
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

	private void addPresentationServerPanel() {
		this.addPresentationServerStatusRows();
		final @Nullable var state = this.presentationPolicyState;
		final var typeButton = Button.builder(
			LanguageUtils.settings("presentation").path("target_type", selectedPresentationTargetType).get(),
			ignored -> {
				int index = PresentationSettings.TARGET_TYPE_IDS.indexOf(selectedPresentationTargetType);
				selectedPresentationTargetType = PresentationSettings.TARGET_TYPE_IDS.get(
					(index + 1) % PresentationSettings.TARGET_TYPE_IDS.size());
				this.rebuildPage(true);
			})
			.bounds(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		typeButton.setTooltip(Tooltip.create(LanguageUtils.settings("presentation.target_type.tooltip").get()));
		this.addFullWidth("presentation_target_type", typeButton);
		this.addHeading(LanguageUtils.settings("presentation").path("fields", "title").get());
		this.addPresentationFieldCatalog(
			"server_" + selectedPresentationTargetType,
			state == null ? null : state.policyFor(selectedPresentationTargetType));
		this.addFullWidth("presentation_server_catalog_note", this.createPresentationReadOnlyRow(
			LanguageUtils.settings("presentation").path("fields", "note").get()));

		final var refresh = Button.builder(
			LanguageUtils.settings("presentation").path("refresh").get(),
			ignored -> this.refreshPresentationPolicy())
			.bounds(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		refresh.active = state != null
			&& state.isConnected()
			&& state.pendingRequestId() == ServerPresentationPolicyState.NO_PENDING_REQUEST;
		refresh.setTooltip(Tooltip.create(LanguageUtils.settings("presentation.refresh.tooltip").get()));
		this.addFullWidth("presentation_server_refresh", refresh);

		String panelKey = "server_" + selectedPresentationTargetType;
		this.addPresentationAdvancedToggle(panelKey,
			state == null ? null : state.policyFor(selectedPresentationTargetType));
		if (!this.presentationAdvancedExpanded.contains(panelKey)) {
			return;
		}

		if (state != null && state.isKnown() && !state.canEdit()) {
			this.addFullWidth("presentation_server_panel_read_only", this.createPresentationReadOnlyRow(
				LanguageUtils.settings("presentation").path("server", "read_only").get()));
		}

		this.addPresentationServerEditableList(true);
		this.addPresentationServerEditableList(false);

		final String whitelistTargetType = selectedPresentationTargetType;
		final var whitelistOnlyButton = Button.builder(
			this.presentationServerWhitelistOnlyText(),
			ignored -> this.setServerWhitelistOnly(whitelistTargetType,
				state != null && !state.rulesFor(whitelistTargetType).whitelistOnly()))
			.bounds(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		whitelistOnlyButton.active = this.canMutateServerPresentation();
		whitelistOnlyButton.setTooltip(Tooltip.create(LanguageUtils.settings("presentation.whitelist_only.tooltip").get()));
		this.addFullWidth(panelKey + "_whitelist_only", whitelistOnlyButton);
	}

	private void addPresentationServerEditableList(boolean white) {
		final String listKey = "server_" + selectedPresentationTargetType + "_" + (white ? "white" : "black");
		final PresentationSelectorDraftModel.Slot slot = PresentationSelectorDraftModel.Slot.server(selectedPresentationTargetType, white);
		this.addHeading(LanguageUtils.settings("presentation").path(white ? "white" : "black").get());

		final @Nullable var state = this.presentationPolicyState;
		if (!this.isPresentationPolicyKnown()) {
			// An unknown route is not an empty authoritative list; a known list
			// stays visible through pending and error states, labelled above.
			this.addPresentationServerUnavailableRow(listKey);
		} else {
			final var rules = state.rulesFor(selectedPresentationTargetType);
			final @Unmodifiable List<String> selectors = PresentationSelectorListModel.copyOf(
				white ? rules.white() : rules.black());
			if (selectors.isEmpty()) {
				this.addPresentationEmptyRow(listKey);
			}

			final boolean editable = this.canMutateServerPresentation();
			for (String selector : selectors) {
				final var label = this.createPresentationSelectorLabel(selector);
				final String mutationTargetType = selectedPresentationTargetType;
				final var remove = Button.builder(
					LanguageUtils.settings("presentation").path("remove").get(),
					ignored -> this.removeServerPresentationSelector(mutationTargetType, white, selector))
					.bounds(0, 0, SettingsScreenLayout.SMALL_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
					.build();
				remove.active = editable;
				remove.setTooltip(Tooltip.create(LanguageUtils.settings("presentation.remove.tooltip").get()));
				this.addPair(listKey + "_label_" + selector, label, listKey + "_remove_" + selector, remove);
				this.settingsList.truncateLabel(label, Component.literal(selector));
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
			ignored -> this.addServerPresentationSelector(slot.targetTypeId(), white, field))
			.bounds(0, 0, SettingsScreenLayout.SMALL_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		add.active = editable;
		add.setTooltip(Tooltip.create(LanguageUtils.settings("presentation.add.tooltip").get()));
		this.addPair(listKey + "_field", field, listKey + "_add", add);
		this.addPresentationSlotFeedbackRow(listKey, slot);
	}

	private void addPresentationServerStatusRows() {
		final var status = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			this.presentationServerStatusText().copy().withStyle(ChatFormatting.GRAY),
			this.font).alignCenter();
		status.active = false;
		this.settingsList.addSmall(status, null);

		final @Nullable MutableComponent permission = this.presentationServerPermissionText();
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
		final @Nullable MutableComponent message = this.presentationGeneralFeedback == null
			? this.presentationWhitelistFeedback.get(selectedPresentationTargetType)
			: this.presentationGeneralFeedback;
		return message == null
			? Component.empty()
			: message.copy().withStyle(ChatFormatting.RED);
	}

	private void setPresentationGeneralFeedback(@Nullable MutableComponent message) {
		this.presentationGeneralFeedback = message;
		if (this.presentationGeneralFeedbackWidget != null) {
			this.presentationGeneralFeedbackWidget.setMessage(this.presentationGeneralFeedbackText());
		}
	}

	private void setPresentationWhitelistFeedback(String type, @Nullable MutableComponent message) {
		if (message == null) this.presentationWhitelistFeedback.remove(type);
		else this.presentationWhitelistFeedback.put(type, message);
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
		PresentationSelectorDraftModel.@Nullable Feedback feedback
	) {
		this.presentationDrafts.setFeedback(slot, feedback);
		final @Nullable StringWidget widget = this.presentationSlotFeedbackWidgets.get(slot);
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
		if (this.settingsList == null || !this.settingsList.hasUsableViewport()
			|| !target.visible || !target.active || target.getWidth() <= 0 || target.getHeight() <= 0) {
			return false;
		}
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

	private @Nullable MutableComponent presentationServerPermissionText() {
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
		return LanguageUtils.settings("format.name_value").get(label,
			LanguageUtils.settings("presentation").path("field",
				state.rulesFor(selectedPresentationTargetType).whitelistOnly() ? "on" : "off").get());
	}

	private MutableComponent presentationFeedback(String key) {
		return LanguageUtils.settings("presentation").path("feedback", key).get();
	}

	/** Maps a correlated wire status to the compact feedback the UI shows. */
	private PresentationSelectorDraftModel.@Nullable Feedback feedbackForStatus(@Nullable Status status) {
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

	private void addServerPresentationSelector(String type, boolean toWhite, EditBox field) {
		final PresentationSelectorDraftModel.Slot slot = PresentationSelectorDraftModel.Slot.server(type, toWhite);
		final var state = this.presentationPolicyState;
		if (state == null || !state.isKnown()) {
			this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.ERROR);
			return;
		}

		final var rules = state.rulesFor(type);
		final var outcome = PresentationSelectorListModel.add(
			toWhite ? rules.white() : rules.black(),
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
				this.presentationPendingTargetType = type;
				if (!CommonClient.INSTANCE.requestPresentationPolicyMutation(type, operation, selector, false)) {
					if (this.presentationPendingOperation != null) {
						this.presentationPendingOperation = null;
						this.presentationDrafts.abandonSubmission(slot);
						this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.ERROR);
					}
					return;
				}
				if (this.presentationPendingOperation != null) this.presentationDrafts.clearFeedback(slot);
				this.rebuildPage(true);
			}
		}
	}

	private void removeServerPresentationSelector(String type, boolean fromWhite, String selector) {
		final PresentationSelectorDraftModel.Slot slot = PresentationSelectorDraftModel.Slot.server(type, fromWhite);
		if (!this.canMutateServerPresentation()) {
			this.setPresentationSlotFeedback(slot, this.unavailableServerFeedback(this.presentationPolicyState));
			return;
		}
		final Operation operation = fromWhite ? Operation.REMOVE_WHITE : Operation.REMOVE_BLACK;
		this.presentationPendingOperation = operation;
		this.presentationPendingTargetType = type;
		if (!CommonClient.INSTANCE.requestPresentationPolicyMutation(type, operation, selector, false)) {
			if (this.presentationPendingOperation != null) {
				this.presentationPendingOperation = null;
				this.setPresentationSlotFeedback(slot, PresentationSelectorDraftModel.Feedback.ERROR);
			}
			return;
		}
		if (this.presentationPendingOperation != null) this.presentationDrafts.clearFeedback(slot);
		this.rebuildPage(true);
	}

	private void setServerWhitelistOnly(String type, boolean value) {
		if (!this.canMutateServerPresentation()) {
			this.setPresentationWhitelistFeedback(type, this.presentationFeedbackText(
				this.unavailableServerFeedback(this.presentationPolicyState)));
			return;
		}
		this.presentationPendingOperation = Operation.SET_WHITELIST_ONLY;
		this.presentationPendingTargetType = type;
		if (!CommonClient.INSTANCE.requestPresentationPolicyMutation(type, Operation.SET_WHITELIST_ONLY, "", value)) {
			if (this.presentationPendingOperation != null) {
				this.presentationPendingOperation = null;
				this.setPresentationWhitelistFeedback(type, this.presentationFeedbackText(
					PresentationSelectorDraftModel.Feedback.ERROR));
			}
			return;
		}
		if (this.presentationPendingOperation != null) this.setPresentationWhitelistFeedback(type, null);
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
		this.presentationPendingTargetType = null;
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

		if (this.navigation.current() == Page.SERVER_PRESENTATION) {
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
		final String pendingTargetType = this.presentationPendingTargetType;
		final PresentationQuickAction quickAction = this.presentationPendingQuickAction;
		this.presentationPendingOperation = null;
		this.presentationPendingTargetType = null;
		this.presentationPendingQuickAction = null;

		if (!state.isConnected()) {
			this.presentationDrafts.resetSubmission();
			this.presentationDrafts.clearAllFeedback();
			this.presentationGeneralFeedback = null;
			this.presentationWhitelistFeedback.clear();
			if (quickAction != null) {
				this.presentationQuickFeedback.remove(quickAction.panelKey());
			}
			return;
		}

		if (quickAction != null) {
			// A quick field toggle never owns a draft slot; report its outcome in
			// the panel's own feedback row instead of clearing advanced drafts.
			if (state.lastStatus() == Status.OK && !state.lastRequestTimedOut()) {
				this.presentationQuickFeedback.remove(quickAction.panelKey());
			} else {
				this.setPresentationQuickFeedback(quickAction.panelKey(), state.lastRequestTimedOut()
					? PresentationSelectorDraftModel.Feedback.TIMEOUT
					: this.feedbackForStatus(state.lastStatus()));
			}
			return;
		}

		if (operation == Operation.SET_WHITELIST_ONLY) {
			if (state.lastStatus() == Status.OK && !state.lastRequestTimedOut()) {
				this.setPresentationWhitelistFeedback(pendingTargetType, null);
			} else {
				this.setPresentationWhitelistFeedback(pendingTargetType, this.presentationFeedbackText(
					state.lastRequestTimedOut()
						? PresentationSelectorDraftModel.Feedback.TIMEOUT
						: this.feedbackForStatus(state.lastStatus())));
			}
			return;
		}

		final PresentationSelectorDraftModel.Slot slot = PresentationSelectorDraftModel.Slot.server(pendingTargetType,
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
		final var label = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			LanguageUtils.settings("channel").get(),
			this.font).alignLeft();
		label.active = false;
		label.setTooltip(Tooltip.create(LanguageUtils.settings("channel.tooltip").get()));
		this.settingsList.addSmall(label, null);

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
		this.addFullWidth(Setting.CHANNEL.id(), this.channelTextField);
	}

	private Button createOpenClientConfigButton() {
		final Button button = Button.builder(
			LanguageUtils.settings("open_client_config").get(),
			ignored -> this.openClientConfig())
			.bounds(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT)
			.build();
		button.setTooltip(Tooltip.create(LanguageUtils.settings("open_client_config").path("tooltip").get()));
		return button;
	}

	private Button createServerChannelModeButton() {
		final var button = this.createServerValueButton(
			"default_channel_mode",
			() -> LanguageUtils.of("value", this.serverSettings.defaultChannelMode().toString()).get(),
			this.serverSettings::cycleDefaultChannelMode);
		button.active = this.serverSettings.canEdit();
		return button;
	}

	private Button createServerPlayerTrackingButton() {
		final var button = this.createServerValueButton(
			"player_tracking_enabled",
			() -> LanguageUtils.of(
				"value",
				this.serverSettings.playerTrackingEnabled() ? "enabled" : "disabled").get(),
			this.serverSettings::togglePlayerTracking);
		button.active = this.serverSettings.canEdit();
		return button;
	}

	private Button createServerBudgetDelayNotificationsButton() {
		final var button = this.createServerValueButton(
			Setting.BUDGET_DELAY_NOTIFICATIONS_ENABLED.id(),
			() -> LanguageUtils.of("value",
				this.serverSettings.budgetDelayNotificationsEnabled() ? "enabled" : "disabled").get(),
			this.serverSettings::toggleBudgetDelayNotifications);
		button.active = this.serverSettings.canEdit();
		return button;
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

	private AbstractWidget createServerInventoryWidget(Setting setting) {
		final Field inventoryField = java.util.Objects.requireNonNull(setting.inventoryField());
		if (inventoryField.isBoolean()) {
			return createServerInventoryBooleanButton(setting, this.serverSettings);
		}
		if (inventoryField.isSizeScale()) {
			return this.createServerSizeScaleSlider(setting);
		}
		final var slider = InventoryPerformanceOption.slider(LanguageUtils.settings(setting.id()),
			inventoryField, this.serverSettings, () -> this.serverValidationMessage = null);
		return slider;
	}

	/** The production full-width boolean factory, also exercised headlessly without a screen session. */
	static Button createServerInventoryBooleanButton(Setting setting, ServerSettingsModel model) {
		final Field field = java.util.Objects.requireNonNull(setting.inventoryField());
		if (!field.isBoolean()) throw new IllegalArgumentException("not a boolean: " + setting);
		final Supplier<Component> caption = () -> LanguageUtils.settings("inventory.value").get(
			LanguageUtils.settings(setting.id()).get(),
			LanguageUtils.of("value", model.inventoryBoolean(field) ? "enabled" : "disabled").get());
		final var button = Button.builder(caption.get(), clicked -> {
			model.toggleInventoryBoolean(field);
			clicked.setMessage(caption.get());
		}).bounds(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT).build();
		button.setTooltip(Tooltip.create(LanguageUtils.settings(setting.id()).path("tooltip").get()));
		button.active = model.canEdit();
		return button;
	}

	/**
	 * The two size leaves are ordered-position sliders shown as physical KiB
	 * sizes. The display maximum follows the current frame draft; the model
	 * reconciles a lowering frame edit atomically and records the inline notice.
	 */
	private AbstractWidget createServerSizeScaleSlider(Setting setting) {
		final Field field = java.util.Objects.requireNonNull(setting.inventoryField());
		final InventorySizeScale scale = field.sizeScale();
		final IntSupplier maximumPosition = field == Field.DISPLAY_BYTE_MULTIPLIER
			? this.serverSettings::maximumDisplayPosition
			: () -> scale.legalValues().size() - 1;
		final var slider = InventorySizeOption.slider(
			LanguageUtils.settings(setting.id()),
			scale,
			maximumPosition,
			() -> LanguageUtils.settings(setting.id()).path("tooltip").get(),
			() -> scale.valueAt(this.serverSettings.sizeScalePosition(field)),
			value -> this.onSizeScaleSliderChanged(field, value),
			this.sizeSliderAdjustment);
		slider.active = this.serverSettings.canEdit();
		return slider;
	}

	private void onSizeScaleSliderChanged(Field field, BigDecimal value) {
		this.serverValidationMessage = null;
		this.serverSettings.setSizeScalePosition(field, field.sizeScale().indexOf(value));
		// The label, the display maximum, and the adjustment notice are rebuilt
		// once the native gesture has finished.
		this.sizeSliderEditPending = true;
	}

	/** The polite inline notice that explains a frame-priority display reduction. */
	private void addSizeAdjustmentNotice() {
		final @Nullable var adjustment = this.serverSettings.sizeAdjustment();
		if (adjustment == null) {
			return;
		}
		final var notice = new StringWidget(
			0,
			0,
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			LanguageUtils.settings("inventory").path("size_adjusted").get(
				InventorySizeOption.kibLabel(InventorySizeScale.DISPLAY, adjustment.displayMultiplier()),
				InventorySizeOption.kibLabel(InventorySizeScale.FRAME, adjustment.frameMultiplier())).withStyle(ChatFormatting.YELLOW),
			this.font).alignLeft();
		notice.active = false;
		this.settingsList.addSmall(notice, null);
	}

	private void addServerStatus() {
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
		@Nullable String key = this.currentFocusKey();
		if (key == null && this.settingsList != null && !this.settingsList.hasUsableViewport()
			&& (this.getFocused() == null || this.getFocused() == this.settingsList)) {
			key = navigation.focusKey();
		}
		this.saveCurrentViewState(key);
	}

	private void saveCurrentViewState(@Nullable String focusKey) {
		if (this.settingsList == null) {
			return;
		}
		navigation.saveViewStateBeforeNavigation(
			(int) Math.round(this.settingsList.getScrollAmount()),
			focusKey);
	}

	private @Nullable String currentFocusKey() {
		final @Nullable SettingsOptionsList list = this.settingsList;
		final @Nullable Supplier<@Nullable AbstractWidget> listFocusedWidget = list == null ? null : list::focusedWidget;
		return resolveFocusKey(this.getFocused(), list, listFocusedWidget, this.keyedWidgets);
	}

	/**
	 * Resolves the saved focus key while the options list may not exist yet, as
	 * during the permission update in init; the list child is only requested from
	 * a present list.
	 */
	static @Nullable String resolveFocusKey(
		@Nullable GuiEventListener focused,
		@Nullable GuiEventListener list,
		@Nullable Supplier<@Nullable AbstractWidget> listFocusedWidget,
		Map<String, AbstractWidget> keyedWidgets
	) {
		@Nullable GuiEventListener resolved = focused;
		if (list != null && resolved == list) {
			resolved = java.util.Objects.requireNonNull(listFocusedWidget).get();
		}
		for (var entry : keyedWidgets.entrySet()) {
			if (entry.getValue() == resolved || entry.getValue().isFocused()) {
				return entry.getKey();
			}
		}
		return null;
	}

	private void restoreCurrentViewState() {
		this.settingsList.setClampedScrollAmount(navigation.scrollAmount());
		final @Nullable AbstractWidget target = this.keyedWidgets.get(navigation.focusKey());
		if (target == null || !this.canRestoreFocus(target)) {
			return;
		}
		if (this.getFocused() == this.settingsList && this.settingsList.focusedWidget() == target) {
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
		} else if (this.settingsList != null && this.settingsList.hasUsableViewport()) {
			this.setInitialFocus(this.settingsList);
		}
	}

	private void focusFirstInvalidServerField(int invalidMask) {
		final @Nullable AbstractWidget target;
		if ((invalidMask & ServerConfigUpdate.MS_TO_REGENERATE) != 0) {
			target = this.serverMsToRegenerateField;
		} else if ((invalidMask & ServerConfigUpdate.RATE_LIMIT) != 0) {
			target = this.serverRateLimitField;
		} else if ((invalidMask & ServerConfigUpdate.SYNC_DURATION) != 0) {
			target = this.serverSyncDurationField;
		} else {
			final @Nullable Setting invalid = SettingsCategoryCatalog.firstInvalidInventorySetting(invalidMask);
			target = invalid == null ? null : this.keyedWidgets.get(invalid.id());
		}
		if (target != null) {
			this.clearFocus();
			this.settingsList.focusWidget(target, true);
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
		if (this.navigation.current() == Page.SERVER_PRESENTATION
			|| !this.hasLiveServerConnection()
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
		final boolean forcedToOverview = this.navigation.onServerViewAccessLost(
			this.serverSettings.canView(),
			this.isPresentationPolicyKnown());
		if (permission && navigation.scope() == Scope.SERVER
			&& navigation.current() != Page.SERVER_PRESENTATION) {
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
		if (navigation.scope() == Scope.SERVER && navigation.current() != Page.SERVER_PRESENTATION) {
			this.rebuildPage(true);
		}
	}

	public void onServerDisconnected() {
		this.serverSettings.resetForDisconnect();
		this.serverValidationMessage = null;
		this.presentationPendingQuickAction = null;
		this.presentationPendingOperation = null;
		this.presentationQuickFeedback.clear();
		this.presentationWhitelistFeedback.clear();
		this.presentationPendingTargetType = null;
		this.presentationDrafts.resetSubmission();
		this.presentationDrafts.clearAllFeedback();
		this.saveCurrentViewState();
		final boolean forcedToOverview = this.navigation.onServerViewAccessLost(false, false);
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
		IPlatformNetworkService.INSTANCE.sendToServer(new ServerConfigUpdateC2SPacket(values));
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
			() -> text.path("tooltip").get(),
			config::getPingVolume,
			config::setPingVolume);
	}

	private OptionInstance<Integer> getPingDistanceOption() {
		final var text = LanguageUtils.settings("ping_distance");
		return OptionUtils.ofInt(text.getKey(), 0, MAX_PING_DISTANCE, 16,
			value -> text.get(LanguageUtils.UNIT_METERS.get(value)),
			() -> text.path("tooltip").get(),
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
			config::isItemIconVisible, config::setItemIconVisible,
			() -> LanguageUtils.settings("item_icon_visible.tooltip").get());
	}

	private OptionInstance<Boolean> getMarkTransparentBlocksOption() {
		return OptionUtils.ofBool(LanguageUtils.settings("mark_transparent_blocks").getKey(),
			config::isMarkTransparentBlocks, config::setMarkTransparentBlocks,
			() -> LanguageUtils.settings("mark_transparent_blocks.tooltip").get());
	}

	private OptionInstance<Boolean> getMarkBlacklistedTargetsOption() {
		return OptionUtils.ofBool(LanguageUtils.settings("mark_blacklisted_targets").getKey(),
			config::isMarkBlacklistedTargets, config::setMarkBlacklistedTargets,
			() -> LanguageUtils.settings("mark_blacklisted_targets.tooltip").get());
	}

	private OptionInstance<Boolean> getMarkFluidsOption() {
		return OptionUtils.ofBool(LanguageUtils.settings("mark_fluids").getKey(),
			config::isMarkFluids, config::setMarkFluids,
			() -> LanguageUtils.settings("mark_fluids.tooltip").get());
	}

	private OptionInstance<Boolean> getDirectionIndicatorVisibleOption() {
		return OptionUtils.ofBool(LanguageUtils.settings("direction_indicator_visible").getKey(),
			config::isDirectionIndicatorVisible, config::setDirectionIndicatorVisible,
			() -> LanguageUtils.settings("direction_indicator_visible.tooltip").get());
	}

	OptionInstance<PlayerInfoMode> getPlayerInfoModeOption() {
		final var text = LanguageUtils.settings("player_info_mode");
		return OptionUtils.ofEnum(
			text.getKey(),
			PlayerInfoMode.class,
			mode -> LanguageUtils.of("value", mode.toString()).get(),
			mode -> {
				if (mode != PlayerInfoMode.HOLD) {
					return text.path(mode.toString(), "tooltip").get();
				}
				final var keyPlayerListTitle = Component.translatable(Game.options.keyPlayerList.getName());
				final var keyPlayerListName = Game.options.keyPlayerList.getTranslatedKeyMessage();
				return LanguageUtils.settings("player_info_mode").path("hold", "tooltip")
					.get(keyPlayerListTitle, keyPlayerListName);
			},
			config::getPlayerInfoMode,
			config::setPlayerInfoMode);
	}

	OptionInstance<TeamColorMode> getTeamColorModeOption() {
		final var text = LanguageUtils.settings("team_color_mode");
		return OptionUtils.ofEnum(
			text.getKey(),
			TeamColorMode.class,
			mode -> LanguageUtils.of("value", mode.toString()).get(),
			mode -> text.path(mode.toString(), "tooltip").get(),
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
			() -> text.path("tooltip").get(),
			config::getConfigurationNoticeSize,
			config::setConfigurationNoticeSize);
	}

	private OptionInstance<Integer> getPingSizeOption() {
		final var text = LanguageUtils.settings("ping_size");
		return OptionUtils.ofInt(text.getKey(), 40, 300, 10,
			value -> text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			() -> text.path("tooltip").get(),
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
			() -> text.path("tooltip").get(),
			config::getWheelHoldMillis,
			config::setWheelHoldMillis);
	}

	private OptionInstance<Boolean> getLongPressCompatibilityModeOption() {
		final var text = LanguageUtils.settings("long_press_compatibility_mode");
		return OptionUtils.ofBool(text.getKey(),
			config::isLongPressCompatibilityMode,
			enabled -> {
				config.setLongPressCompatibilityMode(enabled);
				this.inputActions.runAfterDispatch(() -> this.rebuildPage(true));
			},
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

	private OptionInstance<Integer> getSpatialRootDistanceOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		return this.spatialDistanceOption("root_distance", MIN_ROOT_DISTANCE, MAX_ROOT_DISTANCE, ROOT_DISTANCE_STEP,
			selector::getRootDistance, selector::setRootDistance);
	}

	private OptionInstance<java.math.BigDecimal> getSpatialSubmenuRadiusScaleOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("submenu_radius_scale");
		return OptionUtils.ofDecimal(text.getKey(), MIN_SUBMENU_RADIUS_SCALE, MAX_SUBMENU_RADIUS_SCALE, SUBMENU_RADIUS_SCALE_STEP,
			value -> text.get(LanguageUtils.UNIT_MULTIPLIER.get(OptionUtils.formatDecimal(value))),
			() -> text.path("tooltip").get(), selector::getSubmenuRadiusScale, selector::setSubmenuRadiusScale);
	}

	private OptionInstance<BigDecimal> getSpatialInventoryMaxWidthFractionOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("inventory_max_width_fraction");
		return OptionUtils.ofDecimal(text.getKey(), MIN_INVENTORY_MAX_WIDTH_FRACTION, MAX_INVENTORY_MAX_WIDTH_FRACTION,
			INVENTORY_MAX_WIDTH_FRACTION_STEP,
			value -> text.get(LanguageUtils.UNIT_MULTIPLIER.get(OptionUtils.formatDecimal(value))),
			() -> text.path("tooltip").get(), selector::getInventoryMaxWidthFraction, selector::setInventoryMaxWidthFraction);
	}

	private OptionInstance<BigDecimal> getSpatialInventoryMaxHeightFractionOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("inventory_max_height_fraction");
		return OptionUtils.ofDecimal(text.getKey(), MIN_INVENTORY_MAX_HEIGHT_FRACTION, MAX_INVENTORY_MAX_HEIGHT_FRACTION,
			INVENTORY_MAX_HEIGHT_FRACTION_STEP,
			value -> text.get(LanguageUtils.UNIT_MULTIPLIER.get(OptionUtils.formatDecimal(value))),
			() -> text.path("tooltip").get(), selector::getInventoryMaxHeightFraction, selector::setInventoryMaxHeightFraction);
	}

	private OptionInstance<Boolean> getGestureSkipHoldEnabledOption() {
		final var text = LanguageUtils.settings("spatial_selector").path("gesture_skip_hold_enabled");
		return OptionUtils.ofBool(text.getKey(), config::isGestureSkipHoldEnabled, config::setGestureSkipHoldEnabled,
			() -> text.path("tooltip").get());
	}

	private OptionInstance<Integer> getGestureSkipHoldMillisOption() {
		var selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("gesture_skip_hold_millis");
		return OptionUtils.ofInt(text.getKey(), MIN_GESTURE_SKIP_HOLD_MILLIS, MAX_GESTURE_SKIP_HOLD_MILLIS,
			GESTURE_SKIP_HOLD_MILLIS_STEP, value -> text.get(LanguageUtils.UNIT_MILLISECONDS.get(value)),
			() -> text.path("tooltip").get(), selector::getGestureSkipHoldMillis, selector::setGestureSkipHoldMillis);
	}

	private OptionInstance<Integer> spatialDistanceOption(String key, int min, int max, int step,
		Supplier<Integer> getter, Consumer<Integer> setter) {
		final var text = LanguageUtils.settings("spatial_selector").path(key);
		return OptionUtils.ofInt(text.getKey(), min, max, step,
			value -> text.get(LanguageUtils.UNIT_PIXELS.get(value)),
			() -> text.path("tooltip").get(), getter, setter);
	}

	private OptionInstance<Integer> getSpatialDeadzoneOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		return this.spatialDistanceOption("deadzone", MIN_DEADZONE, MAX_DEADZONE, DEADZONE_STEP,
			selector::getDeadzone, selector::setDeadzone);
	}

	private OptionInstance<Integer> getSpatialStrokeOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		return this.spatialDistanceOption("stroke", MIN_STROKE, MAX_STROKE, STROKE_STEP,
			selector::getStroke, selector::setStroke);
	}

	private OptionInstance<Integer> getSpatialDwellOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("dwell_millis");
		return OptionUtils.ofInt(text.getKey(), MIN_DWELL_MILLIS, MAX_DWELL_MILLIS, DWELL_MILLIS_STEP,
			value -> text.get(LanguageUtils.UNIT_MILLISECONDS.get(value)),
			() -> text.path("tooltip").get(), selector::getDwellMillis, selector::setDwellMillis);
	}

	private OptionInstance<Boolean> getSpatialHoverEnabledOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("hover_enabled");
		return OptionUtils.ofBool(text.getKey(), selector::isHoverEnabled, selector::setHoverEnabled,
			() -> text.path("tooltip").get());
	}

	private OptionInstance<Integer> getSpatialHoverMillisOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("hover_millis");
		return OptionUtils.ofInt(text.getKey(), MIN_HOVER_MILLIS, MAX_HOVER_MILLIS, HOVER_MILLIS_STEP,
			value -> text.get(LanguageUtils.UNIT_MILLISECONDS.get(value)),
			() -> text.path("tooltip").get(), selector::getHoverMillis, selector::setHoverMillis);
	}

	private OptionInstance<Boolean> getSpatialContentIntentEnabledOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("content_intent_enabled");
		return OptionUtils.ofBool(text.getKey(), selector::isContentIntentEnabled, selector::setContentIntentEnabled,
			() -> text.path("tooltip").get());
	}

	private OptionInstance<Integer> getPreciseCapturePeriodTicksOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("precise_capture_period_ticks");
		return OptionUtils.ofInt(text.getKey(),
			MIN_PRECISE_CAPTURE_PERIOD_TICKS,
			MAX_PRECISE_CAPTURE_PERIOD_TICKS,
			PRECISE_CAPTURE_PERIOD_TICKS_STEP,
			value -> text.get(LanguageUtils.UNIT_TICKS.get(value)),
			() -> text.path("tooltip").get(),
			selector::getPreciseCapturePeriodTicks,
			selector::setPreciseCapturePeriodTicks);
	}

	private OptionInstance<Boolean> getSpatialShowTrailOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("show_trail");
		return OptionUtils.ofBool(text.getKey(), selector::isShowTrail, selector::setShowTrail,
			() -> text.path("tooltip").get());
	}

	private OptionInstance<Integer> getSpatialTrailLengthOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("trail_length");
		return OptionUtils.ofInt(text.getKey(), MIN_TRAIL_LENGTH, MAX_TRAIL_LENGTH, TRAIL_LENGTH_STEP,
			value -> text.get(LanguageUtils.UNIT_POINTS.get(value)),
			() -> text.path("tooltip").get(),
			selector::getTrailLength,
			selector::setTrailLength);
	}

	private OptionInstance<Integer> getSpatialTrailOpacityOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("trail_opacity");
		return OptionUtils.ofInt(text.getKey(), MIN_TRAIL_OPACITY, MAX_TRAIL_OPACITY, TRAIL_OPACITY_STEP,
			value -> value == 0
				? text.get(CommonComponents.OPTION_OFF)
				: text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			() -> text.path("tooltip").get(),
			selector::getTrailOpacity,
			selector::setTrailOpacity);
	}

	private OptionInstance<Boolean> getSpatialReduceMotionOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("reduce_motion");
		return OptionUtils.ofBool(text.getKey(), selector::isReduceMotion, selector::setReduceMotion,
			() -> text.path("tooltip").get());
	}

	private OptionInstance<Integer> getWheelOpacityOption() {
		final var text = LanguageUtils.settings("wheel_opacity");
		return OptionUtils.ofInt(text.getKey(), MIN_WHEEL_OPACITY, MAX_WHEEL_OPACITY, WHEEL_OPACITY_STEP,
			value -> value == 0
				? text.get(CommonComponents.OPTION_OFF)
				: text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			() -> text.path("tooltip").get(),
			config::getWheelOpacity,
			config::setWheelOpacity);
	}

	private OptionInstance<Integer> getWheelTargetOpacityOption() {
		final var text = LanguageUtils.settings("wheel_target_opacity");
		return OptionUtils.ofInt(text.getKey(), MIN_WHEEL_TARGET_OPACITY, MAX_WHEEL_TARGET_OPACITY,
			WHEEL_TARGET_OPACITY_STEP,
			value -> value == 0 ? text.get(CommonComponents.OPTION_OFF) : text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			() -> text.path("tooltip").get(),
			config::getWheelTargetOpacity,
			config::setWheelTargetOpacity);
	}

	private OptionInstance<Integer> getWheelOptionFontSizeOption() {
		final var text = LanguageUtils.settings("wheel_font_size");
		return OptionUtils.ofInt(text.getKey(), MIN_WHEEL_FONT_SIZE, MAX_WHEEL_FONT_SIZE, WHEEL_FONT_SIZE_STEP,
			value -> text.get(LanguageUtils.UNIT_PERCENT.get(value)),
			() -> text.path("tooltip").get(),
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
			() -> text.path("tooltip").get(),
			config::getWheelTargetFontSize,
			config::setWheelTargetFontSize);
	}

	private OptionInstance<Integer> getCancelHalfConeAngleDegreesOption() {
		final var text = LanguageUtils.settings("cancel_half_cone_angle_degrees");
		return OptionUtils.ofInt(text.getKey(),
			MIN_CANCEL_HALF_CONE_ANGLE_DEGREES,
			MAX_CANCEL_HALF_CONE_ANGLE_DEGREES,
			CANCEL_HALF_CONE_ANGLE_DEGREES_STEP,
			value -> text.get(LanguageUtils.UNIT_DEGREES.get(value)),
			() -> text.path("tooltip").get(),
			config::getCancelHalfConeAngleDegrees,
			config::setCancelHalfConeAngleDegrees);
	}

	private OptionInstance<Integer> getDoubleClickDangerWindowMsOption() {
		final var text = LanguageUtils.settings("double_click_danger_window_millis");
		return OptionUtils.ofInt(text.getKey(),
			MIN_DOUBLE_CLICK_DANGER_WINDOW_MS,
			MAX_DOUBLE_CLICK_DANGER_WINDOW_MS,
			DOUBLE_CLICK_DANGER_WINDOW_MS_STEP,
			value -> text.get(LanguageUtils.UNIT_MILLISECONDS.get(value)),
			() -> text.path("tooltip").get(),
			config::getDoubleClickDangerWindowMs,
			config::setDoubleClickDangerWindowMs);
	}

	private OptionInstance<BigDecimal> getDoubleClickDangerAngleDegreesOption() {
		final var text = LanguageUtils.settings("double_click_danger_angle_degrees");
		return OptionUtils.ofDecimal(text.getKey(),
			BigDecimal.valueOf(MIN_DOUBLE_CLICK_DANGER_ANGLE_DEGREES),
			BigDecimal.valueOf(MAX_DOUBLE_CLICK_DANGER_ANGLE_DEGREES),
			BigDecimal.valueOf(DOUBLE_CLICK_DANGER_ANGLE_DEGREES_STEP),
			value -> OptionUtils.decimalSettingLabel(text.getKey(), value,
				LanguageUtils.VALUE_OFF, LanguageUtils.UNIT_DEGREES.getKey()),
			() -> text.path("tooltip").get(),
			() -> BigDecimal.valueOf(config.isLongPressCompatibilityMode()
				? 0.0
				: config.getDoubleClickDangerAngleDegrees()),
			value -> config.setDoubleClickDangerAngleDegrees(value.doubleValue()));
	}

	private OptionInstance<Boolean> getSpatialInventorySelectionStayOpenEnabledOption() {
		SpatialSelectorSettings selector = config.getSpatialSelector();
		final var text = LanguageUtils.settings("spatial_selector").path("inventory_selection_stay_open_enabled");
		return OptionUtils.ofBool(text.getKey(),
			selector::isInventorySelectionStayOpenEnabled,
			selector::setInventorySelectionStayOpenEnabled,
			() -> text.path("tooltip").get());
	}

	OptionInstance<EntityBlockRenderMode> getEntityBlockRenderModeOption() {
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
		return this.createServerNumericField(value, responder, tooltipKey, narrationKey, false);
	}

	private EditBox createServerNumericField(String value, Consumer<String> responder,
		String tooltipKey, String narrationKey, boolean decimal) {
		final var field = new EditBox(
			this.font,
			-1,
			-1,
			SettingsScreenLayout.SMALL_WIDGET_WIDTH,
			SettingsScreenLayout.ROW_HEIGHT,
			LanguageUtils.settings(narrationKey).get());
		field.setMaxLength(decimal ? 32 : Integer.toString(Integer.MAX_VALUE).length());
		field.setFilter(text -> text.isEmpty()
			|| text.chars().allMatch(character -> character >= '0' && character <= '9' || decimal && character == '.'));
		field.setValue(value);
		field.setTooltip(Tooltip.create(LanguageUtils.settings(tooltipKey).get()));
		field.setResponder(text -> {
			this.serverValidationMessage = null;
			responder.accept(text);
		});
		return field;
	}

	private MutableComponent serverRowText(Component label, Component value) {
		return LanguageUtils.settings("format.name_value").get(label, value);
	}

	@NotNullByDefault
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

	@NotNullByDefault
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

	@NotNullByDefault
	static final class SettingsOptionsList extends OptionsList {
		private final List<@Unmodifiable List<AbstractWidget>> declaredRows = new ArrayList<>();
		private final Map<AbstractWidget, Component> fullLabels = new HashMap<>();
		private final ToIntFunction<Component> measure;
		private final BiFunction<Component, Integer, Component> truncate;
		private final OptionsSubScreen screen;

		SettingsOptionsList(net.minecraft.client.Minecraft minecraft, int width, OptionsSubScreen screen,
			ToIntFunction<Component> measure, BiFunction<Component, Integer, Component> truncate) {
			super(minecraft, width, screen);
			this.measure = measure;
			this.truncate = truncate;
			this.screen = screen;
		}

		void truncateLabel(AbstractWidget widget, Component fullText) {
			this.fullLabels.put(widget, fullText);
			widget.setMessage(this.truncate.apply(fullText, Math.max(0, widget.getWidth() - 8)));
		}

		private int textWidth(AbstractWidget widget) {
			return this.measure.applyAsInt(this.fullLabels.getOrDefault(widget, widget.getMessage()));
		}

		@Override
		public int getRowWidth() {
			return SettingsScreenLayout.contentFor(this.getWidth()).width();
		}

		@Override
		public int getRowLeft() {
			return this.getX() + SettingsScreenLayout.contentFor(this.getWidth()).left();
		}

		@Override
		protected int getScrollbarPosition() {
			return this.getX() + SettingsScreenLayout.contentFor(this.getWidth()).scrollbarX();
		}

		@Override
		protected boolean scrollbarVisible() {
			return this.hasUsableViewport() && super.scrollbarVisible();
		}

		@Override
		public void addSmall(AbstractWidget left, @Nullable AbstractWidget right) {
			@Unmodifiable List<AbstractWidget> row = right == null ? List.of(left) : List.of(left, right);
			this.declaredRows.add(row);
			for (var physical : SettingsRowWidgets.rowsFor(SettingsScreenLayout.contentFor(this.getWidth()), List.of(row), this::textWidth)) {
				super.addSmall(physical.getFirst(), physical.size() == 2 ? physical.getLast() : null);
				this.positionEntry(this.children().size() - 1);
			}
		}

		@Override
		public void updateSizeAndPosition(int width, int height, int top) {
			final @Nullable AbstractWidget focused = this.focusedWidget();
			final @Nullable OptionsList.Entry focusedEntry = this.getFocused();
			double scroll = this.getScrollAmount();
			boolean dragging = this.isDragging();
			boolean childDragging = focusedEntry != null && focusedEntry.isDragging();
			var existing = new HashMap<List<? extends GuiEventListener>, OptionsList.Entry>();
			for (var entry : this.children()) existing.put(entry.children(), entry);
			super.updateSizeAndPosition(width, height, top);
			var physicalRows = SettingsRowWidgets.rowsFor(SettingsScreenLayout.contentFor(width), this.declaredRows, this::textWidth);
			// The old entry containers are disposable, but never blur or recreate a
			// usable child: text/caret, slider adjustment, and callbacks stay intact.
			super.setFocused((GuiEventListener) null);
			this.clearEntries();
			for (var row : physicalRows) {
				@Nullable OptionsList.Entry retained = existing.get(row);
				this.addEntry(retained != null ? retained : OptionsList.Entry.small(
					row.getFirst(), row.size() == 2 ? row.getLast() : null, this.screen));
			}
			this.positionEntries();
			if (this.hasUsableViewport()) {
				if (focused != null && focused.visible && focused.active) this.focusWidget(focused);
				this.setDragging(dragging);
				final @Nullable OptionsList.Entry restored = this.getFocused();
				if (restored != null) restored.setDragging(childDragging);
			} else {
				this.suspendInput();
			}
			this.setClampedScrollAmount(scroll);
		}

		boolean hasUsableViewport() {
			return this.getRowWidth() > 8 && this.getHeight() >= SettingsScreenLayout.ROW_HEIGHT;
		}

		/** Hidden native sliders still accept keys; the container must fence dispatch. */
		private void suspendInput() {
			for (var entry : this.children()) {
				entry.setFocused((GuiEventListener) null);
				entry.setDragging(false);
				for (var child : entry.children()) child.setFocused(false);
			}
			super.setFocused((GuiEventListener) null);
			this.setDragging(false);
			this.updateScrollingState(0, 0, -1);
		}

		private boolean acceptsInput() {
			if (this.hasUsableViewport()) return true;
			this.suspendInput();
			return false;
		}

		private boolean acceptsFocusedInput() {
			if (!this.acceptsInput()) return false;
			@Nullable AbstractWidget focused = this.focusedWidget();
			return focused != null && focused.visible && focused.active && focused.getWidth() > 0;
		}

		@Override
		public void setFocused(@Nullable GuiEventListener focused) {
			if (focused != null && !this.hasUsableViewport()) {
				this.suspendInput();
				return;
			}
			super.setFocused(focused);
		}

		@Override
		public @Nullable ComponentPath getCurrentFocusPath() {
			return this.acceptsInput() ? super.getCurrentFocusPath() : null;
		}

		@Override
		public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
			return this.acceptsFocusedInput() && super.keyPressed(keyCode, scanCode, modifiers);
		}

		@Override
		public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
			return this.acceptsFocusedInput() && super.keyReleased(keyCode, scanCode, modifiers);
		}

		@Override
		public boolean charTyped(char character, int modifiers) {
			return this.acceptsFocusedInput() && super.charTyped(character, modifiers);
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			return this.acceptsInput() && super.mouseClicked(mouseX, mouseY, button);
		}

		@Override
		public boolean mouseReleased(double mouseX, double mouseY, int button) {
			return this.acceptsInput() && super.mouseReleased(mouseX, mouseY, button);
		}

		@Override
		public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
			return this.acceptsInput() && super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
		}

		@Override
		public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
			return this.acceptsInput() && super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}

		@Override
		public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
			return this.acceptsInput() ? super.nextFocusPath(event) : null;
		}

		@Override
		public void setClampedScrollAmount(double amount) {
			super.setClampedScrollAmount(amount);
			this.positionEntries();
		}

		private void positionEntries() {
			for (int index = 0; index < this.children().size(); index++) this.positionEntry(index);
		}

		private void positionEntry(int index) {
			var content = SettingsScreenLayout.contentFor(this.getWidth());
			var absolute = new SettingsScreenLayout.Content(this.getX() + content.left(), content.width(),
				this.getX() + content.scrollbarX());
			SettingsRowWidgets.position(absolute, this.getRowTop(index), this.getEntry(index).children().stream()
				.map(AbstractWidget.class::cast).toList());
			if (!this.hasUsableViewport()) {
				for (var child : this.getEntry(index).children()) ((AbstractWidget) child).visible = false;
			}
			for (var child : this.getEntry(index).children()) {
				var widget = (AbstractWidget) child;
				@Nullable Component fullText = this.fullLabels.get(widget);
				if (fullText != null) widget.setMessage(this.truncate.apply(fullText, Math.max(0, widget.getWidth() - 8)));
			}
		}

		/**
		 * Native OptionsList.Entry.render hardcodes 310-wide, 160-offset positions.
		 * Keep its native entry children, input, focus, scrolling and narration, but
		 * render their native widgets at the same responsive bounds used for hits.
		 */
		@Override
		protected void renderItem(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
			int index, int rowLeft, int top, int rowWidth, int rowHeight) {
			if (this.getRowWidth() == 0) return;
			this.positionEntry(index);
			var entry = this.getEntry(index);
			entry.renderBack(graphics, index, top, rowLeft, rowWidth, rowHeight, mouseX, mouseY,
				entry == this.getHovered(), partialTick);
			// ContainerObjectSelectionList does not paint selected-row backgrounds.
			for (GuiEventListener child : entry.children()) {
				((AbstractWidget) child).render(graphics, mouseX, mouseY, partialTick);
			}
		}

		@Override
		public boolean isMouseOver(double mouseX, double mouseY) {
			return this.hasUsableViewport() && super.isMouseOver(mouseX, mouseY);
		}

		private void resetEntries() {
			this.suspendInput();
			this.declaredRows.clear();
			this.fullLabels.clear();
			this.clearEntries();
		}

		private @Nullable AbstractWidget focusedWidget() {
			final @Nullable GuiEventListener focusedEntry = this.getFocused();
			if (focusedEntry instanceof OptionsList.Entry entry && entry.getFocused() instanceof AbstractWidget widget) {
				return widget;
			}
			return null;
		}

		private void focusWidget(AbstractWidget widget) {
			this.focusWidget(widget, false);
		}

		private void focusWidget(AbstractWidget widget, boolean reveal) {
			if (!this.hasUsableViewport() || !widget.visible || !widget.active || widget.getWidth() <= 0) return;
			SettingsListFocus.focus(this.children(), entry -> entry.children().contains(widget),
				entry -> {
					if (entry.getFocused() != widget) entry.setFocused(widget);
					this.setFocused(entry);
				}, this::ensureVisible, reveal);
		}
	}
}
