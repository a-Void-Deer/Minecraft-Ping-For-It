# 01 · 现状清册、路径引用审计与目标映射

> 分析者：analyst（只读）｜时间：2026-11-03 19:0x（Asia/Shanghai）｜分支 `26.3_Fabric` @ `74ae0fd`
> 规格来源：[00-plan.md](00-plan.md)（Lead 定稿的迁移规格）。本文件是它的事实底座：
> 清册 + 全量路径引用审计 + 逐条目标映射 + 风险与待决问题。
> 所有“现文本”均为当次读取 / grep 的**逐字原文**，未做改写；所有行号均指**当前**（迁移前）文件。
>
> **⚠ 后记（2026-11-03 22:5x）**：本文件记录的是重构**当时**的映射。其中出现的**根级 `runClient`** 已被实测推翻 —— 重构后根项目**不是 loom 项目**，`./gradlew runClient` 会直接报 `Cannot locate tasks that match ':runClient'…`，**推荐带 `:fabric:` 前缀**（2026-11-04 起根级同名任务已改为转发别名，见 `AGENTS.md` §7.18）。其余历史路径保持原样以便追溯。

---

> **文档状态（2026-11-03 19:12 更新）**：§1–§9 记录的是**迁移前**基线（commit `74ae0fd`，工作树干净），
> 用于回答“搬什么 / 会撞什么 / 每一行该改成什么”；迁移已由实现者于 19:02–19:09 落地（`src/` 已消失）。
> **实际落地结果、逐条对账与残留风险见 §10**；两者冲突时以 §10 的实测为准。

## 0. 结论摘要（先读这一段）

1. **规模与 00-plan.md 吻合**：`src/main/java` **166** = `nx/pingwheel/common` **162** + `nx/pingwheel/fabric` **4**；
   `src/client/java` **80** = `nx/pingwheel/common` **76** + `nx/pingwheel/fabric` **4**；`deferred/` **95**（不编译）。
   资源：`src/main/resources` **16**、`src/client/resources` **3**、`src/main/templates` **1**。
2. **路径引用总量**：活文件（改了才不坏构建）**41 处**，活文档 **13 处**，历史文档 **516 处**（不改）。
   真正的硬编码构建路径只有 4 个文件：`build.gradle`、`tools/compile-check.sh`、`tools/compile-check.ps1`、`.github/workflows/build.yml`。
3. **`.gitattributes` 与 `.gitignore` 都不需要改**（`git check-ignore` 实测：`common/build/`、`fabric/build/`、`fabric/run/`、`common/.gradle/` 全部已被现有规则覆盖）。
4. **`deferred/` 必须留在仓库根**：它与活源码有 **18 个完全相同 FQCN**（`nx.pingwheel.common.screen.SettingsScreen` 等），
   一旦落进任何 source root 会立刻重复类。
5. **`tools/compile-check.sh` 的 main 遍其实带了客户端 MC jar**（三个 MC jar 全上），**不能**替代 Loom 的 split 闸门。迁移后重写时应让 common 遍只吃 `minecraft-common.jar`（实测落地走的是 grep 闸门路线，见 §10.3-4）。
6. **00-plan.md 没覆盖的 4 个坑**（详见 §6.1）：`LICENSE` 重命名会从 `LICENSE_pingforit` 变成 `LICENSE_fabric`（且 `from("LICENSE")` 会静默丢失）；
   两个 loom 项目会让根级 `./gradlew runClient` 变成**歧义任务**；`:common` 的依赖接线变体解析未验证；`publishing` 归属未定。

---

## 1. 清册（Inventory）

### 1.1 Java 文件计数（按 package root）

```
src/main/java      166 java
├── nx/pingwheel/common/**   162 java   （共享 / 服务端安全）
└── nx/pingwheel/fabric/**     4 java   （Fabric 平台入口 + 服务端侧 SPI 实现）
src/client/java     80 java
├── nx/pingwheel/common/**    76 java   （客户端逻辑，包名仍叫 common）
└── nx/pingwheel/fabric/**     4 java   （FabricClient / ModMenu / 客户端 SPI 实现）
deferred/           95 java             （不参与编译，包名 nx.pingwheel.*，见 §1.4）
参与构建合计 246 java（git ls-files 与 find 口径一致）
```

`src/main/java` 细分（`find | sed | uniq -c` 实测）：

| package | 文件数 | package | 文件数 |
|---|---:|---|---:|
| `nx/pingwheel/common` | 2 | `nx/pingwheel/common/marker` | 24 |
| `nx/pingwheel/common/chat` | 1 | `nx/pingwheel/common/math` | 12 |
| `nx/pingwheel/common/config` | 16 | `nx/pingwheel/common/name` | 7 |
| `nx/pingwheel/common/core` | 3 | `nx/pingwheel/common/network` | 18 |
| `nx/pingwheel/common/domain` | 13 | `nx/pingwheel/common/platform` | 4 |
| `nx/pingwheel/common/integration` | 9 | `nx/pingwheel/common/registry` | 2 |
| `nx/pingwheel/common/integration/externalblock` | 4 | `nx/pingwheel/common/resolve` | 8 |
| `nx/pingwheel/common/integration/sable` | 1 | `nx/pingwheel/common/resource` | 2 |
| `nx/pingwheel/common/integration/sable/server` | 3 | `nx/pingwheel/common/util` | 7 |
| `nx/pingwheel/common/interaction` | 10 | **小计** | **162** |
| `nx/pingwheel/common/interaction/cancel` | 5 | `nx/pingwheel/fabric` | 1 |
| `nx/pingwheel/common/interaction/state` | 9 | `nx/pingwheel/fabric/platform` | 3 |
| `nx/pingwheel/common/interaction/wheel` | 1 | **小计** | **4** |
| `nx/pingwheel/common/log` | 1 | | |

`src/client/java` 细分：

| package | 文件数 | package | 文件数 |
|---|---:|---|---:|
| `nx/pingwheel/common` | 1 | `nx/pingwheel/common/mixin` | 8 |
| `nx/pingwheel/common/client` | 11 | `nx/pingwheel/common/name` | 1 |
| `nx/pingwheel/common/client/diagnostics` | 1 | `nx/pingwheel/common/platform` | 1 |
| `nx/pingwheel/common/client/duration` | 2 | `nx/pingwheel/common/render` | 3 |
| `nx/pingwheel/common/client/hud` | 3 | `nx/pingwheel/common/render/spike` | 1 |
| `nx/pingwheel/common/client/marker` | 7 | `nx/pingwheel/common/screen` | 8 |
| `nx/pingwheel/common/client/outline` | 12 | `nx/pingwheel/common/util` | 2 |
| `nx/pingwheel/common/client/rate` | 2 | **小计** | **76** |
| `nx/pingwheel/common/client/resource` | 2 | `nx/pingwheel/fabric` | 1 |
| `nx/pingwheel/common/client/wheel` | 4 | `nx/pingwheel/fabric/integration` | 1 |
| `nx/pingwheel/common/command` | 1 | `nx/pingwheel/fabric/platform` | 2 |
| `nx/pingwheel/common/config` | 1 | **小计** | **4** |
| `nx/pingwheel/common/core` | 1 | | |
| `nx/pingwheel/common/integration` | 1 | | |
| `nx/pingwheel/common/integration/sable/client` | 1 | | |
| `nx/pingwheel/common/math` | 2 | | |

**同名包在两个源集里并存**（迁移后跨 `common` 与 `fabric` 两个模块，属预期；无任何 FQCN 冲突）：

```
nx/pingwheel/common          nx/pingwheel/common/name
nx/pingwheel/common/config   nx/pingwheel/common/platform
nx/pingwheel/common/core     nx/pingwheel/common/util
nx/pingwheel/common/integration
nx/pingwheel/common/math     nx/pingwheel/fabric
                             nx/pingwheel/fabric/platform
```

> `comm -12`（main ∪ client 的 FQCN 交集）实测**为空**，即 246 个类零重名。

### 1.2 资源文件（19 个）

| 路径 | 说明 |
|---|---|
| `src/main/resources/assets/pingforit/lang/{de_de,en_us,es_ar,fr_fr,pl_pl,tr_tr,zh_cn,zh_tw}.json` | 8 个语言文件 |
| `src/main/resources/assets/pingforit/sounds.json` | 音效事件定义 |
| `src/main/resources/assets/pingforit/sounds/ping.ogg` | 音效 |
| `src/main/resources/assets/pingforit/textures/{arrow,ping}.png` | 2 张贴图 |
| `src/main/resources/icon.png` | mod 图标（`fabric.mod.json` 里 `“icon”: “icon.png”`，必须落在 jar 根） |
| `src/main/resources/META-INF/services/nx.pingwheel.common.platform.IPlatformContextService` | 内容：`nx.pingwheel.fabric.platform.PlatformContextServiceImpl` |
| `src/main/resources/META-INF/services/nx.pingwheel.common.platform.IPlatformNetworkService` | 内容：`nx.pingwheel.fabric.platform.PlatformNetworkServiceImpl` |
| `src/main/resources/META-INF/services/nx.pingwheel.common.platform.IPlatformServerEventService` | 内容：`nx.pingwheel.fabric.platform.PlatformServerEventServiceImpl` |
| `src/main/templates/fabric.mod.json` | 30 行，唯一占位符 `“${version}”` |
| `src/client/resources/pingforit.mixins.json` | 19 行，`“package”: “nx.pingwheel.common.mixin”`、`“compatibilityLevel”: “JAVA_25”`、`client` 数组 8 项 |
| `src/client/resources/META-INF/services/nx.pingwheel.common.platform.IClientPlatformContextService` | 内容：`nx.pingwheel.fabric.platform.PlatformClientContextServiceImpl` |
| `src/client/resources/META-INF/services/nx.pingwheel.common.platform.IPlatformClientEventService` | 内容：`nx.pingwheel.fabric.platform.PlatformClientEventServiceImpl` |

### 1.3 脚本 / CI / 构建入口 / 顶层文件

