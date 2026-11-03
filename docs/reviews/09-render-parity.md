# 09 · 渲染/标记视觉保真度审计（上游 1.21.1 vs 当前 26.3 移植）

> 审核：1号 repo-analyst｜2026-10-29｜**只读源码，未运行游戏**
> 上游：`Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1/common/src/main/java/nx/pingwheel/common/`（下文 `UP:`）
> 当前：`src/client/java/nx/pingwheel/common/`（下文 `CUR:`）；未编译的上游渲染子系统在 `deferred/`

## 0. 结论先行（回答用户的两个现象）

1. **上游对 BLOCK 目标画的不是方块模型，而是「通用 ping 图标」**：默认是 5×5 实心方块绕 Z 轴旋转 45° 的**菱形**（`UP:render/DrawContext.java:76-82`）；只有玩家装了自定义资源包（`hasCustomTexture()` 为真）才画 12×12 贴图（`:84-111`）。**物品实体**才画真·物品图标 16×16（`:72-74`）。
2. **「看起来像具体方块」来自世界空间描边，不是 HUD 图标**：上游对每个被 ping 的方块按**原生 `VoxelShape` 画线框**，穿墙、线宽 3.75px（`UP:client/outline/BlockOutlineRenderer.java:114-127`；`BlockOutlineRenderType.java:20-29,53`）。该层在 `deferred/`，**当前未编译、未渲染**。
3. **「距离越远标记越大」不是代码里真的放大**：上游与当前用的是**同一个**距离缩放公式（`UP:client/marker/MarkerView.java:262-267` ≡ `CUR:client/marker/MarkerView.java:282-287`）：`scale = max(1, 2/d^0.3) * 0.5 * pingSize`。d≈1 时 scale≈1.0，d≳10 时被 `max(1,…)` 夹到 0.5 并不再变小。所以**绝对像素是近大远小**（当前：近 18px、远 10px），但**相对于屏幕上不断缩小的方块**，被「8px 半边长 + 最小 3px + 0.5 下限」钉住的标记会显得越远越大——而 M4 又没有世界空间描边给出与方块一致的尺寸锚点。
4. 当前 `CUR:client/hud/MarkerHudRenderer.java` 是 **M4 的最小占位**（类注释 `:10-35` 自述：只画一个实心方块、无贴图/箭头/文本/物品图标/淡出）。它的 8px 半边长 → **16px 宽**，是上游菱形 **5px 宽的 3.2×（面积约 10.2×）**（`MarkerHudRenderer.java:39-42` vs `DrawContext.java:76-82`）。
5. 现状**没有文本层**：距离数值、目标名、玩家头颅、队伍色都在上游 `PingLocationRenderer`（`UP:render/PingLocationRenderer.java:40-57`）与 `DrawContext.renderLabel`（`:33-52`），当前无对应实现。
6. 轮盘已实现（M6）：`CUR:client/hud/WheelHudRenderer.java` 用 26.3 的 `GuiGraphicsExtractor` 重写；但其**图标退化为纯色方块**，原因写在类注释 `:44-56`——`textures/ping.png` 是 1×1 全透明占位（70 B，上下游同），染色后画不出任何东西。

## 1. 上游视觉层 vs 当前实现（总表）

