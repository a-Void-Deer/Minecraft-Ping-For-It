# 05 · M3a 代码审核（Lombok 移除 / 集成桩化 / 机械改名）

审核人：3号　时间：2026-10-28 19:5x　基线：**HEAD = bed57c3**（M3a）
方法：只读、基于 `git show HEAD:<path>` 与 `git grep HEAD` 核对（**未跑 Gradle**）。

> ⚠️ 审阅基线说明：审查时**工作区是 dirty 的**（107 项：91 个 `D`、8 个 `M`、8 个未跟踪；`deferred/` 有 92 个未跟踪文件，mtime 19:51，晚于 M3a 提交的 19:45）。因此本报告**一律以 HEAD 为准**；磁盘上的"半移动"状态不计入 M3a 结论，单列为 W1。

## 结论

- 机械改名 **完全正确**（0 残留、C2S/S2C 映射方向正确）。
- 集成桩化 **语义等价**（0 第三方 import，无集成时行为与"未安装"一致）。
- Lombok 移除 **大部分等价，但有一处真实回归**：`ClientConfig` 丢掉了 `hashCode()`，而 `ConfigHandler` 的"配置未变则跳过写盘"正是靠 `hashCode` 判断 → **客户端配置改完不会落盘**（阻断级）。
- 另有 1 个**预期内但会阻断 runClient** 的问题：mixin 配置仍列 5 个在 26.3 不存在的注入目标（M7 范围，但 M4 前必须处理）。

---

## 一、Lombok 移除的语义等价性

### 1.1 ClientConfig（533 行，HEAD）

| 项 | 结论 | 证据 |
|---|---|---|
| 字段 | **1:1 一致**（33 个字段，顺序/初值/可见性相同；`blockDisplayPolicy` private transient；`channel/serverChannels` 包私有） | HEAD `:17-59` vs 上游 `:26-76` |
| 访问器 | 36 个 getter（29 `get*` + 7 `is*`）+ 32 个 setter，**setter 均返回 void**（与 lombok 一致） | HEAD 统计：getters=29 / booleanGetters=7 / setters=32 |
| 自定义 getter/setter | 保留：`getChannel/setChannel`（按 server ip 分流）、`setWheelInnerRadius/OuterRadius`（半径互夹）、`setWheelHoldMillis`、`setLongPressCompatibilitySliceMillis`、`getBlockDisplayPolicy`、`setBlockDisplayWhitelist/Blacklist`（`validatedEntries`） | HEAD `:61-102, 156-170`；clamp 调用共 **19 处** |
| 无 getter/setter 的字段 | `blockDisplayPolicy`：无公开 setter ✓；两个 List：lombok 侧是 `@Setter(NONE)`，现由自定义校验 setter 提供 ✓ | 同上 |
| `@ToString.Exclude`（channel/serverChannels） | 上游只排除 toString，**未**排除 equals/hashCode；移除后二者都不再参与——但因为整个 equals/hashCode 都没了，见 S1 | 上游 `:62-65` |
| `@EqualsAndHashCode.Exclude`（blockDisplayPolicy） | 语义消失（无 equals/hashCode） | 上游 `:56-57` |

### S1（阻断）ClientConfig 丢失 `hashCode()` → 客户端配置保存被静默跳过

- 机制：`ConfigHandler.saveInternal(force=false)` 的跳过条件就是用 `config.hashCode()` 比内容：
  `HEAD:src/main/java/nx/pingwheel/common/config/ConfigHandler.java:148`
  `if (!force && configHash == config.hashCode() && Files.exists(configPath)) { return true; }`
  `configHash` 在 load/save 时都赋值为 `config.hashCode()`（`:162,198,224,` …）。
- 上游 `ClientConfig` 有 `@EqualsAndHashCode` → 内容哈希会随字段变化；M3a 手写版**没有** `hashCode`/`equals`/`toString`（HEAD 中 `git grep` 三者均为 0 命中）→ 退化为 `Object` 身份哈希。
- 后果：同一实例的 `configHash == config.hashCode()` **恒成立** → 走 `saveSafely()` 的调用**一个字节都不会写**：
  `src/client/.../util/InputUtils.java:137,147,157`（三个开关热键）、`src/client/.../command/ClientCommandBuilder.java:50`。
  用户改设置、按开关热键后重开游戏会回退——属**功能性回归**，且不会报错（只打 'Saved config' 之外的静默 return）。
