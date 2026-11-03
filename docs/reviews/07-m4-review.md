# 07 · M4 审核（HUD 最小闭环）

审核人：3号　基线：**HEAD = 313efb5**（工作区 clean）　方法：只读；`git show HEAD` + 源码/26.3 源码 jar + JDK-25 `javap`。**未跑 Gradle。**
改动面：5 文件 / +219 −5（`MarkerHudRenderer` 新增 100 行、`CommonClient` onRenderHud、`FabricClient` 注册、两处 INFO 日志）。

## 结论

**API 用法正确、投影链路成立、无 main→client 泄漏、无新增 NPE 路径 → 可以进 M4 冒烟。**
但有 **1 个功能性缺口必须记录**（HUD 隐藏时交互计时冻结）、1 处**违反项目自身隐私契约的日志**，以及若干性能/可测性建议。

---

## 一、Fabric 26.3 HUD API 用法：正确

| 检查 | 结论 | 证据 |
|---|---|---|
| `HudElementRegistry.addLast(Identifier, HudElement)` | 签名匹配 ✓ | `javap`（fabric-rendering-v1 27.2.0）：`addLast(net.minecraft.resources.Identifier, HudElement)` |
| `HudElement#extractRenderState` | 签名匹配 ✓（lambda 两参一致） | `HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker)`；`FabricClient.java:56-58` |
| `GuiGraphicsExtractor` 绘制 API | `guiWidth()/guiHeight()/fill(int,int,int,int,int)` 均在 ✓ | `MarkerHudRenderer.java:59-60,81-82` |
| 绘制时机 | 26.3 的 HUD 提取在 `Hud.extractRenderState`（`mc sources: net/minecraft/client/gui/Hud.java:220`）；`addLast` 挂在 hotbar/装饰之后（Fabric `HudMixin.wrapHotbar` 等 wrap 点） | 同上 |
| **菜单/暂停时是否调用** | **会**：只有 `LevelLoadingScreen` 被整体跳过（`Hud.java:222`）；其它屏幕（暂停、背包）照常提取 HUD（这也是原版 HUD 会画在菜单后面的原因） | `Hud.java:220-245` |
| **F1 隐藏 HUD 时** | **不会调用**：`if (!this.isHidden) { extractCrosshair; extractHotbarAndDecorations; ... }`（`Hud.java:223-230`、`:233-243`），而我们的元素挂在 hotbar 之后 | 同上 |

### 严重 S1：F1（隐藏 HUD）时交互计时冻结

- `onRenderFrame()` 现在**唯一**的调用点是 `CommonClient.onRenderHud`（`CommonClient.java:345`；`git grep` 确认无其它调用方）。
- 它推进的是 `PingInteractionStateMachine` 的帧时间：长按开轮、轮盘超时、`LongPressCompatibilityController` 的 presentFrame —— 全部依赖它。
- 一旦玩家按 F1（`Hud.isHidden == true`），HUD 元素不再被调用 → 长按不会开轮、超时不会触发；松开时 `onRelease` 仍会从 `KeyMapping` 边沿到达，此时状态机停在中途 → **交互结果异常（可能直发或静默丢弃）**。
- 影响面小（需按 F1），但**在 M4 冒烟/自动化里很容易被误判成"轮盘坏了"**。建议：短期在 `hudFailureLogged` 相邻处记录"F1 冻结"到 AGENTS 已知坑；M7 恢复渲染事件钩子后自然消失。

## 二、投影数学：链路正确，3 处建议

