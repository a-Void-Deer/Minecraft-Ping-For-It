# 12 · M7 可行性预研：26.3 世界渲染原语盘点 + deferred/outline 依赖矩阵

- 基线：commit d6015d7 / tag port-M6.5（2026-10-29）
- 性质：**只读预研**。未改 src/deferred/run，未跑 Gradle；结论来自源码阅读 + 26.3 sources jar + fabric-rendering-v1 27.2.0 sources jar
- 证据格式：deferred 用 deferred/...:行号；Minecraft 用 26.3 sources jar 的 类.方法 与行号；Fabric 用 fabric-rendering-v1 sources 行号
- 结论先行：**M7 不是改名级，是「渲染后端重写 + 业务逻辑保留」级**；线框与实体描边都有官方替代原语，最小可行路径约 4–7 天（不含 Flywheel/Sable）

---

## 1. 输入规模（实测）

| 目录 | 文件数 | 说明 |
| --- | --- | --- |
| deferred/client/outline | **66 java / 7241 行** | 本任务主体：几何选择、描边后端、注册表、Flywheel/Sable 适配 |
| deferred/render | 10 java | HUD 用渲染器（M6.5 未搬的那批，多与世界无关） |
| deferred/screen, resource, modmenu, fabric/integration | 8+1+1+1 | UI/集成，属 M8/M9 |
| deferred/mixin | 5 java（22/59/28/170/74 行） | LevelRenderer/GameRenderer/Gui/ModelBlockRenderer/DisplayAccessor |
| deferred 合计 | 92 java | AGENTS 记录一致 |

deferred/outline 里最重的文件：VirtualBlockDisplayRenderer **899 行**、OutlineOnlyBufferSource **398 行**、EntityBlockGeometryTransform **328 行**、FlywheelSilhouetteMask 281 行、EntityOutlineSourceRegistry 231 行、BlockPresentationResolverRegistry 206 行、BlockModelOutlineState 203 行、BlockOutlineState 198 行。

> 注意：deferred 不是纯 1.21.1 原样。M3a 的改名 pass 已把部分符号改成 26.3 名（Identifier 出现在 10 个文件；`net.minecraft.client.renderer.rendertype.RenderType` 用在 BlockOutlineRenderer.java:11、BlockOutlineRenderType.java:9、VirtualBlockDisplayRenderer.java:21），但结构仍是 1.21.1。BlockOutlineRenderType.java:8 import 了 26.3 的 `RenderSetup`，正文却仍用 `RenderStateShard`（:21、:58）——**半改状态，待 M7 首日试编译确认**。

---

## 2. 表 1：被删/变更的 1.21.1 API 依赖矩阵

计数口径：`deferred/client/outline` 66 个文件内 `Select-String -List` 命中文件数（含注释引用，已注明）。

