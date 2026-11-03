# 31 · 复审 E2：mixin 访问器 + 无深度管线 + 分桶路由 mixin

任务：task-40　审核人：3号　日期：2026-10-30
分支：`exp/vanilla-lines-m7a`（HEAD `f3fe654`）；对象：工作树未提交的 4 个新文件 + `VanillaLinesBlockOutline` + `pingforit.mixins.json`。
验证：只读源码 + javap（MC 26.3、Mixin 0.8.7）+ **重跑** `bash tools/compile-check.sh`。未改 src，未跑 Gradle。

## 结论

**可以让用户用 `vanilla-outline.txt=4` 实机测（先 mode 1 冒烟）。** 三个 mixin 的写法合法、路由逻辑正确、管线参数与 29 号复审一致；OIT 模式下 `afterTerrain` 是 `oitTranslucent` 的别名、**会进入渲染列表**（详见 S3-1，31 号初版此处判断已更正），其可见性需实机确认；其余为若干 S4。

---

## 一、通过项

### A. Mixin 合法性

| 检查点 | 结论 | 证据 |
|---|---|---|
| 静态 @Invoker（两个访问器） | ✅ | Mixin 0.8.7（`sponge-mixin:0.17.5+mixin.0.8.7`）的 `MixinCoprocessorAccessor.process` 对 ACC_STATIC 的 @Invoker 接口方法显式处理（sources `:105-119`），接口 static 方法带 body + `throw new AssertionError()` 是标准写法；`InvokerInfo.initTarget` 用 `MemberInfo(name, null, method.desc)` 按描述符匹配，`RenderType.create(String,RenderSetup)->RenderType` 与 `RenderPipelines.register(RenderPipeline)->RenderPipeline` 均能命中（javap 签名逐字一致） |
| SubmitNodeCollectionMixin 目标 | ✅ | `SubmitNodeCollection` 是 **public class（非 final）**（javap）；`submitShapeOutline` 是 public 实现方法 |
| @Shadow @Final 字段 | ✅ | 目标字段确为 `public final SimpleFeatureRenderPhase solid / afterTerrain`（javap 字段列表）；mixin 中同名同类型、非 final 字段 + `@Shadow @Final` 是 Mixin 标准写法（读取用） |
| HEAD @Inject 参数 | ✅ | 目标 6 参 `(PoseStack,VoxelShape,RenderType,int,float,boolean)`；handler 为 6 参 + `CallbackInfo`（`SubmitNodeCollectionMixin:45-53`），`@Inject(method="submitShapeOutline", at=@At("HEAD"), cancellable=true, require=0)`（`:44`），`ci.cancel()`（`:62`）用法正确 |
| require=0 与 defaultRequire=1 | ✅ | `Inject` 注解含 `require`/`expect`（javap）；Mixin `InjectionInfo.parseRequirements`（sources `:395-399`）中 `require > -1` 覆盖 `defaultRequire` → 本注入点缺失时不硬失败；`@Shadow` 字段缺失仍会因 `required:true` 硬失败（版本耦合，见运行风险 2） |
| isCustom 无副作用 | ✅ | `PingOutlineRenderType.isCustom` 只做 `type == cached`（`:64-66`）；首次调用只触发类初始化（字段全为 null/常量，不建管线）；`create()` 仅在 mode 4 分支被调用（`VanillaLinesBlockOutline:107-121`） |
| mixins.json | ✅ | package `nx.pingwheel.common.mixin`（`:4`）、5 个 client 条目（`:6-11`）、`compatibilityLevel JAVA_25`（`:5`），类名/包名一致 |

### B. 路由正确性

- 26.3 的 `submitShapeOutline` 对 alpha=255 一律投 solid 并忽略 `afterTerrain`（`SubmitNodeCollection` 字节码：`ARGB.alpha(color)==255 → solid`）；mixin 在 HEAD 用 **identity** 识别我们的类型并直接投桶，避开该规则 ✓。
- Submit 记录构造与 vanilla 完全同构：`new ShapeOutlineFeatureRenderer.Submit(poseStack.last().copy(), shape, renderType, color, width)`（mixin `:58-59`）与 vanilla 方法体内的构造一致（javap `ShapeOutlineFeatureRenderer$Submit` 5 参 public ctor；`SimpleFeatureRenderPhase.submit` public）✓。
- **afterTerrain 桶位置**：非 OIT 的 `allPhases` 数组顺序为 solid(0), shadows(1), nameTags(2), texts(3), shapeOutlines(4), translucentBlocksAndItems(5), translucentModels(6), translucentCustomGeometry(7), translucentGizmos(8), breakingOverlay(9), waterMask(10), **afterTerrain(11)**, alwaysOnTopGizmos(12), outline(13)（javap 字节码 @288-403）→ 线在**半透明几何之后**绘制，不会被水/半透明盖住 ✓（这正是 D2 的目的）。
- 非自定义类型：handler 直接 return，vanilla 原逻辑不变 ✓；mixin 未织入时退回 vanilla solid（可见但可能被水盖）且不崩 ✓。

