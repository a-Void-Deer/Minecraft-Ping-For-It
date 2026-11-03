# 03 · P0 验收日志审计（2026-11-03 22:1x 三轮运行）

- 编写：1号 repo-analyst｜日期：2026-11-03 22:2x｜对象：`run/logs/latest.log`、`2026-11-03-7.log.gz`、`2026-11-03-6.log.gz`（+ 各自 `debug*.log`）
- 口径：**只用三种措辞** —— ✅ 通过（证据行号）／❌ 不通过（证据 + 归属）／⏸ 未验证（原因）
- 相关：`docs/restructure/02-p0-in-game-checklist.md`（P0 清单）、`docs/reviews/24-fidelity-constraint-audit.md` #19

## 0. 结论速览

| 项 | 结论 |
|---|---|
| **A. 错误面** | ✅ **通过** —— 三轮各 **3 条 ERROR**，全部是 Mojang 账户/Realms 噪声；**0 条来自 `pingforit`**、**0 条 `Mixin apply failed`**、**0 条 `ClassNotFoundException: nx.pingwheel`**（详见 §2.1） |
| **B. WARN 归因** | ✅ **通过** —— R3 6 条 / R2 5 条 / R1 7 条，来源为 Sodium(DH 可选兼容)、PerfOS、voicechat、DH、Mod Menu(外部包图标)；**0 条来自 `pingforit`**（§2.2） |
| **C. P0 逐项** | 混合：**V4 / V6 / V8 / V9 / V16 / V17 ✅**；V12 **日志级 ✅、视觉待看**；**V13 / V15 ⏸ 未验证（OIT 从未开启）**；**V18 ⏸ 未验证（无专用服务器痕迹）**（§3） |
| **D. V18（runServer）** | ⏸ **未验证** —— 三份日志 **0 条** `Starting minecraft server` / `Done (`；出现的 `Stopping server` 是**集成服务器**（单人世界卸载），不是专用服务器（§3 表内 V18 行） |
| **E. 崩溃报告** | ❌ **有 1 条本模组相关崩溃，但不在本轮**：`run/crash-reports/crash-2026-11-03_20.05.47-client.txt`（`NoClassDefFoundError: nx/pingwheel/common/client/hud/WheelHudRenderer`，来自 20:05 那次运行）；**20:58 起三轮无新增崩溃**，该文件即最新（§2.3） |
| **F. 存疑** | 3 条值得记：R3 无 `Stopping!` 收尾、OIT 从未开启（V13/V15 空转）、DH 的 `end_of_frame` post effect 警告（§4） |

## 1. 逐次运行时间线

> **踩坑提示（很关键）**：`run/logs/*.log.gz` 的 **mtime 是「轮转时刻」= 下一次启动的时间**，不是那次运行的结束时间。按 mtime 归类会把运行映射错一位（例如 `-5` 的 mtime 是 20:58，但内容其实是 20:17 那次）。下表按**内容时间**归类。

| # | 归档文件（内容区间） | 启动 → `Sound engine started` | 结束 | `send ping` : `marker created` | 备注 |
|---|---|---|---|---|---|
| **R0** | `2026-11-03-3.log.gz`（20:05:13–20:05:47） | — | **崩溃中止** | — | 就是 §2.3 那次 `WheelHudRenderer` CNF，属重构中间态 |
| — | `2026-11-03-4.log.gz`（20:06:20–20:08:47）、`-5.log.gz`（20:17:08–20:19:55） | 20:17:28（`:187`） | 20:19:55 `Stopping!` | 28 : 28 | 重构前/过渡期，仅作对照 |
| **R1** | `2026-11-03-6.log.gz` + `debug-2.log.gz`（20:58:41–21:00:41） | 20:58:41 → 20:59:07（`:187`，**26 s**） | 21:00:41 `Stopping!`（`:473`） | **22 : 22** | 重构后第一轮基线 |
| **R2** | `2026-11-03-7.log.gz` + `debug-1.log.gz`（22:13:52–22:15:10） | 22:13:52 → 22:14:17（`:187`，**25 s**） | 22:15:10 `Stopping!`（`:423`） | **13 : 13** | |
| **R3** | `latest.log` + `debug.log`（22:15:51–22:21:40） | 22:15:51 → 22:16:18（`:187`，**27 s**） | 22:21:40 末行 DH `Closing all [3] databases...`（**无 `Stopping!`**） | **43 : 43** | 含两个世界会话（22:16:42 / 22:21:39 各一次 `Stopping server`，均为集成服务器） |

