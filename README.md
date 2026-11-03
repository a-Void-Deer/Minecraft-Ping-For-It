# Ping For It · Minecraft 26.3 / Fabric 移植

把上游 [Ping For It](https://github.com/a-Void-Deer/Minecraft-Ping-For-It) `0.4.2-pfi-beta1`（原为 1.21.1 多加载器）移植到 **Minecraft 26.3 + Fabric 单端**。
指哪 ping 哪：准星指向方块/实体按住 ping 键，即可把位置与目标类型播报给同频道/同队玩家；短按发默认类型，长按开轮盘。

| 组件 | 版本 |
| --- | --- |
| Minecraft | **26.3** |
| Java | **25**（release 25，class major 69） |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| Loom | 1.19.3（no-remap 插件） |
| Gradle | 9.8.2（wrapper） |
| 模组版本 | `0.4.2-pfi-beta1`（必须 `x.x.x-pfi-qualifier`） |

## 当前进度

- **已完成**：M0 基线 → M1 构建骨架 → M2 源码导入与源集拆分 → M3 编译期 API 迁移 → M4 MVP 闭环（按键→服务端→HUD）→ M6 轮盘 → **M6.5 标记 HUD 与上游对齐**（菱形图标 / 物品图标 / 距离与目标名 / 头颅 / 屏外箭头）。
- **未开始**：M5 服务端权威完善、M7 渲染重建（世界描边，最大风险）、M8 配置/命令/UI、M9 可选集成、M10 验证与发布。
- 已实测：`gradlew clean build` 通过（重构前产物在根 `build/libs/`，649 KB / 345 class）；`runClient` 可进世界，按键产生成对 `send ping` / `marker created`。
- **2026-11-03 目录重构**：单模块 → `common/`（双端共用）+ `fabric/`（加载器与客户端）多模块，`forge/`、`neoforge/` 仅 README 预留、不参与构建；产物位置随之变为 `fabric/build/libs/`（见下）。

> **事故说明**：2026-10-29 工程目录曾被外部操作清空、`.git` 丢失；已从会话日志 + 上游源码重建，恢复基线 tag `restore-2026-10-29`。原阶段 tag 丢失，语义记录在 [docs/PORTING-PROGRESS.md](docs/PORTING-PROGRESS.md)。

## 构建

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-25"
export GRADLE_USER_HOME="D:/Gradle"

./gradlew -g D:/Gradle build
./gradlew -g D:/Gradle :fabric:runClient
```

Windows cmd：

```
set JAVA_HOME=C:\Program Files\Java\jdk-25
gradlew.bat -g D:\Gradle build
```

- 工程是 `common/` + `fabric/` 两个 Gradle 子项目（`settings.gradle` 只 include 这两个；`forge/`、`neoforge/` 是未启用的预留目录）。
- 产物在 `fabric/build/libs/`：`Ping-For-It-<version>-fabric-26.3.jar`（`fabric` 的 `jar` 任务把 `common` 的 class 与资源合并进同一个 jar，仍是单 jar）。
- **命令块内不要带行内注释**：Windows cmd 下 `#` 不是注释符，会被当成任务名。
- **运行任务必须带项目前缀**：`./gradlew -g D:/Gradle :fabric:runClient` / `:fabric:runServer`。重构后根项目只 `apply false` 引 loom、**不是 loom 项目**，根级 `./gradlew runClient` / `runServer` 会直接报 `Cannot locate tasks that match ':runServer' as task 'runServer' not found in root project 'Ping-For-It'.`（2026-11-03 用户实测）。首次 `:fabric:runServer` 会生成 `run/eula.txt`，改成 `eula=true` 再跑一次。
- 本地快速编译校验（不启动 Gradle）：`tools/compile-check.sh` / `tools/compile-check.ps1`。

## 文档

| 文档 | 内容 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | 协作事实来源：现状、构建、已知坑、待办 |
| [docs/porting-plan.md](docs/porting-plan.md) | 施工方案与阶段估算 |
| [docs/PORTING-PROGRESS.md](docs/PORTING-PROGRESS.md) | 进度、事故与决策记录 |
| [docs/features.md](docs/features.md) | 功能规格（含「标记尺寸/箭头/轮盘扇区数」等易误报项） |
| [docs/reviews/](docs/reviews/) | 各阶段独立评审与渲染保真审计 |

## 许可

移植目标沿用上游 **MIT** 许可：仓库根 `LICENSE` 已是 MIT 文本，`jar` 内的 `LICENSE_pingforit` 就是它。**M10 还需补 `THIRD-PARTY-NOTICES.md`**（上游同款，列第三方声明）。
