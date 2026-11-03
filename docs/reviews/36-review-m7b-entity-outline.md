# 36 · M7b-A3 复审：实体描边（EntityRenderer/LevelExtractor mixin + 选择/颜色/失败策略）

任务：task-53　审核人：3号　日期：2026-10-31
分支：`exp/vanilla-lines-m7a`（HEAD `8bc7afb`）；对象：工作树未提交的 5 个新文件 + `pingforit.mixins.json`（client 5→7）。
验证：只读源码 + 全库 grep + javap（MC 26.3 字节码/字段）；**重跑** `bash tools/compile-check.sh`。未改 src，未跑 Gradle。

## 结论

**可以让用户实机测。** 两个 mixin 的目标/描述符/注入点合法，选择与颜色逻辑与上游一致，非 marker 实体完全不受影响；发现 **1 个 S3 诊断误报风险**（“hook 未织入”告警在“有描边但 60 帧无可见实体”时会误报）和若干 S4。

---

## 一、通过项

### A. Mixin 合法性

| 检查 | 结论 | 证据 |
|---|---|---|
| `EntityRenderer.extractRenderState` 目标 | ✅ | javap：`public void extractRenderState(T, S, float)`，擦除为 `(Entity, EntityRenderState, float)V`；handler 参数 `(Entity, EntityRenderState, float, CallbackInfo)`（`EntityRendererMixin:35-40`）与之一致；类为 `public abstract`（non-final） |
| TAIL 位置 | ✅ | 字节码：方法内唯一 RETURN 在 offset 804；vanilla 在 offset **791** 写 `state.outlineColor = glowing ? opaque(teamColor) : 0`，TAIL 之后执行 → 我们的写覆盖 vanilla ✓ |
| 写入时机（finalize 不会覆盖） | ✅ | `EntityRenderer.createRenderState(T,float)` 为 final：先 `extractRenderState` 再 `finalizeRenderState`；在已检查的 `EntityRenderer`/`LivingEntityRenderer`/`EntityRenderState` 中只有 `EntityRenderer` 一处 `putfield outlineColor`，finalize 不写 → 我们的值存活 ✓ |
| `LevelExtractor.extract` 目标 | ✅ | javap：`public void extract(DeltaTracker, Camera, float)` ✓；handler 参数一致（`LevelExtractorMixin:31-36`）；`GameRenderer` 只调用一次（字节码 offset 101）✓ |
| HEAD 早于实体提取 | ✅ | `extract` HEAD 在 `extractVisibleEntities` 之前；后者经 EntityRenderDispatcher → `EntityRenderer.createRenderState` → `extractRenderState`（javap） |
| require=0 与 defaultRequire=1 | ✅ | Mixin `InjectionInfo.parseRequirements` 中 `require > -1` 覆盖 defaultRequire（同 28/31 号已核）；两个注入点缺失不硬崩 |
| 诊断标志 | ✅/⚠️ | `EntityOutlineDiagnostics.extractionHookApplied` 为 volatile（`:15`），每次成功 extract 都 mark（`EntityRendererMixin:45`）；`diagnoseHook` 在“有描边且 hook 未运行”时累计 60 次后 WARN 一次（`EntityOutlineState:114-125`），不 disabled ✓；**但有误报场景，见 S3-1** |
| mixins.json | ✅ | 7 项、`package` / `JAVA_25` / `required:true` 一致（`:6-17`） |

### B. 选择与身份

