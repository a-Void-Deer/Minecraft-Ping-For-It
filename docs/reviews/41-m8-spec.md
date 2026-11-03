# 41 · M8 规格：设置界面/命令 + 资源重载 v1 + 自定义 ping 贴图（只读分析）

> 分析：1号 repo-analyst｜2026-10-31｜**只读源码 + javap，未运行游戏**
> 分支：`exp/vanilla-lines-m7a` @ `1c0a452`（干净）；任务顺序 M8 → C2 → M9 → M10。
> 上游：`Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1`（`UP:`）；当前：`src/…`（`CUR:`）；26.3：named jar javap。

## 0. 一句话结论（D）

**M8 是「重写级」，不是改名级**：命令与资源逻辑基本可直接搬，但 **设置界面整体建立在 26.3 已替换的 `GuiGraphics` 即时渲染 API 上**（`render()` → `extractRenderState(GuiGraphicsExtractor)`、`renderWidget` → `extractWidgetRenderState`），`SettingsScreen`（1345 行）+ 7 个 helper 需要逐类改写；资源重载则是 v0→v1 的 API 迁移（不是重写）。**预估 5–8 人日**（界面 3–5d、资源 0.5–1d、notice/命令 1–1.5d，含审查与实机）。最可能踩坑的 3 点见 §6。

## 1. 上游设置界面（A.1 / A.5）

### 1.1 文件与分工（`UP:common/src/main/java/nx/pingwheel/common/screen/`）

| 文件 | 行数 | 职责 |
|---|---|---|
| `SettingsScreen.java` | 1345 | 主界面：`OptionsSubScreen` + `OptionsList` + `TabNavigationBar` + 分页/滚动/保存/重置/服务端设置 |
| `SettingsScreenLayout.java` | 95 | 纯布局常量与 `calculate(width,height,…)`（header 57 / leaf 33 / footer 70 等，`:14-25,58`） |
| `SettingsTitleWidget.java` | 167 | 标题（`AbstractWidget` 子类，`renderWidget(GuiGraphics…)` `:81`） |
| `SettingsNavigationModel.java` | 250 | `Scope` / `Category` / `Page` 枚举与导航状态（滚动位置、焦点 key `:247`） |
| `SettingsCategoryCatalog.java` | 112 | **30 个 Setting 枚举 + 8 个分类→控件映射**（`:16-46,71-108`） |
| `ServerSettingsModel.java` | 380 | 服务端设置会话（请求/快照/校验/提交） |
| `OptionUtils.java` | 80 | `ofInt/ofFloat/ofBool/ofEnum` → `OptionInstance`（`:16-78`） |
| `DeferredActionCoordinator.java` | 54 | 输入动作延迟协调 |

### 1.2 界面结构（`SettingsScreen` 关键点）

| 项 | 内容 | 证据 |
|---|---|---|
| 基类 | `extends OptionsSubScreen`（26.3 仍有此类） | `:54` |
| 状态字段 | `config`、`serverSettings`、`navigation`、`keyedWidgets`、`scopeTabs`、`settingsList`、`scopeTabBar`、`primaryButton`、`resetAllButton`、`channelTextField`、服务端 3 个 EditBox、`pageTitle` 等 | `:58-83` |
| 初始化 | `init()` 构建标题/范围 tab/列表/主按钮/重置按钮 | `:123-`；按钮 `:145` |
| 分页 | `addOverview(scope)` / `addCategoryPage(category)` / `addSetting(category, setting)` | `:413,434,465` |
| 控件添加 | `addOption` / `addHalfWidth` / `addFullWidth` / `addSubgroup` | `:503,513,523,537` |
| 特殊行 | 频道输入框、打开客户端配置、服务端频道模式/玩家追踪/整数行、服务端状态 | `:550,578,588,596,606,649` |
| 保存 | `saveSafely() && commitServerSettings()` | `:796` |
| 关闭语义 | `suppressSaveOnClose` 控制取消/保存；`onClose()` `:213` | `:822,983` |
| 重置 | `resetToDefaults()` + 重建条目 | `:985,1322` |
| 滚动/焦点记忆 | `saveCurrentViewState()` / 按 key 恢复 | `:709-713` |

### 1.3 分类与字段（`SettingsCategoryCatalog`）