- `run/logs/devlog/` **不存在** → devlog 镜像未开，本审计只依赖 `latest.log` / `debug.log`。

## 2. 错误 / 警告明细

### 2.1 ERROR（三轮完全同构，全部为账户/Realms 噪声）

| 行号（R3 / R2 / R1） | 原文 | 归属 |
|---|---|---|
| `140` / `141` / `117` | `Failed to fetch user properties` | **Mojang 账户（开发环境未登录，401）** |
| `177` / `173` / `173` | `Failed to fetch Realms feature flags` | **Realms 噪声** |
| `202` / `202` / `202` | `Couldn't connect to realms` | **Realms 噪声** |

- **计数口径**：每轮 ERROR 级 **3 条**；`grep -c ERROR` 会数出 4 条，因为 R3`:127`、R2`:128`、R1`:128` 是 `Caused by: MinecraftClientHttpException[…HTTP_ERROR…]` 的**续行**。
- **与本模组的交集：0**。三轮 `pingforit` 相关的 WARN/ERROR **均为 0 条**，`Mixin apply failed` **0 条**，`ClassNotFoundException: nx.pingwheel` **0 条**。

### 2.2 WARN（全部列出 + 所属 mod）

| 来源（日志括号里的标签） | R3 行号 | R2 | R1 | 原文要点 | 归属 |
|---|---|---|---|---|---|
| `FabricLoader/Mixin` | `85` | `85` | `85` | `Error loading class: net/caffeinemc/mods/sodium/client/util/SodiumChunkSection (ClassNotFoundException)` | **Distant Horizons 的可选 Sodium 兼容 mixin**；本机没装 Sodium → 正常 |
| `PerfmonDisabled` | `89` | `89` | `89` | `Invalid registry value type detected for PerfOS counters…` | Windows 性能计数器（系统层） |
| `voicechat` | `95` | `92` | `92` | `Running in debug mode - Don't leave this enabled in production!` | Simple Voice Chat 自身配置 |
| `Initializer` | `120` | `132` | `150` | `Distant Horizons: G1 Garbage collector detected.` | DH 的 GC 提示 |
| `Minecraft` | `256` | `255` | `255` | `Requested post effect does not exist: minecraft:end_of_frame` | **DH** 请求的 post effect 名（见 §4-3） |
| `Mod Menu \| FabricMod` | `368` | — | — | `Warning! Mod voicechatprofiles has a broken icon, loading default icon` | 外部 mod（`voicechatprofiles`）资源问题 |
| `RegionFileStorageExternalCache` | — | — | `301` | `Unable to access Minecraft's chunk cache… Falling back to DH's implementation` | **DH** 内部回退 |
| `Minecraft`（DH-World Gen 线程） | — | — | `357` | `Skipping Entity with id minecraft:zombie_villager` | **DH** worldgen 噪声 |

- 合计 **R3=6 / R2=5 / R1=7**，与逐条枚举一致；**没有任何一条标签是 `pingforit`**（也交叉核过 `debug.log` 里 133 条 `[PingForIt]`，含 DEBUG 级 23 条，**0 条 WARN/ERROR**）。

### 2.3 Exception / Caused by（全是账户栈，不是崩溃）

| 行号（R3 / R2 / R1） | 内容 | 归属 |
|---|---|---|
| `141`–`151` / `142`–`152` / `118`–`128` | `InvalidCredentialsException: Status: 401` → `Caused by: MinecraftClientHttpException[…/player/attributes…]` | Mojang 账户（未登录） |
| `176`–`178` / `172`–`174` / `172`–`174` | `Could not authorize you against Realms server: Failed to parse into SignedJWT: FabricMC`（**INFO 级**）+ `RealmsServiceException` | Realms（dev 环境无签名） |
| `201`–`203` / `201`–`203` / `201`–`203` | 同上，第二次（`Couldn't connect to realms` 的栈） | Realms |

