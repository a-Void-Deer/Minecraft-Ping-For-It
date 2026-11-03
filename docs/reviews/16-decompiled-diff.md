# 16 · 反编译基线最终收敛 + 最终 jar 核验

- 任务：task-15（4号）｜日期：2026-10-30｜基线 tag `port-M6.5-restored`（提交 `b7e7a39`，12:33:25）
- 对照物：`.tmp-decomp/old.jar`（10/26 我方构建，508 class，反编译 302 java）+ `.tmp-decomp/src`；当前 `build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`（12:34:52 构建，**晚于** 最新提交 → 不是旧 jar）
- 方法：vineflower 1.12 反编译当前 jar（223 java，`build/decomp-current`），与旧反编译源码逐类比较「方法名集合 + 字符串常量集合」，过滤 decompiler 噪音（`method_*`/`class_*`/lambda/record 展开）；判定依据 = 当前源码 grep + 阶段文档 + 实机验收结论
- 只读；未改 `src/**`、`deferred/**`

---

## 0. 结论先行

1. **jar 核验通过**：`664,740 B / 420 entries / 351 class / major 69`；class 拆分 `main 262 + client 89`，与 `tools/compile-check`（0 error, main 262 / client 89）**完全一致**；元数据/资源/LICENSE/SPI 全部正确（第 1 节）。
2. **反编译基线收敛**：10/26 报告的 **50 个差异类 → 36 个已收敛、14 个仍未对齐**；另有 1 个原先未列入的类（`IPlatformContextService`）被本次比对捕获。合计 **15 个剩余差异类**。
3. **15 个剩余差异全部可归因，判定为 0 个真实功能缺口**：M8（设置 UI/资源重载）2 项、M9（可选集成桩）6 项、26.3/D5/M4 已完成的改名与拆分 5 项、非缺口 2 项。明细见第 3 节。
4. **未取回的 89 个旧类全部是 deferred/子系统**（M7 77、M8 8、M9 4），不是丢失。
5. **2号 修复已生效**：`a083122` 触及的 8 个源码文件（任务口径称 7 个）在本次比对中已无方法缺失；`ServerCore`、`ClientCommandBuilder`、`LanguageUtils`、`ClientConfig` 等类已完全收敛。
6. 建议：本基线可继续 M7；剩余 15 项按第 3 节归属阶段处理。

---

## 1. 最终 jar 核验

| 项 | 期望/基线 | 实测（12:34:52 构建） | 判定 |
| --- | --- | --- | --- |
| 文件 | Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar | 664,740 B | ✓ |
| entries / class | 420 / **351** | 420 / 351 | ✓ |
| class 拆分 | main 262 + client 89 | compile-check: 262/89；jar 映射：main 259+3 secondary、client 88+1 secondary = 262/89 | ✓ |
| class major | 69（Java 25） | Global.class 第 7-8 字节 = 0x0045 = 69 | ✓ |
| fabric.mod.json | id pingforit、version 0.4.2-pfi-beta1、license MIT、icon icon.png、environment *、entrypoints main/client、mixins 对象 {config, environment: client} | 全部一致 | ✓ |
| LICENSE | MIT 全文，1,067 B | LICENSE_pingforit 1,067 B，首行 The MIT License (MIT) | ✓ |
| SPI | 5 个 META-INF/services/ | 5 个（IClientPlatformContextService / IPlatformClientEventService / IPlatformContextService / IPlatformNetworkService / IPlatformServerEventService），实现类均在 jar | ✓ |
| 资源 | lang×8 + sounds.json + ping.ogg + arrow.png + ping.png + icon.png | 12 个 assets 齐全；icon.png 29,966 B；ping.png 70 B（上游同款 1×1 占位） | ✓ |
| 构建新鲜度 | 必须晚于最新提交 | jar 12:34:52 > 提交 12:33:25 | ✓ |

---

## 2. 已收敛清单（50 → 36）

### 2.1 2号 补回的文件（`a083122`）

| 文件 | 改动 | 本次比对 |
| --- | --- | --- |
| client/.../CommonClient.java | 补 11 个 TODO(M7) 空壳方法 | 方法缺失清零（仅 onRenderGUI 改名，见第 3 节） |
| config/ClientConfig.java | 补 toString/canEqual/equals/hashCode | 完全收敛 |
| client/outline/BlockDisplayPolicy.java | 补 whitelistEntries/blacklistEntries/shouldUseNativeGlow 等 | 完全收敛 |
| client/outline/BlockDisplayWhitelist.java | 补校验/匹配方法 | 完全收敛 |
| client/outline/BlockDisplayWhitelistEntry.java / BlockTagLookup.java | 新增辅助类 | 完全收敛 |
| integration/DistantHorizonsIntegration.java | 补方法签名，保留桩 | 方法收敛；日志字符串属 M9 |
| integration/VoiceChatIntegration.java | 补插件方法签名，保留桩 | 方法收敛（字符串属 M9） |