- winner 投影：`store.visibleWinnersInDimension(dimension).values()`（`LevelExtractorMixin:46-48` → `EntityOutlineState:53-54`）✓。
- 过滤：`Target.EntityTarget`（`EntityOutlineSelection:53`）、维度（`:57`）、targetType `entity`/`dropped_item`（`:81-83`）。当前 `TargetTypeCatalog` 只有 `dropped_item/entity/entity_block/block`（main `:46-73`），`entity_block/block` 走 `TargetKey.BlockKey` 本来就不会进来 → 与上游（deferred `EntityOutlineSelection:67-76`，**无 targetType 白名单**、靠 `EntityKey`）等价 ✓。
- 同 locator 取最大 markerId（`:61-65`）= 上游“按升序处理、后写覆盖”（deferred `:61-86` 用 LinkedHashMap）✓。
- 颜色 `0xFF000000|outlineColor`、未知白（`:85-89`）= 上游 deferred `:79-81` 一致 ✓。
- locator 一致性：捕获路径（`MinecraftEntityTargetAdapter.capture:31-45`）与渲染路径（`EntityRendererMixin:50`）都走 `locatorFor/canonicalEntity`：`EnderDragonPart → parentMob`、`ExperienceOrb → runtimeId(id)`、其余 UUID（`:50-74`）→ 两侧同一函数，不会错配 ✓。
- 时序：prepare 在 `extract` HEAD、渲染线程；`outlines` 为 volatile 整体替换（`EntityOutlineState:26,62`）→ 无半构建快照/竞态 ✓。

### C. 覆盖语义

- 只对“有 marker 的实体”写 `outlineColor`（`EntityRendererMixin:55-59`，`color == null` 不改）；非 marker 实体的 vanilla `glowing` / 队伍色（extractRenderState offset 764-791 计算）**完全不变** ✓。
- 同实体多 marker：最大 markerId 的颜色（`EntityOutlineSelection:61-78`）✓。
- 原生 outline 通道：`EntityRenderState.appearsGlowing() = outlineColor != 0`（javap），`LevelRenderState.shouldShowEntityOutlines = !camera.isPanoramicMode() && playerRenderState.hasPlayer`（LevelExtractor 静态方法）→ 正常游玩为 true；全景/截图模式无描边（预期）✓。
- dropped item / 玩家 / 龙部件：命中路径与渲染路径 locator 同源（见 B）→ 理论上都能匹配；实机见 §四。

### D. 失败/默认

- `prepare` 异常：`disabled=true + outlines=Map.of() + WARN 一次`（`EntityOutlineState:74-83`）✓；不抛进渲染循环。
- mixin 未织入：60 次 prepare 后 WARN 一次、**不 disabled**（`:114-125`），符合“只诊断不中断”。
- 无 marker：`select` 返回 `Map.of()`，`next.equals(outlines)` 早退（`:58-60`），**无日志** ✓（仍有每帧 map 分配，见 S4-3）。
- 不改 M7a 方块描边：只写 `EntityRenderState.outlineColor`（`EntityRendererMixin:58`），与 BlockOutlinePass/SubmitNodeCollection 路由无交集 ✓。

### E. 编译

`bash tools/compile-check.sh` → `main errors=0 classes=266 / client errors=0 classes=98` ✓。

---

## 二、偏差项（含严重度）

### S3-1 · “hook inactive”告警存在误报场景
- `EntityRenderer.extractRenderState` 只为 **可见实体** 调用（`LevelExtractor.extractVisibleEntities` 经 frustum 筛选）；而 `diagnoseHook` 只检查“有描边 pending 且从未见 hook”（`EntityOutlineState:114-125`）。
- 若 60 帧内“有描边但屏幕上没有任何可见实体”（例如 ping 的实体在身后、世界里暂时无其他可见实体、或极端低帧率下 1 秒内无实体提取），会误报 “EntityRenderer.extractRenderState hook inactive”，实际 mixin 已生效。
- 影响：仅日志误导，无功能影响；且 60 帧 ≈1s，误报窗口短。建议：告警文案加 “possible: hook missing OR no visible entities in 60 frames”，或改为“连续 60 帧有描边且 hasOutlines 但世界有可见实体”才报（需额外判断，成本略高）。

### S4-1 · targetType 白名单比上游窄
- 上游 deferred `EntityOutlineSelection` 不检查 targetType，只认 `TargetKey.EntityKey`；本地只接受 `entity`/`dropped_item`（`:81-83`）。当前 catalog 只有 4 类，等价；若未来新增 entity-key 类型（如 `player`），本地会漏描边。建议注释说明“与 catalog 同步维护”。