| 项 | 结论 | 证据 |
|---|---|---|
| `Camera#getViewRotationProjectionMatrix(Matrix4f)` | 26.3 存在 ✓ | `javap net.minecraft.client.Camera` → `public Matrix4f getViewRotationProjectionMatrix(org.joml.Matrix4f)` |
| `GameRenderer#mainCamera()` / `Camera#isInitialized()` / `DeltaTracker#getGameTimeDeltaPartialTick(boolean)` | 均存在 ✓ | javap 同上 |
| 齐次坐标 | `worldToScreen` 用 `Vector4f(pos-cameraPos, 1) → *modelView(单位阵) → *projection(P·Vrot)`，再按 `w` 透视除法 ✓ | `MathUtils.java:14-33` |
| clip w <= 0 / 背后点 | `depth = w`；`ScreenPos.isBehindCamera()` = `depth <= 0`，渲染器先判后画（`MarkerHudRenderer:65`）→ **不会出现镜像翻转** | `MathUtils.java:22-26`；`ScreenPos.java:17-19` |
| 屏幕纵横映射 | `x = W*(0.5 + ndcX*0.5)`、`y = H*(0.5 - ndcY*0.5)`（y 翻转）✓ | `MathUtils.java:28-32` |
| 深度范围 | 这里的 `depth` 只用于"是否在相机前"，不做深度/遮挡比较 → 与 NDC 深度范围无关 ✓（遮挡是 M7） | — |
| N1 相机来源重复 | `MathUtils.java:17` 又取了一次 `Game.gameRenderer.mainCamera()`，而 `MarkerView.update` 明明已把 `ctx.camera` 传进来（`MarkerView.java:227-228`）→ 建议改用 `ctx.camera`，可测性更好、也避免两处不同步 | — |
| N2 GUI 尺寸来源不一致 | `MathUtils` 用 `Window.getGuiScaledWidth/Height`（`:15,29-30`），而 `MarkerHudRenderer` 用 `graphics.guiWidth/guiHeight`（`:59-60`）→ 窗口 resize 的同一帧可能短暂不一致（点偏/被裁），建议统一走 `graphics` 尺寸 | — |
| N3 待确认 | `getViewRotationProjectionMatrix` 是否与 HUD 相位实际使用的 GUI 投影**逐位一致**（是否含 FOV 缩放/bobbing），以及 HUD 提取时相机矩阵是否已为本帧更新（若在相机更新之前，视角快速转动时会滞后一帧）。建议 M4 冒烟时把日志里的 `at=(x,y,z)` 与屏幕点位置人工对一次 | — |

## 三、线程、生命周期与开销

| 项 | 结论 | 证据 |
|---|---|---|
| 线程 | HUD 元素在渲染线程（= 客户端主线程）调用；marker store 的所有写入在 `context.client().execute(...)`（`FabricClient` 各 receiver）→ 与 `MarkerOverlayState`「主线程封闭」契约一致 ✓ | `CommonClient.java:338-355`；`FabricClient` receivers |
| 无世界保护 | `onRenderHud` 先判 `Game.level/player`（`:341`）；`hudRenderContext` 在 camera 为 null 或未初始化时返回 null（`:386-387`）；`MarkerOverlayState.prepare` 对 null ctx/store 走 `clear()`（`:92-95`） | — |
| 一次性兜底 | `hudFailureLogged`（`:64,360-362`）只写一次且**永不复位**：首帧偶发失败会永久静默后续真实故障。建议改为"首次 warn + 之后每 N 次 debug"或按世界会话重置 | `CommonClient.java:64,360-362` |
| 每帧分配 | `new Matrix4f()` ×2 + `WorldRenderContext`（`:384-395`），`MarkerOverlayState.prepare` 内再 `syncPlan`/`LinkedHashMap`/`HashSet`/`ArrayList`/`List.copyOf`（`MarkerOverlayState.java:101-150`）→ 约 5–7 个短期对象/帧；**无 marker 时也会建这些结构**（可加 `if (markers.isEmpty() && views.isEmpty()) return;`） | — |
| 交互计时耦合 | 见 S1：`onRenderFrame()` 只在这里被调用 | `CommonClient.java:345` |

## 四、数据一致性

