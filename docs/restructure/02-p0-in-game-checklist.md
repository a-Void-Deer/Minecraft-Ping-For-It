# 02 · 重构后 P0 实机验收清单（MC 26.3 / Fabric 单端）

- 编写：1号 repo-analyst｜日期：2026-11-03（21:16 按 3号 第 59 号复审 + 20:58 实机证据改版）｜适用产物：`fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`（**需在 D 修复后重新构建一次**，见 §0）
- 谁跑：**用户**（本沙箱跑不了 Gradle / 开不了游戏）｜怎么记：逐条勾选，末尾「结果回填表」贴日志行号
- 相关：`docs/restructure/00-plan.md`（重构方案）、`01-inventory-and-mapping.md`（清点）、`docs/reviews/24-fidelity-constraint-audit.md` **#19**（improved transparency 路由）、`docs/reviews/55-restructure-review.md` §R5.7、`docs/reviews/59-p0-refactor-acceptance-review.md`（3号 对本文的复审）

## 0. 现在为什么还要跑（2026-11-03 21:16 更新）

**先说结论：重构后的第一轮实机已经跑过了**（20:58:41 启动 → 21:00:41 `Stopping!`），§0.1 那些项已被那次运行覆盖。**这次要跑的是它没覆盖的部分 + 一处刚做完的修复。**

1. 20:58 那次跑的就是**重构后**的构建（加载 58 个 mod，含 `pingforit`；`voicechat` 插件 1 个），所以「重构把模组跑挂了」这类风险已经不在了。原文写的「最后一次运行 20:19 早于 20:54 构建」**已过时**，据此删除。
2. **本次必须先重新构建**：`fabric/src/client/java/nx/pingwheel/common/mixin/SubmitNodeCollectionMixin.java` 的 `pingforit$classicTransparencyActive()` 刚补齐 `renderWireframeTerrain`（精确镜像 `GameRenderer.useImprovedTransparency()`），旧产物不含这项 → 见 V13 的可选子项。
3. **dev 与发布 jar 的资源可见性机制不同**（`AGENTS.md` §7.16）：dev 的 `runClient` 里 `:common` 是以 **jar** 形式在 classpath 上，而 Fabric 的资源包**只按「mod root」建索引**（mod root = 根目录含 `fabric.mod.json` 的 classpath 条目），`:common` 的 jar 根没有 `fabric.mod.json`；发布 jar 则把 assets 一起打进去了。所以「jar 里 assets 齐全」证明不了 `runClient` 里文案/音效正常，反过来也一样 —— **同一份源码，两条路径要分别验**（→ V6/V7 与 V19）。
4. 构建期证据（**只作对照，不算通过**，Lead 20:54 实测）：jar **772 244 B / 392 class**、`pingforit.mixins.json` ×1、`assets/pingforit/**` **16 项**、`icon.png`、`META-INF/services` **5 条**、**无重复条目**、四个 entrypoint 齐全；`fabric/build/resources/main/` 下已合并出 `assets/`（12 文件）、`icon.png`、`fabric.mod.json`。
5. **还没验的只剩**：V6/V7（肉眼）、V10（多人分组）、V11（ModMenu 入口）、V13/V14（OIT 下的描边与颜色差异）、V18（runServer）、V19（发布 jar 装进干净 mods）；加上「重新构建后都要重跑」的 V1 / V4。（V15 已按复审意见删掉「不再出现新日志」的断言，只保留描边正常。）

## 0.1 已由 20:58–21:00 那次运行验证（日志级）

| 项 | 结论 | 证据 |
|---|---|---|
| V4 启动干净 | ✅ | 20:58:41 启动，**0 条** `Mixin apply failed`；21:00:41 `Stopping!` 正常结束 |
| V6 / V8（**日志级**） | 🟡 | `run/logs/debug.log:1713`、`:1770` 的 `[Fabric] Starting internal pack sorting with: [vanilla, distanthorizons, …, pingforit, …]` 含 `pingforit`（= 资源包确实建了索引）；`latest.log` 里 `Missing sound for event` **0 条**。**图标/中文文案仍需肉眼**（V6/V7 保留） |
| V9 VoiceChat | ✅ | `latest.log:95` `[voicechat/PluginManager] Loaded 1 plugin(s)`、`:97` `Initialized 1 plugin(s)`、`:98` `Registering events for 'pingforit'` |
| V12 默认路径 | 🟡 | `latest.log` 里 `improved transparency is ON` **0 条**（默认不该出现，符合预期）；**描边是否可见仍要眼看** |
| V16 闭环 | ✅ | `send ping` **22** / `marker created` **22**（1:1；20:59:39–21:00:26） |
| **V18 专用服务器（2026-11-04 16:52）** | ✅ | 根级 `runServer` 别名 → `Done (2.998s)!`；**47 mods**、`[PingForIt] Init` + `ServerConfig`、voicechat 服务端 `Loaded 1 plugin(s)`；**0 ERROR / 0 class error / 0 mixin fail**（WARN 3 条全外部） |
| V17 错误面 | ✅ | ERROR **3** 条全是账户/Realms 噪声（`latest.log:117`/`:173`/`:202`），**0 条模组相关**（`grep ERROR` 会多命中 `:127` 的 `Caused by … HTTP_ERROR` 续行） |

