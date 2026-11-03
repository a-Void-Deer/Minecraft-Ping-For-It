# 13 · devlog 日志镜像审核（ModLogFile）+ 附：devdiag 诊断模组功能完整性

任务：task-3　审核人：3号（第 2 代）　**审查快照：2026-10-29 22:47（工作树未提交）**
快照指纹（sha256[0:12]）：`ModLogFile.java` 443 行 `2E6955ECCBE8`｜`FabricMain.java` 114 行 `9524D53A342D`
方法：只读源码 + **逐行核对 log4j-core-2.26.0-sources.jar**（`D:/Gradle/caches/.../log4j-core-2.26.0/`）+ 在 log4j 2.26.0 上用 MC 同款 `<Root>` XML **实测复现**（实验脚本与原始输出见 §附录 A；证据独立于实现者自述）。
编译复跑：`bash tools/compile-check.sh` → **main 0 error / 266 class**、**client 0 error / 86 class**（对比 11 号文档 264 / 85，+2 恰为 `ModLogFile` + `BoundedAppender` 内部类，说明本次只新增这两个 class）。

## 结论

**可以合入**（无阻断项）。**2号 声称的 additivity 回归属实，我独立复现并确认修复有效**，但**写法不是最稳妥的**（见 D3 与四.1，属"能工作但可更安全"）；另有 1 项低危偏差（runId 缺 `Locale.ROOT`，会破坏与其他 devlog 模组的目录对齐）。

**实机首跑（runId 2026-10-29_22-49-09）已回来，结论加强**：镜像 705 行（3 行头 + 626 DEBUG + 76 INFO）；`latest.log` 里 `[PingForIt]` **75 行、全 INFO、0 DEBUG**；`sort | uniq -d` = 0。这同时**证明 additivity 修复在真实环境生效**（不修则那 75 行会全部消失），并确认**镜像是 latest.log 的超集**（多出的 626 行 DEBUG 因 MC 的 File appender-ref 只收 INFO 而本来就不进 latest.log）。详见 P18。

对 Lead 三个问题的直接回答：

| 问题 | 结论 | 依据 |
|---|---|---|
| **(a) 结论成立吗？** | **成立**，且不是理论推演——我用 MC 同款 `<Root>` XML 复现了"日志只进镜像、不进 latest.log" | 源码：`AbstractConfiguration.java:939-957`（`:950` 复制 `lc.isAdditive()`）、`:1045-1058`（找不到就退到 root）、`LoggerConfig.java:951`+`:1140-1147`（`<Root>` 的 Builder 用**原始 boolean** `additivity`，无初始值 ⇒ **false**）。实测：PHASE1 `mirrorHits=1 / rootFileHas=false`（附录 A） |
| **(b) 会误伤用户显式 additive=false 吗？** | **不会**，判定条件恰好卡在正确位置 | `ModLogFile.java:185-186` 先算 `configured`；只有 `configured==false`（即配置树里**根本没有**名为 `pingforit` 的节点，节点是 log4j 自己 new 出来的）才改（`:190-200`）。用户显式写的 `<Logger name="pingforit" additive="false">` 会命中 `configured==true` → 不碰。补充：logger 名 `pingforit` **不含点**，`NameUtil.getSubName` 直接返回 null（`AbstractConfiguration.java:1050-1057`），所以用户的 `nx.pingwheel` 配置**根本不会**被命中——不存在"降级命中父包配置再被改掉"的路径 |
| **(c) 有更稳妥的写法吗？** | **有**：先建节点（`Configuration#setLoggerAdditive(logger,true)`）再 `logger.addAppender(...)`。我已在 2.26.0 上实测等价可用（附录 A · Probe3）：节点首次发布就是 `additive=true`，**不需要事后改**，也没有 D3 的窗口 | `Configuration.java:91` 声明、`AbstractConfiguration.java:994-1007` 实现（内部 `new LoggerConfig(name, lc.getLevel(), additive)` + `updateLoggers()`） |

---

## 一、通过项

| # | 项 | 结论 | 证据（本地行号 ↔ log4j 行号） |
|---|---|---|---|
| P1 | **additivity 回归属实** | **成立**。`<Root>` 节点由 `LoggerConfig.RootLogger.Builder` 构建，其 `additivity` 字段是**无初始值的 boolean ⇒ false**；`addLoggerAppender` 新建子节点时把 `lc.isAdditive()`（此处 = root 的 false）复制过去 ⇒ 新节点不向上冒泡 | `LoggerConfig.java:951,1140-1147`；`AbstractConfiguration.java:950`；实测 PHASE1（附录 A） |
| P2 | **修复有效** | `setAdditive(true)` 立即生效：`additive` 是普通字段、`logParent` 直接读它，**没有 PrivateConfig 快照**，不需要再触发一次 `updateLoggers()` | `LoggerConfig.java:72,566-568,796-800`；实测 PHASE2 `mirrorHits=2 / rootFileHas=true` |
| P3 | 不误伤显式配置 | 见上文 (b)；判定用"节点名是否等于自身"（`:185-186`），语义正确 | `ModLogFile.java:185-186,190-200` |
| P4 | **作用域隔离** | appender 只挂到名为 `pingforit` 的 `LoggerConfig`（`:183-188`）；root 的 appender 集合**零改动**（`addLoggerAppender` 只对 `getLoggerConfig(name)` 的节点动手）。实测 PHASE1：事件进了镜像、**没**进 root 的 file appender，同时 root 自己的 appender 仍在（PHASE2 立刻恢复）——反向证明 root 未被破坏 | `AbstractConfiguration.java:939-957`；`Logger.java:355-357` |
| P5 | 其它 logger 进不来 | 只有 `pingforit` 与其**点号子名**（`pingforit.xxx`）会向上冒泡到这里；全库 `LogManager.getLogger` 只有 `Global.java:27`（`pingforit`）与 `PacketHandler.java:14`（类名 → 不是本节点） | grep 实测；`NameUtil` 规则见 `AbstractConfiguration.java:1050-1057` |
| P6 | **开关在创建目录之前** | `enabled()` 在 `installUnchecked()` **之前**判定（`:106` → `:117`），生产环境不会创建任何 `logs/devlog` 目录；判定本身包了 try/catch（`:128-137`），FabricLoader 不可用时退回系统属性 | `ModLogFile.java:106-114,128-137,139-146` |
| P7 | **幂等** | volatile 标志 + 锁内二次检查，且 `installed=true` 在**干活之前**写（`:104`）：一次失败不会重试；并发调用第二个线程阻塞后看到 true 直接返回 | `ModLogFile.java:84,94-104` |
| P8 | **失败降级（4 类）** | ①目录/IO 失败 → 外层 `catch(Throwable)` 一行 warn（`:116-124`）；②appender 打不开 → `FileAppender.Builder.build()` **返回 null**（`FileAppender.java:88-90,115-116`：`isValid()` 失败或 `FileManager` 为 null 即 null）→ 本地 `:171-173` 抛 → 同一 catch；③`session.txt` 失败 → `CREATE_NEW` 以外的异常一行 warn（`:221-225`）；④保留策略移动失败 → 一行 warn（`:275-277,294-296`）。**四处都不影响启动** | `ModLogFile.java:116-124,171-173,216-226,250-278,280-297` |
| P9 | **保留策略只移动不删除** | 全文件**没有** `Files.delete`/`deleteIfExists`（grep 实测，唯一写入动作是 `:293 Files.move`）；只处理匹配 `\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}` 的目录（`:78,256`），`_archive` 天然被排除；当前 runId 显式排除（`:257`）；`excess = 已有 + 1 - 10`（`:264`）⇒ 保活目录数恰好 10 | `ModLogFile.java:250-297`；目录约定见 `tools/devdiag/src/main/java/nx/devdiag/RunLayout.java:188-230` |
| P10 | 同名冲突 `-2/-3` | `for (suffix=2; exists; suffix++)` 逐个试到 `-100` 后放弃并 warn（`:284-290`），不覆盖 | `ModLogFile.java:280-297` |
| P11 | **64 MiB 精确字节 + 正确顺序** | 计数 = header 字节 + `layout.toByteArray(event).length`（与真实写入同一 layout 实例），判据 `bytesWritten + size > MAX` ⇒ 文件**永不超过**上限；超限顺序严格为 **置 capped → 摘除 → stop(delegate) → stop(self) → warn**（`:410-413,420-441`）。`capped` 在读之前置位，所以**摘除失败也不会递归** | `ModLogFile.java:404-441`；`AppenderControl.java:104-113`（"Recursive call to appender" 由 `ThreadLocal` 在同线程检测，**先摘再 warn 是必须的**）、`:139`（对 stopped appender 追加会报 non-started 错误 ⇒ `:433` 先 stop 再 warn 也对） |
| P12 | **runId 算法与约定一致** | `ProcessHandle.current().info().startInstant()` → `LocalDateTime.ofInstant(systemDefault)` → `yyyy-MM-dd_HH-mm-ss`，`orElseGet(LocalDateTime::now)` 兜底 | `ModLogFile.java:331-336,345-351` ↔ `RunLayout.java:84-97`（逐语句一致；**缺 `Locale.ROOT`** 见 D1） |
| P13 | `session.txt` 语义正确 | `CREATE_NEW` + `FileAlreadyExistsException` 静默跳过（谁先写谁赢），与 devdiag 完全一致 | `ModLogFile.java:216-226` ↔ `RunLayout.java:133-148` |
| P14 | **纯净性** | `src/main` 下 `net.minecraft.client` / `com.mojang.blaze3d` 命中 **0**；未新增依赖（`build.gradle` 无 log4j，来自 Minecraft 自带 `log4j-core 2.26.0`，编译期已可用） | grep 实测 + `compile-check` 0 error |
| P15 | **线程安全** | `installed`/`attachedLogger` 均 volatile；`bytesWritten` 受 `synchronized append` 保护；shutdown hook **daemon**（`:305`）且全包 try/catch（`:300-309,312-321`） | `ModLogFile.java:84,86,301-309,404,420-441` |
| P16 | 自定义 MessageFactory 不受影响 | `Global.LOGGER` 用 `FormattedMessageFactory` 加 `[PingForIt] ` 前缀，而 `:183` 用单参 `getLogger` 另取实例。**实测**：两者是不同 Logger 实例（`sameInstance=false`）但**共用同一个 LoggerConfig**，两个创建顺序下镜像都收到事件、前缀都没丢 | 附录 A · Probe2（`mirrorHits=1 globalSeesMirror=true`，两种顺序） |
| P17 | 编译 | 已复跑：main 0 / **266** class、client 0 / **86** class | `tools/compile-check.sh` |

