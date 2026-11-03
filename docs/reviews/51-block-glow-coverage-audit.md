# 51 · 方块 ping 后「模型光晕」覆盖审计（26.3 静态全量）

> 分析：1号 repo-analyst｜2026-11-01｜**只读**：MC 26.3 客户端 jar + 当前源码；未跑 Gradle、未改源码。
> jar：`D:\Gradle\caches\fabric-loom\26.3\minecraft-client.jar`（`assets/minecraft/blockstates` = 1288，`models/block` = 2883，全部 models = 4192）。

## 0. 结论先行

- **1288 个方块（blockstate）中，有烘焙模型几何的 1192 个（92.5%），无几何的 96 个（7.5%），解析失败/缺失模型 **0** 个。**
- **无几何的 96 个里，87 个自带 BlockEntity**（banner 32 / shulker 17 / chest 11 / head+skull 14 / copper_golem_statue 8 / 其他 5），它们走 `entity_block` 的 **BER 光晕**路径，不依赖烘焙模型，因此**不受影响**。
- **真正的风险只有 9 个**：**无 BlockEntity 且无烘焙几何** → `block` 路径拿不到 `collectParts`，M7c C1 会回退线框；其中形状为空的（air/water/lava 等）**连回退线框都不显示**（与上游同行为，见 §3）。
- 结论：**覆盖良好**（92.5% 直接有光晕几何；BE 方块另有 BER 通道）；风险面是 9 个技术/流体方块，且与上游默认一致，**不建议为它们改设计**。

## 1. 统计表

| 项 | 数量 | 说明 |
|---|---|---|
| blockstate 总数 | **1288** | `assets/minecraft/blockstates/*.json` |
| 有烘焙几何 | **1192** | 解析链上任一模型有非空 `elements` |
| 无烘焙几何 | **96** | 见 §2 |
| 解析失败 / 模型缺失 | **0** | 所有引用模型都能解析到；`builtin/missing` 未出现 |
| 无几何 + 有 BlockEntity | **87** | BER 路径覆盖（banner/chest/skull/…） |
| **无几何 + 无 BlockEntity（风险）** | **9** | 见 §3 |

## 2. 无烘焙几何方块完整清单（96）

| 组 | 数量 | id |
|---|---|---|
| Banner（含 wall） | 32 | black_banner, black_wall_banner, blue_banner, blue_wall_banner, brown_banner, brown_wall_banner, cyan_banner, cyan_wall_banner, gray_banner, gray_wall_banner, green_banner, green_wall_banner, light_blue_banner, light_blue_wall_banner, light_gray_banner, light_gray_wall_banner, lime_banner, lime_wall_banner, magenta_banner, magenta_wall_banner, orange_banner, orange_wall_banner, pink_banner, pink_wall_banner, purple_banner, purple_wall_banner, red_banner, red_wall_banner, white_banner, white_wall_banner, yellow_banner, yellow_wall_banner |
| Shulker Box（16 色 + 原色） | 17 | black_shulker_box, blue_shulker_box, brown_shulker_box, cyan_shulker_box, gray_shulker_box, green_shulker_box, light_blue_shulker_box, light_gray_shulker_box, lime_shulker_box, magenta_shulker_box, orange_shulker_box, pink_shulker_box, purple_shulker_box, red_shulker_box, shulker_box, white_shulker_box, yellow_shulker_box |
| Chest 系 | 11 | chest, copper_chest, ender_chest, exposed_copper_chest, oxidized_copper_chest, trapped_chest, waxed_copper_chest, waxed_exposed_copper_chest, waxed_oxidized_copper_chest, waxed_weathered_copper_chest, weathered_copper_chest |
| Head / Skull（含 wall） | 14 | creeper_head, creeper_wall_head, dragon_head, dragon_wall_head, piglin_head, piglin_wall_head, player_head, player_wall_head, skeleton_skull, skeleton_wall_skull, wither_skeleton_skull, wither_skeleton_wall_skull, zombie_head, zombie_wall_head |
| Copper Golem Statue | 8 | copper_golem_statue, exposed_copper_golem_statue, oxidized_copper_golem_statue, waxed_copper_golem_statue, waxed_exposed_copper_golem_statue, waxed_oxidized_copper_golem_statue, waxed_weathered_copper_golem_statue, weathered_copper_golem_statue |
| 其他 BE | 5 | conduit, decorated_pot, end_gateway, end_portal, moving_piston |
| **无 BE（风险组）** | **9** | **air, barrier, bubble_column, cave_air, lava, light, structure_void, void_air, water** |

