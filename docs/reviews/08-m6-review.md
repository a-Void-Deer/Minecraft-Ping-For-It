# 08 · M6 审核（轮盘 HUD + 7 类 ping 选择）

审核人：3号　基线：**HEAD = 97bcf1a**（工作区 clean）　方法：只读；`git show HEAD` + 源码/26.3 源码 jar + JDK-25 `javap`。**未跑 Gradle。**
改动：`WheelHudRenderer` 新增 830 行；`CommonClient` +17；`PingInteractionLogger` +26（`info` 默认方法）；`PingInteractionStateMachine` +13（纯日志）；另删除误提交的 `.tmp-m6` 反编译产物。

## 结论

**可以进入用户实机验收（有条件）。** 渲染几何自洽、交互仍由原状态机独占、异常隔离到位、无泄漏、日志不含敏感字段。
条件：① F1 隐藏 HUD 时轮盘/交互计时仍会冻结（M4 遗留，未修）；② 鼠标坐标映射用 `Window.getWidth()`（=framebuffer）而非官方 `getScaledXPos`，在 HiDPI/Windows 显示缩放 ≠100% 时可能选错扇区 —— **必须在实机验收里覆盖 GUI scale 2 与系统缩放 150% 两种情形**。

---

## 一、渲染正确性：通过（1 处坐标风险）

| 检查 | 结论 | 证据 |
|---|---|---|
| GUI 坐标系 | 圆心 = `graphics.guiWidth()/2, guiHeight()/2`（准星中心）✓；与 HUD 相位同一 `GuiGraphicsExtractor` 空间 | `WheelHudRenderer.java:176-177` |
| 角度约定 | 0 = 正上方、顺时针为正、点 = `(r·sin a, −r·cos a)`（GUI y 向下）；`clockwiseAngle = atan2(dx, −dy)` 归一到 [0,2π) —— **画与选完全同一约定** | `WheelGeometry.java:14-16,192-194,274-277` |
| 扇区划分 | `sectors()` 按 `2π/n` 等分、相邻边界共用、末扇区恰止于 2π；n=1 时整环 | `WheelGeometry.java:105-124` |
| 命中判定 | `select()`：距离 ≤ 内径 → CENTER；> 外径 → NONE；否则按角度区间取扇区（边界归后一扇区，越界下标 clamp） | `WheelGeometry.java:143-173` |
| **高亮与命中是否同源** | **是**：同一帧先算 `selection = selectionFromMouse(...)` → `runtime.setWheelSelection(selection)` → 同一 `selection` 变量传给 `drawRing`/`drawCenter`；`isSelected` 用 `pingType.equals` 比较，而扇区来自同一 frozen 列表 → 结构上不可能"亮 A 发 B" | `WheelHudRenderer.java:183-185,210-220,789-797` |
| 绘制几何 | 环形底、扇区楔形（逐行合并 run，无 atan2）、内外弧（`arcPoints`）、径向分隔（仅 >1 类型）、中心取消盘 | `WheelHudRenderer.java:279-311,660-787` |
| 标签/图标位置 | `WheelLabelLayout` 用扇区中点角 + `geometry.pointAt`，与选择同一几何；空/退化输入有 `maxWidth>0` 兜底 | `WheelLabelLayout.java:210-212,188-198,226-235` |
| pose 栈 | 唯一缩放绘制 push/pop 在 `try/finally` 内，不会污染后续 HUD | `WheelHudRenderer.java:431-440` |

