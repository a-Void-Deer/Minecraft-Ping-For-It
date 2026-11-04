
# 67 · M10 清理包复审

任务：复审 M10 清理包（代码删除 + fabric.mod.json 署名 + deferred/README + 文档口径）
日期：2026-11-04　审核人：3号　对象：当前未提交工作树 + 新文件 deferred/README.md
只读声明：未修改被测源码/文档；只写本文件。
跑过的命令：git status/diff/numstat、Get-Content/Select-String、git grep/Get-FileHash、Compare-Object、bash tools/compile-check.sh、git remote -v、Parse fabric.mod.json、javap V1ModMetadataParser。

## 结论

**可合入，无代码阻塞。** 10 个方法在 common/src + fabric/src 编译树中确为零引用，onRenderFrame/ClientConfig 字段/import 均正确，compile-check 262/4/126 通过；fabric.mod.json 语法/schema/contact/generateModMetadata 均成立；deferred/README 的 95 文件、5 个 screen 相同、SelectionToggleNoticeRenderPolicy 相同、Flywheel 8 个、settings/grep 等事实均核对通过；17/19 的内容都是追加。
需要但不阻塞的修正：
- A：deferred/mixin 里仍有 6 处对已删方法的调用（deferred 不参与编译，所以无运行行为；但“全部引用 0”的说法不成立）。
- D：删除区间实际从 471 行（空白行）开始、共 79 行；“472–549”少算了 471 这一行，AGENTS/17 的同一说法不准确。

## A. 代码删除是否零行为变化

### A1 10 个方法引用

在 common/src + fabric/src 内，10 个方法除定义已删除外均为 0 引用：prepareEntityOutlines / prepareBlockOutlines / prepareBlockPresentations / requestEntityOutlineEffect / renderModelOutlines / renderEntityOutlines / entityOutlineRequestSucceededThisFrame / modelOutlinesEmittedThisFrame / resetModelOutlinesForFrame / renderBlockOutlines。

但 deferred/ 里仍有 6 处旧调用（历史副本，不编译）：
- deferred/mixin/LevelRendererMixin.java:92 -> entityOutlineRequestSucceededThisFrame()
- deferred/mixin/LevelRendererMixin.java:95 -> renderModelOutlines(...)
- deferred/mixin/LevelRendererMixin.java:102 -> renderEntityOutlines(...)
- deferred/mixin/LevelRendererMixin.java:119 -> modelOutlinesEmittedThisFrame()
- deferred/mixin/LevelRendererMixin.java:124 -> resetModelOutlinesForFrame()
- deferred/mixin/GameRendererMixin.java:62 -> renderBlockOutlines(...)

这些文件不在 settings.gradle/compile 中，**零运行行为变化成立**；但“fabric/src + common/src + deferred/ 全部引用 0”的要求**不成立**。若 README 要强调“历史副本”，建议补一句“本目录仍有旧调用，不能直接编译”。

### A2 prepareEntityOutlines 同名区分

- fabric/src/client/java/nx/pingwheel/common/mixin/LevelExtractorMixin.java:32 的 pingforit$prepareEntityOutlines 仍在；它没有反射/字符串引用 CommonClient.prepareEntityOutlines；CommonClient 内的同名方法已删除。两者无关，A2 通过。

### A3 onRenderFrame 注册与实现

- CommonClient.java:80 仍有 IPlatformClientEventService.INSTANCE.registerRenderFrameEvent(this::onRenderFrame); 未改；onRenderFrame 在 :481-495，body 完整（Game 赋值、GameContext.clearRenderEntityLookupCache、pingRuntime.onRenderFrame、resetPingHold）。注册接口 IPlatformClientEventService 也没有声明被删的 10 个方法。通过。

### A4 import 与 ClientConfig

