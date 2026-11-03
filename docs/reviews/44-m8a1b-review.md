# 44 · M8a-1b 复审：完整设置界面（1344 行整体移植）

任务：M8a-1b（Lead 直派）　审核人：3号　日期：2026-10-31
分支：`exp/vanilla-lines-m7a`（HEAD `accc881`，工作树未提交）；对象：`SettingsScreen.java`（src 1426 行 vs deferred 1344 行，`+128/−46`）。
验证：`git diff --no-index` 全量差异 + javap（26.3 `OptionsSubScreen`/`Screen`/`Tab`/`TabManager`/`TabNavigationBar`/`TabButton`/`MenuTabBar`/`MenuTabButton`/`WidgetSprites`/`StringWidget`/`AbstractScrollArea`/`EditBox`/`PermissionLevel/`Permissions`）+ 上游 deferred 对照 + **重跑** `bash tools/compile-check.sh`（`main 266 / client 119，0 error`）。未跑 Gradle。

## 结论

**打回（不通过）。** 15 条适配中 11 条完全正确、#9 基本正确（仅 bottom 3px），其余 4 处有可见/行为差异：**3 个阻断问题**（B1/B2/B3）+ 1 个非阻断 N2。几个我原以为会炸的点（null Options、空 GridLayout、scroll 夹取、tab 高度 24）都复核安全；阻断问题是：`LeftTextWidget` 丢失垂直居中（所有左对齐标签上移 ~5px）、权限常量把上游的等级 3 误放宽成等级 2、两处 `.alignCenter()` 删除后实际变成左对齐（26.3 `StringWidget` 默认并不是水平居中）。

---

## 一、阻断问题（必须修）

### B1 · `LeftTextWidget` 丢掉垂直居中，所有左对齐标题/标签上移 5px
- 位置：`SettingsScreen.java:1308-1316`（`visitLines` 的 `collector.accept(this.getX(), this.getY(), ...)`）；使用点 `:553`（subgroup heading）、`:566`（channel label）、`:641`（server numeric label）。
- 依据：26.3 `StringWidget.visitLines` 的字节码明确是 `y = getY() + (getHeight() - 9) / 2`（javap：`getY → getHeight → 9 → isub → iconst_2 → idiv → iadd → istore 7`），再 `collector.accept(x, y, message)`。上游 `.alignLeft()` 只改水平对齐，垂直仍然居中。
- 现象：ROW_HEIGHT=20 时文字整体上移 `(20-9)/2 = 5px`；设置页的每个分组标题、channel 标签、服务端数值标签都会贴到行的上半部分（与同行的按钮/文本框基线不一致）。
- 怎么改：
`
  @Override
  public void visitLines(ActiveTextCollector collector) {
      final int y = this.getY() + (this.getHeight() - 9) / 2;
      collector.accept(this.getX(), y, this.getMessage());
  }
