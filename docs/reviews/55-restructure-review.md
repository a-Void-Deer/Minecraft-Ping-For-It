# 55 · 目录重构独立复审（单模块 → common/ + fabric/ 多模块）

> 复审人：3号 code-reviewer（**只读源码**，未修改任何代码/构建脚本）｜2026-11-03
> 对象：工作区 `26.3_Fabric` 上**未提交**的重构（266 个 `R100` 已 staged + 9 个已改文件 + 6 个新文件）
> 规格：[docs/restructure/00-plan.md](../restructure/00-plan.md)（含 R1–R6 刻意差异）
> 方法：`git diff --cached -M`、`git ls-files --eol`、Node 直读文件系统计数、**fabric-loom 1.19.3 源码 jar + `javap`**、实跑 `bash tools/compile-check.sh`、旧产物 jar 反查。**未运行 Gradle**（沙箱禁止）。
> 独立证据原则：迁移者/分析的结论一律重新取证；凡只能由真实构建判定的，标 **NEEDS-REAL-BUILD**。

---

## 0. 结论速览

| # | 检查 | 结论 | 一句话 |
| --- | --- | --- | --- |
| A1 | 纯重命名、0 内容改动、无意外增删 | **PASS** | 266 `R100`，`0 insertions(+), 0 deletions(-)` |
| A2 | 文件数 162/4/80/95 + 资源齐全 | **PASS** | 逐项实测吻合，`deferred/` 零改动 |
| A3 | 无空目录、`src/` 已消失、forge/neoforge 只有 README | **PASS** | `git ls-files src` 报「no file known」，空目录扫描 0 |
| B1 | 8 个构建文件 Groovy/Gradle 正确性（人审） | **FAIL（P0×1）** | 结构全对，但 `common/build.gradle:16` fabric-api 坐标少一段 → 必然构建失败 |
| B2 | Loom API 面真实存在（jar + javap） | **PASS** | 4 组签名全部命中，引 javap 原文 |
| B3 | split 源集的 client 输出是否自动进 `jar` | **PASS（源码级证明）** | `MinecraftSourceSets.java:191-197` 显式 `jar.from(clientOutput)` |
| B4 | 合并 jar 无重复 class / 无嵌套 common jar | **PASS** | 三源集 0 同名类、0 同名资源；旧 jar 0 重复条目 |
| B5 | `runDir` 仍落根 `run/`，与迁移前同构 | **PASS** | 绝对路径 + 迁移前默认就是根 `run/` |
| B6 | `generateModMetadata` 与产物名 | **PASS** | 模板/生成目录/srcDir 全对；产物名可推导 |
| B7 | 集成 jar 检查挂对任务、四处 glob 一致 | **PASS** | common/voicechat、fabric/modmenu；4 处 glob 逐字一致 |
| B8 | 没有模块相对的 `run/mods` 引用 | **PASS** | 全用 `rootProject.fileTree/file('run/mods')` |
| C1 | 实跑 `compile-check.sh` + 脚本正确性 | **PASS** | exit 0，`[summary]` 三段，gate 文本出现 2 次 |
| C2 | `tools/.compile-check/` 旧布局残留 | **PASS（含 P2）** | `main/` `client/` `javac-main.log` 等仍在；目录已 gitignore |
| C3 | CI 上传路径 / fetch 位置 / 注释 | **PASS** | `fabric/build/libs/Ping-For-It-*.jar`；fetch 仍写根 `run/mods` |
| C4 | 行尾 | **PASS（含 P2）** | 19 个改动/新增文件全 LF；8 个 `.java` 工作区 CRLF 属**既有**（`i/lf`） |
| D1 | AGENTS.md §2/3/4/10 准确性 | **PASS（含 P2）** | 路径/命令/产物/闸门描述全对；`docs/TEAM.md` 链接大小写错（既有） |
| D2 | README 构建说明 | **PASS（含 P2）** | 正确；但 32 行行内注释与 44 行「不要行内注释」自相矛盾 |
| D3 | forge/ / neoforge/ README | **PASS** | 无假构建文件，明确「不在 settings.gradle」 |
| D4 | `features.md:359/379/382`、`porting-plan.md:56,103` 旧路径 | **FAIL（P2）** | 3 处**活代码指针**必须改；2 处历史施工单可留 |
| D5 | 与上游 fork 形状对比、差异是否只有 R1–R6 | **PASS（但登记不全）** | R1–R6 属实且无第 7 条**目录形状**差异；另有 5 条**非形状**差异未登记（建议补 R7–R11） |
| E1 | 源码/包/mixin/fabric.mod.json 内容零改动 | **PASS** | 266 个移动文件全 `R100`＝逐字节相同 |

**总体判定：结构重构本身是干净的（A/C/D 基本 PASS），构建接线有 1 个 P0（一行坐标）＋ 1 个 P1（IDE run 配置默认关闭）＋ 若干 P2。P0 修掉后，剩余风险只能由真实 `gradlew clean build` 收敛（见 §7）。**

---

## 1. 必修清单（按严重度）

### P0 · 构建必失败（1 条）

**P0-1 `common/build.gradle:16` fabric-api 坐标少了 artifact 段**

```
common/build.gradle:16:	compileOnly "net.fabricmc.fabric-api:${project.fabric_api_version}"
fabric/build.gradle:36:	implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"   ← 正确
HEAD:build.gradle:30:	implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"   ← 重构前也正确
```

`gradle.properties:19` 的 `fabric_api_version=0.165.0+26.3` 只是**版本**，所以这一行解析成
`group=net.fabricmc.fabric-api`、`name=0.165.0+26.3`、**无 version** —— 既不是合法模块名也没有版本，
`:common:compileJava` 解析 `compileClasspath` 时必然失败（`Could not find net.fabricmc.fabric-api:0.165.0+26.3` /
`no version specified`）。这不是「用不到就无所谓」的依赖：`compileOnly` 只要在 `compileClasspath` 上就会被解析。

**修法（两种都可，推荐 a）**
```groovy
// a) 补齐坐标（与 fabric/HEAD 一致，最小 diff）
compileOnly "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
// b) 直接删掉：已核实 common/src/main 下 net.fabricmc.fabric.api 的 import 命中数为 0
```

> 证据：grep 实测 `common/src/main/java` 引用 `net.fabricmc.fabric.api` 的文件 = **0**；
> 也就是说 common 不需要 Fabric API，但**这一行在就会解析、就会失败**。

### P1 · 很可能坏 / 行为回退（2 条）

**P1-1 `fabric` 不再生成 IDE run 配置（迁移前会生成）**
Loom 的默认值：`DefaultRunConfigurationSettings.java:42` → `run.getGenerateRunConfig().convention(GradleUtils.isRootProject(project))`，
而 `GradleUtils.java:144-146` 是 `project.getRootProject() == project`。重构后 `fabric` **不是** root → 默认 `false`
→ IntelliJ/Gradle 同步不会再生成 “Fabric Client/Server” 运行配置（迁移前单模块就是 root，默认 `true`）。
不影响 `./gradlew runClient`，但影响用户点 IDE 绿色三角的既有习惯。
修法：`fabric/build.gradle` 的 `runs.client/server` 里补 `ideConfigGenerated(true)`（上游 fork 正是这么写的：`git show Ping-for-it/26.3:fabric/build.gradle`）。

**P1-2 `common` 的 run 任务只是 `enabled = false`，其依赖任务仍会跑**
`common/build.gradle:30-32` 关掉 `:common:runClient` 是**正确且必要**的（见 §3.Q2），但 Gradle 对 disabled
任务**仍会执行它依赖的任务**：根级 `./gradlew runClient` 会额外跑 `:common:configureClientLaunch`、
`:common:generateDLIConfig`、`:common:downloadAssets` 等（资产走共享缓存，不会第二个客户端起来）。
不致命，但白花时间且容易让人误以为 common 在启动客户端。**建议文档化为「用 `./gradlew :fabric:runClient`」**，
README:32 已宣称 `runClient = :fabric:runClient`，严格说只对「真正的启动」成立。

### P2 · 清理/文档（6 条）

| # | 位置 | 问题 | 建议 |
| --- | --- | --- | --- |
| P2-1 | `docs/features.md:359`、`:379`、`:382` | 仍写 `src/main/java/…`、`src/main/templates/…`、`src/client/java/…`（**活代码指针**） | 改成 `common/src/main/java/…`、`fabric/src/main/templates/…`、`fabric/src/client/java/…`（3 行，`sed` 即可） |
| P2-2 | `tools/.compile-check/` | 旧布局残留：`main/`、`client/`、`javac-main.log`、`javac-client.log`、`cp-main.txt`、`cp-client.txt`、`src-main.txt`、`src-client.txt`（脚本只 `rm -rf` 三个新目录） | 手动删一次；脚本里可选加一行清理旧名（该目录已 gitignore，**新克隆看不到**，故只算本地卫生问题） |
| P2-3 | `README.md:52,54`、`AGENTS.md` §8 | 链接 `docs/PORTING-PLAN.md`/`docs/FEATURES.md`/`docs/TEAM.md`，实际文件是 `porting-plan.md`/`features.md`/`team.md` → GitHub 上 404（**迁前就错**，`git show HEAD:README.md` 同样） | 顺手改大小写 |
| P2-4 | `README.md:32` vs `README.md:44` | 命令块里带 `#` 行内注释，而同页 44 行明确警告「命令块内不要带行内注释（cmd 会把 # 当任务名）」 | 把注释移到代码块外 |
| P2-5 | `docs/porting-plan.md:56,103` | 旧路径；56 是 M2 施工单（已完成）、103 是 M8 修复说明 | **可保留为历史**；建议 103 补一句「路径为当时布局」 |
| P2-6 | 工作区 | 根 `build/libs/` 仍是**迁移前**的 jar（旧布局产物），`fabric/build/` 尚不存在 | 真实构建前 `rm -rf build/`，避免拿旧 jar 当新产物验收 |

---

## 2. A · 组合完整性

### A1 PASS — 纯重命名

```
$ git diff --cached -M --name-status | cut -f1 | sort | uniq -c
    266 R100
$ git diff --cached -M --stat | tail -1
 266 files changed, 0 insertions(+), 0 deletions(-)
$ git diff --cached -M --summary | grep -v '(100%)' | wc -l
0
```
266 = **246 java** + 11 json（8 lang + sounds.json + mixins + template）+ 3 png + 1 ogg + 5 个无扩展名 SPI 文件。
**staged 集合里没有 A/D**（`uniq -c` 只有 `R100`）；9 个已改文件（`build.gradle`/`settings.gradle`/`gradle.properties`/
`AGENTS.md`/`README.md`/`PORTING-PROGRESS.md`/两个 compile-check/workflow）与 6 个新文件**未 staged**，
这是重构的预期形态（迁移者只 staged 移动）。

