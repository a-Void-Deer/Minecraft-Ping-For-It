# 57 · 运行期钩子与管线冲突审计（render pipeline / mixin / HUD / 资源重载 / 关停）

> 审计人：pipeline-auditor（**只读**，除本文件外未修改任何代码）｜2026-11-03
> 对象：工作区 `26.3_Fabric` HEAD **e8790a5**、工作树干净（`git status --porcelain` 空）
> 方法：逐文件读源码 + **对 `D:/Gradle/caches/fabric-loom/26.3/minecraft-client-only.jar` 反查 `javap -p/-c/-v`** + Fabric API 27.2.0/3.1.0 源码 jar + 对 `run/mods` 四个 jar 做 mixin 配置/常量池扫描 + 复读 `run/logs/latest.log`（20:17–20:19）。
> 限制：**未运行 Gradle、未启动游戏**（沙箱禁止）。凡只能由实机判定的，一律标 **NEEDS-REAL-RUN**，不假装已验证。

---

## 0. 结论速览

| # | 审计项 | 裁决 | 一句话 |
| --- | --- | --- | --- |
| H1 | 自定义 `RenderPipeline` 注册 | **PASS** | `RenderPipelines.register(RenderPipeline)` 私有静态重载签名精确命中；实机日志 `:103` 已证注册成功 |
| H2 | 自定义 `RenderType` 创建 | **PASS** | `RenderType.create(String, RenderSetup)` 包私有静态精确命中；实机 `:104` 已证 pass 就位 |
| H3 | `afterTerrain` 分桶路由 | **RISK（P1）** | 该 phase **只在「改进透明度关闭」的经典通道执行**；本次能画是因为 DH 把 OIT 关了 |
| H4 | 8 个 mixin 目标类/方法存在性 | **PASS** | 8/8 在 26.3 jar 里逐个 `javap` 命中（含泛型擦除后的描述符） |
| H5 | mixin require 级别 | **RISK（P1）** | 5 处注入点/2 个 accessor 走默认 `require=1` → 任何签名变动=**启动崩溃**，不是软降级 |
| H6 | HUD 两层 id | **PASS** | 两个 namespaced id 唯一；`addLast` 语义、顺序、F1 联动均已核实 |
| H7 | 资源重载 v1 监听器 | **PASS** | `ResourceLoader.registerReloadListener(Identifier, PreparableReloadListener)` 签名正确；prepare/apply 线程契约满足 |
| H8 | KeyMapping ×5 + Category | **PASS** | 5 个默认键全部合法（无 `-1`）；Category `pingforit:name` 只注册一次 |
| H9 | 客户端命令注册 | **PASS** | 走 `ClientCommandRegistrationCallback`，无重复 |
| H10 | 4 个 entrypoint | **PASS** | main/client/modmenu/voicechat 四个类都存在且唯一 |
| H11 | 5 个 ServiceLoader SPI | **PASS** | 3 服务端（main）+2 客户端（client），FQCN 全部存在、无跨模块重复 |
| C1 | 与 DH / voicechat / modmenu 目标重叠 | **PASS** | 唯一共混入类是 `Minecraft`，**方法不重叠**；其余 7 个目标无人触碰 |
| C2 | 与 Fabric API 自身 mixin 重叠 | **PASS** | `LevelExtractor`/`SubmitNodeCollection` 双混入但注入点不同、均不 cancel |
| C3 | 重复注册（id/pipeline/listener/event） | **PASS** | 全部一次性 + 幂等闩锁；唯一"重复"是死代码 `OutlineExperiment`（无调用点） |
| S1 | 静态/ThreadLocal 持有 Level/Store | **RISK（P2）** | `GameContext.lastWorld`（静态 `ClientLevel`）断线后**永不清空** |
| S2 | mixin 静态初始化合并进 vanilla `<clinit>` | **RISK（P2）** | `BlockEntityRenderDispatcherMixin` 的 `PING_TYPES` 会在 vanilla 类初始化里跑 |
| S3 | 关停期是否会被我们的钩子碰到 | **PASS** | 6 条关停相关路径全部 null 检查/幂等，无抛出点 |
| S4 | 我们创建的线程 | **PASS** | **0** 个（全库无 `new Thread`/自建 executor） |
| L1 | `stmt pointer is closed` | **HOLD-HARMLESS** | 100% DH 自有线程 + DH 自有 sqlite，栈里 0 个我们的帧 |
| L2 | `end_of_frame` post effect | **HOLD-HARMLESS** | vanilla `GameRenderer` 请求 + vanilla `ShaderManager` 报警；**原版 jar 里根本没有这个 json** |

**总体：0 个 P0（没有构建/启动崩溃级问题），2 个 P1（均与渲染可见性/前向兼容有关），6 个 P2 清理项。本次实机运行的 0 条模组相关 WARN/ERROR 可以采信。**

---

## 1. 运行期钩子全量清单

### 1.1 客户端 mixin（`fabric/src/client/resources/pingforit.mixins.json`）

