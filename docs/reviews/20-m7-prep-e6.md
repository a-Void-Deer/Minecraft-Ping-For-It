# 20 · M7-prep E6：deferred/outline 试编译普查 + 26.3 渲染 API 签名核实

- 任务：task-17（4号）｜日期：2026-10-30｜基线 tag `port-M6.5-restored`（`b7e7a39`）
- 性质：**只统计，不接线**。未改 `src/**`、`deferred/**`；临时树在 `build/e6/`（gitignore 已覆盖）
- 输入：`deferred/client/outline`（**66 个 java**，任务口径 62，见第 1 节注）+ `deferred/mixin` 4 个渲染 mixin
- 方法：把 outline 映射到 `nx/pingwheel/common/client/outline`、mixin 映射到 `nx/pingwheel/common/mixin`，用 `tools/.compile-check/cp-client.txt` 的 classpath（追加 `tools/.compile-check/client` 以解析现有客户端类）跑 javac
- 命令：`javac --release 25 -proc:none -nowarn -encoding UTF-8 -Xmaxerrs 20000 @cp @sources -d build/e6/out`
- 结果：**70 个源文件 → 132 errors / 0 class**（javac 有错不产 class）；错误仅落在 **14 个文件**，另 **56 个文件 0 error**

---

## 0. 结论先行

1. **E6 可行且结论明确**：70 个文件里 **56 个已能按现状编译**，错误集中在 14 个文件；其中 3 个文件占 **82/132**（BlockOutlineRenderType 33、OutlineOnlyBufferSource 25、VirtualBlockDisplayRenderer 24）。
2. **错误类型分布**：类型不存在（旧名/已删，含其静态成员用法）**84**、缺方法 **24**、缺字段/常量 **13**、签名/类型不匹配 **11**。
3. **大多可机械改名**：ResourceLocation→Identifier（27 处）、RenderType 换包（19）、VertexFormat/VertexFormatElement 换包（7）、LightTexture→Lightmap（4）、Camera 访问器（4）、ResourceKey.location→identifier（2）——这些占了错误的大头，M7 第一遍「改名 pass」就能消掉约 60%。
4. **真正要重写的是 5 个文件 + 2 个 mixin**：BlockOutlineRenderType（RenderStateShard/RenderType 构造，33 错）、OutlineOnlyBufferSource（MultiBufferSource/新 VertexConsumer 抽象方法，25）、VirtualBlockDisplayRenderer（Camera/Dispatcher/getLightColor/hitbox，24）、BlockDisplayWhitelistEntry+Whitelist+Policy+TagLookup（13+4+3+2，Identifier 级联）、LevelRendererMixin（13）+GameRendererMixin（2）。
5. **API 面已核实**（第 4 节）：submitShapeOutline / submitBlockModel / submitMovingBlock / submitCustomGeometry、SubmitRenderPhases（**Fabric 包**，不是 MC 包）、RenderTypes.lines*、RenderPipelines.register（源码 private，但 Fabric classtweaker 已 transitive-accessible）、withDepthStencilState(Optional)、Gizmos.line/setAlwaysOnTop、EntityRenderState.outlineColor、LevelRenderState.shouldShowEntityOutlines 均存在，签名可用。
6. 判定：M7 仍是**重写级**（5 个渲染后端文件 + 钩子），但**集中、可控**；与 12 号报告的 4–7 天一致。

---

## 1. E6 试编译结果

| 项 | 值 |
| --- | --- |
| 源码 | 66 outline + 4 mixin = **70** |
| classpath | `tools/.compile-check/client + main` + MC common/client/client-only + 153 个依赖 jar（同 compile-check） |
| javac | JDK 25 (25.0.4.1)，`--release 25` |
| 结果 | **132 errors / 0 class**（有错不产 class；不代表文件全废，第 1.2 节的 56 个文件本身是干净的） |
| 输出 | `build/e6/javac.log`、`build/e6/errors.json`、`build/e6/sources.txt`、`build/e6/cp.txt` |

> 注：任务写 `deferred/client/outline（62 java）`，实际目录当前是 **66** 个；E6 全量编译（多出的 4 个不影响结论，错误仅落在 14 个文件，其中无额外文件引入新错误类）。

### 1.1 按错误类型分组

