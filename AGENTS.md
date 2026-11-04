# AGENTS.md · Ping For It（MC 26.3 / Fabric 单端）协作说明

> 团队唯一事实来源：现状、目录、构建、已验证结论、已知坑、待办。
> 维护者：Lead｜**最后更新：2026-11-04（M9-3 DH 集成实现 + 复审通过（方案 A，实机待验）；补待完成清单含 M10 技术债与发布门禁；根级 run 任务为转发别名；P0 收口；M9-1/M9-2 实机通过）**

## 0. 2026-10-29 数据事故与恢复（先读）

**已恢复（2026-10-30）**：工程目录曾被外部操作清空、`.git` 丢失、IDEA Local History 只回了空壳。重建后基线为 tag **`restore-2026-10-29`**（提交 `bca73e0`，事故记录 `87bceb2`）。

| 项 | 恢复后状态 |
|---|---|
| 规模 | **2026-11-02 实测**：`src/main` **166** java + `src/client` **80** java + `deferred/` **95** java（恢复当时的 162/55/96 已陈旧；事故前为 165/54/92，差异与保真度见 `docs/reviews/14-restore-verification.md`、`15-restore-baseline-audit.md`） |
| 编译 | `tools/compile-check` **0 error / 0 error**（两源集）；用户终端 `gradlew clean build` **通过**（2026-10-30 12:07） |
| 产物 | `build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`：**649 KB / 345 class / major 69 / `id=pingforit` / `version=0.4.2-pfi-beta1`** |
| 依赖 | `.gradle/` 已重建；`D:\Gradle\caches` 里的 MC 26.3 与 Fabric API jar 未受影响 |
| git 历史 | 原 tag（`pre-port`/`port-M2`/`port-M3`/`port-M4`/`port-M6.5`）随 `.git` 丢失；**语义保留在 `docs/PORTING-PROGRESS.md` 的阶段记录里**。恢复基线 `restore-2026-10-29`；恢复后实机验收通过并打 **`port-M6.5-restored`**（2026-10-30 12:30–12:33） |
| 上游源码 | `Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1`（从 Downloads 的 tar.gz 重新解压，617 文件 / 513 java 完好） |

> 教训：**不要再依赖 IDEA Local History 做恢复**——它对工具写入的文件只存元数据、不存内容。

## 1. 定位与现状

- 上游 **Ping For It `0.4.2-pfi-beta1`** 的 **MC 26.3 + Fabric 单端**移植（决策 D1，只保留 Fabric 端）。
- 身份：`mod id = pingforit`、`rootProject.name = Ping-For-It`（与上游 fork 一致）、Java 包 **`nx.pingwheel`**（D3）、maven group `isharmla.mod`。
- 版本：`0.4.2-pfi-beta1`，**必须符合 `x.x.x-pfi-qualifier`**（`config/PingForItVersion.java` 的正则）。
- 规模（**2026-11-03 重构后实测**）：`common/src/main` **162** java + `fabric/src/main` **4** java + `fabric/src/client` **80** java + `deferred/` **95** java（`deferred/` 完全不参与编译；client 是独立源集，与 common/main 不同一编译单元）。
- 进度：**M0–M6.5 已完成，并已恢复后实机重新验收**（tag `port-M6.5-restored`）；原 tag 随 `.git` 丢失，语义见 `docs/PORTING-PROGRESS.md`。**M5 未开始；M7 处于 M7-prep 阶段**。

## 2. 目录职责

> 历史文档（`docs/reviews/*`、`docs/PORTING-PROGRESS.md` 的 M0–M8 各节、`docs/survey/`、`docs/porting-plan.md`）可能仍写 `src/main` / `src/client` / 根 `build/libs` —— 那是重构前的事实，**以本节为准**。

```
build.gradle / settings.gradle / gradle.properties   构建入口（id/version 唯一来源）；settings 只 include common、fabric
buildSrc/                          Gradle 约定插件：multiloader-common / multiloader-loader
gradle/wrapper/                    固定 Gradle 9.8.2
common/build.gradle                java-library + fabric-loom（splitEnvironmentSourceSets）
common/src/main/java/   162 java   双端共用业务层（marker/core/network/config/domain/math/…；含 VoiceChat 插件入口 common/integration/VoiceChatIntegration）
common/src/main/resources/         assets/pingforit（lang、音效、贴图）、icon.png
fabric/build.gradle                multiloader-loader + fabric-loom（split）；jar 任务合并 common 的 class 与资源
fabric/src/main/java/     4 java   加载器入口与平台实现（FabricMain、PlatformContext/Network/ServerEventServiceImpl）
fabric/src/main/resources/META-INF/services/   3 个服务端 ServiceLoader 条目（IPlatformContext/Network/ServerEventService）
fabric/src/main/templates/fabric.mod.json      元数据模板（构建时生成到 fabric/build/generated/modMetadata）
fabric/src/client/java/  80 java   仅客户端：HUD、输入、命令、平台适配、客户端 mixin、ModMenu entrypoint
fabric/src/client/resources/       pingforit.mixins.json（JAVA_25，client 列表）+ 2 个客户端 ServiceLoader 条目
forge/ neoforge/                   只有 README 的预留目录，**不在 settings.gradle**，不参与构建
deferred/                95 java   26.3 无对应原语的渲染/UI 子系统，不参与编译，M7 取回（原 96，M9-1 移出 `modmenu/`）
tools/compile-check.sh|.ps1        本地 javac 三遍校验（common → fabric main → fabric client）+ 服务端安全 grep 门禁
compile-libs/                      DistantHorizonsApi-<ver>.jar（**仅编译期**，不入库；方案 A，见 §7.15）
docs/                              porting-plan / PORTING-PROGRESS / features / reviews / team.md / restructure/
Mod_Project/                       上游源码（已重新解压，**.gitignore 排除**）
run/ build/ .gradle/ .idea/        运行与构建产物（gitignore）
```

