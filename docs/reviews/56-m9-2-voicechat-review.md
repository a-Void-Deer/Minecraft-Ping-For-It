# 56 · M9-2 Simple Voice Chat 集成复审

任务：M9-2（Simple Voice Chat 集成）独立审查　审核人：3号　日期：2026-11-03
对象：`src/main/java/nx/pingwheel/common/integration/VoiceChatIntegration.java`、`VoiceChatWrapper.java`、`src/main/templates/fabric.mod.json`（未 commit）。
基线复核：`bash tools/compile-check.sh` → `main errors=0 classes=266 | client errors=0 classes=126`；`run/mods` 命中 `voicechat-fabric-2.6.27+26.3.jar`。

## 结论

**通过。** 两个类与上游逐字节一致；`voicechat` entrypoint 键与上游一致；没有新增 `depends`/`recommends`；插件 id、API 调用、团队优先级、LinkageError 降级与上游一致。剩余两处为非阻断边界：`serverApi`/`clientApi` 字段解析在 `try` 外；CI 用 `voicechat-api`、本地用完整 jar（当前 API 面相同）。

---

## 一、非阻断项

### B1 · `serverApi`/`clientApi` 字段解析不在 LinkageError 保护范围内（非阻断，上游同形）

**位置**：`VoiceChatWrapper.java:14`、`:31`。

**现象**：
```java
if (!ModContext.HasVoiceChat || LINK_GUARD.disabled() || VoiceChatIntegration.serverApi == null) return Optional.empty();
try { ... serverApi.getConnectionOf(...) } catch (LinkageError error) { ... }
```
`VoiceChatIntegration.serverApi` 的字段解析/类初始化发生在 `try` 之外。若 `HasVoiceChat=true`（voicechat mod 已加载）但 API 类缺失/不兼容，`NoClassDefFoundError` 会直接冒泡，`LINK_GUARD` 捕不到。

**为什么非阻断**：`HasVoiceChat` 由 `isModLoaded("voicechat")` 置位；完整 voicechat jar 自 2.6.27 自带全部 API 类（本 jar 104 个 API class，含 `VoicechatServerApi`/`VoicechatClientApi`），正常路径不会触发；上游同文件逐字节一致，改成 try 内会改变上游行为。若未来要加固，可把字段读取挪进 try，或在 `VoiceChatIntegration` 类加载前做 API 版本检查，但需登记为偏离。

### B2 · CI 的 `voicechat-api` 与本地完整 jar 的编译面（非阻断）

**位置**：`.github/workflows/build.yml`（CI 下载 `voicechat-api-2.6.27.jar`）vs 本地 `run/mods/voicechat-fabric-2.6.27+26.3.jar`。

**现象**：本地 `compile-check` 用完整 jar，CI 用 API jar；M9-2 只 import API 类，API jar 应包含 `ForgeVoicechatPlugin`/`VoicechatClientApi`/server event（上游 `fabric` 也 compileOnly API，源码逐字节一致，正常可编译）。

**判定**：当前不阻塞；但这是“本地 full / CI API”的又一次潜在漂移。M9-3 的 API/full 决策应统一处理；M9-2 至少应在 CI 实跑一次 Gradle（而不是只跑本地 compile-check）确认 API jar 含全部 3 个 import。

---

## 二、必查项 A–H

### A. 保真（通过）

- `VoiceChatIntegration.java`：
```text
upstream SHA256 = 3EE5C452739E5D961D7F10DD6F09FD305B37D6E5E00F9FDF3D373AB8C740E317
local    SHA256 = 3EE5C452739E5D961D7F10DD6F09FD305B37D6E5E00F9FDF3D373AB8C740E317  IDENTICAL
```
- `VoiceChatWrapper.java`：
```text
upstream SHA256 = 5F2D73B6BBCFB295CF823BE0E9F695848D878ED0FCBFDEE31B07BB65160CEAF6
local    SHA256 = 5F2D73B6BBCFB295CF823BE0E9F695848D878ED0FCBFDEE31B07BB65160CEAF6  IDENTICAL
```
- 上游 `fabric/src/main/resources/fabric.mod.json` 的 `voicechat` entrypoint 值就是 `nx.pingwheel.common.integration.VoiceChatIntegration`；本地名称一致。
- 上游 `depends` 只有 `fabricloader`/`fabric-api`/`minecraft`，**没有 voicechat 的 depends/recommends/suggests**；本地模板也没有额外声明。
- `TODO(M9)` 现在只剩 DH（5 处）、FTB（1）、Sable（1）；**VoiceChat 的 TODO 已清零**。

### B. @ForgeVoicechatPlugin 的 Fabric 惰性（通过）

- 注解类 `de.maxhenkel.voicechat.api.ForgeVoicechatPlugin` 的字节码是 `RUNTIME` retention；但 Fabric 的 VoiceChat 发现方式是 `voicechat` entrypoint，不是注解扫描；上游 fabric 产物也保留该注解（本文件逐字节一致），因此保留是保真而非 Fabric 行为依赖。
- 注解缺失/未被读取不会在 Fabric 上触发 Forge 扫描；`VoicechatPlugin` 实现通过 `fabric.mod.json` 的 `voicechat` entrypoint 被 VoiceChat 读取。
- 缺 API 类时：`VoiceChatIntegration` 只由 entrypoint 惰性加载；无 voicechat 时不会被请求，不加载类，annotation 也不会被解析。