## 1. 准备（跑之前 2 分钟）

- [ ] **P1** 环境变量就位：`JAVA_HOME=C:/Program Files/Java/jdk-25`、`GRADLE_USER_HOME=D:/Gradle`。
- [ ] **P2** `run/mods/` 里四个 jar 都在：`modmenu-21.0.0.jar`、`voicechat-fabric-2.6.27+26.3.jar`、`voicechatprofiles-fabric-26.3-1.0.1.jar`、`DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`。缺 **modmenu / voicechat** 会直接 fail-fast（见**附录 A**）。
- [ ] **P3** 备份日志：把 `run/logs/latest.log`（**20:58 那次**，即 §0.1 的基线证据）复制成 `run/logs/2026-11-03-2058-baseline.log`；重启后 MC 会把上一份轮转成 `logs/日期-N.log.gz`，但基线单独留一份最稳。
- [ ] **P4** 建议**一次会话跑完全部条目**（尤其第 6 组的 OIT 开关要在同一次运行里对比），中途不要重启。

## 2. 逐项清单

### 第 1 组 · 构建（`V1`–`V2`；反向 fail-fast 已降级到**附录 A**）

- [ ] **V1** 在仓库根执行 **`./gradlew -g D:/Gradle clean build`**（Windows：`gradlew.bat -g D:/Gradle clean build`）。
  - **为什么必须 `clean`（或加 `--rerun-tasks`）**：那两行 jar 清单是在编译任务的 `doFirst` 里打印的；任务 UP-TO-DATE 时**不会执行**，日志里看不到它们，容易被误判成失败。
  - **期望**：`BUILD SUCCESSFUL`；**日志里有两行 jar 清单**：`compileOnly integration jars (compileJava): [...]` 与 `compileOnly integration jars (compileClientJava): [...]`，两行都应含 `run/mods` 里对应的 jar 名。
  - **期望**：产物在 `fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`（根目录 `build/libs` **不应该**再有产物）。
  - **失败要贴**：`BUILD FAILED` 前后各 30 行 + 那两行 `integration jars` 的原文（如果根本没打印，说明编译任务没跑或 jar 清单为空）。
- [ ] **V2**（可选，构建期已核对，可跳过）产物自查：`unzip -l fabric/build/libs/Ping-For-It-*.jar | grep -c '\.class$'` → 期望 **392**；`unzip -l … | grep -c pingforit.mixins.json` → **1**；`unzip -l … | awk '{print $4}' | sort | uniq -d` → **空**（无重复条目）。
> 反向验证 fail-fast（原 V3）**已从主流程移除**，搬到 **附录 A**：它要求临时挪走 jar、验完再放回，主流程里做既容易忘、又会把「缺 jar」的真实失败和人为干扰混在一起。

### 第 2 组 · 启动（`V4`–`V5`）

- [ ] **V4**（20:58 那次已 ✅ 过；**若你重新构建过就必须重跑**）启动客户端：**`./gradlew -g D:/Gradle :fabric:runClient`**（推荐写法），或等价的别名 `./gradlew -g D:/Gradle runClient`（2026-11-04 起根级为转发别名；更早版本会报 `Cannot locate tasks that match ':runClient'…`，见 `AGENTS.md` §7.18 与 §3/§4）。
  - **期望**：加载条正常走完（不卡死、不超 60 秒无进展）；**无** `Mixin apply failed`；无 `ClassNotFoundException: nx.pingwheel…`。
  - **期望日志（顺序大致如此）**：`[PingForIt] Init` → `[PingForIt] Loaded config type=ServerConfig` → `[PingForIt] Loaded config type=ClientConfig` → `[PingForIt] ping outline pipeline registered (depth test disabled, cull off, translucent)` → `[PingForIt] block outline pass registered (custom pipeline, afterTerrain routing)`。
  - **失败要贴**：从启动第一行到 `Sound engine started` 的全部日志（或 `latest.log` 前 150 行）+ 卡住时的截图。