| 类别 | 文件 | 备注 |
|---|---|---|
| 构建入口 | `settings.gradle`(13)、`build.gradle`(169)、`gradle.properties`(19) | 单模块 |
| Wrapper | `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.{jar,properties}` | Gradle 9.8.2 |
| 校验脚本 | `tools/compile-check.sh`(145)、`tools/compile-check.ps1`(165) | tools 下唯一 tracked 文件 |
| CI | `.github/workflows/build.yml`(58) | ubuntu-24.04 + JDK25 + `./gradlew build` + 上传 `build/libs/Ping-For-It-*.jar` |
| git 配置 | `.gitattributes`(19)、`.gitignore`(47) | 见 §2.6 |
| 顶层文档/资源 | `AGENTS.md`、`README.md`、`LICENSE`(1067 B, **MIT**；此前误记为 CC0 —— CC0 是当时 **jar 内** `LICENSE_pingforit` 的来源模板，根 `LICENSE` 自 `feb9452` 起就是 MIT)、`img.png`、`改进方案.md`、`docs/**`(76 tracked) | `Mod_Project/` 与 `run/` 被 gitignore |
| tracked 总数 | **454** 文件 | `git ls-files` 实测 |

### 1.4 `deferred/` 清册（95 java，不编译）

| 子目录 | 文件数 | 声明的 package | 与活源码 FQCN 冲突 |
|---|---:|---|---:|
| `deferred/client/outline/` | 66 | `nx.pingwheel.common.client.outline` | **6** |
| `deferred/screen/` | 8 | `nx.pingwheel.common.screen` | **7** |
| `deferred/render/` | 10 | `nx.pingwheel.common.render` | **3** |
| `deferred/mixin/` | 5 | `nx.pingwheel.common.mixin` | 0 |
| `deferred/client/integration/sable/client/` | 4 | `nx.pingwheel.common.integration.sable.client` | **1** |
| `deferred/resource/` | 1 | `nx.pingwheel.common.resource` | 0 |
| `deferred/fabric/integration/` | 1 | `nx.pingwheel.fabric.integration` | 0 |
| **合计** | **95** | | **18** |

冲突的完整 FQCN（`deferred/` **绝不能**进任何 source root）：

```
nx.pingwheel.common.client.outline.BlockDisplayPolicy
nx.pingwheel.common.client.outline.BlockDisplayWhitelist
nx.pingwheel.common.client.outline.BlockDisplayWhitelistEntry
nx.pingwheel.common.client.outline.BlockTagLookup
nx.pingwheel.common.client.outline.EntityOutlineSelection
nx.pingwheel.common.client.outline.EntityOutlineState
nx.pingwheel.common.integration.sable.client.SableClientProvider
nx.pingwheel.common.render.SelectionToggleNoticeRenderPolicy
nx.pingwheel.common.render.SelectionToggleNoticeRenderer
nx.pingwheel.common.render.WorldRenderContext
nx.pingwheel.common.screen.DeferredActionCoordinator
nx.pingwheel.common.screen.OptionUtils
nx.pingwheel.common.screen.ServerSettingsModel
nx.pingwheel.common.screen.SettingsCategoryCatalog
nx.pingwheel.common.screen.SettingsNavigationModel
nx.pingwheel.common.screen.SettingsScreen
nx.pingwheel.common.screen.SettingsScreenLayout
nx.pingwheel.common.screen.SettingsTitleWidget
```

> 这同时解释了 `AGENTS.md` §9.1 的 E6 实验（“把 `deferred/client/outline` 临时加入编译”）为什么会在 javac 阶段先撞重复类：
> 其中 6 个类与活源码同名同包。

---

## 2. 路径引用审计（Exhaustive path-reference audit）

检索口径：`grep -rInE` 上述 14 个关键字，覆盖 `*.gradle *.properties *.sh *.ps1 *.yml *.yaml *.json *.md *.java .gitattributes .gitignore`，
排除 `Mod_Project/ .git/ .gradle/ build/ .idea/ .tmp-* run/`。命令与原始计数见 §8。

级别：**MUST** = 不改就构建/CI 失败或骗人；**SHOULD** = 活文档，会误导操作；**HIST** = 历史记录，禁止改写；**NOCHG** = 已核实无需改。

### 2.1 `build.gradle`（18 处命中，整文件拆到两个模块）

| 行 | 现文本（逐字） | 迁移后 |
|---|---|---|
| 2 | `id 'net.fabricmc.fabric-loom' version “${loom_version}”` | **[MUST]** 根改 `… apply false`；`common/build.gradle` 与 `fabric/build.gradle` 各写 `id 'net.fabricmc.fabric-loom'` |
| 3 | `id 'maven-publish'` | **[MUST / 待决 Q11]** 暂留根；或两个子项目各自 apply |
| 13 | `// gradle network, so their jars are supplied locally in run/mods (see below).` | **[MUST]** 文案改“仓库根 `run/mods`”，实现改 `rootProject.file("run/mods")`（见 48/73 行） |
| 17 | `splitEnvironmentSourceSets()` | **[MUST]** 移入 `fabric/build.gradle`；**并新增**到 `common/build.gradle`（00-plan §2 已定，保闸门） |
| 37 | `// run/mods (which doubles as the dev runtime mods dir) instead of maven coordinates.` | **[NOCHG]** 语义不变（`run/` 仍在根） |
| 40 | `// tree: run/ is gitignored, so a fresh clone must supply them (CI does it with curl). The` | **[NOCHG]** |
| 48 | `dir: “run/mods”,` | **[MUST]** `dir: rootProject.file(“run/mods”),`（否则解析成 `fabric/run/mods`） |
| 56 | `// Same exclusion as tools/compile-check.sh\|.ps1, so a sources-only jar can never` | **[NOCHG]** 脚本仍在 `tools/` |
| 69 | `// Configuration-cache safety: run/mods is captured as a plain java.io.File and the jar list is` | **[NOCHG]** 若挪进 `multiloader-common.gradle`，文本随块搬 |
| 73 | `def integrationModsDir = file(“run/mods”)` | **[MUST]** `def integrationModsDir = rootProject.file(“run/mods”)` |
| 99 | `attachIntegrationJarCheck(“compileClientJava”, “modmenu-”,` | **[MUST]** 整块搬 `fabric/build.gradle`（ModMenuIntegration 只在 `fabric/src/client`） |
| 101 | `“modmenu-21.0.0.jar into run/mods/ ” +` | **[SHOULD]** 文案写明“仓库根 `run/mods`”，否则读者会以为是 `fabric/run/mods` |
| 107 | `attachIntegrationJarCheck(“compileJava”, “voicechat-”,` | **[MUST]** 整块搬 `common/build.gradle`（VoiceChatIntegration 在共享源集） |
| 109 | `“voicechat-fabric-2.6.27+26.3.jar (or voicechat-api-2.6.27.jar) into run/mods/ ” +` | **[SHOULD]** 同 101 |
| 113 | `[“compileJava”, “compileClientJava”].each { name ->` | **[MUST]** 守护块留根但改成 `:common:compileJava` / `:fabric:compileClientJava`，或下沉到各自模块 |
| 122 | `def generateModMetadata = tasks.register(“generateModMetadata”, Sync) {` | **[MUST]** 移到 `fabric/build.gradle` |
| 123 | `from “src/main/templates/fabric.mod.json”` | **[MUST]** 相对路径不变（相对 `fabric/`），实际指向 `fabric/src/main/templates/fabric.mod.json` |
| 129 | `sourceSets.main.resources.srcDir(generateModMetadata.flatMap { it.destinationDirectory })` | **[MUST]** 随 122 一起进 `fabric/build.gradle` |
| 132 | `dependsOn generateModMetadata` | **[MUST]** 同块搬迁 |
| 152 | `archiveClassifier = “fabric-26.3”` | **[MUST]** 进 `fabric/build.gradle` 的 `jar`（产物名不变） |
| 154 | `from(“LICENSE”) {` | **[MUST · 00-plan 漏了]** 在 `fabric/` 下解析为 `fabric/LICENSE`（不存在 → Gradle **静默跳过**，jar 里没有 LICENSE）。改 `from(rootProject.file(“LICENSE”))` |

> **LICENSE 重命名目标会变**：`rename { “${it}_$projectName” }`（156 行）里 `projectName` 现在是 `pingforit` → jar 内是 `LICENSE_pingforit`
>（已用 `unzip -l` 核实）。搬到 `fabric/` 后 `project.name == “fabric”` → 变 `LICENSE_fabric`。要么改用 `rootProject.name`，要么显式写死。
>
> `base { archivesName = “Ping-For-It” }`（6-8 行）同理进 `fabric/build.gradle`；`common` 若也设同名 `archivesName`，
> `:common:jar` 会产出同前缀 jar（只在 `common/build/libs/`，与 CI 的 `fabric/build/libs/Ping-For-It-*.jar` 不冲突，但会误导人）→ 见 Q12。

### 2.2 `tools/compile-check.sh`（7 处命中，整脚本重写）

| 行 | 现文本（逐字） | 迁移后 |
|---|---|---|
| 69 | `# M9 optional integrations: the very jars build.gradle compiles against (run/mods).` | **[NOCHG]**（`run/` 仍在根） |
| 70 | `# Uses the same glob list as build.gradle's integrationJars, so a stray jar in run/mods can` | **[NOCHG]** |
| 75 | `for jar in run/mods/*.jar; do` | **[NOCHG]**（脚本 `cd “$T”`，`T` 仍是仓库根） |
| 90 | `echo “compile-check: integration jars from run/mods: ${joined%, }”` | **[NOCHG]** |
| 92 | `echo “compile-check: no integration jars matched in run/mods (M9 integration classes cannot compile)” >&2` | **[NOCHG]** |
| 134 | `run_pass “main” “src/main/java” “$CP_BASE” “$OUT_WIN/main” “$OUT_MSYS/javac-main.log”` | **[MUST]** 拆两遍 `run_pass “common” “common/src/main/java”` 与 `run_pass “fabric-main” “fabric/src/main/java”` |
| 138 | `run_pass “client” “src/client/java” “$CLIENT_CP” “$OUT_WIN/client” “$OUT_MSYS/javac-client.log”` | **[MUST]** `“fabric/src/client/java”`，`CLIENT_CP` = fabric-main 输出 + common 输出 + 原 CP |
| 22 | `if [ ! -f build.gradle ]; then` | **[NOCHG]** 根仍有 `build.gradle`，守卫成立 |

