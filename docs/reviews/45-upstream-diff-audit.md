# 45 · 上游源码 vs 当前代码 全量对账（只读）

> 分析：1号 repo-analyst｜2026-10-31｜**只读 `src/` 与 `Mod_Project/`**
> 方法：`git diff --no-index --numstat` 逐文件比对 `src/main|client/java/<rel>` ↔ `common/src/main/java/<rel>`（回退 `fabric/`），计数 = 增+删行。
> 分支：`exp/vanilla-lines-m7a`；仓库内**未留下任何临时脚本**。

## 1. 一句话结论

**没有发现 F 类（未解释漂移）。** 72 个 DIFF 文件全部可归入 A–E；其中 41 个是 26.3 平台改名/API 等价替换、7 个结构重写、7 个 Lombok 移除、7 个集成桩、10 个有登记的增强/恢复。**另附 6 条「近 F」观察**（已在 docs 登记或自注释，但依据可再加固），见 §3。

> 计数复核：本报告重算 **SAME=146 / DIFF=72 / NOUP=23**，与 Lead 基线一致；但**单文件差异行数**与 Lead 的数值不同（例：`CommonClient` 本报告 483、Lead 1137；`ClientConfig` 391 vs 883）。差异可能来自统计口径/时点，**不影响分类**。

## 2. 统计表

| 类 | 数量 | 文件 |
|---|---|---|
| **A 平台强制**（26.3 改名/删除导致的等价替换） | **41** | network/ 15 包（IPacket、Marker*×6、PingLocation C2S/S2C、RateLimitPolicy、ServerConfig Request/Update/Snapshot、SyncDurationPolicy、UpdateChannel）<br>name/ ×4（ClientTargetNameDecoder、ClientTargetNameResolver、MinecraftTargetNameResolver、TargetNameJsonCodec）<br>math/ ×4（EntityLocalGeometryRegistry、EntitySelectionBlacklist、NativeLocalShapeRaycaster、Raycast）<br>interaction/ ×2（MinecraftEntityTargetAdapter、MinecraftTargetSnapshotFactory）<br>marker/MinecraftAuthoritativeTargetValidator、client/marker/MarkerOverlayState<br>client/ ×4（MinecraftClientTargetValidator、MinecraftLocalErrorSink、SelectedLocaleTranslationKeyCache、WheelMouseCapture）<br>platform/IPlatformContextService、fabric/platform/PlatformContextServiceImpl<br>resource/ResourceConstants、command/ClientCommandBuilder、mixin/MinecraftMixin、core/ServerCore、screen/OptionUtils<br>client/outline/BlockDisplayWhitelistEntry、BlockTagLookup<br>fabric/FabricMain |
| **B 结构重写**（26.3 原语消失，按新 API 重写） | **7** | CommonClient、FabricClient、math/MathUtils、screen/SettingsScreen、screen/SettingsTitleWidget、client/outline/EntityOutlineState、client/outline/EntityOutlineSelection |
| **C Lombok 移除**（D7） | **7** | config/ConfigHandler、config/ServerConfig、config/ClientConfig、resource/LanguageUtils、core/GameContext、render/WorldRenderContext、client/marker/MarkerView |
| **D 集成桩**（M9） | **7** | integration/DistantHorizonsIntegration、integration/FTBTeamsWrapper、integration/TeamContextHandler、integration/VoiceChatIntegration、integration/VoiceChatWrapper、integration/sable/client/SableClientProvider、integration/sable/server/SableExternalBlockServerProvider |
| **E 有意增强/登记恢复** | **10** | interaction/state/PingInteractionStateMachine、interaction/state/PingInteractionLogger、client/ClientPingActionDispatcher、mixin/KeyMappingMixin、util/InputUtils、client/ClientPingRuntime、platform/IPlatformClientEventService、fabric/platform/PlatformClientEventServiceImpl、client/outline/BlockDisplayPolicy、client/outline/BlockDisplayWhitelist |
| **F 未解释漂移** | **0** | — |

### A 类依据（举例，均「改名即可编译」）

| 替换 | 证据 |
|---|---|
| `ResourceLocation` → `Identifier`；`fromNamespaceAndPath` 不变 | `network/*`、`name/*`、`math/*` 等 41 文件 |
| `dimension().location()` → `dimension().identifier()` | `MarkerOverlayState`、`ClientPingRuntime`、`ServerCore` 等 |
| `getGameProfile().getName()` → `name()` | `ServerCore`、`MinecraftTargetNameResolver` |
| `displayClientMessage(…,false)` → `sendOverlayMessage(…)`（actionbar 语义不变） | `MinecraftLocalErrorSink` |
| `serverLevel()` → `level()`；`hasPermissions(3)` → `permissions().hasPermission(Permissions.COMMANDS_ADMIN)` | `ServerCore`、`MinecraftAuthoritativeTargetValidator` |
| `PayloadTypeRegistry.playS2C/playC2S` → `clientboundPlay/serverboundPlay` | `FabricMain` |
| `Component.Serializer` → `ComponentSerialization.CODEC` + `RegistryOps` | `ClientTargetNameDecoder`、`TargetNameJsonCodec` |
| `Game.screen` → `Game.gui.screen()`；`Game.cameraEntity` → `getCameraEntity()`；`GameRenderer.getMainCamera()` → `mainCamera()` | `WheelMouseCapture`、`ClientPingRuntime`、`MathUtils` |
| `EntityType` → `EntityTypes`；`EnderDragonPart` 包路径 | `MarkerView`、`MinecraftEntityTargetAdapter` |
| `InputConstants.Type.KEYSYM/GLFW 常量` → `KEYBOARD/MOUSE` + SDL 常量（含 `UNKNOWN` 代替 `-1`，AGENTS §7-3/§7-11） | `InputUtils` |
| `xmap(a,b)` → `xmap(a,b,true)`（26.3 重载） | `OptionUtils` |
| `setScreen` → `setScreenAndShow` | `MinecraftMixin`、`ClientCommandBuilder` |

