# 42 · M8b 复审：资源重载 v1 + 自定义 ping 贴图

任务：task-68　审核人：3号　日期：2026-10-31
分支：`exp/vanilla-lines-m7a`（HEAD `1c0a452`）；对象：新增 `client/resource/PingTextureState`、`PingTextureReloadListener`，改 `FabricClient`、`MarkerHudRenderer`（`WheelHudRenderer` 未改）。
验证：只读源码 + javap（MC 26.3 / fabric-resource-loader-v1）+ sources javadoc + PNG 解码 + **重跑** `bash tools/compile-check.sh`。未改 src，未跑 Gradle。

## 结论

**可以让用户实机测。** A–F 全部通过，未发现 S2/S3；仅有 2 个 S4（blit tint 与上游 shader color 的等价性假设、12x12 固定采样尺寸）和若干边界记录。

---

## 一、通过项

### A. v1 API 正确性

| 检查 | 结论 | 证据 |
|---|---|---|
| `ResourceLoader.get(PackType)` | ✅ | javap：`public static ResourceLoader get(PackType)`（`ResourceLoader.java:50-52`）；`get(CLIENT_RESOURCES)` 返回 loader |
| `registerReloadListener(Identifier, PreparableReloadListener)` | ✅ | javap + sources javadoc（`ResourceLoader.java:54-61`）；调用在 `FabricClient.onInitializeClient`（`:101-102`），id = `pingforit:reload-listener`（`:36`），位于客户端初始化、首次资源重载之前 |
| 线程语义 | ✅ | `SimpleReloadListener<T> implements PreparableReloadListener`（javap）；`reload` 为 final：`supplyAsync(prepare, prepareExecutor) → barrier.wait → thenAcceptAsync(apply, gameExecutor)`（字节码 offset 0-46）；sources javadoc 原文：“The prepare stage … can run on any thread! However, the apply stage is guaranteed to run on the game thread.” → 本实现 prepare 只读 `sharedState.resourceManager()`，apply 只写 volatile，安全 |
| 不阻塞加载条 | ✅ | 未使用 v0（全库 grep `ResourceManagerHelper`/`IdentifiableResourceReloadListener` 只命中注释 `PingTextureReloadListener:17`）；v1 由 barrier 正常串联，prepare/apply 均为 O(1)，无自定义 future/死锁路径 |

### B. 监听器健壮性

- `sharedState.resourceManager().getResourceStack(PING_TEXTURE_ID)`（`PingTextureReloadListener:31-32`）：`ResourceManager.getResourceStack` 返回 `List<Resource>`（javap），包含内置资源 + 覆盖包；随包发布的 1x1 占位恒在 → 无包时 size==1、有覆盖时 >1 ✓。
- `stack == null ? 0 : stack.size()`（`:34`，API 非 null，判空冗余但无害）；`catch (RuntimeException)` → WARN 一次 + 返回 1（按占位处理）（`:35-43`）✓。
- `apply`：`setHasCustomTexture(size != null && size > 1)`（`:47-50`），幂等；`PingTextureState.hasCustomTexture` 为 `volatile`（`PingTextureState:12`）✓，HUD 渲染线程读取安全。
- 与上游等价：上游 `ResourceReloadListener:25-33` 同样是 `getResourceStack(PING_TEXTURE_ID).size()` 且 `>1` 判自定义 ✓。

### C. HUD 分支

- `MarkerHudRenderer.drawPingIcon` 顺序：item（`:255-261`）→ custom（`:263-281`）→ default diamond（`:283-294`），与上游 `DrawContext:62-70` 的 if/else-if/else 完全对应 ✓。
- 自定义 blit：`graphics.blit(RenderPipelines.GUI_TEXTURED, PING_TEXTURE_ID, -6, -6, 0f, 0f, 12,12,12,12,12,12, view.getPingColor())`（`:266-279`）＝上游 `renderTexture(PING_TEXTURE_ID, 12, color)`（offset=size/-2= -6，size 12；上游 `:84-107`）✓；颜色与默认菱形同源 `view.getPingColor()`（`:291`）✓。
- 默认 `hasCustomTexture=false` → 该分支不执行，渲染路径与之前逐字节一致 ✓；item 图标优先于自定义贴图，也与上游一致。

### D. WheelHudRenderer