配置本身：`required: true`、`minVersion 0.8`、`package nx.pingwheel.common.mixin`、`compatibilityLevel JAVA_25`、`injectors.defaultRequire = 1`、**无 refmap**、`client` 数组 8 项、无 `mixins`/`server` 数组（服务端不加任何 mixin）。
> 无 refmap 在 **no-remap + mojmap** 下是正确的；但它同时意味着这 8 个 mixin 的引用是**直接写死 mojmap 名**，一旦换成需要 remap 的构建就会全灭（信息项，非缺陷）。

| # | mixin | 目标（jar 实测） | 注入点 / 时机 | require | 若目标或 id 错 |
| --- | --- | --- | --- | --- | --- |
| 1 | `KeyMappingMixin` | `KeyMapping.click(InputConstants$Key)` | `:23` `@Inject TAIL`；按键边沿当帧 | 默认 **1** | 启动即 `Mixin apply failed` → 崩溃 |
| 1 | 同上 | `KeyMapping.set(InputConstants$Key, boolean)` | `:28` `@Inject TAIL` | 默认 **1** | 同上 |
| 1 | 同上 | `KeyMapping.setAll()` 内 `InputConstants.isKeyDown(I)Z` | `:44` `@Redirect`；**javap 证实 `setAll` 里该调用只有一处（字节码偏移 55）** | 默认 **1** | 同上（且这处正是 `-1` 键位崩溃的护栏） |
| 1 | 同上 | `KeyMapping.releaseAll()` | `:62` `@Inject HEAD` | 默认 **1** | 同上 |
| 2 | `MinecraftMixin` | `Minecraft.setScreenAndShow(Screen)` | `:15` `@Inject HEAD`；屏幕切换边界 | 默认 **1** | 启动崩溃 |
| 3 | `RenderTypeAccessor` | `RenderType.create(String, RenderSetup)`（**包私有 static**） | `:17` `@Invoker`；首次 `PingOutlineRenderType.create()` 时调用 | accessor（缺则 apply 失败） | 启动崩溃（accessor 解析失败不认 `require`） |
| 4 | `RenderPipelinesAccessor` | `RenderPipelines.register(RenderPipeline)`（**private static**，另有 `register(OitPipelineSet)` 重载） | `:17` `@Invoker` | accessor | 启动崩溃；注：重载靠 accessor 签名消歧，`RenderPipeline` 版正确 |
| 5 | `SubmitNodeCollectionMixin` | `SubmitNodeCollection.submitShapeOutline(PoseStack, VoxelShape, RenderType, int, float, boolean)` | `:47` `@Inject HEAD, cancellable` | **0** | 静默失效 → 线框落回 solid 桶（仍可见），不崩 |
| 5 | 同上 | `SubmitNodeCollection.submitModel(...9 参)` 的 **slot 9** | `:83` `@ModifyVariable HEAD argsOnly index=9` | **0** | 静默失效 → entity_block 回到线框 |
| 6 | `BlockEntityRenderDispatcherMixin` | `BlockEntityRenderDispatcher.submit(S extends BlockEntityRenderState, PoseStack, SubmitNodeCollector, CameraRenderState)` | `:61` HEAD + `:93` RETURN | **0** | 静默失效 → 无 BER 光晕，一次 WARN |
| 7 | `EntityRendererMixin` | `EntityRenderer.extractRenderState(T extends Entity, S extends EntityRenderState, float)` | `:34` `@Inject TAIL` | **0** | 静默失效 → 实体描边丢失，一次 WARN |
| 8 | `LevelExtractorMixin` | `LevelExtractor.extract(DeltaTracker, Camera, float)` | `:31` `@Inject HEAD` | **0** | 静默失效 → 描边晚一帧或不出现，不抛进渲染循环 |

**javap 复核要点（逐条打过）**

- `submitModel` 的 slot 语义：`<S> void submitModel(Model, S, PoseStack, RenderType, int, int, int, UvMapping, int)` 是**实例方法**，slot0=`this`，最后一个 `int` 落在 **slot 9**；字节码偏移 100 处确实是 `iload 9 / ifeq 148`，且该值被传进 `ModelFeatureRenderer$Submit` 的 outline 通道 —— **`index=9` 的注释与实码一致**。
- `submitShapeOutline` 的路由前提为真：`ARGB.alpha(color)==255 → solid`（**忽略 afterTerrain**），否则 `afterTerrain ? afterTerrain : shapeOutlines`。我们恒用 `0xFF000000|rgb`，所以**必须**靠 mixin 才能进 afterTerrain。
- `@Shadow` 的两个字段 `solid`/`afterTerrain` 均为 `public final SimpleFeatureRenderPhase`，`SimpleFeatureRenderPhase.submit(SubmitNode)` 接受 `ShapeOutlineFeatureRenderer.Submit`（record，5 参构造 `(PoseStack$Pose, VoxelShape, RenderType, int, float)` 精确命中）。

### 1.2 自定义 RenderPipeline + RenderType