| # | 视觉层 | 上游（1.21.1） | 当前（26.3） | 差异 | 归属 |
|---|---|---|---|---|---|
| 1 | 默认标记图标 | `renderDefaultPingIcon`：`fill(0,0,5,5)` + `rotateZ(π/4)` + `translate(-2.5,-2.5)`（`DrawContext.java:76-82`） | `fill` 实心方块，半边长 `8*scale`（`MarkerHudRenderer.java:78-82,91-99`） | 形状（方块 vs 菱形）、尺寸（16px vs 5px 基准）、无旋转 | **M6.5** |
| 2 | 自定义贴图图标 | `renderTexture(PING_TEXTURE_ID, 12, color)`（`DrawContext.java:84-111`），仅 `hasCustomTexture()` 时 | 无 | 资源缺失分支未实现；注意 `ping.png` 是 1×1 透明占位（70 B，上下游一致），**不能**直接当图标 | **M6.5** |
| 3 | 物品实体图标 | `renderItem(itemStack, -8, -8, 0, -150)` 16×16 居中（`DrawContext.java:72-74`），仅 `ItemEntity` + `itemIconVisible`（`MarkerView.java:193-195`） | `itemStack` **已采集**（`CUR:MarkerView.java:206-210`）但渲染器未使用 | 掉落的物品看不到图标 | **M6.5** |
| 4 | 距离文本 | `renderLabel(distanceText, -1.5f, …)`（`PingLocationRenderer.java:40-41`），`%,.1f` 米 | 无 | 缺整层 | **M6.5** |
| 5 | 目标名文本 | `renderLabel(targetName, -2.75f, null, white)`（`:47`），恒不透明白 | 无 | 缺整层 | **M6.5** |
| 6 | 玩家头颅 / 队伍色 | `renderPlayerHead` 8×8 皮肤贴图（`DrawContext.java:54-60`）；队伍色走 `renderLabel`（`PingLocationRenderer.java:49-57`） | 无 | 缺整层 | **M6.5** |
| 7 | 屏外/背后箭头 | `DirectionIndicatorRenderer`：safe-zone 内缩、`calculateAngleRectIntersection` 取边、12px 偏移、0.25× 缩放后画 10px `arrow.png`（`:18-83`） | 直接 `continue` 跳过，注明 TODO(M7)（`MarkerHudRenderer.java:65-68,73-76`） | 看不到屏外目标 | **M6.5**（箭头是 HUD 层） |
| 8 | 方块世界描边 | `BlockOutlineRenderer`：按 `VoxelShape` 生成边线，`NO_DEPTH_TEST` 穿墙、`LINE_WIDTH=3.75px`（`:114-127`；`BlockOutlineRenderType.java:20-29,53`） | `deferred/client/outline/`（66 文件），**未编译** | 完全没有世界空间高亮 | **M7** |
| 9 | 实体描边/发光 | `EntityOutlineRunner`（143 行）+ `LevelRenderer` 的 outline 批处理钩子 | `deferred/` | 无 | **M7** |
| 10 | 虚拟方块展示（entity_block/展示型方块） | `VirtualBlockDisplayRenderer`（990 行）+ `BlockPresentation*` 一整套 | `deferred/` | 无 | **M7** |
| 11 | 轮盘 | `WheelOverlayRenderer`（644 行）：弧段按采样填充、opacity 走 `WheelOpacity`（`:137,152,258`） | `WheelHudRenderer`（837 行，26.3 重写）；图标为纯色方块（`:44-56`） | 已可用；图标/贴图退化 | **M6 已完成**；图标细节 M7 |
| 12 | z 排序 | `OverlayRenderer`：整体 `translate(0,0,-views*16)`，逐条 `+16`（`:29-45`） | 按列表顺序 `fill`，无 z 平移 | 多标记重叠时的前后顺序不同 | **M6.5** |
| 13 | 淡出/过期 | 无 per-marker 淡出；到显示期限整体消失（`WheelOpacity` 只服务轮盘） | 同（`MarkerHudRenderer.java:33-34` 注明） | 基本一致 | — |

## 2. 三种目标类型：上游到底画什么、多大、画在哪

### 2.1 共同骨架（`UP:render/OverlayRenderer.java:12-57`）

- 每帧取 `MarkerOverlayState.renderViews()`；无玩家/无世界/无视图直接返回（`:16-18`）。
- 只画**当前维度**（`:35`）；相机背后且关闭方向指示器时跳过（`:41-43`）。
- 整层先 `translate(0,0,-renderViews.size()*16f)`（`:30`），逐条再 `translate(0,0,16f)`（`:45`）→ 这是**标记间的 z 分层**，避免叠放时互相覆盖。
- 屏幕内：`PingLocationRenderer.draw`（`:52`）；屏幕外/背后：`DirectionIndicatorRenderer.draw`（`:48`）。

### 2.2 屏幕内标记（`UP:render/PingLocationRenderer.java:19-60`）