- **同一份状态** ✓：HUD 读 `MarkerOverlayState.INSTANCE.renderViews()`（`CommonClient.java:355`），而 `onRenderWorld` 把 `pingRuntime.store()` 传进 `prepare`（M3b 起未改）；`applyCreated` 写的就是 `ClientPingRuntime.markerStore`（`ClientPingRuntime.java:998`）→ 同一个 `ClientMarkerStore`。
- **日志顺序** ✓：M4 的 `marker created ... at=(...)` 在 `markerStore.onCreated(...)` **之后**（`ClientPingRuntime.java:998 → 1016`）→ 只要日志出现，状态确实已写入；「日志有了、点没画」只可能来自下面 4 种**预期**情形：
  1. 目标在别的维度（`prepare` 只更新当前维度的视图，`MarkerOverlayState.java:126`）；
  2. marker 已超出客户端展示时长（`store.renderMarkers()` 过滤）；
  3. 点在相机背后或屏幕外（`MarkerHudRenderer.java:65,73` 静默跳过，箭头是 M7）；
  4. GUI 缩放/窗口尺寸不一致（见 N2）。
- **过期/移除同步** ✓：`store.onRemoved` 后下一帧 `prepare` 的 `plan.toRemove()` 会移除视图（`MarkerOverlayState.java:109-111`）。
- 注：那条 INFO 对**重传/同 id 刷新**也会打印（`newlySeen` 只控制音效/聊天），排障时别把重复日志当成重复 marker。

## 五、main→client 泄漏与 NPE

- `src/main` 对 client 专属 FQN 的 import：**0**（HEAD FQN 集合差集比对）✓。`ScreenPos` 位于 main 但为纯数学类型，可接受。
- 无新增 NPE 路径：进入 HUD 前有 level/player 判空（`:341`）、矩阵构建有 camera 判空（`:386-387`）、`prepare` 有 null 早退、`MarkerHudRenderer.render` 对空列表短路（`:55-57`）、`screenPos == null` 也判了（`:65`）。
- 唯一的脆弱点：`Game.gameRenderer` 本身若为 null（非正常状态）会在 `:384` NPE，但 `Minecraft.getInstance()` 在客户端初始化后恒非 null，且该路径已被 `Game.level != null` 前置约束。

## 六、分级问题清单

### 严重
- **S1**：F1 隐藏 HUD 时 `onRenderFrame()` 不执行 → 长按/轮盘超时/兼容模式计时全部冻结（`CommonClient.java:345`；26.3 `Hud.java:223-230` 证据）。M7 恢复渲染钩子后消失；在那之前请写进 AGENTS 已知坑。

### 建议
- **N4（契约违背，建议 M5 一并修）**：`ClientPingRuntime.java:115` 的类注释明确「positions, names, and colors are never logged」，但 M4 新增的 INFO 记录了锚点坐标（`:1016-1022`）。建议降级为 debug 时去掉坐标，或改成 `kind + markerId` 即可。
- **N5**：`ClientPingActionDispatcher.java:127` 的 INFO 未带 payload 风险（id/kind/pingType 均安全 ✓），但与上面的 N4 一起纳入 M5「日志开关/降级」任务。
- **N1/N2/N3**：见 §二（相机来源、GUI 尺寸来源、矩阵对齐待确认）。
- **N6**：`hudFailureLogged` 永不复位（`:64`），建议加会话/世界重置或限频。
- **N7**：无 marker 时仍构造 5–7 个临时集合/帧（`MarkerOverlayState.java:101-150`），可提前早退。
- **N8**：`MarkerHudRenderer` 用的是 `fill` 纯色方块（M7 换 `ping.png`/箭头/文字），当前没有离屏箭头也不做遮挡 → 冒烟时"墙后也能看到点"属预期。

## 七、复现命令（只读）
```
git show HEAD -- src/client/java/nx/pingwheel/common/CommonClient.java   # onRenderHud/hudRenderContext
git show HEAD -- src/client/java/nx/pingwheel/fabric/FabricClient.java   # HudElementRegistry.addLast
git grep -n 'onRenderFrame()' HEAD -- src                                 # 唯一调用点
javap -p -cp <minecraft-clientOnly-...-26.3.jar> net.minecraft.client.Camera | findstr ViewRotationProjection
javap -p -cp <minecraft-clientOnly-...-26.3.jar> net.minecraft.client.gui.GuiGraphicsExtractor | findstr "guiWidth fill"
javap -p -cp <fabric-rendering-v1-27.2.0.jar> net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
# 26.3 Hud 门控：解包 -sources.jar 后看 net/minecraft/client/gui/Hud.java:220-245
```
