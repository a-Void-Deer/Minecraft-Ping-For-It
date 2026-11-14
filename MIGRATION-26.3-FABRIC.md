# Ping For It — 26.3 Fabric 迁移方案

> 状态：F0–F3 已实现并提交；F5 的 no-remap 身份校验已调整；多格方块 door/bed 按 26.3 前原设计恢复；chest/HUD 中心与其他未解决问题见 [改进方案.md](改进方案.md)；F4 运行验证待用户环境
> 分支：`26.3-Fabric`
> 代码起点：`6373ee9`（2026-11-09）；方案文档提交：`f010d3d`；F0 提交 `eb8b17a`；Fabric 模块提交 `aba76a7`；身份校验提交 `ef8796c`
> 参考工程：`C:\Users\65653\Desktop\参考文件`（同 fork 的 26.3 Fabric 单端移植，仅作 26.3 API/构建用法参考）
>
> 本文是**维护者迁移计划**，不是产品行为规范。按 `docs/AGENTS.md` 的目录边界，迁移步骤、类路径、构建脚本和验证命令不放进 `docs/`；产品行为仍以 `docs/` 为准。

## 0. 目标与边界

### 0.1 目标

1. 在 `26.3-Fabric` 分支上恢复 Fabric 模块，使 `Ping For It` 能以 26.3 + Java 25 + Fabric Loader 0.20.1 + Fabric API 0.165.0+26.3 启动。
2. Fabric 只承担 loader 适配：入口、网络注册、SPI 实现、资源/元数据、可选集成入口；共享逻辑继续留在 `common`。
3. `common` 保持 Fabric/Forge/NeoForge 三端通用，不引入 `net.fabricmc` import；Fabric 专属 API 只出现在 `fabric/`。
4. 分阶段提交，每阶段同步更新本文档并跑对应验证；审查发现问题则回到操作阶段修复，直到复审通过。

### 0.2 非目标

- 本阶段不迁移 Forge/NeoForge 源码；它们继续留在 `mod-source-isolated/`。
- 不复制参考工程的 `fabric/src/client/java/nx/pingwheel/common/**`。当前 `common` 已有 26.3 代码，复制会造成双份实现。
- 不把 `common` 改成 Loom/split 源集；`common` 继续用 ModDevGradle + NeoForm 26.3-1。
- 不把 `common` 的客户端类搬到 `fabric`；只把“注册/事件入口”通过 SPI 暴露。
- 不宣称游戏内行为已验证；构建/静态检查与运行验证分开记录。

## 1. 现状（F0–F3 完成后）

| 面 | 状态 |
| --- | --- |
| `common` | F0 seam 清理已落地：父接口去 `registerKeyMapping`、`clearLastWorld`、`PingTextureState` apply 写入、`IClientNetworkService` 拆分；`javac 25` 手工编译通过 |
| `fabric` | F1–F3 已落地：main/client 源集、Loom split、26.3 入口/网络/SPI、v1 ResourceLoader、services；旧 adapter/ModMenu 留在 `mod-source-isolated/` |
| `forge`/`neoforge` | 空骨架；源码在 `mod-source-isolated/` |
| Fabric 构建 | Loom 1.19.3、无 mappings、无 remapJar；已有 common 依赖、split 源集、`loom.mods`、元数据生成、资源/类合并 |
| Fabric 源码 | `FabricMain`/`FabricClient`/platform SPI 已按 26.3 替换；active `fabric/src` 无 `playS2C/playC2S`、v0 `ResourceManagerHelper`、旧 adapter |
| SPI | `IPlatformContextService`/`IClientPlatformContextService`、`IPlatformNetworkService`/`IClientNetworkService` 已拆分；client event 三回调已实现 |
| 构建验证 | `verifyModIdentity` 仍要求 Fabric refmap，与 26.3 no-remap 冲突；Forge/NeoForge 尚未恢复，暂不能跑聚合校验 |

## 2. 目标架构

```text
common/   ModDevGradle + NeoForm 26.3-1，Java 25
          ├─ 共享/服务端逻辑
          ├─ 客户端逻辑（vanilla 26.3 类型）
          ├─ SPI 接口（IPlatform*Service）
          └─ common 资源（assets/pingforit、icon.png、pingforit.mixins.json）

fabric/   Loom 1.19.3，splitEnvironmentSourceSets
          ├─ src/main   FabricMain、主端 SPI、services、fabric.mod.json 模板
          ├─ src/client FabricClient、client SPI、services、资源重载、命令
          └─ jar 合并 common classes + common resources
```

关键决策：