### C. 服务端/客户端安全（通过，带边界）

- `VoiceChatIntegration` 在 `src/main` 只是“双端都编译”，不等于“双端都提前加载”：Fabric Loader 的 entrypoint 是懒实例化（`EntrypointStorage.add` 只存 `NewEntry`，`EntrypointContainerImpl.getEntrypoint` 才 `getOrCreate`），`voicechat` key 只有 VoiceChat 会请求。
- 无 voicechat：不会请求 `voicechat` entrypoint；`VoiceChatWrapper` 先判 `ModContext.HasVoiceChat`，为 false 时短路，不会解析 `VoiceChatIntegration.serverApi`。
- 有完整 voicechat：jar 内含 `VoicechatServerApi`/`VoicechatClientApi`/两个事件类；专用服务器加载该类不会缺 client API 类（完整 jar 的 API 面完整）。
- `registerEvents` 同时注册 server-started 和 client-init；server 只触发 server 事件、client 只触发 client 初始化事件，upstream 同形。

### D. VoiceChatWrapper 语义（通过）

- 返回类型仍是 `Optional<UUID>`；无 voicechat/guard disabled/api null 时返回 `Optional.empty()`，与 stub 的降级契约一致。
- `player.getUUID()` 在 26.3 编译通过，调用 `serverApi.getConnectionOf(UUID)` 正确；`getGroup()` 为 null 时返回 empty。
- 团队上下文优先级未变：`TeamContextHandler.getContext` = VoiceChat > FTB > vanilla team > NONE；`inSameContext` 同样先 VoiceChat 再 FTB；客户端 `ClientTeamContextHelper` 同序。
- `LinkageError` 捕获覆盖 `NoSuchMethodError`/`IncompatibleClassChangeError`/`NoClassDefFoundError` 等（都是 LinkageError 子类）；`LINK_GUARD.disable(error)` 实现一次性 warn + debug，和 `IntegrationLinkGuard` 语义一致。字段解析在 try 外的边界见 B1。

### E. 插件 id（通过）

- `Global.MOD_ID = "pingforit"`；`getPluginId()` 返回 `MOD_ID`，与上游一致。
- `run/mods` 的 `voicechatprofiles-fabric...` 是另一个 mod（46 KB），其 id 不是 `pingforit`；未发现插件 id 冲突。VoiceChat 插件 id 由插件自己声明，`pingforit` 唯一。

### F. 编译产物（通过）

- `main sources=166 / classes=266`、`client sources=80 / classes=126`，class 数与 M9-1 相同：因为 `VoiceChatIntegration`/`VoiceChatWrapper` 原来就有 stub 类，本轮只是替换方法体/字段类型，没有新增类。
- 改动文件新增 import 只有 `de.maxhenkel.voicechat.api.*`，无 `net.minecraft.client.*` / `com.mojang.blaze3d.*`；`src/main` 门禁未被突破。
- `fabric.mod.json` 解析通过：`environment=*`，`voicechat=nx.pingwheel.common.integration.VoiceChatIntegration`。

### G. 风险（通过，带非阻断）

- `LinkageError` 覆盖：API 方法调用上的 NoSuchMethodError/IncompatibleClassChangeError 都被捕获；字段解析边界见 B1。
- `serverApi`/`clientApi` 是 `public static` 可写；`git grep` 只有 `VoiceChatIntegration` 自身写入（`onServerStarted`/`onClientInitialized`），没有其它代码赋值。
- 初始化顺序：`serverApi` 由 server-started 事件设置、`clientApi` 由 client-init 事件设置；wrapper 在事件前返回 empty，事件后返回 group；上游顺序一致。
- `getSelfGroupId` 只在客户端 helper 调用；`getGroupId` 在服务端/团队上下文调用；不存在用错 API 的调用点。

### H. 是否顺手改坏（通过）

`git status --porcelain` / `git diff --stat` 只有 3 个文件：`VoiceChatIntegration.java`、`VoiceChatWrapper.java`、`fabric.mod.json`。没有碰 M9-1 的 ModMenu、build.gradle、workflow 或 M7/M8 代码。

---

## 三、待实机

- [ ] 装 `voicechat-fabric-2.6.27+26.3.jar`：日志应从 `Loaded 0 plugin(s)` / `Initialized 0` 变为 `1 plugin(s)`。
- [ ] 同组/不同组玩家：VoiceChat group 优先于 FTB/vanilla；开关/离开 group 后回退正确。
- [ ] 无 voicechat：客户端/专用服务器均无 `NoClassDefFoundError`，团队上下文回到 FTB/vanilla。
- [ ] 客户端与专用服务器分别装 voicechat：`serverApi`/`clientApi` 只在对应侧可用，无跨端调用异常。
- [ ] 与 `voicechatprofiles` 同时装：插件 id 无冲突，VoiceChat 日志只有一只 `pingforit` 插件。