| P18 | **镜像 ⊃ latest.log（超集，通过）** | 镜像挂在 logger 节点上，节点 level 继承 root 的 **debug**，因此**收得到 DEBUG**；MC 的 `latest.log` appender-ref 只放 INFO → 镜像是 `latest.log` 的**超集**，`latest.log` 仍是自己的子集。实测 runId `2026-10-29_22-49-09`：mirror 705 行 = 3 头 + 626 DEBUG + 76 INFO，latest.log 侧 `[PingForIt]` 75 行全 INFO，无重复行。**结论：行为合理且有价值**（排障时能看到平时只在 `debug.log` 里的 DEBUG），但 2号 报告里"与 latest.log 逐行一致"的说法**不准确**，应改为"超集"；类 javadoc `:43-48` 的原话也只说 latest.log "keep receiving every line"，没说明多收 DEBUG → **D6 待补文档** |

> 说明：`:183` 另取一个 Logger 实例虽无功能问题，但会让 log4j 为同名 logger 建两个实例（P16 已证无害），属可接受开销。

---

## 二、偏差项（含严重度）

### D1 · 低危 · 建议修｜runId 少了 `Locale.ROOT`，会破坏跨模组目录对齐
- **本地**：`ModLogFile.java:77` `DateTimeFormatter.ofPattern(RUN_ID_PATTERN_TEXT)`——**未指定 Locale**，默认取 `Locale.getDefault(FORMAT)`；`DecimalStyle` 随 locale 的 numbering system 变。
- **约定参考实现**：`RunLayout.java:45-46` 明确写 `ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)`。
- **后果**（只在阿拉伯/波斯/泰等**非 ASCII 数字** locale 触发，如 `-Duser.language=ar`）：
  1. 本模组与 devdiag 在同一次运行里算出**不同 runId** → 各写各的目录，"一次运行一个目录"的约定失效；
  2. 产出的目录名不匹配 `:78` 的 `\d` 正则（Java 默认 `\d` = `[0-9]`）→ **永远不会被保留策略归档**，这类目录会无限堆积。
- **修法（1 处）**：`:77` 加 `Locale.ROOT`（与 `RunLayout.java:46` 逐字一致）。
- 备注：主路径（中文/英文 Windows）不受影响，属"小概率但会静默留下垃圾目录"的坑。

### D2 · 低危 · 建议修｜`session.txt` 的 `startTime`/`jvmArgs` 与共享约定口径不一致
- `session.txt` 是**共享文件**，`CREATE_NEW` 谁先写谁赢（P13），所以同一份文件的字段格式取决于哪个模组先跑。
- `startTime`：本地 `:342` 用 `DateTimeFormatter.ISO_OFFSET_DATE_TIME`（`2026-10-29T22:47:03.123+08:00`）；devdiag 用 `yyyy-MM-dd HH:mm:ss`（`RunLayout.java:47-48,153`）。
- `jvmArgs` 空值哨兵：本地 `:356` 为 `"none"`/`"unknown"`；devdiag 为 `"(none)"`/`"(unavailable)"`（`RunLayout.java:169-175`）。同一文件会出现两种口径。
- **影响**：任何按固定格式解析 `session.txt` 的采集/脚本（含 Lead 的诊断流程）在"本模组先跑"的那次运行里会拿到带 `T`/`+08:00` 的时间戳。
- **修法**：`:342` 改为 `yyyy-MM-dd HH:mm:ss`（`Locale.ROOT`），`:356` 哨兵对齐 `RunLayout`。

### D3 · 低危 · 建议改顺序｜additivity 是在节点**已经发布之后**才改的
- `logger.addAppender(mirror)`（`:188`）内部会 `updateLoggers()` 把新节点**发布**给所有线程（`AbstractConfiguration.java:950-955`、`LoggerContext.java:824-828`），之后 `:196-199` 才 `setAdditive(true)`。
- 两个后果：
  1. **窗口**：发布到修正之间的极短窗口内，其它线程打的 `pingforit` 日志仍不冒泡（丢几行 dev 日志，无功能影响）；
  2. **JMM**：`LoggerConfig.additive` 是**非 volatile** 字段（`LoggerConfig.java:72`），`logParent` 直读（`:797`）；`:198` 的写入没有 `updateLoggers`/`volatile` 的发布边，理论上其它线程可能长期读到旧值（x86 上实际不可见，属理论风险）。
- **不是"必须现在改"**：P2/P3 已证当前写法可用。修法见四.1（顺带把窗口和 volatile 问题一起消掉）。

### D4 · 观察（非缺陷）｜镜像只覆盖 `pingforit` 一个 logger，`PacketHandler` 的日志不进镜像
- `PacketHandler.java:14` 用 `LogManager.getLogger(PacketHandler.class)`（名字 `nx.pingwheel.common.network.PacketHandler`）→ 不在本节点上，**不会**进 `pingforit.log`。
- 类的 javadoc（`:39-42`）已诚实写明"the `pingforit` logger"，所以**不是实现错误**；但如果 Lead/1号 的预期是"模组全部日志一个文件"，这是一处口径缺口。
- 影响面：网络包相关的告警仍只在 latest.log 里。**要么改 PacketHandler 用 `Global.LOGGER`，要么在文档里写明"镜像仅覆盖 Global.LOGGER 的行"。**

### D5 · 观察｜生产（关闭）路径仍会触发 `Global` 类初始化
- `:109` 在 disabled 分支里调 `Global.LOGGER.debug(...)`：即使只打 debug，`Global.LOGGER` 的**表达式求值**就会初始化 `Global` 类 → `IPlatformContextService.INSTANCE`（ServiceLoader）提前加载。
- 实测无害（`onInitialize` 阶段 FabricLoader 已就绪，且 `CommonServer` 紧接着也要用它），但"安装点在一切之前 / 关闭时零副作用"这个说法要打个折。
- **可选修法**：disabled 分支直接 `return`（连 debug 都不打），或改用 `System.getLogger`。