> 注：以上全部有对应 `blockstates/<id>.json`；`chest`/`banner`/`skull` 等模型只含 `textures.particle` 或 `parent` 无 `elements`，与 Lead 给的 chest 例子一致。

## 3. 风险清单：无 BE 且无烘焙几何（9）

| id | 有无模型几何 | 形状是否为空 | M7c C1 实际行为 | 上游默认行为 | 判定 |
|---|---|---|---|---|---|
| `air` / `cave_air` / `void_air` | 无 | 空 | 不发光、无回退线框 | 同（VOXEL 也空） | **不可被 ping**（射线不会命中空气） |
| `water` / `lava` | 无 | 空 | 不发光、无回退线框 | 同 | 仅 `markFluids=true` 时可 ping；与上游一致 |
| `bubble_column` | 无 | 空 | 同上 | 同 | 同上 |
| `barrier` | 无 | **全方块形状**（碰撞用） | 回退 **M7a 线框**（可见） | 上游同样 VOXEL 线框 | 一致；非正常获取 |
| `light` | 无 | 极小/空（待实机） | 预计无/极小线框 | 同 | 技术方块，非正常获取 |
| `structure_void` | 无 | 小形状（待实机） | 预计小线框 | 同 | 技术方块 |

> 说明：`barrier`/`light`/`structure_void` 的形状判定未在静态阶段实机确认（标 **待验证**）；前 6 个（空气/流体）在“无几何”下本来就不该有光晕，**与上游默认一致**，不是移植回归。

## 4. 实机抽样清单（20 个，覆盖 8 类）

| # | 方块 id | 类别 | 期望（当前实现） |
|---|---|---|---|
| 1 | `stone` | 普通方块 | 模型光晕 |
| 2 | `oak_stairs` | 楼梯 | 模型光晕（跟随阶梯形状） |
| 3 | `oak_fence` | 栅栏 | 模型光晕 |
| 4 | `oak_slab` | 台阶 | 模型光晕 |
| 5 | `short_grass` | 植物（cross） | 模型光晕（小十字） |
| 6 | `oak_sapling` | 植物 | 模型光晕 |
| 7 | `oak_sign` | 告示牌（BE） | **BER 光晕**（无烘焙几何） |
| 8 | `oak_wall_sign` | 墙告示牌（BE） | BER 光晕 |
| 9 | `chest` | 箱子（BE） | BER 光晕 |
| 10 | `trapped_chest` | 陷阱箱（BE） | BER 光晕 |
| 11 | `furnace` | 熔炉（BE + 有几何） | BER 光晕（优先）/模型光晕 |
| 12 | `campfire` | 营火（BE + 有几何） | BER 光晕 |
| 13 | `spawner` | 刷怪笼（BE） | BER 光晕 |
| 14 | `skeleton_skull` | 头颅（BE，无几何） | BER 光晕 |
| 15 | `red_bed` | 床（BE，无几何） | BER 光晕 |
| 16 | `bell` | 钟（BE + 有几何） | BER 光晕 |
| 17 | `piston` | 活塞（有几何） | 模型光晕（若 piston 无 BE） |
| 18 | `water` | 含水/流体（风险组） | 不发光（无形状、无几何） |
| 19 | `barrier` | 技术方块（风险组） | 回退线框 |
| 20 | `oak_leaves` | 半透明方块 | 模型光晕（注意透明排序） |

