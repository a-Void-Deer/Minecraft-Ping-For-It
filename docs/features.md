# FEATURES.md · Ping For It 0.4.2-pfi-beta1 功能规格（开发查阅用）

> 用途：查功能、查字段、查默认值。**不是移植方案**（移植见同目录 03/04 与后续文档）。
> 源码根：`Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1`（只读）。简写：`common/…/common` = `common/src/main/java/nx/pingwheel/common`。
> 证据格式 `文件:行号`；未能从源码确证的标 **待确认**。本文所有数字均来自源码，不照抄二手结论。

---

## 0. 速查

| 维度 | 结论 | 证据 |
| --- | --- | --- |
| mod id / 命令根 | `pingforit`（本项目移植后为 `ping-for-it`） | `Global.java:14,25` |
| 目标类型 | 5 种：`dropped_item / entity / entity_block / block / location` | `domain/TargetTypeCatalog.java:46-82` |
| ping 类型 | 7 种：`attention / danger / go_to / loot / destroy / take / request` | `domain/PingTypeCatalog.java:38-44` |
| 网络包 | 14 个 payload（6 C2S + 8 S2C），通道 `pingforit-c2s` / `pingforit-s2c` | `Global.java:23-24`；`network/` 14 个包类 |
| 客户端配置 | 27 个持久化字段（24 个在设置界面，2 个列表 + 1 个映射非界面） | `config/ClientConfig.java:26-65` |
| 服务端配置 | 6 个字段，其中 5 个可远程改 | `config/ServerConfig.java:15-21`；`docs/architecture/config/changing-server-config.md:17-27` |
| 语言 | 8 个 locale；en_us 163 键，其余 148–158 键（**均有缺键**） | `assets/pingforit/lang/*.json`（本报告实测） |
| 世界内渲染 | HUD 图标 + 方块原生形状描边 + 实体发光；无方块/物品注册 | `render/OverlayRenderer.java:12-54`、`mixin/LevelRendererMixin.java:142-181` |

---

## 1. 功能总览与用户故事

### 1.1 一句话

准星指向**方块 / 实体 / 空间点**，按 ping 键把「这里/这个」用颜色 + 图标 + 名字播报给同一频道（或同队）的玩家；短按发默认类型，长按开轮盘选类型（README.md:3-4, :24-29）。

### 1.2 单人 vs 多人

| 场景 | 能做什么 | 依据 |
| --- | --- | --- |
| 单人（无其他玩家） | ping 仍会创建并被服务端接受、回显给本人（受众=自己），可用来做标记/练手；集成服务器同样走权威链路 | `core/ServerCore.java:416-487`；受众计算 `:702-723` |
| 多人（同服） | 同频道/同队玩家看到标记；服务端限流、距离、类型全部校验；他人可用轮盘中心取消**自己**的标记 | `core/ServerCore.java:436-449`、`marker/ServerMarkerStore.java:105-125` |
| 装了可选集成 | VoiceChat/FTB Teams 改变「队伍」判定；DH 让远处地形也能 ping 到；Sable 让子关卡里的方块可 ping；Mod Menu 提供设置入口 | 见 §8（**26.3 现状**：Mod Menu ✅ 已接；VoiceChat/DH 仍是桩；FTB Teams/Sable 平台强制跳过） |

### 1.3 七种 ping 类型（语义 / 颜色 / 图标）

颜色为 24-bit RGB；两个值为 **outlineColor**（描边）与 **textColor**（文字）。**所有类型的 `iconId` 都是 empty**，按 `PingType.java:11-15` 的约定 = 「复用默认 ping 图标并染色」，因此**没有按类型区分的独立图标文件**，只有 `textures/arrow.png` 与 `textures/ping.png` 两张图。

| id | 显示名（en_us） | 播报短语（phrase） | outlineColor | textColor | 图标 |
| --- | --- | --- | --- | --- | --- |
| `attention` | Attention | ATTENTION | `0xFFC247` | `0xFFAA00` | 默认图标染色 |
| `danger` | DANGER | alert | `0xFF4D4D` | `0xFF5555` | 同上 |
| `go_to` | GO TO | Going to | `0x4DB8FF` | `0x55FFFF` | 同上 |
| `loot` | Loot | LOOT | `0x52D273` | `0x55FF55` | 同上 |
| `destroy` | Destroy | DESTROY | `0xE66BDD` | `0xF0A0EA` | 同上 |
| `take` | TAKE | Take things | `0x52D273` | `0x55FF55` | 同上 |
| `request` | Request | REQUEST | `0x8C8CFF` | `0xB8B8FF` | 同上 |

证据：`domain/PingTypeCatalog.java:38-44`（颜色/键名）、`assets/pingforit/lang/en_us.json`（显示名与 phrase）、`domain/PingType.java:17-24`（字段含义）。

### 1.4 目标类型 → 可用 ping 类型（谁能用哪几个）

| 目标类型 | 优先级 | kind | 可用 ping 类型（轮盘扇区顺序） | 短按默认 |
| --- | --- | --- | --- | --- |
| `dropped_item` | 100 | ENTITY | loot / attention / danger | **loot** |
| `entity` | 200 | ENTITY | attention / danger / go_to | **attention** |
| `entity_block` | 250 | BLOCK | attention / destroy / take / request | **attention** |
| `block` | 300 | BLOCK | attention / go_to / danger | **attention** |
| `location` | MAX | LOCATION | go_to / attention / danger | **go_to** |

证据：`domain/TargetTypeCatalog.java:46-82`。优先级越小越先匹配，同优先级按声明顺序（`TargetTypeCatalog.java:92-103`）。反向看：`loot` 只对掉落物；`destroy/take/request` 只对带方块实体的方块（`entity_block`）。