---

### D6 · 低危 · 文档｜镜像的"超集"属性没写进任何文档（2号 的口头结论也不准确）
- **事实**（实机实测）：镜像 **705 行 = 3 头 + 626 DEBUG + 76 INFO**；`latest.log` 的 `[PingForIt]` **75 行全 INFO、0 DEBUG**。镜像比 latest.log 多出整整 626 行 DEBUG。
- **原因是设计的必然结果**：appender 挂在 `pingforit` 这个 **logger 节点**上，节点 level 继承 root 的 debug，所以在节点层就收下了 DEBUG；而 MC 的 `latest.log` appender-ref 只放 INFO 才丢掉它们。→ **镜像天然是 latest.log 的超集，不可能"逐行一致"**。
- **文档现状（已逐处核）**：
  1. 仓库里**没有** devlog/镜像的 README（`grep devlog docs/` 命中 0）；唯一说明是 `ModLogFile` 类 javadoc `:39-62`；
  2. javadoc `:43-48` 只说 root 上的 console/latest.log "keep receiving every line, so the normal log output is unchanged" —— 这句**本身没错**（latest.log 的输出确实没变），但**没有一句**说明镜像会额外收到 DEBUG、是超集；
  3. 因此 2号 交接里写"与 latest.log 逐行一致"时，没有任何文档与他矛盾 —— 这正是**该补文档**而不是补代码的地方。
- **修法**：在 javadoc/AGENTS §7（或新建 README）加一句：**镜像含本次运行全部本模组日志（DEBUG+INFO+…），比 latest.log 多出 DEBUG；latest.log 自身不变**。**不建议**改成"只镜像 INFO"——能看到 DEBUG 正是它的价值。

---

## 三、风险与实机待验

| # | 项 | 说明 / 验证方法 |
|---|---|---|
| R1 | **首次实机是本轮修复的验收点** | `gradlew runClient` 后应同时满足：① `run/logs/devlog/<runId>/pingforit/pingforit.log` 存在且首 3 行是 header（`:151-153`）；② **`run/logs/latest.log` 里同一批 `[PingForIt]` 行仍在**（这是 additivity 修复的核心证据；不修则 latest.log 里会一行不剩）；③ 同一行在控制台也可见 |
| R2 | `-Dpingforit.devlog=true` 在非 dev 环境生效 | 该 `-D` 必须进 JVM 参数（`runClient` 要加在 Loom 的 `vmArg`/`gradle.properties`），**不是** Fabric 的模组配置。建议写进 AGENTS/README，避免下个人以为改个配置文件就行 |
| R3 | 64 MiB 上限未实测 | 代码路径已核（P11），但"真到 64 MiB 时摘除 + 一行 warn + 后续只进 latest.log"建议在 dev 里用高频 debug 打点实测一次；**不要**在正式环境压测 |
| R4 | `_archive` 归档未实测 | 需连续 11 次运行才触发（`:264`）。可手工造 10 个 `yyyy-MM-dd_HH-mm-ss` 空目录 + 跑一次验证（**只读建议**：在 `run/logs/devlog/` 造，不要动真实日志） |
| R5 | log4j 重配置后镜像丢失 | 镜像靠"运行时挂到现有 LoggerConfig"，若将来有人触发 `LoggerContext.reconfigure()`（MC 当前不会），节点被替换 → 镜像静默失效且不再重挂。低风险，记录备查 |
| R6 | `updateLoggers()` 的全局副作用 | `addLoggerAppender` 建新节点时会 `updateLoggers()`：为**所有**已注册 logger 重建 `PrivateConfig` 并 `firePropertyChangeEvent(PROPERTY_CONFIG)`（`LoggerContext.java:824-828`）。实测无行为变化（配置对象未变，只是重绑定），但它是"本模组代码触发了全局日志刷新"这一事实，建议在实机日志里确认没有异常 PropertyChange 告警 |
| R7 | 同秒双进程写同一 runId | 两个进程同一秒启动会落同一目录，且 `setAppend(false)`（`:163`）会互相截断。多开客户端才会遇到；记录备查 |
| R8 | `<Root>` 形态依赖 | 结论依赖"root 来自 XML `<Root>` 元素"。若某天换成程序化配置（无 `<Root>`），root 的默认 ctor 给的是 **additive=true**（`LoggerConfig.java:72,349-357`）→ 回归**不会发生**、修复分支也**不会触发**（`:197` 的判断为 false）。也就是说这段修复是"条件性正确"，两种 root 形态下都不会坏——但这也说明**不要**把 `:190-200` 注释里的"root is never additive"当成绝对事实 |

---

| R9 | **实机首跑已闭环（原 §四/R1 的验收点）** | 22:49:09 那次：镜像三行头正确（`# Ping For It 0.4.2-pfi-beta1` / `# side: client` / `# runId=2026-10-29_22-49-09`），且 `[PingForIt]` 行**在 latest.log 里仍在（75 行）** → **additivity 修复的实机证据**；同目录下 `pingforit-devdiag/` 共存无冲突 ✓ |
| R10 | **镜像 76 INFO vs latest.log 75 行的 1 行差（待查，不阻塞）** | 方向是"镜像多 1 行"。三种可能：① D3 的窗口（`addAppender` → `setAdditive(true)` 之间另一线程打的 1 行）；② 该行是**参数化消息**（`Global` 的 Formatter 只覆盖 `newMessage(String)`，`{}` 形态**不带** `[PingForIt] ` 前缀）→ 在 latest.log 里但 grep 不到前缀；③ 进程被强杀，latest.log 缓冲丢了最后一行（镜像有自己的 shutdown hook 先 flush）。**下次运行一条命令即可定性**：把两侧 INFO 消息体排序后 `comm -13`。三者都不影响功能 |

---

## 四、建议汇总

1. **（建议，2 行级）把 additivity 改成"先建节点再挂 appender"**，一次消掉 D3 的窗口与 volatile 问题，并且不再依赖"root 恰好是 false"这个实现细节：
   ```java
   // ModLogFile.java:183-200 替换为
   final Logger logger = (Logger) LogManager.getLogger(Global.MOD_ID);
   final Configuration configuration = logger.getContext().getConfiguration();
   if (!configuration.getLoggerConfig(Global.MOD_ID).getName().equals(Global.MOD_ID)) {
       // 先以 additive=true 建节点并发布，再挂镜像；不要事后改
       configuration.setLoggerAdditive(logger, true);
   }
   logger.addAppender(mirror);
   ```
   已在 log4j 2.26.0 实测（附录 A · Probe3）：`nodeAfterSet additive=true`、`nodeAfterAppend sameObject=true`、`mirrorHits=1 rootFileHas=true`。
2. **（建议）D1**：`:77` 加 `Locale.ROOT`。
3. **（建议）D2**：`:342` 的 `startTime` 与 `:356` 的 `jvmArgs` 哨兵对齐 `RunLayout`。
4. **（文档）** 把"生产默认关闭 + `-Dpingforit.devlog=true` 要加在 JVM 参数"写进 AGENTS §7（或新 README），并把"镜像只覆盖 `Global.LOGGER`"写明（D4）；并补上 **P18/D6 的超集说明**（镜像含 DEBUG、比 latest.log 更全，而 latest.log 自身不变）—— 这是本轮唯一必须改文档的项。
5. **（可延后）** 若确需"模组全部日志"，把 `PacketHandler` 的 logger 换成 `Global.LOGGER`；不改也不影响本任务验收。
6. **（留档）** R8 那条要写进注释：这段修复是**条件性**正确的，"root 恒为 false"只对 XML `<Root>` 形态成立。

---

## 附录 A · log4j 2.26.0 实测（可复跑）

环境：`jdk-25`，`log4j-api-2.26.0.jar` + `log4j-core-2.26.0.jar`（Gradle 缓存），配置为 MC 同款形态（`<Root level="info">` + Console + File，**不写 additivity**），logger 名 `pingforit`。