## 3. 构建与运行

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-25"
export GRADLE_USER_HOME="D:/Gradle"
./gradlew -g D:/Gradle build
./gradlew -g D:/Gradle :fabric:runClient
./gradlew -g D:/Gradle :fabric:runServer   # 首次需先把 run/eula.txt 改成 eula=true
```

- 产物在 **`fabric/build/libs/Ping-For-It-<version>-fabric-26.3.jar`**：`fabric` 的 `jar` 任务把 `common` 的 class 与资源合并进同一个 jar，所以仍是单 jar 发布（不是 JiJ）。
- `./gradlew buildAll` 是等价的聚合入口（根 build.gradle 里 `dependsOn ':common:build', ':fabric:build'`）。
- dev 运行目录仍是**仓库根 `run/`**（`fabric/build.gradle` 的 `runs { client/server }` 显式 `runDir(rootProject.file('run'))`）；`run/mods` 既是离线编译 jar 目录也是运行时 mods 目录。
- **根项目没有 Loom 的 run 任务**：重构后根 `build.gradle` 只 `apply false` 引入 loom，**根项目不是 loom 项目**，所以 `./gradlew runClient` / `runServer` 会直接失败。用户 2026-11-03 实测原文：
  ```text
  Cannot locate tasks that match ':runServer' as task 'runServer' not found in root project 'Ping-For-It'.
  ```
  Loom 只在 `fabric` 子项目里建这两个任务 → **显式写法**是 `./gradlew -g D:/Gradle :fabric:runClient` / `:fabric:runServer`。
- **根级 `runClient`/`runServer` 现在是可用的转发别名**（2026-11-04 起，根 `build.gradle` 注册，`dependsOn ':fabric:runX'`，本身没有 action）：`./gradlew -g D:/Gradle runClient` 也能用。但**显式 `:fabric:runX` 仍是推荐写法** —— 别名的报错信息与任务归属不如显式直白（出错时看不出是哪个模块，IDE 里也容易点错）。
- **`common` 的 run 任务被禁用**：`common` 是纯库模块，`common/build.gradle` 末尾用 `tasks.matching { it.name == 'runClient' || it.name == 'runServer' }.configureEach { enabled = false }` 关掉，否则两个客户端会抢同一个 `run/` 目录。**不要删这段**。
- **首次 `:fabric:runServer` 需要 eula**：第一次跑会在仓库根 `run/` 生成 `eula.txt`（`eula=false`），改成 `eula=true` 再跑一次即可 —— 但**必须用 ASCII 写**（PowerShell 默认 UTF-16 会让服务端读不到，见 §7.20）。V18 已于 2026-11-04 按此通过。

**沙箱限制**：DSH 会话内跑不了 Gradle；本地回归用 `tools/compile-check.sh`（三遍直接 javac），真实产物由用户在终端跑 `gradlew clean build`。

**compile-check 全绿 ≠ `gradlew build` 能过**：`compile-check` 用 `D:/Gradle/caches/modules-2` 的全量 jar 拼 classpath，Fabric API / Loader 的类永远在盘上，所以**依赖坐标写错**（例如漏掉 `fabric-api:` 段）javac 完全不会报错，只有 Gradle 解析 `compileClasspath` 时才会挂。这类错误只能靠人审坐标或真实构建；改完依赖行用 `grep -rn 'fabric-api:' --include=*.gradle .` 之类的三段式自检。

## 4. 构建脚本要点

- 布局：`settings.gradle` 只 `include('common')` / `include('fabric')`；`forge/`、`neoforge/` 是**未启用的预留目录**（只有 README，未 include）。加模块的前提是先补对应工具链，见各自 README。
- 约定插件在 `buildSrc/src/main/groovy/`：`multiloader-common.gradle`（Java 25 / 产物名 / 公共 `compileOnly` / `run/mods` 集成 jar 的 glob + `attachIntegrationJarCheck`）、`multiloader-loader.gradle`（`implementation project(':common')`）。模块自己的 `build.gradle` 只写插件、loom 配置与依赖。
- `splitEnvironmentSourceSets()`：`common` 与 `fabric` 都开；**`common/src/main` 绝不能 import `net.minecraft.client.**` / `com.mojang.blaze3d.**`**（编译期由 split 源集拦截，`tools/compile-check.sh|.ps1` 另有一道 grep 门禁，命中即 exit 1）。
- `fabric/build.gradle` 的 `loom.mods { "pingforit" { sourceSet main; sourceSet client } }`；`runs.client/server` 显式 `runDir(rootProject.file('run'))` + `ideConfigGenerated(true)`（fabric 不是 root project，Loom 默认只在 root 生成 IDE run 配置），运行目录仍是根 `run/`。**推荐 `./gradlew :fabric:runClient` / `:fabric:runServer`**（根级同名任务是转发别名，见 §7.18）；首次 `:fabric:runServer` 要先把仓库根 `run/eula.txt` 改成 `eula=true`。
- `fabric/build.gradle` 的 `processResources` 从 `:common` 合并资源（dev 的 mod 资源只认本模块源集，见 §7.16）；`jar` 只合并 `:common` 的 `classesDirs`，资源不重复打包。
- `generateModMetadata`（**Sync** 任务，在 `fabric/build.gradle`）：模板 `fabric/src/main/templates/fabric.mod.json` → `fabric/build/generated/modMetadata` 并替换版本；`inputs.property "version"` 必须保留；不要改回 `processResources { filesMatching { expand } }`。
- 编译选项在 `multiloader-common.gradle`：`options.release = 25` + `withSourcesJar()`。
- 产物名：`base { archivesName = 'Ping-For-It' }`（约定插件）+ `fabric/build.gradle` 的 `jar { archiveClassifier = 'fabric-26.3' }`（no-remap 插件下没有 `remapJar`）→ `fabric/build/libs/Ping-For-It-<version>-fabric-26.3.jar`。
- 集成 jar 检查挂在真正需要它的编译任务上：`attachIntegrationJarCheck('compileJava', 'voicechat-', ...)`（common）、`attachIntegrationJarCheck('compileClientJava', 'modmenu-', ...)`（fabric）。

## 5. 版本矩阵

| 组件 | 值 |
| --- | --- |
| Minecraft | `26.3`（class major 69） |
| Java | `25`（release 25） |
| Fabric Loader | `0.20.1` |
| Fabric API | `0.165.0+26.3` |
| Loom | `1.19.3`（no-remap 插件） |
| Gradle | `9.8.2` |

## 6. 已验证结论

| 验证点 | 证据 |
| --- | --- |
| 构建通过（事故前） | `Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`，346 class / major 69 / `id=pingforit` |
| 启动不卡加载条 | 20:11:23 启动 → 20:11:34 `Sound engine started` + 12 atlas |
| 进世界 + 闭环 | 22 对 `send ping` / `marker created` 1:1 |
| M6 轮盘 | 3 次 select → send → marker，pingType 三方 3/3 一致 |
| M6.5 HUD | 菱形/物品图标+装饰/三段文本+头颅/2.5px 箭头，与上游逐项对齐；`compile-check` main 264 class / client 85 class，0 error |
| **恢复后（2026-10-30 12:07）** | `compile-check` 两源集 **0 error**；`gradlew clean build` **通过**；jar **649 KB / 345 class / major 69 / id=pingforit**；CI workflow 已改为本项目的 `./gradlew build` + 上传 `Ping-For-It-*.jar` |
| **恢复后实机验收（2026-10-30 12:30–12:33）** | `run/logs/2026-10-30-7.log.gz`：**37 条 `send ping` / 36 条 `marker created`**（差 1，无 reject/cancel/异常）；**轮盘 8 次 select，8/8 与随后 `send` 的 `pingType` 一致**；类型覆盖 `attention`/`danger`/`go_to`/**`loot`**；**0 条模组相关 ERROR**（5 条 ERROR 均为 Realms/Minecraft Services 噪声）、**0 条 HUD 失败**；12:33:04 正常 `Stopping!` |
| **M9-1 实机验收（2026-11-03 18:27–18:30）** | `run/logs/latest.log`：Mod 列表含 `modmenu 21.0.0` + `pingforit 0.4.2-pfi-beta1`（且上一轮 voicechatprofiles 的 `Incompatible mods found` 已消失）；初始化顺序 `Init → ServerConfig → ClientConfig → ping outline pipeline registered → block outline pass registered`；**`send ping` / `marker created` = 28 : 28（1:1）**（复读全日志到 18:31:19 为 **34 : 34**）；`block model glow active` ≥1 条；**0 条模组相关 ERROR、0 条 `Mixin apply failed`**，12 条 WARN 全部归因于 DH/Sodium/PerfOS/voicechat；正常退出 |
| **P0 实机（2026-11-03 R1–R4，重构后）** | `run/logs` 四轮（20:58 / 22:13 / 22:15 / 22:28）：**0 条本模组 ERROR**、**0 条 `Mixin apply failed`**、0 条 `ClassNotFoundException: nx.pingwheel`；VoiceChat `Loaded 1 plugin(s)` + `Registering events for 'pingforit'`；闭环 **32 : 32**（R4，严格 1:1，0 reject/deny）；资源包索引含 `pingforit`（`debug.log:1713/1770`）；`Missing sound for event` **0**。**唯一 1 条本模组 WARN**（`entity-block glow: model hook not observed on 120 glowing dispatches`）经 3号 审计判定为**营火误报**（`CampfireRenderer` 只 `submitItem`、从不 `submitModel`；且营火有 C1 烘焙模型兜底），见 `docs/reviews/61-model-hook-warn-audit.md`。逐轮明细见 `docs/restructure/03-p0-log-audit.md` |
| **V18 专用服务器（2026-11-04 16:52）** | `gradlew.bat -g D:\Gradle runServer`（**根级别名生效**，Gradle 执行 `:fabric:runServer`）→ `Starting minecraft server version 26.3` → **`Done (2.998s)! For help, type "help"`** → `stop` → `Stopping server` 正常退出。**47 mods**；本模组服务端路径 `[PingForIt] Init` + `Loaded config type=ServerConfig`（common 源集在专用服务器跑通、无客户端类误引）；voicechat 服务端侧 `Loaded 1 plugin(s)` / `Initialized 1 plugin(s)` / `Registering events for 'pingforit'`；**0 条 ERROR/FATAL、0 条 `NoClassDefFoundError`/`ClassNotFoundException`、0 条 `Mixin apply failed`**；WARN 3 条全外部（PerfOS 注册表、voicechat debug mode、DH 的 G1 GC 提示）。日志：`run/logs/latest.log:102/107/157/170` |

## 7. 已知坑与约束

1. **`deferred/` 不在编译路径**：类找不到是正常的，M7 取回；不要为「修红」重写。
2. **26.3 的 HUD/渲染 API 与 1.21.1 完全不同**：HUD 走 `HudElementRegistry.addLast` + `GuiGraphicsExtractor`；旧 `WorldRenderEvents`/`MultiBufferSource`/`OutlineBufferSource`/`RenderStateShard`/`LevelRenderer.renderLevel` 都不存在。
3. **`InputConstants` 是 SDL 语义、鼠标键 1 基**：`MOUSE_BUTTON_LEFT=1 … MOUSE_BUTTON_5=5`、`KEY_LSHIFT=225`，`Type` 只剩 `KEYBOARD/MOUSE`。
4. **配置版本号必须是 `x.x.x-pfi-qualifier`**，否则配置迁移/入口异常。
5. **禁止用 fabric-resource-loader v0 注册资源重载**（会让资源重载永远完不成、卡加载条）；M8 走 v1。
6. **`run/` 下日志与世界只读**：排障时复制出去。
7. **标记尺寸是「距离补偿」设计**：`scale = max(1, 2/d^0.3) * 0.5 * pingSize`，远处恒 0.5 倍是上游刻意行为。
8. **屏外箭头「最终 2.5px」是上游行为**，不要放大。
8.1 **保真铁律（用户 2026-10-30 明确）**：移植代码**不得改变上游源代码呈现的效果**。任何「看起来更好/更快」的替代都要求**视觉与行为 1:1**，否则不做；确有平台强制差异（如 `-1` 默认键）必须逐条登记在 `docs/reviews/24-fidelity-constraint-audit.md`。对 M7 的直接含义：**自定义 RenderPipeline 是复刻上游描边规格（3.75px / NO_DEPTH_TEST / TRANSLUCENT / NO_CULL / COLOR_WRITE）的唯一路线**，gizmo 只能作参照。
9. **26.3 物品渲染拆两步**：`item(stack,x,y)` 只画模型，必须再调 `itemDecorations(font,stack,x,y)` 才有数量/耐久条。
10. **不要再依赖 IDEA Local History 恢复**（只存打开过的文件内容）。
11. **`InputConstants.isKeyDown` 直接按值索引 SDL 扫描码缓冲区**：26.3 里 `isKeyDown(int)` 内部是 `SDL_GetKeyboardState().get(key)`，所以任何 KeyMapping 的默认键**绝不能写 `-1`**（上游 1.21.1 用 `Type.KEYSYM, -1` 是合法的，因为走 GLFW）。未绑定键必须用 `InputConstants.UNKNOWN.getValue()`（=0）。写 `-1` 会在世界加载完成、`KeyMapping.setAll()` 时抛 `IndexOutOfBoundsException` 闪退（2026-10-30 12:17/12:18 两次崩溃的根因）。

12. **26.3 客户端没有权限数据**：`LocalPlayer#permissions()` 恒返回 `PermissionSet.NO_PERMISSIONS`，唯一写入口 `LocalPlayer#setPermissions` 全版本无调用者（Fabric API 也不同步）。**任何界面/逻辑都不要拿客户端权限当门**，否则单人下恒 false；授权一律交给服务端回包（`ServerPlayer#permissions()` → `MinecraftServer.getProfilePermissions` 是实时且可靠的）。需要客户端前置判断时只用「连接可达性」。
13. **原版 Screen 控件层 ≠ HUD 层 ≠ 世界渲染层**：26.3 的 GUI 迁移经验分三层，互不通用。设置界面那批坑（`TabButton` 变抽象、`StringWidget` 删掉 `alignLeft/alignCenter` 且默认不居中、`OptionsList.Entry` 变具体子类、`EditBox.setFilter` 删除、`Tab.getLayout()` 新增、`Blaze3D.openPath` 取代 `Util.getPlatform().openFile`）**编译全部通过、运行也不报错**，只会静默走样。动界面之前先查 `docs/reviews/46-mc26.3-gui-api-map.md`。

