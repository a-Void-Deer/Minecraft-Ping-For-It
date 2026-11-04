# 待完成清单（带说明）· 2026-11-05

> 用途：**给用户看的剩余工作总账**。机器可读的权威表仍是 `AGENTS.md` §9.1；本文件是它的「人话版 + 操作说明」，两者不一致时以 §9.1 为准并回来改本文件。
> 生成时分支 `26.3`（HEAD 以 `git log -1` 为准）。远端两个仓库的对应分支当时都停在 `ff950b2`，**本地领先 5 笔**（`0cb4a59` → `13051af` → `475172c` → `7157fda` → `cc7ff12` → 本文件那笔）。
> 离线校验现状：`compile-check` 262/4/126 · `test-check` **454/454/0** · `locale-check` **used=155 / dead=8, problems=none**。

---

## A. 现在就能做的三条命令（只需要你跑）

### A1. 出 jar 并确认许可文件真的进包

```bash
gradlew -g D:/Gradle clean build
unzip -l fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar | grep -iE 'LICENSE|THIRD-PARTY'
```

**期望**：看到**两个**文件 —— `LICENSE_pingforit`（本项目 MIT）与 `THIRD-PARTY-NOTICES.md`（237 行：上游 MIT 归属 + vendor 的 Maven `ComparableVersion` 的 Apache-2.0 全文）。

> 为什么必须你跑：沙箱里没有 Gradle，`compile-check` / `test-check` 都是直接抓 javac 拼 classpath，**证明不了 Gradle 的打包接线**。当前 `fabric/build/libs` 里还是**改动前的旧 jar**，所以现在 `unzip` 一定看不到 notices——这是预期的。
> 顺带会验证 `common/build.gradle` 里那段「关掉 `test` 任务」是否真的没影响 `build`（我判断没影响，但只能实证）。

### A2. 推到 GitHub，让 CI 第一次真正跑起来

```bash
# 模组主仓库（Natsume-Anan/Minecraft-Ping-For-It），远端已有同名分支 26.3
git push Ping-for-it 26.3

# 另一个人仓库（Natsume-Anan/QwQ），注意它的分支叫 Minecraft_26.3，不是 26.3
git push Natsume-Anan 26.3:Minecraft_26.3
```

> 推之前先确认：两处远端分支都停在 `ff950b2`，本地领先 5 笔；`git push` 是快进，不会覆盖别人的提交。**不要**用 `git push Natsume-Anan 26.3`（那条会在 QwQ 上新建一个叫 `26.3` 的分支，和现有的 `Minecraft_26.3` 分叉）。

- workflow 是 `on: push / pull_request`（`.github/workflows/build.yml`），**推上去就会跑**。
- CI 会自己下载三个可选集成的 jar（modmenu / voicechat / DH **API**），然后 `./gradlew build`，最后上传 `fabric/build/libs/Ping-For-It-*-fabric-26.3.jar`。
- 这是**发布前唯一的外部未知项**：本地能做的都做完了，但「Ubuntu + JDK 25 + 干净 checkout + 真 Gradle」这套组合从没跑过。
- 可能翻车的点（按概率排）：① CI 上 Gradle 首次解析依赖超时；② `gradle/actions/wrapper-validation` 对 wrapper jar 有意见；③ 某条 `curl` 的 CDN 链接失效（那三个 URL 是硬编码的）。

### A3. 看一眼 jar 里的 `fabric.mod.json` 署名

```bash
unzip -p fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar fabric.mod.json | grep -A4 'authors'
```

**期望**：`authors: ["Luken"]`（上游原作者）+ `contributors: ["Natsume Anan"]` + `contact` 三条指向 `https://github.com/Natsume-Anan/QwQ`。

---

## B. 需要你拍板的三件事（代码已就位，只差决策）

### B4 · 剩下 5 处 mixin 隐式 `require = 1` 要不要改

| | 现状 |
|---|---|
| 显式 `require = 1` | **0 处** |
| 已经是 `require = 0` + 诊断 | **15 处** |
| 仍是**默认值（=1）** | **5 处**：`KeyMappingMixin`（`@Inject` ×3 + `@Redirect` ×1）+ `MinecraftMixin`（`setScreenAndShow`） |

