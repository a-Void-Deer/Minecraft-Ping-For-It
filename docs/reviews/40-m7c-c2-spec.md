# 40 · M7c-C2 规格验证：entity_block 光晕（方案 A thread-local vs 方案 B 包装 collector）

> 分析：1号 repo-analyst｜2026-10-31｜**只读源码 + javap 反汇编，未运行游戏**
> 分支：`exp/vanilla-lines-m7a` @ `72a1147`（干净）；用户要求：**两案都可行则优先 A**，并把决定写进进度文档。
> 依据 jar：`D:\Gradle\caches\fabric-loom\26.3\minecraft-client-only.jar`（named jar，javap）。

## 0. 结论先行（4 条）

1. **两案都可行**：`BlockEntityRenderDispatcher.submit(...)` 把 `SubmitNodeCollector` 原样转交给 `BlockEntityRenderer.submit(...)`（javap 字节码 `:13-20`），因此既可以在 dispatch 前后设 thread-local 目标色（A），也可以替换 collector 参数（B）。
2. **推荐 A（thread-local），符合用户偏好**：改动最小（≈2 个 mixin 方法 + 1 个 tiny state 类），且能复用现有 `SubmitNodeCollectionMixin`；覆盖范围与 B 相同（都只能覆盖走到 `submitModel` 主方法的几何）。
3. **关键验证：所有 `submitModel` 重载都汇聚到 9 参主方法，`submitModelPart` 也走主方法**——`OrderedSubmitNodeCollector` 的 default 重载直接 `invokeinterface submitModel(...I)V`（javap `:14`），`submitModelPart` 8 参实现构造 `Model.Simple` 后同样调 9 参主方法（javap `:34`）。所以 A 只需在**主方法**的 `outlineColor` 变量槽（**slot 9**，`iload 9`）上 `@ModifyVariable(argsOnly=true)` 即可覆盖 CAB/模型部件两类 BER。
4. **必须解决的状态安全点**：`BlockEntityRenderDispatcher.submit` 的异常路径会构造 `ReportedException` 抛出（javap `:28-57`，客户端崩溃），`@Inject(RETURN)` 不会执行 → A 需要「**save/restore**（可重入安全）+ **per-frame reset**（复用 `LevelExtractorMixin:30-48`）」双保险，而不是只靠 RETURN。

## 1. 方案 A：thread-local 目标色（详细验证）

### 1.1 注入点与签名

| 项 | javap 结果 | 用途 |
|---|---|---|
| `BlockEntityRenderDispatcher.submit` | `public <S extends BlockEntityRenderState> void submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)` | **A 的 HEAD/RETURN 注入点**（`:0` 起 `getRenderer` → `:13-20` 调 `BlockEntityRenderer.submit(S,PoseStack,SubmitNodeCollector,CameraRenderState)`） |
| `BlockEntityRenderState` 可用字段 | `public BlockPos blockPos`、`public BlockEntityType<?> blockEntityType`、`public int lightCoords`、`private BlockState blockState`（**无 getter**） | 用 `blockPos` 匹配 marker；BlockState 走 `level.getBlockState(state.blockPos)` 取（policy 判定需要） |
| `SubmitNodeCollection.submitModel` 主方法 | `<S> void submitModel(Model<? super S>, S, PoseStack, RenderType, int, int, int, UvMapping, int)`（最后 int = outlineColor） | **A 的 `@ModifyVariable` 目标**；实例方法槽位：`this=0, Model=1, S=2, PoseStack=3, RenderType=4, int=5, int=6, int=7, UvMapping=8, outlineColor=9`；字节码 `100: iload 9 / ifeq 148` 直接证明 |
| 默认重载 | `submitModel(...,7 args)`、`(...,Identifier,...)`、`(SpriteId/SpriteGetter,...)` 均 `invokeinterface submitModel(...I)V` | 全部汇聚 → A 覆盖所有模型提交 |
| `submitModelPart` | 6 参 → 8 参 → **9 参 `submitModel`**（构造 `Model.Simple`，outlineColor 取 8 参的最后一个 int） | **模型部件类 BER 也被 A 覆盖** |

### 1.2 状态安全

