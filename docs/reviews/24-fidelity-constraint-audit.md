# 24 · 保真约束审计：移植不得改变上游呈现的效果

> 约束来源：用户 2026-10-30 明确要求 —— **移植后的模组代码不可修改原来模组源代码中呈现的效果**。
> 本文件盘点当前所有偏离、判定可接受性，并给出对 M7 的直接结论。

## 1. 上游的描边视觉规格（M7 的复刻目标）

`deferred/client/outline/BlockOutlineRenderType.java:82-93` 明确定义了方块描边的渲染状态：

| 维度 | 上游取值 |
| --- | --- |
| 顶点模式 | `VertexFormat.Mode.LINES` |
| **线宽** | **3.75 px**（`LINE_WIDTH`，约为原版选择框 2.5px 的 1.5×） |
| Shader | `rendertype_lines` |
| 层 | `VIEW_OFFSET_Z_LAYERING` |
| 混合 | `TRANSLUCENT_TRANSPARENCY` |
| 渲染目标 | `MAIN_TARGET` |
| 剔除 | `NO_CULL` |
| **深度** | **`NO_DEPTH_TEST`（穿墙）** |
| 写掩码 | `COLOR_WRITE` |

实体描边与模型光晕分别走 `EntityOutline*`（`EntityRenderer.extractRenderState` 写 `outlineColor`）与 `VirtualBlockDisplayRenderer`（真实方块模型/方块实体渲染器 + 发光）。

## 2. 当前偏离清单（逐项判定）

| # | 偏离 | 位置 | 性质 | 是否改变上游效果 |
| --- | --- | --- | --- | --- |
| 1 | 默认键 `-1` → `InputConstants.UNKNOWN.getValue()`（0） | `InputUtils` | **平台强制**（26.3 `isKeyDown` 按 SDL 扫描码索引，`-1` 必崩） | 否（仍是「未绑定」，按键行为与上游一致） |
| 2 | `KeyMappingMixin` 越界键守卫（坏绑定当未按下 + 一次 WARN） | `mixin/KeyMappingMixin` | 防御性 | 否（只在**非法**键值下生效，正常路径零影响） |
| 3 | Sable 客户端桩（`resolvePosition` 恒 `empty`） | `SableClientProvider` | 集成桩（M9） | **是**（装了 Sable 时上游会吸附子层级方块，现在回退 anchor）→ M9 恢复 |
| 4 | ~~Distant Horizons / VoiceChat 桩~~ **已恢复**（M9-3 / M9-2）；只剩 **FTB Teams** 仍为桩 | `integration/*` | 集成桩（M9） | DH / VoiceChat 已回填上游实现 → **否**（2026-11-04 更新）；FTB Teams 见 **#17**（平台强制） |
| 5 | HUD 用绘制顺序代替上游的 z 阶梯 | `MarkerHudRenderer` | 26.3 无 z 参数 | 否（3号 复核：视觉等价） |
| 6 | `scale` 为 0/NaN 时的兜底下限 0.01 | `MarkerHudRenderer` | 防御性 | 否（正常路径零影响） |
| 7 | 轮盘：曾加过扇区楔形填充与纯色图标 | `WheelHudRenderer` | **已回退** | 否（已与上游一致） |
| 8 | E1/E2/E3 实验桩（`OutlineExperiment`） | `render/spike/` | 调试专用，文件开关 | 否（没有 `outline-experiment.txt` 时完全不注册） |

> 结论：**当前运行路径上不存在改变上游视觉效果的偏离**；3/4 两项是「集成能力缺失」而非「效果被改」，且都登记在 M9。

## 3. 对 M7 的直接结论（重要）

用户约束把 E2 的路线问题**从「哪条最省事」变成「哪条能 1:1 复刻」**：

| 候选路线 | 能否复刻上游视觉 | 判定 |
| --- | --- | --- |
| 现成 `RenderTypes.lines*` | 深度测试固定开启（`LINES_SNIPPET` 用的是 `DepthStencilState.DEFAULT`），**无法穿墙** | ❌ 达不到 `NO_DEPTH_TEST` |
| `Gizmos` / `DrawableGizmoPrimitives`（mode 6） | 走原版 gizmo 管线，线宽/混合/剔除/深度都由原版决定，**无法保证 3.75px、NO_CULL、COLOR_WRITE** | ❌ 属「改变效果」，**只能作参照实验** |
| **自定义 RenderPipeline + RenderType** | 可以逐项复刻 `LINES / 3.75px / rendertype_lines / VIEW_OFFSET_Z_LAYERING / TRANSLUCENT / MAIN_TARGET / NO_CULL / NO_DEPTH_TEST / COLOR_WRITE` | ✅ **唯一满足约束的路线** |