| 范围 | 分类 | 控件（Setting id） |
|---|---|---|
| 客户端 | `MARKER_DISPLAY` | ping_distance, marker_display_duration, ping_size, item_icon_visible, direction_indicator_visible, player_info_mode, team_color_mode（`:71-78`） |
| 客户端 | `TARGET_SELECTION` | pass_through_transparent_blocks, mark_blacklisted_targets, mark_fluids（`:79-82`） |
| 客户端 | `WHEEL_APPEARANCE` | wheel_inner_radius, wheel_outer_radius, wheel_opacity, wheel_target_font_size, wheel_font_size（`:83-88`） |
| 客户端 | `INPUT_INTERACTION` | wheel_hold_millis, wheel_timeout_millis, long_press_compatibility_mode/slice_millis, cancel_half_cone_angle_degrees（`:89-94`） |
| 客户端 | `CHANNEL_NOTICES` | channel, ping_volume, configuration_notice_size（`:95-98`） |
| 客户端 | `GEOMETRY_CONFIG` | entity_block_render_mode, open_client_config（`:99-101`） |
| 服务端 | `CHANNEL_PLAYERS` / `SEND_RATE` / `MARKER_DURATION` | default_channel_mode, player_tracking_enabled / ms_to_regenerate, rate_limit / sync_duration（`:102-108`） |

**控件工厂**：`OptionUtils.ofInt/ofFloat` 用 `OptionInstance.IntRange(min/step,max/step).xmap`（`OptionUtils.java:16-49`）、`ofBool` 用 `createBoolean`（`:51-64`）、`ofEnum` 用 `OptionInstance.Enum` + `Codec.STRING.xmap`（`:66-78`）。

## 2. 命令（A.2）

| 命令 | 行为 | 证据 |
|---|---|---|
| `/pingforit` / `/pingforit help` | 输出 3 行帮助（config / channel get / channel set），语言键 `command.*` | `UP:command/ClientCommandBuilder.java:69-90` |
| `/pingforit config` | `Game.tell(() -> Game.setScreen(new SettingsScreen()))` | `:63-67` |
| `/pingforit channel` | 读当前频道，空则 default 文案 | `:27-40` |
| `/pingforit channel <name>` | 长度校验 `MAX_CHANNEL_LENGTH` → `setChannel` + `HANDLER.save()` | `:41-61` |

**当前 port 已基本可用**：`CUR:src/client/java/nx/pingwheel/common/command/ClientCommandBuilder.java:27-67`（channel 完整、config 已用 26.3 `Game.setScreenAndShow(new SettingsScreen())` 打开空壳），帮助 `:69-90`。

## 3. 自定义 ping 贴图与资源重载（A.3）

| 项 | 上游 | 证据 |
|---|---|---|
| 路径 | `assets/pingforit/textures/ping.png`（`ResourceConstants.PING_TEXTURE_ID`） | `UP:resource/ResourceConstants.java:13` |
| 判定 | `hasCustomTexture()` = `resourceManager.getResourceStack(PING_TEXTURE_ID).size() > 1`（自带 1 张 → 用户资源包叠 1 张 → 2） | `ResourceReloadListener.java:19-23,25-34` |
| 注册 | Fabric：`ResourceManagerHelper.get(CLIENT_RESOURCES).registerReloadListener(new IdentifiableResourceReloadListener(){…})` | `UP:fabric/…/FabricClient.java:96-105` |
| 失败回退 | 未加载自定义贴图时 `DrawContext.renderPing` 走内置菱形/物品图标分支 | `UP:render/DrawContext.java:62-70` |

**当前 port**：`hasCustomTexture` 类已不存在（`src/client` 无 resource 包）；`FabricClient` 只有未使用的 `RELOAD_LISTENER_ID` 常量（`CUR:fabric/FabricClient.java:32`）；自定义贴图分支因此**恒为关闭**（`MarkerHudRenderer` 注释记录该 TODO：`:45-46,237`）。**没有 v0 注册**（`AGENTS §7-5` 的卡死路径已摘除），所以 M8b 是「新增 v1 监听器 + 接线」，不是修 bug。

> `26.3 ResourceManager.getResourceStack(Identifier)` 仍存在（`minecraft-common.jar` javap）→ `hasCustomTexture` 逻辑可原样搬。

## 4. 配置提示 / toggle notice（A.4）

| 项 | 上游 | 当前 port |
|---|---|---|
| 状态机 | `SelectionToggleNoticeState`：2s 不透明 + 1s 淡出；单槽、可被替换 | **已有**：`CUR:client/SelectionToggleNoticeState.java`（102 行） |
| 触发 | `InputUtils` 切目标选择时 `show(kind, enabled)` | **已有**：`CUR:client/util/InputUtils.java:141` |
| 渲染 | `SelectionToggleNoticeRenderer` + `SelectionToggleNoticeRenderPolicy`（锚点 `guiHeight*0.25`、缩放 `sizePercent/100`、`isVisibleAtSize>0`） | **缺**：renderer/policy 在 `deferred/render/`；`CommonClient.java:274,357` 是 TODO(M7/M8) |
| HUD 接入 | 上游 `CommonClient.onRenderGUI` 里 `SelectionToggleNoticeRenderer.draw(...)` | 当前 HUD 只有 marker/wheel 两条 pass |

