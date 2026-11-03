# 38 · M7c-A1 规格：模型光晕（只读分析）

> 分析：1号 repo-analyst｜2026-10-31｜**只读源码 + javap 反汇编，未运行游戏**
> 分支：`exp/vanilla-lines-m7a` @ `ad26dcd`（M7a 线框 + M7b 实体描边已完成并实机通过）；用户已批准 M7c。
> 上游：`Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1`（`UP:`）；当前：`src/…`（`CUR:`）；26.3：`D:\Gradle\caches\fabric-loom\26.3\minecraft-client-only.jar`（named jar，javap）。

## 0. 结论先行（**先回答核心问题**）

**上游默认配置下，普通方块 ping 的视觉 = 模型光晕（native glow，虚拟 `BlockDisplay` 渲染出的原版发光轮廓），不是 VoxelShape 线框。** 判定链：

1. `ClientConfig` 默认 `blockDisplayWhitelist = ["*:*"]`、`blockShapeBlacklist = []`（`UP:config/ClientConfig.java:52,54`）。
2. `BlockDisplayPolicy.shouldUseNativeGlow("block", state)` = `whitelist.matchesOrdinaryBlock(state)` **且** `!blacklist.matches(state)`；`matchesOrdinaryBlock` 要求 **无 BlockEntity + `RenderShape.MODEL` + 白名单命中**（`UP:client/outline/BlockDisplayPolicy.java:60-71`；`BlockDisplayWhitelist.java:151-158`）。默认 `*:*` 命中所有已注册方块（`BlockDisplayWhitelistEntry.java:62-68`），黑名单为空 → **普通方块默认 `nativeGlowMatches = true`**。
3. `BlockModelOutlineRoute.route("block", true)` → **`BLOCK_DISPLAY`**（`BlockModelOutlineRoute.java:76-78`）；`route("entity_block", true)` → `ENTITY_BLOCK`（`:72-74`）；其它（白名单未命中/黑名单命中/`RenderShape` 非 MODEL/有 BlockEntity）→ **`VOXEL`**（`:80`）。
4. `VirtualBlockDisplayRenderer` 对 `BLOCK_DISPLAY` 路线：**创建一个从不加入世界的合成 `Display.BlockDisplay`**，经 accessor 写入 BlockState、`tick()`、再用原版 `EntityRenderDispatcher.render(...)` 渲染到 `OutlineOnlyBufferSource`（把模型 RenderType 解析成 `RenderType.outline` 并**强制 marker 色**）→ 原版 outline buffer → 原版后处理合成发光（`UP:client/outline/VirtualBlockDisplayRenderer.java:690-839`；`OutlineOnlyBufferSource.java:14-70`）。
5. `CommonClient.renderModelOutlines`（模型 pass）先跑，只有 **RENDERED**（有顶点）的 subject 记录进 `BlockModelOutlineState`；随后 `renderBlockOutlines`（VoxelShape pass）对**未被模型覆盖**的 subject 补画线框（`UP:CommonClient.java:374-388,480-531`；`BlockOutlineState.java:142-166`）。

### 三类目标的默认视觉

| 目标 | 默认视觉 | 判定 | 证据 |
|---|---|---|---|
| **普通方块**（无 BE、`RenderShape.MODEL`） | **模型光晕**（发光轮廓，穿墙） | whitelist `*:*` → `BLOCK_DISPLAY` | `BlockDisplayPolicy.java:64-70`；`BlockModelOutlineRoute.java:76-78` |
| **entity_block**（箱子/告示牌等，默认 `entityBlockRenderMode=ALL`） | **真实 BlockEntity/烘焙模型的光晕**（非线框）；失败才回退 VoxelShape | `ENTITY_BLOCK` 路线，`BlockEntityRenderer`/baked model 通过 `EntityBlockGeometry*` 写入 outline buffer | `ClientConfig.java:35`；`BlockModelOutlineRoute.java:72-74`；`VirtualBlockDisplayRenderer.java:284-320` |
| **黑名单方块**（仅当用户配置了 `blockShapeBlacklist`；默认空） | **VoxelShape 线框**（回到 M7a 那套） | `shouldUseNativeGlow=false` → `VOXEL` | `BlockDisplayPolicy.java:70`；`BlockModelOutlineRoute.java:80` |

> ⚠️ **视觉变化预警**：M7c（C1）落地后，**默认配置下普通方块会从 M7a 的线框变成模型光晕**——这是向上游默认行为靠拢，但属于用户可见的明显变化，**需要用户确认**（见 §4）。

## 1. 26.3 机制映射表（javap 核实）

