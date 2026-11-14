# Ping For It — 26.3 NeoForge 迁移方案

> 状态：**N0–N6 已完成并合并**。`26.3-NeoForge` 已 `--ff-only` 到 `26.3` @ `e7937b4`；`:neoforge:compileJava`、`:neoforge:compileTestJava` 通过；`:neoforge:test` 以 `--project-prop pfi.fmlUnitTests=true` 实跑 **15 tests / 15 successful / 0 failed / 0 skipped**；FML 日志出现 `[PingForIt] Init`，无 `IncompatibleClassChangeError`、无 `logoFile` 警告。N4 client 进入 26.3 主菜单；N4 server `DEDICATED_SERVER in DEV`、`Done (0.365s)`，两者均无 ERROR/FATAL。N5 打包/静态检查 PASS：`Ping-For-It-0.4.2-pfi-beta1-neoforge-26.3.jar`，831081 B，SHA256 `b9661bad794884f4945429afb008ebbab5f96ecce4cfa1ba924fd97d96f59cb6`，508 entries / 433 classes / 6 services / 8 client mixins；无重复 entry、无 test class、无旧命名空间、无 refmap。主菜单顶部 “NeoForge Beta” 是 loader beta 提示，不是故障。N6 独立审查 PASS（coverage consumer 与 3 个契约测试对齐）；`26.3` 已从 `e7937b4` 快进到 `d174df2`，与 `26.3-NeoForge` 同步。N7 Create/Flywheel parity 仍延后。本文是维护者迁移计划与审查基线，不是产品行为规范。
> 分支：`26.3-NeoForge`（从 `26.3` @ `e7937b4` 快进）
> 目标：恢复 NeoForge 26.3 模块，使核心 ping/marker/配置/渲染/网络可用；首轮不含 Create/Flywheel 完整 parity。
> 参考：`MIGRATION-26.3-FABRIC.md`、`docs/`（产品行为 owner；本文件不替代 docs）

## 0. 目标与边界

### 0.1 目标

1. 在 `neoforge/` 恢复 26.3 入口、网络、SPI、资源、metadata、本地 mixin 和测试。
2. NeoForge 只承担 loader 适配；共享逻辑留在 `common/`，不复制 common 实现。
3. 首轮范围：核心 + `CreateDoorPresentationResolver` + `SimulatedDockingConnectorPresentationResolver`。
4. 每阶段按“分析 → 编写 → 静态审查 → 编译/测试 → 结果审查”推进；审查通过后再提交/合并。
5. 区分编译、测试、运行、产物静态检查；不用编译通过替代游戏内验证。

### 0.2 非目标

- 首轮不恢复 Create/Flywheel/WorldAware/Contraption/WaterWheel/Create mixin；它们依赖 26.3 已删除的 common 几何框架或只有 1.21.1 的 Create/Flywheel。
- 不恢复 Forge；Forge 源码仍在 `mod-source-isolated/forge/`。因此 `verifyModIdentity` 仍不可用。
- 不修改 `docs/`。产品行为、配置语义、协议和渲染边界仍以 docs owner 为准。
- 不把 `common` 改成 NeoForge 专用；不在 common 引入 `net.neoforged`。

## 1. 已完成的前置事实

- `26.3-NeoForge` 原先为 `407fbc6`，是 `26.3` 的严格祖先（0 ahead / 46 behind）。
- 已执行 `git merge --ff-only 26.3`，HEAD = `e7937b4`，与 `26.3` 一致。
- `mod-source-isolated/neoforge/src` 共 17 个 main Java、3 个 test Java、7 个资源。
- 本地 26.3 工件：NeoForge `26.3.0.0-beta`、FML 12.0.0、sponge-mixin `0.17.3+mixin.0.8.7`、NeoForm `26.3-1`。
- Create/Flywheel 缓存只有 1.21.1 构建，不能作为 26.3 compileOnly。

## 2. 目标架构

