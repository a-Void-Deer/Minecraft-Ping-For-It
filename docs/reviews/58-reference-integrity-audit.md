# 58 · 引用完整性审计（翻译键 / 资源引用 / ServiceLoader / fabric.mod.json / 构建路径）

> 审计人：reference-auditor（**只读**；除本文件外未写入任何文件）｜2026-11-03
> 对象：工作区 `26.3_Fabric` HEAD **e8790a5**；审计开始时工作树干净（`git status --porcelain` 仅他人的 `57-pipeline-conflict-audit.md` 未跟踪），收尾时其他成员已开始改 `AGENTS.md`/`24`/两处渲染代码，与本审计对象无关。
> 方法：全量 grep/read 源码 → 构造"用键集合"→ 与 8 个 lang 文件做集合 diff；对 `common/src/main/resources`、`fabric/build/resources/{main,client}`、发布 jar、**合并后的 jar** 做路径/哈希对照；对 MC 26.3 与 sponge-mixin 0.8.7 做 `javap` 反查；复读 `run/logs/latest.log`（20:17–20:19 的重构后实机运行）。
> 限制：**未运行 Gradle、未启动游戏**（沙箱只读）。凡只能由真实构建/专用服务端判定的，第 7 节逐条列出，不假装已验证。

---

## 0. 结论速览

| # | 审计项 | 裁决 | 一句话 |
| --- | --- | --- | --- |
| A | 翻译键（用键 vs en_us / zh_cn） | **PASS**（P2×3） | en_us 缺键 **0**、原始键值 **0**、占位符错配 **0**；8 条死键、zh_cn 少 5 条用键（均回落 en_us，不显示原始 key） |
| B | 资源引用（贴图/音效/图标） | **PASS** | 代码引用的 2 张贴图 1 个音效 1 个 icon 在源码、合并 dev root、jar 三处都在，且哈希逐一对齐 |
| C | ServiceLoader（5 个 SPI） | **PASS**（P2×2） | 文件名=FQCN、实现类在正确源集、全 public 无参构造、跨模块 0 重复；两个"源集纯度"遗留项 |
| D | fabric.mod.json（模板/产物） | **PASS**（P2×2） | 4 个 entrypoint 类都在 jar；mixins 对象形式 + `environment: client`；icon/depends 正常；模板与产物仅 `${version}` 展开有别 |
| E | 构建/引用路径 | **PASS**（P2×3） | gradle/tools/CI 无 `src/main`/`src/client`/`build/libs` 陈旧引用；坐标全三段式；CI 路径正确但通配符**多收** sources jar |

**总体：0 个 P0、0 个 P1、10 个 P2。** 重构后的引用面是干净的：没有"渲染成原始 key"的路径、没有缺失的资源、没有断掉的 SPI/entrypoint。剩余项全部是**清理/一致性/前向风险**，不影响当前发布 jar 与 dev 运行。

---

## 1. 翻译键审计（A）

### 1.1 用键集合是怎么构造的（不是只 grep 字面量）

键有三种来源，只 grep 字面量会漏掉第三类：

1. **字面量**：`Component.translatable("…")` / 直接字符串常量 / `sounds.json` 的 `subtitle`。
2. **`LanguageUtils` 拼装**：`settings(...)` / `command(...)` / `of(category, ...)` / `keyOf(category, ...)` / `.path(...)` —— 统一生成 `<category>.pingforit.<...>`（`LanguageUtils.java:22-32,40-42`）。
3. **动态后缀**：设置项 id（`SettingsCategoryCatalog.java:17-46`）、分类 id（`SettingsNavigationModel.java:28-36`）、分组 id、枚举 `toString()`（`ChannelMode`/`PlayerInfoMode`/`TeamColorMode`/`EntityBlockRenderMode`/`TeamContext`）、ping 类型 id（`PingTypeCatalog.java:38-44`）。

按键族归并后**去重共 155 个用键**（下列均含 file:line 证据）：