> 注意取证陷阱：`git status -- src` / `git diff --cached -- fabric/src/main/resources/META-INF/services` 这类
> **带 pathspec** 的调用会退化成 `D`/`A`（rename 两侧被裁剪后无法配对），**不能**当作「有增删」的证据。
> 全量（无 pathspec）的 `--name-status` 才是 266 `R100`。

### A2 PASS — 计数与资源

```
$ find common/src/main/java -name '*.java' | wc -l      → 162
$ find fabric/src/main/java  -name '*.java' | wc -l     →   4
$ find fabric/src/client/java -name '*.java' | wc -l    →  80
$ find deferred -name '*.java' | wc -l                  →  95
$ git status --porcelain -- deferred                    → (空；staged diff 也为空)
```
资源（逐文件枚举）：
- `common/src/main/resources` **13**：8 lang + `sounds.json` + `sounds/ping.ogg` + `textures/{arrow,ping}.png` + `icon.png` ✓
- `fabric/src/main/resources/META-INF/services` **3**（IPlatformContext/Network/ServerEvent 服务端 SPI）✓
- `fabric/src/client/resources` **3**（`pingforit.mixins.json` + IClientPlatformContextService + IPlatformClientEventService）✓
- `fabric/src/main/templates/fabric.mod.json` **1** ✓

### A3 PASS — 无残留

```
$ git ls-files --error-unmatch src → error: pathspec 'src' did not match any file(s) known to git
$ 空目录扫描（common/ fabric/ 全库，排除 build/.gradle/run/…）→ []
$ forge/ → forge/README.md  neoforge/ → neoforge/README.md
```

---

## 3. B · 构建接线

### B1 FAIL（P0×1，其余全对）

逐个文件核对（`settings.gradle` `build.gradle` `gradle.properties` `buildSrc/build.gradle`
`multiloader-common.gradle` `multiloader-loader.gradle` `common/build.gradle` `fabric/build.gradle`）：

| 检查点 | 结论 | 证据 |
| --- | --- | --- |
| 插件只在根声明版本、子模块无版本 | ✓ | `build.gradle:6-8` `apply false`；`common:1-4`/`fabric:1-4` 只写 id |
| 插件 id 真实存在 | ✓ | loom jar `META-INF/gradle-plugins/net.fabricmc.fabric-loom.properties → net.fabricmc.loom.LoomNoRemapGradlePlugin`；缓存里有 marker `net.fabricmc.fabric-loom.gradle.plugin:1.19.3` → **离线可解析** |
| 版本占位符在 `plugins {}` 里插值 | ✓ | Groovy DSL 支持（Fabric 官方模板同写法），值来自 `gradle.properties:10` |
| 属性继承 | ✓ | `version/group/minecraft_version/loader_version/fabric_api_version` 均在根 `gradle.properties`，子项目直接读 |
| `ext.integrationJars` 早于 `dependencies{ compileOnly integrationJars }` | ✓ | 约定脚本 `:43` 赋值；`plugins{}` 先于脚本体执行 → `common:17`/`fabric:37` 读到时已存在 |
| closure-in-ext 当方法调用 | ✓ | `ext.attachIntegrationJarCheck = { String t, String p, String m -> … }`（`:59`），`common:22`/`fabric:41` 以方法形式调用 → Groovy `Script.invokeMethod` 会回退到属性闭包；closure 内用的是 `:37-40` 捕获的 `projectRef/loggerRef/tasksRef` 词法变量，**不依赖 owner 动态解析** |
| 检查放在 `doFirst`（不在配置期） | ✓ | `:60-74`；因此无 jar 的 checkout 里 `tasks`/`clean`/`sync` 照常 |
| config-cache 友好 | ✓(有保留) | 迁移前 `gradle.properties` **就已** `org.gradle.configuration-cache=true` 且同款 `doFirst`+`logger` 写法构建通过；执行期只碰 `java.io.File`，不 resolve FileTree |
| `jar { from project(':common').sourceSets.main.output }` | ✓ | `fabric:63-64` 配 `dependsOn ':common:classes'`；跨项目取 SourceSetOutput 在 CC 下允许（未开 project isolation） |
| **fabric-api 坐标** | **✗ P0** | `common:16` 见 §1 |
| `tasks.matching{}.configureEach{ enabled = false }`（common 关 run） | ✓ | 语义见 §3.Q2 |

### B2 PASS — Loom API 面（javap 原文）

```
$ javap -cp fabric-loom-1.19.3.jar net.fabricmc.loom.api.LoomGradleExtensionAPI | grep -E 'splitEnvironment|getRunConfigs|getRuns|void runs|void mods|getMods'
  public abstract void runs(org.gradle.api.Action<org.gradle.api.NamedDomainObjectContainer<net.fabricmc.loom.configuration.ide.RunConfigSettings>>);
  public abstract org.gradle.api.NamedDomainObjectContainer<net.fabricmc.loom.configuration.ide.RunConfigSettings> getRunConfigs();
  public default  org.gradle.api.NamedDomainObjectContainer<net.fabricmc.loom.configuration.ide.RunConfigSettings> getRuns();
  public abstract void mods(org.gradle.api.Action<org.gradle.api.NamedDomainObjectContainer<net.fabricmc.loom.api.ModSettings>>);
  public abstract org.gradle.api.NamedDomainObjectContainer<net.fabricmc.loom.api.ModSettings> getMods();
  public abstract void splitEnvironmentSourceSets();
  public abstract boolean areEnvironmentSourceSetsSplit();

$ javap -cp fabric-loom-1.19.3.jar net.fabricmc.loom.configuration.ide.RunConfigSettings | grep -E 'runDir|setConfigName|void client|void server'
  public void client();
  public void server();
  public void setConfigName(java.lang.String);
  public void setRunDir(java.lang.String);
  public void runDir(java.lang.String);
```
四个 `runDir`/`configName` 方法都是 **@Deprecated 兼容壳**，源码里明确转发到新 Property：
`RunConfigSettings.java:205-215` `setRunDir(String) → getRunDirectory().set(getProject().file(runDir))`；
`:160-168` `setConfigName → getDisplayName().set(name)`；`runDir(String) → setRunDir`。
→ **构建脚本用的四个调用全部存在且有效**（会有 deprecation 提示，不报错）。

### B3 PASS（源码级证明）— split 源集的 client 输出会自动进 `jar`

`net/fabricmc/loom/configuration/providers/minecraft/MinecraftSourceSets.java:191-197`（`splitEnvironmentSourceSets()` →
`evaluate(project)`，`LoomGradleExtensionApiImpl.java:472-481` 调用）：

```java
// Include the client only output in the jars
project.getTasks().named(mainSourceSet.getJarTaskName(), Jar.class).configure(jar -> {
    jar.from(clientOnlySourceSet.getOutput().getClassesDirs());
    jar.from(clientOnlySourceSet.getOutput().getResourcesDir());
    jar.dependsOn(project.getTasks().named(clientOnlySourceSet.getProcessResourcesTaskName()));
});
```
所以 `fabric` 的 `jar` 自动包含 `fabric/src/client/java` 的 class 与 `fabric/src/client/resources`（`pingforit.mixins.json` + 2 SPI），
**迁移不需要为此加任何 wiring** —— 与「迁移者说这里什么都没改」一致。
反向佐证：迁移前产物 `build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar` 里 **392 个 class**，
而本次 `compile-check` 三遍合计 262(common)+4(fabric-main)+126(fabric-client) = **392** —— 数字精确吻合。

> 仍需真实构建确认的只有一点：**新布局下这三个数字能否原样进 jar**（机制已证明，产物未证明）。

### B4 PASS — 合并 jar 不会重复、不会嵌套 common jar

| 风险 | 结论 | 证据 |
| --- | --- | --- |
| common 的 class 会不会出现两次 | **不会** | `implementation project(':common')` 只进 **classpath**；只有 `include` 配置会被 Loom 的 `IncludeConfigurations.nestJars` 嵌套（`NonRemappedJarTaskConfiguration.java:52-59`）。本项目不用 `include`，故 common 的 jar 不会成为 jar-in-jar；class 只经 `fabric:64 from(common.sourceSets.main.output)` 出现一次 |
| 三个源集之间会不会同名类 | **不会（实测 0）** | 逐文件比对：common-main↔fabric-main = 空、common-main↔fabric-client = 空、fabric-main↔fabric-client = 空。注意 `nx/pingwheel/common/{config,core,math,name,platform,util,integration}` 是**跨源集的 split package**（合法，非 JPMS），但类名不相交 |
| 资源路径冲突 | **不会（实测 0）** | 19 条资源路径全互异（common 13 + fabric main 3 + fabric client 3），交集为空 |
| MANIFEST 冲突 | **不会** | Loom 用 `doLast` 的 `ManifestModificationAction`（`NonRemappedJarTaskConfiguration.java:43-48`）写 `Fabric-Loom-Client-Only-Entries` 等；我们的 `from(...)` 不提供 `META-INF/MANIFEST.MF` |
| 旧产物对照 | **0 重复条目** | 旧 jar 464 条目、duplicate entries: none、根目录有 `LICENSE_pingforit` |
| Gradle 9 的重复条目策略 | 待真实构建 | Gradle 9 对 archive 里未声明策略的重复条目会**报错**，所以 `clean build` 会替我们把这条彻底证伪（已写进 AGENTS §10 自检） |

### B5 PASS — `runDir` 仍指仓库根 `run/`

- `fabric/build.gradle:23,28` `runDir(rootProject.file('run').absolutePath)` → `setRunDir` 里 `getProject().file(abs)` 对**绝对路径**直接返回该路径（`RunConfigSettings.java:213-215`）→ 落在 `<repo>/run`。
- 唯一会抛异常的是 **getter** `getRunDir()`（`:197-203`：runDir 不在项目目录内会 `IllegalStateException`）。全 jar 源码里**没有内部调用者**（检索 `getRunDir(` 只命中定义与无关的 `AbstractProductionRunTask`），IDE 生成走 `RunConfigUtils.formatRunDir(…getRunDirectory()…)` → 安全。
- 「client+server 共用一个 run 目录」与迁移前**完全同构**：迁移前没有 `runs{}` 块，默认 `DefaultRunConfigurationSettings.java:41` `run.getRunDirectory().set(project.file("run"))`，而当时项目就是仓库根 → 同样是根 `run/`。

### B6 PASS — 元数据与产物名

- `fabric/build.gradle:48-55`：`from 'src/main/templates/fabric.mod.json'`（相对 `fabric/` → 正确落点）、`into build/generated/modMetadata`、`inputs.property 'version'`、`sourceSets.main.resources.srcDir(provider)`、`processResources.dependsOn` 全在。
- 产物名：`multiloader-common.gradle:9-11` `base { archivesName = 'Ping-For-It' }` + `fabric/build.gradle:61` `archiveClassifier = 'fabric-26.3'` + 版本 `0.4.2-pfi-beta1`
  → `fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`（no-remap 插件下无 `remapJar`：`RemapTaskConfiguration.java:41-44` 在 `dontRemapOutputs()` 时走 `NonRemappedJarTaskConfiguration`，不再创建 remapJar）。

