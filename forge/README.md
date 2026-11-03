# forge/ · 预留占位（未参与构建）

本目录只有一个 README：**没有 `build.gradle`，也不在 `settings.gradle` 的 `include()` 里**
（那边是被注释掉的 `// include('forge')`）。因此 `./gradlew build`、`./gradlew buildAll`
和 IDE 同步都不会构建它，仓库里目前只有一个可构建的加载器模块：`fabric/`。

## 为什么留着

上游 fork 是 `common/ + fabric/ + forge/ + neoforge/` 的四模块形状。本轮迁移
（[`docs/restructure/00-plan.md`](../docs/restructure/00-plan.md)）按决策 D1 只交付 Fabric 端，
但保留同名目录：将来真要补 Forge 端时，不用再搬一次源码，也不用改 `common/` 的引用路径。

## 未来启用需要什么

| 项 | 说明 |
| --- | --- |
| 构建插件 | **ForgeGradle**，插件 id `net.minecraftforge.gradle`（上游 fork 声明的是 `[6.0.24,6.2)`） |
| Mixin | `org.spongepowered.mixin` 插件 + `mixin { config "${mod_id}.mixins.json" }`；Forge 端的 mixin 配置不走 Loom 的 refmap 生成 |
| Minecraft / Forge 版本 | 必须是**与 Minecraft 26.3 对应的 Forge 发行版**。上游 1.21.1 用的是 `forge_version=52.0.2`；26.3 的具体版本号需按 https://files.minecraftforge.net 核对（迁移期间本机无网络，**未验证**） |
| Java | 25（`gradle.properties` 与 `buildSrc/` 约定固定 `--release 25`） |
| 模块接线 | 取消 `settings.gradle` 里 `include('forge')` 的注释、新增 `forge/build.gradle`（`plugins { id 'multiloader-loader'; ... }`），并给 `gradle.properties` 补 Forge 版本属性 |
| 共享代码 | `implementation project(':common')` 已由 `buildSrc/src/main/groovy/multiloader-loader.gradle` 提供；服务端安全约束见 `AGENTS.md` §4 |

> 现在**没有任何 Forge 专用源码**。加载器入口与平台实现（`IPlatformContextService` /
> `IPlatformNetworkService` / `IPlatformServerEventService` 的 Forge 实现）需要按 Forge API 新写，
> `fabric/` 里的实现依赖 Fabric API，不能直接搬。
