# Ping For It · 26.3 移植方案（Fabric 单端）

> 依据：Mod_Project/analysis 下 8 份分析报告（2290 行）。本文件是施工依据，AGENTS.md §11 是摘要。
> 源码基线：Ping For It 0.4.2-pfi-beta1（MC 1.21.1 / Java 21 / multiloader）
> 目标：MC 26.3 / Java 25 / Fabric 单端 / Loom 1.19.3 / FAPI 0.165.0+26.3 / Loader 0.20.1

## 0. 一句话结论

**服务端权威、14 个网络包、配置系统几乎零成本；渲染层（描边/HUD/世界渲染钩子）是重写，不是改名。**
所以路线是：先把「不依赖渲染新模型」的链路跑通（MVP），再把渲染当成一个独立攻坚阶段。

## 1. 决策记录（已在方案中固定，移植中不再反复）

| # | 决策 | 理由 |
|---|---|---|
| D1 | 只保留 Fabric 端 | 用户决定 |
| D2 | mod id 改为 `pingforit` | 与上游一致（assets / sounds / 通道 / 命令名都不用改），减少改名面 |
| D3 | Java 包名先保留 `nx.pingwheel` | 上游 298 个文件全在此包下，改包是纯噪声；最后可选统一 |
| D4 | 产物名 `Ping-For-It-<version>-fabric-26.3.jar` | 延续上游命名，便于对照 |
| D5 | 暂时保留 ServiceLoader 平台层 | 少改一处是一处；单端下它是冗余但无害，M9 之后再考虑简化 |
| D6 | 不引入 Parchment | 26.3 是否有 Parchment 未确认；official 映射足够 |
| D7 | 移除 Lombok | 1.18.32 不支持 JDK 25 |
| D8 | 188 个测试类不进 M1–M9 | 它们依赖 NeoForm 编译产物，需在 M10 重建测试基座 |
| D9 | 可选集成逐项评估，最后做 | 26.3 下 DH / VoiceChat / FTB / Sable 版本未知 |

## 2. 现状对照

| 维度 | 上游（1.21.1） | 目标（26.3） |
|---|---|---|
| 加载器 | Fabric + Forge + NeoForge | 仅 Fabric |
| 构建 | buildSrc 两个 convention 脚本 + 三 loader 各自编译 common 源码 | 单模块 build.gradle（Loom 单源） |
| 编译基座 | ModDevGradle + NeoForm（common）、Loom（fabric） | 仅 Loom |
| 映射 | officialMojangMappings + Parchment | Loom 默认 official（无 refmap） |
| Java | 21 | 25 |
| 源集 | common 单源集（37 个文件含 client import） | split：main / client |
| 元数据 | 三端 mods.toml / fabric.mod.json | 仅 fabric.mod.json（模板生成） |
| 校验 | verifyModIdentity（三端 + refmap） | 只保留单端有意义的部分 |

## 3. 阶段计划

每个阶段结束都要：`git commit` + 打 tag + 填验收记录。任何阶段失败，回滚到上一个 tag。

### M0 · 基线冻结（0.5 天）
- 打 tag `pre-port`；确认工作区干净；把 Mod_Project 与 analysis 备份一份（Mod_Project 已 gitignore）。
- 验收：`git tag` 可见 `pre-port`，`git status` 干净。

### M1 · 构建骨架（1 天）
- 重写 `build.gradle`：Java 25、Loom 1.19.3（钉死）、FAPI 0.165.0+26.3、Loader 0.20.1。
- 保留 `splitEnvironmentSourceSets`、`generateModMetadata`、`jar` 打 LICENSE。
- 删除上游 verify 中与三端/refmap 相关的分支（详见 §5）。
- 现有 ping-for-it 样例代码先留着，保证这一步就能 build。
- 验收：`gradlew clean build` 通过，产物名符合 D4。

### M2 · 源码导入与源集拆分（2–3 天）
按 07-source-split.md 的施工单执行，顺序不能乱：
1. 整棵客户端子树（client / render / screen / mixin / CommonClient，116 文件）搬入 `src/client/java`，零改造。
2. 三个「放错源集」的类搬 client：ClientConfig / ClientCommandBuilder / Raycast。
3. 抽 `network/ProtocolLimits.MAX_CHANNEL_LENGTH`，解开 3 个网络包对 ClientConfig 的依赖。
4. **TeamContextHandler 按方法切**（唯一需要写代码的拆分）：getSelfContext 进 client，其余留 main。
5. 拆 `IPlatformContextService`：去掉 KeyMapping 方法，新增 client 子接口。
6. fabric 8 个文件接入：FabricMain→PingForIt、FabricClient→PingForItClient，ServiceImpl 按源集落位。
7. 改 mixins 配置与资源命名空间（pingforit）。
- 验收：`src/main` 下 grep 不到 `net.minecraft.client` / `com.mojang.blaze3d`；两边都能编译。