> 任务口径写「7 个文件」，`a083122` 实际触及 **8 个源码文件**（多两个辅助类），以 commit 为准。
> 另有 `6ad0207`（轮盘诊断/M4 日志/世界加载崩溃）与 `d11eb5b`（越界键码容错），不属于反编译差异但已计入当前 jar。

### 2.2 完全收敛的 36 类（按 10/26 报告口径）

| 组 | 类 | 数 |
| --- | --- | --- |
| 网络 14 包 | MarkerCreate/Remove/WinnerChanged/Rejected/Removed、PingLocation C2S/S2C、RateLimitPolicy、ServerConfigRequest/Update/Snapshot、SyncDurationPolicy、UpdateChannel 等 | 14 |
| 配置/版本 | ConfigHandler、ConfigVersionUpdater、PingForItVersion、ClientConfig | 4 |
| 服务端/公共 | ServerCore、CommonServer、Global、AuthoritativeTargetValidation、MarkerCreateOutcome、TargetKey、EntityLocalGeometryRegistry | 7 |
| 资源/命令 | LanguageUtils、ResourceConstants、ClientCommandBuilder | 3 |
| 客户端其它 | ClientPingActionDispatcher、SelectedLocaleTranslationKeyCache、BlockDisplayPolicy、BlockDisplayWhitelist、SableExternalBlockLocator | 5 |
| 集成/交互/名称 | VoiceChatIntegration、CancelCandidatePicker、TargetNameJson | 3 |
| 合计 | — | **36** |

---

## 3. 仍未对齐的 15 个类（逐条判定）

| # | 类 | 缺失内容（相对 10/26） | 判定 | 归属 | 证据/理由 |
| --- | --- | --- | --- | --- | --- |
| 1 | client/ClientPingRuntime | 方法名 WorldVector | **非缺口（假阳性）** | — | 当前源码仍有 private WorldVector candidatePosition(...)（grep 命中）；差异来自 decompiler 把返回类型/构造器当方法名 |
| 2 | common/CommonClient | 方法 onRenderGUI | **M4 重构** | M4 已完成 | HUD 已改为 onRenderHud + HudElementRegistry（AGENTS 第 7 节第 2 条） |
| 3 | config/ServerConfig | canEqual、toString 括号格式 | **非缺口（Lombok/格式）** | — | 手写 equals/hashCode/toString 均在（ServerConfig.java:91-118）；canEqual 为 Lombok 产物（D7 已移除）；toString 由 {} 改成 () 无解析依赖 |
| 4 | integration/DistantHorizonsIntegration | 5 条日志/版本字符串 | **M9 桩** | M9 | 字符串仅在 TODO 注释（:31-32,68,73） |
| 5 | integration/FTBTeamsWrapper | 字符串 ftbteams | **M9 桩** | M9 | 无依赖桩，mod id 探测 M9 恢复 |
| 6 | integration/sable/client/SableClientProvider | 约 40 方法/60+ 字符串 | **M9 桩** | M9 | 当前 71 行 stub；完整 485 行在 deferred；无 Sable API 依赖 |
| 7 | integration/sable/server/SableExternalBlockServerProvider | 反射 API 全套 | **M9 桩** | M9 | 需 dev.ryanhcode.sable 类，M9 接入 |
| 8 | integration/TeamContextHandler | getSelfContext | **M9 桩** | M9 | FTB Teams 集成 M9 |
| 9 | integration/VoiceChatWrapper | 字符串 voicechat | **M9 桩** | M9 | entrypoint 也未加，M9 恢复 |
| 10 | mixin/MinecraftMixin | 字符串 setScreen | **26.3 改名** | — | 26.3 目标 setScreenAndShow（当前 @Inject 已改） |
| 11 | platform/IPlatformContextService（新捕获） | registerKeyMapping | **D5 平台拆分** | 已完成 | 已移到 IClientPlatformContextService/PlatformClientContextServiceImpl |
| 12 | screen/SettingsScreen | 60+ 方法/80+ 设置键 | **M8** | M8 | 当前 42 行桩；完整 UI 在 deferred/screen 7 文件 |
| 13 | util/InputUtils | 字符串 key.category | **26.3 API 变化** | 已完成 | 26.3 用 KeyMapping.Category.register(Identifier...)（InputUtils.java:17-20） |
| 14 | fabric/FabricClient | IdentifiableResourceReloadListener#getFabricId、registerReloadListener | **M8** | M8 | v0 会卡加载条（AGENTS 第 7 节第 5 条），已摘除，M8 走 v1 |
| 15 | fabric/platform/PlatformContextServiceImpl | registerKeyMapping | **D5 平台拆分** | 已完成 | 同 #11 |

