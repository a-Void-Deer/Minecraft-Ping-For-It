# 59 · P0 重构验收复审

任务：P0 重构后验收的代码侧独立复审 + 对 1号 实机清单的复审
审核人：3号　日期：2026-11-03　对象：`common/`+`fabric/` 重构（e8790a5/f18e31e）、`processResources`（e8790a5）、improved-transparency 路由（d7c2975）、M9-2 VoiceChat；`docs/restructure/02-p0-in-game-checklist.md`。
方法：只读源码；`javap` 反汇编 26.3 client jar；读取现有 `fabric/build/resources/main` 与 `fabric/build/libs`；未运行 Gradle/游戏（沙箱禁止）。

## 0. 结论速览（A–E）

| 项 | 裁决 | 一句话 |
|---|---|---|
| A dev 资源合并 | **PASS（静态）** | `processResources` 从 common 合并到 fabric 资源根；`jar` 只合并 common classesDirs，资源只经 processResources 一次；dev 资源根已有 assets/icon。实际资源索引仍需 runClient 验证。 |
| B improved transparency 路由 | **有条件通过 / 有 1 个实现边界** | `outline` 相位确实在 OIT 之后执行，`hasAnyOutline` 会被我们进入；但 `classicTransparencyActive` 只看 `options.improvedTransparency`，没有同步 `renderWireframeTerrain`；且 `outline` 进的是 entityOutlineTarget，颜色/亮度不能断言与直出一致。 |
| C VoiceChat 插件加载 | **PASS（静态）** | Fabric 走 `voicechat` entrypoint；类在 common 但最终合并进同一 jar，visible；入口懒加载/初始化顺序无阻断。`Loaded 1` 仍需实机（当前 4 jar 环境基线 0）。 |
| D 未登记的重构行为 | **PASS（除 B 的 gate 细节）** | `processResources`、jar 合并、runDir、SPI、common split、common run 禁用、outline 回退均已在 55/57/58/24 登记；未发现其它影响运行期的未登记项。 |
| E 静态 vs 实机 | **见 §2.5** | 可静态判掉的：V2、V3（且 V3 风险高）、V15 的“第二次不出现”根本不可判；必须实机：V1（构建）、V4–V14/16/17，以及清单缺的 runServer/安装 jar。 |

---

## 1. A–E 逐条

### A. dev 资源合并（PASS 静态）

`fabric/build.gradle:58-70`：
```groovy
tasks.named('processResources') {
    dependsOn generateModMetadata
    from project(':common').sourceSets.main.resources
}
```

`fabric/build.gradle:75-84`：
```groovy
jar {
    dependsOn project(':common').tasks.named('classes')
    from project(':common').sourceSets.main.output.classesDirs
    from(rootProject.file('LICENSE')) { rename { it + '_pingforit' } }
}
```

- **为什么 dev 资源能读到**：`runClient` 的 mod root 是 **fabric** 的 `build/resources/main`（该根含 `fabric.mod.json`）；`processResources` 已把 common 的 `assets/pingforit/**`、`icon.png` 复制进来。现有 `fabric/build/resources/main` 实测含 `fabric.mod.json`、`icon.png`、`assets/pingforit/{sounds.json,lang/8,sounds/ping.ogg,textures/2}` 与 3 个服务端 SPI；因此 dev 路径不再依赖“common jar 根”的资源索引。
- **为什么 jar 不重复**：`jar` 默认只带 fabric 自身 `sourceSets.main.output`（其中 resources 已经是 processResources 结果）；`from(common.classesDirs)` 只带 `.class`，不带 common 资源。资源只经 processResources 进一次，class 只来自 fabric 输出 + common classesDirs；与用户实测 `assets 16 项 / uniq -d 空` 的因果一致。
- **边界**：静态只能证明 `fabric/build/resources/main` 里有文件；Fabric 资源包真正索引、语言/声音/图标的运行时行为只能 runClient 验证（V6–V8）。

### B. improved transparency 路由（有条件通过）

#### B1 `outline` 相位确实在 OIT 之后，且会执行（成立）