| 项 | 位置 | 内容 | 时机 | 失败模式 |
| --- | --- | --- | --- | --- |
| pipeline id | `PingOutlineRenderType.java:50,81` | `pingforit:pipeline/block_outline` | 客户端初始化（`BlockOutlinePass.register()` → `create()`） | 命名空间隔离，无撞车面 |
| RenderType 名 | `:51,102` | `ping_block_outline` | 同上，**单次** | `cached`/`failed` 双闩锁 → 绝无二次创建 |
| 管线规格 | `:80-93` | NO_DEPTH_TEST（`withDepthStencilState(Optional.empty())`）+ TRANSLUCENT + `withCull(false)` + LINES + `POSITION_COLOR_NORMAL_LINE_WIDTH` | — | 建不出来就 `failed=true`，全 session 只 WARN 一次 |
| 非管线维度 | `:97-100` | `LayeringTransform.VIEW_OFFSET_Z_LAYERING` + `RenderPipelines.OIT_LINES_TRANSLUCENT` | — | — |
| 注册调用 | `:95` | `RenderPipelinesAccessor.pingforit$register(pipeline)` | — | `RuntimeException|LinkageError|AssertionError` 全捕获（含 accessor 未织入时的 `AssertionError` 桩） |
| 分桶 | `SubmitNodeCollectionMixin:57` | `PingOutlineRenderType.isCustom(type)` **身份比较**，零副作用、不触发懒建 | 每次 shape outline 提交 | 不命中 → 走 vanilla 路由 |

实机证据：`run/logs/latest.log:103` `ping outline pipeline registered (depth test disabled, cull off, translucent)`、`:104` `block outline pass registered (custom pipeline, afterTerrain routing)` —— **这条路径不只是编译通过，是真跑通了**。

### 1.3 HUD / 资源重载 / 输入 / 命令 / 网络 / 声音

| 钩子 | 位置 | id / 名称 | 时机 | 失败模式 |
| --- | --- | --- | --- | --- |
| HUD 层 1 | `FabricClient.java:39,49-51` | `pingforit:marker-hud` | `onInitializeClient`（一次） | id 重名 → `HudElementRegistryImpl.validateUnique` 抛 `IllegalArgumentException` → 客户端初始化崩溃 |
| HUD 层 2 | `FabricClient.java:42,56-58` | `pingforit:toggle-notice-hud` | 同上，**晚于**层 1 注册 | 同上；顺序保证提示画在标记之上 |
| 资源重载 v1 | `FabricClient.java:36,111-112` + `PingTextureReloadListener.java:24` | `pingforit:reload-listener` | 客户端初始化（一次） | 用 v0 会卡加载条（本项目已踩过）；v1 契约：`prepare` 任意线程、`apply` 游戏线程，本类只在 `prepare` 读 `getResourceStack`、在 `apply` 写 `volatile boolean` ✅ |
| KeyMapping ×5 | `InputUtils.java:21,22-26,27-31,32-36,40` | 5 个 `nx.pingwheel.key.*` | `InputUtils` 类初始化（首次被 `CommonClient.onInit` 触碰） | 默认键越界（`-1`）→ `setAll` 时 `IndexOutOfBoundsException` 闪退（历史事故，见 `run/crash-reports/crash-2026-10-30_12.17.25-client.txt:17`）；现全部合法：`MOUSE_BUTTON_5`/`KEY_LSHIFT`/`KEY_LCONTROL`/`KEY_LALT`/`UNKNOWN(0)` |
| KeyMapping Category | `InputUtils.java:19-20` | `pingforit:name` | 同上，**只注册一次** | 重复 → `KeyMapping$Category.register` 抛 `IllegalArgumentException("Category '%s' is already registered.")`（javap 实证） |
| 客户端命令 | `FabricClient.java:117-125` | `/pingforit`（`ClientCommandBuilder`） | `ClientCommandRegistrationCallback` | 只注册一次；`sendError` 走 `LanguageUtils`，无异常面 |
| S2C 接收器 ×8 | `FabricClient.java:59-107` | 8 个 `PACKET_TYPE` | 客户端初始化 | 与 `FabricMain:45-58` 的 payload 注册配对；都在 `main` 初始化之后 |
| 声音事件 | `FabricClient.java:67` | `pingforit:ping` | 客户端初始化 | **仅客户端注册**（`environment:"*"` 不对称）；当前只有 `ClientPingRuntime:1038` 本地播放，服务端从不解析该 id，故无害 |
| 平台事件 ×4 | `PlatformClientEventServiceImpl.java:12,17,22,35` | tick / JOIN / DISCONNECT / `END_EXTRACTION` | 各一次 | 无重复注册点（全库唯一） |
| 声音/键位/SPI 入口 | `fabric.mod.json:13-16` | main/client/modmenu/voicechat | Loader | 类缺失 → entrypoint 解析失败 |

### 1.4 入口点与 ServiceLoader