> 另建议补 1 个 **modded 常见方块**（如 Create/机械动力或任意整合包方块）实机点一次，验证 modded 模型集合同样进入 `collectParts`。

## 5. 方法（可复现）

1. 从 `minecraft-client.jar` 读 `assets/minecraft/blockstates/*.json` 与 `models/**/*.json`；
2. blockstate 的 `variants[*].model`（含数组/带 weight）与 `multipart[*].apply.model` 收集引用；模型名去 `minecraft:` 前缀；
3. 沿 `parent` 链解析（最多 30 层），任一链上模型 `elements` 为非空数组 → 该方块“有几何”；链断裂/缺失 → `unresolved`；
4. BE 判定：按 id 分组（banner/shulker/chest/head/statue/其他），并用 `javap` 对不确定类确认（`CopperGolemStatueBlock`/`EndPortalBlock`/`MovingPistonBlock`/`EndGatewayBlock` 均 `extends BaseEntityBlock`）；
5. 本次统计：`blockstates=1288`、`models=4192`（其中 `models/block=2883`），全部解析成功。

## 6. 局限

- 静态解析基于**原版 jar 默认资源**；资源包/模组可增删模型，实机需按抽样表验证。
- `elements` 非空≠一定渲染出可见像素（全透明贴图、零尺寸元素等）；本审计只回答“有没有可提交的几何”。
- BE 清单按原版类名/已知分类整理，未遍历全部注册；若发现漏标，按 `javap` 复核后补。

## 7. 与 BER 来源的交叉判定（2026-11-01，来自 `BlockEntityRenderers` 全量反汇编）

| 项 | 数量 |
|---|---|
| `BlockEntityType` | **49** |
| 注册了渲染器 | **26**（24 个类，箱子三兄弟共用） |
| 无渲染器（只能靠烘焙模型） | **23** |

**BER 带模型通道（走 C2 的 outlineColor 光晕）13 类**：`PISTON`、`CHEST`/`ENDER_CHEST`/`TRAPPED_CHEST`、`ENCHANTING_TABLE`、`LECTERN`、`SKULL`、`BANNER`、`SHULKER_BOX`、`CONDUIT`、`BELL`、`DECORATED_POT`、`COPPER_GOLEM_STATUE`。
> `submitModelPart` 的 8 参重载会把最后一个 int 原样传给 `submitModel(..., UvMapping, int)` 的 outlineColor，故 CONDUIT / DECORATED_POT 的 BER 光晕判定成立（已字节码核实）。

**BER 无模型通道（落到烘焙模型）13 类**：`SIGN`、`HANGING_SIGN`（只提交文字；牌子本体由烘焙模型发光，**文字不亮**）、`MOB_SPAWNER`、`TRIAL_SPAWNER`（内部实体不亮）、`CAMPFIRE`、`BRUSHABLE_BLOCK`、`SHELF`、`VAULT`（内部物品不亮）、`BEACON`（光柱不亮）、`STRUCTURE_BLOCK`、`TEST_INSTANCE_BLOCK`。

**唯二「既无 BER 模型、又无烘焙几何」的方块**：`end_portal`、`end_gateway`。
- 它们的 BER 只提交 `submitCustomGeometry`，而 26.3 的 `submitCustomGeometry` **没有 outline 通道**（签名里没有颜色/outline 参数）；
- 结果：回退 VoxelShape 线框，形状为空时无任何显示；
- 上游用 `OutlineBufferSource` 包裹整个 buffer source，这类自定义几何会一起发光 → 属**平台强制差异**，应登记到 `24-fidelity-constraint-audit.md`。

**结论**：1288 个方块中，**1192 有烘焙几何**（普通方块恒走模型光晕；带 BE 的则由 BER 或烘焙模型覆盖），96 无几何里 87 有 BE 走 BER；真正“ping 后无模型光晕”的只有 `end_portal`/`end_gateway`（+ 上游同样不可见的空气/流体类），**不是移植回归**。

