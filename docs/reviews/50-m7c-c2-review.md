# 50 · M7c-C2 复审：entity_block 真实方块实体光晕（方案 A）

任务：M7c-C2（Lead 直派）　审核人：3号　日期：2026-11-01
对象：新增 `EntityBlockGlowState` / `BlockEntityRenderDispatcherMixin`，改 `SubmitNodeCollectionMixin` / `LevelExtractorMixin` / `BlockOutlinePass` / mixins.json（未提交）。
验证：只读源码 + javap（`SubmitNodeCollection.submitModel` / `BlockEntityRenderDispatcher.submit` / `LevelRenderer.submitFeatures` / Mixin `ModifyVariable` / Fabric `LevelRendererMixin`）+ **重跑** `bash tools/compile-check.sh`（`main 266 / client 124，0 error`）。未跑 Gradle。

## 结论

**打回。** 线程本地生命周期、`@ModifyVariable index=9`、首帧顺序、颜色与编译都通过；但**抑制谓词不是光晕侧的真实谓词**：全局 `isHookApplied` 在 renderer 为 null/未 submitModel 时也会置位，且 `require=0` 的 model hook 失败没有诊断，会出现「线框被抑制、光晕没画、也没有 WARN」的可见缺失。

---

## 一、阻断项

### B1 · 抑制条件缺少 per-target / per-model 证据（S1/S2）
- 位置：`BlockOutlinePass.java:192-198`（抑制分支）＋ `EntityBlockGlowState.java:104-115`（`markHookApplied/isHookApplied`）＋ `BlockEntityRenderDispatcherMixin.java:55-83`（HEAD）。
- 现象：HEAD 在任何 `BlockEntityRenderDispatcher.submit` 上先 `markHookApplied()`，此时**还没检查 `getRenderer` 是否为 null**（vanilla 该方法在 `:12` 有 renderer==null 的早退）。抑制分支只检查 `targetTypeId==entity_block && isHookApplied() && !isDisabled() && policy.shouldUseNativeGlow(...)`，并不确认**这个 target 真的走到了 `submitModel`**。
- 会导致「线框被抑制、光晕没画」的具体路径：
  - block entity 的 `getRenderer` 为 null（HEAD 已置 hookApplied，早退 restore；没有 model submit）；
  - renderer 只调用 `submitText`/`submitCustomGeometry`/`submitItem`（C2 只改 `submitModel`，这些没有光晕）；
  - 目标 block entity 本帧没有被 `submitBlockEntities` 提交（例如可见性/状态被跳过），但其它 block entity 已把全局 `hookApplied` 置 true；
  - `@ModifyVariable` 没织入/槽位变化（见 B2），dispatch hook 仍置全局 true。
- 同一条 `targetTypeId/policy/state` 谓词是满足的，但**光晕侧真正的存在条件是“本次 dispatch 的 model submit 被执行”**，这个证据没有进入抑制条件；`diagnoseDispatchHook`（`:153-173`）也只检查 `hookApplied`，因此上述路径不会告警。
- 怎么改（最小且可验证）：
  1. `EntityBlockGlowState` 增加 per-frame `Set<Long/BlockPos> glowingPositions`（或 per-thread `HashSet`）；`resetFrame()` 清空；
  2. `begin` 记录当前 `BlockPos`；`@ModifyVariable` 里 `currentColorOr` 发现 `CURRENT != null` 时，把该 pos 加入 `glowingPositions`，并置 `modelHookApplied=true`；
  3. `BlockOutlinePass` 抑制改为 `modelHookApplied && !isDisabled() && isGlowing(pos)`（policy 仍可保留）；这样 renderer==null / 只画文字 / 未被 dispatch 的目标会回到线框；
  4. `diagnoseDispatchHook` 增加“有候选但 `modelHookApplied==false`”的一次性 WARN，防止 model hook 静默失效。
- 这一改同时把 C2 的抑制从“全局 hook 猜测”变成“本帧本目标的真实提交”，与上游 outline-only 覆盖边界一致。

