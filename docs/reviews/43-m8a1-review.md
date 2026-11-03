# 43 · M8a-1 复审：设置界面最小闭环

任务：M8a-1（Lead 直派）　审核人：3号　日期：2026-10-31
分支：`exp/vanilla-lines-m7a`（HEAD `accc881`）；对象：未提交的 4 个新 helper + 重写的 `SettingsScreen`。
验证：只读源码 + javap（MC 26.3 `Screen`/`Button`/`GuiGraphicsExtractor`）+ 上游 deferred 源码 + 8 个语言文件 grep + MC `en_us.json` 校验 + **重跑** `bash tools/compile-check.sh`。未跑 Gradle。
编译基线：`main errors=0 classes=266 / client errors=0 classes=110` ✓。

## 结论

**打回（不通过）。** 屏幕可以用命令打开（`CommonClient:235`、`ClientCommandBuilder:65`），控件与布局也能渲染，但有 **4 个阻断问题**：Reset 会替换 config 对象导致后续控件全部失效、标题完全不显示、Cancel 语义与上游不一致、Done 忽略保存失败。另有语言键缺失、javadoc 错误与大量未接线 helper/未覆盖设置项。

---

## 一、阻断问题（必须修）

### B1 · Reset 之后所有控件失效（config 对象被替换，按钮仍持有旧对象）
- 位置：`SettingsScreen.java:60`（`ClientConfig config = ClientConfig.HANDLER.getConfig();` 被 init 捕获进每个按钮的 lambda）＋ `:121-125`（Reset 调 `ClientConfig.HANDLER.resetToDefaults()`）。
- 现象：`ConfigHandler.resetToDefaults()` 会 `config = createDefaultConfig()` 并立即落盘（`ConfigHandler.java:119-130`，`saveInternal(true)`）。Reset 之后，屏幕上其余按钮的 lambda 仍在改**旧** config 实例；`HANDLER.getConfig()` 已返回新实例，HUD/后续保存都看不到这些改动。必须重开屏幕才恢复。
- 为什么错：Reset 不是“改值”，是“换对象”；捕获的引用失效。同时按钮 label 还是旧值（没有重建/刷新），可见状态与内存不一致。
- 怎么改（任一）：① Reset 后重建屏幕：`this.minecraft.setScreen(new SettingsScreen())`（与上游 `SettingsScreen:983-986` 一致，同时解决 label 刷新）；② 所有回调改为每次 `ClientConfig.HANDLER.getConfig()` 现取，并在 Reset 后 `rebuildWidgets()`。推荐 ①，并用现有 `DeferredActionCoordinator` 把重建延后到 dispatch 之后。
- 附带：Reset 应立即落盘这一点与 `:30` 的 javadoc（“not yet persisted until Done”）**相反**，文档必须改；上游 Reset 还先弹 `ConfirmScreen`（`:951-990`），本轮无确认，属可见行为差异。

### B2 · 标题完全不显示
- 位置：`SettingsScreen` 没有覆写 `extractRenderState`，也没有添加标题 widget；`Screen.extractRenderState(GuiGraphicsExtractor,int,int,float)` 的默认实现**只遍历 renderables**（javap 字节码：`renderables.iterator() → Renderable.extractRenderState`），不画 `title`。
- 现象：进屏幕只看到 8 个按钮和背景，没有 “Ping For It” 标题。上游用 `SettingsTitleWidget` + `addRenderableOnly` 显示标题（deferred `SettingsScreen:130-132`）。
- 为什么错：26.3 的 `Screen` 不再在 render 里画标题，标题需要 widget 或子类自己画；漏掉即视觉缺失（且构造器里传入的 title 从未展示）。
- 怎么改（任选）：① 覆写 `extractRenderState`，先 `super` 再 `graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFF)`（`GuiGraphicsExtractor.centeredText(Font, Component, int, int, int)` javap 已确认）；② 或 `addRenderableOnly(new StringWidget(this.title, this.font))` 并设置位置（`StringWidget` 26.3 存在）。

