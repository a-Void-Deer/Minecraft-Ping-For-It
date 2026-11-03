# 03 · M1 构建脚本复核：remapJar 与产物命名

审核人：3号　时间：2026-10-28 19:xx　对象：`build.gradle:6-8`（base.archivesName）与 `:83-88`（remapJar classifier）
方法：只读。解包 `D:/Gradle/caches/.../net.fabricmc/fabric-loom/1.19.3/*-sources.jar` 做源码级核对 + 项目现有 `build/` 产物反证。**未运行 Gradle。**

## 结论（先行）

1. **Lead 的诊断不成立**：`remapJar` 不是"Loom 延迟注册"，而是**在当前插件配置下根本不会被创建**。
2. 因此 `tasks.matching { it.name == "remapJar" }.configureEach { ... }` 是**静默 no-op**：不报错、也不生效，产物**不会**变成 `Ping-For-It-1.0.0-fabric-26.3.jar`，仍是 `Ping-For-It-1.0.0.jar`。
3. 当前路径下正确的最小修复是给 **`jar`** 任务设 classifier 或文件名（见 §四 方案 A/B）。

## 一、根因证据链（为什么 remapJar 不存在）

| # | 证据 | 位置 |
|---|---|---|
| 1 | **插件 id 决定分支**：`net.fabricmc.fabric-loom` 在 1.19.3 里是**no-remap 标记插件**，不是主干插件 | `fabric-loom-1.19.3.jar!/META-INF/gradle-plugins/net.fabricmc.fabric-loom.properties` → `implementation-class=net.fabricmc.loom.LoomNoRemapGradlePlugin` |
| 2 | 对照：`net.fabricmc.fabric-loom-remap` → `LoomRemapGradlePlugin`（内部再 apply `fabric-loom` 主干）；`fabric-loom` → `LoomGradlePlugin` | 同目录另两个 `.properties`；`LoomRemapGradlePlugin.java:34,38`；`LoomNoRemapGradlePlugin.java:34,42` |
| 3 | 本项目 build.gradle:2 用的正是 **no-remap** 那个 id | build.gradle:2 `id 'net.fabricmc.fabric-loom' version "${loom_version}"` |
| 4 | no-remap 插件把 `disableObfuscation` 强制为 true 并 finalize | `extension/LoomGradleExtensionImpl.java:123-129`（`hasPlugin(LoomNoRemapGradlePlugin.NAME)` → `disableObfuscation.set(true)`） |
| 5 | `dontRemap = disableObfuscation \|\| -Pfabric.loom.dontRemap` | `LoomGradleExtensionImpl.java:131` |
| 6 | **`dontRemap` 为真 ⇒ 直接 return，不创建 remapJar** | `task/RemapTaskConfiguration.java:70-73` |
| 7 | 反向证据：remap 分支才会 `create("remapJar", ...)` 并把 jar 改 classifier=`dev`、目录=`build/devlibs` | `RemapTaskConfiguration.java:97`、`:107-110`、`:112` |
| 8 | 项目实测产物符合 no-remap 分支：`build/libs/ping-for-it-1.0.0.jar` 直接存在，**无 `build/devlibs`** | `Get-ChildItem build/libs`；`Test-Path build/devlibs` = False |

> 补充：`RemapTaskConfiguration` 是在 `LoomGradlePlugin.apply()` 里被同步执行的（`LoomGradlePlugin.java:61-70,95-97` 的 SETUP_JOBS 循环），也就是说——**即使是 remap 分支，任务也是"立即创建"而非延迟注册**。所以"延迟注册导致 tasks.named 失败"这个解释在任何分支下都不成立。

## 二、问题 1：Loom 1.19.3 到底有没有 remapJar、类型是什么、有没有 archiveClassifier

- **有**，但只在 remapping 分支：任务名常量 `REMAP_JAR_TASK_NAME = "remapJar"`（`RemapTaskConfiguration.java:50`），sources 侧 `"remapSourcesJar"`（`:51`）。
- **类型**：`net.fabricmc.loom.task.RemapJarTask` ← `AbstractRemapJarTask` ← `Jar`（`task/AbstractRemapJarTask.java:82` `extends Jar`）。
- **`archiveClassifier` 有**：来自 Gradle 的 `AbstractArchiveTask`，`Jar` 继承它 → `remapJar.archiveClassifier = "..."` 语法合法。
- 但**本项目走的是 no-remap 分支**（§一），该任务不会被创建 → 任何针对它的配置都无对象。

## 三、问题 2：`tasks.matching{...}.configureEach{}` 的 Gradle 语义