### M3 · 编译期 API 迁移（3–5 天）
机械改名，按 05-api-delta.md 执行：
- `ResourceLocation → Identifier`（35 文件，工厂方法 1:1）
- `FastColor → ARGB`（2）、`LightTexture → Lightmap`（2）、`RenderType` 换包（4）
- `PayloadTypeRegistry.playC2S/playS2C → serverboundPlay/clientboundPlay`（1）
- `KeyBindingHelper → KeyMappingHelper`（1，换包）
- `Minecraft.setScreen → setScreenAndShow`（1）
- `ClipContext` 换包（2）
- 移除 Lombok（8 文件）、补 `org.jetbrains.annotations` compileOnly（16 文件）
- 验收：`gradlew build` 通过。

### M4 · 最小可用闭环（3–4 天）★ 第一个可玩版本
目标：**无描边**，只证明「按键 → 服务端受理 → 其他人能看到」这条链路。
- 协议 14 个包全部保留（编解码可原样搬，只改 Identifier 与 Registry 名）。
- 客户端：按键注册（KeyMappingHelper）→ 按下捕获目标 → 短按发默认类型 → 发 MarkerCreateC2S。
- 服务端：接收 → 基础校验（在线/维度/距离/目标存在）→ 入库 → 广播 MarkerCreatedS2C。
- 客户端：收到 → `HudElementRegistry` + `GuiGraphicsExtractor` 在屏幕画一个点（屏幕坐标投影）。
- 绕开：所有描边、实体发光、世界渲染 mixin。
- 验收：进世界按 ping → 自己屏幕出现标记点；双人局域网对方也能看到；日志无异常。

### M5 · 服务端权威完善（2–3 天）
- 受众快照、赢家选择（arrivalTick → MarkerId，输家保留）、移除（仅本人）、限流、过期。
- 验收：双人互测；限流生效；非本人无法移除；断开重连后状态正确。

### M6 · 轮盘与 7 类型（3–4 天）
- 长按判定（生效 200ms / 超时 3000ms，注意别用兜底常量 300/5000）、轮盘 UI、中心取消五规则、类型目录与颜色。
- 验收：7 种类型都能发；中心取消只取消自己的最近标记；超时不发。

### M7 · 渲染重建（5–10 天）★ 最大风险
26.3 没有 `MultiBufferSource` / `OutlineBufferSource` / `RenderStateShard` / `WorldRenderEvents`，`LevelRenderer.renderLevel` 也已删除。
- 方块描边：VoxelShape → `ShapeOutlineFeatureRenderer` / 新 pipeline（需重新设计写法）。
- 实体发光：`LevelRenderer.blitEntityOutline` + 新的提交模型。
- HUD 完整版：屏外箭头、深度错开、物品图标。
- 7 个 mixin 里 6 个要重写（唯一幸存目标：KeyMapping.click/set/releaseAll）。
- 验收：描边与发光符合设计文档；FPS 无明显回退；专用服务器不受影响。

### M8 · 配置 / 命令 / UI（2–3 天）
- 双配置文件、版本化与迁移、远程可改的 5 个字段、/pingforit 三个子命令、设置界面。
- **资源重载 API 迁移（不仅消 deprecation note，且是功能性阻断）**：**v0 的注册方式在 26.3 会让资源重载永远完不成（卡在加载条、无异常、无 crash）——M4 阶段已实证**（摘掉注册后 2 秒内完成，修复 `a6cef45` 只是临时摘除，M8 用 v1 恢复）。`FabricClient` 原先用 v0 的 `ResourceManagerHelper.get(PackType)` + `IdentifiableResourceReloadListener`（`src/client/java/nx/pingwheel/fabric/FabricClient.java:9-10,110-111`），二者在 fabric-resource-loader-v0 **3.3.27** 均已 `@Deprecated`（本地 `javap -v` 常量池确认 `Ljava/lang/Deprecated;`）。改用 fabric-resource-loader-**v1 3.1.0**：
  - `ResourceLoader.get(PackType).registerReloadListener(Identifier, PreparableReloadListener)`；
  - 监听器改用 `net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener<T>`（封装新的 `reload(SharedState, Executor, PreparationBarrier, Executor)` 签名，只需实现 `prepare(SharedState)` / `apply(T, SharedState)`）；
  - 需要注册表/特性旗标时用 `ResourceLoader.REGISTRY_LOOKUP_KEY` / `ResourceLoader.FEATURE_FLAG_SET_KEY`；需要顺序控制时用 `ResourceLoader.addListenerOrdering(...)`。
  - 现状备注：`RELOAD_LISTENER_ID` 的贴图/音效预加载在 26.3 管线重写时已被 defer（`FabricClient.java:117-120` 的 TODO），迁移时只需保住注册点与 id。
