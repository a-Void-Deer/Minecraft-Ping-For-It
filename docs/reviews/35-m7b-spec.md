# 35 · M7b-A1 规格：实体描边（只读分析）

> 分析：1号 repo-analyst｜2026-10-31｜**只读源码 + javap 反汇编，未运行游戏**
> 分支：`exp/vanilla-lines-m7a` @ `8bc7afb`（干净）；用户已批准 M7b（OIT 暂不考虑）。
> 上游：`Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1`（`UP:`）；当前：`src/…`（`CUR:`）；26.3 依据：`D:\Gradle\caches\fabric-loom\26.3\minecraft-client-only.jar`（named jar，javap 反汇编）。

## 0. 结论先行（6 条）

1. **上游实体描边不是自绘轮廓，而是「借用原版发光描边」**：重定向 `Minecraft.shouldEntityAppearGlowing`（让实体进入原版 outline 通道）+ 重定向 `Entity.getTeamColor()`（把 outline 颜色换成 ping 色）；由原版 `OutlineBufferSource.endOutlineBatch()` 统一 flush 与后处理（`UP:mixin/LevelRendererMixin.java:160-190`）。因此**天然穿墙**、天然复用原版发光风格。
2. **26.3 里这条通道仍然存在，只是入口变成数据字段**：`EntityRenderState.outlineColor`（public int，`NO_OUTLINE`）由 `EntityRenderer.extractRenderState` 写入（原版逻辑 = glowing ? `ARGB.opaque(entity.getTeamColor())` : 0），再由各渲染器传进 `SubmitNodeCollector.submitModel(..., int outlineColor)`；非 0 时进入 **`outline` phase**（`SubmitNodeCollection.submitModel` 字节码 `100: iload 9 / ifeq 148`）。
3. **26.3 的 gating 不需要额外处理**：`LevelRenderState.shouldShowEntityOutlines` 由 `LevelExtractor` 计算，逻辑是 **`!camera.isPanoramicMode() && playerRenderState.hasPlayer`**（字节码 `1082-1085`）——普通游戏内恒 `true`；`LevelRenderer.executeOutline` / `blitEntityOutline` 据此工作。上游需要的 `requestOutlineEffect()` 反射探测在 26.3 **不需要**。
4. **M7b 的最小闭环只需要两件事**：① 每帧把 winner 实体 marker 解析成 `locator → 颜色` 快照；② 在 `EntityRenderer.extractRenderState` **TAIL** 把 `state.outlineColor` 覆盖成 ping 色（原版写入的是队伍色，TAIL 覆盖即等于上游的 `getTeamColor` 重定向）。
5. **Fabric 没有「逐实体提取」事件**：`LevelExtractionEvents` 只有 `AFTER_BLOCK_OUTLINE_EXTRACTION` 与 `END_EXTRACTION`（`LevelExtractionEvents.java`），而 `END_EXTRACTION` 时 `EntityRenderState` **没有 UUID/实体引用**（字段只有 `entityType/x/y/z/outlineColor…`，javap `EntityRenderState`）→ **必须 mixin `EntityRenderer.extractRenderState`**（它有 `entity` 形参可解析 locator）。
6. **风险集中点**：`getOutlineRenderType(RenderType)` 可能对部分 RenderType 返回 `null`（字节码 `112-114` 判空），这类实体会**静默无描边**；需要实机确认覆盖范围（生物/玩家/掉落物分别验证）。

## 1. 上游实体描边行为清单