- `fabric/src/main/templates/fabric.mod.json:11-23`：`environment:"*"`；4 个 entrypoint；mixins 对象形式且 `environment:"client"`；`depends` 5 条（`fabricloader>=0.20.1` / `minecraft ~26.3` / `java>=25` / `fabric-api *`）。
- 5 个 SPI：`fabric/src/main/resources/META-INF/services/` → `IPlatformContextService`→`PlatformContextServiceImpl`、`IPlatformNetworkService`→`PlatformNetworkServiceImpl`、`IPlatformServerEventService`→`PlatformServerEventServiceImpl`；`fabric/src/client/resources/META-INF/services/` → `IPlatformClientEventService`→`PlatformClientEventServiceImpl`、`IClientPlatformContextService`→`PlatformClientContextServiceImpl`。**一一对应、无重复、无服务端引用客户端类**。
- ⚠ 隐含初始化链：`Global.java:13` `MOD_VERSION = IPlatformContextService.INSTANCE.getSelfModVersion()` → **首次触碰 `Global` 就触发 ServiceLoader**。首次触碰点是 `PingOutlineRenderType` 的 `LOGGER.info`（客户端初始化期），此时 Fabric 类加载器完全可用，安全；但任何"在极早期 mixin <clinit> 里读 `Global`"的改动都会把 ServiceLoader 拖进 vanilla 类初始化 —— 目前没有这种情况。

---

## 2. 冲突分析

### 2.1 与 `run/mods` 里实际加载的 mod

本次运行 58 个 mod（`latest.log:3-61`），**没有 Sodium**（`latest.log:85` DH 的 Sodium mixin 因 `ClassNotFoundException: net/caffeinemc/mods/sodium/client/util/SodiumChunkSection` 被跳过）。

对四个 jar 做了**配置级 + 常量池级**扫描（`DistantHorizons-3.3.4`、`voicechat-fabric-2.6.27`、`modmenu-21.0.0`、`voicechatprofiles-1.0.1`，只看含 `mixin` 的 class）：**在我们的 8 个目标里，只有 `Minecraft` 被别的 mod 混入**。

| 目标类 | 我们 | 其它 mod | 冲突判定 |
| --- | --- | --- | --- |
| `Minecraft` | `setScreenAndShow(Screen)` | DH `MixinMinecraft` → `close()V`；voicechat `MinecraftMixin` → `disconnect(Screen,boolean,boolean)` + `handleKeybinds`；modmenu/voicechatprofiles → `MixinPauseScreen`/`MixinTitleScreen` | **无方法重叠**，全部并存 |
| `KeyMapping` | 4 点 | 无 | 无人触碰 |
| `RenderType` / `RenderPipelines` | 2 个 accessor | 无 | 无人触碰 |
| `SubmitNodeCollection` | 2 点 | 无 mod；**Fabric API 有自己的 `SubmitNodeCollectionMixin`** | 见 2.2 |
| `BlockEntityRenderDispatcher` | 2 点 | 无 | 无人触碰 |
| `EntityRenderer` | 1 点 | 无（voicechat 只混 `AvatarRendererMixin` 等子类） | 无人触碰 |
| `LevelExtractor` | 1 点 | 无 mod；**Fabric API 混入** | 见 2.2 |
| `LevelRenderer` | 不混入（用 `LevelRenderEvents`） | DH `MixinLevelRenderer` | 不共享注入点 |

> 附带结论：**M9-3 的 DH 集成是纯桩**（`common/.../integration/DistantHorizonsIntegration.java:29-35` 恒 `false`），所以除"共用 `Minecraft`/`LevelRenderer`"外，我们和 DH **没有任何代码级交互面**。

### 2.2 与 Fabric API 自身的 mixin（容易被忽略）

| 目标 | Fabric 的 mixin | 我们的 mixin | 判定 |
| --- | --- | --- | --- |
| `LevelExtractor` | `fabric/mixin/client/rendering/LevelExtractorMixin`：`extract` **RETURN**、`extractBlockOutline` RETURN、`allChanged` HEAD | `extract` **HEAD** | 注入点不同、都非 cancellable → 链式执行，顺序由 priority（均默认 1000）决定。**PASS** |
| `SubmitNodeCollection` | 同名 mixin，但**只加一个接口方法** `submitCustom(...)`，无注入器 | 2 个注入器 | 方法合并，互不干扰。**PASS** |

### 2.3 重复注册面

- HUD id：`pingforit:marker-hud` / `pingforit:toggle-notice-hud`，注册前 `validateUnique` 会遍历**全部**层（含 vanilla 24 个 root），撞名即抛。**当前唯一、且 namespaced。**
- pipeline id / RenderType 名 / 重载 listener id / 各事件：全部由 `registered`、`cached`、`failed`、`outlineTypeResolved` 之类闩锁保护，**只在客户端初始化执行一次**。
- 唯一的"重复注册库存"是 **`OutlineExperiment`（`render/spike/`）**：它自己会注册 `COLLECT_SUBMITS` 或 `BEFORE_GIZMOS`，但 `registerIfEnabled()`（`:103`）**全库 0 个调用点**（`docs/reviews/34`、`45` 已记录为待删残留），且 `run/` 下没有 `outline-experiment.txt`。→ 死代码，见 P2-3。

