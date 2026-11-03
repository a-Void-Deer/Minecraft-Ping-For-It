# runClient 首启采集报告 20261028-145120

采集时间: 2026-10-28 14:51:20  |  采集者: 5号 log-monitor

## 1) Gradle JVM 版本行（必须是 25）

选用 daemon 日志: daemon-33824.out.log（最后构建启动于 2026-10-28 14:46:05）

```
2026-10-28T14:46:05.028+0800 [DEBUG] [org.gradle.launcher.daemon.server.exec.ExecuteBuild] Executing build with daemon context: DefaultDaemonContext[uid=eb274c98-9e96-4230-a458-50ed47fef4c6,javaHome=C:\Program Files\Java\jdk-25,javaVersion=25,javaVendor=Oracle Corporation,daemonRegistryDir=D:\Gradle\daemon,pid=33824,idleTimeout=10800000,priority=NORMAL,applyInstrumentationAgent=true,nativeServicesMode=ENABLED,daemonOpts=-Xmx1G,-Dfile.encoding=UTF-8,-Duser.country=CN,-Duser.language=zh,-Duser.variant]
```

## 2) 任务名（区分 build / genSources / runClient）

```
Calculating task graph as no cached configuration is available for tasks: build # 产物：build/libs/modid-1.0.0.jar
Calculating task graph as no cached configuration is available for tasks: build # 编译 + 打包 → build/libs/modid-1.0.0.jar
Calculating task graph as no cached configuration is available for tasks: :runClient
```

```
2026-10-28T14:46:05.028+0800 [INFO] [org.gradle.launcher.daemon.server.exec.LogAndCheckHealth] Starting 28th build in daemon [uptime: 49 mins 44.39 secs, performance: 100%, GC rate: 0.00/s, heap usage: 0% of 1 GiB]
```

构建结果行:
```
BUILD SUCCESSFUL in 27s
BUILD SUCCESSFUL in 2s
```

提醒: BUILD SUCCESSFUL 不等于编译；以 build/libs 是否有 jar 为准。

## 3) Launching / Loom run 配置行

Gradle 侧:
```
Fabric Loom: 1.19.3
Fabric Loom: 1.19.3
Fabric Loom: 1.19.3
Fabric Loom: 1.19.3
Fabric Loom: 1.19.3
Fabric Loom: 1.19.3
Fabric Loom: 1.19.3
Fabric Loom: 1.19.3
Fabric Loom: 1.19.3
Fabric Loom: 1.19.3
Fabric Loom: 1.19.3
Fabric Loom: 1.19.3
```

## 4) Mixin 相关 WARN/ERROR 原文（compatibilityLevel 提示属正常）

命中行数: 0（最多列 80 行）

## 5) 首启里程碑（LWJGL / Sound engine / 窗口）

```
run\logs\latest.log:61: [14:34:08] [Render thread/INFO] (Minecraft) Setting user: Player13
run\logs\latest.log:64: [14:34:09] [Render thread/INFO] (Minecraft) Backend library: LWJGL version 3.4.3-snapshot
run\logs\latest.log:72: [14:34:09] [Render thread/INFO] (Minecraft) Created window using SDL video driver: windows
run\logs\latest.log:102: [14:34:15] [Render thread/INFO] (Minecraft) OpenAL initialized on device OpenAL Soft on 耳机 (Realtek(R) Audio)
run\logs\latest.log:103: [14:34:15] [Render thread/INFO] (Minecraft) Sound engine started
run\logs\latest.log:104: [14:34:15] [Render thread/INFO] (Minecraft) Created: 512x256x0 minecraft:textures/atlas/particles.png-atlas
run\logs\latest.log:105: [14:34:15] [Render thread/INFO] (Minecraft) Created: 256x128x0 minecraft:textures/atlas/celestials.png-atlas
run\logs\latest.log:106: [14:34:15] [Render thread/INFO] (Minecraft) Created: 128x128x0 minecraft:textures/atlas/decorated_pot.png-atlas
run\logs\latest.log:107: [14:34:15] [Render thread/INFO] (Minecraft) Created: 512x256x0 minecraft:textures/atlas/paintings.png-atlas
run\logs\latest.log:108: [14:34:15] [Render thread/INFO] (Minecraft) Created: 1024x512x0 minecraft:textures/atlas/shield_patterns.png-atlas
run\logs\latest.log:109: [14:34:15] [Render thread/INFO] (Minecraft) Created: 1024x512x0 minecraft:textures/atlas/banner_patterns.png-atlas
run\logs\latest.log:110: [14:34:15] [Render thread/INFO] (Minecraft) Created: 2048x2048x4 minecraft:textures/atlas/blocks.png-atlas
run\logs\latest.log:111: [14:34:15] [Render thread/INFO] (Minecraft) Created: 1024x512x0 minecraft:textures/atlas/items.png-atlas
run\logs\latest.log:112: [14:34:15] [Render thread/INFO] (Minecraft) Created: 1024x1024x0 minecraft:textures/atlas/gui.png-atlas
run\logs\latest.log:113: [14:34:15] [Render thread/INFO] (Minecraft) Created: 128x64x0 minecraft:textures/atlas/map_decorations.png-atlas
run\logs\latest.log:114: [14:34:15] [Render thread/INFO] (Minecraft) Created: 512x512x0 minecraft:textures/atlas/chest.png-atlas
run\logs\latest.log:115: [14:34:15] [Render thread/INFO] (Minecraft) Created: 512x512x0 minecraft:textures/atlas/shulker_boxes.png-atlas
```