> 注意 `request` 只出现在 `entity_block`；`block` 没有 loot/destroy/take/request。这是「轮盘扇区数量随目标变化」的根因。

---

## 2. 交互规格

### 2.1 按键（默认）

| 绑定 | 默认键 | 常量/位置 |
| --- | --- | --- |
| Ping | **Mouse5** | `util/InputUtils.java:17` |
| 切换「穿透透明方块」 | LeftShift | `InputUtils.java:18-22` |
| 切换「标记黑名单目标」 | LeftControl | `InputUtils.java:23-27` |
| 切换「标记流体」 | LeftAlt | `InputUtils.java:28-32` |
| 打开设置 | 未绑定（-1） | `InputUtils.java:33` |

三个切换键是**常驻切换**，按下即改配置并保存，且 HUD 有提示（`InputUtils.java:134-162`）。

### 2.2 短按 / 长按判定

| 参数 | 生效默认 | 兜底常量（无配置时） | 范围 / 步进 |
| --- | --- | --- | --- |
| 长按阈值 `wheelHoldMillis` | **200 ms** | `300`（`PingInteractionStateMachine.java:51`） | 50–2000，步进 10（`ClientConfigBounds.java:12-15`） |
| 轮盘超时 `wheelTimeoutMillis` | **3000 ms** | `5000`（`:57`） | 500–30000，步进 100（`:22-25`） |

> 关键事实：`300/5000` 只是**没有配置 supplier 时的兜底**；真实值在 `PingInteractionStateMachine.java:238`（hold）与 `openWheel() :550-554`（timeout）从配置读入并按 Bounds 钳制。照抄常量会导致手感偏差（04 报告 R7 同结论）。

判定流程：
1. 按下 → 相位 IDLE→PRESSED，开始捕获（不设「捕获超时」：捕获没回来就一直等，见 `PingInteractionStateMachine.java:443-470`）。
2. 渲染帧推进：`elapsed >= hold` → WHEEL_OPEN（`:403-405`）。
3. 松手时：**若轮盘从未真正打开过** → 按短处理，直接以该目标类型的默认 ping 提交（`:455-465`，注释强调「以 presentFrame 是否真开过为准」，不是按 tick 量化后的 elapsed）。
4. 松手时已在轮盘中 → 提交扇区 / 中心取消 / 无选择（见 2.3）。

### 2.3 轮盘

| 动作 | 结果 |
| --- | --- |
| 松开在某个扇区 | 提交该 ping 类型（`:503-529`） |
| 松开在中心区 | 触发「取消候选」；有候选→移除自己最近的那个；无候选→**静默结束**（`:511-523`） |
| 松开在轮盘外/非法扇区 | 归一到 NONE，静默结束（`:564-568`，`:526-528`） |
| 超时（tick 侧与帧侧双查） | 静默结束，**不产生任何动作**（`:481-487`、`:386-394`） |

轮盘内容来源：`openWheel()` 把 `targetType().pingTypes()` 冻结成扇区列表（`:549-562`），因此**扇区集合取决于按下瞬间的目标类型**。几何：角度 0 在正上方、顺时针增长（GUI y 向下），起点 ∈ [0,2π)、终点 ∈ (start, 2π]（`client/wheel/WheelSector.java:7-17`）。

### 2.4 中心取消：五条候选规则（按序）

`interaction/cancel/CancelCandidatePicker.java:13-36`：

1. 只考虑**自己**拥有的标记；
2. 只考虑与本地玩家**完全同维度**的；
3. 只考虑落在**视线方向半角圆锥**内的（比较归一化点积与 cos(halfAngle)，不用 acos；阈值取 `Math.nextDown(cos)` 使边界包含）；
4. 眼位置零距离的候选**无方向定义，视为合规**；
5. 余下按**到眼位置距离平方最小**取；等距平局取 **MarkerId 较大者**（源码自述为 spec 未规定的约定，靠 id 单调唯一保证确定性）。

半角默认 **5°**（`:42`），合法范围 (0,180]（`:59-60`）；可配置 `cancelHalfConeAngleDegrees`（1–90°）。

### 2.5 鼠标捕获与释放

`client/WheelMouseCapture.java:9-38`：

- 轮盘打开且无 screen、鼠标处于 grab：调用 `MouseHandler#releaseMouse()` 让光标可选扇区，并记住「是模组释放的」。
- 退出 WHEEL_OPEN（提交/取消/超时/被新交互顶替）时：仅当「是模组释放的」且**当前无 screen** 才重新 grab；若此时有 screen，**延后到后续 tick**，绝不从 screen 手里抢鼠标（`:24-26`）。
- 断线时同样规则清理。

### 2.6 按键冲突处理（与「拾取方块」共用键）

`util/InputUtils.java:48-78` + `util/PingClickArbitration.java:33-48`：

| 情形 | 行为 |
| --- | --- |
| ping 键**未**与拾取方块共用 | 直接 claim（`Plan(false,true,true)`） |
| 共用，且当前**可拾取**（准星 MISS，或非创造模式命中实体） | 同时排空两个 mapping 的点击计数，任一收到边即 claim（`Plan(true,true,true)`）——兼容 Fabric 单槽与 Forge/NeoForge 多 mapping |
| 共用，但当前**不可拾取** | 只排空 ping 计数、**不 claim**，把原版拾取留给原版（`Plan(false,true,false)`） |

补充：同一次物理按压只会 arm 一次（`:68-75`，防止共享绑定的两次回调开两个交互）；`isPingHotkeyDown()` 是纯事件驱动，不按当前准星/模式/绑定重新判定（`:199-209`）；断线或失焦时 `resetPingHold()` 清 claim（`:219-227`）。三个切换键在屏幕打开时只记 press、不触发效果，且会吞掉 GLFW 重复边直到 release（`:85-124`）。

