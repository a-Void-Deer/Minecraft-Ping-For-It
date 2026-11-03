# 复核：两条探针 mixin + generateModMetadata 构建脚本

审核人：3号·代码审核员　时间：2026-10-28 16:5x　对象：ping-for-it 的探针改动与 build.gradle:41-53
**一句话结论：代码本身没问题（无逻辑变更、无重复注入、无线程问题）；但两条探针都证明不了「进入世界」；构建脚本能跑但少了 1 个输入声明，存在版本号不刷新的隐患。**

---

## 一、代码正确性（问题 1）

| 检查项 | 结论 | 证据 |
|---|---|---|
| 是否改变原有逻辑 | 否，只多了一行 `LOGGER.info` | ExampleMixin.java:15、ExampleClientMixin.java:15；方法体其余内容与原注释一致 |
| 日志级别 | `INFO` 合适（每次启动/每服务器实例各 1 次，不是每 tick） | 见二节调用次数表 |
| 日志前缀重复 | 建议去掉消息里的 `[PingForIt]` | 日志行实测形如 `(ping-for-it) [PingForIt] client main loop started`——logger 名已是 modid，前缀冗余（run/logs/latest.log:94、:134） |
| method 名是否匹配 | 匹配，且**只有一个**目标方法 | `javap -p -cp <common.jar> net.minecraft.server.MinecraftServer` → `protected void loadLevel();`；`... Minecraft` → `public void run();`（Minecraft.java:560 那个 `run()` 是 :556 匿名 Runnable 的，不是 Minecraft 成员，源码已核） |
| 目标签名 | 均为 `()V` | 同上；源码 MinecraftServer.java:395 `protected void loadLevel()`、Minecraft.java:895 `public void run()` |
| 重复注入风险 | 当前无；**但有隐患** | `method = "loadLevel"` 是**按名匹配**。若将来 MC 加一个重载，Mixin 会同时注入两处且**不报错**（defaultRequire 只保证至少 1 处）→ 表现为日志打两遍。建议写死描述符 `method = "loadLevel()V"` / `"run()V"` |
| 线程安全 | 无问题 | `loadLevel` 在 Server thread（latest.log:134 线程名 `Server thread`）；`run` 在 Render thread（:94）；slf4j 线程安全；两处无共享可变状态、无锁 |
| 客户端/服务端隔离 | 正确 | client mixin 引用 main 源集的 `isharmla.mod.PingForIt`（client→main 合法方向）；main 未引用任何 `net.minecraft.client.*` |
| 类加载副作用 | 无 | `PingForIt` 由 ModInitializer 在启动时已初始化，探针里的 `PingForIt.LOGGER` 只是首次方法调用，不触发额外类加载竞争 |

---

## 二、语义（问题 2）：两条探针都**不能**证明「进入世界」

### 时间线（run/logs/latest.log，全部同一会话）
```
16:53:47  :94  (Render thread)  [PingForIt] client main loop started     ← 探针 1
16:53:58  :132 (Server thread)  Starting integrated minecraft server
16:53:58  :133 (Server thread)  Generating keypair
16:53:58  :134 (Server thread)  [PingForIt] server world loading          ← 探针 2
16:53:58  :135 (Server thread)  Selecting global world spawn...
16:54:01  :136 (Server thread)  Loading 0 persistent chunks...
16:54:05  :156 (Server thread)  Player884[...] logged in with entity id 9 ← 玩家真正进世界
```
两条探针都**早于玩家存在**：探针 1 早 11 秒（客户端启动阶段），探针 2 早 7 秒（世界还在生成）。

### 源码依据（genSources 的 26.3 sources jar，已解包核对）
- `MinecraftServer.java:395` `protected void loadLevel() { ...:401 this.createLevels(); :402 forceDifficulty(); :403 prepareLevels(); }`
- `MinecraftServer.java:367` `protected abstract boolean initServer()`
- `MinecraftServer.java:708-712` `runServer() { if (!this.initServer()) throw new IllegalStateException("Failed to initialize server"); ... }` → **initServer 每实例只调 1 次**
- `IntegratedServer.java:104-114` `initServer() { :105 LOGGER.info("Starting integrated minecraft server..."); :106 setUsesAuthentication; :107 initializeKeyPair(); :108 this.loadLevel(); ... }` ← 与日志 :132→:133→:134 完全对齐
- `DedicatedServer.java:243` `this.loadLevel()`（在 `initServer()` 内，:164 起）
- `GameTestServer.java:179` `this.loadLevel()`
- 全库 7301 个 .java 里 `loadLevel` 的调用点**只有这 3 个**（其余命中都是 `loadLevelSummaries`/`openWorldLoadLevel*` 等无关同名）

