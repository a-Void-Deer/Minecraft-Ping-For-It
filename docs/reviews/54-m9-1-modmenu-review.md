# 54 · M9-1 Mod Menu 集成复审

任务：M9-1（Mod Menu 集成）独立审查　审核人：3号　日期：2026-11-02
对象：`src/client/java/nx/pingwheel/fabric/integration/ModMenuIntegration.java`（新增）、`deferred/modmenu/ModMenuIntegration.java`（删除）、`src/main/templates/fabric.mod.json`、`build.gradle`、`tools/compile-check.sh`、`.github/workflows/build.yml`。
工作树：`git status --porcelain` 见 A/G；`改进方案.md` 为用户改动，未计入。

## 结论

**打回（唯一阻断：B1）。** 主功能（类、entrypoint、离线 compileOnly、jar 不可见性、服务端延迟加载）正确；但本地检查工具只改了 `.sh`，`tools/compile-check.ps1` 仍是旧 classpath，实际运行失败（5 error）。另有一处非阻断的文案/前置说明问题 B2：`run/mods` 缺失时不是“只丢集成”，而是 `ModMenuIntegration` 无条件在 client 源集编译失败；AGENTS #15 已把本地 jar 定义为前置，但 build.gradle/CI 注释仍写成可选降级，应改文案或 fail-fast。

---

## 一、阻断项（B1）与非阻断前置说明（B2）

### B1 · `tools/compile-check.ps1` 未同步 run/mods（阻断，确定性失败）

**位置**：`tools/compile-check.ps1:59-68`（classpath 只有 MC + modules-2）。

**现象**：在仓库根实际执行：

```text
pwsh -NoProfile -File tools/compile-check.ps1
...
[client] sources=80
[client] exit=1
[client] errors=5
ModMenuIntegration.java:3: error: package com.terraformersmc.modmenu.api does not exist
ModMenuIntegration.java:4: error: package com.terraformersmc.modmenu.api does not exist
ModMenuIntegration.java:7: error: cannot find symbol
ModMenuIntegration.java:10: error: cannot find symbol
ModMenuIntegration.java:9: error: method does not override or implement a method from a supertype
[summary] main errors=0 classes=266 | client errors=5 classes=0
```

**为什么是问题**：AGENTS 把 `tools/compile-check.sh|.ps1` 定义为同一套本地校验；Windows 用户/备用检查用 `.ps1` 会误报新集成类，Lead 只跑了 `.sh` 才得到 126/0。

**建议改法**：把 `.sh` 的 run/mods 片段镜像到 `.ps1`（在 `$CP_BASE` 构造前）：

```powershell
$modJars = @(Get-ChildItem (Join-Path $T 'run/mods') -Filter *.jar -File -ErrorAction SilentlyContinue |
    Sort-Object Name | ForEach-Object { $_.FullName -replace '\\','/' })
$CP_JARS += $modJars
if ($modJars.Count -gt 0) { Write-Output ('compile-check: integration jars from run/mods = ' + $modJars.Count) }
```

并排除 `*-sources.jar`/`*-javadoc.jar`（见 D）。

### B2 · run/mods 缺失时不是“只丢集成”，而是构建失败（非阻断；AGENTS #15 已定义为前置）

**位置**：`build.gradle:35-49` 注释 + `ModMenuIntegration.java` 无条件在 `src/client`。

**现象/推理**：`run/` 被 `.gitignore` 忽略；`integrationJars` 是空 fileTree 时，`compileOnly` 为空，但 `ModMenuIntegration` 仍被 client 源集编译，`com.terraformersmc.modmenu.api` 不存在 → `compileJava` 失败（`compile-check.ps1` 的 5 error 就是这种 classpath 缺失的实证）。

**为什么是非阻断**：AGENTS #15 已明确 run/mods 是编译前置，因此缺 jar 失败本身是预期；真正的问题是 build.gradle/CI 注释仍说“Absent jars only drop ...”，与事实不符，且失败信息是 package does not exist，不是清晰的“put jar here”。

**建议改法**（Lead 二选一）：
1. 保持 jar 为强制构建前置：改掉注释，并在 `dependencies` 前对 `run/mods/modmenu-*.jar` 做显式存在性检查，抛清晰错误（“M9-1 requires run/mods/modmenu-*.jar”）；
2. 真要做成可选：把 integration 源集按 jar 存在性条件加入编译（或拆独立 sourceSet），缺 jar 时不编译该类；这比单改注释大，建议在 M9-2 之前定。

---

## 二、必查项 A–H

### A. 保真 / entrypoint（通过）

- `ModMenuIntegration.java` 与上游：

```text
upstream SHA256 = 552C004F3A22FC6BA59985F5AFE2F6C1579C4DF0F7EF2E5B7890EFD8BB0935D0
local    SHA256 = 552C004F3A22FC6BA59985F5AFE2F6C1579C4DF0F7EF2E5B7890EFD8BB0935D0
BYTE-IDENTICAL
```

