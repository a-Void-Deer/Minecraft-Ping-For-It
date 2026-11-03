# 18 · 恢复后实机验收（M6.5-restored）

> 日期：2026-10-30 12:30–12:33｜会话：`2026-10-30-7.log.gz`（快照 `run/dsh-logs/test-20261030-1230.log`）｜tag：`port-M6.5-restored`

## 1. 结论

**通过。** 重建后的代码在实机跑通了 M4 闭环 + M6 轮盘 + M6.5 诊断字段；**0 条模组相关 ERROR**（5 条 ERROR 均为 Realms/Minecraft Services 联网噪声）、0 条 HUD 失败、0 次崩溃、无 reject/cancel/移除事件。

## 2. 会话原始数据

| 指标 | 数值 |
| --- | --- |
| 会话区间 | 12:30:37 启动 → 12:31:30 进世界 → 12:33:04 退出（约 2.5 分钟） |
| `send ping` | **37** |
| `marker created` | **36** |
| 配对 | 36/37 成对；唯一未配对的 `requestId=40` 出现在退出瞬间，**日志里没有任何 reject / cancel / 异常记录**（不推断原因） |
| kind/pingType 不一致 | **0** |
| `wheel open` | 8（全部 `types=[attention, go_to, danger]`，即 block 桶） |
| `wheel select` | 8，**select → send 的类型 8/8 一致** |
| 轮盘扇区 | sector 0 / 1 / 2 均命中 |
| GUI scale | 6（高缩放下的坐标换算被验证） |
| HUD 失败 | 0（无 `marker HUD pass failed`） |
| 崩溃 | 0（12:24/12:25 的两次崩溃发生在修复前，之后无新增 crash-report） |
| ERROR | **0 条模组相关**（会话共 5 条 ERROR，全部是 Realms / Minecraft Services 联网噪声，行号 75/85/122/164/188） |

### ping 类型分布（本次覆盖 4 种类型 / 3 个桶）

| kind | pingType | 次数 |
| --- | --- | --- |
| BLOCK | attention | 6 |
| BLOCK | danger | 4 |
| BLOCK | go_to | 2 |
| ENTITY | attention | 4 |
| ENTITY | **loot** | 6 |
| LOCATION | go_to | 15 |

> `loot`（掉落物桶的默认类型）是**事故前从未在日志里出现过**的类型，本次首次实机验证通过。

## 3. M6.5 诊断字段验证

```
[12:31:50] wheel open types=[attention, go_to, danger]
[12:31:50] wheel select pingType=attention scale=6 mouse=237,97 sector=0
[12:31:51] wheel select pingType=danger   scale=6 mouse=165,102 sector=2
[12:31:53] wheel select pingType=go_to    scale=6 mouse=212,160 sector=1
```

- `scale` / `mouse` / `sector` 三个字段全部出现 → 事故中丢失的 `WheelSelectionDiagnostics` 接线已恢复（`6ad0207`）。
- `scale=6` 说明鼠标坐标换算在非默认 GUI 缩放下也正确。

## 4. 环境噪声（与模组无关）

| 日志 | 归属 |
| --- | --- |
| `Failed to fetch user properties` (401) | 离线开发账号 |
| `Couldn't connect to realms` | 离线 |
| `Mappings not present!` | 官方映射常规提示 |

## 5. 验收判定

| 项 | 判定 |
| --- | --- |
| 按键 → C2S → 服务端 → S2C → HUD（M4） | ✅ 36/37 配对（末条因断开未到） |
| 轮盘开/选/超时（M6） | ✅ 8 次 open、8 次 select、类型 8/8 一致 |
| 7 类型分桶 | ✅ attention / go_to / danger / loot 实测；`destroy`/`take`/`request` 属 `entity_block` 桶，本次未触发（非缺陷） |
| 标记 HUD 上游对齐（M6.5） | ✅ 实机可见；诊断字段齐全 |
| 崩溃 / HUD 失败 / 拒绝 | ✅ 0 |

## 6. 遗留

1. `destroy` / `take` / `request`（`entity_block` 桶）需 ping 箱子/熔炉这类方块实体才能触发，留给 M7 验收时一并覆盖。
2. 屏外箭头在本次会话中未被明确验证（日志无相关记录，属视觉项），M7 一并复核。