两种失败模式，选一个：

- **保留 `require=1`**：未来 MC 改个签名就**启动崩溃**。好处是「一眼可见、绝不静默」；坏处是玩家直接进不去游戏。
- **改 `require=0`**：不会崩，但会**静默降级**（快捷键失灵、界面拦截失效），所以**必须同时加一次性诊断日志**才可接受。

### B5 · 装了 Distant Horizons 的玩家永远走不到 `outline` 相位（`24` #19）

DH 每次进世界都强制关 improved transparency → 我们的描边永远走经典 `afterTerrain` 通道。两条路线：

1. **加诊断开关**强制走 `outline` 相位 —— 可测了，但这属于**非上游行为**，必须在 `docs/reviews/24-fidelity-constraint-audit.md` 登记为保真偏差。
2. **在 `24` 里登记为已知行为** —— 零代码改动，写清「装 DH 时该分支不可达，描边仍然可见、仍穿墙」。

> 顺带：V13–V15 现在状态是 ⏸ 受限未验证，就是卡在这条上（无 DH 的实测环境里也没触发过）。

### B9 · 你自己写的 `改进方案.md` 还剩 3 条没有结论（**发布门禁**）

| # | 条目 | 状态 |
|---|---|---|
| B9-1 | 描边线宽随距离补偿 | ✅ 已结案（2026-10-31 你决定：保持与上游一致，不做） |
| B9-2 | **整体方块标记判定**：大箱子 / 床 / 木门只标**单格**而非整体 | ⬜ 要你给结论：实现 / 不做（写原因）/ 转下一版 |
| B9-3 | **标记点远离后的「标记伪形」** | ⬜ 同上 |
| B9-4 | **基准线** | ⏸ 你原文只写「待补充」，内容还没定义，无法评估 |

> 规则写进 §9.1 了：**任何一条没有结论都不得发布**。

---

## C. 需要实机 / 双端的验收（我做不了，环境限制）

| # | 项 | 怎么验 | 备注 |
|---|---|---|---|
| C1 | **M5 服务端权威（双客户端）** | 专用服务器 + 两个客户端进同一世界：受众快照 / 赢家选择 / 移除仅本人 / 限流 / 过期 / 断线重连 | 步骤与判据已写好在 `docs/reviews/65-m5-server-authority-code-analysis.md` §6；**代码层已确认 5/6 条由结构保证**，只差实机 |
| C2 | **V19 发布 jar 干净安装** | 干净 Fabric 26.3 实例只放本 jar + fabric-api，启动查图标 / 中文 / ping | ⏸ 可选，但**不做就要在发布说明里注明** |
| C3 | **V6 / V7 / V11 肉眼确认** | Mods 列表图标、设置界面中文、ModMenu 配置按钮 | 🟡 日志级已过，差截图留证 |
| C4 | **M8b 自定义资源包** | 替换 `assets/pingforit/textures/ping.png` 看是否生效 | ⏸ 等你的资源包 |
| C5 | **性能回归** | 繁忙世界（大量 BER/实体）对比空闲/繁忙帧时 | ⬜ 没做过 |
| C6 | **OIT 复测** | 半透明物体叠加顺序 | ⏸ 可选 |

---

## D. 被环境卡住的（不是没做，是做不了）

| # | 项 | 卡在哪 | 解除条件 |
|---|---|---|---|
| D1 | **`gradlew test`（真正的 Gradle 测试任务）** | 本机**无网络**，Gradle 缓存里**一个 JUnit jar 都没有**（只有 `junit-bom` 元数据）。引 JUnit 依赖会让 `gradlew clean build` 在依赖解析阶段挂 | 联网，或把 `junit-jupiter` 的本地 jar 放进离线目录；然后删掉 `common/build.gradle` 末尾那段「关掉 `test` 任务」再正式接线 |
| D2 | **未搬的上游测试（130 文件 / 908 用例）** | 四桶：A 组直接 `import net.minecraft`（要真 MC 运行时）；B 组被测类在 `fabric/src/client`；C 组被测类在本移植里不存在（deferred / 已改名）；D 组传递依赖 MC | 要接真 MC 测试运行时（Gradle + JUnit + MC），属 M10 之后 |
| D3 | **`deferred/` 95 个 java 的去留** | 等你决定 | 保留（已加 `deferred/README.md`：历史参考、不能编译、M7 是重写而非取回）或删除 |
| D4 | **`render/spike/OutlineExperiment` 清理** | 你已确认**保留** | — |