- upstream `fabric.mod.json` 有 `main` / `client` / `modmenu` / `voicechat`；本地有 `main` / `client` / `modmenu`，**键名 `modmenu` 一致**，`voicechat` 属 M9-2 不在范围。
- 本地模板无 `recommends`/`suggests`，与 upstream 一致；`environment: "*"` 与 upstream 一致；JSON 可解析（`ConvertFrom-Json` 成功，entrypoints=main,client,modmenu，modmenu=nx.pingwheel.fabric.integration.ModMenuIntegration）。

### B. compileOnly fileTree 副作用（部分通过；缺 jar 见 B2）

- `compileOnly` 不会进入 runtime classpath；`jar` 默认不打包依赖；本项目 no-remap 无 `remapJar`，不会把 jar 重映射进产物。
- `build.gradle` 有 `maven-publish`（不是没有）；`compileOnly` 不属于 `components.java` 的 runtime/api，不会作为发布依赖进入 POM。
- `def integrationJars = fileTree(...)` 放在 `dependencies {}` 内是合法 Groovy；`compileOnly integrationJars` 是 Gradle 支持的 FileCollection 依赖。**配置阶段**在 `run/mods` 不存在时不报错（fileTree 为空），但编译阶段会因 B2 失败；`logger.lifecycle` 会打印空列表。
- **configuration cache 待确认**：`logger.lifecycle` 在配置期调用 `integrationJars.files*.name` 会提前解析 fileTree；这通常是允许的，但会引入文件系统配置期读取。本环境不能跑 Gradle，建议 Lead 在真实 `--configuration-cache` 下补一次验证（非阻断）。

### C. fabric.mod.json / 服务端安全（通过，带边界）

- Fabric Loader 0.20.1 源码：`EntrypointStorage.add` 只把 `NewEntry` 放入 `entryMap`（`EntrypointStorage.java:163-171`），不 `Class.forName`；`EntrypointContainerImpl.getEntrypoint` 才懒加载（`EntrypointContainerImpl.java:50-55`）。
- 服务端不会请求 `modmenu` key，所以不会加载 `ModMenuIntegration`，也不会因缺 ModMenu 或 client-only 类而 CNFE；缺 ModMenu 客户端同理。
- 边界：如果将来有代码在 server 端枚举 `modmenu` entrypoint，仍会 CNFE；当前没有这种调用。`environment: "*"` 是 upstream 原样，不建议为本次改。

### D. tools/compile-check.sh（部分通过）

- `.sh` 在 `run/mods` 存在时确实工作：`bash tools/compile-check.sh` → `main 266 / client 126，0 error`；`find tools/.compile-check/client -name '*ModMenuIntegration*.class'` 命中 `tools/.compile-check/client/nx/pingwheel/fabric/integration/ModMenuIntegration.class`，client 总 class=126。
- 路径用 `CP_JARS+=("$TW/$jar")`，`CP_BASE` 用 `;` 拼接，当前无空格路径工作正常；`for jar in run/mods/*.jar` 在无匹配时保留字面量，`[ -f ]` 会跳过，不会因缺目录报错。
- **计数不可靠**：`MOD_JAR_COUNT` 统计 `run/mods/*.jar` 全部文件，不按 build.gradle 的 include 模式过滤；本地 `voicechatprofiles-fabric...jar` 也会被计入，未来 `*-sources.jar` 也会被计入并放进 javac classpath（当前无 sources jar）。日志“integration jars from run/mods = N”只能说明目录里有 N 个 jar，不能证明 ModMenu API 存在。
- `.ps1` 缺失见 B1。

### E. CI 一致性（部分通过）

- CI 在 build 前 `mkdir -p run/mods` 并 curl 3 个 jar：modmenu、voicechat-api、DistantHorizonsApi；`run/` 被 gitignore 不影响 `./gradlew build`（CI 自己创建）。
- **CI 必须联网**：curl 失败即步骤失败，`./gradlew build` 也随之失败；这是可接受的取舍，但应在 CI/README 明确写“build 需要网络或预置 run/mods”。
- **本地与 CI 的 DH 不同**：本地是 `DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`（完整 jar），CI 是 `DistantHorizonsApi-7.2.0.jar`（API jar）。M9-1 只用到 ModMenu，暂不影响；M9-3/M9-4 做 DH 时必须确认 API 版本/类覆盖一致，否则会出现“本地过、CI 红”或反向。
- curl 无 checksum/签名校验（供应链风险，非阻断）；URL 固定版本是优点。

### F. 残留（非阻断，需 1号/Lead 同步文档）

- 实际 `deferred/` = **95** java（`Get-ChildItem deferred -Recurse -Filter *.java` = 95；`git ls-files deferred` = 95）；`AGENTS.md` 已在 2026-11-02 更新为 95，但：
  - `docs/PORTING-PROGRESS.md:175` 仍写 `deferred/modmenu/` 1 个；`:757` 仍写 `deferred/ 96`；
  - `docs/reviews/05-m3a-review.md:63`、`14-restore-verification.md:105`、`19-feature-gap.md:3/36` 仍引用旧 `deferred/modmenu`/96；这些是历史审查，可保留但应加“已被 M9-1 取代”的注记；
  - `docs/reviews/53-m9-integration-feasibility.md` 仍写“加 repo `maven.terraformersmc.com` + `com.terraformersmc:modmenu:21.0.0`”，与当前本地 jar 方案不一致（历史计划，建议更新或标注 superseded）。