## 5. 当前 port 盘点（B）

| # | 项 | 现状 | 证据 |
|---|---|---|---|
| B1 | config 命令 | 已指向空壳 `SettingsScreen` | `CUR:command/ClientCommandBuilder.java:63-67` |
| B2 | SettingsScreen | **42 行空壳**（只有标题 + 两个空 notify） | `CUR:screen/SettingsScreen.java:23-40` |
| B3 | 可复用界面逻辑 | 全部在 `deferred/screen/`（8 文件 2533 行）但要按 26.3 API 改 | `deferred/screen/*` |
| B4 | ClientConfig | **32 字段**，覆盖 catalog 的客户端设置；`HANDLER` + `saveSafely/resetToDefaults/getConfigPath` 均在 | `CUR:config/ClientConfig.java:17-58,329`；`ConfigHandler.java:98,102,111,119` |
| B5 | 服务端设置 | `ServerConfig` 系列已在 main；`ServerSettingsModel` 在 deferred | `src/main/…/config/ServerConfig*.java`；`deferred/screen/ServerSettingsModel.java` |
| B6 | 语言键 | `en_us.json` **163 键**，其中 `settings.*` **93**、`command.*` **8**（上游资产已复制） | `src/main/resources/assets/pingforit/lang/en_us.json` |
| B7 | 资源监听 | v0 未注册（正确）；v1 未接 | `CUR:fabric/FabricClient.java:32` |

### 5.1 26.3 GUI API 差异（B5 / javap）

| 1.21.1 | 26.3 | 影响 |
|---|---|---|
| `Screen#render(GuiGraphics,int,int,float)` | `Screen#extractRenderState(GuiGraphicsExtractor,int,int,float)`（`Screen` javap） | 所有 screen 渲染方法要改名/改型 |
| `AbstractWidget#renderWidget(GuiGraphics,…)` | `protected abstract extractWidgetRenderState(GuiGraphicsExtractor,int,int,float)`（`AbstractWidget` javap） | 自定义 widget（`SettingsTitleWidget`）必须改 |
| `CydeButton/Button/EditBox` | `Button.builder(...)`、`EditBox(Font,…)` 构造器仍在 | 控件可复用 |
| `TabNavigationBar`（`gui/components/`） | 移到 **`gui/components/tabs/TabNavigationBar`**（含 `$Builder`） | import 变化 |
| `OptionsSubScreen` + `OptionsList` + `OptionInstance` + `addBig/addSmall/addHeader` | 仍在（javap `OptionsList` 方法表） | 设置列表逻辑可复用 |
| `Minecraft#setScreen` | `setScreenAndShow(Screen)`（`Minecraft` javap） | 打开方式已改（当前命令已用新 API） |
| `GuiGraphics` 文本/blit/fill | `GuiGraphicsExtractor.text/blit/fill/pose`（先前任务已核实） | 自定义绘制改型 |

### 5.2 Fabric 资源重载 v1（B4）

| API | 签名（javap v1 jar） | 用法 |
|---|---|---|
| `ResourceLoader.get(PackType)` | `static ResourceLoader get(PackType)` | `PackType.CLIENT_RESOURCES` |
| `registerReloadListener` | `void registerReloadListener(Identifier, PreparableReloadListener)` | 替代 v0 `ResourceManagerHelper` |
| `SimpleReloadListener<T>` | `prepare(SharedState) : T` + `apply(T, SharedState)` + `final reload(SharedState, Executor, PreparationBarrier, Executor)` | 最省事的抽象 |
| 排序 | `addListenerOrdering(Identifier, Identifier)`；`ResourceReloaderKeys.BEFORE_VANILLA/AFTER_VANILLA` | 与语言/贴图顺序协调 |

> v0 jar 里 `ResourceManagerHelper` / `IdentifiableResourceReloadListener` 仍在，但 **AGENTS §7-5 已实证 v0 注册会卡加载条** → M8b 只能走 v1。

## 6. 分阶段建议与验收（C）

### M8a · 设置界面（重写级）

**必做**：把 `deferred/screen/` 8 个类按 26.3 API 迁回（`render`→`extractRenderState`、`renderWidget`→`extractWidgetRenderState`、`TabNavigationBar` 新路径、`Button.builder`/`EditBox` 适配）；接 `/pingforit config` 与设置键（已接线）；保存/取消/重置语义沿用（`saveSafely()` + `suppressSaveOnClose`）；服务端设置区用现有 `ServerConfig*` + S2C 包。
**可延后**：服务端设置区（M8a 可先客户端部分，服务端复用现有 `SettingsScreen.notifyServerConfigSnapshot` 占位）；焦点/滚动记忆的像素级一致。
**验收**：打开不崩；每类控件可改值；Done 保存并立即生效（HUD/轮盘/描边随配置变化）；Cancel 不落盘；重开值持久；`/pingforit channel` 仍正常；语言键无 missing。