### C. 管线参数（相对 29 号复审未变）

`PingOutlineRenderType:80-93`：GLOBALS→PROJECTION→DYNAMIC_TRANSFORMS→FOG、`core/rendertype_lines`、`withCull(false)`、`POSITION_COLOR_NORMAL_LINE_WIDTH`、`LINES`、`new ColorTargetState(BlendFunction.TRANSLUCENT)`、`withDepthStencilState(Optional.empty())` 均与 LINES_SNIPPET/LINES_TRANSLUCENT 对照一致；location `pingforit:pipeline/block_outline` 唯一；RenderSetup `:97-100` 含 `VIEW_OFFSET_Z_LAYERING` 与 `OIT_LINES_TRANSLUCENT` ✓。

### D. S2 修复与外观

- 空形状 `continue`（`:215-219`）；winner 投影（`:194`）；`isLoaded`（`:201`）；registryId 校验（`:211`）；`CollisionContext.of/empty`（`:188-190`）；`disabled` 真禁用（`:152-155, 240-245`）；poseStack 判空（`:158-164`）；日志仅变化且 ≥5s（`:251-263`）均保留 ✓。
- 正常方块外观参数未变：颜色 `0xFF|outlineColor` + FALLBACK 白、宽度 3.75、粗细/维度过滤与 27/29 号一致；mode 1/2/3 的 `afterTerrain=false`（`:191` 仅 mode 4 为 true）✓。

### E. 默认行为

- 无 `vanilla-outline.txt` → mode 1（`:77-78, 144`）；mode 4 才调用 `PingOutlineRenderType.create()`（`:107-121`）；失败回退 mode 2 + 仅一次 WARN（`:113-119`）✓。
- 与 HEAD `f3fe654` 相比，mode 1/2/3 的**绘制参数**（RenderType、alpha、宽度、颜色、维度过滤）一致；差异只有 27 号已接受的 S2 语义修复（winner 投影/空形状/加载校验/日志/禁用），不是本次 mode 4 改动引入。

### F. 编译

`bash tools/compile-check.sh` → `main errors=0 classes=266 / client errors=0 classes=92` ✓。

---

## 二、偏差项（含严重度）

### S3-1（原判有误，已更正）· OIT 模式下 afterTerrain = oitTranslucent（字节码已确认）；OIT 阶段的可见性需实机确认
- **更正（2026-10-30，本轮）**：31 号初版把 OIT 分支里的别名赋值误读成“普通初始化顺序”，得出“afterTerrain 桶不在渲染列表”的错误结论；以本条为准。
- 26.3 的 `SubmitNodeStorage.useImprovedTransparency` 为 true 时，`SubmitNodeCollection` 构造函数（`iload_1; ifeq` 后进入的 OIT 分支，javap 偏移 64-172）执行的是 **别名赋值**：
  `this.shadows = this.nameTags = this.texts = this.shapeOutlines = this.translucentBlocksAndItems = this.translucentModels = this.translucentCustomGeometry = this.breakingOverlay = this.afterTerrain = this.translucentGizmos = this.oitTranslucent`，
  随后 `this.allPhases = List.of(this.solid, this.waterMask, this.outline, this.alwaysOnTopGizmos, this.oitTranslucent)`（偏移 148-172）。
- 因此 OIT 开启时，mixin 投向 `afterTerrain` 的提交**实际落在 `oitTranslucent`**，该阶段在 `allPhases` 中并由 OIT 执行路径渲染，**不会因为“不在渲染列表”而消失**。
- 仍待实机确认的是：OIT 阶段能否正确绘制我们的提交（自定义管线已 `setOitPipelines(OIT_LINES_TRANSLUCENT)` 且 depth test 关闭、带 `VIEW_OFFSET_Z_LAYERING`）。**若实机开启 OIT 时线消失**，再排查 OIT pass 对 `ShapeOutlineFeatureRenderer.Submit`、depth state、layering 的兼容性，作为“OIT 兼容性待查项”记录，而不是分流丢失。