**崩溃报告（唯一与本模组相关的一条，时间在本次三轮之前）**

- 文件：`run/crash-reports/crash-2026-11-03_20.05.47-client.txt`｜时间 **20:05:47**｜首行 `---- Minecraft Crash Report ----` / `// Who set us up the TNT?` / `Description: Unexpected error`
- 关键栈：`java.lang.NoClassDefFoundError: nx/pingwheel/common/client/hud/WheelHudRenderer` → `at nx.pingwheel.common.CommonClient.onJoinServer(CommonClient.java:102)`；`Caused by: ClassNotFoundException: …WheelHudRenderer`
- 归属：**重构中间态**（client 源集类没进当时的 classpath），对应运行是 `2026-11-03-3.log.gz`（20:05:13–20:05:47）。**R1/R2/R3 三轮均无该异常**，其后也没有新增崩溃报告（`20.05.47` 是最新一份）。

## 3. P0 逐项结论（日志能判的部分）

| 项 | 结论 | 证据 |
|---|---|---|
| **V4 启动干净** | ✅ **通过** | 三轮 `Sound engine started` 都在 `:187`：R3 22:15:51→22:16:18（27 s）、R2 25 s、R1 26 s（均无「卡加载条」）；`Mixin apply failed` **0**、`ClassNotFoundException: nx.pingwheel` **0**；`[PingForIt] Init`（R3 `:93`）+ `Loaded config type=ServerConfig`（`:94`）+ `ClientConfig`（`:130`）+ `ping outline pipeline registered…`（`:131`）+ `block outline pass registered…`（`:132`）五条齐全 |
| **V6 资源包索引** | ✅ **通过（日志级）** | `debug.log:1713`/`:1770`、`debug-1.log.gz:1712`/`:1769`、`debug-2.log.gz:1713`/`:1770`：`[Fabric] Starting internal pack sorting with: [vanilla, distanthorizons, …, pingforit, placeholder-api, voicechat, voicechatprofiles]` —— **含 `pingforit`**。**图标是否显示日志判不了（需肉眼）** |
| **V8 音效** | ✅ **通过（日志级）** | `Missing sound for event` **三轮均 0 条**（全文件计数）；音效本身需耳朵确认 |
| **V9 VoiceChat 插件** | ✅ **通过** | R3 `:98` `Loaded 1 plugin(s)`、`:100` `Initialized 1 plugin(s)`、`:101` `Registering events for 'pingforit'`；R2 `:95`/`:97`/`:98`；R1 `:95`/`:97`/`:98`（全部 **1**，不再是 M9-1 的 0） |
| **V12 默认路径** | ✅ **日志级通过 / 视觉待看** | `improved transparency is ON` **0 次**（默认关闭，符合预期）；`options.txt` 现为 `improvedTransparency:false`；同轮有 `block model glow active (outline-only submitBlockModel)` 各 1 条（R1 另有 `entity outline pass active (native glow channel via outlineColor)` 1 条）→ 渲染路径**有**提交记录，但「看得见描边」只能靠眼睛 |
| **V13 OIT 路由** | ⏸ **未验证** | `improved transparency is ON` 在 **R1/R2/R3 全部 0 次**；`run/options.txt` 仍是 `improvedTransparency:false` → **这三轮根本没开过 OIT**，无法区分「没测」与「路由没走到」。补测提示：`run/options.txt` 里有 `key_key.debug.improvedTransparency:key.keyboard.x`，游戏里按 **X** 可直接切 |
| **V15 关闭 OIT 后描边正常** | ⏸ **未验证** | 同上：没有 OIT 开启→关闭的切换过程（且按 3号 复审意见，本条**不再**做「日志不新增」的断言，只验视觉） |
| **V16 闭环 1:1** | ✅ **通过** | R3 **43 : 43**、R2 **13 : 13**、R1 **22 : 22**（`send ping:` / `marker created:`）；三轮 **0 条** `reject` / `deny` |
| **V17 错误面** | ✅ **通过** | 3 条 ERROR/轮 全为账户/Realms（§2.1）；`pingforit` WARN/ERROR **0**；`Mixin apply failed` **0** |
| **V18 runServer** | ⏸ **未验证** | 三份日志 **0 条** `Starting minecraft server` / `Done (` / `DedicatedServer`；`Stopping server`（R3 `:361`、`:673`）上下文是单人世界卸载（前后是 `Saving chunks for level 'ServerLevel[v]'`）→ **集成服务器，不是 runServer** |

