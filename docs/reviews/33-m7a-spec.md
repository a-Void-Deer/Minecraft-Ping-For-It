# 33 · M7a-A1 规格冻结：正式化方块描边（只读分析）

> 分析：1号 repo-analyst｜2026-10-31｜**只读源码，未运行游戏**
> 分支：`exp/vanilla-lines-m7a` @ `9645bbc`；用户已批准 M7a（去实验化），**OIT 暂不考虑**。
> 上游：`Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1`（`UP:`）；当前：`src/client/java/nx/pingwheel/common/`（`CUR:`）

## 0. 结论先行

1. **上游的方块描边是「winner 投影 + 逐帧快照 + 世界渲染之后单独 pass」**：`BlockOutlineState.prepare` 每帧从 `visibleWinnersInDimension` 重选（`UP:client/outline/BlockOutlineState.java:60-125`），`hasOutlines()` 为空时连 batch 都不建（`:133-135`），pass 在 `LevelRenderer.renderLevel` 返回后执行（`UP:mixin/GameRendererMixin.java:44-66`）。
2. **上游对「画不画」的判定共 13 条**（§1 表 T1–T13）：winner / `block`|`entity_block` / BlockKey 全等 / 维度 / `isLoadedAndCurrent` / registryId / 空形状跳过 / 无满方块兜底 / model 覆盖跳过 / 整体短路 / external；颜色 = `0xFF000000 | pingType.outlineColor()`。
3. **上游没有「描边开关」配置项**：`ClientConfig` 中与描边相关的字段只影响 **model 路线**（whitelist/blacklist/entityBlockRenderMode）或 **捕获射线**（markFluids/passThroughTransparentBlocks）——M7a **不新增开关**（§2 矩阵）。
4. **当前 E2 实现已经包含 M7a 所需的全部语义**（winner/维度/加载/registryId/CollisionContext/空形状/颜色/3.75px/失败隔离），需要去掉的只是**实验外壳**（`vanilla-outline.txt` 与 mode 1–3 分支、`OutlineExperiment`、每 5 秒 `submitted=` 日志、`(experiment branch)` 字样）。
5. **两个必须在 M7a 决定并落地的工程点**：① 自定义管线创建失败时**回退策略**（建议：禁用 pass + WARN 一次，而不是退回内置线——内置线不穿墙会改变上游效果，违反保真铁律）；② `SubmitNodeCollectionMixin` 是 `require=0`，注入失败会**静默**把线框丢进 `solid` 桶，需加「首次观察到 routing 的确认日志/一次性 WARN」以便现场可诊断。

## 1. 上游触发条件清单（VoxelShape 线框）