`LevelRenderer.lambda$addMainPass$0`（26.3 client jar，偏移 177–248）：
```text
177: iload_2; ifne 190          // improvedTransparency ? skip classic : executeClassicTransparency
181: executeClassicTransparency(...)
234: iload_2; ifeq 245          // if improved
238: executeOit(...)
245: executeOutline(preparedFrame)   // 无条件调用，方法内再判 currentFrameRendersEntityOutline
```
`executeOutline`（`LevelRenderer:1828`）：
```text
0: getfield currentFrameRendersEntityOutline; ifeq 89
20: entityOutlineTarget.getColorTextureView()
47: preparedFrame.executeOutline(pass)
```
`currentFrameRendersEntityOutline` 的来源（`LevelRenderer.render` 偏移 104–128）：
```text
107: PreparedFrame.hasAnyOutline()
117: LevelRenderState.shouldShowEntityOutlines
128: putfield currentFrameRendersEntityOutline
```
`FeatureRenderDispatcher$PreparedFrame.hasAnyOutline` 的字节码遍历 `groupsByPhase`，只看 `SubmitNodeCollection.outline` 这个 `SimpleFeatureRenderPhase`；我们的 `this.outline.submit(submit)` 正好把它填满，所以 **alpha=255 的提交会进入 `hasAnyOutline()`**。`shouldShowEntityOutlines` 在游戏内为真（主菜单全景/无玩家才假），所以 outline target 会被 blit。

#### B1-risk：`classicTransparencyActive` 与 vanilla gate 不同

`GameRenderer.useImprovedTransparency()`（26.3 字节码）实际是：
```text
optionsRenderState.improvedTransparency && !levelRenderState.renderWireframeTerrain
```
而 `SubmitNodeCollectionMixin.pingforit$classicTransparencyActive()` 只读：
```java
minecraft == null || !minecraft.options.improvedTransparency().get()
```
两者在 **`renderWireframeTerrain == true`** 时不一致：vanilla 会走 `executeClassicTransparency`（afterTerrain 可用），我们的 mixin 却会把 afterTerrain 提交改投 `outline`。默认/F3 通常不触发，但这是 **未登记的 gate 差异**，建议把 helper 改成 `Minecraft.getInstance().gameRenderer.useImprovedTransparency()`（或同时读 level state 的 `renderWireframeTerrain`）。

#### B2 `outline` 不是 afterTerrain 的等价物（颜色/合成路径）

- `executeOutline` 渲染进 **独立的 `entityOutlineTarget`**（`ZERO_CLEAR`），随后 `blitEntityOutline()`（`LevelRenderer`）用 `blitAndBlendToTexture(mainColor, mainDepth)` 合回主画面；`24` #19 已登记：**几何/管线/线宽不变，但最终合成走原版 `ENTITY_OUTLINE_BLIT` 的 blend，颜色/亮度需实机比对**；该 blit **没有深度状态**，所以穿墙性质保留。
- 因此 V14 的“两图颜色/线宽肉眼一致”不是可保证的期望，只能 **记录差异** 并在 #19 评估；线宽来自同一管线，通常不变，颜色/亮度可能变。
- 更贴近 afterTerrain 的其它选择：`alwaysOnTopGizmos` 相位虽然也在 outline 之后、同一主 target，但 `frameHasAlwaysOnTopGizmos()` 只检查 `finalizedGizmos.alwaysOnTopPrimitives().isEmpty()`，不是任意 shape submission；把它当作自定义几何的 afterTerrain 替代会绕过 target/资源分配，风险更高。**在当前 26.3 相位模型下，outline 是唯一可用的“OIT 之后”的通用 SimpleFeatureRenderPhase；保留它但要按 #19 登记。**

### C. M9-2 VoiceChat（PASS 静态）