| 场景 | 行为 | 处理 |
|---|---|---|
| 正常 dispatch | HEAD 设置、RETURN 清除 | save/restore：`previous = ThreadLocal.get()`；RETURN 恢复 previous（嵌套/重入安全） |
| dispatch 内抛异常 | vanilla 转成 `ReportedException` → 客户端崩溃 | 崩溃场景下 thread-local 无意义；但**每帧 `LevelExtractorMixin` HEAD 重置**可防「未崩溃但漏清」路径 |
| 同帧后续实体/方块渲染 | 若未清理，会污染其它 `submitModel` | per-frame reset（`LevelExtractorMixin` 已在 M7b 每帧调用，可顺势清空） |

### 1.3 颜色来源（每 dispatch 解析一次）

1. 用 `blockPos` 在**当前维度的 winner 快照**里查 `entity_block` 目标（`Target.BlockTarget` + `targetTypeId == "entity_block"`）。
2. 用 `ClientConfig.HANDLER.getConfig().getBlockDisplayPolicy().shouldUseNativeGlow("entity_block", level.getBlockState(pos))` 判定（与上游 `BlockDisplayPolicy.java:60-71` 一致）。
3. 颜色 = `0xFF000000 | pingType.outlineColor()`，未知类型回落白（与 M7a/M7b 同源）。

### 1.4 覆盖缺口（与上游一致）

| 几何 | 是否被 A 覆盖 | 说明 |
|---|---|---|
| `submitModel` / `submitModelPart`（绝大多数 BER） | ✅ | 走主方法 |
| `submitText` / `submitNameTag`（告示牌文字、悬空文字） | ❌ | 无 outlineColor 概念；上游 `OutlineOnlyBufferSource` 也只接受 QUADS 贴图几何，文字本来不发光 |
| `submitCustomGeometry`（光束/特殊渲染） | ❌ | 同上，上游同样不覆盖 |
| `submitItem` / `submitBlockModel`（BER 内自绘） | ❌ | 上游同限制（`OutlineOnlyBufferSource` 只映射模型贴图类型） |

> 结论：A 的覆盖边界与上游「outline-only 缓冲」的行为一致，不是移植回归。

## 2. 方案 B：包装 `SubmitNodeCollector`（验证）

| 项 | javap 结果 |
|---|---|
| 接口方法总数 | `OrderedSubmitNodeCollector` 共 **23** 个方法（16 抽象 + 7 default）；`SubmitNodeCollector` 另有 `order(int)` → **wrapper 需实现 16 抽象 + `order(int)` = 17 个委托**；7 个 default 继承后会自动调到 wrapper 的 `submitModel` 主方法 |
| 替换点 | `BlockEntityRenderDispatcher.submit` 的第 3 参（实例方法槽位：`this=0, S=1, PoseStack=2, collector=3, CameraRenderState=4`）→ `@ModifyVariable(method="submit", at=@At("HEAD"), argsOnly=true, index=3)` |
| wrapper vs `Proxy` | 手写委托类：编译期检查、无反射开销、可读；`Proxy`：方法名/重载识别易错、每次调用反射、不支持 default 语义 → **选手写** |
| 作用域安全 | collector 对象只在本次 dispatch 内有效 → 天然异常安全（无需清理） |

## 3. 对比与结论

| 维度 | A thread-local | B 包装 collector |
|---|---|---|
| 代码量 | ≈60 行（1 state 类 + 2 处注入） | ≈150 行（17 个委托方法 + 1 处 `@ModifyVariable`） |
| 覆盖 | `submitModel`/`submitModelPart` → 主方法，其余不覆盖 | 相同（wrapper 仍只改 `submitModel`） |
| 异常安全 | 需 save/restore + per-frame reset | 天然安全 |
| 维护成本 | 低；复用现有 `SubmitNodeCollectionMixin` | 中；MC 新增抽象方法会编译报错（可接受） |
| 与现有 mixin 冲突 | 无（新增方法到已有 mixin 类） | 新增一个 wrapper 类 + 新 mixin 方法 |
| 可读性 | 中（隐式 thread-local，需注释清楚） | 高（显式） |

**决定：A 优先（用户要求「都能实现则优先 A」），B 作为备选**。A 的清理策略：**save/restore（可重入）+ `LevelExtractorMixin` 每帧 HEAD 重置**；RETURN 注入只作为正常路径的提前恢复，不依赖它处理异常。