**Probe1（回归 + 修复）**
```
config=org.apache.logging.log4j.core.config.xml.XmlConfiguration
root.additive=false
BEFORE name='' additive=false              <- 配置树里没有 pingforit，getLoggerConfig 退回 root
AFTER  name='pingforit' additive=false      <- addLoggerAppender 复制了 root 的 false  ← 回归
PHASE1 mirrorHits=1 rootFileHas=false       <- 事件只进了镜像，root 的 console/file 全丢  ← 复现
[CONSOLE INFO] PHASE2-FIXED
PHASE2 mirrorHits=2 rootFileHas=true        <- setAdditive(true) 后恢复冒泡
```
**Probe2（自定义 MessageFactory 的 Global.LOGGER 会不会漏事件）**
```
installFirst: sameInstance=false mirrorHits=1 globalSeesMirror=true installerSeesMirror=true
globalFirst : sameInstance=false mirrorHits=1 globalSeesMirror=true installerSeesMirror=true
```
⇒ 两者是不同 Logger 实例但共用同一 LoggerConfig，**两种创建顺序都不会漏事件、都不会丢 `[PingForIt] ` 前缀**。

**Probe3（四.1 推荐写法）**
```
nodeAfterSet='pingforit' additive=true
nodeAfterAppend sameObject=true additive=true
[CONSOLE INFO] RECOMMENDED-PATH
mirrorHits=1 rootFileHas=true
```

> 复跑方式：`javac -cp "<api>;<core>"`，运行加 `-Dlog4j.configurationFile=file:///<xml>`（Windows 上必须是 `file:///` 形式，裸 `C:/` 会 MalformedURLException 并静默退到 DefaultConfiguration——此时 root.additive 会是 true，测的不是 MC 形态）。

---

# 附：devdiag 诊断模组功能完整性审查

任务：Lead 追加（同 task-3）　审查人：3号　**审查快照：2026-10-29 22:55（`tools/devdiag/` 未提交）**
指纹（sha256[0:12]，全 3 331 行 + harness 357 行）：`DevDiag` 322 `9A090D739E5C`｜`DevDiagWorker` 407 `D47C1D5CB4A1`｜`FrameProbe` 319 `9FAC3172DE61`｜`GcWatcher` 242 `FD20088BC752`｜`RunLayout` 257 `E80E2E2DD307`｜`SamplerProbe` 365 `FAC5506B4834`｜`ThreadProbe` 237 `99B7F6FA48BC`｜`ContextProbe` 228 `01D820BB62A4`｜`MemoryProbe` 142 `D9C9A3B2E7AE`｜`MetricsCsv` 138 `79AAA51A9AAC`｜`SummaryWriter` 153 `FFB6B23DDED3`｜`DevDiagLog` 122 `1C3B42A1CD6E`｜`FrameHistogram` 78 `66BAEC612389`｜`ErrorGuard` 61 `13593EAAF8C6`｜`SmokeHarness` 357 `46DED394CAF4`
方法：只读源码 + **独立解包 jar 核验**（`unzip -l`、`javap -verbose` 逐类、`fabric.mod.json`、class 内字符串 grep）+ 只读 harness 与 `smoke.sh`。**未**重跑 `build.sh`/`smoke.sh`（Lead 已跑 exit 0 / 57 项全绿），本报告**不采信**该结论，只核其覆盖面与可信度。

## 结论

**可以进 `run/mods` 使用**（开发期诊断模组，**不进发布包**）。**1 项需修**：GC 降级路径会写出错误数据（D1，三处，约 6 行）；**3 项建议**：`extractionGap`/任务队列无上限（D2）、物理内存无时间序列（D3）、smoke 缺数值断言（D4）。**无阻断项。**

---

## 一、通过项（逐条对照 Lead 的 5 个问题）

### A. 能否被 Fabric Loader 正常加载 —— ✅（静态证明，非自述）

| # | 项 | 结论 | 证据 |
|---|---|---|---|
| A1 | jar 结构 | 29 条目 / **24 class** / **67 537 B**（与 Lead 数字一致）；无 `META-INF/services`、无 mixin 配置 json | `unzip -l run/mods/pingforit-devdiag-1.0.0.jar` |
| A2 | 元数据 | `id=pingforit-devdiag`、`version=1.0.0`、`environment=client`、`entrypoints.client=["nx.devdiag.DevDiagMod"]`、`depends={fabricloader>=0.20.1, minecraft~26.3, java>=25, fabric-api *}` | 解包后 `fabric.mod.json` |
| A3 | 依赖无虚挂 | 声明的 fabric-api **确实在用**：`LevelExtractionEvents`/`ClientTickEvents`/`ClientLifecycleEvents`/`ClientPlayConnectionEvents`/`ClientCommands` 5 处 import | `grep ^import net.fabricmc` |
| A4 | 入口点形状 | `public final class DevDiagMod implements ClientModInitializer`，隐式 **public 无参构造** + `onInitializeClient`（javap 仅 2 方法）；方法体 `try { DevDiag.init(); } catch (Throwable)` 兜底 | `javap -verbose` + `DevDiagMod.java:13-28` |
| A5 | 字节码版本 | **24/24 全为 major 69**（Java 25） | 逐类 `javap -verbose` 统计 |
| A6 | 不耦合主模组 | `nx.pingwheel` 引用 **0**（jar 内 class 与源码双向 grep） | `grep -rl "nx/pingwheel"` 空；`grep -rn pingwheel` 源码空 |
| A7 | 无 Mixin | `fabric.mod.json` 无 `mixins` 键，jar 内无 mixin 配置 | 同 A1 |

> **但是**：Loader 真把它加载起来这件事我在本沙箱**观察不到**（起不了 MC）。A1–A7 只是"具备被加载条件"的静态证明；闭环要等实机出现 `devdiag:` 行（R1）。

### B. 会不会拖垮游戏 —— ✅（1 项遗留见 D2）

| # | 项 | 结论 | 证据 |
|---|---|---|---|
| B1 | **渲染线程零 I/O** | `onFrame()` 只有：`nanoTime` + `Stacks.setRenderThread`（volatile 写）+ 计数 + `histogram.add`（synchronized，无竞争）+ 最多一次入队。文件、排序、GC 格式化、栈 dump **全在 worker** | `FrameProbe.java:93-135`、`DevDiagWorker.java:10-14` |
| B2 | 两个线程均 daemon | `devdiag-io`（daemon + `MIN_PRIORITY`）、`devdiag-sampler`（daemon） | `DevDiagWorker.java:89-91`、`SamplerProbe.java:75-76` |
| B3 | **落盘恰好一次** | `CLIENT_STOPPING` 与 JVM shutdown hook 都调 `finish()`，`AtomicBoolean.compareAndSet` 保证一次；内部 `worker.shutdown()` 有 2 s 期限 + 1 024 任务上限 + csv/log flush | `DevDiag.java:151-156,163-192`、`DevDiagWorker.java:377-398` |
| B4 | 采集器互相隔离 | 每轮循环体 `ErrorGuard.guard`、每个 hook 内层 try/catch；`ErrorGuard.fail` **每采集器限 5 条**、且经 worker 队列（渲染线程报错不会变写盘） | `DevDiagWorker.java:102-127`、`ErrorGuard.java:12,32-55` |
| B5 | 硬上限齐备 | log **5 000 行**；GC 500 事件；hitch 200 / hitch50 100 / hitch200 60；栈 dump 100；不同栈 5 000；5 s 窗口 65 536 样本（512 KB）；采样总时长 30 min；每次 pump ≤256 | `DevDiagLog.java:21`、`GcWatcher.java:27`、`DevDiagWorker.java:16-26`、`SamplerProbe.java:35-36,115` |
| B6 | 无 `System.exit` | 全库 0（唯一一处是 harness 失败时，不进 jar） | grep |
| B7 | 永不删除用户数据 | `Files.delete` **0**，归档只 `Files.move` | grep |

### C. 数据是否真的全 —— ✅ 8/9，1 项口径缺口（D3）

