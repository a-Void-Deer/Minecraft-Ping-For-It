# 04 · M2 结构审核（source-set 拆分 / 元数据 / 资源 / 构建脚本）

审核人：3号　时间：2026-10-28 19:xx　目标：`ping-for-it`（MC 26.3 / Fabric 单端）的 M2 导入与拆分结果
方法：只读。按 7 个检查面逐项脚本化核对（FQN 集合比对 / 引用计数 / 哈希比对 / 解包 loader 与 loom 源码），**未运行 Gradle**。
统计：`src/main` **165** + `src/client` **137** = 302 个 .java（上游 298 + 新增 4），与 2号 自述一致。

## 结论

**拆分结构正确，7 个检查面里 6 个干净。** 发现 **1 个严重（会让 dev `runServer` 启动失败）+ 1 组 M3 前置（第三方依赖/Lombok 未声明）+ 5 条建议**。
没有发现 main→client 的源码级引用，也没有 client 侧该有的类被留在 main 造成的编译依赖。

---

## 一、源集正确性：通过

| 检查 | 结果 |
|---|---|
| `src/main` 内 `import net.minecraft.client.*` | **0** |
| `src/main` 内 `import com.mojang.blaze3d.*` | **0** |
| `src/main` 内 import 到「只存在于 client 源集」的 `nx.pingwheel.*` FQN | **0**（脚本比对 165×137 的 FQN 集合） |
| 同名包跨源集的**简单名**引用（main 同包类引用 client 类） | 仅 1 处 **javadoc**：`config/ClientConfigBounds.java:7` 的 `{@link ClientConfig#validate()}`（不参与编译，见建议 N4） |
| 明显该在 client 却在 main 的类 | 见建议 N2（16 个仅被 client 引用的类，均为纯逻辑，无编译风险） |
| 服务端类被误搬 client | 无：main 不引用任何 client 类；`ServerCore/ServerConfig/network/marker` 全在 main |

## 二、拆分三特例：全部正确

| 特例 | 核实结果 | 证据 |
|---|---|---|
| TeamContextHandler 只剩服务端安全方法 | **是**：仅 `hasTeam(Player)` / `getContext(Player)` / `inSameContext(Player,Player)` 3 个成员 | `src/main/.../integration/TeamContextHandler.java:8,14,26` |
| `getSelfContext` 已迁 client | **是**：只在 `ClientTeamContextHelper.getSelfContext()`；唯一调用点是 `screen/SettingsScreen.java:1004` | `src/client/.../client/ClientTeamContextHelper.java:16,20` |
| ServerCore 调用点仍指向 main 版本 | **是**：4 处 `TeamContextHandler.hasTeam/inSameContext` | `core/ServerCore.java:363,386,445,715` |
| ClientConfig / ClientCommandBuilder / Raycast 整类在 client | **是**（无 main 同名副本） | `client: nx.pingwheel.common.config.ClientConfig`、`...command.ClientCommandBuilder`、`...math.Raycast` |
| ProtocolLimits 在 main 且三个网络包引用正确 | **是**：`MAX_CHANNEL_LENGTH`；被 `PingLocationC2S/PingLocationS2C/UpdateChannelC2S` 静态导入；client 侧 `ClientCommandBuilder`、`ClientConfig` 也用它 | `src/main/.../network/ProtocolLimits.java:15`；三包 `:11/:11/:8` |

## 三、平台层：通过（1 条建议）

- `IPlatformContextService` 只剩 `getSelfModVersion / resolveGameDir / resolveConfigDir / isModLoaded(String)`，**无 KeyMapping 等 client 类型**（`platform/IPlatformContextService.java:12-15`）。
- `IClientPlatformContextService extends IPlatformContextService`，唯一新增成员 `registerKeyMapping(KeyMapping)`；**只被 client 引用**（`CommonClient.java:92-96` 共 5 处 + 自己的实现），main 0 引用。
- 5 个 services 文件 ↔ 实现类 ↔ 源集**一一对应**：

