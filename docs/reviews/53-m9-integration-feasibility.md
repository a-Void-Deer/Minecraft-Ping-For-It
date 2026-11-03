# 53 · M9 可选集成可行性调研（MC 26.3 / Fabric 单端）

- 作者：1号 repo-analyst｜日期：2026-11-01｜阶段：M9 前置调研
- 范围：只读 `src/`、`Mod_Project/`（上游 0.4.2-pfi-beta1）+ 联网元数据（Modrinth API / maven metadata / DH GitLab 源码）
- 限制：本会话**跑不了 Gradle**，下列坐标**未做真实依赖解析**；只核验了「版本存在 + 目标 API 签名仍在」。M9 第一步必须先跑一次 `./gradlew build` 验证 compileOnly 可解析。

> **落地状态（2026-11-02）**：M9-1（Mod Menu）已按 §三 方案落地（依赖改为 `run/mods` 本地 jar，详见 `docs/PORTING-PROGRESS.md` 的 M9-1 节）；M9-2 / M9-3 未开始。**本文正文保持 2026-11-01 调研快照，未改动。**

## 一、结论速览

| # | 集成 | 26.3 可用 | 结论 | 一行理由 |
|---|---|---|---|---|
| 1 | Mod Menu | ✅ 21.0.0（2026-09-23） | **做**（最先） | 只需 1 个 13 行类 + 1 个 entrypoint，零 API 迁移 |
| 2 | Simple Voice Chat | ✅ fabric-2.6.27+26.3（2026-10-28） | **做** | API 面只用 UUID，上游实现可逐字照搬 |
| 3 | FTB Teams | ❌ 最高 26.1.2（2026-08-24） | **跳过** | 无 26.3 构建 → 无法编译/验证 |
| 4 | Distant Horizons | ✅ 3.3.4-26.3（2026-09-29）/ API 7.2.0 | **做** | 目标 API 签名与上游逐字一致，纯代码回填 |
| 5 | Sable | ❌ 仅 1.21.1（最新 2.0.6，2026-10-03） | **跳过** | 专用于 1.21.1 子关卡/物理，26.3 无构建 |

## 二、逐项详表