| 键族 | 数量 | 代码来源（代表） |
| --- | --- | --- |
| `key.pingforit.*`（5 键位） | 5 | `InputUtils.java:21,23,28,33,40`（`keyOf("key", …)`） |
| `key.category.pingforit.name` | 1 | `InputUtils.java:19-20`（`KeyMapping.Category.register(Identifier)`，格式见 §1.6） |
| `subtitles.pingforit.ping` | 1 | `sounds.json:3` |
| `settings.pingforit.*` | 85 | 30 个设置项（`SettingsCategoryCatalog.java:17-46`）+ 9 分类×2（标题/按钮）+ 8 分组（`SettingsScreen.java:469-480,518`）+ `title/client_settings/server_settings/reset_all*/channel*/open_client_config*/server_status.*/server_settings.validation`（`SettingsScreen.java:132,154,448,570,583-598,606-610,690-720,949,997-1004,1114,1265`） |
| `value.pingforit.*` | 20 | `LanguageUtils.java:12-13`；`SettingsScreen.java:617,625-627,1038,1073,1109,1125,1249`；`TeamContext.java:4`（`vanilla_team/ftb_teams/voice_chat`） |
| `unit.pingforit.*` | 6 | `LanguageUtils.java:15-20`（meters/percent/milliseconds/seconds/degrees/pixels） |
| `command.pingforit.*` | 8 | `ClientCommandBuilder.java:24-25,32,34,45,53,55,73,75,77` |
| `notice.pingforit.*` | 5 | `SelectionToggleNoticeState.java:83-85`；`SelectionToggleNoticeRenderer.java:47-50` |
| `pingforit.chat.*` | 8 | `PingChatBuilder.java:36-37,54`（含 4 个 override）；`PingInteractionAction.java:53`；`MinecraftLocalErrorSink.java:23` |
| `pingforit.ping_type.*` | 14 | `PingTypeCatalog.java:38-44`（7 类型 × phrase/display） |
| `pingforit.target.*` | 2 | `TargetNameComposer.java:58,67`；`TargetNameJsonCodec.java:37` |

额外说明：`deferred/`（不参与编译，M7 取回）里 81 处 `LanguageUtils` 引用是当前设置界面的旧副本；抽查 37 个字面量键，**没有一个是 en_us 缺失的独立键**（4 个"命中"是 `settings(…).path(…)` 的动态前缀，不是独立键）。M7 取回不会新增键需求。

### 1.2 对照 `en_us.json`（163 键）：0 缺失、8 死键

- **用键但 en_us 缺失：0**（不存在"游戏里显示原始 key"的路径；Minecraft 的 `Language` 还会以 en_us 兜底，所以只要 en_us 全，任何语言最多是英文而不是裸 key）。
- **en_us 定义但代码从不使用：8 条**（`en_us.json` 行号）：

| # | 键 | 行 | 说明 |
| --- | --- | --- | --- |
| 1 | `settings.pingforit.server_settings.loading` | 13 | 已被 `settings.pingforit.server_status.loading` 取代（`SettingsScreen.java:698`） |
| 2 | `settings.pingforit.server_settings.locked` | 14 | 无引用（`grep -rn "server_settings\." common/src fabric/src` 只命中 `validation`） |
| 3-6 | `settings.pingforit.server_settings.confirm.{title,message,discard,cancel}` | 16-19 | 全库（含 `deferred/`）搜 `"confirm"` = 0 命中；"放弃服务器草稿"确认框未实现 |
| 7-8 | `settings.pingforit.open_block_shape_blacklist_config{,.tooltip}` | 45-46 | 已被 `open_client_config{,.tooltip}` 取代（`SettingsScreen.java:606,610`） |

这 8 条**与上游逐字节一致**（见 §1.7），不是重构丢的，属继承性死资源；不渲染、不报错，只是语言文件漂移。

### 1.3 对照 `zh_cn.json`（158 键）：5 条"用键缺失"

zh_cn 没有 en_us 之外的键（extra=0），缺 5 条**会被用到**的键：

| 缺失键 | en_us 定义处 | zh_cn 现状 | 影响 |
| --- | --- | --- | --- |
| `value.pingforit.vanilla_team` | `en_us.json:63` | 无 | 频道占位符 `Synced to %s`（`SettingsScreen.java:1039-1040`）在中文界面显示英文 "Vanilla Team" |
| `value.pingforit.ftb_teams` | `en_us.json:64` | 无 | 同上，"FTB Teams" |
| `value.pingforit.voice_chat` | `en_us.json:65` | 无 | 同上，"Voice Chat" |
| `pingforit.chat.go_to.template.override` | `en_us.json:99` | 无（zh_cn 只有 danger:95 / request:96） | 中文客户端 `selectTemplateKey` 判定"本语言无 override"（`PingChatBuilder.java:70-76`）→ 回落通用模板 `pingforit.chat.pingmsg.template`（zh_cn 已翻译），**不显示原始 key**，但丢失 go_to 的专用句式 |
| `pingforit.chat.take.template.override` | `en_us.json:100` | 无 | 同上 |

结论：**没有"渲染成原始 key"的路径**；3 条队伍名会英文兜底，2 条只影响聊天句式保真。属 P2（若发布明确面向中文社区，可升 P1 补译）。