## 4. C2 实现清单（方案 A）

| # | 文件/改动 | 内容 |
|---|---|---|
| 1 | 新增 `client/outline/EntityBlockGlowState.java` | `ThreadLocal<Integer> current`；`begin/restore/currentColorOr(int fallback)`；`resetFrame()` |
| 2 | 新增 `mixin/BlockEntityRenderDispatcherMixin.java` | `@Inject(submit, HEAD)`：按 `state.blockPos` 查 winner + policy → `begin(color)`；`@Inject(submit, RETURN)`：`restore`；**`@Inject` 不加 require=1**（`require=0` + 一次性 WARN 诊断，同 M7b 风格） |
| 3 | 修改 `mixin/SubmitNodeCollectionMixin.java` | 新增 `@ModifyVariable(method="submitModel", at=@At("HEAD"), argsOnly=true, index=9)`：thread-local 有值时替换 outlineColor |
| 4 | 修改 `mixin/LevelExtractorMixin.java` | 每帧 HEAD 调 `EntityBlockGlowState.resetFrame()`（与 `EntityOutlineState.prepare` 同一处） |
| 5 | `BlockModelGlowPass`/`BlockOutlinePass` | 无需改动（C1 已抑制 `block`；C2 额外抑制 `entity_block` 的线框） |
| 6 | 日志 | 首次激活 INFO 一次；失败 WARN 一次；无 per-frame |

## 5. 验收标准

| 类别 | 标准 |
|---|---|
| 编译 | `tools/compile-check` main/client **0 error** |
| 视觉 | 箱子/告示牌等 `entity_block` 出现**与 ping 类型同色的光晕**（形状跟随真实 BER，不是包围盒）；穿墙可见；**告示牌文字不发光**（与上游一致） |
| 行为 | 同一方块只画一条（winner）；换维度/过期清空；不与 C1 普通方块光晕或 M7a 线框双画 |
| 回归 | C1 普通方块、M7a 线框、M7b 实体描边均不受影响；无每帧日志 |
| 失败路径 | mixin 未织入：无 crash + 一次 WARN，`entity_block` 回退线框 |

## 6. 风险

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | thread-local 泄漏（异常/嵌套） | 其它几何被染色 | save/restore + 每帧 reset + RETURN |
| R2 | 部分 BER 用 `submitCustomGeometry`/`submitText` | 该部分不发光（与上游一致） | 验收里明确「文字不发光是预期」 |
| R3 | `BlockEntityRenderState.blockState` 私有 | policy 判定需从 level 取 BlockState | 用 `level.getBlockState(state.blockPos)`（同帧一致） |
| R4 | 与 C1 抑制逻辑重叠 | 同一方块双画 | C2 在 `BlockOutlinePass` 增加 `entity_block` 抑制分支 |
| R5 | `@ModifyVariable index=9` 依赖参数槽位 | 版本升级后错位 | `require=0` + 诊断；编译期/运行期双重告警 |

## 7. 证据索引

| 主题 | 位置 |
|---|---|
| BER dispatch 链 | `BlockEntityRenderDispatcher.submit` javap 字节码 `:0-25`（异常 `:28-57`） |
| BER state 字段 | `BlockEntityRenderState` javap（`blockPos`/`blockEntityType`/`lightCoords`/私有 `blockState`） |
| submitModel 主方法槽位 | `SubmitNodeCollection.submitModel` 字节码 `100: iload 9`、`:105-148` |
| 重载汇聚 | `OrderedSubmitNodeCollector` default 重载 `invokeinterface submitModel(...I)V`；`submitModelPart` 8 参 `:34` |
| 界面方法数 | `OrderedSubmitNodeCollector` javap（23 方法：16 抽象 + 7 default）；`SubmitNodeCollector.order(int)` |
| 现有可复用 mixin | `src/client/java/nx/pingwheel/common/mixin/SubmitNodeCollectionMixin.java:45-63`；`LevelExtractorMixin.java:30-48` |
| 上游语义 | `docs/reviews/38-m7c-spec.md`（模型光晕判定链/覆盖边界） |