- 验收：改配置即时生效并持久化；channel 按服务器 IP 分桶；旧配置能迁移；**编译输出不再出现该处 deprecation note**。

### M9 · 可选集成（3–5 天，可裁剪）
顺序：Mod Menu → VoiceChat → FTB Teams → Distant Horizons → Sable。
- 每项先确认 26.3 是否有对应版本；没有就明确跳过并记录。
- 验收：装与不装各跑一次，降级路径正确。

### M10 · 验证与发布（2–3 天）
- verify 任务（单端版）、`runServer` 无 client 类加载、CI、locale 补键、LICENSE / THIRD-PARTY-NOTICES 打包、性能回归。
- 188 个测试类：重建测试基座（需要 26.3 的编译产物），能救多少救多少。
- 验收：CI 绿；专用服务器启动无 NoClassDefFoundError；jar 内容合规。

## 4. 风险登记

| 级别 | 风险 | 影响 | 应对 |
|---|---|---|---|
| P0 | 渲染层 4 项「26.3 无替代」 | M7 可能返工 | MVP 绕开；M7 单独排期；先做技术验证再铺开 |
| P0 | 6/7 mixin 锚点失效 | 注入全灭 | 按 05 报告逐个核对；每个 mixin 单独验证 |
| P0 | 源集拆分遗漏 client 引用 | 专用服务器崩溃 | M2 用 grep 静态门禁 |
| P1 | Lombok 8 文件 | 编译失败 | M3 手写 getter/setter |
| P1 | 188 个测试依赖 NeoForm | 测试全灭 | M10 重建，先保证主代码 |
| P1 | 集成依赖 26.3 版本 | 功能缺失 | M9 逐项确认，可跳过 |
| P2 | 协议无版本号 | 客户端/服务端版本错配时行为未定义 | 保持现状；可在 README 标注 |
| P2 | 包名/产物名与上游不一致 | 维护成本 | D3/D4 已定 |

## 5. verifyModIdentity 改造要点

保留：产物命名、资源命名空间、mixins 文件存在、fabric.mod.json 的 id/version 一致、无旧通道残留。
删除：refmap 校验（official 映射不产 refmap）、三端 dependsOn 校验、Forge/NeoForge 资产校验、Manifest MixinConfigs。
新增：ServiceLoader 四件套文件存在、entrypoint 类存在、LICENSE 打入 jar。

## 6. 验收与回滚

- 每阶段：`gradlew clean build` + 该阶段的冒烟路径（M4 起需 actually 进世界）。
- 每阶段打 tag：`port-M0` … `port-M10`；失败回退到上一个 tag。
- 禁止在未打 tag 的情况下跨阶段并行改动。

## 7. 工作量估算（单人）

| 阶段 | 乐观 | 悲观 |
|---|---|---|
| M0–M1 | 1 天 | 2 天 |
| M2 | 2 天 | 4 天 |
| M3 | 3 天 | 5 天 |
| M4 | 3 天 | 5 天 |
| M5 | 2 天 | 3 天 |
| M6 | 3 天 | 5 天 |
| M7 | 5 天 | 10 天 |
| M8 | 2 天 | 3 天 |
| M9 | 3 天 | 5 天 |
| M10 | 2 天 | 3 天 |
| **合计** | **26 天** | **45 天** |

MVP（M0–M4）大约 **9–16 天**，是「能玩」的第一道门槛。

## 8. 开始前需要用户确认

1. 决策 D1–D9 是否全部认可（尤其 D2 mod id 改 `pingforit`、D3 包名保留 `nx.pingwheel`）。
2. 是否接受 MVP 先做「无描边」版本。
3. 是否需要在 M1 之前先跑一次上游原始工程的构建做对照（需要联网下载 1.21.1 依赖）。