### B2 · `@ModifyVariable require=0` 失败静默，且没有 fallback 诊断（S1/S2）
- 位置：`SubmitNodeCollectionMixin.java:83-85`；`EntityBlockGlowState.java:153-173`。
- 现象：dispatch HEAD 已经 `markHookApplied()`；如果 `submitModel` 的 `@ModifyVariable` 因槽位/方法变化未织入（`require=0`），`hookApplied` 仍为 true，`BlockOutlinePass` 抑制线框，但没有任何模型被染色、也没有一条 WARN——违反“mixin 未织入 → entity_block 回退线框 + 一次 WARN”。
- 怎么改：`currentColorOr` 在 `CURRENT != null` 时置 `modelHookApplied=true`；`isHookApplied` 拆成 `dispatchHookApplied` 与 `modelHookApplied`；抑制必须要求两者；诊断把“dispatch 已应用但 model 未应用”作为独立 WARN。若还想覆盖 RETURN 缺失，可在 HEAD 记录 `beginCount`、RETURN/帧末比较 `restoreCount`，不匹配时一次性 WARN（可选）。

---

## 二、非阻断

1. **每 dispatch 的 map 分配（性能 S3）**：`BlockEntityRenderDispatcherMixin:163` 每次 BER dispatch 调 `store.visibleWinnersInDimension(dimensionId)`；该方法在 `ClientMarkerStore.java:573-596` 里 `stream().filter().sorted().toList()` + `new LinkedHashMap` + `unmodifiableMap`，是**每个 block entity 每次**分配/排序，不是每帧一次。繁忙世界（大量 block entity）会明显增加分配与 CPU；建议在 `LevelExtractorMixin.resetFrame` 时缓存当前 `(store,dimension)` 的 winner 快照，`findWinner` 复用；`diagnoseDispatchHook` 也复用。
2. **`dimension.identifier().toString()` 每 dispatch**（`:119`）也分配字符串；可与上面缓存一起处理。
3. **异常路径**：`@Inject(at=RETURN)` 只覆盖自然返回（Mixin `BeforeReturn` 源码：所有 RETURN，不含 throw）；`BlockEntityRenderDispatcher.submit` 的异常会转 `ReportedException`（通常客户端崩溃），此时靠下一帧 `resetFrame()` 兜底；若异常被外层捕获且渲染继续，当前帧后续 model 可能短暂继承颜色——属崩溃边界，记录即可。
4. **`resetFrame` 依赖 `LevelExtractorMixin`**：@Inject HEAD 未织入时每帧重置不存在；正常 RETURN restore 仍工作，仅异常路径可能跨帧泄漏；与 M7b 同一风险，记录。
5. **颜色**：C2 `0xFF000000 | pingType.outlineColor()` 与 M7a `BlockOutlinePass:175-177`、M7b `EntityOutlineSelection` 同源；fallback `0xFFFFFFFF` 相同；告示牌文字不发光与上游一致（非回归）。
6. **`PingTypeCatalog.builtIn()`** 是 mixin 类静态 final，不是每帧重建；`SAVED` 是 ThreadLocal 复用 ArrayList，无每帧新建（首次创建后 clear/复用）；这两项通过。

---

## 三、已核对通过的关键项

