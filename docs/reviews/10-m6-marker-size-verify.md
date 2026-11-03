# 10 · M6 标记尺寸独立验算（『距离越远越大』+ 纯色方块）

任务：task-3　审核人：3号　基线：**HEAD = 3504a59**（工作区 clean）　方法：只读源码 + 26.3 sources jar（`.gradle/loom-cache/...`）+ 手算数值表。未跑 Gradle、未改 `src/**`。

> **Lead 决策（2026-10-29，已采纳）**
>
> - **保持 `MarkerView.calculateScale` 与上游逐字一致**（`max(1.0, 2/d^0.3) * 0.5 * pingSize/100`）：远处 `0.5` 倍恒定是上游**刻意设计**（保证远处 ping 始终可读），真正的空间定位交给世界描边；移植期擅自改公式会让后续与上游的行为对比失去基准。**不得为观感改公式。**
> - **视觉差异在几何层解决**：标记按上游三选一重画（物品 16×16 图标 / 默认 5×5 旋转 45° 菱形 / 自定义 12px 贴图），尺寸统一经 `pose().scale(view.getScale())`；详见 §6 **方案 A**。
> - **世界层观感由 M7 负责**（取回 `BlockOutlineRenderer` / `VirtualBlockDisplayRenderer`）；届时"恒定尺寸"不再突兀。
> - **方案 B（改公式）已被 Lead 否决**，仅在本报告 §6 保留记录。

## 一、结论速览

| # | 待验命题 | 结论 | 依据 |
|---|---|---|---|
| 1 | 代码会让「距离越远标记越大」 | **不成立**（单调递减，10 格后恒定） | `MarkerView.java:282-287` + `MarkerHudRenderer.java:91-99` |
| 2 | 那为什么用户觉得「越远越大」 | **相对尺寸错觉**：标记像素不再缩小，而世界方块持续变小 → 标记/方块比从 ~10% 涨到 100%+ | 数值表见 §三 |
| 3 | 标记「只是纯色方块」 | **成立**：只 `fill` 外框+实心块，不用物品图标/贴图 | `MarkerHudRenderer.java:81-82`；`MarkerView.getItemStack():96` 未被使用；`textures/ping.png` 实测 **1×1**（70 B 占位） |
| 4 | HUD 重复绘制 | **不存在**：HUD 元素注册 1 处、`render` 调用 1 处、每帧提取 1 次 | `FabricClient.java:48`；`CommonClient.java:376` |
| 5 | `fill` 坐标语义 | GUI 缩放坐标，宽度 = `|x1−x0|`；`fill` 内部会把两端点归一为(大,小) | `GuiGraphicsExtractor.java:137-145,190-208,238` |
| 6 | 相对上游的倍率 | scale=1 时方块宽 16px = 上游默认菱形 5px 的 **3.2×**（面积 10.2×） | 上游 `DrawContext.java:76-82` |

> 一句话：**"越远越大"不是代码算出来的，是"远处不再缩小"造成的相对观感；而"纯色方块"是真的，且基准尺寸比上游大 3.2 倍。**

---

## 二、验算 1：距离 → 尺寸（可复算）

公式链（两处、无其它放大路径）：

```
MarkerView.calculateScale (282-287):
    raw   = 2.0 / distance^0.3
    scale = max(1.0, raw) * 0.5 * pingSize/100

MarkerHudRenderer.halfSize (91-99):
    half  = clamp( round(8 * scale), 3, 32 )      // HALF_SIZE_AT_SCALE_ONE = 8

MarkerHudRenderer.render (78-82):
    fill(x-half-1, y-half-1, x+half+1, y+half+1)  // 1px 外框
    fill(x-half,   y-half,   x+half,   y+half)    // 实心块
    → 内部宽 = 2*half px，含外框 = 2*(half+1) px
```

默认配置 `pingSize = 100`（`ClientConfig.java:28`，且 `setPingSize` 无 clamp，`:410-412`）：

| distance (格) | raw = 2/d^0.3 | max(1,raw) | scale | half | 内部宽 | 含 1px 外框 |
|---|---|---|---|---|---|---|
| 0.2 | 3.24 | 3.24 | 1.621 | 13 | 26px | 28px |
| 0.5 | 2.462 | 2.462 | 1.231 | 10 | 20px | 22px |
| **1** | 2.000 | 2.000 | 1.000 | **8** | **16px** | 18px |
| 2 | 1.625 | 1.625 | 0.812 | 6 | 12px | 14px |
| 3 | 1.438 | 1.438 | 0.719 | 6 | 12px | 14px |
| 5 | 1.234 | 1.234 | 0.617 | 5 | 10px | 12px |
| 10 | 1.002 | 1.002 | 0.501 | 4 | 8px | 10px |
| **20 / 50 / 100 / ∞** | 0.814 / 0.63 / 0.50 / →0 | **1.000** | **0.500** | **4** | **8px** | **10px** |