- [ ] **V5** 进单人世界（创造模式即可），跑动 10 秒。
  - **期望**：无崩溃、无「整屏冻结」（注意：GUI 里抛异常的表现是**画面卡死但进程活着**，日志只有一条异常，见 `AGENTS.md` §7.14）。
  - **失败要贴**：`run/logs/latest.log` 尾部的 `Exception` / `Caused by` 段。

### 第 3 组 · dev 资源合并（`e8790a5`，`V6`–`V8`）

> 这一组专验 `AGENTS.md` §7.16 那条：dev 的 assets 不在 mod root 上。**只看发布 jar 或只看构建目录都验不出来。**

- [ ] **V6**（🟡 日志级已过，**图标仍需肉眼**）主菜单 → Mods（Mod Menu）→ 找到 **Ping For It**。
  - **期望**：列表里该条目**有图标**（不是默认的缺失贴图/空白）。
  - **失败要贴**：Mods 列表截图 + 日志里 `assets/pingforit` 相关行（或说明日志里根本没有）。
- [ ] **V7** 打开设置界面（命令 `/pingforit config`，或 Mod Menu 的配置按钮）。先把游戏语言切到 **简体中文**。
  - **期望**：**优先检查**——所有文案中文正常；**没有裸 key**。设置界面真正会用到的是下面这些（取值自 `common/src/main/resources/assets/pingforit/lang/en_us.json` 的 `settings.pingforit.*`），只要界面上出现其中任何一个原文就说明 lang 没进资源包：
    - 标题 `settings.pingforit.title`；分类标签 `settings.pingforit.category.display` / `settings.pingforit.category.selection` / `settings.pingforit.category.wheel_appearance`；
    - 某个选项标签 `settings.pingforit.ping_volume`（英文是 `Ping Volume: %s`）；分组小标题 `settings.pingforit.group.marker_display`；按钮 `settings.pingforit.reset_all`。
  - **失败要贴**：截图 + 出现裸 key 的**完整字符串**（这是判断缺哪个 lang 文件的关键）。
- [ ] **V8**（🟡 日志级已过：`Missing sound for event` 0 条；**顺手听一次音效**即可）回到游戏，按 ping 键 ping 一个方块。
  - **期望**：听到 ping 音效；**日志里没有** `Missing sound for event: pingforit:ping`。
  - **失败要贴**：那一行 `Missing sound for event:` 原文 + ping 时的截图（有没有标记）。

### 第 4 组 · M9-2 VoiceChat 插件加载（`V9`–`V10`）

- [ ] **V9**（✅ 20:58 那次已过：`Loaded 1 plugin(s)` / `Initialized 1 plugin(s)` / `Registering events for 'pingforit'`；重跑时可跳过）无需任何操作，看启动日志里 `voicechat` 的插件管理段。
  - **期望**：`[voicechat/PluginManager] Loading plugins` 之后是 **`Loaded 1 plugin(s)`** 与 **`Initialized 1 plugin(s)`**（M9-1 那次是 `0`，`0` 就说明我们的插件没被发现）。
  - **失败要贴**：`voicechat/PluginManager` 那 4–6 行原文（`Loading/Loaded/Initializing/Initialized`）+ `fabric.mod.json` 里 `entrypoints` 段。
- [ ] **V10**（可选，需两个人/两个客户端）建一个 VC 分组，只让 A、B 同组，C 不在组内；由 A ping。
  - **期望**：只有 B 看到标记，C 看不到（判定用的是 VC 的**分组 group**，不是「团队」）。
  - **失败要贴**：A/B/C 三端各自的 `send ping` / `marker created` 行 + 分组截图。

### 第 5 组 · M9-1 Mod Menu 入口（`V11`）

- [ ] **V11** Mods 列表里点 Ping For It 的「配置」按钮。
  - **期望**：打开的就是本项目 `SettingsScreen`（与 `/pingforit config` 打开的完全一致）；**tab 切换、列表滚动、翻页都不冻结**；标题栏不重叠。
  - **失败要贴**：点开后的截图 + `latest.log` 里点开前后的 20 行（冻结时通常只有一条异常，位置就在这两行之间）。

