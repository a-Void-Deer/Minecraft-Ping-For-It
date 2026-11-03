# 17 · 补回代码复核（M6.5-restored）

任务：task-14　审核人：3号　日期：2026-10-30
基线：tag `port-M6.5-restored`（`b7e7a39`）；复核对象 = `a083122`（7 文件补回）+ `6ad0207` / `d11eb5b`（M6.5 增量与崩溃修复）的**结果态**。
方法：只读源码；对照 `.tmp-decomp/src`（反编译基线）、`deferred/`（上游 1.21.1 原样）、`Mod_Project` 上游；**复跑** `tools/compile-check.ps1` → `main 262 class / client 89 class，0 error`。未改 `src/**`。

## 结论

**可以保留为当前基线，但有 1 项应修（配置持久化字段缺失）和 2 项低危/文档项。**
- 11 个 TODO(M7) 空壳：**无副作用、零调用、签名与反编译版一致**（仅 26.3 已删除类型退化为 `Object`）；HUD 三步未受影响。
- `ClientConfig` 的 `equals` 覆盖 32 个非 transient 字段，但 **`hashCode` 少 `serverChannels`**（反编译基线 :719-720 有）→ 只改 `serverChannels` 时 `ConfigHandler` 会误判“无变化”跳过写盘。
- BlockDisplay 四件套：校验三态与路由与 deferred/反编译版一致；仅 `validatedEntries` 把 null 变空表（`NULL_LIST` 不可达）与两处过期注释需处理。
- DH / VoiceChat：**仍是纯桩**，无真实集成调用。

---

## 一、通过项

| # | 项 | 证据 |
|---|---|---|
| P1 | **11 个 TODO(M7) 空壳无副作用** | `CommonClient.java:363-432`：10 个 outline 相关方法体只有注释/`return false`，第 11 个 `onRenderToggleNotice`（:430，M8）为空体；**全库 grep 每个名字仅 1 处命中（即定义本身），零调用**；不在任何 mixin/事件注册里 |
| P2 | **空壳签名与反编译版一致** | 反编译 `.tmp-decomp/.../CommonClient.java`：`prepareEntityOutlines :206`、`renderModelOutlines :240`、`renderEntityOutlines :248`、`renderBlockOutlines :286`、`onRenderToggleNotice :324` —— 方法名/参数个数/顺序全部一致；仅 26.3 已删除的类型（`Camera`→保留、`MultiBufferSource`/`GuiGraphics`）退化为 `Object`（:399、:425、:430） |
| P3 | **HUD 三步未破坏** | `onRenderHud :274-302` 顺序仍为 `onRenderWorld(ctx) :288` → `MarkerHudRenderer.render :292` → `WheelHudRenderer.render :296`，失败兜底 `hudFailureLogged :297-300` 在 |
| P4 | **`prepare` 契约与排序** | `onRenderWorld :310-315` 以 `(ctx, store, nameStore)` 调 `MarkerOverlayState.INSTANCE.prepare`；状态机字典排序在 `MarkerOverlayState:148`（降序） |
| P5 | **`equals` / `canEqual` / `toString` 覆盖** | `equals` :616-657 逐项覆盖 32 个非 transient 字段（含 `channel :656` 与 `serverChannels`）；`canEqual :611` 与反编译 `:520-527` 一致；`toString` 与反编译同（均不含 `serverChannels`） |
| P6 | **`ConfigHandler` 跳写逻辑成立** | `:148` `if (!force && configHash == config.hashCode() && Files.exists(configPath)) return true;`，`configHash` 在 load/save 后多处刷新（:@162,198,224…）——机制本身正确，**问题在 hashCode 覆盖面**（见 D1） |
| P7 | **BlockDisplay 校验三态一致** | `BlockDisplayWhitelist.validateEntries :44-62` 与反编译 `:30-46` 逐条同序：`NULL_LIST`(list null) → `BLANK_ENTRY`(raw null/blank) → `INVALID_GRAMMAR`(tryParse empty)；`ConfigValidationException.Reason` 三值在 `:9-12` |
| P8 | **Entry/TagLookup 与 deferred 语义一致** | 与 `deferred/.../BlockDisplayWhitelistEntry.java` 的差异**仅** `ResourceLocation→Identifier`（26 行，两侧一一对应）；`BlockTagLookup` 差异 4 行（import + 签名）；语法严格性（`*:@ `、`ns:*`、`#tag`、`isValidNamespace/Path`）在 `Entry:128-161` |
| P9 | **Policy 路由一致** | `BlockDisplayPolicy.shouldUseNativeGlow`（`:51-62` / `:64-84`）：`block` → `!hasBlockEntity && RenderShape.MODEL`、`entity_block` → `hasBlockEntity`，黑名单优先（`&& !blacklist`）；字符串常量与 deferred `BlockModelOutlineRoute:45,48` 的 `"block"/"entity_block"` 相同；5 参 `matches` 忽略 flags 也与反编译 `:84-85` 相同（同样只转调 2 参） |
| P10 | **策略随配置重建** | `ClientConfig.validate :285-289` 与 `onUpdate :304` 都重新 `compile(...)`；setter :159-171 亦同步重建（与反编译 :211/:222 一致） |
| P11 | **DH 仍是桩** | `DistantHorizonsIntegration:29-35` `traceDistantAsync` 直接 `return false`；`traceDistant :62-65` `return Optional.empty()`；两个 log 方法空体；仅保留纯函数 `rootCause :42-56`；无 GH/DH 类引用 |
| P12 | **VoiceChat 仍是桩** | `VoiceChatIntegration`：`PLUGIN_ID="pingforit"`、两个 `Object` 句柄、`registerEvents/onServerStarted/onClientInitialized` 空体；`VoiceChatWrapper.getGroupId/getSelfGroupId` 恒 `Optional.empty()`（:18-24） |
| P13 | **M6.5 链路回归通过** | diagnostics 回来了：`ClientPingRuntime:287` 传 `ClientPingRuntime::wheelSelectionDiagnostics`、`:1278-1294` 用 `getScaledXPos/YPos`；M4 日志回来了：`dispatcher:122 send ping:`、`runtime:1010 marker created:`；`CommonClient:92,107` 调 `resetFailureFlag`；`WheelHudRenderer:272` HiDPI、`:116` reset API；`MarkerHudRenderer:257 itemDecorations`（ICON 5 :91、ARROW 10/0.25 :104,112）；`MarkerLabelRenderer:189 PlayerFaceExtractor`、extraWidth 10 :74,161；`InputUtils:38-40` `UNKNOWN.getValue()` + `KeyMappingMixin`（66 行） |
| P14 | 编译 | `main errors=0 classes=262 / client errors=0 classes=89` |