**趋势：随距离单调减小，并在约 `d = 2^(1/0.3) ≈ 10.08` 格之后被 `max(1.0, …)` 夹住、永久恒定。**
即：**最远与 10 格处尺寸完全一样；最近（<1 格）反而最大（可达 26–66px）。** 代码里不存在任何一条"越远越大"的路径（`halfSize` 是 `round(8*scale)` 的纯线性映射，`Math.round`/`clamp` 均不反转单调性）。
若用户把 `pingSize` 调到 150，则远/近为 12px vs 24–30px，结论不变（`d≥10` 仍是常数）。

---

## 三、为什么"看起来越远越大"：透视比例（量化）

世界中方块在屏幕上的高度（竖直 FOV 70°，屏幕高 H px）：

```
face_px ≈ H / (2 · d · tan(35°)) = H / (1.4004 · d)
```

标记内部方块在 `d ≥ 10` 之后恒为 **8px**。对照（1080p，GUI scale = 1 ⇒ H = 1080）：

| distance | 1 格方块面（px） | 标记（px） | 标记 / 方块面 |
|---|---|---|---|
| 5 | 154.2 | 10 | 6% |
| 10 | 77.1 | 8 | 10% |
| 20 | 38.6 | 8 | 21% |
| 50 | 15.4 | 8 | 52% |
| **96** | **8.0** | **8** | **100%（相等）** |
| 100 | 7.7 | 8 | 104%（标记更大） |

GUI scale = 2 时整体除以 2：**交叉点在约 48 格**，即"半屏距离外，标记就已经比一个方块面还大"。
→ 用户从近到远看同一个 ping：30 格内方块迅速变小、标记纹丝不动，视觉上就是"标记越来越大（相对）"，并在 ~48–96 格外**绝对吃掉**方块面。这解释了实机反馈，而不需要代码里存在放大逻辑。

次因（同样可查）：近距离时锚点常落在屏幕外或边缘（`MarkerHudRenderer.java:73` 越界直接 `continue`），用户更容易只看到远处那批"恒定 8px"的标记，采样偏差会强化"远处更大"的印象。

---

## 四、验算 2：纯色方块 vs 上游几何

| 呈现 | 上游（1.21.1） | 当前 M4/M6 | 倍率/差异 |
|---|---|---|---|
| 默认图标 | 旋转 45° 的 **5×5 菱形**（`DrawContext.java:76-82`：`rotateZ(π/4)` + `fill(0,0,5,5)`） | 未旋转的实心方块+1px 外框（`MarkerHudRenderer.java:81-82`） | scale=1 时 **16px vs 5px = 3.2×**（面积 10.2×），且丢了 45° 姿态 |
| 自定义贴图 | `renderTexture(PING_TEXTURE_ID, 12, color)` → **12px**（`:84-111`） | 不用贴图；`textures/ping.png` 实测 **1×1**（70 B，占位） | M7 TODO（`MarkerHudRenderer.java:25-35`） |
| 物品 ping | `renderGuiItemModel(itemStack, -8, -8)` → **16px 物品图标**（`:72-74`） | `MarkerView.getItemStack():96` **有数据但 HUD 不读** | 物品 ping 显示为纯色方块，功能可辨但语义丢失 |
| 玩家头/名字/距离 | 有（`DrawContext:53-60` 皮肤头、上游 `OverlayRenderer` 文本） | 无（M7 TODO） | 观感差距 |

**因此"只是纯色方块"成立**，且不只是"没画贴图"：**基准尺寸本身是上游默认图标的 3.2 倍**，再叠加 §三 的相对放大，观感会显著"更大更方"。

---

## 五、验算 3：重复绘制与坐标语义（排除误判）

1. **只注册一次**：`FabricClient.java:48` `HudElementRegistry.addLast(MARKER_HUD_LAYER_ID, …)` 是全库唯一注册点（`grep` 1 处）。
2. **每帧只调一次**：`MarkerHudRenderer.render(...)` 唯一调用点 `CommonClient.java:376`；`onRenderHud` 每帧被 Fabric HUD 提取调用一次（26.3 `Hud.extractRenderState:220` → hotbar wrap 点）。
3. **不存在世界侧二次绘制**：M4–M6 没有任何世界空间标记渲染（描边/世界渲染整体在 `deferred/`，不参与编译）。
4. **坐标语义**：`GuiGraphicsExtractor.fill` 把两端点归一到 `(较大, 较小)`（`:195-205`）后构造 `ColoredRectangleRenderState(...)`（`:238`），顶点按 `(x0,y0)/(x1,y1)` 画（`ColoredRectangleRenderState:39-40`）→ **宽度 = |x1−x0|**，与两次 `fill` 的意图一致，无 off-by-one/翻转。
   `guiWidth()/guiHeight()/pose()` 都是 **GUI 缩放坐标**（`:137-145`，pose 为 `Matrix3x2fStack`），所以标记尺寸会随 GUI scale 变化（scale=2 时 8px 方块对应物理 16px）——这属预期，不是重复绘制。
