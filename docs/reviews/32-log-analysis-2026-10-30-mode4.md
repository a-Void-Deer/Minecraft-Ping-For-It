# 32 · mode 4 实机会话日志分析（2026-10-30 22:03–22:09）

> 分析：1号 repo-analyst｜2026-10-30｜**只读 `run/`，未改日志、未动 src**
> 分支：`exp/vanilla-lines-m7a` @ **`4bbcc92`**「feat(m7-prep): see-through block outline pipeline (E2)」；工作树干净。
> 开关：测试时 `run/vanilla-outline.txt=4`，**Lead 已在测试后删除**（现不存在）。

## 0. 结论先行（5 条）

1. **pipeline 创建成功且只创建一次**：`ping outline pipeline registered (depth test disabled, cull off, translucent)`（`latest.log:66`）+ `mode=4 (experiment branch)`（`:67`）；**无** `falling back` / `pipeline unavailable` / `pass failed` / `disabled`。
2. **链路与计数正常**：**28 条 `send ping` ↔ 28 条 `marker created`**，全部 `kind=BLOCK` / `pingType=attention`；`markerId 0–27` 连续；**0 removed / 0 rejected / 0 wheel**。
3. **`submitted` 采样正常**（变化才打）：12 条，`1,2,1,4,6,2,0,1,8,3,1,0`（`:216-295`），空闲回 0 且不刷屏，峰值 8，**无累积增长**。
4. **异常全是已知噪声**：3 WARN + 3 ERROR（Perfmon、401、Realms/SignedJWT×2、`end_of_frame`、**22:07:03 最小化窗口**，`:59-193`）；**0 条 pingforit WARN/ERROR**。
5. **本次不是 OIT 会话**：`run/options.txt` 里 **`improvedTransparency:false`**（`graphicsPreset:fancy`）→ **OIT 路径未覆盖**；如果要验 OIT 叠加，需用户开启该选项再跑一次。

## 1. 时间线（`latest.log` 行号）

| 时间 | 事件 | 行号 |
|---|---|---|
| 22:03:16 | 启动 `Loading Minecraft 26.3 …` | `:1` |
| 22:03:24 | **自定义管线注册成功**：`ping outline pipeline registered (depth test disabled, cull off, translucent)` | `:66` |
| 22:03:24 | **mode 4 生效**：`vanilla-lines outline: mode=4 (experiment branch)` | `:67` |
| 22:03:27 | 资源重载完成 | `:94` |
| 22:03:29 | `Sound engine started` | `:109` |
| 22:04:12 | 玩家登录（集成服） | `:148` |
| 22:08:01 → 22:08:58 | **28 次 ping**（`requestId=0` … `requestId=35`） | `:214-290` |
| 22:08:01 → 22:09:05 | 12 条 `submitted=` 采样（变化才打） | `:216-295` |
| 22:09:09 | `Saving worlds` | `:305` |
| 22:09:10 | **`Stopping!`**（正常退出） | `:309` |

> 进世界到首次 ping 之间有约 4 分钟静默（22:04:12 → 22:08:01），期间 `submitted` **没有**任何输出 → 变化才打 + 不刷屏的预期成立。

## 2. 计数与请求序列

| 项 | 值 | 证据 |
|---|---|---|
| send ping | **28** | `latest.log:214-289` |
| marker created | **28** | `latest.log:215-290` |
| 配对 | 逐条相邻、同秒或 +1 秒 | 例：`:214/215`（22:08:01，req 0 / id 0）… `:289/290`（req 35 / id 27） |
| pingType | `attention` ×28（全量） | 同上 |
| kind | `BLOCK` ×28 | 同上 |
| removed / rejected / wheel | **0 / 0 / 0** | 全文 grep |

**`requestId` 不连续是正常现象**：send 序列为 `0–18, 23, 27, 29–35`（缺 19–22、24–26、28），而 `markerId` 为 **0–27 连续**。说明这 8 个编号的请求**没有真正发出**（推断：客户端礼让/限流路径只递增请求号，不写 `send ping` 日志，也不产生 reject；日志本身只显示「没有 send 行」）。与「被本地限流而没发的请求不提示」的既有行为一致。