1. `fabric` 用 `implementation(project(':common')) { transitive = false }`，只消费 common 的类，避免把 common 的 ModDev/NeoForm 依赖泄漏到 Loom classpath。
2. `common` 的客户端类留在 common；Fabric client 源集直接引用它们。
3. Fabric 的 HUD/world-submit/render-frame 通过 `IPlatformClientEventService` 回调注册，不在 `FabricClient` 里重复注册 HUD/outline。
4. 资源只经 `processResources` 合并一次；jar 只合并 common classes，避免重复条目。
5. `fabric.mod.json` 由模板生成，只展开 `version`；mixin 使用 common 的 `pingforit.mixins.json`，无 refmap。

## 3. 分层迁移方案

### 3.1 构建层

F1 已落地：`fabric/build.gradle` 不再使用 `multiloader-loader` 的 common 源码注入，改为 `implementation(project(':common')) { transitive = false }` + Loom split。当前目标配置如下：

```groovy
plugins {
    id 'net.fabricmc.fabric-loom'
}

base {
    archivesName = archive_name
    version = mod_version
}

loom {
    splitEnvironmentSourceSets()
    mods {
        'pingforit' {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
    runs { ... runs/fabric-client, runs/fabric-server ... }
}

dependencies {
    minecraft "com.mojang:minecraft:${minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${fabric_loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${fabric_version}"
    implementation(project(':common')) { transitive = false }
}

def generateModMetadata = tasks.register('generateModMetadata', Sync) { ... }
sourceSets.main.resources.srcDir(generateModMetadata.flatMap { it.destinationDirectory })

processResources {
    dependsOn generateModMetadata
    from project(':common').sourceSets.main.resources
}

jar {
    archiveClassifier = 'fabric-26.3'
    dependsOn project(':common').tasks.named('classes')
    from project(':common').sourceSets.main.output.classesDirs
    from(rootProject.file('LICENSE')) { rename { it + '_pingforit' } }
    from(rootProject.file('THIRD-PARTY-NOTICES.md'))
}
```

验收：

- `:fabric:processResources` 生成 `build/resources/main/fabric.mod.json`、`assets/pingforit/**`、`pingforit.mixins.json`。
- `:fabric:compileJava` 通过；`:fabric:build` 产出 `Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`。
- jar 内 common 类与资源各出现一次，无重复条目。

### 3.2 源集层

当前：`fabric/src` 已恢复 main/client 源集；旧 1.21.1 入口已在 F2/F3 替换为 26.3 版本。

目标布局：

| 位置 | 文件 |
| --- | --- |
| `fabric/src/main/java/nx/pingwheel/fabric/` | `FabricMain` |
| `fabric/src/main/java/nx/pingwheel/fabric/platform/` | `PlatformContextServiceImpl`、`PlatformNetworkServiceImpl`、`PlatformServerEventServiceImpl` |
| `fabric/src/main/resources/META-INF/services/` | context/network/server event |
| `fabric/src/main/templates/fabric.mod.json` | 元数据模板 |
| `fabric/src/client/java/nx/pingwheel/fabric/` | `FabricClient` |
| `fabric/src/client/java/nx/pingwheel/fabric/platform/` | `PlatformClientContextServiceImpl`、`PlatformClientEventServiceImpl` |
| `fabric/src/client/resources/META-INF/services/` | client context/client event |

不恢复：

- `FabricWorldAwareBlockModelOutlineAdapter`（26.3 由 common `BlockOutlinePass` 取代）。
- `ModMenuIntegration`（26.3 ModMenu 依赖未解析，F5 再处理）。

### 3.3 SPI 层

当前 common 接口：

- `IPlatformClientEventService`：tick/join/leave + renderFrame + HUD + worldSubmit。
- `IPlatformContextService`：只保留共享成员（版本、目录、`isModLoaded`）。
- `IClientPlatformContextService`：只保留 `registerKeyMapping(KeyMapping)`。
- `IPlatformNetworkService`：只保留服务端 `sendToClient`。
- `IClientNetworkService`：只保留客户端 `sendToServer`，避免 Fabric main 源集引用 `ClientPlayNetworking`。

F0 清理（common）：

1. 从 `IPlatformContextService` 删除 `registerKeyMapping` 和 `KeyMapping` import。
2. 只在 `IClientPlatformContextService` 保留 `registerKeyMapping`。
3. 更新 `TestPlatformContextService`，删除对应 `@Override` 与 import。
4. 补 `GameContext.clearLastWorld()`，并在 common 的 `CommonClient.onLeaveServer()` 调用；三端统一清理，不依赖某个 loader 的 disconnect 事件。
5. 修 `ResourceReloadListener`：在 apply 阶段写 `PingTextureState.setHasCustomTexture(...)`，否则 HUD 永远读不到自定义 ping 纹理。
6. 清理 `BlockOutlinePass.register()` 的旧注释/日志（“loader must register”已过时）。