| 项 | 证据 |
|---|---|
| `submitModel` slot 9 | `SubmitNodeCollection.submitModel` 签名（实例方法 this=0, Model=1, S=2, PoseStack=3, RenderType=4, int=5/6/7, UvMapping=8, outlineColor=9）；字节码 `100: iload 9`、`137: iload 9` 作为 outline Submit 的颜色，确认 slot 9 就是 outlineColor。 |
| `@ModifyVariable argsOnly/index` | Mixin `ModifyVariable` javadoc：`index` 是 **absolute LVT index**，`argsOnly=true` 只限制为方法参数；`SubmitNodeCollection` 只有这一个 `submitModel`，index=9 正确；`require=0` 合法。 |
| 线程本地生命周期 | `begin` 推入 `SAVED` 并设置/移除 `CURRENT`（`:63-72`）；`restore` 弹栈恢复（`:75-89`）；`@Inject(at=RETURN)` 覆盖所有自然 RETURN（`BlockEntityRenderDispatcher.submit` 的 renderer==null 早退也被覆盖）；异常路径不 RETURN，靠 `resetFrame()`（`LevelExtractorMixin:40`，每帧 HEAD，SAVED.clear）兜底。 |
| `currentColorOr` 非 dispatch 期 | `CURRENT.remove`/restore 后为 null，返回 fallback；nested dispatch 由 SAVED 栈隔离。 |
| 首帧顺序 | `LevelRenderer.submitFeatures` 字节码在 offset 31 调 `submitBlockEntities`，之后才到 RETURN；Fabric `LevelRendererMixin.java:90-92` 把 `COLLECT_SUBMITS` 注入在 `submitFeatures` **RETURN**。因此 BER 的 HEAD/model submit 先于 `BlockOutlinePass`；**首帧是 1 次 glow（单画），不是 wireframe+glow 双画**。若目标没有 model 提交，则是 B1 的 0 画，不是双画。 |
| 失败回退（dispatch 侧） | `require=0` dispatch 未织入时 `hookApplied=false` → `BlockOutlinePass` 不抑制，线框保留；`diagnoseDispatchHook` 在有 `entity_block` 候选且 block entity 存在时累计 120 帧后 WARN 一次。**model hook 的失败由 B2 补。** |
| 颜色一致性 | C2、M7a、M7b 都是 `0xFF000000 \| pingType.outlineColor()`，fallback 0xFFFFFFFF；policy 用同一 `shouldUseNativeGlow("entity_block", state)`。 |
| 编译 | `main 266 / client 124，0 error`。 |

---

## 四、保真差异清单

| 项 | 与 upstream / M7a 的差异 | 判定 |
|---|---|---|
| entity_block 几何 | 只给 `submitModel` 的模型几何 outlineColor；`submitText`/`submitCustomGeometry`/`submitItem` 不发光 | 与 upstream outline-only 覆盖边界一致（spec 40 §1.4）；告示牌文字不发光是预期 |
| 渲染方式 | 复用原生 model outline 通道（no custom pipeline） | 上游也是 outline-only buffer；模型轮廓/颜色随 ping type |
| 抑制线框 | 本实现用全局 hook + policy 抑制；上游是按实际 outline-only 提交判定 | **B1 的偏差**；修成 per-target set 后一致 |
| 首帧 | 正常目标单画；无 model 目标 0 画 | 单画 OK；0 画是 B1 |
| 颜色 | 0xFF\|outlineColor，fallback 白 | 一致 |
| 异常/失败 | model hook 静默失效无 WARN（B2）；dispatch 侧有 WARN | 修 B2 后一致 |

---

## 五、待实机项

- [ ] 箱子/告示牌/熔炉/潜影盒等 entity_block：模型轮廓发 ping 色、无 M7a 线框、无 C1 模型光晕叠加；告示牌文字不发光。
- [ ] **无 renderer / 只画 text 的 entity_block**：修 B1 后应保留线框（这是重点回归）。
- [ ] 同帧多个 entity_block、nested BER、切维度/过期：只画 winner，无串色。
- [ ] 首帧（ping 出现的第一帧）：应只有模型光晕，不应看到一帧线框或双画。
- [ ] 模拟 model hook 失败（临时改坏 `submitModel` 选择器/槽位）→ 应线框回退 + 120 帧内一次 WARN（B2 修后）。
- [ ] 繁忙世界（大量 block entity）观察帧时间/分配，确认 per-dispatch winner 查询是否需要缓存。

---

## 六、结论

