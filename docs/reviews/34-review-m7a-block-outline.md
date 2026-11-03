# 34 · M7a-A3 复审：正式化方块描边（去实验化 / 失败禁用 / 路由诊断）

任务：task-47　审核人：3号　日期：2026-10-31
分支：`exp/vanilla-lines-m7a`（HEAD `9645bbc`）；对象：工作树未提交改动（删 `VanillaLinesBlockOutline`、新增 `BlockOutlinePass` / `OutlineRoutingState`、改 `FabricClient` / `SubmitNodeCollectionMixin`）。
验证：只读源码 + 全库 grep + javap 对照；**重跑** `bash tools/compile-check.sh`。未改 src，未跑 Gradle。

## 结论

**可以让用户直接实机测（默认开启、无需任何文件）。** 代码层面去实验化彻底、失败策略与 Lead 要求一致、路由诊断逻辑正确；发现 **2 个 S3 文档/文案问题**（FabricClient 残留旧注释、失败 WARN 文案仍说“回退内置线”）和若干 S4。

---

## 一、通过项

### A. 去实验化

| 检查 | 结果 | 证据 |
|---|---|---|
| `vanilla-outline` / `readMode` / `VanillaLinesBlockOutline` | **0** | src 全库 grep 命中 0；`git status` 显示该文件已删除（`D`） |
| `(experiment branch)` | **1 处残留（注释）** | `FabricClient.java:98`（见 S3-1） |
| `Files`/`FabricLoader`/`MAX_MODE`/`mode=` | 仅 `render/spike/OutlineExperiment.java` 内残留（按要求保留、**无任何调用点**） | 全库 grep：`OutlineExperiment` 仅 4 处命中且都在自身文件（`:63,95,133,135`）；`FabricClient` 已无 import/调用 |
| FabricClient 接线 | 只调用 `BlockOutlinePass.register()`（`:99`），无实验开关 | `git diff` 已删 `OutlineExperiment`/`VanillaLinesBlockOutline` import 与调用 |
| mixins.json | 仍 5 项（含 `SubmitNodeCollectionMixin`），未误删 | `pingforit.mixins.json:6-11` |

### B. 默认行为

- 无任何文件开关，`FabricClient:99` 无条件 `register()`；register 内即 `PingOutlineRenderType.create()`（`BlockOutlinePass:92`），成功后注册 COLLECT_SUBMITS（`:101`）。
- 提交恒 `afterTerrain=true`（`:186`）；颜色 `0xFF000000|outlineColor`（`:175-177`，alpha 255）；宽度 `LINE_WIDTH=3.75f`（`:63`）。
- targetType 仅 `block`/`entity_block`（`:145, 207-209`），与上游 `BlockModelOutlineRoute.acceptsForBlockRendering`（deferred `:45,48,54-57`）逐字一致。
- winner/维度/未加载/registryId/空形状/CollisionContext 全部保留（`:144-173`）；与上游 `BlockOutlineRenderer:101,110,114,116-118` 对应。

### C. 失败策略

- `create() == null → disabled = true; WARN; return`（不注册事件）（`:92-98`）✓。
- **无 RenderTypes fallback 残留**：`BlockOutlinePass` 不 import `RenderTypes`（全文件无），javadoc `:47-52` 明确说明“宁可缺失也不静默改变上游观感” ✓。
- 事件内异常：`catch (RuntimeException | LinkageError) → disabled=true + 一次 WARN`（`:195-203`）✓。
- `disabled` 为 per-session，join/leave 不重置（无 reset API）——按 Lead 要求记为已知限制。

### D. 路由诊断

- `OutlineRoutingState.routedSeen` 为 `volatile`（`:15`）；mixin 在 `submit(...)` 之后 `markRouted()`（`SubmitNodeCollectionMixin:61-63`）✓。
- `diagnoseRouting`（`BlockOutlinePass:225-237`）：`routingWarned || submitted<=0 || hasRouted()` 时直接返回；否则累计 `framesWithoutRouting`，到 120 帧 WARN 一次并置 `routingWarned`。
  - routed 后不再累计 ✓（提前 return）；
  - 同一帧内 `submitShapeOutline` 同步触发 mixin → `markRouted` 先于 `diagnoseRouting` 执行，**不会误报** ✓；
  - 只在“有提交且未 routed”的帧计数，无每帧分配（仅告警时拼字符串）✓。

### E. 日志