| 集成 | 26.3 可用性（版本 / 日期 / 来源） | 依赖坐标 + 装配 | 我们桩的差距 | 结论 |
|---|---|---|---|---|
| **Mod Menu** | `modmenu 21.0.0 for 26.3`，2026-09-23，release，loaders `fabric/quilt`，`client_only`（Modrinth `/project/modmenu/version?game_versions=["26.3"]`）；`maven.terraformersmc.com` 上取到 `modmenu-21.0.0.pom` | `com.terraformersmc:modmenu:21.0.0` + repo `https://maven.terraformersmc.com/releases`，**compileOnly**（上游 `fabric/build.gradle:22` 用 4.1.2，同一套写法）；`fabric.mod.json` 加 `entrypoints.modmenu` | 类整体迁到 `deferred/modmenu/ModMenuIntegration.java`（package `nx.pingwheel.fabric.integration`，未在编译路径）；`src/main/templates/fabric.mod.json:12-15` 只有 `main`/`client`。**好消息**：`SettingsScreen(Screen lastScreen)` 已存在（`src/client/java/nx/pingwheel/common/screen/SettingsScreen.java:131`），与 `ConfigScreenFactory` 签名天然匹配，恢复时不用改设置界面 | **做** |
| **Simple Voice Chat** | `fabric-2.6.27+26.3`，2026-10-28（Modrinth `simple-voice-chat`）；API 独立版本 `de.maxhenkel.voicechat:voicechat-api:2.6.27`（maven metadata `latest=2.6.27`，目录内含 `-fabric-stub.jar`） | `de.maxhenkel.voicechat:voicechat-api:2.6.27` + repo `https://maven.maxhenkel.de/repository/public`，compileOnly；官方 API 文档建议 `fabric.mod.json` 的 `depends` 补 `voicechat_api`（上游没加，只挂 `voicechat` entrypoint）；运行时 `isModLoaded("voicechat")` 已有（`ModContext.java:17`） | `VoiceChatIntegration` 只剩 `PLUGIN_ID` + 两个 `Object` 句柄 + 空体回调（`src/main/java/nx/pingwheel/common/integration/VoiceChatIntegration.java:16-45`）；`VoiceChatWrapper` 恒 `Optional.empty()`（`:18-24`）；`fabric.mod.json` 的 `voicechat` entrypoint 已删。上游实现仅 37 行（`VoicechatPlugin` + 2 事件回调），我们使用的 API 面只有 `getConnectionOf(UUID).getGroup().getId()` 与 `clientApi.getGroup().getId()` — **全是 UUID/纯 Java 类型，不碰 MC 类** | **做** |
| **FTB Teams** | **不存在 26.3 版**。FTB 官方 maven `dev.ftb.mods:ftb-teams-fabric`：26.x 线最后一条 `26.1.2.4`，整体 `latest=2101.1.11`（1.21.1 线），metadata `lastUpdated=2026-08-24`（**早于** 26.3 的 2026-09-15 发布）；第三方索引页同样标注支持到 `[26.1.2]` | 若将来有：上游用 `curse.maven:ftbteams-404468:6930910`（cursemaven），compileOnly | `FTBTeamsWrapper` 恒 `empty`（`:18-24`）。降级链 `TeamContextHandler` 已写好 4 级回落（VoiceChat > FTB > 原版队伍 > NONE，`:15-30`），缺的只是真实调用 —— **跳过它不会破坏任何行为**，等价「未安装」 | **跳过** |
| **Distant Horizons** | `3.3.4-26.3`，2026-09-29，loaders `fabric/neoforge`（Modrinth `distanthorizons`）；API 包 `DistantHorizonsApi 7.2.0`，2026-09-21（`api.modrinth.com/maven` metadata `latest=7.2.0`，与 DH 3.3.x 同周期） | `maven.modrinth:DistantHorizonsApi:7.2.0` + repo `https://api.modrinth.com/maven`，compileOnly（上游 `5.1.0`） | `DistantHorizonsIntegration` 只剩 `rootCause()` 纯函数：`traceDistantAsync` 直接 `return false`（`:29-35`）、`traceDistant` 空（`:62-65`）、两个 log 空体（`:67-75`）。上游 201 行实现（异步 `CompletableFuture` + 10s 软缓存 + 4096 格雷射 + `LinkageError` 熔断）待回填 | **做** |
| **Sable** | **不存在 26.3 版**。Modrinth `sable` 项目 `game_versions` 全域只有 `1.21.1`（28 个版本，最新 `2.0.6+mc1.21.1`，2026-10-03）；`maven.ryanhcode.dev` 的 `sable-companion-common-26.3` 路径 404（上游用 `dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0`） | — | 客户端 `SableClientProvider`（`src/client/.../sable/client/`）四个入口全返回 `empty`，`SableIntegration.projectOutOfSubLevel` 直接转发空；真实实现 4 文件仍在 `deferred/client/integration/sable/`。服务端侧 `ExternalBlockServerProviders` 已用**反射 + provider 注册表**解耦（`ExternalBlockServerProviders.java:48-80`），未来有 26.3 版可零改动接回 | **跳过** |

## 三、建议实施顺序（M9 内）

| 步 | 内容 | 预估 | 判据 |
|---|---|---|---|
| M9-1 | **Mod Menu**：`deferred/modmenu/*` → `src/client/java/nx/pingwheel/fabric/integration/`，加 compileOnly + repo，`fabric.mod.json` 加 `entrypoints.modmenu` | 0.5h | Mods 列表出现配置按钮，点开即 `SettingsScreen` |
| M9-2 | **Simple Voice Chat**：`VoiceChatIntegration` 升为真 `VoicechatPlugin`（Fabric 走 `voicechat` entrypoint，不需要 `@ForgeVoicechatPlugin`）、回填 `VoiceChatWrapper` 两方法、`depends` 加 `voicechat_api` | 2–3h | 装 VC 后 `optional integrations: voiceChat=true`；同组 ping 互见（AUTO/TEAM_ONLY）；无 VC 时行为与现状一致 |
| M9-3 | **Distant Horizons**：回填 201 行实现 + `maven.modrinth:DistantHorizonsApi:7.2.0` compileOnly | 3–4h | 装 DH 后远处地形可 ping；日志出现 `Distant Horizons API Version: 7.2.0` |
| M9-4 | **收尾**：`fabric.mod.json` 的 `depends/recommends/suggests` 策略统一、`ModContext` 日志、`docs/reviews/24` 保真登记 | 1h | `tools/compile-check` 0/0 + 用户终端 `gradlew build` 通过 |