### S4-2 · 只在 base `extractRenderState` 注入
- 子类若 override 且**不调用 super**，该实体不会被写 outlineColor。已核 `LivingEntityRenderer` / `ItemEntityRenderer` / `ThrownItemRenderer` 均 `invokespecial EntityRenderer.extractRenderState`；未穷举全部 renderer（约百个），建议实机抽测常见实体。

### S4-3 · “无 marker 零开销”不严格
- 每帧仍会 `visibleWinnersInDimension` + `EntityOutlineSelection.select` 建 HashMap（`EntityOutlineState:53-54`，`EntityOutlineSelection:46,72`），即使结果为空；无日志、无渲染影响。可加 `if (store 为空) 快路径`（非必须）。

### S4-4 · `dimensionId` 在等值早退时不更新
- `:58-60` 先 `next.equals(outlines)` 返回，`dimensionId` 保持旧值；`dimensionId()` 目前无调用者，属死 API/一致性小问题。

### S4-5 · per-session 状态不重置
- `disabled/failureLogged/hookWarned/activationLogged` 均静态、join/leave 不重置（与 BlockOutlinePass 的已知限制一致）；不影响单次会话。

### S4-6 · 异常类型只到 RuntimeException/LinkageError
- `prepare` 与两个 mixin 的 catch 未含 `AssertionError`（与 M7a 的 pipeline 创建不同）；理论上不会从这里抛出 AssertionError，记录为一致性建议。

---

## 三、运行风险

1. **原生 glow 通道的视觉差异**：上游是自定义 silhouette 渲染；本实现借用原生 entity outline（outlineColor → appearsGlowing）。颜色/透明度/是否覆盖装备层/是否真正穿墙需实机确认（预期原生 glow 穿墙、颜色为该 ARGB）。
2. **mixin 未织入**：无描边，60 次 prepare 后一条 WARN；不会崩。
3. **诊断误报**（S3-1）：看到 “hook inactive” 先确认当时屏幕上是否有可见实体。
4. **shouldShowEntityOutlines**：全景/截图模式不渲染（预期）；非正常 HUD 状态（F1 不影响世界渲染）。
5. **@Shadow/字段/描述符版本耦合**：26.3 小版本改动 `extractRenderState` 签名会在启动阶段崩（required:true），与 M7a 的 mixin 同风险。

---

## 四、实机验证步骤

1. **启动冒烟**：进世界，ping 一个生物 → 应出现该生物轮廓（pingType 颜色），日志首条非空快照时 INFO `entity outline pass active`。
2. **颜色**：分别用不同 pingType（attention/danger/go_to）ping 同一实体 → 轮廓颜色应随 pingType 变化；未知类型回退白色。
3. **穿墙**：隔墙 ping 生物/玩家 → 轮廓应可见（原生 glow 预期穿墙）；与 M7a 方块描边同时存在不互相影响。
4. **非 marker 实体不变**：对另一个未 ping 的生物观察 vanilla glowing（光谱箭）/队伍色 → 应与之前完全一致。
5. **玩家/掉落物**：ping 玩家（多人）与掉落物（item entity）→ 轮廓出现；龙类部件（若可）命中龙身任意部位 → 整个龙轮廓（locator 归一）。
6. **多 ping 同实体**：连续 ping 同一实体 → 颜色应为最后一次（larger markerId）。
7. **诊断**：若 1 秒后出现 `hook inactive` WARN，先看当时屏幕是否有可见实体（S3-1 误报）；若确实无轮廓且无 WARN，查 mixin 是否应用。
8. **回归**：M7a 方块描边、HUD、轮盘正常；退出无 crash；日志无每帧刷屏。

---

## 五、结论

