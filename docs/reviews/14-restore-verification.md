# 14 · 恢复后「无原文」文件保真度复核（三档标注）

任务：task-10　审核人：3号　日期：2026-10-30　基线：tag `restore-2026-10-29`（`bca73e0`）+ 其后 `87bceb2`
方法：只读源码；上游对照 `Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1`（`common`+`fabric` 共 **298** java）；行级 `Compare-Object`；污染扫描；**复跑** `tools/compile-check.ps1` → `main 262 class / client 83 class，0 error`（与恢复记录一致）。
**未改 `src/**`。**

## 结论先行

1. **重建整体可信**：`src` 217 个 java 全部能映射到上游或属新增类；**143 个与上游逐字节相同**，**51 个仅机械改名/结构性等价**，13 个人工改造（stub/deferred 迁移），10 个无上游对应（M 阶段新增或移植类）。**未发现任何转录污染**（无 `(Showing lines…)`、无路径包装、无重复类声明、Lombok/ResourceLocation 残留 = 0）。
2. **发现 2 处 M 阶段增量在重建中丢失**（真正的问题，均不阻断启动/构建）：
   - **R1（应修）**`ClientPingRuntime` 被按「上游 + 改名」重建，**丢了 M6.5 的 wheel 诊断增量**：状态机仍要求 `WheelSelectionDiagnostics` 供应商，但运行时用 8 参构造器传入 `() -> null` → `wheel select` 日志**永远不含 `scale=/mouse=`**（`sector=` 仍在）。
   - **R2（应修）**`CommonClient` 的 `WheelHudRenderer.resetFailureFlag()` 调用丢失（进/离世界各一次）；`WheelHudRenderer.resetFailureFlag()` 因此成为**零调用死 API**，扫描失败标志一次触发后终身不复位。
3. **R3（待确认）**M4 的验证日志 `send ping:` / `marker created:` 在当前树中**不存在**（运行时/分发器只剩上游的 DEBUG 行），而 `docs/PORTING-PROGRESS.md:234-235,245-246` 仍把它们当作现有行为、1号 的 `make-report-m4/m6.ps1` 也按它们计数 → 要么补 2 行 INFO，要么同步改文档与脚本。
4. **47 这个数字在仓库里没有可核证据**：没有 replay manifest/清单。按行级差异重建的口径，与「47 个无原文」最接近的是 **51 个非逐字节文件**（NEAR 23 + MECH 28），差额 4 个可能因日志里有部分片段被归入「可回放」。**需要 2号 的清单钉死名单**（见 §5 第 1 项）。

---

## 一、方法（可复跑）

```powershell
# 1) 映射：按类名在 上游 common+fabric 中找候选，行级 Compare-Object 取最小差异
# 2) 分档：IDENTICAL(0) / NEAR(1-6) / MECH(7-40) / HEAVY(>40) / NO-UPSTREAM
Get-ChildItem src -Recurse -Filter *.java | ForEach-Object { <Compare-Object 与上游同名类> }
# 3) 污染扫描：'(Showing lines' / 'truncated' / '<path>' / 'BEGIN FILE' / U+2026 / 重复 class 声明
# 4) 复跑编译
& tools\compile-check.ps1
```

上游 java 口径：`Mod_Project/.../common/src/main/java` + `fabric/src/main/java` = **298**（513 为整包含测试/资源）。

---

## 二、三档标注（全量 + 重点 10 文件）

### 2.1 分档汇总

| 档位 | 数量 | 判定依据 | 代表 |
|---|---|---|---|
| **T1 逐字节恢复（会话回放）** | 90（2号 口径） | 会话日志有完整 write；本报告只能**行为级**复核（仓库内无回放哈希） | `WheelHudRenderer`(727 行，含 `getScaledXPos`)、`MarkerHudRenderer`(421 行，含 `itemDecorations`)、`MarkerLabelRenderer`(191 行，含 `PlayerFaceExtractor`)、`PingInteractionStateMachine`(720 行，含 M6.5 诊断) |
| **T2 上游改名重建** | **194**（143 IDENTICAL + 51 NEAR/MECH） | 与上游差异全部可由 14 类机械规则解释（见 §3） | `ConfigHandler`(8 行)、`ServerCore`(34)、`MarkerOverlayState`(2)、`MarkerView`(42)、`NativeLocalShapeRaycaster`(6) |
| **T3 人工重建（含 M 阶段改动/stub/新增）** | **23**（HEAVY 13 + NO-UPSTREAM 10） | 与上游差异是**设计性**的（deferred 迁移、Lombok 移除、M4/M6/M6.5 增量、桩化） | `ClientPingRuntime`(见 R1)、`CommonClient`(见 R2)、`ClientConfig`、`FabricClient`、`SettingsScreen`(stub)、`SableClientProvider`(stub)、`WheelHudRenderer`/`MarkerHudRenderer`/`MarkerLabelRenderer`(无上游) |