### M8b · 资源重载 v1 + 自定义贴图

**必做**：新增 `client/resource/PingTextureReloadListener extends SimpleReloadListener<Integer>`（`prepare` 取 `getResourceStack(PING_TEXTURE_ID).size()`，`apply` 写 `hasCustomTexture` 状态）；在 `FabricClient` 用 `ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(...)` 注册；`MarkerHudRenderer`/`WheelHudRenderer` 接入自定义 12px 贴图分支（上游 `DrawContext:62-111`）。
**可延后**：贴图 atlas 合并、多包优先级细节。
**验收**：默认（1×1 透明占位）视觉不变；放入资源包替换 `ping.png` 后 ping 图标变为自定义贴图；**启动不卡加载条**；删除资源包后回退。

### M8c · 配置提示 + 命令/语言补齐

**必做**：把 `deferred/render/SelectionToggleNoticeRenderer` + `SelectionToggleNoticeRenderPolicy` 迁到 HUD（`HudElementRegistry` + `GuiGraphicsExtractor`），接 `SelectionToggleNoticeState`（已有）；命令输出/帮助语言键核对补齐。
**验收**：按目标选择快捷键出现 2s 提示 + 1s 淡出；`configuration_notice_size=0` 时隐藏；命令输出无 missing key。

## 7. 风险（最可能踩坑的 3 点）

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | **GUI 渲染 API 全换**（`render`→`extractRenderState`，`renderWidget`→`extractWidgetRenderState`） | 1345 行界面 + 自定义 title widget 逐处改写，编译错误密集 | 先做 1 个最小 Screen（标题+1 按钮）跑通 26.3 提取管线再批量迁 |
| R2 | **资源重载 v1 的 reload 签名/排序语义** | 写错导致贴图不生效或加载条卡住 | 用 `SimpleReloadListener`（不再手写 `reload`）；先只注册空监听器验证不卡，再加逻辑 |
| R3 | **服务端设置区**（`ServerSettingsModel` 380 行 + 请求/快照/校验） | 连接服务器时界面空转/状态不同步 | M8a 先交付客户端部分，服务端区单独小任务；用现有 S2C 包与 `notifyServerConfigSnapshot` 接线 |

> 其他次风险：`SettingsScreenLayout` 依赖旧 `OptionsList` 视口计算（`:58-90`）需重算；`TabNavigationBar` 路径变化；语言键已存在（93 个）但需确认无重命名。

## 8. 证据索引

| 主题 | 位置 |
|---|---|
| 上游界面 | `UP:screen/SettingsScreen.java`（1345）；`SettingsScreenLayout.java:14-58`；`SettingsTitleWidget.java:81`；`SettingsNavigationModel.java:18-108,247`；`SettingsCategoryCatalog.java:16-108`；`OptionUtils.java:16-78` |
| 上游命令 | `UP:command/ClientCommandBuilder.java:27-90` |
| 上游资源 | `UP:resource/ResourceReloadListener.java:12-34`；`ResourceConstants.java:13`；`UP:fabric/…/FabricClient.java:96-105` |
| 上游 notice | `UP:render/SelectionToggleNoticeRenderPolicy.java:7-21`；`client/SelectionToggleNoticeState.java:14-16` |
| 当前桩 | `CUR:screen/SettingsScreen.java:7-40`；`CUR:command/ClientCommandBuilder.java:27-67`；`CUR:fabric/FabricClient.java:32`；`CUR:client/util/InputUtils.java:141`；`CommonClient.java:274,357` |
| 当前配置 | `CUR:config/ClientConfig.java:17-58,329`；`ConfigHandler.java:98,102,111,119` |
| 26.3 GUI | `Screen#extractRenderState`、`AbstractWidget#extractWidgetRenderState`、`Minecraft#setScreenAndShow`、`gui/components/tabs/TabNavigationBar`、`OptionsList#addBig/addSmall/addHeader`（javap） |
| Fabric v1 | `fabric-resource-loader-v1-3.1.0`：`ResourceLoader.get/registerReloadListener/addListenerOrdering`；`SimpleReloadListener.prepare/apply`；`ResourceReloaderKeys.BEFORE_VANILLA/AFTER_VANILLA` |
| 26.3 ResourceManager | `getResourceStack(Identifier)` 存在（`minecraft-common.jar` javap） |