而 26.3 里 `RenderPipelines.register(...)` 是 **private**、`RenderType.create(String, RenderSetup)` 是 **package-private** → **必须引入 access widener（或等价 mixin）**。

**所以：M7 走 access widener 是保真约束的必然结果，不是可选项。**

## 4. 待用户决策

| # | 决策 | 说明 |
| --- | --- | --- |
| 1 | 是否批准在 `build.gradle` 引入 access widener（新增 `.accesswidener` 文件 + 1 行 `loom { accessWidenerPath }`） | 打开 `RenderPipelines.register` 与 `RenderType.create`。这是复刻上游描边的必要前提 |
| 2 | 若不愿引入 AW：只能接受 gizmo 近似（线宽/混合由原版决定） | 与「不得改变效果」冲突，需你明确豁免 |

## 5. 其他需要保持一致的视觉点（M7 施工清单）

- 方块线框：颜色 = ping 类型 `outlineColor`（`0xFF000000 | outlineColor`），**不透明度由 ping 类型决定**（`RenderColorPolicy.markerColor` 原样透传）
- 实体描边：颜色 = ping 色；只在 ping 生效期间出现
- 模型光晕：`entity_block` 与白名单方块走真实模型/方块实体渲染器 + 发光；黑名单优先

> **2026-11-01 闭环**：`entity_block` 原先只能走 M7a 线框（`BlockModelGlowPass` 对非 `block` 目标早退），是当时**唯一登记在案的功能性偏差**。M7c-C2 已改为在 `BlockEntityRenderDispatcher#submit` 解析 ping 色、经 `submitModel` 的 outlineColor 走真实 BER 模型发光；线框仅在“本帧该位置真的画出了光晕”时才抑制，其余路径回退线框（上游 fallback 语义）。
>
> **来源顺序与上游一致（2026-11-01 补）**：BER → **烘焙方块模型** → VoxelShape。补第二层是因为 26.3 熔炉无 BER、营火 BER 只提交物品，纯 BER 路径对它们 0 画；`BlockModelGlowPass` 现接受 `entity_block`，仅在 BER 本帧未发光时兜底，`missingModel` 仍回退线框。审查 `docs/reviews/50-m7c-c2-review.md`；**✅ 已实机销项**（2026-11-01 阶段总览记为「M7 含 C2 全部完成并实机通过」，`docs/PORTING-PROGRESS.md:22`；2026-11-03 P0 运行再次见到 C2 路径生效——`run/logs/latest.log:368` `entity-block model glow active (outlineColor via dispatch hook)`，该次紧随其后的 `model hook not observed` WARN 已由 `docs/reviews/61-model-hook-warn-audit.md` 判定为**营火误报**，与本项销项无关）。
- 距离补偿、文本、箭头等 HUD 元素已在 M6.5 对齐，不受 M7 影响

## 6. M8a 设置界面（2026-10-31）的平台强制偏离

> 全部源于 26.3 的 GUI 重构，非设计选择。审查记录见 `44-m8a1b-review.md`。

| # | 偏离 | 位置 | 性质 | 是否改变上游效果 |
| --- | --- | --- | --- | --- |
| 9 | **客户端权限门 = 连接可达性**（不再读客户端权限） | `SettingsScreen.updatePermissionState` | **平台强制**：26.3 客户端无权限数据（`LocalPlayer#permissions()` 恒 `NO_PERMISSIONS`，`LocalPlayer#setPermissions` 无任何调用者，Fabric API 亦不同步）。服务端 `ServerPlayer.permissions()` 走 `MinecraftServer.getProfilePermissions`，权威可靠 | 否：两个 scope tab 上游本就常驻可见，权限只影响页内控件是否可编辑；非 op 现在会多发一次快照请求，由服务端 denial 回落到同样的 locked 状态 |
| 10 | 数字输入框由 `EditBox#setFilter` 改为 responder 内整段回退 | `SettingsScreen.createServerIntegerField` | **平台强制**：26.3 删除 `setFilter`（只剩 `setResponder`/`addFormatter`） | 极小：非法输入被整段拒绝（与上游一致），但回退用 `setValue`，光标会跳到行尾；上游 filter 是插入阶段拒绝、光标不动 |
| 11 | 自写 `ScopeTabButton extends TabButton` | `SettingsScreen.ScopeTabButton` | **平台强制**：26.3 的 `TabButton` 变为抽象类，唯一具体实现 `MenuTabBar$MenuTabButton` 会额外绘制菜单背景与焦点下划线 | 待实机确认：贴图集合（`widget/tab_selected`/`tab`/`tab_selected_highlighted`/`tab_highlighted`）、选中上移 3px、文本边距 1px 均照抄 `MenuTabButton` 反汇编，未复制菜单背景与下划线 |
| 12 | `Tab.getLayout()` 返回空 `GridLayout` | `SettingsScreen.ScopeTab` | 平台强制：26.3 `Tab` 接口新增该抽象方法 | 否：`TabManager`/`TabNavigationBar` 字节码中 `Tab.getLayout()` 调用次数为 0 |