| # | 项 | 规则 | 证据 |
|---|---|---|---|
| E1 | 选择来源 | 只从 `store.visibleWinnersInDimension(dim)` 取；仅 `TargetKey.EntityKey` 且 `Target.EntityTarget` 的维度/locator 与 key 一致 | `UP:client/outline/EntityOutlineSelection.java:61-76` |
| E2 | 去重 | 同一 locator 只保留**最大 markerId** 的那条（迭代按 markerId 升序，后写覆盖） | `EntityOutlineSelection.java:83-85`（注释 `:31-34`） |
| E3 | 颜色 | `0xFF000000 \| pingType.outlineColor()`，未知类型回落**不透明白** | `EntityOutlineSelection.java:79-81` |
| E4 | spec 内容 | `(markerId, locator, pingTypeId, argbColor)`，颜色强制不透明 | `EntityOutlineSpec.java:22-44` |
| E5 | 身份解析 | `EntityLocator`（UUID 或 runtime id）；`EntityOutlineLocatorResolver` 把 `EnderDragonPart` 归一到父龙、把已失效 locator 安全丢弃 | `EntityOutlineLocatorResolver.java:16-54` |
| E6 | 每帧快照 | `prepare(store, dimensionId)`：重选 → 与上次相等则跳过 → 否则整体替换；`clear()` 在无 runtime/离开世界时清空 | `EntityOutlineState.java:59-95,153-161` |
| E7 | 查询 | `shouldOutline(locator/UUID)`、`colorFor(locator/UUID)`；空快照 `hasOutlines()==false` | `EntityOutlineState.java:103-133` |
| E8 | 渲染方式 | **不画几何**：重定向 `shouldEntityAppearGlowing` 返回 true，重定向 `Entity.getTeamColor()` 返回 ping 色；原版 outline buffer + 后处理负责全部视觉 | `UP:mixin/LevelRendererMixin.java:160-190` |
| E9 | 与方块线框 | **两条独立通道**：方块用自定义 `BLOCK_OUTLINE` 批（`BlockOutlineRenderer`），实体走原版 outline buffer；同帧可共存 | `LevelRendererMixin.java:89-107`；M7a `BlockOutlinePass` |
| E10 | 无 outline 时 | 实体无描边（不退化到方块线框）；方块线框照常 | `LevelRendererMixin.java:91-92,166-174` |
| E11 | 与 vanilla glowing | 未命中 ping 时返回原值；命中时才覆盖颜色——**vanilla 发光/队伍色被保留** | `LevelRendererMixin.java:162-164,183-189` |
| E12 | 后处理 | 复用原版 `entityEffect`（`PostChain`）；模型描边若已处理，则重定向 ordinal-0 `process` 只跑一次 | `LevelRendererMixin.java:117-147` |
| E13 | 自定义几何 seam | `EntityOutlineSource/Runner/Registry` 只服务可选集成（Flywheel/Create 剪影），**不是 M7b 必需** | `EntityOutlineSource.java:9-25`；`EntityOutlineRunner.java:10-27`；`EntityOutlineSourceRegistry.java:14-31` |

## 2. 26.3 机制映射表（全部经 javap 核实）

| 上游机制 | 26.3 等价物 | 证据（javap -p -c） | 结论 |
|---|---|---|---|
| `OutlineBufferSource` 写轮廓 | `EntityRenderState.outlineColor` → `SubmitNodeCollector.submitModel(..., int)` → `SubmitNodeCollection.outline` phase | `EntityRenderState` 字段 `outlineColor`/`NO_OUTLINE`；`SubmitNodeCollection.submitModel` 字节码 `100: iload 9`、`ifeq 148`，非 0 时 `getOutlineRenderType(...)` → `outline.submit(...)` | 等价 |
| `shouldEntityAppearGlowing` 重定向 | 不需要：直接写 `state.outlineColor`（原版在 `extractRenderState` 末尾写入） | `EntityRenderer.extractRenderState` 字节码 `766-791`：`shouldEntityAppearGlowing(entity) ? ARGB.opaque(entity.getTeamColor()) : 0` | 改为「覆盖字段」 |
| `Entity.getTeamColor()` 重定向 | 同上，`state.outlineColor = pingColor` | `LivingEntityRenderer` 字节码 `205-214` 把 `state.outlineColor` 传给 `submitModel` 最后一个 int | 等价 |
| `endOutlineBatch()` + 后处理 | `LevelRenderer.currentFrameRendersEntityOutline` + `executeOutline(RenderPass)` + `blitEntityOutline()`；`PreparedFrame.hasAnyOutline()` | `LevelRenderer` 方法表：`executeOutline`、`blitEntityOutline`、字段 `entityOutlineTarget`、`currentFrameRendersEntityOutline` | 等价 |
| `shouldShowEntityOutlines()` gating | `LevelRenderState.shouldShowEntityOutlines`（`LevelExtractor` 设置） | `LevelExtractor` 字节码 `1082-1085`：`!camera.isPanoramicMode() && playerRenderState.hasPlayer` | **普通游戏恒 true，无需强制** |
| `requestOutlineEffect()` 反射探测 | 不需要 | 同上（outline pass 由玩家存在自动开启） | 可省 |
| 每帧 reset | 提取阶段每帧重建 `EntityRenderState` | `EntityRenderState` 由 `extractRenderState` 每帧填充 | **天然每帧重置** |
| 逐实体提取事件 | **不存在** | `LevelExtractionEvents`：仅 `AFTER_BLOCK_OUTLINE_EXTRACTION`、`END_EXTRACTION` | **必须 mixin** |