### 第 6 组 · 渲染回归 + improved transparency 路由（`d7c2975`，`V12`–`V15`）

> 顺序很重要：**先默认跑 V12，再开 OIT 跑 V13–V14，最后关掉验 V15**。同一会话内完成，别重启。
>
> **前置条件（V13–V15）：必须先把 DH 移出 `run/mods`** —— `DistantHorizons-3.3.4-26.3-fabric-neoforge.jar` 在时，DH 每次进世界都会 `Disabling improved transparency...`（R4 `latest.log:270`），OIT 根本不会生效，**测不出来**。把它临时挪走后重进世界，确认日志里没有 `Disabling improved transparency` 再开始；测完放回。（源码 0 处 DH 引用、编译期检查只查 `modmenu-`/`voicechat-`，移走不影响构建。）

- [ ] **V12**（🟡 日志级已过：`improved transparency is ON` 0 条；**描边可见性仍需眼看**）（默认设置，**不要动**视频选项）对着 3–5 格外的一个方块 ping。
  - **期望**：能看到该方块被描边（线框/光晕；`entity_block` 类目标是模型发光）；隔墙也可见（`NO_DEPTH_TEST`）。
  - **期望**：日志里**不应出现** `[PingForIt] improved transparency is ON`（默认路径不该打这条）。
  - **失败要贴**：截图（含准星与方块）+ 该次 ping 附近的日志 20 行。
- [ ] **V13** 打开 **improved transparency**：视频设置里对应开关（中文可能显示为「改进透明度」之类），或直接改 `run/options.txt` 的 `improvedTransparency:true` 后重进世界。再 ping 同一个方块。
  - **期望**：描边**仍然可见**（这条是 `d7c2975` 的核心）。
  - **期望**：日志出现**恰好一条** info：`[PingForIt] improved transparency is ON: MC 26.3 never executes the afterTerrain phase, routing the outline to the outline phase instead (see docs/reviews/24-fidelity-constraint-audit.md #19)`。
  - **失败要贴**：截图 + 该行原文（若**完全没有**这行 → 路由没走到；若**出现多次** → 一次性标记失效）。
  - **可选子项（验刚补的 `renderWireframeTerrain`）**：保持 OIT 开启，再按 **F3 打开「线框地形」（wireframe terrain）**，ping 一次。
    - **期望**：**不应**出现新的 `improved transparency is ON` 行 —— 因为 `pingforit$classicTransparencyActive()` 现在精确镜像 `GameRenderer.useImprovedTransparency()`（`!improvedTransparency || renderWireframeTerrain`），线框模式下原版会走经典通道，旧实现会在这里**误报**。
    - **失败要贴**：F3 开启前后的日志行（标出是否新增该 info 行）+ 截图。
- [ ] **V14**（`R5.5-a`）对着同一方块、同一视角、同一距离，各截一张 V12（默认）与 V13（OIT）的图，**记录差异**，而不是要求两者一致。
  - **判据**：OIT 模式下最终合成走原版 `entityOutlineTarget` + `ENTITY_OUTLINE_BLIT`，**颜色/亮度本来就可能**与我们的管线直出不同；需要如实记录两件事：**① 线宽是否一致；② 色相/亮度是否有明显偏移**（偏亮/偏暗/偏色）。
  - **通过标准**：线宽一致 + 偏移在「看得出来但不影响可读性」范围内 → 记为**可接受差异**（附在 `24` #19 备注里）；若偏移严重到看不清/认错颜色 → 记为**保真风险**，回到 `24` #19 评估是否要改路线。
  - **要贴**：两张截图（标明哪张是 OIT）+ 一句话差异描述。
- [ ] **V15** 关掉 improved transparency，再 ping 一次。
  - **期望**：描边正常可见（与 V12 同款表现）。
  - **不再断言日志**：`fallbackLogged` 是 **per-session 一次性**标记，重启即重置，所以「关掉后不再出现该行」在逻辑上不可判（3号 第 59 号复审意见）——只验行为即可。
  - **失败要贴**：截图 + ping 前后各 10 行日志。

### 第 7 组 · 常规闭环（`V16`–`V17`）

