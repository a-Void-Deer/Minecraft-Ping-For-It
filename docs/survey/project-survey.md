# 项目清点与静态分析（1号 · repo-analyst）

- 生成时间：2026-10-28 14:2x（Asia/Shanghai）
- 工作目录：`F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric`
- 方法：只读清点 + 静态分析；**未执行任何 gradle 命令**（构建执行归 4号）。
- 结论可信度：凡标注「已验证」的均有本机文件/日志证据；标注「待验证」的需要编译或反编译确认。

---

## 1. 环境事实（已验证）

| 项 | 值 | 证据 |
| --- | --- | --- |
| 默认 JDK | 24.0.2（`JAVA_HOME=C:\Program Files\Java\jdk-24`） | `java -version`；环境变量 |
| 可用 JDK 25 | 25.0.4.1 LTS（`C:\Program Files\Java\jdk-25`，环境变量 `JDK25_HOME`） | `jdk-25/bin/java -version` |
| Gradle 用户目录（有缓存） | `D:\Gradle`（caches 1409 MB，wrapper 163 MB，daemon 日志在此） | 目录实测 |
| Gradle 用户目录（默认） | `C:\Users\65653\.gradle`（**只有 wrapper/dists，无 caches**） | 目录实测 |
| 版本控制 | **无 .git**（不是 git 仓库） | `ls .git` 不存在 |
| 已有 Gradle 构建记录 | 13:55 失败（JDK 24）、13:56 成功（JDK 25，4m34s）、14:04 两次成功（4s/2s） | `D:\Gradle\daemon\9.8.2\daemon-*.out.log` |
| 编译产物 | **无**：`build/` 仅 `loom-cache/` 与 `reports/`，无 `classes/`、`libs/` | 目录实测 |

> 关键判断：3 次 `BUILD SUCCESSFUL` 都是 **IDE 同步/配置阶段**，不是编译。
> 证据：`build/reports/problems/problems-report.html` 中 `"requestedTasks":""`、`"buildName":"build ':'"`，且 `build/classes`、`build/libs` 均不存在。
> 即：**本仓库至今没有成功编译过一次。**

---

## 2. 文件清单（模板遗留 vs 项目自有）

### 2.1 构建与配置

| 路径 | 作用 | 归属 |
| --- | --- | --- |
| `settings.gradle` | 插件仓库（Fabric + Central + PluginPortal），`rootProject.name='modid'` | 模板遗留 |
| `build.gradle` | loom 插件、`splitEnvironmentSourceSets()`、`mods{"modid"}`、依赖、`release=25`、publishing | 模板遗留（未改） |
| `gradle.properties` | 版本参数、`org.gradle.jvmargs=-Xmx1G`、parallel、**configuration-cache=true** | 模板+人工调整（mtime 14:04，晚于首次构建） |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle **9.8.2**-bin | 模板遗留 |
| `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` | Wrapper 启动脚本（jar 48749 B） | 模板遗留 |
| `.gitignore` / `.gitattributes` | 忽略 `.gradle/ build/ run/ .idea/`；LF/CRLF 规则 | 模板遗留 |
| `LICENSE` | CC0-1.0（模板许可证，7 KB） | 模板遗留 |
| `.github/workflows/build.yml` | CI：ubuntu-24.04 + **Java 25** + `./gradlew build` + 上传 `build/libs/` | 模板遗留 |
| `.idea/misc.xml`、`.idea/runConfigurations/Minecraft_{Client,Server}.xml` | IDEA 工程与 `runClient`/`runServer` 运行配置 | 模板/IDE 生成 |
| `.idea/workspace.xml` | IDE 工作区状态 | IDE 生成 |

### 2.2 源码与资源

| 路径 | 作用 | 归属 |
| --- | --- | --- |
| `src/main/java/com/example/ExampleMod.java` | 主入口：`MOD_ID="modid"`、LOGGER、`id(String)` 工具方法 | 模板遗留 |
| `src/main/java/com/example/mixin/ExampleMixin.java` | 注入 `MinecraftServer#loadLevel` HEAD | 模板遗留 |
| `src/client/java/com/example/client/ExampleModClient.java` | 客户端入口（空实现） | 模板遗留 |
| `src/client/java/com/example/client/mixin/ExampleClientMixin.java` | 注入 `Minecraft#run` HEAD | 模板遗留 |
| `src/main/resources/fabric.mod.json` | id/version/entrypoints/mixins/depends | 模板遗留 |
| `src/main/resources/modid.mixins.json` | 通用 mixin 配置（package `com.example.mixin`） | 模板遗留 |
| `src/client/resources/modid.client.mixins.json` | 客户端 mixin 配置（`"client"[` 段，package `com.example.client.mixin`） | 模板遗留 |
| `src/main/resources/assets/modid/icon.png` | 模组图标（453 B） | 模板遗留 |