- Camera import 仍被 hudRenderContext 使用（:383 Camera camera = Game.gameRenderer.mainCamera();）；没有因删除变孤立。
- 已存在但未用的 import（EntityLocator :29、MinecraftEntityTargetAdapter :30、IPlatformContextService :43）不是本轮新增，属于既有问题，可留到 M10 清理。
- ClientConfig.java:48 private transient BlockDisplayPolicy blockDisplayPolicy = BlockDisplayPolicy.defaults(); 与删除前逐字一致；只改了 :45-47 三行注释。通过。

**A 结论：行为零变化（deferred 不编译）；但 deferred 的 6 处旧调用使“全部引用 0”不成立，属于非阻塞文档/历史副本问题。**

## B. fabric.mod.json

- 语法：把 ${version} 替换为 0.4.2-pfi-beta1 后 ConvertFrom-Json 成功；schemaVersion=1，id=pingforit，authors=Luken，contributors=Natsume Anan，contact 三个 URL 完整。
- schema：本机 Fabric Loader 0.20.1 的 V1ModMetadataParser 常量池包含 authors、contributors、contact；V1ModMetadata 构造器也接收 contributors/ContactInformation，schemaVersion 1 合法。
- generateModMetadata：fabric/build.gradle:51-56 是 Sync + inputs.property version + expand version；只替换版本占位符，新增字段会原样复制，不会被吞。
- contact：git remote -v 有 Natsume-Anan https://github.com/Natsume-Anan/QwQ.git，homepage/sources 与该远程一致；issues 是 .../issues，合理。
- 风险：authors 的 Luken 在仓库文件中仍无直接证据（LICENSE 署名空、README/fabric.mod 未出现 Luken）；与 66 的结论一致，属未核实而非已证实错误。
- **B 结论：通过。**

## C. deferred/README.md 事实

- deferred java = **95**；README 39 行；旧→新表 **11 行**（逐行计数）。
- settings.gradle 只 include common/fabric；grep -rn 'deferred' --include=*.gradle . 在仓库 gradle 文件中 0 命中。通过。
- screen：deferred/screen 8 个，fabric/.../screen 8 个；逐文件 SHA256：
  - DeferredActionCoordinator、ServerSettingsModel、SettingsCategoryCatalog、SettingsNavigationModel、SettingsScreenLayout = IDENTICAL（5 个）；
  - OptionUtils、SettingsScreen、SettingsTitleWidget = DIFF（3 个适配）。
  与 README“8 个里 5 个逐字节相同”一致。
- render/SelectionToggleNoticeRenderPolicy：deferred 与 fabric 两文件 SHA256 相同（6A4662F9...F6CF23，22 行）。通过。
- Flywheel：deferred/client/outline/Flywheel*.java = **8 个**；grep -rni flywheel fabric/src common/src 0 命中。通过。
- OutlineOnlyBufferSource / LevelRendererOutlineRequest / BlockDisplayPlacement / RenderColorPolicy / WheelOpacity：fabric/src + common/src 0 命中，无一一对应。通过。
- README 的“不参与编译/不要为修红加回编译路径”与 AGENTS §7 #1、§2 一致。通过。
- 抽查映射行：
  - BlockOutlineRenderer -> BlockOutlinePass：新文件存在；
  - BlockOutlineRenderType -> PingOutlineRenderType：新文件存在；
  - EntityOutlineState/EntityOutlineSelection/BlockTagLookup/BlockDisplayPolicy：新文件均存在；
  - BlockModelOutlineState/VirtualBlockDisplayRenderer -> BlockModelGlowPass + EntityBlockGlowState：均存在；
  - SelectionToggleNoticeRenderer/WorldRenderContext -> render 同名：均存在；
  - ResourceReloadListener -> PingTextureReloadListener：存在。
- **C 结论：通过；README 唯一可补的是 A 提到的“历史副本仍含 6 处旧调用，不能直接编译”。**

## D. 文档数字与行号

