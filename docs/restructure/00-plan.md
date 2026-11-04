# 00 · 目录重构方案（对齐上游 fork 的多模块形状）

> 目标：把当前**单模块**布局改造为上游 fork（`Ping-for-it`）的**多模块**形状，同时保持
> **Fabric-only 构建可用**（决策 D1）与**保真铁律**（不改任何上游可见效果）。
> 本文件是迁移的唯一规格；执行细节与最终证据见 `01-inventory-and-mapping.md`（分析）
> 与 `docs/reviews/53-restructure-review.md`（独立复审）。

## 1. 目标树

```
settings.gradle                     include('common', 'fabric')；forge/neoforge 注释预留
build.gradle                        根：plugins apply false + buildAll 聚合任务
gradle.properties                   原有属性全部保留（version/group/minecraft/loader/loom/api）
buildSrc/
  build.gradle                      groovy-gradle-plugin
  src/main/groovy/multiloader-common.gradle
  src/main/groovy/multiloader-loader.gradle
common/
  build.gradle                      java-library + fabric-loom（splitEnvironmentSourceSets）
  src/main/java/nx/pingwheel/common/**     ← src/main/java/nx/pingwheel/common/**（162 java）
  src/main/resources/**                    ← src/main/resources/**（assets/pingforit、icon.png）
fabric/
  build.gradle                      multiloader-loader + fabric-loom（split）
  src/main/java/nx/pingwheel/fabric/**     ← src/main/java/nx/pingwheel/fabric/**（4 java）
  src/main/resources/META-INF/services/
        nx.pingwheel.common.platform.IPlatformContextService
        nx.pingwheel.common.platform.IPlatformNetworkService
        nx.pingwheel.common.platform.IPlatformServerEventService
  src/main/templates/fabric.mod.json       ← src/main/templates/fabric.mod.json（保留模板机制）
  src/client/java/nx/pingwheel/**          ← src/client/java/nx/pingwheel/**（80 java，原样搬）
  src/client/resources/pingforit.mixins.json
  src/client/resources/META-INF/services/
        nx.pingwheel.common.platform.IClientPlatformContextService
        nx.pingwheel.common.platform.IPlatformClientEventService
forge/  README.md                   预留目录，不参与构建
neoforge/ README.md                 预留目录，不参与构建
```

保持不动的顶层目录：`deferred/`（95 java，仍不参与编译）、`tools/`、`docs/`、`Mod_Project/`、
`run/`（dev 运行目录 + 离线 compile jar）、`.github/`、`AGENTS.md`、`README.md`、`LICENSE`。

## 2. 为什么这样切

| 位置 | 内容 | 理由 |
| --- | --- | --- |
| `common/src/main` | 原 `src/main/java/nx/pingwheel/common` | 服务端安全的共享代码；上游同名模块 |
| `fabric/src/main` | 原 `src/main/java/nx/pingwheel/fabric` | 加载器入口/平台实现（服务端侧） |
| `fabric/src/client` | 原 `src/client/java` 全部 | 客户端逻辑 + mixin；沿用 `splitEnvironmentSourceSets` |
| `fabric/src/client/resources` | mixins json + 客户端 META-INF/services | 客户端专属资源 |
| `fabric/src/main/resources` | 服务端 META-INF/services | 服务端 ServiceLoader 条目 |

**两个模块都开 `splitEnvironmentSourceSets()`**：这样 `common` 的 `src/main` 编译类路径里
依然**没有客户端类**，误用 `net.minecraft.client.*` 会在编译期失败（与迁移前同一道闸门）。

## 3. 构建接线要点（Lead 已写好，迁移者不要重写）

1. 根 `build.gradle` 用 `apply false` 引入 loom 插件；两个子项目只写 `id 'net.fabricmc.fabric-loom'`。
2. `gradle.properties` 的属性被子项目继承，`version` 仍是 `0.4.2-pfi-beta1`。
3. 集成 jar：glob/排除规则集中在 `multiloader-common.gradle`，`ext.integrationJars` 供两个模块
   `compileOnly`；`attachIntegrationJarCheck` 把检查挂到**真正需要的编译任务**上：
   - `:common:compileJava` → `voicechat-`（VoiceChatIntegration 在共享源集）
   - `:fabric:compileClientJava` → `modmenu-`（ModMenuIntegration 在 client 源集）