```text
common/    ModDevGradle + NeoForm 26.3-1，Java 25
           ├─ 共享/服务端逻辑
           ├─ 客户端逻辑（vanilla 26.3 类型）
           ├─ 6 个 platform SPI
           └─ assets/pingforit、icon.png、pingforit.mixins.json

neoforge/  ModDevGradle 2.0.148，单一 universal 源集
           ├─ NeoMain / NeoClient
           ├─ 6 个 SPI 实现（main/client 逻辑分文件，同一 jar）
           ├─ 本地 neoforge.mods.toml / pingforit.mixins.json / pack.mcmeta
           ├─ 6 个 META-INF/services
           └─ jar 合并 common classes + common resources
```

关键决策：

1. `neoforge` 用 `implementation(project(':common')) { transitive = false }`，只消费 common 类，避免 NeoForm/测试/可选依赖泄漏。
2. 资源经 `processResources.from(common.resources)` 合并一次；排除 common 的 `pingforit.mixins.json`，使用 NeoForge 本地配置。FML 开发/测试时还必须把 `project(':common').sourceSets.main` 加入 `neoForge.mods.pingforit`，否则 common 类只在 legacy classpath，`IPacket` 会被 AppClassLoader 加载并触发 `IncompatibleClassChangeError`。
3. jar 只合并 common `classesDirs`，资源经 processResources 进入，避免重复 entry。
4. NeoForge 没有 Fabric split 源集；client-only 类仍进 universal jar，靠 `Dist` 守卫和 ServiceLoader 按需加载，禁止在 `NeoMain` 直接引用 `CommonClient`。
5. NeoForge 26.3 使用 sponge-mixin 0.17.3，`CompatibilityLevel` 支持 `JAVA_25`；本地 mixin 配置使用 `JAVA_25`，不沿用 AGENTS.md 旧的 NeoForge `JAVA_21` 断言。

## 3. 构建接线

`neoforge/build.gradle` 必须包含：

- `base { archivesName = archive_name }`、`version = mod_version`。
- `neoForge { version = neoforge_version; runs client/server -> runs/neoforge-* }`；`runs` 内 `disableIdeRun()` 阻止 MDG 在 Gradle sync 时自动生成 `neoforge - Client/Server`，避免与手动的 Application 配置重复。
- IDE 侧保留 `NeoForge Client/Server (:neoforge)` Application 配置；IDEA Project SDK 必须是 25（`misc.xml` 的 `project-jdk-name`），Application 配置同时设置 `ALTERNATIVE_JRE_PATH=25`。SDK 21 不识别 MDG 生成的 `--sun-misc-unsafe-memory-access=allow`，会在 JVM 启动阶段直接失败。
- `dependencies { implementation(project(':common')) { transitive = false } }`。
- `generateModMetadata`（Sync）从 `src/main/templates` 展开 `META-INF/neoforge.mods.toml` 到生成目录；`processResources` 合并 common 资源并排除 common `pingforit.mixins.json`。不要再用 `filesMatching/expand`，否则 IntelliJ Gradle sync 会报 `MatchingCopyAction` 警告。
- `jar { archiveClassifier = 'neoforge-26.3'; dependsOn :common:classes; from common.classesDirs; LICENSE/THIRD-PARTY }`。
- 测试启用后 `neoForge.addModdingDependenciesTo(sourceSets.test)`。
- 不引入 `multiloader-loader`；`commonJava/commonResources` 在 26.3 已不存在。

目标产物：

```text
neoforge/build/libs/Ping-For-It-0.4.2-pfi-beta1-neoforge-26.3.jar
```

## 4. SPI 与入口映射

| SPI / 事件 | 26.3 NeoForge 映射 |
| --- | --- |
| 客户端 tick | `ClientTickEvent.Pre` |
| join / leave | `ClientPlayerNetworkEvent.LoggingIn / LoggingOut` |
| 每帧 | `RenderFrameEvent.Pre` |
| HUD | `RegisterGuiLayersEvent.registerAboveAll(Identifier, GuiLayer)` |
| 世界提交 | `SubmitCustomGeometryEvent`（patched LevelRenderer 中位于 `submitBlockEntities` 之后） |
| 按键 | `RegisterKeyMappingsEvent` |
| 资源重载 | `AddClientReloadListenersEvent.addListener(Identifier, listener)` |
| 客户端发包 | `ClientPacketDistributor.sendToServer` |
| 服务端发包 | `PacketDistributor.sendToPlayer` |
| 客户端 payload handler | `RegisterClientPayloadHandlersEvent` |
| payload 类型/服务端 handler | `RegisterPayloadHandlersEvent` + `PayloadRegistrar.optional()` |
| 配置界面 | `ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class, ...)` |