14. **GUI 里抛一次异常 = 整屏冻结（不是崩溃）**：26.3 的 `Minecraft.renderFrame` 在 `acquireNextTexture()` 之后、`present()` 之前抛异常时，window surface 保持 acquired，之后每帧开头 `if (windowSurface.isAcquired()) return` 直接返回——**看起来就是卡死**，日志里只有一条异常。典型触发：退化控件框（宽度 0/1）进入 `ActiveTextCollector.acceptScrolling*` 的 `Parameters.withScissor`（**该路径不做屏幕裁剪**），退化为 2×1 的 scissor 被 `FrontendRenderPass` 拒绝。**任何自绘/滚动文字控件都必须自带退化框闸门**。案例与修法见 `docs/reviews/48-m8a-tab-freeze-review.md`。

15. **可选集成的编译期依赖分两类：`run/mods`（编译 + 运行时共用）与 `compile-libs`（仅编译期）**：本机 Gradle 没有可用网络，所以都不在 maven 里声明，而是直接吃本地 jar。
    - **`run/mods`（编译 + 运行时）**：`ext.integrationJars`（`buildSrc/src/main/groovy/multiloader-common.gradle:43-54`）用 `rootProject.fileTree(dir: 'run/mods', include: ["modmenu-*.jar", "voicechat-api-*.jar", "voicechat-*.jar"])` + `exclude "*-sources.jar", "*-javadoc.jar"` + `compileOnly`；`compileClientJava` → 前缀 **`modmenu-`**、`compileJava` → 前缀 **`voicechat-`**（前缀带连字符，避免 `voicechatprofiles-*.jar` 误满足）。
    - **`compile-libs`（仅编译期）**：`ext.integrationApiJars`（同文件 `:62-70`）指向 `compile-libs/`，include 只有 **`DistantHorizonsApi-*.jar`**，由 `attachIntegrationApiJarCheck("compileJava", "DistantHorizonsApi-", ...)` 检查。
    - **`run/mods` 里的完整 DH jar（`DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`）只做运行时，不得再进编译类路径** —— 它已被从 `ext.integrationJars` 的 include 里**剔除**（方案 A）；否则「编译期独立 API / 运行时完整 mod」的隔离失效，API 漂移也观测不到。
    - **两行日志要分清**：`run/mods` 命中打印 `compileOnly integration jars (<task>): [...]`；`compile-libs` 命中打印 **`compileOnly integration API jars (<task>): [...]`**。`tools/compile-check.sh|.ps1` 对应两行：`integration jars from run/mods: …` 与 **`integration API jars from compile-libs: …`**（0 命中只警告、不退出；真正的 fail-fast 在 Gradle 端）。
    - **缺 jar 的症状**：**已有检查的集成**（Mod Menu / VoiceChat / DH API）缺失会在**编译时、javac 之前**抛 `GradleException`（消息含 `Missing compile-only jar for …` 或 `Missing compile-time API jar for …` + 应放的文件名与下载 URL）——fail-fast，不是「先报一屏 package does not exist」；检查**刻意不放在配置期**，所以没有 jar 的 checkout 里 `./gradlew tasks` / `clean` / IDE sync 照常可用。**排查第一步永远是看那两行 jar 列表**，先确认命中了哪些 jar，再改代码。
    - **CI 必须自带**：`run/` 与 `compile-libs/` 都被 gitignore，全新 checkout 没有 jar，所以 `.github/workflows/build.yml` 在 build 前用 `curl` 补：`run/mods/` 放 `modmenu-21.0.0.jar` 与 **VoiceChat 完整 mod jar** `voicechat-fabric-2.6.27+26.3.jar`；**`compile-libs/` 放 `DistantHorizonsApi-7.2.0.jar`**（**不再**下完整 DH jar —— CI 只跑 `./gradlew build`、从不启动游戏，编译只需要 API）。新增集成时**三处要一致**：`ext.integrationJars` / `ext.integrationApiJars` 的 include + exclude、两个脚本的 glob + 排除、workflow 的 fetch 列表；另外要给该集成**自己的存在性检查**（`attachIntegrationJarCheck` / `attachIntegrationApiJarCheck`）。

