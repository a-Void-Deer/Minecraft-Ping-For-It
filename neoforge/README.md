# neoforge/ · 预留占位（未参与构建）

本目录只有一个 README：**没有 `build.gradle`，也不在 `settings.gradle` 的 `include()` 里**
（那边是被注释掉的 `// include('neoforge')`）。因此 `./gradlew build`、`./gradlew buildAll`
和 IDE 同步都不会构建它，仓库里目前只有一个可构建的加载器模块：`fabric/`。

## 为什么留着

与 `forge/` 同理：对齐上游 fork 的四模块形状（`common/ + fabric/ + forge/ + neoforge/`），
本轮按决策 D1 只交付 Fabric 端，但先占住目录，未来补 NeoForge 端时不必回头重排源码。
规格见 [`docs/restructure/00-plan.md`](../docs/restructure/00-plan.md)。

## 未来启用需要什么

| 项 | 说明 |
| --- | --- |
| 构建插件 | **NeoForge ModDevGradle**，插件 id `net.neoforged.moddev`（上游 fork 用 `mod_dev_gradle_version=2.0.115`，根 `build.gradle` 以 `apply false` 引入） |
| Minecraft 产物 | ModDevGradle 走 NeoForm：需要该 MC 版本的 `neo_form_version`（上游 1.21.1 为 `1.21.1-20240808.144430`），并把 `neoForge { version = ... }` 指向对应 NeoForge 版本 |
| 版本核对 | NeoForge 版本必须与 **Minecraft 26.3** 对应。上游 1.21.1 用的是 `neoforge_version=21.0.143`；26.3 的对应版本需按 https://projects.neoforged.net/neoforged/neoforge 核对（迁移期间本机无网络，**未验证**） |
| Java | 25（ModDevGradle 下仍需与 `--release 25` 一致） |
| 模块接线 | 取消 `settings.gradle` 里 `include('neoforge')` 的注释、新增 `neoforge/build.gradle`（`plugins { id 'multiloader-loader'; id 'net.neoforged.moddev' }`），并在根 `build.gradle` 的 `plugins` 里加 `id 'net.neoforged.moddev' version "..." apply false` |
| 共享代码 | `implementation project(':common')` 已由 `multiloader-loader` 约定提供；服务端安全约束见 `AGENTS.md` §4 |

> 现在**没有任何 NeoForge 专用源码**。加载器入口、平台实现与 mixin 配置都需要按 NeoForge API 新写。