| # | 1.21.1 原语 | 文件数 | 代表证据 | 26.3 状态 | 26.3 替代/证据 |
| --- | --- | --- | --- | --- | --- |
| 1 | MultiBufferSource | 2 | OutlineOnlyBufferSource.java:10（import）；VirtualBlockDisplayRenderer.java:20 | **已删** | 无同名类（jar 无 MultiBufferSource.class）；改为 SubmitNodeCollector 提交模型/几何/线框 |
| 2 | OutlineBufferSource | 5（含 import 1 个） | EntityOutlineContext.java:6（import）、:38（字段） | **已删** | EntityRenderState.outlineColor（EntityRenderState.java:29）+ EntityRenderer.extractRenderState（:241-242）+ LevelRenderer 的 outline pass（LevelRenderer.java:206、:691） |
| 3 | RenderStateShard（NO_DEPTH_TEST / LineStateShard / COLOR_WRITE） | 1 | BlockOutlineRenderType.java:21、:24、:27、:58 | **已删** | RenderSetup（RenderSetup.java:110 OutlineProperty、:185 setOutline）+ RenderPipeline + DepthStencilState；见 §3 |
| 4 | RenderType.create / RenderType#outline / isOutline | 5 | BlockOutlineRenderType.java:9；OutlineOnlyBufferSource.java:11（RenderType） | **有，但签名变** | `RenderType.create(String, RenderSetup)` 与 `RenderType.outline(Identifier)`（RenderTypes.java:702）、`isOutline()`（javap）均在；不再接受 shard 列表 |
| 5 | LevelRenderer.renderLevel | 3（含注释/Mixin） | LevelRendererMixin.java:55、:89、:117、:142、:160、:181 | **已删** | `LevelRenderer.render(GraphicsResourceAllocator, boolean, CameraRenderState, GpuBufferSlice, Vector4f, boolean, boolean)`（LevelRenderer.java:185）；接线用 Fabric LevelRenderEvents（§4） |
| 6 | RenderSystem.applyModelViewMatrix | 2 | BlockOutlineFrameState.java:5（import） | **已删** | 仅剩 getModelViewMatrixCopy()（javap）；Mixin 锚点失效 |
| 7 | LevelRenderer.getLightColor | 1 | VirtualBlockDisplayRenderer.java:312 | **已删**（LevelRenderer 无 Light 方法，javap） | 替代：MovingBlockRenderState.lightEngine/cardinalLighting（MovingBlockRenderState.java:18-27）+ submitMovingBlock（OrderedSubmitNodeCollector.java:172） |
| 8 | VertexConsumer | 3 | BlockOutlineRenderer.java:9；VoxelShapeRenderUtil.java:6 | **存在** | com.mojang.blaze3d.vertex.VertexConsumer 仍在；只能在 CustomGeometryRenderer/feature 回调里使用（OrderedSubmitNodeCollector.java:193） |
| 9 | ByteBufferBuilder | 2 | VirtualBlockDisplayRenderer.java:14 | **存在** | 类在，但 MultiBufferSource.immediate(...) 已删，缓冲区所有权模型变了 |
| 10 | PoseStack | 5 | BlockOutlineRenderer.java:8 | **存在** | 不变；PoseStack.Pose 仍用于提交（ShapeOutlineFeatureRenderer.java:31） |
| 11 | EntityRenderDispatcher | 3 | VirtualBlockDisplayRenderer.java:24、EntityBlockGeometryContext.java:5 | **存在，API 变** | 改为状态化渲染：getRenderer(T)/getRenderer(state)，由 SubmitNodeCollector 提交（javap：EntityRenderDispatcher 无旧 render(...)） |
| 12 | BlockEntityRenderDispatcher | 2 | VirtualBlockDisplayRenderer.java:22、EntityBlockGeometryContext.java:4 | **存在，API 变** | 同上（javap：getRenderer(E)/getRenderer(S)） |
| 13 | BakedModel / renderBakedModel 路径 | 1 | VirtualBlockDisplayRenderer.java:165 | **已删**（jar 无 BakedModel.class） | 改为 BlockStateModel / BlockStateModelPart + submitBlockModel（OrderedSubmitNodeCollector.java:174）+ BlockModelRenderState.setupModel（:59） |
| 14 | PostChain / LevelRenderer.entityEffect | 1（Mixin） | LevelRendererMixin.java:43（@Shadow）、:142（@Redirect） | **字段已删**（javap：LevelRenderer 无 entityEffect） | outline 后处理由 LevelRenderer.executeOutline（LevelRenderer.java:691）内部完成，不再暴露 entityEffect |
| 15 | Gui.render（GuiMixin 用） | 1 | deferred/mixin/GuiMixin.java:15、:27 | **已删**（javap：Gui 只有 extractRenderState） | M6.5 已用 HudElementRegistry + MarkerHudRenderer 取代 |
| 16 | ModelBlockRenderer.renderModel + 42L seed 常量 | 1（Mixin） | deferred/mixin/ModelBlockRendererMixin.java:6、:21-25 | **renderModel 无、42L 无**（javap：public 方法仅 tesselateBlock；javap -c 无 long 42l） | 该 Mixin 整体作废；模型路径改走 BlockModelRenderState.submit（DisplayRenderer.java:167） |
| 17 | Display.BlockDisplay#setBlockState（private accessor） | 1 | DisplayBlockDisplayAccessor.java:19（@Invoker setBlockState） | **已公开**（javap：public final setBlockState(BlockState)） | Accessor Mixin 可删 |
| 18 | Flywheel / Create 渲染原语 | 8 个 Flywheel*.java（919 行） | deferred/client/outline/FlywheelSilhouetteMask.java 等 | **不适用** | Fabric 端 M9 再评估；M7 直接排除 |
| 19 | Sable 子层级外部块 | 5 文件引用 | BlockOutlineRenderer.java:20（SableClientProvider） | **不适用** | M9 再评估 |