- `TaskCollection.configureEach(Action)` 是**惰性**配置：动作只在该任务被 realize 时执行，且集合是"活视图"，**对之后才注册/创建的任务同样生效**。所以"任务创建得晚"这个场景它本来就能覆盖 —— 写法本身没问题。
- 本项目失效的根本原因是**任务永远不存在**（匹配集合恒为空），configureEach 自然是 no-op，且**不会报错**（这正是最危险的地方：看起来修好了，产物名没变）。
- 更稳/更可读的写法（仍治不了根）：`tasks.withType(net.fabricmc.loom.task.AbstractRemapJarTask).configureEach { archiveClassifier = "fabric-26.3" }`（按类型匹配，同时覆盖 remapJar 与 remapSourcesJar，且不依赖字符串名）。

## 四、问题 3/4：产物名实测推导与替代方案

### 当前配置的真实结果（no-remap 分支）
| 任务 | 产物 | 依据 |
|---|---|---|
| `jar` | `build/libs/Ping-For-It-1.0.0.jar` | `base.archivesName`(build.gradle:6-8) + `version=1.0.0`；no-remap 分支不改 classifier（`NonRemappedJarTaskConfiguration.java:63-82` 只加 manifest doLast 与 nestJars） |
| `sourcesJar` | `build/libs/Ping-For-It-1.0.0-sources.jar` | `withSourcesJar()`（build.gradle:68）；`remapSourcesJar` 同样因 :70 的 return 不创建 |
| `remapJar` | **不存在** | §一 |

**⇒ 目标名 `Ping-For-It-1.0.0-fabric-26.3.jar`（决策 D4）目前无法产出。**

### 替代方案对比

| 方案 | 写法 | 产物 | 优点 | 缺点 |
|---|---|---|---|---|
| **A（推荐，最小改动）** | `jar { archiveClassifier = "fabric-26.3" }` | `Ping-For-It-1.0.0-fabric-26.3.jar` | 1 行；当前插件路径立即生效；`publishing` 的 artifact 也带该 classifier | 若日后切到 `fabric-loom-remap`，Loom 会把 jar 推到 `build/devlibs` 并再产一个 remapJar，命名/目录会变 |
| **B（最不依赖他人）** | `jar { archiveFileName = "Ping-For-It-${version}-fabric-26.3.jar" }` | 同上 | 显式、不会被 `convention` 覆盖；classifier 相关逻辑完全不参与 | 同上未来风险；`archiveFileName` 是"最终名"，换版本要确保占位符逐字保留 |
| **C（最解耦）** | 新增 `tasks.register("dist", Copy)`：从 `jar` 复制并重命名，挂到 `assemble` | `build/dist/...` 或 `build/libs` 双份 | 与插件分支解耦，可同时给 sources 改名；将来切 remap 也不用改 | 多 5–8 行；`build/libs` 会同时存在原名与新名两份（需约定只发 dist 的） |
| D（不推荐） | `version = "1.0.0-fabric-26.3"` | 文件名对 | 一行 | **污染 `fabric.mod.json` 的 `version`**（模板里 `${version}` 会被替换），mod 版本变成带后缀；D4 只要求文件名 |

**建议**：先用 A 验证构建（改完 `build` 后确认 `build/libs` 里是 `Ping-For-It-1.0.0-fabric-26.3.jar`）；若担心后续切 remap 插件，改成 C。

## 五、附带发现（不阻塞）

1. `build.gradle:2` 的插件 id 值得写进施工文档：**`net.fabricmc.fabric-loom` = 不 remap（26.3 未混淆）；需要包装/重映射时才用 `net.fabricmc.fabric-loom-remap`**。后续任何人想"加 remap"都会再次踩这个坑。
2. 因此 Loom 1.19.3 下的 `dev` 分类 jar（`build/devlibs`）在本项目里**不存在**是正常的，不是构建异常。
3. `build.gradle:86` 的注释（"Loom 的 remapJar 是延迟注册的…"）与事实不符，建议改成"本项目使用 no-remap 插件，不存在 remapJar 任务"，避免下一个人继续按错误前提改。
4. `base { archivesName = "Ping-For-It" }` 本身没问题：archivesName 只影响文件名/artifactId，与 modid（`ping-for-it`）不冲突。

## 六、复现命令（只读）
```
tar -tf D:/Gradle/caches/modules-2/files-2.1/net.fabricmc/fabric-loom/1.19.3/*/fabric-loom-1.19.3.jar | findstr gradle-plugins
tar -xOf <jar> META-INF/gradle-plugins/net.fabricmc.fabric-loom.properties
# 源码分支：RemapTaskConfiguration.java:70-73,97,107-112；LoomGradleExtensionImpl.java:123-131；LoomGradlePlugin.java:61-70,95-97
dir build\libs
```
