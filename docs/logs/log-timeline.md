# 构建/运行 异常时间线（5号 · log-monitor）

> 记录格式：`时间戳 | 级别 | 来源文件:行号 | 关键行 | 初步归因 | 是否需要1号分析`
> 只记**有原文证据**的条目；「待验证」= 未自证。采集窗口 2026-10-28 14:14–14:30。

## 一、今日已有事件（按时间）

| 时间戳 | 级别 | 来源:行号 | 关键行 | 初步归因 | 需1号 |
| --- | --- | --- | --- | --- | --- |
| 2026-10-28 13:55:42 | ERROR | `D:\Gradle\daemon\9.8.2\daemon-8724.out.log:198` | `> Dependency requires at least JVM runtime version 25. This build uses a Java 24 JVM.` | Gradle 自身 JVM 是 jdk-24；P0 已知坑，非代码问题。已在 AGENTS.md #1 | 否 |
| 2026-10-28 13:56:20 | INFO | `daemon-33824.out.log` | `javaHome=C:\Program Files\Java\jdk-25,javaVersion=25` + `daemonOpts=-Xmx1G` | 换 JDK 25 后新 daemon；堆仍 1G（P2 #5 未改） | 否 |
| 2026-10-28 14:04:46 | WARN | `build/reports/problems/problems-report.html` | `Relying on the default value of the 'org.gradle.tooling.parallel' property ...` (totalProblemCount=1) | IDE sync 配置阶段唯一告警，Gradle 10 才致命 | 否 |
| 2026-10-28 14:20:11→14:21:38 | BUILD | `daemon-33824.out.log:1438` | `BUILD SUCCESSFUL in 1m 24s` / `Calculating task graph ... tasks: genSources` / `2 actionable tasks: 2 executed` | **这是 `genSources`（反编译），不是编译**；Loom 1.19.3；decompile cache 0 hits / 5037+2264 misses | 否 |
| 2026-10-28 14:22:07 | ERROR | `docs/build/build-attempt-1.log:1` | `java.io.FileNotFoundException: D:\Gradle\wrapper\dists\gradle-9.8.2-bin\8wepis63pzuhrlfaiy04bagwu\gradle-9.8.2-bin.zip.lck (拒绝访问。)` | Gradle wrapper 在 `Install.createDist` 阶段就失败，**早于任何构建逻辑**；属环境/锁问题，非代码问题。原因待验证 | 是（确认是否阻塞后续 build） |
| ≈2026-10-28 14:2x（该段为纯控制台输出、无时间戳前缀，故为估计值） | ERROR | `daemon-33824.out.log:1727,1736,1746`；`:2136,2145,2155`；`:2808,2817,2827`；`:2983,2992,3002` | `Task '#' not found in root project 'modid'.` / `... 'Isharmla'.` / `BUILD FAILED in 907ms / 933ms / 560ms / 609ms` | **文档坑，共 4 次**：把带行内注释的整行粘进 **cmd**（`#` 不是注释符）→ `#` 与注释中文被当成任务名。前两组针对 `modid`（注释文案 `build # 编译 + 打包 → ...`），后两组针对 `Isharmla`（文案含 `# 产物：...`）。**已由 1号 记入 AGENTS.md §6-12 并改掉 §3/README 的写法** | 已归档 |
| 2026-10-28 14:28:09→14:28:14 | BUILD | `daemon-33824.out.log:2084` / `:2087` / `:2088` | `tasks: build` / `BUILD SUCCESSFUL in 7s` / `7 actionable tasks: 7 executed` | **首次真正编译成功**（推翻 P0 #2 的「从未编译过」）：`build/libs/modid-1.0.0.jar` 8311B，`build/classes/java/main` 2 个 + `java/client` 2 个，split 环境正常。**注**：`:2276` 那条 `BUILD SUCCESSFUL in 2s` 实为随后 `4 up-to-date` 空跑，**不是编译证据**（此处初版引错，1号 已纠正） | 已归档（AGENTS.md §6-2） |
| 2026-10-28 14:30:06 | INFO | `settings.gradle:13` | `rootProject.name = 'Isharmla'` | 非团队成员的改名操作，产物名随之变 `Isharmla-1.0.0.jar`（8391B, 14:30:32）；此后 14:30 起的两次 `build` 都被上一条 `#` 坑打断 → **Isharmla 无成功完整构建**（待 4号 重跑）。后续 14:5x 已完成 `modid → isharmla` 全量重命名（`fabric.mod.json:3` "id": "isharmla"、包名 `com.isharmla`、mixin 配置改名 `isharmla*.mixins.json`） | 已归档（AGENTS.md §6-3） |
| 2026-10-28 14:34:03→14:34:29 | BUILD/INFO | `run/logs/latest.log:1,3,55,56,61,62,64,72,102,103,126`；`debug.log:985,1071`；`daemon-33824.out.log:3154,3250` | `:3154 tasks: :runClient` → `:3250 BUILD SUCCESSFUL in 27s`；`latest.log:1 Loading Minecraft 26.3 with Fabric Loader 0.20.1`、`:3 Loading 52 mods`、`:55 - modid 1.0.0`、`:62 (modid) Hello Fabric world!`、`:126 Stopping!` | **首次 runClient 成功启动到主菜单并正常退出**（约 26 秒）；无 `Mixin apply failed`、无 crash-report；4 条 ERROR/WARN 全部与 mod 无关（3 条联网/Realms + 1 条 Perfmon 注册表）。**边界：只证到主菜单，未进世界，不构成功能验证** | 是（见 §六） |
| ≈2026-10-28 14:2x（该段为纯控制台输出、无时间戳前缀，故为估计值） | ERROR | `daemon-33824.out.log:2145` / `:2155` | `Task '#' not found in root project 'modid'.` / `BUILD FAILED in 933ms` | **文档坑**：AGENTS.md §3 的 bash 代码块带行内注释 `build # 编译 + 打包 → build/libs/modid-1.0.0.jar`，整行粘进 **cmd**（`#` 不是注释符）后，`#` 与中文全被当成任务名。L1727 与 L2136 两处 task graph 都带这串注释 | 是（1号 改 §3 写法） |
| 2026-10-28 14:28:09→14:28:14 | BUILD | `build/libs/modid-1.0.0.jar`；`daemon-33824.out.log:2276` | `BUILD SUCCESSFUL in 2s`；jar 8311B，含 4 个 class | **首次真正编译成功**（推翻 P0 #2 的「从未编译过」）：`build/classes/java/main` 2 个 + `java/client` 2 个，split 环境正常 | 是（AGENTS.md P0 #2 必须更新） |