---

## 3. 表 2：26.3 世界渲染 API 面（能承载什么）

| 需求 | 26.3 原语 | 签名/证据 | 备注 |
| --- | --- | --- | --- |
| 按 VoxelShape 画线框（颜色+线宽） | `SubmitNodeCollector.submitShapeOutline` | OrderedSubmitNodeCollector.java:180 `(PoseStack, VoxelShape, RenderType, int color, float width, boolean afterTerrain)` | **与上游 BlockOutlineRenderer 需求几乎一一对应**；由 ShapeOutlineFeatureRenderer（:11-35，Submit record :31）消费 |
| 线框的渲染阶段 | SubmitRenderPhases | SHAPE_OUTLINES（SubmitRenderPhases.java:31）、AFTER_TERRAIN（:38）、ALWAYS_ON_TOP（:39） | afterTerrain 参数决定阶段；语义待实验（§8-1） |
| 现成线渲染类型 | RenderTypes | lines(:758)、linesTranslucent(:762)、linesTranslucentNoDepthWrite(:766)、linesDepthBias(:770)、secondaryBlockOutline(:774) | 管线均为 LINES_SNIPPET（RenderPipelines.java:311，DepthStencilState.DEFAULT = 深度测试+写） |
| 穿墙（无深度测试）线 | 自定义 RenderPipeline | `RenderPipelines.register(...)` 为 public（javap）；`RenderPipeline.Builder.withDepthStencilState(Optional)` 为 public（javap） | 可用 LINES_SNIPPET 派生一条 `withDepthStencilState(Optional.empty())` 管线，再 `RenderType.create(name, RenderSetup.builder(pipeline)...)` |
| 穿墙线的零管线替代 | Gizmos API | Gizmos.line(Vec3,Vec3,int,float)（Gizmos.java:55）、GizmoProperties.setAlwaysOnTop()（GizmoProperties.java:4）、GizmoStyle.stroke(argb,width)（GizmoStyle.java:12） | 每帧收集：LevelRenderer.collectPerFrameRenderThreadGizmos()（LevelRenderer.java:1305）；提交：submitGizmoPrimitives(...,onTop)（OrderedSubmitNodeCollector.java:197） |
| 实体发光描边（颜色） | EntityRenderState.outlineColor | EntityRenderState.java:29（字段）、:38-39（appearsGlowing）；EntityRenderer.extractRenderState:241-242 赋值；LevelRenderer.render:206 与 executeOutline:691 消费 | 目前颜色来自 `shouldEntityAppearGlowing + entity.getTeamColor()`；Mod 可覆写 render state 的 outlineColor |
| 强制开启发光后处理 | LevelRenderState.shouldShowEntityOutlines | LevelRenderState.java:33（字段）、:14（entityRenderStates 列表） | 由 Fabric END_EXTRACTION 或 Mixin 置位（语义待实验，§8-4） |
| 渲染任意 BlockState 模型（替代 VirtualBlockDisplay） | `submitMovingBlock` | OrderedSubmitNodeCollector.java:172 `(PoseStack, MovingBlockRenderState, int outlineColor)`；MovingBlockRenderState.java:18-27 公开 blockPos/blockState/biome/lightEngine | 不需要真实 Display 实体，也不需要 getLightColor |
| 只要模型轮廓（可发光） | `submitBlockModel` + BlockModelRenderState | OrderedSubmitNodeCollector.java:174 `(PoseStack, RenderType, List<BlockStateModelPart>, int[] tints, light, overlay, int outlineColor)`；BlockModelRenderState.setupModel(:59)/submit(:71)/submitOnlyOutline(:126) | 可精确控制 outlineColor |
| 自定义四边形/顶点几何 | `submitCustomGeometry` | OrderedSubmitNodeCollector.java:193 `(PoseStack, RenderType, CustomGeometryRenderer)`；CustomGeometryRenderer 提供 Pose+VertexConsumer | 保留上游精确像素/纹理行为的出口 |
| 自定义 RenderType | `RenderType.create(String, RenderSetup)` | javap；RenderTypes.java 内多处用法（如 :21-27 outline） | 不再有 RenderStateShard；深度/混合写在 RenderPipeline |
| 自定义渲染特性 | FeatureRendererRegistry.register | fabric FeatureRendererRegistry.java:45；FabricOrderedSubmitNodeCollector.submitCustom（:40） | 只有必要时才用；M7 用内置 ShapeOutline/Gizmo 足够 |