## 3. F 类清单（**0 条**）与 6 条「近 F」观察

**F = 0。** 所有语义差异都能找到 A–E 归属或已有文档登记。以下 6 条是审核中**最接近 F** 的项，列出以便复核（均建议补一条登记、无需改代码）：

| # | 文件:行号 | 上游写法 | 当前写法 | 是否改变上游可见行为 | 依据/建议 |
|---|---|---|---|---|---|
| N1 | `client/outline/EntityOutlineSelection.java:35-36,81-83` | 上游 `EntityOutlineSelection` **不按 targetType 过滤**（只认 `TargetKey.EntityKey` + `Target.EntityTarget`） | 仅接受 `entity` / `dropped_item` | **理论上会**：未来若新增 entity 类 targetType，上游会画、当前不会 | 已在 `docs/reviews/36-review-m7b-entity-outline.md` S4-1 登记；当前 catalog 下等价。**建议在 M9/新增类型时同步白名单** |
| N2 | `client/outline/EntityOutlineState.java:26,116-125` | 上游 `EntityOutlineState` 持 `EntityOutlineSpec` 快照 + 无诊断 | 仅 `locator→color` map + 60 帧 hook 诊断 WARN | 否（诊断不改渲染；spec 的 pingType 未用于渲染） | 已在 `35-m7b-spec.md`/`36` 登记；**建议在 24 号偏差清单补一行「简化快照」** |
| N3 | `client/ClientPingRuntime.java:1281-1296` | 上游无该函数 | 新增 `WheelSelectionDiagnostics`（含 `mouseHandler`/window 尺寸空值防御） | 否（仅 INFO 日志字段） | M6.5 任务产物（`10`/`11` 号评审）；**建议在 M6.5 段补 `WheelSelectionDiagnostics` 行** |
| N4 | `fabric/platform/PlatformClientEventServiceImpl.java:29-35` | 上游无 render-frame 事件（1.21.1 用 `WorldRenderEvents`） | `LevelExtractionEvents.END_EXTRACTION` 注册逐帧回调（F1 也不停） | 否（26.3 必要适配；S1 问题记录） | 注释已说明 S1；**建议引用到 `33-m7a-spec.md` 或 M4 记录** |
| N5 | `util/InputUtils.java:38-41` + `mixin/KeyMappingMixin.java` | 上游默认键 `-1`、无越界守卫 | `UNKNOWN.getValue()`（0）+ 越界键守卫 | 否（仅在非法键值下生效，防闪退） | `AGENTS §7-11` 已登记（2026-10-30 崩溃根因） |
| N6 | `client/outline/BlockDisplayPolicy.java:14` + `BlockDisplayWhitelist.java:19-20` | 上游完整 policy/whitelist | 从 10/26 反编译构建**恢复**（M3b 占位符曾丢逻辑） | 否（恢复=向上游收敛）；只影响配置校验严格度（与上游一致） | `docs/reviews/16-decompiled-diff.md:45-46` 已登记「完全收敛」 |

## 4. 23 个 NOUP 文件分类

| 归属 | 数量 | 文件 | 依据 |
|---|---|---|---|
| **M3b 搬迁/占位的替代品**（本地新写） | 2 | `client/hud/MarkerHudRenderer`、`client/hud/WheelHudRenderer` | `docs/reviews/06-m3b-review.md`；M4/M6 实机记录 |
| **M6.5 新增** | 3 | `client/hud/MarkerLabelRenderer`、`interaction/state/WheelSelectionDiagnostics`、`client/ClientTeamContextHelper` | M6.5 记录（AGENTS §9 M6.5 / 11 号评审）；`ClientTeamContextHelper` 因源集拆分（review 16） |
| **M7a/M7b/M7c 新增（26.3 渲染新写）** | 8 | `outline/BlockOutlinePass`、`outline/PingOutlineRenderType`、`outline/OutlineRoutingState`、`mixin/RenderTypeAccessor`、`mixin/RenderPipelinesAccessor`、`mixin/SubmitNodeCollectionMixin`、`outline/BlockModelGlowPass`、`outline/EntityOutlineDiagnostics` | `33`/`35`/`38`/`40` 号规格 + `34`/`36`/`39` 复审 |
| **M7b mixin 新增** | 2 | `mixin/EntityRendererMixin`、`mixin/LevelExtractorMixin` | `35-m7b-spec.md`、`36` 复审 |
| **M8b 新增** | 2 | `client/resource/PingTextureState`、`client/resource/PingTextureReloadListener` | `41-m8-spec.md`、`42` 复审 |
| **新平台适配层** | 4 | `platform/IClientPlatformContextService`、`fabric/platform/PlatformClientContextServiceImpl`、`log/ModLogFile`、`network/ProtocolLimits` | review 13（ModLogFile）、review 15/16（平台层）、PORTING-PROGRESS M2（ProtocolLimits） |
| **诊断/守护新增** | 1 | `client/diagnostics/ClientFrameWatchdog` | review 15/16（`ClientFrameWatchdog` 未回退记录） |
| **实验残留（建议删除）** | 1 | `render/spike/OutlineExperiment` | M7-prep E1/E3 实验桩；M7a 已完成、正式 pass 就位，**应在收尾时删除**（不影响编译） |