### 调用次数表

| 场景 | `MinecraftServer#loadLevel` 是否触发 | `Minecraft#run` |
|---|---|---|
| 客户端启动 | — | 1 次 |
| 单人新建/读取存档进入世界 | 1 次（新建 IntegratedServer） | 不再触发 |
| 退出到标题再进同一世界 | 又 1 次（新 IntegratedServer 实例） | 不再触发 |
| **切换维度（下界/末地）** | **不触发**（`createLevels()` 一次性建好全部维度，:420-429 遍历 `Registries.LEVEL_STEM`） | 不触发 |
| 死亡重生 / 传送 / 换区 | 不触发 | 不触发 |
| 局域网：其他人加入 | 不触发（只房主触发一次） | 不触发 |
| 专用服务器启动 | 1 次，**即使没有任何玩家进服** | — |
| GameTestServer | 1 次 | — |

### 结论（必须传达的风险）
1. **探针 2 ≠ 进入世界**：它是「服务器开始装配维度」的信号，早于玩家登录、早于 chunk 生成，且专用服务器无人也触发；换维度则完全收不到。
2. **注入点在 HEAD，此时世界对象尚不存在**：`loadLevel()` 第一行就注入，`createLevels()` 还没跑（:401）→ `server.getLevel(...)` 返回 null、`getPlayerList()` 为空。若后续功能在这里读世界/玩家，必 NPE。
   日志顺序也证明这点：:134 探针 → :135 `Selecting global world spawn` 才刚开始。
3. **探针 1 只是客户端主循环启动**：早于任何网络/世界活动，与"进入世界"无关（本会话里它比进世界早 11 秒）。
4. **若要可靠的"进入世界"信号**（以下 API 均已用 `javap` 确认存在于当前 fabric-api 0.165.0+26.3 的 jar 中）：
   - 服务端按玩家：`net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN`
   - 客户端进服/进世界：`net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN`
   - 「服务器世界就绪」（loadLevel 之后、ServerLevel 已存在）：`net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED`

---

## 三、构建脚本（问题 3）：generateModMetadata

现状：build.gradle:43-53 注册 `Copy` 任务 `generateModMetadata`（`src/main/templates/fabric.mod.json` → `build/generated/modMetadata`，`expand "version": project.version`），再把该目录 `srcDir` 进 `sourceSets.main.resources`，并显式 `processResources.dependsOn`。

### 3.1 配置缓存兼容性：**实测通过**
- `build/reports/configuration-cache/` 下 6 份报告，全部 `totalProblemCount=0`：`clean`(16:41:33)、`build`(16:41:47)、`build`(16:41:51)、`runDatagen`(16:43:30)、`runServer`(16:44:03)、`runClient`(16:45:12)。
- 观察（非缺陷）：6 次全是 `cacheAction=storing`，没见到 `reusing`；报告 diagnostics 显示构建逻辑读了 `文件系统条目 .idea`、`.idea\\misc.xml` 等（Loom 探测 IDEA 同步/系统属性）。→ **.idea 一有变动 CC 条目就失效**，这是 Loom 行为，跟本改动无关，但会稀释 CC 的收益。

### 3.2 任务依赖顺序：**正确**，且有一处冗余
- `sourceSets.main.resources.srcDir(generateModMetadata.flatMap { it.destinationDirectory })`（:49）——传 Provider，Gradle 会据此自动建立「processResources / sourcesJar 依赖 generateModMetadata」的隐式依赖。
- `tasks.named("processResources") { dependsOn generateModMetadata }`（:51-53）因此是**冗余**的（无害，但别把它当成顺序的唯一保证）。
- 实证：main jar 内 `fabric.mod.json` 的 `version` 已是 `1.0.0`、无 `${version}` 残留；`build/resources/main/fabric.mod.json` 与 `build/generated/modMetadata/fabric.mod.json` 内容一致（仅 version 行与模板不同）。

### 3.3 sourcesJar：**模板没被带进去**
- `tar -tf build/libs/ping-for-it-1.0.0-sources.jar` → 只有 `isharmla/**/*.java`、`fabric.mod.json`（已展开）、两个 mixins.json、`assets/ping-for-it/icon.png`、MANIFEST。**无 `templates/` 路径**。
- 生成的 `fabric.mod.json` 会进 sources jar（因为它现在是 resources 根之一），这是预期行为。

