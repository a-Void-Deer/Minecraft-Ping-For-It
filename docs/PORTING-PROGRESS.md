# 移植进度 · Ping For It → MC 26.3

> 每完成一个阶段更新本文件。施工方案见 [PORTING-PLAN.md](PORTING-PLAN.md)，摘要见 [../AGENTS.md](../AGENTS.md) §11。
> **最后更新：2026-11-03（目录重构：common/ + fabric/ 多模块）**

> **2026-11-03 · 目录重构**：单模块 → `common/` + `fabric/`（`forge/`、`neoforge/` 只有 README、未 include），`src/**` 全部 `git mv` 保历史，`tools/compile-check.*` 改三遍编译（common → fabric main → fabric client）+ 服务端安全 grep 门禁；规格 `docs/restructure/00-plan.md`。
>
> **2026-11-03 · 重构后实机回归 + 修复**：`gradlew clean build` 通过，jar **392 class / 464 entries / 0 重复条目 / 5 份 SPI / id=pingforit / version=0.4.2-pfi-beta1**（`fabric/build/libs`）。实机发现 **dev 里翻译/声音/ModMenu 图标全丢**（`pingforit.chat.pingmsg` 原始 key + `Missing sound for event: pingforit:ping`），根因：dev 的 mod 资源根只取 Loom 登记的源集输出，`:common` 的 resources 不被当 mod 资源扫描（发布 jar 里 assets 是齐的）。修复：`fabric/build.gradle` 的 `processResources` 从 `:common` 合并资源、`jar` 只合并 `:common` 的 `classesDirs`；详见 `AGENTS.md` §7.16。

## 阶段总览

| 阶段 | 内容 | 状态 | 完成时间 | 回滚点 |
|---|---|---|---|---|
| M0 | 基线冻结 | ✅ 完成 | 2026-10-28 | tag `pre-port` |
| M1 | 构建骨架（Fabric 单端） | ✅ **完成**（构建配置与产物命名均已修复；真实构建验证随 M3 一并通过） | 2026-10-28 | 不单独打 tag（并入 `port-M3`） |
| M2 | 源码导入与源集拆分 | ✅ 完成（源码提交 `c48c640`） | 2026-10-28 | tag `port-M2` → `8135ea0`（已打） |
| M3 | 编译期 API 迁移 + 渲染最小编译修复 | ✅ **完成**：`gradlew clean build` 通过，产物 `Ping-For-It-1.0.0-fabric-26.3.jar`（615 KB / 345 class / major 69） | 2026-10-29 | tag `port-M3` → `c09e808` |
| M4 | MVP 闭环（无描边） | ✅ **完成**：冒烟 22/22 配对、HUD 0 失败、正常退出；tag `port-M4`（annotated `5cb059c` → `a6cef45`） | 2026-10-29 | tag `port-M4` |
| M5 | 服务端权威完善 | ⬜ 待办 | — | — |
| M6 | 轮盘与 7 类型 | ✅ 完成（评审 `08-m6-review.md`） | 2026-10-29 | — |
| M6.5 | 标记 HUD 上游对齐 | ✅ **已恢复并重新验收**（tag `port-M6.5-restored`，2026-10-30 12:30–12:33 实机） | 2026-10-30 | tag `port-M6.5-restored` |
| M7 | 渲染重建 | ✅ **M7a 方块线框 + M7b 实体描边 + M7c C1 模型光晕 + C2 entity_block 真实体光晕全部完成并实机通过（2026-11-01）**；含 HUD 投影补 bob、ping 提示改回聊天框（`dde1be5`） | 2026-11-01 | — |
| M8 | 配置 / 命令 / UI | ✅ **完成（2026-11-01）**：**M8a** 完整分页设置界面 ✅、**M8b** 资源 v1 + 自定义贴图 ✅（**自定义资源包仍待补测**）、**M8c** toggle notice 第二层 HUD ✅，三者均实机通过；文档：`46`/`47`/`48`/`49` | 2026-11-01 | — |
| M9 | 可选集成 | ✅ **M9-1/2/3 全部实现 + 实机通过**：Mod Menu（2026-11-03）、VoiceChat（**2026-11-03 起每轮 `Loaded 1 plugin(s)`**）、Distant Horizons（2026-11-04，方案 A，58-mod 会话装 DH 3.3.4，视距外 370.8 m BLOCK 命中）；FTB Teams / Sable 平台强制跳过（`24` #17/#18） | 2026-11-04 | — |
| M10 | 验证与发布 | ⬜ 待办（**含合规：LICENSE 换 MIT + THIRD-PARTY-NOTICES**） | — | — |
| 事故恢复 | 目录重建 + 重新验证 | ✅ 完成 | 2026-10-30 | tag `restore-2026-10-29` |

> **合规待办（M10 处理，非 M3 缺陷）**：已实测 jar 内 `LICENSE_pingforit` 内容是工程自带的 **CC0 1.0** 模板，而上游 Ping For It 为 **MIT** → M10 需替换为上游 MIT 全文，并补 `THIRD-PARTY-NOTICES`（上游仓库已有 `THIRD-PARTY-NOTICES.md` 可作底本）。

---

## M0 · 基线冻结（2026-10-28）✅

**目标**：固定回滚点，确认工作区干净。

**做法**
- 确认 `git status` 干净（0 变更）
- 打 tag `pre-port`（指向 `06b71dc`，含分析文档与移植方案）

**验收**
- `git tag -l` 可见 `pre-port`
- 当时 HEAD：`06b71dc docs: add porting plan, feature reference and phase decisions`

**备注**：`Mod_Project/`（上游源码 + 分析报告）已被 .gitignore 排除，仅本地保留。

---

## M1 · 构建骨架（2026-10-28）✅

**目标**：把工程调整成「Fabric 单端 + 固定版本矩阵」，且样例代码仍能构建。

**改动**（提交 `2f8fd0a build: M1 skeleton - pin Loom 1.19.3, raise heap, artifact naming`）

| 文件 | 改动 | 原因 |
|---|---|---|
| `gradle.properties` | `loom_version` 1.19-SNAPSHOT → **1.19.3** | 钉死版本，避免 SNAPSHOT 漂移 |
| `gradle.properties` | `org.gradle.jvmargs` -Xmx1G → **-Xmx3G** | 首次全量 remap 内存不足风险 |
| `build.gradle` | 新增 `base { archivesName = "Ping-For-It" }`（`:6-8`） | 决策 D4 产物命名 |
| `build.gradle` | ① `4077eeb` 曾用 `tasks.matching { remapJar }.configureEach`（**无效，已删**）→ ② `5003f48` 改为 `jar { archiveClassifier = "fabric-26.3" }`（`:74-80`） | 期望产出 `Ping-For-It-1.0.0-fabric-26.3.jar`（no-remap 分支真正产出工件的任务就是 `jar`） |

**保持不变（本阶段有意不动）**
- `splitEnvironmentSourceSets()` + 样例代码（isharmla.mod）
- `generateModMetadata` 模板生成链路
- mod id 仍是 `ping-for-it`、包名仍是 `isharmla.mod`
  —— 决策 D2（id → `pingforit`）与 D3（包名 `nx.pingwheel`）**放到 M2 与源码导入一起做**，避免改两次。

### 验证结果（2026-10-28 19:xx）

| 项 | 结果 | 依据 |
|---|---|---|
| IDEA Gradle 同步 | ✅ 成功（IDE 侧确认，仓库内无直接证据） | Lead 报；`build.gradle:83-88` 已改为 lazy 配置并提交 `4077eeb` |
| 产物命名配置 | ✅ **已修复（`5003f48`）** | 3号 复核确认：插件 id 是 `net.fabricmc.fabric-loom`（**no-remap** 分支），`remapJar` 根本不会被创建，原 `tasks.matching{...}` 是静默 no-op（`docs/reviews/03-m1-build-review.md:16-23,49`）；`5003f48` 已删掉该段并把 classifier 放到 `jar` 任务（`build.gradle:74-80`），方案同 3号 建议的 A |
| 产物命名 `Ping-For-It-1.0.0-fabric-26.3.jar` | 🟡 **配置已就位，产物待验证** | `build.gradle:7` `archivesName` + `:80` `archiveClassifier` 已齐；`build/libs` 现有仍是修复前的 `ping-for-it-1.0.0.jar` / `-sources.jar`，尚未重新构建 |
| 一次完整 `clean build` | ⏳ **与 M2 合并执行** | 沙箱内 Gradle 不可用（wrapper 锁 + 子进程管道限制）；M2 导入完成后一并跑一次 |

**结论**：M1 的构建配置与产物命名**均已在代码层面修复**（`5003f48`：classifier 移到 `jar`，删除静默失效的 `tasks.matching{remapJar}`）。唯一未完成的是「跑一次真实构建看产物名」，该验证**与 M2 合并执行**；M1 不单独打 tag。

**待执行验证（终端）**
```
set JAVA_HOME=C:\Program Files\Java\jdk-25
gradlew -g D:\Gradle clean build
dir build\libs
```

**打 tag 条件**：`build/libs` 出现目标名产物且 jar 内 `fabric.mod.json` 的 `version` = `1.0.0`，才可打 `port-M1`。

---

## M2 · 源码导入与源集拆分（2026-10-28）✅

**状态**：✅ **完成**（2号 施工，提交 `c48c640 feat(M2): import Ping For It sources, split main/client source sets`）。
**独立复核**：3号 `docs/reviews/04-m2-structure-review.md` —— 7 个检查面 **6 项通过**；发现 **S1（严重，已修）** 与 **S2（M3 前置，已转 M3）**。

**范围**

| # | 工作项 | 说明 |
|---|---|---|
| 1 | 源码导入 | 上游 **298 个 Java**（拆分后 **main 164 / client 134**）；加 M2 新增 4 个类（`ProtocolLimits`、`IClientPlatformContextService`、`PlatformClientContextServiceImpl`、`ClientTeamContextHelper`）后为 **main 165 / client 137 = 302**（实测，`src/main/java`+`src/client/java`） |
| 2 | 资源导入 | **19 个资源文件**（main 16 + client 3）：8 个 lang、`sounds.json`、`ping.ogg`、`arrow.png`、`ping.png`、`icon.png`、`pingforit.mixins.json` 等 |
| 3 | 源集拆分 | 按 `PORTING-PLAN.md:56-60`：客户端子树 116 文件 + 3 个放错源集的类（ClientConfig / ClientCommandBuilder / Raycast）进 `src/client`；`TeamContextHandler` 按方法切；`IPlatformContextService` 拆 client 子接口 |
| 4 | 协议独立 | 抽出 `network/ProtocolLimits.MAX_CHANNEL_LENGTH`，解开 3 个网络包对 `ClientConfig` 的依赖 |
| 5 | Fabric 接入 | 8 个文件改名接入：`FabricMain`→`PingForIt`、`FabricClient`→`PingForItClient`，4 个 `*ServiceImpl` 按源集落位（D5 暂保留 ServiceLoader） |
| 6 | D2 · mod id | `ping-for-it` → **`pingforit`**（元数据、mixin 配置名、assets 命名空间、通道命名空间同步） |
| 7 | D3 · 包名 | **保留 `nx.pingwheel`**（298 个文件全在此包下，改包是纯噪声，末段再统一） |

**本阶段 8 项自检**

| # | 自检项 | 通过标准 |
|---|---|---|
| 1 | 导入完整性 | Java 合计 298；main / client 分别为 161 / 129，无遗漏、无重复 |
| 2 | 客户端归属 | 客户端子树 116 文件 + 3 个放错源集的类（ClientConfig / ClientCommandBuilder / Raycast）全部在 `src/client/java` |
| 3 | TeamContextHandler | `getSelfContext` 在 client、其余方法留 main，双方均无悬空引用 |
| 4 | 协议解耦 | `ProtocolLimits.MAX_CHANNEL_LENGTH` 存在，3 个网络包不再 import `ClientConfig` |
| 5 | 平台接口拆分 | `IPlatformContextService` 的 KeyMapping 方法移入 client 子接口，main 侧无 `KeyMapping` 引用 |
| 6 | Fabric 接入 | `PingForIt` / `PingForItClient` 与 4 个 ServiceImpl 落位正确，`META-INF/services` 四件套齐全 |
| 7 | 身份一致（D2） | 模组 id / mixins 文件名 / assets 目录 / 通道命名空间统一为 `pingforit`，`grep -r "ping-for-it" src` 为 0 |
| 8 | grep 门禁 | `src/main` 下 `grep -rn "net\.minecraft\.client\|com\.mojang\.blaze3d"` 命中 **0** |

**独立复核结果（3号，7 个检查面 / 6 通过 + 1 严重已修）**

| # | 检查面 | 结果 |
|---|---|---|
| 1 | 源集正确性（main 内 client/blaze3d 引用、跨源集 FQN） | ✅ 0 命中 |
| 2 | 拆分三特例（TeamContextHandler / 三整类 / ProtocolLimits） | ✅ 全部正确 |
| 3 | 平台层（接口拆分 + 5 个 services 一一对应） | ✅ 通过（建议 N1） |
| 4 | 元数据（id / 4 entrypoint / mixin package / icon） | ⚠️ 发现 **S1** → 已修复（见下） |
| 5 | 资源（12 个 assets 文件 SHA-256 与上游逐一相同 + icon） | ✅ 逐字节一致 |
| 6 | 残留与重复（`isharmla`/`ping-for-it`/同 FQN/旧样例类） | ✅ 0；`PingForIt` 31 处为上游自身命名 |
| 7 | build.gradle（`loom.mods` 键 / 无 datagen / classifier 在 `jar` / 无 remapJar） | ✅ 通过 |

**S1（严重，已修复）**：`templates/fabric.mod.json` 原以**字符串**声明 mixins（`"mixins": ["pingforit.mixins.json"]`），而该文件位于 `src/client/resources`；Fabric Loader 对字符串形式一律按 **universal** 处理（loader 0.20.1 的 `V1ModMetadataParser`：*"All mixin configs specified via string are assumed to be universal"*），dev `runServer` 的 classpath 无该资源 → `FabricMixinBootstrap` 抛 `RuntimeException`，**启动即失败**（生产 jar 不受影响，Loom 会合并 client 资源）。
→ **已修**：改为对象形式 `{ "config": "pingforit.mixins.json", "environment": "client" }`（`templates/fabric.mod.json:35-40`，实测已生效）。

**S2（转 M3，第一批编译错误来源）**：`build.gradle` 尚未声明任何第三方 `compileOnly` 依赖，但导入的源码仍在引用：
`lombok`（8 文件 / 16 处，D7 说移除但 M2 未删）、`com.terraformersmc.modmenu`（1）、`de.maxhenkel.voicechat`（1）、`com.seibel.distanthorizons`（1）、`dev.ftb.mods.ftbteams`（1）、`dev.ryanhcode.sable`（1）。
→ M3 开头一次性补齐 `compileOnly`，或按 D9 对暂不移植的集成明确跳过；**这是 M3 的第一批编译错误**，不是 M2 的结构问题。

**顺带修正**：`settings.gradle:13` `rootProject.name` 已改为 `pingforit`（原 `ping-for-it`，对应 04 报告建议 N3）。