> NOUP 合计 23 与脚本输出一致：2+3+8+2+2+4+1+1 = 23。

## 5. 建议的后续动作（按优先级）

| # | 动作 | 理由 |
|---|---|---|
| 1 | **无需修 F**：本次对账未发现未解释漂移 | A–E 全部有依据 |
| 2 | 删除 `render/spike/OutlineExperiment`（M7-prep 实验桩） | 正式 pass 已替代；避免误注册/误读 |
| 3 | 补 6 条「近 F」的文档登记（N1–N6，其中 N3/N4 新增到 M6.5/M7 记录） | 让「有依据」可检索，避免下次对账重复排查 |
| 4 | 把 `EntityOutlineSelection` 的 targetType 白名单与 `EntityOutlineState` 简化 snapshots 登记到 `docs/reviews/24-fidelity-constraint-audit.md` | 属与上游行为差异（虽当前等价） |
| 5 | 可选：把本对账脚本化（`tools/upstream-diff.sh`，输出三类计数） | 后续阶段每次收口可复跑；但本次按约束不留脚本 |


## 附录：72 个 DIFF 文件的分类归属（按类合并）

| 类 | 文件 |
|---|---|
| A | network/IPacket, MarkerCreate*, MarkerCreated*, MarkerRejected*, MarkerRemove*, MarkerRemoved*, MarkerWinnerChanged*, PingLocationC2S/S2C, RateLimitPolicy, ServerConfigRequest/Update/Snapshot, SyncDurationPolicy, UpdateChannel |
| A | name/ClientTargetNameDecoder, ClientTargetNameResolver, MinecraftTargetNameResolver, TargetNameJsonCodec |
| A | math/EntityLocalGeometryRegistry, EntitySelectionBlacklist, NativeLocalShapeRaycaster, Raycast |
| A | interaction/MinecraftEntityTargetAdapter, MinecraftTargetSnapshotFactory |
| A | marker/MinecraftAuthoritativeTargetValidator, client/marker/MarkerOverlayState |
| A | client/MinecraftClientTargetValidator, MinecraftLocalErrorSink, SelectedLocaleTranslationKeyCache, WheelMouseCapture |
| A | platform/IPlatformContextService, fabric/platform/PlatformContextServiceImpl, resource/ResourceConstants, command/ClientCommandBuilder, mixin/MinecraftMixin, core/ServerCore, screen/OptionUtils |
| A | outline/BlockDisplayWhitelistEntry, outline/BlockTagLookup, fabric/FabricMain |
| B | CommonClient, fabric/FabricClient, math/MathUtils, screen/SettingsScreen, screen/SettingsTitleWidget, outline/EntityOutlineState, outline/EntityOutlineSelection |
| C | config/ConfigHandler, config/ServerConfig, config/ClientConfig, resource/LanguageUtils, core/GameContext, render/WorldRenderContext, client/marker/MarkerView |
| D | integration/DistantHorizonsIntegration, FTBTeamsWrapper, TeamContextHandler, VoiceChatIntegration, VoiceChatWrapper, sable/client/SableClientProvider, sable/server/SableExternalBlockServerProvider |
| E | interaction/state/PingInteractionStateMachine, PingInteractionLogger, client/ClientPingActionDispatcher, mixin/KeyMappingMixin, util/InputUtils, client/ClientPingRuntime, platform/IPlatformClientEventService, fabric/platform/PlatformClientEventServiceImpl, outline/BlockDisplayPolicy, outline/BlockDisplayWhitelist |

> 计数：A 41 / B 7 / C 7 / D 7 / E 10 / F 0 = 72。

## 6. 复核命令（可复现，只读）

```bash
# 对单个文件
git diff --no-index --numstat -- \
  Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1/common/src/main/java/nx/pingwheel/common/CommonClient.java \
  src/client/java/nx/pingwheel/common/CommonClient.java
```

> 备注：本次未对 `deferred/` 做对账（Lead 已确认 8 个 screen 文件 diff=0，其余为 M3b 快照）。