### B7 PASS — 集成 jar 检查与四处 glob

- `common/build.gradle:22-25` `attachIntegrationJarCheck('compileJava','voicechat-',…)` —— `VoiceChatIntegration` 在 `common/src/main/java/nx/pingwheel/common/integration/` ✓
- `fabric/build.gradle:41-44` `attachIntegrationJarCheck('compileClientJava','modmenu-',…)` —— `ModMenuIntegration` 在 `fabric/src/client/java/nx/pingwheel/fabric/integration/` ✓
- 任务名在 split 源集下确实是 `compileClientJava`（`MinecraftSourceSets.java:156` 创建 client 源集），且 `tasks.matching{}.configureEach{}` 是惰性的 → 与声明顺序无关。
- 四处 glob 逐字一致：`multiloader-common.gradle:46-54` ≡ `compile-check.sh:85` ≡ `compile-check.ps1:93` ≡ workflow 的三个 curl 产物名
  （5 个 include + `*-sources.jar`/`*-javadoc.jar` 排除）。

### B8 PASS — 没有模块相对的 `run/mods`

`multiloader-common.gradle:40,43-44` 用 `rootProject.file('run/mods')` + `rootProject.fileTree(...)`；
再无 `file('run/mods')` 这类模块相对写法（迁移前是 `fileTree(dir: "run/mods")`，当时 root 就是唯一项目，语义等价）。

---

### 3.Q · 复审 Lead 追加的三个问题

#### Q2a：Loom 是否给**每个** loom 项目（含纯库 common）都建 run 配置与 `run<Name>` 任务？——**是**

`net/fabricmc/loom/task/LoomTasks.java`：
```java
 203|		extension.getRunConfigs().whenObjectAdded(config -> {
 204|			var runTask = getTasks().register(getRunConfigTaskName(config), RunGameTask.class, config);
 …
 229|		extension.getRunConfigs().create("client", RunConfiguration::client);
 230|		extension.getRunConfigs().create("server", RunConfiguration::server);
```
`:229-230` **无条件**注册 client/server（没有「是否加载器模块」的判断），`:203-204` 为每个配置建 `run<Name>` 任务；
`LoomGradlePlugin.java:61-70,95-97` 在 **apply 期**就执行这批 setup job（`project.getObjects().newInstance(jobClass).run()`）。
所以 `:common:runClient` / `:common:runServer` **确实存在**，analyst 的推断成立。

#### Q2b：`tasks.matching{}.configureEach{}` 能覆盖「稍后创建」的任务吗？disabled 任务是 SKIPPED 吗？——**都能**

- `TaskCollection.matching{}.configureEach{}` 是 Gradle 的**惰性**约定：对已存在与之后创建的所有匹配任务都生效。
  此处顺序其实是「先创建后配置」（任务在 apply 期建，patch 在脚本体），`configureEach` 两种顺序都覆盖。
- `enabled = false` 的任务在执行图里保留但**不执行其 action**，结果是 `> Task :common:runClient SKIPPED`，**不是失败**。
- 一个必须知道的副作用：**它的依赖任务仍会被执行**（`:common:generateDLIConfig`/`configureClientLaunch` 等），
  见 P1-2；不会起第二个客户端（弹窗的是任务 action，被跳过）。

#### Q2c：没有这个 patch 时根级 `./gradlew runClient` 会同时选中两个项目吗？——**会**（推断成立）

Gradle 的按名选任务会匹配**所有项目**里叫 `runClient` 的任务，因此会执行 `:common:runClient` 与 `:fabric:runClient`。
两个 `RunGameTask` 都指向 `run/`（common 默认是 `common/run`，因为没有显式 runDir），两个客户端会抢同一份
`logs/latest.log`/`options.txt`/世界锁 —— **patch 是对的，不要删**。
（更稳的用法仍是 `./gradlew :fabric:runClient`。）建议（P2）：把这段 disable 放进 `multiloader-common.gradle`，
由 `multiloader-loader.gradle` 反向 re-enable，这样将来加 forge/neoforge 时不会漏。

#### Q3：两个 loom 项目之间 `implementation project(':common')` 会不会 `No matching variant`？——**没有任何证据表明会；建议保留**

从 loom 1.19.3 全量源码（576 个源文件）检索：
- **没有** `Attribute.of(`、**没有** `capability(` / `outgoing.capability` —— Loom 不会给 `apiElements`/`runtimeElements` 加自定义属性或能力。
  唯一一处 attribute 是 `RemapConfigurations.java:144-145`，只作用于 Loom **自己创建**的 remap 配置（`modImplementation` 等）。
- no-remap 分支（`RemapTaskConfiguration.java:41-44` → `NonRemappedJarTaskConfiguration`）只改 `jar` 任务、`include` 嵌套与 `unmappedModCollection`，**完全不动 outgoing variants**。
- 本项目的 `multiloader-common.gradle` 也**没有**向外声明 capability（上游 fork 声明了 capability，所以上游必须在消费端写
  `capabilities { requireCapability … }`；我们没这层，普通 java-api/java-runtime 变体直接匹配）。

**结论：保留 `implementation project(':common')`。** 唯一保留意见是「我无法运行 Gradle」，故标
**NEEDS-REAL-BUILD（低风险）**。若真实构建真的报变体不匹配，fallback 补丁（注意会丢 Gradle 的自动任务依赖与传递依赖）：

```groovy
// fabric/build.gradle —— 仅在 implementation project(':common') 无法解析时使用
dependencies {
    compileOnly files(project(':common').sourceSets.main.output)
    runtimeOnly files(project(':common').sourceSets.main.output)   // runClient 需要
}
tasks.named('compileJava')       { dependsOn ':common:classes' }
tasks.named('compileClientJava') { dependsOn ':common:classes' }
```

#### Q4：`jar` 合并 vs Loom 自己塞 client 输出 —— 有冲突吗？SPI 会重复吗？

- **重复条目**：不会。Loom 只追加 **client 源集**的 `classesDirs` + `resourcesDir`（B3），我们追加 **common 源集**输出，
  两类路径互斥（B4 实测 0 同名）；Gradle 9 若发现重复会直接报错，因此这条会被真实构建强制验证。
- **MANIFEST**：Loom 在 `jar` 上用 `doLast` 改写 manifest，我们的 `from(...)` 不带 `META-INF/MANIFEST.MF` → 无覆盖竞争。
- **META-INF/services 只有一份**：**确认**。5 个 SPI 文件里 3 个在 `fabric/src/main/resources`、2 个在 `fabric/src/client/resources`，
  `common/src/main/resources` 下**完全没有 META-INF**（实测；旧 jar 里也正好 5 条、无重复）。
- `LICENSE_pingforit` 的最终名（`fabric:66-68` `rename { it + '_pingforit' }`）与**迁移前产物里的实际文件名一致**
  （旧 jar 根目录：`fabric.mod.json` / `icon.png` / `pingforit.mixins.json` / `LICENSE_pingforit`）→ 静态字面量反而去掉了
  旧代码依赖 `project.name` 的隐式耦合。

---

## 4. C · 质量闸门

### C1 PASS — 我亲自跑的结果

```
$ bash tools/compile-check.sh
compile-check: integration jars from run/mods: DistantHorizons-3.3.4-26.3-fabric-neoforge.jar, modmenu-21.0.0.jar, voicechat-fabric-2.6.27+26.3.jar
[gate] common/server-safety OK (common/src/main/java: no net.minecraft.client. / com.mojang.blaze3d)
compile-check: javac=C:\Program Files\Java\jdk-25\bin\javac.exe (javac 25.0.4.1)
compile-check: classpath jars=159 (MC 3 + modules-2 cache)
[common] sources=162  exit=0  errors=0  classes=262
[fabric-main] sources=4  exit=0  errors=0  classes=4
[fabric-client] sources=80  exit=0  errors=0  classes=126
[gate] common/server-safety re-check after compile: OK
[summary] common errors=0 classes=262 | fabric-main errors=0 classes=4 | fabric-client errors=0 classes=126
$ echo $? → 0
```
脚本静态核对：
- 三遍链式 classpath ✓ `compile-check.sh:174,177,180`（fabric-main 前置 common 输出；fabric-client 再前置 fabric-main）。
- 闸门 ✓ `:115-129`（编译前）与 `:183-192`（编译后复查）各一次，`grep -rnE 'net[.]minecraft[.]client[.]|com[.]mojang[.]blaze3d'` 输出 **file:line:text**
  （格式已验证：对 `fabric/src/client/java` 跑同样 grep 会打印 `…ClientPingRuntime.java:8:import net.minecraft.client.Minecraft;`），命中 `exit 1`。
- glob 与 buildSrc 一致 ✓ `:85` + `:91-93` 排除。
- 无旧路径 ✓（`:38,177,180` 全是新布局三目录）。
- `.ps1` 与 `.sh` 逐段对应（`:117-135`、`:206-224`、`:239`），参数默认 `all`；唯一既有瑕疵：单传 `-Pass fabric-client` 时前面几遍不编译、
  其输出目录已被 `:193-196` 删除 → 只能默认全跑。

### C2 PASS（含 P2-2）

实测 `tools/.compile-check/` 里同时存在：新布局的 `common/` `fabric-main/` `fabric-client/` `javac-{common,fabric-main,fabric-client}.log`
**和** 旧布局的 `main/` `client/` `javac-main.log` `javac-client.log` `cp-main.txt` `cp-client.txt` `src-main.txt` `src-client.txt`（还有 `live-run.log`）。
脚本只 `rm -rf` 三个新目录（`:166`）。**排名：P2** —— 该目录在 `.gitignore` 里（`tools/.compile-check/`），**新克隆者根本拿不到这些旧文件**，
只影响本机；但本机上看 `javac-client.log` 很容易误当成当前结果。建议：手动清一次 + 在脚本里顺手补旧名清理（可选）。

### C3 PASS

`.github/workflows/build.yml:60` `path: fabric/build/libs/Ping-For-It-*.jar` ✓；`:36-49` fetch 仍写 **根** `run/mods` ✓；
`:27-33` 的注释（Keep this list in sync with ext.integrationJars … common/ -> voicechat-, fabric/ -> modmenu-）与现状一致 ✓。
小提示（不计缺陷）：该 glob 也会匹配 `…-fabric-26.3-sources.jar`（`withSourcesJar()`），迁移前同样如此。

### C4 PASS（含 P2）