- `git grep 'com.terraformersmc:modmenu'` 仅命中 `docs/reviews/53`，build.gradle 已无该 maven 坐标；`git grep 'deferred/modmenu'` 仅文档，无源码引用。

### G. 是否顺手改坏别的东西（通过）

`git diff --name-only` / `git status`：`build.gradle`、`.github/workflows/build.yml`、`src/main/templates/fabric.mod.json`、`tools/compile-check.sh`、staged `deferred/modmenu/ModMenuIntegration.java`、新增 `src/client/.../ModMenuIntegration.java`；另有 `AGENTS.md`（1号文档更新）与用户自己的 `改进方案.md`。**没有 M7/M8 源码改动**；删除文件是纯移出。

### H. 266/126 的 +1（通过）

- `bash tools/compile-check.sh`：`main sources=166 errors=0 classes=266`；`client sources=80 errors=0 classes=126`。
- `find tools/.compile-check/client -name '*ModMenuIntegration*.class'` → `tools/.compile-check/client/nx/pingwheel/fabric/integration/ModMenuIntegration.class`；client 总 class=126。+1 确实是新集成类，不是意外。
- `.ps1` 同一源集为 5 error（B1），不影响 `.sh` 的结论，但说明工具不一致。

---

## 三、非阻断 / 待确认

1. **compile-check 计数语义**（D）：`MOD_JAR_COUNT` 统计所有 jar，不按 include 过滤；建议打印匹配列表而不是“数量”，或按 build.gradle 的 5 个 glob 逐个匹配。
2. **CI 网络/供应链**（E）：需要联网、无 checksum；可接受但应写入文档；DH API/full 版本差异在 M9-4 前必须对齐。
3. **configuration cache**（B）：`logger.lifecycle` 配置期解析 fileTree 是否在 Gradle 9.8.2 configuration cache 下稳定，建议 Lead 实跑一次 `--configuration-cache`；当前没有证据表明会失败。
4. **run/mods 强制前置**（B2）：若选择“jar 必须存在”，建议 fail-fast 报错；若选择“真可选”，需条件源集。两种都比当前“注释说可选、实际编译失败”好。
5. **服务端边界**（C）：当前 lazy 安全；未来若新增服务端枚举 `modmenu` 的代码需重新评估。

---

## 四、证据索引

- 上游/本地类 SHA：`Get-FileHash` → `552C004F...0935D0` 两边相同。
- 上游 metadata：`Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1/fabric/src/main/resources/fabric.mod.json`（`modmenu` entrypoint、无 recommends/suggests、environment `*`）。
- Fabric Loader lazy：`fabric-loader-0.20.1-sources.jar` → `net/fabricmc/loader/impl/entrypoint/EntrypointStorage.java:163-171`、`EntrypointContainerImpl.java:50-55`。
- `.sh` 输出：`main 266 / client 126，0 error`；class 路径如上。
- `.ps1` 输出：`[client] errors=5 classes=0`，5 条 ModMenu package/symbol/override 错误。
- `git grep` 残留：`AGENTS.md`（已 95）、`docs/PORTING-PROGRESS.md:175/757`、`docs/reviews/05/14/19/53`。
- `run/mods` 当前：`modmenu-21.0.0.jar`、`DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`、`voicechatprofiles-fabric-26.3-1.0.1.jar`。

---

## 五、建议的下一步

1. 修 B1（`.ps1` 镜像 `.sh` 的 run/mods classpath）。
2. 决定 B2 的策略（强制前置 + fail-fast，或条件源集真可选），并改注释/CI 文案。
3. 1号更新 `docs/PORTING-PROGRESS.md` 的 `deferred` 计数/`deferred/modmenu` 条目，并给历史 reviews 加 superseded 注记。
4. 修完后 Lead 再跑：`bash tools/compile-check.sh` 与 `pwsh -File tools/compile-check.ps1` 都应 `266/126，0 error`；有 Gradle 环境再跑 `./gradlew --configuration-cache build` 验证。

---

## 五、第 2 轮复审（2026-11-02）

范围：`tools/compile-check.ps1`、`tools/compile-check.sh`、`build.gradle`、`.github/workflows/build.yml`；1号 的 `AGENTS.md` / `PORTING-PROGRESS` / `24` / `53` 更新；`docs/reviews/.tmp55/` 已删除。

### 结论

**打回（唯一阻断：B3）。** B1 已闭环（两个脚本都实际通过），B2 已闭环（缺 modmenu jar 现在配置期 GradleException + URL，注释已改）；但 fail-fast 放在 `dependencies {}` 使 `./gradlew tasks` / `clean` / IDE sync 在没有 jar 时也直接失败，属于新引入的过宽检查。建议把存在性检查挪到 `compileClientJava`。

