# 22 · M7-prep E1/E2 实验桩审查（OutlineExperiment）

任务：task-19　审核人：3号　日期：2026-10-30　基线：`567c0fc`（feat(m7-prep)）+ 工作树
方法：只读源码；`javap`（JDK 25，MC 26.3 deobf jar + fabric-rendering-v1 27.2.0）核签名/参数名；**独立复现** Lead 的 javac 现象（最小 classpath 实编译）；未改 `src/**`。

## 结论

**当前桩不能产出任何 E1/E2 数据：反射字段名拼错了。**
真实字段是 `LevelRenderEvents.COLLECT_SUBMITS`（注意 **B**），而代码与文档写的是 `COLLECT_SUMITS`（缺 B）。`Class#getField("COLLECT_SUMITS")` 会在运行时抛 `NoSuchFieldException` → 被 `registerIfEnabled` 的 catch 吞掉 → 只打一条 WARN → **事件从未注册，探针永不运行**。
Lead 遇到的 `cannot find symbol` **不是 jar、不是 classpath、也不是 javac 的问题**，就是标识符拼写错误：用正确拼写 `COLLECT_SUBMITS` 编译**直接通过**（我用 JDK 25 + fabric-api-base + fabric-rendering-v1 最小 classpath 实测 `exit=0`）。因此 **M7 不需要因此改用反射**。

其余设计基本站得住：
- 开关「文件不存在 = 零开销」成立（初始化时一次 stat/读文件，不存在则**不注册任何事件**）；
- 异常隔离、WARN 一次、日志 ≤1 条/5s 均成立；
- mode 1–4 的 2×2 矩阵与 `afterTerrain` 参数语义一致（参数名已用 javap 常量池核实）；
- **2号 关于 mode 5 的结论正确**：`RenderPipelines.register` 是 **private**、`RenderType.create` 是**包私有**、`LINES_SNIPPET` 是 **private** → 自注册管线确实需要 access widener / mixin。
- **但「E2 必须引入 access widener」下结论过早**：26.3 有一条**公开**的「always-on-top 线」路径（`net.minecraft.gizmos.Gizmos.line(...).setAlwaysOnTop()` / `DrawableGizmoPrimitives` + `submitGizmoPrimitives(..., onTop)`），建议先跑 E3 再决定 AW。

---

## 一、通过项

| # | 项 | 证据 |
|---|---|---|
| P1 | **开关零开销** | `FabricClient.java:96-98` 仅在客户端初始化调用一次 `registerIfEnabled()`；`OutlineExperiment:90-101` 先 `Files.isRegularFile`，不存在就 `return`（不注册事件、不建对象）；文件用 `Files.readAllLines` 读**一次**（:155-179），**无任何每帧文件 I/O**（`onCollectSubmits :199-242` 不碰文件系统） |
| P2 | **无条件注册不存在** | 事件注册（:117）在开关与 mode 校验之后；无文件时全库只有一次 stat；删除文件重启即彻底消失（javadoc :41-47 亦如此声明） |
| P3 | **异常隔离** | 注册路径 catch `RuntimeException | LinkageError | ReflectiveOperationException` → `warnOnce` 后 return（:134-136）；提交路径 catch `RuntimeException | LinkageError` → `submissionDisabled = true` 并 WARN 一次（:236-241）；不会崩客户端，也不会连续重试 |
| P4 | **WARN 只一次** | `failureLogged` 单布尔（:74, 256-264），注册失败与提交失败共用同一道闸 |
| P5 | **日志 ≤ 每 5 秒一条** | `logBudgeted :244-254`：`System.currentTimeMillis` 差 < `LOG_INTERVAL_MILLIS(5000)` 直接返回；INFO 仅 `onCollectSubmits` 每帧末尾调用一次 |
| P6 | **mode 1–4 映射正确（矩阵本身）** | `select :188-196`：1=`linesTranslucent`/false、2=`linesTranslucent`/true、3=`linesTranslucentNoDepthWrite`/false、4=`NoDepthWrite`/true —— 干净的 2×2；`RenderTypes.linesTranslucent()/linesTranslucentNoDepthWrite()` 均存在（javap 已核，:762/:766 级公开方法） |
| P7 | **`afterTerrain` 参数确实存在且是最后一个参数** | `javap -v OrderedSubmitNodeCollector`：`submitShapeOutline` 描述符 `(PoseStack,VoxelShape,RenderType,I,F,Z)`，常量池参数名 `shape`、`width`、`afterTerrain`；调用点 :229 传 `(poseStack, CUBE, renderType, COLOR(int), LINE_WIDTH(float), afterTerrain(bool))` 顺序正确 |
| P8 | **mode 5 的非 public 结论正确** | javap：`RenderPipelines.register` 为 `private static`（另有 `registerOptional` 亦 private）；`RenderType.create(String, RenderSetup)` 无 public 修饰（包私有）；`RenderPipelines.LINES_SNIPPET` 为 `private static final`；而 `RenderPipeline.Builder.withDepthStencilState(Optional<...>)` 是 public —— 即「snippet/注册/建类型」三处都拿不到，**必须 AW 或 mixin** |
| P9 | 姿态栈成对 | `poseStack.pushPose()` / `finally popPose()`（:222-232） |
| P10 | 探针坐标合理 | 以 `player.blockPosition().below()` 为基准、相机相对平移（:217-228），3×3×3 `Shapes.box`（:71），红色 3px 线（:65-68） |
| P11 | 编译（表面） | 反射写法掩盖了拼写错误，`tools/compile-check` client 0 error（见 doc 21 §7），**但这正是 D1 的隐蔽之处** |

