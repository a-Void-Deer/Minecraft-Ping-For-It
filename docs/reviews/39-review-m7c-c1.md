# 39 · M7c-C1 复审：模型光晕（outline-only submitBlockModel + 事前抑制线框）

任务：task-61　审核人：3号　日期：2026-10-31
分支：`exp/vanilla-lines-m7a`（HEAD `ad26dcd`）；对象：新增 `BlockModelGlowPass` + `BlockOutlinePass` 的 trySubmit 分支（task-58/60，未提交）。
验证：只读源码 + javap（MC 26.3）+ **重跑** `bash tools/compile-check.sh`。未改 src，未跑 Gradle。

## 结论

**可以让用户实机测。** route/policy、outline-only 提交、parts/offset、事前抑制、失败回退均正确；发现 **1 个 S3 回归**（全部走 glow 时会误报“routing mixin inactive”）和若干 S4，均不影响功能正确性。

---

## 一、通过项

### A. route / 策略

- 只对 `marker.targetTypeId() == "block"` 走 glow（`BlockModelGlowPass:93-96`）；`entity_block` 直接 false → M7a 线框 ✓。
- 策略 `ClientConfig.HANDLER.getConfig().getBlockDisplayPolicy().shouldUseNativeGlow("block", state)`（`:99-103`）：default whitelist `*:*`（`BlockDisplayWhitelist:26`）+ `matchesOrdinaryBlock` 强制 `!hasBlockEntity && renderShape == MODEL`（`:86-93`），再取反 blacklist（`BlockDisplayPolicy:61`）→ 普通方块默认 glow、block entity/非 MODEL 不 glow、黑名单可覆盖 ✓。
- M7a 过滤仍在 glow 之前：维度/winner → `isLoaded` → air → registryId → `getShape` 非空（`BlockOutlinePass:153-173`）✓。

### B. outline-only（无重复模型）

- `RenderTypes.solidMovingBlock().outline()` 确实存在：`createMovingBlockSetup` 设置 `OutlineProperty.AFFECTS_OUTLINE` + `Sampler0→LOCATION_BLOCKS` 绑定（javap RenderTypes @123），`RenderType` 构造函数在 AFFECTS_OUTLINE 时用该绑定生成 `outline` Optional（javap RenderType ctor offset 14-64）→ 非空；translucent 回退同理（`BlockModelGlowPass:174-186`）✓。
- `submitBlockModel` 字节码：`renderType.isOutline()` 为 true → **跳过普通 submit**（offset 9-13 `ifne 67`）；`outlineColor` 非 0 时 `getOutlineRenderType`（outline 类型返回自身）→ 只投 **1 个** `BlockModelFeatureRenderer.Submit` 到 outline phase，参数 `EMPTY_TINTS / 15728880 / NO_OVERLAY` 与 vanilla 自己的 outline 分支逐项一致（javap @67-114）→ outline-only、无第二次模型 ✓。
- 对比 `submitMovingBlock`：那是 normal+outline 两条；本实现只走 outline ✓。

### C. parts / offset

- `game.getModelManager().getBlockStateModelSet().get(state)`、`collectParts(RandomSource, List)`、`state.getSeed(pos)`、`state.getOffset(pos)`、`RandomSource.createThreadLocalInstance()` 均经 javap 确认存在且签名一致（BlockStateModel:collectParts；BlockStateBase `getSeed(BlockPos)`/`getOffset(BlockPos)`）✓。
- seed 语义与上游一致：上游 `BlockModelRenderSeed` 文档明确 vanilla 由 `BlockState#getSeed(pos)` 派生（deferred `:8-13`）；offset 平移 `pos - camera + offset` 与上游 `BlockDisplayPlacement:36-45` 逐项相同 ✓。
- `parts.isEmpty()` → 返回 false 回退线框（`:128-131`）✓。

### D. 抑制线框

- `BlockOutlinePass:179-185`：颜色计算后先 `trySubmit`；true → `submitted++ ; continue`，**不会**进入后面的 `submitShapeOutline`（`:187-198`）✓；entity_block 走原线框 ✓。

### E. 失败策略

- outline 类型缺失：两次 `.outline()` 都空 → `disable(...)` WARN 一次 + `glowDisabled`（`:188, 192-206`）✓。
- parts 空：静默回退（`:129-131`，不刷 WARN）✓；policy false/model null 静默回退 ✓。
- 异常：`catch (RuntimeException | LinkageError | AssertionError)` → `disable` + WARN 一次（`:160-163`），per-session（已知不重置）✓。
- 日志：首次成功 `LOGGER.info` 一次（`:154-157`）；无每帧 INFO ✓。