- 另有 **V10 / V11 / V19** 三项**日志完全判不了**（需要多人环境 / 界面肉眼 / 干净 mods 实例），不在本审计范围。

## 4. 存疑清单

| # | 现象 | 判断 | 建议 |
|---|---|---|---|
| 1 | **R3 没有 `Stopping!` 收尾**：末行是 22:21:40 的 `(AbstractDhRepo) Closing all [3] databases...`，但之前 22:21:39 已 `Stopping server` + `Saving chunks` | 很可能是**关窗/采样时正在退出**；不含任何模组异常 | 低优先：下次正常退出确认能看到 `Stopping!`（R1/R2 都有） |
| 2 | **OIT 三轮从未开启** → V13/V15 空转 | 不是缺陷，是**没测到** | 用 X 键（或 `options.txt`）开启后补测，最好与 V14（截图比对）同一次会话 |
| 3 | `Requested post effect does not exist: minecraft:end_of_frame`（R3 `:256`） | **DH** 请求的 post effect 名，1.21 时代留下的兼容问题；与本模组无引用关系 | **需 3号 判代码（低优先）**：确认 DH 的这条与 OIT/outline 相位是否可能互相影响 |
| 4 | DH 另三类 WARN（G1 GC `:120`、`RegionFileStorageExternalCache` `:301`、`Skipping Entity zombie_villager` `:357`） | **平台/DH 噪声**，与本模组无关 | 无需处理 |
| 5 | `Warning! Mod voicechatprofiles has a broken icon`（R3 `:368`） | **外部 mod** 资源问题（Mod Menu 提示） | 无需处理（若要干净可删该 jar） |
| 6 | 20:05:47 `WheelHudRenderer` 崩溃 | **重构中间态**，R1 起未复现 | 记录在 §2.3；如需 3号 复核可给这条栈 |
| 7 | `debug.log` 里 `[PingForIt] [sable-server] stage=provider-selection reason=sable-not-loaded`（DEBUG 级 1 条） | **既有诊断**（Sable 未装时的正常输出） | 无需处理 |
| 8 | `run/logs/devlog/` 不存在 | devlog 镜像未开 → 本审计只能看 `latest.log`/`debug.log` | 若以后要查渲染细节可考虑开启 |

## 5. 附：统计口径与原始输出

**为什么不用 grep 直接跑**：三份归档是 `.gz`，且需要「按内容时间」而不是 mtime 归类。本次用脚本解压后逐行统计（等价 shell 命令如下，供复现）：

```bash
# 每轮 ERROR/WARN/pingforit 相关计数
zcat run/logs/2026-11-03-6.log.gz | grep -cE "/(ERROR|FATAL)]"     # R1 → 3
zcat run/logs/2026-11-03-6.log.gz | grep -c "/WARN]"              # R1 → 7
grep -cE "/(ERROR|FATAL)]" run/logs/latest.log                    # R3 → 3
grep -c "/WARN]" run/logs/latest.log                              # R3 → 6

# 本模组相关（三轮都应为 0）
for f in run/logs/latest.log run/logs/2026-11-03-7.log.gz; do
  zcat -f "$f" | grep -c "(pingforit).*/(WARN|ERROR)]"   # 0
  zcat -f "$f" | grep -c "Mixin apply failed"            # 0
  zcat -f "$f" | grep -c "ClassNotFoundException: nx.pingwheel"  # 0
done

# P0 证据
zcat -f run/logs/latest.log | grep -n "PingForIt] send ping:" | wc -l      # 43
zcat -f run/logs/latest.log | grep -n "PingForIt] marker created:" | wc -l # 43
zcat -f run/logs/latest.log | grep -c "improved transparency is ON"        # 0（OIT 未开）
grep -n "internal pack sorting with: .*pingforit" run/logs/debug.log       # 1713, 1770
zcat -f run/logs/latest.log | grep -c "Missing sound for event"            # 0
zcat -f run/logs/latest.log | grep -nE "Starting minecraft server|Done \(" # 0 行 → V18 未验证
```