## 7. 对账补登记（2026-10-31，来自 `docs/reviews/45-upstream-diff-audit.md`）

| # | 偏离 | 位置 | 性质 | 是否改变上游效果 |
| --- | --- | --- | --- | --- |
| 13 | 实体描边目标类型白名单：上游 `EntityOutlineSelection` **不按 targetType 过滤**（只认 `TargetKey.EntityKey` + `Target.EntityTarget`）；本地只接受 `entity` / `dropped_item` | `client/outline/EntityOutlineSelection.java:35-36,81-83` | 与上游的行为差异（当前 catalog 下等价；见 `docs/reviews/36-review-m7b-entity-outline.md` S4-1） | **潜在**：未来新增 entity 类 targetType 时必须同步白名单，否则该类型**静默不描边** |
| 14 | 实体描边快照简化：上游持 `EntityOutlineSpec` 快照并逐实体查询 `shouldOutline/colorFor`；本地简化为 `locator→color` map + 60 帧 hook 诊断 WARN | `client/outline/EntityOutlineState.java:26,116-125` | 实现差异（`35-m7b-spec.md`、`36` 复审讨论过） | 否：`spec` 的 `pingTypeId` 不参与渲染；诊断只写日志 |
| 15 | tab 栏宽度分配由本类复刻：26.3 把「按 `min(400,width)-28` 均分、`roundToward(…,2)` 取整」的逻辑从 `TabNavigationBar.arrangeElements` 移进了 `MenuTabBar`，本地在 `SettingsScreen.arrangeScopeTabBar` 重现同一公式 | `SettingsScreen.arrangeScopeTabBar` | **平台强制**（26.3 基类不再给按钮设宽，否则按钮恒 0 宽） | ✅ **已实机确认（2026-10-31，用户）**：tab 显示正常、位置与选中态符合预期 → 销项。公式与 26.3 `MenuTabBar` 字节码逐项一致（review 48） |
| 16 | `end_portal` / `end_gateway` 的 BER 只提交 `submitCustomGeometry`，而 26.3 该 API **没有 outline 通道**，故只能回退 VoxelShape 线框（形状为空时无显示）；上游用 `OutlineBufferSource` 包裹整个 buffer source，这类自定义几何会一起发光 | `BlockEntityRenderDispatcherMixin` / C2 路径 | **平台强制**（`submitCustomGeometry(PoseStack, RenderType, CustomGeometryRenderer)` 无颜色参数） | **是**（仅这两个技术方块；全量审计见 `51-block-glow-coverage-audit.md` §7） |
| 17 | **FTB Teams 集成缺失**：对端模组无 26.3 构建（26.x 线止于 `26.1.2.4`），本地 `FTBTeamsWrapper` 恒返回空 | `integration/FTBTeamsWrapper` | **平台强制**（第三方无对应版本，非我方取舍） | 是（未装 FTB 时行为相同；装了也无法识别其队伍） |
| 18 | **Sable 集成缺失**：Sable 仅 1.21.1（最新 `2.0.6`），本地 `SableIntegration` / `SableClientProvider` / `SableExternalBlockServerProvider` 四入口恒 empty | `integration/sable/**` | **平台强制** | 是（Sable 的子层级/外置方块回落 anchor） |

> **交叉引用（N5）**：`InputUtils` 默认键 `UNKNOWN(0)` 与 `KeyMappingMixin` 越界守卫的完整依据见 `AGENTS.md §7-11`（本清单 #1/#2 已登记，不再重复）。