### 2.7 长按兼容模式

`client/LongPressCompatibilityController.java:15-27`：仅在正常短按时序**已经发出 CreatePing 之后**，回填启动一次虚拟交互（backdated），用于兼容某些环境的按键时序；单槽状态，不保留定时器/线程。`longPressCompatibilitySliceMillis` 是兼容邻接窗口，默认 20 ms（10–500，步进 5），且**有效 slice ≤ 有效 hold 的一半**（`ClientConfigBounds.java:84-96`；文档 `docs/architecture/input/long-press.md:7-11`）。

---

## 3. 标记生命周期与权威判定

### 3.1 端到端

| 阶段 | 关键行为 | 证据 |
| --- | --- | --- |
| ① 按下采样 | 在**物理按键边**采样射线并冻结（不在松手时重算） | `mixin/KeyMappingMixin.java:15-18`；`CommonClient.java:146-179`；决策文档 `docs/decisions/D0005-press-time-capture.md:9-18` |
| ② 客户端派发 | 只发 `requestId + Target + pingTypeId`；本地礼让限流失败则**不发包只打 debug** | `network/MarkerCreateC2SPacket.java:11`；`client/ClientPingActionDispatcher.java:107-123` |
| ③ 服务端受理 | 顺序：结构损坏→INVALID_REQUEST；per-player 限流→RATE_LIMITED；频道门禁→CHANNEL_DISABLED；**冻结受众**；算 arrivalTick/expiresAtTick；校验并入库；拒绝则回 reject；成功则**先 MarkerCreated 后 WinnerChanged** | `core/ServerCore.java:416-487`（受众 `:452`，生命周期 `:454-455`，顺序注释 `:481-486`） |
| ④ 校验 | 见 3.2 | `marker/MinecraftAuthoritativeTargetValidator.java` |
| ⑤ 广播 | `MarkerCreatedS2CPacket(snapshot, targetName, ownerName)` 只发给仍在线且仍在受众表里的玩家 | `ServerCore.java:732-735` |
| ⑥ 客户端落地 | 校验腐坏/已被移除则丢弃；**仅首次**播定向音效与聊天行 | `client/ClientPingRuntime.java:980-1011`（音效 `:1017-1036`，聊天 `:1044-1091`） |
| ⑦ 渲染 | 见 §7 | `render/`、`client/marker/` |
| ⑧ 过期/移除 | 每 tick 扫描过期→广播 MarkerRemoved + WinnerChanged；玩家断线移除其标记并把自己从他人受众摘掉 | `ServerCore.java:535-559`、`:569-596`；`marker/ServerMarkerStore.java:148,160-196` |

### 3.2 服务端校验规则（客户端射线不作数）

| 校验 | 规则 | 证据 |
| --- | --- | --- |
| 请求者 | UUID 必须解析到在线 ServerPlayer，否则 INVALID_REQUEST | `MinecraftAuthoritativeTargetValidator.java:127-130` |
| 维度 | 目标 dimensionId 必须**字符串完全等于**请求者当前 level；不做跨维度 | `:134-137`（不一致 → TARGET_GONE） |
| 实体 | 必须在请求者 level 中存在、存活、未被移除；锚点取服务端实体位置；**目标是玩家且 playerTracking 关闭 → 归一化成该玩家位置的 LocationTarget（不拒绝）**（:203-208） | `:186-223` |
| 方块 | 位置已加载、方块状态与客户端给的 registry id 一致；否则 TARGET_GONE | `:239-268` |
| 外部方块(Sable) | provider 校验：providerId、候选状态已提交、维度、locator、注册 id、子关卡存活、非空气等 | `integration/sable/server/SableExternalBlockServerProvider.java`（详见 04 报告 §1.4d） |
| 距离 | 眼到锚点**平方距离 > pingDistance²** → OUT_OF_RANGE；默认 2048 | `:321-333`；上限注入 `ServerCore.java:183` |
| 视线 | **服务端不做 LOS 校验**（源码无 lineOfSight/canSee 调用）；遮挡只在客户端射线阶段处理（`math/Raycast.java:88` 用 `level.clip`） | 本报告全文检索结论 |
| 分类 + 类型 | 服务端重新分类目标，再校验 ping 类型存在且属于该 TargetType，否则 INVALID_PING_TYPE | `marker/MarkerCreationService.java:218,226-239` |
| 外部物化 | 外部方块需 provider 物化 + 再分类，失败释放引用 | `MarkerCreationService.java:247-288` |

拒绝原因枚举（8 种）：`TARGET_GONE / INVALID_PING_TYPE / OUT_OF_RANGE / CHANNEL_DISABLED / RATE_LIMITED / INVALID_REQUEST / NOT_OWNER / NOT_FOUND`（`marker/MarkerRejectReason.java:10-19`）。移除原因 4 种：`CANCELLED / EXPIRED / TARGET_INVALID / OWNER_DISCONNECTED`（`MarkerRemovalReason.java:6-11`）。

### 3.3 受众与赢家

- **受众在创建时冻结**：频道 = 服务端存的服务端频道（不信任包内频道字段）；`AUTO` 等模式按团队上下文过滤；返回不可变列表（`ServerCore.java:697-723`）。创建后改频道不改变既有标记的受众。
- **同目标赢家**：按 `arrivalTick` 升序、再比 `MarkerId`，**后到者赢**（`marker/MarkerWinner.java:22-23,64`）；**输家保留**在库里，赢家移除后自动回退（`ServerMarkerStore.java:318-340`）。
- **移除权限**：必须 owner 本人，否则 NOT_OWNER；不存在则 NOT_FOUND（`ServerMarkerStore.java:105-125`、`ServerCore.java:507-525`）。
- **过期**：`expiresAtTick = arrivalTick + syncDuration*20`（秒×TPS），每 tick 扫（`ServerCore.java:454-455,535-559`）。