**项目自有文件：目前为 0。** 所有文件都是 Fabric 官方模板原样，尚无任何业务代码、资源或本地化。

### 2.3 目录（只列不深入）

- `build/`：`loom-cache/`（空）、`reports/problems/problems-report.html`（128 KB，仅 1 条 deprecation）
- `run/`：14:34 首次 `runClient` 后已生成 `config/ data/ downloads/ logs/ mods/ options.txt resourcepacks/ resources/ saves/`（**更新：游戏已启动过，见 §10**）
- `.gradle/`：`9.8.2/` 执行历史 + `loom-cache/`（`launch.cfg`、`minecraftMaven/`、`natives/26.3`、`source_mappings/`）
- `docs/`：`reviews/00-baseline-review.md`（3号）、`build/_probe_fs.txt`（4号）— 非本人写入
- `tools/`：空目录
- `build/loom-cache`（注意：与 `.gradle/loom-cache` 是两个不同目录，前者为空）

---

## 3. 命名一致性（三处不一致，属最高优先级改造项）

| 位置 | 当前值 | 证据 |
| --- | --- | --- |
| 模组 id / 工程名 | `modid` | `settings.gradle:13`、`build.gradle:18`、`fabric.mod.json:3`、`ExampleMod.java:11` |
| Java 包名 | `com.example` | `src/main/java/com/example/**`、`src/client/java/com/example/client/**`、两个 mixins.json 的 `package` |
| Gradle group | `com.Isharmla` | `gradle.properties:14` |
| 显示名 | `Example Mod` | `fabric.mod.json:5` |
| 作者 / 链接 | `Me!` / `fabricmc.net` / `FabricMC/fabric-example-mod` | `fabric.mod.json:7-13` |
| 描述 | `This is an example description! ...` | `fabric.mod.json:6` |

结论：**id（modid）自洽，但 group（com.Isharmla）与包名（com.example）互相冲突，且显示名/作者/链接仍是模板占位符。**
重命名 `modid` → 正式 id 时，必须同步修改的位置共 7 处：`settings.gradle:13`、`build.gradle:18`、`fabric.mod.json:3,15`、两个 mixins.json 的**文件名**、`ExampleMod.java:11`、资源目录 `assets/modid/`。

---

## 4. entrypoints / mixins 一致性核验（已验证，全部正确）

| 声明位置 | 声明值 | 对应实体 | 结果 |
| --- | --- | --- | --- |
| `fabric.mod.json:19` | `com.example.ExampleMod` | `src/main/java/com/example/ExampleMod.java:1,10` | 一致 |
| `fabric.mod.json:22` | `com.example.client.ExampleModClient` | `src/client/java/com/example/client/ExampleModClient.java:1,5` | 一致 |
| `fabric.mod.json:26` | `modid.mixins.json` | `src/main/resources/modid.mixins.json` 存在 | 一致 |
| `fabric.mod.json:27-30` | `modid.client.mixins.json` + `"environment":"client"` | `src/client/resources/modid.client.mixins.json` 存在 | 一致 |
| `modid.mixins.json:3,5-7` | package `com.example.mixin`，数组 `"mixins":["ExampleMixin"]` | `ExampleMixin.java` 包名/类名 | 一致 |
| `modid.client.mixins.json:3,5-7` | package `com.example.client.mixin`，**`"client"` 段** | `ExampleClientMixin.java` 包名/类名 | 一致（split 环境下写法正确） |
| `build.gradle:17-22` | `mods{"modid"}` 含 main+client 两个 sourceSet | `fabric.mod.json:3` id | 一致 |
| `fabric.mod.json:15` | `assets/modid/icon.png` | 文件存在（453 B） | 一致 |

补充（已验证）：
- `.gradle/loom-cache/launch.cfg` 的 `fabric.classPathGroups` 同时包含 `build/classes/java/main` 与 `build/classes/java/client`，说明 `splitEnvironmentSourceSets()` 已生效。
- 客户端 mixin 配置放在 `src/client/resources` 而不是 main，**这是正确的**：若误放到 `src/main/resources`，专用服务器加载时会出现 client 类缺失崩溃。

---

## 5. 风险清单

### R1【P0 · 阻断】Gradle 必须用 JDK 25 启动，而本机默认 JAVA_HOME 是 JDK 24