### A. B1 是否真闭环（通过；两脚本语义基本等价）

同一次工作树实跑：

```text
bash tools/compile-check.sh
compile-check: integration jars from run/mods: DistantHorizons-3.3.4-26.3-fabric-neoforge.jar, modmenu-21.0.0.jar
main errors=0 classes=266 | client errors=0 classes=126  (exit 0)

pwsh -NoProfile -File tools/compile-check.ps1
compile-check: integration jars from run/mods: DistantHorizons-3.3.4-26.3-fabric-neoforge.jar, modmenu-21.0.0.jar
main errors=0 classes=266 | client errors=0 classes=126  (exit 0)
```

- **glob 集合相同**：两边都是 `modmenu-*.jar`、`voicechat-api-*.jar`、`voicechat-*.jar`、`DistantHorizons-*.jar`、`DistantHorizonsApi-*.jar`；`voicechatprofiles-fabric...jar` 不被命中，符合预期。
- **排除规则相同**：两边都排除 `*-sources.jar`/`*-javadoc.jar`。
- **空命中行为相同**：都打印 `no integration jars matched in run/mods` 并继续（随后编译失败），不中断脚本；`.sh` 写 stderr、`.ps1` 写 stdout，属提示流差异，不是功能差异。
- **路径拼接**：`.sh` 的 `$TW/$jar`（`TW` 无尾斜杠）与 `.ps1` 的 `Join-Path $T "run/mods"` + 全路径 `/` 转换等价；`$T` 无尾斜杠，`Join-Path` 不会产生双斜杠。
- **两个细微差异（非阻断）**：PowerShell 的 `-like` 默认**大小写不敏感**，shell `case` glob 大小写敏感；当前 jar 名全小写/固定大小写，实际无差异。若以后有 `MODMENU-...JAR`，`.ps1` 会命中而 `.sh` 不会。

### B. B2 是否真闭环（通过；fail-fast 文案正确）

- `build.gradle:52` 先打印 `compileOnly integration jars: [...]`，`:55-59` 再对 `fileTree("run/mods", include: ["modmenu-*.jar"]).files.isEmpty()` 抛 `GradleException`，消息含 `Missing compile-only jar ... put modmenu-21.0.0.jar into run/mods/` 和官方 URL；`run/` 被 gitignore 时不会走到 `package ... does not exist`。**B2 目标达成。**
- 注释已明确“REQUIRED build input / fresh clone must supply / add glob AND own check”，与行为一致。
- **配置缓存判断**：`fileTree(...).files.isEmpty()` 和 `logger.lifecycle(...files*.name)` 在**配置期**读取文件系统；Gradle CC 允许配置期文件访问，且 `throw` 发生在配置期，失败不会写 CC。**正常 CI/首次构建可接受**。唯一风险是“已有成功 CC 后手工删掉 jar”：CC 可能复用旧配置，不再执行这条检查；这不是当前 CI/正常构建路径（新 checkout 无 CC，缺 jar 首次就抛）。若要彻底稳，可把检查做成任务级输入，或在 jar 变动后 `--no-configuration-cache`；**非阻断**。

### C. 新增检查是否误伤（阻断 B3）

**是，当前放在 `dependencies {}` 过宽。** Gradle 的 `dependencies` 块在**配置阶段**执行，任何任务（包括 `./gradlew tasks`、`clean`、IDE Gradle sync）都会先配置项目；没有 `modmenu-*.jar` 时直接抛 GradleException，用户无法查看任务列表/清理/导入 IDE，尽管这些操作不需要编译集成类。

**建议改法**：把检查移到 `compileClientJava` 的 `doFirst`，保留配置期 logger：

```groovy
def modMenuJarMissing = fileTree(dir: "run/mods", include: ["modmenu-*.jar"]).files.isEmpty()
tasks.named("compileClientJava") {
    doFirst {
        if (modMenuJarMissing) {
            throw new GradleException("Missing compile-only jar for the Mod Menu integration: put " +
                "modmenu-21.0.0.jar into run/mods/ " +
                "(https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/21.0.0/modmenu-21.0.0.jar)")
        }
    }
}
```

这样 `tasks`/`clean`/IDE sync 不受影响，`build`/`compileClientJava` 仍 fail-fast 且消息一致。若 26.3 的 source set 任务名不是 `compileClientJava`，用 `tasks.named(sourceSets.client.compileJavaTaskName)`。

### D. `.sh` / `.ps1` 空数组与 set -u（通过）

- `.sh` 在 `set -u` 下先声明 `INTEGRATION_JARS=()`、`INTEGRATION_NAMES=()`；`${#INTEGRATION_JARS[@]}` 和仅在非空分支里的 `"${INTEGRATION_NAMES[@]}"` 都是安全的（Bash 4.4+/Git Bash 5.x）。当前实际运行命中两项，未走空分支；空分支静态检查也安全。
- `.ps1` 的 `$integrationJars = @()` 后 `+=`/`.Count` 不会出现 `$null` 空展开问题。
- 两边现在都打印**命中名单**，不再是容易误判的 `MOD_JAR_COUNT`；`run/mods` 里无关的 `voicechatprofiles` 不会进入 classpath。