---

## 4. 网络协议（14 个 payload）

命名空间：C2S `pingforit-c2s`、S2C `pingforit-s2c`（`Global.java:23-24`）。注册点（Fabric）：`fabric/.../FabricMain.java:45-58`，客户端 `FabricClient.java:45-91`。

### 4.1 C2S（6）

| # | payload id | 字段 | 触发时机 | 客户端侧校验（isCorrupt） |
| --- | --- | --- | --- | --- |
| 1 | `ping-location` | `channel:String, pos:Vec3, entity:UUID(nullable), sequence:int, dimension:int` | 旧家族「位置 ping」；服务端仍处理以兼容频道握手 | `channel == null` |
| 2 | `update-channel` | `channel:String` | 玩家改频道（/pingforit channel 或配置） | `channel == null` |
| 3 | `marker-create` | `requestId:long, target:Target, pingTypeId:String` | 短按/轮盘提交 create | `requestId<0 || target==null || pingTypeId 空` |
| 4 | `marker-remove` | `markerId:MarkerId` | 删除自己的标记（目前主要经轮盘中心） | `markerId == null` |
| 5 | `server-config-request` | `requestId:long` | 打开设置界面服务端页，请求快照（正数 id 关联回包） | `requestId <= 0` |
| 6 | `server-config-update` | `changedFields:int(mask), defaultChannelMode, playerTrackingEnabled:boolean, msToRegenerate:int, rateLimit:int, syncDuration:int` | 提交服务端设置（仅改动字段掩码） | `!ServerConfigUpdate.isValid()` |

### 4.2 S2C（8）

| # | payload id | 字段 | 触发时机 | 客户端侧校验（isCorrupt） |
| --- | --- | --- | --- | --- |
| 7 | `marker-created` | `snapshot:MarkerSnapshot, targetName:TargetNameJson, ownerName:String` | 服务端接受创建，广播给受众 | snapshot/targetName/ownerName 任一 null；ownerName 空或超长 |
| 8 | `marker-removed` | `markerId:MarkerId, reason:MarkerRemovalReason` | 取消/过期/目标失效/owner 断线 | 任一 null |
| 9 | `marker-winner-changed` | `targetKey:TargetKey, winnerId:Optional<MarkerId>` | 同目标赢家变化（新建/移除/过期） | `targetKey == null || winnerId == null` |
| 10 | `marker-rejected` | `requestId:long, requestKind:MarkerRequestKind, reason:MarkerRejectReason` | 只回给请求者 | requestId<0 或枚举 null |
| 11 | `rate-limit-policy` | `rateLimit:int, msToRegenerate:int` | 服务端限流参数变化时广播 | 任一 < 0 |
| 12 | `sync-duration-policy` | `syncDuration:int` | 服务端时长策略变化时广播 | 超出 1–3600 |
| 13 | `server-config-snapshot` | `requestId:long, canEdit:boolean, defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit, syncDuration` | 回应 server-config-request | requestId<=0、枚举 null、负数、时长越界 |
| 14 | `ping-location` | `channel, pos, entity, sequence, dimension, author:UUID` | 旧家族广播（客户端验证后**忽略渲染**） | `channel == null` |

### 4.3 字段与容错细节

- **MarkerSnapshot** = `id(MarkerId), owner(UUID), target(Target), targetTypeId(String), pingTypeId(String), anchor(MarkerAnchor: x/y/z double), arrivalTick(long), expiresAtTick(long)`；不携带颜色/名字/频道（`marker/MarkerSnapshot.java:20-29,13-18`）。
- **Target** = sealed 四变体：`EntityTarget(dimensionId, EntityLocator)`、`BlockTarget(dimensionId,x,y,z,blockRegistryId)`、`ExternalBlockTarget(dimensionId, providerId, stableTargetId, expectedBlockRegistryId, providerLocator, hasBlockEntity)`、`LocationTarget(dimensionId,x,y,z)`（`domain/Target.java:31,37,58,91,253`）。外部标识符上限 256（`Target.java:101`）。
- **TargetKey**（赢家分组键）= 上述目标的身份投影；未提交的 external 目标不能生成 key（`marker/TargetKey.java:37-56`）。
- **长度上限**：`MAX_ID_LENGTH=256`、ownerName ≤256 字符且 ≤1024 字节、`MAX_NAME_LENGTH = TargetNameJson.MAX_LENGTH`（`network/MarkerPacketCodec.java:34-36,297`）。
- **容错**：所有包经 `PacketHandler.readSafe` 反射构造，任何异常 → 返回空实例哨兵并**清空剩余字节**（`network/PacketHandler.java:16-43`）；入口第一件事 `isCorrupt()` 短路（服务端 `ServerCore.java:421`，客户端 `CommonClient.java:603`）。字符串带长度上限写入（`MarkerPacketCodec.java:49-64`），枚举按 name 字符串传输（`:69-74`），布尔严格 0/1（`:243-247`）。
- **线程**：C2S 一律 `server.execute(...)` 回主线程；S2C 一律 `client.execute(...)`，客户端 marker store 主线程封闭（`FabricMain.java:60-107`、`FabricClient.java:45-91`、`CommonClient.java:591-601`）。

---

## 5. 配置

文件：`config/pingforit.json`（客户端）、`config/pingforit.server.json`（服务端）（`config/ClientConfig.java:340`、`config/ServerConfig.java:48`）；JSON 内含 `pingforit-version` 版本键，支持迁移/未来版本保护/损坏备份（详见 04 报告 §2.4 与 `docs/architecture/config/revisioning.md`）。