Fabric 实现：

- `PlatformContextServiceImpl`（main）：版本、game/config dir、`isModLoaded`；不含 key mapping。
- `PlatformClientContextServiceImpl`（client）：委托 main + `KeyMappingHelper.registerKeyMapping`。
- `ClientNetworkServiceImpl`（client）：`ClientPlayNetworking.canSend/send`，实现 `IClientNetworkService`。
- `PlatformClientEventServiceImpl`（client）：
  - tick/join/leave 保持；
  - renderFrame → `LevelExtractionEvents.END_EXTRACTION`；
  - HUD → `HudElementRegistry.addLast`，每次注册生成唯一 `Identifier`；
  - worldSubmit → `LevelRenderEvents.COLLECT_SUBMITS`，构造 `WorldSubmitContext(context.poseStack(), context.submitNodeCollector(), Minecraft.getInstance().gameRenderer.mainCamera(), 0.0f)`。
- services：main 放 context/network/server；client 放 client context/client event/client network。

验收：

- `common` 无 `net.fabricmc` import。
- Fabric 编译期能实现全部 SPI 方法。
- `HudElementRegistry` 的 marker 层先于 toggle notice 层；world submit 的 camera 非空时 outline 才提交。

### 3.4 入口与网络层

F2/F3 已把旧入口替换为 26.3 版本；当前目标实现如下：

- `FabricMain`：`CommonServer.INSTANCE.onInit()`；14 个 codec；`PayloadTypeRegistry.clientboundPlay/serverboundPlay`；6 个 server receiver（PingLocation、UpdateChannel、MarkerCreate、MarkerRemove、ServerConfigRequest、ServerConfigUpdate）。
- `FabricClient`：8 个 client receiver（RateLimit、SyncDuration、PingLocation、ServerConfigSnapshot、MarkerCreated、MarkerRemoved、MarkerRejected、MarkerWinnerChanged）；`CommonClient.INSTANCE.onInit()`；sound 注册；`ResourceLoader` v1；client command；不单独清 `GameContext.lastWorld`（common `onLeaveServer` 统一做）。
- `ClientNetworkServiceImpl` 实现 `IClientNetworkService.sendToServer`，main 的 `PlatformNetworkServiceImpl` 只实现 `sendToClient`；Fabric main 不再 import `ClientPlayNetworking`。
- 不在 `FabricClient` 里直接注册 HUD 或 `BlockOutlinePass`；common 的 `CommonClient.onInit()` 已通过 SPI 注册。

验收：

- 客户端/服务端 receiver 数量与 packet 集合一致。
- `ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(id, new ResourceReloadListener())` 使用 v1。
- 无 `PayloadTypeRegistry.playS2C/playC2S`、`ResourceManagerHelper`、`IdentifiableResourceReloadListener` 残留。

### 3.5 HUD / 渲染 / 资源 / Mixin

- common 的 `CommonClient.onInit()` 顺序：tick/join/leave → renderFrame → `BlockOutlinePass.register()` → marker HUD → toggle notice HUD → world submit → key mappings。
- Fabric 的 HUD 回调只负责把 `HudElementRegistry` 映射到 SPI；不复制 `MarkerHudRenderer`/`WheelHudRenderer`。
- `ResourceReloadListener` 注册到 v1；在 apply 阶段更新 `PingTextureState`（不在 load 线程提前写）。
- mixin 使用 common 的 `pingforit.mixins.json`（8 个 client mixin、`JAVA_25`、无 refmap）；Fabric 不再维护独立配置。
- `fabric.mod.json` 的 `mixins` 用对象形式 + `environment: "client"`。

### 3.6 元数据 / 运行 / 打包

- `fabric/src/main/templates/fabric.mod.json`：id/name/version/description/links 使用本项目身份；`depends` 写 `fabricloader >=0.20.1`、`minecraft ~26.3`、`java >=25`、`fabric-api *`；entrypoints main/client；不写 modmenu（F5 再处理）。
- run 目录：`runs/fabric-client`、`runs/fabric-server`，不覆盖现有 `run/` 或 `runs/skeleton/`。
- `verifyModIdentity`：已移除“Fabric 必须带 `Ping-For-It-refmap.json`”的断言（`ef8796c`）；若配置仍声明 refmap，则要求对应文件存在。Forge/NeoForge 恢复前不跑聚合任务。
- 可选集成：VoiceChat entrypoint 可保留（缺失时 fail-soft）；ModMenu、DH、FTB、Sable 的 26.3 坐标在 F5 处理。

## 4. 分阶段执行