| 用户要求 | 有 | 口径 / 证据 |
|---|---|---|
| 帧 p50/p95/p99/max + hitch 三档 | ✅ | CSV 每秒**精确分位**（排序）+ 5 s `frame:` 行 + summary 全局；三档既逐事件也计数（CSV `hitch50/hitch100/hitch200`、summary `hitches: >50ms= >100ms= >200ms=`） |
| 渲染线程 CPU 与状态 | ✅ | 每秒 `renderCpuMs` 列 + `threadcpu:` 行（render/server/worker CPU 增量）；5 s `threads:` 行；另 5 s `thread-state: render` 状态分布 |
| 每次 GC 的 cause + duration + 池变化 | ✅（通知路径） | `gc: type= cause= duration= generation= before= after= pools=[池 A 12.3->4.1MB, …]`，只列**变化**的池 | `GcWatcher.java:147-173` |
| 堆与分配速率 | ✅（近似，已声明） | 每秒 heap used/committed + eden/old/meta + `allocMBps`；头注释写明"区间内有 GC 时只是下界" |
| 线程 CPU 与死锁 | ✅ | 每秒/5 s + `finish()` 各查一次 `findDeadlockedThreads()`；summary `threads: total= peak= deadlocked=` |
| 折叠栈三分前缀 | ✅ | `[render]`/`[server]`/`[worker]`（`SamplerProbe.java:26,214`；harness 断言 `^\[[a-z]+\] .+ \d+# 13 · devlog 日志镜像审核（ModLogFile）+ 附：devdiag 诊断模组功能完整性

任务：task-3　审核人：3号（第 2 代）　**审查快照：2026-10-29 22:47（工作树未提交）**
快照指纹（sha256[0:12]）：`ModLogFile.java` 443 行 `2E6955ECCBE8`｜`FabricMain.java` 114 行 `9524D53A342D`
方法：只读源码 + **逐行核对 log4j-core-2.26.0-sources.jar**（`D:/Gradle/caches/.../log4j-core-2.26.0/`）+ 在 log4j 2.26.0 上用 MC 同款 `<Root>` XML **实测复现**（实验脚本与原始输出见 §附录 A；证据独立于实现者自述）。
编译复跑：`bash tools/compile-check.sh` → **main 0 error / 266 class**、**client 0 error / 86 class**（对比 11 号文档 264 / 85，+2 恰为 `ModLogFile` + `BoundedAppender` 内部类，说明本次只新增这两个 class）。

## 结论

**可以合入**（无阻断项）。**2号 声称的 additivity 回归属实，我独立复现并确认修复有效**，但**写法不是最稳妥的**（见 D3 与四.1，属"能工作但可更安全"）；另有 1 项低危偏差（runId 缺 `Locale.ROOT`，会破坏与其他 devlog 模组的目录对齐）。

对 Lead 三个问题的直接回答：

| 问题 | 结论 | 依据 |
|---|---|---|
| **(a) 结论成立吗？** | **成立**，且不是理论推演——我用 MC 同款 `<Root>` XML 复现了"日志只进镜像、不进 latest.log" | 源码：`AbstractConfiguration.java:939-957`（`:950` 复制 `lc.isAdditive()`）、`:1045-1058`（找不到就退到 root）、`LoggerConfig.java:951`+`:1140-1147`（`<Root>` 的 Builder 用**原始 boolean** `additivity`，无初始值 ⇒ **false**）。实测：PHASE1 `mirrorHits=1 / rootFileHas=false`（附录 A） |
| **(b) 会误伤用户显式 additive=false 吗？** | **不会**，判定条件恰好卡在正确位置 | `ModLogFile.java:185-186` 先算 `configured`；只有 `configured==false`（即配置树里**根本没有**名为 `pingforit` 的节点，节点是 log4j 自己 new 出来的）才改（`:190-200`）。用户显式写的 `<Logger name="pingforit" additive="false">` 会命中 `configured==true` → 不碰。补充：logger 名 `pingforit` **不含点**，`NameUtil.getSubName` 直接返回 null（`AbstractConfiguration.java:1050-1057`），所以用户的 `nx.pingwheel` 配置**根本不会**被命中——不存在"降级命中父包配置再被改掉"的路径 |
| **(c) 有更稳妥的写法吗？** | **有**：先建节点（`Configuration#setLoggerAdditive(logger,true)`）再 `logger.addAppender(...)`。我已在 2.26.0 上实测等价可用（附录 A · Probe3）：节点首次发布就是 `additive=true`，**不需要事后改**，也没有 D3 的窗口 | `Configuration.java:91` 声明、`AbstractConfiguration.java:994-1007` 实现（内部 `new LoggerConfig(name, lc.getLevel(), additive)` + `updateLoggers()`） |

---

## 一、通过项

| # | 项 | 结论 | 证据（本地行号 ↔ log4j 行号） |
|---|---|---|---|
| P1 | **additivity 回归属实** | **成立**。`<Root>` 节点由 `LoggerConfig.RootLogger.Builder` 构建，其 `additivity` 字段是**无初始值的 boolean ⇒ false**；`addLoggerAppender` 新建子节点时把 `lc.isAdditive()`（此处 = root 的 false）复制过去 ⇒ 新节点不向上冒泡 | `LoggerConfig.java:951,1140-1147`；`AbstractConfiguration.java:950`；实测 PHASE1（附录 A） |
| P2 | **修复有效** | `setAdditive(true)` 立即生效：`additive` 是普通字段、`logParent` 直接读它，**没有 PrivateConfig 快照**，不需要再触发一次 `updateLoggers()` | `LoggerConfig.java:72,566-568,796-800`；实测 PHASE2 `mirrorHits=2 / rootFileHas=true` |
| P3 | 不误伤显式配置 | 见上文 (b)；判定用"节点名是否等于自身"（`:185-186`），语义正确 | `ModLogFile.java:185-186,190-200` |
| P4 | **作用域隔离** | appender 只挂到名为 `pingforit` 的 `LoggerConfig`（`:183-188`）；root 的 appender 集合**零改动**（`addLoggerAppender` 只对 `getLoggerConfig(name)` 的节点动手）。实测 PHASE1：事件进了镜像、**没**进 root 的 file appender，同时 root 自己的 appender 仍在（PHASE2 立刻恢复）——反向证明 root 未被破坏 | `AbstractConfiguration.java:939-957`；`Logger.java:355-357` |
| P5 | 其它 logger 进不来 | 只有 `pingforit` 与其**点号子名**（`pingforit.xxx`）会向上冒泡到这里；全库 `LogManager.getLogger` 只有 `Global.java:27`（`pingforit`）与 `PacketHandler.java:14`（类名 → 不是本节点） | grep 实测；`NameUtil` 规则见 `AbstractConfiguration.java:1050-1057` |
| P6 | **开关在创建目录之前** | `enabled()` 在 `installUnchecked()` **之前**判定（`:106` → `:117`），生产环境不会创建任何 `logs/devlog` 目录；判定本身包了 try/catch（`:128-137`），FabricLoader 不可用时退回系统属性 | `ModLogFile.java:106-114,128-137,139-146` |
| P7 | **幂等** | volatile 标志 + 锁内二次检查，且 `installed=true` 在**干活之前**写（`:104`）：一次失败不会重试；并发调用第二个线程阻塞后看到 true 直接返回 | `ModLogFile.java:84,94-104` |
| P8 | **失败降级（4 类）** | ①目录/IO 失败 → 外层 `catch(Throwable)` 一行 warn（`:116-124`）；②appender 打不开 → `FileAppender.Builder.build()` **返回 null**（`FileAppender.java:88-90,115-116`：`isValid()` 失败或 `FileManager` 为 null 即 null）→ 本地 `:171-173` 抛 → 同一 catch；③`session.txt` 失败 → `CREATE_NEW` 以外的异常一行 warn（`:221-225`）；④保留策略移动失败 → 一行 warn（`:275-277,294-296`）。**四处都不影响启动** | `ModLogFile.java:116-124,171-173,216-226,250-278,280-297` |
| P9 | **保留策略只移动不删除** | 全文件**没有** `Files.delete`/`deleteIfExists`（grep 实测，唯一写入动作是 `:293 Files.move`）；只处理匹配 `\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}` 的目录（`:78,256`），`_archive` 天然被排除；当前 runId 显式排除（`:257`）；`excess = 已有 + 1 - 10`（`:264`）⇒ 保活目录数恰好 10 | `ModLogFile.java:250-297`；目录约定见 `tools/devdiag/src/main/java/nx/devdiag/RunLayout.java:188-230` |
| P10 | 同名冲突 `-2/-3` | `for (suffix=2; exists; suffix++)` 逐个试到 `-100` 后放弃并 warn（`:284-290`），不覆盖 | `ModLogFile.java:280-297` |
| P11 | **64 MiB 精确字节 + 正确顺序** | 计数 = header 字节 + `layout.toByteArray(event).length`（与真实写入同一 layout 实例），判据 `bytesWritten + size > MAX` ⇒ 文件**永不超过**上限；超限顺序严格为 **置 capped → 摘除 → stop(delegate) → stop(self) → warn**（`:410-413,420-441`）。`capped` 在读之前置位，所以**摘除失败也不会递归** | `ModLogFile.java:404-441`；`AppenderControl.java:104-113`（"Recursive call to appender" 由 `ThreadLocal` 在同线程检测，**先摘再 warn 是必须的**）、`:139`（对 stopped appender 追加会报 non-started 错误 ⇒ `:433` 先 stop 再 warn 也对） |
| P12 | **runId 算法与约定一致** | `ProcessHandle.current().info().startInstant()` → `LocalDateTime.ofInstant(systemDefault)` → `yyyy-MM-dd_HH-mm-ss`，`orElseGet(LocalDateTime::now)` 兜底 | `ModLogFile.java:331-336,345-351` ↔ `RunLayout.java:84-97`（逐语句一致；**缺 `Locale.ROOT`** 见 D1） |
| P13 | `session.txt` 语义正确 | `CREATE_NEW` + `FileAlreadyExistsException` 静默跳过（谁先写谁赢），与 devdiag 完全一致 | `ModLogFile.java:216-226` ↔ `RunLayout.java:133-148` |
| P14 | **纯净性** | `src/main` 下 `net.minecraft.client` / `com.mojang.blaze3d` 命中 **0**；未新增依赖（`build.gradle` 无 log4j，来自 Minecraft 自带 `log4j-core 2.26.0`，编译期已可用） | grep 实测 + `compile-check` 0 error |
| P15 | **线程安全** | `installed`/`attachedLogger` 均 volatile；`bytesWritten` 受 `synchronized append` 保护；shutdown hook **daemon**（`:305`）且全包 try/catch（`:300-309,312-321`） | `ModLogFile.java:84,86,301-309,404,420-441` |
| P16 | 自定义 MessageFactory 不受影响 | `Global.LOGGER` 用 `FormattedMessageFactory` 加 `[PingForIt] ` 前缀，而 `:183` 用单参 `getLogger` 另取实例。**实测**：两者是不同 Logger 实例（`sameInstance=false`）但**共用同一个 LoggerConfig**，两个创建顺序下镜像都收到事件、前缀都没丢 | 附录 A · Probe2（`mirrorHits=1 globalSeesMirror=true`，两种顺序） |
| P17 | 编译 | 已复跑：main 0 / **266** class、client 0 / **86** class | `tools/compile-check.sh` |

