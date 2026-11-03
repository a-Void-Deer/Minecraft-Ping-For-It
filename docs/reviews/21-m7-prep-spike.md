# 21 · M7-prep 实验桩（E1 / E2 / E3）

> 实现：2号 implementer｜2026-10-30｜代码：`src/client/java/nx/pingwheel/common/render/spike/OutlineExperiment.java`
> 接线：`FabricClient.onInitializeClient()` 里一行 `OutlineExperiment.registerIfEnabled()`
> 约束：不改 `deferred/**`、不改 `ClientConfig`、不改 `build.gradle`、不注册任何 mixin、不改配置 schema。

## 0. 这个桩在回答什么

M7 重写描边之前必须钉死的未知量：

| # | 问题 | 本桩怎么答 |
|---|---|---|
| **E1** | 26.3 里 `submitShapeOutline` 画出的线框，默认会不会被墙挡住？ | mode 1–4：4 种 `RenderType` × `afterTerrain` 组合，各画同一个 3×3×3 线框，肉眼对比 |
| **E2** | 能不能做出「穿墙线框」？ | mode 5 尝试自定义无深度测试管线（**受限，见 §5**）|
| **E3** | 不引 access widener / mixin 能否穿墙？ | mode 6：同一立方体走 **Gizmos on-top 通道**（`DrawableGizmoPrimitives.submit(..., onTop=true)`）|

绘制内容固定：以**玩家脚下方块**为起点的一个 3×3×3 红色立方体线框（mode 1–5 用 `Shapes.box(0,0,0,3,3,3)`；mode 6 用同尺寸的 12 条 gizmo 边），颜色 `0xFFFF0000`、线宽 3.0。
不接线任何正式描边逻辑，删开关文件 + 重启即彻底消失。

## 1. 用户操作步骤（3 步）

1. **在游戏目录建开关文件**（与 `mods/` 同级；开发环境即本工程根目录）：
   ```
   <gameDir>/outline-experiment.txt
   ```
2. **文件里只写一个数字**（第一行非空行），取值 1–6：

   | mode | RenderType / 通道 | afterTerrain / onTop | 想验证什么 |
   |---|---|---|---|
   | 1 | `RenderTypes.linesTranslucent()` | afterTerrain=false | 半透明线 + 不推迟提交，是否被墙遮挡 |
   | 2 | `RenderTypes.linesTranslucent()` | afterTerrain=true | 同上，但延到地形之后提交 |
   | 3 | `RenderTypes.linesTranslucentNoDepthWrite()` | afterTerrain=false | 不写深度，是否仍被墙挡住 |
   | 4 | `RenderTypes.linesTranslucentNoDepthWrite()` | afterTerrain=true | 同上 + 地形之后提交 |
   | 5 | 自定义无深度测试管线（**受限，见 §5**） | afterTerrain=true | E2 穿墙尝试（当前降级） |
   | 6 | **Gizmos**：`new DrawableGizmoPrimitives()` + 12 次 `addLine` | **onTop=true** | E3 穿墙尝试（公开 API，无需 AW）|

3. **重启客户端**（文件只在启动时读一次），进世界后看向**自己脚下的方块**：应看到红色 3×3×3 线框。

> 换 mode：改文件数字 → 重启。关掉实验：删掉文件 → 重启。

## 2. 怎么判读

先在**开阔地**（脚下有方块、周围无墙）确认线框存在；再**贴着一堵墙的背面**站（线框在墙的另一侧 / 或半埋进墙里）比较：

- **线框被墙挡住** → 该组合是「按深度遮挡」的；
- **线框透过墙可见** → 该组合已经能穿墙。

每个 mode 记一条结论（E1 期望：1–4 全部被遮挡，差异只在半透明/深度写入；E2 由 mode 5 给出、E3 由 mode 6 给出）。

**mode 6 还要额外记三件事**（都在 E3 里问）：

1. **是否穿墙**：站墙背面看线框是否完整可见（这是 E3 的核心问题）；
2. **线宽**：gizmo 的 `width` 是否真的按 3.0 渲染、还是被管线钳到最细 1px（看是否和 mode 1–4 的线一样粗）；
3. **F3 影响**：打开 F3 调试屏后，gizmo 线是否仍然可见/是否变细或消失（gizmo 属于调试可视化，可能与 F3 状态相关）；
4. 顺带看**遮挡关系**：on-top 线是否画在所有地形/实体之上（也压过准星附近的 HUD 之外的一切）。

## 3. 日志

启动/注册与每 5 秒一条的进度行（不会每帧刷屏）：

```
[PingForIt] outline experiment: mode=3 renderType=linesTranslucentNoDepthWrite(afterTerrain=false) (E1/E2/E3 spike active)
[PingForIt] outline experiment: mode=6 renderType=gizmos(DrawableGizmoPrimitives, onTop=true) afterTerrain=true (E1/E2/E3 spike active)
[PingForIt] outline experiment: mode=3 submitted=312
```

失败时**只打一次** WARN（不会刷屏，也不会崩客户端）：

```
[PingForIt] outline experiment: outline-experiment.txt must contain a mode 1..6 on the first non-blank line; probe disabled
[PingForIt] outline experiment: registration failed (...)
[PingForIt] outline experiment: submission failed, probe disabled for this session (...)
[PingForIt] outline experiment: gizmo submission failed, probe disabled for this session (...)
```

## 4. 行为与安全边界