## 3. submitted 采样（变化才打）

| 时间 | 行号 | submitted |
|---|---|---|
| 22:08:01 | `:216` | 1 |
| 22:08:06 | `:219` | 2 |
| 22:08:11 | `:222` | 1 |
| 22:08:16 | `:229` | 4 |
| 22:08:21 | `:236` | 6 |
| 22:08:26 | `:239` | 2 |
| 22:08:31 | `:245` | **0**（标记全部过期） |
| 22:08:43 | `:255` | 1 |
| 22:08:48 | `:270` | **8**（本会话峰值） |
| 22:08:53 | `:279` | 3 |
| 22:08:58 | `:288` | 1 |
| 22:09:05 | `:295` | **0** |

- 与「过去 ~7 秒内创建的标记数」量级一致（峰值 8 与连续 ping 的密度吻合）；**没有单调上升**，说明 mode 4 下提交的仍是「存活标记」，未出现每帧重复累积。
- **0 不会被反复打印**：22:09:05 之后直到 `Stopping!` 无新行。

## 4. 异常（全部已知噪声）

| 时间 | 级别 | 内容 | 行号 | 判定 |
|---|---|---|---|---|
| 22:03:18 | WARN | `PerfmonDisabled … PerfOS counters` | `:59` | 环境噪声 |
| 22:03:26 | ERROR | `Failed to fetch user properties` + 401 堆栈 | `:77-88` | 认证噪声 |
| 22:03:27 | ERROR | `Failed to fetch Realms feature flags` + `SignedJWT: FabricMC` | `:99-100` | Realms 噪声 |
| 22:03:29 | ERROR | `Couldn't connect to realms` | `:123-124` | Realms 噪声 |
| 22:04:12 | WARN | `Requested post effect does not exist: minecraft:end_of_frame` | `:154` | 原版资产噪声 |
| 22:07:03 | WARN | `Couldn't acquire next surface texture … Cannot acquire minimized window` | `:192-193` | **窗口最小化**（用户操作），非模组 |

- **pingforit 相关 WARN/ERROR：0**；`pass failed`：0；`disabled for this session`：0；`falling back`/`pipeline unavailable`：0。

## 5. OIT（improved transparency）状态

| 项 | 值 | 证据 |
|---|---|---|
| `improvedTransparency` | **false** | `run/options.txt` |
| `graphicsPreset` | `fancy` | 同上 |
| 结论 | **本次会话未启用 OIT** → **OIT 路径未被覆盖**；`SubmitNodeCollectionMixin` 在 OIT 下的相位语义（31 号复审里 S3-1 的更正）仍需单独实机确认 | — |

## 6. 与 mode 1（20:58）会话对照

> **文件名会滚动**：每次启动都会把上一段 `latest.log` 压成新的 `-N.gz`。当前 `2026-10-30-7.log.gz`（mtime 22:03:16）= **20:58 会话**；19:19 会话现在是 `-6.gz`（mtime 20:58:45）。引用请以时间范围为准，别按编号对。

| 项 | mode 4（22:03–22:09） | mode 1（20:58:45–21:06:25） |
|---|---|---|
| 日志 | `latest.log`（310 行） | `2026-10-30-7.log.gz`（658 行） |
| 启动行 / 管线行 | `:66` 自定义管线 + `:67` mode=4 | `:65` **只有 mode=1**（内置管线，无 pipeline 注册行） |
| send / created | **28 / 28** | **165 / 165** |
| requestId 范围 | 0–35（28 条，含 8 个限流缺口） | 0–188（165 条，含 24 个缺口） |
| markerId | 0–27 连续 | 0–164 连续 |
| submitted 采样 | 12 条，峰值 8，回 0 | **59 条**，峰值 8，多次回 0（长时间 ping/停顿交替） |
| 异常 | 3 WARN + 3 ERROR（含最小化窗口） | 3 WARN + 3 ERROR（同一组噪声，无最小化） |
| removed / rejected | 0 / 0 | 0 / 0 |