**打回。** 方案 A 的注入点/@ModifyVariable/线程本地/首帧/颜色都正确，但抑制分支依赖全局 `isHookApplied`，不是光晕侧的真实存在条件；需要按 B1 做 **per-target `glowingPositions` + `modelHookApplied`**，按 B2 加 model hook 的一次性诊断。修完后可直接进入实机（重点测无 renderer/文字-only 的 entity_block 与首帧）。
---

## 复审记录（B1/B2 修复后）

任务：C2 B1/B2 修复复核　审核人：3号　日期：2026-11-01
基线复核：`bash tools/compile-check.sh` → `main 266 / client 125，0 error` ✓。

### 结论

**通过。** B1/B2 的可见问题已闭合：抑制改为“本帧本 pos 的 model submit 真正消费了颜色”的 `isGlowing(pos)`，renderer==null / 只画 text / 未 dispatch / model hook 未织入都不再抑制线框；model hook 失败由 `checkMissedDispatch()` 在 `restore()` 里按 dispatch 计数并一次性 WARN。剩余为诊断误报、性能与 dead code，均非阻断。

### B1 闭合证据

- `EntityBlockGlowState:182-198` `currentColorOr` 只有在 `CURRENT != null` 且 `PENDING_POS != null` 时把 `pos.asLong()` 加入 `GLOWING` 并置 `pendingConsumed=true`；`begin` 在 color 非空时同时设置 `CURRENT/PENDING_POS/pendingGlow`（`:106-122`），color 为空时清空三个（`:110-115`）。
- `BlockOutlinePass:199-203` 的抑制条件改为 `isGlowing(pos) && !isDisabled() && policy`；不再依赖全局 `dispatchHookApplied`。`isGlowing` 只在 `:201-203` 查当前帧 `GLOWING`，`resetFrame:206-214` 每帧清空 → 不会跨帧、跨 pos 误抑制。
- 若 renderer==null（dispatch 早退）、只 `submitText/customGeometry`、目标本帧未 dispatch、model hook 未织入：`GLOWING` 为空 → 不抑制，M7a 线框保留。**B1 的“无光晕但线框被删”路径已闭合。**

### B2 闭合证据

- `SubmitNodeCollectionMixin:83-85` 的 `@ModifyVariable` 仍 `require=0`；失败时 `currentColorOr` 永远不会置 `pendingConsumed/GLOWING/modelHookApplied`。
- `restore:125-127` 在弹栈**之前**调用 `checkMissedDispatch`（`:162-174`），用当前 dispatch 的 `pendingGlow && !pendingConsumed` 计数；到 120 次一次性 WARN。`GLOWING` 为空，所以线框保留。**B2 的 model hook 静默失败已能回退 + 告警。**
- `LevelExtractorMixin:40` 每帧 HEAD `resetFrame()` 清 `CURRENT/SAVED/PENDING_POS/SAVED_STATE/pending*/GLOWING`；异常路径未 RETURN 的残留不会跨帧。

### 嵌套 / 生命周期

- `begin` 同时推 `SAVED`（颜色）和 `SAVED_STATE: List<Saved(pos,glow,consumed)>`；`restore` 先判当前 dispatch 的 missed，再分别弹两栈；内层不会继承/泄漏外层（`:106-155`）。
- `resetFrame` 清 `SAVED_STATE`，异常路径后栈不会滞留（`:206-214`）。
- `pendingGlow/pendingConsumed` 是 static 而非 ThreadLocal，但 `submit`/model 都在渲染线程；若未来跨线程 dispatch 需要改成 ThreadLocal（记录）。

### 四条失败路径

| 场景 | 现在行为 | 判定 |
|---|---|---|
| ① dispatch hook 未织入 | `dispatchHookApplied=false` → 无 `GLOWING` → 线框；`diagnoseDispatchHook` 120 帧后 WARN | ✅ 线框 + 至多一次 WARN |
| ② model hook 未织入 / 只画 text / renderer==null | 无 `GLOWING` → 线框；`checkMissedDispatch` 120 次 glowing dispatch 后 WARN | ✅ 线框 + 至多一次 WARN（诊断可能误报，见下） |
| ③ 正常 | model 消费 → `GLOWING` → 抑制线框，单 glow | ✅ 单画 |
| ④ 顺序反转（若 Fabric 改变事件顺序） | 线框先画、glow 后画 → 双画；当前 Fabric 顺序已验证为 submitBlockEntities 先、COLLECT_SUBMITS 在 submitFeatures RETURN，因此实际不会 | ✅ 当前顺序单画；注释已标风险方向 |