16. **dev 里 common 的资源不在 mod root 上——必须合并进 fabric 的 processResources**（2026-11-03 重构后实机暴露）：机制是 **Fabric 的资源包只按「mod root」建索引，而 mod root = 根目录含 `fabric.mod.json` 的 classpath 条目**（`fabric-resource-loader` 的 `ModNioPackResources` 只走 `ModContainer.getRootPaths()`）。dev 的 `runClient` 里 `:common` 是以 **jar** 形式在 classpath 上（`run/logs/debug.log` 的 Knot classpath 可见 `common/build/libs/….jar`），jar 根没有 `fabric.mod.json`，所以它的 `assets/` 物理上在 classpath 却不会进资源包索引；mixin/SPI 不受影响（走 classloader 全 classpath 扫描）。所以只把 common 的资源塞进发布 jar 是不够的：`runClient` 里会出现未翻译的 key（`pingforit.chat.pingmsg`、`unit.pingforit.meters`…）、`Missing sound for event: pingforit:ping`、Mod Menu 图标丢失，而**同一份构建出来的 jar 完全正常**（jar 里 assets 齐全）。修法：`fabric/build.gradle` 的 `processResources { from project(':common').sourceSets.main.resources }`，并且 `jar` 只合并 `:common` 的 `classesDirs`（资源只经 processResources 进一次，否则 jar 里出现重复条目）。上游 fork 的 `multiloader-loader.gradle` 用同一招（`from(configurations.commonResources)`）。**判断依据**：看 `fabric/build/resources/main` 下有没有 `assets/`。