### E. 文档与代码行为（通过；AGENTS #15 已更新）

- `AGENTS.md` §7 #15 已改成：`compile-check.sh|.ps1` 同一套 glob/排除、打印 names、0 命中提示；并明确 **Mod Menu 缺失时 Gradle 配置期 GradleException**，其它集成缺 jar 才是 package 错误。检查后文本与代码一致。
- `PORTING-PROGRESS` M9-1 已写明 `deferred` 95、ModMenu 迁回 `src/client`；`24` §8 已登记“maven → 本地 jar 不新增偏离编号”；`53` 顶部已加“M9-1 已落地，本文正文是 2026-11-01 快照”的 note。**无新矛盾。**
- 旧历史报告（05/14/19）仍写 96/deferred/modmenu，但它们是历史快照，1号 已在 PORTING-PROGRESS 加 2026-11-02 注；不需要为本次阻塞。

### F. 新问题 / 全量 diff（通过，除 B3）

- `git diff --name-only`：`.github/workflows/build.yml`、`AGENTS.md`、`build.gradle`、`docs/PORTING-PROGRESS.md`、`docs/features.md`、`docs/reviews/24`、`docs/reviews/53`、`src/main/templates/fabric.mod.json`、`tools/compile-check.ps1`、`tools/compile-check.sh`、staged `deferred/modmenu/ModMenuIntegration.java`；新增 `src/client/.../ModMenuIntegration.java`。**无 M7/M8 源码改动。**
- `.github/workflows/build.yml` 注释已改为“fresh checkout 无 jar、这一步供给、build 需要网络或预置 run/mods”，与事实一致；CI 在 build 前 curl，有 jar 时不会触发 GradleException。
- 未发现新的源码/entrypoint 回归；`tools/.tmp55/` 已不存在。

### 第 2 轮最终建议

1. 只改 C（B3）：把 `dependencies` 里的 throw 挪到 `compileClientJava` 的 `doFirst`；保留 logger 与注释。
2. B1/B2 已闭环；修 B3 后建议复跑：无 jar 时 `./gradlew tasks` 应成功、`./gradlew build` 应 GradleException + URL；有 jar 时 `.sh/.ps1` 均 266/126。
3. CC 的 jar 变更场景建议写入运维注记（或对 jar 变更使用 `--no-configuration-cache`），非阻断。

### 第 2 轮新增非阻断 B4（build.gradle 与脚本的 jar 过滤不完全一致）

- `tools/compile-check.sh|.ps1` 已排除 `*-sources.jar`/`*-javadoc.jar`，但 `build.gradle` 的 `integrationJars` 仍使用纯 `include`（`build.gradle:44-50`），没有 `exclude`；因此 `modmenu-21.0.0-sources.jar` 也会进入 `compileOnly`，且 fail-fast 的 `fileTree(...include: ["modmenu-*.jar"]).files.isEmpty()`（`:55`）可被“只有 sources jar”误判为已满足。当前 `run/mods` 没有 sources jar，故不影响本次 266/126；建议在 `fileTree` 加 `exclude: ["*-sources.jar", "*-javadoc.jar"]`，并让 fail-fast 检查同一个过滤后的 fileTree/真实 class jar。
- 这也保证 `build.gradle`、`.sh`、`.ps1` 三处“同一套 glob”真正语义一致。

### 第 2 轮新增非阻断 B5（CC 与 jar 变更）

- `gradle.properties` 有 `org.gradle.configuration-cache=true`；配置期 `fileTree.files` 读取在正常构建可用，但 Gradle CC 不把该文件系统读取当成配置失效输入。首次缺 jar 会在配置期失败（不写 CC），有 jar 后正常；**已经成功缓存后手工删除/替换 jar** 可能复用旧配置，需 `--no-configuration-cache` 或清 CC。建议把这一点写进 M9 运维注记，非阻断。

### 补充（第 2 轮期间）：CI 的 DH 下载改为完整 jar（不影响 B3 判定）

当前工作树事实：`.github/workflows/build.yml:43-44` 下载 `DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`（SHA256 `BA3E5C85...D3947`，本地实测），不再下载 `DistantHorizonsApi-7.2.0.jar`；`build.gradle:48-49` 仍同时接受 `DistantHorizons-*.jar` 与 `DistantHorizonsApi-*.jar`。本地完整 jar 的 `com/seibel/distanthorizons/api/` 有 **181 个 entry、其中 150 个 `.class`**（含 `DhApi.class`）；用户描述的“181 个 API 类”更准确地说是 181 个 API 路径 entry（150 个 class 文件）。