> 说明：`:183` 另取一个 Logger 实例虽无功能问题，但会让 log4j 为同名 logger 建两个实例（P16 已证无害），属可接受开销。

---

## 二、偏差项（含严重度）

### D1 · 低危 · 建议修｜runId 少了 `Locale.ROOT`，会破坏跨模组目录对齐
- **本地**：`ModLogFile.java:77` `DateTimeFormatter.ofPattern(RUN_ID_PATTERN_TEXT)`——**未指定 Locale**，默认取 `Locale.getDefault(FORMAT)`；`DecimalStyle` 随 locale 的 numbering system 变。
- **约定参考实现**：`RunLayout.java:45-46` 明确写 `ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)`。
- **后果**（只在阿拉伯/波斯/泰等**非 ASCII 数字** locale 触发，如 `-Duser.language=ar`）：
  1. 本模组与 devdiag 在同一次运行里算出**不同 runId** → 各写各的目录，"一次运行一个目录"的约定失效；
  2. 产出的目录名不匹配 `:78` 的 `\d` 正则（Java 默认 `\d` = `[0-9]`）→ **永远不会被保留策略归档**，这类目录会无限堆积。
- **修法（1 处）**：`:77` 加 `Locale.ROOT`（与 `RunLayout.java:46` 逐字一致）。
- 备注：主路径（中文/英文 Windows）不受影响，属"小概率但会静默留下垃圾目录"的坑。

### D2 · 低危 · 建议修｜`session.txt` 的 `startTime`/`jvmArgs` 与共享约定口径不一致
- `session.txt` 是**共享文件**，`CREATE_NEW` 谁先写谁赢（P13），所以同一份文件的字段格式取决于哪个模组先跑。
- `startTime`：本地 `:342` 用 `DateTimeFormatter.ISO_OFFSET_DATE_TIME`（`2026-10-29T22:47:03.123+08:00`）；devdiag 用 `yyyy-MM-dd HH:mm:ss`（`RunLayout.java:47-48,153`）。
- `jvmArgs` 空值哨兵：本地 `:356` 为 `"none"`/`"unknown"`；devdiag 为 `"(none)"`/`"(unavailable)"`（`RunLayout.java:169-175`）。同一文件会出现两种口径。
- **影响**：任何按固定格式解析 `session.txt` 的采集/脚本（含 Lead 的诊断流程）在"本模组先跑"的那次运行里会拿到带 `T`/`+08:00` 的时间戳。
- **修法**：`:342` 改为 `yyyy-MM-dd HH:mm:ss`（`Locale.ROOT`），`:356` 哨兵对齐 `RunLayout`。

### D3 · 低危 · 建议改顺序｜additivity 是在节点**已经发布之后**才改的
- `logger.addAppender(mirror)`（`:188`）内部会 `updateLoggers()` 把新节点**发布**给所有线程（`AbstractConfiguration.java:950-955`、`LoggerContext.java:824-828`），之后 `:196-199` 才 `setAdditive(true)`。
- 两个后果：
  1. **窗口**：发布到修正之间的极短窗口内，其它线程打的 `pingforit` 日志仍不冒泡（丢几行 dev 日志，无功能影响）；
  2. **JMM**：`LoggerConfig.additive` 是**非 volatile** 字段（`LoggerConfig.java:72`），`logParent` 直读（`:797`）；`:198` 的写入没有 `updateLoggers`/`volatile` 的发布边，理论上其它线程可能长期读到旧值（x86 上实际不可见，属理论风险）。
- **不是"必须现在改"**：P2/P3 已证当前写法可用。修法见四.1（顺带把窗口和 volatile 问题一起消掉）。

### D4 · 观察（非缺陷）｜镜像只覆盖 `pingforit` 一个 logger，`PacketHandler` 的日志不进镜像
- `PacketHandler.java:14` 用 `LogManager.getLogger(PacketHandler.class)`（名字 `nx.pingwheel.common.network.PacketHandler`）→ 不在本节点上，**不会**进 `pingforit.log`。
- 类的 javadoc（`:39-42`）已诚实写明"the `pingforit` logger"，所以**不是实现错误**；但如果 Lead/1号 的预期是"模组全部日志一个文件"，这是一处口径缺口。
- 影响面：网络包相关的告警仍只在 latest.log 里。**要么改 PacketHandler 用 `Global.LOGGER`，要么在文档里写明"镜像仅覆盖 Global.LOGGER 的行"。**

### D5 · 观察｜生产（关闭）路径仍会触发 `Global` 类初始化
- `:109` 在 disabled 分支里调 `Global.LOGGER.debug(...)`：即使只打 debug，`Global.LOGGER` 的**表达式求值**就会初始化 `Global` 类 → `IPlatformContextService.INSTANCE`（ServiceLoader）提前加载。
- 实测无害（`onInitialize` 阶段 FabricLoader 已就绪，且 `CommonServer` 紧接着也要用它），但"安装点在一切之前 / 关闭时零副作用"这个说法要打个折。
- **可选修法**：disabled 分支直接 `return`（连 debug 都不打），或改用 `System.getLogger`。