- 19 个改动/新增文件的**工作区字节**：`crlf=0`，全 LF，无 BOM。
- `.gitattributes` 已强制 `*.java / *.gradle / *.sh / *.yml / *.json / *.md text eol=lf`，`gradlew`/`*.sh` 也是 lf。
- `git ls-files --eol`：246 个移动的 `.java` 全是 `i/lf` ✓；但有 **8 个 `.java` 工作区是 `w/crlf`**
  （`common/…/integration/DistantHorizonsIntegration.java`、`fabric/src/client/…/hud/WheelHudRenderer.java`、
  `…/outline/BlockDisplayPolicy.java`、`…/outline/BlockDisplayWhitelist.java`、`…/integration/sable/client/SableClientProvider.java`、
  `…/math/MathUtils.java`、`…/render/spike/OutlineExperiment.java`）+ `docs/logs/tools/watch-file.ps1` + `gradlew.bat`（.bat 本就该 CRLF）。
  这些是**迁移前就存在的**工作区状态（`i/lf` 会归一化，`git status` 干净），不是本次引入；提交时不会带进仓库。

---

## 5. D · 文档与形状

### D1 PASS（含 P2-3）— AGENTS.md §2/§3/§4/§10

逐条比对**实际文件系统**：
- §2 目录职责：162/4/80/95、资源 4 类、`buildSrc` 两个约定、`fabric.mod.json` 模板 → 生成目录、forge/neoforge「不在 settings.gradle」、`tools/` 三遍校验 —— **全部属实**。
- §3：`./gradlew -g D:/Gradle build`、产物 `fabric/build/libs/…`、`buildAll`（`build.gradle:11-15` 确实 `dependsOn ':common:build', ':fabric:build'`）、
  dev 根 `run/`、`runClient` 只属 fabric（含新补的 patch 说明）—— **属实**。
- §4：settings 只 include 两个、约定插件职责、两个 split、`loom.mods`、`runs` 显式 runDir、`generateModMetadata`（Sync + inputs.property）、
  `release 25` + `withSourcesJar()`、产物名公式、两个集成检查 —— **属实**。
- §10：新增的「合并 jar 完整性」自检（`nx/pingwheel/common/**` 与 `fabric/**` 都在、mixins json、assets/icon、`LICENSE_pingforit`、
  5 份 SPI、`unzip -l | sort | uniq -d` 无重复）**恰好就是 B3/B4 的验收口径**，写得准确。
- 瑕疵：§8 末尾 `[docs/TEAM.md](docs/TEAM.md)`、§2 里 `TEAM.md` 与实际 `docs/team.md` 大小写不符（**既有**，`git show HEAD:AGENTS.md` 同样）。
- §0/§6/§9 里的旧路径/旧产物属**历史记录**，按规格允许保留（§9 已明说）。

### D2 PASS（含 P2-4）— README

版本矩阵、构建命令、产物位置、`common/+fabric/` 说明、`runClient = :fabric:runClient`、「本地快速校验」全部正确；
第 20 行已明确标注「重构前产物在根 `build/libs/`」，不会被误读。唯一内部矛盾见 P2-4。

### D3 PASS — forge/ neoforge/ README

两份 README 都明确：**没有 `build.gradle`、不在 `settings.gradle` 的 include 里**、`build`/`buildAll`/IDE 同步都不构建、
现在没有对应源码；未来需要 ForgeGradle / ModDevGradle + 对应 26.3 版本（标注「未验证」）；`implementation project(':common')`
由 `multiloader-loader` 提供 —— **无假文件、无过度承诺**。

### D4 FAIL（P2-1 / P2-5）— 旧路径残留

| 位置 | 内容 | 判定 |
| --- | --- | --- |
| `docs/features.md:359` | `src/main/java/nx/pingwheel/common/domain/TargetTypeCatalog.java:43-82` | **活代码指针 → 必须改**（`common/src/main/java/…`） |
| `docs/features.md:379` | `src/main/templates/fabric.mod.json:16` | **活指针 → 必须改**（`fabric/src/main/templates/…`） |
| `docs/features.md:382` | `src/main/templates/fabric.mod.json:15` + `src/client/java/nx/pingwheel/fabric/integration/ModMenuIntegration.java:7-12` | **活指针 → 必须改** |
| `docs/porting-plan.md:56` | M2 施工单「搬入 `src/client/java`」 | **历史**（M2 已完成）→ 可留 |
| `docs/porting-plan.md:103` | M8 修复说明里的 `src/client/java/…/FabricClient.java:9-10,110-111` | **准历史**（描述修复前代码）→ 可留，建议加「（当时布局）」 |

**推荐动作**：本次就改 `features.md` 这 3 行（一次 `sed` 的事，且它是「查功能/查证据」的索引文档，旧路径直接破坏可检索性）；
`porting-plan.md` 两条不动，最多在 103 行前补 4 个字。

### D5 PASS（但差异登记不全）

上游对照：`git ls-tree Ping-for-it/26.3` 与 `git show Ping-for-it/26.3:settings.gradle|common/build.gradle|fabric/build.gradle|buildSrc/…`。

**R1–R6 逐条属实，且没有第 7 条「目录形状」差异**：
- R1 ✓ `forge/`、`neoforge/` 只有 README，`settings.gradle:22-24` 注释掉（上游是真模块，有 `forge/src`、`neoforge/src`）。
- R2 ✓ 上游 client 代码在 `common/src/main/java`（168 条路径含 `/client/`，`fabric/src/client/java` = 0 文件）；我们把 client 放 `fabric/src/client`（80 java）。
- R3 ✓ 上游 `fabric/src/main/resources/fabric.mod.json` 直接进资源（并用 `processResources{filesMatching{expand}}`）；我们用 templates + `generateModMetadata`。
- R4 ✓ 上游 `runDir("runs/client")`/`runs/server`；我们用根 `run/`。
- R5 ✓ 上游没有 `deferred/`、`tools/`、`Mod_Project/`；我们保留。
- R6 ✓ 上游 `common` 用 `net.neoforged.moddev` 单源集；我们 `common` 也 `splitEnvironmentSourceSets()`。

**但 `00-plan.md` §6 的差异表漏了 5 条真实差异（建议补成 R7–R11，均属已决策范围，只是没登记）：**

| # | 差异 | 依据 |
| --- | --- | --- |
| R7 | `common` 的构建工具是 **fabric-loom（no-remap）**，不是上游的 `net.neoforged.moddev` / NeoForm | 上游 `common/build.gradle` plugins 是 `multiloader-common` + `net.neoforged.moddev` |
| R8 | 共享代码的传递方式是 **Gradle 项目依赖 + jar 合并**，不是上游的 `commonJava`/`commonResources` 配置 + 带 capability 的 `compileOnly(project(':common'))` | 上游 `multiloader-loader.gradle` |
| R9 | 没有向 outgoing variants 声明 capability，也没有 `withJavadocJar()` / manifest 属性（Specification-*、MixinConfigs） | 上游 `multiloader-common.gradle` |
| R10 | 没有 `common/src/test`、没有 `CHANGELOG.md`/`THIRD-PARTY-NOTICES.md`/`LICENSE-APACHE-2.0`、`AGENTS.md` 在根而不是 `docs/` | 上游顶层文件树（M10 才做发布资产，属计划内） |
| R11 | 没有 mappings/parchment（`loom.layered { officialMojangMappings() }`），26.3 无混淆故不需要 | 上游 `fabric/build.gradle` |

> 结论：**没有出现「计划外」的目录/文件形状**；缺的是**差异登记表**的完整性（文档负债，P2 级）。

---

## 6. E · 保真

### E1 PASS — 内容零改动

- 266 个移动文件全部 `R100`（`git diff --cached -M --summary | grep -v '(100%)' | wc -l` = 0）→ **逐字节相同**：
  包声明未变（目录 = 包路径，靠 `git mv` 保持）、`pingforit.mixins.json` 未变（R100）、`fabric.mod.json` 模板未变（R100）、
  3+2 个 SPI 未变（R100）、`icon.png`/贴图/音效/lang 未变（R100）。
- 抽查语义：`nx.pingwheel.*` 23 个包（common-main）+ 26 个包（fabric-client）全部与迁移前相对路径一致；
  `mixins.json` 的 `package: nx.pingwheel.common.mixin` 与 `fabric/src/client/java/nx/pingwheel/common/mixin/` 下 8 个类仍同源集（R13 风险未触发）。
- `deferred/` 95 java 未进任何编译路径（`compile-check` 三遍的源目录不含它）。

---

## 7. 必须由用户 `gradlew clean build` 收敛的清单（NEEDS-REAL-BUILD）

按风险从高到低：

1. **P0 修复后 `:common:compileJava` 能否通过**（fabric-api 坐标）。这是唯一「已知必挂」的点。
2. **两个 loom 项目 + `implementation project(':common')` 的变体解析**（§3.Q3）。证据表明没问题，但只有 Gradle 能给答案。
3. **`runs { client { … } }` 在「client 元素已由 Loom 预先创建」时是 configure 而不是重复 create**（`:229-230` 已建，脚本再写）。
   若这里抛 `Cannot add a DomainObject with name 'client' as it already exists`，说明 Gradle 侧语义与预期不符（上游 fork 用同样写法，概率很低）。
4. **最终 jar 的内容与唯一性**：`unzip -l fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`
   → 392 class（262/4/126）、`pingforit.mixins.json`、`fabric.mod.json`（version 已替换）、`assets/pingforit/**`、`icon.png`、
   `LICENSE_pingforit`、5 份 `META-INF/services` 各一份、`unzip -l | awk '{print $4}' | sort | uniq -d` **为空**（Gradle 9 重复条目会直接失败）。
5. **产物路径/名字**：`fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar` 存在。
6. **`./gradlew runClient`**：只真正启动 `:fabric`（输出里 `:common:runClient SKIPPED`），run dir = 仓库根 `run/`。
7. **configuration cache**：连跑两次 `gradlew build`（第二次应复用 CC），确认 `fileTree` 依赖 + `doFirst` logger 不破坏 CC。
8. （可选，验 P1-1）`./gradlew idea` 或 IDE 同步后是否出现 “Fabric Client/Server” 运行配置 —— 现在是**不会**，除非补 `ideConfigGenerated(true)`。

---

## 8. 方法与局限（诚实声明）

- **没有运行 Gradle**（会话沙箱限制），所以任何「Gradle 运行期语义」结论都来自：loom 1.19.3 的 **源码 jar**（576 个源文件，含全部相关实现）
  + `javap` 的字节码签名 + 上游 fork 的**同类写法**。这三者能证伪大部分假设，但不能替代一次真实构建。
- 旧产物 jar（`build/libs/`）是**迁移前**的构建结果，只用来做「期望布局」的对照（392 class / 5 SPI / 无重复 / `LICENSE_pingforit`）。
- 我保留了 `git pathspec` 造成的 D/A 假象作为方法提醒（§A1），所有结论一律以**无 pathspec 的全量 diff** 为准。
- 未审：`deferred/`、`Mod_Project/`、`run/` 内容（按规格不在本次范围）。

---

# 第二轮复核（2026-11-03 晚，task-3 reopen 后）