**① 是否消除 E 项漂移：只消除了一部分。**
- 消除的是：CI 与**当前本地 dev** 都编译同一个 full jar，不再出现“本地 full / CI API”这一条主漂移。
- 仍存在的漂移：(a) `build.gradle` 继续接受 API-only jar，任何只放 API 的本地环境仍与 CI 的 full jar 不同；(b) 若同时放 full 与 API，两个 classpath 会重叠，顺序/版本不确定；(c) 没有 SHA256/API 版本断言，CDN URL 的 full jar 与本地文件无法证明是同一件（本地 SHA 已知，CI 未校验）；(d) full jar 含实现类，可能让代码误 import 非 API 类，而 API-only/DH 未安装环境运行期没有这些类。
- 因此“同一件产物”是编译面收敛的正确方向，但在 `build.gradle` 仍允许 API-only 且无 hash 校验的情况下，不能说**完全**消除漂移。

**② 28 MB 是否值得：为当前 parity（一致性）可以接受，但编译期最优解不是 full jar。**
- 28 MB 相对 Gradle/Minecraft 依赖下载不算大，CI 下载 full jar 能保证与 dev runtime 完全相同，**值得**用于短期对齐。
- 但 DH full jar 是**运行时 mod**；若 M9-3 只需要 API，使用**官方 API jar 做 compileOnly** 更小、更快，并且能强制只用 API 面，避免误 import 实现类。full jar 仍应留在 `run/mods` 供 `runClient` 实际加载；两者需要**分目录**（例如 `compile-libs/` 放 API，`run/mods/` 放 full），不要让 `run/mods` 同时承担两个角色。

**③ 更稳妥的做法：优先“API jar 统一编译 + full jar 只做运行时”，而不是两者都下载再断言。**
- 首选：CI 下载 API jar 到独立 `compile-libs/`，`build.gradle`/`compile-check` 只把 API jar 放编译 classpath；`run/mods` 的 full jar 只在 dev runtime 使用。这样 API 面唯一、可校验版本。
- 次选：继续 full jar both；给 CI 加 SHA256 校验（或断言 `DhApi` 存在/API 版本常量），并删掉 `build.gradle` 的 `DistantHorizonsApi-*.jar` include 或明确把它隔离到“轻量环境”配置，避免 full/API 双 classpath。
- “两者都下载 + 断言类集合一致”属于 overkill：28 MB full + 额外 API 下载，收益主要是发现 full 内嵌 API 与官方 API 的差异；如果目标只是避免 CI/本地漂移，API-only 统一编译更简单。

**E 项文档现状（需 1号 同步）**：`AGENTS.md:110` 仍写 CI 下载 `DistantHorizonsApi`；`PORTING-PROGRESS.md:680`/`:700` 仍写 API 坐标/`DistantHorizonsApi`。当前 workflow 已是 full jar，**文档与代码不一致**，属于非阻断文档更新。`features.md` 未提具体 jar，暂不冲突。

**对 M9-1 的影响**：无。M9-1 只编 ModMenu，DH 只是 `build.gradle` classpath 里的额外 compileOnly；上面的分歧仅在 M9-3 落地时才会影响编译面。第 2 轮唯一阻断仍是 **B3（fail-fast 位置）**。

---

## 六、第 3 轮确认（2026-11-02）

范围：仅第 3 轮 `build.gradle` 的 B3 修复、CC 面、新增风险；DH 三种口径记录确认。

### 结论

**仍打回（唯一阻断：B4；B3 的机制本身正确，但当前 build.gradle 的 `fileTree` 写法不能按意图执行）。** 把检查移到 `compileClientJava.doFirst` 的方向正确，无 jar 时 `tasks`/`clean`/IDE sync 不再被 throw；但 `integrationJars` 用了 `fileTree(Map) { ... }`，Gradle 没有 `fileTree(Map, Closure)` 重载，实际会走 `fileTree(Object, Closure)` 并把 Map 当 baseDir 传给 `ConfigurableFileTree.from(...)`，不是“先按 Map 解析 dir/include/release，再执行 exclude”。因此配置阶段仍可能直接失败（或得到空/错误树），B3 的验证无法成立。

### 1. B3 是否闭环（机制对，但被 B4 卡住）

**设计上已闭环：**
- 检查已从 `dependencies {}` 移到 `tasks.matching { it.name == "compileClientJava" }.configureEach { doFirst { ... } }`，配置期没有 `throw`；无 jar 时 `./gradlew tasks`、`clean`、IDE sync 不应再失败（前提是 B4 修好，构建脚本能完成配置）。
- `compileClientJava` 的 `doFirst` 在 javac 主 action 之前执行；缺 `modmenu-*` 时 throw，仍能 fail-fast。
- 防呆 `tasks.matching + projectsEvaluated warn` 比 `tasks.named` 温和；任务名猜错时不会让整个配置期失败，但会退化成“编译期 package does not exist”；这是**非阻断**风险（见 3）。
- **但当前 `integrationJars` 的构造 (B4) 会让配置期无法正常完成/无法得到正确 jar 树**，所以 B3 目前不能验收；B4 修完后只需复跑无 jar 的 `tasks` + 有 jar 的 build。