**可以让用户实机测（无需任何文件；mixin 生效即开启）。** A–E 全部通过：mixin 目标/描述符/TAIL 时机正确（TAIL 在 vanilla 写 outlineColor 之后、finalize 之前），选择/颜色/winner/locator 与上游等价，非 marker 实体不受影响，失败策略符合“只诊断不中断”。需要实机确认的是原生 glow 通道的观感（颜色/穿墙/装备层）与 S3-1 的误报文案；其余为 S4。

---

## 复审（2026-10-31，S3-1 修复后）

任务：task-55　审核人：3号　范围：仅核 task-54 对 `EntityOutlineState` 的 S3-1 修复（未提交工作树，HEAD `8bc7afb`）。
方法：文件 mtime/hash 对比 + 源码逐行 + javap + **重跑** `bash tools/compile-check.sh`。未改 src，未跑 Gradle。

### 通过

| # | 项 | 证据 |
|---|---|---|
| 1 | **诊断前置 `hasRenderableEntities()`** | `EntityOutlineState.java:125-127` 在累计前先判断；实现 `:141-149`：`Minecraft game = Minecraft.getInstance(); game == null || game.level == null → false; return game.level.entitiesForRendering().iterator().hasNext();`。javap 确认 `ClientLevel.entitiesForRendering()` 返回 `Iterable<Entity>`，`iterator().hasNext()` **不遍历**；只在“有描边、hook 未观测、且此前未告警”的帧调用，非热路径 ✓ |
| 2 | **WARN 文案改为二义** | `:133-136` = `entity-outline extraction hook not observed after {} frames (mixin inactive, or the outlined entities were never extracted); entity outlines may be missing`，不再断言“hook inactive” ✓ |
| 3 | **只 WARN 一次、不禁用** | `:117-119` `hookWarned || next.isEmpty() || extractionHookApplied()` 早退；`:131-137` 置 `hookWarned=true` 后 WARN；全函数无 `disabled = true` ✓ |
| 4 | **catch 增加 AssertionError** | `:76` `catch (RuntimeException | LinkageError | AssertionError failure)` ✓（与 M7a 的 pipeline 失败策略一致） |

### 无其他改动（B）

- 本轮只有 `EntityOutlineState.java` 的 mtime 变化（16:35:54, sha `E7950C91E7ED`）；其余文件 mtime 停在 16:24:59/16:25:09：`EntityOutlineSelection`（`F58C5A6D9868`）、`EntityOutlineDiagnostics`（`FC81AA4AAA80`）、`EntityRendererMixin`（`A849B81A00A8`）、`LevelExtractorMixin`（`EBE46C013548`）、`pingforit.mixins.json`（`9A2511C0F5C0`，`git diff --stat` 仍只有那 3+/1- 的 7 项清单）→ hook、选择逻辑、mixins 配置均未变 ✓。

### 编译（C）

`bash tools/compile-check.sh` → `main errors=0 classes=266 / client errors=0 classes=98` ✓。

### 残留（S4，不阻塞）

- `entitiesForRendering()` 是**加载中的实体**，不是视锥内可见实体：若关卡里有实体但全部在相机后（或极端低帧率窗口），60 帧后仍可能出该 WARN；但文案已显式允许 “or the outlined entities were never extracted”，从“误报”降级为“二义诊断”，可接受。
- `hasRenderableEntities` 每次调用新建一个 iterator（`hasNext` 即弃），仅在 pending 诊断路径触发，开销可忽略。
- 其余 S4 沿用 36 号正文（targetType 白名单、base-only 注入、无 marker 的 map 分配、dimensionId、per-session 状态）。

### 结论

**36 号报告的 S3-1 已关闭（降级为 S4 已知二义场景）；本轮仅一个文件、无 hook/选择/管线改动，编译 0/0。可以交付用户实机测**（默认开启、无需任何文件）；实机观察点沿用 36 号正文（生物/玩家/掉落物颜色、穿墙、非 marker 不变、同实体取最后一次、hook WARN 先确认可见实体）。