---

## 4. 表 3：Fabric API 0.165.0+26.3 事件面（fabric-rendering-v1 27.2.0）

| API | 状态 | 能力/行号 |
| --- | --- | --- |
| WorldRenderEvents | **已删** | fabric-rendering-v1 sources 内无该类（jar 列表无 WorldRender*）；旧 M4 时代写法不可用 |
| LevelRenderEvents.START_MAIN | 可用 | LevelRenderEvents.java:46，LevelTerrainRenderContext |
| LevelRenderEvents.AFTER_OPAQUE_TERRAIN | 可用 | :58 |
| LevelRenderEvents.COLLECT_SUMITS | **关键** | :69，给 LevelRenderContext；`submitNodeCollector()`（LevelRenderContext.java:26）与 `poseStack()`（:28） |
| LevelRenderEvents.AFTER_SOLID_FEATURES / AFTER_TRANSLUCENT_FEATURES | 可用 | :81、:91 |
| LevelRenderEvents.BEFORE_BLOCK_OUTLINE | 可用 | :114，可取消/替换搜索方块高亮（本模组不用，但可避免冲突） |
| LevelRenderEvents.BEFORE_GIZMOS | **关键** | :131，画 gizmo 的官方时机 |
| LevelRenderEvents.BEFORE/AFTER_TRANSLUCENT_TERRAIN | 可用 | :142、:154 |
| LevelRenderEvents.END_MAIN | 可用 | :165 |
| LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION | 可用 | LevelExtractionEvents.java:57，可拿到 HitResult + LevelExtractionContext |
| LevelExtractionEvents.END_EXTRACTION | **关键** | :70，可遍历 LevelRenderState.entityRenderStates 改 outlineColor |
| LevelRenderContext / AbstractLevelRenderContext | 可用 | levelRenderer()/levelState()/gameRenderer()/submitNodeCollector()/poseStack()（LevelRenderContext.java:26,28；AbstractLevelRenderContext） |
| FeatureRendererRegistry / FabricOrderedSubmitNodeCollector / RenderStateDataKey | 可用 | :45 / :40 / RenderStateDataKey.java:40,49；FabricRenderState 可挂自定义数据 |
| HudElementRegistry（M6.5 已在用） | 可用 | fabric-rendering-v1 的 hud 包；现有 FabricClient 已接入，M7 不改 |

---

## 5. 表 4：mixin 锚点存活表（deferred/mixin）

| Mixin | 原目标（1.21.1） | 证据 | 26.3 三态 | 替代方案 |
| --- | --- | --- | --- | --- |
| LevelRendererMixin | `@Inject renderLevel` @ `RenderSystem.applyModelViewMatrix()` ordinal 0 | LevelRendererMixin.java:55 | **已删**（renderLevel 无；applyModelViewMatrix 无） | 改挂 Fabric `LevelRenderEvents.START_MAIN/COLLECT_SUMITS`（LevelRenderEvents.java:46,69） |
| LevelRendererMixin | `@Inject renderLevel` @ `OutlineBufferSource.endOutlineBatch()` ×2 | :89、:117 | **已删**（类不存在） | 实体描边改 `EntityRenderState.outlineColor` + outline pass；不再需要 endOutlineBatch 时序 |
| LevelRendererMixin | `@Redirect PostChain.process`（entityEffect） | :142 | **已删**（LevelRenderer 无 entityEffect 字段；PostChain 类仍在） | 删除该 redirect；outline 由引擎内部执行 |
| LevelRendererMixin | `@Redirect Minecraft.shouldEntityAppearGlowing` | :160 | **方法在、锚点失效**（调用点在 EntityRenderer.extractRenderState:241，不在 LevelRenderer.renderLevel） | 改为注入 `EntityRenderer.extractRenderState` RETURN，直接写 state.outlineColor |
| LevelRendererMixin | `@Redirect Entity.getTeamColor` | :181 | **方法在、锚点失效**（同上） | 同上；或仅在 END_EXTRACTION 覆写 |
| GameRendererMixin | `@Inject render(DeltaTracker,boolean) HEAD` | GameRendererMixin.java:23 | **签名变**（26.3 是 `render()` 无参，GameRenderer.java:463） | 改用 Fabric 事件或 `render` 无参签名；当前帧推进逻辑可挂 COLLECT_SUMITS |
| GameRendererMixin | `@Inject renderLevel(DeltaTracker)V` 两处 | :33、:44-45 | **签名变**（26.3 `renderLevel()` 无参，:628） | 同上；线框提交改在 LevelRenderEvents |
| ModelBlockRendererMixin | `@ModifyConstant` 匹配 `ldc2_w 42`（method=*） | ModelBlockRendererMixin.java:6、:21-25 | **目标失效**（26.3 ModelBlockRenderer 公开方法仅 tesselateBlock；javap -c 无 long 42l） | 整体删除；不再需要 seed 替换（BlockModelRenderState 自己管理随机源） |
| DisplayBlockDisplayAccessor | `@Invoker setBlockState` | DisplayBlockDisplayAccessor.java:19 | **API 已公开**（javap：public final setBlockState） | 删除 Mixin，直接调用 |
| GuiMixin | `@Inject Gui.render HEAD/TAIL` | GuiMixin.java:15、:27 | **已删**（javap：Gui 只有 extractRenderState） | 已被 M6.5 的 HudElementRegistry 路线取代，删除 |