- 未改动；`drawIcon`（`:457-479`）无条件 blit `PING_TEXTURE_ID`，颜色由调用方 `applyOpacity(0xFF000000|outlineColor, opacity)` 传入（`:334-338`）＝上游 `WheelOverlayRenderer:486-520` 的无条件 blit + shader color ✓。
- 默认资产 1x1 全透明 → 无包时不可见（与上游“可替换占位”一致），自定义包会同时生效；`ICON_SIZE=6`（`:89`）与上游 `WheelOverlayRenderer:73` 一致，因此**不需要** hasCustomTexture 判断 ✓。

### E. 默认资产

- `src/main/resources/assets/pingforit/textures/ping.png`：**1x1**，像素 `A=0, R/G/B=255`（System.Drawing 解码）→ 全透明占位 ✓；位于 main 源集，会进 jar；`run/` 下未发现覆盖副本。
- 无包时 `getResourceStack` size==1 → `hasCustomTexture=false`，与“默认不可见/默认菱形”结论一致 ✓。

### F. 编译

`bash tools/compile-check.sh` → `main errors=0 classes=266 / client errors=0 classes=101` ✓。

---

## 二、偏差项（含严重度）

### S4-1 · blit tint 与上游 shader color 的等价性属假设
- 上游用 `RenderSystem.setShaderColor(r,g,b,a)` + 白色 blit（`DrawContext:84-107`）；本地用 26.3 `blit(..., int color)` 的 tint 参数（`MarkerHudRenderer:279`）。若 26.3 GUI_TEXTURED 的 color 是 RGBA 乘算（预期），视觉等价；否则 alpha 行为可能不同。需实机对比自定义贴图的颜色/透明度。

### S4-2 · 固定 12x12 采样尺寸
- 本地 `blit` 传 `12,12,12,12`（`:273-278`），上游同样以 12 作为目标与纹理尺寸（`:95-107`）。若自定义包提供的 PNG 不是 12x12，会按 12x12 区域采样（上游同款行为）；在文档/资源包说明里应写明“建议 12x12”。

### S4-3 · 其他边界
- `failureLogged`（`PingTextureReloadListener:26`）非 volatile，但只在串行 prepare 线程写，无并发读；`stack == null` 判空冗余（API 非 null）；`hasCustomTexture` 只看“是否有覆盖”，若包覆盖为全透明贴图，菱形会被替换成不可见（上游同样如此，属包作者责任）。

---

## 三、运行风险

1. **资源包路径必须精确**：`assets/pingforit/textures/ping.png`；放错路径则 size 仍 1，表现为“没生效”（不是 bug）。
2. **F3+T 重载**：v1 会在主线程 apply；若无包，默认行为不变；重载期间不应卡加载条（v1 正常路径）。
3. **监听器注册时机**：必须在客户端初始化阶段（当前 `onInitializeClient`）注册；若未来移到 reload 之后，Fabric 可能拒绝/错过首次重载。
4. **异常降级**：读栈失败 → 仅一条 WARN + 按占位处理（默认菱形），不会中断资源加载。

---

## 四、实机验证步骤

1. **默认不动**：不装资源包启动进世界 → marker 仍是红色菱形，轮盘图标不可见（与之前一致），启动/重载不卡加载条。
2. **覆盖包**：做一个资源包，放 `assets/pingforit/textures/ping.png`（建议 12x12，带 alpha），启用后 `F3+T` 或重启 → marker 图标变成该贴图（颜色仍按 pingType tint），轮盘扇区图标也出现同贴图。
3. **颜色/透明**：用半透明或非白色贴图，确认 tint 后颜色/alpha 与上游观感一致（S4-1 的实机确认点）。
4. **删包回退**：禁用资源包 + `F3+T` → 立即回到默认菱形、轮盘图标消失（幂等 reload）。
5. **日志**：正常情况下无 WARN；若出现 `ping texture reload: could not read the texture stack`，说明读栈失败（按占位处理），记录并排查包的 pack.mcmeta/路径。
6. **回归**：HUD/轮盘/标记、M7 描边、加载条均正常；退出无 crash。

---

## 五、结论

**可以让用户实机测。** v1 API 注册/线程语义正确（prepare 任意线程、apply 主线程），未触碰 v0；listener 以“资源栈深度 >1”判定覆盖，与上游逐字一致，异常时安全回退且 WARN 一次；MarkerHudRenderer 分支顺序/参数/颜色与上游 DrawContext 对齐，默认无包零行为差异；WheelHudRenderer 的既有无条件 blit 与上游一致（占位透明，无需额外判断）。剩余 S4（tint 等价性、12x12 固定采样、少量边界）不阻塞，实机时按 §四 观察即可。