### 1.4 其余 6 个语言（对照"用键集合"的缺失数）

| 语言 | 文件键数 | 缺用键 | 缺失内容摘要 |
| --- | --- | --- | --- |
| de_de | 156 | 9 | 5 个 `settings.pingforit.wheel_*` + `cancel_half_cone_angle_degrees` + `unit.degrees/pixels` + `chat.danger.template.override` |
| es_ar | 156 | 9 | 同上 |
| fr_fr | 153 | 12 | de 的 9 条 + `value.vanilla_team/ftb_teams/voice_chat` |
| pl_pl | 148 | 15 | fr 的 12 条 + `go_to/take/request` 三个 override（连同 danger 共 4 个全缺） |
| tr_tr | 148 | 15 | 同 pl_pl |
| zh_tw | 150 | 13 | 5 个 `wheel_*` + `cancel` + `unit.degrees/pixels` + 3 个队伍名 + `go_to/take` 两个 override（danger 有） |

其中 `unit.degrees/pixels` 的 en_us 值就是纯格式串（`"%s°"`/`"%spx"`），回落后显示无差别；**真正可见的是 5 个 `wheel_*` 设置项 + `cancel_half_cone_angle_degrees` 在 de/es/fr/pl/tr/zh_tw 下显示英文标签**。全部为上游继承（§1.7），非重构回归。

### 1.5 其他扫描（全部 0 命中）

- **值为原始 key**（en_us 及其他 7 语言）：0（`value === key` 0 条；值形如 `settings.pingforit…` 的 0 条）。
- **占位符个数错配**（用键范围内 en_us vs 各语言 `%s` 计数）：0。
- **重复键**（JSON 层同名键）：8 个文件各 0 条（en_us 163 occurrences / 163 unique）。
- **空值**：en_us 0 条。

### 1.6 易错点复核：`key.category.pingforit.name` 这个格式对不对

26.3 的键位分类不是手写字符串，而是 `Identifier` 推导（`InputUtils.java:19-20`）。字节码实证（`javap -v`，MC 26.3 common jar）：

- `KeyMapping$Category.label()`：`ldc "key.category"` → `Identifier.toLanguageKey(String)` → `Component.translatable`；
- `Identifier.toLanguageKey(String)` 的 `makeConcatWithConstants` 配方 = **`\u0001.\u0001`**（BootstrapMethods #3），`toLanguageKey()` 同理 = `namespace + "." + path`。

所以 `Identifier("pingforit","name")` → `"key.category.pingforit.name"`，与 `en_us.json:2` **精确一致**。PASS。

### 1.7 与上游的一致性（重要定性）

把 8 个 lang 文件与 `Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1` 对齐：**键集与值全部逐字节相同**（en/zh_cn/de/es/fr/pl/tr/zh_tw 均 `onlyUp=0 onlyCur=0 diffVal=0`）。即：本节的死键与缺译**全部继承自上游**，重构没有增删任何一条翻译资源。

---

## 2. 资源引用审计（B）

### 2.1 资源清单（`common/src/main/resources`，共 13 文件）

```
assets/pingforit/lang/{en_us,zh_cn,zh_tw,de_de,es_ar,fr_fr,pl_pl,tr_tr}.json
assets/pingforit/sounds.json                    98 B
assets/pingforit/sounds/ping.ogg                7 616 B（OggS + vorbis）
assets/pingforit/textures/ping.png              1×1、70 B（透明占位，M8b 契约）
assets/pingforit/textures/arrow.png             64×64、3 627 B
icon.png                                        128×128、29 966 B
```

### 2.2 代码引用 → 文件存在性（源码 / 合并 dev root / jar 三处）

| 代码引用 | 位置 | common/src/main/resources | fabric/build/resources/main | 发布 jar |
| --- | --- | --- | --- | --- |
| `pingforit:textures/ping.png` | `ResourceConstants.java:13` | 有 | 有（SHA256 相同） | 有（SHA256 相同） |
| `pingforit:textures/arrow.png` | `ResourceConstants.java:14` | 有 | 有（相同） | 有（相同） |
| `pingforit:ping`（SoundEvent） | `ResourceConstants.java:11-12` | sounds.json `ping` + sounds/ping.ogg | 有（相同） | 有（相同） |
| `icon.png` | `fabric.mod.json:10` | 有 | 有（相同） | 有（相同，jar 根） |
| 8 个 `lang/<code>.json` | `SelectedLocaleTranslationKeyCache.java:137-139` | 8/8 有 | 8/8 有 | 8/8 有 |