- 证据：`D:\Gradle\daemon\9.8.2\daemon-8724.out.log:105,204,207,210`
  ```
  105: start() called on daemon - javaHome=C:\Program Files\Java\jdk-24 javaVersion=24
  204: > Could not resolve net.fabricmc:fabric-loom:1.19.3.
  207:    > Dependency requires at least JVM runtime version 25. This build uses a Java 24 JVM.
  210: > Run this build using a Java 25 or newer JVM.
  ```
- 影响：直接 `./gradlew build` 会在**插件解析阶段**（比编译更早）失败，整个构建不可用。
- 关键细节：这**不是** toolchain 能解决的问题——失败发生在 buildscript classpath 解析，用的是 Gradle 自身 JVM。只能换 Gradle 的 JVM（`JAVA_HOME` 或 `org.gradle.java.home`）。
- 验证方式：`JAVA_HOME=/c/Program Files/Java/jdk-24 ./gradlew -g D:/Gradle help` 应复现上述报错；换成 jdk-25 后应成功（`daemon-33824.out.log:121,221` 为反证）。

### R2【已闭环】`release 25` 编译已验证（2026-10-28 14:28，见 §9.1）

- 证据：`build.gradle:43-45`（`it.options.release = 25`）、`build.gradle:47-55`（`sourceCompatibility/targetCompatibility = VERSION_25`）；`build/` 下无 `classes/`、`libs/`
- 影响：`options.release` 与 `sourceCompatibility` 同时设置属冗余配置，通常只产生提示；但 mixin 注解处理 + `release 25` 的组合、以及 Loom 的 remap 阶段是否正常，全部未知。
- 验证方式：4号 用 JDK 25 执行一次 `./gradlew -g D:/Gradle build`，检查 `build/classes/java/{main,client}`、`build/libs/modid-1.0.0.jar` 是否生成、有无 "warning: [options] source value 25 ..." 类提示。

### R3【已闭环（客户端）/ 服务端未验证】编译与客户端启动均已通过，见 §9.1 与 §10

- 证据：`run/` 仅含空 `resources/`；`build/` 无 classes/libs，`.class` 计数 0
- 影响：编译链路仍未验证；但 mixin 的两个目标方法**已确认存在**（见下），启动期 `MixinApplyError` 的风险大幅降低。
- 已验证（2026-10-28 14:2x，用 4号 `genSources` 产出的 26.3 源码 jar 反查，未跑 gradle）：
  - `MinecraftServer.loadLevel()` = `protected void loadLevel()`，`net/minecraft/server/MinecraftServer.java:395`
  - `Minecraft.run()` = `public void run()`，`net/minecraft/client/Minecraft.java:895`（同文件 560 行的 `run()` 属于窗口关闭回调里的**匿名 Runnable**，不在 `Minecraft` 类内，不会与 mixin 目标冲突）
- 剩余验证方式：`./gradlew -g D:/Gradle runClient`，观察是否出现 `Mixin apply failed` / `InvalidInjectionException`。

### R4【P2】`loom_version=1.19-SNAPSHOT` 是浮动快照，构建不可复现

- 证据：`gradle.properties:10`；失败日志中插件坐标为 `net.fabricmc.fabric-loom.gradle.plugin:1.19-SNAPSHOT:20261022.150838-3`，实际解析为 **loom 1.19.3**（`daemon-33824.out.log:211` "Fabric Loom: 1.19.3"）
- 影响：快照更新后行为可能变化；CI 与本地可能拉到不同快照。
- 建议：改为固定 `loom_version=1.19.3`（由 2号 改 gradle.properties，4号 立即构建验证）。
- 验证方式：`./gradlew -g D:/Gradle dependencies --configuration buildscript` 或查看 `--info` 中的 "Resolved plugin version"。

### R5【P2】configuration-cache 已开启，但只验证了配置阶段

- 证据：`gradle.properties:4` `org.gradle.configuration-cache=true`；`build/reports/problems/problems-report.html` 中仅 1 条问题（Gradle 9.8.2 的 `org.gradle.tooling.parallel` 默认值 deprecation，见 `gradle.properties:3` `parallel=true`），**无 CC 相关报错**
- 影响：`runClient`/`remapJar` 在 CC 下的兼容性未验证；Loom 的 run 任务历史上对 CC 支持是逐步补齐的。若出现 CC 不兼容，需对具体任务加 `--no-configuration-cache`。
- 验证方式：`./gradlew -g D:/Gradle build --configuration-cache`，随后再跑一次确认命中缓存（输出 "Reusing configuration cache"）。