| 阶段 | 内容 | 主要文件 | 验证 | 提交 |
| --- | --- | --- | --- | --- |
| F0 | common SPI/客户端 seam 清理 | `IPlatformContextService`、`IClientPlatformContextService`、`IClientNetworkService`、`TestPlatformContextService`、`GameContext`、`ResourceReloadListener`、`BlockOutlinePass` | `:common:compileJava` | `fix: split common client platform seams for Fabric`（`eb8b17a`） |
| F1 | Fabric 构建 + 源集骨架 | `fabric/build.gradle`、`fabric/src/main/**`、`fabric/src/client/**`、`fabric.mod.json` 模板 | `:fabric:processResources`（编译留到 F2/F3） | 与 F2/F3 合并提交，避免提交不可编译源码 |
| F2 | Fabric 主端入口/网络/SPI | `FabricMain`、main platform impls、main services | `:fabric:compileJava` | 与 F3 合并为 `feat: restore Fabric 26.3 module and client SPI`（`aba76a7`） |
| F3 | Fabric 客户端入口/SPI/HUD/资源 | `FabricClient`、client platform impls、client services | `:fabric:compileJava`、`:fabric:compileClientJava` | 同上 |
| F4 | 运行与修复 | 运行目录、日志 | `:fabric:runClient` | `fix: ...`（按实际） |
| F5 | 元数据/打包/身份校验/可选集成 | `build.gradle`、`fabric.mod.json`、可选依赖 | `:fabric:build`、jar 检查 | `chore: align Fabric packaging and identity checks` |

审查循环：每个阶段完成后由 reviewer 对照本文档、参考工程和真实文件审查；发现问题由 operator 修复，reviewer 复审，直到 PASS 再提交/进入下一阶段。

## 5. 验收标准

### 5.1 编译/构建

- `:common:compileJava` 通过（允许警告，不允许 error）。
- `:fabric:compileJava` 通过。
- `:fabric:compileClientJava` 通过（split 源集下 `compileJava` 只编 main，client 错误不会被发现）。
- `:fabric:processResources` 产物包含 `fabric.mod.json`、`assets/pingforit/`、`pingforit.mixins.json`。
- `:fabric:build` 产出精确命名的 jar；jar 内无重复 common 类/资源。

### 5.2 静态契约

- `common` 无 `net.fabricmc` import。
- Fabric 无 `PayloadTypeRegistry.playS2C/playC2S`、`ResourceManagerHelper` v0、`IdentifiableResourceReloadListener`、旧 outline adapter 引用。
- `IPlatformClientEventService` 的三个新方法在 Fabric client 实现；`IClientNetworkService` 在 Fabric client 实现；services 文件按 main/client 拆分。
- Fabric main 源集无 `ClientPlayNetworking`/`KeyMappingHelper`/`HudElementRegistry` 等 client-only import。
- `fabric.mod.json` 的 mixins 为对象形式 + client environment；无 refmap 断言。
- 资源/协议命名空间仍为 `pingforit`；Java 包仍为 `nx.pingwheel`。

### 5.3 运行（人工/用户协助）

- `:fabric:runClient` 能进入主菜单并加载世界；日志无 SPI `ServiceConfigurationError`、无 mixin 初始化错误、无资源重载卡死。
- 游戏内至少验证：ping 按键、轮盘、marker HUD、方块 outline、设置界面、断开重连。
- 未执行/未观察的项目明确写“未验证”，不得用编译通过替代。

### 5.4 本轮已执行/未执行的验证

已执行：

- 用 `javac 25.0.4.1` + 26.3 Minecraft merged jar + Fabric Loader 0.20.1 + Fabric API 0.165.0+26.3 模块 jar，手工编译：
  - 变更的 common 类（SPI/network/CommonClient/GameContext/ResourceReloadListener）：exit 0；
  - `fabric/src/main/java`：exit 0；
  - `fabric/src/client/java`（以 main 输出为 classpath）：exit 0。
- 这只是 javac 类型检查，不是 Gradle/Loom 构建，也不是运行时验证。

未执行（环境阻塞）：

- `:common:compileJava`、`:fabric:compileJava`、`:fabric:compileClientJava`、`:fabric:processResources`、`:fabric:runClient`。
- `gradlew.bat --no-daemon --version` 因 `C:\Users\65653\.gradle\wrapper\dists\...\gradle-9.8.2-bin.zip.lck` 拒绝访问而 exit 1；需要在可写/联网环境由用户执行。

## 6. 风险与未确认项