新脚本还要补 00-plan §4.3 要求的 grep 闸门（当前不存在）：`common/src/main/java` 里不得出现 `net.minecraft.client.` / `com.mojang.blaze3d`。
**当前脚本做不到**：52-59 行把 `minecraft-common.jar`、`minecraft-client.jar`、`minecraft-client-only.jar` **三件全加进同一个 `CP_BASE`**，两遍共用（134/138 行），
所以 main 遍其实编得过客户端 import。→ 新脚本的 common 遍应只用 `minecraft-common.jar`。

### 2.3 `tools/compile-check.ps1`（6 处命中，与 .sh 同步重写）

| 行 | 现文本（逐字） | 迁移后 |
|---|---|---|
| 2 | `# Rebuilt 2026-10-29. Mirrors tools/compile-check.sh: MC jars from` | **[NOCHG]** |
| 69 | `# M9 optional integrations: the very jars build.gradle compiles against (run/mods).` | **[NOCHG]** |
| 70 | `# Uses the same glob list as build.gradle's integrationJars, so a stray jar in run/mods can` | **[NOCHG]** |
| 74 | `$modDir = Join-Path $T “run/mods”` | **[NOCHG]** |
| 85 | `Write-Output (“compile-check: integration jars from run/mods: ” + …)` | **[NOCHG]** |
| 87 | `Write-Output “compile-check: no integration jars matched in run/mods (M9 integration classes cannot compile)”` | **[NOCHG]** |
| 151 | `Invoke-Pass “main” (Join-Path $T “src\main\java”) $CP_BASE (Join-Path $OUT “main”)` | **[MUST]** 拆 `common` / `fabric-main` 两遍 |
| 158 | `Invoke-Pass “client” (Join-Path $T “src\client\java”) $clientCp (Join-Path $OUT “client”)` | **[MUST]** `“fabric\src\client\java”`；`$clientCp` 需拼 fabric-main 输出 |

> `.ps1` 的 `param([string]$Pass = “both”)`（11 行）与 `main|client|both` 分支（150/156 行）是 `.sh` 没有的接口，
> 建议保留并扩成 `common|fabric-main|fabric-client|both|all`。

### 2.4 `.github/workflows/build.yml`（9 处命中）

| 行 | 现文本（逐字） | 迁移后 |
|---|---|---|
| 28 | `# run/mods (the dev machine has no usable gradle network). run/ is gitignored, so a` | **[NOCHG]** |
| 32 | `# the build therefore needs network here (or pre-placed run/mods jars) to succeed.` | **[NOCHG]** |
| 35 | `mkdir -p run/mods` | **[NOCHG]**（`run/` 仍在仓库根） |
| 36 | `curl -fsSL --retry 3 -o run/mods/modmenu-21.0.0.jar \\` | **[NOCHG]** |
| 41 | `curl -fsSL --retry 3 -o “run/mods/voicechat-fabric-2.6.27+26.3.jar” \\` | **[NOCHG]** |
| 45 | `# API-only jar is smaller but would silently drift from run/mods.` | **[NOCHG]** |
| 46 | `curl -fsSL --retry 3 -o run/mods/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar \\` | **[NOCHG]** |
| 48 | `ls -l run/mods` | **[NOCHG]** |
| 57 | `path: build/libs/Ping-For-It-*.jar` | **[MUST]** `path: fabric/build/libs/Ping-For-It-*.jar`（`if-no-files-found: error` 会立刻暴露写错） |
| 51 | `run: ./gradlew build` | **[SHOULD]** 可保留（会命中 `:common:build` + `:fabric:build`），建议改显式 `./gradlew buildAll` |

### 2.5 活文档

