# 19 · 功能缺口清单（上游 0.4.2-pfi-beta1 → 当前 26.3 移植）

> 基线：`port-M6.5-restored`（2026-10-30）｜上游：`Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1`（298 java）｜当前：`src` 217 java + `deferred/` 96 java

## 0. 一句话结论

上游 **298** 个 Java 文件里：**209 个已实现并接线（70%）**、**85 个在 `deferred/` 有代码但未接线（28.5%）**、**真正丢失 0 个**。
缺口按**功能**看集中在四块：**世界空间渲染（M7）**、**设置界面与资源重载（M8）**、**可选集成（M9）**、**测试与发布（M10）**。

## 1. 完全未接线的玩家可见功能（M7 · 世界空间渲染）

> 代码位置：`deferred/client/outline/`（62 java）+ `deferred/mixin/`（LevelRendererMixin / GameRendererMixin / ModelBlockRendererMixin / DisplayBlockDisplayAccessor）

| 功能 | 上游表现 | 当前表现 | 归属 |
| --- | --- | --- | --- |
| **方块描边** | ping 方块时在方块本体画 VoxelShape 线框（穿墙、线宽 3.75px、随透视缩放） | **完全没有**，只有 HUD 菱形 | M7 |
| **实体描边/发光** | ping 生物时实体整体发光描边（ping 色） | 无 | M7 |
| **虚拟方块模型光晕** | `entity_block`/白名单方块渲染真实方块模型 + 发光（`VirtualBlockDisplayRenderer` 899 行） | 无 | M7 |
| **模型渲染钩子** | `ModelBlockRendererMixin` 替换模型种子（42L） | 无 | M7 |
| **世界渲染时序** | `LevelRendererMixin`（renderLevel / outline batch / PostChain） | 无（26.3 管线需重写） | M7 |

> 注：HUD 层已用 `MarkerHudRenderer` / `MarkerLabelRenderer` 完整复刻（菱形/物品图标/距离/目标名/玩家名/头颅/屏外箭头），所以「标记点本身」不缺；缺的是**世界里的高亮**。

## 2. UI 与资源配置（M8）

| 功能 | 上游 | 当前 | 影响 |
| --- | --- | --- | --- |
| **设置界面** | `SettingsScreen` 47 KB + 7 个辅助类（分类/导航/布局/服务器设置/延迟动作） | `src/client/.../screen/SettingsScreen.java` **1.6 KB 桩** | 玩家无法在游戏里改配置（只能手改 `config/pingforit.json`） |
| **自定义 ping 贴图** | `ResourceReloadListener` 检测资源包是否提供 `ping.png`，有则用 12px 贴图替换菱形 | 监听器已摘除（v0 会卡加载条） | 资源包替换图标**不生效** |
| **切换提示** | `SelectionToggleNoticeRenderer`（按键切换「穿透透明方块/黑名单/流体」时右侧提示） | `onRenderToggleNotice` 空方法 | 切换无视觉反馈 |

## 3. 可选集成（M9）

| 集成 | 上游能力 | 当前 |
| --- | --- | --- |
| **Mod Menu** | Mods 列表里的配置入口（`deferred/modmenu/ModMenuIntegration.java`） | 未注册，无入口 |
| **Simple Voice Chat** | 语音频道内的 ping 路由与音量 | `VoiceChatIntegration` 纯桩，`VoiceChatWrapper.getGroupId` 恒 `empty` |
| **FTB Teams** | 队伍判定/队伍色 | `FTBTeamsWrapper` 桩，回退 vanilla team |
| **Distant Horizons** | 对 DH 远景方块/实体的 ping 命中 | `traceDistantAsync` 恒 `false` |
| **Sable（Create 子层级）** | 子层级方块的命中与呈现 | 3 个 client 类在 `deferred/client/integration/sable/`，运行时 `SableClientProvider` 恒 `empty`（回退 anchor） |

## 4. 测试与发布（M10）

| 项 | 上游 | 当前 |
| --- | --- | --- |
| 单元测试 | **188 个测试类**（common/src/test） | **0** |
| 专用服务器 | 支持 | `runServer` **未验证**（M4 遗留） |
| 合规 | `THIRD-PARTY-NOTICES.md` | 未入 jar；`fabric.mod.json` 的 `contact` 为空 |
| 多端 | Fabric + NeoForge/Forge | 只做 Fabric（决策 D1，非缺口） |

## 5. 不是缺口的三类

### 5.1 已被新实现替代（旧代码留在 deferred/ 待清理）
| 上游类 | 替代者 |
| --- | --- |
| `OverlayRenderer` / `PingLocationRenderer` / `DirectionIndicatorRenderer` / `DrawContext` | `MarkerHudRenderer` + `MarkerLabelRenderer`（M6.5） |
| `WheelOverlayRenderer` / `WheelOpacity` | `WheelHudRenderer`（M6） |
| `GuiMixin` | `HudElementRegistry.addLast` |

### 5.2 我们主动加的差异化（不是上游功能）
- `KeyMappingMixin` 的越界键守卫（防 `-1` 崩溃）——上游没有，属健壮性补强
- Sable/集成桩（用无依赖实现替代真实依赖）
- `src/main` / `src/client` 源集拆分（上游是 common + 各 loader，架构差异）

### 5.3 已完整实现的（用于对照覆盖面）
14 个网络包与协议版本校验、服务端权威（受众/赢家/限流/移除/过期）、配置系统（版本迁移/备份/校验）、命令（客户端 + 服务端）、轮盘与 7 类型分桶、HUD 全要素（菱形/物品图标/距离/目标名/玩家名/头颅/屏外箭头）、方向提示音、聊天播报、目标名解析（Item/Block/Entity/Team）、射线拾取与选择策略、标记生命周期。

## 6. 建议顺序

| 顺序 | 内容 | 预计 |
| --- | --- | --- |
| 1 | **M7-prep**：E1/E2 实验钉死「线框穿墙」方案 | 1 天 |
| 2 | **M7**：方块描边 → 实体描边 →（可选）模型光晕 | 4–7 天 |
| 3 | **M8**：设置界面 + 资源重载 v1（自定义贴图） | 2–3 天 |
| 4 | **M9**：ModMenu / 语音 / FTB / DH / Sable | 3–5 天 |
| 5 | **M10**：测试基座 + runServer + 合规 | 2–3 天 |
