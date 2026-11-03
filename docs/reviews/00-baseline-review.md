# 基线审核报告（模板 vs MC 26.3 / Java 25）

审核人：3号·代码审核员　　时间：2026-10-28　　范围：只读通读 4 个 Java 文件 + 2 个 mixins.json + fabric.mod.json + 构建脚本
结论：**当前模板不能编译（阻断）**，原因是本机只有 JDK 24 而 build.gradle 要求 release 25。其余为改名/一致性/风险项。

## 已核实的有利事实（说明模板本身是对的）

| # | 事实 | 证据 |
|---|---|---|
| F1 | `net.minecraft.resources.Identifier` 在 26.3 中确实存在（1.21 的 `net.minecraft.util.Identifier` 已迁包） | `unzip -l .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-1b0498e74c/26.3/minecraft-common-1b0498e74c-26.3.jar\| grep Identifier` → `net/minecraft/resources/Identifier.class` |
| F2 | 26.3 确实把命名空间类改成 `Identifier`（`IdentifierArgument`/`IdentifierPattern`/`IdentifierException` 同批存在） | 同上 jar 清单 |
| F3 | Loom 已成功解析/重映射 MC 26.3（76 MB 产物） | `.gradle/loom-cache/minecraftMaven/.../*.jar` |
| F4 | 配置阶段可跑通，仅 1 条 deprecation 警告 | `build/reports/problems/problems-report.html:656` → `org.gradle.parallel` 隐式 `org.gradle.tooling.parallel`；Gradle 10 会变错误 |
| F5 | 模板 CI 目标 JDK 25（微软发行版） | `.github/workflows/build.yml:20-21` `java-version: '25'` |
| F6 | modid / loom mods / fabric.mod.json id 三者一致（均为 `modid`） | settings.gradle:13、build.gradle:18、fabric.mod.json:2 |
| F7 | icon 路径与实体文件一致 | fabric.mod.json:15 `assets/modid/icon.png` ↔ src/main/resources/assets/modid/icon.png |
| F8 | client mixin 配置放在 client 源集，与 `splitEnvironmentSourceSets()` 吻合 | src/client/resources/modid.client.mixins.json + build.gradle:15 |

## 阻断（必须解决才能编译）

### B1 build.gradle 要求 Java 25，本机只有 JDK 24
- 位置：build.gradle:44 `it.options.release = 25`；build.gradle:53-54 `JavaVersion.VERSION_25`；fabric.mod.json:35 `"java": ">=25"`
- 证据：`java -version` → `24.0.2`；`JAVA_HOME=C:\Program Files\Java\jdk-24`；`~/.gradle` 下只有 wrapper（无依赖缓存），`build/` 下无 classes
- 后果：compileJava 直接报 `error: release version 25 not supported`，一个 class 都不会产出（至今未编译过）
- 方案 A（推荐，与模板/CI/MC 26.3 一致）：安装 Microsoft OpenJDK 25 并让 Gradle 用它（`org.gradle.java.home` 或 `-Dorg.gradle.java.home=...`）
- 方案 B（不改环境）：release/source/target 全降到 24，同时改 fabric.mod.json:35 为 `">=24"` 与两个 mixins.json 的 compatibilityLevel 为 `JAVA_24`；缺点：偏离模板意图，且 MC 26.3 运行仍需 25 时依然跑不起来
- 需 Lead 决策（A 需下载安装，属环境变更）

## 严重（不阻断编译，但交付前是坑）

### S1 包名与 group 不一致，且 group 含大写
- `group=com.Isharmla`（gradle.properties:14）vs 代码包 `com.example`（4 个 Java 文件全部）
- Java 包名规范小写；Maven groupId 惯例小写 → 建议 `com.isharmla`，代码根包同步
- 现值能编译，但发布坐标与代码包对不上，改名越晚越贵

### S2 modid 仍是占位符 `modid`（改名要动 8 处，漏一处即启动崩溃/资源 404）
1. settings.gradle:13 `rootProject.name`
2. build.gradle:18 `mods { "modid" }`
3. fabric.mod.json:2 `"id"`
4. fabric.mod.json:15 icon 路径 `assets/modid/icon.png`
5. fabric.mod.json:26 `modid.mixins.json`
6. fabric.mod.json:28 `modid.client.mixins.json`
7. 两个 mixin 配置文件名（src/main/resources/、src/client/resources/）
8. src/main/resources/assets/modid/ 目录名 + ExampleMod.java:11 `MOD_ID`
> Fabric 规定 modid 仅小写 a-z0-9_-，建议 `isharmla_xxx` 形式，别用大写