使用点（不是死常量）：音效 `FabricClient.java:67`（注册）+ `ClientPingRuntime.java:1037-1042`（播放）；贴图 `MarkerHudRenderer.java:267,390`、`WheelHudRenderer.java:467`、`PingTextureReloadListener.java:31-32`。

### 2.3 `sounds.json` ↔ 代码引用的 SoundEvent id

`sounds.json:2-6`：事件名 `ping` → 资源 `pingforit:ping` → 文件 `assets/pingforit/sounds/ping.ogg`；字幕键 `subtitles.pingforit.ping`（`en_us.json:8` 有）。代码 id = `Identifier("pingforit","ping")`。**命名空间/路径/文件名三方一致**。PASS。

### 2.4 贴图 ReloadListener 的期望路径

`PingTextureReloadListener.java:31-32` 用 `resourceManager.getResourceStack(PING_TEXTURE_ID)`，即 `assets/pingforit/textures/ping.png`；该文件存在且是 **1×1 全透明占位**（70 B），与 `size > 1 ⇒ 有自定义包` 的判据（`PingTextureReloadListener.java:47-49`）自洽。

> 注意：dev 的 `:common` 是以 jar 形式在 classpath（`common/build/libs/Ping-For-It-0.4.2-pfi-beta1.jar`，其根**无** `fabric.mod.json`），所以不会被当成第二个 mod root 让 stack 变 2——这条启发式在 dev 与发布环境都成立。实机"自定义包"分支未测（AGENTS 也标注 M8b 自定义包待补测）。

### 2.5 `fabric.mod.json` 的 icon 路径

`"icon": "icon.png"`（模板 `:10`）→ jar 根确有 `icon.png`（128×128，与源文件同哈希）。PASS。

### 2.6 合并 dev root 与 jar 的完整性（哈希级）

- `fabric/build/resources/main`：3 个 `META-INF/services` + `fabric.mod.json` + `assets/pingforit/**` + `icon.png` —— 与 `common/src/main/resources` 对应文件 **SHA256 全部 SAME**（含 en_us/zh_cn/sounds.json/ping.ogg/ping.png/arrow.png/icon.png）。
- `fabric/build/resources/client`：`pingforit.mixins.json` + 2 个客户端 SPI。
- 发布 jar（`fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`，20:06:10，771 558 B）：**464 条目 / 392 class / class major 69 / 0 重复条目**，5 份 SPI 各 1 份，`assets/pingforit/**`+ `icon.png` + `LICENSE_pingforit` 齐全；上述 10 个关键条目与合并 root **逐字节相同**。

### 2.7 重构后的实机证据（不是只靠静态推断）

`run/logs/latest.log`（**20:17–20:19**，即 20:06 构建 + 20:17 资源合并之后的一次真实 `runClient`）：

- `:52-54` `fabricloader 0.20.1 / java 25 / minecraft 26.3`；`:58` `pingforit 0.4.2-pfi-beta1` 已加载；
- `:168` 资源包列表含 **`pingforit`**（说明 dev 合并 root 被索引为 mod 资源包——AGENTS §7.16 的修法生效）；
- `:187` `Sound engine started`；全日志 grep `Missing sound`/`Missing texture`/`Failed to load`/`Mixin apply failed` = **0**；
- `:298-370` 成对 `send ping` / `marker created`；`:315-341` 轮盘 `[attention, go_to, danger]` 选择正常。

即：资源引用在 dev 侧"能被找到并生效"有直接证据，而不只是文件存在。

---

## 3. ServiceLoader 审计（C）

### 3.1 五个 SPI 的逐项核对

| # | SPI 文件名（= 接口 FQCN） | 接口位置 | 实现类 | 实现所在源集 | jar 内 | 无参构造 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `nx.pingwheel.common.platform.IPlatformContextService` | common/src/main | `nx.pingwheel.fabric.platform.PlatformContextServiceImpl` | `fabric/src/main/java` | 有 | public（javap） |
| 2 | `nx.pingwheel.common.platform.IPlatformNetworkService` | common/src/main | `…fabric.platform.PlatformNetworkServiceImpl` | `fabric/src/main/java` | 有 | public |
| 3 | `nx.pingwheel.common.platform.IPlatformServerEventService` | common/src/main | `…fabric.platform.PlatformServerEventServiceImpl` | `fabric/src/main/java` | 有 | public |
| 4 | `nx.pingwheel.common.platform.IClientPlatformContextService` | `fabric/src/client/java`（接口本身引用 `net.minecraft.client.KeyMapping`） | `…fabric.platform.PlatformClientContextServiceImpl` | `fabric/src/client/java` | 有 | public |
| 5 | `nx.pingwheel.common.platform.IPlatformClientEventService` | common/src/main（**接口在 common**） | `…fabric.platform.PlatformClientEventServiceImpl` | `fabric/src/client/java` | 有 | public |