| # | 错误类型 | 数量 | 代表（文件:行） | 26.3 处理 |
| --- | --- | --- | --- | --- |
| 1 | 类型不存在：旧名（ResourceLocation） | 27（class 20 + 静态成员 7） | `BlockDisplayPolicy.java:6`、`BlockDisplayWhitelistEntry.java:128` | **改名** `Identifier`（`net.minecraft.resources.Identifier`） |
| 2 | 类型不存在：旧包/已删类型（RenderType） | 19（class 12 + variable 4 + package 3） | `BlockOutlineRenderType.java:9`、`OutlineOnlyBufferSource.java:283` | **换包** `net.minecraft.client.renderer.rendertype.RenderType` |
| 3 | 类型不存在：MultiBufferSource（已删） | 12（6+3+3） | `OutlineOnlyBufferSource.java:10`、`VirtualBlockDisplayRenderer.java:543` | **无替代**；改 `SubmitNodeCollector`/`RenderSetup`（第 4 节） |
| 4 | 类型不存在：RenderStateShard（已删） | 12（class 2 + package 10） | `BlockOutlineRenderType.java:58/72-79` | **无替代**；改 `RenderPipeline`+`DepthStencilState`+`RenderSetup` |
| 5 | 类型不存在：LightTexture（已删） | 4 | `LevelRendererMixin.java:8` | **改名** `Lightmap` |
| 6 | 类型不存在：VertexFormat / VertexFormatElement | 7（5+2） | `BlockOutlineRenderType.java:7/69/83`、`OutlineOnlyBufferSource.java:9` | **换包** `com.mojang.blaze3d.vertex.*` |
| 7 | 类型不存在：OutlineBufferSource（已删） | 2 | `EntityOutlineContext.java:6/38` | **无替代**；`EntityRenderState.outlineColor` + outline pass |
| 8 | 类型不存在：EnderDragonPart 包移动 | 1 | `EntityOutlineLocatorResolver.java:5` | 改为 `net.minecraft.world.entity.boss.enderdragon.EnderDragonPart` |
| 9 | 缺方法 | 24 | 见第 2 节 | 见第 2 节（改名/删除/引擎新 API） |
| 10 | 缺字段/常量 | 27 | 见第 2 节（RenderType shard 常量 12 个） | 改 `RenderSetup`/`RenderPipeline` 描述 |
| 11 | 签名/类型不匹配 | 11 | 见第 2 节 | 逐条重写/删 hack |
| 合计 | — | **132** | — | — |

### 1.2 按文件分布（仅 14 个文件有错）

| 文件 | 错误数 | 主要类型 | 处置级别 |
| --- | --- | --- | --- |
| client/outline/BlockOutlineRenderType.java | **33** | RenderStateShard/RenderType/RenderSetup 常量 | **重写（删）** |
| client/outline/OutlineOnlyBufferSource.java | **25** | MultiBufferSource / VertexConsumer 新抽象方法 | **重写（删）** |
| client/outline/VirtualBlockDisplayRenderer.java | **24** | Camera/Dispatcher/getLightColor/hitbox | **重写/改走 submitMovingBlock** |
| client/outline/BlockDisplayWhitelistEntry.java | **13** | ResourceLocation 级联 + 歧义 | 改名 + 小改 |
| mixin/LevelRendererMixin.java | **13** | @Shadow 字段/方法已删、PostChain 签名 | **重写钩子** |
| client/outline/BlockDisplayWhitelist.java | 4 | ResourceLocation 歧义 | 改名 + 小改 |
| client/outline/BlockOutlineFrameState.java | 4 | RenderSystem 旧方法 | 小改/删 |
| client/outline/BlockDisplayPolicy.java | 3 | ResourceLocation 歧义 | 改名 |
| client/outline/EntityBlockGeometrySourceIds.java | 3 | ResourceLocation | 改名 |
| client/outline/BlockOutlineRenderer.java | 3 | Camera/ResourceKey | 小改 |
| client/outline/BlockTagLookup.java | 2 | ResourceLocation | 改名 |
| client/outline/EntityOutlineContext.java | 2 | OutlineBufferSource | 重写（删） |
| mixin/GameRendererMixin.java | 2 | @Shadow getMainCamera/renderBuffers | 重写钩子 |
| client/outline/EntityOutlineLocatorResolver.java | 1 | 包移动 | 改名 |
| （其余 56 个文件） | **0** | — | 保持 |

---

## 2. 缺方法 / 缺字段 明细与 26.3 替代