服务端 handler 只引用 `CommonServer`；所有 clientbound handler 移入 `NeoClient`，避免专用服务器加载客户端类。

## 5. 源码迁移分类

### 5.1 首轮保留/改写

- `NeoMain`、`NeoClient`
- `platform/PlatformContextServiceImpl`、`PlatformClientContextServiceImpl`
- `platform/PlatformNetworkServiceImpl`、`ClientNetworkServiceImpl`
- `platform/PlatformClientEventServiceImpl`、`PlatformServerEventServiceImpl`
- `integration/create/CreateDoorPresentationResolver`
- `integration/simulated/SimulatedDockingConnectorPresentationResolver`
- `src/main/templates/META-INF/neoforge.mods.toml`、`pingforit.mixins.json`、`pack.mcmeta`
- 6 个 `META-INF/services`
- 测试：`CreateDoorPresentationResolverTest`、`SimulatedDockingConnectorPresentationResolverTest`

### 5.2 首轮延后（保留在 mod-source-isolated）

- `NeoForgeWorldAwareBlockModelOutlineAdapter`（26.3 common 由 `BlockModelGlowPass` 取代）
- `CreateEntityOutlineAdapter`、`CreateEntityOutlineMaskScope`
- `CreateFlywheelGeometryAdapter`
- `CreateContraptionRaycastAdapter`、`CreateContraptionRaycastDelegate`
- `CreateLargeWaterWheelPresentationResolver`
- `CreateVisualizationManagerMixin`
- `CreateContraptionRaycastAdapterTest`

延后原因：依赖 26.3 已删除的 `EntityBlockGeometry*`/`EntityOutline*`/`WorldAware*`/`OutlineOnlyBufferSource`/`Flywheel*`，或依赖只有 1.21.1 的 Create/Flywheel。

### 5.3 小改点

- `CreateDoorPresentationResolver`：`EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID` 已删，内联合法 namespaced source id；coverage relation 由 common `BlockOutlinePass` 消费：owner 的 BER 本帧实际渲染后抑制 covered upper，避免上半门重复 wireframe。
- `Simulated`：`ResourceLocation` → `Identifier`，registry lookup/getKey 同步改名。
- `NeoMain`：`FMLEnvironment.getDist()`；补 sound event 注册。
- `NeoClient`：`AddClientReloadListenersEvent`；补 3 个 client event；移除 WorldAware 直调。
- 本地 mixin：26.3 common 的 8 条，`JAVA_25`，无 refmap。
- `pack.mcmeta`：`min_format: 121` / `max_format: [121, 0]`。

## 6. 分阶段执行

| 阶段 | 内容 | 出口条件 |
| --- | --- | --- |
| N0 | FF 到 26.3 | HEAD = e7937b4，工作区 clean |
| N1 | build.gradle + docs/AGENTS 基线 | build 脚本可解析，静态审查通过 |
| N2 | 核心入口/SPI/网络/资源 | `:neoforge:compileJava` 通过 |
| N3 | door/Simulated + 2 个测试 | `:neoforge:compileTestJava` 通过；`:neoforge:test` 用 `--project-prop pfi.fmlUnitTests=true` 实跑 15/15 通过 |
| N4 | client/server 启动 | client：26.3 主菜单；server：`Done (0.365s)`；均有 `[PingForIt] Init`，无 ERROR/FATAL |
| N5 | 打包/静态检查 | PASS：精确 jar、433 classes、6 services、8 client mixin；无重复/test class/旧命名空间/refmap |
| N6 | 独立审查 + 合并 26.3 | PASS：独立审查修复 3 项阻断；common 1193/0；`26.3` ff-only 到 `d174df2` |
| N7 | Create/Flywheel parity | 需 26.3 artifact + common 几何框架决策；单独立项 |