17. **improved transparency 打开时描边改投 `outline` 相位**（2026-11-03，登记 `24` **#19**）：`afterTerrain` 相位在 26.3 只有 `LevelRenderer.executeClassicTransparency` 会执行，而它在 `Options.improvedTransparency` 打开时整个被跳过；`SubmitNodeCollectionMixin` 因此在该模式下把描边投到 `outline` 相位（`hasAnyOutline() && shouldShowEntityOutlines` 触发，排在 OIT 之后）。默认值是 false，所以默认仍走 `afterTerrain`（已实机验证）——**默认路径不打任何新日志**，只有真的走到回退时才会打一条 info。门控 `shouldShowEntityOutlines = !camera.isPanoramicMode() && playerState.hasPlayer`，游戏内恒真（主菜单全景/无玩家时才为假）。**实测（2026-11-04，无 DH、OIT=true 的 57-mod 会话）：本环境从未走到该回退**（`improved transparency is ON` 0 条，而 mixin 已织入、pass 有 `submitted=0/1/2/3`），见 `24` **#19 的实测结论**。

18. **根项目没有 Loom 的 run 任务（2026-11-03 实测；2026-11-04 已加根级转发别名）**：重构后根 `build.gradle` 只 `apply false` 引 loom，**根项目不是 loom 项目**，所以**当时（2026-11-03）**根级 `./gradlew runClient` / `runServer` 会直接失败：
    ```text
    Cannot locate tasks that match ':runServer' as task 'runServer' not found in root project 'Ping-For-It'.
    ```
    Loom 只在 `fabric` 子项目里建这两个任务 → **显式写法**：`./gradlew -g D:/Gradle :fabric:runClient` / `:fabric:runServer`。
    **现状（2026-11-04）**：根 `build.gradle` 注册了转发别名（`['runClient','runServer'].each { tasks.register(it) { dependsOn ':' + 'fabric:' + it } }`），所以 `./gradlew runServer` 现在**可用**；但别名只是把 `:fabric` 的任务加进任务图（本身没有 action，不复制运行配置），出错时看不出属于哪个模块 → **仍推荐显式 `:fabric:runX`**。
    **首次 `:fabric:runServer`（用别名跑也一样）** 会在仓库根 `run/` 生成 `eula.txt`（`eula=false`），改成 `eula=true` 再跑一次；**务必用 ASCII 写**（§7.20）。V18 已于 2026-11-04 走这条路通过。