### 2. CC 面（基本同意，需注意 logger 与回调）

- 动作里捕获 `java.io.File` 与 `java.util.regex.Pattern`；两者都 `Serializable`；`File.listFiles()`/`Pattern` 不需要 project；`doFirst` 在 task 执行期访问文件系统，**不触碰 Project**，方向正确。
- `logger` 在 `doFirst` 里是 `Task.logger`；Gradle CC 允许 task action 使用 logger（日志不是配置输入），**未见不可序列化点**。
- `gradle.projectsEvaluated` 是配置期回调，只读 `tasks.names` 并 warn；它不是 task action、不跨执行期，**CC 下应可接受**。唯一语义差异：CC 命中时配置期回调不会重跑，如果任务名后来变化，warn 不会再次出现；不影响构建，**非阻断**。
- `compileOnly integrationJars` 是任务输入，jar 增删会被 Gradle 跟踪（修 B4 后）；因此“jar 增删不靠 CC 快照”这个目标是成立的。

### 3. 新风险

**B4（阻断，当前代码问题）：`fileTree(Map) { exclude ... }` 没有对应的 Gradle API 重载。**

Gradle 9.8.2 `Project` 的可用签名是：

```text
fileTree(Object)
fileTree(Object, Closure)
fileTree(Object, Action<? super ConfigurableFileTree>)
fileTree(Map<String, ?>)
```

没有 `fileTree(Map, Closure)`。Groovy 对 `fileTree(dir: ..., include: [...]) { exclude ... }` 会选中 `fileTree(Object, Closure)`；`DefaultProject.fileTree(Object, Closure)` 先调用 `fileTree(Object)`，而 `DefaultFileOperations.fileTree(Object)` 走 `ConfigurableFileTree.from(Object)`，把 Map 当作 baseDir，而不是解析 `dir/include`。结果不是“Map 配置 + exclude 闭包”，而是配置阶段报错或得到错误/空树。Groovy 语法解析（GroovyClassLoader）不会发现这个问题，因为它只验证语法，不验证 Gradle DSL 重载。

**改法（二选一）**：

```groovy
// A. 单 Map，无闭包（推荐）
def integrationJars = fileTree(dir: "run/mods", include: [
    "modmenu-*.jar", "voicechat-api-*.jar", "voicechat-*.jar",
    "DistantHorizons-*.jar", "DistantHorizonsApi-*.jar",
], exclude: ["*-sources.jar", "*-javadoc.jar"])
```

```groovy
// B. baseDir 用 String，闭包只配置 include/exclude
def integrationJars = fileTree("run/mods") {
    include "modmenu-*.jar", "voicechat-api-*.jar", "voicechat-*.jar",
        "DistantHorizons-*.jar", "DistantHorizonsApi-*.jar"
    exclude "*-sources.jar", "*-javadoc.jar"
}
```

**其他非阻断风险**：
- `tasks.matching` 只匹配字面 `compileClientJava`；若 Loom/source set 任务名变化，`projectsEvaluated` 只 warn，构建会退化为 javac `package ... does not exist`，不是 fail-fast。建议 Lead 在用户终端确认一次 `./gradlew tasks --all | grep compileClientJava`。
- `doFirst` 与 Loom 自己的 `doFirst` 顺序：Gradle 的 `doFirst` 是后进先出，但都在 javac 主 action 前；只要检查动作不依赖 Loom 的 doFirst 副作用就安全。当前只读 run/mods，**不依赖**。
- `exclude` 意图正确；修 B4 后与 `.sh/.ps1` 的 sources/javadoc 排除一致。

### 4. DH 三种口径记录（接受）

- 接受把 DH 方案记为 **M9-3 待决项**：首选“CI/本地都用 API jar 编译，full jar 只做 dev runtime，独立 `compile-libs/`”；次选“full both + SHA/API 版本断言”；“两者都下载 + 断言类集合”仅作最后手段。**本轮 M9-1 不实施**，未改 DH 代码。
- 当前 workflow 用 full jar 是短期 parity 取舍；M9-3 再定。

### 第 3 轮最终建议

1. **先修 B4**（`fileTree` Map+Closure → 单 Map 或 `fileTree("run/mods"){...}`），这是唯一阻断。
2. 修后验证：无 jar 的 `./gradlew tasks` 成功；有 jar 的 `./gradlew build` 通过；无 jar 的 `build` 在 `compileClientJava` 的 doFirst 抛 URL 明确的 GradleException。
3. B3 的 CC 设计方向可保留；确认 `compileClientJava` 任务名存在；B4 修完即可合入。

---

## 七、第 4 轮确认（2026-11-02）

范围：只核 B4 修复及第 3 轮 check 块的 API 合法性；不重跑 B1/B2 全量。

### 结论