**对照结论**：mode 4 与 mode 1 在**链路、计数、生命周期、日志节流**上行为一致；唯一新增是 `:66` 的自定义管线注册行，且**没有回退/降级日志**。

## 7. 日志能证明 / 不能证明

| 能证明 | 依据 |
|---|---|
| **E2 的自定义无深度管线创建成功**，且走的是 mode 4 分支 | `latest.log:66-67`（各只出现一次） |
| **没有回退到 mode 2**（否则会有 `falling back` WARN） | 全文 0 条 |
| 提交链路正常（28/28 配对）、无异常、无永久禁用 | §2、§4 |
| 描边 pass 在 mode 4 下仍按「存活标记数」提交，且日志节流生效 | §3 |
| 本次**未启用 OIT** | `options.txt: improvedTransparency=false` |

| **不能证明**（需用户描述/截图，或下次带 OIT 复测） | 原因 |
|---|---|
| **穿墙是否真的可见**（`NO_DEPTH_TEST` 视觉生效） | 日志只记「depth test disabled」，不记录实际深度状态/像素结果 |
| **水面/染色玻璃上的叠放顺序**（afterTerrain 相位是否符合上游） | 相位路由的视觉效果无法从日志判定 |
| **线宽是否 3.75px、ping 颜色是否一致** | 日志不记录线宽/颜色实测值 |
| **同一方块连点是否只画一条**（winner 去重） | 日志无 target 坐标/winner 明细；`submitted` 只能看总数 |
| **OIT 下的相位语义**（S3-1 更正的结论） | 本次 `improvedTransparency=false`，该路径未执行 |

## 8. 结论与后续排查清单

**日志侧结论：通过。** 自定义管线在 mode 4 下成功注册、无回退、无异常；28/28 配对、生命周期正常、日志节流符合预期；预热与退出都干净。**视觉结论仍待用户确认**（日志不具备判定能力）。

若用户反馈视觉问题，按以下顺序查：

| 现象 | 先查什么 | 证据来源 |
|---|---|---|
| 隔墙看不到线框 | ① 是否有 `falling back`（说明 mode 4 创建失败）；② 深度测试是否真关（`PingOutlineRenderType` 的 `DepthStencilState`）；③ 是否被 `SubmitNodeCollectionMixin` 路由到 `solid` 相位（alpha 分桶） | `latest.log:66` + 源码 `PingOutlineRenderType` / `SubmitNodeCollectionMixin` |
| 水面/玻璃上叠放不对 | `submitShapeOutline` 的 alpha 与 `afterTerrain` 路由；OIT 开启后 `oitTranslucent` 别名行为 | `docs/reviews/31-review-e2-pipeline-routing.md`、`AGENTS.md §7-12` |
| 线框太细/太粗 | `PingOutlineRenderType` 的 line width 是否被管线钳制；`LINES` 模式参数 | 源码 + 截图对比上游 3.75px |
| 同一方块重复 ping 出现多条 | winner 投影（`visibleWinnersInDimension`）+ 是否旧版全量遍历 | `VanillaLinesBlockOutline` 的循环处 + `docs/reviews/27` |
| 开了 OIT 才有问题 | 单独跑 `improvedTransparency=true`，看 `SubmitNodeCollectionMixin` 的路由与有无 `falling back` | 新会话日志 + 截图 |

## 9. 证据索引

| 主题 | 位置 |
|---|---|
| mode 4 会话全文 | `run/logs/latest.log`（310 行，22:03:16–22:09:10） |
| 管线注册 / mode 行 | `latest.log:66`、`:67` |
| ping 配对 | `latest.log:214-290` |
| submitted 采样 | `latest.log:216-295` |
| 正常退出 | `latest.log:305`、`:309` |
| mode 1 对照会话 | `run/logs/2026-10-30-7.log.gz`（658 行，20:58:45–21:06:25） |
| OIT 设置 | `run/options.txt`（`improvedTransparency:false`） |
| 开关文件（测试后已删） | `run/vanilla-outline.txt` 现不存在 |