```java
m.translate(screenPos.x, screenPos.y, 0);
m.scale(pingScale, pingScale, 1f);           // :30-32  —— 整个标记（图标+文本）同缩放
ctx.renderLabel(distanceText, -1.5f, …);    // :40-41  —— 距离在下
ctx.renderPing(itemStack, itemIconVisible, pingColor); // :42 —— 图标在原点
ctx.renderLabel(targetName, -2.75f, null, white);      // :47 —— 目标名在上
ctx.renderLabel(displayName, 1.75f, author, WHITE);    // :56 —— 玩家名在更下（可选）
```

| 目标类型 | 图标内容（`renderPing`，`DrawContext.java:62-70`） | 尺寸 | 屏幕位置 |
|---|---|---|---|
| **BLOCK** | `itemStack == null` → 有自定义资源包画 12px `ping.png` 染色；否则 **5×5 菱形**（旋转 45°） | 5×5（菱形对角线约 7px），乘 `scale` | 投影点 `screenPos` 原点居中；距离文本 `-1.5` 行高、目标名 `-2.75` 行高在上 |
| **ENTITY（非物品）** | 同上（菱形/12px 贴图），无实体自身模型 | 同上 | 同上 |
| **ENTITY（ItemEntity）** | `renderItem(itemStack, -8, -8)` → **真·物品图标 16×16** | 16×16，乘 `scale` | 同上（-8 偏移使图标居中） |
| **LOCATION** | 同上（菱形/12px 贴图）——位置类没有“本体”可画 | 同上 | 同上 |

### 2.3 缩放（两个版本一致，`MarkerView.java:262-267` ≡ `282-287`）

```java
final var scale = 2.0 / Math.pow(this.distance, 0.3);
final var pingSize = config.getPingSize() / 100f;
this.scale = (float)Math.max(1.0, scale) * 0.5f * pingSize;
```

| 距离 d | `2/d^0.3` | 夹取后 | `scale` | 上游菱形边长 | 当前方块边长（含 1px 边框） |
|---|---|---|---|---|---|
| 1 | 2.00 | 2.00 | 1.00 | 5.0 px | 18 px |
| 4 | 1.32 | 1.32 | 0.66 | 3.3 px | 13 px |
| 10 | 1.00 | 1.00 | 0.50 | 2.5 px | 10 px |
| 64 / 256 | 0.50 / 0.37 | **1.00**（下限） | 0.50 | 2.5 px | 10 px |

> 结论：**远距离时两个实现都不再缩小**（`max(1.0, …)` 是“下限”不是“距离补偿上限”）；差别在于基准尺寸（上游 5px、当前 16px）与是否有世界描边。

## 3. 两个用户现象的事实依据

### 3.1 「距离越远，屏幕上的方块越大」

**代码没有把远处画大**，产生该观感的是三个叠加因素：

1. **尺寸下限**：`max(1.0, 2/d^0.3)` 让 d≳10 之后 `scale` 恒为 0.5；当前渲染器再把 `8*scale` 夹在 `[3,32]`（`MarkerHudRenderer.java:91-99`）→ 远处方块**固定 8×8 内部 + 1px 边框**，不再随距离缩小。
2. **基准过大**：同样是 `scale=1`，上游菱形 5px，当前方块 16px（`:40` 的 `HALF_SIZE_AT_SCALE_ONE = 8f`）→ 3.2×。
3. **缺世界锚点**：上游用 `BlockOutlineRenderer` 在方块本体上画线框（随透视自然缩放），远看是「小方块+小描边」；M4 没有这层，屏幕上只剩一个不缩小的 HUD 方块，于是**相对于变小的方块**显得“越远越大”。

### 3.2 「只显示一个纯色方形，而不是具体的方块/物品」

- **上游对 BLOCK 也从不画方块模型**；它画的是通用 ping 图标 + 世界空间 `VoxelShape` 线框。所以“具体方块”的观感来自描边层（`BlockOutlineRenderer.java:114-127`），该层在 `deferred/`。
- **物品实体**上游确实画真物品图标（16×16，`DrawContext.java:72-74`），当前未使用已采集的 `itemStack`（`CUR:MarkerView.java:206-210`）。
- 想接贴图也不行：`textures/ping.png` 是 **70 B 的 1×1 全透明占位**（上下游一致，`assets/pingforit/textures/ping.png`），染色后仍然透明；`arrow.png` 是正常 64×64（3627 B）可复用。**正确做法是恢复“5×5 旋转 45° 菱形”的 fills 画法**，而不是贴图。