---

## 3. 关停（shutdown / disconnect）安全

### 3.1 静态与 ThreadLocal

| 持有物 | 位置 | 断线时 | 判定 |
| --- | --- | --- | --- |
| `CommonClient.Game` | `CommonClient.java:59` | 指向 `Minecraft` 单例（进程级对象） | 无害 |
| `GameContext.lastWorld` | `GameContext.java:22` | **从不清空**（`updateDimension()` 在 `Game.level==null` 时直接 return，`:52-54`） | **RISK（P2）**：静态持有最后一个 `ClientLevel`，退出世界后整棵 level/chunk/entity 图被续命 |
| `GameContext.RENDER_ENTITY_LOOKUP_CACHE` | `:23-49` | `clearRenderEntityLookupCache()` 从 `onLeaveServer` 和 `onRenderFrame`(level==null) 调用 | 已释放 |
| `EntityBlockGlowState` 的 5 个 ThreadLocal + `cachedStore` | `EntityBlockGlowState.java:63-75,107-109` | 只在 `resetFrame()`（`LevelExtractor.extract` HEAD）清 | **RISK（P2、轻微）**：退出到主菜单后不再有 extract，于是最后一次的快照（含 `ClientMarkerStore` 引用 + 一个 `HashSet<Long>`）留在渲染线程 ThreadLocal 里；下次进世界第一帧即被清 |
| `MarkerOverlayState.INSTANCE` / `ClientMarkerStore` | `MarkerOverlayState.java:69` / runtime 实例 | `onLeaveServer`→`runtime.close()`→`markerStore.clear()/MarkerOverlayState.clear()`（`ClientPingRuntime.java:433-441`、`CommonClient.java:108-127`） | 已释放 |
| `EntityOutlineState` 快照 | `EntityOutlineState.java:28` | `level==null` 时 `clear()` | 已释放 |
| `PingOutlineRenderType.cached` / `BlockOutlinePass.renderType` | `PingOutlineRenderType.java:53` / `BlockOutlinePass.java:78` | 持有 `RenderType`（GPU 管线描述符，非 world 数据） | 无害 |
| 自建线程 / executor | —— | 全库 `grep "new Thread|ThreadLocal|CompletableFuture|Executor"`：只有 `EntityBlockGlowState` 的 ThreadLocal 与 `RandomSource.createThreadLocalInstance()` | **0 个线程**，我们没有关停时需要 join 的东西 |

### 3.2 关停期会被调用到的注入点

| 路径 | 触发 | 是否会抛 |
| --- | --- | --- |
| `KeyMappingMixin.releaseAll` → `CommonClient.onInputReset` | vanilla 清键位（失焦/退出） | 否：`abortInteractionIfActive` 全程 null 检查 |
| `KeyMappingMixin.setAll` 的 `@Redirect` | 世界加载完成 | 否：越界键直接 `return false` 并一次性 WARN |
| `MinecraftMixin.setScreenAndShow` | 开/关菜单、退出 | 否：`onScreenChanged` 先判等再 abort |
| `LevelExtractorMixin.extract` | 每帧抽取 | 否：整体 `try/catch (RuntimeException|LinkageError)` 且不外抛 |
| `BlockEntityRenderDispatcherMixin.submit` | 每帧 BE 派发 | 否：颜色解析包在 `try/catch`，`restore()` 一定配对执行 |
| `EntityRendererMixin.extractRenderState` | 每帧实体 | 否：locator 解析 `try/catch` 吞掉 |
| `SubmitNodeCollectionMixin` | 每帧提交 | 否：只做身份比较与 `phase.submit` |

---

## 4. 两条日志的归因（决定性证据）

### 4.1 `java.sql.SQLException: stmt pointer is closed` → **HOLD-HARMLESS**

`latest.log:457-472`（20:19:49，`Stopping!` 前 1 秒，处于 **level shutdown** 阶段）：

```
[DH-Regen Queue [minecraft:overworld] Thread[0]/ERROR] (FullDataUpdatePropagatorV2)
Regen queueing exception: java.sql.SQLException: stmt pointer is closed
  at com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo.getChildPositionsToRegen(FullDataSourceV2Repo.java:568)
  at com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataUpdatePropagatorV2.queueRegeneration(...:601)
  ... ThreadPoolExecutor.runWorker ...
Caused by: java.sql.SQLException: stmt pointer is closed
  at dh_sqlite.core.SafeStmtPtr.ensureOpen(SafeStmtPtr.java:148)
  at dh_sqlite.jdbc3.JDBC3ResultSet.getInt(JDBC3ResultSet.java:331)
```