## 8. M9-1 备注：依赖获取方式变更（2026-11-02，**不新增编号**）

Mod Menu 集成落地时，编译期依赖由 maven 坐标 `com.terraformersmc:modmenu:21.0.0` **改为 `build.gradle` 直接吃 `run/mods/modmenu-21.0.0.jar`**（本机 Gradle 无可用网络），CI 侧由 workflow 用 `curl` 从官方 maven 补齐同样的 jar。

判定：**不新增偏离编号**。理由：

1. 入口类 `src/client/java/nx/pingwheel/fabric/integration/ModMenuIntegration.java` 与上游同名文件**逐字节一致**，entrypoint 键名（`modmenu`）也与上游一致；
2. 点开的就是既有 `SettingsScreen`（M8a 已与上游对齐）——**运行时呈现与上游 1:1**；
3. 变化只发生在**构建期**（远端 maven 解析 → 本地 jar），不进入运行时行为，不改变任何视觉/交互；
4. `fabric.mod.json` 的 `depends`/`recommends` 仍与上游一致地**不声明** Mod Menu（未安装则无按钮，上游同样如此）。

对照：`#17`（FTB Teams）/ `#18`（Sable）是**运行时集成能力缺失**，属真实平台强制差异，编号保留。

## 9. M9-2 备注：VoiceChat 集成恢复（2026-11-03，**不新增编号**）

Simple Voice Chat 集成由桩恢复为上游实现：`VoiceChatIntegration`（37 行）、`VoiceChatWrapper`（43 行）与上游同名文件**逐字节一致**（`diff` 无差异、SHA256 相同），`fabric.mod.json` 加了与上游同名的 `voicechat` entrypoint，**未新增 `depends`/`recommends`**。

判定：**不新增偏离编号**。理由：

1. 两个类逐字节一致、entrypoint 键名一致、插件 id = `Global.MOD_ID`；
2. `@ForgeVoicechatPlugin` 在 Fabric 上不参与装载（该注解只服务 Forge 侧的插件发现，Fabric 侧由 `voicechat` entrypoint 触发）——**上游的 fabric 产物是同一个类 + 同一个注解**，属上游原样，非移植引入；
3. 判定用的是 VoiceChat 的**分组（group）**，`VoiceChat > FTB Teams > 原版队伍 > NONE` 的优先级与上游 `TeamContextHandler` 一致；
4. 编译期依赖同样改走 `run/mods` 本地完整 jar（与 M9-1 同类的构建期差异，见 §8），不改变运行时行为。

对照：`#17`（FTB Teams）/ `#18`（Sable）仍是**运行时集成能力缺失**，编号保留；本项属「能力恢复」，不需要编号。

## 10. 备注：improved transparency 下的描边相位回退（2026-11-03，**新增 #19**，来自 `57-pipeline-conflict-audit.md` 的 P1-1）

| # | 偏离 | 位置 | 性质 | 是否改变上游效果 |
| --- | --- | --- | --- | --- |
| 19 | `Options.improvedTransparency` 打开时，自定义描边由 `afterTerrain` 相位改投 `outline` 相位 | `fabric/src/client/java/nx/pingwheel/common/mixin/SubmitNodeCollectionMixin.java`（`pingforit$classicTransparencyActive`） | **平台强制**（26.3 相位调度） | **部分**：几何/管线/线宽不变，穿墙性质保留；但最终合成走原版 `ENTITY_OUTLINE_BLIT` 的 blend，**颜色/亮度需实机比对**（见下方说明） |

**依据（`javap` 字节码，2026-11-03）**：

1. `FeatureRenderDispatcher$PreparedFrame.executeTranslucentAfterTerrain` 的**唯一**调用者是 `LevelRenderer.executeClassicTransparency`（偏移 131）。
2. `executeClassicTransparency` 只在 `improvedTransparency == false` 时执行（`lambda$addMainPass$0` 偏移 177-187：`iload_2 / ifne 190`）；`improvedTransparency == true` 时走 `executeOit`（偏移 234-242），而它只执行 `oitTranslucent` 相位。
3. `executeOutline`（偏移 248）由 `currentFrameRendersEntityOutline = preparedFrame.hasAnyOutline() && levelRenderState.shouldShowEntityOutlines` 触发（`LevelRenderer` 偏移 107-128），我们投进 `outline` 相位会让 `hasAnyOutline()` 为真。
4. `Options.improvedTransparency` 的默认值是 **false**（`iconst_0` 传给 `OptionInstance.createBoolean`），所以默认路径仍是 `afterTerrain` —— 即 2026-10-31 实机验收过的那条路径。