> 注：T1 与 T2/T3 有交叠口径（会话回放的文件可能同时"与上游有差"）。表中 T2/T3 的数量按**与上游的差异性质**统计，T1 按**来源**统计。

### 2.2 重点 10 文件逐项

| 文件 | 档位 | 与上游差异（证据） | 结论/风险 |
|---|---|---|---|
| `client/ClientPingRuntime.java` | **T2（但丢了 port 增量）** | 与上游**仅 10 行**机械改名：`dimension().location()→identifier()`×4、`game.cameraEntity→getCameraEntity()`×2、`displayClientMessage→sendOverlayMessage`×1；1271 行 = 上游 1271 行 | **R1**：M6.5 诊断增量（+54 行）整体丢失 → `wheel select` 无 `scale=/mouse=`；M4 INFO 日志也不在（只剩 DEBUG `marker created applied:` `:1006`） |
| `CommonClient.java` | **T3** | 519 行（上游 720）：M3b 移出 outline/render 子系统、HUD 接线为 port 新增 | HUD 接线完整（`:274 onRenderHud`、`:289 Marker`、`:293 Wheel`、`:294-301` 失败兜底），且**含 M4-S1 修复**（`:70 registerRenderFrameEvent` → Fabric `LevelExtractionEvents.END_EXTRACTION`）✓；但 **R2**：`:81-114` 无 `WheelHudRenderer.resetFailureFlag()` |
| `PingInteractionStateMachine.java` | **T1/T3** | 720 行；比上游多 M6 select 日志 + M6.5 诊断供应商 | **M6/M6.5 增量在**：`:552-554` `wheel select pingType=<id>` + `describeSelectionDiagnostics()` + `sector=<i>`；`:647-661` helper（`scale=<guiScale> mouse=<x>,<y>`）；3 个构造器 `:86/:111/:142`，8 参 `:121-131` 以 `() -> null` 兜底（→ 运行时未传诊断时字段为空） |
| `config/ConfigHandler.java` | **T2** | 8 行：Lombok `@Getter/@SneakyThrows` → 手写 getter/try（D7） | 等价 ✓（`getConfig()` 手写，行为不变） |
| `core/ServerCore.java` | **T2** | 34 行：`Identifier`、`sendSystemMessage`、`getName()→name()`、`serverLevel()→level()`、`hasPermissions` | 等价 ✓；多人路径未实机验证（见 §5-6） |
| `marker/MinecraftAuthoritativeTargetValidator.java` | **T2** | 14 行：`Identifier`、`serverLevel()→level()`、注释里的 `location()` 文案 | 等价 ✓ |
| `client/marker/MarkerView.java` | **T2/T3** | 42 行：Lombok `@Getter`→手写 getter、`EntityTypes`、`worldToScreen(...,camera)` 3 参、`Team#getColor()→Optional<TeamColor>` | **M6.5 依赖公式逐字一致**：`:282-287` = `2.0/Math.pow(distance,0.3)` → `max(1.0,scale)*0.5f*pingSize`（上游 `:262-267` 同文）✓ |
| `math/NativeLocalShapeRaycaster.java` | **T2** | 6 行：`Identifier` 两处 | 等价 ✓ |
| `client/marker/MarkerOverlayState.java` | **T2** | **仅 1 行**：`dimension().location()→identifier()` | 契约 ✓：`prepare(ctx, store, nameStore)` `:85-151`，null→`clear()`，维度过滤 `:123-129`，按距离**降序** `:148`，`hudRenderMarkers` 选单视图 `:134-146` |
| `name/MinecraftTargetNameResolver.java` | **T2** | 12 行：`name()`、`getItem().getDescriptionId()`、`Identifier` | 等价 ✓ |

