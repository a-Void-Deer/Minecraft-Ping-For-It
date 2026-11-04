
# 62 · OIT 路由稳健化修复复审

任务：复核 PingOutlineRenderType.isCustom 名称回退 + SubmitNodeCollectionMixin 的 improved-transparency 门控
审核人：3号　日期：2026-11-04　对象：当前未提交 diff（PingOutlineRenderType / SubmitNodeCollectionMixin / OutlineRoutingState TEMP 探针）
基线：bash tools/compile-check.sh -> common 262 / fabric-main 4 / fabric-client 126，0 error。

## 结论

**需改（不是路由逻辑，是 TEMP 探针）。** isCustom 的名称回退成立（RenderType.toString 确实包含 name），WeakHashMap 缓存的线程/性能模型基本可行，门控口径也正确；但 OutlineRoutingState 里的 TEMP 探针仍在，导致默认 OIT 关闭路径新增 info 日志，且 noteHookSeen 的实参每次都会先求值 renderType.toString() —— 这是每个 shape outline 的热路径开销；验证后必须删除，不能随修复合入。

## A. 名称回退是否正确

**正确。** 26.3 的 RenderType.toString() 字节码：

  public String toString();
    0: aload_0
    1: getfield name:Ljava/lang/String;
    4: aload_0
    5: getfield state:LRenderSetup;
    8: invokestatic String.valueOf(Object)
   11: invokedynamic makeConcatWithConstants(String,String)
   16: areturn

toString 明确拼接了 private/protected name 字段（以及 state），不是只有 pipeline/hash。PingOutlineRenderType 创建时使用 TYPE_NAME = "ping_block_outline" 作为 name，所以自定义实例的 toString 必然包含它；原版 lines_translucent_no_depth_write 不包含这个子串，不会误匹配。null 先返回 false，也不会 NPE。

边界：如果未来某个模组也创建 name 含 ping_block_outline 的 RenderType，会把它误判为我们的；当前没有这种类型，属可接受的低风险（可在 M10 改成带命名空间的唯一前缀）。

## B. WeakHashMap + synchronizedMap 缓存

**基本安全，但不是零成本。**

- 键是 RenderType，按对象 identity 使用（RenderType 未覆写 equals/hashCode）；WeakHashMap 不会 pin 住 RenderType，值只是 Boolean，不会反向引用键。
- Collections.synchronizedMap 包住 WeakHashMap；JDK 的 SynchronizedMap 对 computeIfAbsent 有同步覆盖，isCustom 只在首次 miss 时执行 toString，之后是 O(1) 查表。
- 调用点主要在渲染线程，但即使其它线程调用，synchronized 也提供可见性；不会死锁（computeIfAbsent 内部只调用 RenderType.toString，不回调本 map）。
- 每帧每个 shape outline 都会进入 isCustom，所以有一次 synchronized + hash 查表开销；对少量方块描边可接受。RenderType 实例通常长期存活，弱键不会频繁 miss；但若某个临时 render type 只活一帧，WeakHashMap 会在下一次 GC 后重新 toString 一次，仍非每帧。
- 缓存不是行为正确性条件；它是性能优化。如果担心热路径锁，可换 ConcurrentHashMap + 保留 cached 快路径，但当前版本不是阻塞问题。

## C. 门控口径漂移

**可接受，最坏一帧。**

- 现在 improved 读 Minecraft.options.improvedTransparency().get()（实时选项），renderWireframeTerrain 读 GameRenderState.levelRenderState（帧快照），缺失按 false。
- 与 vanilla GameRenderer.useImprovedTransparency() 的差别只在玩家在帧中途切换选项的一瞬间：
  - options 已 true、state 仍 false：我们投 outline，vanilla 走 classic；outline 相位仍会执行（LevelRenderer.addMainPass 无条件 executeOutline），通常只是该帧合成路径不同，不丢；
  - options 已 false、state 仍 true：我们投 afterTerrain，vanilla 跳过 afterTerrain -> 该帧可能少画一次；
  - render state 缺失：我们按 classic/或按 option 决定，vanilla 没有有效渲染；无实际帧。
- 最坏后果是单帧的相位/合成差异，不是持续错位或双画；切选项后下一帧恢复。相比之前 optionsRenderState 在早期恒 null 导致“路由永不进入”，现在的取舍更可靠。
- 若要求绝对同帧一致，可把 improved 也放入 render state（或在 gate 里读 GameRenderer.useImprovedTransparency()）；但那会重新引入 state 未就绪问题，且不是本修复的阻塞项。

## D. OIT 关闭时的回归

**路由判定式未变，但 TEMP 探针新增了默认路径日志。**

- OIT 关闭（improved=false）时：!improved -> true -> afterTerrain；和修复前一致；!afterTerrainFlag 仍进 solid；没有改调用顺序。
- 但 noteTransparencyProbe(...) 在 gate 里第一次调用就 INFO，noteHookSeen(...) 也在我们的/afterTerrain 提交上 INFO（上限 5）。所以 AGENTS/24 的“默认路径不打任何新日志”在 TEMP 探针存在时不成立。这不是渲染回归，但与本轮目标冲突；验证结束后必须删除 TEMP。

## E. TEMP 探针

- 判定影响：没有。noteHookSeen/noteTransparencyProbe 只读参数、写日志/计数器，不改变 customRenderType、afterTerrainFlag 或提交目标。
- 刷屏：有上限（5 + 1），不会无限刷；但 noteHookSeen 的第三个实参在每次调用点先求值 renderType.toString()，即使 hookProbeLogged 已到 5 也照样 toString；这是热路径的真实开销，需在删除前至少改成“先判断是否要 log，再格式化”。
- 最干净删除（等 OIT 实测通过后）：
  - OutlineRoutingState.java:29-33（TEMP 字段 + HOOK_PROBE_LIMIT）
  - OutlineRoutingState.java:47-76（noteHookSeen、noteTransparencyProbe）
  - SubmitNodeCollectionMixin.java:64-65（noteHookSeen 调用）
  - SubmitNodeCollectionMixin.java:130 与 :141（gate 里的 noteTransparencyProbe 调用）
  - 保留 markRouted/hasRouted/noteImprovedTransparencyFallback；这些不是 TEMP。
- 删除后不会影响 isCustom/gate，只是不再有临时 probe 日志。

## F. 编译 / 线程 / 性能风险

- 编译：bash tools/compile-check.sh 当前通过（common 262 / fabric-main 4 / fabric-client 126，0 error）。
- 线程：NAME_CACHE 的 synchronizedMap 提供安全；isCustom 是静态纯函数，不依赖 Minecraft；无新竞争。
- 性能：isCustom 的 map 查表每 shape 一次，但 toString 只在首次 miss；真正的热路径问题是 TEMP noteHookSeen 每调用一次 toString。建议在合入前删除 TEMP，或把 noteHookSeen 改成惰性格式化（先判断 hookProbeLogged < LIMIT && (afterTerrainFlag || customRenderType) 再计算参数）。
- 名称回退不会触发 pipeline 创建，因为只读 name/toString；没有额外 GPU/资源副作用。

## 建议

1. 路由/gate/isCustom 三处逻辑**可合入**。
2. **TEMP 探针不要合入**：按 E 删 5 处；若本轮必须保留以便用户实机观察，至少把 noteHookSeen 改成惰性格式化（仅前 5 次）并在验证后立即删除。
3. 文档登记：默认 OIT off 路径在探针存在时会新增 6 行 INFO（1 gate + 5 hook）；删除后恢复“默认不打印新日志”。