**可合入。** `fileTree(Map)` 写法合法，`include`/`exclude` 会同时生效；B3 的配置期/执行期拆分方向正确；`tasks.matching{...}.configureEach{...}`、`gradle.projectsEvaluated{...}`、`file(...)`、`~/(...)/` 均为合法 API。唯一建议是合入前用真实 Gradle 跑一次（mock 只证明了重载绑定，不证明 Gradle 的 PatternSet/CC 全链路）。

### 1. `fileTree(Map)` 语义（通过）

- Gradle 9.8.2 `Project` 确有 `fileTree(Map<String, ?>)` 重载（javap：`public abstract ConfigurableFileTree fileTree(java.util.Map)`），实现是 `FileOperations.fileTree(Map)` → `ConfigureUtil.configureByMap` 配置 `ConfigurableFileTree`；`dir` 是 baseDir，`include`/`exclude` 被映射到 `PatternFilterable`。
- `include` 模式相对 `dir` 解析；`exclude` 是同一 `PatternSet` 的排除集，构建结果 = include 命中后再减去 exclude；`*-sources.jar`/`*-javadoc.jar` 会被排除。
- `compileOnly integrationJars` 接收 `ConfigurableFileTree`；`fileTree(Map)` 不再经过 `fileTree(Object, Closure)`，B4 的 Map-as-baseDir 问题已消除。

### 2. mock 验证是否充分（部分充分）

- **对 B4 的根因判定充分**：mock 只有 `fileTree(Map)`/`fileTree(Object)`/`fileTree(Object, Closure)` 三个重载，OLD 命中 Object+Closure、NEW 命中 Map，正是真实 `Project` 的签名结构；这足以证明旧写法绑定错误、新写法走 Map。
- **对完整 Gradle 行为不完全充分**：mock 没有 Gradle 的 `ConfigureUtil.configureByMap`、`ConfigurableFileTree.from`/PatternSet、真实 `compileClientJava` 任务实例、配置缓存序列化。最好补一次真实 Gradle（离线即可，不需要网络）：
  - `./gradlew --offline tasks` —— 无 jar 时不应因检查失败；
  - `./gradlew --offline compileClientJava` / `build` —— 有 jar 时应通过；
  - 临时移走 `modmenu-*.jar` 后再跑 `build`，应在 `compileClientJava.doFirst` 抛 URL 明确的 GradleException（跑完恢复 jar）。
  如果这些实跑都过，mock 的“绑定”结论就升级为端到端证据。

### 3. 其它 API 合法性

| API | 结论 | 说明 |
|---|---|---|
| `tasks.matching { ... }.configureEach { ... }` | **合法** | `TaskContainer.matching(Spec)` 返回 live `TaskCollection`；`configureEach` 对之后创建/匹配的任务也生效（Gradle `DomainObjectCollection.matching` 的 live 语义）。 |
| `gradle.projectsEvaluated { ... }` | **合法** | `Gradle.projectsEvaluated(Action)` 是配置期回调；只读 `tasks.names` + warn，不进入执行期。CC 命中时回调不重跑，只影响“任务名猜错”的再次告警，不影响构建。 |
| `file("run/mods")` | **合法** | `Project.file(Object)` 返回 `File`；动作期只用 `File` 读目录，不触碰 project。 |
| `~/(modmenu|...)-.*\.jar/` + `it ==~ pattern` | **合法** | Groovy `Pattern` + `==~` 全串匹配；Pattern 实现 `Serializable`。 |
| `doFirst` + `logger.lifecycle` | **合法** | task action 内使用 task logger；不解析配置期 FileCollection，不访问 project。 |

**非阻断提醒**：`tasks.matching` 只匹配字面 `compileClientJava`；如果 Loom/source set 任务名变化，`projectsEvaluated` 只 warn，构建会退化为 javac 的 package 错误，而不是 fail-fast。合入前用真实 Gradle 确认任务名，成本很低。

### B3 / B4 状态

- **B3**：检查已移到 `compileClientJava.doFirst`；配置期不再 throw，`tasks`/`clean`/IDE sync 不受阻；`build` 在 javac 主 action 前 fail-fast。**通过（静态）**。
- **B4**：单 Map `fileTree` 修复；**通过**。
- **B1/B2**：前两轮已闭环；本轮未发现回归。

### DH 口径

- 接受把 DH 记为 **M9-3 待决项**：首选“API jar 统一编译 + full jar 只做 dev runtime（独立 `compile-libs/`）”；次选“full both + SHA/API 版本断言”；“两者都下载 + 断言类集合”仅作最后手段。**本轮 M9-1 不实施**，与现有 full jar workflow 不冲突。

### 合入前最后一步（建议）

1. `./gradlew --offline tasks`（无 jar 也成功）。
2. `./gradlew --offline build`（当前 jar 齐全，成功）。
3. 临时移走 `modmenu-*.jar` 再 `./gradlew --offline compileClientJava`，确认在 doFirst 抛含 URL 的 GradleException；恢复 jar。
4. 若想验证 CC：连续跑两次 `./gradlew --offline --configuration-cache build`，第二次应 cache hit 且仍能正常读取当前 run/mods（jar 变化会作为 compileOnly 输入触发任务重跑）。