> 跳过项（FTB Teams / Sable）**不要**为「填坑」写占位实现：现有桩语义已等于「模组未安装」，而 `TeamContextHandler` / `ExternalBlockServerProviders` 的降级链是上游原样。

## 四、保真约束登记建议（`24-fidelity-constraint-audit.md`）

用户铁律是「不得改变上游呈现的效果」。M9 的三类结果要分清：

| 类别 | 条目 | 登记理由 |
|---|---|---|
| **恢复即保真**（只记完成，不登记差异） | Mod Menu / VoiceChat / DH 的真实实现 | 桩→上游行为，等于把临时削掉的可选行为补回 |
| **平台强制差异（必须登记）** | **FTB Teams 无 26.3 版本** → 上游「FTB 队伍参与团队上下文」在 26.3 不可用 | 对端模组不存在，非我方取舍；玩家装不上即等价「未安装」 |
| **平台强制差异（必须登记）** | **Sable 无 26.3 版本** → 子关卡（sub-level）方块 ping 与外部方块 provider 不可用 | 同上；服务端 provider 路径已保留，未来可接回 |

建议在 `24` 文档 §7「对账补登记」追加 **#15（FTB Teams 26.3 缺失）**、**#16（Sable 26.3 缺失）**，注明「上游有该集成，26.3 生态无对应构建，核验日期 2026-11-01」。

## 五、待验证项 / 风险

1. **坐标未真实解析**：本会话无 Gradle、bash 无出网，仅核验 maven metadata 与 Modrinth API。M9-1 落地时必须先 `gradlew build` 确认三个 compileOnly 能拉到 jar。
2. **VoiceChat API 的 pom 是 `packaging=pom`**：目录里同时有 `voicechat-api-2.6.27.jar` 与 `voicechat-api-2.6.27-fabric-stub.jar`。Gradle 默认取主 artifact（jar），预计可用；若报缺类再评估 stub 变体（官方文档仍写 `de.maxhenkel.voicechat:voicechat-api:<ver>`，与上游一致）。
3. **ModMenu maven host 直连不稳**：`maven-metadata.xml` 两次 fetch failed，但 `21.0.0/modmenu-21.0.0.pom` 取到 → 坐标有效；构建若超时再考虑镜像。
4. **DH `DhApiRaycastResult.payload.pos` 字段名未逐字核对**（GitLab main 只核到 `DhApiResult.success/payload`）；M9-3 第一步是确认 `pos` 类型再写映射。
5. **Fabric 未知 entrypoint key 的安全性**：上游把 `modmenu`/`voicechat` entrypoint 挂在没有 `depends` 声明的 `fabric.mod.json` 上（`fabric/src/main/resources/fabric.mod.json:28-33`），依赖「Loader 只实例化 main/client/server，未知 key 由对端模组自行读取」。我们照抄该策略即可，但不要自己提前 Class.forName 这些类。
6. **26.3 的 entrypoint 语义**：M9-2 恢复时由 Simple Voice Chat 侧发起 `getEntrypointContainers("voicechat", VoicechatPlugin.class)`，我方只提供实现类，不主动查找。

## 六、结论一句话

**M9 = 做 3 个（ModMenu / VoiceChat / DH，约 6–8h）+ 跳过 2 个（FTB Teams / Sable，生态无 26.3 构建），跳过项按平台强制差异登记进保真审计。**