1. **common 依赖泄漏**：`common` 用 ModDev/NeoForm，Fabric 用 Loom；`implementation(project(':common'))` 可能带入 NeoForm 依赖。用 `transitive = false` 规避，仍需编译验证。
2. **split 源集与 common 客户端类**：common 的客户端类在 common main jar 中；Fabric main 源集只要不引用它们即可。若 main 端误引用 `CommonClient`，会在服务端启动时触发客户端类加载。
3. **客户端发包 SPI 已拆分**：`IClientNetworkService` 负责 `sendToServer`，Fabric main 的 `PlatformNetworkServiceImpl` 只保留 `sendToClient`；仍需 Gradle split 编译/专用服务器启动验证。
4. **`LevelRenderEvents.COLLECT_SUBMITS` 的 camera/partialTick**：`BlockOutlinePass` 只用 pose/collector/camera；camera 从 `Minecraft.gameRenderer.mainCamera()` 取。若 26.3 方法名/生命周期不同，需按实际 API 调整。
5. **`HudElementRegistry` 唯一 ID/顺序**：SPI 每次注册生成唯一 ID；marker 层必须先于 toggle notice。顺序错误会导致通知被 marker 覆盖。
6. **`ResourceLoader` v1**：必须用 `net.fabricmc.fabric.api.resource.v1.ResourceLoader`；v0 会卡加载条。common 的 `ResourceReloadListener` 是 vanilla `PreparableReloadListener`，v1 可直接注册。
7. **`PingTextureState` 已接通**：`ResourceReloadListener` 在 apply 阶段写 `PingTextureState`，HUD 读同一状态；仍需资源包重载实测。
8. **`GameContext.lastWorld` 已清理**：`clearLastWorld()` 由 common `onLeaveServer()` 统一调用；三端不再各自注册 disconnect。
9. **`verifyModIdentity` refmap**：26.3 no-remap 无 refmap；F5 前不要跑聚合任务，F5 调整断言。
10. **可选集成 26.3 坐标未确认**：ModMenu/DH/VoiceChat/FTB/Sable 仍是旧坐标或缺失；F5 再处理，核心迁移不依赖它们。
11. **Gradle 沙箱**：本机 `%USERPROFILE%\.gradle` 的 wrapper 锁文件拒绝访问；本轮已用 `javac 25.0.4.1` + 26.3 工件做手工编译验证，但 `:common:compileJava`、`:fabric:compileJava`、`:fabric:compileClientJava`、`:fabric:runClient` 仍需用户在可写/联网环境执行。

## 7. 参考文件映射

| 参考文件 | 用途 | 本项目落点 |
| --- | --- | --- |
| `参考/fabric/build.gradle` | Loom split、mods、generateModMetadata、资源/类合并 | `fabric/build.gradle` |
| `参考/fabric/src/main/java/.../FabricMain.java` | 26.3 payload 注册/receiver | `fabric/src/main/java/.../FabricMain.java` |
| `参考/fabric/src/client/java/.../FabricClient.java` | 26.3 client receiver、v1 reload、命令、sound | `fabric/src/client/java/.../FabricClient.java`（去掉重复 HUD/outline 注册） |
| `参考/fabric/src/client/java/.../PlatformClientEventServiceImpl.java` | render frame 事件 | 本项目 client 实现（补 HUD/world submit） |
| `参考/fabric/src/client/java/.../PlatformClientContextServiceImpl.java` | `KeyMappingHelper` | 本项目 client 实现 |
| `参考/fabric/src/main/java/.../PlatformContextServiceImpl.java` | 无 key mapping 的主端实现 | 本项目 main 实现 |
| `参考/fabric/src/main/templates/fabric.mod.json` | 对象式 mixins、依赖范围 | 本项目模板（身份换成当前项目） |
| `参考/fabric/src/client/resources/...` | client services | 本项目 client services |

不要复制：

- `参考/fabric/src/client/java/nx/pingwheel/common/**`：common 已有 26.3 实现。
- `参考/fabric/src/client/resources/pingforit.mixins.json`：使用 common 的配置。
- `参考/deferred/**`：1.21.1 历史代码。

## 8. 进度记录