**影响面**：仅影响手动开启「改进透明度」的玩家。回退后描边在 OIT 之后绘制，几何/管线/线宽（3.75px / NO_DEPTH_TEST / NO_CULL / COLOR_WRITE）不变；但 `executeOutline` 渲染进独立的 `entityOutlineTarget`（ZERO_CLEAR），再由 `blitEntityOutline()` 用 `RenderPipelines.ENTITY_OUTLINE_BLIT` 合回主画面 —— **合成 blend 由原版决定，颜色/亮度只能靠实机与默认路径对比**（该 blit 管线没有深度状态，所以穿墙性质保留）。门控 `shouldShowEntityOutlines = !camera.isPanoramicMode() && playerState.hasPlayer`，游戏内恒真，只有主菜单全景/无玩家时为假。首次回退会打一条 `[PingForIt] improved transparency is ON: … routing the outline to the outline phase instead`。

**为什么不是“看得更好”的替代**：这是 26.3 相位调度的强制结果（`afterTerrain` 在 OIT 模式下根本没有执行点），不是主观优化；不改变任何颜色/线宽/深度/混合语义。

**实测结论（2026-11-04）**：该回退路由**在本环境（MC 26.3 + Fabric Loader 0.20.1 + 无 DH 的 57-mod 会话，21:24:44–21:25:27）从未触发** —— `[PingForIt] improved transparency is ON …` **0 条**（连续三处修复后仍为 0）。已排除的外部因素：① `run/mods` 里**已无** Distant Horizons（日志中 `Disabling improved transparency` **0 条**），② 运行期间 `improvedTransparency` 为 **true**（注：`run/options.txt` 在运行结束后的 21:27:29 被改回 `false`，以文件 mtime 与日志时间可辨），③ mixin **已织入**（`debug.log:2547` `Mixing SubmitNodeCollectionMixin from pingforit.mixins.json into net.minecraft.client.renderer.SubmitNodeCollection`），④ 我们的 pass **确实在提交**描边（`debug.log` 的 `block outline: submitted=0/1/2/3`，21:25:17–22）。

**因此实际行为是**：描边仍由我们的 **depth-off 自定义管线**在 **solid 桶**绘制 —— **仍然可见、仍然穿墙**，与回退路径的唯一差别是「被半透明物体叠加时可能被染色」（回退路径会在 `entityOutlineTarget` 里走原版 `ENTITY_OUTLINE_BLIT` 合成）。

**定性**：记为**已知限制**（本条 #19 的触发条件在当前环境无法复现），**不阻塞发布**。若后续要深究，需要加一支**只记录我们自己的提交**的探针，用来区分 `isCustom` 判定与 `afterTerrain` 是否真的执行（当前 `submitted=N` 只能证明提交，不能证明相位）。

## 11. M9-3 备注：Distant Horizons 集成恢复（2026-11-04，**不新增编号**）

`DistantHorizonsIntegration` 由 76 行 stub 换回**上游 201 行实现**（与上游仅 4 行 javadoc 缩进差），调用点 `ClientPingRuntime` **零改动**；构建侧改走**方案 A**（编译期只吃 `compile-libs/DistantHorizonsApi-7.2.0.jar`，`run/mods` 的完整 jar 只做运行时）。复审 `docs/reviews/64-m9-3-dh-integration-review.md`（通过、无阻塞）。

判定：**不新增偏离编号**。理由：

1. 这是**恢复上游行为**，不是新增/改变效果 —— 它把 §2 第 4 行登记过的「集成能力缺失」消掉了一项（该行已同步更新为「DH 已恢复」）；
2. 接线与平台无关（同一份 DH API 7.2.0 在 26.3 上签名逐条一致，见 `63` §5），没有引入任何 26.3 特有的替代实现；
3. 唯一的行为差别是「远处地形现在也能 ping 到」，与上游 1.21.1 一致；失败路径按上游语义降级到普通同步射线。

对照：`#17`（FTB Teams）仍是运行时能力缺失，编号保留；本项属「能力恢复」，不需要编号。**已知限制仍按 #19**（DH 会关闭 OIT，`outline` 分支在装 DH 时不可达）。