## 4. 差异清单（按修复归属）

### M6.5 · HUD 视觉层（低风险、无新渲染原语）

| # | 待修 | 上游依据 |
|---|---|---|
| A | 标记图标改为 **5×5 菱形**（绕 Z 轴 45°），基准与上游一致；`pingSize` 只做倍率 | `DrawContext.java:76-82` |
| B | 物品实体画 **16×16 物品图标**（**26.3 入口已确认**：`GuiGraphicsExtractor.item(ItemStack, int x, int y)`，`GuiGraphicsExtractor.java:876-904`，x/y 为左上角 → 上游 `renderItem(-8,-8)` 等价物写作 `item(stack, -8, -8)`；另有 `fakeItem` :906-912、`itemDecorations` :914-926） | `DrawContext.java:72-74`；`MarkerView.java:193-195` |
| C | 距离文本（`%,.1f` 米）+ 目标名（恒白）+ 可选玩家名 + 8×8 头颅 | `PingLocationRenderer.java:40-57`；`DrawContext.java:33-60` |
| D | 屏外/背后箭头：safe-zone 内缩 + 边相交 + 0.25× 缩放（用 64×64 `arrow.png`） | `DirectionIndicatorRenderer.java:18-83` |
| E | 标记间 z 分层（`-views*16` + 逐条 `+16` 的等价顺序） | `OverlayRenderer.java:29-45` |

### M7 · 世界空间层（重写，26.3 无对应原语）

| # | 待修 | 上游依据 |
|---|---|---|
| F | 方块原生 `VoxelShape` 线框，**穿墙**、线宽 3.75px | `BlockOutlineRenderer.java:64-171`；`BlockOutlineRenderType.java:20-29,53` |
| G | 实体描边/发光（`EntityOutlineRunner` + 渲染钩子） | `deferred/client/outline/EntityOutlineRunner.java`（143 行） |
| H | 虚拟方块展示（entity_block / 展示型） | `deferred/client/outline/VirtualBlockDisplayRenderer.java`（990 行） |
| I | 轮盘图标/贴图细化（当前纯色方块 → 与上游一致的弧段+标签+可染色图标） | `WheelOverlayRenderer.java:137-258`；`CUR:WheelHudRenderer.java:44-56` |

> 注：轮盘**同时**只显示当前目标类型的 3–4 个扇区（7 种类型是并集，按 `TargetTypeCatalog` 分桶：`src/main/java/nx/pingwheel/common/domain/TargetTypeCatalog.java:43-82`），所以“轮盘只有 3 项”是正确行为，不是缺项。

## 5. 26.3 HUD API 核实结果

### 5.1 26.3 `GuiGraphicsExtractor` 已核实 API（M6.5 直接可用）

> 来源：`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-1b0498e74c/26.3/...-sources.jar` → `net/minecraft/client/gui/GuiGraphicsExtractor.java`（1489 行），逐条读过。