### R6【P2】Gradle 堆内存仅 1 GB

- 证据：`gradle.properties:2` `org.gradle.jvmargs=-Xmx1G`
- 影响：首次 remap（MC 26.3 约 25 MB 合并 jar + 全部 Fabric API 模块重映射）在 1 GB 堆下容易 OOM，且 `daemon-33824` 的 4m34s 配置阶段已经接近边界。
- 建议：调至 `-Xmx3G`（属 2号 范围，改前报备 4号 避免构建冲突）。
- 验证方式：`./gradlew -g D:/Gradle build --info`，观察是否 `OutOfMemoryError: Java heap space`。

### R7【P1】命名三处不一致（见第 3 节）

- 证据：`gradle.properties:14`（com.Isharmla）vs 源码包 `com.example` vs `modid` / `Example Mod` / `Me!`
- 影响：越晚改成本越高（包名重命名会牵动 4 个 .java、2 个 mixins.json 的 package 字段、fabric.mod.json 的 entrypoints）。发布坐标（maven group）与包名长期不一致也会让 mixin/反射排查变难。
- 验证方式：改造后用 `grep -rn "com\.example" src` 与 `grep -rn "modid" --include=*.json --include=*.gradle .` 双向核对，应为 0 命中。

### R8【P3】（已排除）mixin `compatibilityLevel: JAVA_25` 高于 Mixin 的 MAX_SUPPORTED

- 证据：`modid.mixins.json:4`、`modid.client.mixins.json:4` 声明 `JAVA_25`；sponge-mixin 0.17.5 源码中 `CompatibilityLevel.JAVA_25` 存在且 `isSupported()` 要求 JRE≥25 + ASM≥9.8；`MAX_SUPPORTED = JAVA_13`，但 `MixinConfig.java:600-603` 对"高于上限"只做 **WARN/DEBUG 日志**，不中断。
- 影响：无功能影响，**不要**为了消警告把兼容级别降到 13（会导致新字节码特性处理错误）。
- 验证方式：启动日志中只应看到 "Compatibility level JAVA_25 ... is higher than the maximum level supported" 的提示。

### R9【P2】没有版本控制兜底

- 证据：无 `.git` 目录；`.gitignore` 存在但未生效
- 影响：任何误删/误改无法回滚；多人并行写同一目录风险高。
- 建议：由 Lead 决策 `git init` + 首次提交（属不可逆/全局操作，1号不擅自执行）。

### R10【P2】CLI 构建会丢掉 1.4 GB 依赖缓存

- 证据：`C:\Users\65653\.gradle` 只有 `wrapper/dists`（36 MB），**无 caches**；`D:\Gradle` 有 `caches` 1409 MB
- 影响：在未设置 `GRADLE_USER_HOME` 的终端里执行 `./gradlew` 会重新下载 Loom/MC/全部 Fabric API，耗时且可能受网络影响。
- 验证方式：命令统一加 `-g D:/Gradle`（或 `export GRADLE_USER_HOME=D:/Gradle`）。

### R11【P3】CI 与本地 JDK 不一致的风险

- 证据：`.github/workflows/build.yml:20` 用 `java-version: '25'`，CI 是 ubuntu；本地默认 24
- 影响：本地"能跑"不等于 CI 能跑（反之亦然）；目前 CI 只跑 `./gradlew build`（`:25`），无测试。
- 验证方式：首次推送后看 Actions 结果；本地一律用 JDK 25 复现。

### R12【P3】无自动化测试

- 证据：无 `src/test`；`build.gradle` 无 test 配置；CI 只执行 `build`
- 影响：`build` 通过 ≠ 运行正确。烟雾测试只能靠 `runClient`。
- 建议：把「`runClient` 启动到主菜单且日志无 ERROR」作为合并前最低门槛（写入 AGENTS.md 自检清单）。

---

## 6. 待验证项

已闭环（用 4号 `genSources` 产出的 26.3 源码 jar 反查，2026-10-28 14:2x）：

1. ✅ `MinecraftServer#loadLevel` 存在且为 `()V` —— `MinecraftServer.java:395`（`protected void loadLevel()`）。
2. ✅ `Minecraft#run` 存在且为 `()V` —— `Minecraft.java:895`（`public void run()`）。
3. ✅ `Identifier.fromNamespaceAndPath(String,String)` 存在 —— `net/minecraft/resources/Identifier.java:40`（类声明在同文件 :18）。

仍待验证（必须真跑构建/启动，不能静态判定）：