### B3 · Cancel 语义与上游不一致，且与 Reset 组合会产生“假取消”
- 位置：`SettingsScreen.java:127-134`（Cancel → `suppressSaveOnClose(); onClose()`）；所有控件直接修改 live `ClientConfig`（`:67-113`）。
- 上游事实：deferred `SettingsScreen` 的 footer 只有 **Reset all + primary(Done/Back)**（`:138-148`），**没有 Cancel**；关闭时 `performClose` 走 `persistSettings` = flush + `saveSafely()` + commit server（`:212-232, 792-797`）。也就是说上游没有“取消”概念，所有可见修改随关闭保存。
- 现象：本轮点 Cancel 只跳过写盘，内存里的 config 已经被改，HUD/轮盘立即按新值渲染；而 Reset 又已经立即落盘（B1），Cancel 也无法撤销 Reset。用户看到的是“取消后效果仍在/已经写盘”的矛盾状态。
- 怎么改：① 最贴近上游——删掉 Cancel，只保留 Reset + Done（Done 负责保存）；② 若产品坚持要 Cancel，则在 `init()` 记录 5 个受控字段的快照，Cancel 时恢复后再关（或改编辑副本、Done 提交），并补 UI 文案说明。不要在 live config 上做“只不落盘”的取消。

### B4 · Done 忽略保存失败，失败也关闭屏幕
- 位置：`SettingsScreen.java:143-150`：`onClose() { if (saveOnClose) ClientConfig.HANDLER.saveSafely(); super.onClose(); }` —— 返回值被丢弃。
- 上游：`persistSettings()` 返回 false 时 `performClose()` **直接 return，不离开屏幕**（deferred `:218-232, 792-797`）；`saveSafely` 在 saveProtection/写盘失败时会返回 false（`ConfigHandler:137-142`）。
- 现象：配置写入失败（只读目录、保护模式）时屏幕照常关闭，用户以为已保存，实际丢失。
- 怎么改：`if (saveOnClose && !ClientConfig.HANDLER.saveSafely()) { return; } super.onClose();`，并可用 `Component.translatable("settings.pingforit.save_failed")` 之类提示（需补 lang）。

---

## 二、非阻断问题（建议修）

### N1 · Reset 按钮显示原始 key
- `SettingsScreen.java:122` 用 `Component.translatable("settings.pingforit.reset")`；8 个语言文件里都**没有** `settings.pingforit.reset`，实际存在的是 `settings.pingforit.reset_all`（含 `.title/.message/.server_draft_warning`）。
- 现象：按钮显示 `settings.pingforit.reset` 原文。修法：改用 `settings.pingforit.reset_all`（与上游 `LanguageUtils.settings("reset_all")` 对齐），或在 8 个语言文件补 key。
- 其余 key 核对：`settings.pingforit.title`、5 个 `settings.pingforit.<setting>` 均在 8 个语言文件存在；枚举值 `value.pingforit.hold/disabled/always/compact/full/ping_only/labels_only/all/compatible/voxel_shape_only` 均存在；`gui.done/gui.cancel/options.on/options.off` 在 MC 26.3 `en_us.json` 中存在（非模组 key，可正常解析）。

### N2 · javadoc 与代码不符
- `:26` 引用不存在的 `ClientConfig.ConfigHandlerAccess#save()`（`ClientConfig` 只有 `public static final ConfigHandler<ClientConfig> HANDLER`，`ClientConfig.java:329`；实际方法是 `saveSafely()`）。
- `:30` 说 Reset “not yet persisted until Done”，实际 `resetToDefaults()` 立即 `saveInternal(true)`（`ConfigHandler:119-130`）。
- `:51-52` 说 `suppressSaveOnClose` 用于 “refreshing the screen after a reset”，代码中 Reset 既不刷新也不调用它。

### N3 · TODO 方法静默吞掉真实调用
- `SettingsScreen.notifyServerDisconnected()`（`:152-155`）与 `notifyServerConfigSnapshot(requestId, snapshot)`（`:157-160`）是空实现，但 **已被调用**：`CommonClient:116` 与 `CommonClient:584`。当前会静默丢弃断开/快照事件；M8a-2 接服务端 tab 前，应在方法里至少 DEBUG 记录一次“not implemented”，避免误以为已接通。

### N4 · helper 已复制但完全未接线
- `DeferredActionCoordinator`、`SettingsCategoryCatalog`、`SettingsNavigationModel`、`SettingsScreenLayout` 在 src 中**只有自身/javadoc 引用**，SettingsScreen 未使用；其中 `DeferredActionCoordinator` 恰好是 B1 延后重建的正确工具。建议本轮就把它接到 Reset/未来导航，否则先不要提交这些未使用类（或标注 TODO）。