19. **装了 Distant Horizons 时 improved transparency 会被 DH 强制关闭**：`run/options.txt` 里写 `improvedTransparency:true` 也没用 —— DH 每次进世界都会打 `Disabling improved transparency... This is done to fix vanilla chunks (specifically water blocks) not fading into Distant Horizons LODs`（R4 `latest.log:270`；`:267`–`:269` 还依次关了 vanilla clouds / chunk fade）。我们读到的选项恒为 false → 走经典 `afterTerrain` 通道 → `improved transparency is ON …` **永不打印**。**影响**：V13/V14/V15 在 `run/mods` 里有 `DistantHorizons-3.3.4-26.3-fabric-neoforge.jar` 时**不可测**（不是路由没工作）。**验证方法**：把该 jar 临时移出 `run/mods`（源码 0 处 DH 引用、`build.gradle` 的检查只要求 `modmenu-`/`voicechat-`，移走不影响 `build`）→ 重进世界，确认日志里**没有** `Disabling improved transparency` → ping 方块应出现**恰好 1 条** `[PingForIt] improved transparency is ON: …`；测完放回。

20. **`run/eula.txt` 必须用 ASCII 写（2026-11-04 实测踩坑）**：第一次 `:fabric:runServer` 会生成 `eula.txt`，但用 PowerShell 的 `>` / `Out-File` 改写成 **UTF-16 LE + BOM**（文件头变成 `ÿþe\0u\0l\0a\0…`）时，服务端读不到 `eula=true` → 报 `You need to agree to the EULA…` 并退出。
    - **正确写法**：PowerShell 用 `"eula=true" | Set-Content -Encoding ascii run\eula.txt`；cmd 用 `echo eula=true>run\eula.txt`（**`>` 前不要留空格**，否则会写进一个尾随空格）。
    - **怎么确认**：`Format-Hex run\eula.txt` 应看到 `65 75 6C 61 3D 74 72 75 65 0D 0A`（即 `eula=true\r\n` 的纯 ASCII）；看到 `FF FE` 开头就是 UTF-16，需重写。
    - 这条与本项目代码无关，是**环境/工具链坑**；V18（2026-11-04）就是修好它之后才通过的。

## 8. 团队分工与写入范围

| 角色 | 职责 | 写入范围 |
| --- | --- | --- |
| 1号 repo-analyst | 清点、文档、日志归因 | `AGENTS.md`、`docs/` |
| 2号 implementer | 功能实现、构建脚本、资源 | `common/**`、`fabric/**`、`deferred/**` |
| 3号 code-reviewer | 独立审核（只读源码） | `docs/reviews/` |
| 4号 build-engineer | 构建、反编译、jar 核验、编译校验工具 | `build/`、`run/`、`tools/` |
| ~~5号 log-monitor~~ | **已退役**，职责并入 1号 | — |

> 花名册与退役记录：[docs/team.md](docs/team.md)。**Lead 直接承担部分实现与排障**，子代理只向 Lead 汇报。

## 9. 待办

