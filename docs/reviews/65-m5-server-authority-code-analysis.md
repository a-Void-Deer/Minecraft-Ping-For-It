# 65 · M5 服务端权威：代码层分析（对照上游 0.4.2-pfi-beta1）

> 目的：用户暂时无法做双客户端实机演示，先用「对照上游源码」把 M5 六项契约钉死在代码层，产出「哪几项代码已保证 / 哪几项必须双客户端 / 有哪些缺口」，供后续实机验收与 M10 测试基座使用。
>
> 范围：**只读源码 + 写本文档**，未改动任何代码，未动 AGENTS.md。
> 基线：上游 Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1；本仓库 common/src/main（服务端+共享）+ fabric/src/client（客户端）。

## 0. 结论速览

| 项 | 代码层判定 | 一句话依据 |
|---|---|---|
| 受众快照 audience | ✅ **代码已保证** | 收件人在**创建时**由服务端按 channel/team 算好并冻结进 ServerMarker.recipients，C2S 包不含任何受众字段 |
| 赢家选择 winner | ✅ **代码已保证** | 唯一裁量在服务端 MarkerWinner.ARRIVAL_THEN_ID + 变更包；客户端只镜像（authoritativeWinnerByKey），本地 fallback 仅用于权威数据缺失时收尾 |
| 移除仅本人 remove-only-self | ✅ **代码已保证（服务端）/ ⏸ 需伪造包才能实测** | removeOwned 按 **UUID** 比对 marker.owner()，非名字；正常客户端的 UI 根本不会给出别人的 marker |
| 限流 rate limit | ✅ **代码已保证，但有 4 个行为注意点** | 每人一个 RateLimiter（UUID 键），服务端独立判定；注意墙钟、先扣额度、重连重置、0=无限 |
| 过期 expiry | ✅ **代码已保证** | expiresAtTick = arrivalTick + syncDuration×20，由**服务端 tick** 驱动 expire(currentTick)，闭区间判据 |
| 断线重连 reconnect | ✅ **代码已保证「不重放」** | 断线即删自有 marker + 把该 UUID 从所有受众剔除；全代码中**不存在** JOIN 时补发既有 marker 的路径 |

**结论**：M5 **代码层可信** —— 六项全部有服务端唯一裁量点，且核心契约类与上游逐字节一致；
**0 个硬缺口**、**6 个待确认点/行为注意点**（§7）。其中两条决定了 §6 怎么设计：
**限流的服务端那一层在正常客户端下被客户端礼让掩盖**、**remove-only-self 的越权面只能靠伪造包才能实测**。

## 1. 方法与目录映射