### N5 · 其他
- `SettingsScreen.java:10` 的 `ServerConfigSnapshot` import 只在 N3 空方法签名里“被使用”，实际是死参数；不构成编译告警，但审阅时容易被误判为未使用。
- `next(E[] values, E current)`（`:162-174`）边界正确（空数组返回 current、找不到返回首项）；枚举非空，实际安全。
- 窗口 resize：`Screen.init(int,int)` 会经 `rebuildWidgets()` 重新调用 `init()`，当前实现会重建 8 个控件并重新抓 config ✓；但这也意味着 **resize 后 B1 的“失效”会暂时恢复**，造成更迷惑的状态。
- 布局：5 行控件从 `height/4` 起、footer 固定在 `height-32`；极矮窗口（<200）可能重叠，建议后续用 `SettingsScreenLayout` 统一。

---

## 三、未覆盖清单（M8a-1b 排期）

上游客户端设置共 **25 项**（deferred `SettingsScreen:457-490` + `:493` 的 Open config 按钮）；本轮覆盖 **5 项**：`player_info_mode`、`team_color_mode`、`entity_block_render_mode`、`item_icon_visible`、`direction_indicator_visible`。

未覆盖的客户端项（20）：`ping_distance`、`marker_display_duration`、`ping_size`、`pass_through_transparent_blocks`、`mark_blacklisted_targets`、`mark_fluids`、`wheel_inner_radius`、`wheel_outer_radius`、`wheel_opacity`、`wheel_target_font_size`、`wheel_font_size`、`wheel_hold_millis`、`wheel_timeout_millis`、`long_press_compatibility_mode`、`long_press_compatibility_slice_millis`、`cancel_half_cone_angle_degrees`、`channel`（文本框）、`ping_volume`、`configuration_notice_size`、`open_client_config`（按钮）。

服务端 tab（0/5）：`default_channel_mode`、`player_tracking_enabled`、`ms_to_regenerate`、`rate_limit`、`sync_duration`（对应 `ServerSettingsModel` 与 request/snapshot 往返，本轮 N3 只是占位）。

结构性缺口：无分类概览/叶子页、无导航与返回、无滚动列表、无标题/描述、无 tooltip、无确认弹窗、无 server 权限与校验反馈、无 view-state 保留（helper 已复制但未用）。

---

## 四、与上游差异逐条对照

| 维度 | 上游 deferred SettingsScreen | 本轮 M8a-1 | 判定 |
|---|---|---|---|
| 打开入口 | 命令/绑定（deferred 由平台层打开） | `ClientCommandBuilder:65` / `CommonClient:235` | ✅ 可达 |
| 标题 | `SettingsTitleWidget` + `addRenderableOnly`（`:130-132`） | 无标题 widget/覆写 | ❌ B2 |
| 分类/分页 | 9 个 Category、概览/叶子、返回、view state | 单页 5 控件 | ❌ 范围（已知） |
| Reset | `ConfirmScreen` → 清屏 → `resetToDefaults()` → new SettingsScreen（`:951-990`） | 直接 `resetToDefaults()`，无确认/无重建 | ❌ B1 |
| Reset 落盘 | 立即（`ConfigHandler:119-130`） | 立即 | ⚠️ 行为同，但 javadoc 说“未落盘” |
| Done/关闭 | `persistSettings()` 返回 false 则**不关闭**（`:218-232`） | `saveSafely()` 返回值丢弃、总关闭 | ❌ B4 |
| Cancel | **无此按钮**；关闭即保存（`:138-148, 212-232`） | 有 Cancel，跳过写盘但保留内存改动 | ❌ B3（新增且语义矛盾） |
| 编辑模型 | 控件 setter 直接改 live config；关闭时 flush+save（`OptionUtils:51-77`） | 同 live config，但无 flush 概念 | ⚠️ 与上游同源，但 Cancel 使其不可取消 |
| 语言键 | `settings.pingforit.*` / `settings.pingforit.reset_all` | 用了不存在的 `settings.pingforit.reset` | ❌ N1 |
| server tab | 5 项 + 权限/校验/快照往返 | 无（两个 notify 空实现） | 已知范围 |
| 客户端项 | 25 项 | 5 项 | 已知范围（20 项待排） |

---

## 五、修复优先级建议

1. **P0**：B1（Reset 换对象导致控件失效 + 无刷新/无确认）、B2（标题缺失）。
2. **P1**：B4（Done 失败不关闭）、B3（Cancel 删除或实现快照恢复）。
3. **P2**：N1（语言键）、N2（javadoc）、N3（TODO 占位日志）。
4. **P3**：N4（未接线 helper 清理/接线）、N5（布局/死参数）。

修完 B1–B4 + N1 后即可进入实机最小闭环验收（打开/切换 5 项/Reset 后再切换/Done 落盘/Cancel 语义），其余缺口按 M8a-1b 继续。