---

## 四、结论

**通过。** 没有阻塞项；B1/B2 两处属于上游同形/构建面边界，非阻断。M9-1 代码和构建脚本未被触碰。

---

## 附：M9-2 提交前追加确认（2026-11-03）

范围：提交前 `build.gradle` 的集成检查泛化 + CI voicechat 改完整 jar；不重跑全量。

### 结论

**通过。** 前缀判断、闭包参数和 CC 面都成立；CI 的 voicechat 已与本地同为完整 jar，当前三个集成没有“CI 用 API、本地用 full”的产物差。

### A. `"voicechat-"` 不会命中 `voicechatprofiles-...`（通过）

- `String.startsWith("voicechat-")` 要求第 10 个字符是 `-`；`voicechatprofiles-fabric-26.3-1.0.1.jar` 在 `voicechat` 后是 `p`，所以返回 false。
- `voicechat-api-2.6.27.jar`（第 10 个字符是 `-`）会命中，这是允许的：API-only 与完整 jar 都能提供本集成需要的 API。

### B. startsWith 与大正则的一致性（通过，注意未来前缀）

- 大正则：`(modmenu|voicechat-api|voicechat|DistantHorizons|DistantHorizonsApi)-.*\.jar`；`voicechatprofiles` 不在 alternation 内，也不会被 `voicechat-` 前缀命中。
- 当前两次检查：`modmenu-` 只匹配 modmenu；`voicechat-` 同时匹配 `voicechat-fabric-...` 与 `voicechat-api-...`，与正则收进来的集合一致。
- **未来注意**：若给 DH 加 check，前缀不能用 `DistantHorizons-`（会漏掉 `DistantHorizonsApi-...`）；要么用两个前缀，要么用能同时覆盖 `DistantHorizons` / `DistantHorizonsApi` 的逻辑。当前没有 DH check，不影响 M9-2。

### C. 闭包两次调用的参数捕获（通过）

- `attachIntegrationJarCheck(String taskName, String requiredPrefix, String message)` 是 Groovy 闭包；每次调用创建独立的调用帧/参数绑定，内部 `configureEach` 的 `doFirst` 捕获的是**该次调用的** `taskName`/`requiredPrefix`/`message`，不会串到另一次调用。
- CC 视角：动作捕获 `File`、`Pattern`、`String`，不访问 project、不解析 FileCollection；`logger` 是 task action 的 logger。与第 3 轮判断一致，**仍安全**。`tasks.matching{...}` 仍是配置期，`projectsEvaluated` 只 warn，不进入执行期。
- 唯一语义点：`compileJava` 的 check 用 `voicechat-`，`compileClientJava` 的 check 用 `modmenu-`；两个任务各自只检查自己的前缀，不会互相满足。

### D. CI 换完整 voicechat jar 是否消除漂移（对 voicechat 是；全局仍未强制）

- 当前 workflow 下载 `voicechat-fabric-2.6.27+26.3.jar`（Modrinth CDN），本地 `run/mods` 也是同名文件；两边的 `de.maxhenkel.voicechat.api.*` 来自**同一个完整 jar**，不再有 `voicechat-api` vs full 的漂移。
- 完整 jar 的外层 API class 实测 104 个（含 `ForgeVoicechatPlugin`/`VoicechatClientApi`/`VoicechatServerApi`/两个事件类），足以编译；嵌套的 2.4 KB `voicechat-api-2.6.27.jar` 不参与本轮 classpath，**不影响**。
- 但 `build.gradle` 的 `include` 仍接受 `voicechat-api-*.jar`；如果某个本地环境只放 API jar，仍会与 CI 的 full 不同。所以“CI 与当前 dev 的 voicechat 漂移”已消除，“所有可能的本地组合都强制同 artifact”还没有；这不是本轮的阻塞点，M9-3 统一处理 API/full 时一起收口。

### E. 其他集成的 CI/本地产物（DH 已同 full，无差异）

- CI：`modmenu-21.0.0.jar`、`voicechat-fabric-2.6.27+26.3.jar`、`DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`；本地 `run/mods` 同名同版本（另有无头 `voicechatprofiles`，不在 glob/check 内）。
- **没有**“CI 用 API、本地用 full”的集成残留；DH 在上一轮已改成 full both。
- `build.gradle` 仍保留 `DistantHorizonsApi-*.jar` include，允许 API-only 轻量环境，但与 CI 的 full 不强制一致；建议在 M9-3 的 DH 决策中一并处理（与 voicechat 一样，要么 CI/local 都 full，要么都 API + 独立 compile-libs）。

### 提交前建议

- 真实 Gradle 跑一次（离线即可）：`./gradlew --offline tasks`（无 jar 不失败）、`build`（有 jar 通过）、缺 `modmenu`/`voicechat` 时分别在 `compileClientJava`/`compileJava` 的 doFirst 抛 URL 明确异常。
- 若想验证 CC：连续跑两次 `--configuration-cache build`，第二次 cache hit 且 run/mods 变化会作为 compileOnly 输入触发重编。