**原始计数汇总（脚本输出）**

| 文件 | 行数 | ERROR | WARN | pingforit WARN/ERROR | CNF nx.pingwheel | Mixin apply failed |
|---|---|---|---|---|---|---|
| `latest.log`（R3） | 679 | 3 | 6 | 0 | 0 | 0 |
| `2026-11-03-7.log.gz`（R2） | 423 | 3 | 5 | 0 | 0 | 0 |
| `2026-11-03-6.log.gz`（R1） | 473 | 3 | 7 | 0 | 0 | 0 |
| `2026-11-03-5.log.gz`（20:17，对照） | 500 | 4 | 6 | 0 | 0 | 0 |

> 对照那轮的 4 条 ERROR 里多出的是 `(FullDataUpdatePropagatorV2)`（**DH**），与本模组无关。

**`[PingForIt]` 消息分布（R3，共 122 条）**

| 条数 | 消息 |
|---|---|
| 43 / 43 | `send ping: requestId=…` / `marker created: markerId=…` |
| 10 | `Saved config type=ClientConfig` |
| 4 / 4 | `wheel open types=…` / `wheel select pingType=…` |
| 2 | `Loaded config type=ServerConfig` / `ClientConfig` |
| 1 各 | `Init`、`ping outline pipeline registered (…)`、`block outline pass registered (…)`、`block model glow active (outline-only submitBlockModel)` |

> 全部为**既有诊断**，无新增异常型消息；`improved transparency is ON` **不在**列表中（= 0 次）。

## 6. 追加一轮：R4（2026-11-03 22:28:33–22:39:51，OIT 补测）

> 本轮专为补测 §3 里空转的 **V13 / V14 / V15**。结论先说：**装了 Distant Horizons 时 OIT 会被 DH 强制关闭**，所以这三项在「带 DH」的环境里**不可测**；另外本轮出现**唯一一条**与本模组相关的异常（1 条 WARN，已转 3号 判定）。

### 6.1 R4 时间线

| 节点 | 时间 | 证据 |
|---|---|---|
| 启动 | 22:28:33 | `latest.log:1` `Loading Minecraft 26.3 with Fabric Loader 0.20.1` |
| `Sound engine started` | 22:28:58 | `:177`（启动→音频 **25 s**，不卡加载条） |
| 进世界（模拟距离生效） | 22:29:57 | `:264` `Changing simulation distance to 12, from 0` |
| **DH 关闭 OIT** | 22:29:57 | `:270`（见 §6.3-1） |
| 首次 ping | 22:30:16 | `:309` `send ping: requestId=…` |
| **pingforit WARN** | **22:38:44** | `:369`（见 §6.3-3） |
| 末次 ping | 22:39:25 | `:427` |
| 关服 → 退出 | 22:39:27 → 22:39:51 | `:488` `Stopping server`、**`:495` `Stopping!`**（本轮**正常收尾**，补上了 R3 缺的那行） |
| 总量 | — | **495 行**；`send ping` **32** : `marker created` **32** |

### 6.2 错误 / 警告明细

**ERROR：3 条（与 R1–R3 同构，全是账户/Realms 噪声）**

| 行号 | 原文 | 归属 |
|---|---|---|
| `:147` | `Failed to fetch user properties` | Mojang 账户（未登录，401） |
| `:191` | `Failed to fetch Realms feature flags` | Realms 噪声 |
| `:202` | `Couldn't connect to realms` | Realms 噪声 |