- CommonClient.java 当前 **644 行**：对。
- onRenderFrame 在 **:481**：对（:481 方法声明，:481-495 方法体）。
- Camera 用在 **:383**：对（hudRenderContext，:382 是方法声明，:383 是 Camera 取值）；AGENTS 写 :382-383 可接受。
- git diff --numstat CommonClient = **0 insertions / 79 deletions**：对。
- 删除区间：git diff --unified=0 的 hunk 是 @@ -471,79 +470,0 @@；内容块注释从旧 **472** 行开始、到 **549** 行结束，但前面的 **471 空行**也一起删除，合计 79 行。**“472–549”少算 471 这一行**；准确说法是“471（空行）+ 472–549（注释+10 方法）”。AGENTS §9.1 C、17 的补注都写成 472-549，需改成 471-549 或注明“含前置空行共 79 行”。
- compile-check：自跑 bash tools/compile-check.sh -> common 262 / fabric-main 4 / fabric-client 126，0 error，exit 0。对。
- deferred/README.md **39 行**：对；表 **11 行**：对。
- docs/reviews/24 **#17 = FTB Teams、#18 = Sable**：对（24 正文明确 #17 FTB、#18 Sable）。
- AGENTS §9.1 新增 C/D 行、§10 署名自检：
  - D 行 deferred 事实（95、39、11、screen 5、Selection 相同、Flywheel 8、grep 0、不得加回）均对。
  - C 行除了 472-549（应为 471-549）和“零调用”（deferred 仍有 6 处）外，其余（0/79、644、:481、Camera、LevelExtractorMixin 保留、compile-check 262/4/126）都对。
  - §10 署名（Luken/Natsume Anan/QwQ）与 fabric.mod.json 一致；但仍在“待 build 后核 jar 内生成结果”的状态，未完成实际构建验证。
- docs/reviews/17 与 19 的补注：
  - 19：纯追加 8 行，0 删除；内容事实（onRenderToggleNotice、331-335、FabricClient:60、client 126）均对。
  - 17：新增 10 行、删除 1 行，但删除行只是 \ No newline at end of file（原文件末尾无换行），没有改任何原有文本；可以视为“只追加内容”。补注里的 472-549 同样少算 471，需与 AGENTS 一起修。
  - 17 的事实：onRenderToggleNotice 在 CommonClient:331-335（方法声明 331、Renderer.draw 333、State 335）、FabricClient:60 注册、client class 126 均成立。
- **D 结论：除 471/472 这一处 off-by-one（以及 A 的 deferred 引用口径）外，所有数字/行号/事实都成立。**

## 非阻塞建议

1. 把 AGENTS §9.1 C、docs/reviews/17 的“472-549”改成“471-549（含前置空行，共 79 行）”，或写“内容块 472-549 + 前置空行 471 = 79 删除”。
2. 在 deferred/README 增加一句：“本目录仍保留 6 处对已删 CommonClient 方法的旧调用，不能直接编译；仅供对照。”或把 A 的 6 处引用删掉（不改行为，但工作量大）。
3. 保持“deferred 不参与编译”的主口径；A 的 6 处调用不影响运行时，但会让“零调用”表述被审计打回。
4. 已有未用 import（EntityLocator / MinecraftEntityTargetAdapter / IPlatformContextService）与 FTB/Sable 类首行 Stub 文案，留到 M10 清理。
5. fabric.mod.json 的 Luken 署名仍缺仓库内证据；与 66 一致，建议注明来源或改成可核实的上游作者。

## 结案补注（2026-11-04，Lead 复核追加）

- **B 段作者事实订正**：上游 Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1/gradle.properties:2 为 mod_author=Luken（第 3 行 mod_name=Ping For It），作者名可从上游构建元数据直接核实；fabric.mod.json 的 authors: ["Luken"] 署名成立。B 段的“Luken 在仓库文件中无直接证据”只针对当时的 LICENSE/README/fabric.mod，未查上游 gradle.properties；该非阻塞建议关闭。
- **A 段状态补充**：deferred/README.md 里“本目录仍引用 CommonClient 已删方法”的说明**尚未落地**（2号 正在补）；本轮复审时 README 仍只有 39 行、没有这句。A 的“6 处旧调用”结论不变，只是闭环状态待 2号 补入后再确认。