| # | 条件 | 上游规则 | 证据 |
|---|---|---|---|
| T1 | winner 投影 | 只取 `visibleWinnersInDimension(dimensionId)`（每 `TargetKey` 一个赢家），**不是**全部存活记录 | `BlockOutlineState.java:66-69`；`BlockOutlineSelection.java:61-74` |
| T2 | targetType 白名单 | 仅 `block` 与 `entity_block` 参与方块渲染 | `BlockModelOutlineRoute.java:54-58`；`BlockOutlineSelection.java:85` |
| T3 | BlockKey 全等 | `TargetKey.BlockKey` 与 `Target.BlockTarget` 的维度/x/y/z/`blockRegistryId` **逐项相等** | `BlockOutlineSelection.java:78-85` |
| T4 | 维度过滤 | spec 的 `blockKey.dimensionId()` 必须等于 `level.dimension().location().toString()` | `BlockOutlineRenderer.java:94-96` |
| T5 | 已加载 | `BlockPresentationSubjectValidation.isLoadedAndCurrent(level, subject)` 不通过则跳过 | `BlockOutlineRenderer.java:101-103` |
| T6 | registryId 复核 | 当前 subject 的 block registry id 必须与期望一致（方块被替换则丢弃该 outline） | `BlockOutlineRenderer.java:32-33`（类注释「compared exactly to its expected id」）；`BlockOutlineSelection.java:84`（key 里带 `blockRegistryId`） |
| T7 | 空形状 | `shape == null \|\| shape.isEmpty()` → 跳过；**没有满方块兜底** | `BlockOutlineRenderer.java:114-118`；类注释 `:36`「no full-cube fallback exists」 |
| T8 | CollisionContext | 用相机实体构造：`cameraEntity == null ? CollisionContext.empty() : CollisionContext.of(cameraEntity)`，再 `getShape(level, pos, ctx)` | `BlockOutlineRenderer.java:85-87`、`:114` |
| T9 | 颜色 | `0xFF000000 \| pingType.outlineColor()`，找不到 ping 类型回落 `0xFFFFFF`；`BlockOutlineSpec` 强制不透明 | `BlockOutlineSelection.java:89-91`、`:132-134`；`BlockOutlineSpec.java:20-23,47` |
| T10 | model 覆盖跳过 | 该 subject 的 model-outline 成功则**只**抑制 VoxelShape（不是整个 pass） | `BlockOutlineRenderer.java:107-112`；`BlockOutlineState.java:142-166` |
| T11 | 整体短路 | `hasOutlines()` 为空 → 不建/不 flush batch；全部被 model 覆盖 → 直接 return | `BlockOutlineState.java:133-135`；`CommonClient.java:509-518` |
| T12 | 外部方块（Sable） | `ExternalBlockOutlineSpec` 单独选择，模型路线失败仍回退 VoxelShape | `BlockOutlineSelection.java:108-140`；`ExternalBlockOutlineSpec.java:10-38` |
| T13 | 距离/可见性 | **没有**额外距离过滤；只要 marker 仍存活且是 winner 就画（寿命由 marker store 控制） | `BlockOutlineState.java:60-75`（无距离字段） |

**渲染规格**（M7a 目标，已由 E2 落地）：`LINES` + `rendertype_lines` + **3.75px** + `TRANSLUCENT` + **`NO_DEPTH_TEST`** + `NO_CULL` + `COLOR_WRITE` + `VIEW_OFFSET_Z_LAYERING` + `MAIN_TARGET`，单例、显式 flush（`UP:BlockOutlineRenderType.java:53,82-93,103-112,123-137,144-161`；当前实现见 `CUR:client/outline/PingOutlineRenderType.java:80-100`）。

## 2. config 影响矩阵（字段 → 是否影响 M7a 线框）

| 字段 | 默认 | 实际作用 | 影响 M7a？ | 本阶段 | 证据 |
|---|---|---|---|---|---|
| `blockDisplayWhitelist` | `*:*` | 决定 block **走 native model glow 还是回退 VoxelShape**；白名单命中 → model 路线 | ❌（M7a 无 model 路线，全部走 VoxelShape） | **延后到 M7c** | `UP:config/ClientConfig.java:52`；`BlockDisplayPolicy.java:60-71`；`BlockModelOutlineRoute.java:64-77` |
| `blockShapeBlacklist` | 空 | 黑名单命中 → **不使用 model glow**（仍画 VoxelShape，不是关闭描边） | ❌ | 延后到 M7c | `ClientConfig.java:54`；`BlockDisplayPolicy.java:70` |
| `entityBlockRenderMode` | `ALL` | `entity_block` 选择哪些几何来源（ALL/COMPATIBLE/VOXEL_SHAPE_ONLY） | ❌（只被模型渲染器读取） | 延后到 M7b/M7c | `ClientConfig.java:35,157-166`；`VirtualBlockDisplayRenderer.java:301,417` |
| `markFluids` | `false` | 捕获时是否允许把流体作为目标（射线） | ❌（影响**目标选择**，不影响描边） | 不在 M7a | `ClientConfig.java:32`；`ClientPingRuntime.java:707` |
| `passThroughTransparentBlocks` | `false` | 捕获射线是否穿过透明方块 | ❌（同上） | 不在 M7a | `ClientConfig.java:30`；`ClientPingRuntime.java:705` |
| 其他（pingSize/itemIcon/UI…） | — | HUD/轮盘/文本 | ❌ | — | `ClientConfig.java:26-50` |