- 对照：`ServerConfig` 是手写重写，`toString/equals/hashCode` 齐全（HEAD `:91,101,113`），字段覆盖 6/6，**不受影响**。
- 修法（二选一）：
  1. 给 `ClientConfig` 手写 `hashCode/equals`，字段集合同上游 `@EqualsAndHashCode`（**排除** `blockDisplayPolicy`，包含 channel/serverChannels）；
  2. 改 `ConfigHandler`：把"未变更跳过"从 `hashCode` 换成显式的 `contentHash`（序列化后字符串哈希），对两类配置都更稳。
- 置信度：高（调用链与判定条件均在 HEAD 内闭环）。

### 1.2 ServerConfig / ConfigHandler / WorldRenderContext

| 项 | 结论 |
|---|---|
| ServerConfig | 字段 6 个（`defaultChannelMode/playerTrackingEnabled/msToRegenerate/rateLimit/syncDuration/pingDistance`）；equals 覆盖全部 6（`:104-109`）、hashCode `Objects.hash` 同 6 项（`:114`）、toString 同 6 项 ✓。**唯一差异**：lombok 用 `ServerConfig(...)` 括号，手写用 `{...}` 花括号（`:92-97`），无调用点，仅日志观感（N1） |
| ConfigHandler `@SneakyThrows` 移除 | 只删注解与 `@Getter`（`load()` 现为 `public synchronized void load()`，`HEAD:167`）；方法体只做 `Files.exists`（不抛受检异常）并转发到 `loadWithLegacyRecovery/loadWithClientRecovery`，后两者内部 try/catch 包住 IO（`:184-206,228-243`）→ 不引入受检异常逃逸（**待一次编译确认**） |
| WorldRenderContext | 上游 `@AllArgsConstructor(staticName="of")` + `public final` 字段；手写版字段同为 `public final`、有 public 构造器与 `static of(...)`，**完全等价** ✓ |

---

## 二、集成桩化：等价（无集成时行为不变）

- **第三方 import 全清**：HEAD 下 `lombok / com.terraformersmc / de.maxhenkel / com.seibel / dev.ftb / dev.ryanhcode` **0 命中**。
- DH：`traceDistantAsync(...)` `{ return false; }`（HEAD `DistantHorizonsIntegration:22-24`），并在 javadoc 明确"gated off, no completion will ever be invoked"；调用方以 `false` 走同步射线回退——与"DH 未安装"路径一致 ✓。`logVersionDiagnostics()`/`logUnguardedLinkFailure()` 变为空实现（仅丢失诊断日志，无功能影响，N5）。
- VoiceChat：`VoiceChatWrapper.getGroupId/getSelfGroupId` 返回 `Optional.empty()`（等价"未安装"）；`VoiceChatIntegration` 变成**空类**并标注 `TODO(M9)`；`fabric.mod.json` 的 `voicechat` entrypoint **已删除**（HEAD 仅 `main`/`client` 两个），且全库**除自身外 0 引用** ✓。
- FTB Teams：`FTBTeamsWrapper.getTeamId/getSelfTeamId` 返回 `Optional.empty()` → `TeamContextHandler.hasTeam/inSameContext` 走"无队伍"分支 ✓。
- Sable：两条 create 路径都在且被保留——`SableClientCompanionAccess.create()`（`:19`，声明 `throws ReflectiveOperationException`）与 `create(SableDiagnostics)`（`:23`，返回 `UNAVAILABLE` 单例）；服务端 `SableExternalBlockServerProvider.create()`（`:27`）返回空实现，`ExternalBlockServerProviders` 仍按类名反射加载（`ExternalBlockServerProviders:17`）→ 类存在、加载即得"无 provider" ✓。
- ModMenu：移到 `deferred/modmenu/ModMenuIntegration.java`（HEAD 中 `deferred/` 只有这 1 个文件），`fabric.mod.json` 已无 `modmenu` entrypoint ✓。

## 三、机械改名：正确