### S3 Mixin 注入目标未经本版本验证（运行期才炸，且 required:true 会硬崩）
- src/main/java/com/example/mixin/ExampleMixin.java:11 `method = "loadLevel"`（MinecraftServer）
- src/client/java/com/example/client/mixin/ExampleClientMixin.java:11 `method = "run"`（Minecraft）
- 验证命令：`javap -p -cp ".gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-1b0498e74c/26.3/minecraft-common-1b0498e74c-26.3.jar" net.minecraft.server.MinecraftServer | grep loadLevel`
- 两个 mixins.json 都是 `required:true` + `defaultRequire:1`：目标方法不存在 = 启动即 MixinApplyError

### S4 无版本控制
- 无 `.git`（已确认）；`.gitignore` 已就绪（:1-4 覆盖 .gradle/build/out/classes，:33 覆盖 run/）
- 建议 Lead 批准后 `git init` + 首次提交，作为所有人改动的回滚兜底

## 建议（可延后）

- N1 gradle.properties:2 `-Xmx1G`：remap/反编译 MC 26.3 偏紧，建议 2G
- N2 gradle.properties:3 `org.gradle.parallel=true` 触发了问题报告里的 deprecation（Gradle 10 会报错），单模块收益≈0，可删或显式设 `org.gradle.tooling.parallel=false`
- N3 gradle.properties:4 `configuration-cache=true`：目前能过；Loom SNAPSHOT + CC 偶有不兼容，出现诡异构建错误先加 `--no-configuration-cache` 复测
- N4 两个 mixins.json `compatibilityLevel: "JAVA_25"` 必须与最终 release 一致（走 B1 方案 B 时一并改）
- N5 README.md 仍是模板英文原文；无 AGENTS.md、无 docs/（本次新建 docs/reviews/）
- N6 两个 Mixin 的注入体是空实现 —— 功能开发时替换或删除，别留死代码占 defaultRequire

## 我每轮的审核清单（复审用）

1. **构建**：`./gradlew compileJava` / `build` 通过；`build/libs/*.jar` 里 fabric.mod.json 的版本已展开（不能残留占位符）；class 文件 major version 与 fabric.mod.json `"java"` 一致
2. **命名一致性**：S2 的 8 处 + 包名/group（S1）+ 资源路径全小写
3. **fabric.mod.json**：entrypoints 类真实存在且实现对应接口；icon/mixins 文件存在；depends 版本区间合理；environment 与源集匹配
4. **mixins**：package 与实际包一致；common mixin 不得引用 client 类；client mixin 必须在 client 源集 + client config；注入目标/描述符存在（javap 复验）；不靠 `required:true` 掩盖问题
5. **API 用法**：Registry 注册在 onInitialize 内且只注册一次；Identifier 用 `fromNamespaceAndPath`；RegistryKey/事件回调里不做耗时或访问未初始化资源
6. **崩溃点**：NPE（getPlayer/getServer/getLevel 为 null 分支）、注册时机（客户端注册放 onInitializeClient）、越界与空 tag
7. **隔离**：main 源集不得 import `net.minecraft.client.*`；网络包处理器线程 → `server.execute(...)`
8. **资源完整性**：blockstates/models/lang JSON 语法合法、lang key 与注册名匹配、en_us 与 zh_cn 同步

## 复现命令（本机 bash 执行器当前不可用，恢复后按序跑）

```bash
java -version                                  # 需确认是 25
./gradlew --version                            # 确认 Gradle 用的 JVM
./gradlew compileJava --stacktrace             # B1 是否仍阻断
./gradlew build && ls -l build/libs
unzip -p build/libs/*.jar fabric.mod.json | grep version   # 版本占位符是否展开
```

## 待 Lead 决策
1. B1 走方案 A（装 JDK 25）还是方案 B（降 release 到 24）？
2. S4 是否允许 `git init`？
3. modid / group / 根包最终取值（S1+S2 一次性改，减少返工）