4. `release 25` + mixin 注解处理的首次编译是否干净（`build/classes`、`build/libs` 是否产出）。
5. `runClient` 在 configuration-cache 开启下是否正常。
6. mixin 注入在运行期是否真的命中（静态只能确认目标方法存在，确认不了注入语义）。

---

## 7. 建议的推进顺序（供 Lead 决策）

1. 固定构建前置：JDK 25 + `GRADLE_USER_HOME=D:/Gradle`；由 4号 跑一次真实 `build`，把「编译从未验证」这一空白补上。
2. 一次性完成重命名（modid → 正式 id；com.example → com.Isharmla），改完立刻构建验证；之后再让 2号 写功能代码。
3. 建立 VCS 兜底（git init + 首提交），再进入并行开发。

---

## 8. 14:2x 增量（来自 5号 日志线索 + 1号 复核）

### 8.1 genSources 事件：不是编译，P0#2 结论不变

- 证据：`D:\Gradle\daemon\9.8.2\daemon-33824.out.log:1433`（`tasks: genSources`）、`:1438`（`BUILD SUCCESSFUL in 1m 24s`）、`:1439`（`2 actionable tasks: 2 executed`）。
- 复核：`build/` 下 `.class` 计数 **0**、`build/libs` **不存在**。
- 结论：`genSources` 只做反编译与源码 jar 生成，**不产生编译产物**；截至 14:21「从未成功编译」仍成立（**但 14:28 已被真实 `build` 推翻，见 §9.1**）。

### 8.2 26.3 源码 jar 已可用（静态反查 API 不必等 runClient）

- 路径：
  - `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-1b0498e74c/26.3/minecraft-common-1b0498e74c-26.3-sources.jar`（5.9 MB）
  - `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-1b0498e74c/26.3/minecraft-clientOnly-1b0498e74c-26.3-sources.jar`（2.5 MB）
- 用法：直接解压/读 zip 条目即可读 named 源码（如 `net/minecraft/server/MinecraftServer.java`），不必再跑 gradle；需要更新时由 4号 执行 `./gradlew -g D:/Gradle genSources`。
- 已用它闭环第 6 节前 3 条待验证项（方法存在且均为 `()V`）。
- **防误判**：日志里指向 `Minecraft.java:560` 的 `run()` 是窗口关闭回调里的匿名 `Runnable`，**不是** mixin 目标（目标在 :895）。5号 已在 `docs/logs/LOG-TIMELINE.md` 固化这条警告。
- **首启分诊顺序**（若报 `Mixin apply failed`）：① 配置 `package`/数组一致性 → ② client mixin 是否误放 `src/main/resources` → ③ refmap/remap 是否因绕过 Gradle 直跑而丢失 → ④ 最后才怀疑方法签名变化。
- **防误判**：日志里指向 `Minecraft.java:560` 的 `run()` 是窗口关闭回调里的匿名 `Runnable`，**不是** mixin 目标（目标在 :895）。5号 已在 `docs/logs/LOG-TIMELINE.md` 固化这条警告。
- **首启分诊顺序**（若报 `Mixin apply failed`）：① 配置 `package`/数组一致性 → ② client mixin 是否误放 `src/main/resources` → ③ refmap/remap 是否因绕过 Gradle 直跑而丢失 → ④ 最后才怀疑方法签名变化。

### 8.3 新归档 ERROR：wrapper 取锁失败（Access denied）

- 现象：`docs/build/build-attempt-1.log:1`（GBK 编码，644 B，14:22:07）
  `java.io.FileNotFoundException: D:\Gradle\wrapper\dists\gradle-9.8.2-bin\8wepis63pzuhrlfaiy04bagwu\gradle-9.8.2-bin.zip.lck (拒绝访问。)`，栈在 `org.gradle.wrapper.Install.createDist`。
- 复核到的环境事实：
  - `.zip.lck` 长度 0 B、属性仅 `Archive`（非只读）、ACL 正常（`Authenticated Users` 有 `Modify`）；`.lck` 的 `LastWriteTime` 为 13:55:17。
  - dist 目录完整：`gradle-9.8.2/`（含 `bin/gradle.bat`、`lib/`）+ `gradle-9.8.2-bin.zip.ok` 标记。
  - 复检时无 `java/javaw/gradle` 进程。