**证明**：
1. 线程名 `DH-Regen Queue [minecraft:overworld] Thread[0]` 是 DH 自建线程池（`:462-464` 的 `ThreadPoolExecutor` 栈帧），与渲染线程/客户端线程无关。
2. 栈里 12 个帧**全部**是 `com.seibel.distanthorizons.*` 与 `dh_sqlite.*`，**0 个 `nx.pingwheel` / `pingforit` 帧**。
3. 触发条件在 `dh_sqlite.core.SafeStmtPtr.ensureOpen` —— DH 自己关闭数据库（`:499` `AbstractDhRepo Closing all [3] databases`）后，它的 regen 队列仍在跑，属于 DH 内部关停顺序竞态。
4. 我们**没有任何数据库/sqlite/线程/异步 IO**（§3.1 扫描 0 线程），结构上不可能参与。

**判定：完全无关。** 该条在 `latest.log` 里出现 1 次，是可复现的 DH 关停噪声。

### 4.2 `Requested post effect does not exist: minecraft:end_of_frame` → **HOLD-HARMLESS**

`latest.log:255`（20:17:40，`Render thread/WARN (Minecraft)`）。**字节码级证据链**：

1. **报警者**：`net.minecraft.client.renderer.ShaderManager.isPostEffectValid(Identifier, Set)` 的常量池里有字面量 `"Requested post effect does not exist: {}"`，命中时 `LOGGER.warn(...)` 并 `return false`（javap 实证）。
2. **请求者**：`net.minecraft.client.renderer.GameRenderer` 有 `public static Identifier END_OF_FRAME_POST_EFFECT;`，在 `<clinit>` 里 `putstatic` 赋值 —— 即 `minecraft:end_of_frame` 是 **vanilla 自己写死的 id**。
3. **缺的是原版资源**：对整个 `minecraft-client-only.jar` / `minecraft-client.jar` / `minecraft-common.jar` 扫描，`assets/minecraft/post_effect/` 下只有 `spider/creeper/blur/invert/entity_outline` 等，**没有任何 `end_of_frame` 条目（0 命中）**。
4. **不是别的 mod 请求的**：对 DH jar 做同样的字面量扫描，`end_of_frame` **0 命中**；我们把整个 client jar 里唯一含该字面量的类也定位到了 `GameRenderer`。
5. **我们完全没有 post effect 面**：`grep post[_ ]?effect|PostEffect|PostChain` 在 `fabric/src` 只命中 1 处 —— `CommonClient.java:506` 的一句 **TODO 注释**（方法体是空的），`common/src` 0 命中；`assets/pingforit/` 不含 `post_effect/` 任何文件。
6. 时间线也不支持我们：我们所有管线/RT 注册在 `20:17:23`（`:103-104`），该警告在 `20:17:40` 进世界瞬间，且**之后 2 分钟内不再出现**（整份日志 1 次）。

**判定：完全无关，是 26.3 这个 jar 的原版资源缺口**（vanilla 请求一个 jar 里不存在的 post effect，只警告一次）。它和紧跟其后的 `LodRenderer / MinecraftClientWrapper` 三行都属于"进世界时的一次性初始化噪声"。

### 4.3 本次运行的整体信号质量（给 lead 的旁证）

- `grep pingforit` = **92 行，全部 INFO/DEBUG**，其中 `send ping` **28** 条 / `marker created` **28** 条（1:1，requestId/markerId 0–27 连续）；`block model glow active` 1 条（`:301`）；轮盘 `select`→`send` 的 pingType 逐对一致。
- 全日志 **WARN 6 条 / ERROR 4 条**，归属：Sodium-mixin 跳过(1)、PerfOS(1)、voicechat debug(1)、DH GC(1)、`end_of_frame`(1)、DH chunk cache(1)；ERROR：Mojang 服务(3) + DH sqlite(1)。**模组相关 0 条。**

---

## 5. 逐项裁决与排名修复清单

### 5.1 需要修的（按严重度）

#### P0 · 构建/启动崩溃 —— **无**

#### P1-1 · `afterTerrain` 只在「改进透明度关闭」时被绘制（可见性风险）

**证据链**：
- jar 全量扫描：`afterTerrain` 这个字段名只出现在 5 个类，而**唯一 `getfield` 它的方法**是 `FeatureRenderDispatcher$PreparedFrame.executeTranslucentAfterTerrain(RenderPass)`。
- 该方法的**唯一调用者**是 `LevelRenderer.executeClassicTransparency`。
- `LevelRenderer.lambda$addMainPass$0` 字节码：
  ```
  177: iload_2                 // oit?
  178: ifne 190                // OIT 就跳过
  187: invokevirtual executeClassicTransparency(...)     // ← 仅 !OIT
  ...
  234: iload_2
  235: ifeq 245                // !OIT 就跳过
  242: invokevirtual executeOit(...)                       // ← 仅 OIT
  248: invokevirtual executeOutline(...)                    // ← 无条件（两条路都跑）
  253: hasAnySeeThrough → 264 executeSeeThrough             // 条件
  267/269/277: executeAlwaysOnTop                            // 条件
  ```
- 本次运行 `latest.log:259` 明确：`(MinecraftClientWrapper) Disabling improved transparency...` —— **是 DH 把 OIT 关掉了**，所以我们才走经典通道、线框才画得出来。