### 5.1 客户端 27 项

| # | 键 | 类型 | 默认 | 范围/枚举 | 含义 | 界面 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `pingVolume` | int | 100 | 无钳制 | ping 音效音量倍数（`/100f` 传给音效） | 有 |
| 2 | `pingDistance` | int | 2048 | 无钳制；常量 `MAX_PING_DISTANCE=2048` | 客户端射线最大距离 | 有 |
| 3 | `itemIconVisible` | bool | true | — | HUD 是否显示目标物品图标 | 有 |
| 4 | `directionIndicatorVisible` | bool | true | — | 屏幕外目标是否画方向箭头 | 有 |
| 5 | `passThroughTransparentBlocks` | bool | false | — | 射线是否穿透透明方块 | 有（可键切换） |
| 6 | `markBlacklistedTargets` | bool | false | — | 是否标记被黑名单排除的目标 | 有（可键切换） |
| 7 | `markFluids` | bool | false | — | 是否允许 ping 流体 | 有（可键切换） |
| 8 | `playerInfoMode` | enum | HOLD | HOLD/DISABLED/ALWAYS/COMPACT | 玩家名显示策略（HOLD=按住玩家列表才显示） | 有 |
| 9 | `teamColorMode` | enum | FULL | FULL/DISABLED/PING_ONLY/LABELS_ONLY | 队伍颜色应用到哪些元素 | 有 |
| 10 | `entityBlockRenderMode` | enum | ALL | ALL/COMPATIBLE | 带方块实体方块允许的几何来源 | 有 |
| 11 | `pingSize` | int | 100 | 百分比，无钳制 | HUD 标记/箭头缩放 | 有 |
| 12 | `configurationNoticeSize` | int | 200 | 0–1000 步 10 | 配置提示尺寸 | 有 |
| 13 | `wheelHoldMillis` | int | 200 | 50–2000 步 10 | 长按开轮盘阈值 | 有 |
| 14 | `longPressCompatibilityMode` | bool | false | — | 长按兼容模式开关 | 有 |
| 15 | `longPressCompatibilitySliceMillis` | int | 20 | 10–500 步 5，且 ≤hold/2 | 兼容邻接窗口 | 有 |
| 16 | `wheelTimeoutMillis` | int | 3000 | 500–30000 步 100 | 轮盘超时 | 有 |
| 17 | `cancelHalfConeAngleDegrees` | int | 5 | 1–90 | 中心取消的视线半角 | 有 |
| 18 | `wheelInnerRadius` | int | 8 | 6–120，且 ≤ outer-8 | 轮盘内径 | 有 |
| 19 | `wheelOuterRadius` | int | 54 | 20–300 | 轮盘外径 | 有 |
| 20 | `wheelOpacity` | int | 75 | 0–100 步 5 | 轮盘不透明度 | 有 |
| 21 | `wheelFontSize` | int | 200 | 10–1000 步 10 | 扇区标签字号 | 有 |
| 22 | `wheelTargetFontSize` | int | 200 | 10–1000 步 10 | 目标名标签字号 | 有 |
| 23 | `markerDisplayDuration` | int | 0 | 0=跟随服务端；>0 上限 3600 秒 | 本地标记显示时长覆盖 | 有 |
| 24 | `blockDisplayWhitelist` | list | ["*:*"] | 语法见 7.2 | 允许描边的方块 | 文件/动作 |
| 25 | `blockShapeBlacklist` | list | [] | 同上 | 强制禁用形状描边的方块 | 文件/动作 |
| 26 | `channel` | string | "" | ≤128 字符 | 当前频道（空=默认频道） | 有 |
| 27 | `serverChannels` | map | {} | ip→channel | **按服务器 IP 分桶**的频道表 | 无（内部） |

补充：另有 **5 个隐藏字段**（不在设置界面、可手改 JSON）：`raycastDistance=1024`、`safeZoneLeft/Right/Top/Bottom=5/5/5/60`（`ClientConfig.java:67-72`）。`channel` 读写按 `GameContext.getCurrentServerIp()` 在 `serverChannels` 与全局 `channel` 间切换（`:78-94`）。

> 数值钳制只覆盖交互/轮盘/时长类（`ClientConfig.validate()` 在 `:207-300`）；`pingVolume/pingDistance/pingSize` **未在 validate 中钳制**，只有常量参考，实际使用点边界 **待确认**。

### 5.2 服务端 6 项（★ = 可远程改，共 5 个）

| 键 | 类型 | 默认 | 范围/枚举 | 含义 | 远程可改 |
| --- | --- | --- | --- | --- | --- |
| `defaultChannelMode` | enum | AUTO | AUTO/DISABLED/GLOBAL/TEAM_ONLY | 空频道时的默认可见范围 | ★ |
| `playerTrackingEnabled` | bool | true | — | 关闭后玩家目标被归一化为位置点（不再跟踪玩家实体） | ★ |
| `msToRegenerate` | int | 1000 | ≥0 | 限流令牌恢复间隔（毫秒） | ★ |
| `rateLimit` | int | 5 | ≥0（0=不限） | 每玩家创建配额 | ★ |
| `syncDuration` | int | 7 | 1–3600 秒（旧键 `pingDuration` 作别名读入） | 标记寿命 | ★ |
| `pingDistance` | int | 2048 | 1–2048 | 服务端接受的最大距离 | ✗（**仅 JSON**） |

AUTO/DISABLED/GLOBAL/TEAM_ONLY 的实际判定：空频道 + DISABLED → 拒绝（CHANNEL_DISABLED）；TEAM_ONLY 且无队伍 → 拒绝；GLOBAL → 不过滤；AUTO（及其它）→ 只发给**同团队上下文**的玩家（`ServerCore.java:356-391`、`:437-449`、`:703-723`）。