| 阶段 | 待办 | 状态 |
| --- | --- | --- |
| 事故恢复 | 源码重建、compile-check 重建、文档重写、git 重建、**实机重新验收** | ✅ **完全收尾**（2026-10-30；基线 tag `restore-2026-10-29`，验收 tag `port-M6.5-restored`） |
| M5 | **M5 服务端权威：完善 + 双客户端验收**（受众/赢家/限流/移除/过期/重连） | ⬜ |
| M7 | 渲染重建：`deferred/` 描边/世界渲染按 26.3 管线重写 | ✅ **M7a 方块线框 + M7b 实体描边 + M7c C1 模型光晕完成并实机通过（2026-10-31）**；**M7c C2（entity_block 真实体光晕）✅ 完成并实机通过**（`020d920`/`b3ad60f`/`f88a887`，含契约对齐：渲染模式门控、不短路、RenderShape 前置）；规格 `33`/`35`/`38`/`40`、复审 `34`/`36`/`39` |
| M8 | 配置/命令/UI + 资源重载迁 v1 | ✅ **完成（2026-11-01）**：**M8a** 完整分页设置界面（6 客户端分类/25 项 + 3 服务端分类/5 字段，含 tab 冻结修复）实机通过；**M8b** 资源 v1 + 自定义贴图（默认路径通过，**自定义包待补测**）；**M8c** toggle notice 第二层 HUD 实机通过。文档：`46`/`47`/`48`/`49` |
| M9 | 可选集成（ModMenu/VoiceChat/FTB/DH/Sable） | **M9-1 ✅ 实现 + 实机通过（2026-11-03）**：Mod Menu 类从 `deferred/` 迁回 `src/client`（与上游逐字节一致）、`fabric.mod.json` 加 `modmenu` entrypoint、依赖改 `run/mods` 离线 jar；**M9-2 ✅ 实现 + 实机通过（2026-11-03）**：`VoiceChatIntegration`/`VoiceChatWrapper` 与上游逐字节一致、`fabric.mod.json` 加 `voicechat` entrypoint、`build.gradle` 加 `compileJava` 的 `voicechat-` jar 检查；实机证据：R1 起每轮 `Loaded 1 plugin(s)` + `Initialized 1 plugin(s)` + `Registering events for 'pingforit'`（不再是从前的 0）。**M9-3 ✅ 实现 + 复审通过（2026-11-04）·实机待验**：`DistantHorizonsIntegration` 由 76 行 stub 换回**上游 201 行实现**（`rootCause` 未动、调用点零改动），**方案 A** = 编译期只吃 `compile-libs/DistantHorizonsApi-7.2.0.jar`（Modrinth 官方件，363 318 B）、运行时仍用 `run/mods` 的完整 jar；复审 `docs/reviews/64-m9-3-dh-integration-review.md`（通过、无阻塞）；FTB Teams / Sable **平台强制跳过**（`24` #17/#18） |
| **P0（重构后验收）** | 多模块重构（`f18e31e`）后的实机回归 | **✅ P0 收口（V13–V15 受限未验证）**：V1/V4/V9/V12/V16/V17 ✅、**V18 ✅（2026-11-04 16:52，`Done (2.998s)!` / 47 mods / 0 ERROR）**；V6/V7/V11 🟡（需肉眼/留证）；**V13–V15 ⏸ 受限未验证**（见 `24` #19 实测结论；描边仍可见、仍穿墙，仅半透明叠加时可能有染色差异）；V19 ⏸ 可选。逐项见 `docs/PORTING-PROGRESS.md` §P0 |
| M10 | 验证与发布：`runServer`、CI、测试基座、LICENSE → MIT + THIRD-PARTY-NOTICES | ⬜ |

**任务顺序（2026-10-31 用户确认）**：**M8 → M7c-C2 → M9 → M10**（M8 ✅；C2 实现完成待实机，实机通过后进 M9；`entity_block` 已改为真实 BER 光晕，线框仅作 fallback）。

## 9.1 待完成清单（2026-11-04 盘点）

> M0–M8、M9-1、M9-2 已完成；**P0 已收口**——除 **V13–V15（⏸ 受限未验证）** 与 **V19（⏸ 可选）** 外全部通过。

| 分组 | 项 | 验收方式 | 状态 |
| --- | --- | --- | --- |
| A · P0 收尾 | **V13–V15 OIT 描边路由**：移走 DH → 开 Improved Transparency → ping，日志应出现**恰好 1 条** `improved transparency is ON …` | 实机日志 | ⏸ **受限未验证**：已尝试两项修复（门控改读 `Options.improvedTransparency`、`isCustom` 改「实例 → 注册名回退」）仍未触发；结论与影响见 `docs/reviews/24-fidelity-constraint-audit.md` **#19 实测结论**（描边仍可见、仍穿墙，仅半透明叠加时可能有染色差异） |
| A · P0 收尾 | **V19 发布 jar 干净安装**：干净 Fabric 26.3 实例只放本 jar + fabric-api，启动查图标/中文/ping | 实机启动 | ⏸ 可选 |
| A · P0 收尾 | **V6 / V7 / V11 肉眼确认**：Mods 列表图标、设置界面中文、ModMenu 配置按钮 | 实机截图 | 🟡 日志级已过，待肉眼留证 |
| B · 主线功能 | **M9-3 Distant Horizons 集成**：stub → 上游真实现（异步远距离射线 + `ModContext.HasDistantHorizons` + 失败降级） | 装/不装 DH 各跑一次 | 🟡 **实现 + 复审通过（2026-11-04，方案 A），实机待验**（`64` 通过、无阻塞；待验见 §9 的 M9 行与 `PORTING-PROGRESS` §M9-3） |
| B · 主线功能 | **M5 服务端权威验收**：受众快照 / 赢家选择 / 移除仅本人 / 限流 / 过期 / 断线重连 | **两个客户端互测** | ⬜（代码已存在） |
| C · M10 发布 | **LICENSE → MIT** + `THIRD-PARTY-NOTICES` 并入 jar | 检查 jar 内 `LICENSE_pingforit` 与 notices | ⬜ |
| C · M10 发布 | **CI 真实跑绿**：`clean build` + 产物上传 + jar 元数据检查 | GitHub Actions 绿 | ⬜ **从未真正跑过**（本机无网）——**发布前唯一外部未知项** |
| C · M10 发布 | **188 个测试类**基座重建 | `gradlew test` | ⬜ |
| C · M10 发布 | **locale 补键**：`zh_cn` 少 5 条用键 + 8 条死键，并全量扫描 8 个语言 | 脚本比对 key | ⬜ |
| C · M10 发布 | **性能回归**：繁忙世界（大量 BER/实体）下 HUD/光晕开销 | 对比空闲/繁忙帧时 | ⬜ |
| C · M10（技术债） | `GameContext.lastWorld` 静态 `ClientLevel` 断线后不清空（`docs/reviews/57-pipeline-conflict-audit.md` **S1**）→ 内存滞留 | 改代码 + 断线复测 | ⬜ |
| C · M10（技术债） | 5 处 mixin 仍 `require=1`（同 `57` **H5**）→ 未来 MC 签名变动会**启动崩溃**而不是降级 | 评估改 `require=0` + 诊断 | ⬜ |
| C · M10（技术债） | 诊断信号变弱（`entity-block glow` 的 WARN 已降 DEBUG）→ 真回归只能靠视觉 | 2号 补强方案（`resolved`/`consumed` 计数 + 首次消费 DEBUG） | ⬜ |
| C · M10（技术债） | **DH × OIT 互斥策略**：装 DH 的玩家永远走不到 `outline` 相位分支 | 加诊断开关强制走，或在 `docs/reviews/24-fidelity-constraint-audit.md` 登记为已知行为 | ⬜（**M9-3 已实现（方案 A），此项待决**） |
| D · 遗留 / 可选 | **M8b 自定义资源包补测**（替换 `assets/pingforit/textures/ping.png`） | 实机贴图 | ⏸ 等用户提供资源包 |
| D · 遗留 / 可选 | **OIT 复测**：透明物体叠加顺序 | 实机 | ⏸ 可选 |
| D · 遗留 / 可选 | `render/spike/OutlineExperiment` 清理 | 删除并确认无引用 | ⏸ 用户已确认保留 |
| D · 遗留 / 可选 | **`改进方案.md` 里的用户条目** | 用户逐条确认 | ⏸ 用户暂缓 |