**预期：本阶段结束时工程编不过属正常（已验证）**

- 导入的是 1.21.1 源码，而工程是 26.3；`ResourceLocation → Identifier`、`FastColor → ARGB`、`RenderType` 换包等 **API 迁移安排在 M3**（`PORTING-PLAN.md:65-74`）。
- M2 的验收**不是「能编译」**，而是「**文件归属正确 + 8 项自检全过 + grep 门禁为 0**」，这三点已全部达成；编译错误清单本身就是 M3 的输入。

**回滚点**：tag **`port-M2` → `8135ea0`**（M2 进度文档提交，已存在；其父提交 `c48c640` 为 M2 源码提交）。回退顺序：`8135ea0`（M2 完成态）→ `pre-port`（移植前）。

---

## M3 · 编译期 API 迁移 + 渲染最小编译修复（2026-10-28 → 10-29 构建通过）✅

**状态**：✅ **完成**。本地 javac 校验：`main` **0 error / 263 class**、`client` **0 error / 82 class**；随后用户终端 `gradlew clean build` **通过**（详见文末「构建验收」）。
**tag**：`port-M3` → `c09e808`。

M3 拆成两轮，避免「渲染层大改」把机械改名拖成长期红。

### M3a · 去阻塞 + 机械改名（提交 `bed57c3`）✅