## 3. M7b 必做项（最小闭环）

| # | 项 | 说明 | 验收 |
|---|---|---|---|
| M1 | `EntityOutlineSelection` | 从 `visibleWinnersInDimension` 选 `Target.EntityTarget`；按 locator 去重（保留最大 markerId）；颜色 `0xFF000000\|outlineColor`，回落白 | 与上游 E1–E4 逐条一致 |
| M2 | `EntityOutlineState` | 每帧 `prepare(store, dim)` → `Map<EntityLocator,Integer>`；`shouldOutline/colorFor/clear`；离开世界清空 | 状态切换有 debug 计数，无 per-frame 日志 |
| M3 | `EntityRendererMixin` | `@Inject(method="extractRenderState", at=TAIL)`：`entity` → `MinecraftEntityTargetAdapter.locatorFor(canonicalEntity(entity))`（`CUR:src/main/.../MinecraftEntityTargetAdapter.java:50,64`）→ 命中则 `state.outlineColor = color` | 生物/玩家/掉落物各测一个 |
| M4 | 帧准备时机 | 新增 `LevelExtractorMixin`（`@Inject(method="extract", at=HEAD)`）或 `LevelRenderEvents.START_MAIN`（**待验证是否早于 extraction**）调 `EntityOutlineState.prepare(...)` | 快照在提取前就绪 |
| M5 | 维度/身份校验 | mixin 内比较 `entity.level().dimension().identifier().toString()` 与 target 的 dimensionId；`EnderDragonPart` 用 canonical entity | 跨维度不误画 |
| M6 | 失败隔离 | mixin `require=0`；未生效时 `END_EXTRACTION` 打一次 WARN「entity outline mixin 未应用」；异常不外抛 | 人为改方法名可报警 |
| M7 | 日志收敛 | 注册 INFO 一次；状态变化 DEBUG；无 per-frame | 正常会话 ≤2 行 |

## 4. 可延后项

| 项 | 归属 | 证据/备注 |
|---|---|---|
| 与 M7c 模型描边的交互（`BlockModelOutlineState`/`requestOutlineEffect` 桥接） | M7c | 上游 `LevelRendererMixin.java:89-147` |
| 自定义几何 seam（`EntityOutlineSourceRegistry`/Flywheel 剪影） | 后续（M9/可选集成） | `EntityOutlineSourceRegistry.java:32-35`「no built-ins」 |
| 多人/专用服务器验证 | M10 | 上游行为与单人一致，但需实测 |
| 性能缓存（每帧 map/字符串分配） | 性能任务 | 同 M7a 后置项 |
| 玩家皮肤层/装备层的轮廓完整性 | 待验证 | `LivingEntityRenderer` 只把 outlineColor 传给主模型；layers 是否同色需实机 |

## 5. 推荐实现方案

1. **新增 3 个文件**（`client/outline/`）：`EntityOutlineSelection.java`、`EntityOutlineState.java`；`mixin/EntityRendererMixin.java`（+ 可选 `LevelExtractorMixin`）。
2. **hook 点**：`EntityRenderer.extractRenderState(T, S, float)` 的 **TAIL**（mixin 目标用擦除后描述符 `(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V`）。这是**唯一**同时拿到 `entity` 与 `state` 的公共点。
3. **帧准备**：优先 `LevelExtractorMixin` HEAD（与上游 `onRenderWorld` 的时机对应，在提取前）；若不愿再加 mixin，退而用 `ClientTickEvents.END_CLIENT_TICK`（20Hz，最多滞后 1 tick，需在 fidelity 审计里登记）。
4. **复用 M7a 经验**：`@Inject(require=0)` + 一次性 WARN 诊断（M7a `OutlineRoutingState` 同款）；日志一次性；异常隔离。
5. **不需要新 mixin 配置项**（walk `pingforit.mixins.json` client 列表追加即可），**不需要 AW**（`outlineColor` 是 public 字段）。