---

## 6. 命令与权限

**只有客户端命令，无服务端命令、无权限节点**（`command/ClientCommandBuilder.java` 全文件无 `requires`；注册走 `ClientCommandRegistrationCallback`，`fabric/.../FabricClient.java:110-118`）。根命令 `/pingforit`（`Global.java:25`）。

| 命令 | 参数 | 行为 | 上限/失败 |
| --- | --- | --- | --- |
| `/pingforit` | — | 打印帮助 | — |
| `/pingforit help` | — | 同上 | — |
| `/pingforit config` | — | 打开设置界面（切主线程） | — |
| `/pingforit channel` | — | 查询当前频道 | 空则提示「正在用默认频道」 |
| `/pingforit channel <name>` | string | 设置频道并保存 | >128 字符 → **拒绝并返回 0**；`""` 表示回默认频道 |

证据：`ClientCommandBuilder.java:27-86`；上限 `ClientConfig.java:76`；频道按服务器 IP 分桶（§5.1 #27）。

---

## 7. 渲染与反馈

### 7.1 HUD（世界内标记的屏幕投影）

| 内容 | 规则 | 证据 |
| --- | --- | --- |
| 总入口 | 取 `MarkerOverlayState.renderViews()`；无玩家/世界/视图直接返回 | `render/OverlayRenderer.java:12-54` |
| 深度错开 | `translate(0,0,-views.size()*16f)` 逐条错开，避免 z-fighting | `:29-30` |
| 维度 | 只画**当前维度**的标记 | `:35` |
| 屏幕外 | 仅在 `directionIndicatorVisible` 时画箭头（含背面相机翻转） | `:39-53`、`render/DirectionIndicatorRenderer.java:43-75` |
| 图标 | 目标为掉落物且 `itemIconVisible` 时显示物品图标；否则显示 ping 图标 | `client/marker/MarkerView.java:193`、`render/PingLocationRenderer.java:42` |
| 文本 | 距离（米，1 位小数）在图标上、目标名在上；目标名**恒为不透明白**，不继承队伍/ping 颜色 | `PingLocationRenderer.java:40-47` |
| 队伍颜色 | FULL / LABELS_ONLY 时玩家名用队伍色；否则 RESET | `:34,52-57` |
| 玩家信息 | ALWAYS 或（HOLD 且按住玩家列表）显示详细名；COMPACT 只显示紧凑标签 | `:37-38,49-56` |
| 缩放 | `pingSize/100` 作用于标记与箭头 | `MarkerView.java:264`、`DirectionIndicatorRenderer.java:51` |

### 7.2 方块描边

- 用方块**原生 VoxelShape**（非满方块也按真实形状），可穿墙（through-wall outline）——README.md:36-43。
- **白名单**（`blockDisplayWhitelist`，默认 `*:*`）与**形状黑名单**（`blockShapeBlacklist`，默认空）语法：`namespace:block`、`namespace:*`、`*:*`、`#namespace:tag`；并集语义、声明顺序；非法条目直接评估时 fail-false，配置加载时直接拒绝（`client/outline/BlockDisplayWhitelist.java:23-34`）；未注册方块一律 false（`:179-181`）。
- 目标安全门由 `BlockDisplayPolicy` 的 `matchesOrdinaryBlock / matchesEntityBlock` 分开处理，避免把带方块实体的方块塞进普通模型路线（`BlockDisplayWhitelist.java:148-172`）。
- 透明方块穿透由 `passThroughTransparentBlocks` 控制，作用于**客户端射线**（`ClientPingRuntime.java:705`）。

### 7.3 实体发光（outline）

- 复用原版 `OutlineBufferSource` 与后处理：在 `renderLevel` 的 `endOutlineBatch()` 前后插入描边，`@Redirect PostChain.process`（ordinal 0）接管实体描边后处理，`@Redirect shouldEntityAppearGlowing` 与 `Entity.getTeamColor` 决定谁发光、用什么颜色（`mixin/LevelRendererMixin.java:89,117,142-181`）。
- 说明：这**不是**给实体加 GLOWING 状态，而是复用原版描边通道，避免自造 shader（同文件注释）。

### 7.4 标记尺寸、屏外箭头、轮盘扇区数（**规格，不是 bug**）

> 本节用于防止「越远越大 / 箭头太小 / 轮盘少了几项」被反复当缺陷上报。三条都是上游 0.4.2-pfi-beta1 的**既有设计**，26.3 移植后保持不变（M6.5 已对齐）。

**① 标记尺寸是距离补偿的，远处恒定 0.5 倍**

```java
// 上游 MarkerView.java:262-267 ≡ 当前 client/marker/MarkerView.java:282-287
final var scale = 2.0 / Math.pow(this.distance, 0.3);
final var pingSize = config.getPingSize() / 100f;
this.scale = (float)Math.max(1.0, scale) * 0.5f * pingSize;
```

| 距离 d（格） | `2/d^0.3` | 夹取后 | `scale` | 屏幕上标记宽度（上游基准 5px） |
|---|---|---|---|---|
| 1 | 2.00 | 2.00 | 1.00 | 5.0 px |
| 4 | 1.32 | 1.32 | 0.66 | 3.3 px |
| 10 | 1.00 | 1.00 | **0.50** | 2.5 px |
| 64 | 0.50 | **1.00（下限）** | **0.50** | 2.5 px |
| 256 | 0.37 | **1.00（下限）** | **0.50** | 2.5 px |