| 上游机制 | 26.3 等价物 | 证据（javap -p -c） | 结论 |
|---|---|---|---|
| 合成 `Display.BlockDisplay` + 原版实体渲染器 → outline buffer | **`SubmitNodeCollector.submitMovingBlock(PoseStack, MovingBlockRenderState, int outlineColor)`** | `SubmitNodeCollection.submitMovingBlock` 字节码：先按 `BlockStateModelSet.get(state).hasMaterialFlag(1)` 决定 solid/translucent；`83: iload_3 / ifeq 118`，outlineColor **非 0** 时再构造 `MovingBlockFeatureRenderer$Submit` 投进 **`outline` phase**；Submit record 字段含 `outlineColor`/`forceTranslucent` | ✅ **首选路线**（官方等价管线，自带模型解析/光照/偏移语义） |
| 模型逐 part 提交（低层） | `submitBlockModel(PoseStack, RenderType, List<BlockStateModelPart>, int[] tint, int light, int overlay, int outlineColor)` | `SubmitNodeCollection.submitBlockModel` 字节码：`67: iload 7 / ifeq 114`，outlineColor 非 0 → `getOutlineRenderType(...)` → `outline` phase | ✅ 可用；需自己选 part/tint/light，**仅在 moving-block 路线不够时使用** |
| `OutlineOnlyBufferSource` 强制 marker 色 | `outlineColor` 参数本身即颜色（引擎负责按 outline 材质渲染） | 同上 + `EntityRenderer`/`LivingEntityRenderer` 已验证 `outlineColor` 走 `submitModel` 最后 int | 等价 |
| `requestOutlineEffect()` gating | 26.3 `LevelRenderState.shouldShowEntityOutlines` = `!panoramic && player.hasPlayer`，普通游戏恒 true；`LevelRenderer.executeOutline` 自动执行 | `LevelExtractor` 字节码 `1082-1085` | 无需探测 |
| 模型成功（有顶点）才抑制 VoxelShape | **26.3 提交无返回值**，不能事后判定 | `submitMovingBlock`/`submitBlockModel` 均 `void` | ⚠️ 必须**事前判定**（见 §2） |
| `entity_block` 真实 BER 光晕 | `BlockEntityRenderDispatcher.submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)` **没有 outlineColor 参数** | `BlockEntityRenderer.submit` 签名；`BlockEntityRenderState` 只有 `blockPos/blockEntityType/lightCoords/...`，无 outlineColor | ❌ **C1 不可直接复用**，需单独设计（C2） |
| 白名单/黑名单/entityBlockRenderMode | 当前 port **已编译**：`CUR:client/outline/BlockDisplayPolicy.java` 等；`ClientConfig.getBlockDisplayPolicy()` | `CUR:src/client/java/nx/pingwheel/common/config/ClientConfig.java:42-48,160-175,288` | ✅ 可直接取当前 policy |

### `MovingBlockRenderState` 必填字段（javap 实测）

| 字段 | 来源 | 说明 |
|---|---|---|
| `blockPos` / `randomSeedPos` | 目标方块坐标 | 模型随机种子与位置 |
| `blockState` | `level.getBlockState(pos)` | |
| `biome` (`Holder<Biome>`) | `level.getBiome(pos)` | 生物群系染色 |
| `cardinalLighting` | `level.cardinalLighting()`（`ClientLevel` 已有该 public 方法） | 方向光 |
| `lightEngine` (`LevelLightEngine`) | `level.getChunkSource().getLightEngine()`（`ClientChunkCache.getLightEngine()` 已验证；`Level#getChunkSource` 未在 javap 输出匹配到，**待 2号 编译确认**） | 光照 |

## 2. M7c 分阶段建议

### C1（最小闭环，建议先做）：普通方块模型光晕 + 抑制 VoxelShape

| 项 | 内容 |
|---|---|
| 判定 | `ClientConfig.getBlockDisplayPolicy().shouldUseNativeGlow(targetTypeId, state)`；仅 `targetTypeId="block"` 且命中 → 走光晕；否则保持 M7a 线框 |
| 提交 | 每帧对每个 winner block 构造 `MovingBlockRenderState`（字段见上表）→ `collector.submitMovingBlock(pose, state, argbColor)`（color 非 0） |
| 抑制 | **事前抑制**：判定为 `BLOCK_DISPLAY` 的 spec 不再进 M7a 的 `BlockOutlinePass` 线框提交 |
| 回退 | 白名单未命中/黑名单命中 → 走 M7a 线框；`entity_block` → **暂保持线框**（等 C2） |
| 验收 | 默认配置普通方块出现**发光轮廓**（穿墙、跟随模型形状而非包围盒）；黑名单方块（临时配置）仍线框；无崩溃、无每帧日志 |

> 注：`RenderShape.ENTITYBLOCK_ANIMATED`（箱子/告示牌等）**不满足** `matchesOrdinaryBlock` 的 `MODEL` 门槛 → 默认走 `entity_block`/VOXEL 路径，不会误用 moving-block 渲染。

### C2：entity_block 真实 BlockEntity 几何光晕