- 注册成功 INFO 1 条（`:102`）；失败 WARN（`:96`；另有 `PingOutlineRenderType:109`，见 S4-1）。
- 每帧提交降为 `LOGGER.debug`，且仅计数变化时打（`:212-219`）✓；路由告警仅一次（`:232-236`）。无 INFO 刷屏。

### F. 回归/清理

- `VanillaLinesBlockOutline` 删除后，全库无引用；`BlockOutlinePass` 无未用 import/字段（`FALLBACK_COLOR`/`TARGET_TYPE_*`/`ROUTING_WARN_FRAMES` 均使用）。
- `pushPose/popPose` 在 try/finally（`:179-190`）；`poseStack == null` 早退（`:117-121`）；`context == null` 守卫（`:113`）。
- `OutlineExperiment` 保留但**不再被引用**（无重复注册 COLLECT_SUBMITS/BEFORE_GIZMOS）✓。
- 编译：main 266 / client 93，0 error ✓（G）。

---

## 二、偏差项（含严重度）

### S3-1 · FabricClient 残留旧实验注释（A 项要求清零但仍有 3 行）
- `FabricClient.java:96-98` 仍写：“M7-prep E1/E2 spike: opt-in through <gameDir>/outline-experiment.txt / Without that file nothing is registered… / EXPERIMENT BRANCH: built-in line pipelines only”。
- 事实：现在**没有文件开关、无条件注册、用的是自定义管线**（`:99` + `BlockOutlinePass:86-103`），注释与行为相反，会误导 M7 后续维护者。
- 修复：改成“M7a block outline pass (custom no-depth pipeline + routing mixin)”一类描述；1 行级。

### S3-2 · 失败 WARN 文案仍说“回退内置线”，与 A3 策略相反
- `PingOutlineRenderType.java:109` `LOGGER.warn("ping outline pipeline unavailable, falling back to built-in lines ({})", …)`。
- 事实：调用方 `BlockOutlinePass:94-98` 在 null 时是 **禁用本 session**，**没有** built-in 回退（javadoc `:47-52` 明确拒绝回退）。文案会让日志读者以为线还画、只是换了类型。
- 修复：改为“...; block outline will be disabled”。1 行级。

### S4-1 · 同一次失败打两条 WARN
- `PingOutlineRenderType:109` 一条 + `BlockOutlinePass:96` 一条，同因同秒。建议保留调用方那条（含“disabled for this session”），把 create 内改成 DEBUG 或去掉。

### S4-2 · `OutlineExperiment` 保留但仍有文件开关/注册方法
- 文件按 Lead 要求保留（无调用点），但其内部仍含 `Files/FabricLoader` 与 `registerIfEnabled`（`:63-135`）。将来若有人误调用会重新引入实验路径。建议在 M7 收尾时删除，或在类注释标明“retired, do not call”。

### S4-3 · `registered = true` 在 create 之前
- `BlockOutlinePass:91` 先置位再 `create()`；若 create 抛出非受控 Error（非 RuntimeException/LinkageError/AssertionError），register 会抛出且 `registered` 已 true、无法重试。影响极小（init 已失败）。

### S4-4 · external block（Sable）未覆盖
- 只遍历 `Target.BlockTarget`（`:149`）；上游 `BlockOutlineState` 另有 `externalSpecs`。当前 port 无 Sable 运行时，属已知缺口（M9/M7 后补）。

### S4-5 · `logSubmitted` 在 DEBUG 下逐变化打
- `:212-219` 仅变化时打，但标记频繁增减时 DEBUG 可能多条；DEBUG 默认关闭，可接受。

---

## 三、运行风险

1. **mixin 未织入时线仍在 solid 桶**：`require=0` + identity 路由缺失 → 线仍画（depth test 关，仍穿墙），但半透明几何可能给它染色；`:225-237` 会在约 120 个“有提交但未 routed”的帧后 WARN 一次，且**不误报**（同帧 markRouted 先执行）。
2. **管线创建失败 = 无描边**：`create()==null` → disabled + 不注册；这是有意的“宁缺毋错”，实机若看不到线且有一条 WARN，即为该路径。
3. **`disabled` 永不复位**：一次失败/异常后本 session 不再画，直到重启；无 join/leave 重置（已知限制）。
4. **OIT（改善透明度）未覆盖**：mixin 投 `afterTerrain`，在 OIT 模式下该字段别名到 `oitTranslucent`（31 号报告已更正）；是否可见需实机确认（默认关闭）。
5. **mixin 应用失败 = 启动崩**：`required:true` + @Shadow 字段解析；先做启动冒烟。

---

## 四、实机验证步骤