> 范围：只复核本轮改动 + 回归。第一轮结论不重写（见上文）；此处给出**逐条状态**。
> 复核方式：直读文件字节（`JSON.stringify` 去可疑字符）、`grep`、重跑 `bash tools/compile-check.sh`、以及再次对 loom 1.19.3 源码取证。

## R2.0 总判定：**FAIL（新 P0×1）**

| 条目 | 本轮声称 | 复核结论 |
| --- | --- | --- |
| P0-1 `common/build.gradle:16` 坐标 | 已修 | **PASS** — 现为 `net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}`（三处一致） |
| **P0-2（新）`fabric/build.gradle:39`** | 未提及 | **FAIL — 新增的 P0 回归**：`implementation "net.fabricmc.fabric-api:${project.fabric_api_version}"` 少了 `fabric-api:` 段 |
| P1-1 `ideConfigGenerated(true)` | 已加 | **PASS** — 签名/语义/位置全部核实；副作用见 R2.2 |
| P1-2 文档改推荐命令 | 已写 | **PARTIAL** — §4:73 正确，但 §3:64 仍写「只会跑 :fabric」，与 §4 自相矛盾（P3） |
| P2-1 `features.md` 三处活指针 | 已改 | **PARTIAL** — 359/379 全对，382 修了两处但仍残留 1 个缩写裸路径（P3） |
| P2-2 `.compile-check` 旧残留 | 已删 | **PASS** — 只剩 common / fabric-main / fabric-client 三套（+ 既有的 `live-run.log`） |
| P2-3 README/AGENTS 链接大小写 | 已改 | **PASS**（README:52-54 与 AGENTS:138 均指向真实文件）；但 AGENTS:47 的散文清单仍写 `PLAN / PROGRESS / FEATURES / … / TEAM.md`（P3） |
| P2-4 README 命令块行内注释 | 未处理 | **FAIL（仍在，且变严重）** — README:32 把 `#` 注释放进了**推荐命令**那一行，直接违反 README:44 自己的警告 |
| P2-5 `porting-plan.md:56,103` | 保留历史 | **PASS（按裁定）** |
| P2-6 根 `build/libs/` 旧 jar | 未处理 | **仍存在**（`Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`，771558 B，2026-11-03 10:53），真实构建前建议清掉 |
| 组合完整性 | 未动 | **PASS** — 仍是 `266 R100` staged，`deferred/` 零改动 |
| 编译闸门 | Lead 已跑 | **PASS** — 我重跑：exit 0，`[summary] common 0/262 | fabric-main 0/4 | fabric-client 0/126`，gate 文本 2 次 |

## R2.1 新 P0（必须先修）：`fabric/build.gradle:39`

字节级复核（排除不可见字符/误读）：
```
fabric/build.gradle:39 repr: "\timplementation \"net.fabricmc.fabric-api:${project.fabric_api_version}\""
common/build.gradle:16 repr: "\tcompileOnly \"net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}\""
```
`grep -rn 'fabric-api' build.gradle common/build.gradle fabric/build.gradle buildSrc/` 只有两行命中：
`common:16`（正确）与 `fabric:39`（缺段）。**没有**任何 `substitut`/`resolutionStrategy`/`eachDependency` 能把它救回来。

**为什么会挂（两重）**：
1. 解析：仍是无版本的两段坐标（`group=net.fabricmc.fabric-api`、`name=0.165.0+26.3`）→ `:fabric:compileJava` 的 `compileClasspath` 解析失败。
2. 编译：`fabric` 里**确有 9 个 java 文件 import `net.fabricmc.fabric.api.*`**（`FabricMain`、`FabricClient`、`PlatformNetworkServiceImpl` 等），
   即使把这一行删掉也会 `package net.fabricmc.fabric.api… does not exist`。

**修**（一行，与 `common:16`/HEAD:30 对齐）：
```groovy
implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
```
> 回归成因推测：改 `common` 时对两处同形字符串做了一次替换，把 `fabric` 的那份改坏、`common` 的那份改对。
> 这类「同形字符串跨文件」改动，建议改完立刻 `grep -rn 'fabric-api' ` 一次自检（两行都必须是三段式）。

## R2.2 P1-1 复核：`ideConfigGenerated(true)` 有效（附用户可见副作用）

- **签名存在**：`RunConfigSettings.java:340-343`
  ```java
  @Deprecated
  public void ideConfigGenerated(boolean ideConfigGenerated) {
      getGenerateRunConfig().set(ideConfigGenerated);
  }
  ```
  → `runs { client { … } }` 的闭包 delegate 就是 `RunConfigSettings`，所以**写在块内有效**，且覆盖了默认
  `convention(GradleUtils.isRootProject(project))`（＝fabric 为 false）。`@Deprecated` 只是提示，不报错。
- **真的会生成**：`IdeaConfiguration.java:20-27` 对**每个项目**注册 `ideaSyncTask`，
  仅当本项目有 run 配置 `getGenerateRunConfig()==true` 时启用并依赖 `configureClientLaunch` —— 没有「只在 root 生成」的额外闸门。
- **用户会看到的名字**：`RunConfigUtils.java:132-142` 的 `getDisplayName` 在 `appendProjectPathToDisplayName`（默认 true）
  且项目非 root 时会追加项目路径 → IDE 里显示为 **`Fabric Client (:fabric)` / `Fabric Server (:fabric)`**（不是裸 `Fabric Client`）。
  想显示成裸名，需要额外 `getAppendProjectPathToDisplayName().set(false)`（**不建议**，多模块下带项目路径更好辨认）。
- 生成的运行配置走 `preferGradleTask=true`（默认）→ 点击即执行 `:fabric:runClient`，与命令行等价。

## R2.3 P2/P3 复核细节（可直接照改）

1. **P2-4 README 行内注释（仍未修，且落到推荐命令上）**
   `README.md:32` `./gradlew -g D:/Gradle :fabric:runClient   # 推荐：根级 runClient 也可用…`
   与 `README.md:44`「**命令块内不要带行内注释**：Windows cmd 下 `#` 不是注释符，会被当成任务名」直接冲突。
   `#` 在 bash 里没问题，但该 README 同一节专门给了 Windows cmd 用法，用户复制整行到 cmd 就会把
   `# 推荐：…` 当任务名解析。**建议：把注释移到代码块外**（例如 `:fabric:runClient` 单独一行，推荐理由写在下方的 bullet 里）。
2. **P3 AGENTS 自相矛盾**：`AGENTS.md:64`（§3）「所以根级 `./gradlew runClient` **只会跑** `:fabric:runClient`」
   vs `AGENTS.md:73`（§4）「根级 `./gradlew runClient` 虽然能跑，但**会先执行 `:common` 的 run 任务依赖**」。
   §4 是准确的（我第一轮 P1-2 的结论）。建议把 §3 那句改成「只会**启动** `:fabric:runClient`（`:common:runClient` 为 SKIPPED，
   但其依赖任务仍会先执行，见 §4）」。
3. **P3 `AGENTS.md:47`**（§2 代码块）：`docs/  PLAN / PROGRESS / FEATURES / reviews / TEAM.md / restructure/`
   —— 实际文件是 `porting-plan.md` / `PORTING-PROGRESS.md` / `features.md` / `team.md`。散文清单不构成链接，
   GitHub 不会 404，但与刚修好的 §8 命名不一致，建议一并改。
4. **P3 `docs/features.md:382` 残留**：同一行里两处已改对（`fabric/src/main/templates/fabric.mod.json:15`、
   `fabric/src/client/java/…/ModMenuIntegration.java:7-12`），但「启用后改变什么」列的**缩写指针**仍是
   `src/client/.../integration/ModMenuIntegration.java:7-12`（无 `fabric/`）。按你给的验收口径（不得再有裸 `src/main/|src/client/`）
   这条算未达标；改成 `fabric/src/client/...` 即可。
5. **AGENTS §3 顶部代码块仍写 `./gradlew -g D:/Gradle runClient`（AGENTS.md:58）** —— 我的判断：**可以保留，不必统一**。
   理由：它记录的是「可用入口」，紧随其后的 §3 bullet(:64) 与 §4(:73) 都给出了推荐写法；把它改成 `:fabric:runClient`
   反而会让「根级也能跑」这条事实消失。若要更稳，只在 §3 代码块后加一行「（推荐显式 `:fabric:runClient`，见下）」。

## R2.4 仍然只能由真实构建证明的（R2 更新）

第一轮 §7 的 8 项**全部仍有效**，且新增一条**方法学提醒**：

> `tools/compile-check.sh` **无法**发现 R2.1 这类坐标错误 —— 它用 `D:/Gradle/caches/modules-2` 全量 jar 拼 classpath，
> Fabric API 的类永远在盘上，所以 `fabric` 那 9 个 import 照样编过。**Gradle 依赖坐标只能靠人审或真实构建**验证。
> 这也说明：`compile-check` 全绿 ≠ `gradlew build` 能过。

真实构建清单（与第一轮相同，仅把第 1 条换成「`:fabric:compileJava` 的 fabric-api 解析」）：

1. `:common:compileJava` 与 `:fabric:compileJava` 的 fabric-api 解析（**当前 fabric 必挂**）。
2. 两 loom 项目 + `implementation project(':common')` 的变体解析（证据仍表明无问题）。
3. `runs { client { } }` 对「已存在元素」是 configure 而非重复 create。
4. 最终 jar：392 class、5 份 SPI、`pingforit.mixins.json`、`fabric.mod.json`（version 已替换）、`LICENSE_pingforit`、无重复条目。
5. 产物路径 `fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`。
6. `./gradlew runClient` / `:fabric:runClient` 的 run dir = 仓库根 `run/`。
7. 连跑两次验证 configuration cache。
8. IDE 同步后应出现 **`Fabric Client (:fabric)` / `Fabric Server (:fabric)`**（本轮 P1-1 的验收口径）。

---

## R2.5 本轮结论

- **PASS**：P0-1（common 坐标）、P1-1（ideConfigGenerated，签名+语义+位置均核实）、P2-2（旧残留清理）、P2-3（链接大小写）、A/C/E 全部维持第一轮 PASS（重跑 `266 R100`、`compile-check` exit 0 一致）。
- **FAIL（必须再修一轮）**：**P0-2 `fabric/build.gradle:39` 少了 `fabric-api:` 段** —— 这是本轮**新引入**的构建必失败项，`:fabric:compileJava` 双重失败（坐标不可解析 + 9 个文件缺包）。
- **仍需处理**：P2-4（README:32 vs :44 自相矛盾，落在推荐命令上）、P3×3（AGENTS:64 与 :73 口径不一致、AGENTS:47 散文命名、features.md:382 残留裸 `src/client/...`）。
- 一句话：**再改一行（fabric:39）就回到「只剩真实构建待验」的状态；P2-4 顺手清掉即可交付。**

---

# 第三轮复核（2026-11-03 晚，diff 级复验）