| 符号 | 次数 | 代表 | 26.3 替代 |
| --- | --- | --- | --- |
| Camera.getPosition() | 2 | VirtualBlockDisplayRenderer.java:195 | **Camera.position()**（javap: `public Vec3 position()`） |
| Camera.getEntity() | 2 | BlockOutlineRenderer.java:85 | **Camera.entity()** |
| RenderSystem.applyModelViewMatrix() | 1 | BlockOutlineFrameState.java:124 | 删除；只剩 `getModelViewMatrixCopy()` |
| RenderSystem.getProjectionMatrix() | 2 | BlockOutlineFrameState.java:134 | **getProjectionMatrixBuffer()**（返回 GpuBufferSlice） |
| RenderSystem.getVertexSorting() | 2 | BlockOutlineFrameState.java:139 | 无；改用 `CameraRenderState`/提交管线 |
| RenderSystem.setProjectionMatrix(Matrix4f, VertexSorting) | 1（签名） | BlockOutlineFrameState.java:144 | 新签名 `setProjectionMatrix(GpuBufferSlice, ProjectionType)` |
| ResourceKey#location() | 2 | BlockOutlineRenderer.java:84 | **identifier()** |
| EntityRenderDispatcher.shouldRenderHitBoxes()/setRenderHitBoxes(boolean) | 3 | VirtualBlockDisplayRenderer.java:793/795/815 | **无该 API**；删除「临时关闭 hitbox」hack（M7 改 submitMovingBlock 后不需要） |
| LevelRenderer.getLightColor(ClientLevel, BlockPos) | 2 | VirtualBlockDisplayRenderer.java:312 | **无**；用 `MovingBlockRenderState.lightEngine` 或 `submitMovingBlock`（第 4 节） |
| LevelRenderer.outlineBufferSource()/getMainCamera()/renderBuffers()/getMainRenderTarget() | 4 | LevelRendererMixin.java:105/63/64/129 | @Shadow 失效；改 Fabric `LevelRenderEvents` / 引擎状态 |
| LevelRenderer#getModelViewMatrix / GameRenderer 旧访问器 | 2 | LevelRendererMixin.java:68 | RenderSystem.getModelViewMatrixCopy() 或事件 pose |
| DisplayRenderer.render(BlockDisplay, ...) | 1 | VirtualBlockDisplayRenderer.java:803 | 状态化 `submit/extractEntity`；或 `submitMovingBlock` |
| SableClientProvider.resolvePresentation(ClientLevel, ...) | 2 | BlockOutlineRenderer.java:146 | 当前 client 桩签名未含该重载 → **M9**（完整实现留 deferred） |
| BlockStateBase.getOffset(Level, BlockPos) | 1（签名） | VirtualBlockDisplayRenderer.java:713 | 现签名 **getOffset(BlockPos)**（javap 已核） |
| BlockEntityRenderer<BlockEntity> 泛型 | 1（签名） | VirtualBlockDisplayRenderer.java:522 | 26.3 为 `<E,S extends BlockEntityRenderState>` 两参；用 `? extends` 或改 `submit` |
| PostChain.process(float) | 2（签名） | LevelRendererMixin.java:128/145 | 现签名 `process(RenderTarget, GraphicsResourceAllocator)`；且 entityEffect 字段已删 → 删 redirect |
| VertexConsumer 新抽象方法 setLineWidth(float)（及 setUv3） | 2+ | OutlineOnlyBufferSource.java:334/414 | 自定义 VertexConsumer 必须实现新抽象方法（javap 列 9 个抽象方法） |
| RenderType 静态 shard 常量（RENDERTYPE_LINES_SHADER、VIEW_OFFSET_Z_LAYERING、TRANSLUCENT_TRANSPARENCY、MAIN_TARGET、NO_CULL、NO_DEPTH_TEST、COLOR_WRITE、NO_TEXTURE、NO_LIGHTMAP、NO_OVERLAY、DEFAULT_TEXTURING、NO_COLOR_LOGIC） | 12 | BlockOutlineRenderType.java:86-135 | 全部删除；用 `RenderSetup.builder(RenderPipeline)` + `RenderPipeline.Builder.withDepthStencilState(Optional)` 表达（第 4 节） |
| ResourceLocation 静态方法（isValidNamespace/isValidPath/fromNamespaceAndPath） | 7 | BlockDisplayWhitelistEntry.java:128-150 | **Identifier 同名静态方法**（改名即可） |
| BLOCK_DISPLAY 变量 | 1 | VirtualBlockDisplayRenderer.java:860 | `EntityTypes.BLOCK_DISPLAY`（26.3 注册表常量） |
| matches 调用歧义 | 2（签名） | BlockDisplayPolicy.java:70 / BlockDisplayWhitelist.java:131 | 改名后重载不再与缺失类型冲突；建议顺手明确参数类型 |