---

## 三、风险与实机待验

| # | 项 | 说明 / 验证方法 |
|---|---|---|
| R1 | **首次实机是本轮修复的验收点** | `gradlew runClient` 后应同时满足：① `run/logs/devlog/<runId>/pingforit/pingforit.log` 存在且首 3 行是 header（`:151-153`）；② **`run/logs/latest.log` 里同一批 `[PingForIt]` 行仍在**（这是 additivity 修复的核心证据；不修则 latest.log 里会一行不剩）；③ 同一行在控制台也可见 |
| R2 | `-Dpingforit.devlog=true` 在非 dev 环境生效 | 该 `-D` 必须进 JVM 参数（`runClient` 要加在 Loom 的 `vmArg`/`gradle.properties`），**不是** Fabric 的模组配置。建议写进 AGENTS/README，避免下个人以为改个配置文件就行 |
| R3 | 64 MiB 上限未实测 | 代码路径已核（P11），但"真到 64 MiB 时摘除 + 一行 warn + 后续只进 latest.log"建议在 dev 里用高频 debug 打点实测一次；**不要**在正式环境压测 |
| R4 | `_archive` 归档未实测 | 需连续 11 次运行才触发（`:264`）。可手工造 10 个 `yyyy-MM-dd_HH-mm-ss` 空目录 + 跑一次验证（**只读建议**：在 `run/logs/devlog/` 造，不要动真实日志） |
| R5 | log4j 重配置后镜像丢失 | 镜像靠"运行时挂到现有 LoggerConfig"，若将来有人触发 `LoggerContext.reconfigure()`（MC 当前不会），节点被替换 → 镜像静默失效且不再重挂。低风险，记录备查 |
| R6 | `updateLoggers()` 的全局副作用 | `addLoggerAppender` 建新节点时会 `updateLoggers()`：为**所有**已注册 logger 重建 `PrivateConfig` 并 `firePropertyChangeEvent(PROPERTY_CONFIG)`（`LoggerContext.java:824-828`）。实测无行为变化（配置对象未变，只是重绑定），但它是"本模组代码触发了全局日志刷新"这一事实，建议在实机日志里确认没有异常 PropertyChange 告警 |
| R7 | 同秒双进程写同一 runId | 两个进程同一秒启动会落同一目录，且 `setAppend(false)`（`:163`）会互相截断。多开客户端才会遇到；记录备查 |
| R8 | `<Root>` 形态依赖 | 结论依赖"root 来自 XML `<Root>` 元素"。若某天换成程序化配置（无 `<Root>`），root 的默认 ctor 给的是 **additive=true**（`LoggerConfig.java:72,349-357`）→ 回归**不会发生**、修复分支也**不会触发**（`:197` 的判断为 false）。也就是说这段修复是"条件性正确"，两种 root 形态下都不会坏——但这也说明**不要**把 `:190-200` 注释里的"root is never additive"当成绝对事实 |

---

## 四、建议汇总

1. **（建议，2 行级）把 additivity 改成"先建节点再挂 appender"**，一次消掉 D3 的窗口与 volatile 问题，并且不再依赖"root 恰好是 false"这个实现细节：
   ```java
   // ModLogFile.java:183-200 替换为
   final Logger logger = (Logger) LogManager.getLogger(Global.MOD_ID);
   final Configuration configuration = logger.getContext().getConfiguration();
   if (!configuration.getLoggerConfig(Global.MOD_ID).getName().equals(Global.MOD_ID)) {
       // 先以 additive=true 建节点并发布，再挂镜像；不要事后改
       configuration.setLoggerAdditive(logger, true);
   }
   logger.addAppender(mirror);
   ```
   已在 log4j 2.26.0 实测（附录 A · Probe3）：`nodeAfterSet additive=true`、`nodeAfterAppend sameObject=true`、`mirrorHits=1 rootFileHas=true`。
2. **（建议）D1**：`:77` 加 `Locale.ROOT`。
3. **（建议）D2**：`:342` 的 `startTime` 与 `:356` 的 `jvmArgs` 哨兵对齐 `RunLayout`。
4. **（文档）** 把"生产默认关闭 + `-Dpingforit.devlog=true` 要加在 JVM 参数"写进 AGENTS §7（或新 README），并把"镜像只覆盖 `Global.LOGGER`"写明（D4）。
5. **（可延后）** 若确需"模组全部日志"，把 `PacketHandler` 的 logger 换成 `Global.LOGGER`；不改也不影响本任务验收。
6. **（留档）** R8 那条要写进注释：这段修复是**条件性**正确的，"root 恒为 false"只对 XML `<Root>` 形态成立。

---

## 附录 A · log4j 2.26.0 实测（可复跑）

环境：`jdk-25`，`log4j-api-2.26.0.jar` + `log4j-core-2.26.0.jar`（Gradle 缓存），配置为 MC 同款形态（`<Root level="info">` + Console + File，**不写 additivity**），logger 名 `pingforit`。

**Probe1（回归 + 修复）**
```
config=org.apache.logging.log4j.core.config.xml.XmlConfiguration
root.additive=false
BEFORE name='' additive=false              <- 配置树里没有 pingforit，getLoggerConfig 退回 root
AFTER  name='pingforit' additive=false      <- addLoggerAppender 复制了 root 的 false  ← 回归
PHASE1 mirrorHits=1 rootFileHas=false       <- 事件只进了镜像，root 的 console/file 全丢  ← 复现
[CONSOLE INFO] PHASE2-FIXED
PHASE2 mirrorHits=2 rootFileHas=true        <- setAdditive(true) 后恢复冒泡
```
**Probe2（自定义 MessageFactory 的 Global.LOGGER 会不会漏事件）**
```
installFirst: sameInstance=false mirrorHits=1 globalSeesMirror=true installerSeesMirror=true
globalFirst : sameInstance=false mirrorHits=1 globalSeesMirror=true installerSeesMirror=true
```
⇒ 两者是不同 Logger 实例但共用同一 LoggerConfig，**两种创建顺序都不会漏事件、都不会丢 `[PingForIt] ` 前缀**。

**Probe3（四.1 推荐写法）**
```
nodeAfterSet='pingforit' additive=true
nodeAfterAppend sameObject=true additive=true
[CONSOLE INFO] RECOMMENDED-PATH
mirrorHits=1 rootFileHas=true
```

> 复跑方式：`javac -cp "<api>;<core>"`，运行加 `-Dlog4j.configurationFile=file:///<xml>`（Windows 上必须是 `file:///` 形式，裸 `C:/` 会 MalformedURLException 并静默退到 DefaultConfiguration——此时 root.additive 会是 true，） |
| 上下文快照 | ✅ | `context: session: …`（JVM/系统）+ `context: world: …`（维度/实体/区块/粒子），走队列 |
| **系统 CPU 与物理内存** | ⚠️ **半** | **CPU** 有每秒列（`processCpuPct,systemCpuPct`）；**物理内存只有 2 个点**（session 快照 + summary），CSV 无该列 → **D3** |
| JVM 参数与类加载 | ✅ | `context: session: jvmArgs=[…]`、`classes: loaded= total=`；summary `jvm: loadedClasses= totalLoaded= jitTotalCompileMs= physicalMemFreeMB= physicalMemTotalMB=` |

**口径不符必须点明的 4 处**（都不是缺陷，但**不要互相校验**）：
1. **summary 的全局 p50/p95/p99 是 1 ms 分桶**（`FrameHistogram.java:9-11,32-48`），CSV 每行是当秒排序**精确值** → 两者不是同一个统计量。分桶误差 <1 ms，已在类头注释声明。
2. **summary 的 `max` 与三档计数包含 >5 000 ms 的溢出帧**（`overflow` 进 `maxMs()`/`above()`，但不进分位桶）→ 一次 20 s 卡死会抬高 `max`，却不影响 p99。
3. **`allocMBps` 是两次采样差的下界**（`MemoryProbe` 头注释已声明）。
4. **`hitch-stack:` 是事后 safepoint 快照**（`DevDiagWorker.java:332-334` 注释已写明），不是卡顿指令现场。

### D. 目录约定 v2 —— ✅ 全过