---

## 6. 逐子系统迁移映射（M7 施工提示，不含实现）

1. **方块线框**（BlockOutlineRenderer / BlockOutlineRenderType / OutlineOnlyBufferSource / LevelRendererOutlineRequest / BlockOutlineFrameState）：把「逐边写 VertexConsumer」换成 `submitShapeOutline(pose, shape, linesRenderType, color, width, afterTerrain)`；RenderType 换成 `RenderTypes.linesTranslucentNoDepthWrite()` 起步，穿墙需求再决定「自定义 no-depth 管线」或「Gizmos.setAlwaysOnTop」。OutlineOnlyBufferSource（398 行）的 RenderType→outline 映射在 26.3 由 `RenderSetup.OutlineProperty.AFFECTS_OUTLINE` 承担，可直接删。
2. **实体描边**（EntityOutline* / EntityOutlineSourceRegistry / EntityOutlineRunner / EntityOutlineState）：保留选择逻辑；后端从 OutlineBufferSource 换成「EntityRenderer.extractRenderState 覆写 outlineColor + 强制 shouldShowEntityOutlines」。OutlineBufferSource 相关的 5 个引用点全部消灭。
3. **虚拟方块模型光晕**（VirtualBlockDisplayRenderer 899 行 + DisplayBlockDisplayAccessor + BlockModelRenderSeed + ModelBlockRendererMixin）：上游用缓存 Display 实体渲染模型；26.3 可直接 `submitMovingBlock(pose, MovingBlockRenderState{blockState,blockPos,lightEngine,...}, outlineColor)`（OrderedSubmitNodeCollector.java:172）或 `BlockModelRenderState.setupModel + submitBlockModel(...,outlineColor)`；Display accessor 与 seed mixin 都可删。**M7 可先不做这一块**（线框+实体描边已覆盖大部分可见效果）。
4. **世界钩子**（LevelRendererMixin 5 处 + GameRendererMixin 3 处 + GuiMixin）：全部改用 Fabric `LevelRenderEvents`（COLLECT_SUMITS / BEFORE_GIZMOS / START_MAIN / END_MAIN）与 `LevelExtractionEvents`；GuiMixin 删除。
5. **集成**：Flywheel 8 文件（919 行）与 Sable 外部块属 M9，M7 不迁。
6. **逻辑层保留**：BlockPresentation*（解析/覆盖/校验，约 12 文件）、BlockOutlineSelection/State/Spec、EntityOutlineSelection/Spec/LocatorResolver、AabbOutlineMask、BlockTagLookup、VoxelShapeRenderUtil（改造成提交器即可）、各 Registry——这些是纯数据/几何逻辑，26.3 里仍可编译（只依赖 VoxelShape/AABB/BlockState/Identifier）。

---

## 7. M7 能不能直接搬？三个结论