**结论：上游没有 `outlineEnabled` 之类的描边开关；M7a 不新增配置项。** 若未来要「关描边」，属于上游没有的新行为，需用户单独批准。

## 3. 当前实现盘点（`CUR:client/outline/`）

### 3.1 `VanillaLinesBlockOutline`（271 行，E2 版）

| 区块 | 内容 | M7a 处置 |
|---|---|---|
| `:71-77` 常量 | `LINE_WIDTH=3.75f`、`DEFAULT_MODE=1`、`MAX_MODE=4` | **保留 3.75**；删 mode 常量 |
| `:93-127` `register()` + mode switch | mode 1/2/3 内置线、mode 4 自定义管线、失败回退 mode 2 并 WARN | **重写**为单一正式路径（见 §3.4） |
| `:129-151` `readMode()` | 读 `<gameDir>/vanilla-outline.txt` | **删除**（实验开关） |
| `:154-239` `onCollectSubmits` | winner（`:194`）、维度（`:195`）、`isLoaded`（`:201`）、air（`:207`）、registryId（`:211`）、CollisionContext（`:188-190`、`:215`）、空形状（`:217`）、颜色（`:221-223`）、`submitShapeOutline(...)`（`:232`） | **语义全部保留**（与 §1 T1–T9 一一对应） |
| `:241-248` 失败隔离 | 首次异常 → `disabled=true` + WARN 一次 | **保留**；建议改成 per-frame 早退 + join/leave 重置（B6） |
| `:251-269` `logBudgeted` | 每 5 秒/变化时 INFO `submitted=` | **删除**；改为初始化 INFO 一次 + 状态变化 DEBUG（对齐上游 `BlockOutlineLogger` 的 transition 日志，`UP:BlockOutlineState.java:120-124`） |
| 类注释 `:35-67` | 描述 E1/E2 实验、mode 表 | **重写**为正式类注释 |

### 3.2 `PingOutlineRenderType`（115 行）

- 身份判定 `isCustom`（`:64-66`）→ 供 mixin 用，**保留**。
- 管线构建（`:80-93`）：`core/rendertype_lines` ×2、`withCull(false)`、`POSITION_COLOR_NORMAL_LINE_WIDTH`、`LINES`、`ColorTargetState(TRANSLUCENT)`、`withDepthStencilState(Optional.empty())` → **保留**（逐项对齐上游 §1 渲染规格）。
- `RenderSetup`（`:97-100`）：`VIEW_OFFSET_Z_LAYERING` + `OIT_LINES_TRANSLUCENT` → **保留**；OIT 虽不在本轮验收范围，但设置不应删（删了会改变未来 OIT 行为）。
- 失败隔离（`:106-112`）：`failed=true` 后永久返回 null + WARN 一次 → **保留**，但调用方回退策略要改（§3.4）。

### 3.3 `SubmitNodeCollectionMixin`（65 行）

- `@Inject(method="submitShapeOutline", at=HEAD, cancellable, `**require=0**`)`（`:44`）。
- 仅拦截 `PingOutlineRenderType.isCustom(renderType)`（`:54`），按 `afterTerrainFlag` 投到 `afterTerrain`/`solid`（`:61`），并 `ci.cancel()`（`:62`）。
- **约束**：`require=0` 意味着目标方法改名/变签名时**静默不生效**——线框会退回 vanilla 的 solid 桶，视觉上可能被水/玻璃盖住。M7a 需加一次性确认（§4 风险 R2）。

### 3.4 注册点与配置读取

- `CUR:fabric/FabricClient.java:99` `OutlineExperiment.registerIfEnabled()` → **M7a 删除**；`:101` `VanillaLinesBlockOutline.register()` → 保留（改名后仍在此注册）。
- 配置读取惯例：`ClientConfig.HANDLER.getConfig()`（`ClientPingRuntime.java:194-196,247,701`、`MarkerHudRenderer.java:145`、`WheelHudRenderer.java:178`）——M7a 不含配置项，因此**不需要**读 config。

## 4. M7a 规格（必做 / 可延后）

