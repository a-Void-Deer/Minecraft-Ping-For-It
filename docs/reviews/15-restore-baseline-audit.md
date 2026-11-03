# 15 · 恢复后基线对账：文件数 / jar / 纯净性 / 元数据 / 资源

- 任务：task-11（4号 build-engineer）｜日期：2026-10-30｜基线：tag `restore-2026-10-29`
- 方法：只读。对照 = 上游 tarball（298 java）+ 当前树 + 已构建 jar + 16 个会话日志（`.tmp-restore/*.jsonl.zstd`）+ `docs/reviews/14`
- 未改 `src/**`、`deferred/**`、`run/**`；本文所有数字均可用文末命令复跑
- 结论先行：**构建与产物可用、元数据/资源/许可证正确；文件数差异可解释到「类」这一级，但 3 个我方可控 main 文件与 4 个 class 的差额无法用现有物证钉死名字，标 待确认**

---

## 0. 结论速览

| 项 | 结论 |
| --- | --- |
| 文件数 162/55/96 | main −3、client +1、deferred +4 vs 事故前 165/54/92 |
| main −3 | 不是上游丢失：**298 个上游 java 仍在树里（0 缺失）**；推断缺的是 3 个**我方可控 main 类**（由数量差与上游 0 缺失反推；名字无法从现存物证恢复，见第 2.3 节） |
| client +1 | 净变化 = **+5 个恢复期 stub（每个 1 class）− 4 个 Sable 实现文件移出编译路径** |
| deferred +4 | = 4 个 Sable client 文件（名字见第 2.2 节），因无 Sable API 依赖而从 src/client 迁入 deferred |
| jar | 649 419 B / **414 entries / 345 class** / major 69；fabric.mod.json id=pingforit、version=0.4.2-pfi-beta1、license=MIT、mixins 对象形式、env client；5 个 SPI 文件；assets 12 + icon |
| 纯净性 | src/main 中 `net.minecraft.client.` = **0**、`com.mojang.blaze3d` = **0**；deferred 未进编译路径（build.gradle/settings.gradle/gradle.properties 引用 = 0） |
| LICENSE | jar 内 `LICENSE_pingforit` 1067 B = **MIT 全文**，与根目录 `LICENSE`、上游 `LICENSE` **逐字节相同** |
| class 差额 | 345 vs M6.5 349（main −2 / client −2）：**ClientFrameWatchdog 没有回退**（与会话最后写出版本 5624 B / sha256 完全一致）；其余差额只能部分归因（第 3.2 节） |

---

## 1. 文件数对账总表

| 源集 | 事故前 | 现在 | 差 | 组成（按 basename 与上游匹配） |
| --- | --- | --- | --- | --- |
| src/main/java | 165 | **162** | −3 | 159 个上游文件 + 3 个我方类（ProtocolLimits / WheelSelectionDiagnostics / ModLogFile） |
| src/client/java | 54 | **55** | +1 | 48 个上游定位 + 7 个我方类（IClientPlatformContextService、PlatformClientContextServiceImpl、ClientTeamContextHelper、MarkerHudRenderer、MarkerLabelRenderer、WheelHudRenderer、ClientFrameWatchdog） |
| deferred/ | 92 | **96** | +4 | 91 个 deferred 独有上游文件 + 5 个与 client 同名的副本（BlockDisplayPolicy / BlockDisplayWhitelist / SableClientProvider / WorldRenderContext / SettingsScreen）；其中含 4 个 Sable 实现（92 原样 + 4 Sable） |
| 合计 | 311 | 313 | +2 | 上游 298 个 basename 全部存在（唯一文件 298 + 5 个 client/deferred 双份 + 我方 10） |

> 校验：298（上游）+ 5（stub 副本）+ 10（我方新增）= 313，与当前 162+55+96 一致；上游 java 逐个按 basename 在树内可命中，**缺失 0**（脚本 `tools/.upstream-java.txt` 对 `.cur-main/client/deferred.txt` 比对）。

### 1.1 src/main −3 的定位

- 排除法：上游 298 个文件全部在树里（含被移到 client/deferred 的），因此 −3 不可能是「上游源码没引入」。
- 当前 main 的 3 个非上游类：`network/ProtocolLimits`（M2）、`interaction/state/WheelSelectionDiagnostics`（M6.5）、`log/ModLogFile`（恢复期日志隔离用）。
- 推断：事故前 main 应有 6 个非上游类（165 = 159 个上游 + 6）；现只剩 3 个 → **3 个我方可控 main 类未恢复**（该推断依赖「上游文件在 main 的 159 个定位未变」，未见反证）。
- 为什么无法给出文件名：
  1. `.git` 历史与旧 jar 均已丢失；磁盘（Downloads/Desktop/Documents/F:）未找到 `Ping-For-It*.jar`（find 实测 0 命中）。
  2. 16 个会话日志里 **没有**任何 src/main 路径出现在「事故前存在、现在不存在且上游没有」的过滤结果中（脚本已按 CamelCase/排除 devdiag 与模板路径过滤，仅剩 `domain/Marker.java` 一个早被 M3 重构淘汰的中间产物）。
  3. `docs/reviews/14` 的类名映射也确认「217 个 src java 全部可映射到上游或 M 阶段新增」，但同样未列出丢失者的名字。
  待确认（建议 2号 提供 47 个「无原文」回放清单；若清单里出现 main 类，即可直接补回）。