- **零开销**：没有开关文件时 `registerIfEnabled()` 直接 return，不注册任何事件、不读文件第二次。
- 文件只在**客户端初始化时读一次**，不随每帧读取。
- 提交逻辑整体 try/catch：任何异常都把本次实验桩标记为失败并禁用，仅 WARN 一次。
- 两条渲染路线：
  - mode 1–5：Fabric `LevelRenderEvents.COLLECT_SUBMITS`（`context.poseStack()` + `context.submitNodeCollector()`）→ `submitShapeOutline(pose, shape, renderType, color, width, afterTerrain)`；
  - mode 6：Fabric `LevelRenderEvents.BEFORE_GIZMOS` → `new DrawableGizmoPrimitives()` + 12×`addLine` → `submit(context.submitNodeCollector(), context.levelState().cameraRenderState, true)`。

## 5. mode 5（E2 直路）为什么降级：26.3 无法注册自定义管线

任务书设想 `RenderPipelines.register(RenderPipeline.builder(LINES_SNIPPET)…withDepthStencilState(Optional.empty())…)` + `RenderType.create(...)`。javap 核对 26.3 + Fabric API 0.165.0：

| 需要的东西 | 26.3 现状 | 证据 |
|---|---|---|
| `RenderPipelines.register(RenderPipeline)` | **private**（`registerOptional` 也是 private） | `javap -p net.minecraft.client.renderer.RenderPipelines` |
| `RenderType.create(String, RenderSetup)` | **package-private** | `javap net.minecraft.client.renderer.rendertype.RenderType` |
| Fabric 侧替代 | `FabricRenderPipeline` 只给 `RenderPipeline.Builder` 加了个 GUI 绘制模式开关，**没有注册入口**；`SubmitRenderPhase` 只能复用既有 phase | `fabric-rendering-v1-27.2.0/api/client/rendering/v1/*` |

结论：**不引 AW/mixin 时无法自定义管线**，mode 5 降级为 `linesTranslucentNoDepthWrite()`（+afterTerrain=true），启动 WARN 一次。
但「穿墙」不一定非要自定义管线 → **E3（mode 6）用 gizmo 的 on-top 通道**验证另一条路；E3 有结论后再决定 M7 是否需要 AW。

## 6. mode 6（E3）用到的 26.3 公开 gizmo API（javap 实测）

| 成员 | 签名 | 用途 |
|---|---|---|
| `net.minecraft.gizmos.Gizmos` | `static GizmoProperties line(Vec3, Vec3, int color, float width)` | 高层门面（需要活动的 gizmo collector） |
| `net.minecraft.gizmos.GizmoProperties` | `GizmoProperties setAlwaysOnTop()` | 高层「永远在上层」开关 |
| `net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives` | `public void addLine(Vec3, Vec3, int, float)` | **本桩使用**：直接攒线 |
| 同上 | `public void submit(SubmitNodeCollector, CameraRenderState, boolean onTop)` | **本桩使用**：onTop=true 即上网层组 |
| `SubmitNodeCollector` | `submitGizmoPrimitives(DrawableGizmoPrimitives.Group, CameraRenderState, boolean onTop)` | submit 内部转发到它 |
| `LevelRenderState` | `public CameraRenderState cameraRenderState`（字段） | 取相机渲染状态 |
| Fabric `LevelRenderEvents` | `BEFORE_GIZMOS` → `beforeGizmos(LevelRenderContext)` | **本桩注册点** |

**为什么不用 `Gizmos.line(...).setAlwaysOnTop()`**：它写入 `Gizmos` 的 `private static final ThreadLocal<GizmoCollector>`，该 collector 只在**原版自己的 gizmo 收集作用域内**存在；在 Fabric 渲染事件里调用会拿不到 collector（`addGizmo` 直接抛异常）。而 `DrawableGizmoPrimitives.submit(..., onTop=true)` 正是原版 on-top gizmo 组走的同一条提交路径，纯公开 API，因此 mode 6 走它。

## 7. 更正：所谓「javac 怪现象」其实是字段名拼写错误

早期版本把 `LevelRenderEvents.COLLECT_SUBMITS` 写成 `COLLECT_SUMITS`（少一个 B），于是 javac 报 `cannot find symbol`、运行期 `getField("COLLECT_SUMITS")` 抛 `NoSuchFieldException` 被 catch 吞成一条 WARN，事件从未注册。

3号 在 `docs/reviews/22-review-m7-prep-spike.md` 用最小 classpath 实测确认：jar、classpath、javac 都正常，就是拼写；改成 `COLLECT_SUBMITS` 后直接引用即可，与 JDK 版本无关。

**结论**：M7 **不需要**反射访问 Fabric 事件字段，直接引用；本桩已改回直接引用。

## 8. 验证状态

| 项 | 结果 |
|---|---|
| `bash tools/compile-check.sh` | **main 0 error / client 0 error**（client classes 91，含本桩） |
| 实机 E1（mode 1–4） | ⬜ 待用户按 §1 操作 |
| 实机 E2（mode 5） | ⬜（当前降级，等价 mode 4） |
| 实机 E3（mode 6） | ⬜ 待用户按 §1/§2 判读穿墙 + 线宽 + F3 影响 |
| 回归风险 | 无开关文件时不注册任何东西；删除文件即恢复 |

## 9. 清理

删掉 `<gameDir>/outline-experiment.txt` 并重启客户端即可完全停用；代码本身在 M7 正式实现落地后整文件删除（`OutlineExperiment.java` + `FabricClient` 里那一行）。