### 4.1 必做项

| # | 项 | 说明 | 验收 |
|---|---|---|---|
| A1 | **去实验化** | 删 `vanilla-outline.txt`/`readMode()`/mode 1–3 分支/`MAX_MODE`/`CUSTOM_MODE`/`(experiment branch)` 字样；删 `OutlineExperiment` 与 `FabricClient:99` | 全库无 `vanilla-outline` / `outline-experiment` / `(experiment branch)` 残留 |
| A2 | **正式命名** | `VanillaLinesBlockOutline` → `BlockOutlineRenderer`（或 `PingBlockOutlineRenderer`）；`PingOutlineRenderType` 保留 | 命名与上游一致、无 `VanillaLines`/`Experiment` |
| A3 | **自定义管线默认生效** | 无条件 `PingOutlineRenderType.create()`；成功即用；**失败策略见 A4** | `runClient` 日志出现且仅出现一次 `ping outline pipeline registered` |
| A4 | **失败回退策略（需 Lead 拍板）** | 候选：① **禁用 pass + WARN 一次**（保持「宁可不画，不改变效果」）；② 退回内置线（= 不穿墙，改变效果，**不建议**） | 用 root/无 mixin 环境模拟失败，确认无 crash、日志一次 |
| A5 | **日志收敛** | 初始化 INFO 一次（pipeline + pass 注册）；运行期只在状态变化 DEBUG（可选，默认关）；**删除每 5 秒 `submitted=`** | 正常会话日志 ≤ 2 条 pingforit 描边行 |
| A6 | **保留全部上游过滤** | winner / targetType / BlockKey / 维度 / `isLoaded` / registryId / `CollisionContext` / 空形状 / 颜色 / 3.75px | 逐条对照 §1 T1–T9 复审 |
| A7 | **routing 确认信号** | mixin 首次拦截时置位；pass 连续 N 帧使用自定义类型但从未被拦截 → WARN 一次「routing mixin 未生效」 | 人为把 `require=0` 方法名改错，确认能报警 |

### 4.2 可延后项（M7b/M7c 或后续）

| 项 | 归属 | 依据 |
|---|---|---|
| model 覆盖跳过（T10） | M7c（`BlockModelOutlineState`/`BlockPresentation*`） | `BlockOutlineRenderer.java:107-112` |
| external block / Sable（T12） | M7c（`ExternalBlockOutlineSpec`） | `BlockOutlineSelection.java:108-140` |
| `entity_block` 模型/原生发光路线 | M7b/M7c（`BlockModelOutlineRoute`） | `BlockModelOutlineRoute.java:69-81` |
| 白名单/黑名单/entityBlockRenderMode | M7c（`BlockDisplayPolicy`） | `ClientConfig.java:52-54,35` |
| 每帧分配优化（winners map / registry id 字符串） | 性能任务（可后置） | 3号 `27-review-m7a-fixes.md:87-88` |
| OIT 行为验收 | 后续（用户明确暂不考虑） | 32 号报告 §5 |

## 5. 当前实现 vs 上游差异清单

| # | 项 | 上游 | 当前（E2） | 处置 |
|---|---|---|---|---|
| D1 | 触发过滤 | 8 条（§1） | **已全部具备**（`:194-223`） | **保留** |
| D2 | 颜色 | `0xFF000000\|outlineColor`，回落白 | 同（`:221-223`） | **保留** |
| D3 | 线宽 | 3.75px | `LINE_WIDTH=3.75f`（`:71-72`） | **保留** |
| D4 | 渲染状态 | 自定义 RenderType（穿墙/无剔除/半透明/COLOR_WRITE） | `PingOutlineRenderType` 对齐（`:80-100`）；**COLOR_WRITE 由 26.3 pipeline 决定，需复审确认** | 保留 + 复核 |
| D5 | 调用时机 | 世界渲染之后（`GameRendererMixin`） | `LevelRenderEvents.COLLECT_SUBMITS` + `afterTerrain` 桶 | **保留**（26.3 等价相位） |
| D6 | 状态缓存 | `BlockOutlineState` 逐帧快照 + `hasOutlines()` 短路 | 每帧现场遍历 winners，无缓存 | M7a 可先保留现状；优化延后 |
| D7 | 日志 | transition DEBUG（次数） | 每 5s INFO `submitted=` | **删除/收敛**（A5） |
| D8 | 失败隔离 | 无（上游假定可用） | `disabled` 永久关闭 + WARN 一次 | 保留；join/leave 重置延后 |
| D9 | 实验开关 | 无 | `vanilla-outline.txt` + 4 个 mode | **删除**（A1） |
| D10 | 命名 | `BlockOutlineRenderType`/`BlockOutlineRenderer` | `PingOutlineRenderType`（可留）/`VanillaLinesBlockOutline`（改） | 改名（A2） |