## 6) 崩溃：顶层异常类名 + 第一段 Caused by

无 crash-report；改从 run\logs\latest.log 提取

```
run\logs\latest.log:93: [14:34:15] [Download-2/INFO] (Minecraft) Could not authorize you against Realms server: java.lang.RuntimeException: Failed to parse into SignedJWT: FabricMC
run\logs\latest.log:95: com.mojang.realmsclient.exception.RealmsServiceException: Realms authentication error with message 'java.lang.RuntimeException: Failed to parse into SignedJWT: FabricMC'
run\logs\latest.log:116: [14:34:27] [Download-2/INFO] (Minecraft) Could not authorize you against Realms server: java.lang.RuntimeException: Failed to parse into SignedJWT: FabricMC
run\logs\latest.log:118: com.mojang.realmsclient.exception.RealmsServiceException: Realms authentication error with message 'java.lang.RuntimeException: Failed to parse into SignedJWT: FabricMC'
```

第一段 Caused by:
```
run\logs\latest.log:82: Caused by: MinecraftClientHttpException[type=HTTP_ERROR, status=401, response=ErrorResponse[path=/player/attributes, error=null, errorMessage=null, details=null]]
```

## 7) 按级别标记统计（防 \[ERROR\] 假阴性）

级别格式是 [HH:mm:ss] [thread/LEVEL]，用 \[ERROR\] 匹配 [Download-2/ERROR] 永远是 0 —— 这里只用 /(ERROR|FATAL)\]。

### latest.log：ERROR/FATAL = 3，WARN = 1

~~~
run\logs\latest.log:59: [14:34:05] [main/WARN] (PerfmonDisabled) Invalid registry value type detected for PerfOS counters. Should be REG_DWORD. Ignoring: HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\PerfOS\Performance\Disable Performance Counters.
run\logs\latest.log:73: [14:34:13] [Download-2/ERROR] (Minecraft) Failed to fetch user properties
run\logs\latest.log:94: [14:34:15] [Download-2/ERROR] (Minecraft) Failed to fetch Realms feature flags
run\logs\latest.log:117: [14:34:27] [Download-2/ERROR] (Minecraft) Couldn't connect to realms
~~~

### debug.log：ERROR/FATAL = 3，WARN = 1

~~~
run\logs\debug.log:530: [14:34:05] [main/WARN] (oshi.driver.windows.perfmon.PerfmonDisabled) Invalid registry value type detected for PerfOS counters. Should be REG_DWORD. Ignoring: HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\PerfOS\Performance\Disable Performance Counters.
run\logs\debug.log:1532: [14:34:13] [Download-2/ERROR] (net.minecraft.client.Minecraft) Failed to fetch user properties
run\logs\debug.log:1778: [14:34:15] [Download-2/ERROR] (com.mojang.realmsclient.client.RealmsClient) Failed to fetch Realms feature flags
run\logs\debug.log:1843: [14:34:27] [Download-2/ERROR] (com.mojang.realmsclient.RealmsAvailability) Couldn't connect to realms
~~~

**Mixin 级失败命中数（应为 0）: 0**

---
（本报告只含原文与定位，不做归因；归因按 AGENTS.md §6-4 分诊顺序）