- 判定：**不是代码/项目问题**，属 wrapper 在 `createDist` 阶段打开锁文件被系统拒绝，早于任何 Gradle 构建逻辑；dist 未受损。
- 原因**待验证**（候选：① 另一个 wrapper/gradle 进程并发持有同一 `.lck`；② 安全软件对 `.lck` 的独占打开拦截；③ 沙箱/ACL 对 `D:\Gradle` 写入的一次性限制）。归 4号 + Lead 定位。
- 绕法（已验证 dist 完整，可直接用解压后的发行版）：
  1. 直接调用 `D:\Gradle\wrapper\dists\gradle-9.8.2-bin\8wepis63pzuhrlfaiy04bagwu\gradle-9.8.2\bin\gradle.bat -g D:\Gradle <task>`；
  2. 或在同一会话内重试 `gradlew.bat`（若为瞬时占用）；
  3. 不要删除 `dists` 目录重下（dist 完好，且重下成本 160 MB+）。

---

## 9. 14:28–14:31 编译成功与随之而来的三个新事实

### 9.1 P0#2 已过期：首次真正编译成功（本文件 §5 的 P0 结论作废）

| 证据 | 值 |
| --- | --- |
| `build/libs/modid-1.0.0.jar` | 8311 B，14:28:14 |
| `build/libs/Isharmla-1.0.0.jar` | 8391 B，14:30:32（改名后重建） |
| `build/classes/java/main` | 2 个 `.class`：`ExampleMod`、`ExampleMixin` |
| `build/classes/java/client` | 2 个 `.class`：`ExampleModClient`、`ExampleClientMixin` |
| jar 内 class 版本 | **major=69 / minor=0** → Java 25 字节码，`release 25` 生效 |
| jar 内 `fabric.mod.json` | `version` 已由 `processResources.expand` 展开为 `1.0.0` |
| jar 内条目 | 4 class + `fabric.mod.json` + 两个 mixins.json + `assets/modid/icon.png` + `LICENSE_*` |
| 真实构建日志 | `daemon-33824.out.log:2084`（`tasks: build`）、`:2087`（`BUILD SUCCESSFUL in 7s`）、`:2088`（`7 actionable tasks: 7 executed`） |

→ **编译链路、split 源集、mixin 注解处理、remap、jar 打包全部验证通过。** 剩余未验证的只有运行期。

**对 5号 引用的一处修正**：`:2276` 的 `BUILD SUCCESSFUL in 2s` 实为随后 `4 up-to-date` 的空跑（`:2277`），**不是**编译证据；决定性的是 `:2084/:2087/:2088`。

### 9.2 改名后仍是「半改」状态（P1，需 Lead + 用户定名）

- `settings.gradle:13` `rootProject.name = 'Isharmla'`（14:30:06 变更，非团队成员操作，应为 IDE/外部终端）。
- 其余全部未改：`build.gradle:18` `mods { "modid" }`、`fabric.mod.json` 的 `id`、Java 包 `com.example`、mixin 配置文件名、`assets/modid/`。
- 实测：`Isharmla-1.0.0.jar` 内 `fabric.mod.json` 的 `id` **仍是 `modid`**，`LICENSE_Isharmla` 随 projectName 变了。
- 影响：产物名与模组 id 不一致，分发/整合包会看到 `Isharmla-1.0.0.jar` 却加载 `modid`；不致命，但必须在定名前统一。
- 14:30 之后的两次 `build` 尝试都因 9.3 的 `#` 问题失败 → **改名后尚无一次成功的完整构建记录**（`Isharmla-1.0.0.jar` 出自 14:30:32 那次 2 executed / 5 up-to-date 的运行）。

### 9.3 文档行内注释造成 4 次 cmd 构建失败（已修）

- 现象：`Task '#' not found in root project 'modid'` + `FAILED in 933ms`。
- 4 处证据：`:1727,1736,1746`、`:2136,2145,2155`（针对 `modid`）、`:2808,2817,2827`、`:2983,2992,3002`（针对 `Isharmla`）。
- 成因：把 `./gradlew build # 注释` 整行粘进 **Windows cmd**，`#` 不是注释符，与中文一起被当成任务名。
- **责任归属（1号 自认）**：前两处来自 AGENTS.md §3 的行内注释，后两处来自 README 的行内注释（`:2808` 的任务串里还带着「产物：build/libs/modid-1.0.0.jar」字样）。
- 修复：AGENTS.md §3 与 README 均改为「纯命令块 + 独立说明表/列表」；AGENTS.md §6-12 记录该坑与证据。

### 9.4 待清理

- `src/__probe.txt`：探测沙箱留下的临时文件，位于 `src/` 内，建议下次构建前删除（属 2号 范围，1号 不动）。

---

## 10. 14:34 客户端首启成功（runClient 已验证）

### 10.1 证据（1号 逐行复核，与 Lead 口径一致）