- [ ] **V16**（✅ 20:58 那次已过：22:22）**单客户端**连续 ping 10 次以上（方块、实体、掉落物各来几次）。
  - **前置条件（1:1 成立的前提）**：**只开一个客户端**，且日志里**没有** `reject` / `deny` 记录 —— 多人同服时别人的 ping 会进同一份日志，1:1 就不成立了。
  - **期望**：日志里 `[PingForIt] send ping:` 与 `[PingForIt] marker created:` **条数相等**（1:1），`requestId` 允许跳号（客户端节流属正常，`14/16/17/19/20/23` 这类缺号不会产生 marker）。
  - **失败要贴**：两条计数 + 不成对的那几次 `requestId` 附近日志。
- [ ] **V17**（✅ 20:58 那次已过：3 条 ERROR 全为账户/Realms 噪声）全日志扫一遍 ERROR。
  - **期望**：**0 条与本模组相关**的 ERROR；允许的噪声只有 Mojang 账户/Realms（`Failed to fetch user properties`、`Failed to fetch Realms feature flags`、`Couldn't connect to realms`）与 DH/Sodium/PerfOS/voicechat 的 WARN。
  - **失败要贴**：所有 `ERROR` 行的原文 + 前后各 3 行（**不要只贴一行**，需要上下文判断归属）。

### 第 8 组 · 安装形态与服务端（`V18`–`V19`，本次新增）

> 组 1–7 验的都是 **dev 形态**（classpath 上有 `common` jar 与 `fabric/build/resources/main`）。这两条验的是**别人装上去会不会坏** —— 重构改了模块边界，这两条正是重构最容易漏的地方。

- [x] **V18** ✅ **已通过（2026-11-04 16:52）**：`Done (2.998s)!` / 47 mods / 0 ERROR / voicechat `Loaded 1 plugin(s)`（服务端）—— 详细证据见 `docs/PORTING-PROGRESS.md` §P0.3 与 `AGENTS.md` §6。命令回顾：`./gradlew -g D:/Gradle :fabric:runServer`（推荐）或别名 `./gradlew -g D:/Gradle runServer`。
  - **首次运行**会在仓库根 `run/` 生成 `eula.txt`（`eula=false`）→ 改成 `eula=true` → **再跑一次**。⚠ **必须用 ASCII 写**：PowerShell 的 `>` / `Out-File` 会写成 UTF-16 LE + BOM，服务端读不到 → 用 `"eula=true" | Set-Content -Encoding ascii run\eula.txt`（cmd：`echo eula=true>run\eula.txt`）；`Format-Hex` 应看到 `65 75 6C 61 3D 74 72 75 65 0D 0A`。详见 `AGENTS.md` §7.20。
  - **期望**：启动到 **`Done (…s)! For help, type "help"`**；**无** `NoClassDefFoundError`；**无**客户端类加载错误（例如 `Attempted to load class net/minecraft/client/…`）；控制台里能看到 `[PingForIt] Loaded config type=ServerConfig`。
  - **期望**：输入 `stop` 后正常关停（`Stopping server` → `ThreadedAnvilChunkStorage … All dimensions are saved` 一类收尾行，进程退出码 0）。
  - **失败要贴**：从 `Loading Minecraft …` 到失败点的全部控制台输出（`runServer` 不写 `latest.log`，请直接复制终端）+ 完整异常栈。
  - 备注：这就是 `AGENTS.md` §9 里 **M10 的 #6**，因为重构改了模块边界，提前到 P0 一起做。
- [ ] **V19** 发布 jar「安装场景」冒烟：把 `fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar` 复制进一个**干净的**实例 `mods/`（只放它 + Fabric API + Loader 依赖，**不要**放 `common` 的 jar、也不要指向 `fabric/build/resources/main`），启动一次。
  - **为什么单列**：dev 的 `runClient` 与发布形态的资源/类路径不同（§0 第 3 条），dev 通过**不能**代表安装后通过；这一步是唯一能验「打进 jar 的 assets/SPI/entrypoint 真的被 Loader 认到」的方式。
  - **期望**：能进主菜单与世界；`Mods` 列表里有 Ping For It 且带图标；设置界面中文正常（同 V7 的判据）；ping 有音效与标记。
  - **失败要贴**：该实例的 `logs/latest.log`（前 200 行 + 报错段）+ `mods/` 目录列表 + 用的启动器/版本截图。
## 3. 结果回填表

> 每条填「通过?（✅/❌/跳過）」+ 备注 + 关键日志证据行（行号或原文片段）。