- **`grep -c ERROR` 得 4**，多的那条是 `:157` 的 `Caused by: MinecraftClientHttpException[…HTTP_ERROR…]` **续行**，与 R1–R3 完全一致（每轮都是 3 条真 ERROR）。

**WARN：6 条（其中 1 条来自 `pingforit`，是本轮唯一与本模组相关的异常）**

| 行号 | 标签 | 原文要点 | 归属 |
|---|---|---|---|
| `:85` | `FabricLoader/Mixin` | `Error loading class: …SodiumChunkSection (ClassNotFoundException)` | **DH** 的可选 Sodium 兼容（本机无 Sodium） |
| `:89` | `PerfmonDisabled` | `Invalid registry value type detected for PerfOS counters…` | Windows 系统 |
| `:92` | `voicechat` | `Running in debug mode - Don't leave this enabled in production!` | Simple Voice Chat 配置 |
| `:129` | `Initializer` | `Distant Horizons: G1 Garbage collector detected.` | DH |
| `:266` | `Minecraft` | `Requested post effect does not exist: minecraft:end_of_frame` | DH（同 §4-3） |
| **`:369`** | **`pingforit`** | **`entity-block glow: model hook not observed on 120 glowing dispatches; entity_block stays on the wireframe`** | **本模组（待 3号 判）** |

### 6.3 逐条结论

**1）OIT 没生效的真因：DH 把它关了（不是我们没走到回退）**

- 事实：`run/options.txt` 现在是 **`improvedTransparency:true`**，但 R4 日志 `:270`（22:29:57）写着：
  `(MinecraftClientWrapper) Disabling improved transparency... This is done to fix vanilla chunks (specifically water blocks) not fading into Distant Horizons LODs when DH's 'Vanilla Fade' option is enabled.`
- 上下文（`:267`–`:269`）是 DH 进世界时**批量覆盖原版设置**：`Overriding vanilla MC settings to better fit Distant Horizons...` → `Disabling vanilla clouds...` → `Disabling vanilla chunk fade in...` → `Disabling improved transparency...`。
- 统计口径：该行**每轮运行各 1 次**；当前 `run/logs` 里共 **13 条 = 主日志 7 轮 + `debug*.log` 镜像 6 份**（同一事件在主日志与 debug 镜像各记一条，**不要重复计数**）。
- **因果**：`SubmitNodeCollectionMixin.pingforit$classicTransparencyActive()` 读的是 `optionsRenderState.improvedTransparency`；DH 关掉后它**恒为 false** → 永远走经典 `afterTerrain` 通道 → `improved transparency is ON …` **永不打印**。所以本工程里「0 条」= **OIT 被 DH 关了**，不能用来判断路由是否工作。
- **对 §3 的措辞更正**：§3 的 V13 行写「这三轮根本没开过 OIT」→ 实际是「**玩家开着 OIT，但 DH 每次进世界把它关掉了**」（当时 `options.txt` 里已被 DH 写回 `false`，光看文件看不出来）。
- **结论**：**V13 / V14 / V15 在装 DH 的环境下不可测** —— 不是失败，是环境把它短路了。
- **验证方法（临时移走 DH）**：把 `run/mods/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar` 临时挪出（源码里 **0 处** `com.seibel` / `distanthorizons.api` 引用，`build.gradle` 的编译期检查只要求 `modmenu-` / `voicechat-`，**移走不影响编译**）→ 重进世界，确认 `options.txt` 为 `improvedTransparency:true` 且日志里**没有** `Disabling improved transparency` → ping 方块 → 期望出现**恰好 1 条** `[PingForIt] improved transparency is ON: …`，再按 V14 截图比对（线宽 / 色相 / 亮度差异）；测完把 jar 放回。

**2）ERROR 计数**：真 ERROR **3 条**（`:147`/`:191`/`:202`），`grep -c ERROR` 的 4 条含 `:157` 续行 —— ✅ 与 R1–R3 一致，**0 条本模组相关**。