> 范围：只验上一轮 FAIL/PARTIAL/未处理的项 + 回归；第一/二轮结论不重写。
> 方式：`grep`（含自证式三段式自检）、字节直读、重跑 `compile-check`、`git status`。**未运行 Gradle**（沙箱限制不变）。

## R3.0 总判定：**PASS —— 无 must-fix，可以 commit**

| 上轮问题 | 本轮状态 | 证据 |
| --- | --- | --- |
| **P0-2 `fabric/build.gradle:39` 少 `fabric-api:`** | **PASS（已修）** | `fabric/build.gradle:39: implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"` |
| 全仓依赖坐标自检 | **PASS** | `grep -rn 'net.fabricmc' --include=*.gradle .`（排除 `Mod_Project/`）只有 7 行：3 行插件 id + 4 行依赖，**4 行依赖全是三段式**（`common:15` loader、`common:16` fabric-api、`fabric:38` loader、`fabric:39` fabric-api） |
| P2-4 README 命令块行内注释 | **PASS（已修）** | `README.md:31-33` 命令块只剩两行命令（`build` / `:fabric:runClient`），注释移到 `README.md:45` 的 bullet；与 `README.md:44` 的警告不再冲突 |
| P3 AGENTS:64 与 §4 矛盾 | **PASS（已修）** | `AGENTS.md:64` 现为「只会**启动** `:fabric`（`:common:runClient` 本身被禁用 → SKIPPED，但它的依赖任务仍会先执行，见 §4）」，与 `AGENTS.md:73` 一致 |
| P3 AGENTS:47 散文命名 | **PASS（已修）** | `AGENTS.md:47`：`porting-plan / PORTING-PROGRESS / features / reviews / team.md / restructure/`（与实际文件一致） |
| P3 `features.md:382` 残留裸路径 | **PASS（已修）** | 严格检查（先剔除 `common/src/`、`fabric/src/` 前缀再找残留）：**0 处裸 `src/main/` / `src/client/`**；`grep -n 'src/client/\|src/main/' docs/features.md` 的 4 行命中（:4/:359/:379/:382）全部带模块前缀 |
| P2-6 根 `build/libs/` 旧 jar | **PASS（已删）** | `ls build/libs` → No such file or directory；`build/` 只剩 classes/generated/processIncludeJars/reports/resources/tmp |
| 新增 AGENTS §3 方法论段 | **PASS** | `AGENTS.md:68`：compile-check 全绿 ≠ `gradlew build` 能过（modules-2 全量 jar 让坐标错误逃过 javac）+ 三段式自检命令 —— 与本轮教训一致 |

### 编译闸门（我复跑，第三次）
```
[gate] common/server-safety OK (common/src/main/java: no net.minecraft.client. / com.mojang.blaze3d)
[common] sources=162  errors=0            [fabric-main] sources=4  errors=0     [fabric-client] sources=80  errors=0
[gate] common/server-safety re-check after compile: OK
[summary] common errors=0 classes=262 | fabric-main errors=0 classes=4 | fabric-client errors=0 classes=126
EXIT=0
```
三轮完全一致（262/4/126），gate 文本 2 次。

## R3.1 提交前的两条机械提醒（不是缺陷）

1. **`common/build.gradle` 与 `fabric/build.gradle` 仍是 `??`（未跟踪）**，所以 `git diff -- common/build.gradle fabric/build.gradle` **永远为空**——
   这两份的改动无法用 git diff 复核，只能直读（本轮已直读+grep 复核）。**commit 前必须 `git add`**。
2. 当前待提交集合（我已核对）：
   - staged：**266 `R100`**（无 A/D）；
   - modified（10）：`.github/workflows/build.yml`、`AGENTS.md`、`README.md`、`build.gradle`、`docs/PORTING-PROGRESS.md`、`docs/features.md`、`gradle.properties`、`settings.gradle`、`tools/compile-check.sh`、`tools/compile-check.ps1`；
   - untracked（10，需 `git add`）：`buildSrc/build.gradle`、`buildSrc/src/main/groovy/multiloader-common.gradle`、`buildSrc/src/main/groovy/multiloader-loader.gradle`、`common/build.gradle`、`fabric/build.gradle`、`forge/README.md`、`neoforge/README.md`、`docs/restructure/00-plan.md`、`docs/restructure/01-inventory-and-mapping.md`、`docs/reviews/55-restructure-review.md`。
   - **用 `git commit -a` 会漏掉这 10 个新文件**（Rename 已 staged，但新文件不会自动加）→ 用 `git add -A` 或逐个 add；commit 后 `git status` 应为干净。
3. 已被 gitignore、不会误提交：`tools/.compile-check/`、`build/`、`run/`、`Mod_Project/`、`.tmp-*/`。

## R3.2 剩余 must-fix：**无**

代码/构建/文档层面没有待修项。**剩下的全部是「只能由真实构建证明」的 8 项**（第一轮 §7 / 第二轮 R2.4，条目不变）：

1. `:common:compileJava` 与 `:fabric:compileJava` 的 fabric-api 解析（坐标已人审通过，待 Gradle 证实）。
2. 两个 loom 项目 + `implementation project(':common')` 的变体解析（证据仍表明无问题）。
3. `runs { client { … } }` 对「Loom 预先创建的 client 元素」是 configure 而非重复 create。
4. 合并 jar：392 class（262+4+126）、`pingforit.mixins.json`、`fabric.mod.json`（version 已替换）、`assets/pingforit/**`、`icon.png`、`LICENSE_pingforit`、5 份 `META-INF/services` 各一份、`unzip -l | awk '{print $4}' | sort | uniq -d` 为空。
5. 产物 `fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`。
6. `:fabric:runClient` 的 run dir = 仓库根 `run/`（根级 `runClient` 时 `:common:runClient` 为 SKIPPED）。
7. 连跑两次验证 configuration cache。
8. IDE 同步后出现 `Fabric Client (:fabric)` / `Fabric Server (:fabric)`。

## R3.3 三轮小结（给人看的）

- **R1**：结构/保真/闸门都干净，抓出 1 个 P0（common 坐标）+ 1 个 P1（IDE run 配置）+ 6 个 P2。
- **R2**：P0 修好但**同形字符串被改坏到 fabric**（新增 P0 回归）；P1-1 修好并核实签名/语义；若干 P2/P3 残留。
- **R3**：全部闭合。**4 条依赖坐标均三段式、0 处裸 `src/` 路径、README/AGENTS 自洽、旧产物清除、compile-check 三轮同结果**。
- 经验：**同一改动里出现「同形字符串」时，改完必须全仓 grep 自检**（`grep -rn 'fabric-api:' --include=*.gradle .`）；
  `compile-check` 永远抓不到坐标类错误（它不看 Gradle 解析）。这两条已进 AGENTS §3。

---

# 第四轮复核（2026-11-03 20:0x 实机问题 + 修复）

> 背景：用户 `gradlew :fabric:runClient` 实机发现**所有翻译 / 声音 / 图标丢失**（dev），而同一份构建出来的 jar 正常。
> 本轮任务：验证根因诊断 + 复核修复 + 用 20:06 产物做 jar 级终验。
> 方式：日志取证（`run/logs/latest.log`、`debug.log`）、构建输出目录直读、jar 独立解包复算、Gradle/Loader/Fabric API 机制取证。**未运行 Gradle**。

## R4.0 总判定：**PASS** —— 诊断成立、修复正确；**发布 jar 需重建后再验一次数值**；实机需用户重跑 `runClient` 验收。

| # | 复核项 | 结论 |
| --- | --- | --- |
| 1 | 根因诊断（dev 资源不进 mod root） | **PASS**，且我补到一条决定性证据（R4.1-4）与机制证据（R4.1-5） |
| 2 | `processResources { from :common sourceSets.main.resources }` 语义 | **PASS**（跨项目源目录、无隐式任务依赖缺失、CC 安全；前提见 R4.3） |
| 3 | `jar { from …classesDirs }` 后发布 jar 仍完整 | **PASS（推演）**；产物待重建复核 |
| 4 | 20:06 产物 jar 级终验 | **PASS —— 独立复算与 Lead 报的数字逐项一致**（但该 jar 早于修复，见 R4.5） |
| 5 | 剩余 must-fix | **代码 0 条**；文档 P3×2（R4.7） |

## R4.1 证据链（3 条原有 + 2 条我补的）

1. **dev 资源根缺 assets（复现）**
   ```
   fabric/build/resources/main      → 4 files: fabric.mod.json + 3 server SPI        (mtime 12:06:03Z = 20:06 本地)
   fabric/build/resources/client    → 3 files: mixins json + 2 client SPI
   common/build/resources/main      → 13 files: assets/pingforit/** + icon.png   ← 但没有 fabric.mod.json
   ```
2. **同一时刻的发布 jar 里 assets 齐全**：`fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`（771558 B，20:06:10）
   含 `assets/pingforit/` 下 16 条（5 个目录 + 11 个文件）+ 根 `icon.png` —— 「jar 正常 / dev 异常」这一对照成立。
3. **日志三条症状全中**：`run/logs/latest.log:186` `Missing sound for event: pingforit:ping`；`:305` 起 多行 `[CHAT] pingforit.chat.pingmsg`；
   HUD 的 `pingforit.ping_type.*` / `unit.pingforit.meters` 同类（合计 38 条命中）。**且 0 条 mixin 失败、0 条其它资源告警**。
4. **【我补的决定性证据】assets 物理上就在 dev classpath 里，只是不可见** —— `run/logs/debug.log` 的 Knot classpath 行：
   ```
   [20:06:21] (FabricLoader/Knot) Adding …\fabric\build\classes\java\main    to classpath.
   [20:06:21] (FabricLoader/Knot) Adding …\fabric\build\resources\main       to classpath.
   [20:06:21] (FabricLoader/Knot) Adding …\fabric\build\classes\java\client  to classpath.
   [20:06:21] (FabricLoader/Knot) Adding …\fabric\build\resources\client     to classpath.
   [20:06:21] (FabricLoader/Knot) Adding …\common\build\libs\Ping-For-It-0.4.2-pfi-beta1.jar to classpath.
   ```
   → dev 里 `:common` 是以**它的 jar**形式上 classpath 的（不是 `common/build/resources/main`），
   所以 assets 确实在 classpath 上（在 common 的 jar 里），却依然不可见。**这排除了「资源根本没上 classpath」这个替代解释**，
   把问题精确锁定在「mod 资源根」这一层。
5. **【我补的机制证据】资源包只按 mod 的 root paths 建索引** —— 扫 `fabric-resource-loader-v1-3.1.0` 的字节码常量池：
   `net/fabricmc/fabric/impl/resource/pack/ModNioPackResources.class` 引用 `getRootPaths` / `getRootPath` / `ModContainer` / `ModMetadata`
   （v0 的 `ModResourcePack.class` 只引用 `ModMetadata`）。而 dev 上唯一含 `fabric.mod.json` 的 classpath 条目是
   `fabric/build/resources/main` → 只有它算 mod root → common jar 里的 assets 不在任何 root 内 → 资源管理器看不到。