---

## E. 发布门禁（M10 收尾前必须全过）

- [ ] 分组 A / B / C 全部 ✅（V19 若不做，**发布说明里必须注明**）
- [ ] 重新通读 `改进方案.md`，**逐条**给结论（= B9-1…B9-4）
- [ ] **CI 在真实 GitHub Actions 上绿过一次**，并核对上传 jar 的名字/大小与本地一致（= A2）
- [ ] `AGENTS.md` §10 自检清单全过（含 `bash tools/test-check.sh` 454/454、`bash tools/locale-check.sh` used=155/dead=8）
- [ ] `docs/PORTING-PROGRESS.md` 的剩余工作表与 §9.1 一致

---

## F. 我下一轮能直接做的（不需要你，按建议顺序）

1. **B4 两种方案各写一份 patch 草案 + 代价对照**（你只要挑一个，不用读代码）。
2. **B5 的诊断开关实现**（如果选路线①），含 `24` 的偏差登记。
3. **locale 的 en_us 死键清理**：那 8 个 dead key（6 个 `server_settings.*` + 2 个 `open_block_shape_blacklist_config`）还白占 8 个语言文件的位置——删或留要你一句话（删了要动 8 个文件，属「改变资源内容」，我不擅自做）。
4. **C 桶里 26 个 MC-free 但目标类在 deferred 的描边测试**：这些「上游有测试、我们的重写没测试」的地方可以**新写**（不是移植），是当前最大的测试空洞。
5. `docs/reviews/65` 的 G1 追加注（「部分关闭：A 组 443 用例已跑通」）。

---

## G. 工具速查（全部离线、不依赖 Gradle）

| 命令 | 作用 | 当前结果 |
|---|---|---|
| `bash tools/compile-check.sh` | 三遍 javac（common → fabric main → fabric client）+ 服务端安全 gate | 262 / 4 / 126，0 error |
| `bash tools/test-check.sh` | 编 `common/src/main` + `common/src/test`，跑 `TestRunner`（**离线替代 `gradlew test`**） | **454 / 454 / 0**，exit 0 |
| `bash tools/locale-check.sh` | 8 语言键一致性 + used/dead 扫描（`--strict` 有问题 exit 1；`--root` 可指向临时副本自检） | **used=155 / dead=8**，problems=none |
| `bash tools/locale-check.sh --strict` | 同上的严格模式（CI 可用） | exit 0 |

> 三个脚本都**不是** Gradle：只能证明「源码能编、单测能过、语言键自洽」；**不能**证明 Gradle 打包、mixin 织入、或游戏里跑得对——那些要么靠你终端 `gradlew`，要么靠实机。

---

## H. 本轮已完成的（避免重复劳动）

- **M5 代码层分析**：`docs/reviews/65-m5-server-authority-code-analysis.md`（376 行）—— 5/6 条契约由结构保证，只差双客户端实机。
- **M10 首批 4 项**：许可（MIT + notices 进 jar 接线）/ locale 补 70 行 / `GameContext.lastWorld` 断线清理 / glow 诊断补强 —— 复审 `66`「可合入、无阻塞」。
- **清理包**：`CommonClient` 10 个 M7 残留空方法删除（79 行）、`fabric.mod.json` 署名、`deferred/README.md` —— 复审 `67`「可合入、无代码阻塞」。
- **B6 测试基座**：零依赖基座 + **454 用例全绿**（443 上游 + 11 自写），A 组 58/58 全覆盖 —— 复审 `68`「可合入、无代码阻塞」。
- **B7 locale 工具**：`used=155 / dead=8` 与团队旧口径一致，`--strict` 做过负向验证（删键 / 加重复键都能抓）。
- **文档**：许可事实订正为 MIT（上游也是 MIT，Apache 只属于 vendored 的 `ComparableVersion`）、M5/M10 状态、`deferred` 口径、`AGENTS.md` §9.1 补 B4/B5/B6/B7/B9。