- 结论：**近距离才放大（最多 1.0 倍），d≳10 之后固定 0.5 倍**，不再随距离缩小。`Math.max(1.0, …)` 是**下限保护**，不是「远处补偿」。
- 所以「远处的标记看着比如今缩小的方块还大」是透视 + 尺寸下限的合成观感；上游同样如此，且靠**世界空间描边**（§7.2，M7 移植）给出与方块一致的尺寸锚点。
- `pingSize`（0–100，默认 100）只做整体倍率；改这个公式等于改上游手感，需 Lead 决策。

**② 屏外/背后箭头最终 2.5px 是有意的**

- `arrow.png` 是 64×64，画入 **10px** 四边形，再乘 **0.25** → **2.5px**（上游 `DirectionIndicatorRenderer.java:71-79`；当前 `MarkerHudRenderer.java:102-113`）。
- 箭头贴在 safe-zone（左/右/上/下内缩，默认 5/5/5/60）边缘，按目标角度旋转；背后目标方向取反（`DirectionIndicatorRenderer.java:43-55`）。
- 看起来小属预期，**不要放大**；要更明显只能改 `pingSize`。

**③ 轮盘同时只显示当前目标类型的 3–4 种，不是缺项**

- 7 种 ping 类型是**并集**；每个目标类型只挂其中 3–4 种（`common/src/main/java/nx/pingwheel/common/domain/TargetTypeCatalog.java:43-82`）：
  - `dropped_item`：loot / attention / danger（默认 loot）
  - `entity`：attention / danger / go_to（默认 attention）
  - `entity_block`：attention / destroy / take / request（默认 attention）
  - `block`：attention / go_to / danger（默认 attention）
  - `location`：go_to / attention / danger（默认 go_to）
- 因此 `block` 目标开轮盘只看到 3 项、`entity_block` 看到 4 项是**正确行为**。
### 7.5 生命周期与反馈

- 本地显示时长：`markerDisplayDuration==0` 用快照里的服务端寿命，否则用本地秒数×20 tick（`client/duration/ClientMarkerDisplayDuration.java:26-35`）。
- 提交前目标失效 → **本地聊天行**（#FF5555），不是 actionbar/toast；同类方块 blockstate 变化不算失效；取消/超时**不提示**（`docs/UI/ping-feedback.md:19-23,49-55`）。
- 服务端拒绝（TARGET_GONE 等）只对**最近一次实际派发**的请求提示；被客户端礼让限流而没发的请求不提示（`docs/UI/ping-feedback.md:30-33`）。

---

## 8. 集成行为

| 集成 | 判定 | 启用后改变什么 | 缺失/失败时 | **本地现状（2026-11-02）** |
| --- | --- | --- | --- | --- |
| **Distant Horizons** | `isModLoaded("distanthorizons")`（`integration/ModContext.java:16`） | 客户端射线可穿过 4096 格地形（异步 + 10s 缓存），并把远处地形命中投影成方块目标（`integration/DistantHorizonsIntegration.java:31-32,53-96,121-168`） | 返回 false/空 → 走普通同步射线；LinkageError 一次性熔断 | 🟡 **已实现（M9-3，复审通过，实机待验）**：`DistantHorizonsIntegration` 已回填上游 201 行实现（**异步远距离射线** + 10 s 软缓存 + 4096 格；`worldProxy`/`level` 为空或 `raycast` 失败 → 回调 empty，**失败降级**到普通同步射线；`LinkageError` 一次性熔断）；编译期只吃 `compile-libs/DistantHorizonsApi-7.2.0.jar`（方案 A） |
| **Simple Voice Chat** | `isModLoaded("voicechat")`（`:17`） | 「团队上下文」优先用**语音小组**；同组玩家才互见（AUTO/TEAM_ONLY 时） | 回落到 FTB Teams → 原版队伍 → NONE（`integration/TeamContextHandler.java:16-26`） | **✅ 已实现（M9-2，编译/审查通过，实机待验）**：`VoiceChatIntegration` / `VoiceChatWrapper` 与上游逐字节一致，`voicechat` entrypoint 已注册（`fabric/src/main/templates/fabric.mod.json:16`）；判定用的是 **VoiceChat 的分组（group）**（`serverApi.getConnectionOf(uuid).getGroup().getId()` / `clientApi.getGroup().getId()`），**不是「团队」**；插件未加载或 `LinkageError` 时回落原版队伍 |
| **FTB Teams** | `isModLoaded("ftbteams")`（`:18`） | 团队上下文用 FTB 队伍；**单人队不算团队**（`FTBTeamsWrapper.java:20,35`） | 同上回落 | **平台强制跳过**：对端模组无 26.3 构建（26.x 线止于 `26.1.2.4`），桩恒 `empty`；登记 `24` **#17** |
| **Sable** | `isModLoaded("sable")`（`:19`） | 子关卡（sub-level）内的方块可被 ping：捕获时投影回主世界、服务端用 provider 校验/物化/刷新；外部方块标记有独立生命周期（`integration/SableIntegration.java:18-26`；`docs/integrations/sable.md:31-87`） | provider 不可用 → 退回普通投影/位置捕获；刷新三态：暂不可用保留、同身份更新、非法移除（`ExternalBlockRefreshTriage.java:15-35`） | **平台强制跳过**：Sable 仅 1.21.1（最新 `2.0.6`），客户端 `SableClientProvider` 四入口恒 `empty`、服务端 provider 靠反射注册（无 jar 不加载）；登记 `24` **#18** |
| **Mod Menu**（仅 Fabric） | `modmenu` entrypoint（`fabric/src/main/templates/fabric.mod.json:15`） | Mod 列表出现「配置」按钮直开设置屏（`fabric/src/client/.../integration/ModMenuIntegration.java:7-12`） | 无按钮，命令仍可用（`/pingforit config`、按键路径） | **✅ 已实现（M9-1，2026-11-02）**：entrypoint 见 `fabric/src/main/templates/fabric.mod.json:15`，类为 `fabric/src/client/java/nx/pingwheel/fabric/integration/ModMenuIntegration.java:7-12`（与上游**逐字节一致**）→ 装了 Mod Menu 就在 Mods 列表出现「配置」按钮，点开即本模组 `SettingsScreen` |
| Create / Flywheel（仅 NeoForge） | `isLoaded("create") / isLoaded("flywheel")`（`neoforge/.../NeoClient.java:79-103`） | 机关 raycast、实体描边、entity-block 几何三条独立路由 | 与 Fabric 移植无关（04 报告 §1.4f） | **不适用**：上游仅 NeoForge 侧路由，Fabric 端无对应实现 |