| 日期 | 阶段 | 提交 | 结果/证据 |
| --- | --- | --- | --- |
| 2026-11-09 | 方案建立 | `f010d3d` | 本文档创建；代码起点 `6373ee9` |
| 2026-11-09 | F0 | `eb8b17a` | common seam 清理 + `IClientNetworkService` 拆分；R1/R3 通过 |
| 2026-11-09 | F1/F2/F3 | `aba76a7` | Fabric 构建/源集、26.3 入口/网络、client SPI/HUD/world-submit、v1 ResourceLoader、services |
| 2026-11-09 | 审查 R1 | — | 2 blocker / 2 major / 2 minor；已修复 |
| 2026-11-09 | 审查 R2 | — | 1 major（F2-01 网络 SPI）/ 3 minor；F2-01 已修复 |
| 2026-11-09 | 审查 R3 | — | PASS；仅 1 个文档边界 minor |
| 2026-11-09 | F5（部分） | `ef8796c` | `verifyModIdentity` 不再强制 Fabric refmap；jar 打包/可选集成/运行仍未验证 |
| 2026-11-09 | 手工编译 | — | `javac 25.0.4.1`：common patch、Fabric main、Fabric client 均 exit 0（26.3 工件 classpath）；Gradle wrapper `--version` exit 1（`.gradle` 锁拒绝访问） |
| 2026-11-09 | 多格方块分析 | — | 根因：5fb5d76 删除 presentation 层；方案 A1（客户端 subject 展开）见 §9；待用户批准后执行 |
| 2026-11-09 | 多格方块原设计恢复 | — | 按 26.3 前原设计恢复 door/bed presentation subjects；outline/BER 整物消费；HUD anchor、chest/HUD center、双 marker 等问题移交 `改进方案.md` |


## 9. 已知问题：门/床/大箱子只标记单格（door/bed 已按原设计恢复）

> 状态：door/bed 已按 26.3 前原设计恢复 presentation subjects；chest/HUD 中心、双 marker、source 顺序等问题不在本次范围，见 [改进方案.md](改进方案.md)。
> 结论：这是 26.3 outline 重写时丢失 presentation subjects 层的回归，不是捕获或协议错误。

### 9.1 现象

- 对门、床、大箱子等跨格方块按 ping 时，HUD 的菱形/名称/距离只落在被命中的半格中心。
- 方块 outline 只框住被命中的半格，而不是整个物体。
- 大箱子另一半、床的另一半、门的另一半没有进入 outline/glow 工作项。

### 9.2 根因分层

| 层 | 现状 | 证据 |
| --- | --- | --- |
| Capture | `MinecraftTargetSnapshotFactory.blockSnapshot` 只取 `hitResult.getBlockPos()` + registry id；`Target.BlockTarget` 只有 dimension/pos/blockRegistryId | `MinecraftTargetSnapshotFactory.java:142-146`；`Target.java:15,58` |
| Server anchor | `validateBlock` 固定 `pos + 0.5`；`MarkerSnapshot` 只带一个 `MarkerAnchor`；range 也测该点 | `MinecraftAuthoritativeTargetValidator.java:247-261`；`MarkerSnapshot.java:20-26` |
| HUD | `MarkerView` 只使用 `marker.anchor()`；每个 marker 一个 view | `MarkerView.java:124,169,193,216`；`MarkerOverlayState.java:114` |
| Outline | `BlockOutlinePass` 每个 marker 只取一个 pos/state，`BlockModelGlowPass.trySubmit` 单 state/pos，`getShape` 一次，submit 一次 | `BlockOutlinePass.java:147-219`；`BlockModelGlowPass.java:94-104,180` |
| BER glow | `EntityBlockGlowState.entityBlockWinners` 只把 canonical pos 放进 map；BER dispatch 只按该 pos 查询 | `EntityBlockGlowState.java:288-312`；`BlockEntityRenderDispatcherMixin.java:183` |

历史证据：`5fb5d76`（`refactor: port common 26.3 outline and mixin set`）删除了 `BlockPresentation*`、`BlockRenderSubject`、`BlockOutlineSpec`、`VanillaDoorBlockPresentationResolver`、`VanillaBedBlockPresentationResolver` 等；当前 outline/glow 是重写后的单 state/pos 版本。旧 presentation 层只注册了 door/bed，**从未支持大箱子**；本次按用户要求只恢复 door/bed 原设计，chest resolver、HUD 整物中心等扩展不在本次范围，已移交 [改进方案.md](改进方案.md)。

### 9.3 文档契约

- `docs/architecture/rendering/presentation_subjects.md:46-59`：床解析为 foot+head，门解析为 lower+upper；损坏 pair 回退 direct subject。
- `docs/decisions/D0003-multipart-presentation-types.md:9-13,21-27,45-46`：multipart 解析为 presentation subjects，不重写 marker canonical target；“只渲染命中格”被明确拒绝。
- `docs/architecture/identity/target_model.md:33`：block identity 是 dimension + position + block type，BlockState 不属于 identity。
- 大箱子不在现有 beds/doors 条款中；新增 chest presentation 需要 docs owner/决策授权（见 §9.6）。

### 9.4 推荐方案 A1（客户端 presentation，不改协议/服务端）

> 执行说明：最终只落地 door/bed 的原设计恢复；chest/HUD 中心未纳入，相关问题见 [改进方案.md](改进方案.md)。

原则：canonical `Target.BlockTarget`、`MarkerAnchor`、packet、server range 都不变；只在客户端当前帧解析 presentation subjects。