- `fabric/src/main/templates/fabric.mod.json`：`voicechat` entrypoint 指向 `nx.pingwheel.common.integration.VoiceChatIntegration`；`common/build.gradle` 编译该类，`fabric` 的 jar 合并 `common` classesDirs（`fabric/build.gradle:79-80`），最终单 jar 可见。
- Fabric 下 VoiceChat 2.6.27 的发现路径是 **entrypoint**（上游 fabric 产物同样用 `voicechat` key；`@ForgeVoicechatPlugin` 是 Forge 扫描用的注解，Fabric 不读它；保留是为了逐字节保真）。
- 初始化顺序：VoiceChat 在自身初始化时读取插件 entrypoint；`VoiceChatIntegration` 的 `serverApi`/`clientApi` 分别由 `VoicechatServerStartedEvent`/`ClientVoicechatInitializationEvent` 缓存；`VoiceChatWrapper` 在 API 未就绪时返回 empty，不会阻塞。`common` 合并进同一 jar，不存在“两个模块看不到类”的问题。
- 空载安全：`fabric.mod.json` 的 `voicechat` key 不是 Fabric Loader 自动实例化的 main/client/server；没有 VoiceChat 时不会请求该 entrypoint。`VoiceChatWrapper` 先判 `ModContext.HasVoiceChat`，false 时短路，不解析 `VoiceChatIntegration.serverApi`。

### D. 重构未登记行为（PASS，除 B 的 gate 细节）

- **已登记**：`processResources` 合并 common 资源（AGENTS §7.16）、jar class/resources 合并方式（55 B4）、`runDir` 根 `run/`（00-plan R4）、SPI 分模块（57 H11）、`common` split（R6）、`common` runClient disabled（55 P1-2）、outline 回退（24 #19）。
- **未登记但影响运行期的**：只有 B1 的 `classicTransparencyActive` 未同步 `renderWireframeTerrain`（F3 wireframe 边角）。除此之外没有发现新的运行期行为差异；`runServer` 覆盖缺口是“未验证”而不是“已知差异”。

### E. 静态 vs 实机（详见 §2.5）

- **静态可判**：A 的 processResources/jar 因果、B 的 outline 相位/顺序/`hasAnyOutline`、C 的 entrypoint/类可见性、D 的模块/SPI、V2（jar 计数）、V3（静态 fail-fast 代码）。
- **必须实机**：V1 构建（真实 Gradle）、V4–V14、V16、V17，以及清单未覆盖的 `runServer` 和“安装发布 jar 启动”。

---

## 2. 对 P0 实机清单的复审

### 2.1 必改（会误导用户或判据不成立）

| 条目 | 问题 | 建议改法 |
|---|---|---|
| **V1** | 期望“日志里有两行 `compileOnly integration jars (...)`”。若 `./gradlew build` 的 compile 任务 **UP-TO-DATE**，doFirst 不会执行，**两行根本不会打印**，用户会误判构建失败。 | 改成 `./gradlew clean build` 或 `build --rerun-tasks`；或把两行标为“仅当编译任务实际执行时出现”，不作为通过条件。 |
| **V3** | 反向验证要求制造缺 jar 的失败；正常有 jar 时永远不会触发，用户只能临时移走 jar，**容易忘记恢复**；静态代码 + V1 已足够。 | **从常规清单删除**，或标为“仅在怀疑 fail-fast 时手动做，做完立即恢复 jar”。不要把它作为 P0 通过条件。 |
| **V7** | 示例里 `pingforit.chat.pingmsg` 只在聊天 legacy fallback 出现，`key.pingforit.ping_location` 在**按键设置**而非设置界面；用户在设置界面看不到它们，可能误以为已覆盖。 | 设置界面只检查 `settings.pingforit.*` / `unit.pingforit.*`；聊天键另在“发送一次 ping 的聊天行”检查；键位名另在“控制/按键绑定”检查。不要用一个界面的截图声称覆盖四类 key。 |
| **V14** | “两图颜色/线宽肉眼一致”与 24 #19 的结论矛盾：outline 进 entityOutlineTarget 后经 `ENTITY_OUTLINE_BLIT` 合成，颜色/亮度可能变；要求一致会把正常差异误判成失败。 | 改成“记录颜色/亮度/线宽差异；线宽应基本一致，颜色差异记录后回到 #19 评估”。 |
| **V15** | “关掉 improved 后再 ping，日志不再出现新行”是**一次性 logger 的必然结果**；即使 V15 仍错误走 fallback，`fallbackLogged` 已为 true，也永远不会再打。这个负向断言无法证明 V15 的路径正确。 | 删掉 V15 的日志断言；V13 的“恰好一条”已覆盖一次性。若要看关闭后的视觉，只保留截图/描边可见性，不把“无新日志”作为证据。 |
| **V16** | “`send ping` 与 `marker created` 严格 1:1”只在**单客户端、无拒绝、无其他接收者**时成立；做 V10 两客户端或服务端拒绝/限流时会被打破。 | 明确条件：“单人客户端、无 reject、只看本客户端日志”；或按 `requestId` 配对，拒绝/其他客户端另行解释。 |
| **覆盖缺口（必须补）** | 清单没有 `runServer`，也没有“安装发布 jar 启动”；V4 只验证 dev runtimeClasspath，V2 只 unzip jar。 | 增加 **runServer 冒烟**（模块/SPI/服务端 VoiceChat/无客户端类加载）与 **安装 jar 启动**（资源/entrypoint/SPI/Mod Menu/VoiceChat 从发布 jar 加载）。 |