团队优先级固定：**VoiceChat > FTB Teams > 原版队伍 > NONE**（`TeamContextHandler.java:16-26`）；`inSameContext` 先比语音组 id，再 FTB，最后 `p1.getTeam() == p2.getTeam()`（`:40-47`）。所有集成走三层降级：布尔门 → `IntegrationLinkGuard` 一次性熔断 → 调用方 Optional 回退（`IntegrationLinkGuard.java:12-35`）。

---

## 9. 兼容性事实

### 9.1 语言（8 个 locale，均缺键）

实测（本报告逐文件比对，**与 04 报告的键数不同，以本文为准**）：en_us 163 键；其余：

| locale | 键数 | 相对 en_us 缺 | 缺的典型键 |
| --- | --- | --- | --- |
| de_de | 156 | 9 | 轮盘半径/不透明度/长按时长/取消角度等 |
| es_ar | 156 | 9 | 同上 |
| fr_fr | 153 | 12 | 同上 + `value.pingforit.vanilla_team/ftb_teams` |
| pl_pl | 148 | 15 | 同上 |
| tr_tr | 148 | 15 | 同上 |
| zh_cn | 158 | 5 | `value.pingforit.vanilla_team/ftb_teams/voice_chat`、两条聊天模板覆盖键 |
| zh_tw | 150 | 13 | 同上 + 轮盘项 |

- 缺键行为：Minecraft 的 `Language` 回退到 **key 原文**（不崩、不报错），所以缺键只影响显示文案。
- 缺键集中在**较晚加入的轮盘设置项**与**团队枚举显示名**；de_de/es_ar/fr_fr 还有 2 个「多出来」的旧键 `pingforit.chat.{loot,destroy}.template.override`。
- 键族：`key.category.*`、`key.pingforit.*`（6 个按键）、`subtitles.pingforit.ping`、`settings.pingforit.*`、`value.pingforit.*`、`command.pingforit.*`、`pingforit.ping_type.*`、`pingforit.chat.*`。

### 9.2 协议与旧版本

- **没有协议版本字段**：全库检索 `PROTOCOL_VERSION` 等无有效命中；兼容靠「每个 payload 独立注册 + 损坏即哨兵丢弃 + 旧家族降级」：`PingLocationC2S` 服务端仍处理（用于频道握手），`PingLocationS2C` 客户端验证后**忽略渲染**（`CommonServer.java:45-47`；`CommonClient.java:573-589`；`docs/architecture/network/protocol.md:20-24`）。
- 未注册 payload 在 Fabric 侧表现为**收不到**，不会显式拒绝（跨版本混连时排查成本高）——**待确认**（未做运行验证）。
- 明确不做：跨维度实体追踪、Immersive Portals（`docs/compatibility.md:46-47`）。

### 9.3 旧包名/旧身份兼容

- 内部 Java 包仍是上游的 `nx.pingwheel.*`（不是 `pingforit.*`），这是**允许**的内部身份；
- 但对外身份必须干净：构建校验任务禁止 jar 内出现 `assets/pingwheel/`、`pingwheel.mixins.json`、旧通道字符串、旧 lang 文案；也禁止声明对原模组 `pingwheel` 的依赖/冲突（`build.gradle:57-108,151-233`）。
- 因此移植时「内部包名」可保留也可重命名，但「对外资源名/通道名/元数据」必须与最终 mod id 一致。

---

## 10. 查阅索引

| 想查 | 去哪 |
| --- | --- |
| ping 类型与目标类型定义 | `domain/PingTypeCatalog.java:36-46`、`domain/TargetTypeCatalog.java:42-83` |
| 交互状态机 | `interaction/state/PingInteractionStateMachine.java`（短按 `:443-470`，轮盘 `:472-568`） |
| 取消候选 | `interaction/cancel/CancelCandidatePicker.java:13-36` |
| 按键仲裁 | `util/InputUtils.java:48-78`、`util/PingClickArbitration.java:33-48` |
| 服务端创建链路 | `core/ServerCore.java:416-487`、`marker/MarkerCreationService.java:183-313` |
| 校验规则 | `marker/MinecraftAuthoritativeTargetValidator.java:29-66,127-333` |
| 赢家与存储 | `marker/MarkerWinner.java`、`marker/ServerMarkerStore.java:70-96,105-125,318-340` |
| 包定义 | `network/*.java`（每个 `isCorrupt()` 即最小合法性） |
| 配置 | `config/ClientConfig.java:26-76`、`config/ClientConfigBounds.java`、`config/ServerConfig.java:15-21`、`config/ServerConfigBounds.java` |
| 命令 | `command/ClientCommandBuilder.java:27-86` |
| 渲染 | `render/OverlayRenderer.java`、`render/PingLocationRenderer.java`、`client/outline/BlockDisplayWhitelist.java`、`mixin/LevelRendererMixin.java` |
| 集成 | `integration/ModContext.java:15-28`、各 Wrapper/Provider |
| 设计文档 | `docs/architecture/**`、`docs/config/**`、`docs/integrations/**`、`docs/UI/**` |