**3）pingforit 的那条 WARN（本轮唯一与本模组相关的异常）**

- 位置/时间：`latest.log:369`，**22:38:44**，`[Render thread/WARN] (pingforit)`。
- 原文：`entity-block glow: model hook not observed on 120 glowing dispatches; entity_block stays on the wireframe`。
- **操作上下文（前后 10 行，`:359`–`:379`）**：正在连续 ping **方块实体系（BER）目标** —— `:366` 营火、`:371`/`:374` 红色床、`:360`/`:363`/`:377` 草方块；WARN 前 1 秒（`:368`，22:38:43）刚打印过 `entity-block model glow active (outlineColor via dispatch hook)`。
- **“120 次派发”不是一瞬间的突发**：从首 ping（22:30:16）到 WARN（22:38:44）跨约 **8.5 分钟**，其间只发了 **15 次 ping**（`:365` 处 `requestId=14`，之后是 `:370` 的 `15`）；120 次「glowing dispatches」是**每帧派发累积**的，说明这是**运行时累计到阈值后报一次**的诊断，不是某一次 ping 触发。
- **历史对照（判回归用）**：`entity-block model glow active` 与这条 WARN 在 **R1 / R2 / R3 均为 0 次**；R3 虽有 2 次床目标但没触发该 INFO。所以**本次是首次真的走到 entity-block 发光路径**，历史日志**不能**用来判断「是否回归」—— 必须靠代码级判定。
- 已按 Lead 安排**转 3号 判定**；本审计只登记，不下结论。

**4）闭环**：`send ping` **32** / `marker created` **32**，**严格 1:1**；`reject` / `deny` **0 条**。（`kind` 分布：`BLOCK` 30、`ENTITY` 2。）

**5）其它检查（全部保持正常）**

| 检查项 | R4 结果 | 证据 |
|---|---|---|
| VoiceChat 插件 | ✅ 仍是 **1** | `:95` `Loaded 1 plugin(s)`、`:97` `Initialized 1 plugin(s)`、`:98` `Registering events for 'pingforit'` |
| `Mixin apply failed` | ✅ **0** | 全文计数 0 |
| `ClassNotFoundException: nx.pingwheel` | ✅ **0** | 全文计数 0 |
| 资源包索引含 `pingforit` | ✅ | `debug.log:1713`、`:1770` `[Fabric] Starting internal pack sorting with: [… pingforit …]` |
| `Missing sound for event` | ✅ **0** | 全文计数 0 |
| 启动异常/卡加载条 | ✅ 无 | 22:28:33 → 22:28:58 `Sound engine started`（25 s） |

**6）V18（runServer）：⏸ 未验证**

- 判据一：**`run/eula.txt` 不存在**（`run/server.properties`、`run/world` 也不存在）。
- 判据二：**所有日志里 `Starting minecraft server version` 0 条**（含全部 `.log.gz`）。
- 结论：**专用服务器从未启动过** → V18 仍为 ⏸ 未验证；需实际跑一次 `./gradlew -g D:/Gradle :fabric:runServer`（首启生成 `eula.txt`，改 `eula=true` 后重启）。

### 6.4 归档编号漂移（重要提醒）

- MC 每次启动会**重新编号**轮转文件，所以**文件名不稳定**：§1 里 R1=`2026-11-03-6`、R2=`-7`，**现在已整体左移一位**（R1=`-5`、R2=`-6`、R3=`-7`、R4=`latest`）。
- 归类**一律按内容首行时间**，不要按文件名或 mtime（mtime = 下一次启动时间，见 §1 踩坑提示）。
- 当前映射：`-5` = 20:58（R1）、`-6` = 22:13（R2）、`-7` = 22:15（R3）、`latest` = 22:28（R4，本轮）。

> **§6 一句话**：**R4 与本模组相关的异常只有 1 条 WARN（`model hook not observed on 120 glowing dispatches`，22:38:44，`:369`），已转 3号 判定**；其余 0 条本模组 ERROR/WARN，闭环 32:32，V18 仍未验证。