1. 恢复/新增 common 客户端 presentation 数据层：
   - `BlockOutlineSpec`、`BlockPresentationRelation`、`BlockRenderSubject`、`BlockPresentationContext`、`BlockPresentationResolution`、`BlockPresentationResolver`、`BlockPresentationResolverRegistry`、`BlockPresentationSubjectValidation`。
   - 恢复 `BlockPresentation`/`BlockPresentationSuccessKey` 时，若 coverage 依赖已删除的 `EntityBlockGeometry*`，只保留 A1 需要的子集；不要恢复整个旧 geometry 框架。
2. 恢复/新增 resolver：
   - `VanillaDoorBlockPresentationResolver`：lower/upper；校验同 block、HALF 互补、FACING/HINGE/OPEN/POWERED 一致；失败回 direct。
   - `VanillaBedBlockPresentationResolver`：用 26.3 `AbstractBedBlock.getConnectedDirection` 定位 foot/head；顺序 foot→head；校验 PART 互补、FACING 一致；失败回 direct。
   - **新增 `VanillaChestBlockPresentationResolver`**：`ChestBlock.TYPE`；`SINGLE` → `context.directResolution()`（单箱只产生 direct subject，不要 handled-empty）；`LEFT`/`RIGHT` 用 `ChestBlock.getConnectedBlockPos(pos, state)` 找 partner；校验 partner 同 block（含 trapped chest 子类一致）、`TYPE == type.getOpposite()`、`FACING` 相同；稳定顺序 left→right；partner 缺失/未加载/属性不符 → direct。
3. `BlockOutlinePass`：
   - 每个 winner 解析一次 presentation；每个 subject 做 loaded/current 校验。
   - **A1 只做 subject 展开，不改变现有 source 顺序/短路语义**：保持当前 `BlockModelGlowPass` → `EntityBlockGlowState.isGlowing` → `getShape` 的顺序，避免把“presentation 修复”和“source outcome 重构”混在一起。当前顺序是否完全符合 `geometry_sources.md` 的“不短路”契约，是**既有问题**，A1 不宣称修复。
   - 如果后续要修 source 顺序，单列 A1-contract：按 `geometry_sources.md:59-63` 让 BER、baked model、optional source 按序运行且不短路，记录每个 subject/source 的 `RENDERED/EMPTY/FAILED`，只有全部未 RENDERED 才走 VoxelShape fallback；重复几何用 coverage 声明处理，不能用“第一个成功就停”。
   - 计数按 subject 而非 marker 聚合；增加 subject/source 级 DEBUG 计数，便于确认双画/漏画。
4. `EntityBlockGlowState`：对每个 `entity_block` winner 解析 presentation，把**每个 subject pos** 映射到同一 marker（canonical pos 作为 fallback）；BER mixin 的 `get(pos)` 因此能命中另一半。
5. HUD：新增 presentation center（subject block centers 的 AABB 中心；门 y+1、床/箱两格中点）。`MarkerView` 对 `Target.BlockTarget` 优先用该中心，解析失败/未加载回 `marker.anchor()`。
   - A1b（默认推荐）：图标/名称用显示中心，distance/scale 仍用 server anchor；range 语义不变，但图标与数字可能略不一致。
   - A1a：图标、名称、distance/scale 都用显示中心，视觉一致；server range 仍按命中半格，边缘可能有轻微视觉/范围差，需要产品/文档 owner 确认。
   - A1-only：HUD 保持 anchor，只修 outline/glow；零产品行为变化，但 HUD 仍停在半格。
6. 不改：`TargetKey`/winner、name resolver、client validation、server anchor/range、协议。名称对门/床/箱各半格相同；client validation 仍校验 captured pos；两半各 ping 仍是两个 canonical target（见 §9.6）。

### 9.5 方案 B（备选，不推荐）

- B1：服务端把 `MarkerAnchor` 改成 composite center，并可能改 range 语义；只解决 HUD 一半，outline 仍需客户端 presentation，且 range 行为变化。
- B2：协议增加 subject 列表（pos + expected registry + relation），服务端解析/广播；一致性强，但需要协议版本、服务端重复 multipart 逻辑、模组方块无法通用解析，并与 D0003“presentation 客户端解析”相悖。
- 结论：除非产品明确要求“服务端权威的整物 anchor/范围”，否则走 A1；B2 留协议演进。

### 9.6 需要产品/文档 owner 确认