**含义**：`block` 标记的主路径走 `executeOutline`（**OIT 无关，永远执行**）；但**线框回退**（`BlockOutlinePass:220` 的 `submitShapeOutline(..., afterTerrain=true)`）在「改进透明度开启」时会**整体不画**（不是变淡，是被丢进从不执行的分桶），且**不会报任何日志**（`OutlineRoutingState` 只报"mixin 没生效"，不报"分桶没人执行"）。

**现状**：不装 DH / 或用户在视频设置里打开 Improved Transparency 时，`entity_block` 的线框回退与所有"模型光晕被拒"的 `block` 标记会静默消失。**这是 P1，因为它静默且与 DH 是否存在强相关。**

**建议**（择一，均需实机验证）：
1. 关 OIT 时投 `afterTerrain`、开 OIT 时投 `alwaysOnTopGizmos`（后者在 `addMainPass` 里也是条件执行，必须连条件一起核）；或
2. 干脆统一投 `outline` 分桶（无条件执行，语义也最接近"描边"），验证在该 pass 的 RenderPass 下 depth/blend 是否仍等价于 NO_DEPTH_TEST；或
3. 若确认保真允许，登记为"已知限制：需关闭 Improved Transparency"，并**在 `diagnoseRouting` 旁边加一条一次性诊断**（读 `GameRenderer.useImprovedTransparency()`，为 true 时 WARN 一次），避免以后被当成"标记不显示"的玄学 bug。

**NEEDS-REAL-RUN**：三种改法都要在实机上比对 3.75px / 穿透墙体 / 半透明混合是否仍与上游 1:1。

#### P1-2 · 5 处注入点 + 2 个 accessor 走默认 `require=1`

`KeyMappingMixin`（4 点）与 `MinecraftMixin`（1 点）**没有显式 `require`**，因此吃 `pingforit.mixins.json:17` 的 `defaultRequire: 1`；两个 `@Invoker` accessor 在目标方法缺失时同样会在 apply 阶段抛错。也就是说：**26.3 的任何小版本如果改了 `setScreenAndShow`/`click`/`set`/`setAll`/`releaseAll`/`create`/`register` 中的任意一个名字或描述符，客户端会在启动阶段直接崩，而不是降级。**

- 对 `setAll` 的 `@Redirect` 来说，**硬失败是合理的**（它挡的是"越界键位闪退"，静默失效等于把旧崩溃还回去）。
- 但 `RenderTypeAccessor`/`RenderPipelinesAccessor` 的失败已经有 `AssertionError` 兜底（`PingOutlineRenderType:106` 显式捕获 `AssertionError`），这意味着**设计上本来就允许它们不织入**——只是 accessor 解析失败发生在更早的阶段，`require` 管不到它。
- 建议：①在 `pingforit.mixins.json` 旁加一句注释性文档或在 `AGENTS.md` §7 记一条"升级 26.3 小版本后第一件事是复跑这 7 个描述符"；②给 `MinecraftMixin` 明确写 `require = 1`（表达意图：屏幕切换边界是行为正确性的一部分），给 `KeyMappingMixin` 的 4 点分别标注哪些是"必须"、哪些可以退让。

#### P2 清单

| # | 项 | 位置 | 建议 |
| --- | --- | --- | --- |
| P2-1 | 静态持有 `ClientLevel` | `GameContext.java:22,51-58` | `onLeaveServer` 里把 `lastWorld` 置 null（`clearRenderEntityLookupCache` 旁边一行） |
| P2-2 | mixin 静态初始化跑进 vanilla `<clinit>` | `BlockEntityRenderDispatcherMixin.java:58-59`（`PING_TYPES = PingTypeCatalog.builtIn()`） | Mixin 会把它合并进 `BlockEntityRenderDispatcher.<clinit>`；一旦 `PingTypeCatalog` 将来引入任何依赖，异常会变成 vanilla 类的 `ExceptionInInitializerError`。建议改成 lazy holder（`static final class Holder { static final PingTypeCatalog VALUE = ...; }`）或首次使用时初始化 |
| P2-3 | 死代码 `OutlineExperiment` | `render/spike/OutlineExperiment.java:103`，全库 0 调用点 | 删除（`docs/reviews/34/45` 已两次建议），否则它内部那两处 `LevelRenderEvents.register` 是"随时可能被误启用"的重复注册源 |
| P2-4 | `BlockOutlinePass` 一次异常即整场禁用 | `BlockOutlinePass.java:230-238` | `catch` 覆盖了整个循环，任一 marker 的一次 `submitBlockModel` 抖动会永久关掉本 session 的所有描边；建议把 `try` 收窄到单个 marker 的 body，只有"结构性"失败才 latch |
| P2-5 | 重载监听器失败标志不重置 | `PingTextureReloadListener.java:26` | `failureLogged` 是 static 且永不复位；按 `CommonClient` 里 `hudFailureLogged` 的既有做法，在 JOIN 时重置更一致 |
| P2-6 | 声音只注册在客户端 | `FabricClient.java:67` | 当前无人从服务端解析该 id，**无需改**；但如果 M10 之后有服务端播放/转发的需求，必须挪到 common/主入口，否则专用服务端上该 id 不存在 |