### 3.4 重复资源：**当前 0 重复，但没有防呆**
- main jar 内只有 1 个 `fabric.mod.json`；`src/main/resources/` 下已无 `fabric.mod.json`（只剩两个 mixins.json + assets）→ 与模板不会打架。
- 风险：若有人之后又把 `fabric.mod.json` 放回 `src/main/resources/`，会出现两个同名资源，Gradle 默认策略会静默择一（谁赢取决于顺序），排查起来很难。
- 建议：`processResources { duplicatesStrategy = DuplicatesStrategy.FAIL }`，让冲突在构建期炸出来。

### 3.5 真正的坑（3 个，按影响排序）

**坑 A（重要）：少了 `inputs.property "version"`，版本号可能不刷新**
- 原模板写法是 `processResources { inputs.property "version", version; filesMatching(...) { expand ... } }`，`inputs.property` 不是装饰：Gradle 的 `Copy.expand` / `replaceTokens` 属性**不进入任务输入快照**（社区长期确认：Gradle 论坛 "Why are replace tokens and expand properties not part of the inputs of the Copy task?"、StackOverflow "Gradle does not detect change when using Copy task with expand"）。
- 新写法只留了 `expand "version": project.version`，没有 `inputs.property` → 只改 `gradle.properties` 的 `version`（模板与其他输入不变）时，`generateModMetadata` 可能被判 UP-TO-DATE，jar 里仍是旧版本号。
- 最小修复：`generateModMetadata { inputs.property "version", project.version }`（或 `inputs.property("version", providers.gradleProperty("version"))`）。
- 我不能跑 gradle（Lead 要求本轮不跑），这条标 **高置信 / 待一次构建复核**，复核方法：
  ```
  # 改 gradle.properties version=1.0.1 后
  ./gradlew -g D:/Gradle generateModMetadata --console=plain
  # 看该任务是否 UP-TO-DATE；选 build 则看 build/generated/modMetadata/fabric.mod.json 的 version
  ```

**坑 B（中）：用的是 `Copy` 而不是 `Sync`**
- `Copy` 不会删除「源里已经删掉」的旧输出；`Sync` 会。当前只有 1 个文件影响小，但若模板改名/删除，旧的 `build/generated/modMetadata/fabric.mod.json` 会留在 build 里继续被 `processResources` 打包（静默带入过期元数据）。
- 最小修复：把 `tasks.register("generateModMetadata", Copy)` 换成 `Sync`。

**坑 C（低）：`expand` 对 `$` 很脆**
- `expand` 用 Groovy SimpleTemplateEngine：模板里除 `${version}` 外再出现任何 `$`（比如描述里写 `$5`、URL 里带 `$`）会直接构建失败（"Could not expand template"）。
- 建议：模板中避免裸 `$`；或在描述文案里用 `$\{` 转义并加注释。

---

## 四、整改建议（按性价比排序，均未代改）

1. **语义（最能救命）**：把"进世界"判定换成 `ServerPlayConnectionEvents.JOIN`（按玩家）或 `ServerLifecycleEvents.SERVER_STARTED`（世界就绪）；`loadLevel` 的 HEAD 位置**不要**用来访问世界/玩家（此点处 `getLevel()` 为 null）。
2. **mixin 加固**：`method = "loadLevel()V"`、`method = "run()V"`，避免未来重载导致静默双注入。
3. **构建**：加回 `inputs.property "version"`；`Copy` → `Sync`；加 `duplicatesStrategy = FAIL`。
4. **可读性**：`ExampleMixin`/`ExampleClientMixin` 现在承载实体逻辑，名字已名不副实；日志消息去掉重复的 `[PingForIt]` 前缀。

## 五、复核命令（可复现）
```
javap -p -cp <minecraft-common-...-26.3.jar> net.minecraft.server.MinecraftServer | findstr loadLevel
javap -p -cp <minecraft-clientOnly-...-26.3.jar> net.minecraft.client.Minecraft | findstr " run("
tar -tf build/libs/ping-for-it-1.0.0.jar
tar -tf build/libs/ping-for-it-1.0.0-sources.jar
tar -xOf build/libs/ping-for-it-1.0.0.jar fabric.mod.json
grep -n "loadLevel" <解包的 MinecraftServer.java / IntegratedServer.java / DedicatedServer.java / GameTestServer.java>
```
（genSources 产物：`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-{common,clientOnly}-1b0498e74c/26.3/*-sources.jar`）