1. **启动冒烟**：直接启动进世界（**不需要任何文件**）→ 日志应有 `block outline pass registered (custom pipeline, afterTerrain routing)`；若无该行但有 WARN，走失败路径（看第 5 步）。
2. **基本描边**：ping 一个方块 → 应出现 3.75px 红色线框（pingType 颜色），**隔墙可见**（depth test off）。
3. **半透明**：目标与相机之间放水/玻璃 → 线应仍可见（若 routing mixin 生效）；若线被染色/变暗，检查是否出现 `routing mixin inactive` WARN（120 帧后）。
4. **重复 ping**：同一方块连续 ping → 只保留 winner 一份描边（S2 修复）。
5. **失败路径（可选）**：临时制造管线失败不可行；改看日志——失败时应只有 WARN、无描边、无崩溃。
6. **OIT（可选）**：默认关闭；若开启「改善透明度」，记录 mode 4 线是否可见（未覆盖项，消失则记待查，不阻塞验收）。
7. **回归**：HUD/轮盘/标记正常；F1/F3 无异常；正常退出无 crash；日志无每帧 INFO 刷屏。

---

## 五、结论

**可以让用户实机测（默认开启、无需文件）。** A–G 全部通过（去实验化仅剩 2 处**注释/文案**未同步，S3）；D 的路由诊断逻辑正确且无误报；失败策略符合“宁缺毋错”的保真要求。建议顺手修 S3-1/S3-2 两处文案（各 1 行）后再交付用户，运行行为不受影响。已知限制：disabled 不重置、mixin 失败 WARN、OIT 未覆盖。

---

## 复审（2026-10-31，S3 修复后）

任务：task-49　审核人：3号　范围：仅核 task-48 的 S3-1/S3-2 两处文案修复（未提交工作树，HEAD `9645bbc`）。
方法：`git diff` + 文件 mtime/hash + grep + **重跑** `bash tools/compile-check.sh`。未改 src，未跑 Gradle。

### 通过

| # | 项 | 证据 |
|---|---|---|
| 1 | **S3-1 关闭**：FabricClient 旧实验注释清零 | `Select-String 'OutlineExperiment\|EXPERIMENT\|outline-experiment'` → **0 命中**；新注释 `FabricClient.java:95` = `// M7a: block outline pass (custom pipeline + afterTerrain routing).`，`:96` = `BlockOutlinePass.register();`；`// commands`（`:98`）归位到命令注册（`:99`）之前 |
| 2 | **S3-2 关闭**：失败 WARN 不再暗示回退 | `PingOutlineRenderType.java:109-110` = `custom outline pipeline unavailable ({}); block outline will be disabled`；全文件 grep `falling back` → **0 命中**；与 `BlockOutlinePass:94-98` 的 disabled 策略一致 |
| 3 | **无逻辑改动**（B） | 本轮仅 2 个文件被写：`FabricClient.java` mtime 15:40:26、`PingOutlineRenderType.java` 15:40:18；`BlockOutlinePass.java`（15:36:55, sha `046D5293AE`）、`OutlineRoutingState.java`（15:36:36, `8F2FA0BAD2`）、`SubmitNodeCollectionMixin.java`（15:36:36, `BBE4FD4E50`）本轮未动；`git diff` 中 FabricClient 仍是“注释替换 + 原 register 调用”，mixin diff 仍只有 `markRouted()`（`:60-63`），管线参数/BlockOutlinePass 未变 |
| 4 | **编译**（C） | `bash tools/compile-check.sh` → `main errors=0 classes=266 / client errors=0 classes=93` ✓ |

### 残留（均为 S4，窄范围未要求，不阻塞交付）

- **重复 WARN**：管线创建失败时仍会打两条（`PingOutlineRenderType:109` + `BlockOutlinePass:96`）——34 号 S4-1，未处理。
- `BlockOutlinePass:91` `registered = true` 在 create 之前；仅影响非受控 Error 的极端路径。
- external block（Sable）未覆盖；OIT 未覆盖（31 号已更正为“预期可见、待实机”）；`disabled` per-session 不重置——均为 34 号已记录项，本轮未变。

### 结论

**34 号报告的 S3-1/S3-2 均已关闭；本轮两处均为纯注释/文案改动，无逻辑、管线或 mixin 变化，编译 0/0。**
**可以交付用户实机测（默认开启、无需任何文件）**；实机观察点与已知限制沿用 34 号正文（隔墙可见、水/玻璃后可见性、同方块只留 winner、routing WARN、disabled 不重置、OIT 待验）。