### 非阻断 / 新残留

1. **`checkMissedDispatch` 的误报（S3）**：`pendingGlow && !pendingConsumed` 也会命中**合法地只画 text/custom geometry 的 entity_block**（以及 renderer==null 的 dispatch）；`missedDispatches` 跨帧不清零，长会话累计到 120 就会打 “model hook not observed” WARN，即使 model hook 正常。建议开头加 `if (modelHookApplied) return;`（已有字段/未用的 `isModelHookApplied`），或改文案为“some glowing dispatches had no model submit”。
2. **`isModelHookApplied()` 是 dead code**（`EntityBlockGlowState:221-223`；`modelHookApplied` 只写不读）；要么用它做上面的 gate，要么删除。
3. **`new Saved(...)` 每 dispatch 一次**：record 很小（3 字段），但 `begin` 对所有 BER dispatch（含非目标）都分配；可接受，若要零分配可换 primitive 栈。
4. **`visibleWinnersInDimension` 仍是每 BER dispatch 调用**（`BlockEntityRenderDispatcherMixin:163` 一带）：该方法 `ClientMarkerStore:573-596` 每调用都 stream/sorted/LinkedHashMap；比 `Saved` 重，建议缓存每帧 winner 快照。`GLOWING` 的 `Long` 装箱也是小分配。
5. **`currentColorOr` 在 `submitModel` HEAD 就置 `GLOWING`**，但 `SubmitNodeCollection.submitModel` 只有在 `getOutlineRenderType(renderType) != null` 时才真正投 outline（字节码 `100-148`）。若某个 block entity 的 RenderType 没有 outline variant，仍会是“抑制线框、无光晕”。**待实机确认常见 entity_block 的 RenderType 都有 outline variant**；若要严格闭合，应在 outline 分支成功后置位（例如加 TAIL/accessor），而不是 HEAD 消费即置位。
6. `pendingGlow/pendingConsumed` static vs `SAVED_STATE` ThreadLocal：当前仅渲染线程，无问题；若 dispatch 被挪到 worker 线程需改 ThreadLocal。

### 待实机

- [ ] 箱子/告示牌/熔炉/潜影盒：模型光晕 + 无线框；告示牌文字不发光。
- [ ] 无 renderer / 只画 text 的 entity_block：线框保留（B1 修后）。
- [ ] 临时改坏 `@ModifyVariable` 选择器/槽位：线框回退 + 120 次 dispatch 内一次 WARN（B2 修后）。
- [ ] 长会话（>120 次 text-only dispatch）确认是否出现误报 WARN（非阻断）。
- [ ] 首帧单画、同帧多目标/nested/切维度、繁忙世界帧时间。

---

## 复审记录（entity_block 烘焙模型兜底）

任务：C2 烘焙兜底缺口复核　审核人：3号　日期：2026-11-01
基线复核：`bash tools/compile-check.sh` → `main 266 / client 125，0 error` ✓。

### 结论

**通过。** 四条路径的互斥关系正确：BER 已发光的位置不烘焙（`isGlowing` 早退），没有 BER 模型/没有 BER 的 entity_block 走烘焙模型，烘焙不成立则回退线框；普通 `block` 代码路径未变。熔炉/营火 policy 默认白名单可通过。唯一需要记录的是“缺失模型”（modded 无 blockstate JSON）也会是一个非空 missing cuboid，以及 `collectParts` 的轻量分配；均非阻断。

### 1. 顺序/互斥（四条路径）