- **接口文件名的 FQCN 与接口声明一致**：5/5（逐个读过接口声明）。
- **实现类存在且源集正确**：5/5（服务端 3 个在 `fabric/src/main`，客户端 2 个在 `fabric/src/client`；任务约定的 "common IClientPlatformContextService" 即第 4 行，接口放在 client 源集但沿用 `nx.pingwheel.common.platform` 包名）。
- **无参构造**：javap 逐个确认 `public <FQCN>();`（5 个 impl；4 个 entrypoint 也一并确认）。
- **跨模块 0 重复**：`common/src/main/resources` **没有** `META-INF/services`；`fabric/src/main` 3 份、`fabric/src/client` 2 份，同名文件 0 冲突；jar 内 5 份各 1 份（`Group-Object` 去重 0 条重复）。
- 5 个接口都用 `ServiceLoader.load(…).findFirst().orElseThrow(IllegalStateException)`（`IPlatformContextService.java:8-10` 等），失败即硬抛——所以"文件丢了"会立刻可见，不会静默。
- 实机证据：20:17–20:19 的 dev 运行正常注册键位（`CommonClient.java:82-86` 走 `IClientPlatformContextService.INSTANCE`）并完成服务端 tick/断线注册（`CommonServer.java:40-41` 走 `IPlatformServerEventService.INSTANCE`）→ 客户端 2 份与服务端 1 份在 dev 均解析成功。

### 3.2 两个"源集纯度"遗留项（P2，见 §6）

1. `PlatformNetworkServiceImpl.java:3` 在**服务端源集**里 import 客户端 API `net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking`；能编译是因为 `fabric-networking-api-v1` 的模块元数据 `"environment": "*"`（实测其本地模块 jar：`fabric.mod.json` 为 `*`，jar 内同时含 `ClientPlayNetworking.class`）。所以在**专用服务端**上这个类能被加载/实例化（`ServerCore.java:201` 会用到 `IPlatformNetworkService.INSTANCE`），但 `sendToServer` 只被客户端调用，当前不会触发客户端类解析。
2. `IPlatformClientEventService`（`common/src/main/java/.../IPlatformClientEventService.java:5-9`）是"只有客户端实现"的接口，却放在服务端可见的 common；当前所有引用都在 `fabric/src/client`（`CommonClient.java:74-80`），所以专用服务端不会初始化它的静态 `INSTANCE`；一旦以后服务端代码误用，会抛 `IllegalStateException: No IPlatformClientEventService implementation found!`。

---

## 4. `fabric.mod.json`（模板 / 产物）审计（D）

### 4.1 entrypoint 4/4 存在

| key | 类 | 应属源集 | 源文件 | jar 内 | 构造/接口 |
| --- | --- | --- | --- | --- | --- |
| `main` | `nx.pingwheel.fabric.FabricMain` | fabric main | `fabric/src/main/java/...` | 有 | public 无参；`implements ModInitializer`（`FabricMain.java:24`） |
| `client` | `nx.pingwheel.fabric.FabricClient` | fabric client | `fabric/src/client/java/...` | 有 | public 无参；`implements ClientModInitializer`（`FabricClient.java:33-34`） |
| `modmenu` | `nx.pingwheel.fabric.integration.ModMenuIntegration` | fabric client | 同上 | 有 | `implements ModMenuApi`（`ModMenuIntegration.java:7`） |
| `voicechat` | `nx.pingwheel.common.integration.VoiceChatIntegration` | common | `common/src/main/java/...` | 有 | `implements VoicechatPlugin`（`VoiceChatIntegration.java:14`） |

`modmenu`/`voicechat` 依赖的是 compileOnly 的可选 mod 类；对应 mod 不在时 loader 不会实例化这两个 key 的入口（dev 日志 `:100` `voicechat/PluginManager Registering events for 'pingforit'` 证明装了 VoiceChat 时能正常解析）。

### 4.2 mixins：对象形式 + `environment: client` + 8 个类全对