### S4-1 · require=0 的静默降级无法观测
- 若未来 26.3 改方法签名，注入静默跳过，mode 4 退回 vanilla solid（可见但可能被水盖）；没有日志提示。建议在 mixin 首次处理自定义类型时打一条一次性 INFO/DEBUG（或在 mode 4 注册时探测并记录）。

### S4-2 · @Shadow 字段的版本耦合
- `@Shadow` 字段缺失（未来版本改名/改类型）会因 `required:true` 直接启动崩溃；`require=0` 只保护「注入点缺失」，不保护 shadow 解析。属 mixin 路线固有耦合，记录在案。

### S4-3 · 单例/失败标志仍非 volatile
- `cached/failed`（`PingOutlineRenderType:53-54`）只在客户端 init 单线程写；当前安全。若将来从渲染线程按需触发，需同步。

### S4-4 · 提交卫生
- 4 个新文件均未跟踪（`git status` 为 `??`）：`PingOutlineRenderType`、`RenderTypeAccessor`、`RenderPipelinesAccessor`、`SubmitNodeCollectionMixin`；另有 `.tmp-*` 未跟踪目录。提交前 `git add` 并清理。

---

## 三、运行风险

1. **OIT 选项**（S3-1）：该选项默认关闭；开启后 `afterTerrain` 在构造函数里被别名到 `oitTranslucent`（在 `allPhases` 中，由 OIT 执行路径渲染），因此 **预期仍可见**。验收默认关闭即可；也可选开启验证——若线消失，记录为“OIT 兼容性待查”（排查 OIT 阶段与自定义管线/提交的兼容性），不是分流丢失。
2. **mixin 应用失败 = 启动崩**（`required:true`）：`SubmitNodeCollectionMixin` 的 `@Shadow` 或目标类解析失败会在启动阶段抛 Mixin 错误；OIT/版本差异都会触发。先做 mode 1 冒烟可发现。
3. **路由只对 identity 生效**：`cached` 为 null（创建失败）时所有提交走 vanilla；mode 4 已回退 mode 2，行为符合预期。
4. **afterTerrain 相位**（非 OIT 模式）：线绘制在半透明几何之后、alwaysOnTopGizmos/outline 之前；预期不会被水盖，但可能盖住半透明实体/粒子——属预期（穿墙线的设计目标）。OIT 模式下该阶段别名到 `oitTranslucent`，见第 1 条。
5. **新增注入对全局 shape outline 有 1 次 identity 比较开销**（非自定义直接 return），可忽略。

---

## 四、实机验证步骤

1. **前置冒烟**：不建 `vanilla-outline.txt`，启动进世界 → 日志 `vanilla-lines outline: mode=1`；ping 方块确认描边正常（验证 3 个 mixin 已应用、默认行为未变）。
2. **mode 4**：建文件写 `4`，重启 → 日志 `ping outline pipeline registered (depth test disabled, cull off, translucent)` + `mode=4`；隔墙 ping → 应透墙看到红色 3.75px 线框。
3. **水面**：目标与相机之间放水/玻璃 → mode 4 应仍可见（afterTerrain 在 translucent 之后）；mode 2 对照应被挡/变暗。
4. **连点同方块**：同一方块连续 ping 多次 → 只应看到 winner 的一份描边（S2 修复）；旧 ping 不叠加。
5. **日志**：`submitted=` 只在数量变化且 ≥5s 时出现；失败只 WARN 一次；无每帧刷屏。
6. **OIT 验证（S3-1）**：默认关闭即可；可选开启「改善透明度」再测 mode 4 —— 预期线仍可见（OIT 下 `afterTerrain` 别名到 `oitTranslucent`）。若开启后线消失，请记录该设置 + 截图/日志，作为“OIT 兼容性待查项”（不要当成分流丢失）。
7. **回归**：mode 3 仍 alias；HUD/轮盘/标记正常；F1/F3 无异常；正常退出无 crash。

---

## 五、结论

**可以实机测试（先 mode 1 冒烟）。** A/B/C/D/E/F 全部通过。**S3-1 已更正**：OIT 模式下 `afterTerrain` 被别名到 `oitTranslucent`（字节码确认，`allPhases` 含该阶段），因此预期仍会渲染；其可见性作为实机确认项（默认关闭 OIT；可选开启验证，若不可见再排查 OIT 阶段兼容性）。其余为 S4（静默降级、shadow 版本耦合、提交卫生），不影响本次实验结论。