### 2.2 建议改（判据可保留但需加条件）

| 条目 | 建议 |
|---|---|
| **V9** | 当前 4 jar 环境（`voicechatprofiles` 没有 `voicechat` entrypoint，VoiceChat 自己也不注册插件）基线确为 `0`，所以 `Loaded 1` 在本清单里可用；但它不是“必然 1”。建议改成“`Loaded` 比上次的 `0` 多 1（本次应为 1）”，并注意以后加别的 VoiceChat 插件会改变计数。 |
| **V10** | 标为可选可以接受（P0 重构不强制两客户端），但 **M9-2 的功能验收**若跳过 V10，就只证明了插件被发现，没有证明 VC 分组广播；建议在 M9-2 自己的结论里保留“未验 V10”。 |
| **V2** | 构建期静态检查，不是实机项；既然用户已实测 392 class/无重复，可从实机清单删除或标“可选，静态已做”。 |
| **V11** | M9-1 已在 2026-11-03 18:27 实机通过；本清单可不重复，保留为回归即可（不阻塞）。 |
| **V4 的期望日志顺序** | `Init → ServerConfig → ClientConfig → pipeline...` 是当前实现的顺序；Gradle/Loom 并行或日志交错时可能不完全连续，建议只要求“这些行都出现”，不要求严格相邻。 |

### 2.3 可保留（判据成立，建议保持）

- **V4/V5** 启动与冻结：真实、必要。
- **V6** Mod Menu 图标：Mod Menu 用 mod root 的 `icon.png`，dev 资源未合并时会退回默认/空白；可保留。**但**它只验 Mod Menu 图标，不验设置界面内部图标（两者来源不同）；不要把它当作“所有资源都 OK”。
- **V8** 声音：`sounds.json`/ogg 未合并时会有 `Missing sound`；可保留。
- **V11** 可保留（M9-1 回归），但不属于 P0 重构核心。
- **V12/V13** 默认/OIT 视觉与那行 info：成立；V13 文本与 `OutlineRoutingState.java:47-48` 逐字一致，`fallbackLogged` 是 **per-session 一次性**（static volatile，未 reset），所以“恰好一条”可靠。
- **V17** 全日志 ERROR 扫描：可保留；注意允许的噪声列表里 voicechat 现在会从 0 变 1 插件，不影响。

### 2.4 覆盖缺口（建议新增 V18/V19）