| 改名 | HEAD 计数 | 结论 |
|---|---|---|
| `ResourceLocation` → `Identifier` | `ResourceLocation` **0**；`Identifier` 139 行 / 36 文件 | 工厂方法用法正确：`fromNamespaceAndPath/parse/tryParse/withDefaultNamespace`（如 `MarkerCreateC2SPacket:13`、`MinecraftClientTargetValidator:115`）；**未发现误替换字符串/注释**（`git grep '"Identifier"'` 0 命中） |
| `KeyBindingHelper` → `KeyMappingHelper` | 2 行（import + 调用），新包 `api.client.keymapping.v1` ✓ | `FabricClient` 内 `KeyMappingHelper.registerKeyMapping(...)` ✓ |
| `PayloadTypeRegistry.playC2S/playS2C` | 1 import + **14 次注册**（`serverboundPlay` 6 / `clientboundPlay` 8） | 方向映射正确：所有 `*C2S` → `serverboundPlay`、所有 `*S2C` → `clientboundPlay`（`FabricMain:45-58` 逐行核对）✓ |
| `setScreen` → `setScreenAndShow` | 7 处（`CommonClient:255`、`ClientCommandBuilder:65`、`SettingsScreen:802,970,986,988`、`MinecraftMixin:15`）；`.setScreen(` **0 残留** | ✓ |
| Mixin 描述符字符串 | 只有 `MinecraftMixin:15` 同步为 `setScreenAndShow` | **其余 5 个未同步** → 见 S2（M7 范围） |

## 四、main→client 依赖

- HEAD 下 `src/main` 对 `src/client` 专属 FQN 的 import：**0**（FQN 集合比对）✓；main 内仍无 `net.minecraft.client.*` / `blaze3d`（M2 结论维持）。

---

## 五、分级问题

### 阻断

- **S1**（见 §1.1）：`ClientConfig` 无 `hashCode` → `ConfigHandler:148` 判定恒真 → 客户端配置保存静默失效。**M4 前必修**，否则所有客户端设置/开关热键都不落盘。

### 严重

- **S2（预期内，但会挡 M4）**：HEAD 的 `pingforit.mixins.json` 仍列 7 个 mixin，其中 5 个的注入目标在 26.3 不存在：
  - `GameRendererMixin:23` `method = "render"`（回调签名仍为 1.21.1 的 `(DeltaTracker, boolean, CallbackInfo)`；26.3 `render()` 无参）
  - `GameRendererMixin:33/45` `renderLevel(Lnet/minecraft/client/DeltaTracker;)V`（26.3 不存在）
  - `LevelRendererMixin:55,89,117,142,160,181` 目标 `renderLevel` / `OutlineBufferSource` / `PostChain.process(F)V`（26.3 均已不存在）
  - 配置是 `required: true` + `GameRendererMixin:33` 还写了 `require = 1` → **runClient 会启动即崩**。
  - 处理：M4 前把这 5 个从 `client` 数组移除（或按 M7 重写）。**注：当前工作区已有人这么做了（config 缩到 2 个、文件移入 `deferred/`），但未提交。**

### 警告

- **W1 工作区与 HEAD 不一致**：107 项 dirty（91 `D`、8 `M`、8 `??`，`deferred/` 92 个未跟踪文件，mtime 19:51 > 提交 19:45）。任何人此刻构建/评审都会看到"半移动"造成的 30 处悬空 import。建议：要么由 2号 立即提交（作为 M3b），要么 stash；并明确以哪个提交作为验收基线。

### 建议

- **N1**：`ServerConfig.toString` 花括号 vs lombok 圆括号——无调用点，可不改。
- **N2**：`ClientConfig` 建议补 `toString`（排障可读性），但不影响保存逻辑。
- **N3**：`ClientConfig.MAX_CHANNEL_LENGTH` 现指向 `ProtocolLimits`（值 128，与上游一致）——保持单源，好。
- **N4**：`GameRendererMixin:33` 的 `require = 1` 在目标缺失时是硬失败；M7 重写时别原样保留。
- **N5**：DH `logVersionDiagnostics()/logUnguardedLinkFailure()` 空实现——确认没有调用点依赖其副作用（现有调用只是记日志，无功能影响）。

## 六、复现命令（只读）

```
git show HEAD:src/main/java/nx/pingwheel/common/config/ConfigHandler.java | Select-String 'configHash == config.hashCode'
git grep -n 'ResourceLocation' HEAD -- src              # 期望 0
git grep -n 'PayloadTypeRegistry\.' HEAD -- src        # 期望 14 次注册
git grep -n 'method = ' HEAD -- src/client/java/nx/pingwheel/common/mixin
git status --porcelain                                # W1：应为空才算与 HEAD 一致
```
