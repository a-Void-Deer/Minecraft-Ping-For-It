# 61 · M7c-C2 模型光晕 WARN 审计

任务：判定 `entity-block glow: model hook not observed on 120 glowing dispatches` 是否为重构回归
审核人：3号　日期：2026-11-03　对象：`run/logs/latest.log` 22:28:33–22:39:51；`EntityBlockGlowState`、`SubmitNodeCollectionMixin`、`BlockEntityRenderDispatcherMixin`、`BlockModelGlowPass`。

## 结论

**误报（诊断缺陷），不是重构回归，也不是 DH/OIT 环境导致。** 22:38:43 用户 ping 的是**营火（campfire）**；`CampfireRenderer` 只提交物品、从不调用 `submitModel`，所以 C2 的 `modelHookApplied` 永远不会被置位，`missedDispatches` 累计到 120 后打出这条 WARN。营火/告示牌这类“BER 只画物品/文字”的目标本来就该走 C1 烘焙模型或线框 fallback；这条 WARN 的“model hook not observed”与“stays on the wireframe”措辞都不准确。**无需修 C2 渲染逻辑；建议只修诊断文案/级别，并在文档登记。**

---

## A. 是否重构回归（不是）

- **mixin 配置仍在**：`fabric/src/client/resources/pingforit.mixins.json:6-15` 的 `client` 列表包含 `SubmitNodeCollectionMixin`；`package` 仍是 `nx.pingwheel.common.mixin`，与迁移前一致。
- **目标类/方法未变**：26.3 `SubmitNodeCollection.submitModel` 的真实签名（javap）为：
```text
public <S> void submitModel(Model<? super S>, S, PoseStack, RenderType, int, int, int, UvMapping, int)
```
`SubmitNodeCollectionMixin` 的 `@ModifyVariable(method = "submitModel", index = 9, argsOnly = true, require = 0)` 与实例方法 slot 语义一致（this=0，最后一个 int=9）；26.3 字节码 `100: iload 9 / ifeq` 也确实把 slot 9 当 outlineColor。
- **重构只移动文件**：类路径从 `src/client/java/nx/pingwheel/common/mixin` 变为 `fabric/src/client/java/nx/pingwheel/common/mixin`，package/类名/配置 package 未变；`latest.log:62` 有 SpongePowered MIXIN 子系统日志，全日志**没有** `Mixin apply failed` 或启动类错误。
- **`require=0` 的静默语义**：即使 `@ModifyVariable` 未织入也不会崩，也不会打 `Mixin apply failed`；所以“无 apply failed”不能单独证明 hook 已织入。但本次 WARN 有更直接、可复现的因果（campfire 无 `submitModel`），不能据此判回归；要确认 hook 本身，ping 一次**箱子/告示牌牌体**即可（见 E）。

## B. 为什么 120 次都“观察不到 hook”（代码级因果）

### B1 直接因果：目标只有营火/告示牌这类不提交模型的 BER

- `run/logs/latest.log:359-369`：22:38:43 `send ping: requestId=14 kind=BLOCK pingType=attention`，聊天行是 **“请求 注意 营火”**；22:38:43 `marker created: markerId=14`；紧接着 `entity-block model glow active (outlineColor via dispatch hook)`，22:38:44 打出 WARN。
- `CampfireRenderer` 的提交面只有 `submitItem`，**没有 `submitModel`**（26.3 javap 实测；与 C2 的 `BlockEntityRenderer` 边界一致）；因此：
  - `BlockEntityRenderDispatcherMixin.pingforit$beginEntityBlockGlow` 仍会 resolve 到营火的 `entity_block` 颜色并 `begin(pos,color)`；
  - `CampfireRenderer.submit` 只提交物品，`SubmitNodeCollectionMixin.currentColorOr` 没有机会被调用；
  - `restore()` 时 `pendingGlow=true`、`pendingConsumed=false`、`modelHookApplied` 仍为 false；`checkMissedDispatch()` 自增；
  - 120 次营火派发后触发 WARN。
- **告示牌同理**：`AbstractSignRenderer.submit` 只 `submitText`，没有 `submitModel`；如果 ping 告示牌，也会累计。**箱子/潜影盒/钟/旗帜**会 `submitModel`，一旦有一次成功，`modelHookApplied` 会置 true，`checkMissedDispatch` 会早退（`:178`），不再累计。

### B2 为什么 `modelHookApplied` 没救回来

`EntityBlockGlowState.checkMissedDispatch`（`:175-188`）有：
```java
if (modelHookApplied || !pendingGlow || pendingConsumed) return;
```
`modelHookApplied` 只在 `currentColorOr` 真正被 `submitModel` 调用时置 true（`:197-210`）；用户这次会话里只 ping 了营火（以及草方块/床/门等非 BER 或非 entity_block 目标），**没有任何一次模型提交消费颜色**，所以 guard 不生效。

### B3 为什么不是 DH/OIT