## 6. 验收标准

| 类别 | 标准 |
|---|---|
| 编译 | `tools/compile-check` main/client **0 error** |
| 视觉 | ① 被 ping 的生物/玩家出现**与 ping 类型同色**的发光轮廓；② **穿墙可见**；③ 同一实体多个 ping 只一条（winner）；④ 过期后轮廓消失（≤1 帧）；⑤ vanilla 发光实体仍保留原队伍色 |
| 行为 | 换维度不残留；离开世界清空；距离/遮挡不影响（原版 outline 语义）；不与 M7a 方块线框互相干扰 |
| 回归 | M7a 方块描边、HUD/轮盘不受影响；无每帧新日志 |
| 多人 | 只对**本客户端可见 winner** 的实体描边；他玩家视角不串色 |
| 失败路径 | mixin 未应用：无 crash + 一次 WARN；`getOutlineRenderType` 为 null 的实体静默无描边（记录覆盖范围） |

## 7. 风险

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | `getOutlineRenderType(base)` 对部分 RenderType 返回 null | 某些实体（如特殊渲染层）无描边 | 实机分类验证；必要时登记为限制 |
| R2 | `outlineColor` 只传给**主模型** | 装备/皮肤层可能不与主体同色 | 实机对比上游；必要时后续 mixin 补 layers（慎改保真） |
| R3 | 原版 outline 后处理开销 | 每帧目标分配/后处理 | gating 已由原版控制；只有命中实体才有 outline 几何 |
| R4 | 与 vanilla glowing 冲突 | 同实体既发光又被 ping | 上游同样覆盖颜色；按上游行为实现，不改判定 |
| R5 | 提取阶段 mixin 描述符随版本变化 | 静默失效 | `require=0` + 一次性 WARN + 实机日志检查 |
| R6 | 帧准备时机错误（准备晚于提取） | 轮廓延迟 1 帧或不出现 | 用 `LevelExtractorMixin` HEAD，实机确认无延迟 |

## 8. 证据索引

| 主题 | 位置 |
|---|---|
| 上游选择/状态 | `UP:client/outline/EntityOutlineSelection.java:54-89`；`EntityOutlineState.java:59-161` |
| 上游 spec/身份 | `UP:client/outline/EntityOutlineSpec.java:22-44`；`EntityOutlineLocatorResolver.java:16-54` |
| 上游注入点 | `UP:mixin/LevelRendererMixin.java:55-74,89-107,117-147,160-190` |
| 上游自定义 seam | `EntityOutlineSource.java:9-25`；`EntityOutlineRunner.java:10-27`；`EntityOutlineSourceRegistry.java:14-35` |
| 26.3 字段/写入 | `EntityRenderState.outlineColor/NO_OUTLINE`（javap）；`EntityRenderer.extractRenderState` 字节码 766-791 |
| 26.3 消费 | `LivingEntityRenderer` 字节码 205-214；`SubmitNodeCollection.submitModel` 字节码 100-148；`PreparedFrame.executeOutline` |
| 26.3 gating | `LevelExtractor` 字节码 1082-1085（`!panoramic && hasPlayer`）；`LevelRenderer.blitEntityOutline` |
| Fabric 事件缺口 | `fabric-rendering-v1-27.2.0`：`LevelExtractionEvents` 仅 2 个事件；`LevelExtractionContext` 无逐实体回调 |
| 当前可复用件 | `CUR:src/main/java/nx/pingwheel/common/interaction/MinecraftEntityTargetAdapter.java:50,64`；`CUR:client/marker/ClientMarkerStore.java:573`（visibleWinnersInDimension） |
| 前序评审 | `docs/reviews/33-m7a-spec.md`、`34-review-m7a-block-outline.md` |