| 证据 | 值 |
| --- | --- |
| Gradle 任务 | `daemon-33824.out.log:3154` `tasks: :runClient`；`:3250` `BUILD SUCCESSFUL in 27s`；`:3251` `1 executed, 7 up-to-date` |
| 加载模组数 | `run/logs/latest.log:3` `Loading 52 mods:` |
| 本模组 | `:55` `- modid 1.0.0` |
| 入口执行 | `:62` `(modid) Hello Fabric world!` — `ExampleMod.onInitialize()` 确实被调用 |
| Mixin 子系统 | `:56` Mixin `0.8.7`（fabric 版）、`:57` MixinExtras 0.5.5，**全程无 `Mixin apply failed`** |
| 渲染/主菜单 | `:3240-3246`（daemon 侧）依次创建 banner/blocks/items/gui/map/chest/shulker 图集 → 已到主菜单渲染阶段 |
| 正常退出 | `latest.log:126` `Stopping!`（14:34:29），非崩溃退出 |

### 10.2 三类非问题（不要误判为故障）

1. **3 条 ERROR 全是联网/Realms**：`:73` `Failed to fetch user properties`（`:74` `InvalidCredentialsException: Status: 401`）、`:94` `Failed to fetch Realms feature flags`、`:117` `Couldn't connect to realms`。开发环境无正版会话 + 离线，属预期。
2. **1 条 WARN 是 Windows Perfmon**：`:59` `PerfmonDisabled: Invalid registry value type ... PerfOS`（系统注册表问题，与模组无关）。**注**：Lead 记为「:94 附近」，实际行号是 `:59`。
3. **`Mappings not present!`**（`daemon-33824.out.log:3157`）：开发环境下 Fabric Loader 的正常 INFO，loom 通过 classpath 提供 named 映射，不是错误。

### 10.3 验证边界（哪些仍然没验）

- ❌ 专用服务器：`runServer` 从未启动 → `MinecraftServer.loadLevel()` 那条注入仍属未运行验证。
- ❌ 自有功能：项目仍是模板，没有可被测的业务逻辑。
- ❌ 自动化测试：无 `src/test`，CI 只跑 `build`。
- ❌ 发布形态：只在 dev 环境跑过，`remapJar` 产物未在真实客户端 + 正式 Fabric Loader 下装载验证。

---

## 11. IDEA 资源过滤警告（P3，IDE 侧假警报）

- 现象：IDEA 提示 `Cannot resolve resource filtering of MatchingCopyAction. IDEA may fail to build project. Consider using delegated build`。
- 成因（已验证）：IDEA 的 Gradle 模型无法解析 `build.gradle:34-41` 的
  `processResources { filesMatching("fabric.mod.json") { expand "version": version } }`；这是 IDE 侧的模型能力问题，**不是构建脚本错误**。
- 反证（已验证，1号 独立复核）：`build/libs/Isharmla-1.0.0.jar` 内 `fabric.mod.json` 的 `version` = `1.0.0`，且**不含** `${version}` 占位符 → 该过滤在真实 Gradle 构建中确实执行了。
- 真正的风险：若关闭 delegated build、改由 IDEA 自行编译，**Loom 的 remap/refmap 不会执行**，运行期 mixin 可能失败。→ 必须保持 Settings > Build and run using = **Gradle**。
- 附带建议：Settings > Build Tools > Gradle > **Gradle JVM = jdk-25**（否则同步/构建仍报 JVM 版本不足）。注：仓库内 `.idea/workspace.xml` 未记录 gradle JVM 配置，该项属**待人工确认**项，1号 无法从仓库侧核实。

---

## 12. 14:4x 全量重命名核验（modid → isharmla）

### 12.1 已统一的项（1号 逐项复核，全部正确）

| 项 | 值 | 证据 |
| --- | --- | --- |
| 模组 id | `isharmla` | `src/main/resources/fabric.mod.json:3` |
| 主入口 | `com.isharmla.ExampleMod` | `fabric.mod.json:19` ↔ `src/main/java/com/isharmla/ExampleMod.java:1` |
| 客户端入口 | `com.isharmla.client.ExampleModClient` | `fabric.mod.json:22` ↔ `src/client/java/com/isharmla/client/ExampleModClient.java` |
| MOD_ID 常量 | `isharmla` | `ExampleMod.java:11` |
| 通用 mixin 配置 | `isharmla.mixins.json`，package `com.isharmla.mixin` | `fabric.mod.json:26`、`isharmla.mixins.json:3` |
| 客户端 mixin 配置 | `isharmla.client.mixins.json`，package `com.isharmla.client.mixin` | `fabric.mod.json:27-30`、`isharmla.client.mixins.json:3` |
| loom mods 块 | `"isharmla"` | `build.gradle:18` |
| rootProject.name | `isharmla` | `settings.gradle:13` |
| maven group | `com.isharmla` | `gradle.properties:14` |