| 编号 | 组 | 通过? | 备注 | 日志证据（行号/原文） |
|---|---|---|---|---|
| V1 | 构建 |  |  |  |
| V2 | 构建（可选） |  |  |  |
| V3 | → 已移到**附录 A**（可选） |  |  |  |
| V4 | 启动 |  |  |  |
| V5 | 启动 |  |  |  |
| V6 | 资源合并 |  |  |  |
| V7 | 资源合并 |  |  |  |
| V8 | 资源合并 |  |  |  |
| V9 | M9-2 VoiceChat |  |  |  |
| V10 | M9-2 VoiceChat（可选） |  |  |  |
| V11 | M9-1 Mod Menu |  |  |  |
| V12 | 渲染（默认） |  |  |  |
| V13 | 渲染（OIT） |  |  |  |
| V14 | 渲染（OIT 颜色比对） |  |  |  |
| V15 | 渲染（关掉 OIT 后描边正常） |  |  |  |
| V16 | 闭环 |  |  |  |
| V17 | 闭环 |  |  |  |
| V18 | 安装形态（runServer） |  |  |  |
| V19 | 安装形态（干净 mods） |  |  |  |

**整体结论**：⬜ 全通过 / ⬜ 有阻塞（列出编号）

## 4. 判定与后续

| 现象 | 结论 | 下一步 |
|---|---|---|
| V1/V4 失败 | 构建或启动坏了 | 先贴日志，不要动代码 |
| V6–V8 失败（图标缺失/裸 key/无音效） | dev 资源合并没生效 | 查 `fabric/build/resources/main/` 有没有 `assets/`（`AGENTS.md` §7.16） |
| V9 是 `Loaded 0 plugin(s)` | VoiceChat 插件没被发现 | 查 `fabric.mod.json` 的 `voicechat` entrypoint 与 `run/mods` 里的完整 jar |
| V12 通过、V13 不可见 | OIT 路由没生效 | 贴 V13 日志（有没有那行 info），对照 `24` **#19** |
| V14 线宽不一致 / 颜色偏移严重 | 唯一的实质保真风险（`R5.5-a`，合成走原版 `ENTITY_OUTLINE_BLIT`） | 记录差异，回到 `24` #19 评估是否需要改路线；轻微偏移记为可接受差异即可 |
| V18 `NoClassDefFoundError` 或客户端类加载错误 | 模块边界破了（`common` 引到了客户端类 / 服务端 SPI 缺项） | 贴控制台全文；查 `fabric/src/main/resources/META-INF/services/` 三条 SPI 与 `compile-check` 的服务端安全 gate |
| V19 dev 通过但装上去坏 | 打包/资源/entrypoint 问题（不是运行逻辑问题） | 贴该实例 `latest.log` + `mods/` 列表 + `unzip -l` 里 `assets/`、`META-INF/services`、`fabric.mod.json` 三块 |
| 其它 | — | 按编号贴证据给 Lead |

> 一句话：**重构后的第一轮已经跑过（20:58–21:00），这一轮只补它没覆盖的**——肉眼项（V6/V7）、ModMenu 入口（V11）、OIT 下的描边与颜色差异（V13/V14）、多人分组（V10，可选）、以及**重构最容易漏的安装形态**（V18 runServer / V19 干净 mods）。全绿则 P0 收尾；任何一项红，按上表把证据贴出来即可。

## 附录 A（可选）· 反向验证 fail-fast

> 只在**你怀疑 `run/mods` 缺 jar** 时做。做完**一定要把 jar 放回原位**（复审意见：这条放主流程里容易忘）。

- 步骤：把 `run/mods/modmenu-21.0.0.jar` 临时改名（例如加 `.bak`）→ `./gradlew -g D:/Gradle clean build` → **期望**编译期抛 `GradleException`，消息含 `Missing compile-only jar for the Mod Menu integration: put modmenu-21.0.0.jar into run/mods/` 与官方下载 URL（**不是**一屏 `package … does not exist`）→ 把 jar 改回来。
- 同样可验 VoiceChat：临时改名 `voicechat-fabric-2.6.27+26.3.jar` → 期望 `Missing compile-only jar for the Simple Voice Chat integration: …`。
- 注意前缀规则：检查要的是 `modmenu-` / `voicechat-`（**带连字符**），所以 `voicechatprofiles-*.jar` 永远不会误满足。
- 失败要贴：完整异常块 + `run/mods` 的 `ls` 输出。