---

## 3. 结论：M7 是「改名级还是重写级」

| 子系统 | 文件 | 错误数 | 判定 | 预估 |
| --- | --- | --- | --- | --- |
| 白名单/策略/标签（纯逻辑） | BlockDisplayWhitelistEntry/Whitelist/Policy/TagLookup | 22 | **改名级**（ResourceLocation→Identifier + 歧义消除） | 0.5d |
| 其它 outline 逻辑（56 个干净文件 + 5 个小错文件） | 61 文件 | 13 | **改名级/小改**（Camera/ResourceKey/RenderSystem 访问器） | 0.5d |
| 线框渲染类型 | BlockOutlineRenderType | 33 | **删除重写**（RenderType/RenderStateShard → RenderSetup/RenderPipeline/RenderType.create） | 1d |
| 线框缓冲适配 | OutlineOnlyBufferSource | 25 | **删除重写**（无 MultiBufferSource；改 submitShapeOutline/submitCustomGeometry） | 0.5–1d |
| 方块模型光晕 | VirtualBlockDisplayRenderer | 24 | **重写**（优先 `submitMovingBlock`，放弃 Display 实体 impersonation） | 1–2d |
| 世界钩子 mixin | LevelRendererMixin / GameRendererMixin | 15 | **重写钩子**（Fabric LevelRenderEvents；@Shadow 全失效） | 1d |
| 其它 mixin | ModelBlockRendererMixin / DisplayBlockDisplayAccessor | 0（可编译） | **删除**（目标已不存在/API 已公开） | 0.2d |
| **合计** | **14 个错误文件 + 2 个可编译但失效的 mixin** | **132** | **重写级，但高度集中** | **约 4–6d**（与 12 号 4–7d 一致） |

---

## 4. 26.3 渲染 API 真实签名（javap，M7 施工依据）

> classpath：`minecraft-client-only.jar + minecraft-common.jar (+ fabric-rendering-v1)`；`-p -s` 实测。
> **重要修正**：`SubmitRenderPhases` 属于 **Fabric API** 包 `net.fabricmc.fabric.api.client.rendering.v1`，不是 `net.minecraft.client.renderer`（12 号报告写的是简称，M7 引用时用全名）。

### 4.1 提交接口 `net.minecraft.client.renderer.OrderedSubmitNodeCollector`

| 方法 | 真实签名（descriptor） |
| --- | --- |
| submitShapeOutline | `(PoseStack, VoxelShape, RenderType, int color, float width, boolean afterTerrain)` → `(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/client/renderer/rendertype/RenderType;IFZ)V` |
| submitBlockModel | `(PoseStack, RenderType, List<BlockStateModelPart>, int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor)` → `(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Ljava/util/List;[IIII)V` |
| submitMovingBlock | `(PoseStack, MovingBlockRenderState, int outlineColor)` → `(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/block/MovingBlockRenderState;I)V` |
| submitCustomGeometry | `(PoseStack, RenderType, SubmitNodeCollector.CustomGeometryRenderer)` → `(...;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V` |

### 4.2 `net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases`（Fabric）

| 常量 | 泛型 | 用途 |
| --- | --- | --- |
| SHAPE_OUTLINES | SubmitNode | **VoxelShape 线框（submitShapeOutline 的默认相位）** |
| AFTER_TERRAIN / ALWAYS_ON_TOP | SubmitNode | 地形后 / 永远置顶（穿墙线候选相位） |
| GIZMOS | SubmitNode | Gizmos 提交（配 BEFORE_GIZMOS） |
| OUTLINE | SubmitNode | outline 相位 |
| 其余 | SOLID/SHADOWS/NAME_TAGS/SEE_THROUGH_NAME_TAGS/TEXTS/TRANSLUCENT_*/BREAKING_OVERLAY/WATER_MASK | — |

### 4.3 线渲染类型 `RenderTypes`

| 方法 | 签名 |
| --- | --- |
| lines | `static RenderType lines()` |
| linesTranslucent | `static RenderType linesTranslucent()` |
| linesTranslucentNoDepthWrite | `static RenderType linesTranslucentNoDepthWrite()`（深度测试仍开、写关） |
| linesDepthBias | `static RenderType linesDepthBias()` |
| secondaryBlockOutline | `static RenderType secondaryBlockOutline()` |

### 4.4 自定义管线