- 模板 `:18-23`：`"mixins": [ { "config": "pingforit.mixins.json", "environment": "client" } ]`（对象形式，符合 M10 自检项）。
- `fabric/src/client/resources/pingforit.mixins.json:4-15`：`package nx.pingwheel.common.mixin`、`compatibilityLevel JAVA_25`、`client` 8 项、`required: true`、`defaultRequire: 1`、**无 refmap**（no-remap 构建下正确）。
- 8 个配置项 ↔ 8 个 `@Mixin` 类 **1:1**（`fabric/src/client/java/nx/pingwheel/common/mixin/` 恰为这 8 个；`deferred/` 里另 5 个旧 mixin 不参与编译，未列入配置——正确）。
- `JAVA_25` 在运行期 Mixin 中存在：sponge-mixin **0.17.5+mixin.0.8.7** 的 `MixinEnvironment$CompatibilityLevel` 常量表含 `JAVA_25`（javap 实测），不是臆造值。
- 配置只在 client 资源根（`fabric/src/client/resources`），服务端 jar 里虽有文件但 `environment: client` 不会加载。

### 4.3 icon / environment / depends

- `icon: "icon.png"` 存在（§2.5）。
- `environment: "*"`（`:11`）与"共享类 + 客户端入口 + client mixins"一致。
- `depends`（`:24-29`）：`fabricloader >=0.20.1`（= `gradle.properties:9`）、`minecraft ~26.3`（= `minecraft_version`，26.3 在范围内）、`java >=25`（= release 25）、`fabric-api "*"`。dev 实跑（`:52-58` 的 loader/Java/MC 版本 + mod 正常加载、无 `Incompatible mods found`）说明这组范围在真实环境可满足。

### 4.4 模板 vs 生成产物

`fabric/build/resources/main/fabric.mod.json` 与模板**逐行相同，唯一差异是 `${version}` → `0.4.2-pfi-beta1`**（模板 `:4` vs 产物 `:4`）；jar 内同一文件与合并 root 同哈希。`generateModMetadata` 的 Sync 路径、`inputs.property "version"`、`srcDir` 接线都在 `fabric/build.gradle:51-57`，产物证明其生效。PASS。

### 4.5 与上游模板的差异（登记，非缺陷）

上游 `fabric.mod.json` 有 `contributors` 与 `contact.homepage/sources/issues`（`Mod_Project/.../fabric/src/main/resources/fabric.mod.json:7-18`）；本移植模板把 `contact` 留空 `{}`、未带 `contributors`（`fabric/src/main/templates/fabric.mod.json:8-9`）。纯元数据完整性，建议 M10 发布前补齐。

---

## 5. 构建 / 引用路径审计（E）

### 5.1 无陈旧 `src/main` / `src/client` / `build/libs` 引用

对 `*.gradle`、`tools/*.sh|*.ps1`、`.github/workflows/*.yml` 全量 grep：

| 命中 | 判定 |
| --- | --- |
| `build.gradle:2-3`（注释指向 `buildSrc/src/main/groovy/…`） | 正确（buildSrc 自身的源集） |
| `fabric/build.gradle:52` `from 'src/main/templates/fabric.mod.json'` | 正确（fabric 模块内的相对路径） |
| `common/src/main`、`fabric/src/main`、`fabric/src/client`（compile-check 与 CI 注释/命令） | 全部**带模块前缀**，与现布局一致 |
| CI `fabric/build/libs/Ping-For-It-*.jar`（`build.yml:60`） | 与真实产物路径一致（见 5.3） |

**0 条**遗留的根级 `src/main`/`src/client`/`build/libs`。`.gradle` 里也没有 `build/resources`/`build/classes` 硬编码（Gradle 自动推导）。

### 5.2 坐标三段式

| 坐标 | 位置 | 段数 |
| --- | --- | --- |
| `com.mojang:minecraft:26.3` | `common/build.gradle:14`、`fabric/build.gradle:37` | 3 |
| `net.fabricmc:fabric-loader:0.20.1` | `common:15`、`fabric:38` | 3 |
| `net.fabricmc.fabric-api:fabric-api:0.165.0+26.3` | `common:16`、`fabric:39` | 3（`+26.3` 是合法 build metadata） |
| `org.jetbrains:annotations:26.0.2` | `buildSrc/.../multiloader-common.gradle:30` | 3 |

（历史上 `common/build.gradle` 曾漏过 `fabric-api:` 段，55 号复审的 P0 已修，本处复核为正确。）

### 5.3 CI 产物路径：正确，但通配符"多收"

- 真实产物：`fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`（20:06:10）。
- CI：`path: fabric/build/libs/Ping-For-It-*.jar`（`build.yml:60`）→ **路径对**，但同目录还有 `Ping-For-It-0.4.2-pfi-beta1-sources.jar`（20:06:03，同一次构建产物，来自 `multiloader-common.gradle:16` 的 `withSourcesJar()`），也会被匹配上传。建议收窄为 `Ping-For-It-*-fabric-26.3.jar`（P2）。

