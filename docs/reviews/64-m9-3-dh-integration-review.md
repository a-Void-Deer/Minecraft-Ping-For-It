
# 64 · M9-3 Distant Horizons 集成复审

任务：M9-3（方案 A）第 3 步独立复审：保真 / 异步契约 / 构建隔离
审核人：3号　日期：2026-11-04　对象：DistantHorizonsIntegration 实现 + buildSrc/common/scripts/CI/.gitignore 方案 A 改动
证据：git diff、上游 diff、javap、compile-check、build 输出、grep。

## 结论

**通过。** 代码实现与上游仅差 4 行 javadoc 缩进；traceDistantAsync 的 true/false、回调线程和 call-site game.execute 契约成立；方案 A 确实把完整 DH jar 排除出编译类路径，compile-libs 只做编译，run/mods 完整 jar 保留给 runClient；CI/local 的 API 输入一致；common 的 362 class 是 API jar 内嵌 .java 的 implicit 编译，脚本加 -implicit:none 后恢复 262，Gradle 输出也没有 com/seibel。

非阻断：terrainRepo 没有单独 null 判空（上游也没有；NPE 会被 future 捕获并走 empty 回调，不崩）；logUnguardedLinkFailure 不直接 disable LINK_GUARD，但 call-site 会置 HasDH=false；tools 脚本 0 命中只是 warning，随后仍是 package 错误（Gradle 的 doFirst 才是 fail-fast）。没有发现阻塞项。

## A. 保真 / 公共签名

- git diff --no-index 只有 4 行 javadoc 的 leading whitespace（4 insertions / 4 deletions），没有代码行差异；rootCause、traceDistant、logVersionDiagnostics、logUnguardedLinkFailure 都与上游一致。
- 上游与本地 SHA256 不同，差异只来自 javadoc 缩进；不是 hidden code change。
- 公共签名：
  - traceDistantAsync(Vec3, Vec3, Consumer<Optional<BlockHitResult>>): boolean，与 stub 时代一致。
  - rootCause(Throwable): Throwable，package-private，16-hop 上限和 self-cause break 原样。
  - logVersionDiagnostics()、logUnguardedLinkFailure(LinkageError) 原样。
- 结论：保真成立，允许的 javadoc 空白差异，没有行为改动。

## B. 异步契约

- 返回 false：!HasDistantHorizons、LINK_GUARD.disabled、CompletableFuture.supplyAsync 抛 RuntimeException（调度拒绝）——不注册 completion；call-site 在 scheduled == false 时同步 completeCapture(vanilla miss)。
- 返回 true：supplyAsync 成功；whenComplete 必调用一次 completion：
  - traceDistant 成功且 payload -> Optional.of(hit)；
  - 成功但 !success 或 payload null -> Optional.empty()；
  - 非 Linkage 失败 -> debugException + empty；
  - LinkageError -> LINK_GUARD.disable + empty。
- whenComplete 的 action 若抛异常，会写入它返回的 future（未持有），不会二次调用 completion；正常 callback 不抛。
- 回调线程是 CompletableFuture 完成线程（commonPool）；call-site 的 completeDistantHit 第一行就是 game.execute（ClientPingRuntime:828），pool 线程只捕获 game/level/token/fallbackMiss/pressRay，不读 CommonClient.Game，不在 pool 里碰客户端状态。ClientPingRuntime:742-752 的 fallback 与 scheduled 分支符合 true 不 fallback、false 同步 fallback。
- 结论：true 恰一次、false 不回调、回调线程安全，均成立。

## C. 异常/降级