`BlockOutlinePass` 当前顺序是：先 `BlockModelGlowPass.trySubmit`，后 C2 `isGlowing` 抑制。四种目标落点：

| 目标 | BER 模型 | `isGlowing` | 结果 | 判定 |
|---|---|---|---|---|
| 箱子 | 有（submitModel） | true（正常帧） | `trySubmit` 在 isGlowing 处早退 → C2 抑制 → 只画 BER glow | ✅ 单画 |
| 熔炉 | 无 BER | false | 烘焙模型成功 → `submitted++` → continue，不进入 C2 | ✅ 单烘焙 glow |
| 营火 | BER 只提交物品 | false | 烘焙方块模型成功 → 单烘焙 glow | ✅ 单烘焙 glow |
| 告示牌 | BER 模型+文字 | true | 同箱子：BER glow；文字不发光 | ✅ 单画（文字不发光符合上游） |

- **不会双画**：`trySubmit` 在 `entity_block && isGlowing(pos)` 时返回 false，随后 C2 抑制；`trySubmit` 成功时 `continue`，不会走到 C2。
- **不会两边都放弃**：箱子正常由 BER；熔炉/营火由烘焙；失败时 `parts.isEmpty`/outline 不可用则 `trySubmit=false`，C2 也因 `isGlowing=false` 不抑制，线框保留。
- **顺序反转**（若 Fabric 改变 `COLLECT_SUBMITS` 顺序）：箱子会先走烘焙（其模型为空，实际仍回退线框），BER 后到则会“线框+glow”；当前 Fabric 顺序已验证为 `submitBlockEntities` 先、`COLLECT_SUBMITS` 在 `submitFeatures` RETURN，因此实际不会反转。

### 2. policy

- `shouldUseNativeGlow("entity_block", state)` → `BlockDisplayWhitelist.matchesEntityBlock` → `BlockEntityClassification.hasBlockEntity`；熔炉、营火、箱子都实现了 `EntityBlock`，默认 whitelist `*:*` 命中，blacklist 默认空 → **不会被 policy 拒绝**。

### 3. `parts.isEmpty()` / 箱子的 RenderShape

- **26.3 的 `RenderShape` 只有 `INVISIBLE`/`MODEL`**（javap），没有 1.21 的 `ENTITYBLOCK_ANIMATED`；箱子没有覆写 `getRenderShape`，默认是 `MODEL`。所以不能靠 `getRenderShape()` 排除箱子。
- 但箱子资源 `assets/minecraft/models/block/chest.json` **只有 `textures.particle`、没有任何 `elements`**；`BlockStateModelSet.get(chestState)` 返回这个空模型（map 中已有 chest blockstate JSON），`collectParts` 为空 → `BlockModelGlowPass` 返回 false → **回退线框，不会画方盒子**。
- **注意**：`BlockStateModelSet.get(state)` 对**没有 blockstate JSON 的 state** 返回 `missingModel`，而 `MissingCuboidModel` 是一个 0..16 的 **非空 cube part**（javap）；因此“absent state”不会被 `parts.isEmpty()` 拒绝。建议防御性加 `if (model == modelSet.missingModel()) return false;`（或比较引用），避免 modded 实体方块画出 missing cube 光晕。

### 4. 回归

- 普通 `block`：early-return 由「非 block」改为「非 block 且非 entity_block」；`targetTypeId` 对 block 仍是 `"block"`，policy/collectParts/outline 路径逐行相同；C2 抑制分支只匹配 entity_block。**普通 block 行为不变。**
- `BlockModelGlowPass` 仍先于 C2；对普通 block 无新增判断。

### 5. 每帧分配

- `entity_block` 只有 `isGlowing=false` 的候选才 `collectParts` + `List.copyOf`：箱子正常帧在 `isGlowing` 早退，不分配；熔炉/营火每个 winner 一次，数量通常很小。**可接受。**
- `state.getRenderShape()` 不能用来省这次判断（箱子是 MODEL）；若想更省，只能查 `model == modelSet.missingModel()` 或对 block entity 类型做缓存/热路径白名单。当前不做优化即可。
- 未新增每帧日志；`PING_TYPES`/outlineType 仍是 static；`BlockModelGlowPass` 无 weight 分配（ArrayList/List.copyOf 仅在候选内）。