| 用途 | 26.3 API | 行号 | 与上游的对应关系 |
|---|---|---|---|
| 物品图标 | `item(ItemStack, int x, int y)` / `item(ItemStack, int x, int y, int seed)` / `item(LivingEntity, ItemStack, int x, int y, int seed)` | `:876-904` | **x,y 是左上角**：上游 `renderItem(stack, -8, -8)` → `item(stack, -8, -8)` |
| 物品图标（推荐路径） | `item(LivingEntity owner, ItemStack, int x, int y, int seed)`（owner 传**本地玩家**，`Minecraft.player`） | `:884-886` | **比 `fakeItem` 更贴上游**：上游 `renderItem` 会带上本地玩家上下文（附魔光效/模型变体），`fakeItem` 传 `owner=null` 会少这一层；**必须配套 `itemDecorations(...)`** 才有数量/耐久条 |
| 物品装饰 | `itemDecorations(Font, ItemStack, int x, int y[, String countText])` | `:914-926` | 上游 `renderItem` 自带装饰；26.3 拆成两步，要一致需再调一次 |
| 2D 变换 | `pose()` → `Matrix3x2fStack`（`pushMatrix/popMatrix/translate/scale/rotate`） | `:145-147` | 菱形 45° 旋转、`pingScale` 缩放、分层平移都用它，**不必自算坐标** |
| 贴图 | `blit(...)` 多组重载 | `:315-412` | 上游 `renderTexture` / `renderPlayerHead` 的等价物（`arrow.png` 64×64 可直接用） |
| 纯色矩形 | `fill(int x0,int y0,int x1,int y1,int col)` | `:190-208` | 端点会被归一成 (大,小)，宽 = |x1-x0|，坐标是 **GUI 缩放坐标**（`:137-145`）；5×5 方块/菱形三角都靠它 |
| 文本（String） | `text(Font, String, int x, int y, int color)`；另有 `text(..., boolean dropShadow)` | `:251-259` | 上游 `guiGraphics.drawString(font, text, x, y, color, false)` = **`text(font, text, x, y, color, false)`**（默认重载走 `dropShadow=true`，必须显式传 `false` 才对得上 `:44`） |
| 文本（FormattedCharSequence / Component） | `text(Font, FormattedCharSequence, …)` `:261-269`；`text(Font, Component, …)` `:271-277` | `:261-277` | 目标名/玩家名是 `Component` → 直接用 `text(font, component, x, y, color, false)` |
| 居中文本 | `centeredText(Font, String\|Component\|FormattedCharSequence, x, y, color)` | `:279-290` | 上游用 `font.width(text)` 自行做 `-0.5×宽`（`DrawContext.java:35-39`）；26.3 的 `centeredText` 语义等价，但**上游的 yOffset 多行布局**仍需自己算，不冲突 |
| 文本底衬 | `textWithBackdrop(Font, Component, textX, textY, textWidth, textColor)` | `:305-308` | 上游是手绘 `fill(-2,-2,w+1,h,SHADOW_BLACK)`（`DrawContext.java:43`）；26.3 提供了现成的底衬（内部 `fill(textX-2, textY-2, textX+textWidth+2, textY+9+2, …)`），二者可选其一，**若要像素级一致仍用手绘 fill** |
| 文本内部实现 | `addText(new GuiTextRenderState(font, str, new Matrix3x2f(this.pose), x, y, color, 0, dropShadow, false, …))` | `:267` | 说明文本与 `fill`/`item` **共用同一个 `pose()` 2D 变换**：`pushMatrix → translate(屏幕点) → scale(pingScale)` 后，文本自动跟着缩放/定位 |

### 5.2 仍待验证（只剩一条）

| 项 | 状态 |
|---|---|
| 世界空间描边 / 实体描边在 26.3 管线下的可用原语（穿墙线框、线宽 3.75px 的等价实现） | **待验证**（M7 施工时以编译 + 实机截图为准） |

## 6. 复核方式（只读，可复现）

- 图标尺寸：`UP:render/DrawContext.java:62-115`；当前 `CUR:client/hud/MarkerHudRenderer.java:37-99`。
- 缩放公式与屏幕投影：`UP:client/marker/MarkerView.java:212-216,262-267`；`CUR:client/marker/MarkerView.java:227-231,282-287`；`MathUtils.worldToScreen` 两边同构（`UP:math/MathUtils.java:14-32` vs `CUR:math/MathUtils.java:28-50`）。
- 文本/箭头/描边：`UP:render/PingLocationRenderer.java`、`UP:render/DirectionIndicatorRenderer.java`、`UP:client/outline/BlockOutlineRenderer.java`。
- 资源体积：`assets/pingforit/textures/ping.png` 70 B（1×1 透明占位）、`arrow.png` 3627 B（64×64），上下游一致。
- 26.3 HUD API（含文本/阴影/居中）已全部核实（见 §5.1）；仍待验证的只剩世界/实体描边原语（见 §5.2）。