- worldProxy：DhApi.Delayed.worldProxy == null 有判断（DistantHorizonsIntegration:127-129）；getSinglePlayerLevel 返回值有 null 判断（:131-135）。
- terrainRepo：没有单独判空；createSoftCache()/raycast(...) 直接使用（:142/149）。若 terrainRepo 为 null，NPE 发生在 supplyAsync 的 traceDistant 内 -> future 异常 -> whenComplete 捕获 rootCause（NPE 不是 LinkageError，走 debugException + empty callback），call-site 用 vanilla miss 完成；不会崩，只会 debug 一条 trace failed。上游同形，非本次引入；不阻塞，可作 M10 小加固。
- LINK_GUARD：traceDistant 的 LinkageError、traceDistantAsync 的调度 LinkageError、logVersionDiagnostics 的 LinkageError 都走 disable；logUnguardedLinkFailure 只 warnException 不 disable，但 call-site 同时把 HasDistantHorizons=false（ClientPingRuntime:745-748），后续不再进入，安全。
- DH 未安装/被禁用：HasDH=false 时 ClientPingRuntime 不调用 traceDistantAsync；ModContext 不调用 logVersionDiagnostics；DistantHorizonsIntegration 的静态字段只有 LINK_GUARD、CACHE_LOCK、Instant.EPOCH，静态初始化不触碰 DhApi；首次真正引用 DhApi 的只有 traceDistant/logVersion，二者都有 LinkageError 兜底。静态上安全；无 DH 的 runClient 实机仍建议做一次（可并入 M10）。
- 结论：worldProxy 判空保留，terrainRepo 判空未保留但失败被 future 捕获；DH 缺失不 eager 初始化；降级路径成立。

## D. 构建隔离（方案 A 核心）

- buildSrc/src/main/groovy/multiloader-common.gradle：
  - ext.integrationJars 只剩 modmenu-* / voicechat-api-* / voicechat-*；DistantHorizons-* 与 DistantHorizonsApi-* 已从 run/mods 编译 glob 剔除。
  - 新增 ext.integrationApiJars：rootProject.fileTree(dir: compile-libs, include: DistantHorizonsApi-*.jar, exclude: sources/javadoc)。
  - 新增 attachIntegrationApiJarCheck：doFirst + File.listFiles + Pattern + 前缀检查；日志前缀 compileOnly integration API jars (...): 与 run/mods 的 compileOnly integration jars (...): 区分。