| services 文件所在源集 | SPI | 实现类（源集） |
|---|---|---|
| `src/main/resources/META-INF/services` | IPlatformContextService | `fabric.platform.PlatformContextServiceImpl`（main） |
| 同上 | IPlatformNetworkService | `PlatformNetworkServiceImpl`（main） |
| 同上 | IPlatformServerEventService | `PlatformServerEventServiceImpl`（main） |
| `src/client/resources/META-INF/services` | IClientPlatformContextService | `PlatformClientContextServiceImpl`（client） |
| 同上 | IPlatformClientEventService | `PlatformClientEventServiceImpl`（client） |

> 建议 N1：`IPlatformClientEventService` **接口本身在 main**，但实现与 services 都在 client、main 0 引用——与 `IClientPlatformContextService` 的拆分策略不一致（无编译/运行风险，M9 简化平台层时统一）。

## 四、元数据：通过（1 个严重项）

| 项 | 结果 |
|---|---|
| id | `pingforit`（`templates/fabric.mod.json:3`） |
| 4 个 entrypoint 的类存在 & 源集 | main → `nx.pingwheel.fabric.FabricMain`（main）✓；client → `FabricClient`（client）✓；modmenu → `fabric.integration.ModMenuIntegration`（client）✓；voicechat → `common.integration.VoiceChatIntegration`（main）✓（四类均存在） |
| mixins 配置 package vs 类位置 | `pingforit.mixins.json:4` `package: nx.pingwheel.common.mixin`；7 个类全部位于 `src/client/java/nx/pingwheel/common/mixin/` ✓；配置内 `client` 数组 7 项与文件一一对应 ✓ |
| refmap 已移除 | **是**，配置中无 `refmap` 字段（26.3 未混淆 + no-remap Loom，确实不需要）✓ |
| icon | `"icon": "icon.png"`，`src/main/resources/icon.png` 存在（6780 B），与上游 SHA256 相同 ✓ |

### 严重 S1：mixin 配置在 client 资源，但 fabric.mod.json 以「字符串」声明（= 双端 universal）

- `templates/fabric.mod.json:35-37`：`"mixins": ["pingforit.mixins.json"]`；该文件实际位于 `src/client/resources/`。
- Fabric Loader 源码明确：**「All mixin configs specified via string are assumed to be universal」**（`V1ModMetadataParser`，loader 0.20.1）；universal 配置在 **server 端也会被要求加载**。
- `FabricMixinBootstrap.java:88-96`：对每个 config 调 `Mixins.addConfiguration(config)`，失败直接 `throw new RuntimeException("Error parsing or using Mixin config ...")`。
- **后果**：开发环境 `runServer` 的 classpath 只有 main 输出，`pingforit.mixins.json` 不在其中 → 该 resource 找不到 → 启动即失败。**生产 jar 不受影响**（Loom 把 client 资源合并进 jar，文件存在；`client` 段 mixin 在 server 侧会被 Mixin 跳过）。
- 修法（1 行）：`"mixins": [{ "config": "pingforit.mixins.json", "environment": "client" }]`。改后 server 端根本不会要求这个配置。
- 置信度：高（loader 源码两条证据）；建议 M3/M10 跑 `runServer` 时实测确认。

## 五、资源：通过（逐字节一致）

- `src/main/resources/assets/pingforit/` **12 个文件**：`sounds.json`、8 个 lang（de_de/en_us/es_ar/fr_fr/pl_pl/tr_tr/zh_cn/zh_tw）、`sounds/ping.ogg`、`textures/arrow.png`、`textures/ping.png`。
- 与上游 `common/src/main/resources/assets/pingforit/` **SHA-256 全部一致**（12/12）。
- `sounds.json` 命名空间：`"pingforit:ping"` ✓（`assets/pingforit/sounds.json:5`）。
- `icon.png`：`src/main/resources/icon.png`，与上游同哈希 ✓。

## 六、残留与重复：通过

| 检查 | 结果 |
|---|---|
| `isharmla`（大小写敏感） | **0** |
| `ping-for-it`（大小写敏感） | **0** |
| `PingForIt` | 31 处，全部是上游自身命名（`PingForItVersion`、`MOD_PREFIX "[PingForIt] "` 等），**不是本项目残留** |
| 同 FQN 重复类 | **0**（main 165 与 client 137 的 FQN 集合无交集） |
| `src/test` 误搬 | **无**（不存在该目录） |
| 旧样例类 | `PingForIt.java / PingForItClient.java / PingForItDataGenerator.java / ExampleMixin.java / ExampleClientMixin.java / ExampleMod.java` 全部已删 |