### 发布门禁（M10 收尾前必须满足）

- **分组 A / B / C 全部 ✅**（V19 可选，但若不做，**必须在发布说明里注明未做**）。
- **必须重新通读 `改进方案.md`**：那是用户自己写的待办与想法，**发布前逐条给出结论**——「已实现 / 明确不做（写原因）/ 转为后续版本」；**任何一条没有结论都不得发布**。
- **CI 在真实 GitHub Actions 上绿过一次**，并核对上传的 jar 名称/大小与本地一致。
- `AGENTS.md` §10 的自检清单全过，且 `docs/PORTING-PROGRESS.md` 的剩余工作总表与本清单一致（**若不一致，以本清单为准**并同步过去）。

## 9.2 M7-prep 实验（已归档，仅存结论）

> 原「当前任务（M7-prep）」的四项实验（E6 / E1 / E2 / E3）已于 2026-11-04 **归档**：结论并入 M7a–M7c 与本文件 §7；**该节的 🔄 状态不再跟踪**（原表格已删除）。

- **E2（自定义无深度管线）**：✅ 实机通过（2026-10-30，mode 4），直接用于 **M7a**（`RenderTypeAccessor` + `RenderPipelinesAccessor` 的自定义 pipeline + `afterTerrain` 路由），并被 M7b / M7c 沿用。
- **E1（线框遮挡语义）**：结论已写进 `docs/PORTING-PROGRESS.md` 的 M7-prep 节——实为**两档**（depth write 开/关），且 `afterTerrain` 对 alpha=255 不生效。
- **E3（gizmo 对照）**：❌ **未采用**——线宽/混合/剔除/深度全由原版决定，不满足保真铁律，只能作参照。
- **E6（`deferred/client/outline` 试编译普查）**：已被 M7a / M7b / M7c 的实际重写取代，不再单独跟踪。

## 10. 改动后自检

- [ ] `gradlew -g D:/Gradle build` 通过，产物 `fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`
- [ ] jar 内 `fabric.mod.json` 的 `id=pingforit`、version 与 `gradle.properties` 一致；mixins 对象形式且 `environment: client`
- [ ] **合并后的 jar 内容完整**（资源经 `fabric.processResources` 合并 `:common` 资源、class 经 `jar.from(:common classesDirs)`）：`nx/pingwheel/common/**` 与 `nx/pingwheel/fabric/**` 都在、`pingforit.mixins.json` 在、`assets/pingforit/**` + `icon.png` 在、`LICENSE_pingforit` 在、`META-INF/services` 五份 SPI 各一份、`unzip -l | sort | uniq -d` 无重复条目。dev 的 `runClient` 走 runtimeClasspath，**通过它不能证明 jar 合并正确**。
- [ ] `runClient` 不卡加载条，无 `Mixin apply failed`
- [ ] 进世界后按键产生成对 `send ping` / `marker created`
- [ ] `common/src/main/java` 下 grep 不到 `net.minecraft.client.` / `com.mojang.blaze3d`（`tools/compile-check.sh|.ps1` 的 gate 命中即 exit 1）
- [ ] 未把 `deferred/` 误加回编译路径

## 11. 移植阶段与估算

| 阶段 | 目标 |
|---|---|
| M0–M4 | 基线 / 构建骨架 / 源码导入与拆分 / 编译迁移 / MVP 闭环（已完成） |
| M6 / M6.5 | 轮盘与 7 类型（按 target type 分桶）/ 标记 HUD 上游对齐（已完成） |
| M7 | 渲染重建（5–10d，最大风险） |
| M8–M10 | 配置/命令/UI、可选集成、验证与发布 |

## 12. 移植决策记录

| # | 决策 |
|---|---|
| D1 | 只保留 Fabric 端 |
| D2 | mod id 改为 `pingforit` |
| D3 | Java 包名保留 `nx.pingwheel` |
| D4 | 产物名 `Ping-For-It-<version>-fabric-26.3.jar` |
| D5 | 暂时保留 ServiceLoader 平台层 |
| D6 | 不引入 Parchment |
| D7 | 移除 Lombok |
| D8 | 188 个测试类在 M10 重建 |
| D9 | 可选集成最后做 |