- DH 在 22:29:57 `Disabling improved transparency...`，影响的是 **afterTerrain 相位**（OIT/classic 分支），与 `BlockEntityRenderDispatcher.submit` / `submitModel` 无关；C2 的模型 hook 不依赖 OIT。
- OIT 开关只影响 `SubmitNodeCollectionMixin` 的 **afterTerrain vs outline** 路由（C2/B 项已单独审），不影响 `currentColorOr` 是否被调用。
- `CampfireRenderer` 在任何透明度模式下都不提交 `submitModel`；这是目标本身的几何来源，不是 DH 替换。

### B4 其他可能路径

- `renderer == null`：`submit` 早退，`begin` 仍可能 arm 颜色；同样会计入 missed；属于“没有模型”而非 hook 缺失。
- `submitModelPart`：它最终调用同一个 9 参 `submitModel`，不是漏网路径。
- `@ModifyVariable` 若真的未织入：同样会 120 次后 WARN；但本次有 campfire 这一条充分解释，不能据此判 hook 失败。**要区分**，只需 ping 一个箱子/告示牌牌体（见 E）。

## C. 这条 WARN 的原设计意图

- `MISSED_DISPATCH_WARN_AT = 120`（`EntityBlockGlowState:58`）与 `DIAG_AFTER_FRAMES = 120`（`:55`）是两条独立诊断：前者针对 **model submit hook 未观察到**，后者针对 **dispatch hook 未织入**。
- 计数在 `restore()`（`:138-141`）里、弹栈前对**刚结束的 dispatch** 进行：`pendingGlow && !pendingConsumed` 表示“这次派发被标记为要发光，但没有任何 model submit 消费颜色”。阈值 120 次派发后一次性 WARN（`missedDispatches == 120`），**per-session 不重置**。
- 设计意图是：C2 的 `@ModifyVariable` 是 `require=0`，如果它没织入，C2 不应静默；因此用“120 次 dispatch 没有消费”作为证据。**但它没有区分“hook 没织入”与“这个 BER 本来就不提交模型”**；`modelHookApplied` guard 只能解决“会话中曾有过模型目标”的情况，无法解决“整段会话只有营火/告示牌”的情况。
- 因此这条 WARN **本来就允许出现**（在只有无模型 BER 目标时），但它的两级措辞（“model hook not observed”/“stays on the wireframe”）把**目标无模型**误写成**hook 缺失**，且营火实际上还有 C1 烘焙模型兜底，不是“stays on wireframe”。

## D. 结论三选一

**选 (3) 诊断本身误报（不是 (1) 回归，也不是 (2) 环境）。**

理由：
- 26.3 的 `CampfireRenderer` 没有 `submitModel`，营火 ping 在 22:38:43 与 WARN 22:38:44 时间吻合；
- 会话内没有箱子/告示牌牌体等模型目标，`modelHookApplied` 没有被置位；
- C2 的 `@ModifyVariable` 配置/目标/包名未因重构改变，`latest.log` 无 Mixin apply 失败；
- DH/OIT 只影响透明度相位，不影响 BER 模型提交。

**不需要修 C2 渲染行为**；需要修的是诊断的措辞/判据（可选降级），以及文档登记“campfire/sign 会合法触发这条 WARN”。

## E. 最小改动建议（不直接改代码）

1. **不要为这次 WARN 改 C2 hook**；它不是 hook 回归。
2. **最小诊断改动（二选一）**：
   - **降级为 DEBUG**，并改文案为 `no model submit observed on 120 glowing dispatches (expected for text/item-only BERs such as campfire/sign); fallback source decides`；不再声称 `model hook not observed`/`stays on wireframe`。
   - 或保留 WARN，但只在**已知存在模型目标**后才计数：例如在 `currentColorOr` 里额外记录一次 `modelSubmitHookSeen`（不依赖颜色），并在 `BlockModelGlowPass` 命中烘焙源时打标；这需要跨 `BlockOutlinePass` 的时序，改动大于“降级/改文案”，不是最小方案。
3. **`logActivationOnce` 的文案也建议改**：当前在营火派发时打 `entity-block model glow active (outlineColor via dispatch hook)`，但实际只是 resolve 了颜色，模型 hook 没有消费；改成 `entity-block glow colour resolved ...` 或等 `modelHookApplied` 为 true 再打。
4. **验证是否真的 hook 回归**（给 Lead 的一次性动作）：在正常世界 ping 一个**箱子**或**告示牌牌体**（有 `submitModel`），若日志出现 `entity-block model glow active` 且 `modelHookApplied` 的 WARN 不再累计，说明 hook 正常；若箱子仍 120 次 WARN，才需要查 `@ModifyVariable`。
5. **文档登记**：在 `24`/`AGENTS` 的 C2 诊断条目下写明“campfire/sign 等 text/item-only BER 会触发一次 `model hook not observed` WARN；这是诊断误报，不是渲染回归；营火/告示牌牌体走 C1 烘焙模型或线框 fallback”。

---

## 证据索引