## 七、build.gradle：通过

| 项 | 结果 |
|---|---|
| `loom.mods` 键 | `"pingforit"`（`build.gradle:22`），与 fabric.mod.json id 一致 ✓ |
| datagen 块 | **已删除**（无 `fabricApi { configureDataGeneration }`）✓ |
| jar archiveClassifier | `archiveClassifier = "fabric-26.3"` 保留在 `jar {}` 内（`:73`），注释已按 M1 结论改写为 no-remap 分支说明 ✓ |
| remapJar 相关配置 | **无**（无 `tasks.matching{...remapJar}`、无 `tasks.named("remapJar")`）✓ |

## 八、分级问题清单

### 严重

- **S1（见 §四）**：mixin 配置 universal 声明 + 文件在 client 资源 → dev `runServer` 启动失败。修法 1 行。
- **S2（M3 前置，非 M2 结构错误）**：build.gradle **未声明任何第三方 compileOnly 依赖**，但导入的源码仍在引用：
  `lombok`（16 行 / **8 文件**，D7 说移除但 M2 未删）、`com.terraformersmc.modmenu`（1 文件：`ModMenuIntegration`）、`de.maxhenkel.voicechat`（1 文件）、`com.seibel.distanthorizons`（1 文件）、`dev.ftb.mods.ftbteams`（1 文件）、`dev.ryanhcode.sable`（3 文件）、`org.jetbrains.annotations`（16 文件）。
  → M3 第一批编译错误会全部来自这里；建议在 M3 开头一次性加 compileOnly 或按 D9 明确跳过。

### 建议

- **N1**：`IPlatformClientEventService` 接口在 main、实现/services 在 client，策略与 `IClientPlatformContextService` 不一致（无风险）。
- **N2**：以下 16 个 main 类**仅被 client 引用**（可留可搬；M3 迁移时顺手搬可减少后续返工）：
  `chat.PingChatBuilder`、`config.{EntityBlockRenderMode,PlayerInfoMode,TeamColorMode}`、`core.RenderEntityLookupCache`、`interaction.cancel.MarkerCandidatePosition`、`math.{EntityLocalGeometryRegistry,EntitySelectionBlacklist,ScreenPos}`、`name.{ClientTargetNameDecoder,ClientTargetNameStore}`、`platform.IPlatformClientEventService`、`resource.{LanguageUtils,ResourceReloadListener}`、`util.{ClaimedInputState,PingClickArbitration,ToggleInputState}`。
  （注：`fabric.platform.PlatformContextServiceImpl` 被脚本列为「仅 client 引用」是假阳性——它由 main 的 services 文件按名加载。）
- **N3**：`settings.gradle:13` `rootProject.name = 'ping-for-it'` 与 mod id `pingforit` 不一致（产物名被 `archivesName` 覆盖，无功能影响；建议统一便于日志/CI 辨识）。
- **N4**：`config/ClientConfigBounds.java:7` 的 javadoc 链接指向 client 类——javac 不解析 javadoc，但将来加 javadoc 任务会断链；M3 顺手改成纯文字。
- **N5**：mixin 类包名为 `nx.pingwheel.common.mixin` 却位于 client 源集（上游沿袭）。当前无碍；若日后新增**服务端** mixin，需另建 main 侧配置（别再塞进这个 client 配置）。

## 九、复现方式（全部只读）

```
# 1) FQN 集合比对（main 165 × client 137）：转 FQN 后求交
# 2) 客户端泄漏扫描
Get-ChildItem src\main\java -Recurse -Filter *.java | Select-String '^import net\.minecraft\.client\.|^import com\.mojang\.blaze3d'
# 3) 资源一致性：Get-FileHash <上游/本项目各文件> -Algorithm SHA256 比对
# 4) mixin 语义（loader 0.20.1 源码）
V1ModMetadataParser.java（"All mixin configs specified via string are assumed to be universal"）
FabricMixinBootstrap.java:88-96（Mixins.addConfiguration 失败 → RuntimeException）
```