- common/build.gradle：compileOnly integrationJars + compileOnly integrationApiJars；attachIntegrationApiJarCheck('compileJava', 'DistantHorizonsApi-', ...).
- fabric/build.gradle：没有 compileOnly integrationApiJars；只保留自己的 integrationJars（modmenu/voicechat）。DistantHorizonsIntegration 在 common，编译面正确。
- tools/compile-check.sh|.ps1：run/mods 的 INTEGRATION_GLOBS 已剔除 DistantHorizons/DistantHorizonsApi；新增 compile-libs 段（DistantHorizonsApi-*.jar，排除 sources/javadoc）；两段相加进 CP_JARS；javac 已带 -implicit:none（sh:173 / ps1:181），所以 API jar 里的 .java 不再隐式编译。
- 实测：bash tools/compile-check.sh -> common 262 / fabric-main 4 / fabric-client 126，0 error；common/build/classes/java/main 262 个 class，com/seibel 0；fabric/build/libs 的 jar 0 个 com/seibel 条目。362 class 是修复前的工具产物，不是代码新增。
- run/mods 完整 DH jar：只作为 dev 运行时 mods 目录存在；common 不再 compileOnly 它，fabric 也不编译它；Loom runClient 仍会从 run/mods 加载完整 jar（运行时，不是 compileClasspath），compileOnly 不会把它带入 runtime。
- .gitignore 只写 compile-libs/*.jar；README.md 和 .gitkeep 不在忽略范围，作为目录占位/说明入库。
- CI：mkdir -p run/mods compile-libs；modmenu + voicechat 完整 jar 到 run/mods，DistantHorizonsApi-7.2.0.jar 到 compile-libs；不再下载完整 DH。CI 只跑 ./gradlew build，无 runClient，因此 API jar 足够；本地 compile-libs 有同一 API jar，CI/local 编译面一致。
- 结论：方案 A 的隔离成立，没有“完整 DH 从 run/mods 偷进 compileClasspath”的路径。

## E. 检查器正确性

- attachIntegrationApiJarCheck 与 attachIntegrationJarCheck 同构：tasks.matching(taskName).configureEach { doFirst { File/Pattern 自己扫描，不碰 project，不解析 FileTree } }；配置期不 throw，只在 compileJava 执行时 fail-fast；符合既有约定。
- 0 命中：
  - Gradle：doFirst 抛 GradleException，消息含 DistantHorizonsApi-7.2.0.jar 放入 compile-libs/ + 官方 URL，用户看到一行可操作错误，不会先看到 package does not exist。
  - tools 脚本：0 命中仅 warning（设计如此），随后 javac 会报 package com.seibel... does not exist；这是非阻断的工具用户体验差异（脚本用于快速本地检查，Gradle 才是权威 fail-fast）。若想完全一致，可在脚本 0 命中时直接 exit 2，但不在本次范围。
- 前缀：integrationApiJarPattern = DistantHorizonsApi-.*\.jar；requiredPrefix = DistantHorizonsApi-；不会误收完整 DistantHorizons-3.3.4 jar。
- 结论：检查器行为正确；脚本 warning-only 是已知差异，不阻塞。

## F. 回归面

- modmenu/voicechat：integrationJars 的 glob 只删了 DH 两项，modmenu-* / voicechat-api-* / voicechat-* 未动；两个 attachIntegrationJarCheck 调用未动；日志前缀未改。compile-check 的 run/mods 段同样只删 DH，两个已有集成不受影响。
- common class 262 与 fabric 4/126：compile-check 现在与代码一致；362 由 API jar 内嵌 .java 的 implicit 编译导致，加 -implicit:none 后消失；common/build/classes 与发布 jar 均无 com/seibel，确认不是代码新增。
- .gitignore：compile-libs/*.jar 不会匹配 README.md / .gitkeep；目录说明仍可提交。
- CI/local：两边都用 DistantHorizonsApi-7.2.0.jar 编译；本地 run/mods 的完整 DH 仅运行时；没有 local 绿/CI 红的编译面差异。
- 结论：无回归；只有 C/E 的非阻断边界。

## 建议（非阻塞）

1. 可选：给 terrainRepo 加 null 判空（返回 empty），或确认 DH API 在 worldProxy 非空时 terrainRepo 恒非空；当前 NPE 会被 future 捕获，功能上已降级。
2. 可选：tools 脚本 0 命中 API jar 时直接 exit 2，与 Gradle fail-fast 一致。
3. 无 DH 的 runClient 冒烟一次，确认 DistantHorizonsIntegration 的类加载/静态初始化在 absence 下安全（静态分析成立，实机更稳）。

## G. 实机复验（2026-11-04 22:28–22:31，装 DH 3.3.4 的 58-mod 会话）

> 本节点在复审之后，用于把「静态分析成立」升级为「实机成立」；不修改上面 A–F 的任何结论。

- **环境**：`run/logs/latest.log`（498 行，22:28:21 → 22:31:27 `Stopping!`），mod 列表含 `distanthorizons 3.3.4`（`:4`）；`run/options.txt` 的 `renderDistance:16`（=256 格）。
- **API 面**：`[PingForIt] Distant Horizons API Version: 7.2.0`（`:102`）—— `logVersionDiagnostics` 由真实现执行，stub 没有这一行。
- **契约面**：`distanthorizons schedule failed` / `distanthorizons trace failed` / `distanthorizons integration disabled` **各 0 条**（`latest.log` + `debug.log` 全文检索；前两条走 `debugException`，只在 debug 级可见，两处都查过）→ 异步 takeover 无失败、无降级、无 strand。
- **闭环**：`send ping` **36** / `marker created` **36**（严格 1:1，0 reject/deny），其中 BLOCK 22 / LOCATION 14。
- **远距离**：**用户提供的客户端截图（HUD）显示 `草方块 370.8m`** 的 BLOCK 标记（同帧 `此处 727.2m / 584.3m / 561.3m`）—— 超出 256 格加载范围、原版 `Clip` 必 MISS，该 BLOCK 只能来自 DH 异步射线。`send ping`/`marker created` 只记 `requestId|markerId|kind|pingType`，**距离不是日志字段**，故本条为**截图证据**而非日志证据。
- **建议 3（无 DH 冒烟）仍未做**：本轮含 DH。依据不变（`ModContext.HasDistantHorizons` 短路 + `catch LinkageError`，未装时不解析 DH 类），留待 M10 验证批顺手覆盖。
- **结论**：**实机通过**，与 A–F 的静态结论一致，无新增阻塞项。