→ entrypoints、mixin package、配置文件名的**三角一致性**成立，无半改。

### 12.2 遗留 3 项（均不阻断构建，但会影响运行/整洁）

1. **图标文件未搬**：`assets/modid/icon.png` 仍在，`assets/isharmla/` 为**空目录**，而 `fabric.mod.json:15` 已指向 `assets/isharmla/icon.png`。
   - 影响：**静默忽略**——既不阻断启动、也不打 WARN/ERROR（3号 解包 fabric-loader 0.20.1 源码核实：`V1ModMetadataParser.readIcon()` 仅解析字段、不校验文件存在；`getIconPath` 在 impl 里只有定义、无消费方；1号 复核：常量池中 `getIconPath` 仅出现在 metadata/API 类，`readIcon` 只在 parser 内）。仅影响将来 Mod Menu 的图标显示。沙箱无法复制二进制，需用户手动搬。
2. **空目录残留**：`src/main/java/com/example/mixin`、`src/client/java/com/example/client/mixin`（2号 自检「0 残留」未覆盖空目录）。
   - 影响：仅目录整洁度；`grep` 不会命中，构建无影响。
3. **改名后未重新编译**：`build/libs/` 仍是 `modid-1.0.0.jar`（14:28）与 `Isharmla-1.0.0.jar`（14:30），**没有 `isharmla-*.jar`**。
   - 影响：`com.isharmla` 包下的源码从未编译过，13.1 的一致性只到静态层面。

### 12.3 另外两点（非本次任务，仅记录）

- `fabric.mod.json` 的 `name` / `description` / `authors` / `contact` 仍是模板占位（`Example Mod`、`Me!`、`fabricmc.net`），属内容项，未随重命名处理。
- `settings.gradle:12` 注释仍为 `// Should match your modid`（Lead 已认领顺手改）。

### 12.4 收口建议

1. 4号 重跑 `build`（确认产出 `isharmla-1.0.0.jar` 且 jar 内 `id` = `isharmla`），再跑一次 `runClient`。
2. 用户手动把 `assets/modid/icon.png` 搬成 `assets/isharmla/icon.png`。
3. 2号 删除两个空目录 `com/example/**`。

### 12.5 mixin 验证要分三层（5号 纠正，1号 复核采纳）

| 层 | 结论 | 证据 |
| --- | --- | --- |
| 织入（应用） | ✅ 已证实 | `run/logs/debug.log:1071` `Mixing ExampleMixin from modid.mixins.json into net.minecraft.server.MinecraftServer`；`:985` `Mixing ExampleClientMixin ... into net.minecraft.client.Minecraft`。全日志共 353 条 `Mixing`。`Mixing ... into` 是**应用期**日志；若 `@Inject(method="loadLevel")` 目标不存在，会抛 `InvalidInjectionException`。 |
| 回调（行为） | ❌ 未证实 | `run/saves` 为空、日志无 `IntegratedServer`/`loadLevel` 行 → 从未进入世界，`@Inject` 回调一次没执行过。**织入成功 ≠ 行为正确。** |
| 服务端独立运行 | ❌ 未验证 | `runServer` 从未启动。 |

**对 5号 口头结论的一处修正**：其「debug.log 中 `ERROR|FATAL|Exception` 计数 = 0」不准确——按日志级别标记实际有 **3 条 ERROR**（`:1532`、`:1778`、`:1843`，全部是联网/Realms 失败）；另有若干行含 `Exception` 字样但属类名（如 `RemapException`）。结论（无 mixin 级失败）不变，但计数口径要写清。

另：`debug.log:162` 的 `Compatibility level JAVA_25 ...` 提示来源是 **fabric-tag-api-v1**，不是本项目配置，与 §6-7 同类。

### 12.6 git 状态（Lead 已处理）

- `.git` 存在，`HEAD → refs/heads/main` = `54feab17f45ca87622e790e37df34571363d7215`（短 `54feab1`）。
- **更新（14:5x）**：重命名已入库（`030015c`，15 文件）；此后还有若干文档提交（**件数以 `git log` 为准，本文不记**）；`git status` 干净。
- 风险提示：仍不要随手 `git reset --hard` / `git clean -fd`，会丢掉后续未提交的改动；`030015c` 之前的唯一回滚点是 `54feab1`（重命名前）。