### 待实机

- [ ] 熔炉、营火：模型光晕（非线框）、颜色正确、无 C1/C2 双画。
- [ ] 箱子：BER 动画光晕，不出现方盒子/线框；失败/禁用 C2 时箱子应回退线框而非 cube（B1 修后）。
- [ ] 告示牌：木板模型光晕、文字不发光；普通 block 回归不变。
- [ ] 长会话/大量 entity_block 观察 `collectParts` 的帧时间；modded 无 blockstate 的 entity_block 确认不会画 missing cube（若出现，加 missingModel 守卫）。

---

## 复审记录（契约对齐 task-73）

任务：C2 模式门控 / 不短路 / RenderShape / 告示牌数据冲突　审核人：3号　日期：2026-11-01
基线复核：`bash tools/compile-check.sh` → `main 266 / client 125，0 error` ✓。

### 结论

**通过。** 模式门控覆盖 BER 与烘焙两条来源，未发现第三条 entity_block 发光路径；不短路后 common BE 方块的几何核对为互补/空模型，没有同几何双画；告示牌数据冲突裁决为 **4号正确、2号错误**。残留是非阻断的 per-frame `collectParts` 分配，以及 `RenderShape.MODEL` 对少数 INVISIBLE 普通方块的行为收窄。

### 1. 模式门控完整性

| 路径 | VOXEL_SHAPE_ONLY 行为 | 证据 |
|---|---|---|
| BER outlineColor 注入 | `pingforit$resolveGlowColor` 先返回 null；不产生 `CURRENT`/`GLOWING`；`begin(null)` | `BlockEntityRenderDispatcherMixin:109-117` |
| 烘焙模型 | entity_block 分支在 policy 前 return false；不提交 `submitBlockModel` | `BlockModelGlowPass:121-124` |
| 线框抑制 | `BlockOutlinePass` C2 分支要求 `isGlowing(pos)`；BER 无颜色 → false，不会误抑制 | `BlockOutlinePass:198-203` |

- **没有第三条路径**：`BlockModelGlowPass` 是唯一烘焙源；C2 BER 是唯一实体模型源；C2 suppression 只是抑制判定，不产生几何。
- `ALL`/`COMPATIBLE` 都走同一组 built-in sources；M9 optional registry 未接，所以不需要额外分支。
- `block` 目标完全不读 `getEntityBlockRenderMode()`；`VOXEL_SHAPE_ONLY` 下普通方块仍走 C1 烘焙。

### 2. 不短路的几何核对

| 方块 | BER 提交 | 烘焙模型 | 结论 |
|---|---|---|---|
| 箱子 | `submitModel`（ChestRenderer） | `block/chest.json` 无 elements → parts 空 | BER 单画；烘焙自然放弃 |
| 熔炉 | 无 BER | `furnace` 有 elements | 烘焙单画 |
| 营火 | `CampfireRenderer` 只提交物品 | `campfire` 有 elements | 烘焙单画；物品不发光 |
| 告示牌 | `AbstractSignRenderer` 只提交 `submitText` | `template_wall_sign`/`template_sign_rot_*` 有 elements | **烘焙单画牌体；文字不发光** |
| 钟 | `BellRenderer.submitModel` 提交钟体 | `bell_floor`/`bell_wall` 只含 bar/post 支撑 | BER 钟体 + 烘焙支撑，几何互补，非双画 |
| 旗帜 | `BannerRenderer.submitModel` 提交旗面 | `banner.json` 无 elements | BER 单画 |
| 潜影盒 | `ShulkerBoxRenderer.submitModel` 提交盒体 | `shulker_box.json` 无 elements | BER 单画 |
| 移动活塞 | 无对应 submitModel | `moving_piston.json` 无 elements | 无烘焙；不会方盒 |