4. `fabric.mod.json` 仍由 `generateModMetadata`（Sync）从 `fabric/src/main/templates` 生成。
5. 最终 jar：`fabric` 的 `jar` 任务 `from project(':common').sourceSets.main.output`，
   把 common 的 class + 资源**合并**进同一个 jar（不引入 JiJ，保持单 jar 可发布）。
6. dev 运行目录：`loom.runs.client/server` 显式 `runDir(rootProject.file('run'))`，
   `./gradlew :fabric:runClient` 仍落在仓库根 `run/`（mods/logs/saves 不搬家）。**推荐带 `:fabric:` 前缀**：根项目不是 loom 项目（根级同名任务在 2026-11-04 已改为**转发别名**，见 `AGENTS.md` §7.18）。
7. 产物名仍为 `Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar`，位置变为 `fabric/build/libs/`。

## 4. 迁移者的动作

1. `git mv`（保留历史）：
   - `src/main/java/nx/pingwheel/common` → `common/src/main/java/nx/pingwheel/common`
   - `src/main/java/nx/pingwheel/fabric` → `fabric/src/main/java/nx/pingwheel/fabric`
   - `src/main/resources/assets` → `common/src/main/resources/assets`
   - `src/main/resources/icon.png` → `common/src/main/resources/icon.png`
   - `src/main/resources/META-INF/services`（3 个服务端条目）→ `fabric/src/main/resources/META-INF/services`
   - `src/main/templates` → `fabric/src/main/templates`
   - `src/client/java` → `fabric/src/client/java`
   - `src/client/resources` → `fabric/src/client/resources`
   - 删除空掉的 `src/` 目录（git 不跟踪空目录，确认没有遗留文件）
2. 新建 `forge/README.md`、`neoforge/README.md`：说明预留原因、当前未纳入 `settings.gradle`、
   未来需要哪些工具链（ForgeGradle / ModDevGradle + 对应 MC 版本）。
3. 重写 `tools/compile-check.sh` 与 `tools/compile-check.ps1`：三遍编译
   （`common` → `fabric main` → `fabric client`，后者类路径包含前者的输出），
   并新增“服务端安全”grep 闸门：`common/src/main/java` 里不得出现 `net.minecraft.client.`
   或 `com.mojang.blaze3d`。
4. `.github/workflows/build.yml`：上传路径改 `fabric/build/libs/Ping-For-It-*.jar`；
   fetch 集成 jar 的步骤不变（仍写 `run/mods`）。
5. 文档同步：`AGENTS.md` 第 2/3/4/10 节的目录与命令、`README.md` 的构建说明；
   `docs/PORTING-PROGRESS.md` 追加一条重构记录（历史章节不改）。

## 5. 验收标准

- `bash tools/compile-check.sh` 三遍全 0 error，`[summary]` 行三个模块都在。
- 仓库里 grep 不到旧路径引用（`src/main/java`、`src/client/java` 作为构建路径出现的地方）。
- `git status` 干净可提交；`common/`、`fabric/` 里的文件数与迁移前一致（162+4、76+4、资源齐全）。
- 顶层不出现空目录残留；`deferred/` 仍未进编译。
- 用户实机：`./gradlew clean build` 产出同名 jar；`./gradlew :fabric:runClient` 起在根 `run/`（**推荐带前缀**；根级 `runClient` 自 2026-11-04 起是转发别名，也可用）。

## 6. 与上游 fork 的刻意差异（保真度登记）

| # | 差异 | 理由 |
| --- | --- | --- |
| R1 | `forge/`、`neoforge/` 只有 README，未 `include` | 决策 D1：本轮只交付 Fabric；预留目录避免以后大改 |
| R2 | 保留 `common/` 里的客户端代码？**否**——客户端代码放 `fabric/src/client` | 沿用 26.3 已验证的 split 源集与 mixin/refmap 配置，风险最低 |
| R3 | `fabric/src/main/templates/fabric.mod.json` + `generateModMetadata` | 移植期已验证的版本替换方式，避免 IDEA 资源过滤告警 |
| R4 | dev 运行目录是根 `run/`（fork 用 `runs/client`） | 现有 mods/logs/世界都在 `run/`，且 `run/mods` 是离线编译依赖 |
| R5 | 保留 `deferred/`、`tools/`、`Mod_Project/` | 移植工程自有资产，与发布产物无关 |
| R6 | `common` 也开 `splitEnvironmentSourceSets()`（fork 的 common 是单源集） | 用编译器强制“共享代码不碰客户端类”这条既有约束 |