**诊断结论：成立。** 且能解释「为什么 mixins/SPI 没坏而只有 assets 坏」：
mixin 配置与 SPI 走 **classloader `getResource`（扫全 classpath）**，assets 走 **资源管理器（只认 mod root）** —— 与日志里 0 条 `Mixin apply failed` 完全一致。

## R4.2 机制表述建议精修（不影响结论，P3）

现在的注释/AGENTS §7.16 说的是「dev 只把**本模块源集**的资源注册成 mod 资源」，更准确的说法是：

> **只有「根目录含 `fabric.mod.json`」的 classpath 条目才是 mod root**；资源包（`ModNioPackResources`）只索引 `ModContainer.getRootPaths()`。
> dev 里 `:common` 以 jar 形式在 classpath 上（无 fabric.mod.json）→ 不是 root → 它的 assets 对资源管理器不可见。

「判断依据 = 看 `fabric/build/resources/main` 下有没有 `assets/`」这条**不变，仍然正确**（它就是 mod root）。

## R4.3 Q2 — `processResources { from project(':common').sourceSets.main.resources }` 的 Gradle 语义：OK

- **读的是源目录**：`SourceDirectorySet` 根在 `common/src/main/resources`，**没有生产者任务** → 因此**不存在「缺失的隐式任务依赖」**；
  它只是 `:fabric:processResources` 执行时的一次普通文件读取（与同项目 `from 'src/main/resources'` 同类）。`dependsOn generateModMetadata` 也仍在（`fabric/build.gradle:59`）。
- **跨项目模型访问**：`project(':common').sourceSets` 在配置期访问另一个项目的模型 —— 未启用 project isolation 时允许（Gradle 仅在 isolated projects 下禁止）；
  CC 序列化的是一个根在该目录的 file collection，不涉及配置期解析、也不产生跨项目任务依赖。**与项目现有 `jar { from project(':common')… }` 同类风险等级。**
- **前提/副作用**（都不构成缺陷，写下来供将来改）：
  1. 读到的是**源**文件，所以 common 若将来在 `processResources` 里加过滤/expand，这里不会跟随（当前 common 无任何过滤）。
  2. 与 fabric 自己的资源**无同名路径**（第一轮实测 0 交集：common 13 / fabric-main 3 / fabric-client 3）→ 复制后无重复条目。
  3. 若将来切 project isolation，需改成上游那种 configuration（上游 `multiloader-loader.gradle` 用 `commonResources` 暴露 `sourceSets.main.resources.sourceDirectories` —— **也是拿资源源目录**，与本修复同招）。
- 结论：**写法正确**，且与上游一致。

## R4.4 Q3 — `jar { from …classesDirs }` 之后发布 jar 是否仍完整（推演）

最终 jar 的组成（修复后）：
1. Java 插件默认 `jar.from(sourceSets.main.output)` = fabric main classes + **`fabric/build/resources/main`（现已含 common 的 assets/ 与 icon.png）**；
2. Loom 追加 **client 源集** classesDirs + resourcesDir（`MinecraftSourceSets.java:191-197`，第一轮已引）；
3. `from(project(':common').sourceSets.main.output.classesDirs)` = common 的 **class**（不含资源）；
4. `LICENSE` → 重命名 `LICENSE_pingforit`。

因此：**assets 只经 `processResources` → `sourceSets.main.output.resourcesDir` 进一次**；common class 只经 classesDirs 进一次 →
Gradle 9 的「重复条目即报错」策略下应当 0 重复。数量预期与 20:06 产物一致：**392 class（262+4+126）/ 464 entries / 5 SPI**。
⚠ 这只是推演：**没有 post-fix 产物可验**（见 R4.5），重建后必须复核这三个数。

## R4.5 Q4 — 20:06 产物 jar 级终验（我独立解包复算，与 Lead 报的数逐项一致）

```
jar: fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar
size 771558 B | entries 464 | mtime 2026-11-03T12:06:10Z (=20:06 本地)
class entries 392  = nx/pingwheel/common 384 + nx/pingwheel/fabric 8
duplicate entries: none
SPI: 5 份（3 server + 2 client，各一份）
assets/pingforit entries: 16（assets/、assets/pingforit/、sounds.json、lang/ ×8、sounds/、ping.ogg、textures/、arrow.png、ping.png）
fabric.mod.json: id=pingforit  version=0.4.2-pfi-beta1  name="Ping For It"  icon="icon.png"
                 mixins=[{config: pingforit.mixins.json, environment: client}]  entrypoints=[main, client, modmenu, voicechat]
icon.png 29966 B | LICENSE_pingforit 1067 B | pingforit.mixins.json 390 B
MANIFEST: 含 Fabric-Loom-Client-Only-Entries（Loom 正常写入）
```
**与 Lead 报的 392 / 464 / 0 / 5 / id+version / icon 29966 / LICENSE 1067 完全一致。**

⚠ **但要注意**：`fabric/build.gradle` 的 mtime 是 `12:10:11Z`（20:10 本地），**晚于**该 jar 的 20:06:10 →
**这份 jar 是修复前构建的**。所以它证明的是「修复前 jar 内容本来就是完整的」（正好是症状的对照组），
**不能**当作 post-fix 产物验收。post-fix 重建后需再跑一次同样的解包复算（预期数值不变）。

## R4.6 仍需用户验证的点

**A. 重跑 `gradlew :fabric:runClient`（修复验收，4 条）**
1. `fabric/build/resources/main` 下应出现 `assets/pingforit/**` 与 `icon.png`（这是最早的判据，不用进游戏就能看）。
2. 聊天/HUD 文本恢复为中文：日志里不再出现 `[CHAT] pingforit.chat.pingmsg` / `pingforit.ping_type.*` / `unit.pingforit.meters`。
3. `Missing sound for event: pingforit:ping` 消失（ping 能响）。
4. Mod Menu 里出现本模组图标（29966 B 的 icon.png）。

**B. 重跑 `gradlew clean build` 后复核 jar（3 条）**
5. 392 class / 464 entries / 5 SPI / 0 重复条目 / `assets/pingforit/**` 各一份 / 根 `icon.png` / `LICENSE_pingforit`。
6. `fabric.mod.json` 的 `version` 已替换为 `0.4.2-pfi-beta1`。
7. 产物仍在 `fabric/build/libs/Ping-For-It-<version>-fabric-26.3.jar`。

**C. 第一轮 §7 的其余项**（变体解析、`runs{client{}}` configure 语义、config cache、`:common:runClient` SKIPPED）不变。

## R4.7 剩余 must-fix

- **代码：0 条。** 修复方向、写法、与上游一致性都成立。
- **文档 P3×2（可在 commit 前顺手改，不阻塞）**：
  1. `AGENTS.md` §10 自检条目仍写「合并后的 jar 内容完整（`jar.from(:common output)` 没漏东西）」——`jar` 现在只合并 `classesDirs`，
     资源走 `processResources`。描述应改成「common class 经 `jar.from(classesDirs)`、common 资源经 `processResources` 各进一次」，
     否则后来者会照着找一个已不存在的写法。
  2. `AGENTS.md` §7.16 与 `fabric/build.gradle:62-63` 的机制句按 R4.2 精修（「含 `fabric.mod.json` 的 classpath 条目才是 mod root」；
     dev 里 common 是**以 jar 形式**在 classpath 上）。

---

## R4.8 四轮小结

- **R1** 抓 1 个 P0（common 坐标）+ P1/P2；**R2** 抓到「修 A 弄坏 B」的 P0 回归；**R3** 全闭合；**R4** 实机暴露 dev 资源根问题 →
  诊断成立、修复正确、jar 级终验数值一致。
- 一条可复用的判据：**「jar 里对不对」≠「dev 里对不对」** —— 前者看 jar 根，后者看 **mod root**（含 `fabric.mod.json` 的那一个 classpath 条目）；
  两者必须分别验。这条已进 AGENTS §7.16。

---

# 第五轮复核（2026-11-03 20:4x，improvedTransparency 相位回退）

> 对象：pipeline-auditor 报的 P1-1 修复（未提交）—— `SubmitNodeCollectionMixin` 新增 `outline` 相位路由 + `OutlineRoutingState` 一次性日志 + 文档登记 `24` #19 / AGENTS §7.17。
> 方式：读代码、**在 MC 26.3 的 client-only jar 上做 `javap -c -p` 独立复核**（不依赖 auditor/Lead 的结论）、`compile-check` 复跑。**未运行 Gradle / 游戏**。

## R5.0 总判定：**PASS（代码可交付）**，但有 **1 条 P2 文档过度承诺** 与 3 条 P3，实机颜色需比对。

| # | 复核项 | 结论 |
| --- | --- | --- |
| Q1 | `Minecraft.getInstance().options.improvedTransparency().get()` 在 mixin 里的安全性 | **PASS**（渲染线程、非空、每帧个位数次；语义提醒见 R5.1） |
| Q2 | `outline` 相位是否在 `currentFrameRendersEntityOutline` 之前被计入 | **PASS（字节码证实顺序）** |
| Q3 | 默认路径（improved=false）是否与改前完全等价 | **PASS**（仅新增一次 boolean 读） |
| Q4 | `@Shadow … outline` 字段是否真实存在 | **PASS（javap 原文）** |
| Q5 | 相位选择与残留风险 | **PASS（保留 outline 合理）**；残留风险 3 条，其中颜色合成需实机比对（R5.5） |
| Q6 | 文档 `24` #19 / AGENTS §7.17 | 编号无冲突、依据准确；**1 处结论过强（P2）**、2 处表述（P3） |
| — | `compile-check` | **PASS**：EXIT=0，`0/262 | 0/4 | 0/126`，gate 两次 |

## R5.1 Q1 — 在提交阶段读 `Options` 是安全的

- **线程**：`SubmitNodeCollection.submitShapeOutline` 只在 `LevelRenderer.render(...)` 的 `submitFeatures(...)` 段（profiler 段名可见：`submitFeatures` → `prepareFeatures` → `setupFrameGraph`）被调用，即客户端渲染线程 —— MC 的 "Render thread" 就是客户端主线程，与 `Options` 属主同线程；不涉及跨线程读写。
- **时机**：`Minecraft` 实例在渲染路径上恒存在；`options` 在 `Minecraft` 构造时创建，非空。`minecraft == null` 的兜底实际只在「渲染尚未开始/已关闭」的异常时机生效，且此时走 `afterTerrain`（安全侧、与旧行为一致），不会 NPE。
- **成本**：每帧只对**我们自己的**描边提交求值（`PingOutlineRenderType.isCustom` 前置过滤，vanilla 方块选中框走同一方法但立即 return），个位数次 `OptionInstance.get()`（字段读）。
- **语义提醒（不是缺陷）**：渲染器自身**不直接读 `Options`** —— 它用的是每帧快照 `OptionsRenderState.improvedTransparency`（我在 `LevelRenderer` 的字节码里找不到任何 `Options.improvedTransparency()` 引用；该布尔从 `optionsRenderState` 取）。快照在 extract 阶段从 `Options` 抓取，**早于**我们的提交，所以同一帧内两者必然一致；只有「用户在同一帧内切换选项」这种不可能场景才会分歧。
- **mixin 结构**：只有 1 个 `private static` 方法（`:110-113`），**无静态字段、无 `<clinit>`** → 不触发 Mixin 的静态初始化合并问题；`pingforit$` 前缀也避免了成员冲突。