- 其余常见 `PistonHeadRenderer`/`SpawnerRenderer` 没有 `submitModel`，只走烘焙静态模型；与 BER 无重叠。
- **结论：没有同几何双画**；钟是“BER 钟体 + 烘焙支架”，告示牌是“BER 文字 + 烘焙牌体”，都属于文档允许的互补贡献。

### 3. 告示牌裁决（数据冲突）

**4号正确，2号错误。**

- `AbstractSignRenderer.submit` 的字节码只有 `SubmitNodeCollector.submitText(...)`（偏移 168），**没有 `submitModel`**。
- `StandingSignRenderer` / `HangingSignRenderer` 的方法表都**没有覆写 `submit`**；它们只实现 `createRenderState`/`extractRenderState`，所以提交逻辑完全继承上面的 `submitText`。
- 牌体来自烘焙方块模型：`template_wall_sign` 有 elements；`oak_sign_rot_*` / `oak_hanging_sign_rot_*` 通过 parent 继承牌体几何。
- **因此告示牌的牌体依赖烘焙源，BER 只负责文字**；这也意味着 `BlockModelGlowPass` 的 `RenderShape.MODEL`（告示牌为 MODEL）是牌体发光的必要条件。

### 4. RenderShape.MODEL 前置的误伤 / 回归

26.3 的 `RenderShape` 只有 `INVISIBLE`/`MODEL`（javap）。常见 BE 方块：

- **MODEL**：箱子、熔炉、营火、告示牌（standing/wall/hanging）、钟、旗帜、潜影盒、活塞头、刷怪笼、试炼刷怪笼。
- **INVISIBLE**：`BarrierBlock`、`LightBlock`、`StructureVoidBlock`（还有 `AirBlock` 等不可见方块）。
- `MovingPistonBlock` 没有覆写，默认 MODEL，但 `moving_piston.json` 无 elements；两种情况下都不会烘焙。

**注意**：`BlockModelGlowPass:130` 的 `RenderShape.MODEL` 检查对 **`block` 和 `entity_block` 都生效**；因此普通 `block` 目标里的 **barrier/light/structure_void 会从“旧 C1 烘焙”退到线框**。这不是 visible block 的回归（这些方块本来不可见，按 outline gate 也不该有 native glow），但“`block` 行为完全不变”应更正为“**可见 MODEL 方块不变；INVISIBLE 方块收窄**”。

### 5. 每帧分配 / 回归

- 不短路后，**所有 entity_block winner** 都会进入 `BlockModelGlowPass`：箱子/旗帜/潜影盒的 block model 为空，仍会创建 `ArrayList`/`RandomSource` 并 `collectParts`（然后 `parts.isEmpty` 返回 false）。这是 **每帧每个 entity_block 的轻量分配**；钟/告示牌/熔炉/营火则会实际烘焙。可接受但不为零；可缓存 `BlockState → List<BlockStateModelPart>` 或“空模型”结果来消除。
- `List.copyOf` 只在 `parts` 非空时执行；箱子等空模型不 copy。
- 无新增每帧日志；`PING_TYPES`/outlineType 仍 static。
- 普通可见 `block`（MODEL）路径不变；`VOXEL_SHAPE_ONLY` 下普通 block 仍发光。

### 待实机

- [ ] 告示牌：牌体发光、文字不发光；确认牌体来自烘焙源（非 BER）。
- [ ] 钟、旗帜、潜影盒、箱子、熔炉、营火：无双画、无方盒、颜色正确。
- [ ] `VOXEL_SHAPE_ONLY`：entity_block 只剩线框，普通方块仍 glow。
- [ ] barrier/light/structure_void（若可 ping）：线框保留，不应有 native glow；文档确认这是预期收窄。
- [ ] 大量 entity_block 的帧时间/分配，确认 `collectParts` 空模型成本。