## 二、本次采集的关键结论（给 Lead / 1号）

1. **「BUILD SUCCESSFUL」≠ 编译成功。** 14:21 那次是 `genSources`，
   `build/libs/`、`build/classes/` 仍**不存在**，`.class` 数量 = **0**。
   当时 AGENTS.md P0 #2「本项目从未成功编译过」**仍然成立**。
   **⚠ 但此结论已在 14:28 被推翻**：首次真正编译成功，产出 `build/libs/modid-1.0.0.jar`
   （8311B、4 个 class、14:28:14）→ **AGENTS.md P0 #2 现已过期，必须更新**。
2. `genSources` 成功产出两份 sources jar（2.6MB + 6.2MB），位于
   `.gradle/loom-cache/minecraftMaven/net/minecraft/...` → **26.3 源码可供 3号 反查
   `Minecraft#run`、`MinecraftServer#loadLevel` 是否还存在**（P1 #4 可提前核查，不必等 runClient）。
   **已由 1号 用此闭环（2026-10-28）**：两个目标方法均存在、签名 `()V`，
   静态匹配风险降级为 P2「仅剩运行期命中待验证」。行号速查与防误判见 §五。
   **已被 1号 用它闭环**（2026-10-28）：两个目标方法都在、签名均为 `()V`，
   静态匹配风险降级为 P2「仅剩运行期命中待验证」。速查表见 §五。
3. ~~`run/` 仍是空壳，`runClient` 从未成功启动过。~~
   **14:34 已首次成功启动到主菜单并正常退出**（见表格最后一行）；`run/logs/latest.log`、
   `debug.log`、`options.txt` 均已生成，**无 crash-report**。
4. wrapper 的 `gradle-9.8.2-bin.zip.ok` 与 `gradle-9.8.2/` 目录均在（13:55:27 已装好），
   说明 dist 本身没坏；14:22 的失败发生在**取锁**一步。
5. 5号 无法自证 `.lck` 是否仍被占用：`D:\Gradle` 在写入沙箱外，`tasklist` 返回
   `Access denied`。需 4号 用 pwsh（`Get-Process java`）或有权限方核实。

## 三、空模板（新事件照抄）

| 时间戳 | 级别 | 来源:行号 | 关键行 | 初步归因 | 需1号 |
| --- | --- | --- | --- | --- | --- |
| YYYY-MM-DD HH:mm:ss | ERROR/WARN/FATAL/INFO/BUILD | path:line | （原文一行，不改写） | （猜测，待验证） | 是/否 |

## 四、待采空白区（一旦发生立即补录）

- [x] 首次 `runClient`（2026-10-28 14:34）→ 摘要见 [`run-20261028-1434-runclient.md`](run-20261028-1434-runclient.md)
- [x] 首次真正 `build`（14:28）→ `build/libs/modid-1.0.0.jar`
- [ ] **重命名后的 `runClient`**：日志里 mod 名会变成 `isharmla`，
      重点看 `Mixing ExampleMixin from isharmla.mixins.json` 是否仍出现
- [ ] `remapJar` 是否 OOM（`-Xmx1G`，P2 #5）
- [ ] configuration cache 在 `runClient`/`remapJar` 是否报不兼容（P2 #6）

## 五、mixin 目标行号速查 + 防误判（1号 已核，2026-10-28）

> 用途：`runClient` 出 `Mixin apply failed` / `InvalidInjectionException` 时，
> 栈里会给目标类的行号；**照着下表比对，别把相邻的匿名类当成目标**。