### 1.2 src/client +1 的逐项解释（可闭环）

**增加的 5 个恢复期 stub（每个在 jar 中恰有 1 个 class）**：

| 文件 | 行数 | jar 内 class | 用途 |
| --- | --- | --- | --- |
| `client/outline/BlockDisplayPolicy.java` | 57 | BlockDisplayPolicy | 供已编译代码引用的最小门面 |
| `client/outline/BlockDisplayWhitelist.java` | 33 | BlockDisplayWhitelist | 同上 |
| `client/integration/sable/client/SableClientProvider.java` | 71 | SableClientProvider | Sable 不可用回退（无 Sable API 依赖） |
| `client/render/WorldRenderContext.java` | 23 | WorldRenderContext | 渲染上下文占位 |
| `client/screen/SettingsScreen.java` | 42 | SettingsScreen | 设置界面桩（M8 恢复） |

**减少的 4 个 Sable 实现（迁入 deferred，见第 2.2 节）**：SableClientCompanionAccess / SableClientInternalAccess / SableClientProvider / SablePresentationLogGate。

> net：+5 − 4 = **+1**，与 54 → 55 完全一致。

### 1.3 deferred +4 的逐项解释（可闭环）

| # | 文件（deferred/client/integration/sable/client/） | 行数 | 类型声明数 | 来源 |
| --- | --- | --- | --- | --- |
| 1 | SableClientCompanionAccess.java | 621 | 4 | 上游同名文件，从 src/client 迁入 |
| 2 | SableClientInternalAccess.java | 139 | 3 | 同上 |
| 3 | SableClientProvider.java | 485 | 2 | 同上 |
| 4 | SablePresentationLogGate.java | 83 | 1 | 同上 |

- 迁移原因：当前 `build.gradle` **没有** `dev.ryanhcode.sable` 的 `compileOnly` 依赖（全文 grep 无 sable），保留在 client 会编译失败；迁入 deferred 后不参与编译。
- 上游 92 个 deferred 文件未减少（与 `docs/porting-progress.md` 的 M3b 清单一致），所以 92 + 4 = **96**。

---

## 2. jar 级核验

### 2.1 产物事实

| 项 | 值 | 证据 |
| --- | --- | --- |
| 路径 | `build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar` | 12:07 用户构建 |
| 大小 / entries / class | **649 419 B / 414 / 345** | `unzip -l` 统计 |
| manifest | Fabric-Loom 1.19.3、Fabric-Mapping-Namespace official、Fabric-Loader 0.20.1、MC 26.3、split-env true | `META-INF/MANIFEST.MF` |
| fabric.mod.json | id `pingforit`、version `0.4.2-pfi-beta1`、name `Ping For It`、license `MIT`、icon `icon.png`、environment `*`、entrypoints main/client、mixins 1 个对象 `{config, environment: client}` | `unzip -p ... fabric.mod.json` |
| SPI | `META-INF/services/` 5 个文件（IClientPlatformContextService / IPlatformClientEventService / IPlatformContextService / IPlatformNetworkService / IPlatformServerEventService），5 个实现类 class 均在 jar 内 | `unzip -l` + class 检索 |
| 资源 | assets 12 个（lang×8、sounds.json、ping.ogg、arrow.png、ping.png）+ jar 根 `icon.png`（29 966 B） | `unzip -l` |
| 许可证 | `LICENSE_pingforit` 1 067 B，MIT 全文 | 与根 `LICENSE`、上游 `LICENSE` `cmp` 相同 |

### 2.2 345 class 的构成（可复核）

- 按「顶层文件名」映射：**main 259 + client 82 + 4 个 secondary 顶层类**（声明在别的文件里）
  - secondary main 3 个：`AtomicConfigWriter`、`BackupWriter`（均在 `config/ConfigHandler.java`）+ `ExternalBlockTransaction`（`marker/MarkerCreationService.java`）
  - secondary client 1 个：`ClampWarningSink`（`config/ClientConfig.java`）
- 合计 259+3 = **262 main**、82+1 = **83 client**，与 `tools/compile-check` 的「main 262 / client 83」完全一致；另含 124 个嵌套类（`Global$1`、`ConfigHandler$SaveProtection` 等）。
- 我方 main 类在 jar 中的 class：`ProtocolLimits` 1 个、`WheelSelectionDiagnostics` 1 个、`ModLogFile` **2 个**（外部类 + `$BoundedAppender`）。