### 风险 R1（待实机确认）：鼠标映射用 framebuffer 尺寸
- 代码：`dx = mouseHandler.xpos() / window.getWidth() * guiWidth − centerX`（`WheelHudRenderer.java:247-257`）。
- 26.3 事实：`Window.getWidth()` 返回 **`framebufferWidth`**（`Window.java:661-662`），而 `MouseHandler.xpos` 是 **screen 坐标**（`MouseHandler.onMove:228-246` 直接存 GLFW 值；`releaseMouse()` 把 xpos/ypos 设为 `getScreenWidth()/2`，`:413-417`）。
- 26.3 官方换算：`MouseHandler.getScaledXPos(window) = xpos * getGuiScaledWidth() / getScreenWidth()`（`:302-316`）。
- 结论：当 `framebufferWidth == screenWidth`（Windows 100% 缩放）时两者等价；**HiDPI / 系统缩放 / macOS Retina 下会整体缩放错误**（轮盘选中偏移、开轮瞬间中心不居中）。
- 来源：该公式与上游 deferred 实现一致（`deferred/render/WheelOverlayRenderer.java:219,228-229`），**不是 M6 新引入**，但 M6 是它第一次在 26.3 真正跑起来。
- 建议（1 行级）：改用 `game.mouseHandler.getScaledXPos(window)`/`getScaledYPos(window)`，两种缩放下都正确。

## 二、交互边界：通过

| 路径 | 归属 | 证据 |
|---|---|---|
| 松开在环内 | 状态机 `commitWheelSelection` → `commitPing(selection)`；HUD 不参与 | `PingInteractionStateMachine.java:505-516,498-500` |
| 松开在中心 | 同上：CENTER → 取消本地 marker（无本地 marker → 无动作） | `:513-528` |
| 松开在环外 | `select` 返回 NONE → `commitWheelSelection` 走 "no-selection" 分支，无动作 | `:530-535`；`WheelGeometry.java:160-162` |
| 超时 | `presentFrameAt`（渲染帧路径）与 `updateWheelOpen`（松手路径）各自判超时后 `resetMachineState`，二者互斥（前者 reset 后 phase=IDLE，后者不再进入） | `:386-396` vs `:482-489` |
| HUD 是否"抢答" | 否：HUD 只 `setWheelSelection`（写入下一帧输入），不产生 action、不改 phase | `WheelHudRenderer.java:183-185,228-232` |

### 时序（回答"setWheelSelection 会不会晚于 commit"）
同一帧内顺序固定为：`onRenderFrame()`（`presentFrameAt` + `advanceInteraction`，消费**上一帧**队列）→ `onRenderWorld` → Marker HUD → **Wheel HUD（算本帧 selection 写入队列）**（`CommonClient.java:345,371-374`）。
因此松手事件提交的永远是"**最后一次真实渲染出的那一帧**"的选择（`ClientPingRuntime.baselinePresentFrame:502-517` 保留队列、非 WHEEL_OPEN 时清 NONE），最坏滞后 1 帧（≈16 ms）。**不会出现"没画过就被提交"**：开轮发生在 `presentFrameAt`，同帧随后就会渲染并写入队列。

### WheelMouseCapture（本提交未改，核过是否够用）
26.3 API 齐全：`isMouseGrabbed()`(`MouseHandler:391`)、`grabMouse()`(`:395`)、`releaseMouse()`(`:413`)；**且 `releaseMouse()` 会把光标重新置到屏幕中心**（`:416-417`）→ 与"准星为轮盘圆心、初始选中 CENTER"的设计吻合。故"不改"是合理的；唯一耦合是上面的 R1 缩放问题。

## 三、健壮性：达标（2 条小建议）

| 项 | 结论 | 证据 |
|---|---|---|
| try/catch | 双层：`CommonClient.onRenderHud` 外层兜底 + `WheelHudRenderer.render` 自身 try/catch；**marker 先画、wheel 后画**，wheel 崩不影响 marker | `CommonClient.java:355-374`；`WheelHudRenderer.java:124-133` |
| 一次性告警 | `failureLogged` 静态单布尔，仅首次 warn（`Global.warnException`）；**永不复位**（同 M4 的 `hudFailureLogged`）→ 若首帧偶发失败，之后轮盘静默不工作。建议按世界/会话重置（N1） | `WheelHudRenderer.java:102,129-132` |
| 空 pingTypes | 双重保护：`WheelPresentationSnapshot` 构造器拒绝空列表(`:28-30`)、`renderFrame` 再判空早退 | `WheelPresentationSnapshot.java:28-30`；`WheelHudRenderer.java:151-154` |
| 单类型 | 整环填充、跳过分隔线；标签布局 `maxWidth>0` 兜底 | `WheelHudRenderer.java:308,684`；`WheelLabelLayout.java:226-235` |
| 目标名缺失 | `resolve()` 返回 null 直接 return；scale≤0 也 return | `WheelHudRenderer.java:362-380` |
| 每帧对象 | `new WheelGeometry` ×1 + `sectors/labels/textWidths/placements` 4 个列表；逐行合并 run 的填充不产生每像素对象；无字体/贴图缓存 | `WheelHudRenderer.java:173,195,313-322` — 可接受 |
| 语言键 | 7 类 `pingforit.ping_type.*` 与 `settings.pingforit.title` 在 en_us 全部存在（否则只会显示原始 key，不崩） | 已核对 |