## R5.2 Q2 — 顺序成立（我独立读了 `LevelRenderer.render` 的字节码）

```
public void render(GraphicsResourceAllocator, boolean, CameraRenderState, GpuBufferSlice, Vector4f, boolean, boolean)
  …
  78: invokevirtual  submitFeatures:(LevelRenderState, SubmitNodeCollector, Z)V     ← 我们的提交在这里发生
  86: popPush "prepareFeatures"
  99: invokevirtual  FeatureRenderDispatcher.prepareFrame:(SubmitNodeStorage)L…$PreparedFrame;
 107: invokevirtual  PreparedFrame.hasAnyOutline:()Z
 117: getfield       LevelRenderState.shouldShowEntityOutlines:Z
 128: putfield       currentFrameRendersEntityOutline:Z
 136: popPush "setupFrameGraph"
 141: new            FrameGraphBuilder                                                 ← 帧图之后才建
```
即 **submitFeatures → prepareFrame → hasAnyOutline() → 置标志 → 才 setupFrameGraph**，我们的提交必然被计入。
并且 `PreparedFrame.hasAnyOutline()` 的实现（同样字节码确认）是：
`groupsByPhase.getOrDefault(((SubmitNodeCollection) it).outline, List.of()).isEmpty()` 取反 —— **只检查 `outline` 相位的分组**，所以把它投进 `outline` 正好能让这个判断为真。**auditor 的推断成立。**

## R5.3 Q3 — 默认路径完全等价

```java
if (!afterTerrainFlag)            this.solid.submit(submit);          // 与改前一致
else if (pingforit$classicTransparencyActive()) this.afterTerrain.submit(submit);  // 与改前一致
else { this.outline.submit(submit); … }                                // 仅 improved=true 走
```
`improvedTransparency` 默认 false（`Options` 字节码：`OptionInstance.createBoolean(…, iconst_0, valueChanged)`），
且 `Minecraft.getInstance() == null` 时 `pingforit$classicTransparencyActive()` 也返回 true → 仍然是 `afterTerrain`。
→ 默认路径的 solid / afterTerrain 两条分支、颜色、线宽、取消语义（`ci.cancel()` 位置不变、`markRouted()` 仍每次都调）**与改前逐字等价**，唯一新增开销是每次自定义提交一次 boolean 读，且默认路径**不会**打任何新日志。

## R5.4 Q4 — `@Shadow` 字段真实存在（javap 原文）

```
$ javap -p -cp minecraft-client-only.jar net.minecraft.client.renderer.SubmitNodeCollection
  public final net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase solid;
  public final net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase waterMask;
  public final net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase outline;        ← 命中
  public final net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase alwaysOnTopGizmos;
  public final net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase seeThrough;
  public final net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase oitTranslucent;
  …
  public void submitShapeOutline(PoseStack, VoxelShape, RenderType, int, float, boolean);           ← 注入签名一致
```
字段名 `outline`、类型 `SimpleFeatureRenderPhase`、可见性 `public final` → `@Shadow @Final public` 完全匹配 ✓；
`submitShapeOutline` 的 6 个参数与 `pingforit$routeCustomOutline` 一一对应 ✓。

## R5.5 Q5 — 相位选择：保留 `outline` 合理，但**残留风险要写清楚**

**相位执行顺序**（`LevelRenderer.lambda$addMainPass$0` 偏移，逐条对上）：
```
174 executeSolid
177 iload_2 / 178 ifne 190          ← improvedTransparency（每帧快照）为真则跳过 classic
187 executeClassicTransparency      ← 内部偏移 131 调 executeTranslucentAfterTerrain
234/242 executeOit                 ← improvedTransparency 为真时
248 executeOutline                 ← 我们投的相位（OIT 之后）
253 hasAnySeeThrough / 264 executeSeeThrough
269 ifeq / 279 executeAlwaysOnTop  ← 最后
```

**`outline` 相位的真实代价（我今天新查到的）**：
1. `executeOutline` 把内容渲染进**独立 target** `entityOutlineTarget`（`ZERO_CLEAR_COLOR` 清空），
   随后 `blitEntityOutline()` 用 **`RenderPipelines.ENTITY_OUTLINE_BLIT`** 做 `blitAndBlendToTexture(mainColor, mainDepth)`。
   该 pipeline 的 builder 只有 `withColorTargetState(new ColorTargetState(BlendFunction.ENTITY_OUTLINE_BLIT)) + withPrimitiveTopology(...)`，
   **没有设 `depthTestFunction`/深度状态** → 不会被主深度裁掉（“穿过方块”的性质保留 ✓）。
   但**最终合成用的是原版 outline 的 blend**，所以「颜色/亮度与默认路径完全一致」**并不能由代码推断**，只能实机比对。
2. 门控条件比预想宽松：`LevelExtractor.shouldShowEntityOutlines(camera, playerState)` = `!camera.isPanoramicMode() && playerState.hasPlayer`
   （字节码：`Camera.isPanoramicMode()` → `PlayerRenderState.hasPlayer`）→ **游戏内恒真**（不需要有发光实体）；只有全景/无玩家（主菜单背景等）为假。
3. 副作用：我们的提交会让 `hasAnyOutline()` 恒真 → 只在 improvedTransparency 模式下每帧多一次 outline target + 一次全屏 blit（可接受，但应登记）。

**替代相位评估**：
- `seeThrough`：pass 只有 color attachment（`executeSeeThrough` 的 `RenderPassDescriptor.builder` 无 depth 附件），且在**主色 target**上、在 outline 之后绘制 —— 语义上**最贴近上游「主帧缓冲 + 无深度测试」**。但它的字段类型是 `TranslucentFeatureRenderPhase`（`submit(TranslucentSubmit)`），
  我们的 `ShapeOutlineFeatureRenderer.Submit` 是 `SubmitNode`，**不能一行替换**，要额外适配 → 不建议本轮做。
- `alwaysOnTopGizmos`：最后绘制、使用独立 `LevelTargetBundle.alwaysOnTopDepth` 深度；可行但引入 gizmo 语义 + 第二套深度，偏离更大。
- `shapeOutlines` / `waterMask` / `oitTranslucent`：前者没有对应的 `execute*` 调用点可依赖，后两者语义不符。
→ **结论：保留 `outline` 作为回退是正确的工程选择**（顺序正确、门控宽松、无需改数据结构）；把「颜色/线宽视觉比对」列为实机验收项即可。

**残留风险清单（写进文档更妥当）**：
R5.5-a 颜色/亮度经 `ENTITY_OUTLINE_BLIT` 合成，可能不等于我们的 pipeline 直出（唯一实质保真风险）。
R5.5-b `shouldShowEntityOutlines` 为假（全景/无玩家）时描边不出现 —— 非游戏内场景，可接受。
R5.5-c 与其它使用 `outline` 相位的 mod 共享同一 target（极端情况）。

## R5.6 Q6 — 文档登记

- **编号无冲突** ✓：`24` 里 #17/#18 之后新增 **#19**（`docs/reviews/24-fidelity-constraint-audit.md:125`），新增 §10（`:121`）不与已有 §1–§9 冲突。
- **依据准确** ✓：§10 的 4 条 javap 依据与我的独立复核逐条一致（唯一调用者、`iload_2/ifne` 分支、`hasAnyOutline && shouldShowEntityOutlines` 在偏移 107–128、默认值 `iconst_0`）。
- **P2（必须改）**：#19 行结论写「颜色/线宽/**混合**规格不变」——**混合这一项不成立**：几何与我们的 RenderPipeline 未变，但最终合成由原版 `BlendFunction.ENTITY_OUTLINE_BLIT` 完成。
  建议改为：「几何/管线规格不变；最终合成走原版 `ENTITY_OUTLINE_BLIT`，颜色需实机比对（见 #19 备注）」。
- **P3（建议改）**：
  1. `AGENTS.md` §7.17 末句「日志首行会提示走的是哪条」——实际只有**回退时**才打日志，默认路径不打。建议改成「仅回退时打一条」。
  2. `AGENTS.md` §7.17 可补一句 `shouldShowEntityOutlines = !panoramic && hasPlayer`（说明游戏内几乎恒真），避免后来者误以为要“有发光实体”。
  3. `24` §10 标题写「M7a 备注」，但本项来自 pipeline-auditor 的 P1-1（不在 M7a 范围）；建议改成「improved transparency 相位回退（2026-11-03，新增 #19）」。
- **P3 代码**：`OutlineRoutingState.usedImprovedTransparencyFallback()` **无调用者**（`hasRouted()` 有，被 `BlockOutlinePass:261` 用）。要么接进 `BlockOutlinePass` 的诊断，要么删掉。

## R5.7 实机验证步骤（建议按这个顺序）

1. **默认（不改选项）先跑一遍** `:fabric:runClient`：描边照旧；日志里**不应出现** `[PingForIt] improved transparency is ON`（验证"默认路径不受影响"+ 日志只在回退时打）。
2. 打开 improved transparency（`options.txt` 的 `improvedTransparency:true`，或视频设置里对应开关），重启/重进世界：
   - 描边应仍然可见（且墙后可见）；
   - 日志出现**一行** `[PingForIt] improved transparency is ON: … routing the outline to the outline phase instead`（且只出现一次）；
   - **同一场景截图与步骤 1 对比**：描边颜色/亮度/线宽是否一致（这是 R5.5-a 的唯一验收方式）。
3. 切到主菜单（全景背景）看不到描边属预期（R5.5-b）。
4. 该模式下 FPS 无明显回退（每帧多一次 outline target blit）。

## R5.8 剩余 must-fix

- **代码：0 条**（`classicTransparencyActive` 的 null 兜底、`@Shadow` 字段、注入签名、默认路径等价、无静态初始化 —— 全部核实通过；`compile-check` EXIT=0）。
- **文档 P2×1**：`24` #19「混合规格不变」→ 改成「几何/管线不变，合成 blend 由原版决定，颜色待实机比对」。
- **P3×4**：AGENTS §7.17 的日志表述、补 `shouldShowEntityOutlines` 事实、`24` §10 标题、`usedImprovedTransparencyFallback()` 死代码。

> 一句话：**这个修复可以进（默认路径零影响、回退路径有据可查、顺序与门控都经字节码证实）；唯一需要“眼见为实”的是 improvedTransparency 模式下的合成颜色。**