### 2.3 class 差额 349 → 345 的归因（部分待确认）

| 源集 | M6.5（文档口径） | 现在 | 差 | 已证实的成分 |
| --- | --- | --- | --- | --- |
| main | 264 | 262 | **−2** | 3 个我方 main 文件缺失（第 1.1 节）；`ModLogFile` 带 2 个 class，若为恢复期新增，则 264 = 262 − 2 + 4（3 个缺失文件共 4 个类型，其中 1 个文件含 2 个类型）——**数量上可闭环，但缺失文件名待确认** |
| client | 85 | 83 | **−2** | 5 个 stub（+5 个 class）与 4 个 Sable 实现（声明类型数 4+3+2+1=**10**）一增一减得到 +5/−10；无论 Sable 事故前是否被编译，都**无法仅用这两项凑出 −2** → 还存在其他 client 变化（例如某个内嵌类丢失/某个文件被合并），**待确认** |

**对 Lead 假设的核验**：`ClientFrameWatchdog` **没有回退**——当前文件 5 624 B / 180 行，sha256 与 2026-10-29T13:45 会话最后一次写出版本**完全相同**；它在 jar 中贡献 2 个 class（`ClientFrameWatchdog` + 1 个内部类），不是差额来源。

**结论**：345 不是构建事故，class 与编译计数自洽（262+83）；与 M6.5 的 4 个 class 差**主要来自 3 个未恢复的我方可控 main 类**（可解释 −2）与 client 侧一笔尚未闭合的重建差异（−2）。**不阻断本基线**，但 M7 前应让 2号 用回放清单把 3 个 main 文件补齐并复跑 `compile-check` 验证回到 349。

---

## 3. 纯净性 / 元数据 / 资源 / 构建脚本

### 3.1 纯净性

| 检查 | 结果 |
| --- | --- |
| `src/main` 下 `net.minecraft.client.` | **0 文件** |
| `src/main` 下 `com.mojang.blaze3d` | **0 文件** |
| deferred 进入编译路径 | **否**：`build.gradle` / `settings.gradle` / `gradle.properties` 中 `deferred` 引用数 = 0；`src/test` 不存在 |
| jar 内 `pingforit$` 之外的中文/临时资源 | 无（临时文件仅 `.compile-check` 与本文，均在 gitignore 范围） |

### 3.2 元数据一致性

| 项 | 值 | 一致性 |
| --- | --- | --- |
| `gradle.properties` version / group | `0.4.2-pfi-beta1` / `isharmla.mod` | ✓ |
| `build.gradle` mods 名 / archivesName / classifier | `pingforit` / `Ping-For-It` / `fabric-26.3` | ✓（产物名 `Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`） |
| 模板 entrypoints | main `nx.pingwheel.fabric.FabricMain`、client `nx.pingwheel.fabric.FabricClient` | 与 jar 内 `fabric.mod.json` 一致；对应 class 均在 jar |
| mixins | 1 个对象 `{config: pingforit.mixins.json, environment: client}`；配置 `package` = `nx.pingwheel.common.mixin`，类 = `KeyMappingMixin` / `MinecraftMixin` | 与 `src/client/resources/pingforit.mixins.json` 一致；两个 mixin class 均在 jar |
| SPI | 5 文件 ↔ 5 接口 ↔ 5 实现类 | 文件名 = 接口 FQN，内容 = 实现 FQN；5 个实现 class 均在 jar |

> 与事故前 M3 记录相比：当时有 **2 个** mixin 配置声明（main+client），当前只有 **1 个 client 配置**；这不是恢复错误（当前 main 侧无 mixin 类，5 个历史 mixin 在 deferred 中未编译），但属于**与旧基线的元数据差异**，M7 取回 mixin 时需同步加回配置。

### 3.3 资源齐全性

| 资源 | 期望 | 实测 |
| --- | --- | --- |
| lang | 8 种（de/en/es/fr/pl/tr/zh_cn/zh_tw） | 8 个，jar 内齐全（每个 10–12 KB） |
| sounds.json | 1 | 98 B，引用 `pingforit:ping` |
| 音效 | ping.ogg | 7 616 B |
| 贴图 | arrow.png（正常 64×64）/ ping.png | arrow 3 627 B；ping.png 70 B——**上游同款 1×1 透明占位**（09 报告已记录，不是恢复丢失） |
| 图标 | icon.png 真 PNG | 29 966 B，magic `89504e47` ✓；jar 根路径 `icon.png` 与 fabric.mod.json 的 `icon` 一致 |

### 3.4 构建脚本