- 上游 common/src/main/java/nx/pingwheel/common/**（290 java，**client 也在 common 里**）
  ↔ 我们 common/src/main/java/nx/pingwheel/common/**（162 java）+ fabric/src/client/java/nx/pingwheel/**（客户端）。
- 上游**没有** core/ClientCore.java（任务书里的假设名字不存在）：上游客户端入口是 CommonClient，与我们的 CommonClient 对应。
- 做法：逐文件 diff -u（不忽略空白）+ 关键方法逐行读。下表是 M5 相关文件的实测结果。

| 文件（相对 nx/pingwheel/common） | 行数（上游/我们） | diff 结果 |
|---|---|---|
| core/ServerCore.java | 813 / 813 | **17 行不同，全是 26.3 平台改名**（§4） |
| marker/MarkerCreationService.java | 424 / 424 | **IDENTICAL** |
| marker/ServerMarkerStore.java | 456 / 456 | **IDENTICAL** |
| marker/MarkerWinner.java | 71 / 71 | **IDENTICAL** |
| marker/ServerMarker.java · MarkerSnapshot.java · TargetKey.java | 95 / 79 / 154 | **IDENTICAL** |
| marker/AuthoritativeTargetValidation.java · MarkerCreateOutcome.java · MarkerRemovalResult.java · MarkerIdSource.java | 99 / 123 / 89 / 55 | **IDENTICAL** |
| marker/MinecraftAuthoritativeTargetValidator.java | 340 / 340 | **7 行不同，全是平台改名** |
| marker/ServerEntityLocatorPolicy.java · MinecraftServerEntityLookup.java | 80 / 77 | **IDENTICAL** |
| util/RateLimiter.java | 44 / 44 | **IDENTICAL** |
| integration/TeamContextHandler.java | 49 / 35 | **删掉 getSelfContext()**（迁到客户端 ClientTeamContextHelper，逻辑逐行相同） |
| network/PacketHandler.java · MarkerPacketCodec.java | 44 / 333 | **IDENTICAL** |
| network/*Packet.java（13 个） | 44–125 | 每个 **3–4 行**：ResourceLocation→Identifier；MAX_CHANNEL_LENGTH 迁到新增的 network/ProtocolLimits.java（16 行，仅常量） |

> 结论：核心契约类（store / winner / creation service / snapshot / target key / id source）**逐字节等于上游**，
> 所以「M5 语义」不是重新推演，而是**上游语义原样继承**；差异只出现在 MC API 面与分源集导致的搬移。

## 2. 六项契约逐条

### 2.1 受众快照（audience）

**时机**：服务端收到 MarkerCreateC2SPacket → ServerCore.onMarkerCreate（core/ServerCore.java:416）。

**判定顺序**（每步都可能直接 return，且**受众在创建前算好**）：

1. packet.isCorrupt() → INVALID_REQUEST（:421-425）
2. 限流 → RATE_LIMITED（:427-434）
3. 该玩家**服务端记录的** channel 为空且模式为 DISABLED → CHANNEL_DISABLED（:436-443）
4. channel 为空、模式 TEAM_ONLY 且 TeamContextHandler.hasTeam 为假 → CHANNEL_DISABLED（:445-449）
5. snapshotRecipients(playerList, player, channel, defaultChannelMode)（:452；实现 :702-723）
6. arrivalTick = server.getTickCount()，expiresAtTick = arrivalTick + getSyncDuration()*20（:454-455）
7. markerService(server).create(level, uuid, target, pingTypeId, arrivalTick, expiresAtTick, recipients)（:457-459）

**snapshotRecipients 的判据**（:702-723）：

- 创建者**恒入列**（:708）；
- 遍历在线玩家：channel 串必须**完全相等**（:711）；channel 为空且模式非 GLOBAL 时还要求 TeamContextHandler.inSameContext(creator, p)（:715）；
- 返回 distinct().toList() 的不可变列表（:722）；方法注释明写 immutable, so channel switches after creation cannot change the marker's audience（:698-700）。

**冻结与下发**：受众存进 ServerMarker.recipients（marker/ServerMarker.java:31；构造器排序+去重+非空 :82-94）；
此后**所有**下发都只走 marker.recipients()：创建 :743、删除 :760、赢家变更 :783（经 change.recipientId()）。
→ 之后任何人切频道、退队、换维度都**不改变**既有 marker 的受众。

**客户端能伪造什么 / 不能伪造什么**：

- 不能伪造受众：MarkerCreateC2SPacket 只带 requestId / target / pingTypeId（network/MarkerCreateC2SPacket.java:10），**没有 channel、没有 recipients、没有 owner**；
- 不能伪造身份：接收器用连接派生对象 context.player()（fabric/src/main/java/nx/pingwheel/fabric/FabricMain.java:63,79-81），不是包内字段；
- 能选的只有 target / pingTypeId / requestId —— 三者在服务端都被重新校验（§2.2 白名单、§2.3 归属、渲染前还有 targetType 分类一致性，见 ServerMarker.java:54-66）。

**与上游差异**：🟢 **一致**（snapshotRecipients、mode 判定、冻结点全部逐行相同）。

### 2.2 赢家选择（winner）

**唯一裁量点**：MarkerWinner（marker/MarkerWinner.java:17-70）。

- 比较器 ARRIVAL_THEN_ID：先 arrivalTick 升序，再 MarkerId 升序（:22-23），winning = 取最大 → **最新到达者赢，同 tick 靠更大的 id 定序**；
- 候选过滤：不同 TargetKey 跳过（:56-58）；**非本 marker 受众跳过**（:60-62）→ 同一 target 下每个 recipient 的 winner 可以不同；
- 全序比较、与集合遍历顺序无关 → 跨端确定（上游 MarkerWinnerTest.resultIsDeterministicAcrossIterationOrders 即守此点）。

**变更包的产生**：

- 创建：ServerMarkerStore.creationChanges（:318-340）**先算 before、再插入**（create :70-96，插入在 :93），只对本 marker 的 recipients 产生 MarkerWinnerChange（有变化才产生 :334）；
- 删除/过期：removeAll（:360-407）先 beforeWinners 全量快照（:413-431），删除后逐 pair 算 after，LinkedHashSet 去重（:381,392）→ **一批内每个 (targetKey, recipient) 只出 1 条、无中间态**；顺序 = 被删 marker 的 id 升序（:361-363）；
- 下发：ServerCore.sendWinnerChanges（:777-790）按 change.recipientId() 逐人发 MarkerWinnerChangedS2CPacket。

**客户端只是镜像**：

- CommonClient.onMarkerWinnerChangedPacket → ClientPingRuntime.applyWinnerChanged（client/ClientPingRuntime.java:1127-1139）→ ClientMarkerStore.onWinnerChanged 写 authoritativeWinnerByKey（client/marker/ClientMarkerStore.java:107,331）；
- 一切「谁画描边」的查询**只读**这张权威表：winnerId（:514-534）、winnerMarker（:543-563）、visibleWinnersInDimension（:573-596）；槽位与 marker 不匹配时返回 empty（:525-527），**不会**退化成「客户端自己挑一个」。

**客户端唯一的自算点**：fallbackWinnerId（:603-614）+ recomputeWinners（:669-679）—— 只在**本地移除/清理导致权威槽位失效**时重算，用于收尾显示，不作为可见性来源。这是 §7 的 R2。

**与上游差异**：🟢 **一致**（MarkerWinner / ServerMarkerStore 逐字节相同；客户端 store 是上游文件平移）。

### 2.3 移除仅本人（remove-only-self）

**时机**：MarkerRemoveC2SPacket → ServerCore.onMarkerRemove（:495）。

- 包损坏 → INVALID_REQUEST（:498-504）；
- 其余一律交给 store：markerStore().removeOwned(player.getUUID(), markerId)（:507）；
- store 判定（marker/ServerMarkerStore.java:105-120）：不存在 → NOT_FOUND（:111-113）；!marker.owner().equals(requester) → NOT_OWNER（:115-117）；否则按 CANCELLED 删（:119）；
- **owner 比较是 UUID**（:115），不是玩家名 —— 改名不影响，同名不同 UUID 不可能误判；
- 结果映射（:509-526）：REMOVED → 释放外部占位 + 广播 MarkerRemovedS2CPacket + 赢家变更（:510-517）；NOT_FOUND / NOT_OWNER → 只回请求者 MarkerRejectedS2CPacket（:520,524）。

**客户端能伪造什么**：MarkerRemoveC2SPacket 只有 markerId（client/ClientPingActionDispatcher.java:132）。所以**知道别人的 markerId 就能发请求** —— 但服务端按 UUID 拒，攻击面止于「一个被拒的包 + 一条 debug 日志」。

**UI 侧不提供越权入口**（纵深防御）：取消候选来自 markersOwnedInDimension(dimensionId, ownerId)（client/ClientPingRuntime.java:895-903，ownerId 见 :878），只含自有 marker。

**注意**：移除请求**不限流**（:489-494 注释写明 Removal is not rate-limited），与上游一致。

**与上游差异**：🟢 **一致**。

### 2.4 限流（rate limit）

**算法**（util/RateLimiter.java，44 行，与上游逐字节一致）：等价令牌桶 —— 每次成功调用把 startTime 前移 timeToRegenerate（:40），窗口上限 timeWindow = msToRegenerate × limit（:13），超出即返回 true（:36-38）；首次调用按「窗口已满」初始化（:21-25）。

**配置来源**：ServerCore.init() 里 RateLimiter.setRates(SERVER_CONFIG.getMsToRegenerate(), SERVER_CONFIG.getRateLimit())（:102）；
热更新走 ServerConfig.onUpdate() → ServerCore.init() + broadcastRateLimitPolicy() + broadcastSyncDurationPolicy()（config/ServerConfig.java:35-40），与上游同形（上游 ServerConfig.java:43）。

**每 sender 独立**：PLAYER_RATES（core/ServerCore.java:74）以 **UUID** 为键，putIfAbsent 后取实例（:427-428），断线整条移除（:594）→ 天然按人隔离，不存在共享桶。

**两个执行点**：

1. onMarkerCreate（:430-434）→ 拒绝并回 RATE_LIMITED（客户端能收到）；
2. 兼容路径 onPingLocation（:348-353）→ **静默丢弃、不回包**；我们客户端已不消费该包（CommonClient.java:594-601 明确忽略），只服务旧版本对端。

**客户端礼让（不是权威）**：服务端每次 channel 更新都会推 RateLimitPolicyS2CPacket（ServerCore.java:201-203；同步时长首次同步在 :204-210；广播入口 :219-235）；客户端 onRateLimitPolicyPacket → ClientCreateRateLimiter.applyPolicy（ClientPingRuntime.java:1193），发送前 tryAcquire()，失败只 debug 并放弃（ClientPingActionDispatcher.java:110-115）。

**四个行为注意点**（详见 §7 R3）：

- 用**墙钟** Instant.now()，不是 tick；
- 限流检查**在频道判定之前**（:348-353 早于 :355-370），被频道拒的请求也吃额度；
- 断线清空额度（:594）→ 重连可重置；
- getRateLimit() == 0 时**完全不调用**检查（:351,430 的 > 0 守卫）＝ 无限。

**与上游差异**：🟢 **一致**。

### 2.5 过期（expiry）

**冻结与单位**：创建时 arrivalTick = server.getTickCount()、expiresAtTick = arrivalTick + getSyncDuration() * 20（core/ServerCore.java:454-455；TICKS_PER_SECOND = 20 在 :69）→ **syncDuration 单位是秒**，上下限由 ServerConfigBounds.clampSyncDuration 钳制（1–60s，见 config/ServerConfigBoundsTest）。

**驱动力**：服务端 END_SERVER_TICK（CommonServer.java:41 注册）→ ServerCore.onServerTick（:535）→ markerStore().expire(server.getTickCount())（:540）。判据是**闭区间** expiresAtTick <= currentTick（marker/ServerMarkerStore.java:148-154）。

**下发**：一批过期 → 逐个 releaseExternal + sendMarkerRemoved（:548-551），再一次性 sendWinnerChanges（:553）；顺序由 removeAll 的 id 升序保证。

**客户端不可干预**：arrivalTick / expiresAtTick 由服务端算好随 MarkerSnapshot 下发（marker/MarkerSnapshot.java:27-28,58-67），客户端只读；客户端显示时长策略（ClientSyncDurationPolicy）只裁剪**本地显示**，服务端过期后一定补一条 MarkerRemovedS2CPacket。

**与上游差异**：🟢 **一致**。

### 2.6 断线重连（reconnect）

**服务端 onPlayerDisconnect**（core/ServerCore.java:569-596；由 ServerPlayConnectionEvents.DISCONNECT 触发，fabric/src/main/java/nx/pingwheel/fabric/platform/PlatformServerEventServiceImpl.java:15）：

1. removeOwnedBy(uuid, OWNER_DISCONNECTED) —— **自己 owner 的 marker 全部删掉**（:574；store 侧 :168-177）；
2. 对仍在线受众逐个 sendMarkerRemoved，并**排除**断开者本人（:577-580；排除逻辑 :761-763）；同理赢家变更（:582）；
3. store.forgetRecipient(uuid) —— 把该 UUID 从**所有** marker 受众里剔除，受众被清空的 marker 直接丢弃（:584；store 侧 :196-221，丢弃项返回给调用方用于释放外部占位 :586-588）；
4. 清 PLAYER_CHANNELS / PLAYER_RATES / SYNC_DURATION_POLICY（:593-595）。

**所以「重连后旧 marker 是否重放」= 不会**，两个理由各自独立成立：

- 服务端：旧 marker 要么已被删（自己 owner），要么已把他从受众里剔掉；并且**全代码库不存在**「JOIN 时补发既有 marker」的路径 —— 服务端只注册了 DISCONNECT 与 TICK（CommonServer.java:40-41），服务端 JOIN 事件根本没有注册；
- 客户端：ClientPlayConnectionEvents.DISCONNECT → CommonClient.onLeaveServer（:108-127）→ pingRuntime.close() 清 markerStore / nameStore / MarkerOverlayState（ClientPingRuntime.java:436-438）+ 覆盖层清空（CommonClient.java:118）；JOIN → onJoinServer（:96-105）重建 runtime 并重发 channel。

**副作用（预期行为，需实机确认体感）**：A 一断线，**A 自己打的所有 ping 会立刻从所有人屏幕上消失**（OWNER_DISCONNECTED）；A 重连后 PLAYER_CHANNELS 是全新的（默认 channel），若服务端 defaultChannelMode=DISABLED，A 重连后不设频道就无法 ping。

**与上游差异**：🟢 **一致**。

## 3. 「只依赖客户端就无法保证」的点（设计上必须服务端持权）

| 契约 | 为什么客户端做不到 | 证据 |
|---|---|---|
| 受众 | 客户端包**没有**受众字段，也不知道别人在哪个 channel（那是服务端 PLAYER_CHANNELS 私有状态） | MarkerCreateC2SPacket.java:10；ServerCore.java:73,702-723 |
| 赢家 | 客户端只有自己收到的 marker 子集，看不到「同 key 下别人可见的 marker」 | ClientMarkerStore.java:514-534；MarkerWinner.java:60-62 |
| 移除 | 客户端不知道 marker 真实归属的可信来源，也没有权限表；owner 字段只是展示数据 | ServerMarkerStore.java:115；MarkerSnapshot.java:22 |
| 限流 | 客户端限流器是**可改包的本地礼让** | ClientPingActionDispatcher.java:110-115 vs ServerCore.java:427-434 |
| 过期 | 客户端没有独立于服务端的权威时间轴；本地 tick 与服务器 tick 不同源 | ServerCore.java:454-455,540 |
| 重连 | 断线瞬间客户端进程已停，无法参与「删自己 marker / 剔除受众」 | ServerCore.java:569-596 |

## 4. 与上游的差异清单（逐条：一致 / 平台强制 / 我们新增）

| # | 位置 | 差异 | 性质 | 影响 M5 语义？ |
|---|---|---|---|---|
| 1 | 全 common（ServerCore、Validator、network/*…） | ResourceLocation → Identifier、dimension().location() → identifier() | **平台强制**（26.3 改名） | 否（纯符号） |
| 2 | ServerCore:195,344 | getGameProfile().getName() → name()；displayClientMessage → sendSystemMessage | **平台强制** | 否（只影响报错文案投递通道） |
| 3 | ServerCore:192-201,341-346 | serverLevel() → level() | **平台强制** | 否（等价访问器） |
| 4 | ServerCore:278,290 | hasPermissions(3) → permissions().hasPermission(Permissions.COMMANDS_ADMIN) | **平台强制**（26.3 权限 API） | **否，但需注意**：COMMANDS_ADMIN = ADMINS(3)，与上游 hasPermissions(3) 等权（已核，见 docs/reviews/44-m8a1b-review.md:31-33,139 与 46-mc26.3-gui-api-map.md:135） |
| 5 | integration/TeamContextHandler | 删掉 getSelfContext()（依赖 client-only CommonClient.Game） | **分源集强制** | 否：逻辑逐行搬到 fabric/src/client/.../client/ClientTeamContextHelper.java:20-30，调用点只有 SettingsScreen（屏幕显示用），**不在 ping 链路上** |
| 6 | network/*Packet | MAX_CHANNEL_LENGTH 从 ClientConfig 迁到新增 network/ProtocolLimits.java:15（=128） | 我们新增（编译面必需） | 否（常量值不变） |
| 7 | marker/*、util/RateLimiter、network/PacketHandler & MarkerPacketCodec | **无差异（逐字节相同）** | — | — |

> 复核口径：以上 1–4 类差异全部是「编译器逼迫的符号替换」，没有一处改动控制流、判定顺序或数据结构；
> 换句话说 **M5 的行为等价性是结构性的**（核心类同一份源码），而不是「读过觉得像」。

## 5. 可搬测试清单（供 M10 测试基座）

上游 `common/src/test/java/nx/pingwheel/common/**` 共 **188 个测试文件**；与 M5 直接相关的 10 个 marker 测试文件约 **115 个 @Test**，网络编解码 8 个文件约 **89 个**，配置面 2 个文件 **11 个**。
按依赖分成三组（这决定 M10 基座要准备什么 classpath）。

### A 组：纯 Java，**零 Minecraft 依赖 —— 可原样搬**（最高性价比）

| 上游测试文件 | @Test | 直接可用的关键断言 |
|---|---|---|
| marker/ServerMarkerStoreTest.java | 28 | removeOwnedMissingReturnsNotFoundWithoutMutation / removeOwnedForeignOwnerReturnsNotOwnerWithoutMutation（**remove-only-self 的核心断言**）/ removalRecomputesOlderWinner / expiryBoundaryIsInclusiveAndUsesExpiredReason / expiryComputesSingleCombinedChangePerPairWithoutTransientStates / forgetRecipientShrinksAudienceAndDropsEmptyMarkers / winnerChangesArePerRecipientAudienceIsolation / createAssignsMonotonicIdsAndPreservesFields |
| marker/MarkerWinnerTest.java | 11 | latestArrivalWins / equalArrivalResolvesToLargerId / winnerIsPerRecipient / differentTargetKeysAreIgnored / resultIsDeterministicAcrossIterationOrders |
| marker/MarkerSnapshotTest.java | 6 | fromServerMarkerMapsAllFields / rejectsExpiresNotAfterArrival |
| marker/MarkerResultValuesTest.java | 10 | removalResultStatusEnumIsComplete / markerCreationIsImmutableAndStrict |
| marker/TargetKeyTest.java | 16 | entityKeyIdentityIsByUuidAndDimension / blockKeyIdentityIncludesBlockType / locationKeyIdentityUsesExactCoordinates（**同 target 判定 = 赢家分桶的依据**） |
| marker/MarkerIdSourceTest.java | 7 | generatesMonotonicSequence / concurrentGenerationProducesUniqueIds / failsClearlyAtLongMaxValueBeforeOverflow |
| marker/AuthoritativeTargetValidationTest.java | 5 | acceptedCarriesValidatedTargetAndNoReason / rejectedCarriesReasonAndNoTarget |
| marker/ServerEntityLocatorPolicyTest.java | 4 | runtimeIdExperienceOrbIsAcceptedAndNormalizedToActualRuntimeId / runtimeIdNonOrbIsDisallowedBeforeAliveOrNamePolicy（**防伪造 locator**） |
| marker/ExternalBlockMarkerRefreshTest.java | 1 | locatorRefreshPreservesMarkerIdentityWinnerLifetimeAndAudience |
| marker/MarkerAnchorTest（4）· ServerMarkerTest（14）· MarkerRemovalReasonTest（2） | 20 | 值对象不变量（anchor 有限性；ServerMarker 构造校验 marker/ServerMarker.java:45-66；reason 全枚举） |
| config/ServerConfigUpdateServiceTest.java | 3 | levelTwoPermissionIsRejectedWithoutMutation / levelThreePermissionAppliesOnlyDirtyFields（**服务端改配置的权限门**） |
| config/ServerConfigBoundsTest.java | 8 | clampSyncDuration 1–60s / clampPingDistance / boundaryConstantsAreConsistent |
| config/ServerConfigSerializationTest.java | 3 | 配置往返与迁移（restore 基线的一部分） |

### B 组：需要 **MC 服务端类**（common 测试源集即可满足，不能进 client 源集）

| 上游测试文件 | @Test | 说明 |
|---|---|---|
| marker/MarkerCreationServiceTest.java | 27 | 用匿名 ExternalBlockServerProvider + 假 validator，**只把 net.minecraft.server.MinecraftServer / ServerLevel 当类型**（:5-6,701-722）；断言链：validationRunsFirstAndRejectionSkipsResolverAndStore / createUsesNormalizedTargetNotRequested / createPreservesOwnerRecipientsAndLifetime / createRejectsPingTypeDisallowedForResolvedTargetType / rejectedCreateCarriesNoName / removeOwnedMapsStoreStatuses / expireDelegatesToStore |

> 这一条是 M5 的**主测试**：它同时覆盖「受众/赢家/移除/过期」在创建服务层的接线（只是不含真实世界校验）。

### C 组：需要 **FriendlyByteBuf**（Loom 的 test 源集能拿到 MC 类即可）

| 上游测试文件 | @Test | 关键断言 |
|---|---|---|
| network/MarkerPacketsTest.java | 33 | **clientPacketsDoNotCarryServerAuthoritativeFields**（M5 保真的现成断言！）/ createPacketRejectsNegativeRequestId / rejectedPacketRoundTripsForEveryKindAndReason / createdPacketRejectsBlankOwner |
| network/MarkerPacketCodecTest.java | 37 | snapshotRoundTrips / committedMarkerSnapshotRejectsAnUncommittedExternalCandidate / rejectsNonFiniteAnchorCoordinatesOnRead / rejectsNegativeMarkerIdOnRead |
| network/PacketHandlerTest.java | 3 | validDecodeLeavesBufferFullyConsumed / corruptDecodeLeavesBufferFullyConsumed / fallbackConstructorFailureThrowsIllegalStateExceptionAndStillDrainsBuffer |
| network/RateLimitPolicyS2CPacketTest.java | 5 | roundTripPreservesBothFields / negativeValuesAreCorrupt（**限流策略下发面**） |
| network/SyncDurationPolicyS2CPacketTest.java | 3 | roundTripPreservesDuration / negativeAndOutOfBoundsPoliciesAreCorrupt（**过期时长下发面**） |
| network/MarkerCodecTargetTypeIdTest.java · ServerConfigPacketsTest · ServerConfigPolicyPacketTest | ≈ 8 | targetType id 稳定性、配置包往返 |

**搬运成本**：A 组 0 改动（包名 nx.pingwheel.common.* 一一对应）；B 组要 common 测试源集的 MC classpath；C 组同上（FriendlyByteBuf 在 MC 服务端/客户端共有）。
**不建议搬**：任何依赖 `CommonClient.Game`、`WheelGeometry`、渲染类的测试（那是客户端源集的事，且与 M5 无关）。

## 6. 必须双客户端才能验的清单

> 前置：一台 **专用服务器**（`./gradlew :fabric:runServer`，见 AGENTS §3/§7.18）+ **两个正式客户端实例**（不要用两个 `runClient`，它们共用 `run/` 目录）。
> 判据优先用 **INFO 级**日志（两处现成）：客户端 A 派发时打 `send ping: requestId=N kind=... pingType=...`（ClientPingActionDispatcher.java:122）；**每个**收到 marker 的客户端打 `marker created: markerId=N kind=... pingType=...`（ClientPingRuntime.java:1010-1012）。
> 需要 DEBUG 级判据的地方我已标注「(debug)」，可在客户端/服务端临时开 debug 日志再验。

### V-A 受众快照：谁看得见

| 步骤 | 期望 | 判据 |
|---|---|---|
| 1. A、B 都在默认频道（`/pingforit channel` 清空），A 对方块 ping | A、B 都看到 marker | A 日志 1 条 `send ping: requestId=r`；A、B 日志各有 1 条 `marker created: markerId=m`，**同一个 m** |
| 2. C 加入并把频道设成 `x`（A、B 仍默认空频道），A 再 ping | 只有 A、B 看到 | A、B 各 1 条同 id `marker created`；**C 日志无该 id** |
| 3. A 把自己的频道改成 `x`（与 B 分离），A 再 ping | 只有 A、C 看到 | 同 id 的 `marker created` 只出现在 A、C；B 无 |
| 4. 服务端把 `defaultChannelMode` 设为 `TEAM_ONLY`，A、B 都不在同队且无频道，A ping | 被服务端拒 | A、B 都**没有** `marker created`；服务端 debug 有 `marker create rejected: ... reason=CHANNEL_DISABLED`（ServerCore.java:440-447）(debug) |

> 观察点（受众是**快照**）：第 2 步广播之后、第 3 步之前，把一个已存在的 marker 的受众里某人换频道 —— 旧 marker 在该人屏幕上**不会**消失（因为受众已冻结，ServerCore.java:698-700）。这是**预期**行为，不要当 bug 报。

### V-B 赢家选择：同一目标谁亮描边

| 步骤 | 期望 | 判据 |
|---|---|---|
| 1. A、B 同时对**同一个方块**发 ping（间隔 < 1s 内，尽量同 tick） | 两端都只亮**一个**描边，且是**后到**的那条 | 客户端 debug `marker winner applied: kind=... winner=<id>`（ClientPingRuntime.java:1136-1138）在 A、B 上给出**同一个** winner id，且等于后发者的 markerId (debug) |
| 2. 后发者把自己的 marker 取消（默认取消手势） | 两端的描边切回先发者 | 两端各收到 1 条 `marker winner applied: ... winner=<先发 id>` (debug) |
| 3. 两端目标**不同方块** | 各亮各的，互不影响 | 无 winner 变更包；两端 HUD 各显示各自目标名 |

### V-C 移除仅本人：能不能删别人的

| 步骤 | 期望 | 判据 |
|---|---|---|
| 1. A ping，B 尝试用取消手势去取消 A 的 marker | **B 的取消候选里根本没有 A 的 marker**（UI 只列自有） | 观察 B 取消流程：只有 B 自己的 marker 可选（代码 ClientPingRuntime.java:895-903） |
| 2. （需伪造包，M10 做）用自建客户端向服务端发 `MarkerRemoveC2SPacket(A 的 id)` | 服务端拒绝，A 的 marker 不受影响 | 服务端 debug `marker remove rejected: ... reason=NOT_OWNER`（ServerCore.java:523）(debug)；A、B 均无 `marker removed applied` |

> 结论：正常客户端**无法**打通越权路径，所以这一项的实机验收只能是「UI 不提供入口」+ M10 的伪造包单测；不要为了验它去改产品代码。

### V-D 过期：到点消失

| 步骤 | 期望 | 判据 |
|---|---|---|
| 1. 服务端把 `syncDuration` 设成 5（秒），A ping | A、B 都在 ~5s 后消失 | 服务端 debug `marker expiry: removed=1 ...`（ServerCore.java:545）；两端 HUD 消失时间差 < 1 tick 量级 (debug) |
| 2. 同一目标连打 3 条（A、B 各打），等-过期 | 全部过期，且**每个** recipient 只收到一条收尾 winner 变更 | 服务端 debug `marker expiry: removed=3 winnerChanges=...`；无重复 winner 包 (debug) |
| 3. 单人世界按 Esc 暂停 60s | marker **不会**过期（server tick 不推进） | 服务端无 `marker expiry` 输出 (debug)；恢复后继续计时 |

### V-E 断线重连：消失与不重放

| 步骤 | 期望 | 判据 |
|---|---|---|
| 1. A ping 若干，B 都看得到；A 直接断线 | **B 屏幕上 A 的全部 marker 立刻消失** | B 端 debug `marker removed applied: ... reason=OWNER_DISCONNECTED`（ClientPingRuntime.java:1121）(debug)；B HUD 消失 |
| 2. B 自己 ping 一条（owner=B），A 重连 | B 的 marker 不受影响；A 看不到任何**旧** marker | A 端日志无任何 `marker created`，直到新事件发生 |
| 3. A 重连后 ping 一条 | 新 marker 正常广播给 B | 新 `markerId` **大于**断线前的最大值（id 递增，MarkerIdSource.java:39-53）；A、B 各 1 条同 id `marker created` |
| 4. 服务端 `defaultChannelMode=DISABLED` 时，A 重连后**不设频道**直接 ping | 被拒（频道状态不跨会话保留） | A、B 无 `marker created`；服务端 debug `reason=CHANNEL_DISABLED` (debug) |

### V-F 限流：**这一项无法用原版客户端验**

| 原因 | 结论 |
|---|---|
| 客户端礼让用的策略是服务端广播的**同一组**数字（ServerCore.java:219-235 → ClientCreateRateLimiter.applyPolicy），所以正常客户端会在**之前**就自我限流，服务端那一层基本打不到 | **⏸ 服务端限流层无法用原版双客户端实测**：要么用伪造包/测试客户端注入，要么用单测覆盖（上游**没有** RateLimiter 单测，M10 需补一个纯 Java 的） |
| 可做的降级验证 | 服务端设 `rateLimit=1, msToRegenerate=5000`，A 连点 5 次：**A 自己的日志只出现 1 条 `send ping`**（客户端礼让先拦，ClientPingActionDispatcher.java:110-115）→ 这一条证明的是**客户端层**，不是服务端层 |
| 玩家体感 | 被服务端限流时客户端**不会**提示（`handleRejected` 只对 TARGET_GONE 提示，ClientPingRuntime.java:1149-1160）→ 静默丢弃，与上游一致 |

## 7. 风险与缺口

| # | 疑点 | 判定 | 依据 / 建议 |
|---|---|---|---|
| R1 | 受众快照是**创建时**冻结：切频道/换队后旧 marker 不会重新分配 | **代码已保证（预期行为）** | ServerCore.java:698-700,743；上游同。验收时不要当 bug |
| R2 | 受众**不按维度过滤**：跨维度的玩家也会收到 `MarkerCreatedS2CPacket`，只是客户端按 `dimensionId` 过滤显示 | **代码已保证（上游同）+ 建议实机看一次网络开销** | snapshotRecipients 无维度条件（:702-723）；客户端过滤在 ClientMarkerStore.java:573-596。若要优化属**改变上游行为**，不要做 |
| R3 | 客户端有本地 fallback winner（fallbackWinnerId / recomputeWinners） | **代码已保证（受限）** | ClientMarkerStore.java:603-614,669-679：仅在本地删除导致权威槽位失效时用于收尾，不从它取可见性；仍需双客户端 V-B 第 2 步确认没有视觉闪断 |
| R4.1 | 服务端限流用**墙钟** `Instant.now()`（不是 tick） | **代码已保证（上游同）· 低风险** | RateLimiter.java:22,27-28；系统时间大幅跳变会放宽/收紧窗口，实际影响可忽略；若要改就偏离上游 |
| R4.2 | 限流检查**在频道判定之前** → 被频道拒的请求也吃额度 | **代码已保证（上游同）· 行为注意** | ServerCore.java:348-353 早于 :355-370；create 侧 :427-434 早于 :439-449 |
| R4.3 | 断线清空 `PLAYER_RATES` → **重连可重置额度** | **代码已保证（上游同）· 可绕过** | ServerCore.java:594；属上游设计取舍，M5 不修（保真优先），只记录 |
| R4.4 | `rateLimit == 0` = **无限**（不是「每次」） | **代码已保证** | :351,430 的 `> 0` 守卫；配置钳制在 ServerConfig 构造（:23-29） |
| R5 | 移除请求**不限流**（可被刷） | **可接受** | ServerCore.java:489-494 注释明示；store 侧 O(1) 查找 + NOT_FOUND/NOT_OWNER 拒绝，最坏是日志噪声 |
| R6 | 过期依赖 **server tick 推进**：单人暂停 / 服务器卡顿时不推进 | **代码已保证（上游同）· 行为注意** | ServerCore.java:535-540；V-D 第 3 步专门验证这一点 |
| R7 | `expiresAtTick` 计算是否可能溢出 | **代码已保证** | ServerCore.java:455 显式 `(long) TICKS_PER_SECOND`，且 `syncDuration` 被钳到 1–60（ServerConfigBounds），远不可能溢出 |
| R8 | 换 server 实例（单人换世界）时旧 marker 会不会残留 | **代码已保证** | ensureMarkerStore 在**每个 tick 开头**比对 `ACTIVE_SERVER` 身份并整体重建（ServerCore.java:134-151,536-538），注释 :77-94 说明设计 |
| G1 | **M5 目前没有任何自动化测试在跑**（上游 188 个测试类尚未重建，M10 任务） | **⚠ 这是当前唯一的「真缺口」——不是代码缺口，是证据缺口** | 六项的可信度目前全部来自「上游同源 + 静态推演」；§5 的 A 组（零 MC 依赖，约 100+ @Test）应当是最先补的 |
| G2 | `RateLimiter` **上游就没有单测**，加上 V-F 无法用原版客户端触发服务端层 | **⚠ 需新写测试**（不在可搬清单里） | 建议 M10 自写 `RateLimiterTest`：窗口边界（恰好 timeWindow）、首次调用返回 false、连续 n 次内只有 limit 次通过、`setRates` 变更后行为 |

**没有发现**以下类问题（逐条排查过，均不成立）：

- ❌「remove-only-self 比的是玩家名」→ 实际比 UUID（ServerMarkerStore.java:115）；
- ❌「受众是每 tick 动态重算，切频道会串台」→ 实际创建时冻结（:698-700）；
- ❌「赢家由各客户端自己算，可能不一致」→ 实际服务端算好只发变更（ServerCore.java:777-790）；
- ❌「过期用客户端时间」→ 实际用服务端 tick（:540）；
- ❌「重连会重放旧 marker」→ 实际断线即清（:569-596）+ 客户端断线清 store（ClientPingRuntime.java:436-438）。

## 8. 结论

**M5 当前状态：代码层可信（六项全部有服务端唯一裁量点，核心契约类与上游逐字节一致），0 个硬缺口，2 个证据缺口（G1 无自动化测试、G2 限流无单测），6 个行为注意点（R1–R8，全部为上游同源行为，不建议为验收而改动）。**

分项裁决：

| 项 | 裁决 | 下一步 |
|---|---|---|
| 受众快照 | **代码已保证** | 双客户端 V-A（3 步）+ V-A 广播后切频道不重算 |
| 赢家选择 | **代码已保证** | 双客户端 V-B（3 步）；R3 顺带确认无视觉闪断 |
| 移除仅本人 | **代码已保证**（服务端） | 实机只能验 UI 不提供入口（V-C 第 1 步）；越权面留给 M10 伪造包单测 |
| 限流 | **代码已保证**（服务端执行、每人独立） | **服务端层需单测**（G2）；实机只能验客户端礼让（V-F） |
| 过期 | **代码已保证** | 双客户端 V-D（3 步，含暂停不推进） |
| 断线重连 | **代码已保证（不重放）** | 双客户端 V-E（4 步） |

**建议的推进顺序**：① 先把 §5 的 **A 组测试**搬进 M10 基座（零 MC 依赖，最快给出回归网）；② 再补 G2 的 `RateLimiterTest`；③ 双客户端按 V-A → V-E → V-B → V-D 的顺序做（受众最先，因为它决定其它项的判据是否可信）；④ V-C 第 2 步与 V-F 的服务端层放到 M10 的注入式测试。

## 附录 A · 复核方式

```bash
# 1) 逐文件确认「与上游是否逐字节相同」
U=Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1/common/src/main/java/nx/pingwheel/common
O=common/src/main/java/nx/pingwheel/common
for f in core/ServerCore.java marker/ServerMarkerStore.java marker/MarkerWinner.java marker/MarkerCreationService.java util/RateLimiter.java network/PacketHandler.java; do
  diff -q "$U/$f" "$O/$f" && echo "IDENTICAL $f"
done

# 2) 确认「没有 JOIN 补发旧 marker 的路径」
grep -rn 'ServerPlayConnectionEvents' fabric/src/main/java
grep -rn 'registerPlayerLogoutEvent\|registerServerTickEvent' common/src/main/java
```

本文所有行号以 **2026-11-04** 工作区（HEAD `cfebb15`）为准；改动 ServerCore / ServerMarkerStore / 客户端 store 后需重跑上面的 diff 与行号。

## 附录 B · 已知的上游同源「怪行为」清单（验收时不要当 bug）

1. 断线即删自己全部 marker（不上线、不保留）→ `OWNER_DISCONNECTED`；
2. 切频道后旧 marker 不重新分配受众（快照语义）；
3. 被服务端限流时客户端**静默**（无聊天提示），只有 `TARGET_GONE` 会提示；
4. 移除请求不限流；
5. 单人暂停时 marker 不推进过期；
6. `rateLimit=0` 表示无限而非禁用 ping。