### 3.1 判定汇总

| 判定 | 数量 | 类 |
| --- | --- | --- |
| 非缺口（假阳性/格式） | 2 | ClientPingRuntime、ServerConfig |
| M8（UI/资源重载） | 2 | SettingsScreen、FabricClient |
| M9（可选集成） | 6 | DH、FTBTeamsWrapper、SableClientProvider、SableExternalBlockServerProvider、TeamContextHandler、VoiceChatWrapper |
| 已完成的重构/改名 | 5 | CommonClient(M4)、MinecraftMixin(26.3)、IPlatformContextService/PlatformContextServiceImpl(D5)、InputUtils(26.3) |
| **真实功能缺口** | **0** | — |

---

## 4. 未取回的 89 个旧类（deferred 口径）

| 分组 | 数量 | 归属 | 说明 |
| --- | --- | --- | --- |
| client/outline/** | 62 | **M7** | 描边/几何/呈现解析（含 Flywheel 8、ExternalBlock 2） |
| render/** | 9 | **M7/M8** | DirectionIndicator/DrawContext/WheelOverlay 等 |
| mixin/** 5 个 | 5 | **M7** | GameRenderer/Gui/LevelRenderer/ModelBlockRenderer/DisplayAccessor |
| fabric/integration/FabricWorldAwareBlockModelOutlineAdapter | 1 | **M7** | 描边后端 |
| screen/** 7 + resource/ResourceReloadListener | 8 | **M8** | 设置 UI 与资源重载 |
| integration/sable（client companion/internal/log gate） | 3 | **M9** | 完整 Sable 实现（留 deferred） |
| fabric/integration/ModMenuIntegration | 1 | **M9** | ModMenu 集成 |
| 合计 | **89** | — | 全部为有意 defer，不是丢失 |

> 旧 jar 508 class / 302 java；当前 351 class / 223 java；差额 = 上述 89 个类（含内部类）+ M 阶段新增类（第 5 节）。

## 5. 新增（旧 jar 没有、当前有）的 10 个类

ClientTeamContextHelper、ClientFrameWatchdog、MarkerHudRenderer、MarkerLabelRenderer、WheelHudRenderer、WheelSelectionDiagnostics、ModLogFile、ProtocolLimits、IClientPlatformContextService、PlatformClientContextServiceImpl

---

## 6. 待确认 / 建议

| # | 事项 | 建议 |
| --- | --- | --- |
| 1 | 3号 task-14（17 号复核）尚在进行 | 本文只做差异归类；R1/R2/R3 副作用以 17 号为准 |
| 2 | FabricClient 资源重载 TODO(M8) | M8 用 v1 API 恢复并实测不卡加载条 |
| 3 | 设置 UI/命令/资源 = M8；DH/VoiceChat/FTB/Sable/ModMenu = M9 | M7 不要顺手接这些 |
| 4 | contact 为空、THIRD-PARTY-NOTICES 未入 jar | M10 合规收尾（15 号第 6 节 Q4） |
| 5 | ping.png 70 B 为上游同款占位 | 不修 |

---

## 7. 复跑命令（只读）

```bash
cd F:/Minecraft_Mod_Project/Minecraft_26.3/Minecraft_26.3_Fabric
rm -rf build/decomp-current
"/c/Program Files/Java/jdk-25/bin/java.exe" -jar .tmp-decomp/vineflower.jar \
  build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar build/decomp-current
unzip -l build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar | tail -1   # 420 files
unzip -l build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar | grep -c "\.class$" # 351
unzip -p  build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar fabric.mod.json
unzip -p  build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar LICENSE_pingforit | head -2
bash tools/compile-check.sh   # main 262 / client 89, 0 error
```

---

## 8. 附注：为什么判 0 真实缺口

- 所有「方法缺失」项逐条核对：或当前源码存在（candidatePosition、手写 equals/hashCode/toString），或已被 26.3/D5 显式替代（setScreenAndShow、KeyMapping.Category、client 侧 registerKeyMapping）；
- 所有「字符串缺失」项都落在已声明桩（DH/FTB/VoiceChat/Sable/TeamContext/SettingsScreen/FabricClient 资源重载）或格式/API 变化（ServerConfig toString、setScreen、key.category）；
- 实机验收（12:30–12:33，37 send / 36 marker、8/8 轮盘类型一致、0 模组错误）覆盖 M4/M6/M6.5 主链路，与静态结论一致。