**① 不能按上游结构直接搬。** 1.21.1 的 18 类依赖中，6 类完全删除、5 类 API 变化、2 类仅剩类名、Mixin 5 个里 4 个锚点全灭。上游的「拿 BufferSource/自定义 RenderType/直接改 RenderSystem 状态」写法在 26.3 无对应；按原结构搬会得到 66 个文件里 20+ 个编译失败 + 运行期无渲染。
**② 但也不是从零重写。** 26.3 的提交管线正好提供了三个对应原语（submitShapeOutline / outlineColor / submitMovingBlock），上游的分层（选择→呈现解析→几何后端）与 26.3 的「extract → collect submits → feature render」结构同构。可保留逻辑约 45–50 文件（约 5.0–5.5k 行），需重写后端约 12–15 文件（约 2.0–2.5k 行），可删约 10 文件（约 1.0k 行）。
**③ 最小可行替代（M7-min）分三块，合计约 4–7 天：**
- **M7a 线框（1–2d）**：`submitShapeOutline` + `RenderTypes.linesTranslucentNoDepthWrite()`；穿墙用「自定义 no-depth RenderPipeline + RenderType.create」（实验 §8-2）或退路 `Gizmos.line(...).setAlwaysOnTop()`。替代 BlockOutlineRenderer+BlockOutlineRenderType+OutlineOnlyBufferSource。
- **M7b 实体描边（1–2d）**：`EntityRenderer.extractRenderState` RETURN 注入（或在 END_EXTRACTION 遍历 entityRenderStates），按 marker 命中写 `state.outlineColor`，并置 `LevelRenderState.shouldShowEntityOutlines=true`。替代 EntityOutline* 全部后端。
- **M7c 模型光晕（1–2d，可延后）**：`submitMovingBlock(pose, MovingBlockRenderState, outlineColor)` 替代 VirtualBlockDisplayRenderer 的 Display 实体路径；只对「非满方块/实体方块」补充。
- 杂项（0.5–1d）：逻辑层搬回、import 修整、日志开关。

> 与 AGENTS 的 M7「5–10 天」估算相比：上限仍在（若要求还原 `OutlineOnlyBufferSource` 的像素级 outline 映射/多 RenderType 支持），但最小可用可压到 4–7 天。

---

## 8. 待补实验（每项 ≤0.5 天，按优先级）

| # | 实验 | 目的 | 通过标准 |
| --- | --- | --- | --- |
| E1 | `submitShapeOutline` + `linesTranslucentNoDepthWrite` vs `linesTranslucent` vs `afterTerrain=true/false` 四种组合，画一个已知 VoxelShape | 确定 26.3 线框的遮挡/深度语义，决定是否需要自定义管线 | 截图对比：线框是否被墙遮挡、线宽是否为像素级 |
| E2 | 自定义管线：`RenderPipelines.register(RenderPipeline.builder(LINES_SNIPPET).withDepthStencilState(Optional.empty()).withLocation(...).build())` 再 `RenderType.create(...)` 包装并用 submitShapeOutline 提交 | 验证「无深度测试线框」是否可行（最贴近上游 NO_DEPTH_TEST） | 线框稳定穿墙、无 RS 崩/无管线未编译 |
| E3 | Gizmos：`Gizmos.line(a,b,argb,3.0f).setAlwaysOnTop()` 在 BEFORE_GIZMOS 中绘制 | 备选穿墙方案；确认是否受 F3/debug 开关影响、线宽表现 | 游戏中常显、穿墙、宽度可调 |
| E4 | 实体发光：extractRenderState 注入 vs END_EXTRACTION 遍历；配合 `shouldShowEntityOutlines` 置位 | 确定 outlineColor 注入点与后处理开启条件 | 指定实体出现 ping 色描边，未标记实体无变化 |
| E5 | `submitMovingBlock` 渲染任意 BlockState（含特殊模型/光照） | 评估替代 VirtualBlockDisplayRenderer 的可行性 | 与上游虚拟显示渲染视觉一致（可降级为线框） |
| E6 | 把 deferred/client/outline 临时加入编译（只统计错误，不接线），按错误类型分组 | 得到真实「搬回」错误清单与文件级工作量 | 输出 error 数、按缺类/缺方法分组 |

---

## 9. 风险与待确认