### 5.4 `.gitignore` / `.gitattributes` 覆盖新模块

`git check-ignore -v` 实测：

```
IGNORED common/build/x.class   <- .gitignore:4:build/
IGNORED fabric/build/libs/x.jar <- .gitignore:4:build/
IGNORED run/logs/x.log          <- .gitignore:33:run/
IGNORED tools/.compile-check/x  <- .gitignore:44:tools/.compile-check/
```

即 `build/`（不带前导斜杠）在任意层级生效，`common/build`/`fabric/build` 都被覆盖；`git ls-files common/build fabric/build` = 空（没有误跟踪）。`.gitattributes:17-19` 的 `*.jar/*.png/*.ogg binary` 覆盖新增资源；`*.java/*.gradle/*.json/*.md/*.yml` 显式 LF。PASS。

### 5.5 其他构建引用

- wrapper 固定 `gradle-9.8.2-bin.zip`（`gradle/wrapper/gradle-wrapper.properties:3`），与版本矩阵一致。
- `settings.gradle:19-20` 只 include `common`/`fabric`，`forge`/`neoforge` 保持注释（`:23-24`）。
- `fabric/build.gradle:69` 的 `from project(':common').sourceSets.main.resources` 与 `:80` 的 `from project(':common').sourceSets.main.output.classesDirs` 是两条独立通道（资源只进一次、class 单独合并），实测 jar 0 重复条目。
- `tools/compile-check.sh:64-71` / `.ps1:69-78` 引用的 `D:/Gradle/caches/fabric-loom/26.3/{minecraft-common,minecraft-client,minecraft-client-only}.jar` **实际都存在**（已核），不是陈旧路径。

---

## 6. 发现汇总（0 × P0，0 × P1，10 × P2）

| # | 严重度 | 位置（file:line） | 会破坏什么 | 建议修复 |
| --- | --- | --- | --- | --- |
| P2-1 | P2 | `common/src/main/resources/assets/pingforit/lang/en_us.json:13,14,16-19,45,46`（zh_cn 对应 `:13,14,16-19,42,43`） | 8 条死翻译（无引用），语言文件漂移 | 删除，或把"放弃服务器草稿"确认框/方块形状配置按钮实现后再用；上游同款，可留到 M10 清理 |
| P2-2 | P2 | zh_cn 缺 `value.pingforit.{vanilla_team,ftb_teams,voice_chat}`（en_us:63-65）、`pingforit.chat.{go_to,take}.template.override`（en_us:99,100） | 中文界面 3 个队伍名兜底英文；go_to/take 聊天句式回落到通用模板 | 补 5 条 zh_cn（override 直接抄 en_us 的 `{playerName}: 请求 {pingType} {targetName}` 结构） |
| P2-3 | P2 | 6 个非中英语言（§1.4 表） | de/es/fr/pl/tr/zh_tw 的 5 个 `wheel_*` + `cancel_half_cone_angle_degrees` 显示英文标签；部分队伍名英文 | 按 §1.4 清单补译；上游继承，非回归 |
| P2-4 | P2 | `fabric/src/main/java/nx/pingwheel/fabric/platform/PlatformNetworkServiceImpl.java:3,12-18` | 服务端源集 import 客户端 API `ClientPlayNetworking`；之所以能编译是 `fabric-networking-api-v1` 的 `environment: "*"`。专用服务端上该类会被 ServiceLoader 实例化，但 `sendToServer` 当前只被客户端调用，所以**现在不会炸**；一旦服务端误调即 `NoClassDefFoundError` | 把 `sendToServer` 拆到客户端 SPI（类似 `IClientPlatformContextService`），或加 `Platform.isClient` 前置守卫；最低成本是在类注释登记该约束 |
| P2-5 | P2 | `common/src/main/java/nx/pingwheel/common/platform/IPlatformClientEventService.java:5-9` | 接口在 common（服务端可见）但只有客户端实现；当前 0 服务端引用，未来误用会 `IllegalStateException` | 迁到 `fabric/src/client/java/nx/pingwheel/common/platform/`（与 IClientPlatformContextService 同法），或保持并在 AGENTS 登记 |
| P2-6 | P2 | `.github/workflows/build.yml:60` | 通配符同时匹配 `Ping-For-It-<ver>-sources.jar`（同目录，20:06:03），CI 会多传一个非发布物 | 改为 `fabric/build/libs/Ping-For-It-*-fabric-26.3.jar` |
| P2-7 | P2 | `fabric/src/main/templates/fabric.mod.json:8-9` vs 上游 `fabric.mod.json:7-18` | 丢失 `contributors` 与 `contact.{homepage,sources,issues}`（Modrinth/CurseForge 元数据完整性） | M10 补齐 |
| P2-8 | P2 | `fabric/src/client/java/nx/pingwheel/common/client/outline/PingOutlineRenderType.java:81` | 管线 id 硬编码 `"pingforit"` 而非 `Global.MOD_ID`；当前值相同，无功能影响 | 改用 `MOD_ID` 常量 |
| P2-9 | P2 | `README.md:60` | 文档说"jar 内 LICENSE 仍是 CC0"，但实际 jar 的 `LICENSE_pingforit` 是 **MIT**（1 067 B，与根 `LICENSE:1` 一致，`fabric.mod.json:9` 也声明 MIT） | 更新 README（或由 1号/Lead 确认后改） |
| P2-10 | P2 | 根 `build/`（`.gitignore:4` 已忽略） | 重构前单模块的残留 build 输出（含旧 `modMetadata`、旧 class；无 `libs`），不参与构建，仅干扰排查 | 删除即可（不影响 CI/产物） |