## 四、日志契约

新增 5 类 INFO（`PingInteractionLogger.info` 默认 no-op，`global()` 才输出）：
`wheel open types=[id...]` / `wheel select pingType=id` / `wheel cancel markerId=N` / `wheel cancel reason=no-locally-owned-marker` / `wheel timeout reason=... openMillis=N` / `wheel close reason=no-selection` —— **全部只含 id/枚举/时长，无坐标、无名字、无 UUID** ✓（`PingInteractionStateMachine.java:391,486,508,518,525,533,570`）。
⚠️ 但 **M4 遗留未修**：`ClientPingRuntime.java:1016-1022` 仍在 INFO 里打印锚点世界坐标，违反同类注释 `:115` 的 "positions … never logged"。建议 M7/M8 一并去掉。

## 五、泄漏与 deferred

- `src/main` → `src/client` 专属 FQN 的 import：**0**（HEAD FQN 差集）✓。
- 新代码只 import `src`/MC/Fabric 类型；未引用 `deferred/`、未把 deferred 的 `WheelOverlayRenderer` 抄回（deferred 版本仍在原位，两者独立）✓。
- `WorldRenderContext`/`SettingsScreen` 的 import 指向的是 M3b 的 **src 内桩**，不是 deferred ✓。

## 六、分级问题清单

### 严重（实机前必须知道/验证）
- **R1（待确认，可能只在特定 DPI 下出现）**：鼠标→GUI 映射用 `Window.getWidth()`（framebuffer）而不是 `getScreenWidth()`。**验收时必须分别测 Windows 100% 与 150% 缩放 / GUI scale 1 与 2**；若出现"光标在右、选中在左/选不到外圈"，即命中此条，改 `getScaledXPos/getScaledYPos` 即可。
- **S1（M4 遗留，未修）**：F1 隐藏 HUD → `onRenderFrame()` 与轮盘渲染都不执行 → 长按不开轮、超时不动。验收时**不要按 F1**；M7 恢复渲染钩子后消失。

### 建议
- **N1**：`failureLogged` 永不复位（`WheelHudRenderer.java:102`）→ 加会话/世界重置或限频。
- **N2**：M4 的坐标日志违反 "positions never logged"（`ClientPingRuntime.java:1016`）→ 去掉坐标。
- **N3**：图标仍是实心方块（`textures/ping.png` 是 1×1 透明占位），离屏箭头/淡出/音效均属 M7 —— 验收时按"功能验收"而非"观感验收"。
- **N4**：`new WheelGeometry` 等每帧分配可缓存（非必要）。

## 七、验收建议（给 Lead 的测试清单）

1. 进世界 → 长按 ping 键 ≥ 阈值 → 轮盘出现、中心红盘为取消；松手在环内应发出对应类型。
2. **逐个 7 类**选一次，确认日志 `wheel select pingType=...` 与画面上高亮扇区一致（尤其"正上方"是否对应第一个扇区）。
3. 松手在**中心** → `wheel cancel reason=...`（有本地 marker 时应 CancelMarker；无则 no-locally-owned-marker）。
4. 松手在**环外** → `wheel close reason=no-selection`，不应发包。
5. 不松手等超时 → `wheel timeout reason=duration-exceeded`，轮盘消失。
6. **GUI scale 2 + Windows 150% 缩放各跑一遍第 2 步**（R1 判据）。
7. 全程按 F1 复现一次，确认现已知行为（不掩盖为 bug）。