---

## 二、偏差项（含严重度）

### D1 · **阻断（critical）**｜反射字段名拼写错误 → 探针永不运行
- 代码：`OutlineExperiment.java:151` `.getField("COLLECT_SUMITS")`；javadoc `:140,142,198` 同样写作 `COLLECT_SUMITS`。
- jar 事实（javap `-p` 实测）：字段为 `public static final Event<...$CollectSubmits> COLLECT_SUBMITS;` —— **SUBMITS**，不是 SUMITS。
- 运行期后果：开关文件存在时 `collectSubmitsEvent()` 抛 `NoSuchFieldException`（`ReflectiveOperationException` 子类）→ `registerIfEnabled` catch → `warnOnce` 一条 “registration failed (NoSuchFieldException)” → **registered 仍为 false，事件从不注册，E1/E2 一条数据都不会产生**。日志里只会看到一条 WARN，很容易被忽略。
- 修法：把 4 处 `COLLECT_SUMITS` 改成 `COLLECT_SUBMITS`（代码 2 处、javadoc 2 处），**并删除反射**——直接引用可编译（见 D2 的实测）。删除后 `collectSubmitsEvent()` 可简化为直接 `LevelRenderEvents.COLLECT_SUBMITS`。

### D2 · 文档错误（高）｜doc 21 §6 把「拼写错误」误判为「javac/jar 组合怪现象」
- doc 21 `:89-102` 称：字段存在（javap 可见），但 javac 对**这一个字段名**报 `cannot find symbol`，建议 M7 优先怀疑 «javac/jar 组合» 并改用反射。
- **独立复现结论：不成立。** 我用最小 classpath（`fabric-api-base-2.0.6 + fabric-rendering-v1-27.2.0`，JDK 25 `javac`）编译：
  - 写 `LevelRenderEvents.COLLECT_SUMITS` → 复现 `cannot find symbol`；
  - 写 `LevelRenderEvents.COLLECT_SUBMITS` → **编译通过（exit 0）**；
  - JDK 24 亦同样表现（说明与 JDK 版本无关）；
  - `javap -p` 显示的字段名始终是 `COLLECT_SUBMITS`。
- 结论：**jar 正常、classpath 正常、javac 正常，是标识符拼写错误**。M7 不应因此大面积改用反射；doc 21 §6 与 doc 12 的相关推断都需要更正（doc 12 `:61` 还写着 `RenderPipelines.register(...)` 是 public，实际是 **private**，同样要改）。

