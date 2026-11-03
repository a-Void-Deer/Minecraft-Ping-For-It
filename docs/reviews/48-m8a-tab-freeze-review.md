# 48 · M8a tab 按钮 0×N / 打开界面冻结 修复复审

任务：M8a tab 冻结修复（Lead 直派）　审核人：3号　日期：2026-10-31
对象：`SettingsScreen.java`（工作树未提交，`+67/−23`）；复核 `bash tools/compile-check.sh` → `main 266 / client 120，0 error`。未跑 Gradle。

## 结论

**有条件通过。** 冻结根因（0 宽 tab + `acceptScrollingWithDefaultCenter` 参数错序 + 未屏幕裁剪的 scissor）已被消除，实测日志的 tab 几何恢复正常，无残留阻断；**唯一条件**是第 1 节保真项：本工作区没有 1.21.1 参考 jar/源码，无法逐字节证明“视觉与 1.21.1 完全一致”，需实机截图比对 tab 宽度/位置/文字基线后才能销项。

---

## 一、保真判断（第 1 条必须明确）

| 项 | 证据 | 结论 |
|---|---|---|
| 常量/公式来源 | 26.3 `MenuTabBar.arrangeElements(int)` 字节码：`min(400,width)-28` → `roundToward(tabsWidth / tabs.size(), 2)` → 逐个 `setWidth` → `FrameLayout.arrangeElements()` → `layout.setX(roundToward((width-tabsWidth)/2,2))`、`setY(0)`。本类 `arrangeScopeTabBar` 的 400/28/`roundToward(…,2)`、`/2`（两个 tab）与之逐项一致。 | ✅ 与 26.3 MenuTabBar 同式 |
| 427px 实测 | `tabsWidth=372, tabWidth=186, x=28/214, bar x=28, height=24`（用户日志） | ✅ 与标准两 tab 布局一致 |
| 1.21.1 对照 | 工作区无 1.21.1 jar/源码；只知 1.21.1 的 `TabNavigationBar` 同样用 400/28/roundToward 2 分配宽度，但无法本地证明逐字节一致 | **⚠️ 需实机比对**（不是放行） |
| 结构差异 | 26.3 `MenuTabBar` 移动的是内部 `FrameLayout` 的 x；本类移动的是 `TabNavigationBar` 的 x，再让基类 `arrangeElements(width)` 把 layout 定位到 bar.x。实测按钮位置正确；但 bar 自身矩形 x=28、宽=427（越出屏幕右边 28px），基类 `isMouseOver` 委托 layout 子控件，因此命中/视觉目前无异常 | **非阻断，需实机确认 hover/焦点轨迹** |

> 判定：公式有 26.3 字节码证据；**“与 1.21.1 视觉 1:1”没有本地证据**，按 Lead 要求明确标为需实机比对，不作为放行依据。

---

## 二、阻断项

无。

---

## 三、非阻断项

1. **退化框闸的遮蔽性**：`:1410-1414` 在 `width<=0 || height<=0 || left>=right || top>=bottom` 时**静默不画标签**。正常布局不可达（实测 186×24），能防止 scissor 崩溃；但若将来布局再次退化，会只剩“无标签”而难以发现。建议在 guard 命中时打一条 **一次性 DEBUG/WARN**（例如 `AtomicBoolean` 门闩），不要每帧刷。
2. **bar 矩形越界**：`arrangeScopeTabBar` 设置 `scopeTabBar.setX(28)` 后 bar 仍 `width=427`（右缘 455），基类 `TabNavigationBar.getRectangle()` 返回越界矩形。实测只用于 `tabRectangle.bottom()` 计算标题 Y，不影响绘制；建议若后续有代码用 bar 矩形做命中/裁剪，改为移动内部 layout（需要子类/访问器）或把 bar 宽设为 `tabsWidth`。
3. **常量重复**：`TAB_BAR_MAX_CONTENT_WIDTH=400`、`TAB_BAR_SIDE_MARGIN=28`、`TAB_BAR_HEIGHT=24` 是从 26.3 `MenuTabBar` 抄来的私常量；26.3 若改值会漂移。已用注释标明来源，可接受。
4. **重复调用**：`createScopeTabs`（`:324`）与 `repositionElements`（`:261`）各调用一次 `arrangeScopeTabBar`；`arrangeScopeTabBar` 本身幂等（只 setWidth/setX/arrange），无副作用；不是问题。

---

## 四、逐项复核

### 参数序与取值（第 3 条）
- `MenuTabButton.renderLabel` 字节码：`x=getX()+1`、`y=getY()+(isSelected?0:3)`、`right=getX()+width-1`、`bottom=getY()+height`；调用顺序是 `(message, x, right, y, bottom)` → 即 `(msg, x1, x2, y1, y2)`。
- 本类 `:1402-1412` 的 `left/right/top/bottom` 与上述**逐项一致**；`acceptScrollingWithDefaultCenter` 默认实现也是 `center=(x1+x2)/2` 后转 `acceptScrolling(msg, center, x1, x2, y1, y2)`（字节码已核）→ 参数序正确。