> 说明：P2-4/P2-5 是"源集纯度"，不是当前功能的缺陷；P2-1/P2-3 是上游继承；P2-6/P2-9/P2-10 是发布/文档卫生。真正的 P0/P1 在本审计范围内没有发现。

---

## 7. 无法在没有真实构建 / 实机的情况下验证的项（明确列出）

1. **一次干净的 `gradlew clean build`**：按任务约束未运行 Gradle。我检查的 jar 是 20:06:10 的**既有产物**（晚于全部源码：最新源文件 18:32:46；资源合并 20:17:06 后仍与 jar 内条目逐字节一致），但它不等于"从当前树重新构建"。
2. **CI 的全新 checkout 流程**：`fetch optional integration jars`（`build.yml:34-49`）依赖外网/CDN 与 `modmenu-21.0.0.jar`、`voicechat-fabric-2.6.27+26.3.jar`、`DistantHorizons-3.3.4-26.3-fabric-neoforge.jar` 三个 URL；本地无网络，未验证下载可用性。另外 `checkout@v6 / setup-java@v5 / upload-artifact@v7 / wrapper-validation@v6` 这些 action 版本号未联网核对。
3. **`./gradlew build` 在根项目是否聚合到 `:fabric:build`**：按 Gradle 多项目语义应当会，而且本机 20:06 同时产出了 `common`/`fabric` 的 jar 与 sources jar（与"一次聚合 build"吻合），但我没有重跑确认。
4. **专用服务端 `runServer`**：`run/logs` 里只有客户端日志，没有 server 运行记录。P2-4 的"专用服务端上加载 `PlatformNetworkServiceImpl` 不会立刻炸"是**静态推断**（模块 env=`*`、方法不可达）；真正的验证需要一次 `runServer`。
5. **翻译的实际渲染**：没有中文/其他语言的实机截图；结论基于键集合 + Minecraft `Language` 的 en_us 兜底语义（静态）。"不显示原始 key"这一结论依赖该兜底行为。
6. **自定义资源包替换 ping.png 的分支**（`stack.size() > 1`）：无实机证据（AGENTS 也标注 M8b 自定义包待补测）。
7. **Mixin 的 require 风险**（57 号复审 H5 已登记）：本审计只核对配置与类的一致性、`JAVA_25` 合法性与 dev 日志 0 失败，未覆盖目标方法签名级风险。
8. **jar 内 class 与当前源码的逐字节一致性**：未反编译逐一比对（只核了 class major 69、时间戳与资源哈希）。若需绝对证据，需用 20:06 的构建日志或重新构建。

---

## 8. 复核入口（便于 Lead 抽查）

```bash
# 用键 vs en_us（示例；完整脚本见本文件 §1.1 的键族表）
grep -rnE 'LanguageUtils\.(settings|command|of|keyOf)' common/src fabric/src

# 资源引用三处对照
ls common/src/main/resources/assets/pingforit/{textures,sounds,lang} fabric/build/resources/main/assets/pingforit
unzip -l fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar | grep -E 'assets/pingforit|icon.png|META-INF/services'

# SPI 唯一性 / 构造
for f in fabric/src/main/resources/META-INF/services/* fabric/src/client/resources/META-INF/services/*; do echo "== $f"; cat "$f"; done

# 陈旧路径
grep -rnE 'src/(main|client)|build/libs' --include='*.gradle' --include='*.sh' --include='*.ps1' --include='*.yml' .
```

> 本文件只读审计产物；未修改 `common/`、`fabric/`、`buildSrc/`、`tools/`、`.github/` 的任何内容。