### D3 · 观察（低）｜mode 5 的 fallback 与 mode 4 完全等价
- `select(5)` fallback = `linesTranslucentNoDepthWrite + afterTerrain=true`（:194），与 mode 4 是同一组合。所以即使 D1 修好，跑 mode 5 也**不会给出 E2 的新数据**，只会多一条 WARN。doc 21 已说明 fallback，但建议实机表里把 mode 5 标成「= mode 4，仅验证降级路径不崩」。

### D4 · 建议（影响 M7 决策）｜E2 有不需要 AW 的公开候选路径
- 26.3 公开 API：`net.minecraft.gizmos.Gizmos.line(Vec3, Vec3, int color, float width)` 返回 `GizmoProperties`，其 `setAlwaysOnTop()` 为 public；另有 `DrawableGizmoPrimitives.addLine(...)` + `OrderedSubmitNodeCollector.submitGizmoPrimitives(Group, CameraRenderState, boolean onTop)`（javap 参数名 `group`/`onTop` 已核）。
- 含义：**「穿墙线」不必先上 access widener**，可先做 E3（gizmo 路径）验证 line width / phase / 是否受 F3 或 debug 渲染开关影响；若可用，E2 的结论可以「零 AW」达成，避免为实验引构建改动。
- 注意点：gizmo 线的线宽单位、深度/遮挡语义与 `submitShapeOutline` 可能不同，需在实机里按同一套 2×2 矩阵记录。

### D5 · 建议（低）｜失败信息可更可诊断
- `warnOnce("... registration failed (" + failure.getClass().getSimpleName() + ")")` 只给异常类名；把 `Exception#getMessage`（或 `NoSuchFieldException` 的字段名）带上，D1 这种问题一眼可见。日志仍保持一条。

---

## 三、实机验证指引（修完 D1 后）

1. 先修 D1（`COLLECT_SUBMITS` + 去反射），重新 `gradlew build`。
2. 在游戏目录建 `outline-experiment.txt`，内容分别为 `1`/`2`/`3`/`4`（每次改文件需**重启**，因为只在客户端初始化读一次）。
3. 预期日志（每 5 秒最多一条）：
   `outline experiment: mode=<n> renderType=<desc> afterTerrain=<b> (E1/E2 spike active)`，
   随后 `outline experiment: mode=<n> submitted=<count>`。
4. 记录矩阵（每个 mode 一张截图 + 一句「是否被墙挡住」）：

| mode | RenderType | afterTerrain | 预期（待填） |
|---|---|---|---|
| 1 | linesTranslucent | false | ? |
| 2 | linesTranslucent | true | ? |
| 3 | linesTranslucentNoDepthWrite | false | ? |
| 4 | linesTranslucentNoDepthWrite | true | ? |
| 5 | fallback = mode 4 | true | = mode 4（仅验证不崩） |

5. 判读提示：`linesTranslucentNoDepthWrite` 的名字是「不写深度」，**深度测试仍开**，因此 1–4 很可能**全部被遮挡**——若如此，E1 的答案就是「默认遮挡」，E2 必须走「无深度测试」路径（gizmo onTop 或 AW 自定义管线）。
6. 删除开关文件重启 → 日志应完全静默、无任何注册（零开销复验）。

---

## 四、附：javac 现象的最小复现（可复跑）

```
# 正确拼写可编译（exit 0）
javac -cp "fabric-api-base.jar;fabric-rendering-v1.jar" Probe.java
# Probe.java:
#   static Event<LevelRenderEvents.CollectSubmits> ok = LevelRenderEvents.COLLECT_SUBMITS;
# 错误拼写（COLLECT_SUMITS）→ cannot find symbol: variable COLLECT_SUMITS
```
javap 证据：`javap -p -cp fabric-rendering-v1.jar net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents` →
`public static final Event<...$CollectSubmits> COLLECT_SUBMITS;`（12 个字段，第 3 个）。