1. **大箱子纳入 presentation**：现有 docs/D0003 只写 beds/doors；需要更新 `presentation_subjects.md`（及必要时 D0003），并按需同步 `testing/verification.md` 的覆盖/手动矩阵、`geometry_sources.md` 的 source 关系、`outline.md` 的渲染边界；均需 docs owner 授权。A1 实现前不能把“大箱子整体标记”说成已符合现有文档。
2. **HUD distance 用显示中心还是 anchor**：A1b 默认（图标/名称中心、distance/scale 用 anchor，range 语义不变）；A1a（全部用显示中心）需产品/文档 owner 确认。
3. **两半是否应合并为一个 marker**：按 D0003 canonical identity 不重写，门上下/床两头/箱左右的 ping 是不同 target key；如果产品要求“一个物体只保留一个 marker”，需要单独的 identity 归一化决策（B3），会改 `TargetKey`/winner/网络语义，不能混在 A1 里。

### 9.7 测试与验收

现有测试现状：`common/src/test/java/nx/pingwheel/common/client/outline` 共 47 个 `.java`；其中 **40 个直接引用已删除类**（A1 presentation 2 个、旧 outline/state/selection 12 个、旧 entity outline 6 个、EntityBlockGeometry/Flywheel/WorldAware/Create/Simulated/coverage 20 个），6 个不直接缺类但需 26.3 审阅，1 个 helper（`TestEntitySupport`）。A1 最小恢复集只有 `BlockPresentationResolverTest` + `BlockPresentationSubjectValidationTest`；`:common:test` 仍 disabled（`common/build.gradle:58`），恢复前必须先清理 40 个 orphan，不能直接启用整个测试套件。

A1 最小测试集：

- 恢复/适配 `BlockPresentationResolverTest`（door/bed 现有用例）与 `BlockPresentationSubjectValidationTest`（loaded/current + registry id 校验），并新增 chest 用例：SINGLE → direct；LEFT/RIGHT → 两 subject；TYPE 反向/FACING 一致；错误 TYPE/FACING/不同 block/未加载 → direct；顺序稳定。
- 新增 `BlockMultipartCenterTest`：门 y+1、床/箱两格中点、损坏 pair fallback。
- 新增 `BlockOutlineSubjectRenderTest`：composite → 2 个 subject 的 outline/geometry 工作项；direct → 1 个；subject 级计数与 loaded/current 校验；不把“至多一个 source draw”作为 A1 断言（source 顺序是既有行为，A1-contract 再测）。
- 新增 `EntityBlockGlowSubjectMapTest`：大箱子两半都在 winner map；`isGlowing(partnerPos)` true；缓存按 frame 重建。
- 新增 `MarkerViewBlockCenterTest`：door/bed/chest 显示中心；未加载/无 partner 回 anchor；A1a/A1b distance 行为。
- 手动矩阵（Fabric 26.3）：门开/关、床 foot/head、大箱子左/右/单箱、两半各 ping；菱形/名称/距离居中；两半 outline；大箱子两半 BER glow；range 边缘；跨 chunk；破坏/换状态；重连/多人；无重复画。

延后/删除：`EntityBlockGeometry*`、`EntityOutline*`、Create/Simulated/Flywheel contract tests 等旧框架测试不在 A1 范围；恢复前先分类，不要一次性启用。

### 9.8 不改的链路（明确）

- **Name resolver**：`MinecraftTargetNameResolver`/`ClientTargetNameResolver` 仍按 canonical pos 解析；门/床/箱各半格名称相同，不需要按 subject 改名。
- **TargetKey/winner**：`TargetKey.from` 仍用 canonical block pos；两半各 ping 是不同 TargetKey/marker。若产品要求“一个物体只保留一个 marker”，属于独立 identity 归一化决策，A1 不做。
- **Client validation**：`MinecraftClientTargetValidator` 仍校验 canonical pos 的 registry id/chunk；partner 损坏不拒绝 marker，presentation 回 direct。
- **Server anchor/range**：`MinecraftAuthoritativeTargetValidator.validateBlock` 仍用 `pos+0.5` 和同一 range 检查；A1 不改协议、snapshot 或服务端范围。

### 9.9 风险

- 无运行时验证；双箱两半 BER 是否都 dispatch、门/床/箱每半模型是否独立，需要游戏内确认。
- `BlockModelGlowPass` 逐 subject 提交可能造成某些模型整物提交时的重复 glow；A1 保留现有 source 顺序，不新增“至多一个 draw”的假设；需要 subject/source 计数与实测。若要按 `geometry_sources.md` 修不短路语义，单列 A1-contract 并加 coverage/去重。
- HUD 显示中心与 server anchor/range 可能轻微不一致；A1a 需要产品确认。
- A1 不恢复 coverage/Create 门；不要把它说成完整 D0003 实现。
- 性能：每帧对 block winner 做 neighbor 读取；按 marker id/state 做帧内缓存。

