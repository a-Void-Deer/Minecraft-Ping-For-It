# 37 · M7a/M7b 实机会话日志分析（2026-10-31 17:14–17:16）

> 分析：1号 repo-analyst｜2026-10-31｜**只读 `run/logs/`，未改日志、未动 src**
> 会话：`run/logs/latest.log`（257 行，**17:14:25 → 17:16:35**，正常 `Stopping!`）
> 分支：`exp/vanilla-lines-m7a`（含 M7b 实体描边改动）；用户反馈：**「测试功能正常」**。

## 0. 结论先行（5 条）

1. **两套描边都按预期注册**：`ping outline pipeline registered (depth test disabled, cull off, translucent)`（`:66`）+ `block outline pass registered (custom pipeline, afterTerrain routing)`（`:67`）。
2. **实体描边通道已激活**：`entity outline pass active (native glow channel via outlineColor)` **只出现一次**（`:186`，17:15:09），说明 snapshot 非空且走的是原版发光通道。
3. **链路完整**：**16 条 `send ping` ↔ 16 条 `marker created`**（`requestId 0..15` ↔ `markerId 0..15` 逐条配对）；**6 BLOCK / 10 ENTITY**；类型 **attention 13 / danger 2 / loot 1**；**0 removed / 0 rejected**。
4. **无模组异常**：0 条 pingforit WARN/ERROR、0 `pass failed`、0 `disabled`、0 「hook 未织入 / routing mixin inactive」、无 Mixin 应用错误（`:56-57` 只是 Mixin 子系统初始化 INFO）。
5. **OIT 未覆盖**（本轮不涉及）；**装备/皮肤层的轮廓颜色未单独验证**（日志不可见）。

## 1. 时间线（`latest.log` 行号）

| 时间 | 事件 | 行号 |
|---|---|---|
| 17:14:25 | 启动 `Loading Minecraft 26.3 …` | `:1` |
| 17:14:33 | **自定义描边管线注册** | `:66` |
| 17:14:33 | **方块描边 pass 注册**（afterTerrain routing） | `:67` |
| 17:14:36 | `Sound engine started` | `:109` |
| 17:14:54 | 玩家登录（集成服） | `:146` |
| **17:15:09** | **实体描边通道激活**（仅一次） | `:186` |
| 17:15:09 → 17:16:21 | **16 次 ping** | `:184-237` |
| 17:16:34 | `Saving worlds` | `:252` |
| 17:16:35 | **`Stopping!`**（无 crash） | `:256` |

## 2. 计数与配对

| 项 | 值 | 证据 |
|---|---|---|
| send ping | **16** | `:184-237`（req 0–15） |
| marker created | **16** | 紧随其后（id 0–15） |
| kind 分布 | **BLOCK 6 / ENTITY 10** | 例：`:184` ENTITY、`:203` BLOCK、`:237` BLOCK/danger |
| pingType 分布 | **attention 13 / danger 2 / loot 1** | attention：req 0–9、11、13、14；danger：req 12、15；loot：req 10 |
| removed / rejected | **0 / 0** | 全文 grep |
| 轮盘 | 0（本次未用轮盘） | 全文 grep |

> 说明：日志不记录 targetType，因此 **`dropped_item` 目标是否被本次覆盖无法从日志确认**（`loot` 类型通常对应掉落物，属推断）。

## 3. 异常（全部已知噪声）

| 时间 | 级别 | 内容 | 行号 | 判定 |
|---|---|---|---|---|
| 17:14:27 | WARN | `PerfmonDisabled … PerfOS counters` | `:59` | 环境噪声 |
| 17:14:34 | ERROR | `Failed to fetch user properties` + 401 堆栈 | `:77-88` | 认证噪声 |
| 17:14:34 | ERROR | `Failed to fetch Realms feature flags` + `SignedJWT: FabricMC` | `:99-100` | Realms 噪声 |
| 17:14:37 | ERROR | `Couldn't connect to realms` | `:123-124` | Realms 噪声 |
| 17:14:54 | WARN | `Requested post effect does not exist: minecraft:end_of_frame` | `:151` | 26.3 原版资产噪声 |

- **pingforit WARN/ERROR：0**；`pass failed`：0；`disabled for this session`：0；`hook` 未生效告警：0。
- 无 `Mixin apply failed` / 注入错误；`:56-57` 是 Mixin 子系统版本与 MixinExtras 初始化的 INFO。
- 2 WARN + 3 ERROR 全部为已知噪声；会话以 `Stopping!` 正常结束（`:256`）。

## 4. 日志能证明 / 不能证明

| 能证明 | 依据 |
|---|---|
| M7a 自定义管线 + 方块 pass 正常注册（无回退/无禁用） | `:66-67` |
| M7b 实体描边快照非空、通道被激活 | `:186`（一次性） |
| 端到端链路通（16/16 配对）、消息与渲染入口无异常 | §2、§3 |
| 无 per-frame 日志刷屏（实体/方块 pass 均无周期 INFO） | 全文无 `submitted=` 行 |

| **不能证明**（需用户描述/截图） | 原因 |
|---|---|
| 轮廓观感（粗细/亮度/是否像上游发光） | 日志不含像素信息 |
| 颜色是否与 ping 类型一致 | 日志不记录实际 outlineColor |
| **装备/皮肤层**是否与主体同色 | `outlineColor` 只传给主模型；layer 行为需实机看 |
| `dropped_item` 覆盖是否生效 | 日志无 targetType |
| OIT 下行为 | 本轮未开 OIT |

## 5. 结论

**日志侧通过。** M7a（方块线框）与 M7b（实体描边）在同一会话内共存、各自注册成功、链路与生命周期正常、无模组异常；实体描边通道在首次收到实体 ping 后激活一次且未刷屏。视觉层面以用户「功能正常」反馈为准，**装备/皮肤层颜色与 dropped_item 覆盖仍待专项确认**。

## 6. 证据索引

| 主题 | 位置 |
|---|---|
| 会话全文 | `run/logs/latest.log`（257 行，17:14:25–17:16:35） |
| 注册/激活行 | `latest.log:66`、`:67`、`:186` |
| ping 明细 | `latest.log:184-237` |
| 正常退出 | `latest.log:252`、`:256` |
| 异常 | `latest.log:59,77-88,99-100,123-124,151` |
| 规范与复审 | `docs/reviews/35-m7b-spec.md`、`36-review-m7b-entity-outline.md` |