`
  （9 是 vanilla 硬编码的行高；若想更稳妥可用 `this.getFont().lineHeight` 但需与 vanilla 的 9 对齐。）

### B2 · 权限常量把「等级 3」放宽成「等级 2」
- 位置：`SettingsScreen.java:862-864`：`this.minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)`。
- 上游：deferred `SettingsScreen.java` 原代码是 `player.hasPermissions(3)`；1.21.1 的等级 3 = **ADMINS**。
- 26.3 证据：`PermissionLevel` 静态初始化里 `ALL=0, MODERATORS=1, GAMEMASTERS=2, ADMINS=3, OWNERS=4`；`Permissions.COMMANDS_GAMEMASTER` 构造的是 `HasCommandLevel(GAMEMASTERS)`，即 **等级 2**。因此现在等级 2 的 gamemaster 也会被判定为可编辑服务端设置（上游只有等级 3+）；这是可见行为 + 权限边界的变化。
- 怎么改：改成 `Permissions.COMMANDS_ADMIN`（`ADMINS=3`），与 `hasPermissions(3)` 对齐；改完请在实机分别用等级 2/3 账号验证服务端 tab 的可编辑性。

### B3 · 删掉 `.alignCenter()` 的两处实际变成左对齐，不是居中
- 位置：`SettingsScreen.java:665-670`（`serverStatusWidget` 的 `new StringWidget(...)` 不再 `.alignCenter()`）与 `:1252-1259`（`serverValidationLabel` 的 `StringWidget` 不再 `.alignCenter()`）。
- 依据：26.3 `StringWidget.visitLines` 的默认分支是 `collector.accept(getX(), yCentered, message)`（字节码 offset 146-158），**水平方向从 getX() 开始，没有居中逻辑**；26.3 的 `StringWidget` 也没有 `alignCenter` 字段可设（javap 字段只有 `maxWidth/cachedWidth/textOverflow`）。所以删调用 ≠ 默认居中，而是变成左对齐。
- 现象：服务端页的服务器状态文本、红色校验错误文本从“居中”变为“贴左”，与上游可见布局不同；长错误文本尤其明显。
- 怎么改：加一个 `CenterTextWidget extends AbstractStringWidget`，`visitLines` 里 `x = getX() + (getWidth() - font.width(message)) / 2`、`y` 用 B1 的垂直居中公式；把这两处 `StringWidget` 换掉。或者保留 `StringWidget` 但用布局/宽度让文字自然居中（不推荐，脆弱）。

---

## 二、非阻断问题（建议修）

### N1 · `ScopeTabButton` 的滚动文本 bottom 比 vanilla 低 3px
- `:1338-1343`：`acceptScrollingWithDefaultCenter(message, x+1, top, x+width-1, top+height)`；vanilla `MenuTabButton.renderLabel` 的 bottom 是 `getY()+getHeight()`，不是 `top+height`。未选中时 `top = y+3`，于是我们的 bottom 比 vanilla 多 3px，文本垂直中心低 1.5px。
- 修法：`bottom = this.getY() + this.getHeight()`；其余（x+1、selected?0:3、sprites 顺序）与 MenuTabButton 完全一致。

### N2 · EditBox 的 `setFilter → setResponder` 改变了粘贴/光标行为
- `:1276-1295`：现在遇到非数字会 `field.setValue(去非数字)` 并 return。上游 `setFilter` 是“整段拒绝”：粘贴 `12a` 时上游保持旧值，本地变成 `12`；而且 `EditBox.setValue` 会 `moveCursorToEnd(false)`（javap 已核），输入中间位置打一个非法字符会把光标弹到末尾。
- 递归本身安全（`setValue → onValueChange → responder` 第二层 sees digits equal → 正常提交，深度 2），不会栈溢出；但可见行为已与上游不同。
- 建议：在 responder 里记录上一合法值，非法时 `setValue(lastValid)` 后 `setCursorPosition(旧光标)`，或接受差异并在 fidelity 文档登记。

### N3 · 其余已复核安全的点（不是问题，但 Lead 问到了）
- `super(lastScreen, null, title)`：`OptionsSubScreen` 构造器只做 `this.layout = new HeaderAndFooterLayout(this)` + 存 `lastScreen/options`（javap 已核），不 deref options；`removed()` 已被本类空覆写（`:247-251`，避免 base 的 `minecraft.options.save()`）；`init()/repositionElements()/onClose()` 均为本类实现；本类没有 `this.options` 引用（只有 `Game.options` 静态）。**null Options 安全。**
- `init()` 未调 `super.init()`：`layout` 在构造器创建，本类自己 `addRenderableWidget` 全部控件并加 `SettingsTitleWidget`；`children()/renderables` 由 Screen 维护。**无问题。**
- 空 `getLayout()`：`TabManager` 与 `TabNavigationBar` 的字节码只有 `doLayout/visitChildren`，没有 `Tab.getLayout` 调用（Lead 的判断成立）；返回空 `GridLayout` 安全，但建议注释注明“仅为满足 Tab 接口”。
- `setScrollAmount + refreshScrollAmount`：26.3 `AbstractScrollArea.setScrollAmount` 自身 `Mth.clamp(0, maxScrollAmount)`（javap），`refreshScrollAmount` 只是再 `setScrollAmount(scrollAmount)`；与 1.21.1 `setClampedScrollAmount` 等价，顺序（set → focus → set+refresh）也保留了权威性。**等价。**
- Tab 高度 24：不是拍的——`MenuTabBar.HEIGHT = 24`（javap -constants），`TabNavigationBar.builder(..., 0,0,width,24)` 与 vanilla 菜单 tab 同高；`arrangeElements(width)` 签名正确。
- `ScopeTabButton` 贴图：`WidgetSprites` 字段顺序 `enabled/disabled/enabledFocused/disabledFocused`、`get(enabled,focused)` 语义已核；`MenuTabButton` 的静态初始化正是 `tab_selected, tab, tab_selected_highlighted, tab_highlighted`，与本类**逐项一致**；`SELECTED_OFFSET=3`、`TEXT_MARGIN=1` 也一致（只有 N1 的 bottom）。
- `Blaze3D.openPath(Path)`、`keyPressed(KeyEvent)`、`mouseClicked(MouseButtonEvent,boolean)`、`resize(int,int)`、4 处 `setScreenAndShow` 均编译/签名正确；全文件旧 API 残留 grep：`setScreen(`、`keyPressed(int`、`mouseClicked(double`、`resize(Minecraft`、`hasPermissions`、`getScrollAmount`、`setClampedScrollAmount`、`OptionsList.Entry`、`alignLeft` 均为 0。

---

## 三、保真差异清单（15 条逐条）

| # | 适配 | 是否改变上游可见效果 | 说明 |
|---|---|---|---|
| 1 | Util→`Blaze3D.openPath` | 否 | 同一“打开配置目录”语义；异常仍捕获 |
| 2 | `keyPressed(KeyEvent)` | 否 | 仅签名；`event.key()==256` 与旧 keyCode 一致 |
| 3 | `mouseClicked(MouseButtonEvent)` | 否 | 仅签名 |
| 4 | `resize(int,int)` | 否 | 仅签名 |
| 5 | `setScreen→setScreenAndShow`（4 处） | 否 | 26.3 的显示入口；行为等价 |
| 6 | `lastScreen` 构造器 | 否 | `lastScreen` 为 final，改走 super；null 父屏路径已核安全 |
| 7 | `ScopeTab.getLayout/getTabExtraNarration` | 否 | getLayout 无调用者（字节码已核），narration 为空与默认一致 |
| 8 | TabNavigationBar 5 参 builder / 24 高 | 否 | 24 = `MenuTabBar.HEIGHT`，非拍脑袋 |
| 9 | 新增 `ScopeTabButton` | **是（轻微）** | 贴图/selected offset/text margin 全对；仅 bottom 多 3px（N1）。未画 MenuTabButton 的菜单背景/焦点下划线，符合 1.21.1 经典 tab |
| 10 | `StringWidget.alignLeft→LeftTextWidget` | **是（阻断 B1）** | 垂直居中丢失，所有左对齐标签上移 5px |
| 10b | 删除 `.alignCenter()`（2 处） | **是（阻断 B3）** | 26.3 默认是水平左对齐，不是居中 |
| 11 | scroll 两 API | 否 | setScrollAmount 已含 clamp；refresh 再夹一次，等价 setClampedScrollAmount |
| 12 | `permissions().hasPermission(COMMANDS_GAMEMASTER)` | **是（阻断 B2）** | 等级 2 ≠ 上游等级 3（ADMINS） |
| 13 | `setFilter→setResponder` 净化 | **是（非阻断 N2）** | 粘贴非数字由“整段拒绝”变“保留数字”；非法字符把光标弹到末尾 |
| 14 | `OptionsList.Entry→AbstractEntry` | 否 | 26.3 类型层级变化；`applyUnsavedChanges` 仍只处理具体 `Entry`，自定义 AbstractEntry 行为与 vanilla 26.3 一致 |
| 15 | `ClientTeamContextHelper.getSelfContext()` | 否 | 同一客户端 helper，语义不变 |

---

## 四、服务端三页：最脆弱的 3 个点（本轮按原样搬，实机验证）

1. **权限门（B2 修完后仍需实机）**：26.3 的 `Player.permissions()` 是新的 `PermissionSet`，客户端同步的权限等级是否包含 `HasCommandLevel` 所依赖的 level source，需在**单人/多人、等级 2/3/4**下分别确认服务端 tab 的可见/可编辑状态；若客户端 set 不含 level，可能所有人都被判 false。
2. **会话/快照生命周期**：`ServerSettingsModel`（src 与 deferred **零差异**，按原样搬）+ `SettingsScreen` 的 `requestServerSettingsIfNeeded:847-859`、`onServerConfigSnapshot:891-906`、`currentSettingsScreen` 的 WeakReference：请求只在 rebuild/tick/权限变化时发；若请求丢失（切维度、断线重连、确认屏期间、快速开关页），`loading` 可能长期为 true 且无重试；快照 requestId 不匹配会被丢弃（设计如此），但 UI 会停在 loading。
3. **提交顺序**：`commitServerSettings:921-957` 在 `sendToServer` **之前** `markClean()`；若发送失败/连接在检查后断开，dirty 已清、不会重试，用户以为已保存。上游同代码，但这是本轮最脆的运行时路径；建议 markClean 移到发送成功之后（需要网络层返回结果）或失败回滚 dirty。

---

## 五、待实机验证清单

- [ ] 打开设置（命令/原有入口），标题、tab、列表、滚动、焦点记忆与上游观感一致。
- [ ] B1 修复后：分组标题/channel 标签/服务端数值标签与同行控件基线对齐。
- [ ] B3 修复后：服务器状态文本、红色校验文本水平居中。
- [ ] B2 修复后：等级 2 账号看不到可编辑服务器设置，等级 3+ 可以；单人/多人各一次。
- [ ] 服务端页请求：进入→loading→快照→可编辑；断线/重连/切维度后不永久 loading。
- [ ] 修改服务端字段→Done：成功发送、失败时屏幕不关闭（`persistSettings` 返回 false 路径）。
- [ ] Reset all：ConfirmScreen→重置→新屏幕；取消确认回到原屏。
- [ ] EditBox：输入/粘贴非数字、光标位置、校验提示（N2 的已知差异）。
- [ ] 窗口 resize、Esc 返回、lastScreen null（命令直接打开）路径无 NPE。

---

## 六、结论

**打回。** 先修 B1（LeftTextWidget 垂直居中）、B2（COMMANDS_ADMIN）、B3（CenterTextWidget），再修 N1/N2；修完这 5 处即可进入实机验收。13/15 条适配本身正确，null Options、空 getLayout、scroll、tab 高度、ScopeTabButton 贴图均已独立复核通过；服务端 3 页的接线结构完整，但权限/会话/提交顺序 3 个运行时点必须实机确认。

---

## 复审记录（B1/B2/B3 + N1/N2 修复后）

任务：M8a-1b 修复复核（Lead 直派）　审核人：3号　日期：2026-10-31
基线复核：`bash tools/compile-check.sh` → `main errors=0 classes=266 / client errors=0 classes=120` ✓。

### 结论

**打回（剩 1 个阻断）**。B1/B3/N1 修复正确；N2 的闭包/递归/长度路径复核无栈溢出、无 maxLength 冲突（正常输入路径），只剩已知的光标位置差异；但 **B2 的等级映射虽已改对，权限来源在 26.3 客户端是空的**，服务端 3 页仍不可用（见下）。

### 逐项复核

**B1 · LeftTextWidget 垂直居中：通过。**
- `SettingsScreen:1313-1326`：`textY() = getY() + (getHeight() - 9) / 2`，与 26.3 `StringWidget.visitLines` 字节码一致（`getY → getHeight → 9 → isub → iconst_2 → idiv → iadd → istore 7`）；不是 `font.lineHeight`，与 vanilla 的硬编码 9 对齐 ✓。
- 三个使用点自动跟随：分组标题（`:553`）、channel 标签（`:566`）、服务端数值标签（`:641`）✓。

**B3 · CenterTextWidget：通过（x/y 等价）。**
- `:1329-1339`：`textX = getX() + (getWidth() - getFont().width(message)) / 2`，与 1.21.1 `StringWidget.alignCenter` 的纯文本居中公式一致；这两个 widget 都没有设置 `maxWidth`/overflow，因此无差异。若文本宽于控件，x 可能为负——与旧版行为相同，不是新问题。
- 两处替换：`serverStatusWidget`（`:87, 664-672`）与 `createServerValidationLabel`（`:1252-1261`）均已改为 `CenterTextWidget`，字段/返回类型改为 `AbstractStringWidget` ✓。

**LeftTextWidget 不再 final：无副作用。**
- `AbstractStringWidget.onClick`/`handleStyleClick` 只在 `componentClickHandler != null` 时生效；`LeftTextWidget`/`CenterTextWidget` 都没设置 handler，且 `active=false`，点击行为不变；`visitLines` 重写、构造器 private 但同顶层嵌套类可访问（编译 120 通过）✓。

**B2 · 等级映射：常量正确，但客户端权限源为空（阻断残留）。**
- 常量改对了：`Permissions.COMMANDS_ADMIN` → `HasCommandLevel(ADMINS=3)`，与上游 `hasPermissions(3)` 对齐（`PermissionLevel` id 已核）✓。
- **但** 26.3 客户端 `LocalPlayer.permissions` 的静态事实：
  - `LocalPlayer` 构造器把它初始化为 `PermissionSet.NO_PERMISSIONS`（javap `LocalPlayer.<init>` offset 32-36）；
  - 唯一写入口是 `LocalPlayer.setPermissions(PermissionSet)`；
  - 对 4 个 vanilla jar（common/client/client-only/server）解包后 `findstr /c:setPermissions` **只命中 `LocalPlayer.class` 自身**，没有任何调用者。
  - 因此 `minecraft.player.permissions().hasPermission(COMMANDS_ADMIN)` 在客户端恒为 **false**（单人/多人/局域网主机都一样；Fabric API 也没有可用的权限同步）。`updatePermissionState:862-864` 会把 `clientPermission` 永远置 false，服务端三页永远不可编辑/不请求快照。
- 修法建议（二选一）：
  1. **不要用客户端权限做门**：把 `updatePermissionState` 的门槛改为 `hasLiveServerConnection()`（或直接 `clientPermission=true`），请求快照后由 `ServerConfigSnapshot.canEdit()/accessDenied()` 决定 UI；这是 26.3 没有客户端权限源时最稳的路线，但需要把“低权限用户也会看到 server tab/loading”的行为差异登记进保真审计。
  2. 若必须保留“低权限不显示 server tab”，需要在 >=26.4 找到真正的客户端权限同步源（当前 26.3 vanilla 没有），或加一个服务端下发权限的自定义包（超出本轮）。
- 另：`LocalPlayer.setPermissions` 会通知 `HasGamemasterPermissionReaction` 的当前屏幕（vanilla 接口）；本 Screen 没实现它，靠 tick 更新；即使未来有权限源，建议实现该接口以立即响应权限变化，而不是等下一 tick。

**N1 · ScopeTabButton bottom：通过。**
- `:1360-1365` 已改为 `this.getY() + this.getHeight()`，与 `MenuTabButton.renderLabel` 一致；x+1、selected?0:3、sprites 顺序均保持正确 ✓。

**N2 · EditBox 闭包：递归/长度无新问题，光标差异保留。**
- `String[] accepted` 初始为 authoritative `value`；收到非法输入 → `field.setValue(accepted[0])` → `setValue` 内 `onValueChange` 再进 responder 一次（第二层 digitsOnly=true，正常提交后返回），深度 2，不会无限递归 ✓。
- `setMaxLength` 冲突：26.3 `EditBox.insertText` 在调用 responder **之前**就按剩余容量截断（javap offset 24-98），因此正常键入/粘贴超长数字时 responder 看到的是已截断文本，`accepted` 不会超过 10；`setValue` 本体会把**未截断参数**传给 `onValueChange`，但本屏只在初始化和恢复 `accepted`（均 ≤10）时调用，故无实际冲突。建议为防御性仍在 responder 里对 `text` 做一次 `maxLength` 截断。
- 已知差：`setValue` 会 `moveCursorToEnd`，非法输入后光标到末尾（上游 setFilter 插入阶段拒绝、光标不动）；Lead 已计划登记保真审计，复核认可。

**其他/新问题**
- `import StringWidget` 已删，源码中除注释外无 `StringWidget` 使用（仅 `AbstractStringWidget`），编译 120 通过，无静默问题 ✓。
- `CenterTextWidget` 的 x 公式在文本超宽时可能为负，与旧版 `alignCenter` 相同；无新问题。

### 复审结论

- B1、B3、N1：**修复通过**。
- N2：**修复通过（已知光标差异待登记）**；建议加防御性长度截断。
- B2：**常量修复通过，但客户端权限源为空 → 服务端三页仍不可用，属残留阻断**。按上面路线 1 改完（并实机单人/多人/局域网主机各一次）后可再复核。


---

## 最终复审（B2 权限门改连接可达性 + N2 防御性截断）

任务：M8a-1b 第 3 轮复核　审核人：3号　日期：2026-10-31
基线：`bash tools/compile-check.sh` → `main 266 / client 120，0 error` ✓；`Permissions` import 已删除且无残留使用。

### 结论

**通过。** B2 改为“有连接即可请求、服务端裁决”，denial 路径不会重复请求；N2 截断在正常输入路径安全；无残留阻断。

### 1. 状态机（denial 后不会重复请求）

- `requestServerSettingsIfNeeded:845-858` 四个前置条件 `clientPermission / !accessDenied / !loaded / !loading` 足以抑制：denial 由 `ServerSettingsModel.applySnapshot:131-160` 处理，`!snapshot.canEdit()` 时置 `authoritativeAccessDenied=true` 并 `collapseAndDiscard()`（`expanded=false, loading=false, pending=NO`），随后 `accessDenied` 让 request 直接 return。
- `updatePermissionState:860-891` 现在 `permission = hasLiveServerConnection()`；denial 后 `permission==clientPermission==true`，tick 直接 early-return，不会再次请求；`rebuildPage:392` 的 request 也被 `accessDenied` 抑制。
- **非阻断残留**：denial 是 per-screen latch；同一连接内玩家后来被 op（服务端权限提升）不会自动重试，需重开屏幕/重连。可接受，建议记已知限制。

### 2. server tab 可见性

- 上游 deferred `SettingsScreen` 的 `createScopeTabs` 始终加入 CLIENT/SERVER 两个 scope tab；权限不足只是让页内控件 inert（`canEdit` 分支），没有“隐藏 server tab”的行为。本版一直可见，**不构成可见差异** ✓。

### 3. 服务端权限源（#3 明确结论：可靠，不是 NO_PERMISSIONS）

- `ServerCore:278/290` 用的是 `ServerPlayer.permissions()`；javap `ServerPlayer.permissions()` 的实现是：
  `this.server.getProfilePermissions(this.nameAndId())` → `MinecraftServer.getProfilePermissions`（不是 `PermissionSet.NO_PERMISSIONS`）。
- `MinecraftServer.getProfilePermissions`：op 列表存在 → `ServerOpListEntry.permissions()`；否则 `operatorUserPermissions()`；非 op → `LevelBasedPermissionSet.ALL(0)`。
- `IntegratedServer` 覆写 `getProfilePermissions`：单人主 + `PlayerList.isOp`（要求 `WorldData.isAllowCommands`）→ `OWNER(4)`；单人主但未开作弊 → 走基类 → `ALL(0)`；LAN guest 在开作弊且 `guestCommandAccess` 时 → `GAMEMASTER(2)`，否则 `ALL/自定义`。
- 因此：**单人开作弊的主机 = OWNER(4) → `COMMANDS_ADMIN(3)` 通过，可编辑**；单人不开作弊/普通访客 = 等级 0/2 → 不可编辑，与上游 `hasPermissions(3)` 一致。服务端权限源**不会恒 NO_PERMISSIONS**；之前的问题只在客户端 `LocalPlayer`，现在已被连接可达性替代。
- `LevelBasedPermissionSet.hasPermission(HasCommandLevel)` 用 `level().isEqualOrHigherThan(required)`，`COMMANDS_ADMIN`=ADMINS(3) 与上游 `hasPermissions(3)` 等价 ✓。

### 4. N2 防御性截断

- `:1280-1296`：`maxLength=10` 提为局部变量并同时用于 `setMaxLength`；`accepted[0]` 截断到 maxLength。
- 正常键入/粘贴走 `EditBox.insertText`，它在调用 responder **之前**按剩余容量截断（javap offset 24-98），所以 responder 看到的 text 不会超过 maxLength；`String[] accepted` 的非法输入 → `setValue(accepted[0])` 深度 2 递归安全，无栈溢出。
- **非阻断残留**：防御分支里 `responder.accept(text)` 仍传未截断的 `text`；当前唯一可能传入超长 text 的 `setValue` 调用点是初始化/恢复 `accepted`，均 ≤10，故正常路径无影响。建议防御性改为 `responder.accept(accepted[0])`（或先截断再传），使这条路径也完全自洽。

### 5. 其他

- B1/B3/N1 保持上一轮复核结论：`textY=(height-9)/2` 与 `StringWidget` 字节码一致；`CenterTextWidget` 的 x 与 `alignCenter` 同式；ScopeTab bottom 已修。
- `import StringWidget` 删除后无残留使用；`CenterTextWidget` 继承 `LeftTextWidget` 无 onClick/handleStyleClick 副作用；编译 120 通过。
- 44 号报告中的服务端脆弱点 `commitServerSettings` 先 `markClean()` 再发送仍未变（非本轮改动，非阻断，建议后续处理）。