| # | 工作项 | 内容 | 依据 |
|---|---|---|---|
| 1 | 移除 Lombok | 8 个文件手写 getter/setter（D7）；删除 `annotationProcessor` 依赖意图 | `PORTING-PLAN.md:73`；04 报告 S2 |
| 2 | 补 annotations | `compileOnly "org.jetbrains:annotations:<ver>"`，覆盖 16 个引用 `@NotNull/@Nullable` 的文件 | 04 报告 S2 |
| 3 | 桩化可选集成 | DH / VoiceChat / FTB Teams / Sable 的源码保留但**隔离成桩**（不参与编译或退化为空实现）；**ModMenu 移到 deferred/**，不参与 M3 编译 | 04 报告 S2；D9 |
| 4 | 机械改名 | `ResourceLocation → Identifier`（35 文件）、`FastColor → ARGB`（2）、`LightTexture → Lightmap`（2）、`RenderType` 换包（4）、`PayloadTypeRegistry.playC2S/playS2C → serverboundPlay/clientboundPlay`（1）、`KeyBindingHelper → KeyMappingHelper`（1）、`Minecraft.setScreen → setScreenAndShow`（1）、`ClipContext` 换包（2） | `PORTING-PLAN.md:65-74`、`05-api-delta.md` |

### M3b · 渲染层最小编译修复 + 子系统搬迁（提交 `d8d610f`，121 文件）✅

只做「能编译」所需的最小修复，**不重建渲染管线**；无法编译的渲染/UI 子系统整体搬到 `deferred/`（保留原目录结构，便于 M7 原样取回）。

**搬迁清单（M3b 当时 `deferred/` 共 92 个 java；现值 95，见下方 2026-11-02 注）**

| 目录 | 文件数 | 内容 |
|---|---|---|
| `deferred/client/outline/` | 66 | 方块/实体描边全部实现（26.3 删除了相关渲染原语） |
| `deferred/render/` | 10 | 世界内 HUD/箭头/位置标记渲染 |
| `deferred/screen/` | 8 | `SettingsScreen` 及布局/导航模型 |
| `deferred/mixin/` | 5 | 依赖上述渲染/UI 的 mixin |
| `deferred/fabric/integration/` | 1 | Fabric 侧适配器 |
| `deferred/resource/` | 1 | `ResourceReloadListener` |
| ~~`deferred/modmenu/`~~ | ~~1~~ → **0** | `ModMenuIntegration`（M3a 移入；**M9-1 已迁回 `src/client/java/nx/pingwheel/fabric/integration/`，该目录已删除**） |

> **2026-11-02 现状（M9-1）**：`deferred/` 实测 **95** java（96 − `modmenu/ModMenuIntegration` 1 个）。上表是 **M3b 当时**的快照，不要当现值。

> 本次 M3b 为 **86 个重命名 + 5 个新增门面/占位类**（`BlockDisplayPolicy`、`BlockDisplayWhitelist`、`ExternalBlockOutlineTransform`、`WorldRenderContext`、`SettingsScreen` 的 deferred 版本）；M3a 已移入 `ModMenuIntegration` 1 个，合计 92。源码集现状：`src/main/java` **164**、`src/client/java` **51**。

> 其中 `BlockDisplayPolicy` / `BlockDisplayWhitelist` 当时只是占位（逻辑缺失），后按 10/26 反编译构建**恢复**（`docs/reviews/16-decompiled-diff.md:45-46` 记为「完全收敛」）。

**新增本地校验工具**（4号 负责维护）

- `tools/compile-check.sh` / `tools/compile-check.ps1`：不启动 Gradle，直接用 `javac` 分别校验 main / client 两个源集，输出 error 数与 class 数。
- 用途：沙箱内快速回归；真实产物与 remap/打包仍须 `gradlew build`。

**顺带修复 S1（严重）**：`ClientConfig` 在移除 Lombok 时丢失 `hashCode()`，导致按值查找/缓存失效、配置保存静默不生效 → 已手写 `equals`/`hashCode`（`src/client/java/nx/pingwheel/common/config/ClientConfig.java`），并已复核。

### 26.3 API 迁移清单（M3 实际改动，供后续查阅）

| 旧 API（1.21.1） | 新 API（26.3） |
|---|---|
| `Minecraft#screen` 字段访问 | `Minecraft#screen()`（方法化） |
| `GameProfile#getName()` | `GameProfile#name()` |
| `ServerPlayer#getLevel()` | `ServerPlayer#level()` |
| `InputConstants` 按键读取 | 26.3 对应按键 API |
| `Component.Serializer` 一类序列化 | `ComponentSerialization` |
| `Team#getColor()` | `Team#getColor()` → `Optional<TeamColor>`，取色用 `.rgb()` |
| `Item#getDescriptionId()` | 26.3 对应方法 |
| `PayloadTypeRegistry.playC2S/playS2C` | `serverboundPlay/clientboundPlay` |
| `ResourceLocation` | `Identifier` |
| `FastColor` / `LightTexture` / `RenderType` / `ClipContext` | 各自的 26.3 新包/新类型 |
| `KeyBindingHelper` | `KeyMappingHelper` |
| `Minecraft.setScreen` | `setScreenAndShow` |

### 收尾与验收

**构建验收（2026-10-29，用户终端执行 `gradlew clean build`）✅**

| 项 | 结果 | 实测证据 |
|---|---|---|
| 构建 | ✅ `BUILD SUCCESSFUL` | — |
| 产物 | `build/libs/Ping-For-It-1.0.0-fabric-26.3.jar`，**615 091 B**（+ `-sources.jar` 345 103 B） | 目录实测 |
| 类 | **345 个 `.class`**，**major 69**（Java 25） | 解包统计 |
| 元数据 | `id=pingforit`、`version=1.0.0` | jar 内 `fabric.mod.json` |
| mixins | 2 项声明，均为对象形式 `{config, environment: client}` | jar 内 `fabric.mod.json` |
| 平台层 | `META-INF/services/` 下 **5 个** SPI 文件 | 解包统计 |
| 资源 | `assets/**` 12 个文件 + `icon.png`；`LICENSE_pingforit` 已打入 | 解包统计 |
| tag | **`port-M3` → `c09e808`** | `git tag -l` |

- ✅ 其余验收：`grep -rn "ResourceLocation" src` 无命中；`grep -rn "import lombok" src` 无命中；`build.gradle` 已无 Lombok 依赖。
- 回滚点：`port-M2`（`8135ea0`）→ 当前 `port-M3`（`c09e808`）。

---

## M4 · MVP 闭环（2026-10-29）✅

**目标**：证明「按键 → 捕获 → C2S → 服务端受理 → S2C 广播 → 客户端 HUD 打点」这条最小链路通。**不做描边**（描边整体在 M7）。

**已实现（提交 `313efb5 feat(M4): minimal HUD marker loop with send/receive logging`，5 文件 +219/−5）**

| 项 | 实现 | 证据 |
|---|---|---|
| HUD 输出 | `HudElementRegistry.addLast(...)` 注册一个 HUD 元素，用 26.3 的 `GuiGraphicsExtractor` 状态提取管线绘制标记点 | `src/client/java/nx/pingwheel/fabric/FabricClient.java:56`、`common/client/hud/MarkerHudRenderer.java:54` |
| 世界→屏幕投影 | 用 `Camera` 的 view-rotation-projection 把世界坐标投到屏幕，离屏/背后剔除 | `MarkerHudRenderer`（100 行） |
| 状态复用 | 直接复用既有 `MarkerOverlayState` / `MarkerView`，未新造 marker 存储 | `CommonClient.onRenderHud(...)`（`:338`） |
| 发送日志 | `LOGGER.info("send ping: requestId=%d kind=%s pingType=%s")` | `client/ClientPingActionDispatcher.java:127` |
| 接收日志 | `LOGGER.info("marker created: markerId=%d kind=%s pingType=%s at=(...)")` | `client/ClientPingRuntime.java:1016` |
| 编译验证 | 本地 javac 两遍 0 error（client 源集 **83** class） | `tools/compile-check.*` |

**冒烟结果（2026-10-29 20:11–20:15，用户 `runClient` 实录）✅**

| 项 | 结果 | 证据（`run/logs/latest.log`） |
|---|---|---|
| 启动 | 20:11:23 起，20:11:34 `Sound engine started` + **12 个 atlas** 创建完成 | `:112`（sound）、`:113-124`（12 atlas） |
| 资源重载 | 正常完成，不再卡加载条（移除 v0 注册后 2 秒内） | 见下「阶段发现」 |
| 进世界 | 20:13:32 集成服务器启动，20:13:34 玩家登录 | `:136`、`:156` 附近 |
| 发送 | **22 条** `send ping:`，`requestId=0..21` | `:177-198` |
| 接收 | **22 条** `marker created:`，`markerId=0..21` | `:233-254` |
| 配对 | requestId ↔ markerId **1:1 全部配对**，全为 `kind=BLOCK pingType=attention` | 上两段逐行比对 |
| HUD | 日志中 **0 条** HUD 失败/`pass failed` 记录（`latest.log` 与 `debug.log` 均无）；标记点可见性由人工冒烟确认 | 全文检索 |
| 退出 | 20:14:59 `Stopping!`，无 crash、无 `crash-reports/` | `:268` |

**tag**：`port-M4`（annotated，`5cb059c` → 提交 **`a6cef45`**，注记为「M4: minimal HUD ping loop verified (22/22 send->created pairs in single-player)」）。

### M4 阶段发现：资源重载 v0 注册会让 26.3 启动卡死（重要）

| 项 | 内容 |
|---|---|
| 现象 | 上一轮 `runClient` 停在加载条，**无异常、无 crash、无超时日志**；不是渲染或 mixin 问题 |
| 根因 | `fabric-resource-loader-v0` 的 `ResourceManagerHelper.get(...).registerReloadListener(new IdentifiableResourceReloadListener(){...})` 在 26.3 下**资源重载永远完不成**（该 API 已 `@Deprecated`，语义与新管线不兼容） |
| 定位手法 | 二分：先摘掉该注册 → 启动 2 秒内完成重载 → 回填即复现 |
| 临时处置 | 提交 `a6cef45` **摘除**该注册，`FabricClient.java:101-106` 留 TODO(M8) 注明原因与替代 API |
| 永久修复 | M8 用 v1 恢复：`ResourceLoader.get(PackType).registerReloadListener(...)` / `SimpleReloadListener<T>`（详见 M8 待办 5） |
| 影响面 | **这是功能阻断级**，不是「消除 deprecation note」这种整洁性问题；已知坑已登记到 `AGENTS.md §7-10` 与 `PORTING-PLAN.md` M8 |

**回滚点**：`port-M3`（`c09e808`）。

## M6.5 · 标记 HUD 上游对齐（2026-10-29）✅

**目标**：把 M4 的「实心方块占位」对齐上游 1.21.1 的标记视觉，并让轮盘回归上游结构；不动世界空间渲染（描边仍留 M7）。

**提交与 tag**

| 项 | 值 |
|---|---|
| 主提交 | `d6015d7 feat(M6.5): port upstream marker HUD geometry, labels and edge arrow` |
| 文档提交 | `5884277 docs: refresh line references in 11-m6.5-review.md after the D1/D2 fixes` |
| tag（原） | `port-M6.5`（annotated `78c944a`）——随 `.git` 丢失 |
| tag（恢复后） | **`port-M6.5-restored`**（2026-10-30 实机验收通过） |
| 新增文件 | `src/client/java/nx/pingwheel/common/client/hud/MarkerLabelRenderer.java`（192 行）<br>`src/main/java/nx/pingwheel/common/interaction/state/WheelSelectionDiagnostics.java`（23 行） |

**交付项**

| # | 交付 | 上游依据 | 当前实现 |
|---|---|---|---|
| A | 标记几何 | 5×5 方块绕 Z 轴 45° 旋转成菱形（`DrawContext.java:76-82`） | `MarkerHudRenderer.java:87-92`（`ICON_SIZE=5` + pose 旋转 + 居中平移） |
| B | 物品图标 | `renderItem(stack,-8,-8)` 16×16（`DrawContext.java:72-74`） | `MarkerHudRenderer` 用 `item(...)` 16×16 **并补 `itemDecorations(...)`**（数量/耐久条，`GuiGraphicsExtractor:914-926`） |
| C | 文本层 | 距离 `-1.5`、目标名 `-2.75`、玩家名 `1.75`、8×8 头颅、黑色半透明底（`PingLocationRenderer.java:40-57`、`DrawContext.java:33-62`） | `MarkerLabelRenderer.java:62-71`（偏移常量）、`:167-189`（底块 + 文本 + `PlayerFaceExtractor` 头颅） |
| D | 屏外/背后箭头 | safe-zone 内缩 + 边相交 + 0.25× 缩放（`DirectionIndicatorRenderer.java:18-83`） | `MarkerHudRenderer.java:102-113`（64×64 `arrow.png` → 10px 四边形 → **最终 2.5px**，与上游一致） |
| E | 层级 + 轮盘回归 | z 阶梯 `-views*16` + 逐条 `+16`（`OverlayRenderer.java:29-45`）；轮盘图标 6×6 染色 `ping.png`（`WheelOverlayRenderer.java:73,234-299`） | 用**绘制顺序**等价 z 阶梯（`MarkerHudRenderer.java:64-71`，26.3 `fill` 无 z 参数）；轮盘删除自加楔形填充、图标改回 6×6（`WheelHudRenderer.java:89,466`） |

**两轮审查结论**

- 第一轮（`docs/reviews/11-m6.5-review.md` 初版）：D1 = 菱形尺寸/旋转与上游一致 ✅；D2 = 文本三行偏移与底块一致 ✅；指出 D3 物品图标缺 `itemDecorations`、D4 箭头缩放推导需复核。
- 第二轮（`5884277` 后刷新行号）：D3/D4 已修；补充 `wheel select` 诊断字段 `scale=/mouse=/sector=` 与命中测试同源（`getScaledXPos`，`WheelHudRenderer.java:269-272`）。
- 尺寸专项：`docs/reviews/10-m6-marker-size-verify.md` 用 d=1/4/10/64 的推导表确认「近大远小、d≳10 后恒定」是上游行为，不是 bug。

> 另：M6.5 为轮盘 select 诊断新增 `WheelSelectionDiagnostics`（`ClientPingRuntime.java:1281-1296`），仅日志用途、不影响渲染。

**已知微差（登记，不在 M6.5 修）**

| # | 微差 | 归属 |
|---|---|---|
| D6 | α 通道 float vs double 的极小舍入差，视觉不可见 | 不修（记录在 11 号评审） |
| D7 | 自定义资源包贴图分支（`hasCustomTexture()` → 12px `ping.png`）当前只保留判定与注释，未真正走贴图路径 | **M8**（随资源重载 v1 一起做） |
| D8 | 世界空间描边 / 实体发光 / 虚拟方块展示 | **M7** |

**回滚点**：当前 **`port-M6.5-restored`**（2026-10-30 实机验收通过）；历史基线 `port-M4`（`5cb059c`）/ 原 `port-M6.5`（`78c944a`，已随 `.git` 丢失）。

### 2026-10-30 恢复后验收 ✅（tag `port-M6.5-restored`）

> 日志：`run/logs/2026-10-30-7.log.gz`（12:30:37 启动 → 12:33:04 `Stopping!`）。事故重建后的源码 + 重新构建的 jar 实机跑通。

| 验收项 | 结果 |
|---|---|
| 发送 / 接收 | **37 条 `send ping`**、**36 条 `marker created`**（差 1，无 reject / cancel / 异常记录） |
| 轮盘一致性 | **8 次 `wheel select`，8/8 与随后 `send ping` 的 `pingType` 完全一致** |
| ping 类型覆盖 | `attention` / `danger` / `go_to` / **`loot`**（证明按 target type 分桶生效） |
| 模组相关 ERROR | **0**（会话内 5 条 ERROR 均为 Realms / Minecraft Services 联网噪声） |
| HUD 失败 | **0** 条 `pass failed` / HUD 失败记录 |
| 退出 | 12:33:04 正常 `Stopping!`，无 crash |

结论：M6.5 的视觉对齐（菱形 / 物品图标 / 三段文本 / 头颅 / 2.5px 箭头）在事故恢复后的代码上**重新验证通过**；`port-M6.5-restored` 为当前可回滚基线。
---

## M7-prep · 渲染管线可行性（2026-10-30）🟡

**目标**：在正式重写渲染后端前，用最小代价钉死 E1/E2/E3/E6（编号与含义沿用 `AGENTS.md §9.1`）。

**分支**：`exp/vanilla-lines-m7a`（基线 `b686fac`；初始提交 **`f3fe654`**「draw the real block outline with the built-in line pipelines」，**+210 行 / 2 文件**：新增 `src/client/java/nx/pingwheel/common/client/outline/VanillaLinesBlockOutline.java` 207 行 + `FabricClient` 3 行接线）。**该分支只是「内置管线可行性」试验，不改变项目任务流程**——流程仍按 `AGENTS.md §9.1`：分析需求 → 实现 → 审查 → 有问题修复后重新分析审查 → 再进入下一步；**完成并汇报后，等用户允许再进 M7 本体**。

**试验内容**：用内置 `RenderTypes.linesTranslucent()` / `linesTranslucentNoDepthWrite()` + `SubmitNodeCollector.submitShapeOutline`，给真实方块目标画 **3.75px、ping 色**的 `VoxelShape` 线框；`vanilla-outline.txt` 选 mode 1/2/3（缺省 1）。

**结论（按 `AGENTS.md §9.1` 的 E 编号）**

| # | 结论 | 状态/证据 |
|---|---|---|
| **E1** | 内置线管线**默认被墙遮挡**（`DepthStencilState.DEFAULT`，深度测试开）；且 26.3 `submitShapeOutline` 按 alpha 分桶——**alpha=255 一律进 `solid` 相位、`afterTerrain` 被忽略** → 原「afterTerrain 四组合」实际只有「depth write 开/关」两档（2026-10-30 分析修正；`AGENTS.md §9.1` E1 的旧表述待更正） | 🟡 源码/字节码分析结论，**未实机**；依据 `docs/reviews/24-fidelity-constraint-audit.md`、`docs/reviews/21-m7-prep-spike.md` |
| **E2** | **✅ 实机通过（2026-10-30，mode 4）**：不引 AW 的 **mixin 访问器路线**——`RenderTypeAccessor` + `RenderPipelinesAccessor`（`@Invoker` 打开 `RenderType.create` / `RenderPipelines.register`）+ `PingOutlineRenderType` 自定义无深度管线 + `SubmitNodeCollectionMixin` 分桶路由；22:03–22:09 实机：`ping outline pipeline registered` + `mode=4`、**28 send / 28 created**、0 pingforit WARN/ERROR、0 `pass failed`/`disabled`、0 `falling back`、正常 `Stopping!`；**用户确认视觉正常**（穿墙、水/玻璃之上、同目标只有 winner 一条） | ✅ 实机通过 + `docs/reviews/32-log-analysis-2026-10-30-mode4.md`；`compile-check` main **266** / client **92**，0 error；**OIT 未覆盖**（`improvedTransparency=false`），列为可选补测（非阻塞） |
| **E3** | **已记录**：gizmo `onTop=true` 仅作对照——线宽/混合由原版决定，**不能替代**正式实现 | ✅ 记录在案；`docs/reviews/21-m7-prep-spike.md` |
| **E6** | **已完成**：`deferred/client/outline` 试编译普查 | ✅ `docs/reviews/20-m7-prep-e6.md` |

> **日志侧证据**：E1/E2 的实机日志分析见 `docs/reviews/30-log-analysis-2026-10-30-user-test.md`（19:18 用户测试：24 send ↔ 24 created、`requestId/markerId 0–23` 配对、`submitted` 与 7s 存活数 13/13 一致、**0 `pass failed`**；**当时跑的是 `f3fe654` 旧代码**，不含 E2 改动）。

**按 §9.1 工作流的执行记录**：分析（`docs/reviews/25-review-m7a-experiment.md`、`26-m7a-upstream-fidelity-and-conflicts.md`）→ 实现（`f3fe654`）→ 审查（`25`、`27-review-m7a-fixes.md`）→ 修复（3 个 S2：空形状不画整块、同目标 winner 去重、异常真禁用）→ 复审（`27` 通过）→ 汇报并等许可；同一流程在 mode 4 试验上又走了一遍（28 首审、29 复审）。

**当前基线**：分支 `exp/vanilla-lines-m7a`，基线 `f3fe654`；**E2 改动已提交**（提交号见 `git log`，待 Lead 补记）。`compile-check` main **266** / client **92**，0 error。备份分支/task-4/task-32 已清理。**E2 尚未实机验证**。

**下一步**：M7a 已完成（见下一节）；后续 **M7b → M7c → M8 → M9 → M10**，等用户许可逐段推进。

---

## M7a · 方块描边正式化（2026-10-31）✅

**目标**：把 E2 实验转正式——**去实验化**、自定义无深度管线 + `afterTerrain` 路由**默认生效**（不需要任何开关文件）。

**代码**

| 项 | 内容 | 证据 |
|---|---|---|
| 删除 | `VanillaLinesBlockOutline`、`vanilla-outline.txt` 读取、mode 1–4 分支、`OutlineExperiment` 注册 | 工作树 `D src/client/.../VanillaLinesBlockOutline.java`；`FabricClient` 无残留引用 |
| 新增 | `BlockOutlinePass`（正式 pass）、`OutlineRoutingState`（routing 一次性诊断） | `src/client/java/nx/pingwheel/common/client/outline/BlockOutlinePass.java`、`OutlineRoutingState.java` |
| 保留/修改 | `PingOutlineRenderType`（自定义管线）、`SubmitNodeCollectionMixin`（增加 `markRouted` 上报） | `PingOutlineRenderType.java:80-100`；`SubmitNodeCollectionMixin.java`（`OutlineRoutingState.markRouted()`） |
| 注册 | `FabricClient` **只注册** `BlockOutlinePass` | `src/client/java/nx/pingwheel/fabric/FabricClient.java:96` |

**语义（与上游对齐）**：winner 投影（`BlockOutlinePass.java:144`）+ targetType 仅 `block`/`entity_block`（`:145`）+ 维度（`:149`）+ `isLoaded`（`:155`）+ registryId（`:165`）+ 空形状跳过（`:171`，无满方块兜底）+ `CollisionContext.of(cameraEntity)`（`:139-141`）+ 颜色 `0xFF000000 | pingType.outlineColor()`（`:175-177`，回落白）+ **3.75px**（`:63`）+ 恒 `afterTerrain=true`（`:186`）。

**失败策略**：① 管线创建失败 → `disabled=true` + **WARN 一次**，**不回退内置线**（内置线不穿墙，会改变上游效果）；② 运行期异常 → `disabled=true` + WARN 一次；③ routing mixin（`require=0`）120 帧内从未拦截 → **WARN 一次并继续绘制**（不禁用，`BlockOutlinePass.java:73,225-237`）。

**实机验收（2026-10-31，用户确认通过）**：穿墙/地下可见、压在水/玻璃之上、同一方块只画 winner、HUD/轮盘正常、退出无 crash。**OIT 未验证**（用户要求本轮不考虑）。

**证据**：`docs/reviews/33-m7a-spec.md`（规格冻结）、`docs/reviews/34-review-m7a-block-outline.md`（含复审）、`compile-check` main **266** / client **93**，0 error。

**线宽观感**：远处方块变小而线宽固定 3.75px，观感上相对变粗——这是**上游固有表现**（1.21.1 同样固定 3.75），按保真铁律**保持不改**；候选改进记录在根目录 `改进方案.md`（状态：暂不实施）。

**已知限制**：① `disabled` 是 per-session，join/leave 不重置；② external block（Sable）未覆盖（M7c）；③ OIT 未验证；④ 失败路径会打两条 WARN（`PingOutlineRenderType.java:109` + `BlockOutlinePass.java:96/200`）。

> 26.3 管线多一个逐帧回调点（`LevelExtractionEvents.END_EXTRACTION`，`PlatformClientEventServiceImpl.java:29-35`）；与上游事件序列不同，但无视觉影响。

**下一步**：M7b 已完成（见下一节）；后续 **M7c（模型光晕）→ M8 → M9 → M10**，等用户许可逐段推进。

---
---

## M7b · 实体描边（2026-10-31）✅

**目标**：让被 ping 的实体/玩家出现与上游一致的**原版发光轮廓**（穿墙可见、ping 类型色）。

**机制（复用原版发光通道，不自绘轮廓）**

| 环节 | 实现 | 证据 |
|---|---|---|
| 每帧快照 | `LevelExtractorMixin.extract` HEAD → `EntityOutlineState.prepare(store, dimension)`（严格早于任何 `EntityRenderer.extractRenderState`） | `src/client/java/nx/pingwheel/common/mixin/LevelExtractorMixin.java:30-48` |
| 颜色注入 | `EntityRendererMixin.extractRenderState` **TAIL** 覆盖 `state.outlineColor`（原版已写入 `glowing ? teamColor : NO_OUTLINE`，TAIL 覆盖等价上游的 `getTeamColor` 重定向） | `mixin/EntityRendererMixin.java:34-59` |
| 渲染 | 26.3 的 `outline` phase + `LevelRenderer.executeOutline/blitEntityOutline`（原版后处理）自动接管；无需自建 buffer | `docs/reviews/35-m7b-spec.md` §2 |

**选择规则**（`client/outline/EntityOutlineSelection.java`）：只取 `Target.EntityTarget` 且维度匹配（`:53-59`）；targetType 仅 `entity` / `dropped_item`（`:81-83`）；同一 locator（canonical 后）保留**最大 marker id**（`:61-65`）；颜色 `0xFF000000 | pingType.outlineColor()`、未知类型回落不透明白（`:85-89`）。

**不变量**：未命中 ping 的实体**完全不改变**（`colorFor == null` 时不写 `outlineColor`，`EntityRendererMixin.java:55-59`），vanilla 发光/队伍色保持原样。

**失败策略**：`prepare` 异常 → `disabled = true` + 清空快照 + WARN 一次（`EntityOutlineState.java:76-85`）；两个 mixin 均 `require = 0`，未织入时由 `EntityOutlineDiagnostics` 记录、`EntityOutlineState` 在连续 60 次含描边但无提取回调后 WARN 一次且**不禁用**（`:116-125`）。

**实机验收（2026-10-31）**：用户反馈**功能正常**；日志侧证据 `docs/reviews/37-log-analysis-2026-10-31-m7ab.md` —— 16 send/16 created（6 BLOCK / 10 ENTITY，attention 13/danger 2/loot 1）、`entity outline pass active (native glow channel via outlineColor)` 仅一次（17:15:09）、0 pingforit WARN/ERROR、0 disabled/pass failed、正常 `Stopping!`。

**证据**：`docs/reviews/35-m7b-spec.md`（规格）、`36-review-m7b-entity-outline.md`（复审）、`37-log-analysis-2026-10-31-m7ab.md`（日志）；`compile-check` main **266** / client **98**，0 error。

**已知限制**：① 装备/皮肤层的轮廓颜色未单独确认（`outlineColor` 只传给主模型）；② targetType 白名单比上游窄（仅 `entity`/`dropped_item`，见复审 S4-1，当前 catalog 下等价）；③ `disabled` 为 per-session，join/leave 不重置；④ 「hook 未生效」诊断存在二义（有描边但 60 帧内无可见实体时会误报，复审 S3-1）；⑤ OIT 未验证（本轮不涉及）。

**下一步**：**M7c C1 进行中**（普通方块模型光晕，见下一节）；**C2（entity_block）待排期**；之后 M8 → M9 → M10。

---
---

## M7c · 方块模型光晕（C1 ✅ 实机通过；C2 待排期）🟡

**上游默认视觉**（详见 `docs/reviews/38-m7c-spec.md`）：

| 目标 | 上游默认视觉 | 依据 |
|---|---|---|
| 普通方块（无 BlockEntity、`RenderShape.MODEL`） | **模型光晕**（native glow） | `UP:BlockDisplayPolicy.java:60-71`；`BlockModelOutlineRoute.java:76-78` |
| `entity_block`（箱子/告示牌等，`entityBlockRenderMode=ALL`） | **真实 BlockEntity/烘焙模型光晕** | `BlockModelOutlineRoute.java:72-74`；`VirtualBlockDisplayRenderer.java:284-320` |
| 黑名单方块（仅用户配置；默认空） | **VoxelShape 线框** | `BlockDisplayPolicy.java:70`；`BlockModelOutlineRoute.java:80` |

**决策（2026-10-31，用户确认）**

- **C1 已批准并完成**：普通方块从 M7a 的线框改为**模型光晕**（对齐上游默认）；用户已接受该可见视觉变化。
- **C2 未批准/未排期**：`entity_block`（箱子/告示牌等）**保持线框**，单独排期。

**C1 实机通过（2026-10-31）**：普通方块显示**模型光晕**（走 outline-only `submitBlockModel`，不是 `submitMovingBlock`）；**隔墙可见**；**玻璃/冰无「线框+光晕」双画**；用户反馈**「没有太大问题」**。

**C1 实现要点**

| # | 内容 | 证据 |
|---|---|---|
| 1 | 新增 `BlockModelGlowPass`：每 winner 收集 `BlockStateModelPart`（`model.collectParts(...)`）+ `state.getOffset(pos)`，用**outline RenderType** 调 `submitBlockModel(..., outlineColor)` → `outline` phase | `src/client/java/nx/pingwheel/common/client/outline/BlockModelGlowPass.java:75-156` |
| 2 | 规则判定：`ClientConfig.getBlockDisplayPolicy().shouldUseNativeGlow("block", state)` 命中才发光 | 同文件 `:99-101`；`ClientConfig.java:173-175` |
| 3 | **事前抑制线框**：`BlockModelGlowPass.trySubmit(...)` 返回 true 时 `BlockOutlinePass` 跳过该 marker（保证不双画） | `BlockOutlinePass.java:174-178` |
| 4 | 失败回退：`parts` 为空 / outline 类型解析失败 / 提交异常 → **回退 M7a 线框**；WARN 一次后 `glowDisabled` 锁存本会话 | `BlockModelGlowPass.java:53-57,129,167-192` |
| 5 | 日志：首次激活 INFO 一次（`block model glow active`），之后无 per-frame 输出 | 同文件 `:156` |

> 🔔 **提醒项（用户要求记录）：C2 = `entity_block` 真实 BER 光晕，仍未排期；C2 之前 entity_block 显示的是线框，不是上游默认的模型光晕。每次阶段收口时都要提醒用户一次。**

**已知偏差（C2 前）**：`entity_block` 仍是线框，与上游默认不一致 → 需在 `docs/reviews/24-fidelity-constraint-audit.md` 登记为**临时偏差**（C2 完成或明确放弃后更新）。

**证据**：`docs/reviews/38-m7c-spec.md`（上游判定链 + javap 依据）、`docs/reviews/39-review-m7c-c1.md`（复审）；`compile-check` main **266** / client **99**，0 error。

**C2 方案验证（2026-10-31，详见 `docs/reviews/40-m7c-c2-spec.md`）**

- **两案都可行**（A：thread-local 目标色 + `@ModifyVariable` 覆盖 `submitModel` 的 outlineColor 槽；B：`@ModifyVariable` 替换 BER dispatch 的 collector 参数 + 手写委托类）。
- **决定：A 优先、B 备选**（用户要求）：A 代码量≈60 行 vs B≈150 行，覆盖相同（都只覆盖 `submitModel`/`submitModelPart` 主路径）；A 用 **save/restore + 每帧 reset** 兜异常。
- 覆盖边界与上游一致：BER 的 `submitText`/`submitCustomGeometry` **不发光**（告示牌文字不亮是预期）。

**任务顺序（2026-10-31 用户确认）**：**M8 → C2 → M9 → M10**。C2 仍待做（`entity_block` 当前是线框）。


---

## M8b · 资源重载 v1 + 自定义 ping 贴图（2026-10-31）✅

**实现**

| 项 | 内容 | 证据 |
|---|---|---|
| 状态持有 | `PingTextureState.hasCustomTexture()`（`volatile`，主线程 apply / HUD 读） | `src/client/java/nx/pingwheel/common/client/resource/PingTextureState.java:12,16-21` |
| 监听器 | `PingTextureReloadListener extends SimpleReloadListener<Integer>`：`prepare` 取 `getResourceStack(PING_TEXTURE_ID).size()`，`apply` 落 `size > 1` | `PingTextureReloadListener.java:24,29-34,47-49` |
| 注册 | Fabric v1：`ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(RELOAD_LISTENER_ID, …)` | `src/client/java/nx/pingwheel/fabric/FabricClient.java:101-102` |
| HUD 分支 | `MarkerHudRenderer`：物品图标 → **自定义贴图**（12×12 `blit`，`RenderPipelines.GUI_TEXTURED`）→ 默认菱形 | `MarkerHudRenderer.java:266-279` |
| 轮盘 | `WheelHudRenderer.drawIcon` 既有无条件 `blit(PING_TEXTURE_ID)`（颜色由调用方给） | `WheelHudRenderer.java:457-479`（调用方 `:334-338`） |

**验收（2026-10-31）**

- ✅ **默认路径通过**：无资源包时行为不变（自带 `ping.png` 为 1×1 透明占位 → `hasCustomTexture=false`），**用户反馈游戏内各项设置正常**。
- ✅ **启动不卡加载条**（v1 `SimpleReloadListener`，未使用 v0）。
- ⬜ **自定义资源包尚未实机验证**：用户当前没有资源包，留待后续有包时补测（替换 `assets/pingforit/textures/ping.png` 后应显示自定义贴图）。
- 证据：`docs/reviews/41-m8-spec.md`（规格）、`docs/reviews/42-review-m8b-resource-texture.md`（复审通过）；`compile-check` main **266** / client **101**，0 error。

**已知 S4（复审 42 号）**：① `blit` 的 tint 与上游 `setShaderColor` 的等价性属假设；② 12×12 为固定采样尺寸（上游同尺寸）；③ `PingTextureReloadListener.failureLogged` 非 `volatile`（仅日志闸，影响可忽略）。

## M8 · 配置 / 命令 / UI（✅ 完成 2026-11-01）

> **阶段拆分（2026-10-31 当时的记录；最终状态 2026-11-01 已全部完成）**：**M8b ✅ 完成**（资源 v1 + 自定义贴图默认路径通过）；**M8a ✅ 完成**（设置界面 port，7 个 screen 类 → 26.3 `GuiGraphicsExtractor` 管线，实机通过）；**M8c ✅ 完成**（配置提示 notice 第二层 HUD + 命令/语言补齐，实机通过）。

| 子阶段 | 内容 | 状态 |
|---|---|---|
| M8a | 设置界面 port（`deferred/screen/` 7 个类 → 26.3 `GuiGraphicsExtractor` 管线）；服务端设置区延后 | ✅ **完成（2026-11-01）**：完整分页设置界面（6 客户端分类 / 25 项 + 3 服务端分类 / 5 字段）已实机通过；M8a-1 最小闭环作为前序里程碑保留 |
| M8b | 资源重载 v1 + 自定义 ping 贴图 | ✅ 完成（见上节；自定义包待补测） |
| M8c | 配置提示（toggle notice）+ 命令/语言补齐 | ✅ **完成（2026-11-01）**：toggle notice 第二层 HUD 已实机通过 |

### M8a-1 · 设置界面最小闭环（2026-10-31）🟡

**目的**：先用最小代价打通「命令开界面 → 改值即时生效 → Done 落盘」这条 26.3 GUI 通路，再投入完整分页界面。

**已落地**（`src/client/java/nx/pingwheel/common/screen/`）

| 类 | 行数 | 说明 |
|---|---|---|
| `DeferredActionCoordinator` / `SettingsCategoryCatalog` / `SettingsNavigationModel` / `SettingsScreenLayout` | 53 / 111 / 249 / 94 | 从 `deferred/` 原样复制，零 `net.minecraft.*` 客户端依赖，未做 26.3 适配 |
| `SettingsScreen` | 187 | 单页 5 项（player_info_mode / team_color_mode / entity_block_render_mode / item_icon_visible / direction_indicator_visible）+ Reset all + Done |

**复审（`docs/reviews/43-m8a1-review.md`，打回）与修复**

| # | 问题 | 修法 |
|---|---|---|
| B1 | `resetToDefaults()` 会**换掉 config 实例**，Reset 后其余控件仍改旧实例，界面与 HUD 全部失效 | 控件回调一律 `config()` 现取；Reset 走确认后**重建屏幕**（与上游 `:983-986` 一致） |
| B2 | 标题不显示：26.3 `Screen.extractRenderState` 只遍历 renderables，不画 `title` | 覆写 `extractRenderState`，`centeredText(font, title, width/2, 15, 0xFFFFFF)` |
| B3 | 自造 Cancel 按钮与上游不符（上游 footer 仅 Reset all + primary，关闭即落盘） | 删除 Cancel，footer 对齐上游 |
| B4 | Done 丢弃 `saveSafely()` 返回值，写盘失败仍关闭 | 失败时 `WARN` 并**留在界面**（上游 `persistSettings()` false 不关闭） |
| N1 | `settings.pingforit.reset` 在 8 个 lang 文件全缺失（真实 key 为 `reset_all`）→ 按钮显示原始 key | 改用 `reset_all`（含 `.title`/`.message` 走 `ConfirmScreen`） |
| N2/N3 | javadoc 引用不存在的类型；两个 server-session 空实现被 `CommonClient:116/584` 调用 | javadoc 重写；空实现补 `DEBUG` 日志留痕 |

**关键发现（决定 M8a-1b 走法）**：26.3 **仍然保留** `OptionsSubScreen`（含 `HeaderAndFooterLayout layout` / `addOptions()` / `resetOption()`）、`OptionsList`（`addBig`/`addSmall`/`addHeader`/`findOption`/`applyUnsavedChanges`）、`TabNavigationBar`（`builder`/`arrangeElements`/`selectTab`）、`TabManager`、`ConfirmScreen`、`OptionInstance`（含 `noTooltip()`/`createBoolean`/`IntRange`）。上游 deferred 版正是 `extends OptionsSubScreen` + `TabNavigationBar` + `OptionsList`。**结论：M8a-1b 是近乎逐字搬运**，只需适配 `GuiGraphics → GuiGraphicsExtractor`、`render → extractRenderState`、`keyPressed(KeyEvent)`/`MouseButtonEvent`、`Minecraft#setScreen → setScreenAndShow`。

**未覆盖**：上游客户端 25 项中本页仅 5 项；缺 20 项（ping_distance、marker_display_duration、ping_size、pass_through_transparent_blocks、mark_blacklisted_targets、mark_fluids、wheel 五项、wheel_hold/timeout、long_press 两项、cancel_half_cone、channel、ping_volume、configuration_notice_size、open_client_config）+ 服务端 5 项；分类/叶子/返回/滚动/范围标签页均未做。

**校验**：`tools/compile-check` main **266** / client **110**，0 error。

### M8a-1b · 完整分页设置界面（2026-10-31）✅

**结果**：`SettingsScreen` 由 187 行单页版 → **1426 行完整版**（上游 1344 行 + 82 行 26.3 适配），与 `deferred/` 原文件 diff **164 行**（其余原样搬运）。6 个客户端分类 / 25 项客户端设置 / 3 个服务端分类 / 5 项服务端字段 / 范围 tab / 叶子页与返回 / 滚动与焦点记忆 / Reset 确认 全部到位。

**过程**：2号 两轮尝试均因上下文预算耗尽未完成并主动回退保绿；**Lead 接手完成**（照 2号 提供的行号表 + Lead 自己的 javap 反汇编结论）。

**26.3 适配（14 处，均有字节码依据）**

| 类别 | 内容 |
|---|---|
| 事件/渲染签名 | `keyPressed(KeyEvent)`、`mouseClicked(MouseButtonEvent,boolean)`、`resize(int,int)`、`setScreen→setScreenAndShow`(4)、`render→extractRenderState` 体系 |
| 构造/字段 | `lastScreen` 为 **final** → `this(null)` + `super(lastScreen,null,title)`，删除自建 `parent` 字段 |
| 布局/控件 | `TabNavigationBar` 5 参 builder + `.addTab(TabButton,Tab)`；自写 `ScopeTabButton`（26.3 `TabButton` 已抽象）；`StringWidget.alignLeft/alignCenter` 被删 → 自写 `LeftTextWidget`/`CenterTextWidget`（含上游的 `(height-9)/2` 垂直居中） |
| 其余 | `Util.getPlatform().openFile` → **`Blaze3D.openPath`**；`getScrollAmount→scrollAmount`；`setClampedScrollAmount→setScrollAmount+refreshScrollAmount`；`setFilter→setResponder` 整段回退；`OptionsList.Entry→AbstractEntry`；`Tab.getLayout()` 返回空 `GridLayout`；`TeamContextHandler→ClientTeamContextHelper` |

**审查**：`docs/reviews/44-m8a1b-review.md` —— 3号 三轮独立审查。第 1 轮打回 3 项（文本垂直居中丢失、权限等级 2≠3、居中变左对齐），第 2 轮打回 1 项（**26.3 客户端无权限数据，权限门恒 false**），第 3 轮 **通过**。修复后复核确认：denial 后不会重复请求、server tab 可见性与上游一致、服务端权限源可靠（`ServerPlayer.permissions()` → `getProfilePermissions` 实时解析）。

**保真登记**：平台强制偏离 4 项已记入 `24-fidelity-constraint-audit.md` §6。

**校验**：`tools/compile-check` main **266** / client **120**，0 error。

**待实机**：6 分类 + 2 tab 外观、25 项控件即时生效、分类进出滚动/焦点记忆、Reset 确认重建、服务端三页三种权限矩阵（单人开/关作弊、多人 op/非 op、LAN guest）。

#### 实机缺陷修复 · 打开界面即冻结（2026-10-31）

**现象**：`/pingforit config` 执行后整屏冻结（两个存档一致），日志仅在首帧出现一条 `IllegalArgumentException: Scissor at 6, 1494 with size 12x6 is out of bounds for render area 2560x1494`，之后无渲染日志。

**根因（三方独立收敛）**：
1. 26.3 把 **tab 按钮宽度分配**从 `TabNavigationBar.arrangeElements(int)` 移进了 `MenuTabBar.arrangeElements`，本地 `ScopeTabButton` 因此恒为 **0 宽**；
2. `ActiveTextCollector.acceptScrollingWithDefaultCenter` 的实参序是 **(msg, x1, x2, y1, y2)**，首版按 (x1,y1,x2,y2) 传，0 宽使「文本宽于框」恒真，进入滚动分支；
3. 该分支的 `Parameters.withScissor` **不做屏幕裁剪**，退化矩形（GUI 2×1）进入 `GuiRenderer` → `FrontendRenderPass` 抛错；
4. 异常发生在 `Minecraft.setScreenAndShow → renderFrame` 内，window surface 保持 acquired，其后每帧 `if (surface.isAcquired()) return` → **表现为卡死而非崩溃**。

**修复**（`SettingsScreen`）：新增 `arrangeScopeTabBar()` 复刻 `MenuTabBar` 的宽度公式（`min(400,width)-28` 均分、`roundToward(…,2)`、bar 收窄到 tabsWidth 后居中）；标签调用改为正确实参序；加退化框闸门（命中时一次性 WARN）。

**证据**：修复后 `tab=ScreenRectangle[x=28,0,372x24]`、两按钮 186×24；概览 header 57 / footer 70 / list 122，叶子页 33/33/183，均与上游常量一致；日志 0 ERROR（唯一 WARN `end_of_frame` 自 10-30 起每个会话都存在，属环境噪声）。审查：`docs/reviews/48-m8a-tab-freeze-review.md`（3号 两轮，最终通过）。**tab 观感已由用户实机确认正常（2026-10-31），24 号 #15 销项。**

#### M8a 实机验收（2026-10-31 ~ 11-01，用户）✅

| 项 | 结果 |
|---|---|
| 打开界面 | ✅ 正常（修复冻结后）；用户确认「tab 正常」「当前没有问题」 |
| tab 外观 | ✅ 两个 tab 居中、选中态与悬停正常（24 号 #15 销项） |
| 6 个客户端分类 / 25 项 | ✅ 可浏览、可修改，无异常日志 |
| 服务端设置会话 | ✅ 单人存档内 3 次 `server-config-request`（C2S）↔ 3 次 `server-config-snapshot`（S2C）一一对应（`debug.log` 14:51:24 / 14:53:26 / 14:56:20） |
| 日志 | ✅ 0 条模组相关 ERROR（仅有 Auth/Realms 噪声与历史 `end_of_frame` WARN） |

**M8a-2（服务端设置区）合并说明**：服务端三分类页已随 M8a-1b 整体搬入并实机验证会话往返正常，故不再单列 M8a-2。3号 早期指出的「`commitServerSettings` 在 `sendToServer` 前 `markClean`」经比对**与上游逐行相同**（上游 `:905-942` 同样顺序），属上游语义，**不修改**（保真铁律）。

### M8c · 目标选择切换提示（toggle notice）迁入 HUD（2026-11-01，实现完成待实机）✅

**目标**：把 `deferred/render/SelectionToggleNoticeRenderer`(72) + `SelectionToggleNoticeRenderPolicy`(22) 迁到 26.3，接入已存在的 `SelectionToggleNoticeState`（`InputUtils` 三处 `show()`、断线 `clear()` 均已就绪）。

**26.3 适配**：`GuiGraphics → GuiGraphicsExtractor`；`Matrix3x2fStack` 2D（`pushMatrix/popMatrix/translate(x,y)/scale(s,s)`）；`RenderSystem.setShaderColor` 已不存在 → **alpha 折进颜色 ARGB**；`drawString → text`；HUD 走 `HudElementRegistry.addLast` 第二层（晚于 marker/wheel，等价上游 late pass）。

**保真**：锚点 `guiWidth/2`、`guiHeight*0.25`，`scale=size/100`，prefix/state 两段分别从 `-totalWidth/2` 与 `+prefixWidth` 起，`y=-font.lineHeight/2`，颜色白/`0x55FF55`/`0xFF5555`，`configuration_notice_size=0` 不画；时序（2s + 1s 淡出）沿用现有状态机。

**验收**：三个切换键显示 `<功能> on/off` 并淡出；size=0 隐藏；命令输出无 missing key；`compile-check` 0 error。

**任务**：task-71（2号 实现，Lead 复核，3号 审查**通过**）。

**落地**：`SelectionToggleNoticeRenderPolicy`(22，与上游 SHA-256 相同) + `SelectionToggleNoticeRenderer`(76) + `CommonClient.onRenderToggleNotice` + `FabricClient` 第二层 HUD；`client` 源 77 个类 / 122 class，0 error。

**审查要点（`docs/reviews/49-m8c-review.md`）**：`Font$PreparedTextBuilder#getTextColor` = `ARGB.color(ARGB.alpha(传入色), style.rgb)` ⇒ 传入色只贡献 alpha、style 保留 RGB，shadow 走 `ARGB.scaleRGB` 同样保留 alpha，alpha=0 无「0=不透明」分支 → **淡出与上游 shader alpha 等价**；`HudElementRegistryImpl.addLast` = 追加到 LAST 根层、注册 FIFO ⇒ notice 晚于 marker 与 wheel；`INTERACTION_TIME_SOURCE` 与状态机同为 `nanoTime/1e6`。按审查建议补了 HUD 层 try/catch（一次性 WARN，按 AGENTS §14 防整屏冻结）。

**实机验收（2026-11-01，用户）✅**：三个 toggle 键的提示显示正常（2s 常显 + 1s 渐隐、颜色、层级均符合），用户确认「没有问题」，M8c 收尾。

### M7 收尾 · HUD 投影补 bob + ping 提示改回聊天框（2026-11-01，提交 `dde1be5`）✅

**问题 1（聊天框）**：ping 提示原本走 `sendOverlayMessage`（actionbar，物品栏上方、不进聊天记录）。上游 `ClientPingRuntime:1087` 用 `displayClientMessage(message, false)` → 聊天框；26.3 的等价入口是 `LocalPlayer#sendSystemMessage(Component)`（`ChatListener.handleSystemMessage(..., true)` → `addServerSystemMessage`）。已改 `ClientPingRuntime` 与 `MinecraftLocalErrorSink` 两处。联机可见性机制：服务端按受众快照发 `MarkerCreated`，每个接收者客户端本地显示一次（不是服务端广播聊天），见 `names_chat.md:76-95`。

**问题 2（标记不随视角摇晃）**：上游标记每帧按实体 live 位置 + `tickDelta` 插值投影（`MarkerView:176-215`），我们的跟随逻辑本无问题；差异在 **26.3 的 view bobbing 只在 `GameRenderer.renderLevel` 乘进投影**，而 HUD 用 `Camera#getViewRotationProjectionMatrix()`（=P×V，不含 bob）。已改为从 `gameRenderer.gameRenderState().levelRenderState.cameraRenderState` 取 V/P，并按 `GameRenderer#bobHurt`/`bobView` 字节码逐项复刻 bob 后 `projection.mul(bobPose)`（常数 0.5/3.0/5.0/0.2/14.0/8000/200，受伤倾斜全程 double 收尾再转 float）。fallback 覆盖 renderState/options/cameraState/entityRenderState/矩阵为 null 的情形。

**审查**：`docs/reviews/52-review-hud-bobbing-chat.md`（1 阻断：`damageTiltStrength` 精度顺序，已修 → 复审通过）。**待实机**：聊天框显示与联机可见、走路时标记与线框/光晕同步摆动、关 bobbing 同停、受伤倾斜同步、轮盘/notice 位置不变。

### M8 · 阶段总结（2026-11-01）✅

| 子项 | 内容 | 状态 |
|---|---|---|
| M8a | 完整分页设置界面（6 客户端分类 / 25 项 + 3 服务端分类 / 5 字段 / 范围 tab / 叶子页 / 滚动焦点记忆 / Reset 确认 / 打开配置文件） | ✅ 实机验证通过（含 tab 冻结修复） |
| M8b | 资源重载迁 v1（`SimpleReloadListener`）+ 自定义 `ping.png` 判定 + HUD 分支 | ✅ 默认路径通过；**自定义资源包待用户手上有包时补测** |
| M8c | 目标选择切换提示（渲染器 + 策略 + 第二层 HUD 层） | ✅ 实机验证通过 |
| 命令 | `/pingforit help` / `config` / `channel [name]` | ✅ 可用；语言键 8 文件核对无缺失 |

**关键提交**：`33d6335`（最小设置界面）、`a920dd5`（完整界面）、`2fa773a`（冻结修复）、`d6a4546`（M8c）。

**遗留**：仅 M8b 的自定义资源包实机补测（替换 `assets/pingforit/textures/ping.png` 后应显示自定义图标），不阻塞后续阶段。

**下一步**：**C2（`entity_block` 真实方块实体光晕）** —— 规格 `docs/reviews/40-m7c-c2-spec.md`（方案 A：线程本地目标色 + `SubmitNodeCollection.submitModel` 的 `@ModifyVariable`）。

### C2 · entity_block 真实方块实体光晕（进行中）🟡

**背景**：`entity_block` 目标（箱子/告示牌/刷怪笼等）当前走 M7a 线框兜底，上游默认是**真实 BER 模型发光**（形状跟随实际模型）。这是目前唯一登记在案的功能性保真偏差。

**方案 A（已选定，规格 40 号）**：thread-local 目标色 + 在 `submitModel` 处覆盖 outlineColor —— 约 60 行，不动 collector 的 17 个委托方法。

| # | 改动 | 内容 |
|---|---|---|
| 1 | 新增 `client/outline/EntityBlockGlowState` | `ThreadLocal<Integer>` + `begin/restore/currentColorOr/resetFrame` |
| 2 | 新增 `mixin/BlockEntityRenderDispatcherMixin` | `submit` HEAD 按 `state.blockPos` 判定 → `begin(color)`；RETURN `restore`；`require=0` + 一次性 WARN |
| 3 | 改 `SubmitNodeCollectionMixin` | `@ModifyVariable(method="submitModel", argsOnly=true, index=9)` 覆盖 outlineColor |
| 4 | 改 `LevelExtractorMixin` | 每帧 HEAD 调 `resetFrame()` |
| 5 | 改 `pingforit.mixins.json` | 注册新 mixin |

**已核实 26.3 签名**：`BlockEntityRenderDispatcher#submit(S,PoseStack,SubmitNodeCollector,CameraRenderState)`；`BlockEntityRenderState.blockPos` public（无 `blockState`，需从 level 取）；`submitModel` 9 参、最后 int 为 outlineColor（slot 9）。

**验收**：与 ping 同色、形状跟随真实 BER、穿墙可见、告示牌文字不发光（预期）、无双画、mixin 未织入时无 crash + 一次 WARN 回退线框。

**任务**：task-72（2号 实现，Lead 复核，3号 **两轮**审查通过）。

**落地**：`EntityBlockGlowState`（当前色 + 嵌套栈 + 每帧发光位置集合 + 两条一次性诊断）、`BlockEntityRenderDispatcherMixin`（submit HEAD 解析颜色 / RETURN 还原，`require=0`）、`SubmitNodeCollectionMixin` 的 `@ModifyVariable(index=9)`、`LevelExtractorMixin` 每帧 `resetFrame()`、`BlockOutlinePass` 的线框抑制。`client` 125 class，0 error。

**两轮审查的关键结论**（`docs/reviews/50-m7c-c2-review.md`）：
- `submitModel` 的最后 int 是 outlineColor，LVT slot = 9（`this`=0 … `UvMapping`=8），按 absolute LVT 匹配，`argsOnly` 只限参数 —— 首轮即确认
- **首帧是单画 glow**：Fabric `COLLECT_SUBMITS` 在 `LevelRenderer#submitFeatures` 的 RETURN，而该方法先 `submitBlockEntities`
- 第 1 轮打回：抑制门用「钩子装没装」不等价于「光晕真画了」→ 改成**本帧该位置真的消费过颜色**（`isGlowing(pos)`），四条失败路径（dispatch 钩子缺 / model 钩子缺 / 只画 text / renderer null）全部回退线框 + 一次性 WARN
- 第 2 轮通过；非阻断建议（长会话误报、每 dispatch 一次快照）已按建议改为 `modelHookApplied` 早退 + **每帧一次** winner 缓存

**待实机**：箱子/告示牌/熔炉等模型光晕（同色、贴真实模型、穿墙）；只画 text / 无 renderer 的方块实体保留线框；临时移除 C2 mixin 应回退线框 + 一次 WARN；普通方块 C1 与 M7a 线框不受影响；长会话无误报。

**实机反馈修复（2026-11-01）· 熔炉/营火只有线框无光晕**：26.3 里**熔炉没有 BER**（渲染走方块模型）、**营火 BER 只提交物品**，两者都不经过 `submitModel`，C2 的 outlineColor 钩子无从写入。按上游 `VirtualBlockDisplayRenderer` 的三层来源（**BER → 烘焙模型 → VoxelShape**）补上第二层：`BlockModelGlowPass` 现在也接受 `entity_block`，但仅当本帧 BER 未发光（`!EntityBlockGlowState.isGlowing(pos)`）时才用烘焙模型兜底，避免与 BER 双画；无几何的 state（`missingModel`）仍回退线框。3号 复审通过（`docs/reviews/50-m7c-c2-review.md` 复审记录），提交 `b3ad60f`。

**按上游文档对齐（2026-11-01，task-73，3号 复审通过，提交 `f88a887`）**：比对 `docs/architecture/geometry/geometry_sources.md` 与 `rendering/outline.md` 后修正三处：
1. **`entity_block_render_mode` 此前完全没被渲染路径读取**（设置界面里是失效项）→ 已接入 BER 与烘焙两条来源：`VOXEL_SHAPE_ONLY` 只走线框、`ALL`/`COMPATIBLE` 允许 BER + 烘焙（`ALL` 的 optional 来源属 M9）；普通 `block` 仍不读该模式（文档要求）
2. **短路偏离**：文档要求「所有允许来源都跑、不短路，任一 `RENDERED` 才抑制线框」，此前 BER 发光时跳过了烘焙模型 → 已去掉该早退。审查确认无双画：箱子/旗帜/潜影盒/移动活塞的烘焙模型为空，钟是「烘焙支架 + BER 钟体」互补
3. 烘焙模型来源补 `RenderShape.MODEL` 前置 → `barrier`/`light`/`structure_void`（`INVISIBLE`）**不再有 native glow、回退线框**，与文档一致（它们本就不该有）

**审查纠正的一条事实**：`AbstractSignRenderer.submit` 只提交 `submitText`、**不提交模型**；告示牌牌体来自烘焙模型（`template_sign` 等），故**告示牌依赖烘焙源**，BER 只管文字（文字本来就不发光）。


| # | 待办 | 状态 | 说明 |
|---|---|---|---|
| 1 | 双配置文件 + 版本化迁移 | ⬜ | 客户端 `pingforit.json` / 服务端 `pingforit.server.json`，含 `pingforit-version` 迁移与损坏恢复 |
| 2 | 服务端 5 个远程可改字段 | ⬜ | `defaultChannelMode` / `playerTrackingEnabled` / `msToRegenerate` / `rateLimit` / `syncDuration`（`pingDistance` 仅 JSON） |
| 3 | `/pingforit` 三个子命令 | ⬜ | `help` / `config` / `channel`，channel ≤128 字符、按服务器 IP 分桶 |
| 4 | 设置界面 | ⬜ | 现位于 `deferred/screen/`（M3b 搬迁），需按 26.3 的 `GuiGraphicsExtractor` 管线重建 |
| 5 | **消除 deprecation note：`FabricClient` 资源重载 API 迁移** | ⬜ | 见下 |

### 待办 5 明细：资源重载 API 迁移

**现象（两层）**：① 编译输出有一条 deprecation note，指向 `src/client/java/nx/pingwheel/fabric/FabricClient.java`；② **更严重**：该 API 在 26.3 会让资源重载**永远完不成**（卡加载条、无异常），属**功能阻断**——M4 阶段已实证，暂由 `a6cef45` 摘除注册并留 TODO(M8)。

| 项 | 现状 |
|---|---|
| 旧 API（v0，已 `@Deprecated`） | `import` 第 **9-10 行**：`net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener`、`ResourceManagerHelper`；使用第 **110-111 行**：`ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new IdentifiableResourceReloadListener(){...})` |
| 证据 | fabric-resource-loader-v0 **3.3.27** 的 `ResourceManagerHelper.class` 与 `IdentifiableResourceReloadListener.class` 常量池中均含 `Ljava/lang/Deprecated;`（本地 `javap -v` 核实） |
| 新 API（v1 3.1.0） | `ResourceLoader.get(PackType).registerReloadListener(Identifier, PreparableReloadListener)`；监听器用 `net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener<T>`（封装新 `reload(SharedState, Executor, PreparationBarrier, Executor)`，只需实现 `prepare(SharedState)` / `apply(T, SharedState)`）；注册表/特性旗标用 `ResourceLoader.REGISTRY_LOOKUP_KEY` / `FEATURE_FLAG_SET_KEY`；顺序控制用 `ResourceLoader.addListenerOrdering(...)` |
| v1 类核实 | `fabric-resource-loader-v1-3.1.0` 内确有 `api/resource/v1/ResourceLoader.class` 与 `api/resource/v1/reloader/SimpleReloadListener.class`；`ResourceLoader` 常量池含 `registerReloadListener` / `addListenerOrdering` / `REGISTRY_LOOKUP_KEY` / `FEATURE_FLAG_SET_KEY` |
| 迁移注意 | ① 当前注册**已被 `a6cef45` 摘除**（`FabricClient.java:101-106` TODO(M8)），M8 是「用 v1 重新加回」而不是「原地替换」；② `RELOAD_LISTENER_ID` 的贴图/音效预加载在 26.3 管线重写时已 defer（`:117-120` TODO），迁移只保**注册点 + id**，不要顺手恢复预加载逻辑；③ 改完必须实测启动不再卡加载条 |
| 验收 | 编译输出不再出现该 deprecation note；**启动不卡加载条**（`runClient` 能正常进主菜单/世界）；资源重载注册仍生效（进世界→资源包重载不报错） |

---

## M9 · 可选集成（2026-11-02 调研结论：做 3 / 跳过 2）

> 依据：`docs/reviews/53-m9-integration-feasibility.md`（联网核对 Modrinth / CurseForge / maven metadata 与 API 签名）

| 集成 | 26.3 可用版本 | 依赖坐标 | 结论 | 预估 |
|---|---|---|---|---|
| **ModMenu** | `21.0.0`（2026-09-23） | `run/mods/modmenu-21.0.0.jar`（**compileOnly，离线**；CI 用 `curl` 从 TerraformersMC maven 补） | ✅ **已完成（M9-1）** | 0.5h |
| **VoiceChat** | `fabric-2.6.27+26.3`（2026-10-28） | 当前：`run/mods/voicechat-fabric-2.6.27+26.3.jar`（**完整 mod，自带 104 个 `de/maxhenkel/voicechat/api/*` 类，compileOnly，离线**；CI 用 `curl` 取**同一件产物**） | ✅ **实现完成（审查通过），实机待验** | 2–3h |
| **Distant Horizons** | `3.3.4-26.3`（自带 API 类）+ API `7.2.0` | 当前：`run/mods/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`（**完整 jar，compileOnly，离线**；CI 用 curl 取**同一件产物**）→ **已按方案 A 落地（2026-11-04）**：编译期 `compile-libs/DistantHorizonsApi-7.2.0.jar`、运行时 `run/mods` 完整 jar | ✅ **实现完成（复审通过），实机待验** | 3–4h |
| FTB Teams | **无** | — | **跳过**（保真 #17） | — |
| Sable | **无** | — | **跳过**（保真 #18） | — |

**顺序**：ModMenu → VoiceChat → DH → 收尾（`fabric.mod.json` 依赖声明、保真登记复核），合计 ≈6–8h。
**风险**：沙箱无 Gradle、无出网解析 → 每项落地第一步都要在用户终端 `gradlew build` 验证 compileOnly；待核 `DH payload.pos` 类型、VoiceChat API `packaging=pom`。

### M9-1 · Mod Menu 集成（2026-11-02 实现 / **2026-11-03 实机通过**）✅

**目标**：按 1:1 恢复上游入口——Mods 列表里的「配置」按钮直开同一套 `SettingsScreen`（上游 `fabric/src/main/java/nx/pingwheel/fabric/integration/ModMenuIntegration.java:7-12`，只做 `SettingsScreen::new`）。

**改动清单**

| # | 改动 | 位置 | 说明 |
|---|---|---|---|
| 1 | **新增** `ModMenuIntegration` | `src/client/java/nx/pingwheel/fabric/integration/ModMenuIntegration.java`（`read` 计 13 行，含空行；Lead 报 12 行属行数统计口径差异） | 与上游同名文件 **逐字节一致**（`diff` 为空）；包名 `nx.pingwheel.fabric.integration` 也一致 |
| 2 | **删除** `deferred/modmenu/ModMenuIntegration.java` | `deferred/modmenu/` | 目录已空并移除；`deferred/` 96 → **95** |
| 3 | **注册 entrypoint** | `src/main/templates/fabric.mod.json:15` | `"modmenu": ["nx.pingwheel.fabric.integration.ModMenuIntegration"]`，键名与上游一致；上游还有 `voicechat` 键，属 M9-2，**未加** |
| 4 | **依赖改离线本地 jar + 编译期校验** | `build.gradle:35-56`（integrationJars）、`build.gradle:58-94`（校验块） | 移除 TerraformersMC maven 与 `compileOnly "com.terraformersmc:modmenu:21.0.0"`；改为 `fileTree(dir: "run/mods", include: [5 个 glob])` + **`exclude "*-sources.jar", "*-javadoc.jar"`** + `compileOnly`。校验块（3号 复审第 2 轮后从 `dependencies{}` 挪出）：`tasks.matching { it.name == "compileClientJava" }.configureEach { doFirst { … } }` 里**先** `logger.lifecycle("compileOnly integration jars: …")` 打印命中名单、**再**在没有 `modmenu-*.jar` 时抛 `GradleException`（含 `put modmenu-21.0.0.jar into run/mods/` + 官方下载 URL）。**检查刻意不放配置期** → 没 jar 的 checkout 里 `./gradlew tasks` / `clean` / IDE sync 照常可用；另加 `projectsEvaluated` 兜底警告（找不到 `compileClientJava` 时只 WARN 不炸配置） |
| 5 | **本地 javac 校验同步** | `tools/compile-check.sh:69-93` / `compile-check.ps1:69-89` | 两脚本用**与 `build.gradle` 一致的 5 个 glob + 同样的 `-sources`/`-javadoc` 排除**（即 `build.gradle` 的 include+exclude、两脚本的 glob+排除，三处一致）拼 classpath，命中名单打印成 `compile-check: integration jars from run/mods: <name>, <name>`；0 命中时打印 `no integration jars matched in run/mods (M9 integration classes cannot compile)` |
| 6 | **CI 补 jar** | `.github/workflows/build.yml:27-45` | `run/` 被 gitignore → 全新 checkout 无 jar、集成类无法编译，build 前 `curl` 下载 `modmenu-21.0.0.jar` / `voicechat-api-2.6.27.jar` / **DH 完整 jar** `DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`（**不再下 `DistantHorizonsApi-7.2.0.jar`**；用与 dev `run/mods` 同一件产物的理由写在 workflow 注释里：它自带 181 个 `com/seibel/distanthorizons/api/*` 类（含 `DhApi.class`），**避免 CI/本地编译面漂移**）；注释已改事实口径（**需要网络或预置 `run/mods` 才能构建**，清单须与 `build.gradle` 的 glob **及各集成自己的存在性检查**同步） |

**保真性**：入口类与上游**逐字节一致**、entrypoint 键名一致、打开的就是既有 `SettingsScreen`（M8a 已与上游对齐）→ **不改变上游呈现效果**。依赖从 maven 改成本地 jar 属**构建期差异**，登记说明见 `24` 文件末尾备注，**不新增编号**。

**校验**

| 项 | 结果 |
|---|---|
| `bash tools/compile-check.sh`（Lead 2026-11-02 实测） | `main errors=0 classes=266` / `client errors=0 classes=126`（+1 = 新集成类）；集成 jar 命中数当时为 **3**（旧宽口径，吃 `run/mods/*.jar` 全部） |
| 脚本口径收紧后（同一套 glob） | 按 glob 计算命中 **2 个**：`DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`、`modmenu-21.0.0.jar`；`voicechatprofiles-fabric-26.3-1.0.1.jar` **不在 M9 集成 glob 内被有意排除**（M9-2 需要的是 `voicechat-api-*`），故名字虽在 `run/mods` 也不会进 classpath |
| 源码文件数（2026-11-02 实测） | `src/main/java` **166** / `src/client/java` **80**（+1）/ `deferred/` **95**（−1） |
| `build.gradle` 语法（Lead 2026-11-02 实测） | 用 Gradle 自带的 **Groovy 4.0.33 `parseClass`** 解析通过（不执行配置阶段即可确认语法有效） |
| `run/mods` 现有 3 个 jar | `modmenu-21.0.0.jar`（其 `fabric.mod.json` 声明 `depends.minecraft = ">=26.3-"`，兼容）、`DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`、`voicechatprofiles-fabric-26.3-1.0.1.jar` |
| **实机验收** | ✅ **通过（2026-11-03 18:27–18:30）**：Mods 列表出现 Ping For It 的配置入口、点开是同一个 `SettingsScreen`、日志无模组相关 ERROR —— 逐项证据见下方「实机证据」表 |

**实机证据**（`run/logs/latest.log`，2026-11-03 18:27–18:30，Fabric Loader 0.20.1 / MC 26.3；行号为该日志行号）

| 观察点 | 结果 |
|---|---|
| **Mod 列表**（`:4`/`:56`/`:58`/`:59`/`:60`/`:61`） | `distanthorizons 3.3.4`、`modmenu 21.0.0`、`pingforit 0.4.2-pfi-beta1`、`voicechat 2.6.27+26.3`（嵌套 `voicechat_api 2.6.27`）、`voicechatprofiles 1.0.1`；**上一轮的 `Incompatible mods found`（voicechatprofiles 缺 voicechat）已消失** |
| **模组初始化顺序**（`:93-105`） | `[PingForIt] Init` → `Loaded config type=ServerConfig` → `Loaded config type=ClientConfig` → `ping outline pipeline registered (depth test disabled, cull off, translucent)` → `block outline pass registered (custom pipeline, afterTerrain routing)` |
| **Mod Menu 生效**（`:102`/`:132`） | `(Mod Menu/Update Checker) Checking mod updates...` + 一条 fabric-api 更新提示；同一会话里 `Saved config type=ClientConfig` 出现多次（`:429`/`:434`/`:439`/`:444`，18:31:12 又一条 `:464`）→ 设置界面确实被打开并保存过 |
| **ping 闭环** | 18:27–18:30 窗口 **send 28 / marker 28 = 1:1**（requestId 跳号 14/16/17/19/20/23 属客户端节流，未产生 marker，日志无 reject/deny）；**复读整个 `latest.log`（记录到 18:31:19）为 34:34，仍 1:1**；`block model glow active (outline-only submitBlockModel)` ≥1 条（`:347`） |
| **错误面** | **0 条模组相关 ERROR**、**0 条 `Mixin apply failed`**、0 条 reject/deny；ERROR 仅 3 条 Mojang 账户/Realms 噪声（`:146`/`:176`/`:201`，开发环境未登录） |
| **WARN 归因（12 条，全部与本模组无关）** | Sodium 类缺失（DH 的可选兼容 mixin，`:85`）、PerfOS 注册表值类型（`:89`）、voicechat debug mode（`:95`）、DH 的 G1 GC 提示（`:124`）、DH 的 `Requested post effect does not exist: minecraft:end_of_frame`（`:291`）、DH worldgen `Skipping Entity with id minecraft:zombie_villager` **×7**（`:403-409`） |
| **退出** | 会话正常结束（18:30 之后无异常；日志持续正常记录到 18:31:19） |

**剩余事项**

- **M9-2（VoiceChat）✅ 完成**：**已实现、审查通过并实机通过**（`56-m9-2-voicechat-review.md`），状态见下方 **§M9-2**；`run/mods` 已放入 `voicechat-fabric-2.6.27+26.3.jar`（完整 mod，含 API 类）；`voicechat` entrypoint、真 `VoicechatPlugin`、`VoiceChatWrapper` 两方法均已就位；**2026-11-03 起每轮 `Loaded 1 plugin(s)` + `Registering events for 'pingforit'`**。
- **M9-3（DH）✅ 完成**：`DistantHorizonsIntegration` 201 行实现回填 + API 接线；**实机已于 2026-11-04 通过**（见下方 §M9-3「实机验收」，装 DH 3.3.4 的 58-mod 会话）。
  - **已决（3号 复审第 2 轮采纳为 M9-3 项）**：编译期统一吃 **API jar**、**full jar 只做 dev runtime** —— 已按**方案 A** 落地：新增 `compile-libs/`（`DistantHorizonsApi-7.2.0.jar` 仅 `compileOnly`），完整 DH jar 已从 `ext.integrationJars` 剔除、只留在 `run/mods` 做运行时。
- **M9-4（收尾）**：`fabric.mod.json` 的 `depends/recommends/suggests` 策略统一。~~`docs/features.md:382` 的 Mod Menu 描述已过时~~ → **已于 2026-11-02 更正**（§8 新增「本地现状」列，Mod Menu 标 ✅ 已实现，VoiceChat/DH 标桩、FTB/Sable 标平台强制跳过）。

### M9-2 · Simple Voice Chat 集成（2026-11-03）✅ 实现 + 实机通过（11-03 起每轮均有）

**目标**：恢复上游的语音小组集成——ping 的「团队上下文」优先用 Simple Voice Chat 的**分组（group）**（不是「团队」）：同组成员才互见（AUTO/TEAM_ONLY 时），服务端受众判定同样按 group 走。缺省时按 `VoiceChat > FTB Teams > 原版队伍 > NONE` 回落（`TeamContextHandler.java:15-30`）。

**改动清单**

| # | 改动 | 位置 | 说明 |
|---|---|---|---|
| 1 | **整体替换** `VoiceChatIntegration`（37 行） | `src/main/java/nx/pingwheel/common/integration/VoiceChatIntegration.java` | 与上游同名文件 **逐字节一致**（`diff` 无差异、SHA256 相同）：`implements VoicechatPlugin` + `@ForgeVoicechatPlugin`；`serverApi`/`clientApi` 由 `Object` 改为 `VoicechatServerApi`/`VoicechatClientApi`；注册 `VoicechatServerStartedEvent`/`ClientVoicechatInitializationEvent`；`getPluginId()` 返回 `Global.MOD_ID`（`pingforit`） |
| 2 | **整体替换** `VoiceChatWrapper`（43 行） | `.../VoiceChatWrapper.java` | 逐字节一致：`getGroupId(Player)` → `serverApi.getConnectionOf(uuid).getGroup().getId()`；`getSelfGroupId()` → `clientApi.getGroup().getId()`；两者都先判 `ModContext.HasVoiceChat` + `IntegrationLinkGuard`，`LinkageError` 时降级为 `empty` |
| 3 | **注册 entrypoint** | `src/main/templates/fabric.mod.json:16` | `"voicechat": ["nx.pingwheel.common.integration.VoiceChatIntegration"]`，键名与上游 fabric 一致；**未加** `depends`/`recommends`（上游也没加） |
| 4 | **构建期检查泛化** | `build.gradle:78-109` | 抽成 `attachIntegrationJarCheck(taskName, requiredPrefix, message)` 工厂；现有两个检查：`compileClientJava` 要 `modmenu-`、**`compileJava` 要 `voicechat-`**（前缀带连字符，所以 `voicechatprofiles-*.jar` 永远不会误满足）；两条仍挂在任务 `doFirst`（**编译时**），配置期不受影响 |
| 5 | **CI 用同一件产物** | `.github/workflows/build.yml:41-42` | voicechat 从 API-only jar 改为与本地一致的 `voicechat-fabric-2.6.27+26.3.jar`（Modrinth CDN 固定版本 URL），消除「CI API jar / 本地 full jar」的编译面漂移（正是 56 号审查的 B2） |

调用点未改（本来就通）：服务端 `TeamContextHandler`（voicechat group > FTB > vanilla scoreboard）、客户端 `ClientTeamContextHelper`。

**保真性**：两个类与上游**逐字节一致**、entrypoint 键名一致、插件 id 一致、**未新增 `depends`/`recommends`**。

**保真差异：无新增。** `@ForgeVoicechatPlugin` 在 Fabric 上不参与装载（该注解只服务 Forge 侧的插件发现），Fabric 侧由 `voicechat` entrypoint 触发 —— 上游的 fabric 产物也是**同一个类 + 同一个注解**，属上游原样，非移植引入。

**校验**

| 项 | 结果 |
|---|---|
| `bash tools/compile-check.sh`（Lead 2026-11-03 实测） | `main errors=0 classes=266` / `client errors=0 classes=126`（**class 数不变**：stub 原地替换）；`run/mods` 命中名单含 `voicechat-fabric-2.6.27+26.3.jar` |
| 独立审查 | `docs/reviews/56-m9-2-voicechat-review.md`：**通过、无阻塞**（逐字节一致 / Fabric 只认 entrypoint、注解惰性 / 服务端安全 / API 面 `javap` 核验 / `LinkageError` 覆盖 / 未碰 M9-1）。两处非阻断：**B1** `serverApi` 字段解析在 `try` 之外（上游同形）；**B2** CI 与本地编译面 → 已由改动 #5 消除 |
| `run/mods` 里 voicechat 产物体量 | `voicechat-fabric-2.6.27+26.3.jar`（5.6 MB / 704 class），其中 `de/maxhenkel/voicechat/api/*` **104 个 class**（实测 `unzip -l` 计数；56 号审查记的也是 104） |

**实机验收（2026-11-03 起）✅ 通过**

| 项 | 结果 |
|---|---|
| 客户端 | `run/logs/latest.log:94-98`（2026-11-04 22:28:36，58 mods）：`Loading plugins` → **`Loaded 1 plugin(s)`** → `Initializing plugins` → **`Initialized 1 plugin(s)`** → **`Registering events for 'pingforit'`**（M9-1 那次是 `0`） |
| 归档全量核对（1号 逐档 gunzip 复核） | `run/logs/*.log.gz`：**2026-10-30 / 10-31 / 11-01 的 18 个归档全部 0 条**；**2026-11-03 起（最早 `2026-11-03-1.log.gz`，20:06:20）到 2026-11-04 的 13 个归档各 1 条** `Loaded 1 plugin` → 「2026-11-03 起每轮均有」成立 |
| 专用服务器（2026-11-04 16:52） | 同样 `Loaded 1 plugin(s)` / `Initialized 1 plugin(s)` / `Registering events for 'pingforit'`（见 `AGENTS.md` §6 V18 行） |

**未覆盖**：「同组互见 / 未装时回落原版队伍」是**双客户端行为**，仍在 M5 受众验收里做；本项只验到「插件已被加载且注册了事件」。

**剩余事项**

- ~~实机验证~~ → **已完成**（2026-11-03 起每轮均有，见上表）。
- 服务端侧同组判定需**双客户端**复测（与 M5 的受众验收合并做更省事）。
- M9-3（DH）见下节；FTB Teams / Sable 平台强制跳过不变（`24` #17/#18）。

### M9-3 · Distant Horizons 集成（2026-11-04）✅ 实现 + 复审 + 实机通过

**目标**：把 `DistantHorizonsIntegration` 从 76 行 stub 换回上游真实实现 —— 客户端射线在原版视距外时，交给 DH 的 LOD 地形做**异步远距离射线**（4096 格），命中则投影成方块目标；失败/未装则回退普通同步射线。

**改动清单**

| # | 改动 | 位置 | 说明 |
|---|---|---|---|
| 1 | **stub → 上游实现** | `common/src/main/java/nx/pingwheel/common/integration/DistantHorizonsIntegration.java` | 76 行 → **201 行**（与上游仅 **4 行 javadoc 缩进**差异）；`rootCause` **未动**；`traceDistant` / `traceDistantAsync` / `logVersionDiagnostics` / `logUnguardedLinkFailure` 全部回填 |
| 2 | **调用点零改动** | `fabric/src/client/java/nx/pingwheel/common/client/ClientPingRuntime.java:722-758` | 与上游 `:720-756` 逐字一致（flag 门 / `LinkageError` 兜底 / `!scheduled` 回退都在），所以本次**只换实现** |
| 3 | **方案 A：编译期只用 API jar** | 新目录 `compile-libs/` + `buildSrc/src/main/groovy/multiloader-common.gradle:62-70` | `compile-libs/DistantHorizonsApi-7.2.0.jar`（363 318 B，Modrinth 官方件）只做 `compileOnly`；**`DistantHorizons-*` / `DistantHorizonsApi-*` 已从 `ext.integrationJars` 剔除**，完整 jar 只留在 `run/mods` 做运行时 |
| 4 | **检查与脚本** | `common/build.gradle:18-35`、`tools/compile-check.sh|.ps1` | `compileJava` 上加 `attachIntegrationApiJarCheck("compileJava","DistantHorizonsApi-",…)`（日志前缀 `compileOnly integration API jars (<task>)`）；两脚本新增 `compile-libs` 段（0 命中只警告） |
| 5 | **CI** | `.github/workflows/build.yml:28-51` | 下 **API jar 到 `compile-libs/`**，**删除完整 DH jar 的下载**（CI 只 build、不启动游戏）；`compile-libs/*.jar` 已入 `.gitignore`（README/.gitkeep 入库） |

**为什么用方案 A**（3号 复审第 2 轮提出的 B2 类问题）：编译期只吃 **API** 能让「接口面」与「运行时实现」解耦 —— 完整 jar 若留在编译类路径，`javac` 会看到约 100 个内部类，任何对内部 API 的误用都不会被编译期拒绝，且「CI 用完整 jar / 本地用 API」会产生编译面漂移。现在两处**统一为 API 7.2.0**。

**工具修复（重要）**：官方 API jar 内含 **155 个 `.java`**，`javac` 默认把 classpath 当 sourcepath → 隐式编译出 100 个 `com/seibel/**` class，`compile-check` 计数虚高（262 → 362）。两个脚本已加 **`-implicit:none`**，计数恢复 **262 / 4 / 126**、输出目录无 `com/seibel`。

**复审结论（`docs/reviews/64-m9-3-dh-integration-review.md`）**：**通过、无阻塞** —— 保真（与上游仅 javadoc 缩进差）、异步契约（true/false、回调线程、call-site `game.execute`）、构建隔离（完整 jar 确实被排除出编译面）三项均成立。非阻断项：① `terrainRepo` 未单独判空（上游也没有；NPE 会被 future 捕获 → 走 empty 回调）；② tools 脚本 0 命中只 warning（真正的 fail-fast 在 Gradle）；③ 建议补一次「无 DH 的 runClient 冒烟」。

**实机验收（2026-11-04 22:28–22:31，装 DH 3.3.4）✅ 通过**

| 项 | 结果 |
|---|---|
| 环境 | `run/logs/latest.log`（498 行；22:28:21 启动 → 22:31:27 `Stopping!`）：**58 mods**，mod 列表含 `distanthorizons 3.3.4`（`:4`）；`run/options.txt` = `renderDistance:16`（=256 格） |
| **DH API 探针** | **`[PingForIt] Distant Horizons API Version: 7.2.0`**（`:102`，`(pingforit)` logger）→ `logVersionDiagnostics` 真的执行了（旧 stub 没有这一行） |
| **异步路径零失败** | `distanthorizons schedule failed` / `distanthorizons trace failed` / `distanthorizons integration disabled` **各 0 条**（`latest.log` 与 `debug.log` 全文检索；前两条走 `debugException`，只在 `debug.log` 里可见，所以两处都要查） |
| **闭环** | `send ping` **36** / `marker created` **36**（严格 1:1，0 reject/deny）：**BLOCK 22 / LOCATION 14**；方块目标 22 次（`草方块`×16 / `土径` / `橡木木板`×2 / `玻璃板` 等），`前往 此处`（LOCATION 回落）14 次 |
| **视距外命中（决定性证据）** | **用户提供的客户端截图（HUD）显示 `草方块 370.8m` 的 BLOCK 标记**（同帧还有 `此处 727.2m / 584.3m / 561.3m`）—— `renderDistance:16` 只有 256 格，370.8 m 处没有加载区块，原版 `Clip` 必然 MISS，该 BLOCK 只可能来自 **DH 异步射线**。`send ping`/`marker created` 只记 `requestId|markerId|kind|pingType`，**距离不是日志字段**，故本条为**截图证据**而非日志证据 |
| 其余 | **0 条 `Mixin apply failed`**；`[PingForIt] Init` / `ServerConfig` / `ClientConfig` / `ping outline pipeline registered`（`:140`）/ `block outline pass registered`（`:141`）各 1 条、`block model glow active`（`:314`）1 条；ERROR 3 条全为账户/Realms 噪声（`:147`/`:179`/`:204`），WARN 5 条全外部（Sodium CNF / PerfOS / voicechat debug / DH 的 G1 / `end_of_frame`） |
| DH × OIT | DH 照旧强制关 OIT（`Disabling improved transparency…` `:263`，`:261`/`:262` 另关 vanilla clouds 与 chunk fade）→ 与 `24` **#19** 一致：装 DH 时 `outline` 回退分支仍不可达 |

**未覆盖**：**「不装 DH」未单独跑**（本轮 58 mods 含 DH）。依据：入口是 `ModContext.HasDistantHorizons` 布尔短路 + `try/catch LinkageError`（`ClientPingRuntime.java:722-756`），未装时根本不解析 DH 类（复审 `64` 的静态结论）；建议并入 M10 验证批顺手覆盖。

**剩余事项**：`DH × OIT 互斥策略`（装 DH 时 OIT 被关，`outline` 分支永不触发）仍需在 `24` 登记或加诊断开关（`AGENTS.md` §9.1 C 组）——**本轮实机已复现该前提**（`:261`–`:263`）。

## P0 · 重构后验收（2026-11-03）✅ P0 收口（V13–V15 受限未验证）

> **路径口径说明（以 2026-11-03 为界）**：此前的文档可能仍写 `src/main/java` / `src/client/java` / 根 `build/libs/`；**现行布局**见 `AGENTS.md` §2（`common/src/main`、`fabric/src/main|client`，产物在 `fabric/build/libs/`）。**历史记录不改**：M0–M8 各节与 `docs/reviews/*` 是当时的事实快照，保留原样以便追溯。
>
> 背景：工程在 `f18e31e` 重构为 `common/` + `fabric/` 多模块，随后两个修复：`e8790a5`（dev 合并 common 资源）、`d7c2975`（OIT 下描边改投 `outline` 相位，登记 `docs/reviews/24-fidelity-constraint-audit.md` **#19**）。本节记录重构后的实机验收（清单：`docs/restructure/02-p0-in-game-checklist.md`；日志审计：`docs/restructure/03-p0-log-audit.md`）。

### P0.1 逐项状态

| 项 | 内容 | 状态 |
|---|---|---|
| **V1** | `clean build` + 两行 `compileOnly integration jars` 清单 | ✅ 用户已成功构建，R2–R4 跑的都是 22:13 之后的新产物 |
| V2 | 产物自查（class / mixins / 无重复条目） | ⏸ 可选，未做 |
| **V4** | 启动干净（不卡加载条 / 无 `Mixin apply failed` / 五条初始化日志） | ✅ **R1–R4 全部通过**（启动→`Sound engine started` 25–27 s；0 条 `ClassNotFoundException: nx.pingwheel`） |
| **V6** | Mod 列表图标 | 🟡 **日志级已过**（资源包索引含 `pingforit`），图标仍待肉眼确认 |
| **V7** | 设置界面中文 / 无裸 key | 🟡 需肉眼最终确认 |
| **V8** | ping 音效 | ✅ 日志级（`Missing sound for event` 0 条） |
| **V9** | VoiceChat 插件 | ✅ **R1–R4 每轮 `Loaded 1 plugin(s)` / `Initialized 1 plugin(s)` / `Registering events for 'pingforit'`** |
| **V11** | Mod Menu 配置入口 | 🟡 用户反馈「大致没问题」，但未逐项留证 |
| **V12** | 默认设置下描边可见 | ✅（日志级：`improved transparency is ON` 0 条 + 渲染路径有提交记录） |
| **V13 / V14 / V15** | OIT 下描边 / 颜色比对 / 关掉后正常 | ⏸ **受限未验证**：本环境（MC 26.3 + Fabric Loader 0.20.1 + **无 DH**、OIT=true 的 57-mod 会话）**从未触发**该回退路由（见 **P0.5**，结论登记 `24` #19） |
| **V16** | `send ping` : `marker created` 1:1 | ✅ **32 : 32**（R4，0 reject/deny） |
| **V17** | 0 条本模组 ERROR | ✅（R1–R4 每轮 3 条 ERROR 全为账户/Realms 噪声） |
| **V18** | `:fabric:runServer` 冒烟 | ✅ **通过（2026-11-04 16:52）**：根级 `runServer` 别名 → `Done (2.998s)!` → `stop` 正常；**47 mods**、0 ERROR、0 class error、0 mixin fail（见 **P0.3**） |
| **V19** | 发布 jar 装进干净 mods | ⏸ 未做 |

### P0.2 当前遇到的问题

**1）根项目没有 Loom 的 run 任务**

| 项 | 内容 |
|---|---|
| 现象 | `./gradlew runServer`（根级）报 `Cannot locate tasks that match ':runServer' as task 'runServer' not found in root project 'Ping-For-It'.`（2026-11-03 22:4x 用户实测） |
| 根因 | 重构后根 `build.gradle` 只 `apply false` 引 loom，**根项目不是 loom 项目**；`runClient`/`runServer` 是 Loom 给 `fabric` 子项目建的 |
| 处理 | 推荐命令带前缀：`./gradlew -g D:/Gradle :fabric:runClient` / `:fabric:runServer`；**2026-11-04 已在根 `build.gradle` 注册 `runClient`/`runServer` 转发别名**（`dependsOn ':fabric:runX'`，无 action），根级写法也可用（仍推荐显式）；首次 `:fabric:runServer` 会在仓库根 `run/` 生成 `eula.txt`（`eula=false`），改成 `eula=true` 再跑一次 |
| 状态 | ✅ 文档已改（`AGENTS.md` §3/§4/§7.18、`README.md`、`02-p0-in-game-checklist.md` V4/V18）；**根级别名已于 2026-11-04 加上** → `./gradlew runServer` 现在可用；**V18 已于 2026-11-04 16:52 用它跑通**（见 §P0.3） |

**2）装了 DH 时 OIT 不可测**

| 项 | 内容 |
|---|---|
| 现象 | `run/options.txt` 是 `improvedTransparency:true`，但每轮进世界 DH 都打 `Disabling improved transparency... This is done to fix vanilla chunks (specifically water blocks) not fading into Distant Horizons LODs`（R4 `latest.log:270`；`:267`–`:269` 还依次关了 vanilla clouds / chunk fade） |
| 根因 | DH 主动改原版选项；我们读到的 `improvedTransparency` 恒为 false → 永远走经典 `afterTerrain`，`improved transparency is ON …` 永不打印 |
| 处理 | 测 V13–V15 前把 `run/mods/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar` 临时移出（源码 0 处 DH 引用、编译期检查只查 `modmenu-`/`voicechat-`，移走不影响 `build`），测完放回 |
| 状态 | ⏸ **未处理**（V13–V15 仍空转），方法已写进 `AGENTS.md` §7.19 与清单前置条件 |

**3）「model hook not observed」WARN 是误报**

| 项 | 内容 |
|---|---|
| 现象 | R4 `latest.log:369`（22:38:44）`(pingforit) entity-block glow: model hook not observed on 120 glowing dispatches; entity_block stays on the wireframe` |
| 根因 | 用户 ping 的是**营火**：`CampfireRenderer` 只提交 `submitItem`、**没有 `submitModel`**，所以 `modelHookApplied` 永不置位，累计 120 次派发即报 WARN。告示牌这类 item/text-only BER 同理；营火另有 C1 烘焙模型兜底，WARN 里「stays on the wireframe」也不准确 |
| 处理 | 2号 正在把该日志**降为 DEBUG 并改文案**（只动日志级别与措辞，不动渲染行为） |
| 状态 | ✅ 定性完成（`docs/reviews/61-model-hook-warn-audit.md`：**误报（诊断缺陷），不是重构回归，也不是 DH/OIT 导致**）；代码改动待 2号 |

### P0.3 V18 · 专用服务器冒烟（2026-11-04 16:52）✅

> 命令：`gradlew.bat -g D:\Gradle runServer`（**根级转发别名生效**，Gradle 实际执行 `:fabric:runServer`）。日志：`run/logs/latest.log`（16:52:02–16:52:51，201 行）。

| 观察点 | 结果 |
|---|---|
| 服务端启动 | `:102` `Starting minecraft server version 26.3` → `:107` `Preparing level "world"` → `:157` **`Done (2.998s)! For help, type "help"`** |
| 本模组服务端路径 | `:61` `[PingForIt] Init` → `:62` `Loaded config type=ServerConfig` —— **`common` 源集在专用服务器上跑通，无客户端类误引** |
| VoiceChat 服务端侧 | `:65-68` `Loaded 1 plugin(s)` / `Initialized 1 plugin(s)`；`:69` `Registering events for 'pingforit'` |
| 环境 | **47 mods**（含 `pingforit 0.4.2-pfi-beta1`、`distanthorizons 3.3.4`、`voicechat 2.6.27+26.3` 及嵌套 `voicechat_api`） |
| 错误面 | **0 条 ERROR/FATAL**、**0 条** `NoClassDefFoundError`/`ClassNotFoundException`、**0 条** `Mixin apply failed`；WARN 仅 3 条且全外部（PerfOS 注册表、voicechat debug mode、DH 的 G1 GC 提示） |
| 关停 | 用户 `stop` → `:170` `Stopping server` → 16:52:51 DH 关闭数据库后正常退出 |

**前置修复（已登记 `AGENTS.md` §7.20）**：`run/eula.txt` 曾被 PowerShell 写成 **UTF-16 LE + BOM**（`ÿþe\0u\0l\0a\0…`），服务端读不到 `eula=true`；改用 `"eula=true" | Set-Content -Encoding ascii run\eula.txt` 后通过。**这是环境/工具链坑，不是模组问题。**

### P0.4 结论

**P0 收口**：**除 V13–V15（⏸ 受限未验证，见 P0.5）与 V19（⏸ 可选）外全部通过** —— 0 条本模组 ERROR、0 Mixin 失败、VoiceChat 插件加载、闭环 32:32、资源包索引正常、专用服务器冒烟通过、V18 ✅。V13–V15 不阻塞发布（描边实际仍由我们的 depth-off 管线绘制，仍可见、仍穿墙；唯一差别是被半透明物体叠加时可能被染色）。
### P0.5 V13–V15 受限未验证（2026-11-04）⏸

> 会话：2026-11-04 21:24:44–21:25:27（`run/logs/latest.log` + `debug.log`），**57 mods，`run/mods` 里已无 DH**，`run/options.txt` 运行期间 `improvedTransparency:true`（运行结束后的 21:27:29 被改回 `false`）。

- **已排除的外部因素**：① `Disabling improved transparency` **0 条**（DH 不在场）；② mixin **已织入**（`debug.log:2547` `Mixing SubmitNodeCollectionMixin from pingforit.mixins.json into …SubmitNodeCollection`）；③ pass **确实在提交**描边（`debug.log` 的 `block outline: submitted=0/1/2/3`，21:25:17–22）。
- **结果**：`[PingForIt] improved transparency is ON …` **仍 0 条**（已试两项修复：门控改读 `Options.improvedTransparency`、`isCustom` 改「实例 → 注册名回退」）。
- **实际行为**：描边由我们的 **depth-off 自定义管线**在 solid 桶绘制 —— **仍然可见、仍然穿墙**；与回退路径的唯一差别是「被半透明物体叠加时可能被染色」。
- **定性**：记为**已知限制**，**不阻塞发布**；结论与后续探针建议登记在 `docs/reviews/24-fidelity-constraint-audit.md` **#19 实测结论**。
## 剩余工作总表（2026-11-01 盘点，M0–M8 完成后）

> 目的：确保没有遗漏。每项标注**归属阶段 / 验收方式 / 状态**。

| # | 项 | 阶段 | 验收方式 | 状态 |
|---|---|---|---|---|
| 1 | **M5 服务端权威验收**：受众快照、赢家选择、移除仅本人、限流、过期、断线重连 | M5 | **双客户端互测**（两个 runClient 进同一局域网世界） | ⬜ 代码已存在，未正式验收 |
| 2 | **M9 集成可行性调研**：ModMenu / VoiceChat / FTB Teams / DH / Sable 的 26.3 版本与接入代价 | M9 | 调研报告 `53-m9-integration-feasibility.md` | ✅ 已交付（**做 3：ModMenu/VoiceChat/DH；跳过 2：FTB Teams/Sable 无 26.3 构建**） |
| 3 | M9 逐项接入（只做调研判定「可做」的） | M9 | 装/不装各跑一次，降级路径正确 | ✅ **M9-1（ModMenu）实现 + 实机通过（2026-11-03）**；✅ **M9-2（VoiceChat）实现 + 实机通过（2026-11-03 起每轮 `Loaded 1 plugin(s)`，最早 `2026-11-03-1.log.gz`）**；✅ **M9-3（DH）实现 + 复审 + 实机通过（2026-11-04，方案 A，装 DH 3.3.4，远距离 370.8 m BLOCK 命中·用户截图）** |
| 4 | **M8b 自定义资源包补测**：替换 `assets/pingforit/textures/ping.png` 后 HUD/轮盘显示自定义图标 | M8 | 需要用户提供资源包 | ⏸ 等资源包 |
| 5 | LICENSE 换 **MIT** + 生成 `THIRD-PARTY-NOTICES` 并入 jar | M10 | 上游是 Apache-2.0 + 第三方声明，移植版按计划改 MIT | ⬜ |
| 6 | `runServer` 冒烟：专用服务器启动无 `NoClassDefFoundError`（确认 main 不误引客户端类） | M10 | 用户终端 `gradlew :fabric:runServer`（或根级别名）；首次先改 `run/eula.txt` 为 `eula=true`（**ASCII**，见 `AGENTS.md` §7.20） | ✅ **已提前于 P0 完成（2026-11-04 16:52）**：`Done (2.998s)!` / 47 mods / 0 ERROR，见 §P0.3 |
| 7 | CI 校验：workflow 覆盖 `clean build` + 产物上传 + jar 元数据检查 | M10 | GitHub Actions 绿 | ⬜ |
| 8 | locale 补键：8 个语言文件缺失键全量扫描 | M10 | 脚本比对所有 `translatable` key | ⬜ |
| 9 | 188 个测试类：重建测试基座（需 26.3 编译产物），能救多少救多少 | M10 | `gradlew test` | ⬜ |
| 10 | 性能回归：繁忙世界（大量 BER/实体）下的 HUD/光晕开销 | M10 | 对比空闲/繁忙帧时 | ⬜ |
| 11 | **OIT 复测**（M7a 遗留，非阻塞）：透明物体叠加顺序 | M7 | 实机看透明方块后方的线框 | ⬜ 可选 |
| 12 | `改进方案.md` 里用户正在写的条目 | — | 用户决定后处理 | ⏸ 用户暂缓 |
| 13 | `render/spike/OutlineExperiment` 实验残留：OIT 复测后清理 | M7/M10 | 删除并确认无引用 | ⏸ 保留中（用户已确认保留） |

**已登记的平台强制差异**（无需处理，见 `24-fidelity-constraint-audit.md`）：#9 客户端权限门、#10 EditBox 回退、#11 自写 tab 按钮、#12 空 tab layout、#13 实体描边 targetType 白名单、#14 实体快照简化、#15 tab 宽度复刻、#16 end_portal/end_gateway 自定义几何无法描边。

## 决策执行情况

| # | 决策 | 状态 |
|---|---|---|
| D1 | 只保留 Fabric 端 | ✅ M1 已按单端调整 |
| D2 | mod id → `pingforit` | ✅ **M2 完成**：元数据 id / mixins 文件名 / `assets/pingforit/` / 通道命名空间已统一 |
| D3 | 包名保留 `nx.pingwheel` | ✅ **M2 完成**：302 个类全部按 `nx.pingwheel.*` 落位，无半改 |
| D4 | 产物名 `Ping-For-It-<ver>-fabric-26.3.jar` | ✅ **完成（M3 构建实测）**：`build/libs/Ping-For-It-1.0.0-fabric-26.3.jar`（615 091 B）；classifier 在 `jar` 任务（`5003f48`），no-remap 插件下即最终产物 |
| D5 | 保留 ServiceLoader | ✅ **M2 完成**：5 个 SPI（main 3 + client 2）与 services 文件一一对应 |
| D6 | 不引入 Parchment | ✅ M1 保持 official |
| D7 | 移除 Lombok | ✅ **完成（M3a `bed57c3`）**：8 文件手写 getter/setter，依赖已删；副作用 S1（`ClientConfig` 丢 `hashCode`）已在 M3b 修复 |
| D8 | 测试基座放 M10 | ⬜ M10 |
| D9 | 集成放发布前 | 🟡 **已桩化并迁出**（DH/VoiceChat/FTB/Sable 桩化，ModMenu 与渲染/UI 子系统进 `deferred/`），**M9/M7 恢复** |

> 注：**M7-prep 的分支试验不改变 D1–D9 任何决策**；「世界空间描边 → M7」与「测试基座 → M10」（D8）仍然有效。

---

## 事故记录 · 2026-10-29 23:07 工程目录被清空

### 发生了什么

- **23:07:35**：工程目录内容被外部操作清空（IDEA 日志记录 `Cannot find project config directory`、`not a git repository ... .git`），只剩 `.idea`。删除**绕过回收站**。
- **23:20–23:40**：用 IDEA Local History 回滚 → **失败**。它只对“在 IDE 里打开过”的文件保存内容，工具写入的文件只有元数据，回滚产出 **357 个项目文件为 0 字节**（外加 621 个 `Mod_Project` 空文件）；回滚过程还把工作区**二次清空**（23:38）。
- **结论：IDEA Local History 不能作为恢复手段。**

### 怎么恢复的

| 来源 | 内容 | 保真度 |
|---|---|---|
| `Downloads\Minecraft-Ping-For-It-0.4.2-pfi-beta1.tar.gz`（3.28 MB，回收站另有一份） | 上游 1.21.1 源码 513 java | **逐字节原样** |
| DSH 会话日志（`~/.dsh/sessions/--F-Minecraft_Mod_Project-...--/*/session.v4.jsonl.zstd`，16 个会话 / 33,034 事件 / 1,361 次文件操作，多帧 zstd 解压） | src java 与 docs 共约 100 个文件，含 M6.5 的 `MarkerHudRenderer`/`MarkerLabelRenderer`/`WheelHudRenderer` | **逐字节原文**（有完整 write 的 21 个已验证 20 个一致） |
| `Downloads\ping-for-it-template-26.3.zip` | `gradle-wrapper.jar`、`gradlew`、`gradlew.bat`、`.github/workflows/build.yml` | 原样 |
| 文档与上下文 | `AGENTS.md`、`docs/reviews/09–12`、`TEAM.md` | 全文重写 |

**重建方式**：上游源码全量落位 → 重做 M2/M3 的机械迁移（`ResourceLocation→Identifier` 90 处、`PayloadTypeRegistry.*Play`、`ComponentSerialization.CODEC`、`KeyMapping.Category`、`InputConstants` 等 14 类）→ Sable 集成整体移入 `deferred/` 并留无依赖桩（`Optional.empty()`，语义 = 集成不可用回退 anchor）。

### 丢失内容（不可恢复）

1. **`.git` 全部历史**：`pre-port` / `port-M2` / `port-M3` / `port-M4` / `port-M6.5` 五个 tag 及其 commit 全部消失。新仓库从零建立，里程碑日期与验证结论保留在本文档。
2. `run/logs/*`：M4「22/22 配对」、M6「108 对 / pingType 3/3 一致」、M6.5 会话的**原始日志**没了，结论仍在文档。
3. `run/saves/*`（测试世界）、`run/config/*`（首次启动重新生成）、`run/dsh-logs/*`。
4. `build/` 与 `.gradle/` 产物（可重建）。

### 恢复后的验证

| 项目 | 结果 |
|---|---|
| `tools/compile-check.sh` | **main 0 error / 262 class；client 0 error / 83 class** |
| `gradlew -g D:/Gradle build --offline` | **通过**（用户实测，2026-10-30 00:1x） |
| 结构 | `deferred/` 96（92 原样 + 4 Sable）、`src/main/java` 162、`src/client/java` 55、资源与 tools 齐备（**2026-11-02 现值：95 / 166 / 80**，见 `AGENTS.md §1`） |
| 待补 | 实机冒烟（ping / 掉落物 / 轮盘 / 屏外箭头四场景）、47 个「无原文」文件的逐项复核 |

> **新基线**：`restore: rebuild the project after the 2026-10-29 directory wipe` 为恢复后第一个 commit，后续阶段以它为回滚点。