- `run/logs/latest.log:359-369`：营火 ping + 激活 + WARN。
- `EntityBlockGlowState.java:138-188`：`restore`/`checkMissedDispatch`/`modelHookApplied` guard；`:58` 阈值 120。
- `BlockEntityRenderDispatcherMixin.java:61-101`：HEAD `begin` / RETURN `restore`。
- `SubmitNodeCollectionMixin.java:99-102`：`@ModifyVariable`；`pingforit.mixins.json:6-15` 加载配置。
- `CampfireRenderer` javap：只有 `submitItem`，无 `submitModel`（26.3 client jar）。
- `BlockModelGlowPass.java:109-197`：entity_block 烘焙源仍可为营火/告示牌提供模型或回退线框。

---

## 附：诊断日志修复复审（2026-11-03）

### 结论

**需改（仅一处文案）。** WARN→DEBUG 的方向正确，与 61 的“营火误报”结论一致；但 logActivationOnce 的新文案“a model submit decides whether it is drawn”对营火/告示牌不成立——它们没有 submitModel，实际由 C1 烘焙模型或线框决定。建议只改这一句，其余可保持。

### A. 与 61 号结论一致 / 是否降级过头

- **一致**：checkMissedDispatch 现在把“120 次没有 model submit 消费颜色”降为 DEBUG，并明确 campfire/sign 是预期；这是 61 号建议的方向。
- **不算降级过头（但确实失去一条静默回归信号）**：modelHookApplied guard、MISSED_DISPATCH_WARN_AT=120、per-session 一次性都保留；真正“hook 未织入”现在不会 WARN，只会 DEBUG。现有 diagnoseDispatchHook 对 dispatch hook 缺失仍是 WARN，未一起降级；只有 model hook 缺失降为 DEBUG。若希望真回归有可见信号，后续可加“已观察到任何 submitModel 调用”的标志（见 C），而不是恢复当前会误报的 WARN。**当前先降级是合理取舍。**

### B. 文案准确性

- **警告文案（DEBUG）基本准确**：BlockOutlinePass.java:181-185 先 BlockModelGlowPass.trySubmit（C1 烘焙模型），失败后才到 :198-205 的 C2 isGlowing 抑制，再失败才 :207-220 线框。因此“baked-model or wireframe fallback decides what is drawn”顺序正确（C1 优先，线框其次），只是用了“or”没写出优先级；不改也不误导。
- **激活文案不准确**：logActivationOnce 写的是“entity-block glow colour resolved ... a model submit decides whether it is drawn”。对营火/告示牌：没有 submitModel，但 C1 烘焙模型仍可能画；所以“a model submit decides”是假的。2号自己的 javadoc 也写了“the baked-model source or the wireframe fallback may still be what the player sees”，文案与 javadoc 不一致。建议改为：“a model submit, the baked-model source, or the wireframe fallback decides what is drawn”。

### C. 2号 的计数器方案（建议留到 M10/以后，不要现在实现）

不建议现在实现：

1. resolved>0 && consumed=0 不能区分“目标无模型（正常）”与“hook 失效（回归）”：两者都满足该条件。
2. 若只 count consumed / modelHookApplied，campfire-only 会话仍然 consumed=0，无法判断；必须额外记录“任何 submitModel 调用被观察到”（currentColorOr 被调用、即使 CURRENT==null），但 campfire-only 场景仍然没有 submitModel，所以仍不能自动区分。
3. 要真正做到“自动区分”，只能在已知模型目标（箱子/告示牌牌体）上做一次自检，或按 BER renderer 类型做能力表；两者都超出“诊断日志”范围。
4. 当前 DEBUG + 一次 INFO 已足够日常排障；M10 建测试基座时再加“模型目标自检”更合适。

如果以后做，最小安全改法：在 currentColorOr 入口（CURRENT 判空前）置 modelSubmitHookSeen=true，记录一次；120 次 DEBUG 里带 dispatchHookApplied/modelHookApplied/modelSubmitHookSeen/missed；仍不要升级为 WARN；最多在“有任何 submitModel 但 consumed=0”时打一次 INFO。

### D. 编译 / 行为风险

- 本次改动后运行 bash tools/compile-check.sh：common 262 / fabric-main 4 / fabric-client 126，0 error；exit 0。
- WARN→DEBUG 只改日志级别；MISSED_DISPATCH_WARN_AT、missedDispatches、modelHookApplied 仍被使用，无未用变量。
- logActivationOnce 只改文案；activationLogged 一次性语义不变；LOGGER.debug 参数化、每会话最多一次，无性能/异常面。
- 未改 @ModifyVariable/BlockEntityRenderDispatcherMixin/C1/C2 路由，渲染行为不变。

### 最终建议

1. **只改 logActivationOnce 文案**（B 的 one-liner），其余按 2号 方案保留。
2. C 的计数器/自检留到 M10；不要现在把 DEBUG 升级回 WARN。
3. 文档补一句：campfire/sign 的 DEBUG 是预期；真正的 model hook 回归当前只能靠 DEBUG 或模型目标自检发现。