## 6. 验收标准

| 类别 | 标准 |
|---|---|
| 编译 | `tools/compile-check` main/client **0 error** |
| 启动 | `runClient` 能进世界；日志**一次** `ping outline pipeline registered`；无 `Mixin apply failed` |
| 视觉（需用户确认） | ① 方块线框 **穿墙可见**；② 线框压在**水/染色玻璃之上**；③ 水/岩浆等**无形状方块不画整块**；④ 同一方块连点**只一条**（winner）；⑤ 颜色与 ping 类型一致；⑥ 线宽观感与上游 3.75px 一致 |
| 行为 | 换维度不残留；区块卸载不画；方块被替换后不画（registryId 不匹配）；`Stopping!` 正常 |
| 回归 | 无 `vanilla-outline.txt` / `outline-experiment.txt` 也能正常工作；HUD/轮盘不受影响；无每帧新日志 |
| 失败路径 | 管线创建失败时无 crash、只 WARN 一次、pass 不画（或按 A4 决策） |

## 7. 风险

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | **routing mixin 静默失效**（`require=0`） | 线框进 solid 桶 → 被水/玻璃盖住，看似「不穿墙/相位错」 | A7 确认信号 + 实机水下/玻璃后复测 |
| R2 | 自定义管线创建失败（mixin 未应用/版本变化） | 无描边或退化为内置线（改变效果） | A4 决策 + A7 报警 |
| R3 | `COLOR_WRITE` 等状态在 26.3 pipeline 下不可直接表达 | 与上游有细微差异 | M7a 复审时逐项核对；必要时登记为平台强制差异 |
| R4 | 每帧遍历/字符串分配 | 标记多时 GC 抖动 | 可延后（§4.2） |
| R5 | `disabled` 永久性 | 一次异常后整会话无描边 | 保留现状；join/leave 重置延后 |

## 8. 证据索引

| 主题 | 位置 |
|---|---|
| 上游状态/选择 | `UP:client/outline/BlockOutlineState.java:60-166`；`BlockOutlineSelection.java:61-140` |
| 上游渲染/过滤 | `UP:client/outline/BlockOutlineRenderer.java:30-36,84-130` |
| 上游路由/策略 | `UP:client/outline/BlockModelOutlineRoute.java:54-117`；`BlockDisplayPolicy.java:30-96` |
| 上游配置字段 | `UP:config/ClientConfig.java:26-54,157-166` |
| 上游渲染规格 | `UP:client/outline/BlockOutlineRenderType.java:53,82-93,103-112,123-137,144-161` |
| 当前实现 | `CUR:client/outline/VanillaLinesBlockOutline.java:71-77,93-127,129-151,154-239,241-269` |
| 当前管线/路由 | `CUR:client/outline/PingOutlineRenderType.java:48-114`；`CUR:mixin/SubmitNodeCollectionMixin.java:19-63` |
| 注册点 | `CUR:fabric/FabricClient.java:99,101` |
| 配置读取惯例 | `CUR:common/client/ClientPingRuntime.java:194-196,701`；`MarkerHudRenderer.java:145` |
| 前序评审 | `docs/reviews/26-m7a-upstream-fidelity-and-conflicts.md`、`31-review-e2-pipeline-routing.md`、`32-log-analysis-2026-10-30-mode4.md` |