| 项 | 结论 |
|---|---|
| 路径 | ✅ `<gameDir>/logs/devlog/<runId>/pingforit-devdiag/`（`RunLayout.java:64-82`） |
| runId | ✅ `ProcessHandle…startInstant()` → systemDefault → `yyyy-MM-dd_HH-mm-ss`，**且带 `Locale.ROOT`**（`RunLayout.java:45-46,85-97`）——比主模组 `ModLogFile` 更规范（正文 D1） |
| session.txt | ✅ `CREATE_NEW` + `FileAlreadyExistsException` 静默（`:133-148`），11 键齐全。**实机已验证协调生效**：本次主模组先写（session.txt 里 modVersion=0.4.2-pfi-beta1），devdiag 侧识别为 “session.txt already present (another mod was first)”（pingforit-devdiag/devdiag.log:5），四件套齐全 |
| 保留 10 个 / 只移动 | ✅ `while (runs.size()+1 > KEEP_RUNS)`（`:188-214`），当前 run 排除（`:200`），`-2/-3` 冲突后缀（`:216-230`），**`Files.delete` 全库 0** |

### E. smoke 的可信度 —— ✅ 可信但非全覆盖

**可信的部分**：驱动的是**真实采集器**，只对 `DevDiag` 组合根做反射注入（`SmokeHarness.java:49-76`）；断言落在**产出文件**上（黑盒）；真的触发两次 `System.gc()`（`:157-163`）→ GC 行走的是真实 `NotificationListener`；保留策略同时验了"归档目录存在"+"根目录已消失"（`:244-250`），能证明是**移动**不是删除；cap/时间戳/CSV 列数/折叠格式都有断言。

**三类盲区**：
1. **降级路径完全没测**：没有故障注入 → 目录不可写、GC fallback、cap 触发、`/devdiag` 命令全不在覆盖内（**D1 就是这么漏掉的**）。
2. **只断言"存在"，不断言"数值合理"**：没有 `p50<=p95<=p99`、`renderCpuMs` 范围、`allocMBps` 非负、`deadlocked=0` 之类 sanity check（**D4**）。
3. **开销数字的口径**：3.67 µs/帧是 harness 里"连续调 `onFrame()`"的合成循环测的（`:174-180`），**不是真实渲染管线**。它能证明"钩子本身很便宜"，不能证明"游戏里只多 3.67 µs"；2 % 预算门（`:181-187`）是合理代理。
4. 轻度自证：`check(csvLines.get(0).equals(MetricsCsv.HEADER))` 拿生产常量当 oracle（只用列数做了独立校验）；`RunLayout.runDirs()` 是 harness 专用辅助方法（`RunLayout.java:244-255`），与保留策略共用同一套过滤逻辑 —— 邻接的直接 FS 断言补上了这层循环论证。

---

## 二、偏差项（含严重度）

### D1 · 中低 · **需修**｜GC 降级路径写出错误数据（三处）
- 触发：JVM 上没有任何 `NotificationEmitter`（`GcWatcher.java:64` `pollingFallback = registered == 0`）。正常 HotSpot 不走，但**降级路径存在的意义就是"不常见的时候"**。
- 三处错：
  1. **`:112` 把累计时间当单次时长**：`final long pauseMs = Math.max(0L, bean.getCollectionTime());` 是**累计值**，随后 `:115 totalPauseMs += pauseMs` 每 500 ms 累加一次 → summary 的 `totalPause`/`maxPause` 变成无意义大数；
  2. **`:113` 写死 young**：`youngEvents += delta` + 行内 `generation=young` → 老年代收集被记成 young，`young=/old=` 分裂失真；
  3. **`:119-121` 字面量占位符**：`before=?MB after=?MB` 是硬编码字符串，看起来像"采到但解析失败"，实际根本没采。
- 影响：仅降级模式；但"诊断给错数"比"没数"更坏——下一任排障会拿它当依据。
- **修法（约 6 行）**：`pauseMs` 改为 `getCollectionTime()` 的**增量**；`generation` 用 `looksOld(bean.getName(), "")`；`before/after` 整段删掉（连 `?MB`）。

### D2 · 低 · 建议修｜`extractionGap` 与任务队列都没有上限
- `extractionGap`（`DevDiagWorker.java:178-184`）**无任何计数上限**：`gapLines++` 后无条件写；触发源是 `END_EXTRACTION` 间隔超阈值（`FrameProbe.java:124`）。长时间 alt-tab / 卡加载时每次超阈值都入队一条。
- 其余 hitch 线（`:133-176`）日志有上限，但 **cap 判断在任务体内**，入队发生在判定之前 → 上限只省了写盘，没省队列。
- 队列本身无界（`:32` `ConcurrentLinkedQueue`，`:197-199`），下游只有 `DevDiagLog` 5 000 行兜底。
- 影响：极端场景内存缓涨（量级远小于帧开销，实际风险低）。**修法**：把 cap 前移到入队处，或给队列加长度上限 + 丢弃计数。

### D3 · 低 · 口径缺口｜物理内存没有时间序列
- 用户清单里的"系统 CPU 与物理内存"：CPU 有每秒列，**物理内存只有 2 个点**（`ContextProbe.java:83,93,105` 的 session 快照 + summary 末次），`MetricsCsv.HEADER`（`:21-24`）无对应列。
- **建议**：CSV 加 `physFreeMB` 一列，或在 README 明写"物理内存只取首末两次"。

### D4 · 低 · 建议｜smoke 缺数值合理性断言
- 建议加 4 条：`p50<=p95<=p99`、`renderCpuMs ∈ [-1,1000)`、`allocMBps >= 0`、`deadlocked=0`。这 4 条能拦住"字段存在但内容全 0/-1"这类回归。

### D5 · nit｜`RunLayout.moveToArchive` 冲突循环无上限
- `:221-225` `while (Files.exists(target))` 无熔断；`ModLogFile` 同位置有 `>100` 熔断（`ModLogFile.java:285-289`）。实际不可能触发，建议两处对齐。

---

## 三、风险与实机待验

| # | 项 | 说明 |
|---|---|---|
| R1 | ~~未实机加载~~ → **已实机加载（闭环）** | **证据**：`run/logs/devlog/2026-10-29_22-49-09/pingforit-devdiag/devdiag.log:2` = `22:49:20.932 devdiag: runId=2026-10-29_22-49-09`，四件套齐全（`devdiag-summary.txt:31-32` 有 session.txt 块）。**只剩 `/devdiag` 命令（R6）未验** |
| R2 | dev-mapped jar 只能用于 `run/` | javac 按开发期 named 类名编译，**不可**进整合包；放错地方的表现是 `NoSuchMethodError`。README 已声明 |
| R3 | 3.67 µs 的口径 | 合成循环测的，不含真实渲染管线（见 §E.3） |
| R4 | 故障注入缺失 | 目录不可写 / GC fallback / cap 触发 / `/devdiag start\|stop\|dump` 全未测 |
| R5 | 采样 30 min 上限 | `SamplerProbe.MAX_RUNTIME_NANOS` 到点自动停采样（`:115`）；长会话后半程没有折叠栈，README 宜写明 |
| R6 | `/devdiag` 命令 | `DevDiagCommand`（96 行）本次未逐行核，harness 也没覆盖 → **待验证** |

---

## 四、建议汇总（按性价比）

1. **修 D1**（约 6 行）：GC fallback 的时长/代际/占位符 —— 唯一"会产出错误结论"的问题，建议合入前改。
2. **加 D4 的 4 条断言**（约 4 行）：最低成本的回归护栏。
3. **D2 前移 cap**（可选）：队列上限，健壮性。
4. **D3 CSV 加 `physFreeMB`**（1 行 + 1 列）：补齐"系统 CPU 与物理内存"的时间序列。
5. **README 补三句**：dev-mapped jar 边界、物理内存只取两次、采样 30 min 上限。
6. **跨模组对齐**：以 devdiag 为基准改主模组 —— `ModLogFile` 的 runId 补 `Locale.ROOT`、`session.txt` 的 `startTime`/`jvmArgs` 对齐 `RunLayout`（正文 D1/D2）。改完两个模组才算真正"共用一套目录约定"。