| mixin 类 | 目标 | 26.3 位置 | 备注 |
| --- | --- | --- | --- |
| `ExampleMixin` | `MinecraftServer#loadLevel` | `net/minecraft/server/MinecraftServer.java:395` | `protected void loadLevel()` |
| `ExampleClientMixin` | `Minecraft#run` | `net/minecraft/client/Minecraft.java:895` | `public void run()` |
| （参考） | `Identifier.fromNamespaceAndPath` | `net/minecraft/resources/Identifier.java:40` | 类声明在 :18 |

**⚠ 陷阱（1号 特别提醒）**：`Minecraft.java` **560 行**那个 `public void run()` 是
**窗口关闭回调里的匿名 Runnable**，不在 `Minecraft` 类内。
→ 日志里若出现指向 560 的 `run()`，**不是** mixin 目标，不得据此判定注入失败或成功。

**判读规则**：静态度（方法存在 + 签名匹配）已确认，因此 `runClient` 若仍报注入失败，
优先怀疑 **mixin 配置的 `package` 与真实包名不一致 / 类未进 `mixins` 数组**，
而不是怀疑 26.3 删了方法。

## 六、关于「§6-4 `MinecraftServer` 注入」的精确表述（2026-10-28 14:34 首启）

Lead 的要求：**只有真的进过世界、触发了 `loadLevel`，才算「验证」；仅启动到主菜单不算。**
按这个口径，我把证据拆成两层，不要混为一谈：

### 第一层：注入（字节码编织）—— **已发生，有正面日志**
```
debug.log:1071  Mixing ExampleMixin from modid.mixins.json into net.minecraft.server.MinecraftServer
debug.log:985   Mixing ExampleClientMixin from modid.client.mixins.json into net.minecraft.client.Minecraft
debug.log:992   modid.client.mixins.json:ExampleClientMixin from mod modid->@Inject::init(...) doesn't use it's CallbackInfo
```
- `Mixing ... into ...` 是 Mixin **应用期**日志（不是 `Preparing ...` 的准备期），说明
  `MinecraftServer` 类在本次客户端运行中**确实被加载**，且 `ExampleMixin` 被织入。
- Mixin 在织入时会校验 `@Inject(method="loadLevel")` 的目标是否存在；目标缺失会抛
  `InvalidInjectionException` 并让类加载失败。本次**无此类失败**
  （`Mixin apply failed|InvalidInjectionException|MixinTransformerError` 计数 = 0）。
- ⚠ **更正（1号 指出，已复核）**：此处初版写「`ERROR|FATAL|Exception` 计数 = 0」是**错的**。
  `debug.log` 按级别标记实有 **3 条 ERROR**，全部是联网/Realms、与本 mod 无关：
  `:1532 Failed to fetch user properties`、`:1778 Failed to fetch Realms feature flags`、
  `:1843 Couldn't connect to realms`。
- **踩坑原因（务必记住）**：日志级别格式是 `[HH:mm:ss] [thread/LEVEL]`，
  用 `\[ERROR\]` 这种正则**永远匹配不到** `[Download-2/ERROR]`，会得到假阴性 0 条。
  **正确写法：`/(ERROR|FATAL)\]`**。同一个错误我在 `latest.log` 上犯过一次并修正，
  在 `debug.log` 上又犯了一次 → 结论被污染。已把该检查固化进采集脚本（见下）。
- → 所以「注入点定位 + 织入」这一层是**已验证**的，不是「未验证」。

### 第二层：回调执行（loadLevel 方法体真正跑起来）—— **未验证，也无法验证**
- `run/saves` 为空（0 项），日志无 `IntegratedServer` / `loadLevel` 相关行 → **没创建世界**。
- `@Inject(at=@At("HEAD"), method="loadLevel")` 的回调只在 `loadLevel()` 被调用时执行，
  而它只在进入世界时触发 → **回调一次都没跑过**。
- → 按 Lead 的口径，**「loadLevel 的行为」确实不算验证**。

### 结论（建议 AGENTS.md §6-4 就这么写）
> `ExampleMixin` 的注入**已成功织入** `MinecraftServer`（`debug.log:1071`），
> 但 `loadLevel()` **从未被调用**（未进世界），因此**回调行为未验证**。
> 二者不可互相替代：织入成功 ≠ 行为正确。

**为什么值得纠正**：§6-4 现在写「`MinecraftServer` 那条注入仍属未验证」，容易被读成
「可能根本没织进去」——那会让人往 mixin 配置方向瞎查；而实际上配置、package、refmap 都没问题
（`Mixing` 行就是反证）。真正待办的是「进一次世界」。

### 下一轮的判别要点（重命名后）
重命名已完成：mod id = `isharmla`、包名 `com.isharmla`、配置 `isharmla.mixins.json` /
`isharmla.client.mixins.json`。下一轮 `runClient` 应出现：
```
Mixing ExampleMixin from isharmla.mixins.json into net.minecraft.server.MinecraftServer
```
若出现的是 `modid.mixins.json` → 说明跑的是旧 jar/旧 run 目录，需先确认产物。