| 项 | 值 | 状态 |
| --- | --- | --- |
| `gradle.properties` version | `0.4.2-pfi-beta1`（符合 `x.x.x-pfi-qualifier`） | ✓ |
| `gradle.properties` group | `isharmla.mod` | ✓ |
| `build.gradle` loom mods | `"pingforit" { sourceSet main; client }` | ✓ |
| `build.gradle` base.archivesName | `"Ping-For-It"` | ✓ |
| `build.gradle` classifier | `"fabric-26.3"`（no-remap 分支，无 remapJar） | ✓ |
| splitEnvironmentSourceSets | 启用；main 无 client import（第 3.1 节） | ✓ |

---

## 4. 附：与事故前记录的已知偏差（非恢复事故）

1. **LICENSE 已达标**：事故前 jar 内是 CC0 模板，现已换为 MIT 全文（用户 10-29 晚从上游恢复）；`THIRD-PARTY-NOTICES` 仍未打入 jar（M10 合规项）。
2. **运行期已知崩溃（AGENTS 第 7 节第 11 条）**：`InputConstants` 键码 `-1` 触发 `IndexOutOfBoundsException`（12:17/12:18 两次）——这是恢复后的真实缺陷，与本文基线对账无关，归属 M5/M8 修复。
3. **contact 字段为空**（`"contact": {}`）：不阻断加载，M10 元数据收尾。
4. **`docs/reviews/14` 已存在**（3号，106 行），本文与其结论一致；`AGENTS` 引用的 `15` 即本文。
5. **47 个「无原文」清单**：14 号报告指出仓库内无可核清单（最近的数字是 51 个非逐字节文件）；这也是 3 个 main 文件无法命名的直接原因。

---

## 5. 复跑命令（只读，可逐条执行）

```bash
cd F:/Minecraft_Mod_Project/Minecraft_26.3/Minecraft_26.3_Fabric
# 文件数
find src/main/java -name "*.java" | wc -l   # 162
find src/client/java -name "*.java" | wc -l # 55
find deferred -name "*.java" | wc -l        # 96
# 纯净性
grep -rl "net.minecraft.client\." src/main/java | wc -l  # 0
grep -rl "com.mojang.blaze3d" src/main/java | wc -l       # 0
# jar
unzip -l build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar | tail -1   # 414 files
unzip -l build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar | grep -c "\.class$" # 345
unzip -p build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar LICENSE_pingforit | head -2
# 上游覆盖率（298/298）与差异集合：见 tools/.upstream-java.txt、tools/.cur-*.txt
```

---

## 6. 待确认清单（交给 Lead）

| # | 事项 | 影响 | 建议 |
| --- | --- | --- | --- |
| Q1 | 3 个未恢复的我方可控 main 类名字 | class 264→262 的直接原因 | 2号 提供 47/51 文件回放清单；或用 `.dsh` 全量会话（不只在 `.tmp-restore` 的 16 个）重扫 write 事件 |
| Q2 | client 侧 −2 class 的其余成分 | 影响 M6.5 视觉回归基线 | 若有事故前 jar（用户机器未找到），直接 `jar tf` diff；否则按 14 号报告逐文件复核 |
| Q3 | 当前 jar 只含 1 个 mixin 配置（旧基线 2 个） | M7 取回 mixin 时需恢复 main 配置 | M7 施工时补 `pingforit.mixins.json` 与声明 |
| Q4 | `THIRD-PARTY-NOTICES` 未入 jar、`contact` 为空 | 发布合规（M10） | M10 统一收口 |
| Q5 | `ping.png` 70 B 占位 | 视觉（上游同款） | 不修，除非 M8 资源包逻辑需要 |

> 本文只做对账，不修任何文件；Q1/Q2 的修复属于 `src/**`（2号）与 `deferred/**`（M7）范围。
---

## 7. 2026-10-30 12:34 最终构建追加核验（取代 §2/§3 的 345 快照）

- 最新 jar：664,740 B / **420 entries / 351 class**（构建 12:34:52，晚于最新提交 12:33:25，不是旧 jar）。
- class 拆分：**main 262 + client 89**，与 `tools/compile-check.sh`（0 error）完全一致；major 69。
- 相对 §2 的 345 class，**恰好 +6**：`BlockDisplayWhitelistEntry`（外层 + 4 个内部类 Any/Exact/NamespaceWildcard/Tag）与 `BlockTagLookup`——即 2号 按反编译基线补回的类型（`a083122`）。没有删除任何 class。
- 元数据 / 资源 / LICENSE / SPI 与本文 §3 结论一致，无需修订。
- 反编译基线最终收敛（50 → 36 收敛、15 剩余、0 真实缺口）见 **docs/reviews/16-decompiled-diff.md**。

> 因此本文 §2/§3 中的 345 与「class 差 4」只代表 12:07 那一版；最终基线以 351 为准。