5. **F1 隐藏 HUD 时不绘制**（`Hud.java:223-230`），也不会出现"隐藏后残留/重影"。

---

## 六、修复建议（按 Lead 决策重排）

> 前置事实（已核实）：上游 `MarkerView.calculateScale`（`Mod_Project/.../client/marker/MarkerView.java:262-267`）与本项目 `MarkerView.java:282-287` **逐字相同**：`(float)Math.max(1.0, scale) * 0.5f * pingSize`，`scale = 2.0 / distance^0.3`；上游渲染时再对几何统一做 `translate(screenPos) + scale(pingScale)`（`PingLocationRenderer.java:27-32`）。**结论：scale 公式不是缺陷，几何才是。**

### 方案 A（对齐上游几何，推荐；**不改** calculateScale）

目标：`MarkerHudRenderer` 改用上游三种呈现之一，尺寸全部经 `pose().scale(view.getScale())` 统一缩放；删除 `halfSize()` / `HALF_SIZE_*` / 1px 外框。

```java
// MarkerHudRenderer.render(...) 核心（示意，约 18 行）
final float s = Math.max(view.getScale(), 0.01f);
final Matrix3x2fStack pose = graphics.pose();
pose.pushMatrix();
try {
    pose.translate((float) screenPos.x, (float) screenPos.y);
    pose.scale(s, s);
    final ItemStack icon = view.getItemStack();            // 上游：物品 ping 画 16x16 图标
    if (icon != null && ClientConfig.HANDLER.getConfig().isItemIconVisible()) {
        graphics.item(icon, -8, -8, 0);                    // DrawContext:72-74 同参数
    } else {                                               // 上游：默认 5x5 旋转 45° 菱形
        pose.pushMatrix();
        try {
            pose.rotate((float) (Math.PI / 4.0));          // Matrix3x2f.rotate(float) 已核
            pose.translate(-2.5f, -2.5f);
            graphics.fill(0, 0, 5, 5, view.getPingColor());
        } finally { pose.popMatrix(); }
    }
} finally { pose.popMatrix(); }
```

效果（配合 §二 的 scale 表）：`d ≥ 10` 时 `scale = 0.5` → 菱形宽 **2.5px**、物品图标 **8px**，与上游逐像素一致；`pingSize` 仍是全局倍率。

可选（M7）：自定义贴图路径用 `blit(Identifier, x, y, ...)`（26.3 `GuiGraphicsExtractor` 有 `blit(Identifier,int,int,int,int,float,float,float,float)`，参数顺序实现时核对）；注意 `ping.png` 当前是 **1×1 占位**（70 B），需先把资源换成真正的图标。

注意：
- 2D 旋转的菱形边缘会有锯齿（上游同样如此），抗锯齿留 M7。
- 上游没有 1px 外框（`DrawContext:76-82` 只画一次 `fill`）——删掉外框才与上游一致；可见性用 `pingSize` 调，不加外框。
- 物品图标 API 三个重载已核：`GuiGraphicsExtractor.java:876-886`（`item(ItemStack,x,y)` / `item(ItemStack,x,y,seed)` / `item(LivingEntity,ItemStack,x,y,seed)`）。
- 上游 `renderItem(itemStack, -8, -8, 0, -150)` 的第 5 参是 z 偏移（`-150`，压到 HUD 之上）；26.3 的 `item(...)` 无 z 参数，层级改由 `graphics.nextStratum()` / HUD 层序控制 —— 实现时需确认物品图标不被已有 HUD 元素遮挡。

### 方案 B（改 calculateScale；**Lead 已否决，仅记录**）

即初稿做法：`clamp(2/d^0.3, 0.35, 2.0)` 去掉 plateau、基准改 `2.5f`。**不采用**，原因：

1. **与上游不一致**：上游刻意用 `max(1.0, …)` 让远处 ping 恒定可读，空间感交给世界描边；移植期改公式会让后续与上游行为对比失去基准；
2. **掩盖 M7 缺失**（当前没有描边）而不是修复；
3. "远处太小不易读"属产品问题，用配置项 `pingSize`（默认 100，可调）解决，不动公式。

---

## 七、复现方式

```
# 数值表
$ d in 0.2,0.5,1,2,3,5,10,20,50,100:
  raw=2/pow(d,0.3); s=max(1,raw)*0.5; half=clamp(round(8*s),3,32); width=2*half
# 源码
MarkerView.java:282-287 / MarkerHudRenderer.java:39-42,78-82,91-99
# 上游几何
Mod_Project/.../render/DrawContext.java:72-82,84-111
# 26.3 fill 语义
.gradle/loom-cache/.../minecraft-clientOnly-*-26.3-sources.jar → net/minecraft/client/gui/GuiGraphicsExtractor.java:190-208,238
# 贴图占位
src/main/resources/assets/pingforit/textures/ping.png → 70 B, PNG 1x1
```