## 7. 验证门禁

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
.\gradlew.bat --no-daemon :common:compileJava
.\gradlew.bat --no-daemon :neoforge:compileJava
.\gradlew.bat --no-daemon :neoforge:compileTestJava
.\gradlew.bat --no-daemon --project-prop pfi.fmlUnitTests=true :neoforge:test
.\gradlew.bat --no-daemon :neoforge:runServer
.\gradlew.bat --no-daemon :neoforge:runClient
.\gradlew.bat --no-daemon :neoforge:build
.\gradlew.bat --no-daemon :common:test
~~~

- `verifyModIdentity` 仍依赖 `:forge:build`，Forge 空骨架阶段不得运行或宣称通过。
- `:neoforge:test` 首轮只覆盖 door/Simulated；不是游戏内验证。
- `:neoforge:test` 依赖 `net.neoforged.fancymodloader:junit-fml:12.0.0`（MDG `unitTest` 的 FML JUnit 启动器）。该依赖已成功解析并实跑；首次在无网络/无缓存时会在 `testRuntimeClasspath` 解析阶段失败。为不阻塞离线构建，`neoforge/build.gradle` 默认不启用 `unitTest`；显式运行：

  ```powershell
  .\gradlew.bat --no-daemon --project-prop pfi.fmlUnitTests=true :neoforge:test --rerun-tasks
  ```

  当前 Gradle/Windows 下 `-Ppfi.fmlUnitTests=true` 会被误解析成任务选择器（报 `Task '.fmlUnitTests=true' not found`），使用 `--project-prop` 长形式。不要用 `exclude` 绕过，那只会把依赖失败换成 FML Loader 失败。
- NeoForge Client 已在 IDEA 中实跑进入 26.3 主菜单（`runs/neoforge-client/logs/latest.log`）；日志确认 `CLIENT in DEV`、`[PingForIt] Init`、配置保存、outline pipeline 注册、`mod/pingforit` 资源加载，无 ERROR/FATAL。IDEA Project SDK 21 会在 JVM 启动阶段报 `Unrecognized option: --sun-misc-unsafe-memory-access=allow`；必须用 25。
- N5 已执行 `.\gradlew.bat --no-daemon :neoforge:build`（Gradle 自身必须运行在 JDK 25；Loom 1.19.3 在 Java 24 下拒绝配置）。产物 `Ping-For-It-0.4.2-pfi-beta1-neoforge-26.3.jar` 静态检查 PASS：831081 B / 508 entries / 433 classes（NeoForge 12 + common 421）/ 6 services / 8 client mixins；所有 class 与资源 SHA256 与 build 输出一致，无重复 entry、无 test class、无旧命名空间、无 refmap；`pack.mcmeta` 121，metadata/icon/LICENSE/THIRD-PARTY 完整。
- 联机、可选集成、HUD/outline 视觉、mixin 注入、Create/Flywheel 运行时均未验证。

## 8. 风险

1. 服务端类加载：`NeoMain` 禁止直接引用 `CommonClient`；client handler 必须在 `NeoClient`。FML dev/test 必须把 common source set 放进 `neoForge.mods`，否则 `IPacket` 与 `CustomPacketPayload` 会落在不同 classloader，出现 `IncompatibleClassChangeError`。
2. 本地 mixin 配置已按 common 26.3 的 8 条重列；不再引用已删除的 `GameRendererMixin`/`GuiMixin`/`LevelRendererMixin` 等旧类。client 已进入主菜单，说明 mixin 配置可加载；HUD/outline/输入等行为仍需游戏内交互验证。
3. `JAVA_25` 与旧 AGENTS 规则冲突；以 NeoForge 实际 sponge-mixin 依赖为准。
4. `SubmitCustomGeometryEvent` 时序已从 patched LevelRenderer 核实，但运行期仍需观察双画/漏画。
5. Create/Flywheel 没有 26.3 artifact；不要加 1.21.1 compileOnly。
6. `pack.mcmeta` 使用 `min_format: 121`（data 格式）；client 资源重载已通过 `mod_resources` 加载 `mod/pingforit`，但资源侧格式未单独声明（26.3 resource 为 97.1）。若后续要支持独立 resource pack 安装，需复核或改为双端兼容区间；当前不作为 N6 阻断项。

## 9. 变更记录

