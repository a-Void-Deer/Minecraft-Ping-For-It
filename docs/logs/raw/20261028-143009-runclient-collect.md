# runClient 首启采集报告 20261028-143009

采集时间: 2026-10-28 14:30:09  |  采集者: 5号 log-monitor

## 1) Gradle JVM 版本行（必须是 25）

选用 daemon 日志: daemon-33824.out.log（最后构建启动于 2026-10-28 14:30:06）

```
2026-10-28T14:30:06.239+0800 [DEBUG] [org.gradle.launcher.daemon.server.exec.ExecuteBuild] Executing build with daemon context: DefaultDaemonContext[uid=eb274c98-9e96-4230-a458-50ed47fef4c6,javaHome=C:\Program Files\Java\jdk-25,javaVersion=25,javaVendor=Oracle Corporation,daemonRegistryDir=D:\Gradle\daemon,pid=33824,idleTimeout=10800000,priority=NORMAL,applyInstrumentationAgent=true,nativeServicesMode=ENABLED,daemonOpts=-Xmx1G,-Dfile.encoding=UTF-8,-Duser.country=CN,-Duser.language=zh,-Duser.variant]
```

## 2) 任务名（区分 build / genSources / runClient）

```
Calculating task graph as no cached configuration is available for tasks: build # 编译 + 打包 → build/libs/modid-1.0.0.jar
Calculating task graph as no cached configuration is available for tasks: build
Calculating task graph as no cached configuration is available for tasks: build # 编译 + 打包 → build/libs/modid-1.0.0.jar
```

```
2026-10-28T14:30:06.238+0800 [INFO] [org.gradle.launcher.daemon.server.exec.LogAndCheckHealth] Starting 18th build in daemon [uptime: 33 mins 45.6 secs, performance: 100%, GC rate: 0.00/s, heap usage: 0% of 1 GiB]
```

构建结果行:
```
BUILD SUCCESSFUL in 2s
BUILD SUCCESSFUL in 2s
```

提醒: BUILD SUCCESSFUL 不等于编译；以 build/libs 是否有 jar 为准。

## 3) Launching / Loom run 配置行

Gradle 侧:
```
Fabric Loom: 1.19.3
2026-10-28T14:28:28.051+0800 [DEBUG] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] Finishing executing command: Build{id=70ba00aa-e956-4b84-b329-701de68d3b14, currentDir=F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric}
2026-10-28T14:30:06.147+0800 [INFO] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] Received command: Build{id=eaa3c15c-69ae-456c-8317-ac1bc71dbfc8, currentDir=F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric}.
2026-10-28T14:30:06.147+0800 [DEBUG] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] Starting executing command: Build{id=eaa3c15c-69ae-456c-8317-ac1bc71dbfc8, currentDir=F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric} with connection: socket connection from /127.0.0.1:58131 to /127.0.0.1:54969.
2026-10-28T14:30:06.197+0800 [DEBUG] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] Finishing executing command: Build{id=eaa3c15c-69ae-456c-8317-ac1bc71dbfc8, currentDir=F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric}
2026-10-28T14:30:06.231+0800 [INFO] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] Received command: InvalidateVirtualFileSystemAfterChange[id=53ee37fd-6000-47de-8f16-ea3ecab38e4e].
2026-10-28T14:30:06.231+0800 [DEBUG] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] Starting executing command: InvalidateVirtualFileSystemAfterChange[id=53ee37fd-6000-47de-8f16-ea3ecab38e4e] with connection: socket connection from /127.0.0.1:58131 to /127.0.0.1:54971.
2026-10-28T14:30:06.231+0800 [DEBUG] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] Finishing executing command: InvalidateVirtualFileSystemAfterChange[id=53ee37fd-6000-47de-8f16-ea3ecab38e4e]
2026-10-28T14:30:06.234+0800 [INFO] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] Received command: Build{id=48a3e7fc-a8fd-491f-8c44-8ecefeadaace, currentDir=F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric}.
2026-10-28T14:30:06.234+0800 [DEBUG] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] Starting executing command: Build{id=48a3e7fc-a8fd-491f-8c44-8ecefeadaace, currentDir=F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric} with connection: socket connection from /127.0.0.1:58131 to /127.0.0.1:54972.
Fabric Loom: 1.19.3
2026-10-28T14:30:08.787+0800 [DEBUG] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] Finishing executing command: Build{id=48a3e7fc-a8fd-491f-8c44-8ecefeadaace, currentDir=F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric}
```
MISSING: run\logs\latest.log 尚未生成（runClient 未启动或启动即失败）

## 4) Mixin 相关 WARN/ERROR 原文（compatibilityLevel 提示属正常）

MISSING: run\logs\latest.log

## 5) 首启里程碑（LWJGL / Sound engine / 窗口）

MISSING: run\logs\latest.log

## 6) 崩溃：顶层异常类名 + 第一段 Caused by

MISSING: 无 crash-report，也没有 latest.log

---
（本报告只含原文与定位，不做归因；归因按 AGENTS.md §6-4 分诊顺序）
