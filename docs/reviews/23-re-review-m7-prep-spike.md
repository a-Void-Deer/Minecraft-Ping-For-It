# 23 · M7-prep 实验桩复审（D1 修复 + mode 6 gizmo）

任务：task-21　审核人：3号　日期：2026-10-30　基线：`7595b35`（fix(m7-prep)）+ 工作树
方法：只读源码；`javap -c/-v/-p`（JDK 25 + MC 26.3 deobf + fabric-rendering-v1 27.2.0）核 bytecode 与注入点；**复跑** `tools/compile-check.ps1` → `main 0 / client 91`（与 Lead 一致）。未改 `src/**`。

## 结论

**D1 修复通过；mode 6 实现正确且与原生 on-top gizmo 提交路径等价；但 2号 给出的「不能用 `Gizmos.line`」理由不成立（结论要改，代码可留）。** 其余（单次注册、日志预算、失败隔离）全部保持。

---

## 一、通过项

| # | 项 | 证据 |
|---|---|---|
| P1 | **D1 拼写修复彻底** | `collectSubmitsEvent()` 现为 `return LevelRenderEvents.COLLECT_SUBMITS;`（:159-161）；全文件 grep 无 `COLLECT_SUMITS` / `getField` / `java.lang.reflect` 残留；javadoc :208 同步 |
| P2 | **反射移除、catch 修正** | 直接引用；注册 catch 只剩 `RuntimeException | LinkageError`（:154），不再 catch `ReflectiveOperationException` |
| P3 | **mode 6 只注册 BEFORE_GIZMOS** | `registerIfEnabled :130-136`：`if (mode == GIZMO_MODE) BEFORE_GIZMOS.register(onBeforeGizmos); else collectSubmitsEvent().register(...)` —— if/else 互斥，全文件仅这两处 `.register(` |
| P4 | **mode 6 与原生 on-top 组等价** | vanilla `LevelRenderer.submitFeatures` 字节码（offset 95-128）：`finalizeGizmoCollection(); finalizedGizmos.alwaysOnTopPrimitives().submit(submitNodeCollector, levelRenderState.cameraRenderState, true);` 随后 `standardPrimitives().submit(..., false)`；`DrawableGizmoPrimitives.submit` 的 bytecode 把 opaque 与 translucent 两个 Group **以同一个 onTop 参数**转发给 `SubmitNodeCollector.submitGizmoPrimitives(group, cameraRenderState, onTop)` —— 桩里 `submit(..., true)`（:312）就是这条调用 |
| P5 | **mode 6 的 12 条边完整** | :295-308 底/顶各 4 条 + 4 条竖边；`addEdge` 调 `addLine(Vec3, Vec3, 0xFFFF0000, 3.0f)`（:323-329），坐标是世界坐标（gizmo API 的约定），无 pose 变换 ✔ |
| P6 | **开关零开销不变** | 仍只在客户端初始化调用一次（`FabricClient:96-98`）；文件不存在直接 return；`readAllLines` 仅一次；`onBeforeGizmos/onCollectSubmits` 不碰文件系统 |
| P7 | **日志预算与失败隔离不变** | `logBudgeted :331-341`（≤1 条/5s，首次立即）；`warnOnce :343-351`（单闸）；mode 6 失败路径独立：`submissionDisabled = true` + 一次 WARN（:316-320）；@:210` 的 `renderType == null` 对 mode 6 无副作用（不注册该事件） |
| P8 | **compile** | `main errors=0 classes=262 / client errors=0 classes=91` |
| P9 | **文档同步（大部分）** | doc 21 §7 已改成「拼写错误」，doc 12 已把 `RenderPipelines.register` 改为 **private**（`git show 7595b35 -- docs/reviews/12`） |

---

## 二、偏差/需要更正的地方

### D1 · 应修（文档/注释，不是代码）｜「`Gizmos.line` 在 Fabric 事件里 collector 为 null」不成立
- 2号 的机制描述**对**：`Gizmos.addGizmo` 字节码先 `ThreadLocal<GizmoCollector>.get()`，为 null 时抛 `IllegalStateException("Gizmos cannot be created here! No GizmoCollector has been registered.")`。
- 但「在 `BEFORE_GIZMOS` 时为 null」**不成立**。字节码证据链：
  1. `Minecraft.renderFrame`：`:473 collectPerFrameRenderThreadGizmos()`（内部 `Gizmos.withCollector(renderThreadGizmos)` 设置 ThreadLocal，见 `collectPerFrameRenderThreadGizmos` 字节码）→ **try 开始** → `:500 GameRenderer.render()` → `:508 TemporaryCollection.close()`（恢复旧值）。
  2. `GameRenderer.render` → `LevelRenderer.render(...)` → `submitFeatures(...)`（`LevelRenderer` offset 78 调用）。
  3. `BEFORE_GIZMOS` 的注入点是 `LevelRendererMixin.copyBeforeGizmos`：`@Inject(method="submitFeatures", at=@At(value="INVOKE", target="LevelRenderer;finalizeGizmoCollection()V"))` —— 发生在 `submitFeatures` 内、且在 `Minecraft.renderFrame` 的 try 作用域里。
  → 事件触发时 ThreadLocal **已设置**（renderThreadGizmos），`Gizmos.line(...).setAlwaysOnTop()` 不会抛；而且事件在 `finalizeGizmoCollection` 的 drain 之前，加入的 gizmo 会被本帧收走。
- 影响面：仅注释与 doc（`OutlineExperiment:257-265` javadoc、doc 21 `:110` 的「只在原版 collection scope 内存在」表述）。代码用 `DrawableGizmoPrimitives` 没问题，甚至更稳（不依赖 vanilla 的 try 作用域实现细节）。
- 建议改法（二选一）：
  a) 保留实现，把理由改成「避免依赖 vanilla 内部 ThreadLocal 生命周期（当前虽然在 render scope 内可用，但不是公开契约）」——事实准确；
  b) 若想用门面，可加 mode 7 用 `Gizmos.line(...).setAlwaysOnTop()` 做对照（同样走 on-top 组），预计可用；反正 E3 只是对照实验（AGENTS 8.1 已限定 gizmo 不能替代正式实现）。

### D2 · 建议（低）｜mode 6 启动日志打印无意义的 `afterTerrain=true`
- :140-141 对所有 mode 都打 `afterTerrain={}`；gizmo 模式不经过 `submitShapeOutline`，该值无意义。建议 mode 6 打印 `afterTerrain=n/a` 或省略，避免实机记录误导。

### D3 · 建议（低）｜`collectSubmitsEvent()` 现在是纯转发
- :159-161 可以内联到调用点（:135），少一层间接；不影响行为。

---

## 三、实机验证指引（在 doc 21 §3 基础上补充）

1. 编译已 0/0；运行后创建 `<gameDir>/outline-experiment.txt` 写 `6`，重启。
2. 预期首条日志：`outline experiment: mode=6 renderType=gizmos(DrawableGizmoPrimitives, onTop=true) afterTerrain=true (E1/E2/E3 spike active)`（注意 D2 的 `afterTerrain=true` 为无意义字段）；随后每 5 秒一条 `submitted=N`。
3. **E3 要回答的三件事**（doc 21 已列）：① 是否真的穿墙（on-top 组）；② 线宽是否按 3.0 渲染（gizmo 管线可能钳到 1px）；③ 打开 F3 后是否仍可见/变细（gizmo 属调试可视化，可能受调试开关影响）。
4. 对照基线：同位置用 mode 4 跑一次（`linesTranslucentNoDepthWrite + afterTerrain=true`，预期被墙遮挡），确认 E3 的「穿墙」确实是 on-top 组带来的而不是坐标巧合。
5. 失败注入（可选）：临时在 `onBeforeGizmos` 里抛异常 → 应只出现一条 `gizmo submission failed` WARN 且之后不再画（`submissionDisabled`）；恢复文件重启即可。

---

## 四、复跑要点

```
& tools/compile-check.ps1        # main 262 / client 91, 0 error
javap -c -p -cp <mc jars> net.minecraft.client.renderer.LevelRenderer        # alwaysOnTopPrimitives().submit(..., true)
javap -c -p -cp <mc jars> net.minecraft.client.Minecraft                     # renderFrame: 473 withCollector → 500 render → 508 close
javap -v -p -cp <fabric-rendering-v1+mc> net.fabricmc.fabric.mixin.client.rendering.LevelRendererMixin  # BEFORE_GIZMOS @Inject(INVOKE finalizeGizmoCollection)
javap -c -p -cp <mc jars> net.minecraft.gizmos.Gizmos                        # addGizmo: ThreadLocal null → IllegalStateException
```