---

## 三、机械改名清单（T2 差异的全部解释）

重建期间使用、并在当前树中**零残留**的规则（抽检结果）：

| 规则 | 抽检证据 |
|---|---|
| `ResourceLocation → Identifier`（全库 `ResourceLocation` 命中 **0**） | `NativeLocalShapeRaycaster` 6 行差异全部为此 |
| `resourceKey.location() → identifier()`（`.location()` 仅剩 1 处注释文案 `domain/Target.java:24`） | `MarkerOverlayState` 唯一差异 |
| `GameProfile#getName → name()`（record 化） | `MinecraftTargetNameResolver`、`ServerCore` |
| `player.serverLevel() → level()`、`sendSystemMessage`/`sendOverlayMessage` | `ServerCore` 34 行 |
| Lombok 移除（`import lombok` / `@Getter` / `@SneakyThrows` 命中 **0**） | `ConfigHandler` 8 行 = 手写 getter |
| `Team#getColor() → Optional<TeamColor>`、`EntityType→EntityTypes` | `MarkerView` |
| `worldToScreen(pos, modelView, projection, camera)` 3 参 | `MarkerView` |

---

## 四、污染扫描（转录风险）

| 模式 | 命中 | 说明 |
|---|---|---|
| `(Showing lines …)`、`<path>`、`BEGIN/END FILE`、U+2026 省略号 | **0**（`<path>` 2 处是 `ModLogFile` 的 `Stream<Path>` 泛型） | 无拼接/截断残留 |
| 重复 `class` 声明（同文件同名） | **0** | 无两份内容拼接 |
| markdown 围栏/提示词残留 | **0** | — |
| 哨兵/占位符（`TODO(M3b)` 等） | 仅出现在**有意桩**里（`SettingsScreen`、`Sable*`、`DistantHorizonsIntegration`、`VoiceChatIntegration`、`BlockDisplayWhitelist`），且 javadoc 写明 M3b/M9 归属 | 属设计，不是污染 |
| HUD 注册重复 | `HudElementRegistry.addLast` **1 处**（`FabricClient:41`） | 无重复挂载 |

---

## 五、必须人工确认（6 项）

1. **47 名单 + replay manifest**：仓库里没有可核清单；请 2号 提供「90 个逐字节回放 / 51 个机械重建」的实际文件列表，本报告的三档表才能与任务口径一一对应（我按差异重建的候选集 = NEAR+MECH 51）。
2. **R1 是否必须恢复**：若 `wheel select` 要有 `scale=/mouse=`（`PORTING-PROGRESS.md:292` 的验收口径），需在 `ClientPingRuntime` 补 `wheelSelectionDiagnostics()`（`getScaledXPos/getScaledYPos`）并把 8 参构造器改成 9 参传供应商；若不恢复，应同步删 `WheelSelectionDiagnostics`/状态机字段，避免文档与实现分叉。
3. **R3 的定性**：M4 的 `send ping:`/`marker created:` 是「重建丢失」还是「有意降级为 DEBUG」？前者补 2 行 INFO；后者改 `PORTING-PROGRESS.md:234-246` 与 `make-report-*.ps1`（否则报表永远显示 0 对）。
4. **R2 是否补回**：进/离世界调 `WheelHudRenderer.resetFailureFlag()`（2 行）；否则删除死 API 并在注释说明"单次会话内不再重试"。
5. **T1 的逐字节证明**：本报告只能验证「行为与 21:19/d6015d7 版一致 + 行数吻合」，无法从仓库证明逐字节；若 Lead 需要，需从 `.dsh` 会话日志重算 90 个文件的 SHA-256 比对。
6. **多人/服务端路径**：`ServerCore` 属 T2 机械改名，但 M5 未开始、`runServer` 未验证；`hasPermissions(name())` 等改动需实机多人冒烟。

---

## 六、复跑与附注

- 编译：`main errors=0 classes=262 | client errors=0 classes=83`（2026-10-30 复核）。
- `docs/reviews/15-restore-baseline-audit.md` 在 `AGENTS.md` 被引用但**当前不存在**（1号 待补，非本任务范围）。
- 本报告未覆盖：`deferred/` 96 文件（M7 取回时另审）、`tools/`、构建脚本、资源文件（恢复记录称已与上游逐字节比对）。