### F. 编译

`bash tools/compile-check.sh` → `main errors=0 classes=266 / client errors=0 classes=99` ✓。

---

## 二、偏差项（含严重度）

### S3-1 · 全部走 glow 时会误报 “routing mixin inactive”
- `BlockOutlinePass:181-185` 的 glow 路径也执行 `submitted++`；末尾 `diagnoseRouting(submitted)`（`:233-245`）只判断 `submitted>0 && !hasRouted`。glow 经 `submitBlockModel` 直接进 outline phase，**不经过** `submitShapeOutline` → `OutlineRoutingState` 永远不会被 mark。
- 后果：当**所有** marker 都是普通方块（都成功 glow，没有 entity_block/黑名单/非 MODEL 回退）时，120 帧后会 WARN “routing mixin inactive; outline stays in the solid bucket (translucent blocks may tint it)”——实际这次没有任何线框提交，属**误报**。
- 修复建议：诊断只统计“形状线框提交数”（新增 `shaped++` 或在 `diagnoseRouting` 用 `shaped ` 而非 `submitted`），glow 计数只用于 `logSubmitted`。1-3 行改动。

### S4-1 · `level` 参数未使用 + offset 来源
- `trySubmit` 的 `ClientLevel level` 参数未被使用（`:78-91`）；上游用 `BlockState#getOffset(BlockGetter, BlockPos)`（BlockDisplayPlacement 文档），本地用单参 `getOffset(pos)`。多数方块等价，少数依赖 level 的 offset 可能有偏差；顺手改成两参或删参数。

### S4-2 · 空形状方块不 glow
- `shape.isEmpty()` 在 trySubmit 之前 continue（`BlockOutlinePass:169-173`），所以“模型有几何但 VoxelShape 为空”的方块既无 glow 也无线框；上游 glow 不依赖 shape。当前 ping 目标基本都有 shape，记录为边界。

### S4-3 · 性能：每帧每 marker 收集 parts + 重复 tessellation
- `BlockModelGlowPass:123-149`：每次 `new ArrayList`、thread-local RandomSource、`collectParts`、`List.copyOf`；FeatureRenderer 每帧按 parts tessellate。标记数量少时可接受，多标记/高 ping 频率下成本线性上升；可按 `(state)` 缓存 parts 或在 M7c C3 优化。

### S4-4 · 极端路径下的双提交
- 若 `submitBlockModel` 已成功、随后发生非受控异常（例如 LOGGER 意外抛错），catch 会 `disable` 并返回 false → 调用方再画一次线框（该帧双画）。实际几乎不可达，记录。

### S4-5 · glow 失败后 per-session 锁存
- `glowDisabled` 一旦置位本 session 不再尝试（`:64, 192-193`），join/leave 不重置；与 BlockOutlinePass/M7b 的已知限制一致。

---

## 三、运行风险

1. **S3-1 误报**：全 glow 场景 120 帧后可能看到 “routing mixin inactive” WARN；线框本身正常，忽略即可（或按建议修诊断）。
2. **outline 类型解析**：默认 solid moving block 的 outline；若未来 26.3 改动 `AFFECTS_OUTLINE`/绑定，可能解析失败 → WARN 一次 + 回退线框（不崩）。
3. **模型光晕观感**：走原生 outline 通道（单次 outline submit），预期为“发光模型轮廓”，与 M7a 线框不同；颜色/穿墙/半透明方块需实机确认。
4. **OIT**：outline phase 在 OIT 5 相列表内（31 号已核），glow 预期仍可见；未单独验证。

---

## 四、实机验证步骤

1. **普通方块**：ping 石头/泥土 → 应看到**模型轮廓发光**（不是 3.75px 线框），隔墙可见。
2. **entity_block**：ping 箱子/熔炉等 block entity（若可 ping）→ 仍为 M7a 线框（C2 未做）。
3. **黑名单**：把该方块加入 `blockShapeBlacklist` → 该方块回退线框；白名单移除同理。
4. **半透明方块**：ping 玻璃/冰 → 不应出现“模型被画两次变暗”（对比旧 submitMovingBlock）；只应有轮廓。
5. **无崩溃/日志**：首次 glow 只有一条 INFO；全 glow 场景若出现 routing WARN，按 S3-1 记录（不是功能故障）；无每帧 INFO。
6. **回归**：M7a 线框（entity_block/黑名单/非 MODEL）、M7b 实体描边、HUD/轮盘正常；退出无 crash。