- 26.3 `BlockEntityRenderDispatcher.submit(...)` **没有 outlineColor 入口** → 需要：① 用 mixin/包装 `SubmitNodeCollector` 把 BER 提交改投 outline phase；或 ② 对 `MODEL` 形状的 BE 用 `submitMovingBlock`（**会丢失动画状态，不满足 1:1，需用户批准**）；或 ③ 明确延后，`entity_block` 继续线框并在 `24-fidelity` 登记偏差。
- 建议：C1 后先与用户确认「是否必须 entity_block 也发光」；若必须，走 ①（新增 mixin）并单独复审。

### C3：白名单/黑名单/`entityBlockRenderMode` 细节与 fallback 语义

- 把「模型失败 → VoxelShape 兜底」在 26.3 侧重新定义：由于提交无返回值，建议对 `BLOCK_DISPLAY` **信任模型可用性**（`RenderShape.MODEL` + 已注册模型），失败时宁可无描边也不双画；在 `24-fidelity` 登记该策略差异。
- `entityBlockRenderMode` 仅在 C2 完成后才有意义（ALL/COMPATIBLE/VOXEL_SHAPE_ONLY）。

## 3. 推荐实现方案（C1）

| # | 文件/改动 | 说明 |
|---|---|---|
| 1 | 新增 `client/outline/BlockModelGlowPass.java` | 每帧遍历 winner block → policy 判定 → 构造 `MovingBlockRenderState` → `submitMovingBlock`；异常一次性 WARN + 禁用本会话 |
| 2 | 修改 `BlockOutlinePass` | 跳过「已判定为光晕」的 spec（事前抑制），其余照旧画线框 |
| 3 | 帧准备 | 复用 `MarkerOverlayState`/`LevelExtractionEvents` 已有 winner 数据；无需新 mixin（`MovingBlockRenderState` 全 public，`submitMovingBlock` 是公共接口） |
| 4 | 日志 | 注册一处 INFO（首次提交成功 count>0），失败一次性 WARN；无 per-frame |
| 5 | 配置 | 复用 `ClientConfig.getBlockDisplayPolicy()`；**不新增开关**（上游没有） |

## 4. 视觉变化预警（需用户确认）

> 若上游默认确实是模型光晕（本报告已核实为真），则 **M7c C1 会把普通方块的 ping 视觉从 M7a 的线框改为模型光晕**。这是「向默认上游行为对齐」的**可见变化**，与保真铁律一致（M7a 线框本来就是上游的 fallback 形态），但需要用户明确确认后再提交实机验证。

## 5. 风险

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 不能判定模型提交成功（无返回值） | 抑制线框后若模型缺失 → 该方块无任何描边 | 事前判定 `RenderShape.MODEL` + 已编译 policy；接受并登记；失败路径日志一次 |
| R2 | 每 marker 每帧构造 `MovingBlockRenderState` + 提交 | 标记多时 CPU/矩阵分配 | winner 通常个位数；可后续缓存 state（同方块复用） |
| R3 | 发光后处理兼容（Fabulous/OIT） | 轮廓可能受图形模式影响 | 26.3 outline phase 由原版统一处理；实机在 fancy/fast 各测一次 |
| R4 | 与 M7a/M7b 叠加 | 同一方块既线框又光晕（若抑制失效） | C1 必须在 M7a pass 内显式排除光晕 spec |
| R5 | `entity_block` 无光晕（C2 前） | 箱子/告示牌仍是线框 | 明确分阶段并在 24 号登记；C2 决策前不承诺 |
| R6 | `lightEngine` 字段获取路径未编译验证 | `submitMovingBlock` 可能渲染异常/无光 | 2号 编译确认 `level.getChunkSource().getLightEngine()`；否则用 `ClientChunkCache` 直取 |

## 6. 证据索引

| 主题 | 位置 |
|---|---|
| 默认配置 | `UP:config/ClientConfig.java:35,52,54`；`BlockDisplayWhitelist.java:46,151-158` |
| 判定链 | `UP:client/outline/BlockDisplayPolicy.java:30-96`；`BlockModelOutlineRoute.java:54-117` |
| 上游模型光晕实现 | `UP:client/outline/VirtualBlockDisplayRenderer.java:690-839`；`OutlineOnlyBufferSource.java:14-70` |
| 上游 pass 顺序/抑制 | `UP:CommonClient.java:374-388,480-531`；`BlockOutlineState.java:142-166` |
| 26.3 `submitMovingBlock` | `SubmitNodeCollection.submitMovingBlock` 字节码（outlineColor 分支 83-118）；`MovingBlockFeatureRenderer$Submit` 字段 |
| 26.3 `submitBlockModel` | `SubmitNodeCollection.submitBlockModel` 字节码（67-114） |
| 26.3 BE 无 outlineColor | `BlockEntityRenderer.submit` 签名；`BlockEntityRenderState` 字段 |
| 26.3 gating | `LevelExtractor` 字节码 1082-1085 |
| 当前可用件 | `CUR:src/client/java/nx/pingwheel/common/config/ClientConfig.java:42-48,160-175,288`；`CUR:client/outline/BlockDisplayPolicy.java` 等 |