### 副作用（第 4 条）
- `repositionElements` 先 `arrangeScopeTabBar()` 再取 `tabRectangle.bottom()`，顺序正确；`createScopeTabs` 先构建 bar/buttons 再 arrange，再 `setCurrentTab`，宽度与选择状态无关。
- 文件内已无旧的 `scopeTabBar.arrangeElements()`/`setWidth` 重复调用（grep 仅 `arrangeScopeTabBar` 内部一处 `arrangeElements(this.width)`）。

### 清理（第 5 条）
- 全文件 grep：`dump/widget tree/settings widget` 0 命中；仅保留 `:285` 一条 `LOGGER.debug("settings layout...")`；无残留诊断字段/import（`ScreenRectangle`、`Mth`、`WidgetSprites` 均有使用）。`compile-check` 0 error。

### 同类隐患（第 6 条）
- 全仓 grep `acceptScrolling|acceptScrollingWithDefaultCenter|TabNavigationBar|ScopeTabButton`：**只有本文件**使用；其它界面没有同类调用。
- 未发现其他 0 尺寸自定义控件：`EditBox` 以 `(-1,-1)` 创建但宽高为 `150/20`，布局后由 List 重新定位；`StringWidget`/`Button` 均有显式宽高。

---

## 五、待实机项

- [ ] 427×249（或用户常用分辨率）下截图对比 1.21.1：两个 tab 的 x/宽度（预期 28/214、186）、高度 24、文字基线、selected/unselected 上移 3px。
- [ ] 窗口 resize 后 tab 位置/文字仍正确（`repositionElements` 路径）。
- [ ] tab hover/focus/键盘 Tab 切换轨迹（bar 矩形越界的非阻断项）。
- [ ] 叶子页/overview 切换、标题 Y（`tabRectangle.bottom()+3`）与 list 高度。
- [ ] 极小窗口（宽 < 428）时 tab 宽度/文字是否仍正常；guard 是否被触发（若触发应只有一次诊断）。

---

## 六、结论

**有条件通过。** 冻结修复正确、清理干净、无阻断；tab 几何公式有 26.3 字节码证据，但“与 1.21.1 视觉 1:1”因缺少本地 1.21.1 参考，必须实机截图比对后才能最终销项。其余非阻断项（guard 静默、bar 矩形越界、常量重复）建议顺手处理 guard 诊断与 bar 宽度。

---

## 跟进复审（bar 宽度收窄 + 退化 guard WARN）

任务：48 号两条跟进　审核人：3号　日期：2026-10-31
基线复核：`bash tools/compile-check.sh` → `main 266 / client 120，0 error` ✓。

### ① `scopeTabBar.setWidth(tabsWidth)`：通过

- **对 `getRectangle()`/titleY**：`ScreenRectangle.bottom() = y + height`，只依赖 bar 的 y/height；本类 bar 的 y=0、height=24 未变，titleY 仍为 `bottom()+3`，不受 width 影响 ✓。
- **对 `isMouseOver`**：javap `TabNavigationBar.isMouseOver` 是遍历内部 `FrameLayout` 的子控件（`layout.visitChildren`），**不读 bar 自身 width**；命中仍由 tab 按钮的 28/214、186 宽决定 ✓。
- **对 `children()` 转发**：`TabNavigationBar` 的 children 由构造器中 `FrameLayout + LinearLayout.horizontal()` 的 tab 按钮组成；bar 的 width 不参与该结构 ✓。
- **对 resize**：`repositionElements` 每次重算 `tabsWidth` 并调用 `arrangeScopeTabBar`；bar 宽度随新屏宽重设，不会残留旧宽度 ✓。
- **几何**：427px 时 `tabsWidth=372`、bar x=28、右缘 400，落在屏内；按钮仍 28/214、宽 186。**不改变宽度分配公式**，只修 bar 自身矩形越界。
- **保真**：仍与 26.3 `MenuTabBar.arrangeElements` 的 400/28/roundToward 2 公式一致；1.21.1 像素级等价仍需实机截图比对（48 号原有条件不变）。

### ② 退化 guard 的 one-shot WARN：通过

- `SettingsScreen:1414-1422`：正常路径不打印；仅当 `width<=0 || height<=0 || left>=right || top>=bottom` 时打印一次 `WARN` 并置 `degenerateLogged=true`（static，per-JVM），不会刷屏 ✓。
- 相比静默跳过，WARN 保留了可观测性，不会掩盖未来布局退化；正常布局实测 186×24，不会触发 ✓。

### ③ 对既有结论的影响：无

- 幂等/顺序未变：`createScopeTabs` 与 `repositionElements` 仍各调用一次 `arrangeScopeTabBar`；仅 bar 宽度从 screen width 改为 tabsWidth，不影响按钮 setWidth/setX/arrange 顺序 ✓。
- 无新增字段/import 问题；`degenerateLogged` 只读/写于 render thread 的 guard，未参与逻辑 ✓。

### 结论

**通过。** 两处修改均按预期工作，无新问题；剩余项仍是 48 号已列出的**实机像素比对**（1.21.1 tab 宽度/位置/文字基线），不阻塞本次修复验收。