---

## 二、偏差项（含严重度）

### D1 · **应修**｜`ClientConfig.hashCode` 漏了 `serverChannels`（与 equals 不对称，会导致配置不写盘）
- 现状：`equals() :616-657` 最后一行为 `Objects.equals(getServerChannels(), other.getServerChannels())`（`:656`）；但 `hashCode() :660-694` 最后一项是 `Objects.hashCode(getChannel())`（`:692`）后直接 `return`，**没有 `serverChannels`**。
- 反编译基线：`ClientConfig.java:719-720` 明确有 `Object $serverChannels = this.getServerChannels(); result = result*59 + (...)`。
- 后果（真实场景）：`ConfigHandler:148` 靠 `configHash == config.hashCode()` 跳过写盘。若一次修改只影响 `serverChannels` 而非当前连接的 `getChannel()`（例如换服务器后写入的条目、或 `setServerChannels` 恢复历史映射），hash 不变 → **静默不保存**，重启后丢失。等于把“持久化正确性”交给一个不完整的哈希。
- 修法（1 行）：在 `:692` 后追加
  `result = result * 59 + java.util.Objects.hashCode(getServerChannels());`
  与 equals 字段集完全对齐。
- 注：`equals/hashCode` 使用 `getChannel()`（依赖当前连接 ip）是**反编译基线自身的设计**，本次不追责；但补上 `serverChannels` 后，至少“同一对象在任意连接下 hash 稳定”这一半能得到保证。

### D2 · 低（待确认）｜`validatedEntries` 把 null 变空表，`NULL_LIST` 从配置路径不可达
- 现状：`ClientConfig.validatedEntries :177-184` 先 `entries == null ? List.of() : entries`，再 `validateEntries`。因此 JSON 里 whitelist 为 `null` 时不再抛 `NULL_LIST`，而是静默变成空表（=不发光）。
- 反编译基线：`:144-147` 直接 `validateEntries(entries, fieldName); return List.copyOf(entries);` → null 会触发 `NULL_LIST` → `ConfigHandler` 走“配置无效→恢复/备份”路径（`:552-566` 已支持）。
- 影响：畸形配置的处理从“显式报错并恢复”降级为“静默空表”。若 Lead 认为“宁可空表也不要恢复”，现状可接受；否则改回直接传 `entries`（1 行）。**需产品/Lead 定夺**。

### D3 · 文档（建议）｜两处注释仍说“M3b placeholder / never throws”
- `ClientConfig:45-47`：“BlockDisplayPolicy/BlockDisplayWhitelist are M3b placeholders for now.”
- `ClientConfig:178-180`：“validateEntries is an M3b placeholder (no registry resolution, never throws).”
- 事实：`validateEntries` 现在**会抛** `ConfigValidationException`（三种 Reason 全在），Policy 也已是真实路由。注释会误导下一位修改者，建议同步更新（并删掉 `TODO(M7/M8)` 中已不成立的部分）。

---

## 三、建议

1. **D1 立即修**（1 行，风险极低）；修完建议加一条单测/断言：`config.setServerChannels(map)` 后 `hashCode()` 变化（M10 测试基座里补）。
2. **D2 由 Lead 定调**：保留“null→空表”（安全优先）或恢复“null→NULL_LIST→恢复流程”（基线行为）。
3. **D3 更新注释**，避免“已恢复的校验被误认为桩”。
4. **`Object` 参数**（`renderEntityOutlines`/`renderBlockOutlines`/`onRenderToggleNotice`）是 26.3 类型缺失下的合理降级；M7 接管时记得把签名收紧（反编译版分别是 `MultiBufferSource.BufferSource` / `BlockOutlineFrameState.FrameTransform` / `GuiGraphics`），否则调用方仍会拿到无类型约束的入口。
5. **空壳零调用**是当前的安全边界：M7 接回时必须**同时**接回调用链（`onRenderWorld` 里挂 `prepareEntityOutlines()` 等），否则会重演“方法在、链路断”。建议在 M7 任务里显式列出这 11 个入口。

---

## 四、复跑

```powershell
& tools\compile-check.ps1   # main 262 / client 89, 0 error
# 关键证据行
CommonClient.java:274-302,363-432   ClientConfig.java:159-184,285-304,616-694
ConfigHandler.java:148,552-566      BlockDisplayWhitelist.java:44-62,103-113
BlockDisplayPolicy.java:51-84       .tmp-decomp/src/.../ClientConfig.java:719-720
ClientPingRuntime.java:287,1278-1294;  WheelHudRenderer.java:116,272
MarkerHudRenderer.java:91,104,112,257; MarkerLabelRenderer.java:74,161,189
Interactions: dispatcher:122 / runtime:1010 / InputUtils:38-40
```