### 5.2 明确判定为 PASS 且**不要动**的（防止后续误改）

- `PingOutlineRenderType` 的 `cached/failed` 双闩锁与 `catch (RuntimeException | LinkageError | AssertionError)`：`AssertionError` 是 accessor 未织入时的桩行为，**不要"清理"掉这个 catch**。
- `SubmitNodeCollectionMixin` 的 `@Shadow public final` 两字段、`require=0`：字段与描述符经 javap 双证，改任何一处都会从"软降级"变"硬崩溃"。
- `BlockEntityRenderDispatcherMixin` 的 begin/restore 配对（含 `color == null` 也 `begin`）：这是嵌套派发的正确性基础，别加 `if (color != null)` 的早退。
- `HudElementRegistry.addLast` ×2 的顺序（标记层先、提示层后）：`HudElementRegistryImpl.addLast` 是 **append 到 LAST(SUBTITLES) root 的 list 尾**，顺序即注册顺序。
- `ClientCommandBuilder`/8 个 S2C 接收器：与 `FabricMain` 的 payload 注册一一配对。

---

## 6. 需要改代码吗？

**结论：没有必须改的代码来"修 bug"；有 1 个应当改的产品级风险（P1-1），1 个应当登记的前向兼容风险（P1-2），6 个可择机清理的 P2。**

- 若要**立刻降低风险且改动最小**：只做 **P1-1 的诊断版**——在 `BlockOutlinePass.diagnoseRouting` 旁加一条"improved transparency 开启时 afterTerrain 不执行"的一次性 WARN（约 10 行，不改渲染行为，不影响保真）。这样下次谁开 OIT 也不会以为是玄学。
- 若要**真正修掉 P1-1**：需要实机 A/B（DH 在 / DH 去掉 / OIT 开与关四组），比对描边像素宽度与穿透，属于 M7/M10 的验证工作量，不建议在没有实机对比的情况下盲改分桶。
- **P2-1（`lastWorld`）一行就能改，且零风险**，建议顺手做掉。

---

## 7. 本次沙箱未能验证的（NEEDS-REAL-RUN，不假装 PASS）

1. `afterTerrain` 在 OIT 通道下"线框是否真的完全不可见"——目前是**字节码推论**（分桶读取点唯一 + 该分桶唯一调用点在经典通道），没有实机像素证据。
2. `entity_block` 的模型光晕在 `executeOutline` 通道里与 DH 的 LOD 合成顺序（会不会被 DH 的 framebuffer 覆盖）——本次只证明 `block model glow active` 打印过，未证明像素正确。
3. `RenderTypes.solidMovingBlock().outline()` 在资源重载（F3+T）后是否仍指向有效 `RenderType`（我们在 `outlineTypeResolved` 后缓存，理论上 `RenderType` 是稳定描述符，但没有实机 F3+T 记录）。
4. 装 Sodium（或 Iris）后：它们通常会混入 `SubmitNodeCollection`/`LevelExtractor`/`EntityRenderer`，本次环境无 Sodium，**不能替未来环境背书**。

---

## 8. 附：复核用命令（可重跑）

```powershell
$javap = "C:\Program Files\Java\jdk-25\bin\javap.exe"
$jar   = "D:\Gradle\caches\fabric-loom\26.3\minecraft-client-only.jar"
# 1) mixin 目标存在性
& $javap -p -cp $jar net.minecraft.client.KeyMapping
& $javap -p -cp $jar net.minecraft.client.Minecraft | Select-String setScreen
& $javap -p -cp $jar net.minecraft.client.renderer.rendertype.RenderType | Select-String 'create\('
& $javap -p -cp $jar net.minecraft.client.renderer.RenderPipelines | Select-String 'register\('
& $javap -p -cp $jar net.minecraft.client.renderer.SubmitNodeCollection
& $javap -p -cp $jar net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher | Select-String submit
& $javap -p -cp $jar net.minecraft.client.renderer.entity.EntityRenderer | Select-String extractRenderState
& $javap -p -cp $jar net.minecraft.client.renderer.extract.LevelExtractor | Select-String 'extract\('
# 2) slide9 就是 outlineColor
& $javap -p -c -cp $jar net.minecraft.client.renderer.SubmitNodeCollection   # 找 submitModel 的 "iload 9 / ifeq"
# 3) afterTerrain 唯一读取点
& $javap -p -c -cp $jar 'net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedFrame'
# 4) OIT vs 经典通道分支
& $javap -p -c -cp $jar net.minecraft.client.renderer.LevelRenderer   # lambda$addMainPass$0: 178/187/235/242/248
# 5) 归因
& $javap -p -c -cp $jar net.minecraft.client.renderer.ShaderManager   # "Requested post effect does not exist"
& $javap -p -cp $jar net.minecraft.client.renderer.GameRenderer | Select-String END_OF_FRAME
```