| 文件:行 | 现文本（摘录，逐字） | 迁移后 |
|---|---|---|
| `README.md:20` | 产物 **`build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`**（649 KB / 345 class） | **[SHOULD]** `fabric/build/libs/…`；且 649 KB/345 class 已陈旧（当前 jar **771,558 B / 392 class / 464 entries**，2026-11-03 18:53 构建） |
| `README.md:41` | `- 产物在 `build/libs/`：`Ping-For-It-<version>-fabric-26.3.jar`。` | **[SHOULD]** `fabric/build/libs/` |
| `README.md:43` | `- 本地快速编译校验（不启动 Gradle）：tools/compile-check.sh` / `tools/compile-check.ps1`。` | **[NOCHG]** |
| `README.md:57` | `…当前 jar 内的 LICENSE 仍是模板自带的 CC0 文本…` | **⚠ 该行已过时（2026-11-04 订正）**：README 里已无此句；现 `README.md` 的「许可」段写的是 **MIT**（根 `LICENSE` 与上游 SHA256 一致）、jar 内为 `LICENSE_pingforit`、`THIRD-PARTY-NOTICES.md` 已一并入 jar。本行仅作当时审计快照保留；另订正原判断「jar 内条目会变 `LICENSE_fabric`」——实际一直是 `LICENSE_pingforit` |
| `AGENTS.md:12` | `src/main` **166** java + `src/client` **80** java + `deferred/` **95** java | **[HIST]** §0 是事故恢复记录，保留；建议加脚注指向本文 |
| `AGENTS.md:13` | `tools/compile-check` **0 error / 0 error**（两源集） | **[MUST]** 迁移后三遍 → `0/0/0`（三源集） |
| `AGENTS.md:14` | 产物 `build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`…649 KB / 345 class | **[HIST]** 2026-10-30 历史记录，加脚注即可 |
| `AGENTS.md:26` | `- 规模（2026-11-02 实测）：src/main 166 java + src/client 80 java + deferred/ 95 java…` | **[MUST]** 改为 `common/src/main` **162** + `fabric/src/main` **4** + `fabric/src/client` **80** + `deferred/` **95** |
| `AGENTS.md:34-38` | 目录职责代码块里 5 行（`src/main/java/`、`src/main/resources/`、`src/main/templates/…`、`src/client/java/`、`src/client/resources/`） | **[MUST]** 见 §2.5.1 整块替换 |
| `AGENTS.md:39` | `deferred/ 95 java …（原 96，M9-1 移出 modmenu/）` | **[NOCHG]** |
| `AGENTS.md:40` | `tools/compile-check.sh\|.ps1       本地 javac 两源集校验（已重建），不启动 Gradle` | **[MUST]** 改“三遍（common / fabric main / fabric client）” |
| `AGENTS.md:43` | `run/ build/ .gradle/ .idea/       运行与构建产物（gitignore）` | **[SHOULD]** 补 `common/build/ fabric/build/`；`run/` 仍在根 |
| `AGENTS.md:55` | `…本地回归用 tools/compile-check.sh（直接 javac）…` | **[NOCHG]** |
| `AGENTS.md:59` | `- splitEnvironmentSourceSets()：main 双端共用、client 仅客户端；…` | **[MUST]** 补“**common 与 fabric 两个模块都开**”“`common` 的 client 源集恒空，只作闸门” |
| `AGENTS.md:61` | `- generateModMetadata（Sync 任务）：模板 → build/generated/modMetadata…` | **[MUST]** 产物目录改 `fabric/build/generated/modMetadata` |
| `AGENTS.md:62` | `- java { release 25; withSourcesJar() }；jar { archiveClassifier = “fabric-26.3” }…` | **[MUST]** 说明这两条现在在 `fabric/build.gradle` |
| `AGENTS.md:86,87` | 实机验收记录里的 `run/logs/…` | **[NOCHG]** |
| `AGENTS.md:91` | `1. deferred/ 不在编译路径：类找不到是正常的…` | **[NOCHG]** |
| `AGENTS.md:96` | `6. run/ 下日志与世界只读：排障时复制出去。` | **[NOCHG]** |
| `AGENTS.md:109-112` | §7.15 的 `run/mods` 长条目（含 `fileTree(dir:“run/mods”,…)`、`compileClientJava`/`compileJava` 两个检查） | **[MUST]** 三处要改：① 写明“仓库根 `run/mods`”；② 挂载点改 `:fabric:compileClientJava` / `:common:compileJava`；③ 新增第 4 个同步点 `multiloader-common.gradle` 的 `ext.integrationJars` |
| `AGENTS.md:119` | `\| 2号 implementer \| … \| src/**、deferred/** \|` | **[SHOULD]** 写入范围改 `common/**`、`fabric/**`、`deferred/**` |
| `AGENTS.md:121` | `\| 4号 build-engineer \| … \| build/、run/、tools/ \|` | **[SHOULD]** 补 `common/build/`、`fabric/build/`、`buildSrc/` |
| `AGENTS.md:134` | `…Mod Menu 类从 deferred/ 迁回 src/client…` | **[SHOULD]** `src/client` → `fabric/src/client`（历史状态可保留） |
| `AGENTS.md:135` | `\| M10 \| 验证与发布：runServer、CI、测试基座、LICENSE → MIT…` | **[NOCHG]** |
| `AGENTS.md:145` | `\| E6 \| 把 deferred/client/outline 临时加入编译…` | **[SHOULD]** 补“会撞 6 个重复 FQCN，见 01 §1.4” |
| `AGENTS.md:160` | `- [ ] src/main 下 grep 不到 net.minecraft.client. / com.mojang.blaze3d` | **[MUST]** `- [ ] common/src/main/java 与 fabric/src/main/java 下 grep 不到 …` |
| `AGENTS.md:161` | `- [ ] 未把 deferred/ 误加回编译路径` | **[NOCHG]** |
| `docs/porting-plan.md:49` | `- 保留 splitEnvironmentSourceSets、generateModMetadata、jar 打 LICENSE。` | **[SHOULD]** 加脚注“2026-11-03 重构后分别位于 common/fabric 两模块，语义不变” |
| `docs/porting-plan.md:56` | `1. 整棵客户端子树（client / render / screen / mixin / CommonClient，116 文件）搬入 src/client/java，零改造。` | **[HIST]** M2 施工单，已完成 |
| `docs/porting-plan.md:63` | `- 验收：src/main 下 grep 不到 net.minecraft.client / com.mojang.blaze3d；两边都能编译。` | **[HIST]** 同上（验收口径在 AGENTS §10 更新） |
| `docs/porting-plan.md:103` | `…（src/client/java/nx/pingwheel/fabric/FabricClient.java:9-10,110-111）…` | **[SHOULD]** `fabric/src/client/java/…`（该引用用于定位活代码） |
| `docs/porting-plan.md:116` | `- verify 任务（单端版）、runServer 无 client 类加载、CI、…` | **[NOCHG]** |
| `docs/features.md:4` | `> 源码根：Mod_Project/…（只读）。简写：common/…/common = common/src/main/java/nx/pingwheel/common。` | **[NOCHG]** —— 迁移后这条简写**才第一次名副其实**（当前仓库里没有 `common/` 目录） |
| `docs/features.md:359` | `（src/main/java/nx/pingwheel/common/domain/TargetTypeCatalog.java:43-82）` | **[SHOULD]** `common/src/main/java/…/TargetTypeCatalog.java:43-82` |
| `docs/features.md:379` | `…已注册（src/main/templates/fabric.mod.json:16）…` | **[SHOULD]** `fabric/src/main/templates/fabric.mod.json:16` |
| `docs/features.md:382` | `…entrypoint 见 src/main/templates/fabric.mod.json:15，类为 src/client/java/nx/pingwheel/fabric/integration/ModMenuIntegration.java:7-12…` | **[SHOULD]** `fabric/src/main/templates/fabric.mod.json:15`、`fabric/src/client/java/…/ModMenuIntegration.java:7-12` |
| `docs/team.md:34,37,50` | `run/dsh-logs/`、`run/ 报告` | **[HIST]** 退役会话记录 |

#### 2.5.1 `AGENTS.md` §2 建议整块替换（34-43 行）

```
build.gradle / settings.gradle / gradle.properties   构建入口（id/version 唯一来源；根 build.gradle 只 apply false）
gradle/wrapper/                                      固定 Gradle 9.8.2
buildSrc/src/main/groovy/multiloader-common.gradle   共用构建逻辑（java/library/jar/集成 jar glob）
buildSrc/src/main/groovy/multiloader-loader.gradle   加载器模块公共接线（commonJava/commonResources）
common/src/main/java/      162 java  双端共用业务层（marker/core/network/config/domain/math/…；含 VoiceChat 插件入口
                                    integration/VoiceChatIntegration；**client 源集恒空，只作闸门**）
common/src/main/resources/      assets/pingforit（lang、音效、贴图）、icon.png
fabric/src/main/java/        4 java  FabricMain + 3 个服务端侧 Platform*ServiceImpl（server-safe）
fabric/src/main/resources/      META-INF/services/ 三件套（服务端 SPI）
fabric/src/main/templates/fabric.mod.json  元数据模板（→ fabric/build/generated/modMetadata）
fabric/src/client/java/     80 java  仅客户端：HUD、输入、命令、平台适配、客户端 mixin、ModMenu entrypoint
fabric/src/client/resources/    pingforit.mixins.json（JAVA_25，client 列表）+ META-INF/services/ 两件套
forge/ neoforge/               预留目录（只有 README，未 include 进 settings.gradle）
deferred/                  95 java  26.3 无对应原语的渲染/UI 子系统，不参与编译（**与活源码 18 个 FQCN 同名，禁止进 source root**）
tools/compile-check.sh|.ps1       本地 javac 三遍校验（common / fabric main / fabric client），不启动 Gradle
docs/                             PLAN / PROGRESS / FEATURES / reviews / TEAM.md
Mod_Project/                      上游源码（**.gitignore 排除**）
run/ build/ .gradle/ .idea/       运行与构建产物（gitignore；run/ 仍在仓库根，loom runDir 显式指回根）
```

### 2.6 `.gitattributes` / `.gitignore`（结论：都不改）

| 文件 | 命中 | 结论 |
|---|---|---|
| `.gitattributes` | **0 处** | **[NOCHG]** 规则全部按扩展名/文件名（`* text=auto eol=crlf`、`*.java text eol=lf`、`*.gradle text eol=lf`、`gradle text eol=lf`、`*.sh text eol=lf`、`*.bat text eol=crlf`…）。新增的 `buildSrc/src/main/groovy/*.gradle` 自动落 LF。**没有任何路径规则需要增删** |
| `.gitignore:33` | `run/` | **[NOCHG]** 无前导 `/`，语义为“任意层级的 run 目录”；实测 `fabric/run/mods/z.jar` 已被命中 |

实测证据（`git check-ignore -v`，2026-11-03）：

```
common/build/libs/x.jar      -> .gitignore:4:build/    common/build/libs/x.jar
fabric/build/libs/x.jar      -> .gitignore:4:build/    fabric/build/libs/x.jar
fabric/build/classes/y.class -> .gitignore:4:build/    fabric/build/classes/y.class
fabric/run/mods/z.jar        -> .gitignore:33:run/     fabric/run/mods/z.jar
common/.gradle/x             -> .gitignore:3:.gradle/  common/.gradle/x
```

> 例外：`tools/.compile-check/`（44 行）已被忽略，脚本位置不变，三遍输出仍写这里。

### 2.7 `.java` 源码里的路径引用（全部 NO CHANGE，仅注释）

| 文件:行 | 现文本（摘） | 结论 |
|---|---|---|
| `…/hud/MarkerHudRenderer.java:31,81` | `{@code deferred/}: {@code OverlayRenderer.java:12-57}…` / `({@code deferred/client/outline/}) is still absent…` | **[NOCHG]** `deferred/` 不搬家 |
| `…/hud/MarkerLabelRenderer.java:21,23` | `…upstream {@code deferred/render/DrawContext.java:35-62}…` | **[NOCHG]** |
| `…/hud/WheelHudRenderer.java:56` | `…helper stays in {@code deferred/})…` | **[NOCHG]** |
| `src/client/java/nx/pingwheel/common/CommonClient.java:479` | `// WheelOverlayRenderer, ...) now lives in deferred/. …` | **[NOCHG]** |
| `…/integration/sable/client/SableClientProvider.java:16` | `…lives in {@code deferred/client/integration/sable/}…` | **[NOCHG]** |
| `…/mixin/RenderTypeAccessor.java:12` | `…the plain javac classpath of {@code tools/compile-check.sh} keeps working).` | **[NOCHG]** 脚本仍在 `tools/compile-check.sh` |
| `src/main/java/nx/pingwheel/common/config/MavenComparableVersion.java:10` | `*   http://www.apache.org/licenses/LICENSE-2.0` | **[NOCHG]** `LICENSE` 子串的**假阳性**（URL），与本仓库 LICENSE 无关 |
| `src/main/templates/fabric.mod.json:20` | `“config”: “pingforit.mixins.json”,` | **[NOCHG]** 模板里是 jar 根相对名；json 搬到 `fabric/src/client/resources/`，引用文本不变 |

### 2.8 历史文档（HIST · 禁止改写，需要时只加“历史”标注）

| 文件 | 命中数 | 处置 |
|---|---:|---|
| `docs/PORTING-PROGRESS.md` | **84** | 全部是已完成阶段的施工/验收记录，**逐字保留**；分布见下表 |
| `docs/reviews/*.md`（53 个文件） | **391** | 独立评审存档，逐字保留（最多：`54-m9-1-modmenu-review.md` 74、`15-restore-baseline-audit.md` 31、`04-m2-structure-review.md` 19、`02-mixin-and-build-review.md` 17） |
| `docs/survey/project-survey.md` | **41** | 移植**前模板工程**的勘察（`com/example/ExampleMod`、`modid.mixins.json`），与当前布局无关，逐字保留 |
| `docs/logs/**` | **25** | 原始日志索引与运行记录，逐字保留 |
| `docs/logs/raw/*.md` | 含 `build/libs/modid-1.0.0.jar` 等 | 原始命令输出快照，逐字保留 |

`docs/PORTING-PROGRESS.md` 的 84 处按阶段分布（行号为该章起点）：

| 章 | 行 | 命中 | 章 | 行 | 命中 |
|---|---:|---:|---|---:|---:|
| 阶段总览 | 6 | 2 | M8b 资源重载 v1 | 460 | 2 |
| M1 构建骨架 | 44 | 3 | M8 配置/命令/UI | 481 | 1 |
| M1 验证结果 | 63 | 2 | M8a-1 最小闭环 | 491 | 3 |
| M2 源码导入与源集拆分 | 85 | 7 | M8a-1b 分页界面 | 519 | 2 |
| M3a 去阻塞+机械改名 | 152 | 1 | M8c toggle notice | 568 | 1 |
| M3b 渲染层最小编译修复 | 161 | **13** | C2 entity_block 光晕 | 609 | 2 |
| M3 收尾与验收 | 207 | 2 | 待办5 资源重载迁移 | 657 | 1 |
| M4 MVP 闭环 | 227 | 3 | M9 可选集成 | 672 | 3 |
| M6.5 标记 HUD | 270 | 1 | M9-1 Mod Menu | 687 | **14** |
| 2026-10-30 恢复后验收 | 312 | 1 | M9-2 Voice Chat | 734 | 5 |
| M7-prep 渲染管线可行性 | 328 | 2 | 剩余工作总表 | 770 | 1 |
| M7a 方块描边 | 355 | 3 | 决策执行情况 | 792 | 2 |
| M7b 实体描边 | 387 | 1 | 事故·怎么恢复的 | 818 | 1 |
| M7c 方块模型光晕 | 416 | 1 | 事故·丢失内容 | 829 | 2 |
| M8 配置/命令/UI（章） | 481 | 1 | 事故·恢复后验证 | 836 | 2 |

> 00-plan §4.5 要求“追加一条重构记录（历史章节不改）”——建议在文件末尾新增 `## 2026-11-03 · 目录重构（双模块）`，
> **不要**动上面任何一章的行号内容，否则本文的行号表会失效。

---

## 3. 目标映射表（Exact target mapping）

### 3.1 目录级映射（`git mv` 逐条可执行）

| # | 源（当前 tracked 路径） | 目标 | 文件数 | 说明 |
|---|---|---|---:|---|
| M1 | `src/main/java/nx/pingwheel/common/**` | `common/src/main/java/nx/pingwheel/common/**` | 162 | 整棵搬，包名不变 |
| M2 | `src/main/java/nx/pingwheel/fabric/**` | `fabric/src/main/java/nx/pingwheel/fabric/**` | 4 | **不进 common**：见 §3.2 |
| M3 | `src/main/resources/assets/**` | `common/src/main/resources/assets/**` | 12 | lang 8 + sounds.json + ping.ogg + 贴图 2 |
| M4 | `src/main/resources/icon.png` | `common/src/main/resources/icon.png` | 1 | 必须仍在 jar 根（靠 fabric 合并 common 输出实现） |
| M5 | `src/main/resources/META-INF/services/**` | `fabric/src/main/resources/META-INF/services/**` | 3 | 与 3 个 `PlatformXxxServiceImpl` 同模块（fork 同做法） |
| M6 | `src/main/templates/fabric.mod.json` | `fabric/src/main/templates/fabric.mod.json` | 1 | 保留 Sync 模板机制（差异 D4） |
| M7 | `src/client/java/**` | `fabric/src/client/java/**` | 80 | 整棵搬，包名不变 |
| M8 | `src/client/resources/pingforit.mixins.json` | `fabric/src/client/resources/pingforit.mixins.json` | 1 | **必须与 8 个 mixin 类同模块同源集**（refmap） |
| M9 | `src/client/resources/META-INF/services/**` | `fabric/src/client/resources/META-INF/services/**` | 2 | 客户端 SPI |
| M10 | `src/`（空壳） | 删除 | 0 | `git mv` 后确认无残留文件；git 不跟踪空目录 |
| — | `deferred/`、`tools/`、`docs/`、`.github/`、`Mod_Project/`、`run/` | **不动** | — | 00-plan §1“保持不动的顶层目录” |
| — | `settings.gradle` `build.gradle` `gradle.properties` `gradlew*` `gradle/` `.gitignore` `.gitattributes` `LICENSE` `README.md` `AGENTS.md` `img.png` `改进方案.md` | **原地**，内容改写 | — | 见 §2 |

新增（无源可搬）：

| 路径 | 内容 |
|---|---|
| `buildSrc/build.gradle` | `plugins { id “groovy-gradle-plugin” }` |
| `buildSrc/src/main/groovy/multiloader-common.gradle` | java-library/base/java/repos/deps/jar+manifest / `ext.integrationJars` / `attachIntegrationJarCheck` |
| `buildSrc/src/main/groovy/multiloader-loader.gradle` | `commonJava`/`commonResources` 配置、`compileOnly project(':common')`、`compileJava` 合并 common 源、`processResources` 合并 common 资源 |
| `common/build.gradle` | `multiloader-common` + `net.fabricmc.fabric-loom`（**开 split**） |
| `fabric/build.gradle` | `multiloader-loader` + `net.fabricmc.fabric-loom`（开 split）、`generateModMetadata`、`jar{archiveClassifier; from(:common output); LICENSE}` |
| `forge/README.md`、`neoforge/README.md` | 预留说明（git 不跟踪空目录，**必须**放文件） |

### 3.2 `nx/pingwheel/fabric/**` 的归属（决定落 fabric/src/main 还是 fabric/src/client）

**`src/main/java/nx/pingwheel/fabric/`（4 个，全部 server-safe → `fabric/src/main/java/`）**

| 文件 | 行数 | 依据 |
|---|---:|---|
| `FabricMain.java` | 109 | `fabric.mod.json` 的 `main` entrypoint；服务端也加载 |
| `platform/PlatformContextServiceImpl.java` | 33 | 实现 `IPlatformContextService`（main 侧接口） |
| `platform/PlatformNetworkServiceImpl.java` | 28 | 实现 `IPlatformNetworkService` |
| `platform/PlatformServerEventServiceImpl.java` | 22 | 实现 `IPlatformServerEventService` |

> 佐证：`grep -rn 'net\.minecraft\.client\.\|com\.mojang\.blaze3d' src/main/java` 结果为 **NONE**，
> 即这 4 个文件（连同 162 个 common 文件）都不碰客户端类；三个 SPI 文件的 impl 名与此完全对应。

**`src/client/java/nx/pingwheel/fabric/`（4 个，client-only → `fabric/src/client/java/`）**

| 文件 | 行数 | 依据 |
|---|---:|---|
| `FabricClient.java` | 127 | `fabric.mod.json` 的 `client` entrypoint |
| `integration/ModMenuIntegration.java` | 13 | `modmenu` entrypoint（唯一 import `terraformersmc` 的文件） |
| `platform/PlatformClientContextServiceImpl.java` | 41 | 实现 `IClientPlatformContextService`（client 侧接口） |
| `platform/PlatformClientEventServiceImpl.java` | 37 | 实现 `IPlatformClientEventService` |

> 结论：**两侧各有 4 个同名包文件，不能整包一刀切**。若把 main 侧 4 个塞进 `fabric/src/client`，专用服务器会因找不到
> `FabricMain` 而 `NoClassDefFoundError`；若把 client 侧 4 个留在 `src/main`，编译期就会被 split 拦下（这正是想要的闸门行为）。

### 3.3 迁移后的目标树（含计数校验点）

```
F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric\
├── settings.gradle                 include('common') include('fabric')；forge/neoforge 仅注释
├── build.gradle                    根：loom apply false + buildAll 聚合
├── gradle.properties               不变（version/group 由子项目继承）
├── buildSrc/  (4 files)            build.gradle + 2 groovy
├── common/
│   ├── build.gradle
│   ├── src/main/java/nx/pingwheel/common/**   162
│   ├── src/main/resources/**                  13 (assets 12 + icon.png)
│   └── src/client/                            ← 刻意留空（闸门）
├── fabric/
│   ├── build.gradle
│   ├── src/main/java/nx/pingwheel/fabric/**    4
│   ├── src/main/resources/META-INF/services/** 3
│   ├── src/main/templates/fabric.mod.json      1
│   ├── src/client/java/**                     80
│   └── src/client/resources/**                 3 (mixins json + 2 services)
├── forge/README.md  neoforge/README.md
├── deferred/ (95 java)            不动
├── tools/compile-check.sh|.ps1    重写
├── .github/workflows/build.yml    上传路径改 fabric/build/libs
├── docs/ …                        AGENTS/README/PROGRESS/features 同步
└── run/  Mod_Project/  build/ …   不动
```

**迁移后必须成立的数字**（可直接当验收断言）：

```
find common/src/main/java -name '*.java' | wc -l     -> 162
find fabric/src/main/java -name '*.java' | wc -l     ->   4
find fabric/src/client/java -name '*.java' | wc -l   ->  80
find common/src/main/resources -type f | wc -l       ->  13
find fabric/src/main/resources -type f | wc -l       ->   3
find fabric/src/client/resources -type f | wc -l     ->   3
find fabric/src/main/templates -type f | wc -l       ->   1
find deferred -name '*.java' | wc -l                 ->  95   (未动)
```

---

## 4. 耦合清单（必须同批改，漏一条就断）

| # | 组 | 一起改的东西 | 漏掉的后果 |
|---|---|---|---|
| C1 | **jar 身份** | `base.archivesName`、`jar.archiveClassifier`、`jar.from(LICENSE)+rename`、CI 上传 glob、README/AGENTS 的产物路径 | 产物名变 `LICENSE_fabric` / 上传步骤 `if-no-files-found: error` 直接红 |
| C2 | **元数据生成** | `generateModMetadata`(Sync) + `from “src/main/templates/…”` + `sourceSets.main.resources.srcDir` + `processResources.dependsOn` + `inputs.property “version”` | `fabric.mod.json` 的 `${version}` 不被替换（加载时报版本非法），或 `build/generated` 为空导致资源缺失 |
| C3 | **集成 jar（4 处必须一致）** | `multiloader-common.gradle` 的 include/exclude、`attachIntegrationJarCheck(:common:compileJava → voicechat-)`、`attachIntegrationJarCheck(:fabric:compileClientJava → modmenu-)`、`tools/compile-check.sh\|.ps1` 的 glob、`.github/workflows/build.yml` 的 curl 列表 | 检查挂在不存在的任务上 → **静默失效**；缺 jar 时退化成一屏 `package … does not exist`（AGENTS §7.15 已记录该症状） |
| C4 | **dev 运行目录** | `loom.runs.client/server.runDir(rootProject.file('run'))` + 集成 jar 的 `rootProject.file(“run/mods”)` + CI 的 `run/mods` + `.gitignore` + 两个 compile-check 脚本的 `run/mods` + AGENTS §7.6/§7.15 | `runClient` 会在 `fabric/run` 新建空世界；现有 `run/logs`、`run/saves`、`run/mods` 里的 jar 全部“消失” |
| C5 | **split 闸门** | `common/build.gradle` 的 `splitEnvironmentSourceSets()` + compile-check 的 common 遍 classpath（只给 `minecraft-common.jar`）+ AGENTS §10 的 grep 自检 | 闸门三重失效：Loom 不再拦、脚本编得过、自检没写 → 服务端运行时 `NoClassDefFoundError` |
| C6 | **mixin 三件套** | `pingforit.mixins.json` 位置、8 个 mixin 类位置、`fabric.mod.json` 的 `mixins[0].config`、Loom refmap 生成 | 加载期 `Mixin apply failed`（AGENTS §6/§10 首查项） |
| C7 | **SPI** | 5 个 `META-INF/services/*` 的归属模块 + 对应 impl 的源集 + `ServiceLoader` 调用方 | 服务端找不到 `IPlatformContextService` 实现 → 启动即崩 |
| C8 | **git 属性** | `.gitattributes`（已确认不用改）+ `gradlew` 可执行位 + `*.sh` LF | 本次无动作 |
| C9 | **文档** | AGENTS §1/§2/§3/§4/§7.15/§8/§9.1/§10、README §构建、features 3 处、porting-plan 2 处、PORTING-PROGRESS 追加一节 | 下一个人按旧路径操作，浪费一轮排障 |
| C10 | **构建入口命名** | `settings.gradle` 的 `rootProject.name`（`LICENSE_${projectName}` 依赖它）+ `include` 列表 | `LICENSE_pingforit` 改名（见 C1） |

---

## 5. 有意保留的分叉差异（Fork deviations we keep on purpose）

| # | 差异 | 一句话理由 |
|---|---|---|
| D1 | `forge/`、`neoforge/` 只有 `README.md`，**不** `include` 进 `settings.gradle` | 决策 D1 只交付 Fabric；没有 build.gradle 的项目 include 进来只会造出空项目任务图，等真正接入再开 |
| D2 | 客户端代码放 `fabric/src/client`，**不**像 fork 那样塞进 `common/src/main` | 26.3 的 split 源集 + mixin/refmap 配置已实机验证，改动面最小 |
| D3 | `common` 也开 `splitEnvironmentSourceSets()`（fork 的 common 是单源集） | 让编译器替我们守“共享代码不碰客户端类”，代价只是一个恒空的 client 源集 |
| D4 | 保留 `fabric/src/main/templates/fabric.mod.json` + `generateModMetadata`(Sync)，不用 fork 的 `processResources{filesMatching{expand}}` | 移植期已验证；AGENTS §4 明确禁止改回（IDEA 的 Gradle 模型能解析资源过滤） |
| D5 | 可选集成走仓库根 `run/mods` 的本地 jar（`compileOnly`），不声明 maven 坐标 | 本机 Gradle 无网络；fork 用 `maven.modrinth` / `de.maxhenkel` / `curse.maven` 在这里必然解析失败 |
| D6 | 产物 `Ping-For-It-<version>-fabric-26.3.jar`，classifier 挂在 `jar` 上 | 决策 D4；no-remap 的 Loom 没有 `remapJar`，classifier 只能挂 `jar` |
| D7 | Loom 用 `net.fabricmc.fabric-loom 1.19.3`（no-remap），无 mappings/Parchment 块 | 26.3 只有这一支可用，且 dev 与产物同一份 class，不需要 remap |
| D8 | `gradle.properties` 仍是 `version=` / `group=`，不引入 fork 的 `mod_version=` + buildSrc 里 `base.version` | AGENTS §2 规定“id/version 唯一来源”；两处设 version 必然漂移 |
| D9 | 保留 `deferred/`(95)、`tools/`(javac 离线校验)、`Mod_Project/`(上游源码) | 移植工程自有资产，不进产物；且 `deferred/` 与活源码 18 个 FQCN 重名，必须留在 source root 之外 |
| D10 | dev 运行目录是仓库根 `run/`（fork 用 `runs/client`、`runs/server`） | 现有 mods/logs/saves 都在 `run/`，且 `run/mods` 同时是离线编译依赖目录 |
| D11 | 没有 fork 的 `verifyModIdentity` 聚合任务（约 200 行）；身份校验靠 AGENTS §10 清单 + 本文件 §6.0 的 jar 基线 | 单端场景下该任务的 refmap/forge/neoforge/THIRD-PARTY 分支用不上 |
| D12 | 单 `LICENSE`(当时 jar 内是 CC0 模板，待 M10 换 MIT)；fork 是 `LICENSE`+`LICENSE-APACHE-2.0`+`THIRD-PARTY-NOTICES.md` | **2026-11-04 已结案**：本项目与上游**都是 MIT**（`LICENSE` SHA256 相同、21 行）；**Apache-2.0 只适用于 vendor 的 `MavenComparableVersion`**，全文已内嵌 `THIRD-PARTY-NOTICES.md` §38–237 并由 `fabric/build.gradle:90` 并入 jar 根（见 `docs/reviews/66-m10-batch1-review.md`） |

---

## 6. 风险清单（two-Loom-project build）与迁移前必须回答的问题

### 6.0 基线（迁移前实测，2026-11-03 18:53，用于迁移后比对）

```
build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar   771,558 B / 464 entries / 392 class / 0 duplicate entry
jar 根条目：LICENSE_pingforit  fabric.mod.json  icon.png  pingforit.mixins.json
services：META-INF/services/ 下 5 个（3 main + 2 client）
```

迁移后应满足：**392 class 不变、464 entries 不变、0 重复条目、根条目同名**（LICENSE 后缀按 C1 决定）。
这是发现“common 被合并两次”最直接的断言。

### 6.1 00-plan.md 未覆盖的 4 个坑

| # | 坑 | 证据 / 机理 | 建议 |
|---|---|---|---|
| G1 | `LICENSE` 会静默丢失且改名 | `build.gradle:154` 的 `from(“LICENSE”)` 相对**项目**目录；搬到 `fabric/` 后即 `fabric/LICENSE`（不存在 → Gradle 静默跳过）。`:156` 的 `rename { “${it}_$projectName” }` 里 `projectName` 变 `fabric`，而迁移前实测产出是 `LICENSE_pingforit` | 改 `from(rootProject.file(“LICENSE”))`，rename 用 `rootProject.name`；迁移后 `unzip -l` 断言 |
| G2 | 根级 `./gradlew runClient` 可能变歧义任务 | Loom 在**每个**应用它的项目里默认注册 `runClient`/`runServer`；两个模块都 apply loom → 根执行 `runClient` 命中两个同名任务 | 迁移后先 `./gradlew tasks --all \| grep runClient`；若歧义则文档改 `:fabric:runClient`，并在 `common` 抑制默认 run 配置 |
| G3 | `compileOnly project(':common')` 在“两个都是 loom 项目”时的变体解析未验证 | Loom 给自身的 `apiElements/runtimeElements` 加 `-dev`/`-named` 属性；`compileOnly` 是普通 configuration，可能报 `No matching variant` 或挑到带 client 类的 jar | 首选 `compileOnly project(':common')`；失败 fallback：`compileOnly files(project(':common').sourceSets.main.output)` + 显式 `dependsOn(':common:classes')` |
| G4 | `publishing { create(“mavenJava”, …) from components.java }` 归属未定 | 迁移前在根 `build.gradle:159-168`；根若无 java 插件则 `components.java` 直接报错 | 只留 `fabric`，或整块删除；**不要**留根 |

### 6.2 风险表

| # | 风险 | 触发条件 | 后果 | 处置 |
|---|---|---|---|---|
| R1 | **split 闸门被架空** | `common` 不开 split，或开了但 compile-check 仍把 3 个 MC jar 全塞进去 | `net.minecraft.client.*` 编得过 → 专用服务器 `NoClassDefFoundError` | common 开 split（D3）+ 脚本 common 遍只给 `minecraft-common.jar` + grep 门禁 |
| R2 | **common 类被合并两次** | 既 `from project(':common').sourceSets.main.output`，又 `implementation project(':common')` | jar 里重复 class / mixin 重复 apply / class 数 >392 | 只 `compileOnly` + `jar.from(output)`；用 §6.0 的 392/464 断言 |
| R3 | **LICENSE 丢失/改名** | 见 G1 | jar 里没有 LICENSE，或名字变 `LICENSE_fabric` | `rootProject.file` + `rootProject.name` |
| R4 | **runClient 歧义** | 见 G2 | 用户启动命令直接失败 | 先探明再改文档 |
| R5 | **run/mods 解析到 fabric/run/mods** | 子项目里仍写 `file(“run/mods”)` | 集成 jar 静默 0 命中 → 一屏 `package does not exist`，且 `runClient` 加载不到 modmenu | 全部改 `rootProject.file(“run/mods”)`；CI 仍写根 `run/mods` |
| R6 | **generateModMetadata 相对路径错位** | `from “src/main/templates/…”` 被写成 rootProject 相对，或模板被搬到 `common` | `${version}` 不替换 / 任务输入缺失 | 模板留 `fabric/src/main/templates`，任务留 `fabric/build.gradle`，相对路径不加前缀 |
| R7 | **资源合并重复或缺失** | common 的 `assets/`、`icon.png` 既经 output 合并、又经 `processResources` 合并 | 重复条目 / 或缺 `icon.png`（`fabric.mod.json` 引用它） | 二选一（推荐 `jar.from(project(':common').sourceSets.main.output)`）；断言 `unzip -l \| grep -c icon.png` = 1 |
| R8 | **集成检查挂在不存在的任务上** | 检查仍写在根 `build.gradle` 的 `compileJava` | 检查**永不执行**（`tasks.matching` 找不到不报错），缺 jar 时退化成一屏编译错 | 放 `:common:compileJava` / `:fabric:compileClientJava`；保留 `projectsEvaluated` warn 守护并加项目前缀 |
| R9 | **compile-check 假绿** | 只改源目录，忘了 classpath 链（fabric-main 需 common 输出；fabric-client 需前者输出） | 0 error 但编的是空集，或漏 common 输出导致大量假错 | 三遍有依赖顺序；`[summary]` 必须出现 3 个模块，sources = 162/4/80 |
| R10 | **configuration-cache / parallel 在双 loom 下翻车** | `gradle.properties` 有 `org.gradle.parallel=true`、`org.gradle.configuration-cache=true` | 首个失败点很可能是任务动作里引用 `project(':common')` | 沙箱不能跑 Gradle，**必须由用户终端验证**；排查顺序 `--no-configuration-cache` → `--no-parallel` |
| R11 | **deferred/ 落进 source root** | 有人顺手把 `deferred/client/outline` 加进编译（E6 实验） | 6 个重复 FQCN → javac duplicate class | 本文 §1.4 登记；AGENTS §9.1 的 E6 行补一句 |
| R12 | **forge/neoforge 被顺手 include** | `settings.gradle` 里 `include(“forge”)` 但无 `build.gradle` | 空项目进任务图；聚合任务因 `:forge:build` 不存在而失败 | 本轮只放 README + 注释 |
| R13 | **mixins json 与 mixin 类分家** | json 放 `common/src/main/resources`（fork 做法），类在 `fabric/src/client` | refmap/配置不在同一 jar 分支 → `Mixin apply failed` | json **必须**在 `fabric/src/client/resources`，与 8 个类同源集 |
| R14 | **跨模块 split package** | `nx.pingwheel.common.*` 同时存在于 common 与 fabric/client | classpath 加载没问题（非 JPMS），但 IDE 报 “package exists in multiple modules”，未来不能模块化 | 已知并接受（fork 也这样），文档说明即可 |
| R15 | **版本来源漂移** | 同时在 `gradle.properties` 与 `multiloader-common` 里设 version | 产物版本与 `fabric.mod.json` 的 `${version}` 不一致 | 只保留 `gradle.properties` 的 `version`（子项目继承）；buildSrc 里不设 `base.version` |

### 6.3 迁移者动手前必须回答的问题

| # | 问题 | 推荐答案（依据） |
|---|---|---|
| Q1 | `common` 开不开 `splitEnvironmentSourceSets()`？ | **开**（00-plan §2）。代价：恒空 client 源集；收益：编译器级闸门 |
| Q2 | common 以“输出合并”还是“项目依赖”进 fabric？ | **输出合并**：`jar.from(project(':common').sourceSets.main.output)` + `compileOnly project(':common')`（00-plan §3.5；避免 JiJ 与重复类） |
| Q3 | 5 个 SPI 文件归谁？ | **3 个 main → `fabric/src/main/resources`，2 个 client → `fabric/src/client/resources`**（与 impl 同模块；fork 同做法） |
| Q4 | dev 运行目录？ | **根 `run/`**（`runDir(rootProject.file('run'))`），`run/mods` 同步用 `rootProject.file` |
| Q5 | `deferred/` 落哪？ | **原地不动**（18 个 FQCN 冲突，见 §1.4） |
| Q6 | `forge/`、`neoforge/` 进 `settings.gradle` 吗？ | **不进**，只放 README + 注释（00-plan R1） |
| Q7 | compile-check 几遍、什么 classpath？ | **三遍**：common（仅 `minecraft-common.jar` + modules-2 + voicechat/DH jar）、fabric-main（同上 + common 输出）、fabric-client（3 个 MC jar + common 输出 + fabric-main 输出 + modmenu jar） |
| Q8 | 谁生产最终 jar，classifier 与 LICENSE 怎么定？ | `fabric:jar`，`archiveClassifier=“fabric-26.3”`，`from(rootProject.file(“LICENSE”))` + `rootProject.name` |
| Q9 | `fabric.mod.json` 模板机制换不换？ | **不换**（AGENTS §4 明令；00-plan R3） |
| Q10 | 要不要 buildSrc？ | **要**（fork 形状；两个模块共用 40+ 行集成 jar/仓库/编译配置，复制两份必漂移） |
| Q11 | `publishing` 放哪？ | **只放 `fabric`**（或整块删除）。根项目没有 java 组件，`components.java` 会直接报错（G4） |
| Q12 | `common:jar` 的 `archivesName`？ | 建议**显式不同**（如 `Ping-For-It-common`）或关掉 common 的发布化，避免两个同前缀 jar 让人误取 |

---

## 7. `tools/compile-check.sh` 现状摘要（将被重写）

**它编译什么**：两遍 javac（`--release 25 -proc:none -nowarn -encoding UTF-8 -Xmaxerrs 20000`），不启动 Gradle、不联网、不 remap：

| 遍 | 源 | 数量 | 输出 |
|---|---|---:|---|
| `main` | `find src/main/java -name '*.java'` | 166 | `tools/.compile-check/main` |
| `client` | `find src/client/java -name '*.java'` | 80 | `tools/.compile-check/client` |

**classpath 组成**（两遍**共用同一个** `CP_BASE`，共 3 段）：

1. **固定 3 个 MC jar**（`compile-check.sh:52-59`，缺任一即 `exit 2`）：
   `D:/Gradle/caches/fabric-loom/26.3/minecraft-common.jar`、`minecraft-client.jar`、`minecraft-client-only.jar`；
2. **`D:/Gradle/caches/modules-2/files-2.1` 下所有 `*.jar`**（排除 `-sources`/`-javadoc`，`sort` 后全量入 CP；<10 个视为缓存空 → `exit 2`）；
3. **`run/mods` 里匹配 5 个 glob 的 jar**（`modmenu-*.jar`、`voicechat-api-*.jar`、`voicechat-*.jar`、`DistantHorizons-*.jar`、`DistantHorizonsApi-*.jar`，同样排除 sources/javadoc），
   0 命中时打印 `no integration jars matched in run/mods (M9 integration classes cannot compile)` 但仍继续。

**两遍的唯一差别**：client 遍的 classpath **前置** main 遍的输出目录（`CLIENT_CP=“$OUT_WIN/main;$CP_BASE”`）。
因此 `main` 遍**也能看见客户端 MC 类**——这是它无法替代 Loom split 闸门的原因（见 §2.2）。

**其他固定行为**：`COMPILE_CHECK_ROOT` 可覆盖根目录；用 `build.gradle` 是否存在判定“是不是项目根”；
自动找 JDK25（`JAVA_HOME` → `C:/Program Files/Java/jdk-25` → `PATH`）；
把 `-cp` 与源码清单写成 argfile 规避 Windows 命令行长度；输出 `[name] sources/errors/classes/log` 与前 40 条错误；
**退出码**：0 无错、1 有错、2 环境不就绪。`.ps1` 是等价实现（多一个 `-Pass main|client|both` 参数）。

**重写要点**（与 Q7 一致）：`common` → `fabric-main` → `fabric-client` 三遍；common 遍**只给 `minecraft-common.jar`**；
每遍 classpath 依次前置前序输出；新增 `common/src/main/java` 的客户端 import grep 闸门；`[summary]` 行改为三段。

---

## 8. 复现证据（命令原文，迁移后可按新路径重跑对比）

```bash
# 计数（迁移前：166 / 80 / 95）
find src/main/java -name '*.java' | wc -l
find src/client/java -name '*.java' | wc -l
find deferred -name '*.java' | wc -l

# 闸门（迁移前：无输出）
grep -rn 'net\.minecraft\.client\.\|com\.mojang\.blaze3d' src/main/java

# FQCN 冲突（deferred vs live，迁移前 18 条）
find deferred -name '*.java' | while read f; do
  p=$(grep -m1 '^package ' "$f" | sed 's/^package //; s/;$//'); echo "$p.$(basename "$f" .java)"
done | sort > /tmp/def.txt
find src/main/java src/client/java -name '*.java' | while read f; do
  p=$(grep -m1 '^package ' "$f" | sed 's/^package //; s/;$//'); echo "$p.$(basename "$f" .java)"
done | sort > /tmp/live.txt
comm -12 /tmp/def.txt /tmp/live.txt | wc -l

# gitignore 覆盖（全部命中）
for p in common/build/libs/x.jar fabric/build/libs/x.jar fabric/run/mods/z.jar common/.gradle/x; do
  git check-ignore -v "$p"
done

# jar 基线
unzip -l build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar | tail -1
unzip -l build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar | grep -c '\.class$'
```

---

## 9. 与 00-plan.md 的逐条核对

| 00-plan 条目 | 本文件核对结果 |
|---|---|
| §1 目标树（162 / 4 / 80 / 资源位置 / services 归属） | **一致**，计数实测吻合（§1.1、§3.1） |
| §1 “保持不动的顶层目录” | **一致**；并给出 `deferred/` **必须**不动的硬理由（18 个 FQCN 冲突） |
| §2 两个模块都开 split | **一致**；补充“common 的 client 源集恒空”这条口径 |
| §3.1 根 apply false / 子项目 apply | **一致** |
| §3.3 集成 jar 三处一致 | **一致**；补充“第 4 处” = `multiloader-common.gradle` |
| §3.4 模板机制 | **一致** |
| §3.5 jar 合并 common 输出 | **一致**；补充 R2/R7 两个失败模式与断言 |
| §3.6 runDir 指回根 run/ | **一致**；补充 G2（runClient 歧义）这个连带风险 |
| §3.7 产物名/位置 | **一致**；补充 G1（LICENSE 重命名） |
| §4.1 git mv 清单 | **一致**；补充“空 `src/@ 删除后 `git status` 复核” |
| §4.2 forge/neoforge README | **一致**（git 不跟踪空目录，README 是唯一能让目录进仓库的方式） |
| §4.3 三遍编译 + grep 门禁 | **一致**；补充“现有脚本 main 遍其实带了 client jar”这个前提 |
| §4.4 CI 上传路径 | **一致** |
| §4.5 文档同步 | **一致**；补充 §2.5 逐行清单与 PORTING-PROGRESS 追加位置建议 |
| §6 R1–R6 差异登记 | **一致**；本文件 §5 的 D1–D12 是它的细化（多出 D5–D12） |
| 缺口 | **G1 LICENSE / G2 runClient 歧义 / G3 compileOnly 变体 / G4 publishing** 四处未覆盖，见 §10 的实测核对 |

---

## 10. 迁移落地后的实测复核（2026-11-03 19:12，只读核对）

> **重要**：本文 §1–§9 的“现状”是**迁移前**基线（commit `74ae0fd`，工作树干净）。
> 迁移由实现者在 19:02–19:09 并行完成（`src/` 已消失）。本节把实际落地结果与上面的风险/问题清单逐条对账，
> **不跑 Gradle**，全部是文件读取与计数结果。

### 10.1 已核对通过（实测数字）

| 检查点 | 预期（本文 §3.3） | 实测 | 结论 |
|---|---|---|---|
| `common/src/main/java` java | 162 | **162** | PASS |
| `fabric/src/main/java` java | 4 | **4** | PASS |
| `fabric/src/client/java` java | 80 | **80** | PASS |
| `deferred/` java | 95（未动） | **95** | PASS |
| `common/src/main/resources` 文件 | 13（assets 12 + icon.png） | **13** | PASS |
| `fabric/src/main/resources` 文件 | 3（服务端 SPI） | **3** | PASS |
| `fabric/src/client/resources` 文件 | 3（mixins json + 2 客户端 SPI） | **3** | PASS |
| `fabric/src/main/templates` | 1 | **1** | PASS |
| 空 `src/` 残留 | 无 | **不存在** | PASS |
| `common/src/client` | 恒空（可以不建） | **未创建** | PASS（符合 D3 口径） |
| `forge/ neoforge/` | 只有 README | 各 **1 个 README.md** | PASS |
| 5 个 SPI 归属 | 3 → fabric/main，2 → fabric/client，common 无 | **完全一致**（`common/src/main/resources` 下无 META-INF） | PASS（Q3） |
| mixin 8 类 + config | 同模块同源集 | `fabric/src/client/java/nx/pingwheel/common/mixin/` **8 个**，`pingforit.mixins.json` 的 `“package”: “nx.pingwheel.common.mixin”` 不变 | PASS（R13/C6） |
| `fabric.mod.json` 模板 | 仍是模板 + 4 个 entrypoint | `${version}` 仍在；4 个 entrypoint 原样 | PASS（C2/R6） |
| `.gradle` 脚本里的旧路径 | 只剩合法相对路径 | 命中仅 2 条注释 + `fabric/build.gradle:49 from 'src/main/templates/fabric.mod.json'`（相对 `fabric/`，**正确**） | PASS |

### 10.2 与风险/问题清单对账

| 条目 | 落地情况 | 状态 |
|---|---|---|
| **Q1 / D3** common 开 split | `common/build.gradle:9-11` 有 `loom { splitEnvironmentSourceSets() }` | **已解决** |
| **Q2 / R2** common 进 fabric 的方式 | `multiloader-loader.gradle:9` `implementation project(':common')` + `fabric/build.gradle:63-64` `jar.dependsOn(:common:classes)` / `from :common.sourceSets.main.output` | **结构就位**，但类路径机制见 §10.3-2 |
| **Q3 / C7** SPI 归属 | 3+2 全部在 fabric，common 干净 | **已解决** |
| **Q4 / C4 / R5** run 目录 | `multiloader-common.gradle:40,44` `rootProject.file('run/mods')`；`fabric/build.gradle:23,28` `runDir(rootProject.file('run').absolutePath)` | **已解决** |
| **Q5 / R11** deferred 不动 | 95 java 原地，未进任何 source root | **已解决** |
| **Q6 / R12** forge/neoforge | `settings.gradle:23-24` 是**注释掉的** `// include('forge')` / `// include('neoforge')` | **已解决** |
| **Q7 / R9 / C5** compile-check 三遍 + 闸门 | `compile-check.sh:174-181` 三遍（common → fabric-main → fabric-client，逐级前置输出）；`:115-129,185-192` 编译前后各跑一次 `common/server-safety` grep 闸门；`.ps1` 同步（`-Pass all\|both\|common\|fabric-main\|fabric-client`） | **已解决**（残留见 §10.3-4） |
| **Q8 / R3 / G1** LICENSE | `fabric/build.gradle:66-68` `from(rootProject.file('LICENSE'))` + `rename { it + '_pingforit' }` —— 因为 `rootProject.name` 已改成 `Ping-For-It`，这里**写死** `_pingforit` 恰好保住了旧的 jar 条目名 `LICENSE_pingforit` | **已解决**（但属“靠写死保住”的隐式契约，建议加一行注释） |
| **G4** publishing | 三个 build 文件里已**无** `publishing`/`components.java`（整块删除） | **已解决** |
| **R8 / C3** 集成检查挂载点 | `common/build.gradle:22` `attachIntegrationJarCheck('compileJava','voicechat-')`；`fabric/build.gradle:41` `attachIntegrationJarCheck('compileClientJava','modmenu-')` | **已解决** |
| **R15** 版本单一来源 | `gradle.properties` 仍是 `version=` / `group=`，buildSrc 里没有 `base.version`（只是 `archivesName`） | **已解决** |
| **C1** 产物名 | `multiloader-common.gradle:10` `archivesName='Ping-For-It'` + `fabric/build.gradle:61` `archiveClassifier='fabric-26.3'` → `fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar` | **已解决** |
| **C9** CI | `build.yml:60` `path: fabric/build/libs/Ping-For-It-*.jar` | **已解决**（`:52` 仍是 `./gradlew build`，见 §10.3-5） |

### 10.3 残留风险（按优先级；每条都附一行验证命令）

1. **P1 · 根级 `./gradlew runClient` 大概率变歧义任务（未验证）** —— `common` 现在也是 loom 项目，而 Loom 会为**每个** loom 项目注册默认 `runClient`/`runServer`
   （迁移前的单模块工程 `build.gradle` 里同样没有 `runs{}`，却一直有 `runClient` —— 这就是默认 run 存在的直接证据）。
   两个子项目同名 → 从根执行会得到 `Task 'runClient' is ambiguous in root project 'Ping-For-It'`。
   但 `AGENTS.md` §3 与 `README.md` 现在仍写 `./gradlew -g D:/Gradle runClient`。
   验证：`./gradlew tasks --all | grep -n “runClient”`（应能看出 `:common:runClient` 与 `:fabric:runClient` 两个）。
   处置二选一：① 文档改 `./gradlew :fabric:runClient`（最小改动，推荐）；② 在 `common/build.gradle` 里抑制默认 run 配置。
2. **P1 · `implementation project(':common')` 的变体解析（= 本文 G3，未验证）** —— `common` 与 `fabric` 都是 loom 项目，
   loom 会给自身 variant 加 `-dev`/`-named` 属性。失败症状是 `No matching variant of project :common` 或拿到与预期不同的 jar。
   验证：`./gradlew :fabric:compileJava --dry-run` / 真正的 `compileJava`。
   已备 fallback：改 `compileOnly files(project(':common').sourceSets.main.output)` + 显式 `dependsOn(':common:classes')`。
3. **P2 · dev 运行期与产物期的类来源不同（新发现）** —— `runClient` 走 `runtimeClasspath`（含 `common` 的 jar），
   而可发布 jar 走 `jar.from(:common output)` 的**合并**。因此 **`runClient` 通过不能证明合并是对的**；合并只能靠 jar 内容断言。
   验证（用户终端 `gradlew clean build` 之后，与本文 §6.0 基线逐项比）：
   ```bash
   unzip -l fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar | tail -1        # 期望 ≈464 files
   unzip -l fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar | grep -c '\.class$'  # 期望 392
   unzip -l fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar \
     | awk '/^ *[0-9]+/ {print $4}' | grep -v '/' | sort        # 期望 4 个根条目，且含 LICENSE_pingforit
   unzip -l fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar \
     | awk '/^ *[0-9]+/ {print $4}' | grep -v '^$' | sort | uniq -d   # 期望空（0 重复）
   unzip -l fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar | grep -c 'icon.png'  # 期望 1
   ```
4. **P3 · compile-check 的 common 遍仍带客户端 MC jar** —— `compile-check.sh:64-71` 仍把三个 MC jar 都放进 `CP_JARS`，
   `:174` 的 common 遍直接用 `CP_BASE`。所以离线校验里**只有那条 grep 闸门**在守服务端安全（Gradle 侧则由 split 守）。
   这是有意的取舍（00-plan §4.3 只要求 grep 闸门），但比“只给 `minecraft-common.jar`”弱。
   加固（可选，失败面可控）：common 遍改用只含 `minecraft-common.jar` 的 `CP_COMMON`；若因此冒出错误，说明现有代码确实依赖了客户端类，需要单独评审。
5. **P3 · CI 仍用 `./gradlew build`** —— 建议改 `./gradlew buildAll`（根已注册 `buildAll` → `:common:build` + `:fabric:build`），
   把“构建哪些模块”变成显式契约，同时规避 §10.3-1 那类同名任务的隐患。
6. **P4 · `common:jar` 与 `fabric:jar` 同前缀** —— `common/build/libs/Ping-For-It-0.4.2-pfi-beta1.jar` 会同时存在（Q12）。
   CI 只扫 `fabric/build/libs` 所以不会传错，但建议给 common 显式改名或加注释，避免人工取件取错。
7. **P4 · `fabric:sourcesJar` 不含 common 源码** —— `withSourcesJar()` 在约定插件里，只覆盖本项目源集。仅影响 sources 产物，不影响 mod jar。
8. **P4 · `rootProject.name` 从 `pingforit` 改成 `Ping-For-It`** —— 目前唯一消费者是 LICENSE 改名（已写死），
   但 IDE 工程名会变，首次同步后建议在 IDEA 里重新 import 一次。

### 10.4 给复审者的最小清单

- [ ] §10.1 的 12 行数字全部复现（纯计数，不用 Gradle）
- [ ] `tools/compile-check.sh` 与 `.ps1` 都能 0 error / 0 error / 0 error，且 `[gate] common/server-safety OK`
- [ ] `git status` 里 `src/` 的删除全部是 `R`（rename），没有 `D`（丢文件）
- [ ] 用户终端 `gradlew clean build` + §10.3-3 的 6 条 jar 断言
- [ ] 用户终端 `gradlew :fabric:runClient`（**必须带前缀**；§10.3-1 说的「歧义」已被实测推翻 —— 根项目根本没有该任务）