- **V18 `runServer` 冒烟**：`common`/`fabric` 拆模块后服务端 SPI（3 个 `META-INF/services`）、`FabricMain`、`ServerCore`、`ModContext` 和 `VoiceChatIntegration` 的 server 侧都只在这一路径验证；还可发现 client-only 类被误加载。
- **V19 发布 jar 启动**：V4 是 dev runtimeClasspath，V2 只是 unzip；应把 `fabric/build/libs/...jar` 放进**干净的 mods 目录**启动一次（或独立实例），验证 jar 内资源、4 个 entrypoint、5 个 SPI、Mod Menu 图标从 jar 可见。**这不是可选**，因为 P0 的最终交付物是 jar，不是 dev classpath。
- **V20（可选）专用服务器 + VoiceChat**：V10 的两客户端通常是集成服务器；专用服务器上 `voicechat` entrypoint 的 server 事件/`serverApi` 路径不同，建议至少记录“未验”。
- **SPI** 的客户端 2 条 + 服务端 3 条已在 V4/V18 隐式覆盖，但不会在 V2 的 unzip 里被真正加载；V18/V19 是补足。

### 2.5 静态 vs 实机（可直接从清单删/改的）

| 条目 | 能否静态判定 | 结论 |
|---|---|---|
| V2 | **能**（jar 计数/重复条目） | 实机清单可删；构建期已验证即可。 |
| V3 | **能**（代码静态） | 实机清单应删/可选；反向验证风险高、收益低。 |
| V9 | 入口/类可静态；**Loaded 计数必须实机** | 保留实机；判据加 baseline。 |
| V12/V13 的相位/顺序 | **能静态**（本报告已给出 javap）；但“看得见/颜色”必须实机 | 保留视觉验证；V13 的日志文本可静态核对，实机确认出现即可。 |
| V15 | **不能**用一次 logger 的“无新行”判定 | 必须删/改；不是实机可判，是逻辑上不可判。 |
| V14 | 只能实机 | 保留，但改“记录差异”。 |
| V16/V17 | 只能实机（日志计数/扫描） | 保留，V16 加条件。 |
| A（dev 资源） | `processResources`/jar 文件可静态；资源包索引必须实机 | V6–V8 保留；构建目录检查可静态辅助。 |
| B（outline） | 相位/顺序/gate 静态；颜色/穿墙实机 | V12–V14 保留，V14 改判定。 |
| C（VoiceChat） | entrypoint/类可见性静态；Loaded/分组实机 | V9/V10 保留。 |
| D（重构） | 静态 | 无需实机；runServer/jar 是覆盖缺口，不是 D 的静态问题。 |

### 2.6 误导风险总结

- **V1** 把“UP-TO-DATE 不打印”误判为失败。
- **V3** 把“反向缺 jar”当常规验证，实际是手工破坏环境。
- **V7** 用设置界面验聊天/按键 key，覆盖不到。
- **V9** 把“Loaded 1”当必然；当前环境是 1，但未来/其他插件会变。
- **V14** 把“颜色可能不同”误判为失败。
- **V15** 把“一次性 logger 不再打印”当路径验证，实际不可判。
- **V16** 把单人 1:1 扩大到所有场景（两客户端/拒绝）。
- **V2** 把构建期静态检查放进实机清单，浪费用户时间。

---

## 3. 结论

- **A PASS（静态）**：`processResources` 合并 common 资源 + `jar` 只合并 common classesDirs，因果上 dev 能读到 assets/icon，jar 不重复；实际资源索引需 V6–V8。
- **B 有条件通过**：outline 相位/顺序/`hasAnyOutline` 成立；但 `classicTransparencyActive` 漏了 `renderWireframeTerrain`，且 outline 走 entityOutlineTarget 的合成颜色不能要求“肉眼一致”；V14/V15 必须改。
- **C PASS（静态）**：VoiceChat Fabric entrypoint + common 类合并进 jar；无可疑可见性/初始化顺序；Loaded 1 需实机。
- **D PASS**：除 B 的 gate 细节外，未发现其它未登记运行期行为；`runServer`/安装 jar 是清单覆盖缺口。
- **E**：V2/V3 可静态删；V15 逻辑上不可判；其余关键项需实机。

---

## 3. B 项修复确认（2026-11-03）

### 结论