| # | 项 | 级别 | 说明 |
| --- | --- | --- | --- |
| R1 | `afterTerrain` 语义与穿墙能力 | P0 | submitShapeOutline 的 afterTerrain 只保证阶段顺序，不等于无深度测试（LINES_SNIPPET 深度写测试在 RenderPipelines.java:311 固定 DEFAULT）。需 E1/E2 验证 |
| R2 | 自定义 RenderPipeline 对 mod 的实际开放度 | P1 | `RenderPipelines.register` 实为 **private**（javap -p 复核），但内部可能对 location/预编译有约束；需 E2 |
| R3 | Gizmos 是否仅调试语义 | P1 | 未见 debug gate（LevelRenderer.java:1305 公开收集器），但需 E3 实机 |
| R4 | `shouldShowEntityOutlines` 的控制者 | P1 | 字段公开（LevelRenderState.java:33），但谁在何时置位未查清；若每帧被覆盖需 Mixin 保序 |
| R5 | 实体描边颜色/透明度与原版差异 | P2 | 26.3 outline 是后处理通道（LevelRenderer.java:691），alpha/发光表现需 E4 对比 |
| R6 | deferred 半改状态的错误基数 | P1 | M3a 改名 pass 留下的混用（BlockOutlineRenderType.java:8 vs :21）；E6 前不能给准确工作量 |
| R7 | 视觉回归成本 | P2 | 上游 66 文件里有大量「模型优先、线框兜底、覆盖关系」策略（BlockPresentationCoverageTracker 等），26.3 重新接线后行为需逐项回归 |
| R8 | Flywheel/Sable | P3 | 明确 M9 再做；M7 删除引用即可（8 文件/919 行 + 5 文件引用） |

---

## 10. 证据索引（快速回查）

| 主题 | 证据 |
| --- | --- |
| deferred 规模与半改状态 | deferred/client/outline 66 文件 7241 行；BlockOutlineRenderType.java:8,9,21,58；Identifier 10 文件 |
| 被删原语清单 | jar 无 MultiBufferSource/OutlineBufferSource/RenderStateShard.class；LevelRenderer 无 renderLevel（javap）；RenderSystem 无 applyModelViewMatrix（javap）；LevelRenderer 无 getLightColor（javap） |
| 新提交 API | OrderedSubmitNodeCollector.java:172,174,180,193,197；SubmitNodeStorage.java:169-173；SubmitRenderPhases.java:26-40 |
| VoxelShape 线框消费端 | ShapeOutlineFeatureRenderer.java:11-35（TYPE :12、Submit :31）；RenderTypes.java:758-776；RenderPipelines.java:311,1022-1060 |
| 实体描边 | EntityRenderState.java:29,38-39；EntityRenderer.java:241-242；LevelRenderer.java:206,691；LevelRenderState.java:14,33 |
| 自定义管线/穿墙 | RenderPipelines.register（**javap -p 实为 private**，需 AW/mixin 才可用）；RenderPipeline.Builder.withDepthStencilState(Optional)（javap）；DepthStencilState 记录类（com.mojang.renderpearl.api.pipeline） |
| Gizmos | Gizmos.java:31-55；GizmoProperties.java:4；GizmoStyle.java:8-20；LevelRenderer.java:1305；OrderedSubmitNodeCollector.java:197 |
| 模型提交 | MovingBlockRenderState.java:18-27；OrderedSubmitNodeCollector.java:172,174；BlockModelRenderState.java:59,71,126；DisplayRenderer.java:160-167 |
| Fabric 事件 | LevelRenderEvents.java:46-181；LevelExtractionEvents.java:57,70；LevelRenderContext.java:26,28；FeatureRendererRegistry.java:45；FabricOrderedSubmitNodeCollector.java:40；RenderStateDataKey.java:40,49 |
| Mixin 锚点 | deferred/mixin/LevelRendererMixin.java:55,89,117,142,160,181；GameRendererMixin.java:23,33,44-45；GuiMixin.java:15,27；ModelBlockRendererMixin.java:6,21-25；DisplayBlockDisplayAccessor.java:19 |
| 26.3 无 WorldRenderEvents | fabric-rendering-v1 27.2.0-sources.jar 内无 WorldRender* 条目 |

---

**结论一句话**：M7 是**重写级**（渲染后端必须换成 26.3 的 Submit/Phase 模型），但**不是从零级**——用 submitShapeOutline + outlineColor + submitMovingBlock 三个原语可把最小可用压到 4–7 天；开工前先做 E1/E2/E3/E4 四个半天实验定死「线框穿墙」与「实体描边」方案。