- 2026-11-13：N0 完成；建立本基线；首轮范围确定为核心 + door/Simulated。
- 2026-11-13：N2 核心源码与 N3 测试源码写入；`:neoforge:compileJava`、`:neoforge:compileTestJava` 通过；静态审查 PASS。`:neoforge:test` 首次因 `SharedConstants` 在无 FML Loader 的普通 JUnit 中初始化失败，随后启用 MDG `unitTest`；`junit-fml:12.0.0` 只发布在 `maven.neoforged.net`；首次在无缓存/无网络时会解析失败，当前已成功解析。测试改为 `--project-prop pfi.fmlUnitTests=true` opt-in，默认不跑 FML 测试。
- 2026-11-13：`-Ppfi.fmlUnitTests=true` 被 Gradle/Windows 误解析为任务 `.fmlUnitTests=true`；改用 `--project-prop pfi.fmlUnitTests=true` 后属性识别正常。`neoforge.mods.toml` 改为 `src/main/templates` + `generateModMetadata`，消除 IDEA `MatchingCopyAction` 警告。
- 2026-11-13：`:neoforge:test` 首次 FML 加载失败，根因是 common 类只在 legacy classpath，`IPacket` 由 AppClassLoader 加载、`CustomPacketPayload` 由 module classloader 加载；把 `project(':common').sourceSets.main` 加入 `neoForge.mods.pingforit` 后修复。`logoFile` 改为 `iconFile`。最终 `:neoforge:test` 15/15 通过，日志 `[PingForIt] Init`，无错误/警告。
- 编译/测试交接：用户接管 Gradle 编译；下一步以用户返回的 `:neoforge:test`、runClient/runServer 结果为准。
- 2026-11-14：N4 client 完成。NeoForge Client 通过 IDEA 启动进入 26.3 主菜单；日志 `runs/neoforge-client/logs/latest.log` 显示 CLIENT in DEV、`[PingForIt] Init`、配置保存、outline pipeline 注册、`mod/pingforit` 资源加载，无 ERROR/FATAL；主菜单 “NeoForge Beta” 是 loader beta 提示。
- 2026-11-14：修复 IDEA 启动 JVM 失败。项目 SDK 原为 21，无法识别 JDK 24+ 的 `--sun-misc-unsafe-memory-access=allow`；改为 25，并在 Application 配置启用 `ALTERNATIVE_JRE_PATH=25`。同时用 `disableIdeRun()` 阻止 MDG 重建重复的 `neoforge - Client/Server`。
- 2026-11-14：N4 server 完成。`runs/neoforge-server/logs/latest.log` 显示 `DEDICATED_SERVER in DEV`、`[PingForIt] Init`、`Loaded config type=ServerConfig`、`Starting Minecraft server on *:25565`、`Done (0.365s)`，无 ERROR/FATAL。
- 2026-11-14：N5 完成。`:neoforge:build` 在 JDK 25 下 BUILD SUCCESSFUL；jar `Ping-For-It-0.4.2-pfi-beta1-neoforge-26.3.jar`，831081 B，SHA256 `b9661bad794884f4945429afb008ebbab5f96ecce4cfa1ba924fd97d96f59cb6`；静态检查通过：508 entries、433 classes、6 services、8 client mixins、无重复/无 test class/无旧命名空间/无 refmap、metadata/icon/LICENSE/THIRD-PARTY 完整。首次在 Java 24 下构建失败（Loom 1.19.3 要求 JVM 25），设置 `JAVA_HOME=jdk-25` 后成功。
- 2026-11-14：N6 完成。独立审查（n6-reviewer）PASS；修复 3 项阻断：common `BlockOutlinePass` 消费 Create door 的 BER coverage；3 个契约测试对齐首轮范围（Create water wheel/WorldAware 延迟断言、Simulated 反射诊断）；文档门禁命令与 junit-fml 描述同步。`common:test` 1193/1193 通过；N5 jar 重建并复核；`26.3` 从 `e7937b4` 快进到 `d174df2`，与 `26.3-NeoForge` 同步。残余：coverage consumer 无新增聚焦测试；建议合并后做一次 Fabric outline 冒烟；N7 仍延后。`:neoforge:test` 15/15 是 3 个契约测试调整后、coverage consumer 前的结果；最终提交后复跑受当前沙箱 attach 限制未完成，但 door/Simulated resolver 测试不经过 `BlockOutlinePass`。
