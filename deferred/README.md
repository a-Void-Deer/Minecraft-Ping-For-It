# deferred/ — 1.21.1 渲染 / UI 子系统的历史参考副本

这里是上游 1.21.1（`nx.pingwheel.common.{client.outline,render,screen,resource,mixin}`）的**原样副本**，共 95 个 java 文件；**不参与编译**，只作对照查阅。

- `settings.gradle` 只 `include('common')` / `include('fabric')`，**没有** `deferred`；
- 仓库内 `grep -rn 'deferred' --include=*.gradle .` **零命中**（exit 1，0 行）；
- 因此编译期找不到这里的类是**正常现象**，`package does not exist` 不是 bug。

## 不要为了「修红」把它加回编译路径

M7 是以 **26.3 submit-node / RenderPipeline 重写收口**的，不是把这些文件搬回 sourceSets。加回编译只会重新引入 26.3 已删除的 API（`MultiBufferSource`、`OutlineBufferSource`、`GuiGraphics`、`WorldRenderEvents`…），制造一堆修不掉的错。

### 副本自身也已编译不过（复审 `67` A 项）

这些副本只保证「当年能编译」，**不保证现在能**：它们仍调用 M10 清理时已从 `CommonClient` 删掉的空方法——

- `deferred/mixin/LevelRendererMixin.java:92,95,102,119,124` → `entityOutlineRequestSucceededThisFrame` / `renderModelOutlines` / `renderEntityOutlines` / `modelOutlinesEmittedThisFrame` / `resetModelOutlinesForFrame`
- `deferred/mixin/GameRendererMixin.java:62` → `renderBlockOutlines`
- `deferred/mixin/GuiMixin.java:22` → `CommonClient.INSTANCE.onRenderGUI(...)`（26.3 的 HUD 入口已改为 `HudElementRegistry.addLast`）

所以「零引用」只在**编译树**（`common/src` + `fabric/src` + `settings.gradle`）成立——这本身就是「不要加回编译路径」的又一条直接理由。

## 已验证的 旧 → 新 对照

下表 `旧` = 本目录，`新` = `fabric/src/client/java/nx/pingwheel/common/`。

| 旧 | 26.3 现实现 |
| --- | --- |
| `client/outline/BlockOutlineRenderer.java` | `client/outline/BlockOutlinePass.java` |
| `client/outline/BlockOutlineRenderType.java` | `client/outline/PingOutlineRenderType.java` |
| `client/outline/{EntityOutlineState,EntityOutlineSelection,BlockTagLookup,BlockDisplayPolicy,BlockDisplayWhitelist*}` | 同名文件，内容重写 |
| `client/outline/{BlockModelOutlineState,VirtualBlockDisplayRenderer}` | `BlockModelGlowPass` + `EntityBlockGlowState` |
| `mixin/{GameRendererMixin,LevelRendererMixin,ModelBlockRendererMixin}` | `mixin/{SubmitNodeCollectionMixin,LevelExtractorMixin,EntityRendererMixin,BlockEntityRenderDispatcherMixin,RenderPipelinesAccessor,RenderTypeAccessor}` |
| `mixin/GuiMixin` | `FabricClient` 的 `HudElementRegistry.addLast`（不再需要 mixin） |
| `render/{OverlayRenderer,PingLocationRenderer,DirectionIndicatorRenderer,DrawContext}` | `client/hud/MarkerHudRenderer` |
| `render/WheelOverlayRenderer` | `client/hud/WheelHudRenderer` |
| `render/{SelectionToggleNoticeRenderer,WorldRenderContext}` | `render/` 同名（`*RenderPolicy` 逐字节相同，其余适配） |
| `screen/*`（8 个） | `screen/` 同名（5 个逐字节相同，3 个适配） |
| `resource/ResourceReloadListener` | `client/resource/PingTextureReloadListener`（resource-loader **v1**） |

## 未找到对应重写的部分

- `client/outline/Flywheel*`（8 个）：26.3 无 Flywheel 依赖（`grep -rni flywheel fabric/src common/src` 零命中）。
- `client/outline/{OutlineOnlyBufferSource,LevelRendererOutlineRequest,BlockDisplayPlacement}`、`render/{RenderColorPolicy,WheelOpacity}`：26.3 没有 outline-only buffer / 虚拟方块展示位 / 颜色与透明度策略，分别由 submit-node 路由与 HUD 渲染器合并取代。
- `mixin/DisplayBlockDisplayAccessor`、`fabric/integration/FabricWorldAwareBlockModelOutlineAdapter`、`client/integration/sable/*`：分别被 glow（`submitModel` 的 `outlineColor`）路径、`common/integration/externalblock/`、单个 `SableClientProvider` 取代，非一一对应。

## 去留

**保留作参考，删除与否等用户决定。** 在此之前不要删除、移动或修改这里的代码；真要清理，先确认上表的新实现都已实机通过。