---

## 五、结论

**可以让用户实机测。** A/B/C/D/E/F 全部通过：outline-only 由 `submitBlockModel` 的 `isOutline()` 分支保证（javap 已核），parts/seed/offset 与上游一致，事前抑制不会双画，失败一律回退线框。**唯一需要先知道的是 S3-1**：所有 marker 都走 glow 时会误报 routing WARN（诊断问题，不影响绘制）；建议把诊断计数改成“线框提交数”后再交付。其余 S4（level 参数、空形状、性能、双提交极端路径、per-session 锁存）不阻塞。

---

## 复审（2026-10-31，S3-1/S4-2 修复后）

任务：task-63　审核人：3号　范围：仅核 task-62 对 `BlockOutlinePass` 的两处修复（未提交工作树，HEAD `ad26dcd`）。
方法：`git diff` + 文件 mtime/hash + javap + **重跑** `bash tools/compile-check.sh`。未改 src，未跑 Gradle。

### 通过

| # | 项 | 证据 |
|---|---|---|
| 1 | **S3-1 关闭：诊断只看线框提交数** | `wireframeSubmitted` 新增于 `BlockOutlinePass:143`；只在 `submitShapeOutline` 之后自增（`:197-199`），glow 分支不计入；末尾 `diagnoseRouting(wireframeSubmitted)`（`:206`）。全 glow 时 `wireframeSubmitted == 0` → `diagnoseRouting` 的 `submitted <= 0` 早退（`:233-236`）→ 不再误报；混合/纯线框语义保持不变（glow 成功仍 `submitted++ ; continue`，`:180-182`） |
| 2 | **S4-2 关闭：glow 提前到形状计算之前** | color 计算后即 `BlockModelGlowPass.trySubmit(...)`（`:170-182`），其后才 `state.getShape(...)`（`:184`）；MODEL 方块即使 VoxelShape 为空也能 glow；glow 失败再计算 shape 并回退线框（空形状仍 `continue`，`:186-188`）✓ |
| 3 | **S4-1 依据成立** | javap `BlockBehaviour$BlockStateBase` 只有 `getOffset(BlockPos)` **单参**（无 `getOffset(BlockGetter, BlockPos)` 重载）→ 保留单参合理；`trySubmit` 的 `level` 形参仍未使用（见残留） |
| 4 | **过滤顺序未破坏** | `isLoaded`（`:156`）、air（`:162`）、registryId（`:166`）仍在 glow 之前；`acceptsForBlockRendering`/维度/BlockTarget 不变（`:145-152`）✓；线框仍 `afterTerrain=true`、3.75、失败/日志策略未变（`:190-202, 205, 207+`） |

### 无其他改动（B）

- 本轮只有 `BlockOutlinePass.java` 的 mtime 变化（18:17:28, sha `70D588F00DC6`）；`BlockModelGlowPass.java` mtime 18:10:41（task-62 之前）、`EntityOutlineSelection`（16:24:59, `F58C5A6D9868`）、`EntityOutlineState`（16:35:54, `E7950C91E7ED`）、`pingforit.mixins.json`（16:25:09, `9A2511C0F5C0`）均未变；`git status` 中 src 侧只有 `BlockOutlinePass.java` 为 `M` ✓。

### 编译（C）

`bash tools/compile-check.sh` → `main errors=0 classes=266 / client errors=0 classes=99` ✓。

### 残留（S4，不阻塞）

- `trySubmit` 的 `ClientLevel level` 形参仍未使用；既然 26.3 无两参 `getOffset`，建议删参数或在注释里写明“单参已足够”。
- 空 VoxelShape 且 glow 失败的方块既无 glow 也无线框（无法绘制，预期）；形状非空时才回退线框。
- 性能与 39 号一致（每帧每 marker parts 收集/复制/tessellation）；`glowDisabled` per-session 不重置；异常双提交极端路径未变。

### 结论

**39 号报告的 S3-1、S4-2 均已关闭；本轮仅 `BlockOutlinePass` 一个文件，glow/线框/失败策略无其他变化，编译 0/0。可以交付用户实机测**（默认开启、无需任何文件）；实机观察点沿用 39 号正文（普通方块=模型轮廓、entity_block=线框、黑名单=线框、半透明无双画、日志仅一条 INFO、全 glow 不再出 routing WARN）。