**通过。** `pingforit$classicTransparencyActive` 现在与 `GameRenderer.useImprovedTransparency()` 在状态可用时严格互为否定；null 兜底返回 `true`（经典路径）是保守且不会把正常渲染帧误送 outline。`hasAnyOutline()` 不依赖 options，仍是纯 phase-group 判定。没有发现新的 B 口径差异。

### A. 等价关系（严格成立，含 null 兜底）

`GameRenderer.useImprovedTransparency()` 的 26.3 字节码：
```text
optionsRenderState.improvedTransparency && !levelRenderState.renderWireframeTerrain
```
本地：
```java
return !renderState.optionsRenderState.improvedTransparency
    || renderState.levelRenderState.renderWireframeTerrain;
```
在 `optionsRenderState`/`levelRenderState` 都非空时，这是 `!(A && !B) = !A || B`，**严格等价**；`renderWireframeTerrain` 为 true 时回到 afterTerrain，与 vanilla 的 classic 分支一致。

**null 兜底**：`minecraft == null || gameRenderer == null` 或 `renderState/options/level` 任一为空时返回 `true`（当作 classic，投 afterTerrain）。这与实际渲染路径不矛盾：`submitShapeOutline` 的调用点在 `LevelRenderer.submitFeatures` 的收集阶段（`BlockOutlinePass` 的 `COLLECT_SUBMITS`，Fabric `LevelRendererMixin` 注入在 `submitFeatures` RETURN），此时 `GameRenderer.extract` 已在同一帧写入 `gameRenderState`；状态没就绪时也不会有一个随后执行 OIT、却把 afterTerrain 跳过的有效渲染帧。若未来有代码在 extract 之前提交 outline，这个保守兜底会失去 afterTerrain，但那不是当前调用链，且 extract 之前也没有合法的当帧 phase 调度可依赖。

### B. mixin 注入点调用安全性（安全）

- `SubmitNodeCollection.submitShapeOutline` 的 HEAD 处于 **收集阶段**，不是 `GameRenderer.extract` 之前；`BlockOutlinePass` 在 `LevelRenderEvents.COLLECT_SUBMITS`（`LevelRenderer.submitFeatures` RETURN）提交，此时 `gameRenderer.gameRenderState()` 已是本帧状态。
- `Minecraft.getInstance().gameRenderer` 在客户端初始化后恒非空；`gameRenderState()` 返回的是 `GameRenderer` 持有的当前 `GameRenderState`，不是按帧复制的旧对象。收集阶段调用不会拿到上一帧 state：`extract` 先更新 state，`submitFeatures` 才收集 outline。
- 即使在极端早期/菜单路径拿到 null，A 的兜底只影响“是否有自定义 outline 可提交”，不会 NPE；`GameRenderState` import 只是类型。

### C. 其它口径差异（无新增）

- `PreparedFrame.hasAnyOutline()` 的字节码只遍历 `groupsByPhase` 的 **`SubmitNodeCollection.outline`**，不看 `Options`/`GameRenderer`；我们的 `outline.submit` 已经填充该 phase，因此 `hasAnyOutline()` 仍为 true。
- `executeOutline` 仍由 `LevelRenderer.lambda$addMainPass$0` 在 `executeOit` 之后调用；`currentFrameRendersEntityOutline` 仍由 `hasAnyOutline() && shouldShowEntityOutlines` 决定。
- `SubmitNodeStorage` 的 `useImprovedTransparency` 只影响 vanilla 自己的 shape 路由；我们自定义 render type 走 mixin，已按同一 gate 对齐。
- **不改上一轮 V14 的结论**：outline 仍进 `entityOutlineTarget`，经 `ENTITY_OUTLINE_BLIT` 合成，颜色/亮度必须记录差异，不能要求“肉眼一致”。这与本次 gate 修复无关。

### 证据 / 编译

- 用户已跑 `bash tools/compile-check.sh`：`common 262 / fabric-main 4 / fabric-client 126，0 error`；本次未改代码，只复核 diff。
- `SubmitNodeCollectionMixin.java:111-130` 的 null 分支与否定表达式与 `GameRenderer.useImprovedTransparency()` 字节码逐项对应。