| API | 签名/可见性 |
| --- | --- |
| RenderPipelines.register | `private static RenderPipeline register(RenderPipeline)`（descriptor `(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)L...;`）——**源码 private，但 Fabric `fabric-transitive-access-wideners-v1` 的 classtweaker 第 436–438 行把它标为 `transitive-accessible`，Loom 构建下可调用**（E6 用裸 jar 未应用 AW，故 javap 看不到 public） |
| RenderPipelines.registerOptional | `private static RenderPipeline registerOptional(RenderPipeline)`（同样 AW 可访问） |
| RenderPipeline.Builder.withDepthStencilState | `public Builder withDepthStencilState(DepthStencilState)` 与 `public Builder withDepthStencilState(Optional<DepthStencilState>)`（`Optional.empty()` = 无深度测试） |
| RenderPipeline.Builder.withLocation / build | `withLocation(String|Identifier)`、`RenderPipeline build()` |
| RenderSetup.builder / RenderType.create | `RenderSetup.builder(RenderPipeline)` 公开；`RenderType.create(String, RenderSetup)` 由 AW（classtweaker:128）开放 |

### 4.5 Gizmos / 实体与关卡状态

| API | 签名 |
| --- | --- |
| Gizmos.line | `static GizmoProperties line(Vec3, Vec3, int argb)` 与 `(Vec3, Vec3, int, float width)` |
| GizmoProperties.setAlwaysOnTop | `GizmoProperties setAlwaysOnTop()`（穿墙/置顶） |
| GizmoStyle.stroke | `static GizmoStyle stroke(int argb)` / `(int argb, float width)` |
| EntityRenderState.outlineColor | `public int outlineColor`（0 = 不发光；非 0 触发 outline） |
| LevelRenderState.shouldShowEntityOutlines | `public boolean shouldShowEntityOutlines` |
| LevelRenderer.collectPerFrameRenderThreadGizmos | 公开（javap 可见） |

---

## 5. 与 E1/E2 的衔接建议

1. E1（submitShapeOutline 遮挡语义）：用 `RenderTypes.linesTranslucentNoDepthWrite()` + `afterTerrain=true/false` 四组合，直接复用本报告的签名。
2. E2（自定义无深度管线）：`RenderPipeline.builder(RenderPipelines.LINES_SNIPPET).withDepthStencilState(Optional.empty()).withLocation(...).build()` → `RenderPipelines.register(...)`（AW 可访问）→ `RenderType.create(name, RenderSetup.builder(pipeline)...build())`；注意 E6 裸 jar 下 register 显示 private，是 AW 未应用，不是 API 不可用。
3. 线框渲染走 `submitShapeOutline` 后，BlockOutlineRenderType（33 错）与 OutlineOnlyBufferSource（25 错）**直接删除**，不需要逐条修 58 个错误。
4. 方块光晕优先 `submitMovingBlock`（`MovingBlockRenderState` 可填 blockState/pos/lightEngine），可让 VirtualBlockDisplayRenderer 的 24 个错误大部分消失；Display 实体 impersonation 与 hitbox hack 一并废弃。
5. 4 个 mixin：LevelRenderer/GameRenderer 改 `LevelRenderEvents`（`COLLECT_SUMITS/BEFORE_GIZMOS/START_MAIN/END_MAIN`），ModelBlockRenderer/DisplayAccessor 删除。

---

## 6. 复跑命令

```bash
cd F:/Minecraft_Mod_Project/Minecraft_26.3/Minecraft_26.3_Fabric
rm -rf build/e6 && mkdir -p build/e6/src/nx/pingwheel/common/client/outline build/e6/src/nx/pingwheel/common/mixin build/e6/out
cp deferred/client/outline/*.java build/e6/src/nx/pingwheel/common/client/outline/
cp deferred/mixin/{LevelRendererMixin,GameRendererMixin,ModelBlockRendererMixin,DisplayBlockDisplayAccessor}.java build/e6/src/nx/pingwheel/common/mixin/
find build/e6/src -name "*.java" | sort > build/e6/sources.txt
sed "s#^-cp #-cp F:/Minecraft_Mod_Project/Minecraft_26.3/Minecraft_26.3_Fabric/tools/.compile-check/client;#" tools/.compile-check/cp-client.txt > build/e6/cp.txt
"/c/Program Files/Java/jdk-25/bin/javac.exe" --release 25 -proc:none -nowarn -encoding UTF-8 -Xmaxerrs 20000 @build/e6/cp.txt @build/e6/sources.txt -d build/e6/out > build/e6/javac.log 2>&1
grep -c ": error:" build/e6/javac.log   # 132
```

